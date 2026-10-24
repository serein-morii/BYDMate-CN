package com.bydmate.app.agent

import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.local.LocalePreferences
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** QWeather engine of [WeatherClient]: routing, coordinate convention, and the shape mapping. */
class WeatherClientQWeatherTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): kotlinx.coroutines.flow.Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val nowFixture = """
        {"code":"200","updateTime":"2026-07-04T12:00+08:00","now":{
          "temp":"28","feelsLike":"31","text":"多云","windSpeed":"18","humidity":"62"}}
    """.trimIndent()

    private val dailyFixture = """
        {"code":"200","daily":[
          {"fxDate":"2026-07-04","tempMax":"31","tempMin":"22","textDay":"多云","precipProb":"40"},
          {"fxDate":"2026-07-05","tempMax":"30","tempMin":"21","textDay":"雷阵雨"},
          {"fxDate":"2026-07-06","tempMax":"29","tempMin":"20","textDay":"晴"}]}
    """.trimIndent()

    private val geoFixture = """
        {"code":"200","location":[{"name":"北京","lat":"39.90498","lon":"116.40528"}]}
    """.trimIndent()

    private val requestedUrls = mutableListOf<String>()

    /** Routes by host+path: weather/now, weather/3d, city lookup. */
    private fun clientFor(now: String? = null, daily: String? = null, geo: String? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestedUrls += req.url.toString()
                val body = when {
                    req.url.encodedPath.endsWith("/weather/now") -> now
                    req.url.encodedPath.endsWith("/weather/3d") -> daily
                    req.url.host == "geoapi.qweather.com" -> geo
                    else -> null
                }
                Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body((body ?: "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun clientWithKey(http: OkHttpClient, key: String = "qkey"): WeatherClient {
        val dao = FakeSettingsDao()
        dao.map[SettingsRepository.KEY_QWEATHER_API_KEY] = key
        val client = WeatherClient(http)
        client.injectSettings(SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true)))
        return client
    }

    // (a) A saved key routes both calls to QWeather; the forecast maps onto the Open-Meteo
    // shape, converting km/h wind to m/s.
    @Test fun forecast_maps_qweather_onto_internal_shape() = runTest {
        val client = clientWithKey(clientFor(now = nowFixture, daily = dailyFixture))
        val result = client.forecast(39.90498, 116.40528)
        assertTrue(result.isSuccess)
        val json = JSONObject(result.getOrThrow())

        val now = json.getJSONObject("now")
        assertEquals(28.0, now.getDouble("temp_c"), 0.01)
        assertEquals(31.0, now.getDouble("feels_c"), 0.01)
        assertEquals("多云", now.getString("text"))
        assertEquals(5.0, now.getDouble("wind_ms"), 0.05) // 18 km/h
        assertEquals(62, now.getInt("humidity"))

        val days = json.getJSONArray("days")
        assertEquals(3, days.length())
        assertEquals("2026-07-04", days.getJSONObject(0).getString("date"))
        assertEquals(22.0, days.getJSONObject(0).getDouble("min_c"), 0.01)
        assertEquals(31.0, days.getJSONObject(0).getDouble("max_c"), 0.01)
        assertEquals(40, days.getJSONObject(0).optInt("precip_prob", -1))
        // precipProb is plan-dependent: absent -> the field is omitted, not zeroed.
        assertTrue(days.getJSONObject(1).isNull("precip_prob") || !days.getJSONObject(1).has("precip_prob"))
    }

    // (b) QWeather coordinate queries are lon,lat (in that order).
    @Test fun forecast_queries_location_lon_first() = runTest {
        val client = clientWithKey(clientFor(now = nowFixture, daily = dailyFixture))
        client.forecast(39.9, 116.4)
        val query = requestedUrls.first { it.contains("devapi") }.substringAfter("location=")
            .substringBefore('&')
        assertEquals("116.4,39.9", java.net.URLDecoder.decode(query, "UTF-8"))
    }

    // (c) Geocoding rides on geoapi city lookup and reports the localized name.
    @Test fun geocode_maps_first_lookup_location() = runTest {
        val client = clientWithKey(clientFor(geo = geoFixture))
        val geo = client.geocode("北京").getOrThrow()
        assertEquals(39.90498, geo.lat, 0.0001)
        assertEquals(116.40528, geo.lon, 0.0001)
        assertEquals("北京", geo.name)
    }

    // (d) Body-level error codes: 404 is an honest "no such city"; other codes are failures.
    @Test fun qweather_code_errors_surface() = runTest {
        val client = clientWithKey(clientFor(geo = """{"code":"404"}"""))
        val result = client.geocode("Нигдегород")
        assertTrue(result.exceptionOrNull() is WeatherClient.UserError)

        val client2 = clientWithKey(clientFor(now = """{"code":"402"}""", daily = dailyFixture))
        assertTrue(client2.forecast(1.0, 2.0).isFailure)
    }

    // (e) Without a key (or without injected settings) the keyless Open-Meteo engine stays.
    @Test fun no_key_keeps_open_meteo() = runTest {
        val openMeteoFixture = """
            {"current":{"temperature_2m":1.0,"apparent_temperature":0.0,"weather_code":0,
             "wind_speed_10m":1.0,"relative_humidity_2m":50},
             "daily":{"time":["2026-07-04"],"temperature_2m_max":[2.0],"temperature_2m_min":[0.0],
             "weather_code":[0],"precipitation_probability_max":[0]}}
        """.trimIndent()
        val plain = WeatherClient(OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requestedUrls += req.url.toString()
            Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(openMeteoFixture.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertTrue(plain.forecast(55.75, 37.62).isSuccess)
        assertTrue(requestedUrls.any { it.contains("open-meteo.com") })
        assertTrue(requestedUrls.none { it.contains("qweather") })
    }
}
