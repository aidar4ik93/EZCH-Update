package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class WallpaperPolicyTest {
    @Test fun autoFallsBackForLowMemoryAndPowerSaving() {
        assertTrue(shouldUseLite(WallpaperMode.AUTO, true, false))
        assertTrue(shouldUseLite(WallpaperMode.AUTO, false, true))
        assertFalse(shouldUseLite(WallpaperMode.AUTO, false, false))
    }
    @Test fun explicitModesAreStable() {
        assertTrue(shouldUseLite(WallpaperMode.LITE, false, false))
        assertFalse(shouldUseLite(WallpaperMode.PREMIUM, true, false))
    }
}
