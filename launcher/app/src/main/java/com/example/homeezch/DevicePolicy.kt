package com.example.homeezch

import android.app.ActivityManager
import android.content.Context

internal const val LITE_RAM_LIMIT = 3L * 1024 * 1024 * 1024 / 2
internal fun isLiteMemory(totalBytes: Long, lowRamDevice: Boolean) =
    lowRamDevice || totalBytes <= LITE_RAM_LIMIT

internal fun deviceLowRam(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(ActivityManager::class.java)
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    isLiteMemory(info.totalMem, manager.isLowRamDevice)
}.getOrDefault(true)

internal fun effectivePremium(context: Context): Boolean =
    !shouldUseLite(WallpaperSettings.load(context).mode, deviceLowRam(context),
        context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true)
