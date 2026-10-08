package com.example.homeezch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class StoreEntry(
    val name: String, val packageName: String, val versionName: String,
    val versionCode: Long, val apkUrl: String, val apkPath: String
)

internal suspend fun fetchStoreCatalog(): List<StoreEntry> = withContext(Dispatchers.IO) {
    val url = URL("https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/apps.json")
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 7000
    connection.readTimeout = 9000
    try {
        if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
        val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val apps = root.getJSONArray("apps")
        (0 until apps.length()).map { i ->
            val app = apps.getJSONObject(i)
            StoreEntry(app.optString("name"), app.optString("packageName"),
                app.optString("versionName"), app.optLong("versionCode"),
                app.optString("apkUrl"), app.optString("apkPath"))
        }.filter { it.name.isNotBlank() && it.packageName.isNotBlank() }
    } finally { connection.disconnect() }
}
