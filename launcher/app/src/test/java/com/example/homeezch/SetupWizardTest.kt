package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R31 «Настроить EZCH»: order, real-state re-check, skip, «Позже», mic reminder. */
class SetupWizardTest {
    private val fresh = SetupFacts(effectiveHome = false, homeServiceEnabled = false, homeServiceConnected = false,
        evaServiceEnabled = false, evaServiceConnected = false, evaOn = false, micGranted = false, micTakeover = true)
    private val all = fresh.copy(effectiveHome = true, homeServiceEnabled = true, homeServiceConnected = true,
        evaServiceEnabled = true, evaServiceConnected = true, evaOn = true, micGranted = true)

    @Test fun launcherFirstThenServicesThenVoice() {
        assertEquals(SetupStep.HOME, nextSetupStep(fresh, emptySet()))
        assertEquals(SetupStep.HOME_SERVICE, nextSetupStep(fresh.copy(effectiveHome = true), emptySet()))
        assertEquals(SetupStep.EVA_SERVICE, nextSetupStep(all.copy(evaServiceEnabled = false, evaServiceConnected = false, evaOn = false), emptySet()))
        assertEquals(SetupStep.EVA_VOICE, nextSetupStep(all.copy(micGranted = false), emptySet()))
        assertNull(nextSetupStep(all, emptySet()))
    }
    @Test fun finishedStepsAreSkippedWhateverTheOrder() {
        // Services already on, launcher not chosen: only the launcher and voice are left.
        val f = fresh.copy(homeServiceEnabled = true, homeServiceConnected = false, evaServiceEnabled = true, evaServiceConnected = true)
        assertEquals(SetupStep.HOME, nextSetupStep(f, emptySet()))
        assertEquals(StepState.WAITING, stepState(SetupStep.HOME_SERVICE, f))
        assertEquals(StepState.DONE, stepState(SetupStep.EVA_SERVICE, f))
    }
    @Test fun firmwareThatKeepsItsHomeIsCoveredByTheService() {
        val f = fresh.copy(homeServiceEnabled = true, homeServiceConnected = true)
        assertEquals(StepState.COVERED, stepState(SetupStep.HOME, f))
        assertEquals(SetupStep.EVA_SERVICE, nextSetupStep(f, emptySet()))
        assertEquals(2, setupDoneCount(f))
    }
    @Test fun enabledButNotConnectedIsNotDone() =
        assertEquals(StepState.WAITING, stepState(SetupStep.EVA_SERVICE, fresh.copy(evaServiceEnabled = true)))
    @Test fun skippedStepsDoNotBlockAndDoNotCount() {
        val skipped = setOf(SetupStep.HOME_SERVICE, SetupStep.EVA_SERVICE)
        val f = fresh.copy(effectiveHome = true, evaOn = true, micGranted = true)
        assertNull(nextSetupStep(f, skipped))
        assertEquals(2, setupDoneCount(f, skipped))
        // A step that becomes really done is shown as done even if it was skipped earlier.
        assertEquals(StepState.DONE, stepState(SetupStep.HOME, f, setOf(SetupStep.HOME)))
    }
    @Test fun opensByItselfOnceAndNeverAfterLater() {
        assertTrue(shouldAutoOpenWizard(autoShown = false, later = false, finished = false, next = SetupStep.HOME))
        assertFalse(shouldAutoOpenWizard(autoShown = true, later = false, finished = false, next = SetupStep.HOME))
        assertFalse(shouldAutoOpenWizard(autoShown = false, later = true, finished = false, next = SetupStep.HOME))
        assertFalse(shouldAutoOpenWizard(autoShown = false, later = false, finished = false, next = null))
    }
    @Test fun micReminderOnlyAfterFinishedSetupThatLostTheServices() {
        assertTrue(shouldShowMicHint(true, false, false, false, evaOn = true, anyServiceConnected = false))
        assertFalse(shouldShowMicHint(false, false, false, false, evaOn = true, anyServiceConnected = false)) // never set up
        assertFalse(shouldShowMicHint(true, true, false, false, evaOn = true, anyServiceConnected = false))  // «Позже»
        assertFalse(shouldShowMicHint(true, false, true, false, evaOn = true, anyServiceConnected = false))  // «Нет»
        assertFalse(shouldShowMicHint(true, false, false, true, evaOn = true, anyServiceConnected = false))  // skipped
        assertFalse(shouldShowMicHint(true, false, false, false, evaOn = true, anyServiceConnected = true))
    }
}
