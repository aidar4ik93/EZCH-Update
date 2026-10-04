package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class SourceTransitionsTest {
    private fun source(id: String, connected: Boolean?) = TvSourceEntry(id, id, "", TvSourceKind.HDMI, connected)
    @Test fun firstScanDoesNotPretendAnExistingDeviceWasJustInserted() {
        assertTrue(newlyConnected(null, listOf(source("hdmi", true))).isEmpty())
    }
    @Test fun insertionLightsOnlyTheChangedPortAndUnknownNeverLights() {
        val previous = mapOf("hdmi1" to false, "hdmi2" to true, "manual" to null)
        assertEquals(setOf("hdmi1", "usb"), newlyConnected(previous,
            listOf(source("hdmi1", true), source("hdmi2", true), source("manual", null), source("usb", true))))
        assertTrue(newlyConnected(mapOf("hdmi" to true), listOf(source("hdmi", false))).isEmpty())
    }
}
