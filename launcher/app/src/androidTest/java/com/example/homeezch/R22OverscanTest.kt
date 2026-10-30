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

/** Run at each emulator size/density. Includes focused scale and a conservative halo reserve. */
@OptIn(ExperimentalTestApi::class)
class R22OverscanTest {
    @get:Rule val ui = createComposeRule()
    @Test fun firstAndLastFocusedCardsAndQuickSettingsStayInsideFivePercent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        prepareLauncherTest()
        val apps = (0..19).map { LaunchApp("R22 $it", ComponentName("overscan.test.$it", "Main")) }
        ui.setContent { MaterialTheme { HomeScreen(apps, 0, false, {}, {}, preferredFocus = "overscan.test.0", weatherReady = false) } }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("desktop-card-overscan.test.0").fetchSemanticsNodes().isNotEmpty() }
        fun checkFocused(index: Int) {
            val card = ui.onNodeWithTag("desktop-card-overscan.test.$index")
            card.assertIsFocused()
            val b = card.getUnclippedBoundsInRoot()
            val root = ui.onRoot().getUnclippedBoundsInRoot()
            val left = (root.right - root.left) * .05f
            val reserve = androidx.compose.ui.unit.Dp(14f)
            assertTrue("left card+halo $b screen $root", b.left - reserve >= left)
            assertTrue("right card+halo $b screen $root", b.right + reserve <= (root.right - root.left) - left)
        }
        ui.waitForIdle(); checkFocused(0)
        for (i in 1..19) {
            ui.onRoot().performKeyInput { pressKey(Key.DirectionRight) }; ui.waitForIdle()
            checkFocused(i)
        }
        for (i in 18 downTo 0) {
            ui.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }; ui.waitForIdle()
            checkFocused(i)
        }
        ui.onNodeWithTag("settings-launcher").performClick(); ui.waitForIdle()
        val root = ui.onRoot().getUnclippedBoundsInRoot()
        listOf("Настройки EZCH", "Wi-Fi", "Bluetooth").forEach { label ->
            val b=ui.onNodeWithTag("quick-$label").getUnclippedBoundsInRoot()
            assertTrue("quick $label clipped: $b / $root", b.left >= (root.right - root.left)*.05f && b.right <= (root.right - root.left)*.95f)
        }
    }
}
