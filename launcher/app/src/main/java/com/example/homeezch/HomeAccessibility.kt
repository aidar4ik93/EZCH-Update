package com.example.homeezch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

private fun canonicalService(value: String): String {
    val pieces = value.split('/', limit = 2)
    if (pieces.size != 2) return value
    return pieces[0] + "/" + if (pieces[1].startsWith('.')) pieces[0] + pieces[1] else pieces[1]
}
internal fun mergeHomeServices(current: String?, own: String, replaced: Set<String> = emptySet()): String =
    current.orEmpty().split(':').filter { it.isNotBlank() && it != "null" && canonicalService(it) !in replaced.map(::canonicalService) }.toMutableList().apply {
        if (none { canonicalService(it) == canonicalService(own) }) add(own)
    }.joinToString(":")

/** Only used after an explicit assignment button press and a grant made by the device owner. */
internal object HomeAccessibility {
    fun enable(context: Context): Boolean {
        val own = ComponentName(context, HomeButtonService::class.java).flattenToString()
        return try {
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
            val replaced = if (context.packageName == "com.example.homeezch.qa12") setOf("com.example.homeezch.qa/com.example.homeezch.HomeButtonService") else emptySet()
            val merged = mergeHomeServices(current, own, replaced)
            if (merged != current && !Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)) return false
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (error: SecurityException) {
            Log.w("EZCH.Home", "Home helper grant unavailable", error)
            false
        }
    }
}
