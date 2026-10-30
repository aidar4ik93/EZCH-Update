package com.example.homeezch.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV routes the remote's microphone button straight to Google Assistant, and most Google TV
 * firmwares (Homatics Dongle G included) have no screen to pick another assistant. When the owner
 * enables «Кнопка микрофона открывает Еву», the accessibility service that sees the Assistant window
 * appear closes it with BACK and opens Ai EvA instead. Nothing is changed in system settings or roles.
 */
object AssistantTakeover {
    /** Windows of Google Assistant on Android TV / Google TV. */
    internal val ASSISTANT_PACKAGES = setOf("com.google.android.katniss", "com.google.android.apps.googleassistant")
    private const val KEY = "mic_takeover"
    private var lastTakeover = 0L
    private val main = Handler(Looper.getMainLooper())

    fun enabled(context: Context) = AiPreferences.enabled(context) && AiPreferences.store(context).getBoolean(KEY, true)
    fun setEnabled(context: Context, value: Boolean) = AiPreferences.store(context).edit().putBoolean(KEY, value).apply()

    internal fun shouldTakeOver(packageName: String?, now: Long, enabled: Boolean): Boolean =
        enabled && packageName in ASSISTANT_PACKAGES && now - lastTakeover > 2_000L

    /** Called from onAccessibilityEvent of whichever EZCH accessibility service is enabled. */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        CinemaMode.noteWindow(event.packageName?.toString())
        val now = SystemClock.elapsedRealtime()
        if (!shouldTakeOver(event.packageName?.toString(), now, enabled(service))) return
        lastTakeover = now
        AiPreferences.store(service).edit().putLong("mic_takeover_seen", System.currentTimeMillis()).apply()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        // Let Assistant release the microphone before EVA starts listening.
        main.postDelayed({ openEva(service) }, 450L)
    }

    fun openEva(context: Context) {
        EvaEntry.open(context)
    }
}
