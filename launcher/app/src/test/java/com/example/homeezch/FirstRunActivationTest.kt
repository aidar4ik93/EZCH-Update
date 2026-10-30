package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunActivationTest {
    private val fresh = ActivationFacts(evaOn = false, isHome = false, micReady = false)

    @Test fun freshTvAsksEvaFirst() = assertEquals(ActivationStep.EVA, nextActivationStep(null, fresh))
    @Test fun afterEvaYesAsksHome() = assertEquals(ActivationStep.HOME, nextActivationStep(ActivationStep.EVA, fresh.copy(evaOn = true)))
    @Test fun afterHomeAsksMicWhenEvaOn() = assertEquals(ActivationStep.MIC, nextActivationStep(ActivationStep.HOME, fresh.copy(evaOn = true)))
    @Test fun evaNoSkipsMicQuestion() = assertEquals(ActivationStep.DONE, nextActivationStep(ActivationStep.HOME, fresh))
    @Test fun alreadyHomeSkipsHomeQuestion() =
        assertEquals(ActivationStep.MIC, nextActivationStep(ActivationStep.EVA, ActivationFacts(evaOn = true, isHome = true, micReady = false)))
    @Test fun configuredTvAsksNothing() =
        assertEquals(ActivationStep.DONE, nextActivationStep(null, ActivationFacts(evaOn = true, isHome = true, micReady = true)))
    @Test fun afterMicDone() = assertEquals(ActivationStep.DONE, nextActivationStep(ActivationStep.MIC, fresh.copy(evaOn = true)))
}
