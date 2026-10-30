package com.example.homeezch

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.homeezch.ai.*
import org.junit.Assert.*
import org.junit.Test

/** Emulator system integration only; does not verify a physical remote or microphone. */
class AiSystemSessionTest {
    @Test fun assignWithSystemUiShowRealSessionAndRestoreGoogle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = AndroidCapabilityProbe(context)
        org.junit.Assume.assumeTrue(probe.roleAvailable())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun waitRole(held: Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (probe.roleHeld() != held && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            return probe.roleHeld() == held
        }
        ActivityScenario.launch<AssistantSettingsActivity>(Intent(context, AssistantSettingsActivity::class.java)).use { scenario ->
            fun picker() {
                scenario.onActivity { assertTrue(AndroidCapabilityProbe(it).openAssistantSettings(it)) }
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
                val row = device.wait(Until.findObject(By.textContains("Default digital assistant app")), 5000)
                assertNotNull(row); row.click()
                assertTrue(device.wait(Until.hasObject(By.text("Google")), 5000))
            }
            picker()
            try {
                device.findObject(By.text("EZCH Launcher")).click()
                device.wait(Until.findObject(By.res("android", "button1")), 1500)?.click()
                assertTrue("ROLE_ASSISTANT was not assigned through system UI", waitRole(true))
                device.pressBack()
                device.pressKeyCode(KeyEvent.KEYCODE_ASSIST)
                assertTrue(device.wait(Until.hasObject(By.text("EVA · экспериментальная версия")), 5000))
                assertTrue(AiPreferences.store(context).contains("session_seen"))
                val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "ai-system-session.png").outputStream().use {
                    shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                shot.recycle()
                device.pressBack()
            } finally {
                picker()
                device.findObject(By.text("Google")).click()
                device.wait(Until.findObject(By.res("android", "button1")), 1500)?.click()
                assertTrue("Original Google role was not restored", waitRole(false))
                device.pressBack(); device.pressBack()
            }
        }
    }
}
