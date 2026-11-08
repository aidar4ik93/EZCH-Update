package com.example.homeezch.ai

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View

/** System-managed shell. No hotword engine or background microphone. */
class EzchVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Claude r56: Eva never reads the screen. Without this the system captures assist data and a 4K screenshot of
        // the foreground app on every press of the remote's microphone button — slow on a 2 GB box, before Eva even starts.
        runCatching { setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT) }
    }
}
class EzchVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        VoiceTiming.mark(VoiceTiming.Phase.SESSION_NEW)
        return EzchVoiceSession(this)
    }
}
class EzchVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        VoiceTiming.mark(VoiceTiming.Phase.SESSION_PREPARE)
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }
    override fun onCreateContentView(): View {
        // The system session has no assistant window; rendering belongs to the tiny overlay.
        return android.view.View(context)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        VoiceTiming.mark(VoiceTiming.Phase.SESSION_SHOW)
        super.onShow(args, showFlags)
        AiPreferences.store(context).edit().putLong("session_seen", System.currentTimeMillis()).apply()
        // R27: never jump to the desktop from inside an app; EvaEntry keeps the user where he is.
        EvaEntry.open(context, ActivationGate.Source.VOICE_SESSION)
        hide()
    }
    override fun onBackPressed() { EvaAppService.current?.hideMiniPanel(); hide() }
}

/**
 * Role metadata requires a recognition component. When Eva holds the assistant role Android may also route OTHER apps'
 * voice search (YouTube, keyboards) here. Claude r55: forward to the system's real recogniser (Google — never this
 * service, no recursion), falling back to on-device STT; before, without on-device Russian, their voice search failed.
 */
class EzchRecognitionService : android.speech.RecognitionService() {
    private var recognizer: android.speech.SpeechRecognizer? = null
    private fun delegate(): android.speech.SpeechRecognizer? {
        val providers = packageManager.queryIntentServices(android.content.Intent("android.speech.RecognitionService"), 0)
            .filter { it.serviceInfo.packageName != packageName && it.serviceInfo.exported && it.serviceInfo.enabled }
        val provider = providers.firstOrNull { it.serviceInfo.packageName == "com.google.android.katniss" }
            ?: providers.firstOrNull { it.serviceInfo.packageName == "com.google.android.tts" }
            ?: providers.firstOrNull()
        if (provider != null) return android.speech.SpeechRecognizer.createSpeechRecognizer(this,
            android.content.ComponentName(provider.serviceInfo.packageName, provider.serviceInfo.name))
        return if (android.os.Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else null
    }
    override fun onStartListening(intent: android.content.Intent, callback: Callback) {
        stop()
        recognizer = runCatching { delegate() }.getOrNull() ?: run { callback.error(android.speech.SpeechRecognizer.ERROR_CLIENT); return }
        recognizer!!.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { callback.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { callback.beginningOfSpeech() } }
            override fun onRmsChanged(value: Float) { runCatching { callback.rmsChanged(value) } }
            override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) runCatching { callback.bufferReceived(buffer) } }
            override fun onEndOfSpeech() { runCatching { callback.endOfSpeech() } }
            override fun onError(error: Int) { runCatching { callback.error(error) }; stop() }
            override fun onResults(results: Bundle?) { runCatching { callback.results(results ?: Bundle()) }; stop() }
            override fun onPartialResults(results: Bundle?) { runCatching { callback.partialResults(results ?: Bundle()) } }
            override fun onEvent(type: Int, params: Bundle?) {}
        })
        // The caller's language and options are kept as they are.
        recognizer!!.startListening(android.content.Intent(intent))
    }
    override fun onStopListening(callback: Callback) { recognizer?.stopListening() }
    override fun onCancel(callback: Callback) { stop() }
    private fun stop() { recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { stop(); super.onDestroy() }
}
