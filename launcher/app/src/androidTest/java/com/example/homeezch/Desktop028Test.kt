package com.example.homeezch

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class Desktop028Test {
    companion object { @JvmStatic @BeforeClass fun prepare() = prepareLauncherTest() }
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var original = ""
    @Before fun fixture() {
        ui.waitUntil(30000) { ui.onAllNodesWithTag("app-row-main").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        original = DesktopBackup.export(ui.activity)
        ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit()
            .remove("profiles_v1").remove("active_profile").commit()
        WorkspacePrefs.save(ui.activity, Workspace(listOf(AppShelf("main", "Приложения", listOf("ezch-store")),
            AppShelf("movies", "Кино", emptyList(), true))))
        ui.activityRule.scenario.recreate()
        ui.waitUntil(30000) { ui.onAllNodesWithText("Магазин EZCH").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() { assertTrue(DesktopBackup.restore(ui.activity, original)) }

    @Test fun folderPlacementOpeningAndReturningKeepFocusAndPersist() {
        ui.onAllNodesWithText("Все приложения")[0].performClick()
        ui.onNodeWithText("Поиск приложений").performTextInput("Магазин")
        ui.onNodeWithText("Выбрать строку").performClick()
        ui.onNode(hasText("Кино") and hasClickAction()).performClick()
        ui.waitUntil(10000) { WorkspacePrefs.load(ui.activity).rows.first { it.id == "movies" }.appKeys == listOf("ezch-store") }
        ui.onNodeWithText("Закрыть").performClick()
        ui.onNodeWithTag("folder-movies").performScrollTo().performClick()
        assertTrue(ui.onAllNodesWithText("Кино").fetchSemanticsNodes().isNotEmpty())
        ui.onNodeWithText("Магазин EZCH").assertExists()
        ui.onNodeWithText("Закрыть").performClick()
        ui.onNodeWithTag("folder-movies").assertIsFocused()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("folder-movies").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("folder-movies").performScrollTo().performClick()
        ui.onNodeWithText("Магазин EZCH").assertExists()
    }
    @Test fun verticalMoveIntoFolderCanBeConfirmedWithRemote() {
        ui.onNodeWithText("Магазин EZCH").performKeyInput { keyDown(Key.DirectionCenter); advanceEventTime(650); keyUp(Key.DirectionCenter) }
        ui.onNodeWithText("Переместить приложение").performClick()
        ui.onNodeWithText("Магазин EZCH").performKeyInput { pressKey(Key.DirectionDown) }
        ui.onNodeWithTag("folder-movies").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        ui.waitUntil(10000) { WorkspacePrefs.load(ui.activity).rows.first { it.id == "movies" }.appKeys == listOf("ezch-store") }
        ui.onNodeWithTag("folder-movies").assertIsFocused()
    }
    @Test fun profilesAndBackupRoundTripDoNotMixDesktopsOrExposePlainPin() {
        val owner = WorkspacePrefs.load(ui.activity)
        val credential = PinCredential.create("9274")
        assertTrue(Profiles.save(ui.activity, listOf(DesktopProfile("owner", "Владелец", credential), DesktopProfile("guest", "Гость"))))
        assertTrue(Profiles.switch(ui.activity, "guest"))
        assertTrue(WorkspacePrefs.save(ui.activity, Workspace(listOf(AppShelf("guest-row", "Игры", listOf("ezch-store"))))))
        val copy = DesktopBackup.export(ui.activity)
        assertEquals(credential, org.json.JSONArray(org.json.JSONObject(copy).getJSONObject("stores")
            .getJSONObject("ezch_launcher_prefs").getJSONObject("profiles_v1").getString("value")).getJSONObject(0).getString("credential"))
        assertTrue(Profiles.switch(ui.activity, "owner"))
        assertEquals(owner, WorkspacePrefs.load(ui.activity))
        assertTrue(DesktopBackup.restore(ui.activity, copy))
        assertEquals("guest", Profiles.active(ui.activity))
        assertEquals("guest-row", WorkspacePrefs.load(ui.activity).rows.first().id)
        assertTrue(Profiles.check(ui.activity, Profiles.list(ui.activity).first { it.id == "owner" }, "9274"))
    }
    @Test fun pinGateRejectsWrongPinAndUnlocksSettingsOnlyAfterCorrectPin() {
        Profiles.save(ui.activity, listOf(DesktopProfile("owner", "Владелец", PinCredential.create("9274"))))
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("settings-launcher").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("settings-launcher").performClick()
        ui.onNodeWithTag("quick-Настройки EZCH").performClick()
        ui.onNodeWithText("PIN").performTextInput("0000")
        ui.onNodeWithText("Продолжить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Неверный PIN или пауза после 5 попыток (30 секунд)").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("settings-panel").assertDoesNotExist()
        ui.onNodeWithText("PIN").performTextInput("9274")
        ui.onNodeWithText("Продолжить").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("settings-panel").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("settings-panel").assertExists()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitUntil(10000) { ui.onAllNodesWithText("PIN · Владелец").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("PIN · Владелец").assertIsDisplayed()
        ui.onNodeWithText("Отмена").performClick()
        ui.onNodeWithTag("settings-panel").assertDoesNotExist()
    }
    @Test fun pinLockoutPersistsBetweenCallsAndExpiresAfterThirtySeconds() {
        val profile = DesktopProfile("owner", "Владелец", PinCredential.create("9274"))
        val p = ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
        p.edit().remove("pin_owner_until").remove("pin_owner_attempts").commit()
        repeat(5) { assertFalse(Profiles.check(ui.activity, profile, "0000", 100000)) }
        assertFalse(Profiles.check(ui.activity, profile, "9274", 100001))
        assertTrue(Profiles.check(ui.activity, profile, "9274", 130001))
    }
    @Test fun invalidImportLeavesEveryExistingPreferenceUnchanged() {
        val before = DesktopBackup.export(ui.activity)
        assertTrue(runCatching { DesktopBackup.restore(ui.activity, before.replace("ezch-desktop", "wrong-format")) }.isFailure)
        assertEquals(before, DesktopBackup.export(ui.activity))
    }
    @Test fun folderAndProfileCanBeCreatedThroughSettings() {
        ui.onNodeWithTag("settings-launcher").performClick()
        ui.onNodeWithTag("quick-Настройки EZCH").performClick()
        ui.onNodeWithText("Создать папку").performScrollTo().performClick()
        ui.onNodeWithText("Название").performTextInput("Игровая папка")
        ui.onNodeWithText("Добавить").performClick()
        ui.waitUntil(10000) { WorkspacePrefs.load(ui.activity).rows.any { it.title == "Игровая папка" && it.isFolder } }
        ui.onNodeWithText("Добавить профиль").performScrollTo().performClick()
        ui.onNodeWithText("Имя профиля").performTextInput("Гости")
        ui.onNodeWithText("Сохранить").performClick()
        ui.waitUntil(10000) { Profiles.list(ui.activity).any { it.name == "Гости" } }
        ui.onNodeWithText("Профиль: Владелец").performClick()
        ui.onNodeWithText("Гости").performClick()
        ui.waitUntil(10000) { Profiles.active(ui.activity) != "owner" }
        ui.onNodeWithText("Профиль: Гости").assertExists()
        assertFalse(WorkspacePrefs.load(ui.activity).rows.any { it.title == "Игровая папка" })
    }
    @Test fun localBackupSurvivesFileRoundTripAndCanBeRestoredFromUi() {
        val before = WorkspacePrefs.load(ui.activity)
        val file = DesktopBackup.saveLocal(ui.activity)
        try {
            assertTrue(file.isFile)
            WorkspacePrefs.save(ui.activity, before.renameRow("main", "Изменено"))
            ui.activityRule.scenario.recreate()
            ui.waitUntil(30000) { ui.onAllNodesWithTag("settings-launcher").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("settings-launcher").performClick()
            ui.onNodeWithTag("quick-Настройки EZCH").performClick()
            ui.onNodeWithText("Копии в папке EZCH").performScrollTo().performClick()
            ui.onNodeWithText(file.name).performClick()
            ui.onNodeWithText("Восстановить").performClick()
            ui.waitUntil(10000) { WorkspacePrefs.load(ui.activity) == before }
        } finally { file.delete() }
    }
    @Test fun backupPreservesUnmigrated025LayoutAndLiteChoice() {
        val legacy = ui.activity.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
        ui.activity.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit().remove("workspace_v1").commit()
        legacy.edit().putString("app_order", """["com.test.a","com.test.b"]""")
            .putString("hidden_packages", """["com.test.b"]""").putString("home_rows", "[]").putBoolean("lite", true).commit()
        ui.activity.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit().clear().commit()
        val expected = WorkspacePrefs.load(ui.activity)
        val copy = DesktopBackup.export(ui.activity)
        legacy.edit().clear().commit()
        assertTrue(DesktopBackup.restore(ui.activity, copy))
        assertEquals(expected, WorkspacePrefs.load(ui.activity))
        assertEquals(listOf("com.test.a", "com.test.b"), expected.rows.first().appKeys)
        assertEquals(listOf("com.test.b"), expected.hiddenApps)
        assertEquals(WallpaperMode.LITE, WallpaperSettings.load(ui.activity).mode)
    }
}
