package com.example.ezchupdate

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test

class PremiumUiDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun awaitMode(expected: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (UpdateSettings(context).appearance != expected && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertEquals(expected, UpdateSettings(context).appearance)
    }
    @Test fun settingsAndRussianModesPersist() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("EZCH UPDATE")), 15_000))
        device.wait(Until.findObject(By.text("Настройки")), 10_000)!!.click()
        val lite = device.wait(Until.findObject(By.text("Lite")), 10_000)
        assertNotNull(lite); lite.click()
        awaitMode("Lite")
        device.findObject(By.text("Premium"))!!.click()
        awaitMode("Premium")
        repeat(4) {
            if (!device.hasObject(By.text("Информация об устройстве")))
                device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN, 0.6f)
        }
        println("Scroll containers: " + device.findObjects(By.scrollable(true)).map { it.visibleBounds })
        device.takeScreenshot(java.io.File(context.filesDir, "settings-test.png"))
        assertEquals(context.packageName, device.currentPackageName)
        device.pressBack()
    }
    @Test fun dpadDetailsBackAndRapidNavigationStayInCatalog() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        // A fresh Activity restores the catalog's initial card focus, without clearing app data.
        device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("EZCH UPDATE")), 15_000))
        device.waitForIdle()
        assertTrue("Wait for the catalog card, rather than the splash title",
            device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Лайт HD TV"))), 10_000))
        assertTrue("Start with an empty selection",
            device.wait(Until.hasObject(By.text("Установить выбранные (0)")), 5000))
        device.executeShellCommand("input keyevent 23")
        assertTrue("Short OK must select a downloadable card",
            device.wait(Until.hasObject(By.text("✓ Выбрано")), 5000))
        device.waitForIdle()
        device.executeShellCommand("input keyevent 23")
        device.waitForIdle()
        device.dumpWindowHierarchy(java.io.File(context.filesDir, "dpad-after-second.xml"))
        device.takeScreenshot(java.io.File(context.filesDir, "dpad-after-second.png"))
        assertTrue("Second short OK must deselect it",
            device.wait(Until.gone(By.text("✓ Выбрано")), 5000))
        device.executeShellCommand("input keyevent --longpress 23")
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("Источник:")), 5000))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.textStartsWith("Источник:")), 5000))
        repeat(20) { device.pressDPadRight(); device.pressDPadDown(); device.pressDPadLeft(); device.pressDPadUp() }
        assertEquals(context.packageName, device.currentPackageName)
        assertTrue(device.hasObject(By.text("EZCH UPDATE")))
    }
}
