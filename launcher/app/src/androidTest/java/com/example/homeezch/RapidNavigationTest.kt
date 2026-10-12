package com.example.homeezch

import android.content.ComponentName
import android.view.View
import android.view.KeyEvent
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/** Regression: multiple fresh presses before a frame must not skip unseen cards. */
@OptIn(ExperimentalTestApi::class)
class RapidNavigationTest {
    @get:Rule val ui = createComposeRule()

    @Test fun burstCannotAdvancePastUnconfirmedFocus() {
        lateinit var view: View
        val apps = (0 until 150).map {
            LaunchApp("Stress $it", ComponentName("stress.test.$it", "Main"))
        }
        ui.setContent {
            view = LocalView.current
            MaterialTheme {
                HomeScreen(apps, 0, false, {}, {}, preferredFocus = "stress.test.0", weatherReady = false)
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("Stress 0").assertIsFocused()
        ui.mainClock.autoAdvance = false
        try {
            // Dispatch in one UI-thread task: performKeyInput advances the test
            // clock between presses, so it does not model a burst before a frame.
            ui.runOnUiThread {
                val now = SystemClock.uptimeMillis()
                repeat(100) {
                    view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
                    view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
                }
            }
            ui.mainClock.advanceTimeBy(500)
            ui.waitForIdle()
            ui.onNodeWithText("Stress 1").assertIsFocused()
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        ui.waitForIdle()
        ui.onNodeWithText("Stress 0").assertIsFocused()
    }
}
