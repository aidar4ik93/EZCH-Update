package com.example.ezchupdate

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.ezchupdate.data.CatalogRepository
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallQueueStore
import java.util.concurrent.TimeUnit

class UpdateSettings(private val context: Context) {
    val prefs = context.getSharedPreferences("premium_settings", Context.MODE_PRIVATE)
    var appearance: String
        get() = prefs.getString("appearance", "Auto") ?: "Auto"
        set(value) { prefs.edit().putString("appearance", value).apply() }
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(value) { prefs.edit().putBoolean("auto_check", value).apply(); schedule() }
    var autoDownload: Boolean
        get() = prefs.getBoolean("auto_download", false)
        set(value) { prefs.edit().putBoolean("auto_download", value).apply(); schedule() }
    var intervalHours: Long
        get() = prefs.getLong("interval_hours", 6)
        set(value) { prefs.edit().putLong("interval_hours", value).apply(); schedule() }
    fun lite(): Boolean = appearance == "Lite" || (appearance == "Auto" &&
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { manager ->
            val memory = ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory)
            manager.isLowRamDevice || memory.totalMem <= 2L * 1024 * 1024 * 1024
        })
    fun schedule() {
        val manager = WorkManager.getInstance(context)
        if (!autoCheck) { manager.cancelUniqueWork("ezch-catalog-check"); return }
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build()
        manager.enqueueUniquePeriodicWork("ezch-catalog-check", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Android may defer this work. Installation always stays in the foreground user queue. */
class CatalogSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val settings = UpdateSettings(applicationContext)
        if (!settings.autoCheck) return@withContext Result.success()
        val queue = InstallQueueStore(applicationContext).load()
        if (queue?.current != null || queue?.remaining?.isNotEmpty() == true || queue?.waitingForPermission == true)
            return@withContext Result.retry()
        val repository = CatalogRepository(applicationContext)
        val apps = repository.load()
        if (repository.offline) return@withContext Result.retry()
        for (app in apps) {
            if (isStopped) return@withContext Result.retry()
            val installed = repository.installedApp(applicationContext.packageManager, app.packageName)
            if (installed != null && installed.versionCode >= app.versionCode) continue
            if (settings.autoDownload && installed != null) {
                try { AppInstaller().stage(applicationContext, app) { _, _ -> check(!isStopped) } }
                catch (_: Exception) { settings.prefs.edit().putString("background_status", "Не удалось подготовить ${app.name}; повторите загрузку в каталоге").apply() }
            }
            val key = "notified:${app.packageName}:${app.versionCode}:${app.sha256}"
            if (settings.prefs.getBoolean(key, false)) continue
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) continue
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("catalog", "Новые приложения и обновления", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(applicationContext, MainActivity::class.java).putExtra("selected_package", app.packageName)
            val pending = PendingIntent.getActivity(applicationContext, app.packageName.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(app.packageName.hashCode(), NotificationCompat.Builder(applicationContext, "catalog")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle(if (installed == null) "Новое приложение" else "Доступно обновление")
                .setContentText("${app.name} • ${app.versionName}").setContentIntent(pending).setAutoCancel(true).build())
            settings.prefs.edit().putBoolean(key, true).apply()
        }
        Result.success()
    }
}
