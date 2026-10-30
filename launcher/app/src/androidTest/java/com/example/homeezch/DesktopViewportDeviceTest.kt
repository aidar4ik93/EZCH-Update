package com.example.homeezch

import android.content.ComponentName
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Reproduces partial edge cards and the clipped first row after returning from TV inputs. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class DesktopViewportDeviceTest {
    @get:Rule val ui = createComposeRule()

    @Test fun wholeCardsRemainVisibleAfterHorizontalAndVerticalNavigation() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        prepareLauncherTest()
        val apps = (0..19).map { LaunchApp("Viewport $it", ComponentName("viewport.test.$it", "Main")) }
        ui.setContent { MaterialTheme { HomeScreen(apps, 0, false, {}, {}, preferredFocus = "viewport.test.0", weatherReady = false) } }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("desktop-card-viewport.test.0").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        fun assertWholeVisibleCards() {
            var visible = 0
            for (i in 0..19) {
                val card = ui.onNodeWithTag("desktop-card-viewport.test.$i")
                val node = card.fetchSemanticsNodesOrNull() ?: continue
                val clipped = node.boundsInRoot
                if (clipped.width < 2 || clipped.height < 2) continue
                val whole = card.getUnclippedBoundsInRoot()
                val width = with(ui.density) { (whole.right - whole.left).toPx() }
                val height = with(ui.density) { (whole.bottom - whole.top).toPx() }
                assertTrue("Card $i clipped horizontally: $clipped / $whole", clipped.width >= width - 2f)
                assertTrue("Card $i clipped vertically: $clipped / $whole", clipped.height >= height - 2f)
                visible++
            }
            assertTrue("Several whole cards should fit the desktop", visible >= 3)
        }
        assertWholeVisibleCards()
        repeat(10) { ui.onRoot().performKeyInput { pressKey(Key.DirectionRight) }; ui.waitForIdle() }
        assertWholeVisibleCards()
        ui.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; ui.waitForIdle()
        ui.onRoot().performKeyInput { pressKey(Key.DirectionUp) }; ui.waitForIdle()
        assertWholeVisibleCards()
    }
}

private fun SemanticsNodeInteraction.fetchSemanticsNodesOrNull() =
    runCatching { fetchSemanticsNode() }.getOrNull()
