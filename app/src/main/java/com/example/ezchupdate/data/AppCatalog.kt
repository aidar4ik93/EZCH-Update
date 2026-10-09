package com.example.ezchupdate.data

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.URI

data class RemoteApp(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkPath: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val signerSha256: String? = null
)

data class InstalledApp(
    val versionCode: Long,
    val versionName: String?
)

class CatalogRepository(
    private val context: Context? = null,
    private val catalogUrl: String = "https://api.github.com/repos/aidar4ik93/EZCH-Update/contents/apps.json?ref=main",
    private val sourceReader: ((String) -> String)? = null
) {
    var offline: Boolean = false
        private set

    fun load(): List<RemoteApp> {
        val cached = context?.let { File(it.filesDir, "catalog.json") }
        return try {
            val source = sourceReader?.invoke(catalogUrl) ?: readText(catalogUrl)
            val apps = parse(source)
            cached?.let { file ->
                runCatching {
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try {
                        output.write(source.toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(output)
                    } catch (failure: Exception) {
                        atomic.failWrite(output)
                        throw failure
                    }
                }
            }
            offline = false
            apps
        } catch (error: Exception) {
            offline = true
            val apps = runCatching { cached?.takeIf { it.isFile }?.readText()?.let(::parse) }.getOrNull()
            apps ?: runCatching { context?.assets?.open("apps.json")?.bufferedReader()?.use { parse(it.readText()) } }
                .getOrNull() ?: throw IllegalStateException("Не удалось получить каталог. Подключите интернет и нажмите «Проверить обновления».", error)
        }
    }

    fun installedApp(packageManager: PackageManager, packageName: String): InstalledApp? = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        InstalledApp(packageInfo.versionCodeCompat(), packageInfo.versionName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Verified local snapshot is available without waiting for the network. */
    fun loadLocal(): List<RemoteApp>? {
        val cached = context?.let { File(it.filesDir, "catalog.json") }
        return runCatching { cached?.takeIf { it.isFile }?.readText()?.let(::parse) }.getOrNull()
            ?: runCatching { context?.assets?.open("apps.json")?.bufferedReader()?.use { parse(it.readText()) } }.getOrNull()
    }

    internal fun parse(source: String): List<RemoteApp> {
        val apps = JSONObject(source).getJSONArray("apps")
        val result = List(apps.length()) { index ->
            val item = apps.getJSONObject(index)
            RemoteApp(
                name = item.getString("name"),
                packageName = item.getString("packageName"),
                versionCode = item.getLong("versionCode"),
                versionName = item.getString("versionName"),
                apkUrl = item.getString("apkUrl"),
                apkPath = item.getString("apkPath"),
                sha256 = item.optString("sha256").takeIf { it.isNotBlank() }?.lowercase(),
                sizeBytes = if (item.has("sizeBytes")) item.getLong("sizeBytes") else null,
                signerSha256 = item.optString("signerSha256").takeIf { it.isNotBlank() }?.lowercase()
            )
        }
        require(result.map { it.packageName }.distinct().size == result.size) {
            "В каталоге повторяются приложения"
        }
        result.forEach { app ->
            require(app.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                "Некорректное имя пакета: ${app.name}"
            }
            require(app.versionCode > 0 && app.name.isNotBlank() && app.versionName.isNotBlank()) {
                "Некорректная версия приложения: ${app.name}"
            }
            val uri = URI(app.apkUrl)
            require(uri.host?.lowercase() !in setOf("disk.yandex.ru", "disk.yandex.com", "yadi.sk")) { "Источник APK перенесён на Google Drive" }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "Некорректная ссылка на APK: ${app.name}"
            }
            require(app.apkPath.startsWith("/")) { "Некорректный путь APK: ${app.name}" }
            require(app.sha256 == null || app.sha256.matches(Regex("[a-f0-9]{64}"))) { "Некорректный SHA-256: ${app.name}" }
            require(app.signerSha256 == null || app.signerSha256.matches(Regex("[a-f0-9]{64}"))) { "Некорректный сертификат: ${app.name}" }
            require(app.sizeBytes == null || app.sizeBytes > 0) { "Некорректный размер APK: ${app.name}" }
        }
        return result.filterNot(QuarantinedApks::isQuarantined).also { allowed ->
            require(allowed.all { it.sha256 != null && it.sizeBytes != null && it.signerSha256 != null }) {
                "Каталог не содержит данных проверки APK"
            }
        }
    }

    private fun readText(url: String): String {
        val freshUrl = url + (if (url.contains("?")) "&" else "?") + "refresh=" + System.currentTimeMillis()
        val connection = HttpsConnection.open(freshUrl)
        return try {
            check(connection.responseCode in 200..299) {
                "Не удалось загрузить каталог: HTTP ${connection.responseCode}"
            }
            val source = connection.inputStream.bufferedReader().use { it.readText() }
            val document = JSONObject(source)
            if (document.optString("encoding") == "base64" && document.has("content")) {
                String(android.util.Base64.decode(document.getString("content"), android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else source
        } finally {
            connection.disconnect()
        }
    }
}

fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
} else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
}
