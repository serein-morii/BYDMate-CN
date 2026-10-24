package com.bydmate.app.data.push

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * The OkHttp plumbing shared by the four text-only clients: one JSON POST, every failure mapped
 * onto a [PushException]. Calls are cancellable, like TelegramBackupSink's; webhook urls and
 * secrets never reach the log (only the masked url does).
 */
internal class PushHttpPost(okHttpClient: OkHttpClient, private val tag: String) {

    // Text posts are small; the shared client's timeouts only get a wider read for slow modems.
    private val client = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POSTs [body] as JSON to [url] and answers the parsed response object. */
    suspend fun post(url: String, body: JSONObject): Result<JSONObject> {
        val target = url.trim().toHttpUrlOrNull()
            ?: return Result.failure(PushException(PushError.BAD_URL))
        val request = Request.Builder().url(target).post(body.toString().toRequestBody(JSON_MEDIA)).build()
        val (code, text) = try {
            await(client.newCall(request))
        } catch (e: IOException) {
            // No exception text: it may quote the answer body.
            Log.w(tag, "push rc=io ${e.javaClass.simpleName} (${maskedUrl(url)})")
            return Result.failure(PushException(PushError.NO_NETWORK, cause = e))
        }
        if (code !in 200..299) {
            Log.w(tag, "push rc=$code (${maskedUrl(url)})")
            return Result.failure(PushException(PushError.HTTP, code))
        }
        return try {
            Log.i(tag, "push rc=ok (${maskedUrl(url)})")
            Result.success(JSONObject(text))
        } catch (e: JSONException) {
            Log.w(tag, "push rc=$code ${PushError.BAD_RESPONSE} (${maskedUrl(url)})")
            Result.failure(PushException(PushError.BAD_RESPONSE, cause = e))
        }
    }

    /** Runs [call] on OkHttp's thread pool and cancels it with the coroutine. */
    private suspend fun await(call: Call): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body?.string().orEmpty() }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }
        })
    }
}

/** The url with its credential query values masked; webhook urls carry the secret in the query. */
internal fun maskedUrl(url: String): String =
    url.replace(Regex("([?&](?:key|access_token|sign)=)[^&]*"), "$1***")

/** Base64(HmacSHA256([key], [data])) — the signing scheme of the Feishu and DingTalk robots. */
internal fun hmacSha256Base64(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
}

/** [text] cut to at most [maxBytes] utf-8 bytes on a char boundary (WeCom's content limit). */
internal fun truncateUtf8(text: String, maxBytes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var cut = maxBytes
    // Step back over the tail of a multi-byte sequence so no half character is sent.
    while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
    return String(bytes, 0, cut, Charsets.UTF_8)
}

/** Bark (iOS push app): POST `{server}/push`, the answer's `code` 200 means delivered. */
internal class BarkClient(private val post: PushHttpPost) {
    companion object {
        const val DEFAULT_SERVER = "https://api.day.app"
        private const val GROUP = "bydmate"
    }

    /** [title] is the report's first line, [body] the rest; Bark shows both in one notification. */
    suspend fun send(server: String, deviceKey: String, title: String, body: String): Result<Unit> {
        val base = server.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
        val json = JSONObject()
            .put("device_key", deviceKey)
            .put("title", title)
            .put("body", body)
            .put("group", GROUP)
        return post.post("$base/push", json).map { response ->
            if (response.optInt("code", -1) != 200) throw PushException(PushError.BAD_RESPONSE)
        }
    }
}

/** WeCom (企业微信) group robot: one fixed webhook url, plain text, 2048 bytes of content max. */
internal class WecomClient(private val post: PushHttpPost) {
    suspend fun send(url: String, text: String): Result<Unit> {
        val json = JSONObject()
            .put("msgtype", "text")
            .put("text", JSONObject().put("content", truncateUtf8(text, MAX_BYTES)))
        return post.post(url.trim(), json).map { response ->
            if (response.optInt("errcode", -1) != 0) throw PushException(PushError.BAD_RESPONSE)
        }
    }

    private companion object {
        const val MAX_BYTES = 2048
    }
}

/** Feishu custom robot: `{"msg_type":"text",…}`; an optional secret signs with `timestamp`. */
internal class FeishuClient(private val post: PushHttpPost) {
    suspend fun send(url: String, secret: String, text: String): Result<Unit> {
        val json = JSONObject()
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", text))
        if (secret.isNotBlank()) {
            // sign = Base64(HmacSHA256(key = "$ts\n$secret", data = "")), both fields go in the body.
            val ts = System.currentTimeMillis() / 1000
            json.put("timestamp", ts).put("sign", hmacSha256Base64("$ts\n$secret", ""))
        }
        return post.post(url.trim(), json).map { response ->
            if (response.optInt("code", -1) != 0) throw PushException(PushError.BAD_RESPONSE)
        }
    }
}

/** DingTalk custom robot: plain text, 5000 chars max; an optional secret signs the url itself. */
internal class DingClient(private val post: PushHttpPost) {
    suspend fun send(url: String, secret: String, text: String): Result<Unit> {
        var target = url.trim()
        if (secret.isNotBlank()) {
            // sign = urlencode(Base64(HmacSHA256(key = secret, data = "$ms\n$secret"))) on the url.
            val ts = System.currentTimeMillis()
            val sign = URLEncoder.encode(hmacSha256Base64(secret, "$ts\n$secret"), "UTF-8")
            target += (if ('?' in target) "&" else "?") + "timestamp=$ts&sign=$sign"
        }
        val json = JSONObject()
            .put("msgtype", "text")
            .put("text", JSONObject().put("content", text.take(MAX_CHARS)))
        return post.post(target, json).map { response ->
            if (response.optInt("errcode", -1) != 0) throw PushException(PushError.BAD_RESPONSE)
        }
    }

    private companion object {
        const val MAX_CHARS = 5000
    }
}
