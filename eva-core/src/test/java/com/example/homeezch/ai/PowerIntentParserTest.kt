package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class PowerIntentParserTest {
    @Test fun rebootAndSleep() {
        listOf("перезагрузи телевизор", "Ева, перезагрузись", "перезапусти приставку", "перезагрузи приставку", "перезапусти тв", "сделай рестарт", "перезагрузка").forEach { assertEquals(it, Action.Power("reboot"), PowerIntentParser.parse(it)) }
        listOf("выключи телевизор", "Ева, выключись", "усни", "спокойной ночи", "выключи", "выключи всё", "отключи приставку", "погаси экран", "выруби телик").forEach { assertEquals(it, Action.Power("shutdown"), PowerIntentParser.parse(it)) }
        assertEquals(Action.Power("wake"), PowerIntentParser.parse("включи телевизор"))
        assertEquals(Action.Power("wake"), PowerIntentParser.parse("Ева, проснись"))
    }
    @Test fun timers() {
        mapOf("30 минут" to 30, "час" to 60, "полчаса" to 30, "полтора часа" to 90, "два часа" to 120, "пятнадцать минут" to 15, "1440 минут" to 1440).forEach { (duration, minutes) ->
            assertEquals(duration, Action.Power("timer", minutes), PowerIntentParser.parse("Ева, выключи телевизор через $duration"))
        }
        assertEquals(Action.Power("timer", 30), PowerIntentParser.parse("выключись через полчаса"))
        assertEquals(Action.Power("cancel_timer"), PowerIntentParser.parse("отмени таймер"))
        assertEquals(Action.Power("timer_left"), PowerIntentParser.parse("сколько осталось до выключения"))
    }
    @Test fun neverTreatsForeignTargetsNegationsOrQuotesAsPower() {
        listOf("выключи звук", "включи фильм", "выключи свет", "включи первый канал", "выключи звук телевизора", "выключи свет у телевизора",
            "включи фильм на телевизоре", "включи музыку на телевизоре", "включи кондиционер", "выключи субтитры", "перезагрузи ютуб", "не перезагружай телевизор",
            "не включи телевизор", "как перезагрузить телевизор", "почему телевизор выключился", "найди фильм выключи телевизор", "открой приложение restart",
            "выключи телевизор и включи фильм", "не выключай телевизор", "до выключения еще далеко", "выключи свет через 30 минут").forEach { assertNull(it, PowerIntentParser.parse(it)) }
    }
    @Test fun invalidDurationsNeverBecomeImmediatePower() {
        listOf("0 минут", "-1 минут", "1441 минут", "25 часов", "999999999999999999999999 часов", "2147483648 часов", "неизвестно").forEach {
            assertNull(it, PowerIntentParser.parse("выключи телевизор через $it"))
        }
    }
    @Test fun coreRequiresStrictConfirmationOnceAndExpires() {
        var now = 10000L
        val core = EvaCore(clock = { now })
        for (answer in listOf("нет", "не надо", "отмена", "стоп", "да не надо", "да включи фильм", "какая погода")) {
            assertTrue(core.handle("перезагрузи телевизор") is EvaReply.Confirm)
            assertFalse(answer, core.handle(answer) is EvaReply.Execute)
            assertFalse(core.handle("да") is EvaReply.Execute)
        }
        for (answer in listOf("да", "давай", "конечно", "ага", "перезагружай")) {
            assertTrue(core.handle("перезагрузи телевизор") is EvaReply.Confirm)
            assertEquals(EvaReply.Execute(listOf(Action.Power("reboot"))), core.handle(answer))
            assertFalse(core.handle("да") is EvaReply.Execute)
        }
        core.handle("выключи телевизор"); now += 8001
        assertFalse(core.handle("да") is EvaReply.Execute)
        core.handle("выключи телевизор"); core.cancelPowerConfirmation()
        assertFalse(core.handle("да") is EvaReply.Execute)
    }
    @Test fun coreTimerRoutingDoesNotAskOrFallIntoSmartHome() {
        assertEquals(EvaReply.Execute(listOf(Action.Power("timer", 60))), EvaCore().handle("Ева, выключи через час"))
        assertEquals(EvaReply.Execute(listOf(Action.Power("cancel_timer"))), EvaCore().handle("отмени таймер"))
        assertEquals(EvaReply.Execute(listOf(Action.Power("timer_left"))), EvaCore().handle("сколько осталось до выключения"))
    }
    @Test fun repeatedNoAndTimeoutNeverExecute() {
        var now = 10000L
        val core = EvaCore(clock = { now })
        repeat(1000) {
            assertTrue(core.handle("Ева, перезагрузись") is EvaReply.Confirm)
            if (it % 2 == 0) assertTrue(core.handle("нет") is EvaReply.Say)
            else { now += 8001; assertFalse(core.handle("да") is EvaReply.Execute) }
        }
    }
    @Test fun confirmationClockStartsAfterSpeech() {
        var now = 10000L
        val core = EvaCore(clock = { now })
        core.handle("выключи телевизор")
        now += 9000; core.armPowerConfirmation(); now += 7000
        assertEquals(EvaReply.Execute(listOf(Action.Power("shutdown"))), core.handle("выключай"))
    }
    @Test fun powerInterruptsTheRiddleAndRegistryRejectsMalformedActions() {
        val core = EvaCore()
        core.handle("загадай загадку")
        assertTrue(core.handle("слушай перезагрузи телевизор") is EvaReply.Confirm)
        assertTrue(core.handle("нет") is EvaReply.Say)
        assertFalse(LocalActionRegistry.allowed(Action.Power("timer", 0)))
        assertFalse(LocalActionRegistry.allowed(Action.Power("timer", 1441)))
        assertFalse(LocalActionRegistry.allowed(Action.Power("reboot", 1)))
    }
}
