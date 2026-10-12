package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class HomeRoleMismatchTest {
    @Test fun roleHeldButGoogleTakesHomeIsNotEffectiveHome() {
        assertFalse(isEffectiveHome("com.google.android.tvlauncher", "com.example.homeezch.usb", true))
    }
    @Test fun homeReallyResolvesToEzch() {
        assertTrue(isEffectiveHome("com.example.homeezch.usb", "com.example.homeezch.usb", false))
    }
    @Test fun noResolverCannotProveHomeEvenWhenRoleIsHeld() {
        assertFalse(isEffectiveHome(null, "com.example.homeezch.usb", true))
    }
    @Test fun noResolverAndNoRoleMeansNotHome() {
        assertFalse(isEffectiveHome(null, "com.example.homeezch.usb", false))
    }
}
