package com.example.homeezch

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

internal data class MetResponse(val status: Int, val body: String = "", val expires: Long = 0,
    val modified: String? = null, val retryAfterMillis: Long = 0)

internal object MetWeather {
    private const val HALF_HOUR = 30 * 60 * 1000L
    fun key(point: WeatherPoint) = String.format(Locale.US, "%.4f-%.4f", point.latitude, point.longitude)
    fun parse(json: JSONObject, now: Long): WeatherReading {
        val series = json.getJSONObject("properties").getJSONArray("timeseries")
        require(series.length() > 0)
        val rows = (0 until series.length()).map { series.getJSONObject(it) }
        val row = rows.minBy { kotlin.math.abs(Instant.parse(it.getString("time")).toEpochMilli() - now) }
        val forecastTime = Instant.parse(row.getString("time")).toEpochMilli()
        require(kotlin.math.abs(forecastTime - now) < 3 * 60 * 60 * 1000L) { "Forecast has no current time interval" }
        val data = row.getJSONObject("data")
        val temperature = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
        require(temperature.isFinite() && temperature in -100.0..70.0)
        val symbol = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull {
            data.optJSONObject(it)?.optJSONObject("summary")?.optString("symbol_code")?.takeIf(String::isNotBlank)
        } ?: "unknown"
        return WeatherReading(temperature.roundToInt(), symbol, now)
    }
    private fun fetch(url: String, modified: String?): MetResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "EZCH-Launcher/0.26 (https://github.com/aidar4ik93/EZCH-Update)")
            connection.setRequestProperty("Accept", "application/json")
            modified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val status = connection.responseCode
            val retry = connection.getHeaderField("Retry-After")?.let { value ->
                value.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
                    java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis()
                }.getOrDefault(0).coerceIn(0, 86400000)
            } ?: 0
            return MetResponse(status, if (status in listOf(200, 203))
                connection.inputStream.bufferedReader().use { it.readText() } else "",
                connection.getHeaderFieldDate("Expires", 0), connection.getHeaderField("Last-Modified"), retry)
        } finally { connection.disconnect() }
    }
    suspend fun load(context: Context, point: WeatherPoint, force: Boolean = false,
        fetcher: (String, String?) -> MetResponse = ::fetch): WeatherResult = withContext(Dispatchers.IO) {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude.isFinite() && point.longitude in -180.0..180.0)
        val now = System.currentTimeMillis()
        val file = AtomicFile(File(context.filesDir, "met-weather-${key(point)}.json"))
        val saved = runCatching { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
        val cached = runCatching { saved?.let {
            require(it.getInt("temperature") in -100..70 && it.getLong("fetchedAt") in 1..now)
            WeatherReading(it.getInt("temperature"), it.getString("condition"), it.getLong("fetchedAt"))
        } }.getOrNull()
        val freshUntil = saved?.optLong("freshUntil") ?: 0L
        val retryAt = saved?.optLong("retryAt") ?: 0L
        fun save(value: JSONObject) {
            val stream = file.startWrite()
            try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
        // Even manual refresh respects server Expires and backoff.
        if (cached != null && now < freshUntil) return@withContext WeatherResult(cached)
        if (now < retryAt) return@withContext WeatherResult(cached, if (cached != null) "Устаревшие данные · повтор позже" else "Сервис недоступен · повтор позже")
        try {
            val url = String.format(Locale.US, "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", point.latitude, point.longitude)
            val response = fetcher(url, saved?.optString("modified")?.takeIf(String::isNotBlank))
            val reading = when (response.status) {
                200, 203 -> parse(JSONObject(response.body), now)
                304 -> cached?.copy(fetchedAt = now) ?: error("Server returned 304 without cached forecast")
                else -> {
                    val next = saved ?: JSONObject()
                    next.put("retryAt", now + maxOf(response.retryAfterMillis, if (response.status == 429) 60 * 60 * 1000L else 5 * 60 * 1000L))
                    runCatching { save(next) }
                    throw WeatherHttpException(response.status)
                }
            }
            val next = JSONObject().put("temperature", reading.temperature).put("condition", reading.condition)
                .put("fetchedAt", reading.fetchedAt).put("freshUntil", maxOf(response.expires, now + HALF_HOUR))
                .put("modified", response.modified ?: saved?.optString("modified").orEmpty())
            runCatching { save(next) }
            WeatherResult(reading, if (response.status == 203) "MET Norway · API обновляется" else "")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e !is WeatherHttpException) runCatching { save((saved ?: JSONObject()).put("retryAt", now + 5 * 60 * 1000L)) }
            WeatherResult(cached, (if (cached != null) "Устаревшие данные · " else "") + weatherFailure(e))
        }
    }
    fun condition(code: String) = when {
        code.contains("thunder") -> "Гроза"
        code.contains("snow") || code.contains("sleet") -> "Снег"
        code.contains("rain") -> "Дождь"
        code.contains("fog") -> "Туман"
        code.contains("partlycloudy") || code.contains("fair") -> "Малооблачно"
        code.contains("cloudy") -> "Облачно"
        code.contains("clearsky") -> "Ясно"
        else -> "Погода"
    }
    fun icon(code: String) = when (condition(code)) {
        "Ясно" -> "☀"; "Малооблачно" -> "⛅"; "Облачно" -> "☁"; "Дождь" -> "☂"; "Снег" -> "❄"; "Гроза" -> "ϟ"; else -> "≈"
    }
}
