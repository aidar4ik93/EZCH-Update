package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EvaRoutineTest {
    private val home = Resolution.Plan(listOf(Action.Home("routine", "дома")))
    @Test fun arrivalPhrases() {
        listOf("я дома", "Я дома!", "вот я и дома", "я уже дома", "я пришёл", "я пришла домой", "я вернулся домой").forEach {
            assertEquals(it, home, EvaNlu.understand(it))
        }
    }
    @Test fun otherPhrasesStayOther() {
        listOf("я пошёл на кухню", "я пошёл спать", "спокойной ночи", "доброе утро").forEach {
            assertNotEquals(it, home, EvaNlu.understand(it))
        }
    }
    @Test fun routineIsAnAllowedHomeOp() = assertEquals(true, "routine" in EvaNlu.HOME_OPS)
}
