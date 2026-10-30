package com.example.homeezch.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

/** Bounded push-to-talk. One worker owns PCM, native resources and cleanup; UI never joins it. */
internal class OfflineVoskSession(private val context: Context, private val status: (String) -> Unit,
    private val result: (String) -> Unit, private val amplitude: (Float) -> Unit = {},
    private val partial: (String) -> Unit = {}) : Recognizer {
    companion object { private val microphone = ReentrantLock() }
    private val generation = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var isListening = false; private set
    private var closed = false
    @Volatile private var finishRequested = false
    private fun post(token: Int, callback: () -> Unit) { main.post { if (generation.get() == token && !closed) callback() } }
    override fun start() {
        if (closed) return
        cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Разрешите микрофон в настройках EVA"); return
        }
        finishRequested = false
        val token = generation.get()
        isListening = true
        status("Загружаю русскую офлайн-модель…")
        worker.execute {
            if (generation.get() != token) return@execute
            if (!microphone.tryLock()) { post(token) { isListening = false; status("Микрофон уже используется другой сессией EVA") }; return@execute }
            var model: Model? = null; var decoder: org.vosk.Recognizer? = null; var recorder: AudioRecord? = null
            var text: String? = null; var failure: String? = null
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val directory = OfflineModelStore.ensureInstalled(context) { generation.get() != token }
                if (generation.get() != token) return@execute
                model = Model(directory.path)
                if (generation.get() != token) return@execute
                decoder = org.vosk.Recognizer(model, 16000f)
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Микрофон не поддерживает PCM 16 кГц" }
                @Suppress("MissingPermission")
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
                recorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android не разрешил запись" }
                post(token) { status("Я слушаю · офлайн…") }
                val deadline = SystemClock.elapsedRealtime() + 15_000
                val buffer = ByteArray(6400)
                var lastPartial = ""; var nonSilent = false
                while (generation.get() == token && !finishRequested && SystemClock.elapsedRealtime() < deadline) {
                    val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Ошибка чтения микрофона" }
                    if (count == 0) { Thread.sleep(20); continue }
                    var sum = 0.0
                    for (index in 0 until count - 1 step 2) {
                        val value = ((buffer[index].toInt() and 255) or (buffer[index + 1].toInt() shl 8)).toShort().toDouble()
                        sum += value * value
                    }
                    val rms = sqrt(sum / maxOf(1, count / 2)).toFloat()
                    if (rms > 30f) nonSilent = true
                    post(token) { amplitude((rms / 8000f).coerceIn(0f, 1f)) }
                    if (decoder.acceptWaveForm(buffer, count)) {
                        val candidate = JSONObject(decoder.result).optString("text").trim()
                        if (candidate.isNotBlank()) { text = candidate; break }
                    } else {
                        val candidate = JSONObject(decoder.partialResult).optString("partial")
                        if (candidate != lastPartial) { lastPartial = candidate; post(token) { partial(candidate) } }
                    }
                }
                if (generation.get() == token && text == null) {
                    text = JSONObject(decoder.finalResult).optString("text").trim().takeIf { it.isNotBlank() }
                    if (text == null) failure = if (nonSilent) "Речь не распознана" else "Микрофон не передал звук; проверьте микрофон пульта"
                }
            } catch (_: SecurityException) { failure = "Android не разрешил микрофон" }
              catch (_: UnsatisfiedLinkError) { failure = "Офлайн-движок не поддерживает процессор устройства" }
              catch (_: Exception) { failure = "Офлайн-распознавание недоступно; проверьте модель, память и микрофон" }
            finally {
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }
                runCatching { decoder?.close() }; runCatching { model?.close() }; microphone.unlock()
                val recognized = text; val message = failure
                post(token) {
                    isListening = false; amplitude(0f)
                    if (recognized != null) result(recognized)
                    else if (message != null) status(message)
                }
            }
        }
    }
    override fun finish() { if (isListening) finishRequested = true }
    override fun cancel() { generation.incrementAndGet(); isListening = false; amplitude(0f) }
    override fun destroy() { cancel(); closed = true; worker.shutdown() }
}
