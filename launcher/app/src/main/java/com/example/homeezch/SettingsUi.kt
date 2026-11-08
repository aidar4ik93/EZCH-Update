package com.example.homeezch

import androidx.compose.ui.graphics.drawOutline

import androidx.compose.ui.draw.drawWithCache

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Layered translucent strokes approximate diffuse light without bitmap blur or extra textures. */
@Composable internal fun Modifier.focusHalo(intensity: Float, accent: Color = Ice): Modifier = focusHalo({ intensity }, accent)

/** Read animated intensity in drawing, avoiding composition on every animation frame.
 *  Claude r50.4: the rings are painted ONCE per (size, colour, ring count) into a small bitmap shared by every
 *  card of that size; a focus frame draws that one image with alpha = intensity instead of re-tessellating
 *  4–12 stroked round rectangles per card (the costliest part of a focus frame on Mali-G31). */
@Composable internal fun Modifier.focusHalo(level: () -> Float, accent: Color = Ice): Modifier {
    // Weak TVs get a 4-ring halo instead of none: the soft glow is part of the requested focus look.
    val rings = if (LocalRenderProfile.current.heavyEffects) 12 else 4
    return drawBehind {
        val intensity = level()
        if (intensity > .01f && !PerfLab.noHalo) {
            val pad = HaloCache.pad(this)
            val image = HaloCache.image(this, accent, rings)
            if (image != null) drawImage(image, Offset(-pad, -pad), alpha = intensity.coerceIn(0f, 1f))
            else drawHaloRings(intensity, accent, rings)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHaloRings(intensity: Float, accent: Color, rings: Int, inset: Float = 0f) {
    for (ring in rings downTo 1) {
        val spread = (ring * 12f / rings).dp.toPx()
        drawRoundRect(accent.copy(alpha = intensity * .035f * (rings + 1 - ring) / rings * (12f / rings).coerceAtMost(2.2f)),
            Offset(inset - spread, inset - spread), Size(size.width - 2 * inset + 2 * spread, size.height - 2 * inset + 2 * spread),
            CornerRadius(18.dp.toPx() + spread), style = Stroke(2.dp.toPx()))
    }
}

internal object HaloCache {
    private data class Key(val w: Int, val h: Int, val color: Int, val rings: Int, val density: Float)
    private val cache = android.util.LruCache<Key, androidx.compose.ui.graphics.ImageBitmap>(16)
    /** Ring reach (12 dp) + half the stroke + 1 px of antialiasing. */
    fun pad(scope: androidx.compose.ui.unit.Density) = with(scope) { 13.dp.toPx() + 1f }
    fun image(scope: androidx.compose.ui.graphics.drawscope.DrawScope, accent: Color, rings: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val w = scope.size.width; val h = scope.size.height
        if (w < 1f || h < 1f || w > 4000f || h > 4000f) return null
        val key = Key(kotlin.math.round(w).toInt(), kotlin.math.round(h).toInt(), accent.value.hashCode(), rings, scope.density)
        cache.get(key)?.let { return it }
        return runCatching {
            val pad = pad(scope)
            val bw = kotlin.math.ceil(w + 2 * pad).toInt(); val bh = kotlin.math.ceil(h + 2 * pad).toInt()
            val bitmap = androidx.compose.ui.graphics.ImageBitmap(bw, bh)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(scope, scope.layoutDirection,
                androidx.compose.ui.graphics.Canvas(bitmap), Size(bw.toFloat(), bh.toFloat())) {
                drawHaloRings(1f, accent, rings, inset = pad)
            }
            bitmap.prepareToDraw()
            cache.put(key, bitmap); bitmap
        }.getOrNull()
    }
    fun clear() = cache.evictAll()
}

@Composable internal fun SettingsSection(title: String) {
    Text(title, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

internal val LocalSettingsReturnFocus = staticCompositionLocalOf<(FocusRequester) -> Unit> { {} }

/** Each settings group has its own colour, so the list reads at a glance instead of one uniform blue. */
internal fun settingsAccent(icon: String): Color = when (icon) {
    "Пульт" -> Color(0xFFFF9F43)
    "Голос" -> Color(0xFFB57BFF)
    "Настройки" -> Color(0xFF2EE6C5)
    "Домой" -> Color(0xFF5AA9FF)
    "Экран" -> Color(0xFFFF6FAE)
    "Папка" -> Color(0xFFFFC94D)
    "Строки" -> Color(0xFF7BE07B)
    "Обои" -> Color(0xFF9F8CFF)
    "Bluetooth" -> Color(0xFF4D8BFF)
    "Сеть" -> Color(0xFF38D1FF)
    "Звук" -> Color(0xFFFF7A59)
    "Копия" -> Color(0xFF8BD3DD)
    "Профили" -> Color(0xFFFFB86B)
    else -> Ice
}

@Composable internal fun SettingsItem(icon: String, title: String, description: String,
    state: String? = null, accent: Color = settingsAccent(icon), enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val returnRequester = remember { FocusRequester() }
    val rememberReturnFocus = LocalSettingsReturnFocus.current
    // Claude r50: the focus animation is read only while drawing (lift, halo, rim) — the row is not recomposed
    // on every animation frame, which mattered while the remote scrolls the long settings list.
    val glow = animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "settingFocus")
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp)
        .graphicsLayer { val g = glow.value; scaleX = 1f + .018f * g; scaleY = 1f + .018f * g; alpha = if (enabled) 1f else .45f }
        .focusHalo({ glow.value }, accent).clip(shape).background(Color(0xB0192B43))
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx() * 2)
            onDrawWithContent {
                drawContent()
                drawOutline(outline, accent.copy(alpha = .18f + .55f * glow.value), style = stroke)
            }
        }
        .focusRequester(returnRequester)
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, onClick = {
            rememberReturnFocus(returnRequester); onClick()
        })
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(accent.copy(alpha = .42f), accent.copy(alpha = .14f))), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = .55f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            GlassIcon(icon, color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        state?.let {
            Text(it, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(max = 100.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(8.dp)).padding(7.dp))
        }
    }
}
