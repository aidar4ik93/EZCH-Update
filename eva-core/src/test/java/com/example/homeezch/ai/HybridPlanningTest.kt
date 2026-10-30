package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class HybridPlanningTest {
    @Test fun entirePlanIsRejectedBeforeAnyActionWhenOneStepIsInvalid() {
        assertTrue(PlanPreflight.validate(Resolution.Plan(listOf(Action.Settings, Action.Navigate(Direction.RIGHT, 99)))) is Resolution.Rejected)
        assertTrue(PlanPreflight.validate(Resolution.Plan(List(5) { Action.Navigate(Direction.RIGHT, 10) })) is Resolution.Rejected)
        assertEquals(Resolution.Plan(listOf(Action.Cancel)), PlanPreflight.validate(Resolution.Plan(listOf(Action.Cancel, Action.Settings))))
    }
    @Test fun longInputsAndNaturalEvaPrefixes() {
        assertTrue(LocalIntentResolver().resolve("открой " + "а".repeat(601)) is Resolution.Rejected)
        assertEquals(Resolution.Plan(listOf(Action.Settings)), LocalIntentResolver().resolve("Эй, Ева, открой настройки"))
        assertEquals(Resolution.Plan(listOf(Action.Settings)), LocalIntentResolver().resolve("Слушай Ева открой настройки"))
    }
}
