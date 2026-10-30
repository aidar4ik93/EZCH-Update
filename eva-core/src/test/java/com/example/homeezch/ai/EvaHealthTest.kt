package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaHealthTest {
    @Test fun slowTvGetsHonestAdvice() {
        val low = EvaHealth.performance(HealthSnapshot(1300, 120, true, 300, 8000))
        assertTrue(low.message, low.message.contains("оперативная память") && low.message.contains("очисти кэш") && low.message.contains("места"))
        val fine = EvaHealth.performance(HealthSnapshot(4000, 2000, false, 9000, 16000))
        assertTrue(fine.message, fine.message.contains("Явных причин"))
        assertFalse(EvaHealth.performance(HealthSnapshot(0, 0, false, 0, 0)).accepted)
    }
    @Test fun networkReport() {
        assertTrue(EvaHealth.network(HealthSnapshot(1, 1, false, 1, 1, online = false)).message.startsWith("Интернета нет"))
        assertTrue(EvaHealth.network(HealthSnapshot(1, 1, false, 1, 1, online = true, validatedInternet = false, transport = "Wi-Fi")).message.contains("выхода в интернет нет"))
        assertTrue(EvaHealth.network(HealthSnapshot(1, 1, false, 1, 1, online = true, validatedInternet = true, transport = "Wi-Fi", downKbps = 3000)).message.contains("1080p"))
    }
    @Test fun recentInstallPicksNewestInWindow() {
        val day = 86_400_000L; val now = 100 * day
        val a = AppTarget("Old", "a/.A"); val b = AppTarget("New", "b/.B"); val c = AppTarget("Week", "c/.C")
        val list = listOf(a to now - 30 * day, b to now - day / 2, c to now - 5 * day)
        assertEquals(b, EvaHealth.recentInstall(list, now, 2))
        assertEquals(null, EvaHealth.recentInstall(listOf(a to now - 30 * day), now, 7))
    }
}
