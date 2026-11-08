package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.homeezch.ai.Action
import com.example.homeezch.ai.power.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No test invokes sleep/reboot or activates a service/admin. Dedicated emulator only. */
@RunWith(AndroidJUnit4::class)
class EvaPowerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun timerRegistersCancelsAndRejectsStaleDelivery() {
        try {
            assertTrue(SleepTimer.set(context, 1440).accepted)
            assertEquals(1440L, SleepTimer.minutesLeft(context))
            val p = context.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
            val old = p.getString("timer_token", null)
            assertFalse(SleepTimer.consume(context, old)) // too early
            assertTrue(SleepTimer.set(context, 1439).accepted)
            assertFalse(SleepTimer.consume(context, old)) // replaced
            SleepTimer.cancel(context)
            assertNull(SleepTimer.minutesLeft(context))
            assertFalse(SleepTimer.consume(context, old))
        } finally { SleepTimer.cancel(context) }
    }
    @Test fun dueAlarmIsConsumedOnlyOnceWithoutExecutingPower() {
        try {
            assertTrue(SleepTimer.set(context, 1440).accepted)
            val p = context.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
            val token = p.getString("timer_token", null)
            p.edit().putLong("timer_at", SystemClock.elapsedRealtime() - 1).commit()
            assertTrue(SleepTimer.consume(context, token))
            assertFalse(SleepTimer.consume(context, token))
        } finally { SleepTimer.cancel(context) }
    }
    @Test fun irFailureCannotSleepTheBox() = runBlocking {
        val p = context.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
        try {
            p.edit().putBoolean("ir_mode", true).remove("ir_device").commit()
            val controller = PowerController(context)
            assertFalse(controller.execute(Action.Power("timer", 0)).accepted)
            assertFalse(controller.execute(Action.Power("shutdown")).accepted)
            assertFalse(controller.execute(Action.Power("wake")).accepted)
        } finally { p.edit().putBoolean("ir_mode", false).commit() }
    }
    @Test fun rejectsUntrustedPowerMenuWindows() {
        assertTrue(EvaPowerAccessibility.trustedPackage("com.android.systemui"))
        assertFalse(EvaPowerAccessibility.trustedPackage(context.packageName))
        assertFalse(EvaPowerAccessibility.trustedPackage("com.google.android.youtube.tv"))
        assertFalse(EvaPowerAccessibility.trustedPackage(null))
    }
    @Test fun cancellingTheRealVoicePanelDiscardsPowerConfirmation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val panel = com.example.homeezch.ai.VoicePanel(context, close = {})
            try {
                val core = com.example.homeezch.ai.EvaBrain.core(context)
                assertTrue(core.handle("перезагрузи телевизор") is com.example.homeezch.ai.EvaReply.Confirm)
                panel.cancel()
                assertFalse(core.hasPowerConfirmation())
                assertFalse(core.handle("да") is com.example.homeezch.ai.EvaReply.Execute)
            } finally { panel.destroy() }
        }
    }
    @Test fun settingsStartWithoutEnablingCapabilities() {
        val i = InstrumentationRegistry.getInstrumentation()
        val before = PowerController(context).capabilities()
        val activity = i.startActivitySync(Intent(context, PowerSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        i.waitForIdleSync()
        assertEquals(before, PowerController(context).capabilities())
        assertTrue(androidx.test.uiautomator.UiDevice.getInstance(i).takeScreenshot(java.io.File(context.getExternalFilesDir(null), "eva-power-settings.png")))
        i.runOnMainSync { activity.finish() }
    }
    @Test fun timerAcrossProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("power_phase")
        org.junit.Assume.assumeTrue("Run separately with power_phase=stage then verify", phase != null)
        val p = context.getSharedPreferences("eva_power", Context.MODE_PRIVATE)
        if (phase == "stage") {
            assertTrue(SleepTimer.set(context, 1440).accepted)
            p.edit().putInt("test_stage_pid", android.os.Process.myPid()).commit()
        } else {
            try {
                assertNotEquals(p.getInt("test_stage_pid", -1), android.os.Process.myPid())
                assertNotNull(SleepTimer.minutesLeft(context))
                assertTrue(SleepTimer.minutesLeft(context)!! in 1438L..1440L)
            } finally { SleepTimer.cancel(context); p.edit().remove("test_stage_pid").commit() }
        }
    }
}
