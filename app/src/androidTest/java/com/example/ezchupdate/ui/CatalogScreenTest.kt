package com.example.ezchupdate.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ezchupdate.AppRow
import com.example.ezchupdate.CatalogUiState
import com.example.ezchupdate.data.InstalledApp
import com.example.ezchupdate.data.RemoteApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusesUseDeviceVersionsAndDoNotCallMissingAppsUpdates() {
        val rows = listOf(
            AppRow(app("Missing", 0), null),
            AppRow(app("Update", 1), InstalledApp(9, "0.9")),
            AppRow(app("Current", 2), InstalledApp(10, "1.0")),
            AppRow(app("Newer", 3), InstalledApp(11, "1.1"))
        )
        compose.setContent {
            EzchTheme { CatalogScreen(CatalogUiState(rows = rows, isLoading = false), {}, {}, {}, {}, {}) }
        }
        compose.onAllNodesWithText("Установить").assertCountEquals(1)
        compose.onAllNodesWithText("Обновление").assertCountEquals(1)
        compose.onAllNodesWithText("Установлено").assertCountEquals(2)
        compose.onNodeWithText("Приложений: 4  •  Обновлений: 1").assertIsDisplayed()
    }

    @Test fun remoteDirectionsMoveFocusAndCenterTogglesOneCard() {
        val first = app("First", 0)
        val second = app("Second", 1)
        var state by mutableStateOf(CatalogUiState(rows = listOf(AppRow(first, null), AppRow(second, null)), isLoading = false))
        var toggled: String? = null
        var installs = 0
        var reloads = 0
        compose.setContent {
            EzchTheme {
                CatalogScreen(state, { reloads++ }, { packageName ->
                    toggled = packageName
                    state = state.copy(selectedPackages = setOf(packageName))
                }, { installs++ }, {}, {})
            }
        }
        val firstCard = compose.onNodeWithContentDescription("First, версия 1.0")
        val secondCard = compose.onNodeWithContentDescription("Second, версия 1.0")
        firstCard.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        secondCard.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        secondCard.assertIsSelected()
        compose.runOnIdle { assertEquals(second.packageName, toggled) }
        compose.onNodeWithText("Установить выбранные (1)").performClick()
        compose.onNodeWithText("Проверить обновления").performClick()
        compose.runOnIdle { assertEquals(1, installs); assertEquals(1, reloads) }
    }

    @Test fun installationFooterKeepsCancelAndConfirmationAvailable() {
        val app = app("Installing", 0)
        var cancelled = false
        var confirmed = false
        compose.setContent {
            EzchTheme {
                CatalogScreen(
                    CatalogUiState(rows = listOf(AppRow(app, null)), isLoading = false, installing = app,
                        queueIndex = 1, queueTotal = 3, installProgress = 0.56f,
                        downloadedBytes = 12_582_912, totalBytes = 22_020_096,
                        installStage = "Ожидание подтверждения Android", pendingSessionId = 123),
                    {}, {}, {}, { cancelled = true }, { confirmed = true }
                )
            }
        }
        compose.onNodeWithText("1/3   Installing").assertIsDisplayed()
        compose.onNodeWithText("Подтвердить").assertIsDisplayed().performClick()
        compose.onNodeWithText("Отменить", substring = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(confirmed); assertTrue(cancelled) }
    }

    @Test fun unavailableCatalogProvidesFocusedRetry() {
        compose.setContent {
            EzchTheme {
                CatalogScreen(CatalogUiState(isLoading = false, isError = true, message = "Проверьте сеть"), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Каталог недоступен").assertIsDisplayed()
        compose.onNodeWithText("Проверьте сеть").assertIsDisplayed()
        compose.onNodeWithText("Проверить обновления").assertIsFocused()
    }

    @Test fun bundledIconsDecodeAndReplaceTheInitialFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.assets.list("app-icons").orEmpty().filter { it.endsWith(".png") }
        assertEquals(17, files.size)
        files.forEach { file ->
            context.assets.open("app-icons/$file").use {
                val bitmap = BitmapFactory.decodeStream(it)
                assertNotNull("Не декодируется значок $file", bitmap)
                bitmap?.recycle()
            }
        }
        val app = app("ByeByeDPI", 0).copy(packageName = "io.github.romanvht.byedpi")
        compose.setContent { EzchTheme { AppIcon(app, 54.dp) } }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("BY").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun app(name: String, index: Int) = RemoteApp(
        name, "com.test.application$index", 10, "1.0", "https://example.com/app.apk", "/app.apk"
    )
}
