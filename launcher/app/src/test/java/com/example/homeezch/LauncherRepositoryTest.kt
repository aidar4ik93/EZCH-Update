package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRepositoryTest {
    @Test fun orderKeepsUnmentionedAppsAndIgnoresUninstalledEntries() {
        assertEquals(listOf("a.two", "a.one", "a.three"), LauncherPolicy.orderPackages(
            listOf("a.one", "a.two", "a.three"), listOf("a.missing", "a.two", "a.two")))
    }

    @Test fun storedOrderRetainsMissingPackagesForReinstall() {
        val stored = LauncherJson.decodePackages(LauncherJson.encodePackages(listOf("a.old", "a.current")))
        assertEquals(listOf("a.old", "a.current"), stored)
        assertEquals(listOf("a.current"), LauncherPolicy.orderPackages(listOf("a.current"), stored))
        assertEquals(listOf("a.old", "a.current"), LauncherPolicy.orderPackages(listOf("a.current", "a.old"), stored))
    }

    @Test fun badJsonDoesNotCrashAndMalformedPackagesAreDropped() {
        assertTrue(LauncherJson.decodePackages("{broken").isEmpty())
        assertEquals(listOf("a.valid"), LauncherJson.decodePackages("[\"a.valid\",123,null,\"../files\",\"a.valid\"]"))
        assertTrue(LauncherJson.decodeRows("null").isEmpty())
    }

    @Test fun customRowsAndChannelIdsRoundTrip() {
        val rows = listOf(HomeRow("favorites", "Избранное", listOf("a.one", "a.two")),
            HomeRow("channel-42", "Кино • Лучшее", channelId = 42L))
        assertEquals(rows, LauncherJson.decodeRows(LauncherJson.encodeRows(rows)))
    }

    @Test fun rowPolicyRejectsDuplicateIdsAndSanitizesPackages() {
        val rows = LauncherJson.decodeRows("""[
            {"id":"my","title":"  Моё  ","packages":["a.one","invalid","a.one"],"channelId":-3},
            {"id":"my","title":"Дубликат"},
            {"id":4,"title":"Повреждено"},
            {"id":"empty","title":" "}
        ]""")
        assertEquals(listOf(HomeRow("my", "Моё", listOf("a.one"))), rows)
    }

    @Test fun recentLaunchesMoveToFrontAndStayBounded() {
        val recent = (1..20).map { "a.app$it" }
        val result = LauncherPolicy.recordLaunch(recent, "a.app5")
        assertEquals("a.app5", result.first())
        assertEquals(12, result.size)
        assertEquals(12, result.distinct().size)
        assertEquals(result, LauncherPolicy.recordLaunch(result, "not a package"))
    }
}
