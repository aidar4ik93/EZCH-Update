package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.homeezch.ai.AiPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiHomeNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun setup() {
        prepareLauncherTest()
        AiPreferences.store(context).edit().putBoolean("enabled", true)
            .putBoolean("tts", false).putBoolean("network_speech", false).commit()
        context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val apps = (0..11).map { LaunchApp("Voice card $it", ComponentName("voice.test.$it", "Main")) }
        ui.setContent { MaterialTheme { HomeScreen(apps, 0, false, {}, {}, preferredFocus = "voice.test.0", weatherReady = false) } }
        ui.waitForIdle()
        ui.onNodeWithText("Voice card 0").assertIsFocused()
    }
    @After fun cleanup() { AiPreferences.store(context).edit().putBoolean("enabled", false).commit() }
    private fun command(text: String) {
        // The owner removed the TopBar button and full text dialog. Exercise the
        // production phone-text route; callers verify the actual focused card.
        ui.runOnIdle { com.example.homeezch.phone.EvaInbox.post(text) }
    }
    @Test fun threeStepsAreExactAndBoundaryDoesNotJumpRows() {
        command("вправо три раза")
        ui.waitUntil(5000) { runCatching { ui.onNodeWithText("Voice card 3").assertIsFocused(); true }.getOrDefault(false) }
        ui.onNodeWithText("Voice card 3").assertIsFocused()
        command("влево десять раз")
        ui.waitUntil(5000) { runCatching { ui.onNodeWithText("Voice card 0").assertIsFocused(); true }.getOrDefault(false) }
        ui.onNodeWithText("Voice card 0").assertIsFocused()
    }
}
