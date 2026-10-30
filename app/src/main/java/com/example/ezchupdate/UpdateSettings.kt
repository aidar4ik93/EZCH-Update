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
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallQueueStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async

class UpdateSettings(private val context: Context) {
    val prefs = context.getSharedPreferences("premium_settings", Context.MODE_PRIVATE)
    var appearance: String
        get() = prefs.getString("appearance", "Auto") ?: "Auto"
        set(value) { prefs.edit().putString("appearance", value).apply() }
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(value) { prefs.edit().putBoolean("auto_check", value).apply(); schedule() }
    var autoDownload: Boolean
        // Owner's choice (24.10.2026): updates download by themselves; only «Установить» is left to press.
        get() = prefs.getBoolean("auto_download", true)
        set(value) { prefs.edit().putBoolean("auto_download", value).apply() }
    var intervalHours: Long
        get() = prefs.getLong("interval_hours", 1).coerceIn(1, 24)
        set(value) { prefs.edit().putLong("interval_hours", value.coerceIn(1, 24)).apply(); schedule() }
    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) {
            prefs.edit().putBoolean("notifications", value).apply()
            if (!value) UpdateNotifier(context).clear() else checkNow()
        }
    fun lite(): Boolean = appearance == "Lite" || (appearance == "Auto" &&
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { manager ->
            val memory = ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory)
            manager.isLowRamDevice || memory.totalMem <= 2L * 1024 * 1024 * 1024
        })
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule() {
        val manager = WorkManager.getInstance(context)
        if (!autoCheck) {
            manager.cancelUniqueWork("ezch-catalog-check")
            manager.cancelUniqueWork("ezch-catalog-now")
            return
        }
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("ezch-catalog-check", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun checkNow() {
        if (autoCheck) WorkManager.getInstance(context).enqueueUniqueWork("ezch-catalog-now",
            ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CatalogSyncWorker>().setConstraints(constraints()).build())
    }
}

/** One real update summary, never a notification for an app that is not installed. */
internal class UpdateNotifier(private val context: Context) {
    private val settings = UpdateSettings(context)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    companion object {
        const val CHANNEL = "application_updates"
        const val ID = 2401
        const val OPEN_UPDATES = "open_updates"
        /** Opens EZCH Update and installs every available update one after another. */
        const val INSTALL_ALL = "install_all"
    }
    fun clear() {
        manager.cancel(ID)
        settings.prefs.edit().remove("notification_fingerprint").apply()
    }
    fun publish(updates: List<RemoteApp>, readyCount: Int = -1) {
        // Remove notifications from the old per-app implementation during migration.
        manager.activeNotifications.filter { it.notification.channelId == "catalog" }.forEach { manager.cancel(it.id) }
        if (updates.isEmpty() || !settings.notifications) { clear(); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления приложений",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Напоминает обновить установленные приложения"
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val updatesFingerprint = updates.sortedBy { it.packageName }.joinToString("|") { "${it.packageName}:${it.versionCode}:${it.sha256}" }
        val ready = if (readyCount >= 0) readyCount else updates.count { app ->
            app.sha256 != null && java.io.File(java.io.File(context.cacheDir, "downloads"), "${app.sha256}.apk").isFile }
        val allReady = ready >= updates.size
        val fingerprint = "$updatesFingerprint#${if (allReady) "ready" else "new"}"
        if (settings.prefs.getString("notification_fingerprint", null) == fingerprint) return
        val firstTimeForTheseUpdates = settings.prefs.getString("notification_fingerprint", null)?.substringBefore('#') != updatesFingerprint
        val intent = Intent(context, MainActivity::class.java).putExtra(OPEN_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val names = updates.joinToString(", ") { it.name }
        val installAll = PendingIntent.getActivity(context, ID + 1,
            Intent(context, MainActivity::class.java).putExtra(INSTALL_ALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = if (allReady) "Всё загружено: $names. Нажмите «Установить все» и подтвердите каждое приложение."
            else "Доступны обновления: $names. Они загружаются сами; потом останется нажать «Установить все»."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(if (allReady) "Обновления готовы к установке • ${updates.size}" else "Пора обновить приложения • ${updates.size}")
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(0, "Установить все", installAll)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setNumber(updates.size)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .extend(NotificationCompat.TvExtender().setContentIntent(pending).setChannelId(CHANNEL))
            .build()
        try {
            manager.notify(ID, notification)
            // Some TV launchers omit the notification tray. A standard Android
            // text reminder remains visible without overlay/accessibility permissions.
            if (firstTimeForTheseUpdates && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context.applicationContext,
                        "EZCH Update: пора обновить приложения (${updates.size})", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            settings.prefs.edit().putString("notification_fingerprint", fingerprint).apply()
        } catch (_: SecurityException) { /* Permission can be revoked while posting. Retry after authorization. */ }
    }
    fun permissionStatus(): String = when {
        !settings.notifications -> "Уведомления выключены в EZCH Update"
        !manager.areNotificationsEnabled() -> "Разрешите уведомления в настройках Android"
        manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "Канал обновлений выключен в Android"
        else -> "Уведомления включены. Место показа зависит от оболочки телевизора."
    }
}

/** WorkManager survives process exit and reboot; Android may defer the requested interval. */
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
        val updates = apps.filter { app ->
            app.installable && repository.installedApp(applicationContext.packageManager, app.packageName)?.let { it.versionCode < app.versionCode } == true
        }
        UpdateStatus.record(applicationContext, updates)
        UpdateNotifier(applicationContext).publish(updates, readyCount = 0)
        var ready = 0
        if (settings.autoDownload && updates.isNotEmpty()) {
            // Every update downloads in the background, two at a time (AppInstaller bounds all callers to 2);
            // each APK is verified (size, SHA-256, package, signer) before it counts as ready.
            val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
            kotlinx.coroutines.coroutineScope {
                updates.map { app ->
                    async {
                        if (isStopped) return@async false
                        try { AppInstaller().stage(applicationContext, app) { _, _ -> check(!isStopped) }; UpdateStatus.changed(applicationContext); true }
                        catch (_: Exception) { failed += app.name; false }
                    }
                }.forEach { if (it.await()) ready++ }
            }
            if (isStopped) return@withContext Result.retry()
            settings.prefs.edit().putString("background_status",
                if (failed.isEmpty()) "Загружено обновлений: $ready. Осталось нажать «Установить все»"
                else "Загружено: $ready. Не удалось: ${failed.joinToString()}. Повтор при следующей проверке").apply()
            UpdateNotifier(applicationContext).publish(updates, readyCount = ready)
        }
        settings.prefs.edit().putLong("last_background_check", System.currentTimeMillis()).apply()
        Result.success()
    }
}
