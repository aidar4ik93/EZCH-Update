package com.example.homeezch

import android.content.Context

internal object LauncherPrefs {
    private const val PREFS = "ezch_launcher_prefs"
    fun isPremium(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("premium", true)
    fun setPremium(context: Context, premium: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("premium", premium).apply()
    fun loadOrder(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app_order", "")
            .orEmpty().split("|").filter { it.isNotBlank() }
    fun saveOrder(context: Context, order: List<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("app_order", order.joinToString("|")).apply()
    fun loadShelves(context: Context): List<AppShelf> = runCatching {
        val data = org.json.JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("shelves", "[]"))
        (0 until data.length()).map { i ->
            val row = data.getJSONObject(i)
            val keys = row.getJSONArray("apps")
            AppShelf(row.getString("id"), row.getString("title"),
                (0 until keys.length()).map { keys.getString(it) }.distinct())
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    fun saveShelves(context: Context, shelves: List<AppShelf>) {
        val rows = org.json.JSONArray()
        shelves.forEach { row -> rows.put(org.json.JSONObject().put("id", row.id)
            .put("title", row.title).put("apps", org.json.JSONArray(row.appKeys))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("shelves", rows.toString()).apply()
    }
}
