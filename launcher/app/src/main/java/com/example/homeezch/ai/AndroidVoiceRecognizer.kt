package com.example.homeezch.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One user-initiated utterance. No audio buffers or transcripts persisted. */
class AndroidVoiceRecognizer(private val context: Context, private val status: (String) -> Unit,
    private val result: (String) -> Unit,
    private val amplitude: (Float) -> Unit = {}, private val partial: (String) -> Unit = {},
    private val forceNetwork: Boolean = false, private val fallback: ((Int) -> Boolean)? = null,
    /** Picks the best of the recognizer's hypotheses (EVA Core understands «громкость 20» better than «громко 20»). */
    private val choose: (List<String>) -> String = { it.first() }) : Recognizer, RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognizer? = null
    private var active = false
    val isListening: Boolean get() = active || retryPending
    private var generation = 0
    /** Last partial transcript: some TV recognizers end with «no match» although they showed the words. */
    private var heard = ""
    private var busyRetries = 0
    private var noMatchRetries = 0
    private var retryPending = false
    // R26 diagnosis: did any sound reach the recognizer? Separates «remote mic sent silence»
    // from «heard but not understood» (both end as error 7 on Homatics).
    private var peakRms = -100f
    private var speechBegan = false
    private var providerName = ""
    private val timeout = Runnable { cancel(); status("Время ожидания истекло") }
    override fun start() { busyRetries = 0; noMatchRetries = 0; begin() }
    private fun begin() {
        cancel()
        heard = ""
        peakRms = -100f; speechBegan = false; providerName = ""
        AiPreferences.store(context).edit().remove("last_speech_error").putString("speech_state", "starting").apply()
        if (!AiPreferences.enabled(context)) { status("Помощник выключен"); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Разрешите микрофон в настройках EVA"); return
        }
        val local = !forceNetwork && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        AiPreferences.store(context).edit().putString("speech_mode", if (local) "on-device" else "system").apply()
        if (!local && !AiPreferences.networkSpeech(context)) {
            status("Локальный распознаватель недоступен. Сетевой ввод можно разрешить в настройках"); return
        }
        // The remote recognition provider manages audio focus itself. Taking focus here
        // makes its own request look like an interruption and cancels valid TV input.
        try {
            speech = speech ?: if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else {
                // Explicit provider prevents recursion through our role metadata service.
                val providers = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
                    .filter { it.serviceInfo.packageName != context.packageName && it.serviceInfo.exported && it.serviceInfo.enabled }
                val configured = android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                    ?.let(android.content.ComponentName::unflattenFromString)
                val provider = providers.firstOrNull { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == configured }
                    ?: providers.firstOrNull { it.serviceInfo.packageName == "com.google.android.katniss" }
                    ?: providers.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
                    ?: providers.singleOrNull()
                if (provider == null) { destroy(); status("Выберите доступный системный распознаватель"); return }
                providerName = provider.serviceInfo.packageName
                SpeechRecognizer.createSpeechRecognizer(context, android.content.ComponentName(provider.serviceInfo.packageName, provider.serviceInfo.name))
            }
            active = true
            val token = generation
            val owner = this
            speech!!.setRecognitionListener(object : RecognitionListener {
                private fun live() = token == generation && active
                override fun onReadyForSpeech(params: Bundle?) { if (live()) owner.onReadyForSpeech(params) }
                override fun onBeginningOfSpeech() { if (live()) owner.onBeginningOfSpeech() }
                override fun onRmsChanged(value: Float) { if (live()) owner.onRmsChanged(value) }
                override fun onBufferReceived(buffer: ByteArray?) { if (live()) owner.onBufferReceived(buffer) }
                override fun onEndOfSpeech() { if (live()) owner.onEndOfSpeech() }
                override fun onError(error: Int) { if (live()) owner.onError(error) }
                override fun onResults(results: Bundle?) { if (live()) owner.onResults(results) }
                override fun onPartialResults(results: Bundle?) { if (live()) owner.onPartialResults(results) }
                override fun onEvent(type: Int, params: Bundle?) { if (live()) owner.onEvent(type, params) }
            })
            status("Подключаю микрофон…")
            VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_START)
            speech!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, AiPreferences.store(context).getString("language", "ru-RU"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Let the owner finish a phrase with a short pause («громкость … двадцать»); ignored by engines that do not support it.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            })
            handler.postDelayed(timeout, 15000)
        } catch (_: RuntimeException) { destroy(); status("Не удалось подключить распознаватель") }
    }
    override fun cancel() {
        val wasActive = active
        retryPending = false
        releaseSession()
        if (wasActive) runCatching { speech?.cancel() }
    }
    private fun releaseSession() {
        active = false; generation++; handler.removeCallbacks(timeout); amplitude(0f)
    }
    override fun finish() {
        if (retryPending) { cancel(); status("Нажмите микрофон и повторите команду"); return }
        if (!active) return
        runCatching { speech?.stopListening() }
        status("Распознаю…")
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 2500)
    }
    override fun destroy() {
        active = false; retryPending = false
        generation++
        handler.removeCallbacks(timeout)
        speech?.destroy(); speech = null
        amplitude(0f)
    }
    override fun onReadyForSpeech(params: Bundle?) { if (active) { VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_READY); AiPreferences.store(context).edit().putString("speech_state", "listening").apply(); status("Говорите…") } }
    override fun onBeginningOfSpeech() { if (active) { speechBegan = true; VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_SPEECH_BEGIN); status("Слушаю…") } }
    override fun onEndOfSpeech() { if (active) { VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_SPEECH_END); status("Распознаю…")
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 2500) } }
    override fun onRmsChanged(rmsdB: Float) { if (active) { if (rmsdB > peakRms) peakRms = rmsdB; amplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) } }
    override fun onBufferReceived(buffer: ByteArray?) { /* Never save raw audio. */ }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (active) { partial(text); if (text.isNotBlank()) heard = text }
        if (active && normalize(text) in listOf("стоп", "отмена")) { cancel(); status("Отменено") }
    }
    override fun onResults(results: Bundle?) {
        if (!active) return
        VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_RESULT)
        val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
        val text = if (all.isEmpty()) heard.takeIf { it.isNotBlank() } else runCatching { choose(all) }.getOrDefault(all.first())
        com.example.homeezch.EzchDiag.log("STT", "варианты: ${all.joinToString(" | ").take(300)} → «${text.orEmpty().take(120)}»")
        releaseSession() // Keep the bound provider warm until panel destruction.
        VoiceTiming.mark(VoiceTiming.Phase.SYSTEM_RELEASED)
        if (text.isNullOrBlank()) status("Не расслышала. Нажмите ОК и скажите ещё раз") else {
            AiPreferences.store(context).edit().putBoolean("ru_result", true).putString("speech_state", "recognized").apply()
            result(text)
        }
    }
    override fun onError(error: Int) {
        if (!active) return
        AiPreferences.store(context).edit().putInt("last_speech_error", error).putString("speech_state", "error").apply()
        val partialText = heard
        val silent = !speechBegan && peakRms < 2f
        com.example.homeezch.EzchDiag.log("STT", "ошибка $error; распознаватель: ${providerName.ifBlank { "на устройстве" }}; " +
            "начало речи: ${if (speechBegan) "да" else "нет"}; пик громкости: ${if (peakRms <= -100f) "нет данных" else "%.1f дБ".format(peakRms)}")
        // Keep the provider binding on BUSY; recreating it while it closes increases contention.
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 2) {
            releaseSession(); busyRetries++; retryPending = true
            status("Подключаю микрофон…")
            val g = generation
            handler.postDelayed({ if (g == generation && retryPending) { retryPending = false; begin() } }, 180L * busyRetries)
            return
        }
        destroy()
        // The words were on screen, only the final result was lost: use them instead of «не расслышала».
        if (error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && partialText.isNotBlank()) {
            com.example.homeezch.EzchDiag.log("STT", "ошибка $error, беру частичный текст «${partialText.take(120)}»")
            result(partialText); return
        }
        if (fallback?.invoke(error) == true) return
        // GPT R27 (11:15:38, 11:15:57): speech began, peak 10 dB, yet error 7 and no words. One automatic re-listen
        // instead of making the owner press the button again; never for silence, never twice in a row.
        if (shouldRelisten(error, speechBegan, peakRms, noMatchRetries)) {
            noMatchRetries++
            com.example.homeezch.EzchDiag.log("STT", "ошибка $error при речи — слушаю ещё раз")
            status("Не расслышала — повторите, я слушаю…")
            retryPending = true
            handler.postDelayed({ if (retryPending) { retryPending = false; begin() } }, 250L)
            return
        }
        if (silent && error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            status("Микрофон не передал звук. Удерживайте кнопку микрофона на пульте, пока говорите"); return
        }
        status(when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Аудиовход недоступен; микрофон пульта требует проверки"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения микрофона"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Русская модель недоступна"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сетевой распознаватель недоступен"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышала. Нажмите ОК и скажите ещё раз, чуть ближе к микрофону"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Я ничего не услышала. Нажмите ОК и говорите сразу после сигнала"
            else -> "Ошибка распознавателя: $error"
        })
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

/** One automatic re-listen after «no match» while sound clearly reached the recognizer (R31). */
internal fun shouldRelisten(error: Int, speechBegan: Boolean, peakRms: Float, retries: Int): Boolean =
    error == SpeechRecognizer.ERROR_NO_MATCH && retries < 1 && (speechBegan || peakRms >= 2f)
