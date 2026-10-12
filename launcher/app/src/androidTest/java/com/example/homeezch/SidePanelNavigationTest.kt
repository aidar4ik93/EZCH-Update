package com.example.homeezch
import android.content.ComponentName
import android.content.Context
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/** Updated acceptance coverage: inline inputs, serial reveal, opt-in manual sources. */
@OptIn(ExperimentalTestApi::class)
class SidePanelNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: Workspace
    private var manualBefore: String? = null
    private val refresh = mutableIntStateOf(0)
    private val apps = (0 until 30).map { LaunchApp("Panel stress $it", ComponentName("panels.stress.$it", "Main")) }
    @Before fun prepare() {
        original = WorkspacePrefs.load(context)
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Приложения", emptyList()))))
        val p = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
        manualBefore = p.getString("manual", null); p.edit().remove("manual").commit()
    }
    @After fun restore() {
        WorkspacePrefs.save(context, original)
        context.getSharedPreferences("source_settings", Context.MODE_PRIVATE).edit().putString("manual", manualBefore).commit()
    }
    private fun desktop(manual: Boolean = false) {
        if (manual) context.getSharedPreferences("source_settings", Context.MODE_PRIVATE).edit().putString("manual",
            """[{"id":"manual-hdmi-test","label":"HDMI 1 · вручную","kind":"HDMI"}]""").commit()
        ui.setContent { MaterialTheme { HomeScreen(apps, refresh.intValue, false, {}, {}, weatherReady = false) } }
        ui.waitForIdle()
    }
    private fun tv() {
        ui.onNodeWithTag("sources-launcher").performScrollTo().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("source-external-storage").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun settings() {
        ui.onNodeWithTag("settings-launcher").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("quick-Настройки EZCH").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("quick-Настройки EZCH").performClick(); ui.waitForIdle()
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
    @Test fun noFabricatedPortsAndNoLargeSourceWindow() {
        desktop(); tv()
        ui.onNodeWithTag("sources-panel").assertDoesNotExist()
        ui.onNodeWithText("HDMI 1").assertDoesNotExist()
        ui.onNodeWithText("AV").assertDoesNotExist()
        ui.onNodeWithTag("source-external-storage").assertIsDisplayed()
        assertFalse(scanTvSources(context).entries.any { it.id.startsWith("manual-") })
        assertFalse(scanTvSources(context).entries.first { it.kind == TvSourceKind.USB }.connected)
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("source-external-storage").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun oneHundredOpenCloseCyclesReturnFocusWithoutStaleCards() {
        desktop()
        repeat(100) {
            tv(); back()
            ui.onNodeWithTag("sources-launcher").assertIsFocused()
            ui.waitUntil(5000) { ui.onAllNodesWithTag("source-external-storage").fetchSemanticsNodes().isEmpty() }
        }
    }
    @Test fun rapidHorizontalKeysDoNotEscapeInlineSourceRow() {
        desktop(true); tv()
        val source = ui.onNodeWithTag("source-manual-hdmi-test")
        source.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        source.performKeyInput { repeat(500) { pressKey(if (it % 2 == 0) Key.DirectionRight else Key.DirectionLeft) } }
        ui.waitForIdle()
        ui.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("sources-list"))).assertCountEquals(1)
        back(); ui.onNodeWithTag("sources-launcher").assertIsFocused()
    }
    @Test fun moveCancellationAndHiddenSourceRestorationPersist() {
        desktop(true); tv()
        val source = ui.onNodeWithTag("source-manual-hdmi-test")
        source.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        source.performKeyInput { pressKey(Key.Menu) }
        ui.onNodeWithText("Переместить разъём").performClick()
        val before = WorkspacePrefs.load(context)
        source.performKeyInput { pressKey(Key.DirectionRight) }; back()
        assertEquals(before, WorkspacePrefs.load(context))
        source.performKeyInput { pressKey(Key.Menu) }
        ui.onNodeWithText("Скрыть с рабочего стола").performClick()
        ui.waitUntil(5000) { "manual-hdmi-test" in WorkspacePrefs.load(context).hiddenSources }
        ui.onNodeWithTag("source-manual-hdmi-test").assertDoesNotExist()
        back(); settings()
        ui.onNodeWithText("Восстановить скрытые источники").performScrollTo().performClick()
        ui.waitUntil(5000) { WorkspacePrefs.load(context).hiddenSources.isEmpty() }
        back(); tv(); source.assertIsDisplayed()
    }
    @Test fun settingsUseCompactShortcutsBeforeDetailedPanel() {
        desktop()
        ui.onNodeWithTag("settings-launcher").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("quick-Настройки EZCH").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("quick-Wi-Fi").assertIsDisplayed()
        ui.onNodeWithTag("quick-Bluetooth").assertIsDisplayed()
        ui.onNodeWithTag("settings-panel").assertDoesNotExist()
        ui.onNodeWithTag("quick-Настройки EZCH").performClick()
        ui.onNodeWithTag("settings-panel").assertIsDisplayed()
        back(); ui.onNodeWithTag("settings-launcher").assertIsFocused()
    }
    @Test fun refreshedSourceListUsesExplicitManualEntriesOnly() {
        desktop(); tv()
        SourceSettings.add(context, TvSourceKind.HDMI)
        ui.runOnIdle { refresh.intValue++ }
        ui.waitUntil(5000) { ui.onAllNodesWithText("HDMI 1 · вручную").fetchSemanticsNodes().isNotEmpty() }
        val manual = scanTvSources(context).entries.single { it.id.startsWith("manual-") }
        assertFalse(manual.connected); assertTrue(manual.detail!!.contains("неизвестен"))
    }
}
