package com.example.homeezch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object NetworkLocation {
    suspend fun load(): WeatherPoint? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://ipwho.is/?fields=success,city,latitude,longitude").openConnection() as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            if (connection.responseCode != 200) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("success")) return@withContext null
            val lat = json.getDouble("latitude"); val lon = json.getDouble("longitude")
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return@withContext null
            WeatherPoint(json.optString("city").ifBlank { "По сети" }, lat, lon)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }
        finally { connection?.disconnect() }
    }
}
