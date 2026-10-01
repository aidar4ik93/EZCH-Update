package com.example.ezchupdate.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.data.versionCodeCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class InstallSubmission(val sessionId: Int, val packageName: String)

/** Downloads and prepares one APK. Submission is not success: wait for its exact system callback. */
class AppInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun install(
        context: Context,
        app: RemoteApp,
        onProgress: (InstallProgress) -> Unit = {}
    ): InstallSubmission = withContext(Dispatchers.IO) {
        if (!canRequestPackageInstalls(context)) {
            throw InstallException("Разрешите EZCH Update устанавливать приложения в настройках Android")
        }
        val apk = ApkDownloader().download(File(context.cacheDir, "downloads"), app, onProgress)
        try {
            currentCoroutineContext().ensureActive()
            onProgress(InstallProgress(InstallStage.VERIFYING))
            validateApk(context.packageManager, apk, app)
            currentCoroutineContext().ensureActive()
            onProgress(InstallProgress(InstallStage.PREPARING, 0, apk.length()))
            commitInstall(context, apk, app, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: InstallException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw InstallException("Не удалось подготовить установку: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            ApkDownloader.release(apk)
        }
    }

    fun cancel(context: Context, sessionId: Int) {
        val active = InstallEvents.activeSession(context, sessionId) ?: return
        val success = runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }.isSuccess
        InstallEvents.record(context, InstallResult(
            sessionId, active.packageName,
            if (success) PackageInstaller.STATUS_FAILURE_ABORTED else PackageInstaller.STATUS_FAILURE,
            if (success) "Установка отменена" else "Android уже завершает установку. Проверьте установленную версию"
        ))
    }

    fun confirmationIntent(context: Context, sessionId: Int): Intent? = InstallEvents.confirmation(context, sessionId)

    fun confirmationLaunchFailed(context: Context, sessionId: Int, detail: String?) {
        val active = InstallEvents.activeSession(context, sessionId) ?: return
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        InstallEvents.record(context, InstallResult(sessionId, active.packageName, PackageInstaller.STATUS_FAILURE,
            "Android не открыл подтверждение установки. Повторите установку из открытого приложения${detail?.let { ": $it" }.orEmpty()}"))
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(manager: PackageManager, apk: File, app: RemoteApp) {
        // Android 13's archive parser collects/verifies certificates only with GET_SIGNATURES.
        // Request both: modern signing history plus verified legacy certificates on those builds.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw InstallException("Загруженный файл ${app.name} не является корректным подписанным APK")
        if (archive.packageName != app.packageName) {
            throw InstallException("APK содержит пакет ${archive.packageName}, а каталог ожидает ${app.packageName}. Обновите каталог")
        }
        if (archive.versionCodeCompat() != app.versionCode) {
            throw InstallException("Версия APK ${archive.versionCodeCompat()} не совпадает с каталогом (${app.versionCode}). Обновите каталог")
        }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: 1
        if (minSdk > Build.VERSION.SDK_INT) throw InstallException("${app.name} требует Android API $minSdk; на устройстве API ${Build.VERSION.SDK_INT}")
        val incoming = signers(archive)
        if (incoming.isEmpty()) throw InstallException("APK не содержит проверяемой цифровой подписи")
        val installed = try { manager.getPackageInfo(app.packageName, flags) } catch (_: PackageManager.NameNotFoundException) { null }
        if (installed != null) {
            if (installed.versionCodeCompat() > app.versionCode) throw InstallException("На устройстве уже установлена более новая версия ${app.name}")
            if (!DownloadPolicy.compatibleSigners(signers(installed), incoming, signerHistory(archive))) {
                throw InstallException("Подпись ${app.name} отличается от установленной версии. Получите APK того же разработчика")
            }
        }
        ZipFile(apk).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") == null) throw InstallException("В APK отсутствует AndroidManifest.xml")
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .mapNotNull { it.split('/').getOrNull(1) }.toSet()
            if (abis.isNotEmpty() && abis.intersect(Build.SUPPORTED_ABIS.toSet()).isEmpty()) {
                throw InstallException("APK не поддерживает процессор устройства (${Build.SUPPORTED_ABIS.joinToString()}). Нужна другая сборка")
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null
        // Both fields originate from Android's certificate verifier, never ZIP certificate files.
        val signatures = modern?.takeIf { it.isNotEmpty() } ?: info.signatures
        return signatures.orEmpty().map { digest(it.toByteArray()) }.toSet()
    }

    private fun signerHistory(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val history = info.signingInfo?.signingCertificateHistory.orEmpty().map { digest(it.toByteArray()) }.toSet()
        history.ifEmpty { signers(info) }
    } else signers(info)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun commitInstall(context: Context, apk: File, app: RemoteApp, progress: (InstallProgress) -> Unit): InstallSubmission {
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(parameters)
        val submission = InstallSubmission(sessionId, app.packageName)
        try {
            // Persist before writing/commit so a callback or process restart cannot lose ownership.
            InstallEvents.started(context, submission, app.versionCode)
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            progress(InstallProgress(InstallStage.PREPARING, written, apk.length()))
                        }
                        session.fsync(output)
                    }
                }
                currentCoroutineContext().ensureActive()
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .setAction("${context.packageName}.INSTALL_RESULT.$sessionId")
                    .setData(Uri.parse("ezch-install://session/$sessionId"))
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, app.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_OWN_SESSION_ID, sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusIntent = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                InstallEvents.markCommitted(context, sessionId)
                session.commit(statusIntent.intentSender)
                return submission
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            InstallEvents.record(context, InstallResult(sessionId, app.packageName, PackageInstaller.STATUS_FAILURE_ABORTED,
                if (error is CancellationException) "Установка отменена" else "Не удалось передать APK установщику Android"))
            throw error
        }
    }
}
