package com.example.homeezch

import android.content.Context
import org.json.JSONObject

internal data class WeatherPoint(val city: String, val latitude: Double, val longitude: Double)
internal data class WeatherReading(val temperature: Int, val condition: String, val fetchedAt: Long)
internal data class WeatherResult(val reading: WeatherReading?, val status: String = "")

internal object WeatherAccess {
    fun loadPoint(context: Context): WeatherPoint? = runCatching {
        val data = context.getSharedPreferences("ezch_launcher_prefs", 0).getString("weather_point", null) ?: return null
        val json = JSONObject(data)
        WeatherPoint(json.getString("city"), json.getDouble("lat"), json.getDouble("lon"))
    }.getOrNull()
    fun savePoint(context: Context, point: WeatherPoint): Boolean = context.getSharedPreferences("ezch_launcher_prefs", 0)
        .edit().putString("weather_point", JSONObject().put("city", point.city).put("lat", point.latitude).put("lon", point.longitude).toString()).commit()
}
