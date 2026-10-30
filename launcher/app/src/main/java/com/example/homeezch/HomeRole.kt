package com.example.homeezch

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * An OEM can grant ROLE_HOME to EZCH and still route HOME to its own TV launcher.
 * Therefore the resolved HOME intent takes precedence over the role holder.
 */
@Suppress("UNUSED_PARAMETER")
internal fun isEffectiveHome(resolvedHomePackage: String?, ownPackage: String, roleHeld: Boolean): Boolean =
    resolvedHomePackage == ownPackage

internal object HomeRole {
    fun holdsRole(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 29) return@runCatching false
        val roles = context.getSystemService(RoleManager::class.java)
        roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    fun isHome(context: Context): Boolean = runCatching {
        // Real resolver outcome, not just RoleManager. Some Google TV firmware
        // reports our package as role holder but still resolves to tvlauncher.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolvedPackage = context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        isEffectiveHome(resolvedPackage, context.packageName, holdsRole(context))
    }.getOrDefault(false)

    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return settingsIntent(context)
    }

    fun settingsIntent(context: Context): Intent? =
        listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            .firstNotNullOfOrNull { SettingsRouter.supportedIntent(context, it) }

    fun request(context: Context): Boolean = SettingsRouter.open(context,
        listOf(Intent(context, HomeSetupActivity::class.java)))
}
