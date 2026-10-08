package com.example.homeezch

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class SettingsAppearanceTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous = WallpaperChoice()
    @Before fun prepare() { previous = WallpaperSettings.load(context); WallpaperSettings.save(context, WallpaperChoice()) }
    @After fun restore() { WallpaperSettings.save(context, previous) }
    @Test fun settingShowsExplanationAndPersistsSelectedMode() {
        ui.setContent { MaterialTheme { Column { WallpaperControls {} } } }
        ui.onNodeWithText("Авто").assertExists()
        ui.onNodeWithText("Режим оформления").performClick()
        ui.onNodeWithText("Premium").assertExists()
        assertEquals(WallpaperMode.PREMIUM, WallpaperSettings.load(context).mode)
        ui.onNodeWithText("Режим оформления").performClick()
        ui.onNodeWithText("Lite").assertExists()
        assertEquals(WallpaperMode.LITE, WallpaperSettings.load(context).mode)
    }
    @Test fun informationalSettingCanBeActivatedWithRemote() {
        var clicks = 0
        ui.setContent { MaterialTheme { SettingsItem("Домой", "Главный экран", "Открывать EZCH при нажатии Home.", "Назначен") { clicks++ } } }
        ui.onNodeWithText("Главный экран").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithText("Главный экран").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, clicks)
        ui.onNodeWithText("Назначен").assertExists()
    }
}
