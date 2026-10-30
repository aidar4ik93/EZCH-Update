package com.example.homeezch

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.homeezch.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Debug/emulator-only simulation; never touches the owner's release preferences. */
class Round6SmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun guard() {
        check(context.packageName.endsWith(".debug") && Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun screenshot(name: String) {
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), name)))
    }
    @Test fun homeShowsNamedSphere() {
        guard(); prepareLauncherTest()
        AiPreferences.store(context).edit().putBoolean("enabled", false).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("Ai EvA.")), 15000))
            Thread.sleep(2500)
            screenshot("round6-home.png")
        }
    }
    @Test fun disconnectedHomeIsHonest() {
        guard()
        val url = SmartHomeConnection.url(context); val token = SmartHomeConnection.token(context)
        try {
            SmartHomeConnection.clear(context)
            ActivityScenario.launch<SmartHomeActivity>(Intent(context, SmartHomeActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.textContains("Ничего не подключено")), 10000))
                screenshot("round6-smart-home-disconnected.png")
            }
        } finally { SmartHomeConnection.save(context, url, token) }
    }
    private fun reset(mode: String, state: String = "off") {
        val connection = URL("http://10.0.2.2:18123/test/reset").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("mode", mode).put("state", state).toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
        } finally { connection.disconnect() }
    }
    @Test fun simulatedVoiceUsesReadbackAndRejectsFalseSuccess() = runBlocking {
        guard()
        val oldUrl = SmartHomeConnection.url(context); val oldToken = SmartHomeConnection.token(context)
        val results = org.json.JSONArray()
        try {
            SmartHomeConnection.save(context, "http://10.0.2.2:18123", "simulation-only-token-not-real")
            val plan = LocalIntentResolver().resolve("включи свет в гостиной") as Resolution.Plan
            val action = plan.actions.single() as Action.Home
            assertEquals("on", action.op)
            for (mode in listOf("apply", "ignore", "error")) {
                reset(mode)
                val outcome = SmartHomeExecutor(context).execute(action)
                results.put(JSONObject().put("mode", mode).put("accepted", outcome.accepted).put("message", outcome.message))
                assertEquals("HTTP acknowledgement alone must not mean success ($mode)", mode == "apply", outcome.accepted)
            }
            reset("ignore", "on")
            val zero = SmartHomeExecutor(context).execute(Action.Home("brightness", "свет гостиная", 0))
            results.put(JSONObject().put("mode", "ignored-zero-brightness").put("accepted", zero.accepted).put("message", zero.message))
            assertFalse("Zero brightness must also be confirmed by actual state", zero.accepted)
        } finally {
            File(context.getExternalFilesDir(null), "round6-ha-results.json").writeText(results.toString(2))
            SmartHomeConnection.save(context, oldUrl, oldToken)
        }
    }
}
