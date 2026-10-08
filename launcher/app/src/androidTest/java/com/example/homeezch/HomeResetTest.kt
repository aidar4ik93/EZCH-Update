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
        ui.onNodeWithText("Переместить").assertExists()
    }
}
