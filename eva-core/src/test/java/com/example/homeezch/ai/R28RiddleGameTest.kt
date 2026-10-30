package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 29.10.2026: EVA asks a riddle, waits for the user's answer and says if it is right — never answers herself. */
class R28RiddleGameTest {
    private var t = 1_000_000L
    private val core = EvaCore(clock = { t })
    private fun say(p: String): EvaReply { t += 3000; return core.handle(p) { emptyList() } }
    private fun text(r: EvaReply) = when (r) { is EvaReply.Clarify -> r.question; is EvaReply.Say -> r.text; else -> r.toString() }
    private fun answerFor(question: String) = EvaRiddles.ALL.first { question.contains(it.question) }

    @Test fun riddleWaitsForTheUser() {
        val q = say("давай поиграем")
        assertTrue(q is EvaReply.Clarify)
        val r = answerFor(text(q))
        assertTrue("answer must not be told: ${text(q)}", !text(q).contains(r.answer))
        val verdict = say("это ${r.answer}")
        assertTrue(text(verdict), text(verdict).startsWith("Верно"))
        assertTrue(say("да") is EvaReply.Clarify)
    }
    @Test fun wrongThenHintThenReveal() {
        val r = answerFor(text(say("загадай загадку")))
        assertTrue(text(say("бегемот")).startsWith("Нет, не угадал"))
        assertTrue(text(say("подскажи")).startsWith("Подсказка"))
        val last = text(say("бегемот"))
        assertTrue(last, last.contains(r.answer))
    }
    @Test fun commandEndsTheGame() {
        say("ты умеешь играть в игру")
        assertEquals(EvaReply.Execute(listOf(Action.Volume(percent = 20))), say("громкость 20"))
    }
    @Test fun questionEndsTheGame() {
        say("загадай загадку")
        assertTrue(text(say("который час")).startsWith("Сейчас"))
    }
    @Test fun stemsAcceptWordForms() {
        val r = EvaRiddles.ALL.first { it.answer == "ёлка" }
        assertTrue(EvaRiddles.correct(r, "наверное ёлочка"))
        assertTrue(!EvaRiddles.correct(r, "лампочка"))
    }
}
