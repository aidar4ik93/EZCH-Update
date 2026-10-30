package com.example.homeezch

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import org.junit.*
import org.junit.Assert.*

class CompanionCompatibilityTest {
    companion object { @JvmStatic @BeforeClass fun prepare() = prepareLauncherTest() }
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun currentCompanionCanBeOpenedAndBackReturnsToLauncher() {
        val companion = "com.example.ezchupdate.tvtest"
        val context = ui.activity
        org.junit.Assume.assumeTrue(runCatching { context.packageManager.getPackageInfo(companion, 0) }.isSuccess)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.runOnUiThread { assertTrue(openLegacyUpdater(context)) }
        assertTrue(device.wait(Until.hasObject(By.pkg(companion)), 15000))
        val hierarchy = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(hierarchy)
        assertTrue(hierarchy.size() > 1000)
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 15000))
    }
}
