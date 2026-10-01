package com.example.ezchupdate.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow

data class InstallResult(
    val packageName: String,
    val status: Int,
    val message: String?
)

object InstallEvents {
    val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_CATALOG_PACKAGE) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmationIntent = intent.confirmationIntent()
            if (confirmationIntent != null) {
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmationIntent)
            } else {
                InstallEvents.results.tryEmit(
                    InstallResult(packageName, PackageInstaller.STATUS_FAILURE, "Не получено подтверждение установки")
                )
            }
            return
        }

        InstallEvents.results.tryEmit(
            InstallResult(
                packageName = packageName,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.confirmationIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        const val EXTRA_CATALOG_PACKAGE = "catalog_package"
    }
}
