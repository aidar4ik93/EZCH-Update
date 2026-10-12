package com.example.homeezch

import android.content.Context
import android.provider.Settings

/** Manual shortcuts are opt-in; they never assert a physical port or cable. */
internal object SourceSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
    fun manual(context: Context): List<TvSourceEntry> = runCatching {
        val rows = org.json.JSONArray(prefs(context).getString("manual", "[]"))
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            TvSourceEntry(row.getString("id"), row.getString("label"), "Ручной ярлык",
                TvSourceKind.valueOf(row.getString("kind")), detail = "Ручной · статус неизвестен")
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
    fun add(context: Context, kind: TvSourceKind) {
        val entries = manual(context)
        val number = (entries.count { it.kind == kind } + 1)
        val label = if (kind == TvSourceKind.TV) "ТВ" else "${kind.name} $number"
        val row = org.json.JSONObject().put("id", "manual-${java.util.UUID.randomUUID()}")
            .put("label", "$label · вручную").put("kind", kind.name)
        val rows = org.json.JSONArray()
        entries.forEach { entry -> rows.put(org.json.JSONObject().put("id", entry.id).put("label", entry.label).put("kind", entry.kind.name)) }
        rows.put(row)
        prefs(context).edit().putString("manual", rows.toString()).apply()
    }
    fun clear(context: Context) { prefs(context).edit().remove("manual").apply() }
    fun tunerAutoStart(context: Context) = prefs(context).getBoolean("tuner_autostart", false)
    fun setTunerAutoStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("tuner_autostart", enabled).apply()
    }
    fun consumeTunerStart(context: Context): Boolean {
        if (!tunerAutoStart(context)) return false
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
            ?: return false // Missing boot identity must not create a restart loop.
        val p = prefs(context)
        if (p.getInt("tuner_started_boot", -1) == boot) return false
        return p.edit().putInt("tuner_started_boot", boot).commit()
    }
}
