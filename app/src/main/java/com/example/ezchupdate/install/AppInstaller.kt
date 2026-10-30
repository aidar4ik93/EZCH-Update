package com.example.ezchupdate.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.ezchupdate.data.GoogleDriveDownload
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.data.QuarantinedApks
import com.example.ezchupdate.data.versionCodeCompat
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import android.os.StatFs

private val STAGE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
private val STAGE_SLOTS = java.util.concurrent.Semaphore(2, true)

class AppInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(
        context: Context,
        app: RemoteApp,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Int> = runCatching {
        enforceQuarantine(app)
        check(canRequestPackageInstalls(context)) { "Разрешите установку приложений в настройках Android" }
        val apkFile = stage(context, app, onProgress)
        try {
            installFile(context, app, apkFile).getOrThrow()
        } finally {
            apkFile.delete()
        }
    }

    /** Device tests use this with a locally built, signed fixture APK. */
    internal fun installFile(context: Context, app: RemoteApp, apkFile: File): Result<Int> = runCatching {
        enforceQuarantine(app)
        check(canRequestPackageInstalls(context)) { "Разрешите установку приложений в настройках Android" }
        validateApk(context.packageManager, apkFile, app)
        commitInstall(context, apkFile, app)
    }

    private fun enforceQuarantine(app: RemoteApp) {
        check(app.installable) { "Нет проверенного APK: ${app.name}" }
        check(!QuarantinedApks.isQuarantined(app)) {
            "Этот файл на карантине до завершения проверки: ${app.name}"
        }
    }

    /** Background prefetch only stages verified bytes, never an install session. */
    fun stage(context: Context, app: RemoteApp, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        enforceQuarantine(app)
        require(app.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
            app.signerSha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
            app.sizeBytes != null && app.sizeBytes in 1..MAX_APK_BYTES) {
            "Каталог не содержит данных проверки APK: ${app.name}"
        }
        // One download per APK: a background prefetch and the install step may ask for the same file.
        return synchronized(STAGE_LOCKS.getOrPut(app.sha256!!.lowercase()) { Any() }) {
            // The foreground install may download alongside both prefetch workers.
            // Bound all stage callers together, including background update checks.
            STAGE_SLOTS.acquire()
            try { stageLocked(context, app, onProgress) } finally { STAGE_SLOTS.release() }
        }
    }

    private fun stageLocked(context: Context, app: RemoteApp, onProgress: (Long, Long) -> Unit): File {
        val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
        val cached = File(directory, "${app.sha256}.apk")
        if (cached.isFile) {
            val verified = runCatching { validateApk(context.packageManager, cached, app) }.isSuccess
            if (verified) { onProgress(cached.length(), cached.length()); return cached }
            cached.delete()
        }
        val downloaded = downloadApk(context, app, onProgress)
        try { validateApk(context.packageManager, downloaded, app) }
        catch (error: Exception) { downloaded.delete(); throw error }
        return downloaded
    }

    private fun downloadApk(context: Context, app: RemoteApp, onProgress: (Long, Long) -> Unit): File {
        val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
        check(StatFs(directory.absolutePath).availableBytes >= (app.sizeBytes ?: MAX_APK_BYTES) * 2 + 16 * 1024 * 1024) {
            "Недостаточно свободного места для загрузки и установки ${app.name}"
        }
        val apkFile = File.createTempFile("apk-", ".part", directory)
        val connection = GoogleDriveDownload.open(app.apkUrl)
        try {
            val total = connection.contentLengthLong
            var downloaded = 0L
            onProgress(0, total)
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        check(downloaded <= MAX_APK_BYTES) { "APK превышает допустимый размер" }
                        check(app.sizeBytes == null || downloaded <= app.sizeBytes) { "Размер загрузки превышает каталог: ${app.name}" }
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150) {
                            onProgress(downloaded, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            check(downloaded > 0) { "Сервер вернул пустой APK: ${app.name}" }
            check(total < 0 || downloaded == total) { "APK загружен не полностью: ${app.name}" }
            onProgress(downloaded, total)
            val destination = File(directory, "${app.sha256}.apk")
            check(apkFile.renameTo(destination)) { "Не удалось сохранить APK" }
            return destination
        } catch (error: Exception) {
            apkFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun validateApk(packageManager: PackageManager, apkFile: File, app: RemoteApp) {
        app.sizeBytes?.let { expectedSize ->
            check(apkFile.length() == expectedSize) { "Размер APK не совпадает с каталогом: ${app.name}" }
        }
        app.sha256?.let { expectedHash ->
            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(digest.digest().toHex().equals(expectedHash, ignoreCase = true)) {
                "SHA-256 APK не совпадает с каталогом: ${app.name}; файл отклонён"
            }
        }
        @Suppress("DEPRECATION")
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags)
            ?: error("Загруженный файл ${app.name} не является APK")
        check(archive.packageName == app.packageName) { "Имя пакета APK не совпадает с каталогом: ${app.name}" }
        check(archive.versionCodeCompat() == app.versionCode) { "Версия APK не совпадает с каталогом: ${app.name}" }
        check(archive.versionName == app.versionName) { "Название версии APK не совпадает с каталогом: ${app.name}" }
        check(archive.splitNames.isNullOrEmpty()) {
            "${app.name}: нужен полный APK, отдельные части приложения не поддерживаются"
        }
        val archiveInfo = checkNotNull(archive.applicationInfo) { "Не удалось прочитать манифест ${app.name}" }
        archiveInfo.sourceDir = apkFile.absolutePath
        archiveInfo.publicSourceDir = apkFile.absolutePath
        packageManager.getResourcesForApplication(archiveInfo).assets.openXmlResourceParser("AndroidManifest.xml").use { xml ->
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    check(!(xml.name == "manifest" && !xml.getAttributeValue(null, "split").isNullOrBlank()) &&
                        xml.name != "uses-split" && !xml.getAttributeBooleanValue(
                            "http://schemas.android.com/apk/res/android", "isSplitRequired", false)) {
                        "${app.name}: требуется комплект split APK; выберите полный универсальный APK"
                    }
                }
                xml.next()
            }
        }
        java.util.zip.ZipFile(apkFile).use { zip ->
            val nativeAbis = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            check(nativeAbis.isEmpty() || Build.SUPPORTED_ABIS.any { it in nativeAbis }) {
                "${app.name}: APK не поддерживает процессор этого устройства (${Build.SUPPORTED_ABIS.joinToString()})"
            }
        }
        check(archive.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } != false) {
            "${app.name}: требуется более новая версия Android"
        }
        val archiveSigners = currentSignerDigests(archive)
        check(archiveSigners.isNotEmpty()) { "Не удалось проверить подпись APK: ${app.name}" }
        app.signerSha256?.let { expectedCertificate ->
            check(expectedCertificate.lowercase(Locale.ROOT) in archiveSigners) {
                "Сертификат подписи APK не совпадает с каталогом: ${app.name}; файл отклонён"
            }
        }
        val installed = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(app.packageName, signingFlags)
        } catch (_: PackageManager.NameNotFoundException) { null }
        if (installed != null) {
            val installedSigners = currentSignerDigests(installed)
            // A single new signer may carry an Android-verified proof of rotation
            // from the installed signer. Multiple signer sets must match exactly.
            val validRotation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                installedSigners.size == 1 && archiveSigners.size == 1 &&
                archive.signingInfo?.signingCertificateHistory.orEmpty()
                    .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }
                    .containsAll(installedSigners)
            check(installedSigners.isNotEmpty() && (installedSigners == archiveSigners || validRotation)) {
                "${app.name}: подпись APK несовместима с установленной версией; требуется сборка с прежним ключом"
            }
        }
        check(installed == null || installed.versionCodeCompat() < app.versionCode) {
            "${app.name}: эта или более новая версия уже установлена"
        }
    }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        // Some TV package parsers leave SigningInfo empty for archive files even
        // when GET_SIGNING_CERTIFICATES is requested. Request both representations
        // and use the actual legacy certificates rather than skipping verification.
        val currentSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
        } else null
        val signatures = currentSigners ?: info.signatures.orEmpty()
        return signatures.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun commitInstall(context: Context, apkFile: File, app: RemoteApp): Int {
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        }
        val sessionId = installer.createSession(parameters)
        try {
            // Persist before commit; Android can replace this process during self-update.
            InstallQueueStore(context).bindSession(app.packageName, sessionId)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, app.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return sessionId
    }

    companion object { const val MAX_APK_BYTES = 1024L * 1024 * 1024 }
}
