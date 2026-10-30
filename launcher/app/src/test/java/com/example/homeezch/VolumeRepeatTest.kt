package com.example.homeezch

import com.example.homeezch.VolumeRepeat.Next
import org.junit.Assert.*
import org.junit.Test

class VolumeRepeatTest {
    @Test fun heldKeyWithoutSystemRepeatKeepsStepping() {
        val r = VolumeRepeat()
        assertTrue(r.down(100, 1))
        assertEquals(Next.WAIT, r.tick(11))            // the press itself already moved 10 → 11
        var volume = 11
        repeat(5) {
            assertEquals(Next.STEP, r.tick(volume))
            volume++; r.stepped(volume)
        }
        assertEquals(16, volume)
    }
    @Test fun systemThatRepeatsByItselfIsLeftAlone() {
        val r = VolumeRepeat()
        r.down(100, 1)
        assertEquals(Next.WAIT, r.tick(11))
        assertEquals(Next.STOP, r.tick(13))            // volume moved without us: firmware repeats
        assertEquals(Next.STOP, r.tick(14))
    }
    @Test fun outsideChangeDuringOurRepeatStopsIt() {
        val r = VolumeRepeat()
        r.down(100, -1)
        r.tick(9); assertEquals(Next.STEP, r.tick(9)); r.stepped(8)
        assertEquals(Next.STOP, r.tick(6))
    }
    @Test fun samePressFromSecondServiceIsIgnored() {
        val r = VolumeRepeat()
        assertTrue(r.down(100, 1))
        assertFalse(r.down(100, 1))
        assertTrue(r.down(200, 1))
    }
    @Test fun releaseStops() {
        val r = VolumeRepeat()
        r.down(100, 1); r.tick(5); r.stop()
        assertEquals(Next.STOP, r.tick(5))
    }
    @Test fun volumeThatDoesNotMoveStillStepsButIsCapped() {
        // Max volume or CEC-controlled sound: steps are harmless, and a stuck key cannot repeat forever.
        val r = VolumeRepeat()
        r.down(100, 1); r.tick(15)
        var steps = 0
        while (r.tick(15) == Next.STEP) { r.stepped(15); steps++ }
        assertEquals(VolumeRepeat.MAX_STEPS, steps)
    }
}
