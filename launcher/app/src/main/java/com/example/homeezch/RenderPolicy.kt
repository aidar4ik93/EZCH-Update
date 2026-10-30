package com.example.homeezch

import androidx.compose.runtime.staticCompositionLocalOf

internal data class RenderProfile(
    val premium: Boolean = true,
    val heavyEffects: Boolean = true,
    val reason: String = "Premium"
)

internal val LocalRenderProfile = staticCompositionLocalOf { RenderProfile() }

/** An OEM's extended deadline must not mask sustained misses of the display period. */
internal fun frameBudget(reportedNanos: Long, refreshRate: Float): Long {
    val rate = if (refreshRate.isFinite() && refreshRate > 0) refreshRate.coerceIn(24f, 240f) else 60f
    val period = (1_000_000_000.0 / rate).toLong()
    return if (reportedNanos > 0) minOf(reportedNanos, period) else period
}

/** Manual mode selects the theme; resource pressure can still stop expensive effects. */
internal fun renderProfile(mode: WallpaperMode, totalRam: Long, lowRamDevice: Boolean,
    savingPower: Boolean, lowMemory: Boolean, overheated: Boolean,
    slowFrames: Boolean, graphicsAvailable: Boolean): RenderProfile {
    val littleMemory = isLiteMemory(totalRam, lowRamDevice)
    // Keep the Premium artwork on 2 GB TV boxes while avoiding costly glows and
    // overlapping focus animations from the first frame, before metrics arrive.
    val limitedEffectsMemory = totalRam <= 2L * 1024 * 1024 * 1024
    val lite = shouldUseLite(mode, littleMemory, savingPower)
    if (lite) return RenderProfile(false, false, when {
        mode == WallpaperMode.LITE -> "Lite выбран вручную"
        savingPower -> "Lite · энергосбережение"
        else -> "Lite · объём ОЗУ"
    })
    val reason = when {
        lowMemory -> "Premium · эффекты упрощены: нехватка памяти"
        littleMemory || limitedEffectsMemory -> "Premium · эффекты упрощены: объём ОЗУ"
        overheated -> "Premium · эффекты упрощены: нагрев"
        savingPower -> "Premium · эффекты упрощены: энергосбережение"
        !graphicsAvailable -> "Premium · эффекты упрощены: возможности графики"
        slowFrames -> "Premium · эффекты упрощены: задержки кадров"
        else -> "Premium"
    }
    return RenderProfile(true, !littleMemory && !limitedEffectsMemory && !lowMemory && !overheated && !savingPower && graphicsAvailable && !slowFrames, reason)
}

/** Startup frames and missing metrics are ignored. Two slow windows prevent a transient switch. */
internal class FrameHealth {
    private var samples = 0
    private var slow = 0
    private var slowWindows = 0
    var reduced: Boolean = false
        private set

    @Synchronized fun record(durationNanos: Long, budgetNanos: Long, firstDraw: Boolean = false) {
        if (firstDraw || durationNanos <= 0 || budgetNanos <= 0 || reduced) return
        samples++
        if (durationNanos > budgetNanos + budgetNanos / 2) slow++
        if (samples >= 120) {
            slowWindows = if (slow * 100 >= samples * 30) slowWindows + 1 else 0
            if (slowWindows >= 2) reduced = true
            samples = 0; slow = 0
        }
    }
    @Synchronized fun isReduced() = reduced
}
