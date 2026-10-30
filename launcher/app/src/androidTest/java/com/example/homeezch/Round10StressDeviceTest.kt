package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homeezch.ai.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking

/** Test fixtures only: real VoicePanel lifecycle and core, no cloud credentials or speech injection. */
class Round10StressDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun setup() {
        check(context.packageName.endsWith(".debug"))
        prepareLauncherTest()
        AiPreferences.store(context).edit().putBoolean("enabled", true).putBoolean("tts", false)
            .putBoolean("network_speech", false).commit()
        EvaEngine.setHandsFree(context, false)
        EvaBrain.core(context).resetDialog()
    }
    @org.junit.After fun restore() {
        AiPreferences.store(context).edit().putBoolean("enabled", false).commit()
    }
    private fun save(name: String, value: JSONObject) {
        java.io.File(context.getExternalFilesDir(null), name).writeText(value.toString(2))
    }
    @Test fun fiveHundredSerialCommandsAwaitActualAnswers() {
        setup()
        val replies = LinkedBlockingQueue<String>()
        val times = mutableListOf<Long>()
        val commands = listOf("который час", "какое сегодня число", "что ты умеешь", "что ты обо мне помнишь")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var panel: VoicePanel
            scenario.onActivity { activity ->
                panel = VoicePanel(activity, close = {}, onStatusText = { replies.offer(it) })
                (activity.window.decorView as FrameLayout).addView(panel.view, FrameLayout.LayoutParams(180,180))
            }
            try {
                repeat(500) { i ->
                    replies.clear()
                    val start = SystemClock.elapsedRealtime()
                    scenario.onActivity { panel.submit(commands[i % commands.size]) }
                    val answer = replies.poll(5, TimeUnit.SECONDS)
                    assertNotNull("No actual response for command $i", answer)
                    assertFalse("Unexpected disabled state at $i", answer!!.contains("выключен"))
                    when (i % commands.size) {
                        0 -> assertTrue("Clock response: $answer", answer.startsWith("Сейчас"))
                        1 -> assertTrue("Date response: $answer", answer.startsWith("Сегодня"))
                    }
                    times += SystemClock.elapsedRealtime() - start
                }
                save("r10-commands500.json", JSONObject().put("completed", times.size)
                    .put("unique_commands", commands.size).put("route", "VoicePanel.submit -> EvaBrain -> actual status callback; debug, TTS off")
                    .put("p95_ms", times.sorted()[(times.size * .95).toInt()-1]).put("max_ms", times.maxOrNull()))
            } finally {
                scenario.onActivity { (panel.view.parent as? android.view.ViewGroup)?.removeView(panel.view); panel.destroy() }
            }
        }
    }
    @Test fun twoHundredAttachedPanelOpenCloseCyclesReleaseReferences() {
        setup()
        val references = mutableListOf<WeakReference<VoicePanel>>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            fun cycle(): WeakReference<VoicePanel> {
                lateinit var ref: WeakReference<VoicePanel>
                scenario.onActivity { activity ->
                    val panel = VoicePanel(activity, close = {})
                    val parent = activity.window.decorView as FrameLayout
                    parent.addView(panel.view, FrameLayout.LayoutParams(180,180))
                    panel.submit("который час")
                    parent.removeView(panel.view)
                    panel.destroy()
                    ref = WeakReference(panel)
                }
                inst.waitForIdleSync()
                return ref
            }
            repeat(10) { cycle() }
            repeat(3) { System.gc(); Thread.sleep(100) }
            val pssBefore = Debug.getPss()
            val threadsBefore = Thread.getAllStackTraces().size
            repeat(200) { references += cycle(); Thread.sleep(16) }
            // Binder/recognizer/render cleanup is asynchronous. Preserve a time series
            // rather than classifying a one-second transient as an established leak.
            val retention = org.json.JSONArray()
            repeat(10) { index ->
                System.gc(); System.runFinalization(); System.gc(); Thread.sleep(2000)
                retention.put(JSONObject().put("seconds", (index + 1) * 2)
                    .put("retained", references.count { it.get() != null }))
            }
            val retained = references.count { it.get() != null }
            val pssAfter = Debug.getPss()
            val threadsAfter = Thread.getAllStackTraces().size
            save("r10-panels200.json", JSONObject().put("cycles", 200).put("warmup_cycles", 10)
                .put("route", "real attached VoicePanel create/submit/detach/destroy; debug, TTS off")
                .put("retained_panel_references", retained).put("pss_before_kb", pssBefore).put("pss_after_kb", pssAfter)
                .put("retention_after_quiescence", retention)
                .put("threads_before", threadsBefore).put("threads_after", threadsAfter))
            assertTrue("Retained $retained of 200 panels after 20s and GC", retained <= 2)
            assertTrue("Thread accumulation", threadsAfter <= threadsBefore + 8)
        }
    }
    @Test fun cloudWithoutKeysFailsClosedAndSettingsRequireOwnerPin() = runBlocking {
        setup()
        assertFalse("Test must not use stored credentials", GigaChatSettings.hasKey(context))
        val original = DesktopBackup.export(context)
        try {
            Profiles.save(context, listOf(DesktopProfile("owner", "Владелец", PinCredential.create("9274"))))
            val device = UiDevice.getInstance(inst)
            ActivityScenario.launch<AssistantSettingsActivity>(Intent(context, AssistantSettingsActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("PIN · Владелец")), 5000))
                assertFalse(device.hasObject(By.text("Дополнительно · GigaChat (Сбер) по ключу владельца")))
            }
            Profiles.save(context, listOf(DesktopProfile("owner", "Владелец")))
            ActivityScenario.launch<AssistantSettingsActivity>(Intent(context, AssistantSettingsActivity::class.java)).use {
                val list = UiScrollable(UiSelector().scrollable(true))
                assertTrue(list.scrollTextIntoView("Дополнительно · GigaChat (Сбер) по ключу владельца"))
            }
            save("r10-cloud-pin.json", JSONObject().put("no_key_providers", 1).put("pin_blocks_settings", true)
                .put("cloud_section_visible_for_unlocked_owner", true).put("credentials_entered", false))
        } finally { DesktopBackup.restore(context, original) }
    }
}
