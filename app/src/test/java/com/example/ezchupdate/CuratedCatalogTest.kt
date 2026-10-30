package com.example.ezchupdate

import org.junit.Assert.*
import org.junit.Test

class CuratedCatalogTest {
    @Test fun allTwentyPositionsRemainVisibleWithoutInventingApks() {
        val apps = CuratedCatalog.decorate(emptyList(), "com.example.ezchupdate.tvtest")
        assertEquals(20, apps.size)
        assertEquals(20, apps.map { it.packageName }.distinct().size)
        assertEquals(listOf(9, 3, 8), listOf("Фильмы и ТВ", "Видеосервисы", "Инструменты").map { section ->
            apps.count { CuratedCatalog.section(it.packageName) == section }
        })
        assertTrue(apps.none { it.installable || AppRow(it, null).updateAvailable })
        assertEquals("net.gtvbox.vimuhd", apps[18].packageName)
    }
    @Test fun legacyIdentityIsAnUpdateOfTheExistingPackage() {
        val apps = CuratedCatalog.decorate(emptyList(), "com.example.ezchupdate")
        assertTrue(apps.any { it.packageName == "com.example.ezchupdate" })
        assertFalse(apps.any { it.packageName == "com.example.ezchupdate.tvtest" })
    }
}
