package com.example.homeezch

import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvaDiagnosticsTest {
    @Test fun diagnosticsOpensWithoutRoleOrMicrophonePrompt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(EvaDiagnosticsActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Ai EvA — проверка возможностей ТВ")), 5000))
            assertTrue(device.hasObject(By.text("Речь ещё не проверена.")))
            assertTrue(device.hasObject(By.textStartsWith("Android:")))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun systemVoiceServicesRequireSignaturePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (type in listOf(EvaVoiceService::class.java, EvaSessionService::class.java)) {
            val info = context.packageManager.getServiceInfo(ComponentName(context, type), 0)
            assertTrue(info.exported)
            assertEquals("android.permission.BIND_VOICE_INTERACTION", info.permission)
        }
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, EvaDiagnosticsActivity::class.java), 0
        )
        assertTrue(!activity.exported)
    }
}
