package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaSpeakTest {
    private fun say(text: String) = EvaSpeak.prepare(text).joinToString(" ")

    @Test fun brandsAndUnitsBecomeRussianWords() {
        assertEquals("Переключаю на эйч ди эм ай 2", say("Переключаю на HDMI 2"))
        assertEquals("Открываю ютуб", say("Открываю YouTube"))
        assertEquals("Громкость: 40 процентов", say("Громкость: 40%"))
        assertEquals("Ева. Я помню: кино, это зона", say("Ai EvA. Я помню: «кино» — это Zona"))
        assertTrue(say("Свободно 420 из 1900 МБ, на диске 1,2 ГБ").contains("мегабайт") )
        assertTrue(say("Интернет работает через Wi-Fi, около 30 Мбит/с").contains("вай фай") )
        assertEquals("Сейчас 6:18", say("Сейчас 6:18"))
    }
    @Test fun wordsInsideOtherWordsAreNotTouched() {
        assertEquals("Тавлея и автомат", say("Тавлея и автомат"))   // «av» inside a word stays
        assertEquals("Окно открыто", say("Окно открыто"))
    }
    @Test fun noiseIsRemoved() {
        assertEquals("Готово", say("✅ **Готово** https://example.com/x"))
        assertEquals(emptyList<String>(), EvaSpeak.prepare("  🙂 ** "))
    }
    @Test fun longAnswersAreSplitIntoShortPhrases() {
        val long = "Первое предложение. " + "Это очень длинная часть ответа, которая идёт через запятые, ".repeat(8) + "конец!"
        val chunks = EvaSpeak.prepare(long)
        assertEquals("Первое предложение.", chunks.first())
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 180 })
        assertTrue(chunks.last().endsWith("конец!"))
    }
}
