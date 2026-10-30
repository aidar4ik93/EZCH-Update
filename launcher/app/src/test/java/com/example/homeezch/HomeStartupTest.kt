package com.example.homeezch

import org.junit.Test
import org.junit.Assert.*

class HomeStartupTest {
    @Test fun firstServiceConnectionDuringBootStartsOnce() {
        assertTrue(startupEligible(true, 77, 76, -2, 35_000))
        assertFalse(startupEligible(true, 77, 77, 77, 36_000))
    }
    @Test fun serviceReconnectionDuringNormalUseDoesNotInterruptApp() {
        assertFalse(startupEligible(true, 77, 76, -2, 3_600_000))
    }
    @Test fun pendingBootCanWaitForScreenOn() {
        assertTrue(startupEligible(true, 77, 76, 77, 3_600_000))
        assertFalse(startupEligible(true, 78, 76, 77, 3_600_000))
    }
    @Test fun disabledOrUnknownBootNeverStarts() {
        assertFalse(startupEligible(false, 77, 76, 77, 35_000))
        assertFalse(startupEligible(true, null, 76, 77, 35_000))
    }
}
