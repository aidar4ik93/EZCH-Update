package com.example.ezchupdate.install

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.data.versionCodeCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Black-box preflight with real APKs and Android's actual package/certificate parser. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class ApkValidationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val installer = AppInstaller()
    private val fixture = RemoteApp("Signed test fixture", "org.example.ezch.fixture", 42, "42",
        "https://example.com/fixture.apk", "/fixture.apk")

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "apk-validation-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun productionPreflightAcceptsSignedFixtureOnRealAndroidArchiveParser() {
        // This failed on Android 13 when production requested GET_SIGNING_CERTIFICATES alone.
        installer.validateApk(context.packageManager, copyFixture(), fixture)
    }

    @Test fun ownInstalledSignedApkPassesCertificateComparisonForAnUpdate() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val apk = File(directory, "own-app.part.apk")
        File(context.applicationInfo.sourceDir).copyTo(apk)
        val own = RemoteApp("Own app", context.packageName, installed.versionCodeCompat(), installed.versionName.orEmpty(),
            "https://example.com/own.apk", "/own.apk")
        installer.validateApk(context.packageManager, apk, own)
    }

    @Test fun legacyArchiveSignaturesAreVerifiedAndUsedWhenSigningInfoIsAbsent() {
        val legacy = context.packageManager.getPackageArchiveInfo(copyFixture().absolutePath, PackageManager.GET_SIGNATURES)
        assertNotNull(legacy)
        assertTrue(legacy!!.signatures.orEmpty().isNotEmpty())
        // Requesting only the legacy field deliberately yields no modern signingInfo.
        assertNull(legacy.signingInfo)
        assertEquals(legacy.signatures.orEmpty().size, installer.signers(legacy).size)
        assertTrue(installer.signers(legacy).isNotEmpty())
    }

    @Test fun unsignedButParseableFixtureIsRejectedByProductionPreflight() {
        val unsigned = File(directory, "unsigned.part.apk")
        ZipFile(copyFixture()).use { original ->
            ZipOutputStream(unsigned.outputStream()).use { output ->
                // Rewriting a ZIP omits its APK v2/v3 signing block. Omit v1 signature files too.
                original.entries().asSequence().filterNot { it.name.startsWith("META-INF/") }.forEach { entry ->
                    val bytes = original.getInputStream(entry).use { it.readBytes() }
                    val replacement = ZipEntry(entry.name).apply {
                        method = entry.method
                        if (method == ZipEntry.STORED) {
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                    output.putNextEntry(replacement)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        // Its manifest/package remain valid: rejection must be due to missing verified signing.
        val manifestOnly = context.packageManager.getPackageArchiveInfo(unsigned.absolutePath, 0)
        assertNotNull(manifestOnly)
        assertEquals(fixture.packageName, manifestOnly!!.packageName)
        try {
            installer.validateApk(context.packageManager, unsigned, fixture)
            throw AssertionError("Unsigned APK must be rejected")
        } catch (_: InstallException) { }
    }

    @Test fun verifiedDifferentCertificatesDoNotBecomeAnAllowedUpdate() {
        val apk = copyFixture()
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val incoming = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)!!
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val incomingSigners = installer.signers(incoming)
        val installedSigners = installer.signers(installed)
        assertTrue(incomingSigners.isNotEmpty())
        assertTrue(installedSigners.isNotEmpty())
        assertFalse("Fixture requires its own test key, distinct from the app's key", incomingSigners == installedSigners)
        assertFalse(DownloadPolicy.compatibleSigners(installedSigners, incomingSigners, incomingSigners))
    }

    @Test fun productionPreflightRejectsSignedReplacementOfInstalledAppWithDifferentCertificate() {
        val apk = copyFixture("wrong-signer-fixture.apk")
        val replacement = fixture.copy(packageName = context.packageName, versionCode = 4242)
        try {
            installer.validateApk(context.packageManager, apk, replacement)
            throw AssertionError("A different signer must not replace an installed app")
        } catch (error: InstallException) {
            assertTrue("Expected the signature check itself to reject this valid APK: ${error.message}",
                error.message.orEmpty().contains("Подпись"))
        }
    }

    private fun copyFixture(asset: String = "signed-fixture.apk"): File = File(directory, "fixture.part.apk").also { apk ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            apk.outputStream().use { input.copyTo(it) }
        }
    }
}
