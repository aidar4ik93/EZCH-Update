package com.example.homeezch

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observes HOME only. No service, permanent timer or persisted automatic downgrade. */
internal class LauncherRenderMonitor(private val activity: Activity) {
    private val state = MutableStateFlow(RenderProfile(false, false, "Определяем ресурсы"))
    val profile = state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val frames = FrameHealth()
    private var worker: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var running = false
    private var memoryPressureUntil = 0L
    private val sample = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 5000)
        }
    }

    fun refresh() {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        val memoryKnown = runCatching { requireNotNull(manager).getMemoryInfo(memory) }.isSuccess
        val power = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saving = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)
        val hot = Build.VERSION.SDK_INT >= 29 && runCatching {
            power != null && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }.getOrDefault(false)
        val graphics = runCatching {
            activity.window.decorView.isHardwareAccelerated &&
                (manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x20000
        }.getOrDefault(false)
        state.value = renderProfile(WallpaperSettings.load(activity).mode,
            if (memoryKnown) memory.totalMem else 0, manager?.isLowRamDevice ?: true,
            saving, memory.lowMemory || SystemClock.elapsedRealtime() < memoryPressureUntil,
            hot, frames.isReduced(), graphics)
    }

    fun memoryPressure() {
        memoryPressureUntil = SystemClock.elapsedRealtime() + 30000
        refresh()
    }

    fun start() {
        if (running) return
        running = true
        main.post(sample)
        val thread = HandlerThread("EZCH-frame-metrics").also { it.start() }
        val callback = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val reported = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
            @Suppress("DEPRECATION")
            val rate = runCatching { window.windowManager.defaultDisplay.refreshRate }.getOrDefault(60f)
            val budget = if (reported > 0) reported else (1_000_000_000.0 / rate.coerceIn(24f, 240f)).toLong()
            frames.record(duration, budget, metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)
        }
        if (runCatching { activity.window.addOnFrameMetricsAvailableListener(callback, Handler(thread.looper)) }.isSuccess) {
            worker = thread; listener = callback
        } else thread.quitSafely()
    }

    fun stop() {
        running = false
        main.removeCallbacks(sample)
        listener?.let { runCatching { activity.window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        worker?.quitSafely(); worker = null
    }
}
