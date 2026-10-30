package com.example.homeezch

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.homeezch.ai.AiPreferences
import com.example.homeezch.ai.EvaEngine
import org.junit.Test
import org.junit.Assert.*

class AiEntryDeviceTest {
    @Test fun assistantStartsCompactlyInsideHomeWithoutAccessibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        prepareLauncherTest()
        context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit().remove("profiles_v1").remove("active_profile").commit()
        AiPreferences.store(context).edit().clear().commit()
        // This case deliberately checks offline entry; the product now defaults to online speech.
        AiPreferences.store(context).edit().putBoolean("tts", false).putBoolean("prefer_offline", true)
            .putBoolean("network_speech", false).commit()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("Ai EvA.")), 15000))
            // The owner removed the TopBar button. Exercise the production remote route instead.
            assertNull(device.findObject(By.text("◉ EVA")))
            scenario.onActivity { RemoteButtons.execute(it, RemoteAction.EVA) }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (!AiPreferences.enabled(context) && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertTrue(device.wait(Until.hasObject(By.descContains("Ai EvA.")), 15000))
            assertTrue(AiPreferences.enabled(context))
            assertFalse(AiPreferences.networkSpeech(context))
            assertEquals(!deviceLowRam(context), EvaEngine.handsFreeEnabled(context))
            assertEquals(context.packageName, device.currentPackageName)
            // Idle orb is replaced by the command panel; never retain a stale UiObject2.
            var bounds: android.graphics.Rect? = null
            val compactDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (bounds == null && android.os.SystemClock.uptimeMillis() < compactDeadline) {
                bounds = runCatching { device.findObject(By.descStartsWith("Ai EvA."))?.visibleBounds }
                    .getOrNull()?.takeIf { it.width() > 0 && it.height() > 0 }
                if (bounds == null) android.os.SystemClock.sleep(50)
            }
            assertNotNull("Compact EVA stays inside HOME", bounds)
            assertTrue(bounds!!.width() < device.displayWidth / 3)
            assertTrue(bounds.height() < device.displayHeight / 3)
        }
        AiPreferences.store(context).edit().putBoolean("enabled", false).commit()
    }
}
