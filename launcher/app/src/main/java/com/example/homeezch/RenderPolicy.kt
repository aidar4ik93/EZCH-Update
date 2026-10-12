package com.example.homeezch

import androidx.compose.runtime.staticCompositionLocalOf

internal data class RenderProfile(
    val premium: Boolean = true,
    val heavyEffects: Boolean = true,
    val reason: String = "Premium"
)

internal val LocalRenderProfile = staticCompositionLocalOf { RenderProfile() }

/** Manual mode selects the theme; resource pressure can still stop expensive effects. */
internal fun renderProfile(mode: WallpaperMode, totalRam: Long, lowRamDevice: Boolean,
    savingPower: Boolean, lowMemory: Boolean, overheated: Boolean,
    slowFrames: Boolean, graphicsAvailable: Boolean): RenderProfile {
    val littleMemory = isLiteMemory(totalRam, lowRamDevice)
    val lite = shouldUseLite(mode, littleMemory, savingPower)
    if (lite) return RenderProfile(false, false, when {
        mode == WallpaperMode.LITE -> "Lite выбран вручную"
        savingPower -> "Lite · энергосбережение"
        else -> "Lite · объём ОЗУ"
    })
    val reason = when {
        lowMemory -> "Premium · эффекты упрощены: нехватка памяти"
        littleMemory -> "Premium · эффекты упрощены: объём ОЗУ"
        overheated -> "Premium · эффекты упрощены: нагрев"
        savingPower -> "Premium · эффекты упрощены: энергосбережение"
        !graphicsAvailable -> "Premium · эффекты упрощены: возможности графики"
        slowFrames -> "Premium · эффекты упрощены: задержки кадров"
        else -> "Premium"
    }
    return RenderProfile(true, !littleMemory && !lowMemory && !overheated && !savingPower && graphicsAvailable && !slowFrames, reason)
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
