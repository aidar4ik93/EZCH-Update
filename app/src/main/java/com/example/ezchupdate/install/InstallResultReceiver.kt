package com.example.ezchupdate.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

data class InstallResult(
    val sessionId: Int,
    val packageName: String,
    val status: Int,
    val message: String?,
    val versionCode: Long? = null,
    val startedAt: Long = 0
) {
    val terminal: Boolean get() = status != PackageInstaller.STATUS_PENDING_USER_ACTION
}

/** Explicit, session-specific PendingIntent callbacks also run after this app's process was killed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ownId = intent.getIntExtra(EXTRA_OWN_SESSION_ID, -1)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, ownId)
        if (sessionId < 0 || (ownId >= 0 && sessionId != ownId)) return
        val active = InstallEvents.activeSession(context, sessionId) ?: return
        val catalogPackage = intent.getStringExtra(EXTRA_CATALOG_PACKAGE) ?: return
        if (catalogPackage != active.packageName) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.confirmationIntent()
            if (confirmation == null) {
                AppInstaller().confirmationLaunchFailed(context, sessionId, "Не найден системный экран подтверждения")
                return
            }
            // Launch only from the foreground Activity. Broadcast/background launches are blocked
            // on modern Android and can be silently ignored, leaving a queue stuck forever.
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            InstallEvents.pending(context, active, confirmation)
            return
        }
        InstallEvents.record(context, InstallResult(sessionId, active.packageName, status,
            InstallMessages.forStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))))
    }

    @Suppress("DEPRECATION")
    private fun Intent.confirmationIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        const val EXTRA_CATALOG_PACKAGE = "catalog_package"
        const val EXTRA_OWN_SESSION_ID = "own_session_id"
    }
}

internal object InstallMessages {
    fun forStatus(status: Int, details: String?): String? {
        if (status == PackageInstaller.STATUS_SUCCESS) return null
        val message = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена пользователем или Android"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android заблокировал установку. Проверьте разрешение установки и сообщение Play Защиты"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "APK конфликтует с установленной версией или её подписью. Нужен APK того же разработчика"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с версией Android или процессором устройства"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android отклонил повреждённый или неподписанный APK. Повторите загрузку"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно памяти для установки. Освободите место на устройстве"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Android не завершил установку вовремя. Повторите установку"
            else -> "Android не смог установить приложение"
        }
        return if (details.isNullOrBlank()) message else "$message. Подробности: ${details.take(400)}"
    }
}
