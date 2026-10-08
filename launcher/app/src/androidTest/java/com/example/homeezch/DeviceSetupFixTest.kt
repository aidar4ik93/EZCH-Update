package com.example.homeezch

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.homeezch.HomeAccessibility
import org.junit.Assert.*
import org.junit.Test

class DeviceSetupFixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    @Test fun forcedWeatherRefreshBypassesFreshCacheAndReportsFailure() = kotlinx.coroutines.runBlocking {
        val point = WeatherPoint("QA test", 0.12345, 0.54321)
        val file = java.io.File(context.cacheDir, "open-meteo-${point.latitude}-${point.longitude}.json")
        file.delete()
        try {
            val first = OpenMeteoWeather.load(context, point, true) { org.json.JSONObject("{\"current\":{\"temperature_2m\":12.6,\"weather_code\":3}}") }
            assertEquals(13, first.reading!!.temperature)
            val cached = OpenMeteoWeather.load(context, point) { fail("Fresh cache must avoid network"); org.json.JSONObject() }
            assertEquals(first.reading, cached.reading)
            val refused = OpenMeteoWeather.load(context, point, true) { throw WeatherHttpException(403) }
            assertEquals(first.reading, refused.reading)
            assertTrue(refused.status.contains("403"))
        } finally { file.delete() }
    }
    @Test fun secureGrantEnablesOwnServiceAndCanBeRestored() {
        val resolver = context.contentResolver
        val oldServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val previouslyGranted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        device.executeShellCommand("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        try {
            assertTrue(HomeAccessibility.enable(context))
            val own = ComponentName(context, HomeButtonService::class.java).flattenToString()
            assertTrue(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(own))
            assertEquals(1, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
            assertTrue(HomeAccessibility.enable(context))
        } finally {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
            if (!previouslyGranted) device.executeShellCommand("pm revoke ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        }
    }
    @Test fun missingGrantDoesNotChangeEnabledServices() {
        val resolver = context.contentResolver
        device.executeShellCommand("pm revoke ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        val before = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertFalse(HomeAccessibility.enable(context))
        assertEquals(before, Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    @Test fun usbOpensBundledFileManager() {
        instrumentation.runOnMainSync { assertTrue(SettingsRouter.files(context)) }
        val end = SystemClock.elapsedRealtime()+20_000
        while (device.currentPackageName != context.packageName && SystemClock.elapsedRealtime()<end) SystemClock.sleep(200)
        assertEquals(context.packageName, device.currentPackageName)
    }
    @Test fun bluetoothHasUsableTvSettingsRoute() {
        instrumentation.runOnMainSync { assertTrue(SettingsRouter.bluetooth(context)) }
        val end = SystemClock.elapsedRealtime()+20_000
        while (device.currentPackageName?.contains("settings") != true && SystemClock.elapsedRealtime()<end) SystemClock.sleep(200)
        assertTrue(device.currentPackageName.orEmpty().contains("settings"))
    }
}
