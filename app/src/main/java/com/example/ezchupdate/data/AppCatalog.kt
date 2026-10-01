package com.example.ezchupdate.data

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteApp(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkPath: String
)

data class InstalledApp(
    val versionCode: Long,
    val versionName: String?
)

class CatalogRepository(
    private val catalogUrl: String = "https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/apps.json"
) {
    fun load(): List<RemoteApp> = parse(readText(catalogUrl))

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

    private fun parse(source: String): List<RemoteApp> {
        val apps = JSONObject(source).getJSONArray("apps")
        return List(apps.length()) { index ->
            val item = apps.getJSONObject(index)
            RemoteApp(
                name = item.getString("name"),
                packageName = item.getString("packageName"),
                versionCode = item.getLong("versionCode"),
                versionName = item.getString("versionName"),
                apkUrl = item.getString("apkUrl"),
                apkPath = item.getString("apkPath")
            )
        }
    }

    private fun readText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        return try {
            check(connection.responseCode in 200..299) {
                "Не удалось загрузить каталог: HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
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
