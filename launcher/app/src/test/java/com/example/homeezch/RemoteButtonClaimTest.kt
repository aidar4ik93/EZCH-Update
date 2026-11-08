package com.example.homeezch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R52: both EZCH accessibility services receive every key; an assigned button must run once. */
class RemoteButtonClaimTest {
    @Test fun onePressIsExecutedByOneServiceOnly() {
        assertTrue(RemoteButtons.claim(downTime = 777_001, keyCode = 231, deviceId = 7))
        assertFalse(RemoteButtons.claim(downTime = 777_001, keyCode = 231, deviceId = 7))
        assertTrue(RemoteButtons.claim(downTime = 777_900, keyCode = 231, deviceId = 7))
    }
}
