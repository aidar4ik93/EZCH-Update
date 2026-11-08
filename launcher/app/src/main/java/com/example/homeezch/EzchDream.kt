package com.example.homeezch

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Claude r52: EZCH screensaver (Android «Заставка»). Clock, date, weather and the pinned smart-home devices on one
 * glass card in the EZCH style. It redraws once a minute (TextClock) and drifts a little every minute so a TV panel
 * never keeps a static image. Plain Views, no animation loop: idle cost is near zero.
 * The owner selects it once in Android settings → Экран → Заставка («EZCH — часы и погода»).
 */
class EzchDream : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val weather = TextView(this).apply { setTextColor(Color.parseColor("#FFC474")); textSize = 22f; gravity = Gravity.CENTER }
        val smart = TextView(this).apply { setTextColor(Color.parseColor("#B6C6DA")); textSize = 16f; gravity = Gravity.CENTER }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#661E3350"), Color.parseColor("#55101C30"))).apply {
                cornerRadius = dp(28).toFloat(); setStroke(dp(1), Color.parseColor("#4472C7FF"))
            }
            addView(TextClock(this@EzchDream).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 96f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            addView(TextClock(this@EzchDream).apply {
                format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 22f; setTextColor(Color.parseColor("#72C7FF")); gravity = Gravity.CENTER
            })
            addView(weather); addView(smart)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        scope.launch {
            var step = 0
            while (isActive) {
                val point = WeatherAccess.loadPoint(this@EzchDream)
                val reading = point?.let { runCatching { OpenMeteoWeather.load(this@EzchDream, it).reading }.getOrNull() }
                weather.text = if (point != null && reading != null)
                    "${point.city} · ${if (reading.temperature > 0) "+" else ""}${reading.temperature}° · ${OpenMeteoWeather.condition(reading.condition)}" else ""
                weather.visibility = if (weather.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                val pinned = SmartDesk.pinned(this@EzchDream)
                smart.text = com.example.homeezch.home.SmartHub.cache.filter { SmartDesk.key(it) in pinned }.joinToString("   ") { d ->
                    "${d.type.icon} ${d.name}${when { d.reading != null -> " ${d.reading}"; d.on == true -> " вкл"; d.on == false -> " выкл"; else -> "" }}"
                }
                smart.visibility = if (smart.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                // Burn-in protection: a slow drift around the centre, one step a minute.
                val dx = listOf(0, 40, 0, -40)[step % 4] * density; val dy = listOf(-24, 0, 24, 0)[step % 4] * density
                card.animate().translationX(dx).translationY(dy).setDuration(1200).start()
                step++
                delay(60_000)
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
