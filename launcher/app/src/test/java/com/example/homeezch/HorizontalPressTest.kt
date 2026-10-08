package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class HorizontalPressTest {
    @Test fun heldRemoteRepeatsAreLimited() {
        val press = HorizontalPress()
        assertTrue(press.accept(1000, 0))
        assertFalse(press.accept(1001, 1))
        assertFalse(press.accept(1099, 2))
        assertTrue(press.accept(1100, 3))
    }
    @Test fun freshPressAndDirectionChangeRemainResponsive() {
        val press = HorizontalPress()
        assertTrue(press.accept(1000, 0))
        assertTrue(press.accept(1005, 0))
        assertFalse(press.accept(1006, 1))
        assertTrue(press.accept(1105, 2))
    }
}
