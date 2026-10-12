package com.example.homeezch

import android.content.ComponentName
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AllAppsDialogTest {
    @get:Rule val ui = createComposeRule()

    @Test fun hiddenAppKeepsItsIconAndCanStillBeLaunched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = LaunchApp("Проводник", ComponentName(context, FileBrowserActivity::class.java))
        var selected: LaunchApp? = null
        ui.setContent {
            MaterialTheme {
                AllAppsDialog(listOf(app), listOf(context.packageName), false, {}, { selected = it }, {})
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Проводник").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Проводник").assertExists()
        ui.onNodeWithText("Вернуть на рабочий стол").assertExists()
        ui.onNodeWithText("Проводник").performClick()
        ui.runOnIdle { assertEquals(app, selected) }
    }

    @Test fun restoringHiddenAppUpdatesActionWithoutClosingGrid() {
        var hidden by mutableStateOf(listOf("ezch-store"))
        ui.setContent {
            MaterialTheme {
                AllAppsDialog(listOf(LaunchApp("EZCH Store", null)), hidden, false, {}, {}, { hidden = emptyList() })
            }
        }
        ui.onNodeWithText("Вернуть на рабочий стол").performClick()
        ui.onNodeWithText("Скрыть с рабочего стола").assertExists()
        ui.onNodeWithContentDescription("EZCH Store").assertExists()
    }
}
