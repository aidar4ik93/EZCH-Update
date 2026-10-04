package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class LaunchApp(val name: String, val component: ComponentName) {
    val packageName: String get() = component.packageName
}

internal data class HomeRow(
    val id: String,
    val title: String,
    val packages: List<String> = emptyList(),
    val channelId: Long? = null
)

/** Preferences store package identities, never indexes into a changing installed-app list. */
internal class LauncherPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)

    var lite: Boolean
        get() = runCatching { preferences.getBoolean("lite", false) }.getOrDefault(false)
        set(value) { preferences.edit().putBoolean("lite", value).apply() }

    var appOrder: List<String>
        get() = LauncherJson.decodePackages(read("app_order"))
        set(value) { write("app_order", LauncherJson.encodePackages(value)) }

    var rows: List<HomeRow>
        get() = LauncherJson.decodeRows(read("home_rows"))
        set(value) { write("home_rows", LauncherJson.encodeRows(value)) }

    var recentPackages: List<String>
        get() = LauncherJson.decodePackages(read("recent_packages")).take(12)
        set(value) { write("recent_packages", LauncherJson.encodePackages(value.take(12))) }

    var hiddenPackages: List<String>
        get() = LauncherJson.decodePackages(read("hidden_packages"))
        set(value) { write("hidden_packages", LauncherJson.encodePackages(value)) }

    @Synchronized
    fun recordLaunch(packageName: String) {
        recentPackages = LauncherPolicy.recordLaunch(recentPackages, packageName)
    }

    private fun read(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

internal object LauncherPolicy {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun validPackage(value: String): Boolean =
        value.length in 3..255 && packagePattern.matches(value)

    fun normalizePackages(values: Iterable<String>): List<String> =
        values.filter(::validPackage).distinct().take(512)

    fun normalizeRows(rows: Iterable<HomeRow>): List<HomeRow> = rows
        .filter { it.id.isNotBlank() && it.id.length <= 100 && it.title.isNotBlank() }
        .distinctBy { it.id }
        .take(20)
        .map { it.copy(title = it.title.trim().take(80),
            packages = normalizePackages(it.packages).take(128),
            channelId = it.channelId?.takeIf { id -> id >= 0 }) }

    fun recordLaunch(recent: List<String>, packageName: String): List<String> =
        if (!validPackage(packageName)) normalizePackages(recent).take(12)
        else normalizePackages(listOf(packageName) + recent).take(12)

    /** Keep missing packages in stored order so reinstalling an app restores its position. */
    fun orderPackages(installed: List<String>, savedOrder: List<String>): List<String> {
        val unique = installed.distinct()
        val installedSet = unique.toSet()
        val ordered = savedOrder.distinct().filter { it in installedSet }
        return ordered + unique.filterNot { it in ordered }
    }
}

internal object LauncherJson {
    private const val MAX_JSON_LENGTH = 1024 * 1024

    fun encodePackages(packages: List<String>): String =
        JSONArray(LauncherPolicy.normalizePackages(packages)).toString()

    fun decodePackages(raw: String?): List<String> {
        if (raw == null || raw.length > MAX_JSON_LENGTH) return emptyList()
        return runCatching { packagesFrom(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun encodeRows(rows: List<HomeRow>): String = JSONArray().apply {
        LauncherPolicy.normalizeRows(rows).forEach { row ->
            put(JSONObject().apply {
                put("id", row.id)
                put("title", row.title)
                put("packages", JSONArray(row.packages))
                row.channelId?.let { put("channelId", it) }
            })
        }
    }.toString()

    fun decodeRows(raw: String?): List<HomeRow> {
        if (raw == null || raw.length > MAX_JSON_LENGTH) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            LauncherPolicy.normalizeRows((0 until array.length().coerceAtMost(100)).mapNotNull { index ->
                val value = array.opt(index) as? JSONObject ?: return@mapNotNull null
                val id = value.opt("id") as? String ?: return@mapNotNull null
                val title = value.opt("title") as? String ?: return@mapNotNull null
                val channelId = (value.opt("channelId") as? Number)?.toLong()
                HomeRow(id, title, value.optJSONArray("packages")?.let(::packagesFrom).orEmpty(), channelId)
            })
        }.getOrDefault(emptyList())
    }

    private fun packagesFrom(array: JSONArray): List<String> = LauncherPolicy.normalizePackages(
        (0 until array.length().coerceAtMost(1024)).mapNotNull { array.opt(it) as? String }
    )
}

@Suppress("DEPRECATION")
internal fun findTvApps(pm: PackageManager, ownPackage: String): List<LaunchApp> {
    val result = linkedMapOf<String, LaunchApp>()
    listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        val matches = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())
        matches.forEach match@{ info ->
            val activity = info.activityInfo ?: return@match
            if (activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                activity.packageName != ownPackage && activity.packageName !in result) {
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrDefault(activity.packageName)
                result[activity.packageName] = LaunchApp(label,
                    ComponentName(activity.packageName, activity.name))
            }
        }
    }
    val priority = listOf("serialtrend", "лайт hd tv", "panda vision", "lift")
    return result.values.sortedWith(compareBy<LaunchApp> {
        val rank = priority.indexOf(it.name.lowercase(Locale.ROOT))
        if (rank < 0) Int.MAX_VALUE else rank
    }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}

internal fun orderedApps(apps: List<LaunchApp>, order: List<String>): List<LaunchApp> {
    val byPackage = apps.associateBy { it.packageName }
    return LauncherPolicy.orderPackages(apps.map { it.packageName }, order).mapNotNull(byPackage::get)
}
