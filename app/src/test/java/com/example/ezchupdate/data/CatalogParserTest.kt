package com.example.ezchupdate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParserTest {
    private fun entry(extra: String = "", code: String = "411600002") = """
        {"name":"RUTUBE","packageName":"ru.rutube.app.tv","versionCode":$code,
         "versionName":"31.16.0.TV-rustore","apkUrl":"https://disk.yandex.ru/d/catalog",
         "apkPath":"/RUTUBE 31.16.0.TV-rustore.apk"$extra}
    """.trimIndent()

    private fun catalog(entries: String = entry()) = """{"apps":[$entries]}"""

    @Test fun keepsRealVersionsAndOptionalMetadata() {
        val app = CatalogParser.parse(catalog(entry(
            """, "iconUrl":"https://example.org/icon.png", "sha256":"${"A".repeat(64)}", "sizeBytes":25165178""",
            "4294967301"
        ))).single()
        assertEquals(4294967301L, app.versionCode)
        assertEquals("31.16.0.TV-rustore", app.versionName)
        assertEquals("a".repeat(64), app.sha256)
        assertEquals(25165178L, app.sizeBytes)
        assertEquals("https://example.org/icon.png", app.iconUrl)
        assertNull(CatalogParser.parse(catalog()).single().iconUrl)
    }

    @Test fun rejectsDuplicatePackagesAndMalformedSecondEntriesAsAWhole() {
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog("${entry()},${entry()}")) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog("${entry()},null")) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog("${entry()},{\"name\":\"broken\"}")) }
    }

    @Test fun rejectsCoercedFractionalNegativeAndOverflowVersionCodes() {
        for (code in listOf("\"13\"", "13.2", "13.0", "0", "-1", "9223372036854775808", "true")) {
            assertThrows("code=$code", IllegalArgumentException::class.java) { CatalogParser.parse(catalog(entry(code = code))) }
        }
    }

    @Test fun rejectsUnsafeUrlsAndPaths() {
        for (url in listOf("http://example.org/app.apk", "https://user:pass@example.org/app.apk", "https://example.org/app.apk#fragment", "https://", "file:///app.apk")) {
            assertThrows("url=$url", IllegalArgumentException::class.java) {
                CatalogParser.parse(catalog().replace("https://disk.yandex.ru/d/catalog", url))
            }
        }
        for (path in listOf("app.apk", "/../app.apk", "/folder/../app.apk", "/folder/./app.apk", "/folder\\\\app.apk")) {
            assertThrows("path=$path", IllegalArgumentException::class.java) {
                CatalogParser.parse(catalog().replace("/RUTUBE 31.16.0.TV-rustore.apk", path))
            }
        }
    }

    @Test fun rejectsInvalidOptionalFieldsAndTextTypes() {
        for (extra in listOf(", \"sha256\":\"not-a-checksum\"", ", \"sizeBytes\":0", ", \"sizeBytes\":\"10\"", ", \"iconUrl\":123")) {
            assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog(entry(extra))) }
        }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog().replace("\"RUTUBE\"", "123")) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(catalog().replace("\"RUTUBE\"", "\" RUTUBE \"")) }
    }

    @Test fun rejectsEmptyMalformedOversizedAndTrailingDocuments() {
        for (source in listOf("", "{}", "[]", "{\"apps\":[]}", "{\"apps\":{}}", catalog() + "{}", "x".repeat(CatalogParser.MAX_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(source) }
        }
        assertEquals(1, CatalogParser.parse("\uFEFF" + catalog()).size)
    }

    @Test fun malformedRemoteNeverOverwritesCacheAndReturnsWholeValidCachedCatalog() {
        var saved: String? = null
        val result = CatalogLoader(
            remote = { catalog("${entry()},{\"name\":\"broken\"}") },
            cached = { catalog() }, bundled = { null }, cacheWriter = { saved = it }
        ).load()
        assertEquals(CatalogSource.CACHE, result.source)
        assertEquals(1, result.apps.size)
        assertNotNull(result.warning)
        assertNull(saved)
    }

    @Test fun offlineFirstRunUsesBundleAndCorruptCacheDoesNotHideBundle() {
        val result = CatalogLoader(
            remote = { throw java.io.IOException("offline") },
            cached = { "corrupt" }, bundled = { catalog() }, cacheWriter = {}
        ).load()
        assertEquals(CatalogSource.BUNDLED, result.source)
        assertEquals("RUTUBE", result.apps.single().name)
        assertNotNull(result.warning)
    }

    @Test fun cacheFailureKeepsValidNetworkResultWithWarning() {
        val result = CatalogLoader(
            remote = { catalog() }, cached = { null }, bundled = { null },
            cacheWriter = { throw java.io.IOException("disk full") }
        ).load()
        assertEquals(CatalogSource.NETWORK, result.source)
        assertTrue(result.apps.isNotEmpty())
        assertNotNull(result.warning)
    }

    @Test fun unavailableSourcesReturnActionableError() {
        val error = assertThrows(IllegalStateException::class.java) {
            CatalogLoader(remote = { throw java.io.IOException("offline") }, cached = { null }, bundled = { null }, cacheWriter = {}).load()
        }
        assertTrue(error.message.orEmpty().contains("подключение"))
    }

    @Test fun cancellationIsNotConvertedIntoOfflineFallback() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            CatalogLoader(remote = { throw java.util.concurrent.CancellationException() },
                cached = { catalog() }, bundled = { catalog() }, cacheWriter = {}).load()
        }
    }
}
