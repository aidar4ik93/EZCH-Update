package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    private val initial = Workspace(listOf(
        AppShelf("main", "Приложения", listOf("a", "offline", "b", "c")),
        AppShelf("second", "Избранное", listOf("d"))
    ), sourceOrder = listOf("hdmi1", "absent-usb", "hdmi2"))
    private val available = setOf("a", "b", "c", "d")

    @Test fun moveBetweenRowsDoesNotDuplicateOrLoseApps() {
        val result = initial.moveApp("b", vertical = 1, available = available)
        assertEquals(listOf("a", "offline", "c"), result.rows[0].appKeys)
        assertEquals(listOf("d", "b"), result.rows[1].appKeys)
        assertEquals(1, result.rows.flatMap { it.appKeys }.count { it == "b" })
    }
    @Test fun horizontalMoveUsesVisibleNeighborWithoutLosingOfflineItem() {
        val result = initial.moveApp("a", horizontal = 1, available = available)
        assertEquals(listOf("b", "offline", "a", "c"), result.rows[0].appKeys)
    }
    @Test fun cancelCanRestoreTheUnchangedSnapshot() {
        val draft = initial.moveApp("a", vertical = 1, available = available).hideApp("b")
        assertNotEquals(initial, draft)
        assertEquals(listOf("a", "offline", "b", "c"), initial.rows[0].appKeys)
        assertTrue(initial.hiddenApps.isEmpty())
    }
    @Test fun rowEdgesAreNoOps() {
        assertEquals(initial, initial.moveApp("a", horizontal = -1, available = available))
        assertEquals(initial, initial.moveApp("a", vertical = -1, available = available))
        assertEquals(initial, initial.moveApp("d", vertical = 1, available = available))
    }
    @Test fun sourceDisconnectDoesNotDestroySavedPosition() {
        val moved = initial.moveSource("hdmi2", -1, listOf("hdmi1", "hdmi2"))
        assertEquals(listOf("hdmi2", "absent-usb", "hdmi1"), moved.sourceOrder)
        assertEquals(moved, moved.moveSource("absent-usb", 1, listOf("hdmi1", "hdmi2")))
    }
    @Test fun discoverDoesNotResetUserOrderOrRestoreHiddenApps() {
        val result = initial.hideApp("b").discover(listOf("c", "a", "b", "d", "new"))
        assertEquals(listOf("a", "offline", "b", "c", "new"), result.rows[0].appKeys)
        assertEquals(listOf("b"), result.hiddenApps)
    }
    @Test fun migratedDuplicateAssignmentsHaveOnlyOneOwner() {
        val result = Workspace(listOf(AppShelf("main", "A", listOf("a", "a")),
            AppShelf("other", "B", listOf("a", "b")))).discover(listOf("a", "b"))
        assertEquals(listOf("a", "b"), result.rows.flatMap { it.appKeys })
    }
    @Test fun hiddenAppStillBelongsToItsRowAfterRestore() {
        val result = initial.hideApp("b").discover(available.toList()).restoreApp("b")
        assertEquals(initial.rows, result.rows)
    }
    @Test fun removingRowReturnsItsAppsToRemainingRow() {
        val result = initial.removeRow("main")
        assertEquals(1, result.rows.size)
        assertEquals(listOf("d", "a", "offline", "b", "c"), result.rows[0].appKeys)
        assertEquals(result, result.removeRow("second"))
    }
    @Test fun reorderingRowsKeepsStableIdsAndContents() {
        val result = initial.moveRow("second", -1)
        assertEquals(listOf("second", "main"), result.rows.map { it.id })
        assertEquals(initial.rows[0].appKeys, result.rows[1].appKeys)
    }
}
