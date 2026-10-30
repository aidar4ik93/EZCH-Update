package com.example.homeezch.ai

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPT R27: error 7 while speech was present gets one automatic re-listen; silence and repeats do not. */
class RelistenTest {
    @Test fun speechThenNoMatchRelistensOnce() {
        assertTrue(shouldRelisten(SpeechRecognizer.ERROR_NO_MATCH, speechBegan = true, peakRms = 10f, retries = 0))
        assertFalse(shouldRelisten(SpeechRecognizer.ERROR_NO_MATCH, speechBegan = true, peakRms = 10f, retries = 1))
    }
    @Test fun silenceOrOtherErrorsDoNot() {
        assertFalse(shouldRelisten(SpeechRecognizer.ERROR_NO_MATCH, speechBegan = false, peakRms = -100f, retries = 0))
        assertFalse(shouldRelisten(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, speechBegan = true, peakRms = 10f, retries = 0))
    }
}
