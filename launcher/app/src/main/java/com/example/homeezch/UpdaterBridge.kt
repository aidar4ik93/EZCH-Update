package com.example.homeezch

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast

internal fun updaterPackages(ownPackage: String): List<String> =
    if (ownPackage.endsWith(".qa")) listOf("com.example.ezchupdate.qa", "com.example.ezchupdate")
    else listOf("com.example.ezchupdate")

internal fun openUpdater(context: Context, selectedPackage: String? = null): Boolean {
    val companion = Intent("com.example.ezchupdate.OPEN_CATALOG")
        .setComponent(ComponentName("com.example.ezchupdate.tvtest", "com.example.ezchupdate.MainActivity"))
    selectedPackage?.let { companion.putExtra("selected_package", it) }
    try {
        context.startActivity(companion)
        return true
    } catch (_: android.content.ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
    val embedded = Intent(context, UpdaterActivity::class.java)
    selectedPackage?.let { embedded.putExtra("selected_package", it) }
    return runCatching { context.startActivity(embedded); true }.getOrDefault(false)
}

/** Compatibility route for older companion installations; the desktop uses the embedded updater. */
internal fun openLegacyUpdater(context: Context, selectedPackage: String? = null): Boolean {
    for (packageName in updaterPackages(context.packageName)) {
        val intent = Intent("com.example.ezchupdate.OPEN_CATALOG")
            .setComponent(ComponentName(packageName, "com.example.ezchupdate.MainActivity"))
        selectedPackage?.let { intent.putExtra("selected_package", it) }
        try {
            context.startActivity(intent)
            return true
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, "Установите EZCH Update, чтобы загружать приложения", Toast.LENGTH_LONG).show()
    return false
}
