package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class RemoteButtonIdTest {
    @Test fun androidUnknownMayBeIdentifiedByScanCode() {
        assertTrue(RemoteButtonId("bt21", 0, 767).assignable())
        assertFalse(RemoteButtonId("bt21", 0, 0).assignable())
    }
    @Test fun duplicateUnknownCodesCannotBeDistinguishedByHidUsage() {
        assertFalse(RemoteButtonId("bt21", 0, 240).assignable())
        assertNotEquals(RemoteButtonId("bt21", 0, 240).storageKey(), RemoteButtonId("bt21", 0, 229).storageKey())
    }
    @Test fun assignmentsDoNotTakeOverNavigationVoiceOrVolume() {
        listOf(3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 82, 85, 164, 219, 231).forEach {
            assertFalse("Protected key $it", RemoteButtonId("bt21", it, 240).assignable())
        }
    }
    @Test fun stableDeviceIdentitySeparatesRemotes() {
        assertFalse(RemoteButtonId("", 176, 0).assignable())
        assertNotEquals(RemoteButtonId("one", 176, 0).storageKey(), RemoteButtonId("two", 176, 0).storageKey())
    }
}
