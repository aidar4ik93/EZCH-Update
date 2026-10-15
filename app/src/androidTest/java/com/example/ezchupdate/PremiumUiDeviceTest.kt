package com.example.ezchupdate

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test

class PremiumUiDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun settingsAndRussianModesPersist() {
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("EZCH UPDATE")), 15_000))
        device.wait(Until.findObject(By.text("Настройки")), 10_000)!!.click()
        val lite = device.wait(Until.findObject(By.text("Lite")), 10_000)
        assertNotNull(lite); lite.click()
        assertEquals("Lite", UpdateSettings(context).appearance)
        device.findObject(By.text("Premium"))!!.click()
        assertEquals("Premium", UpdateSettings(context).appearance)
        assertTrue(device.hasObject(By.text("Информация об устройстве")))
        device.pressBack()
    }
    @Test fun dpadDetailsBackAndRapidNavigationStayInCatalog() {
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Твой центр приложений •")), 15_000))
        device.waitForIdle()
        val card = device.wait(Until.findObject(By.text("Zona")), 5000)
        // Zona may be below the fold; use the always visible first catalog card.
        val first = device.findObject(By.text("EZCH Update")) ?: card
        assertNotNull(first); first.click()
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Источник:")), 5000))
        device.pressBack()
        repeat(20) { device.pressDPadRight(); device.pressDPadDown(); device.pressDPadLeft(); device.pressDPadUp() }
        assertEquals(context.packageName, device.currentPackageName)
        assertTrue(device.hasObject(By.text("EZCH UPDATE")))
    }
}
