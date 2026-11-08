package com.example.homeezch.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One warm speech engine for the whole launcher. Binding the TV's TTS service and loading the Russian
 * voice takes about two seconds (measured 2.4 s on API33), and the first phrase spoken during that time
 * was lost. The engine is therefore created once, warmed with a silent utterance and reused by every EVA
 * panel instead of being created and shut down each time the panel opens.
 * All engine calls run on one worker thread: vendor TTS can block on Binder (confirmed TV ANR earlier).
 */
internal object EvaTts {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "eva-tts").apply { isDaemon = true } }
    @Volatile private var engine: TextToSpeech? = null
    @Volatile var ready = false
        private set
    private var starting = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    /** Progress callbacks of the panel that is speaking now (sphere animation). */
    @Volatile var listener: UtteranceProgressListener? = null

    private fun task(action: () -> Unit) { runCatching { worker.execute { runCatching(action) } } }

    /** [onReady] runs on the worker thread with the result; immediately when the engine is already warm. */
    fun warm(context: Context, onReady: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        synchronized(this) {
            if (ready) { onReady(true); return }
            waiting += onReady
            if (starting) return
            starting = true
        }
        task {
            lateinit var created: TextToSpeech
            created = TextToSpeech(app) { code ->
                task {
                    val e = created
                    val ok = code == TextToSpeech.SUCCESS && EvaSpeechStyle.configure(app, e, locale(app))
                    if (ok) e.playSilentUtterance(60, TextToSpeech.QUEUE_ADD, WARMUP) else runCatching { e.shutdown() }
                    com.example.homeezch.EzchDiag.log(if (ok) "TTS" else "TTS:ОШИБКА",
                        "движок ${runCatching { e.defaultEngine }.getOrNull()} код $code, русский голос ${if (ok) "есть" else "НЕТ"}")
                    val callbacks = synchronized(this) {
                        ready = ok; starting = false
                        if (!ok) engine = null            // a later panel retries instead of staying mute
                        waiting.toList().also { waiting.clear() }
                    }
                    callbacks.forEach { runCatching { it(ok) } }
                }
            }
            created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { if (id != WARMUP) { VoiceTiming.mark(VoiceTiming.Phase.TTS_STARTED); listener?.onStart(id) } }
                override fun onDone(id: String?) { if (id != WARMUP) { utteranceEnded(); listener?.onDone(id) } }
                override fun onStop(id: String?, interrupted: Boolean) { if (id != WARMUP) utteranceEnded() }
                @Deprecated("Platform callback") override fun onError(id: String?) {
                    if (id != WARMUP) { utteranceEnded(); com.example.homeezch.EzchDiag.log("TTS:ОШИБКА", "фраза $id не произнесена"); @Suppress("DEPRECATION") listener?.onError(id) }
                }
                override fun onBeginSynthesis(id: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    if (id != WARMUP) listener?.onBeginSynthesis(id, sampleRateInHz, audioFormat, channelCount)
                }
                override fun onAudioAvailable(id: String?, audio: ByteArray?) { if (id != WARMUP) listener?.onAudioAvailable(id, audio) }
            })
            engine = created
        }
    }

    /** Speaks prepared phrases; [still] is checked before each one so a newer command cancels the rest. */
    fun speak(phrases: List<String>, utterancePrefix: String = "eva-response", append: Boolean = false, still: () -> Boolean) = task {
        val e = engine ?: return@task
        if (!ready || phrases.isEmpty() || !still()) return@task
        VoiceTiming.mark(VoiceTiming.Phase.TTS_REQUESTED)
        val spoken = phrases.size
        if (!append) pending.set(0)
        pending.addAndGet(spoken); pendingSince = android.os.SystemClock.elapsedRealtime()
        phrases.forEachIndexed { i, phrase ->
            if (!still()) { utteranceEnded(); return@forEachIndexed }
            if (still()) e.speak(phrase, if (i == 0 && !append) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$utterancePrefix-$i")
        }
    }

    /** R52: while Eva speaks, the hands-free listener must not hear her own voice («…Ева…» in an answer).
     *  R52.2: a counter kept by the engine's own callbacks — no binder call per audio buffer (the wake loop asks
     *  this ~5 times a second; GPT's idle measurement showed the extra CPU). Safety cap: 90 s. */
    private val pending = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var pendingSince = 0L
    fun speaking(): Boolean = pending.get() > 0 && android.os.SystemClock.elapsedRealtime() - pendingSince < 90_000
    private fun utteranceEnded() { if (pending.decrementAndGet() < 0) pending.set(0) }
    fun stop(then: () -> Unit = {}) = task { runCatching { engine?.stop() }; pending.set(0); then() }

    /** Re-applies voice and tempo after the owner changed them in EVA settings. */
    fun refresh(context: Context, then: () -> Unit = {}) {
        val app = context.applicationContext
        if (!ready) { warm(app) { ok -> if (ok) then() }; return }
        task { val e = engine; if (e != null && ready) EvaSpeechStyle.configure(app, e, locale(app)); then() }
    }

    private fun locale(context: Context) =
        Locale.forLanguageTag(AiPreferences.store(context).getString("language", "ru-RU") ?: "ru-RU")

    private const val WARMUP = "eva-warmup"
}
