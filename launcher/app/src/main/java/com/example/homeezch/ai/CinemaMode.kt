package com.example.homeezch.ai

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * «Кино-режим»: the film starts — the chosen Home Assistant lights glide down; pause or the end —
 * they return to exactly what they were. Uses only public signals (AudioManager.isMusicActive and the
 * foreground app the EZCH accessibility service already sees), nothing is recorded or sent elsewhere.
 */
object CinemaMode {
    private const val PREFS = "eva_cinema_v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val policy = CinemaPolicy()
    /** Last foreground app reported by an EZCH accessibility service (overlays such as the volume panel are ignored). */
    @Volatile var foreground: String? = null
    fun noteWindow(packageName: String?) {
        if (packageName == null || packageName.startsWith("com.android.systemui") || packageName.contains("inputmethod") ||
            packageName.contains("keyboard")) return
        foreground = packageName
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun level(context: Context) = prefs(context).getInt("level", 15)
    fun lights(context: Context): Set<String> = prefs(context).getStringSet("lights", emptySet()).orEmpty()
    fun configure(context: Context, enabled: Boolean, level: Int, lights: Set<String>) {
        prefs(context).edit().putBoolean("enabled", enabled).putInt("level", level.coerceIn(1, 60)).putStringSet("lights", lights).apply()
        if (enabled) start(context)
    }

    /** Idempotent. The loop idles cheaply while the mode is off or Home Assistant is not connected. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val audio = app.getSystemService(AudioManager::class.java)
            while (isActive) {
                if (!enabled(app) || lights(app).isEmpty() || !com.example.homeezch.home.SmartHub.anyConnected(app)) {
                    if (policy.dimmed) restore(app)
                    policy.reset(); delay(10_000); continue
                }
                val front = runCatching { EvaAppService.current?.applicationPackage() }.getOrNull() ?: foreground
                val playing = audio.isMusicActive && !EvaAppService.panelVisibility.value &&
                    com.example.homeezch.phone.PhoneRemote.home?.get() == null && CinemaPolicy.eligible(front, app.packageName)
                when (policy.update(SystemClock.elapsedRealtime(), playing)) {
                    CinemaPolicy.Decision.DIM -> dim(app)
                    CinemaPolicy.Decision.RESTORE -> restore(app)
                    CinemaPolicy.Decision.NONE -> Unit
                }
                delay(1_500)
            }
        }
    }

    /** Chosen lights are «provider|id» keys from the «Умный дом» screen (Яндекс, Tuya or Home Assistant). */
    private suspend fun chosenDevices(context: Context): List<com.example.homeezch.home.SmartDevice> {
        val keys = lights(context)
        val all = com.example.homeezch.home.SmartHub.devices(context)
        return all.filter { (it.provider + "|" + it.id) in keys || it.id in keys }
    }

    private suspend fun dim(context: Context) {
        val level = level(context)
        val saved = JSONObject()
        for (d in chosenDevices(context)) {
            if (d.on != true) continue // a light that is off stays off
            saved.put(d.provider + "|" + d.id, d.brightness ?: 100)
            com.example.homeezch.home.SmartHub.send(context, d, com.example.homeezch.home.DeviceCommand.Brightness(level))
        }
        prefs(context).edit().putString("saved", saved.toString()).apply()
    }

    private suspend fun restore(context: Context) {
        val saved = runCatching { JSONObject(prefs(context).getString("saved", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (saved.length() == 0) return
        val level = level(context)
        for (d in com.example.homeezch.home.SmartHub.devices(context)) {
            val key = d.provider + "|" + d.id
            if (!saved.has(key)) continue
            if (d.on != true) continue // switched off by hand during the film: respect it
            if (!CinemaPolicy.untouched(d.brightness, level)) continue
            com.example.homeezch.home.SmartHub.send(context, d, com.example.homeezch.home.DeviceCommand.Brightness(saved.optInt(key, 100)))
        }
        prefs(context).edit().remove("saved").apply()
    }
}
