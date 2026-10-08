package com.example.homeezch
import org.junit.Assert.*
import org.junit.Test
class LegacyMigrationTest {
    @Test fun updatePreservesCustomRowsHiddenAppsAndOrdering() {
        val result = decodeLegacyWorkspace("[\"org.example.b\",\"org.example.a\",\"org.example.c\"]",
            "[{\"id\":\"video\",\"title\":\"Видео\",\"packages\":[\"org.example.c\"]}]", "[\"org.example.hidden\"]")
        assertEquals(listOf("org.example.b", "org.example.a"), result.rows[0].appKeys)
        assertEquals(listOf("org.example.c"), result.rows[1].appKeys)
        assertEquals("Видео", result.rows[1].title)
        assertEquals(listOf("org.example.hidden"), result.hiddenApps)
    }
    @Test fun damagedPreferencesStillProduceUsableDesktop() {
        val result = decodeLegacyWorkspace("broken", "[]", "[false,\"invalid\",\"org.example.hidden\"]")
        assertEquals(1, result.rows.size)
        assertTrue(result.rows.first().appKeys.isEmpty())
        assertEquals(listOf("org.example.hidden"), result.hiddenApps)
    }
}
