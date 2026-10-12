package com.example.homeezch
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
class MetCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val point = WeatherPoint("Test", 12.9876, 23.9876)
    private val cache get() = File(context.filesDir, "met-weather-${MetWeather.key(point)}.json")
    @Before fun clear() { cache.delete(); File(cache.path + ".bak").delete() }
    @After fun cleanup() { clear() }
    @Test fun serverExpiryIsHonouredEvenForManualRefresh() = runBlocking {
        val now = System.currentTimeMillis()
        cache.writeText(JSONObject().put("temperature", 9).put("condition", "rain").put("fetchedAt", now)
            .put("freshUntil", now + 3600000).toString())
        val result = MetWeather.load(context, point, true) { _, _ -> error("Expires must prevent a request") }
        assertEquals(9, result.reading!!.temperature)
        assertEquals("", result.status)
    }
    @Test fun offlineKeepsOldForecastAndLimitsRetryTraffic() = runBlocking {
        cache.writeText(JSONObject().put("temperature", 9).put("condition", "rain").put("fetchedAt", System.currentTimeMillis()-86400000).toString())
        val result = MetWeather.load(context, point) { _, _ -> throw java.net.UnknownHostException() }
        assertEquals(9, result.reading!!.temperature)
        assertTrue(result.status.startsWith("Устаревшие данные"))
        val second = MetWeather.load(context, point, true) { _, _ -> error("Backoff must prevent a request") }
        assertEquals(9, second.reading!!.temperature)
    }
    @Test fun rateLimitWithoutCacheDoesNotProduceFakeWeather() = runBlocking {
        val result = MetWeather.load(context, point) { _, _ -> MetResponse(429, retryAfterMillis=3600000) }
        assertNull(result.reading)
        assertNull(MetWeather.load(context, point, true) { _, _ -> error("No request while rate limited") }.reading)
    }
}
