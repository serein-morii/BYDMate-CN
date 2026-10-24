package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider ordering of the web_search chain: the chosen provider leads, zhipu ahead of exa. */
class AgentToolsSearchChainTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()
    private val zaiSearchClient = mockk<ZaiSearchClient>()
    private val zhipuSearchClient = mockk<ZhipuSearchClient>()
    private val llmConnections = mockk<LlmConnectionResolver>()

    private val context = mockk<Context>(relaxed = true)

    private fun tools(): AgentTools {
        val tools = AgentTools(
            gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
            exa, openRouterClient, settingsRepository, contactLookup, context,
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<ChargerSearchClient>(relaxed = true),
            mockk<InsightsManager>(relaxed = true),
            zaiSearchClient,
            llmConnections,
        )
        tools.injectZhipuSearch(zhipuSearchClient)
        return tools
    }

    private fun result() = """{"results":[{"title":"T","url":"u","text":"t"}]}"""

    // (1) Both keys set, no explicit choice: zhipu comes BEFORE exa; success short-circuits.
    @Test fun zhipu_key_beats_exa_in_default_order() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_WEB_SEARCH_PROVIDER, "") } returns ""
        coEvery { settingsRepository.getString(SettingsRepository.KEY_ZHIPU_SEARCH_API_KEY, "") } returns "zp"
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa"
        coEvery { zhipuSearchClient.search("zp", "q") } returns Result.success(result())
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"q"}""")))
        assertTrue(out.has("results"))
        coVerify(exactly = 0) { exa.search(any(), any()) }
    }

    // (2) Chosen provider first even when another key exists too.
    @Test fun chosen_exa_leads_over_keyed_zhipu() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_WEB_SEARCH_PROVIDER, "") } returns
            SettingsRepository.PROVIDER_EXA
        coEvery { settingsRepository.getString(SettingsRepository.KEY_ZHIPU_SEARCH_API_KEY, "") } returns "zp"
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa"
        coEvery { exa.search("exa", "q") } returns Result.success(result())
        tools().execute(AgentToolCall("1", "web_search", """{"query":"q"}"""))
        coVerify(exactly = 0) { zhipuSearchClient.search(any(), any()) }
    }

    // (3) Zhipu failure falls through to exa, then to the native search of the primary z.ai.
    @Test fun zhipu_failure_falls_through_to_exa_then_native() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_WEB_SEARCH_PROVIDER, "") } returns
            SettingsRepository.PROVIDER_ZHIPU
        coEvery { settingsRepository.getString(SettingsRepository.KEY_ZHIPU_SEARCH_API_KEY, "") } returns "zp"
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa"
        coEvery { zhipuSearchClient.search(any(), any()) } returns Result.failure(RuntimeException("HTTP 500"))
        coEvery { exa.search(any(), any()) } returns Result.failure(RuntimeException("HTTP 500"))
        val conn = LlmConnection(
            LlmConnectionResolver.ID_ZAI, "z.ai", LlmConnectionResolver.ZAI_BASE_URL, "zk", LlmConnectionResolver.ZAI_MODEL,
        )
        coEvery { llmConnections.primary() } returns conn
        coEvery { zaiSearchClient.search("zk", "q") } returns Result.success(result())
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"q"}""")))
        assertTrue(out.has("results"))
        coVerify(exactly = 1) { zhipuSearchClient.search(any(), any()) }
        coVerify(exactly = 1) { exa.search(any(), any()) }
        coVerify(exactly = 1) { zaiSearchClient.search(any(), any()) }
    }

    // (4) Chosen z.ai: the primary connection's native search runs FIRST (no duplicate zhipu).
    @Test fun chosen_zai_runs_native_first() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_WEB_SEARCH_PROVIDER, "") } returns
            SettingsRepository.PROVIDER_ZAI
        coEvery { settingsRepository.getString(SettingsRepository.KEY_ZHIPU_SEARCH_API_KEY, "") } returns ""
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        val conn = LlmConnection(
            LlmConnectionResolver.ID_ZAI, "z.ai", LlmConnectionResolver.ZAI_BASE_URL, "zk", LlmConnectionResolver.ZAI_MODEL,
        )
        coEvery { llmConnections.primary() } returns conn
        coEvery { zaiSearchClient.search("zk", "q") } returns Result.success(result())
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"q"}""")))
        assertTrue(out.has("results"))
        coVerify(exactly = 1) { zaiSearchClient.search(any(), any()) }
    }

    // (5) Whole chain down -> the fixed Russian string, never a raw engine message.
    @Test fun dead_chain_reports_fixed_error() = runTest {
        coEvery { settingsRepository.getString(SettingsRepository.KEY_WEB_SEARCH_PROVIDER, "") } returns ""
        coEvery { settingsRepository.getString(SettingsRepository.KEY_ZHIPU_SEARCH_API_KEY, "") } returns ""
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns ""
        coEvery { llmConnections.primary() } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "web_search", """{"query":"q"}""")))
        assertEquals("поиск недоступен", out.getString("error"))
    }
}
