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
    val canInstall: Boolean
) {
    val roleMismatch: Boolean get() = roleHeld && !effectiveHome
    val homeAvailable: Boolean get() = effectiveHome || (serviceEnabled && serviceConnected)
}

internal fun readHomeSetup(context: Context): HomeSetupState {
    val own = ComponentName(context, HomeButtonService::class.java)
    val enabled = runCatching {
        context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { ComponentName.unflattenFromString(it.id) == own } == true
    }.getOrDefault(false)
    return HomeSetupState(HomeRole.isHome(context), HomeRole.holdsRole(context), enabled,
        enabled && HomeButtonService.connected,
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false))
}
