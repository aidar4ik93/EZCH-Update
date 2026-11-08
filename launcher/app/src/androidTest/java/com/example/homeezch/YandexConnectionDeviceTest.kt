package com.example.homeezch

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.homeezch.ai.SmartHomeActivity
import org.junit.Assert.*
import org.junit.Test

class YandexConnectionDeviceTest {
    @Test fun ordinaryUserGetsQrConnectionInsteadOfCredentialFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch<SmartHomeActivity>(Intent(context, SmartHomeActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.textContains("Подключения")), 15000))
            device.findObject(By.textContains("Подключения")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Подключить по QR-коду")), 10000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "yandex-connection.png"))
            listOf("Client ID", "Client Secret", "OAuth-токен", "Вставить токен", "Приложение OAuth").forEach { text ->
                assertFalse("Manual credential UI must not be exposed: $text", device.hasObject(By.text(text)))
            }
            device.findObject(By.text("Подключить по QR-коду")).click()
            // Cancel if the request is still in flight; a quick network failure is also recoverable.
            device.wait(Until.hasObject(By.text("Отмена")), 3000)
            runCatching { device.findObject(By.text("Отмена"))?.click() }
            assertTrue(device.wait(Until.hasObject(By.text("Подключить по QR-коду")), 15000))
            assertFalse(device.hasObject(By.text("OAuth-токен")))
        }
    }
}
