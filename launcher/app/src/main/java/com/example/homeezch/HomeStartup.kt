package com.example.homeezch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

internal fun startupEligible(enabled: Boolean, boot: Int?, shownBoot: Int, pendingBoot: Int, uptime: Long): Boolean =
    enabled && boot != null && boot != shownBoot && (boot == pendingBoot || uptime in 0..180_000)

/** Uses the existing user-enabled HOME helper; never starts a new background service. */
internal object HomeStartup {
    private fun prefs(context: Context) = context.getSharedPreferences("home_startup_v1", Context.MODE_PRIVATE)
    private fun boot(context: Context): Int? = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply() }
    fun eligible(context: Context): Boolean {
        val p = prefs(context)
        return startupEligible(enabled(context), boot(context), p.getInt("shown_boot", -1), p.getInt("pending_boot", -2), SystemClock.elapsedRealtime())
    }
    fun pending(context: Context) {
        val current = boot(context) ?: return
        if (enabled(context) && prefs(context).getInt("shown_boot", -1) != current)
            prefs(context).edit().putInt("pending_boot", current).apply()
    }
    fun shown(context: Context) {
        val current = boot(context) ?: return
        prefs(context).edit().putInt("shown_boot", current).remove("pending_boot").apply()
        android.util.Log.i("EZCH.Startup", "HOME resumed; boot=$current")
    }
}

class HomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        HomeStartup.pending(context)
        HomeButtonService.requestBootStart()
    }
}
