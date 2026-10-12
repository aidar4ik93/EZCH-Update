package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises real activity routes and captures the actual launcher, beyond the isolated UI fixture. */
@OptIn(ExperimentalTestApi::class)
class PanelWorkflowTest {
    companion object {
        @JvmStatic @BeforeClass fun setupAlreadyReviewed() = prepareLauncherTest()
    }
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun desktopReady() {
        ui.waitUntil(15000) { ui.onAllNodesWithText("Магазин EZCH").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun open(tv: Boolean) {
        val trigger = ui.onNodeWithTag(if (tv) "sources-launcher" else "settings-launcher")
        if (tv) trigger.performScrollTo()
        trigger.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        trigger.performKeyInput { pressKey(Key.DirectionCenter) }
        ui.waitForIdle()
        if (!tv) {
            ui.waitUntil(5000) { ui.onAllNodesWithTag("quick-Настройки EZCH").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("quick-Настройки EZCH").performClick(); ui.waitForIdle()
        } else ui.waitUntil(5000) { ui.onAllNodesWithTag("source-external-storage").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(ui.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
    @Test fun screenshotsShowActualDesktopAndBothIndependentPanels() {
        capture("desktop-026")
        open(true); capture("sources-strip-026")
        device.pressBack(); ui.waitForIdle()
        ui.onNodeWithTag("sources-launcher").assertIsFocused()
        open(false); capture("settings-panel-026")
        device.pressBack(); ui.waitForIdle()
        ui.onNodeWithTag("settings-launcher").assertIsFocused()
    }
    @Test fun bluetoothWifiImageAndSoundRoutesReturnToTheirFocusedItem() {
        for (tag in listOf("settings-bluetooth", "settings-wifi", "settings-display", "settings-sound")) {
            open(false)
            val item = ui.onNodeWithTag(tag)
            item.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            item.performKeyInput { pressKey(Key.DirectionCenter) }
            ui.waitUntil(10000) { device.currentPackageName in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings") }
            device.pressBack()
            ui.waitUntil(10000) { device.currentPackageName == ui.activity.packageName }
            ui.waitForIdle()
            ui.onNodeWithTag("settings-panel").assertIsDisplayed()
            item.assertIsFocused()
            device.pressBack(); ui.waitForIdle()
            ui.onNodeWithTag("settings-launcher").assertIsFocused()
        }
    }
    @Test fun homeIntentClosesEitherPanelAndReturnsToDesktopStart() {
        for (tv in listOf(false, true)) {
            open(tv)
            ui.runOnUiThread {
                ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            ui.waitForIdle()
            ui.onNodeWithTag("sources-panel").assertDoesNotExist()
            ui.onNodeWithTag("settings-panel").assertDoesNotExist()
            ui.onNodeWithText("Магазин EZCH").assertIsFocused()
        }
    }
    @Test fun usbFileManagerActionOpensExistingActivityAndBackReturnsToPanel() {
        open(true)
        ui.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("source-external-storage"))
        ui.onNodeWithTag("source-external-storage").performClick()
        ui.waitUntil(10000) { ui.activity.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED }
        device.pressBack()
        ui.waitUntil(10000) { ui.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        ui.waitForIdle()
        ui.onNodeWithTag("source-external-storage").assertIsDisplayed()
        device.pressBack(); ui.waitForIdle()
        ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }
}

internal fun prepareLauncherTest() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // A real TV has a different installed-app inventory. Stable test focus must
    // not depend on alphabetical labels or modify the owner's release workspace.
    check(context.packageName.endsWith(".debug"))
    WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Приложения",
        listOf("ezch-store", context.packageName)))))
    context.getSharedPreferences("home_setup", Context.MODE_PRIVATE).edit()
        .putBoolean("setup_reviewed_v24", true).commit()
    context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit()
        .putBoolean("weather_auto", false).putBoolean("weather_prompt_v09", true).commit()
}
