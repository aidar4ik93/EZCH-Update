package com.example.homeezch

import android.content.ComponentName
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on TV: verifies shelf boundaries with the actual composable and remote events. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HorizontalBoundaryTest {
    @get:Rule val ui = createComposeRule()
    @Test fun bothEndsStayInShelfAndEveryPressMovesOneCard() {
        val apps = (0..19).map { LaunchApp("Navigation $it", ComponentName("navigation.test.$it", "Main")) }
        ui.setContent { MaterialTheme { HomeScreen(apps, 0, false, {}, {}, preferredFocus = "navigation.test.0", weatherReady = false) } }
        ui.waitUntil(10000) { runCatching { ui.onAllNodesWithText("Navigation 0").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        ui.waitForIdle()
        ui.onNodeWithText("Navigation 0").assertIsFocused()
        repeat(30) { ui.onRoot().performKeyInput { pressKey(Key.DirectionLeft) } }
        ui.onNodeWithText("Navigation 0").assertIsFocused()
        for (index in 1..19) {
            ui.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            ui.waitForIdle()
            ui.onNodeWithText("Navigation $index").assertIsFocused()
        }
        ui.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        ui.waitForIdle()
        repeat(30) { ui.onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
        ui.onNodeWithText("Все приложения").assertIsFocused()
    }
}
