package com.example.homeezch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object WorkspacePrefs {
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    fun load(context: Context): Workspace {
        val prefs = context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("workspace_v1", null)
        if (json != null) runCatching {
            val obj = JSONObject(json); val rows = obj.getJSONArray("rows")
            val parsed = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                AppShelf(row.getString("id"), row.getString("title"), row.getJSONArray("apps").strings())
            }
            require(parsed.isNotEmpty())
            return Workspace(parsed, obj.getJSONArray("hiddenApps").strings(),
                obj.getJSONArray("sourceOrder").strings(), obj.getJSONArray("hiddenSources").strings())
        }
        // Migrate component IDs to package IDs; activity renames cannot reset the order.
        val legacy = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
        if (legacy.contains("app_order") || legacy.contains("home_rows") || legacy.contains("hidden_packages")) {
            return decodeLegacyWorkspace(legacy.getString("app_order", null), legacy.getString("home_rows", null), legacy.getString("hidden_packages", null))
        }
        fun normal(key: String) = key.substringBefore('/')
        val shelves = LauncherPrefs.loadShelves(context).map { it.copy(appKeys = it.appKeys.map(::normal)) }
        val allocated = shelves.flatMap { it.appKeys }.toSet()
        return Workspace(listOf(AppShelf("main", "Приложения",
            LauncherPrefs.loadOrder(context).map(::normal).filterNot { it in allocated })) + shelves)
    }
    /** Called on IO; one atomic preference commit for all rows, hidden flags and sources. */
    fun save(context: Context, workspace: Workspace): Boolean {
        val rows = JSONArray()
        workspace.rows.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("apps", JSONArray(it.appKeys))) }
        val data = JSONObject().put("rows", rows).put("hiddenApps", JSONArray(workspace.hiddenApps))
            .put("sourceOrder", JSONArray(workspace.sourceOrder))
            .put("hiddenSources", JSONArray(workspace.hiddenSources))
        return context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE)
            .edit().putString("workspace_v1", data.toString()).commit()
    }
}

internal fun decodeLegacyWorkspace(order: String?, rows: String?, hidden: String?): Workspace {
    fun packages(raw: String?): List<String> = runCatching {
        require(raw != null && raw.length <= 1024 * 1024)
        val array = JSONArray(raw)
        (0 until array.length().coerceAtMost(512)).mapNotNull { array.opt(it) as? String }
            .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) }.distinct()
    }.getOrDefault(emptyList())
    val shelves = runCatching {
        require(rows != null && rows.length <= 1024 * 1024)
        val array = JSONArray(rows)
        (0 until array.length().coerceAtMost(20)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val id = value.optString("id"); val title = value.optString("title")
            if (id.isBlank() || title.isBlank()) null else AppShelf("legacy-$id", title.take(80), packages(value.optJSONArray("packages")?.toString()))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    val allocated = shelves.flatMap { it.appKeys }.toSet()
    return Workspace(listOf(AppShelf("main", "Приложения", packages(order).filterNot { it in allocated })) + shelves, packages(hidden))
}
