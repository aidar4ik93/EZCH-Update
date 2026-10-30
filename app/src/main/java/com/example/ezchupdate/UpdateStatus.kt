package com.example.ezchupdate

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.example.ezchupdate.data.RemoteApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One application with a newer version in the catalog than the one installed. */
data class PendingUpdate(val packageName: String, val name: String, val versionCode: Long, val sha256: String)

/**
 * The last known list of available updates, shared with EZCH Launcher (desktop banner) through
 * [UpdateStatusProvider]. Installed versions are re-read on every query, so an update installed a
 * minute ago disappears from the banner at once, without waiting for the next catalog check.
 */
object UpdateStatus {
    private const val PREFS = "update_status"
    fun authority(context: Context) = "${context.packageName}.status"
    fun uri(context: Context): Uri = Uri.parse("content://${authority(context)}/updates")

    fun record(context: Context, updates: List<RemoteApp>) {
        val array = JSONArray()
        updates.forEach { app ->
            array.put(JSONObject().put("p", app.packageName).put("n", app.name).put("v", app.versionCode).put("s", app.sha256.orEmpty()))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("updates", array.toString()).putLong("checked_at", System.currentTimeMillis()).apply()
        changed(context)
    }

    fun changed(context: Context) {
        runCatching { context.contentResolver.notifyChange(uri(context), null) }
    }

    fun stored(context: Context): List<PendingUpdate> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("updates", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PendingUpdate(o.getString("p"), o.optString("n", o.getString("p")), o.getLong("v"), o.optString("s"))
        }
    }.getOrDefault(emptyList())

    fun checkedAt(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked_at", 0L)

    /** Pure: keeps entries still newer than what is installed (null = not installed → not an update). */
    fun stillPending(items: List<PendingUpdate>, installedVersion: (String) -> Long?): List<PendingUpdate> =
        items.filter { item -> installedVersion(item.packageName)?.let { it < item.versionCode } == true }

    fun pending(context: Context): List<PendingUpdate> = stillPending(stored(context)) { pkg ->
        runCatching {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrNull()
    }

    /** The verified APK is already in the download cache: only «Установить» remains. */
    fun ready(context: Context, item: PendingUpdate): Boolean =
        item.sha256.matches(Regex("[a-fA-F0-9]{64}")) && File(File(context.cacheDir, "downloads"), "${item.sha256}.apk").isFile
}

/**
 * Read-only status for EZCH Launcher. Only apps signed with the same certificate (the EZCH family)
 * may read it; it exposes app names and counts, never files or catalog URLs.
 */
class UpdateStatusProvider : ContentProvider() {
    override fun onCreate() = true

    private fun trustedCaller(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        return uid == Process.myUid() || context.packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        if (!trustedCaller(context)) throw SecurityException("EZCH status is available to EZCH apps only")
        val identity = Binder.clearCallingIdentity()
        try {
            val cursor = MatrixCursor(arrayOf("package", "name", "version_code", "ready", "checked_at"))
            val checked = UpdateStatus.checkedAt(context)
            UpdateStatus.pending(context).forEach { cursor.addRow(arrayOf(it.packageName, it.name, it.versionCode, if (UpdateStatus.ready(context, it)) 1 else 0, checked)) }
            cursor.setNotificationUri(context.contentResolver, UpdateStatus.uri(context))
            return cursor
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.ezch.update"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
