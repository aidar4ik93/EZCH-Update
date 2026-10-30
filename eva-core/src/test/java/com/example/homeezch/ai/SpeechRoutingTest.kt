package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class SpeechRoutingTest {
    @Test fun noConsentNeverChoosesNetworkEvenWhenConnected() {
        assertEquals(SpeechRoute.VOSK_RUSSIAN, SpeechRouting.choose("ru-RU", false, true, true))
        assertEquals(SpeechRoute.UNAVAILABLE, SpeechRouting.choose("en-US", false, true, false))
    }
    @Test fun lossOfInternetChoosesBundledRussianRecognizer() {
        assertEquals(SpeechRoute.SYSTEM_NETWORK, SpeechRouting.choose("ru-RU", true, true, false))
        assertEquals(SpeechRoute.VOSK_RUSSIAN, SpeechRouting.choose("ru-RU", true, false, false))
    }
    @Test fun explicitOfflinePreferenceDoesNotUploadAudio() {
        assertEquals(SpeechRoute.VOSK_RUSSIAN, SpeechRouting.choose("ru-RU", true, true, false, true))
        assertEquals(SpeechRoute.SYSTEM_ON_DEVICE, SpeechRouting.choose("en-US", true, false, true))
    }
}
