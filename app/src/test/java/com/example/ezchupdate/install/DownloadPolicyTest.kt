package com.example.ezchupdate.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test fun yandexSignedHttpsUrlIsAcceptedWithoutChangingItsQuery() {
        val signed = "https://downloader.disk.yandex.ru/disk/hash/file.apk?uid=0&filename=TV%20App.apk"
        assertEquals(signed, DownloadPolicy.secureUrl(signed).toString())
    }

    @Test fun relativeHttpsRedirectWorksButDowngradeAndCredentialsAreRejected() {
        val source = DownloadPolicy.secureUrl("https://example.com/releases/current.apk")
        assertEquals("https://example.com/releases/next.apk", DownloadPolicy.redirect(source, "next.apk", 0).toString())
        reject { DownloadPolicy.redirect(source, "http://example.com/app.apk", 0) }
        reject { DownloadPolicy.redirect(source, "https://user:password@example.com/app.apk", 0) }
        reject { DownloadPolicy.redirect(source, "https://localhost/app.apk", 0) }
        reject { DownloadPolicy.redirect(source, null, 0) }
        reject { DownloadPolicy.redirect(source, "/next.apk", DownloadPolicy.MAX_REDIRECTS) }
    }

    @Test fun truncatedOversizeAndEmptyDownloadsAreRejected() {
        DownloadPolicy.checkSize(100, 200, 100)
        reject { DownloadPolicy.checkSize(0, 200) }
        reject { DownloadPolicy.checkSize(99, 200, 100) }
        reject { DownloadPolicy.checkSize(201, 200) }
    }

    @Test fun htmlAndIncorrectChecksumAreRejected() {
        DownloadPolicy.checkApkHeader(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        reject { DownloadPolicy.checkApkHeader("<htm".toByteArray()) }
        reject { DownloadPolicy.checkApkHeader(byteArrayOf(0x50, 0x4b)) }
        DownloadPolicy.checkDigest("ABCD", "abcd")
        reject { DownloadPolicy.checkDigest("abcd", "0000") }
    }

    @Test fun signerRotationAllowsNewAuthorizedSignerAndRejectsUnrelatedOrRollbackSigner() {
        assertTrue(DownloadPolicy.compatibleSigners(setOf("old"), setOf("new"), setOf("old", "new")))
        assertFalse(DownloadPolicy.compatibleSigners(setOf("new"), setOf("old"), setOf("old")))
        assertFalse(DownloadPolicy.compatibleSigners(setOf("old"), setOf("other"), setOf("other")))
        assertFalse(DownloadPolicy.compatibleSigners(emptySet(), setOf("other"), setOf("other")))
    }

    @Test fun multipleSignerIdentityMustMatchTheEntireSet() {
        assertTrue(DownloadPolicy.compatibleSigners(setOf("a", "b"), setOf("b", "a"), emptySet()))
        assertFalse(DownloadPolicy.compatibleSigners(setOf("a", "b"), setOf("a"), setOf("a", "b")))
    }

    private fun reject(block: () -> Unit) {
        try { block(); throw AssertionError("Expected InstallException") } catch (_: InstallException) { }
    }
}
