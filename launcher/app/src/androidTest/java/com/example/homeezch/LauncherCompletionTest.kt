package com.example.homeezch

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class LauncherCompletionTest {
    companion object { @JvmStatic @BeforeClass fun setup() = prepareLauncherTest() }
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var original: String? = null
    @Before fun resetWorkspace() {
        val prefs = ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
        original = prefs.getString("workspace_v1", null)
        WorkspacePrefs.save(ui.activity, Workspace(listOf(AppShelf("main", "Приложения", listOf("ezch-store")), AppShelf("games", "Игры", emptyList()))))
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Магазин EZCH").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() {
        ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit().putString("workspace_v1", original).commit()
    }
    @Test fun removingDesktopCardSurvivesRecreationAndCanBeAddedBack() {
        ui.onNodeWithText("Магазин EZCH").performKeyInput {
            keyDown(Key.DirectionCenter); advanceEventTime(650); keyUp(Key.DirectionCenter)
        }
        ui.onNodeWithText("Удалить с рабочего стола").performClick()
        ui.waitUntil(10000) { "ezch-store" in WorkspacePrefs.load(ui.activity).removedApps }
        ui.onNodeWithText("Магазин EZCH").assertDoesNotExist()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Все приложения").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Магазин EZCH").assertDoesNotExist()
        ui.onAllNodesWithText("Все приложения")[0].performClick()
        ui.onNodeWithText("Поиск приложений").performClick().performTextInput("Магазин")
        ui.onNodeWithText("Добавить на рабочий стол").performClick()
        ui.waitUntil(10000) { "ezch-store" !in WorkspacePrefs.load(ui.activity).removedApps }
        ui.onNodeWithText("Закрыть").performClick()
        ui.onNodeWithTag("app-row-main").performScrollToNode(hasText("Магазин EZCH"))
        ui.onNodeWithText("Магазин EZCH").assertExists()
    }
    @Test fun appCanBePlacedInNamedRowThroughTheCatalogAndPersists() {
        ui.onAllNodesWithText("Все приложения")[0].performClick()
        ui.onNodeWithText("Поиск приложений").performClick().performTextInput("Магазин")
        ui.onNodeWithText("Выбрать строку").performClick()
        ui.onNode(hasText("Игры") and hasClickAction()).performClick()
        ui.waitUntil(10000) { WorkspacePrefs.load(ui.activity).rows.first { it.id == "games" }.appKeys == listOf("ezch-store") }
        ui.onNodeWithText("Закрыть").performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Магазин EZCH").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, WorkspacePrefs.load(ui.activity).rows.flatMap { it.appKeys }.count { it == "ezch-store" })
    }
    @Test fun legacyWorkspaceWithoutRemovalFieldLoadsWithoutResettingOrder() {
        val prefs = ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("workspace_v1", """{"rows":[{"id":"old","title":"Кино","apps":["b","a"]}],"hiddenApps":["a"],"sourceOrder":["hdmi"],"hiddenSources":[]}""").commit()
        val loaded = WorkspacePrefs.load(ui.activity)
        assertEquals(listOf("b", "a"), loaded.rows.first().appKeys)
        assertEquals(listOf("a"), loaded.hiddenApps)
        assertEquals(listOf("hdmi"), loaded.sourceOrder)
        assertTrue(loaded.removedApps.isEmpty())
        assertTrue(WorkspacePrefs.save(ui.activity, loaded.renameRow("old", "Фильмы").removeApp("b")))
        assertEquals(listOf("b"), WorkspacePrefs.load(ui.activity).removedApps)
        assertEquals("Фильмы", WorkspacePrefs.load(ui.activity).rows.first().title)
    }
}
