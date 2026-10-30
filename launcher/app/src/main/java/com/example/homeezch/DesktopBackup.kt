package com.example.homeezch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object DesktopBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val stores = listOf("ezch_launcher_prefs", "wallpaper", "source_settings", "launcher_preferences")
    fun localDirectory(context: Context) = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
    fun localCopies(context: Context): List<java.io.File> = localDirectory(context).listFiles()
        ?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun saveLocal(context: Context): java.io.File {
        val directory = localDirectory(context)
        check(directory.isDirectory || directory.mkdirs())
        val file = java.io.File(directory, "EZCH-Desktop-${System.currentTimeMillis()}.json")
        val data = export(context).toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTES)
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(data); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        return file
    }
    fun documentPickerAvailable(context: Context, create: Boolean): Boolean {
        val action = if (create) android.content.Intent.ACTION_CREATE_DOCUMENT else android.content.Intent.ACTION_OPEN_DOCUMENT
        val intent = android.content.Intent(action).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/json")
        return context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any {
            val info = it.activityInfo
            info != null && info.enabled && info.exported && info.applicationInfo.enabled && !info.name.contains("Stub", ignoreCase = true)
        }
    }
    fun export(context: Context): String {
        val root = JSONObject().put("format", "ezch-desktop").put("version", 1)
        val data = JSONObject()
        stores.forEach { store ->
            val values = JSONObject()
            context.getSharedPreferences(store, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value is String || value is Boolean || value is Int || value is Long || value is Float) {
                    val type = when (value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; else -> "float" }
                    values.put(key, JSONObject().put("type", type).put("value", value))
                }
            }
            data.put(store, values)
        }
        return root.put("stores", data).toString(2)
    }
    /** Validate the whole document before writes; retain and roll back the original on a failed commit. */
    fun restore(context: Context, text: String): Boolean {
        val parsed = validate(text)
        val before = export(context)
        return if (runCatching { write(context, parsed) }.getOrDefault(false)) true else {
            check(write(context, validate(before))) { "Не удалось восстановить исходные настройки" }
            false
        }
    }
    internal fun validate(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Резервная копия слишком велика" }
        val root = JSONObject(text)
        require(root.getString("format") == "ezch-desktop" && root.getInt("version") == 1) { "Неподдерживаемый формат" }
        val data = root.getJSONObject("stores")
        val included = data.keys().asSequence().toSet()
        require(included.containsAll(stores.take(3)) && included.all { it in stores })
        included.forEach { require(data.optJSONObject(it) != null) }
        stores.forEach { store ->
            val values = data.optJSONObject(store) ?: return@forEach
            require(values.length() <= 2048)
            values.keys().forEach { key ->
                require(key.length in 1..160)
                val entry = values.getJSONObject(key)
                val value = entry.get("value")
                val expected = when {
                    store == "wallpaper" && key in listOf("mode", "image", "video") -> "string"
                    store == "wallpaper" && key == "moving" -> "boolean"
                    key == "profiles_v1" || key == "active_profile" || key == "workspace_v1" || key.startsWith("workspace_profile_") -> "string"
                    store == "ezch_launcher_prefs" && key in listOf("app_order", "shelves", "weather_point") -> "string"
                    store == "ezch_launcher_prefs" && key in listOf("premium", "weather_auto", "weather_network_location", "weather_prompt_v09") -> "boolean"
                    store == "ezch_launcher_prefs" && key == "weather_location_time" -> "long"
                    store == "launcher_preferences" && key in listOf("app_order", "home_rows", "hidden_packages") -> "string"
                    store == "launcher_preferences" && key == "lite" -> "boolean"
                    key.startsWith("pin_") && key.endsWith("_attempts") -> "int"
                    key.startsWith("pin_") && key.endsWith("_until") -> "long"
                    store == "source_settings" && key == "manual" -> "string"
                    store == "source_settings" && key == "tuner_autostart" -> "boolean"
                    store == "source_settings" && key == "tuner_started_boot" -> "int"
                    else -> null
                }
                require(expected == null || entry.getString("type") == expected)
                when (entry.getString("type")) {
                    "string" -> require(value is String)
                    "boolean" -> require(value is Boolean)
                    "int" -> require(value is Number && value.toDouble() == value.toInt().toDouble())
                    "long" -> require(value is Number && value.toDouble() == value.toLong().toDouble())
                    "float" -> require(value is Number && value.toDouble().isFinite())
                    else -> error("Неподдерживаемый тип")
                }
                if (key == "workspace_v1" || key.startsWith("workspace_profile_")) validateWorkspace(value as String)
                if (key == "profiles_v1") {
                    val profiles = JSONArray(value as String)
                    require(profiles.length() in 1..8)
                    val ids = (0 until profiles.length()).map { i ->
                        val profile = profiles.getJSONObject(i)
                        require(profile.getString("name").length in 1..40)
                        require(profile.optString("credential").let { it.isEmpty() || it.matches(Regex("120000:[a-f0-9]{32}:[a-f0-9]{64}")) })
                        profile.getString("id").also { require(it.matches(Regex("[A-Za-z0-9-]{1,64}"))) }
                    }
                    require("owner" in ids && ids.distinct().size == ids.size)
                }
            }
        }
        return data
    }
    private fun validateWorkspace(text: String) {
        val value = JSONObject(text)
        val rows = value.getJSONArray("rows")
        require(rows.length() in 1..40)
        val ids = mutableSetOf<String>()
        val allocated = mutableSetOf<String>()
        var desktopRows = 0
        repeat(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            if (!row.optBoolean("folder", false)) desktopRows++
            require(row.getString("id").length in 1..80 && ids.add(row.getString("id")))
            require(row.getString("title").length in 1..80)
            val keys = row.getJSONArray("apps")
            require(keys.length() <= 1024)
            repeat(keys.length()) { require(keys.getString(it).length in 1..255 && allocated.add(keys.getString(it))) }
        }
        require(desktopRows > 0)
        listOf("hiddenApps", "hiddenSources", "sourceOrder", "removedApps").forEach { name ->
            val array = value.optJSONArray(name) ?: JSONArray()
            require(array.length() <= 1024)
            repeat(array.length()) { require(array.getString(it).length in 1..255) }
        }
    }
    private fun write(context: Context, data: JSONObject): Boolean {
        stores.forEach { store ->
            val values = data.optJSONObject(store) ?: return@forEach
            val editor = context.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                val entry = values.getJSONObject(key)
                when (entry.getString("type")) {
                    "string" -> editor.putString(key, entry.getString("value"))
                    "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "int" -> editor.putInt(key, entry.getInt("value"))
                    "long" -> editor.putLong(key, entry.getLong("value"))
                    "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                }
            }
            if (!editor.commit()) return false
        }
        return true
    }
}
