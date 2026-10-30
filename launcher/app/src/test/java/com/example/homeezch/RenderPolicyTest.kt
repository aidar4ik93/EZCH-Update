package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class RenderPolicyTest {
    private fun profile(mode: WallpaperMode = WallpaperMode.AUTO, ram: Long = 2L * 1024 * 1024 * 1024,
        lowRam: Boolean = false, power: Boolean = false, memory: Boolean = false, hot: Boolean = false,
        slow: Boolean = false, graphics: Boolean = true) = renderProfile(mode, ram, lowRam, power, memory, hot, slow, graphics)

    @Test fun boundaryUnknownAndLowRamChooseSafeLite() {
        for (ram in listOf(0L, -1L, 1024L * 1024 * 1024, LITE_RAM_LIMIT)) {
            assertFalse(profile(ram = ram).premium)
            assertFalse(profile(ram = ram).heavyEffects)
        }
        assertTrue(profile(ram = LITE_RAM_LIMIT + 1).premium)
        assertFalse(profile(lowRam = true).premium)
    }
    @Test fun pressureStopsEffectsWithoutLosingPremiumThemeOrFunctions() {
        for (p in listOf(profile(memory = true), profile(hot = true), profile(slow = true), profile(graphics = false))) {
            assertTrue(p.premium); assertFalse(p.heavyEffects)
        }
    }
    @Test fun manualPremiumCannotForceVideoUnderMemoryThermalOrPowerPressure() {
        for (p in listOf(profile(WallpaperMode.PREMIUM, memory = true), profile(WallpaperMode.PREMIUM, hot = true), profile(WallpaperMode.PREMIUM, power = true))) {
            assertTrue(p.premium); assertFalse(p.heavyEffects)
        }
    }
    @Test fun manualThemeRemainsSelectedWhenDeviceHasLittleRam() {
        assertTrue(profile(WallpaperMode.PREMIUM, ram = LITE_RAM_LIMIT).premium)
        assertFalse(profile(WallpaperMode.PREMIUM, ram = LITE_RAM_LIMIT).heavyEffects)
        assertFalse(profile(WallpaperMode.LITE, ram = 4L * 1024 * 1024 * 1024).premium)
    }
    @Test fun healthyDeviceKeepsEffectsAndAutomaticPowerSavingUsesLite() {
        assertTrue(profile(ram = 4L * 1024 * 1024 * 1024).heavyEffects)
        assertTrue(profile().premium)
        assertFalse(profile().heavyEffects)
        assertFalse(profile(power = true).premium)
    }
    @Test fun isolatedSlowStartupAndInvalidMetricsDoNotDowngrade() {
        val health = FrameHealth()
        repeat(400) { health.record(100_000_000, 16_666_667, firstDraw = true); health.record(-1, 16_666_667) }
        repeat(120) { health.record(30_000_000, 16_666_667) }
        assertFalse(health.isReduced())
        repeat(120) { health.record(10_000_000, 16_666_667) }
        repeat(120) { health.record(30_000_000, 16_666_667) }
        assertFalse(health.isReduced())
    }
    @Test fun persistentSlowFramesDowngradeAndDoNotOscillate() {
        val health = FrameHealth()
        repeat(240) { health.record(30_000_000, 16_666_667) }
        assertTrue(health.isReduced())
        repeat(400) { health.record(10_000_000, 16_666_667) }
        assertTrue(health.isReduced())
    }
    @Test fun budgetUsesActualRefreshRateRatherThanAssumingSixtyHertz() {
        val health = FrameHealth()
        repeat(240) { health.record(30_000_000, 33_333_333) }
        assertFalse(health.isReduced())
    }
}
