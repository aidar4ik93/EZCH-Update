package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class EvaHomeVoiceTest {
    private fun plan(text: String) = (LocalIntentResolver().resolve(text) as? Resolution.Plan)?.actions
    @Test fun airConditionerTemperature() {
        assertEquals(listOf(Action.Home("set_temp", "кондиционер", 22)), plan("Ева, поставь кондиционер на 22 градуса"))
        assertEquals(listOf(Action.Home("set_temp", "кондиционер", 24)), plan("сделай температуру на кондиционере 24"))
    }
    @Test fun vacuum() {
        for (q in listOf("запусти уборку", "пропылесось", "запусти пылесос", "сделай уборку")) assertEquals(q, listOf(Action.Home("on", "пылесос")), plan(q))
        for (q in listOf("отправь пылесос на базу", "останови уборку")) assertEquals(q, listOf(Action.Home("off", "пылесос")), plan(q))
    }
}
