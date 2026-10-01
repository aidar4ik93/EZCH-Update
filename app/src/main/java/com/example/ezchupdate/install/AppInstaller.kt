package com.example.ezchupdate.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.data.versionCodeCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AppInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(context: Context, app: RemoteApp): Result<Unit> = runCatching {
        check(canRequestPackageInstalls(context)) {
            "Разрешите EZCH Update устанавливать приложения в настройках Android"
        }

        val apkFile = downloadApk(context, app)
        try {
            validateApk(context.packageManager, apkFile, app)
            commitInstall(context, apkFile, app)
        } finally {
            apkFile.delete()
        }
    }

    private fun downloadApk(context: Context, app: RemoteApp): File {
        val destination = File(context.cacheDir, "downloads").apply { mkdirs() }
        val apkFile = File(destination, "${app.packageName}-${app.versionCode}.apk")
        val downloadUrl = resolveDownloadUrl(app)
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
        }

        try {
            check(connection.responseCode in 200..299) {
                "Не удалось скачать ${app.name}: HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output -> input.copyTo(output) }
            }
            check(apkFile.length() > 0) { "Загружен пустой APK для ${app.name}" }
            return apkFile
        } catch (error: Throwable) {
            apkFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveDownloadUrl(app: RemoteApp): String {
        val sourceUri = Uri.parse(app.apkUrl)
        if (sourceUri.host !in setOf("disk.yandex.ru", "yadi.sk")) return app.apkUrl

        val encodedPublicKey = URLEncoder.encode(app.apkUrl, "UTF-8")
        val encodedPath = URLEncoder.encode(app.apkPath, "UTF-8")
        val metadataUrl = "https://cloud-api.yandex.net/v1/disk/public/resources/download" +
            "?public_key=$encodedPublicKey&path=$encodedPath"
        val connection = (URL(metadataUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        return try {
            check(connection.responseCode in 200..299) {
                "Не удалось получить ссылку на APK ${app.name}: HTTP ${connection.responseCode}"
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response).getString("href")
        } finally {
            connection.disconnect()
        }
    }

    private fun validateApk(packageManager: PackageManager, apkFile: File, app: RemoteApp) {
        val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: error("Файл ${app.name} не является корректным APK")

        check(packageInfo.packageName == app.packageName) {
            "APK ${app.name} содержит пакет ${packageInfo.packageName}, ожидался ${app.packageName}"
        }
        check(packageInfo.versionCodeCompat() == app.versionCode) {
            "Версия APK ${app.name} не совпадает с каталогом: ожидалась ${app.versionCode}, получена ${packageInfo.versionCodeCompat()}"
        }
    }

    private fun commitInstall(context: Context, apkFile: File, app: RemoteApp) {
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
        }
        val sessionId = installer.createSession(parameters)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, app.packageName)
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag()
                val statusIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    app.packageName.hashCode(),
                    intent,
                    flags
                )
                session.commit(statusIntent.intentSender)
            }
        } catch (error: Throwable) {
            installer.abandonSession(sessionId)
            throw error
        }
    }

    private fun mutablePendingIntentFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        android.app.PendingIntent.FLAG_MUTABLE
    } else {
        0
    }
}
