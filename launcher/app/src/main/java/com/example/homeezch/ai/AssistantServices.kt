package com.example.homeezch.ai

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View

/** System-managed shell. No hotword engine or background microphone. */
class EzchVoiceInteractionService : VoiceInteractionService()
class EzchVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = EzchVoiceSession(this)
}
class EzchVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }
    override fun onCreateContentView(): View {
        // The system session has no assistant window; rendering belongs to the tiny overlay.
        return android.view.View(context)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AiPreferences.store(context).edit().putLong("session_seen", System.currentTimeMillis()).apply()
        // R27: never jump to the desktop from inside an app; EvaEntry keeps the user where he is.
        EvaEntry.open(context)
        hide()
    }
    override fun onBackPressed() { EvaAppService.current?.hideMiniPanel(); hide() }
}

/** Role metadata requires a recognition component. Broker uses on-device STT only. */
class EzchRecognitionService : android.speech.RecognitionService() {
    private var recognizer: android.speech.SpeechRecognizer? = null
    override fun onStartListening(intent: android.content.Intent, callback: Callback) {
        stop()
        if (!AiPreferences.enabled(this) || android.os.Build.VERSION.SDK_INT < 31 ||
            !android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            callback.error(android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE); return
        }
        recognizer = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer!!.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { callback.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() { callback.beginningOfSpeech() }
            override fun onRmsChanged(value: Float) { callback.rmsChanged(value) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback.endOfSpeech() }
            override fun onError(error: Int) { callback.error(error); stop() }
            override fun onResults(results: Bundle?) { callback.results(results ?: Bundle()); stop() }
            override fun onPartialResults(results: Bundle?) { callback.partialResults(results ?: Bundle()) }
            override fun onEvent(type: Int, params: Bundle?) {}
        })
        recognizer!!.startListening(android.content.Intent(intent).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"))
    }
    override fun onStopListening(callback: Callback) { recognizer?.stopListening() }
    override fun onCancel(callback: Callback) { stop() }
    private fun stop() { recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { stop(); super.onDestroy() }
}
