package com.example.homeezch
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
class UpdaterIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun dismissInitialSetup() {
        ui.activity.getSharedPreferences("ezch_launcher_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("weather_auto", false).putBoolean("weather_prompt_v09", true).commit()
        ui.waitForIdle()
        if (ui.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithText("Позже").performClick()
            ui.waitForIdle()
        }
    }
    @Test fun storeOpensUpdaterAndBackReturnsToLauncher() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        ui.onNodeWithText("EZCH Store").performClick()
        val deadline=SystemClock.elapsedRealtime()+30_000
        while(device.currentPackageName != instrumentation.targetContext.packageName && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(500)
        assertEquals(instrumentation.targetContext.packageName,device.currentPackageName)
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Проверить обновления")), 15000))
        device.pressBack()
        val backDeadline=SystemClock.elapsedRealtime()+30_000
        while(device.currentPackageName != instrumentation.targetContext.packageName && SystemClock.elapsedRealtime()<backDeadline) SystemClock.sleep(500)
        assertEquals(instrumentation.targetContext.packageName,device.currentPackageName)
        ui.onNodeWithText("Разъёмы").assertExists()
    }
}
