package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.diagnostics.Trace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the get_weather tool. Default engine is Open-Meteo (keyless); when the user
 * saved a QWeather key both endpoints switch to QWeather (geoapi.qweather.com city lookup +
 * devapi.qweather.com current/3-day), whose output is mapped onto the same internal JSON
 * shape. QWeather is friendlier for Chinese city names; its coordinate queries take lon,lat.
 */
@Singleton
class WeatherClient @Inject constructor(private val http: OkHttpClient) {

    /** Failure whose message is user-facing Russian text, safe to surface in tool error JSON.
     *  Any other Throwable (HTTP codes, parse errors, timeouts) carries an English message and
     *  must be collapsed to a generic Russian error by the caller. */
    class UserError(message: String) : IOException(message)

    /** A non-2xx answer; [code] is safe to log, unlike the URL with the coordinates. */
    class HttpError(val code: Int) : IOException("HTTP $code")

    /** A geocoded location: coordinates plus the resolved place name, voiced back by the tool. */
    data class GeoPoint(val lat: Double, val lon: Double, val name: String)

    // QWeather needs the key per call; method injection (same idiom as AgentTools.injectSplit)
    // keeps every existing test constructor call intact. Null until Hilt injects it.
    private var settingsRepository: SettingsRepository? = null

    /** Called by Hilt after construction; call manually in unit tests that test QWeather. */
    @Inject
    internal fun injectSettings(repo: SettingsRepository) {
        settingsRepository = repo
    }

    /** Saved QWeather key, or null when unset/unreadable — the Open-Meteo fallback then applies. */
    private suspend fun qweatherKey(): String? = runCatching {
        settingsRepository?.getString(SettingsRepository.KEY_QWEATHER_API_KEY, "")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Current conditions + 3-day forecast, compacted into the JSON the LLM tool returns. */
    suspend fun forecast(lat: Double, lon: Double): Result<String> = withContext(Dispatchers.IO) {
        val key = qweatherKey()
        val startMs = System.nanoTime() / NANOS_PER_MS
        if (key != null) traced(QWEATHER_NOW_URL, startMs) { qweatherForecast(key, lat, lon) }
        else traced(FORECAST_URL, startMs) { openMeteoForecast(lat, lon) }
    }

    /** Geocoding: first match for a free-text city name (QWeather lookup, else Open-Meteo). */
    suspend fun geocode(city: String): Result<GeoPoint> = withContext(Dispatchers.IO) {
        val key = qweatherKey()
        val startMs = System.nanoTime() / NANOS_PER_MS
        if (key != null) traced(QWEATHER_GEO_URL, startMs) { qweatherGeocode(key, city) }
        else traced(GEOCODE_URL, startMs) { openMeteoGeocode(city) }
    }

    // --- Open-Meteo (default engine) ---

    private fun openMeteoForecast(lat: Double, lon: Double): String {
        val url = FORECAST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current",
                "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m")
            .addQueryParameter("daily",
                "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "3")
            // Open-Meteo defaults to km/h; the tool JSON field is wind_ms.
            .addQueryParameter("wind_speed_unit", "ms")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                ?: throw IOException("empty body")
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val now = JSONObject().apply {
                put("temp_c", current.getDouble("temperature_2m"))
                put("feels_c", current.getDouble("apparent_temperature"))
                put("text", wmoToRussian(current.getInt("weather_code")))
                put("wind_ms", current.getDouble("wind_speed_10m"))
                put("humidity", current.getInt("relative_humidity_2m"))
            }
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val maxT = daily.getJSONArray("temperature_2m_max")
            val minT = daily.getJSONArray("temperature_2m_min")
            val codes = daily.getJSONArray("weather_code")
            val precip = daily.getJSONArray("precipitation_probability_max")
            val days = JSONArray().apply {
                for (i in 0 until dates.length()) {
                    put(JSONObject().apply {
                        put("date", dates.getString(i))
                        put("min_c", minT.getDouble(i))
                        put("max_c", maxT.getDouble(i))
                        put("text", wmoToRussian(codes.getInt(i)))
                        put("precip_prob", precip.getInt(i))
                    })
                }
            }
            return JSONObject().put("now", now).put("days", days).toString()
        }
    }

    /** Open-Meteo geocoding: first match for a free-text city name. */
    private fun openMeteoGeocode(city: String): GeoPoint {
        val url = GEOCODE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("name", city)
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "ru")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                ?: throw IOException("empty body")
            val results = JSONObject(body).optJSONArray("results")
            if (results == null || results.length() == 0) throw UserError("город не найден")
            val r = results.getJSONObject(0)
            // admin1 is intentionally dropped: the short name reads best over TTS.
            return GeoPoint(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name"))
        }
    }

    // --- QWeather (Chinese-friendly engine, enabled by the key) ---

    /** QWeather geocoding: {location:[{name,lat,lon}], code:"200"}. */
    private fun qweatherGeocode(key: String, city: String): GeoPoint {
        val url = QWEATHER_GEO_URL.toHttpUrl().newBuilder()
            .addQueryParameter("location", city)
            .addQueryParameter("key", key)
            .addQueryParameter("number", "1")
            .addQueryParameter("lang", QWEATHER_LANG)
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val json = qweatherJson(resp.body?.string())
            val location = json.optJSONArray("location")?.optJSONObject(0)
                ?: throw IOException("qweather: no location")
            // QWeather reports lat/lon as strings; the tool JSON carries doubles.
            return GeoPoint(
                location.getDouble("lat"),
                location.getDouble("lon"),
                location.optString("name"),
            )
        }
    }

    /** QWeather forecast: /v7/weather/now plus /v7/weather/3d, both taking location=lon,lat. */
    private fun qweatherForecast(key: String, lat: Double, lon: Double): String {
        // Coordinate queries are lon-first; Double.toString is already the shortest exact form.
        val location = "$lon,$lat"
        val now = qweatherGet(key, QWEATHER_NOW_URL, location).getJSONObject("now")
        val dailyJson = qweatherGet(key, QWEATHER_3D_URL, location).getJSONArray("daily")
        val nowJson = JSONObject().apply {
            put("temp_c", now.getDouble("temp"))
            put("feels_c", now.optDouble("feelsLike", now.getDouble("temp")))
            put("text", now.optString("text"))
            // QWeather windSpeed is km/h; the tool JSON field is wind_ms.
            put("wind_ms", (now.optDouble("windSpeed", 0.0) / 3.6 * 10).toInt() / 10.0)
            put("humidity", now.optInt("humidity", 0))
        }
        val days = JSONArray().apply {
            for (i in 0 until dailyJson.length()) {
                val d = dailyJson.getJSONObject(i)
                put(JSONObject().apply {
                    put("date", d.getString("fxDate"))
                    put("min_c", d.getDouble("tempMin"))
                    put("max_c", d.getDouble("tempMax"))
                    put("text", d.optString("textDay"))
                    // precipProb is plan-dependent; when absent the day simply omits the field.
                    d.optInt("precipProb", -1).takeIf { it >= 0 }?.let { put("precip_prob", it) }
                })
            }
        }
        return JSONObject().put("now", nowJson).put("days", days).toString()
    }

    /** One QWeather API GET; every body carries a code field, "200" is success. */
    private fun qweatherGet(key: String, url: String, location: String): JSONObject {
        val built = url.toHttpUrl().newBuilder()
            .addQueryParameter("location", location)
            .addQueryParameter("key", key)
            .addQueryParameter("lang", QWEATHER_LANG)
            .build()
        return http.newCall(Request.Builder().url(built).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            qweatherJson(resp.body?.string())
        }
    }

    /** QWeather answers some errors with HTTP 200 and a body code; only "200" passes.
     *  404 (no such city/location) is an answer, not a server failure. */
    private fun qweatherJson(body: String?): JSONObject {
        val json = JSONObject(body?.takeUnless { it.isNullOrBlank() } ?: throw IOException("empty body"))
        val code = json.optString("code")
        if (code == "404") throw UserError("город не найден")
        if (code.isNotEmpty() && code != "200") throw HttpError(code.toIntOrNull() ?: 0)
        return json
    }

    /** The trace event of one engine call; "город не найден" is an answer, not a server failure. */
    private suspend fun <T> traced(url: String, startMs: Long, block: suspend () -> T): Result<T> {
        val result = runCatching { block() }
        val error = result.exceptionOrNull()?.takeUnless { it is UserError }
        Trace.call("weather", url.toHttpUrl().host, System.nanoTime() / NANOS_PER_MS - startMs, error)
        return result
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val QWEATHER_GEO_URL = "https://geoapi.qweather.com/v2/city/lookup"
        private const val QWEATHER_NOW_URL = "https://devapi.qweather.com/v7/weather/now"
        private const val QWEATHER_3D_URL = "https://devapi.qweather.com/v7/weather/3d"
        /** Russian labels read best over TTS; QWeather falls back to its default when unsupported. */
        private const val QWEATHER_LANG = "ru"

        private val WMO_RU = mapOf(
            0 to "ясно",
            1 to "малооблачно",
            2 to "переменная облачность",
            3 to "пасмурно",
            45 to "туман",
            48 to "изморозь в тумане",
            51 to "лёгкая морось",
            53 to "умеренная морось",
            55 to "сильная морось",
            56 to "лёгкая ледяная морось",
            57 to "сильная ледяная морось",
            61 to "небольшой дождь",
            63 to "умеренный дождь",
            65 to "сильный дождь",
            66 to "лёгкий ледяной дождь",
            67 to "сильный ледяной дождь",
            71 to "небольшой снег",
            73 to "умеренный снег",
            75 to "сильный снег",
            77 to "снежная крупа",
            80 to "небольшие ливни",
            81 to "умеренные ливни",
            82 to "сильные ливни",
            85 to "небольшой снегопад",
            86 to "сильный снегопад",
            95 to "гроза",
            96 to "гроза с небольшим градом",
            99 to "гроза с сильным градом",
        )

        /** [code] is the Open-Meteo WMO weather_code. Unknown codes fall back to a generic label. */
        fun wmoToRussian(code: Int): String = WMO_RU[code] ?: "неизвестная погода"
    }
}
