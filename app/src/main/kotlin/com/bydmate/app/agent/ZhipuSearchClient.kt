package com.bydmate.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zhipu (open.bigmodel.cn) Web Search API (POST {base}/web_search, engine search_std) used
 * by the web_search tool. The answer rides in choices[0].message.content as a JSON string
 * with a search_result array (title/link/content); the mapping mirrors ExaSearchClient's
 * unified output shape so the LLM sees one format regardless of the engine.
 */
@Singleton
class ZhipuSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** Test seam: overrides the Zhipu base URL. Production leaves it null. */
    internal var baseUrlForTest: String? = null

    suspend fun search(apiKey: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("search_engine", "search_std")
                .put("search_query", query)
                .put("count", MAX_RESULTS)
            val base = baseUrlForTest ?: BASE_URL
            val request = Request.Builder()
                .url("$base/web_search")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("zhipu HTTP ${resp.code}")
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("zhipu: empty body")
                val results = extractResults(JSONObject(body))
                val out = JSONArray()
                for (i in 0 until minOf(results.length(), MAX_RESULTS)) {
                    val r = results.getJSONObject(i)
                    out.put(JSONObject().apply {
                        put("title", r.optString("title"))
                        put("url", r.optString("link"))
                        put("text", r.optString("content").take(TEXT_LIMIT))
                    })
                }
                JSONObject().put("results", out).toString()
            }
        }
    }

    companion object {
        const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        private const val MAX_RESULTS = 5
        private const val TEXT_LIMIT = 1500
        private val JSON_MEDIA = "application/json".toMediaType()

        /** The result list is usually wrapped in choices[0].message.content as a JSON string;
         *  a bare {search_result:[...]} body is accepted too. Neither shape -> empty array. */
        internal fun extractResults(body: JSONObject): JSONArray {
            val direct = body.optJSONArray("search_result")
            if (direct != null) return direct
            val content = body.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val inner = runCatching { JSONObject(content) }.getOrNull() ?: return JSONArray()
            return inner.optJSONArray("search_result") ?: JSONArray()
        }
    }
}
