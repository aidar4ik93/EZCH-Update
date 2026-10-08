package com.example.homeezch

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import android.util.Log
import kotlin.math.roundToInt

internal object OpenMeteoWeather {
    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "EZCH-Launcher/0.15 (Android TV)")
            val status = connection.responseCode
            if (status != 200) throw WeatherHttpException(status)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    suspend fun search(city: String): List<WeatherPoint> = withContext(Dispatchers.IO) {
        try {
            val rows = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=5&language=ru&format=json").optJSONArray("results")
                ?: return@withContext emptyList()
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                WeatherPoint(listOf(row.optString("name"), row.optString("admin1"), row.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", "),
                    row.getDouble("latitude"), row.getDouble("longitude"))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("EZCH.Weather", "City search failed: ${e.javaClass.simpleName}", e); emptyList() }
    }
    suspend fun load(context: Context, point: WeatherPoint, force: Boolean = false, fetch: (String) -> JSONObject = ::get): WeatherResult = withContext(Dispatchers.IO) {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude.isFinite() && point.longitude in -180.0..180.0)
        val file = File(context.cacheDir, "open-meteo-${point.latitude}-${point.longitude}.json")
        val cache = runCatching {
            val json = JSONObject(file.readText())
            WeatherReading(json.getInt("temperature"), json.getString("condition"), json.getLong("fetchedAt"))
        }.getOrNull()
        val age = cache?.let { System.currentTimeMillis() - it.fetchedAt } ?: Long.MAX_VALUE
        if (!force && cache != null && age in 0 until 30*60*1000L) return@withContext WeatherResult(cache)
        try {
            val now = fetch("https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&current=temperature_2m,weather_code&timezone=auto").getJSONObject("current")
            val temperature = now.getDouble("temperature_2m")
            check(temperature.isFinite() && temperature in -100.0..70.0)
            val reading = WeatherReading(temperature.roundToInt(), now.getInt("weather_code").toString(), System.currentTimeMillis())
            runCatching { val atomic = android.util.AtomicFile(file); val stream = atomic.startWrite(); try { stream.write(JSONObject().put("temperature", reading.temperature).put("condition", reading.condition).put("fetchedAt", reading.fetchedAt).toString().toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e } }
            WeatherResult(reading)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w("EZCH.Weather", "Forecast failed: ${e.javaClass.simpleName}", e)
            val retained = cache?.takeIf { age in 0 until 6*60*60*1000L }
            WeatherResult(retained, (if (retained != null) "Сохранённая погода · " else "") + weatherFailure(e))
        }
    }
    fun condition(code: String): String = when (code.toIntOrNull()) {
        0 -> "Ясно"
        1, 2 -> "Малооблачно"
        3 -> "Облачно"
        45, 48 -> "Туман"
        51, 53, 55, 56, 57 -> "Морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Дождь"
        71, 73, 75, 77, 85, 86 -> "Снег"
        95, 96, 99 -> "Гроза"
        else -> "Погода"
    }
}
