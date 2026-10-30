package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class EvaEnergyGateTest {
    private fun chunk(amplitude: Int): ByteArray {
        val out = ByteArray(3200) // 100 ms, 16 kHz mono PCM16
        for (i in 0 until out.size / 2) {
            val s = if (i % 2 == 0) amplitude else -amplitude
            out[2 * i] = (s and 0xff).toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }
    @Test fun silenceStaysClosedSpeechOpensAndHangsOver() {
        val gate = EvaEngine.EnergyGate()
        var t = 0L
        repeat(30) { assertFalse(gate.update(chunk(60), 3200, t)); t += 100 }   // quiet room
        assertTrue(gate.update(chunk(3000), 3200, t)); t += 100                 // «Ева»
        repeat(10) { assertTrue("hangover keeps decoding", gate.update(chunk(60), 3200, t)); t += 100 }
        repeat(5) { gate.update(chunk(60), 3200, t); t += 100 }
        assertFalse(gate.update(chunk(60), 3200, t))
    }
    @Test fun steadyTvNoiseIsLearnedNotTreatedAsSpeech() {
        val gate = EvaEngine.EnergyGate()
        var t = 0L
        repeat(200) { gate.update(chunk(150), 3200, t); t += 100 }
        repeat(20) { gate.update(chunk(150), 3200, t); t += 100 }
        assertFalse(gate.update(chunk(150), 3200, t))
        assertTrue(gate.update(chunk(900), 3200, t + 100))
    }
}
