package com.example.homeezch.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class InstallSnapshot(
    val packageName: String,
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val confirmationIntent: Intent? = null
)

object InstallEvents {
    val snapshot = MutableStateFlow<InstallSnapshot?>(null)

    fun record(context: Context, result: InstallSnapshot) {
        val json = JSONObject().apply {
            put("package", result.packageName)
            put("session", result.sessionId)
            put("status", result.status)
            put("message", result.message)
            put("confirmation", result.confirmationIntent?.toUri(Intent.URI_INTENT_SCHEME))
        }
        context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit()
            .putString("result", json.toString()).commit()
        snapshot.value = result
    }

    fun restore(context: Context) {
        val json = context.getSharedPreferences("install_result", Context.MODE_PRIVATE)
            .getString("result", null) ?: return
        snapshot.value = runCatching {
            val value = JSONObject(json)
            InstallSnapshot(
                value.getString("package"), value.getInt("session"), value.getInt("status"),
                if (value.isNull("message")) null else value.getString("message"),
                if (value.isNull("confirmation")) null else
                    Intent.parseUri(value.getString("confirmation"), Intent.URI_INTENT_SCHEME)
            )
        }.getOrNull()
    }

    fun consumeConfirmation(sessionId: Int) {
        val current = snapshot.value ?: return
        if (current.sessionId == sessionId) snapshot.value = current.copy(confirmationIntent = null)
    }

    fun consume(context: Context, sessionId: Int) {
        if (snapshot.value?.sessionId != sessionId) return
        context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit().clear().commit()
        snapshot.value = null
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_CATALOG_PACKAGE) ?: return
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return
        var status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        var message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
        } else null
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirmation == null) {
            status = PackageInstaller.STATUS_FAILURE
            message = "Android не предоставил окно подтверждения установки"
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        // The foreground Activity opens confirmation; receivers never launch it in background.
        InstallEvents.record(context, InstallSnapshot(packageName, sessionId, status, message, confirmation))
    }

    companion object {
        const val EXTRA_CATALOG_PACKAGE = "catalog_package"
        const val EXTRA_SESSION_ID = "catalog_session"
    }
}
