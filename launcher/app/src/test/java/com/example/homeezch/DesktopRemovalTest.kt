package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class DesktopRemovalTest {
    private val initial = Workspace(listOf(AppShelf("main", "Приложения", listOf("a", "b")), AppShelf("games", "Игры", listOf("c"))))
    @Test fun removingAppSurvivesRediscoveryAndPreservesOtherRows() {
        val removed = initial.removeApp("b").discover(listOf("a", "b", "c", "new"))
        assertEquals(listOf("a", "new"), removed.rows.first().appKeys)
        assertEquals(listOf("b"), removed.removedApps)
        assertEquals(initial.rows[1], removed.rows[1])
        assertTrue(removed.hiddenApps.isEmpty())
    }
    @Test fun removedAppCanBePlacedInAnotherRowWithoutDuplicateOrHiddenFlag() {
        val result = initial.hideApp("b").removeApp("b").placeApp("b", "games")
        assertEquals(listOf("a"), result.rows[0].appKeys)
        assertEquals(listOf("c", "b"), result.rows[1].appKeys)
        assertTrue(result.removedApps.isEmpty()); assertTrue(result.hiddenApps.isEmpty())
        assertEquals(result, result.discover(listOf("a", "b", "c")))
    }
    @Test fun hiddenRestoreKeepsPositionButRemovedRestoreAddsToMain() {
        assertEquals(initial, initial.hideApp("b").restoreApp("b"))
        assertEquals(initial, initial.removeApp("b").restoreApp("b"))
    }
    @Test fun placingInMissingRowDoesNotDiscardApp() {
        assertEquals(initial, initial.placeApp("b", "missing"))
    }
    @Test fun renamePreservesStableIdsOrderAndTrimmedTitle() {
        val changed = initial.renameRow("games", "  Кино  ")
        assertEquals(initial.rows.map { it.id }, changed.rows.map { it.id })
        assertEquals(initial.rows.map { it.appKeys }, changed.rows.map { it.appKeys })
        assertEquals("Кино", changed.rows[1].title)
        assertEquals(changed, changed.renameRow("games", "  "))
    }
}
