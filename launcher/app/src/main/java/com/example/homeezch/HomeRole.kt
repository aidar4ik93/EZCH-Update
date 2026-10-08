package com.example.homeezch

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

internal object HomeRole {
    fun isHome(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true)
                return@runCatching roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true)
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            .firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
    }

    fun request(context: Context): Boolean {
        HomeAccessibility.enable(context)
        if (isHome(context)) {
            android.widget.Toast.makeText(context, "EZCH уже главный экран", android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true)
                candidates += roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        candidates += Intent(Settings.ACTION_HOME_SETTINGS)
        candidates += Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        candidates += Intent(Settings.ACTION_SETTINGS)
        return SettingsRouter.open(context, candidates)
    }
}
