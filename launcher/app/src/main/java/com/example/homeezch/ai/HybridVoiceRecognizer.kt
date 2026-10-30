package com.example.homeezch.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.SpeechRecognizer

/** A connection change affects the next utterance; audio is never retained for replay. */
internal class HybridVoiceRecognizer(private val context: Context, private val status: (String) -> Unit,
    result: (String) -> Unit, amplitude: (Float) -> Unit = {}, partial: (String) -> Unit = {},
    choose: (List<String>) -> String = { it.first() }) : Recognizer {
    private val offline = OfflineVoskRecognizer(context, status, result, amplitude, partial)
    private var active: Recognizer? = null
    private var fallbackUsed = false
    private val network = AndroidVoiceRecognizer(context, status, result, amplitude, partial,
        forceNetwork = true, fallback = ::fallbackToOffline, choose = choose)
    private val localSystem = AndroidVoiceRecognizer(context, status, result, amplitude, partial,
        fallback = ::fallbackToOffline, choose = choose)
    val isListening: Boolean get() = when (val input = active) {
        is OfflineVoskRecognizer -> input.isListening
        is AndroidVoiceRecognizer -> input.isListening
        else -> false
    }
    private fun language() = AiPreferences.store(context).getString("language", "ru-RU") ?: "ru-RU"
    private fun fallbackToOffline(error: Int): Boolean {
        if (fallbackUsed || !language().startsWith("ru", true) || error !in setOf(
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) return false
        fallbackUsed = true
        active = offline
        status("Интернет-распознавание недоступно. Повторите команду…")
        offline.start()
        return true
    }
    override fun start() {
        cancel(); fallbackUsed = false
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val device = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        active = when (SpeechRouting.choose(language(), AiPreferences.networkSpeech(context), connected, device,
            AiPreferences.store(context).getBoolean("prefer_offline", false))) {
            SpeechRoute.VOSK_RUSSIAN -> offline
            SpeechRoute.SYSTEM_ON_DEVICE -> localSystem
            SpeechRoute.SYSTEM_NETWORK -> network
            SpeechRoute.UNAVAILABLE -> { status("Для этого языка локальной модели нет. Сетевой ввод требует разрешения"); null }
        }
        active?.start()
    }
    override fun cancel() { fallbackUsed = true; active?.cancel(); active = null }
    override fun finish() { active?.finish() }
    override fun destroy() { cancel(); offline.destroy(); network.destroy(); localSystem.destroy() }
}
