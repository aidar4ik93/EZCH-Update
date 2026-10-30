package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class CinemaPolicyTest {
    @Test fun dimsOnlyAfterSustainedPlayback() {
        val p = CinemaPolicy(4000, 2500)
        assertEquals(CinemaPolicy.Decision.NONE, p.update(0, true))
        assertEquals(CinemaPolicy.Decision.NONE, p.update(3000, true))
        assertEquals(CinemaPolicy.Decision.DIM, p.update(4000, true))
        assertEquals(CinemaPolicy.Decision.NONE, p.update(9000, true))
    }
    @Test fun shortSoundsNeverTouchLights() {
        val p = CinemaPolicy(4000, 2500)
        p.update(0, true); p.update(2000, true); p.update(3000, false); p.update(3500, true)
        assertEquals(CinemaPolicy.Decision.NONE, p.update(6000, true)) // restarted at 3500
        assertEquals(CinemaPolicy.Decision.DIM, p.update(7500, true))
    }
    @Test fun restoresAfterPauseAndDimsAgain() {
        val p = CinemaPolicy(4000, 2500)
        p.update(0, true); p.update(4000, true)
        assertEquals(CinemaPolicy.Decision.NONE, p.update(5000, false))
        assertEquals(CinemaPolicy.Decision.NONE, p.update(6000, true)) // brief gap, no flicker
        assertEquals(CinemaPolicy.Decision.NONE, p.update(7000, false))
        assertEquals(CinemaPolicy.Decision.RESTORE, p.update(9500, false))
        p.update(10000, true)
        assertEquals(CinemaPolicy.Decision.DIM, p.update(14000, true))
    }
    @Test fun musicAndHomeAreNotFilms() {
        assertFalse(CinemaPolicy.eligible("ru.yandex.music", "com.example.homeezch.usb"))
        assertFalse(CinemaPolicy.eligible("com.example.homeezch.usb", "com.example.homeezch.usb"))
        assertTrue(CinemaPolicy.eligible("ru.kinopoisk.tv", "com.example.homeezch.usb"))
        assertTrue(CinemaPolicy.eligible(null, "com.example.homeezch.usb"))
    }
    @Test fun manualChangeIsRespected() {
        assertTrue(CinemaPolicy.untouched(16, 15)); assertFalse(CinemaPolicy.untouched(80, 15)); assertTrue(CinemaPolicy.untouched(null, 15))
    }
}
