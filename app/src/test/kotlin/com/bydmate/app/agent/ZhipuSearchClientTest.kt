package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZhipuSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ZhipuSearchClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = ZhipuSearchClient(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    // (a) The usual shape: results wrapped in choices[0].message.content as a JSON string,
    // mapped to the unified {title,url,text} shape.
    @Test
    fun `search parses wrapped choices shape and maps to exa shape`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"tool","content":"{\"search_result\":[{\"title\":\"T1\",\"link\":\"https://a\",\"content\":\"C1\"},{\"title\":\"T2\",\"link\":\"https://b\",\"content\":\"C2\"}]}"}}]}"""
        ))
        client.baseUrlForTest = server.url("/api/paas/v4").toString().trimEnd('/')
        val out = client.search("zp", "充电桩 北京").getOrThrow()
        val req = server.takeRequest()
        assertEquals("/api/paas/v4/web_search", req.path)
        assertEquals("Bearer zp", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("search_std", sent.getString("search_engine"))
        assertEquals("充电桩 北京", sent.getString("search_query"))
        val results = JSONObject(out).getJSONArray("results")
        assertEquals(2, results.length())
        assertEquals("T1", results.getJSONObject(0).getString("title"))
        assertEquals("https://a", results.getJSONObject(0).getString("url"))
        assertEquals("C1", results.getJSONObject(0).getString("text"))
    }

    // (b) A bare {search_result:[...]} body is accepted too.
    @Test
    fun `search accepts bare search_result shape`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"search_result":[{"title":"T","link":"https://a","content":"C"}]}"""
        ))
        client.baseUrlForTest = server.url("/api/paas/v4").toString().trimEnd('/')
        val out = client.search("zp", "q").getOrThrow()
        assertEquals("T", JSONObject(out).getJSONArray("results").getJSONObject(0).getString("title"))
    }

    // (c) Unrecognized body -> empty result list, not a failure: the search chain can move on.
    @Test
    fun `unrecognized body maps to empty results`() {
        assertTrue(ZhipuSearchClient.extractResults(JSONObject("""{"choices":[]}""")).length() == 0)
        assertTrue(ZhipuSearchClient.extractResults(JSONObject("""{"choices":[{"message":{"content":"not json"}}]}""")).length() == 0)
    }

    // (d) HTTP 401 -> Result.failure.
    @Test
    fun `http error is failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        client.baseUrlForTest = server.url("/api/paas/v4").toString().trimEnd('/')
        assertTrue(client.search("zp", "q").isFailure)
    }
}
