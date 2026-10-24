package com.bydmate.app.data.remote

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
 * Adapter for the Claude (Anthropic) native Messages API. The rest of the app speaks
 * OpenAI chat-completions (see [OpenRouterClient.chatRaw]); this client takes the same
 * wire inputs and returns the same choices[0].message shape, so LlmAgentBackend and
 * SettingsViewModel need no per-provider branches beyond picking the client.
 *
 * Deliberately non-streaming (the voice path falls back to speaking the whole answer
 * when no delta arrived) — the SSE dialect here differs enough that streaming Claude
 * is not worth the second reader.
 */
@Singleton
class AnthropicClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        private const val API_VERSION = "2023-06-01"
        /** Anthropic requires an explicit token cap; matches OpenRouterClient's 1024. */
        private const val MAX_TOKENS = 1024
        private val JSON_MEDIA = "application/json".toMediaType()

        /** {base}/v1/messages with or without the /v1 suffix already typed by the user. */
        internal fun messagesUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }

        /** OpenAI tool schema ({type:function, function:{name, description, parameters}}) to
         *  Anthropic's flat {name, description, input_schema}. */
        internal fun toAnthropicTools(tools: JSONArray?): JSONArray? {
            if (tools == null || tools.length() == 0) return null
            val out = JSONArray()
            for (i in 0 until tools.length()) {
                val fn = tools.getJSONObject(i).optJSONObject("function") ?: continue
                val schema = fn.optJSONObject("parameters")
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                out.put(JSONObject()
                    .put("name", fn.getString("name"))
                    .put("description", fn.optString("description"))
                    .put("input_schema", schema))
            }
            return if (out.length() == 0) null else out
        }

        /** Text out of an OpenAI-wire content value: a string, a cache_control parts array
         *  (only OpenRouter sends that; the parts' text is what matters here), or null. */
        private fun textContent(content: Any?): String? = when {
            content == null || content == JSONObject.NULL -> null
            content is String -> content.takeIf { it.isNotEmpty() }
            content is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val text = part.optString("text")
                    if (text.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(text)
                    }
                }
                sb.toString().takeIf { it.isNotEmpty() }
            }
            else -> null
        }

        /** OpenAI-wire message history to the Anthropic request body: system messages fold
         *  into the top-level system field, tool results become tool_result blocks inside a
         *  user turn (Anthropic has no role:tool), and consecutive same-role turns merge —
         *  the API requires roles to alternate. */
        internal fun toAnthropicRequest(
            modelId: String,
            messages: JSONArray,
            tools: JSONArray?,
        ): JSONObject {
            val system = StringBuilder()
            data class Turn(val role: String, val blocks: org.json.JSONArray)

            val turns = mutableListOf<Turn>()
            fun appendBlock(role: String, block: JSONObject) {
                val last = turns.lastOrNull()
                if (last != null && last.role == role) last.blocks.put(block)
                else turns.add(Turn(role, JSONArray().put(block)))
            }

            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                when (m.optString("role")) {
                    "system" -> {
                        val text = textContent(m.opt("content"))
                        if (text != null) {
                            if (system.isNotEmpty()) system.append("\n\n")
                            system.append(text)
                        }
                    }
                    "user" -> {
                        val text = textContent(m.opt("content"))
                        if (text != null) appendBlock("user", JSONObject().put("type", "text").put("text", text))
                    }
                    "assistant" -> {
                        val tc = textContent(m.opt("content"))
                        if (tc != null) appendBlock("assistant", JSONObject().put("type", "text").put("text", tc))
                        m.optJSONArray("tool_calls")?.let { calls ->
                            for (j in 0 until calls.length()) {
                                val call = calls.getJSONObject(j)
                                val fn = call.getJSONObject("function")
                                val args = fn.optString("arguments")
                                val input = if (args.isBlank()) JSONObject() else JSONObject(args)
                                appendBlock("assistant", JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", call.optString("id", "call_$j"))
                                    .put("name", fn.getString("name"))
                                    .put("input", input))
                            }
                        }
                    }
                    "tool" -> appendBlock("user", JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.optString("tool_call_id"))
                        .put("content", m.optString("content")))
                }
            }

            val body = JSONObject().apply {
                put("model", modelId)
                if (system.isNotEmpty()) put("system", system.toString())
                put("messages", JSONArray().apply {
                    turns.forEach { t -> put(JSONObject().put("role", t.role).put("content", t.blocks)) }
                })
                put("max_tokens", MAX_TOKENS)
            }
            toAnthropicTools(tools)?.let { body.put("tools", it) }
            return body
        }

        /** Anthropic stop_reason to the OpenAI finish_reason the agent parses. */
        internal fun mapFinishReason(stopReason: String?): String? = when (stopReason) {
            "tool_use" -> "tool_calls"
            "max_tokens" -> "length"
            null, "" -> null
            else -> "stop" // end_turn, stop_sequence, refusal...
        }

        /** Anthropic response body to the choices[0].message shape OpenRouterClient returns:
         *  text blocks join into content, tool_use blocks become OpenAI tool_calls. */
        internal fun toMessage(body: JSONObject): JSONObject {
            val content = StringBuilder()
            val toolCalls = JSONArray()
            val blocks = body.optJSONArray("content") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> {
                        val text = block.optString("text")
                        if (text.isNotEmpty()) {
                            if (content.isNotEmpty()) content.append('\n')
                            content.append(text)
                        }
                    }
                    "tool_use" -> toolCalls.put(JSONObject()
                        .put("id", block.optString("id", "call_$i"))
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", block.getString("name"))
                            .put("arguments", block.optJSONObject("input")?.toString() ?: "{}")))
                }
            }
            val message = JSONObject()
            message.put("content", if (content.isEmpty()) JSONObject.NULL else content.toString())
            if (toolCalls.length() > 0) message.put("tool_calls", toolCalls)
            mapFinishReason(body.optString("stop_reason"))?.let { message.put(OpenRouterClient.FINISH_REASON, it) }
            return message
        }
    }

    /** Test seam: overrides the base URL. Production leaves it null. */
    internal var baseUrlForTest: String? = null

    /** Same contract as [OpenRouterClient.chatRaw]: full OpenAI-wire history plus optional
     *  tool schemas in, choices[0].message-shaped JSON out. [extras] is accepted for
     *  signature parity and ignored — the Messages API takes no per-connection extras. */
    suspend fun chatRaw(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        messages: JSONArray,
        tools: JSONArray?,
        extras: JSONObject? = null,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = toAnthropicRequest(modelId, messages, tools)
            val request = Request.Builder()
                .url(messagesUrl(baseUrlForTest ?: baseUrl.ifBlank { DEFAULT_BASE_URL }))
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw LlmHttpException(resp.code)
                val bodyStr = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("LLM: empty body")
                toMessage(JSONObject(bodyStr))
            }
        }
    }
}
