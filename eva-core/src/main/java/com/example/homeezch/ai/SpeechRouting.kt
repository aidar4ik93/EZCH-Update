package com.example.homeezch.ai

enum class SpeechRoute { VOSK_RUSSIAN, SYSTEM_ON_DEVICE, SYSTEM_NETWORK, UNAVAILABLE }
object SpeechRouting {
    fun choose(language: String, networkConsent: Boolean, validatedNetwork: Boolean,
        systemOnDevice: Boolean, preferOffline: Boolean = false): SpeechRoute = when {
        networkConsent && validatedNetwork && !preferOffline -> SpeechRoute.SYSTEM_NETWORK
        language.substringBefore('-').equals("ru", true) -> SpeechRoute.VOSK_RUSSIAN
        systemOnDevice -> SpeechRoute.SYSTEM_ON_DEVICE
        networkConsent && validatedNetwork -> SpeechRoute.SYSTEM_NETWORK
        else -> SpeechRoute.UNAVAILABLE
    }
}
