package com.bydmate.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = AnthropicClient(OkHttpClient())
        client.baseUrlForTest = server.url("/base").toString().trimEnd('/')
    }

    @After fun tearDown() { server.shutdown() }

    // --- request conversion ---

    // (a) System messages fold into the top-level system field; user content becomes a text block.
    @Test
    fun request_folds_system_into_top_level_field() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "static prompt"))
            .put(JSONObject().put("role", "system").put("content", "dynamic prompt"))
            .put(JSONObject().put("role", "user").put("content", "вопрос"))
        val body = AnthropicClient.toAnthropicRequest("claude-x", messages, null)
        assertEquals("static prompt\n\ndynamic prompt", body.getString("system"))
        assertEquals(1024, body.getInt("max_tokens"))
        val turns = body.getJSONArray("messages")
        assertEquals(1, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals("вопрос", turns.getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text"))
    }

    // (b) tool_calls become tool_use blocks with a parsed input object; consecutive role:tool
    // results merge into ONE user turn of tool_result blocks (roles must alternate).
    @Test
    fun request_maps_tool_calls_and_merges_tool_results() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "q"))
            .put(JSONObject().apply {
                put("role", "assistant")
                put("content", JSONObject.NULL)
                put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call1").put("type", "function")
                    .put("function", JSONObject().put("name", "get_weather")
                        .put("arguments", """{"city":"Минск"}"""))))
            })
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call1").put("content", "r1"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call2").put("content", "r2"))
        val body = AnthropicClient.toAnthropicRequest("claude-x", messages, null)
        val turns = body.getJSONArray("messages")
        assertEquals(3, turns.length()) // user / assistant(tool_use) / user(2 tool_results)
        val assistantBlocks = turns.getJSONObject(1).getJSONArray("content")
        assertEquals("tool_use", assistantBlocks.getJSONObject(0).getString("type"))
        assertEquals("get_weather", assistantBlocks.getJSONObject(0).getString("name"))
        assertEquals("Минск", assistantBlocks.getJSONObject(0).getJSONObject("input").getString("city"))
        val userBlocks = turns.getJSONObject(2).getJSONArray("content")
        assertEquals(2, userBlocks.length())
        assertEquals("tool_result", userBlocks.getJSONObject(0).getString("type"))
        assertEquals("call1", userBlocks.getJSONObject(0).getString("tool_use_id"))
        assertEquals("r2", userBlocks.getJSONObject(1).getString("content"))
    }

    // (c) OpenAI tool schemas flatten to {name, description, input_schema}.
    @Test
    fun request_converts_tool_schemas() {
        val tools = JSONArray().put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "get_weather")
                .put("description", "d")
                .put("parameters", JSONObject().put("type", "object"))))
        val body = AnthropicClient.toAnthropicRequest("claude-x", JSONArray(), tools)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("get_weather", tool.getString("name"))
        assertEquals("d", tool.getString("description"))
        assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
    }

    // --- response conversion ---

    // (d) text + tool_use blocks -> choices[0].message shape with mapped finish_reason.
    @Test
    fun response_maps_to_message_shape() {
        val body = JSONObject("""{"content":[
            {"type":"text","text":"смотрю"},
            {"type":"tool_use","id":"tu1","name":"get_weather","input":{"city":"Минск"}}
        ],"stop_reason":"tool_use"}""")
        val message = AnthropicClient.toMessage(body)
        assertEquals("смотрю", message.getString("content"))
        val calls = message.getJSONArray("tool_calls")
        assertEquals("tu1", calls.getJSONObject(0).getString("id"))
        assertEquals("get_weather", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("""{"city":"Минск"}""", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("tool_calls", message.getString(OpenRouterClient.FINISH_REASON))
    }

    // (e) stop_reason mapping: max_tokens -> length (the truncation mark depends on it).
    @Test
    fun response_maps_stop_reasons() {
        assertEquals("tool_calls", AnthropicClient.mapFinishReason("tool_use"))
        assertEquals("length", AnthropicClient.mapFinishReason("max_tokens"))
        assertEquals("stop", AnthropicClient.mapFinishReason("end_turn"))
        assertEquals("stop", AnthropicClient.mapFinishReason("stop_sequence"))
        assertNull(AnthropicClient.mapFinishReason(null))
        assertNull(AnthropicClient.mapFinishReason(""))
    }

    // --- HTTP level ---

    // (f) POST lands on {base}/v1/messages with x-api-key + anthropic-version headers.
    @Test
    fun chatRaw_posts_to_messages_endpoint_with_anthropic_headers() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"content":[{"type":"text","text":"готов"}],"stop_reason":"end_turn"}"""
        ))
        val result = client.chatRaw("ignored", "sk-key", "claude-x",
            JSONArray().put(JSONObject().put("role", "user").put("content", "ping")), null)
        val req = server.takeRequest()
        assertEquals("/base/v1/messages", req.path)
        assertEquals("sk-key", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("claude-x", sent.getString("model"))
        assertTrue(result.isSuccess)
        assertEquals("готов", result.getOrThrow().getString("content"))
    }

    // (g) messagesUrl: both with and without the /v1 suffix already typed.
    @Test
    fun messagesUrl_tolerates_v1_suffix() {
        assertEquals("https://api.anthropic.com/v1/messages",
            AnthropicClient.messagesUrl("https://api.anthropic.com"))
        assertEquals("https://relay.example/v1/messages",
            AnthropicClient.messagesUrl("https://relay.example/v1/"))
    }

    // (h) HTTP 401 -> failure carrying the code (the backend voices a key-specific message).
    @Test
    fun chatRaw_http_error_is_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val result = client.chatRaw("ignored", "bad", "claude-x", JSONArray(), null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LlmHttpException)
        assertEquals(401, (result.exceptionOrNull() as LlmHttpException).code)
    }
}
