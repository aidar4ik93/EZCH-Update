package com.example.homeezch
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
class WeatherProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val point = WeatherPoint("Test", 12.3456, 23.4567)
    private val cache get() = File(context.cacheDir, "open-meteo-${point.latitude}-${point.longitude}.json")
    @Before fun clearCache() { cache.delete(); File(cache.path + ".bak").delete() }
    @After fun cleanup() { clearCache() }
    @Test fun forecastIsParsedCachedAndUsedWithoutNetwork() = runBlocking {
        val result = OpenMeteoWeather.load(context, point, fetch = { url ->
            assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
            JSONObject("{\"current\":{\"temperature_2m\":23.4,\"weather_code\":3}}")
        })
        assertEquals(23, result.reading!!.temperature)
        assertEquals("Облачно", OpenMeteoWeather.condition(result.reading.condition))
        assertEquals(result.reading, OpenMeteoWeather.load(context, point, fetch = { error("Fresh cache must prevent a network request") }).reading)
    }
    @Test fun failedRefreshRetainsRecentWeatherWithHonestStatus() = runBlocking {
        cache.writeText(JSONObject().put("temperature", 9).put("condition", "61").put("fetchedAt", System.currentTimeMillis() - 3600000).toString())
        val result = OpenMeteoWeather.load(context, point, true, fetch = { throw java.net.SocketTimeoutException() })
        assertEquals(9, result.reading!!.temperature)
        assertTrue(result.status.startsWith("Сохранённая погода"))
    }
    @Test fun damagedCacheAndOldWeatherAreNotShownAsCurrent() = runBlocking {
        cache.writeText("broken")
        assertNull(OpenMeteoWeather.load(context, point, true, fetch = { throw java.net.UnknownHostException() }).reading)
        cache.writeText(JSONObject().put("temperature", 99).put("condition", "0").put("fetchedAt", System.currentTimeMillis() - 86400000).toString())
        assertNull(OpenMeteoWeather.load(context, point, true, fetch = { throw java.net.UnknownHostException() }).reading)
    }
}
