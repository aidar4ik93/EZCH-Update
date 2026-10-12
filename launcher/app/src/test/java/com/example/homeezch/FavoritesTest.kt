package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class FavoritesTest {
    @Test fun addingFavoritePreservesOtherAppsAndDoesNotDuplicateKeys() {
        val original = Workspace(listOf(AppShelf("main", "Приложения", listOf("a","b","c"))))
        val updated = original.favorite("b").favorite("b")
        assertEquals(listOf("b"), updated.rows.first().appKeys)
        assertEquals("Избранное", updated.rows.first().title)
        assertEquals(setOf("a","b","c"), updated.rows.flatMap { it.appKeys }.toSet())
        assertEquals(3, updated.rows.flatMap { it.appKeys }.size)
        assertEquals(original, original.favorite("not-installed"))
    }
}
