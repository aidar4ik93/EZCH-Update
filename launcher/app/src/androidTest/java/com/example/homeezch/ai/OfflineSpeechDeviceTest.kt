package com.example.homeezch.ai

import android.os.Debug
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic local speech verifies the native model, separately from the physical remote microphone. */
class OfflineSpeechDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test(timeout = 15_000) fun panelTtsInitializationDoesNotBlockUi() {
        val responsive = CountDownLatch(1)
        val start = SystemClock.elapsedRealtime()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val panel = VoicePanel(context, close = {})
            panel.destroy()
            responsive.countDown()
        }
        assertTrue("Creating or destroying EVA must not wait for the TTS provider", responsive.await(5, TimeUnit.SECONDS))
        File(context.getExternalFilesDir(null), "eva-036-ui-tts.txt").writeText("Panel create/destroy UI elapsedMs=${SystemClock.elapsedRealtime()-start}\n")
    }

    @Test fun microphoneSessionCanBeCancelledWithoutLateResult() {
        val preferences = AiPreferences.store(context)
        val isolated = context.packageName.endsWith(".debug")
        val originallyEnabled = AiPreferences.enabled(context)
        if (isolated) preferences.edit().putBoolean("enabled", true).commit()
        assertTrue("EVA must already be enabled by its owner", AiPreferences.enabled(context))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val ready = CountDownLatch(1)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val results = java.util.concurrent.atomic.AtomicInteger()
        // This firmware has a recorded system_server task-reparenting crash on ActivityScenario.close().
        // Keep HOME open and never rearrange or finish its task in a microphone test.
        context.startActivity(android.content.Intent(context, com.example.homeezch.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        lateinit var recognizer: OfflineVoskRecognizer
        instrumentation.runOnMainSync {
            recognizer = OfflineVoskRecognizer(context, { message -> statuses.add(message); if (message.startsWith("Я слушаю")) ready.countDown() }, { results.incrementAndGet() })
            recognizer.start()
        }
        try {
            assertTrue("Microphone did not initialize: $statuses", ready.await(40, TimeUnit.SECONDS))
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val speechName = context.packageName + ":eva_speech"
            val process = manager.runningAppProcesses.firstOrNull { it.processName == speechName }
            assertNotNull("Model must be isolated from the launcher process", process)
            instrumentation.runOnMainSync { recognizer.cancel(); assertFalse(recognizer.isListening) }
            Thread.sleep(2000)
            assertEquals("A cancelled recording must never submit a command", 0, results.get())
            repeat(50) {
                if (manager.runningAppProcesses.none { it.processName == speechName }) return@repeat
                Thread.sleep(100)
            }
            assertTrue("Speech process must exit to return native memory", manager.runningAppProcesses.none { it.processName == speechName })
        } finally {
            instrumentation.runOnMainSync { recognizer.destroy() }
            if (isolated) preferences.edit().putBoolean("enabled", originallyEnabled).commit()
            File(context.getExternalFilesDir(null), "eva-036-microphone-test.txt").writeText("AudioRecord initialization and cancellation only; remote speech not verified.\n$statuses\nresults=${results.get()}\n")
        }
    }

    @Test(timeout = 90_000) fun russianModelRecognizesLocalSyntheticSpeech() {
        val initialized = CountDownLatch(1)
        var engine: TextToSpeech? = null
        var code = -1
        val stages = File(context.getExternalFilesDir(null), "eva-036-speech-stages.txt")
        stages.writeText("TTS constructor scheduled off UI\n")
        Thread({
            try { engine = TextToSpeech(context) { status -> code = status; initialized.countDown() } }
            catch (_: Exception) { code = -3; initialized.countDown() }
        }, "eva-tts-fixture").apply { isDaemon = true; start() }
        val wav = File(context.cacheDir, "eva-synthetic-ru.wav")
        val report = StringBuilder("Synthetic speech only; no physical microphone claim.\n")
        try {
            assertTrue("TTS initialization timed out", initialized.await(30, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, code)
            stages.appendText("TTS initialized; requesting voices\n")
            val tts = requireNotNull(engine)
            assertTrue(EvaSpeechStyle.configure(context, tts, java.util.Locale.forLanguageTag("ru-RU")))
            val voice = tts.voice
            assertNotNull("No installed offline Russian TTS voice", voice)
            assertEquals("ru", voice?.locale?.language)
            assertFalse(voice?.isNetworkConnectionRequired ?: true)
            stages.appendText("Russian voice selected; synthesizing\n")
            val synthesized = CountDownLatch(1)
            var completed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { completed = true; synthesized.countDown() }
                @Deprecated("Platform callback") override fun onError(id: String?) { synthesized.countDown() }
            })
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("Ева открой настройки", null, wav, "local-fixture"))
            assertTrue("Local TTS timed out", synthesized.await(30, TimeUnit.SECONDS))
            assertTrue("Local TTS failed", completed)
            stages.appendText("TTS fixture ready; parsing PCM\n")
            val input = ByteBuffer.wrap(wav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(ByteArray(4).also { input.get(it) }))
            input.position(12)
            var rate = 0; var channels = 0; var bits = 0; var samples = ByteArray(0)
            while (input.remaining() >= 8) {
                val name = String(ByteArray(4).also { input.get(it) })
                val size = input.int
                require(size >= 0 && size <= input.remaining())
                val end = input.position() + size
                if (name == "fmt ") { assertEquals(1, input.short.toInt()); channels = input.short.toInt(); rate = input.int; input.int; input.short; bits = input.short.toInt() }
                if (name == "data") samples = ByteArray(size).also { input.get(it) }
                input.position(end + (size % 2))
            }
            assertEquals(1, channels); assertEquals(16, bits); assertTrue(rate > 0); assertTrue(samples.isNotEmpty())
            val source = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = source.remaining() * 16000L / rate
            val pcm = ByteBuffer.allocate(count.toInt() * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count.toInt()) { index ->
                val position = index * rate / 16000.0
                val first = position.toInt().coerceAtMost(source.limit() - 1)
                val second = (first + 1).coerceAtMost(source.limit() - 1)
                pcm.putShort((source[first] + (source[second] - source[first]) * (position - first)).toInt().toShort())
            }
            val before = Debug.getPss()
            val start = SystemClock.elapsedRealtime()
            val directory = OfflineModelStore.ensureInstalled(context)
            stages.appendText("Model unpacked; loading native model\n")
            val loaded = SystemClock.elapsedRealtime()
            val phrases = mutableListOf<String>()
            Model(directory.path).use { model ->
                report.append("modelPssKb=${Debug.getPss()}\n")
                Recognizer(model, 16000f).use { decoder ->
                    val bytes = pcm.array()
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + 6400, bytes.size)
                        val chunk = bytes.copyOfRange(offset, end)
                        if (decoder.acceptWaveForm(chunk, chunk.size)) phrases += JSONObject(decoder.result).optString("text")
                        offset = end
                    }
                    phrases += JSONObject(decoder.finalResult).optString("text")
                }
            }
            val recognized = phrases.joinToString(" ").trim()
            report.append("voice=${voice?.name}\ninputRate=$rate\ninstallMs=${loaded-start}\ntotalMs=${SystemClock.elapsedRealtime()-start}\nbeforePssKb=$before\nafterClosePssKb=${Debug.getPss()}\nrecognized=$recognized\n")
            // Vosk hears the synthetic voice slightly differently between runs («открой надстройки»):
            // what matters is that EVA turns the recognized words into the same command.
            assertTrue("Unexpected Russian recognition: $recognized", recognized.contains("открой"))
            val reply = EvaCore(EvaMemory(InMemoryEvaMemoryStore())).handle(recognized)
            assertEquals("Recognized «$recognized» → $reply", EvaReply.Execute(listOf(Action.Settings)), reply)
            val sampleDone = CountDownLatch(1)
            var sampleOk = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { sampleOk = true; sampleDone.countDown() }
                @Deprecated("Platform callback") override fun onError(id: String?) { sampleDone.countDown() }
            })
            val sample = File(context.getExternalFilesDir(null), "eva-voice-sample.wav")
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("Привет. Я Ева, твоя помощница. Скажи, что открыть, и я помогу. Давай найдём что-нибудь интересное.", null, sample, "voice-sample"))
            assertTrue(sampleDone.await(30, TimeUnit.SECONDS) && sampleOk)
            report.append("voiceSample=${sample.name}; voice gender must be reviewed by listening\n")
            println(report)
        } finally {
            Thread({ engine?.shutdown() }, "eva-tts-cleanup").apply { isDaemon = true; start() }
            wav.delete()
            File(context.getExternalFilesDir(null), "eva-036-offline-test.txt").writeText(report.toString())
        }
    }
}
