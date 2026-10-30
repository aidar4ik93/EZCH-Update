package com.example.homeezch.ai

import com.example.homeezch.WeatherReading
import org.junit.Assert.*
import org.junit.Test

class WeatherVoiceTest {
    private val now = 1_800_000_000_000L
    @Test fun freshWeatherContainsCityConditionAndNaturalDegrees() {
        val result = formatSpokenWeather("Казань", WeatherReading(13, "partlycloudy_day", now), "", now)
        assertTrue(result.accepted)
        assertEquals("Казань. Сейчас плюс 13 градусов. Малооблачно.", result.message)
    }
    @Test fun spokenNegativeZeroAndSingularTemperatures() {
        for ((value, expected) in listOf(-21 to "минус 21 градус", -2 to "минус 2 градуса", 0 to "ноль градусов", 11 to "плюс 11 градусов")) {
            assertTrue(formatSpokenWeather("Город", WeatherReading(value, "clearsky_day", now), "", now).message.contains(expected))
        }
    }
    @Test fun oldMissingInvalidAndFutureDataAreNeverCalledCurrent() {
        assertFalse(formatSpokenWeather("Город", null, "Нет сети", now).accepted)
        for (reading in listOf(WeatherReading(10, "rain", now - 10_800_001), WeatherReading(10, "rain", now + 1), WeatherReading(500, "rain", now)))
            assertFalse(formatSpokenWeather("Город", reading, "", now).accepted)
    }
    @Test fun cachedDataOnNetworkFailureAreClearlyLabelled() {
        val result = formatSpokenWeather("Город", WeatherReading(2, "rain", now - 1_800_000), "Устаревшие данные · Нет сети", now)
        assertTrue(result.accepted)
        assertTrue(result.message.contains("По сохранённым данным"))
        assertTrue(result.message.contains("Обновить данные пока не удалось"))
        assertFalse(result.message.contains("Сейчас"))
    }
}
