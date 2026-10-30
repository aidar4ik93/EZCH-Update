package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaPowerTest {
    private var now = 1_000_000L
    private fun core() = EvaCore(clock = { now })
    private fun exec(r: EvaReply) = (r as? EvaReply.Execute)?.actions

    @Test fun rebootIsAskedThenRun() {
        listOf("перезагрузи телевизор", "ева перезагрузи приставку", "перезапусти телевизор", "перезагрузись", "перезагрузи тв пожалуйста").forEach { p ->
            val c = core()
            val r = c.handle(p)
            assertTrue("$p → $r", r is EvaReply.Confirm && (r as EvaReply.Confirm).actions == listOf(Action.Power("reboot")))
            assertEquals(p, listOf(Action.Power("reboot")), exec(c.handle("да")))
        }
    }
    @Test fun shutdownAndSleep() {
        val c = core()
        assertTrue(c.handle("выключи телевизор") is EvaReply.Confirm)
        assertTrue(c.handle("нет") is EvaReply.Say)
        assertEquals(listOf(Action.Power("sleep")), (core().handle("переведи телевизор в спящий режим") as EvaReply.Confirm).actions)
    }
    @Test fun ordinaryOffCommandsAreNotPower() {
        assertEquals(listOf(Action.Volume(mute = true)), exec(core().handle("выключи звук")))
        assertTrue(exec(core().handle("выключи свет")).orEmpty().none { it is Action.Power })
        assertTrue(exec(core().handle("перезагрузи ютуб")).orEmpty().none { it is Action.Power })
    }
}
