package com.example.homeezch

import android.content.Context
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class HomeResetTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun dismissSetup() {
        ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("weather_prompt_v09", true).putBoolean("weather_auto", false).commit()
        if (ui.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("Позже").performClick()
        ui.waitForIdle()
    }
    private fun longOk() {
        ui.onNodeWithText("EZCH Store").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter); advanceEventTime(650); keyUp(Key.DirectionCenter)
        }
    }
    @Test fun homeClosesMenuAndReturnsFocusToStart() {
        ui.waitUntil(10000) { ui.onAllNodesWithText("EZCH Store").fetchSemanticsNodes().isNotEmpty() }
        longOk()
        ui.onNodeWithText("Скрыть с рабочего стола").assertExists()
        ui.runOnUiThread {
            ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Скрыть с рабочего стола").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("EZCH Store").assertIsFocused()
        longOk()
        ui.onNodeWithText("Переместить приложение").assertExists()
    }

    @Test fun repeatedMoveThenHoldAllowsHidingWithRemote() {
        val prefs = ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
        val original = prefs.getString("workspace_v1", null)
        try {
            ui.waitUntil(10000) { ui.onAllNodesWithText("EZCH Store").fetchSemanticsNodes().isNotEmpty() }
            repeat(2) {
                longOk()
                ui.onNodeWithText("Переместить приложение").performClick()
                ui.onNodeWithText("EZCH Store").performKeyInput {
                    pressKey(Key.DirectionRight)
                    pressKey(Key.DirectionCenter)
                }
                ui.waitUntil(10000) {
                    ui.onAllNodesWithText("Сохраняем…", substring = true).fetchSemanticsNodes().isEmpty() &&
                        ui.onAllNodesWithText("OK сохранить", substring = true).fetchSemanticsNodes().isEmpty()
                }
                ui.onNodeWithText("EZCH Store").assertIsFocused()
            }
            longOk()
            ui.onNodeWithText("Переместить приложение").assertExists()
            ui.onNodeWithText("Скрыть с рабочего стола").performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionCenter)
            }
            ui.waitUntil(10000) { "ezch-store" in WorkspacePrefs.load(ui.activity).hiddenApps }
            ui.onAllNodesWithText("EZCH Store").assertCountEquals(0)
        } finally { prefs.edit().putString("workspace_v1", original).commit() }
    }

    @Test fun repeatAndReleaseFromOpeningHoldCannotSelectMove() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int, time: Long) = instrumentation.sendKeySync(
            android.view.KeyEvent(now, time, action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat))
        ui.waitUntil(10000) { ui.onAllNodesWithText("EZCH Store").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("EZCH Store").assertIsFocused()
        send(android.view.KeyEvent.ACTION_DOWN, 0, now)
        send(android.view.KeyEvent.ACTION_DOWN, 1, now + 650)
        ui.waitUntil(10000) { ui.onAllNodesWithText("Скрыть с рабочего стола").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        send(android.view.KeyEvent.ACTION_DOWN, 2, now + 700)
        send(android.view.KeyEvent.ACTION_UP, 0, now + 800)
        ui.onNodeWithText("Переместить приложение").assertExists()
        ui.onNodeWithText("Скрыть с рабочего стола").assertExists()
        ui.onAllNodesWithText("OK сохранить", substring = true).assertCountEquals(0)
    }
}
