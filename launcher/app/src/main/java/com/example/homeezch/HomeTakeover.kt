package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Claude r58 (owner, 04.11: «нажимаешь домой — открывается Google TV, потом мой лаунчер… надо заложить в лаунчере по
 * умолчанию»). Google TV gives its launcher HOME priority 2 and the setupwraith fallback priority 1; an app's HOME is 0,
 * so the HOME role alone never wins and the old redirect shows Google TV first. With the box's own ADB (see [LocalAdb])
 * EZCH disables just those stock HOME screens (pm disable-user — reversible, data kept) and becomes the only HOME.
 * On by default; runs once the owner allows debugging in Android's dialog. «Вернуть Google TV» enables them back.
 */
internal object HomeTakeover {
    private const val PREF = "home_takeover"
    private const val DISABLED = "home_takeover_disabled"
    /** Stock TV home screens that outrank EZCH. Nothing else is ever disabled. */
    val STOCK = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
        "com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.android.tv.launcher")
    @Volatile private var attempted = false

    private fun prefs(context: Context) = context.getSharedPreferences("ezch_home_takeover", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREF, true)

    /** Pure (JVM-tested): stock HOME screens ranked above EZCH must go; EZCH itself and other apps never. */
    fun toDisable(candidates: List<Pair<String, Int>>, own: String): List<String> {
        val ownPriority = candidates.firstOrNull { it.first == own }?.second ?: return emptyList()
        return candidates.filter { (pkg, priority) -> pkg != own && pkg in STOCK && priority >= ownPriority }.map { it.first }.distinct()
    }

    private fun candidates(context: Context): List<Pair<String, Int>> = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName to it.priority }
    }.getOrDefault(emptyList())

    /** Called from MainActivity.onResume (background thread). Asks for nothing when EZCH already is HOME or ADB is off. */
    fun maybeApply(context: Context, notify: (String) -> Unit) {
        val app = context.applicationContext
        if (attempted || !enabled(app) || HomeRole.isHome(app)) return
        attempted = true
        if (toDisable(candidates(app), app.packageName).isEmpty() || !LocalAdb.reachable()) return
        notify("EZCH становится главным экраном: на экране нажмите «Разрешить» (отладка по сети)")
        val r = apply(app)
        if (r != null) notify(r)
    }

    /** Returns a message for the owner, or null when ADB did not answer (nothing changed). */
    fun apply(context: Context): String? {
        val targets = toDisable(candidates(context), context.packageName)
        val own = context.packageName
        val home = "$own/${MainActivity::class.java.name}"
        val cmd = (targets.map { "pm disable-user --user 0 $it" } +
            listOf("cmd role add-role-holder --user 0 android.app.role.HOME $own", "cmd package set-home-activity --user 0 $home")).joinToString("; ")
        LocalAdb.shell(context, cmd) ?: return null
        prefs(context).edit().putStringSet(DISABLED, (prefs(context).getStringSet(DISABLED, emptySet()).orEmpty() + targets).toSet()).apply()
        EzchDiag.log("HOME", "главный экран EZCH: отключены ${targets.joinToString()}")
        return if (HomeRole.isHome(context)) "Готово: кнопка «Домой» сразу открывает EZCH" else "Не удалось сделать EZCH главным экраном"
    }

    /** «Вернуть Google TV»: enables what EZCH disabled and turns the default off. */
    fun restore(context: Context): String {
        val list = prefs(context).getStringSet(DISABLED, emptySet()).orEmpty().ifEmpty { STOCK.toSet() }
        val out = LocalAdb.shell(context, list.joinToString("; ") { "pm enable --user 0 $it" })
            ?: return "Нет доступа к отладке по сети — Google TV не включён"
        prefs(context).edit().putBoolean(PREF, false).remove(DISABLED).apply()
        EzchDiag.log("HOME", "Google TV возвращён: ${out.trim().take(200)}")
        return "Google TV включён обратно"
    }
}
