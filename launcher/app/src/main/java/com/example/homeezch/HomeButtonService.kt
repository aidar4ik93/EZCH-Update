package com.example.homeezch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * HOME fallback for Android TV firmware that refuses to offer the standard HOME role.
 *
 * Some TV remotes never deliver KEYCODE_HOME to accessibility services. On those devices
 * an opted-in accessibility service instead notices when a known stock TV HOME window
 * appears. There is no general permission-free way to override an OEM-reserved HOME key.
 */
class HomeButtonService : AccessibilityService() {
    companion object {
        @Volatile internal var connected: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        handler.removeCallbacks(longHome)
        homeDown = false
        return super.onUnbind(intent)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var homeDown = false
    private var longPressSent = false
    private var lastRedirectAt = 0L

    private val stockHomePackages = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV
        "com.google.android.tvlauncher",          // Android TV Home
        "com.google.android.leanbacklauncher",
        "com.android.tv.launcher",
        "com.android.leanbacklauncher",
        "com.amazon.tv.launcher"                 // Fire OS (may still block overriding)
    )

    private fun openLauncher(menu: Boolean): Boolean = runCatching {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClass(this, MainActivity::class.java)
            .putExtra("ezch.home.menu", menu)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        true
    }.getOrElse {
        Log.w("EZCH.Home", "TV denied HOME fallback", it)
        false
    }

    private val longHome = Runnable {
        if (homeDown && !longPressSent) {
            longPressSent = true
            openLauncher(true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage !in stockHomePackages) return
        if (HomeRole.isHome(this)) return // Actual HOME handler is EZCH, no redirect loop.
        // ROLE_HOME alone is not sufficient: some Android TV firmware holds it for
        // EZCH while forwarding HOME to com.google.android.tvlauncher.

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < 1800L) return
        lastRedirectAt = now
        openLauncher(false)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(longHome)
        homeDown = false
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(longHome)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    homeDown = true
                    longPressSent = false
                    handler.postDelayed(longHome, 600L)
                } else if (homeDown && event.isLongPress && !longPressSent) {
                    handler.removeCallbacks(longHome)
                    longPressSent = true
                    openLauncher(true)
                }
                return homeDown
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longHome)
                val consumed = homeDown
                if (homeDown && !longPressSent && !event.isCanceled) openLauncher(false)
                homeDown = false
                return consumed
            }
        }
        return false
    }
}
