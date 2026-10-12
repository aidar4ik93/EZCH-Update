package com.example.homeezch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class MetWeatherTest {
    @Test fun selectsNearestCurrentForecastAndRoundsTemperature() {
        val now = java.time.Instant.parse("2026-10-12T08:30:00Z").toEpochMilli()
        val json = JSONObject("""{"properties":{"timeseries":[
            {"time":"2026-10-12T08:00:00Z","data":{"instant":{"details":{"air_temperature":7.6}},"next_1_hours":{"summary":{"symbol_code":"rain"}}}},
            {"time":"2026-10-12T10:00:00Z","data":{"instant":{"details":{"air_temperature":10}}}}
        ]}}""")
        val result = MetWeather.parse(json, now)
        assertEquals(8, result.temperature)
        assertEquals("Дождь", MetWeather.condition(result.condition))
        assertEquals(now, result.fetchedAt)
    }
    @Test fun coordinatesAreLimitedToFourDecimals() {
        assertEquals("55.1235-37.9877", MetWeather.key(WeatherPoint("city",55.123456,37.987654)))
    }
    @Test(expected = IllegalArgumentException::class) fun expiredForecastIsRejected() {
        val json = JSONObject("""{"properties":{"timeseries":[{"time":"2020-01-01T00:00:00Z","data":{"instant":{"details":{"air_temperature":0}}}}]}}""")
        MetWeather.parse(json, System.currentTimeMillis())
    }
}
