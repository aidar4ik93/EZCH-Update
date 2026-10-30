package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream

class DesktopSecurityTest {
    @Test fun saltedPinsVerifyWithoutAcceptingWrongOrMalformedCredentials() {
        val first = PinCredential.create("123456")
        val second = PinCredential.create("123456")
        assertNotEquals(first, second)
        assertTrue(PinCredential.verify("123456", first))
        assertFalse(PinCredential.verify("123455", first))
        assertFalse(PinCredential.verify("123456", "120000:bad:hash"))
        assertFalse(PinCredential.verify("１２３４", first))
    }
    @Test fun folderKeepsSingleAllocationAndNewAppsArriveOnDesktop() {
        val original = Workspace(listOf(AppShelf("main", "Приложения", listOf("a", "b"))))
        val next = original.addRow("Кино", "movies", true).placeApp("a", "movies").discover(listOf("a", "b", "c"))
        assertEquals(listOf("b", "c"), next.rows[0].appKeys)
        assertEquals(listOf("a"), next.rows[1].appKeys)
        assertTrue(next.rows[1].isFolder)
        assertEquals(listOf("b", "c", "a"), next.removeRow("movies").rows[0].appKeys)
        assertEquals(next, next.addRow("duplicate", "movies", true))
    }
    @Test fun boundedImportRejectsOversizeWithoutReadingEntireStream() {
        assertTrue(runCatching { ByteArrayInputStream(ByteArray(10000)).readBytesBounded(9999) }.isFailure)
        assertEquals(10000, ByteArrayInputStream(ByteArray(10000)).readBytesBounded(10000).size)
    }
    @Test fun folderCannotConsumeTheLastDesktopRowAndRestoredCardsReturnToDesktop() {
        val workspace = Workspace(listOf(AppShelf("movies", "Кино", listOf("a"), true), AppShelf("main", "Приложения", listOf("b"))))
        assertEquals(workspace, workspace.removeRow("main"))
        assertEquals(listOf("b", "c"), workspace.removeApp("c").restoreApp("c").rows.last().appKeys)
        val recovered = Workspace(listOf(AppShelf("main", "Папка", listOf("a"), true))).discover(listOf("a", "b"))
        assertEquals(2, recovered.rows.map { it.id }.distinct().size)
        assertFalse(recovered.rows.first().isFolder)
    }
    @Test fun malformedBackupAndDuplicateOwnershipAreRejectedBeforeWrites() {
        assertTrue(runCatching { DesktopBackup.validate("{}") }.isFailure)
        val values = JSONObject().put("workspace_v1", JSONObject().put("type", "string").put("value",
            """{"rows":[{"id":"main","title":"A","apps":["a","a"]}]}"""))
        val stores = JSONObject().put("ezch_launcher_prefs", values).put("wallpaper", JSONObject()).put("source_settings", JSONObject())
        val json = JSONObject().put("format", "ezch-desktop").put("version", 1).put("stores", stores).toString()
        assertTrue(runCatching { DesktopBackup.validate(json) }.isFailure)
    }
}
