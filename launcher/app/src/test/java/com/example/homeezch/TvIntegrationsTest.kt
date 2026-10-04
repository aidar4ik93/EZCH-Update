package com.example.homeezch

import android.content.Intent
import android.media.tv.TvInputManager
import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvIntegrationsTest {
    @Test
    fun manualSlotsNeverClaimAConnectionOrAnInputId() {
        val entries = manualSourceEntries(hdmiCount = 3, hasTv = true, hasAv = true, usbSlots = 2)
        assertEquals(7, entries.size)
        assertEquals(listOf("ТВ", "HDMI 1", "HDMI 2", "HDMI 3", "AV", "USB 1", "USB 2"), entries.map { it.label })
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach {
            assertNull(it.connected)
            assertNull(it.inputId)
            assertTrue(it.hint.contains("неизвестно"))
        }
    }

    @Test
    fun corruptManualCountsCannotCreateUnboundedOrNegativeLists() {
        assertTrue(manualSourceEntries(-4, false, false, -1).isEmpty())
        assertEquals(18, manualSourceEntries(Int.MAX_VALUE, true, true, Int.MAX_VALUE).size)
    }

    @Test
    fun standbyIsConnectedButUnknownStatesRemainUnknown() {
        assertEquals(true, sourceConnectedState(TvInputManager.INPUT_STATE_CONNECTED))
        assertEquals(true, sourceConnectedState(TvInputManager.INPUT_STATE_CONNECTED_STANDBY))
        assertEquals(false, sourceConnectedState(TvInputManager.INPUT_STATE_DISCONNECTED))
        assertNull(sourceConnectedState(null))
        assertNull(sourceConnectedState(999))
    }

    @Test
    fun onlyMountedVolumesCanBecomeAvailableSources() {
        assertTrue(isMountedRemovableState(Environment.MEDIA_MOUNTED))
        assertTrue(isMountedRemovableState(Environment.MEDIA_MOUNTED_READ_ONLY))
        assertFalse(isMountedRemovableState(Environment.MEDIA_UNMOUNTED))
        assertFalse(isMountedRemovableState(Environment.MEDIA_CHECKING))
        assertFalse(isMountedRemovableState(Environment.MEDIA_EJECTING))
    }

    @Test
    fun mediaCardsCannotRequestInstallDeleteOrUriExecution() {
        assertTrue(isMediaPlaybackAction(Intent.ACTION_VIEW))
        assertTrue(isMediaPlaybackAction(Intent.ACTION_MAIN))
        assertFalse(isMediaPlaybackAction(Intent.ACTION_DELETE))
        assertFalse(isMediaPlaybackAction(Intent.ACTION_INSTALL_PACKAGE))
        assertFalse(isMediaPlaybackAction(null))
        listOf("javascript", "DATA", "file", "package").forEach { assertFalse(isSafeMediaScheme(it)) }
        listOf("https", "content", "android-app", "youtube").forEach { assertTrue(isSafeMediaScheme(it)) }
        assertTrue(isSafeMediaScheme(null))
    }
}
