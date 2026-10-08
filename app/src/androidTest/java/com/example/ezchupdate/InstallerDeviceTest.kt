package com.example.ezchupdate

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.data.CatalogRepository
import com.example.ezchupdate.data.versionCodeCompat
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallEvents
import com.example.ezchupdate.install.InstallResultReceiver
import com.example.ezchupdate.install.InstallSnapshot
import com.example.ezchupdate.install.InstallQueueStore
import com.example.ezchupdate.install.InstallQueueState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class InstallerDeviceTest {
    @Test fun previousYandexCacheFallsBackToGoogleDriveCatalog() {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.cacheDir, "previous-source-test").apply { mkdirs() }
        }
        val old = JSONObject(context.assets.open("apps.json").bufferedReader().use { it.readText() })
        val items = old.getJSONArray("apps")
        for (i in 0 until items.length()) {
            val app = items.getJSONObject(i)
            if (app.getString("apkUrl").contains("drive.usercontent.google.com")) app.put("apkUrl", "https://disk.yandex.ru/d/old-source")
        }
        val cached = File(isolated.filesDir, "catalog.json").apply { writeText(old.toString()) }
        try {
            val repository = CatalogRepository(isolated, sourceReader = { throw java.io.IOException("offline") })
            val apps = repository.load()
            assertTrue(repository.offline)
            assertTrue(apps.any { it.apkUrl.contains("drive.usercontent.google.com") })
            assertTrue(apps.none { it.apkUrl.contains("yandex") })
        } finally { cached.delete() }
    }
    @Test fun firstOfflineLaunchUsesBundledCatalog() {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.cacheDir, "offline-catalog-test").apply { mkdirs() }
        }
        File(isolated.filesDir, "catalog.json").delete()
        val repository = CatalogRepository(isolated, sourceReader = { throw java.io.IOException("offline") })
        assertTrue(repository.load().isNotEmpty())
        assertTrue(repository.offline)
        assertTrue(repository.load().none { com.example.ezchupdate.data.QuarantinedApks.isQuarantined(it) })
    }
    @Test fun corruptCacheFallsBackToBundledCatalog() {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.cacheDir, "corrupt-catalog-test").apply { mkdirs() }
        }
        val cache = File(isolated.filesDir, "catalog.json").apply { writeText("broken JSON") }
        try {
            val repository = CatalogRepository(isolated, sourceReader = { throw java.io.IOException("offline") })
            assertTrue(repository.load().isNotEmpty())
            assertTrue(repository.offline)
        } finally { cache.delete() }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val fixtureApp = RemoteApp("EZCH Test", "com.example.ezchfixture", 1, "1.0", "https://example.com/test.apk", "/test.apk")
    private lateinit var fixture: File

    @Before fun prepare() {
        device.executeShellCommand("am force-stop com.google.android.packageinstaller")
        // A Google TV launcher without an account can reopen its profile chooser
        // after HOME and steal focus from the confirmation Activity.
        device.executeShellCommand("pm uninstall com.example.ezchfixture")
        // Gradle removes this isolated debug application after the suite. Revoking
        // this app-op during a test would kill its instrumentation process.
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        assertTrue("The test APK must be allowed to request installation", AppInstaller().canRequestPackageInstalls(context))
        context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("install_queue", Context.MODE_PRIVATE).edit().clear().commit()
        InstallEvents.snapshot.value = null
        fixture = File(context.cacheDir, "fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
    }

    @After fun clean() {
        context.packageManager.packageInstaller.mySessions.forEach {
            runCatching { context.packageManager.packageInstaller.abandonSession(it.sessionId) }
        }
        device.executeShellCommand("pm uninstall com.example.ezchfixture")
        fixture.delete()
        context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("install_queue", Context.MODE_PRIVATE).edit().clear().commit()
        InstallEvents.snapshot.value = null
    }

    @Test fun rejectsWrongPackageAndVersionBeforeCreatingSession() {
        val installer = AppInstaller()
        val wrongPackage = installer.installFile(context, fixtureApp.copy(packageName = "com.example.wrong"), fixture)
        val wrongVersion = installer.installFile(context, fixtureApp.copy(versionCode = 2), fixture)
        assertTrue(wrongPackage.exceptionOrNull()?.message.orEmpty().contains("Имя пакета APK не совпадает"))
        assertTrue(wrongVersion.exceptionOrNull()?.message.orEmpty().contains("Версия APK не совпадает"))
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun oldCatalogCannotReintroduceQuarantinedPayloads() {
        val allowed = verifiedFixtureApp()
        val oldEntries = listOf(
            fixtureApp.copy(name = "Projectivy", packageName = "com.spocky.projengmenu", versionCode = 92),
            fixtureApp.copy(name = "Task Killer", packageName = "jp.snowlife01.android.appkiller2", versionCode = 43),
            fixtureApp.copy(name = "Panda Vision", packageName = "com.play.pandafref", versionCode = 1001),
            fixtureApp.copy(
                name = "Renamed payload", packageName = "com.example.renamedpayload", versionCode = 2,
                sha256 = "AE3AA779708FC2D32C298871AF6CC139CA4864C18BF8275A22D1726DA323A8BC"
            )
        )
        val oldCatalog = JSONObject().put("apps", JSONArray().apply {
            (listOf(allowed) + oldEntries).forEach { app ->
                put(JSONObject()
                    .put("name", app.name).put("packageName", app.packageName)
                    .put("versionCode", app.versionCode).put("versionName", app.versionName)
                    .put("apkUrl", app.apkUrl).put("apkPath", app.apkPath).apply {
                        app.sha256?.let { put("sha256", it) }
                        app.sizeBytes?.let { put("sizeBytes", it) }
                        app.signerSha256?.let { put("signerSha256", it) }
                    })
            }
        })
        assertEquals(listOf(allowed), CatalogRepository().parse(oldCatalog.toString()))
    }

    @Test fun remoteCatalogChangesOverrideBundledApps() {
        val files = File(context.cacheDir, "catalog-refresh-test").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = files }
        var source = catalogJson(listOf(verifiedFixtureApp()))
        var unavailable = false
        val repository = CatalogRepository(wrapped, sourceReader = {
            if (unavailable) error("offline") else source
        })
        try {
            assertEquals(listOf(verifiedFixtureApp()), repository.load())
            source = catalogJson(listOf(verifiedFixtureApp().copy(packageName = "com.example.newapp", versionCode = 2)))
            assertEquals("com.example.newapp", repository.load().single().packageName)
            source = catalogJson(emptyList())
            assertTrue(repository.load().isEmpty())
            unavailable = true
            assertTrue(repository.load().isEmpty())
            assertTrue(repository.offline)
        } finally {
            File(files, "catalog.json").delete()
            File(files, "catalog.json.tmp").delete()
            files.delete()
        }
    }

    @Test fun invalidRemoteCannotReplaceVerifiedOfflineCatalog() {
        val files = File(context.cacheDir, "catalog-integrity-test").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val verified = verifiedFixtureApp()
        var source = catalogJson(listOf(verified))
        val repository = CatalogRepository(wrapped, sourceReader = { source })
        try {
            assertEquals(listOf(verified), repository.load())
            source = catalogJson(listOf(fixtureApp))
            assertEquals(listOf(verified), repository.load())
            assertTrue(repository.offline)
            assertEquals(catalogJson(listOf(verified)), File(files, "catalog.json").readText())
        } finally {
            File(files, "catalog.json").delete()
            File(files, "catalog.json.tmp").delete()
            files.delete()
        }
    }

    private fun catalogJson(apps: List<RemoteApp>): String = JSONObject().put("apps", JSONArray().apply {
        apps.forEach { app ->
            put(JSONObject().put("name", app.name).put("packageName", app.packageName)
                .put("versionCode", app.versionCode).put("versionName", app.versionName)
                .put("apkUrl", app.apkUrl).put("apkPath", app.apkPath).apply {
                    app.sha256?.let { put("sha256", it) }
                    app.sizeBytes?.let { put("sizeBytes", it) }
                    app.signerSha256?.let { put("signerSha256", it) }
                })
        }
    }).toString()

    @Test fun restoredQueueRejectsQuarantineBeforePermissionOrDownload() {
        val legacyPayload = fixtureApp.copy(
            name = "Panda Vision", packageName = "com.play.pandafref", versionCode = 1001,
            apkUrl = "invalid://must-not-download", sha256 = null
        )
        val hashPayload = fixtureApp.copy(
            name = "Renamed payload", apkUrl = "invalid://must-not-download",
            sha256 = "8525943680e10080489107c6a52df13dc459c832c3ee8f550ad0ada669bac74c"
        )
        InstallQueueStore(context).save(InstallQueueState(remaining = listOf(legacyPayload, hashPayload), totalCount = 2))
        val restored = InstallQueueStore(context).load()!!
        val guardedContext = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager =
                error("Quarantine must be checked before requesting installation permission")
            override fun getCacheDir(): File = error("Quarantine must be checked before downloading an APK")
        }
        restored.remaining.forEach { app ->
            val expectedMessage = "Этот файл временно исключён из каталога после проверки безопасности: ${app.name}"
            assertEquals(expectedMessage, AppInstaller().install(guardedContext, app).exceptionOrNull()?.message)
            assertEquals(expectedMessage, AppInstaller().installFile(guardedContext, app, fixture).exceptionOrNull()?.message)
        }
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun restoresPendingConfirmationAndFinalResult() {
        instrumentation.runOnMainSync {
            val pendingIntent = Intent("test.confirm").putExtra("session", 123)
            InstallEvents.record(context, InstallSnapshot(fixtureApp.packageName, 123, PackageInstaller.STATUS_PENDING_USER_ACTION, null, pendingIntent))
            InstallEvents.consumeConfirmation(123)
            assertNull(InstallEvents.snapshot.value?.confirmationIntent)
            InstallEvents.restore(context)
            assertEquals(123, InstallEvents.snapshot.value?.confirmationIntent?.getIntExtra("session", -1))
            InstallEvents.record(context, InstallSnapshot(fixtureApp.packageName, 123, PackageInstaller.STATUS_SUCCESS, "ok"))
            InstallEvents.snapshot.value = null
            InstallEvents.restore(context)
            assertEquals(PackageInstaller.STATUS_SUCCESS, InstallEvents.snapshot.value?.status)
            InstallEvents.consume(context, 123)
            assertNull(InstallEvents.snapshot.value)
        }
    }

    @Test fun restoresQueueWithSessionAndFailures() {
        val state = InstallQueueState(
            remaining = listOf(verifiedFixtureApp()), current = verifiedFixtureApp(), sessionId = 321,
            completedCount = 1, successCount = 1, totalCount = 2,
            failures = listOf("test failure"), waitingForPermission = true
        )
        val store = InstallQueueStore(context)
        store.save(state)
        assertEquals(state, store.load())
        context.getSharedPreferences("install_queue", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun rejectsDamagedApkBeforeCreatingSession() {
        val damaged = File(context.cacheDir, "damaged-fixture.apk")
        try {
            fixture.copyTo(damaged, overwrite = true)
            RandomAccessFile(damaged, "rw").use { file ->
                file.seek(file.length() - 1)
                val originalByte = file.read()
                file.seek(file.length() - 1)
                file.write(originalByte xor 1)
            }
            val result = AppInstaller().installFile(context, verifiedFixtureApp(), damaged)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256 APK не совпадает"))
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        } finally {
            damaged.delete()
        }
    }

    @Test fun rejectsWrongSizeBeforeCreatingSession() {
        val result = AppInstaller().installFile(context, verifiedFixtureApp().copy(sizeBytes = fixture.length() + 1), fixture)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Размер APK не совпадает"))
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun rejectsWrongSignerCertificateBeforeCreatingSession() {
        val result = AppInstaller().installFile(context, verifiedFixtureApp().copy(signerSha256 = "0".repeat(64)), fixture)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Сертификат подписи APK не совпадает"))
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun missingConfirmationIsFailureInsteadOfHanging() {
        val intent = Intent().putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, fixtureApp.packageName)
            .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, 456)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        InstallResultReceiver().onReceive(context, intent)
        assertEquals(PackageInstaller.STATUS_FAILURE, InstallEvents.snapshot.value?.status)
    }

    @Test fun systemCancellationProducesFailureCallback() {
        beginFixtureInstall()
        val cancel = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(cancel|отмена)"))), 15_000)
        assertNotNull("System installer must show a Cancel button", cancel)
        cancel.click()
        awaitFinalResult()
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, InstallEvents.snapshot.value?.status)
    }

    @Test fun installationRequiresUserConfirmationAndReportsSuccess() {
        beginFixtureInstall()
        val install = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(install|установить)"))), 15_000)
        assertNotNull("System installer must show an Install button", install)
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallEvents.snapshot.value?.status)
        install.click()
        awaitFinalResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallEvents.snapshot.value?.status)
        assertEquals(1L, context.packageManager.getPackageInfo(fixtureApp.packageName, 0).versionCodeCompat())
    }

    private fun beginFixtureInstall() {
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue("Test application must be in foreground", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        val result = AppInstaller().installFile(context, verifiedFixtureApp(), fixture)
        assertTrue(result.toString(), result.isSuccess)
        val deadline = System.currentTimeMillis() + 5_000
        while (InstallEvents.snapshot.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        println("Native session: ${result.getOrNull()}, status: ${InstallEvents.snapshot.value?.status}, foreground: ${device.currentPackageName}")
        device.takeScreenshot(File(context.cacheDir, "installer-test.png"))
        device.dumpWindowHierarchy(File(context.cacheDir, "installer-test.xml"))
    }

    private fun awaitFinalResult() {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val result = InstallEvents.snapshot.value
            if (result != null && result.status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
            Thread.sleep(50)
        }
        fail("No final system installation callback received")
    }

    @Suppress("DEPRECATION")
    private fun verifiedFixtureApp(): RemoteApp {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(fixture.absolutePath, flags)!!
        val currentSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
        } else null
        val signatures = currentSigners ?: info.signatures.orEmpty()
        assertEquals("The signed fixture must expose exactly one certificate", 1, signatures.size)
        val signature = signatures.single()
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return fixtureApp.copy(
            sha256 = sha256(fixture.readBytes()),
            sizeBytes = fixture.length(),
            signerSha256 = sha256(signature.toByteArray())
        )
    }
}
