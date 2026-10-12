package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class HomeSetupStateTest {
    @Test fun grantedRoleIsNotProofOfWorkingHome() {
        val state = HomeSetupState(false, true, false, false, true)
        assertTrue(state.roleMismatch)
        assertFalse(state.homeAvailable)
    }
    @Test fun enabledButUnboundServiceIsNotReady() {
        assertFalse(HomeSetupState(false, false, true, false, true).homeAvailable)
    }
    @Test fun staleConnectionDoesNotOverrideRevokedPermission() {
        assertFalse(HomeSetupState(false, false, false, true, true).homeAvailable)
    }
    @Test fun fallbackDoesNotClaimSystemHome() {
        val state = HomeSetupState(false, true, true, true, false)
        assertTrue(state.homeAvailable)
        assertFalse(state.effectiveHome)
        assertFalse(state.canInstall)
    }
    @Test fun realHomeNeedsNoAccessibilityService() {
        assertTrue(HomeSetupState(true, false, false, false, false).homeAvailable)
    }
}
