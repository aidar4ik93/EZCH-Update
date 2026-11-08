package com.example.homeezch.ai

import com.example.homeezch.ai.ActivationGate.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R52 regression: one remote press reached Eva by two paths and she listened/executed twice. */
class EvaDoubleTriggerTest {
    @Test fun samePressFromTwoServicesOpensOnce() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.KEY_SERVICE, now = 10_000, press = 9_900).accepted)
        // HOME button service executed the same press as an assigned EVA button.
        assertFalse(gate.request(Source.REMOTE_BUTTON, now = 10_005, press = 9_900).accepted)
        assertEquals(1, gate.session)
    }
    @Test fun googleAssistantTakeoverAfterKeyIsAnEcho() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.KEY_SERVICE, now = 10_000, press = 9_900).accepted)
        // Google TV also opened Assistant for that press; the takeover reopens Eva ~1.2 s later.
        assertFalse(gate.request(Source.ASSISTANT_TAKEOVER, now = 11_200).accepted)
        assertEquals(1, gate.session)
    }
    @Test fun assistSessionEchoOfKey() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.KEY_SERVICE, now = 5_000, press = 4_950).accepted)
        assertFalse(gate.request(Source.VOICE_SESSION, now = 5_300).accepted)
    }
    @Test fun takeoverAloneOpensEva() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.ASSISTANT_TAKEOVER, now = 50_000).accepted)
    }
    @Test fun twoSeparatePressesAreTwoSessions() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.KEY_SERVICE, now = 10_000, press = 9_900).accepted)
        // Second press of the same button, even quickly («I have finished» / a new phrase), is a real press.
        assertTrue(gate.request(Source.KEY_SERVICE, now = 10_600, press = 10_500).accepted)
        assertTrue(gate.request(Source.KEY_SERVICE, now = 20_000, press = 19_900).accepted)
        assertEquals(3, gate.session)
    }
    @Test fun differentPathLaterIsANewSession() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.HOME_KEY, now = 1_000, press = 990).accepted)
        assertTrue(gate.request(Source.VOICE_SESSION, now = 4_000).accepted)
        assertTrue(gate.request(Source.ASSISTANT_TAKEOVER, now = 9_000).accepted)
    }
    @Test fun replayedKeyNeverOpensTwice() {
        val gate = ActivationGate()
        assertTrue(gate.request(Source.KEY_SERVICE, now = 1_000, press = 990).accepted)
        assertTrue(gate.request(Source.KEY_SERVICE, now = 3_000, press = 2_990).accepted)
        assertFalse(gate.request(Source.REMOTE_BUTTON, now = 6_000, press = 990).accepted)
    }
    @Test fun oneResultPerRecognitionSession() {
        val results = OneResultGate()
        results.begin()
        assertTrue(results.take())
        assertFalse(results.take()) // late duplicate callback of the same session
        results.begin()
        assertTrue(results.take())   // the same words in a new session are executed again
    }
    @Test fun twoPressesThroughDifferentPathsAreTwoSessions() {
        // GPT R52 GateProbe: HOME_KEY then KEY_SERVICE, 600 ms apart, different downTime.
        val gate = ActivationGate(900L, 2_000L)
        assertTrue(gate.request(Source.HOME_KEY, now = 10_000, press = 9_900).accepted)
        assertTrue(gate.request(Source.KEY_SERVICE, now = 10_600, press = 10_500).accepted)
        assertEquals(2, gate.session)
    }
}
