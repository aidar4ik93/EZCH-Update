package com.example.homeezch

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Claude r55: A/B switches for measuring the desktop on a real box in ONE session, without reinstalling.
 * Set only by an explicit launch extra (`adb shell am start -n …/.MainActivity --ei ezch.perf N`), never persisted;
 * a normal start leaves every switch off, so users always get the default rendering.
 *  bit 0 (1)  — draw the focused shelf directly instead of from its cached layer (r55 experiment; slower on Homatics)
 *  bit 1 (2)  — flat colour instead of the wallpaper (upper bound of the wallpaper cost)
 *  bit 2 (4)  — no focus halo
 *  bit 3 (8)  — app cards without the rounded clip
 *  bit 4 (16) — app cards without the focus scale animation
 *  bit 5 (32) — app cards without the border
 */
internal object PerfLab {
    var flags by mutableIntStateOf(0)
        private set
    fun apply(intent: Intent?) { if (intent?.hasExtra("ezch.perf") == true) flags = intent.getIntExtra("ezch.perf", 0).coerceIn(0, 63) }
    val focusedShelfDirect: Boolean get() = flags and 1 != 0
    val noWallpaper: Boolean get() = flags and 2 != 0
    val noHalo: Boolean get() = flags and 4 != 0
    val noCardClip: Boolean get() = flags and 8 != 0
    val noCardScale: Boolean get() = flags and 16 != 0
    val noCardBorder: Boolean get() = flags and 32 != 0
}
