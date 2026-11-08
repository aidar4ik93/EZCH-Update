package com.example.homeezch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeA11yGateTest {
    private val own = "com.example.homeezch.usb"
    @Test fun ownServicesOnlyQuietsDesktop() {
        assertTrue(ComposeA11yGate.onlyOwnServices(own, listOf("$own/com.example.homeezch.ai.EvaAppService", "$own/.HomeButtonService"), false))
    }
    @Test fun screenReaderKeepsAccessibility() {
        assertFalse(ComposeA11yGate.onlyOwnServices(own, listOf("$own/.HomeButtonService", "com.google.android.marvin.talkback/.TalkBackService"), false))
        assertFalse(ComposeA11yGate.onlyOwnServices(own, listOf("$own/.HomeButtonService"), true))
    }
    @Test fun keyMapperIsNotAScreenReader() {
        val generic = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        val spoken = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
        assertTrue(ComposeA11yGate.noScreenReader(own, listOf("$own/.Eva" to generic, "flar2.homebutton/.Mapper" to generic), false))
        assertFalse(ComposeA11yGate.noScreenReader(own, listOf("$own/.Eva" to generic, "com.google.android.marvin.talkback/.TalkBackService" to spoken), false))
        assertFalse(ComposeA11yGate.noScreenReader(own, listOf("$own/.Eva" to generic), true))
    }
    @Test fun noServicesLeavesComposeAlone() {
        assertFalse(ComposeA11yGate.onlyOwnServices(own, emptyList(), false))
    }
}
