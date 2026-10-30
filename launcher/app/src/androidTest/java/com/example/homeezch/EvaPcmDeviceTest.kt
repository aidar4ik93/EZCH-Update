package com.example.homeezch

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homeezch.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthesized speech, not live microphone coverage. Feeds exactly the production PCM decoder. */
class EvaPcmDeviceTest {
    private fun pcm(wav: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(wav, at, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE")
        var offset = 12
        var validFormat = false
        var audio: ByteArray? = null
        while (offset + 8 <= wav.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= wav.size)
            when (tag(offset)) {
                "fmt " -> {
                    require(size >= 16)
                    validFormat = buffer.getShort(offset + 8).toInt() == 1 &&
                        buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16000 &&
                        buffer.getShort(offset + 22).toInt() == 16
                }
                "data" -> audio = wav.copyOfRange(offset + 8, offset + 8 + size)
            }
            offset += 8 + size + (size % 2)
        }
        require(validFormat)
        return checkNotNull(audio)
    }

    @Test fun russianTtsThroughWakeCommandParserAndAppMatching() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug") ||
            (context.packageName == "com.example.homeezch.usb" && android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))) {
            "Release WAV tests are permitted on an emulator only"
        }
        val assets = instrumentation.context.assets
        val manifest = JSONObject(assets.open("round3-voice/manifest.json").bufferedReader().use { it.readText() }.removePrefix("\uFEFF"))
        val modelStart = SystemClock.elapsedRealtime()
        val model = Model(OfflineModelStore.ensureInstalled(context).path)
        val modelMs = SystemClock.elapsedRealtime() - modelStart
        val apps = listOf(AppTarget("NUM", "ru.yourok.num/.Main"), AppTarget("Zona", "mobi.zona/.Main"))
        val results = JSONArray()
        val failures = mutableListOf<String>()
        try {
            val fixtures = manifest.getJSONArray("fixtures")
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val name = fixture.getString("name")
                val audio = pcm(assets.open("round3-voice/$name.wav").use { it.readBytes() })
                // A 2 s silent tail lets Vosk endpoint a finite WAV; it is recorded as such in the report.
                val stream = audio + ByteArray(64000)
                val setupStart = SystemClock.elapsedRealtime()
                var streamStart = 0L
                var wakeWallMs: Long? = null
                val decoder = EvaPcmDecoder(model) { wakeWallMs = SystemClock.elapsedRealtime() - streamStart }
                val setupMs = SystemClock.elapsedRealtime() - setupStart
                val start = SystemClock.elapsedRealtime()
                streamStart = start
                var text = ""
                var result: Resolution? = null
                var match: AppMatch? = null
                val row = JSONObject().put("fixture", name).put("wavMs", audio.size / 32)
                try {
                    var offset = 0
                    while (offset < stream.size) {
                        val count = minOf(640, stream.size - offset)
                        val wait = start + (offset + count) / 32 - SystemClock.elapsedRealtime()
                        if (wait > 0) SystemClock.sleep(wait)
                        val command = decoder.accept(stream.copyOfRange(offset, offset + count), count)
                        offset += count
                        if (command != null) { text = command; break }
                    }
                    if (text.isBlank()) text = decoder.finish()
                    if (text.isNotBlank()) result = LocalIntentResolver().resolve(text)
                    val plan = (result as? Resolution.Plan)?.actions.orEmpty()
                    val launch = plan.filterIsInstance<Action.Launch>().firstOrNull()
                    if (launch != null) match = matchApp(launch.name, apps)
                    row.put("wakeAudioMs", decoder.wakeAudioMs ?: JSONObject.NULL)
                        .put("candidateAudioMs", decoder.candidateAudioMs ?: JSONObject.NULL)
                        .put("rawRecognition", decoder.lastRecognition)
                        .put("wakeEndBookmarkMs", fixture.getDouble("wakeEndMs"))
                        .put("wakeLatencyAudioMs", decoder.wakeAudioMs?.let { it - fixture.getDouble("wakeEndMs") } ?: JSONObject.NULL)
                        .put("commandEndAudioMs", decoder.audioMs)
                        .put("wakeLatencyWallMs", wakeWallMs?.let { it - fixture.getDouble("wakeEndMs") } ?: JSONObject.NULL)
                        .put("wakeWallMs", wakeWallMs ?: JSONObject.NULL)
                        .put("decoderSetupMs", setupMs)
                        .put("commandAfterWakeEndAudioMs", decoder.wakeAudioMs?.let { decoder.audioMs - fixture.getDouble("wakeEndMs") } ?: JSONObject.NULL)
                        .put("decoderWallMs", SystemClock.elapsedRealtime() - start)
                        .put("text", text).put("resolution", result?.toString() ?: "none").put("match", match?.toString() ?: "none")
                    val correct = when (name) {
                        "num" -> decoder.wakeAudioMs != null && plan.size == 1 && (match as? AppMatch.Unique)?.app == apps[0]
                        "zona" -> decoder.wakeAudioMs != null && (match as? AppMatch.Unique)?.app == apps[1] && plan.lastOrNull() == Action.Search("мстители")
                        "weather" -> decoder.wakeAudioMs != null && plan == listOf(Action.Weather)
                        "negative" -> decoder.wakeAudioMs == null && text.isBlank()
                        else -> false
                    }
                    row.put("pass", correct)
                    if (!correct) failures += "$name: $row"
                } finally { decoder.close(); results.put(row) }
            }
        } finally {
            model.close()
            val report = JSONObject().put("voice", manifest.getString("voice")).put("modelLoadMs", modelMs)
                .put("silentTailMs", 2000).put("feed", "20ms chunks paced at 16kHz; decoding may lag real time")
                .put("cases", results)
            File(context.filesDir, "round3-voice-results.json").writeText(report.toString(2))
            context.getExternalFilesDir(null)?.let {
                File(it, "round3-voice-results.json").writeText(report.toString(2))
            }
            println("EVA_PCM_RESULTS=$report")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
