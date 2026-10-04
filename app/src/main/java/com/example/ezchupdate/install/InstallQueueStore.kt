package com.example.ezchupdate.install

import android.content.Context
import com.example.ezchupdate.data.RemoteApp
import org.json.JSONArray
import org.json.JSONObject

data class InstallQueueState(
    val remaining: List<RemoteApp> = emptyList(),
    val current: RemoteApp? = null,
    val sessionId: Int? = null,
    val completedCount: Int = 0,
    val successCount: Int = 0,
    val totalCount: Int = 0,
    val failures: List<String> = emptyList(),
    val waitingForPermission: Boolean = false,
    val cancelRequested: Boolean = false,
    val message: String? = null
)

/** Stores only queues started by the user, including their final outcome. */
class InstallQueueStore(context: Context) {
    private val preferences = context.getSharedPreferences("install_queue", Context.MODE_PRIVATE)

    fun load(): InstallQueueState? = runCatching {
        val source = preferences.getString("queue", null) ?: return@runCatching null
        val json = JSONObject(source)
        val remaining = json.getJSONArray("remaining")
        val failures = json.getJSONArray("failures")
        InstallQueueState(
            remaining = List(remaining.length()) { appFromJson(remaining.getJSONObject(it)) },
            current = json.optJSONObject("current")?.let(::appFromJson),
            sessionId = if (json.isNull("sessionId")) null else json.getInt("sessionId"),
            completedCount = json.optInt("completedCount"),
            successCount = json.optInt("successCount"),
            totalCount = json.optInt("totalCount"),
            failures = List(failures.length()) { failures.getString(it) },
            waitingForPermission = json.optBoolean("waitingForPermission"),
            cancelRequested = json.optBoolean("cancelRequested"),
            message = if (json.isNull("message")) null else json.getString("message")
        )
    }.getOrNull()

    fun save(state: InstallQueueState) {
        val json = JSONObject()
            .put("remaining", JSONArray().apply { state.remaining.forEach { put(appToJson(it)) } })
            .put("current", state.current?.let(::appToJson) ?: JSONObject.NULL)
            .put("sessionId", state.sessionId ?: JSONObject.NULL)
            .put("completedCount", state.completedCount)
            .put("successCount", state.successCount)
            .put("totalCount", state.totalCount)
            .put("failures", JSONArray(state.failures))
            .put("waitingForPermission", state.waitingForPermission)
            .put("cancelRequested", state.cancelRequested)
            .put("message", state.message ?: JSONObject.NULL)
        preferences.edit().putString("queue", json.toString()).commit()
    }

    private fun appToJson(app: RemoteApp): JSONObject = JSONObject()
        .put("name", app.name)
        .put("packageName", app.packageName)
        .put("versionCode", app.versionCode)
        .put("versionName", app.versionName)
        .put("apkUrl", app.apkUrl)
        .put("apkPath", app.apkPath)
        .put("sha256", app.sha256 ?: JSONObject.NULL)
        .put("sizeBytes", app.sizeBytes ?: JSONObject.NULL)
        .put("signerSha256", app.signerSha256 ?: JSONObject.NULL)

    private fun appFromJson(json: JSONObject): RemoteApp = RemoteApp(
        name = json.getString("name"),
        packageName = json.getString("packageName"),
        versionCode = json.getLong("versionCode"),
        versionName = json.getString("versionName"),
        apkUrl = json.getString("apkUrl"),
        apkPath = json.getString("apkPath"),
        sha256 = if (json.isNull("sha256")) null else json.getString("sha256"),
        sizeBytes = if (json.isNull("sizeBytes")) null else json.getLong("sizeBytes"),
        signerSha256 = if (json.isNull("signerSha256")) null else json.getString("signerSha256")
    )
}
