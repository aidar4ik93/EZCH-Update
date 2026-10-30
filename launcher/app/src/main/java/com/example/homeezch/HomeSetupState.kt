package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager

internal data class HomeSetupState(
    val effectiveHome: Boolean,
    val roleHeld: Boolean,
    val serviceEnabled: Boolean,
    val serviceConnected: Boolean,
    val canInstall: Boolean,
    val resolvedHomeName: String? = null,
    val otherHomeServices: List<String> = emptyList()
) {
    val roleMismatch: Boolean get() = roleHeld && !effectiveHome
    val homeAvailable: Boolean get() = effectiveHome || (serviceEnabled && serviceConnected)
}

internal fun readHomeSetup(context: Context): HomeSetupState {
    val own = ComponentName(context, HomeButtonService::class.java)
    val services = runCatching {
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
    }.getOrDefault(emptyList())
    val enabled = services.any { ComponentName.unflattenFromString(it.id) == own }
    val pm = context.packageManager
    val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    val currentHome = runCatching {
        pm.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.applicationInfo?.loadLabel(pm)?.toString()
    }.getOrNull()
    val otherHomeServices = services.mapNotNull { service ->
        val component = ComponentName.unflattenFromString(service.id) ?: return@mapNotNull null
        if (component.packageName == context.packageName) return@mapNotNull null
        runCatching {
            if (pm.queryIntentActivities(android.content.Intent(home).setPackage(component.packageName),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) null
            else service.resolveInfo.serviceInfo.applicationInfo.loadLabel(pm).toString()
        }.getOrNull()
    }.distinct()
    return HomeSetupState(HomeRole.isHome(context), HomeRole.holdsRole(context), enabled,
        enabled && HomeButtonService.connected,
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false),
        currentHome, otherHomeServices)
}
