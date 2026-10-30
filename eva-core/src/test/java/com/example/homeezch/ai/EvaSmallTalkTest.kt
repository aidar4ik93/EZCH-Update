package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class EvaSmallTalkTest {
    private val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 24, 21, 5) } // суббота

    @Test fun time() {
        for (q in listOf("который час", "Ева, который час?", "сколько времени", "сколько сейчас времени", "подскажи который час", "время"))
            assertEquals(q, "Сейчас 21:05", EvaSmallTalk.answer(q, now))
    }
    @Test fun date() {
        assertEquals("Сегодня 24 октября, суббота", EvaSmallTalk.answer("какое сегодня число", now))
        assertEquals("Сегодня суббота, 24 октября", EvaSmallTalk.answer("какой сегодня день недели", now))
        assertEquals("Сейчас 2026 год", EvaSmallTalk.answer("какой сейчас год", now))
    }
    @Test fun math() {
        assertEquals("2 + 2 = 4", EvaSmallTalk.answer("сколько будет 2+2", now))
        assertEquals("25 × 4 = 100", EvaSmallTalk.answer("сколько будет 25 умножить на 4", now))
        assertEquals("100 ÷ 8 = 12,5", EvaSmallTalk.answer("100 разделить на 8", now))
        assertEquals("7 − 10 = -3", EvaSmallTalk.answer("посчитай 7 минус 10", now))
        assertEquals("2 × 2 = 4", EvaSmallTalk.answer("сколько будет два на два", now))
        assertEquals("На ноль делить нельзя", EvaSmallTalk.answer("5 разделить на 0", now))
        assertEquals("15 процентов от 200 — это 30", EvaSmallTalk.answer("15 процентов от 200", now))
    }
    @Test fun chat() {
        assertTrue(EvaSmallTalk.answer("кто ты", now)!!.startsWith("Я Ева"))
        assertTrue(EvaSmallTalk.answer("что ты умеешь", now)!!.contains("открой YouTube"))
        assertEquals("Пожалуйста!", EvaSmallTalk.answer("спасибо", now))
    }
    @Test fun notSmallTalk() {
        for (q in listOf("расскажи анекдот", "сколько длится время полёта до луны", "почему небо голубое",
                "два на два", "время первых", "какая погода", ""))
            assertNull(q, EvaSmallTalk.answer(q, now))
    }
}
