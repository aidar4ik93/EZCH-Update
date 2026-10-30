package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPT R27 on code 100 (voice-final.txt 11:13–11:14): app + channel in one phrase, bare «сколько». */
class R31HandoffTest {
    private val apps = listOf(AppTarget("Лайт HD TV", "ru.light.hdtv/.M"), AppTarget("YouTube", "com.google.android.youtube.tv/.M"))
    private fun reply(p: String) = EvaCore().handle(p) { apps }

    @Test fun bareHowMuchIsArithmetic() {
        for (p in listOf("сколько 2+2", "Сколько 2 + 2", "сколько будет 2 плюс 2")) {
            val r = reply(p)
            assertTrue("$p -> $r", r.toString().contains("4"))
            assertTrue("$p -> $r", r !is EvaReply.Execute)
        }
    }
    @Test fun appAndChannelAreSplit() {
        for (p in listOf("открой лайт hd tv канал тнт", "Открой Light HD TV канал ТНТ", "открой лайт хд тв канал тнт")) {
            val r = reply(p) as EvaReply.Execute
            assertEquals(p, listOf(Action.Launch("лайт hd tv"), Action.PlayChannel("тнт")), r.actions)
        }
    }
}
