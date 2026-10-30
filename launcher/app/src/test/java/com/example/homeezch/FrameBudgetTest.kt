package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameBudgetTest {
    @Test fun extendedOemDeadlineCannotHideDisplayPeriodMisses() {
        assertEquals(16_666_666L, frameBudget(50_000_000L, 60f))
    }
    @Test fun earlierDeadlineStillApplies() {
        assertEquals(12_000_000L, frameBudget(12_000_000L, 60f))
    }
    @Test fun unavailableDeadlineUsesActualDisplayPeriod() {
        assertEquals(20_000_000L, frameBudget(-1L, 50f))
    }
    @Test fun InvalidRefreshRateKeepsSafePositiveBudget() {
        assertEquals(16_666_666L, frameBudget(-1L, Float.NaN))
        assertEquals(16_666_666L, frameBudget(0L, 0f))
    }
}
