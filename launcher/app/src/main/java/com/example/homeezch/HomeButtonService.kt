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
    private val remotePress = RemoteButtonPress()
    companion object {
        @Volatile internal var connected: Boolean = false
        private var instance: HomeButtonService? = null
        internal fun requestBootStart() { instance?.scheduleStartup() }
        /** For the phone remote (Back/Home, typing into fields) when Ai EvA's own service is off. */
        internal fun accessibility(): AccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        EzchDiag.init(this, "служба кнопки HOME подключена")
        scheduleStartup()
        com.example.homeezch.ai.CinemaMode.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        instance = null
        handler.removeCallbacks(startup)
        handler.removeCallbacks(longHome)
        homeDown = false
        return super.onUnbind(intent)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var homeDown = false
    private var longPressSent = false
    private var lastRedirectAt = 0L
    private var startupAttempts = 0
    private val wake = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) { scheduleStartup() }
    }
    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, wake,
            android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private fun scheduleStartup() {
        handler.removeCallbacks(startup)
        if (!HomeStartup.eligible(this)) return
        HomeStartup.pending(this)
        startupAttempts = 0
        handler.postDelayed(startup, 500)
    }
    private val startup = object : Runnable {
        override fun run() {
            if (!HomeStartup.eligible(this@HomeButtonService)) return
            val power = getSystemService(android.os.PowerManager::class.java)
            val keyguard = getSystemService(android.app.KeyguardManager::class.java)
            if (power?.isInteractive != true || keyguard?.isKeyguardLocked == true) return
            if (startupAttempts++ >= 3) return
            Log.i("EZCH.Startup", "Request HOME after boot; attempt=$startupAttempts")
            openLauncher(false)
            handler.postDelayed(this, 2000)
        }
    }

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
        com.example.homeezch.ai.AssistantTakeover.onEvent(this, event)
        val eventPackage = event.packageName?.toString() ?: return
        val recoveryHome = eventPackage == "com.google.android.tungsten.setupwraith" &&
            event.className?.toString() == "com.google.android.tungsten.setupwraith.RecoveryActivity" &&
            // Returning from the firmware's fake HOME screen must remain available even
            // when the owner disables the separate one-time launch after device boot.
            // Both behaviours use this service, but they are independent preferences.
            runCatching {
                android.provider.Settings.Global.getInt(contentResolver, "device_provisioned", 0) == 1 &&
                android.provider.Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) == 1 &&
                packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                    ?.activityInfo?.let { it.packageName == eventPackage && it.name == event.className?.toString() } == true
            }.getOrDefault(false)
        if (eventPackage !in stockHomePackages && !recoveryHome) return
        if (HomeRole.isHome(this)) return // Actual HOME handler is EZCH, no redirect loop.
        // ROLE_HOME alone is not sufficient: some Android TV firmware holds it for
        // EZCH while forwarding HOME to com.google.android.tvlauncher.

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < 1800L) return
        lastRedirectAt = now
        openLauncher(false)
    }

    override fun onInterrupt() {
        remotePress.reset()
        handler.removeCallbacks(longHome)
        homeDown = false
    }

    override fun onDestroy() {
        connected = false
        instance = null
        handler.removeCallbacks(startup)
        runCatching { unregisterReceiver(wake) }
        handler.removeCallbacks(longHome)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (RemoteButtons.learning) { remotePress.reset(); return false }
        VolumeHold.onKeyEvent(this, event)
        if (remotePress.handle(this, event, defaults = false)) return true
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
