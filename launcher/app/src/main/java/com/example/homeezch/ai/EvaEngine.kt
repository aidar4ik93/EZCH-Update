package com.example.homeezch.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly enabled, foreground-only wake listener. Worker owns all native resources. */
object EvaEngine {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "eva-wake").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    private var model: Model? = null
    private var onCommand: ((String) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    @Volatile private var listening = false
    fun hasMicPermission(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    /** Aidar's TV (Haier) is meant to answer «Ева» without the remote, so hands-free is on by default
     *  wherever there is enough memory. It still needs EVA enabled and the microphone permission,
     *  listens only while HOME is in the foreground and can be switched off in EVA settings. */
    @Volatile private var handsFreeDefault: Boolean? = null
    private fun defaultHandsFree(context: Context): Boolean = handsFreeDefault ?: synchronized(this) {
        handsFreeDefault ?: (!com.example.homeezch.deviceLowRam(context.applicationContext)).also { handsFreeDefault = it }
    }
    fun handsFreeEnabled(context: Context): Boolean {
        val prefs = AiPreferences.store(context)
        return if (prefs.contains("hands_free")) prefs.getBoolean("hands_free", false)
        else defaultHandsFree(context)
    }
    fun setHandsFree(context: Context, value: Boolean) {
        AiPreferences.store(context).edit().putBoolean("hands_free", value).apply()
        if (!value) release()
    }
    fun attach(command: (String) -> Unit, status: (String) -> Unit) { onCommand = command; onStatus = status }
    fun detach(command: (String) -> Unit) {
        if (onCommand === command) { onCommand = null; onStatus = null; release() }
    }
    fun stop() { wanted = null; main.removeCallbacks(resumeAfterInput); generation.incrementAndGet(); listening = false }
    /** True only while the wake listener really holds the microphone (between startRecording and release). */
    @Volatile private var micOpen = false
    /**
     * Claude r56: hand the microphone to a command recogniser FAST. [release] closed the ~150 MB Vosk model and ran its
     * callback on the model worker — behind a model load (1–2 s after returning to HOME) — so the remote's button waited
     * up to 1.9 s before Eva listened (logs 02.11 18:26:32 → 34.8), and on Google TV the first words said while holding
     * the button were lost. Now the wake loop is only stopped; the callback runs as soon as its AudioRecord is released
     * (≤ 0.8 s guard), and the model is freed later only if hands-free does not resume ([releaseWhenIdle]).
     */
    fun freeMicrophone(after: () -> Unit) {
        stop()
        releaseWhenIdle()
        val started = android.os.SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (!micOpen || android.os.SystemClock.uptimeMillis() - started > 800) after() else main.postDelayed(this, 15)
            }
        }
        main.post(check)
    }
    /** Claude r51 (owner: «Ева должна работать и с пульта, и без пульта»): while the remote is in use the wake
     *  listener pauses, and resumes [INPUT_QUIET_MS] after the last press. Reason: on Android TV an open microphone
     *  shows the system «микрофон занят» overlay, which makes the box compose every 4K frame on the GPU — exactly
     *  while the user scrolls. During those seconds the remote is in hand and its EVA button works as always; when
     *  the remote is put down, «Ева» works hands-free again. The model stays loaded, so resuming is instant. */
    @Volatile private var wanted: Context? = null
    private const val INPUT_QUIET_MS = 6_000L
    private val resumeAfterInput = Runnable { wanted?.let { startWake(it) } }
    fun pauseForInput() {
        val context = wanted ?: return
        main.removeCallbacks(resumeAfterInput)
        if (listening) { generation.incrementAndGet(); listening = false; EvaStats.count("wake_paused_for_input") }
        wanted = context
        main.postDelayed(resumeAfterInput, INPUT_QUIET_MS)
    }
    /** Claude r50.4: the wake model (~150–180 MB of native memory on a 2 GB box) is kept only while HOME may
     *  need it again soon. Once HOME has been in the background for a while (a film, YouTube) it is freed, so the
     *  app in front gets that memory instead of the system swapping. Returning to HOME reloads it in ~1–2 s
     *  on the background worker; the remote's EVA button does not depend on it. */
    private val idleRelease = Runnable { if (!listening) release() }
    fun releaseWhenIdle(delayMs: Long = 45_000) { main.removeCallbacks(idleRelease); main.postDelayed(idleRelease, delayMs) }
    fun cancelIdleRelease() { main.removeCallbacks(idleRelease) }
    fun release(after: () -> Unit = {}) {
        stop()
        worker.execute {
            runCatching { model?.close() }; model = null
            main.post { after() }
        }
    }
    private fun status(token: Int, message: String) = main.post {
        if (generation.get() == token) onStatus?.invoke(message)
    }
    fun startWake(context: Context) {
        val app = context.applicationContext
        cancelIdleRelease()
        wanted = app
        if (listening || !AiPreferences.enabled(app) || !handsFreeEnabled(app) || !hasMicPermission(app)) return
        // Claude r56: no always-open microphone (only the remote's) → nothing to hear between presses; skip the model.
        if (!VoiceRemote.hasAmbientMic(app)) { EvaStats.count("wake_skipped_no_ambient_mic"); return }
        val token = generation.incrementAndGet()
        listening = true
        EvaStats.count("wake_listen_started", "g=$token")
        worker.execute {
            // Model loading and wake decoding must never compete with HOME's UI/RenderThread.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var recorder: AudioRecord? = null
            var decoder: EvaPcmDecoder? = null
            try {
                if (generation.get() != token) return@execute
                val directory = OfflineModelStore.ensureInstalled(app) { generation.get() != token }
                if (generation.get() != token) return@execute
                val loaded = model ?: Model(directory.path).also { model = it }
                if (generation.get() != token) return@execute
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0)
                @Suppress("MissingPermission")
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 12800))
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED)
                var deadline = Long.MAX_VALUE
                val streaming = EvaPcmDecoder(loaded) {
                    status(token, "Я слушаю · офлайн…")
                    deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                }
                decoder = streaming
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                micOpen = true
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                val buffer = ByteArray(6400)
                val gate = EnergyGate()
                val preroll = java.util.ArrayDeque<ByteArray>()
                var prerollBytes = 0
                while (generation.get() == token && AiPreferences.enabled(app) && handsFreeEnabled(app)) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        val result = streaming.finish()
                        main.post { if (generation.get() == token) { EvaStats.count("wake_command"); VoiceTiming.mark(VoiceTiming.Phase.WAKE_COMMAND); onCommand?.invoke(result) } }
                        break
                    }
                    val size = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(size >= 0)
                    if (size == 0) { Thread.sleep(20); continue }
                    // R52: Eva's own answer is never a wake word or a command (no self-triggering).
                    if (!streaming.awake && EvaTts.speaking()) {
                        preroll.clear(); prerollBytes = 0; streaming.resetWake(); continue
                    }
                    // Silence is not decoded at all (HOME stays smooth); ~0.6 s of pre-roll keeps the start of «Ева».
                    val speaking = gate.update(buffer, size)
                    if (!streaming.awake && !speaking) {
                        if (gate.justClosed) streaming.resetWake()
                        preroll.addLast(buffer.copyOf(size)); prerollBytes += size
                        while (prerollBytes > 19_200) prerollBytes -= preroll.removeFirst().size
                        continue
                    }
                    var early: String? = null
                    while (preroll.isNotEmpty() && early == null) { val chunk = preroll.removeFirst(); early = streaming.accept(chunk, chunk.size) }
                    prerollBytes = 0; preroll.clear()
                    val heard = early
                    if (heard != null) {
                        main.post { if (generation.get() == token) { EvaStats.count("wake_command"); VoiceTiming.mark(VoiceTiming.Phase.WAKE_COMMAND); onCommand?.invoke(heard) } }
                        break
                    }
                    val result = streaming.accept(buffer, size)
                    if (result != null) {
                        main.post { if (generation.get() == token) { EvaStats.count("wake_command"); VoiceTiming.mark(VoiceTiming.Phase.WAKE_COMMAND); onCommand?.invoke(result) } }
                        break
                    }
                }
            } catch (_: Exception) {
                status(token, "Hands-free недоступен: проверьте микрофон и разрешение. Кнопка EVA остаётся доступной.")
            } catch (_: UnsatisfiedLinkError) {
                status(token, "Офлайн-движок не поддерживает процессор устройства")
            } finally {
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }; micOpen = false; runCatching { decoder?.close() }
                if (generation.get() == token) listening = false
            }
        }
    }
    internal fun cleanCommand(text: String) = EvaSpeechText.clean(text)

    /** Adaptive energy detector: decode only while someone may be speaking (plus 1.2 s hangover). */
    internal class EnergyGate {
        private var floor = 200.0
        private var openUntil = 0L
        private var open = false
        var justClosed = false; private set
        fun update(pcm: ByteArray, count: Int, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
            var sum = 0.0; var i = 0
            while (i + 1 < count) { val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble(); sum += s * s; i += 2 }
            val rms = kotlin.math.sqrt(sum / maxOf(1, count / 2))
            val loud = rms > maxOf(floor * 2.2, 180.0)
            if (!loud) floor = floor * 0.97 + rms * 0.03 // learn the room/TV noise only from quiet chunks
            if (loud) openUntil = now + 1200
            val was = open
            open = now < openUntil
            justClosed = was && !open
            return open
        }
    }
}

