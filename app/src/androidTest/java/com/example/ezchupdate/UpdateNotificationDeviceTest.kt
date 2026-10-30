package com.example.ezchupdate

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.ezchupdate.data.RemoteApp
import org.junit.Assert.*
import org.junit.Test

class UpdateNotificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertTrue("Notification service did not reach the requested state", condition())
    }
    private val sample = RemoteApp("EZCH Update — тест уведомления", context.packageName, 999,
        "notification-fixture", "https://example.com/test.apk", "/test.apk", "a".repeat(64), 1, "b".repeat(64))

    @Test fun backgroundSummaryDeduplicatesOpensUpdatesAndClearsWhenCurrent() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val settings = UpdateSettings(context)
        settings.prefs.edit().putBoolean("notifications", true).remove("notification_fingerprint").commit()
        val notifier = UpdateNotifier(context)
        try {
            device.pressHome()
            notifier.publish(listOf(sample))
            await { manager.activeNotifications.any { it.id == UpdateNotifier.ID } }
            val first = manager.activeNotifications.single { it.id == UpdateNotifier.ID }
            assertTrue(first.notification.extras.getCharSequence("android.title").toString().contains("Пора обновить"))
            assertTrue(first.notification.extras.containsKey("android.tv.EXTENSIONS"))
            Thread.sleep(300)
            device.takeScreenshot(java.io.File(context.filesDir, "desktop-update-reminder.png"))
            // Android TV text toasts are absent from UIAutomator's accessibility tree.
            // Retain the desktop screenshot for visual verification on the real TV.
            notifier.publish(listOf(sample))
            assertEquals("Duplicate version must not re-post", first.postTime,
                manager.activeNotifications.single { it.id == UpdateNotifier.ID }.postTime)
            notifier.publish(emptyList())
            await { manager.activeNotifications.none { it.id == UpdateNotifier.ID } }
            assertNull(settings.prefs.getString("notification_fingerprint", null))
            notifier.publish(listOf(sample.copy(versionCode = 1000)))
            await { manager.activeNotifications.any { it.id == UpdateNotifier.ID } }
            settings.notifications = false
            await { manager.activeNotifications.none { it.id == UpdateNotifier.ID } }
            // Android 14+ blocks programmatic background PendingIntent launches;
            // a real system notification click grants its own foreground launch.
            device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
            first.notification.contentIntent.send()
            assertTrue(device.wait(Until.hasObject(By.text("Обновления")), 15_000))

        } finally {
            notifier.clear()
            settings.prefs.edit().putBoolean("notifications", true).commit()
        }
    }

    @Test fun deniedPermissionDoesNotConsumeFutureReminder() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val notifier = UpdateNotifier(context)
        notifier.clear()
        try {
            val deniedContext = object : android.content.ContextWrapper(context) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (permission == android.Manifest.permission.POST_NOTIFICATIONS) android.content.pm.PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
            }
            UpdateNotifier(deniedContext).publish(listOf(sample))
            assertNull(UpdateSettings(context).prefs.getString("notification_fingerprint", null))
            await { manager.activeNotifications.none { it.id == UpdateNotifier.ID } }
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            notifier.publish(listOf(sample))
            await { manager.activeNotifications.any { it.id == UpdateNotifier.ID } }
        } finally {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            notifier.clear()
        }
    }
}
