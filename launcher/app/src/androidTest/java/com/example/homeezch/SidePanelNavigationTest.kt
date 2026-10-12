package com.example.homeezch

import android.content.ComponentName
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real remote navigation through both modal windows, including long repeated bursts. */
@OptIn(ExperimentalTestApi::class)
class SidePanelNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: Workspace
    private val refresh = mutableIntStateOf(0)
    private val apps = (0 until 100).map {
        LaunchApp("Panel stress $it", ComponentName("panels.stress.$it", "Main"))
    }
    @Before fun prepare() {
        original = WorkspacePrefs.load(context)
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Приложения", emptyList()))))
    }
    @After fun restore() { WorkspacePrefs.save(context, original) }
    private fun desktop() {
        ui.setContent { MaterialTheme { HomeScreen(apps, refresh.intValue, false, {}, {}, weatherReady = false) } }
        ui.waitForIdle()
    }
    private fun open(tv: Boolean) {
        val trigger = ui.onNodeWithTag(if (tv) "sources-launcher" else "settings-launcher")
        if (tv) trigger.performScrollTo()
        trigger.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        trigger.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        ui.waitForIdle()
        ui.onNodeWithTag(if (tv) "sources-panel" else "settings-panel").assertIsDisplayed()
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
    }
    private fun panelFocus(tv: Boolean) = isFocused() and hasAnyAncestor(hasTestTag(if (tv) "sources-panel" else "settings-panel"))

    @Test fun desktopHasOneSettingsIconAndTvTriggerWithoutOldHeader() {
        desktop()
        ui.onAllNodesWithTag("settings-launcher").assertCountEquals(1)
        ui.onNodeWithContentDescription("Открыть настройки").assertExists()
        ui.onNodeWithText("Bluetooth").assertDoesNotExist()
        ui.onNodeWithText("EZCH").assertDoesNotExist()
        ui.onNodeWithText("Launcher").assertDoesNotExist()
        ui.onNodeWithTag("sources-launcher").performScrollTo().assertIsDisplayed()
    }

    @Test fun sourcesEnterAtLeftAndBackRestoresTvFocus() {
        desktop(); open(true)
        val overlay = ui.onNodeWithTag("sources-panel-overlay").fetchSemanticsNode().boundsInRoot
        val panel = ui.onNodeWithTag("sources-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(overlay.left, panel.left, 2f)
        assertTrue(panel.width < overlay.width * .9f)
        ui.onNodeWithTag("settings-panel").assertDoesNotExist()
        ui.onNodeWithText("Bluetooth").assertDoesNotExist()
        for (tag in listOf("source-manual-hdmi-1", "source-external-storage", "source-manual-av")) {
            ui.onNodeWithTag("sources-list").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag).assertIsDisplayed()
        }
        back()
        ui.onNodeWithTag("sources-panel").assertDoesNotExist()
        ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun settingsEnterAtRightContainSystemActionsAndReturnFocus() {
        desktop(); open(false)
        val overlay = ui.onNodeWithTag("settings-panel-overlay").fetchSemanticsNode().boundsInRoot
        val panel = ui.onNodeWithTag("settings-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(overlay.right, panel.right, 2f)
        assertTrue(panel.width < overlay.width * .9f)
        ui.onNodeWithTag("sources-panel").assertDoesNotExist()
        for (tag in listOf("settings-bluetooth", "settings-wifi", "settings-display", "settings-sound")) {
            ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        back()
        ui.onNodeWithTag("settings-launcher").assertIsFocused()
    }

    @Test fun fifteenHundredDirectionalPressesCannotEscapeModalFocus() {
        desktop()
        for (tv in listOf(false, true)) {
            open(tv)
            val panel = ui.onNodeWithTag(if (tv) "sources-panel" else "settings-panel")
            repeat(125) {
                panel.performKeyInput {
                    pressKey(Key.DirectionDown); pressKey(Key.DirectionRight); pressKey(Key.DirectionLeft)
                    pressKey(Key.DirectionUp); pressKey(Key.DirectionUp); pressKey(Key.DirectionDown)
                }
                ui.onAllNodes(panelFocus(tv)).assertCountEquals(1)
            }
            back()
            ui.onNodeWithTag(if (tv) "sources-launcher" else "settings-launcher").assertIsFocused()
        }
    }

    @Test fun oneHundredAlternatingOpenBackCyclesPreserveWorkspaceAndFocus() {
        desktop()
        val before = WorkspacePrefs.load(context)
        repeat(100) { index ->
            val tv = index % 2 == 0
            open(tv)
            ui.onAllNodes(panelFocus(tv)).assertCountEquals(1)
            back()
            ui.onNodeWithTag("sources-panel").assertDoesNotExist()
            ui.onNodeWithTag("settings-panel").assertDoesNotExist()
            ui.onNodeWithTag(if (tv) "sources-launcher" else "settings-launcher").assertIsFocused()
        }
        assertEquals(before, WorkspacePrefs.load(context))
    }

    @Test fun usbExpandsFileActionInsideSourcesPanel() {
        desktop(); open(true)
        ui.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("source-external-storage"))
        val usb = ui.onNodeWithTag("source-external-storage")
        usb.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        usb.performKeyInput { pressKey(Key.DirectionCenter) }
        ui.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("sources-usb-files"))
        ui.onNodeWithTag("sources-usb-files").assertIsDisplayed()
        ui.onNodeWithTag("sources-panel").assertIsDisplayed()
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun moveSourceThenBackCancelsEditBeforeClosingPanel() {
        desktop(); open(true)
        val before = WorkspacePrefs.load(context)
        val source = ui.onNodeWithTag("source-manual-hdmi-1")
        source.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        source.performKeyInput { pressKey(Key.Menu) }
        ui.onNodeWithText("Переместить разъём").performClick()
        source.performKeyInput { pressKey(Key.DirectionRight) }
        ui.onNodeWithText("← → переместить  ·  OK сохранить  ·  Back отменить").assertExists()
        back()
        ui.onNodeWithTag("sources-panel").assertIsDisplayed()
        assertEquals(before, WorkspacePrefs.load(context))
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun hideAndRestoreSourceKeepWorkingInsideLeftPanel() {
        desktop(); open(true)
        val source = ui.onNodeWithTag("source-manual-hdmi-1")
        source.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        source.performKeyInput { pressKey(Key.Menu) }
        ui.onNodeWithText("Скрыть с рабочего стола").performClick()
        ui.waitUntil(5000) { "manual-hdmi-1" in WorkspacePrefs.load(context).hiddenSources }
        ui.onNodeWithTag("source-manual-hdmi-1").assertDoesNotExist()
        ui.onNodeWithTag("sources-list").performScrollToNode(hasText("Восстановить скрытые источники"))
        ui.onNodeWithText("Восстановить скрытые источники").performClick()
        ui.waitUntil(5000) { WorkspacePrefs.load(context).hiddenSources.isEmpty() }
        ui.onNodeWithTag("sources-list").performScrollToNode(hasTestTag("source-manual-hdmi-1"))
        ui.onNodeWithTag("source-manual-hdmi-1").assertIsDisplayed()
        back()
    }

    @Test fun refreshWhilePanelIsOpenKeepsFocusAndPanelAlive() {
        desktop(); open(true)
        repeat(50) {
            ui.runOnIdle { refresh.intValue++ }
            ui.waitForIdle()
            ui.onNodeWithTag("sources-panel").assertIsDisplayed()
            ui.onAllNodes(panelFocus(true)).assertCountEquals(1)
        }
        back()
        ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun hiddenAllSourcesStillOffersCloseAndRestoreWithoutLosingFocus() {
        val all = scanTvSources(context).entries.map { it.id }
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Приложения", emptyList())), hiddenSources = all))
        desktop(); open(true)
        ui.onNodeWithTag("sources-panel-close").assertIsFocused()
        ui.onNodeWithText("Восстановить скрытые источники").performClick()
        ui.waitUntil(5000) { WorkspacePrefs.load(context).hiddenSources.isEmpty() }
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun hidingLastVisibleSourceMovesFocusToCloseButton() {
        val all = scanTvSources(context).entries.map { it.id }
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Приложения", emptyList())),
            hiddenSources = all.filterNot { it == "manual-hdmi-1" }))
        desktop(); open(true)
        val source = ui.onNodeWithTag("source-manual-hdmi-1")
        source.assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        ui.onNodeWithText("Скрыть с рабочего стола").performClick()
        ui.waitUntil(5000) { "manual-hdmi-1" in WorkspacePrefs.load(context).hiddenSources }
        ui.waitForIdle()
        ui.onNodeWithTag("sources-panel-close").assertIsFocused()
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }

    @Test fun nestedAllAppsDialogBackReturnsToSettingsPanel() {
        desktop(); open(false)
        val item = ui.onNodeWithText("Все приложения / восстановить скрытые")
        item.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        item.performKeyInput { pressKey(Key.DirectionCenter) }
        ui.onNodeWithText("Все приложения").assertExists()
        back()
        ui.onNodeWithTag("settings-panel").assertIsDisplayed()
        ui.onAllNodes(panelFocus(false)).assertCountEquals(1)
        back(); ui.onNodeWithTag("settings-launcher").assertIsFocused()
    }
}
