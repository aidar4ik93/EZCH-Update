package com.example.homeezch

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class SourceRevealNavigationTest {
    companion object { @JvmStatic @BeforeClass fun prepare() = prepareLauncherTest() }
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var original: String? = null
    @Before fun manualInputsOnlyForTest() {
        val prefs = ui.activity.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
        original = prefs.getString("manual", null)
        SourceSettings.add(ui.activity, TvSourceKind.HDMI)
        SourceSettings.add(ui.activity, TvSourceKind.HDMI)
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("sources-launcher").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun restore() {
        ui.activity.getSharedPreferences("source_settings", Context.MODE_PRIVATE).edit().putString("manual", original).commit()
    }
    @Test fun rapidKeysDuringRevealKeepNavigationAndLeftReturnsToControl() {
        val last = scanTvSources(ui.activity).entries.last().id
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val trigger = ui.onNodeWithTag("sources-launcher")
        trigger.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(4) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(20)
        }
        ui.waitForIdle()
        repeat(10) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); ui.waitForIdle() }
        ui.onNodeWithTag("source-$last").assertIsFocused()
        repeat(10) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); ui.waitForIdle() }
        trigger.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.waitForIdle()
        ui.onNodeWithTag("source-$last").assertDoesNotExist()
        trigger.assertIsFocused()
    }
}
