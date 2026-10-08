package com.example.homeezch

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** Requires a TV device/emulator. This is navigation smoke coverage, not a frame benchmark. */
@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        ui.waitForIdle()
    }
    @Before fun dismissInitialSetup() {
        ui.activity.getSharedPreferences("ezch_launcher_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("weather_auto", false).putBoolean("weather_prompt_v09", true).commit()
        ui.waitForIdle()
        if (ui.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithText("Позже").performClick()
            ui.waitForIdle()
        }
    }
    @Test fun repeatedNavigationKeepsDesktopAlive() {
        ui.waitUntil(10000) { ui.onAllNodesWithText("EZCH Store").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("EZCH Store").assertIsFocused()
        repeat(25) {
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_UP)
        }
        ui.onNodeWithText("Разъёмы").assertExists()
        ui.runOnUiThread { assertFalse(ui.activity.isFinishing || ui.activity.isDestroyed) }
    }
}
