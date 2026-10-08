package com.example.homeezch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DesktopAppearanceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun appCardDoesNotPaintSquareCorners() {
        ui.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    AppTile(LaunchApp("Corner test", null), 120.dp, Modifier.testTag("card"), {}, {})
                }
            }
        }
        val pixels = ui.onNodeWithTag("card").captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[0, 0])
    }
    @Test fun liteWallpaperIsSolidBlack() {
        ui.setContent { Box(Modifier.fillMaxSize().testTag("wallpaper")) { CosmicBackground(false) } }
        val pixels = ui.onNodeWithTag("wallpaper").captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[pixels.width / 2, pixels.height / 2])
        assertEquals(Color.Black, pixels[0, 0])
    }
}
