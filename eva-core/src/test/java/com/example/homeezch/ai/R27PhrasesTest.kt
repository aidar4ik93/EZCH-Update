package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phrases from the owner's live test on Homatics (29.10.2026) that EVA did not understand. */
class R27PhrasesTest {
    private fun run(p: String) = EvaCore().handle(p) { emptyList() }
    private fun actions(p: String) = (run(p) as EvaReply.Execute).actions

    @Test fun volumeMaxAndMin() {
        assertEquals(listOf(Action.Volume(percent = 100)), actions("сделай громкость на максимум"))
        assertEquals(listOf(Action.Volume(percent = 100)), actions("сделай громкость телевизор на максимум"))
        assertEquals(listOf(Action.Volume(percent = 100)), actions("звук на полную"))
        assertEquals(listOf(Action.Volume(percent = 5)), actions("громкость на минимум"))
    }
    @Test fun cleanupAndOptimize() {
        assertEquals(listOf(Action.ClearCache), actions("очисти мусор от телевизора"))
        assertEquals(listOf(Action.ClearCache), actions("почисти телевизор"))
        assertEquals(listOf(Action.Optimize("ads")), actions("ускорь телевизор"))
    }
    @Test fun searchWithoutApp() {
        assertEquals(listOf(Action.Search("мстители")), actions("открыл поиск и найди фильм мстители"))
        assertEquals(listOf(Action.Search("мстителей")), actions("поиск мстителей"))
    }
    @Test fun chatterIsAnsweredNotExecuted() {
        for (p in listOf("привет как дела", "привет ева как дела", "а ты что ещё умеешь", "ева пора тебе развиваться", "для твоей работы нужен интернет")) {
            val r = run(p)
            assertTrue("$p → $r", r is EvaReply.Say && !(r as EvaReply.Say).text.startsWith("Не поняла"))
        }
        // R28: the game is a dialog now — EVA asks the riddle and waits for the answer.
        assertTrue(run("ты умеешь играть в игру") is EvaReply.Clarify)
    }
}
