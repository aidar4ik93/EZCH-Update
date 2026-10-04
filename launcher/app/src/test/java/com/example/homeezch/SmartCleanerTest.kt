package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCleanerTest {
    private val oldCert = "a".repeat(64)
    private val rotatedCert = "b".repeat(64)

    @Test fun mandatoryPackagesStayProtectedAndCannotBeUnprotected() {
        ProtectionPolicy.mandatoryPackages.forEach { pkg ->
            assertNotNull(ProtectionPolicy.reason(pkg, "New name", setOf(oldCert), ProtectionState()))
            val state = ProtectionPolicy.protect(ProtectionState(), pkg, setOf(oldCert), permanent = true)
            assertEquals(state, ProtectionPolicy.unprotect(state, pkg, "New name"))
        }
    }

    @Test fun conservativeNameVariantsAreProtected() {
        assertNotNull(ProtectionPolicy.mandatoryReason("custom.network", "Bye DPI"))
        assertNotNull(ProtectionPolicy.mandatoryReason("custom.torrserve.mobile", "Server"))
        assertNotNull(ProtectionPolicy.mandatoryReason("custom.server", "ТоррСерв"))
        assertNull(ProtectionPolicy.mandatoryReason("custom.media", "Video player"))
    }

    @Test fun unknownSignatureFailsClosed() {
        assertNotNull(ProtectionPolicy.reason("a.player", "Player", null, ProtectionState()))
        assertNotNull(ProtectionPolicy.reason("a.player", "Player", emptySet(), ProtectionState()))
        assertNull(ProtectionPolicy.reason("a.player", "Player", setOf(oldCert), ProtectionState()))
    }

    @Test fun userProtectionAndObservedSigningHistorySurviveJsonRoundTrip() {
        var state = ProtectionPolicy.protect(ProtectionState(), "a.server", setOf(oldCert), permanent = false)
        state = ProtectionPolicy.protect(state, "a.server", setOf(oldCert, rotatedCert), permanent = false)
        val restored = ProtectionJson.decode(ProtectionJson.encode(state))
        assertEquals(state, restored)
        assertEquals(setOf(oldCert, rotatedCert), restored.lineagePins["a.server"])
        assertNotNull(ProtectionPolicy.reason("a.renamed", "Renamed", setOf(rotatedCert), restored))
    }

    @Test fun permanentPinSurvivesRenamingAndUnknownSigningData() {
        val state = ProtectionPolicy.protect(ProtectionState(), "custom.server", setOf(oldCert), permanent = true)
        assertNotNull(ProtectionPolicy.reason("custom.server", "Renamed", null, state))
        assertEquals(state, ProtectionPolicy.unprotect(state, "custom.server", "Renamed"))
    }

    @Test fun userCanRemoveOnlyTheirOwnProtection() {
        val state = ProtectionPolicy.protect(ProtectionState(), "a.player", setOf(oldCert), permanent = false)
        val unprotected = ProtectionPolicy.unprotect(state, "a.player", "Player")
        assertTrue(unprotected.userPackages.isEmpty())
        assertTrue(unprotected.lineagePins.isEmpty())
        assertNull(ProtectionPolicy.reason("a.player", "Player", setOf(oldCert), unprotected))
    }

    @Test fun corruptedOrFutureProtectionPolicyProtectsAllApps() {
        listOf("broken", "{}", """{"version":2,"users":[],"permanent":[],"pins":{}}""",
            """{"version":1,"users":[],"permanent":[],"pins":{"a.player":["fake"]}}""").forEach { raw ->
            val state = ProtectionJson.decode(raw)
            assertTrue(state.corrupted)
            assertNotNull(ProtectionPolicy.reason("a.player", "Player", setOf(oldCert), state))
            assertEquals(state, ProtectionPolicy.unprotect(state, "a.player", "Player"))
        }
    }

    @Test fun activeDownloadsAndInstallPayloadsNeverBecomeCacheCandidates() {
        val now = 10_000_000L
        listOf(listOf("downloads", "old.png"), listOf("images", "download-cache.png"),
            listOf("pending", "app.cache"), listOf("old.apk"), listOf("old.part"),
            listOf("active.tmp"), listOf("active.lock"), listOf("preferences", "data.json"))
            .forEach { parts -> assertFalse(OwnCachePolicy.deletable(parts, 1L, now, 1L)) }
    }

    @Test fun onlyOldTemporaryFilesAreCandidates() {
        val now = 10_000_000L
        assertTrue(OwnCachePolicy.deletable(listOf("art", "old.png"), 1L, now, 60_000L))
        assertFalse(OwnCachePolicy.deletable(listOf("art", "recent.png"), now - 1L, now, 60_000L))
        assertFalse(OwnCachePolicy.deletable(listOf("art", "future.png"), now + 1L, now, 60_000L))
        assertFalse(OwnCachePolicy.deletable(listOf("art", "unknown.png"), 0L, now, 60_000L))
    }
}
