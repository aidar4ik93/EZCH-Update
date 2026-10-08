package com.example.homeezch

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
internal fun Modifier.focusHalo(intensity: Float, accent: Color = Ice): Modifier = drawBehind {
    if (intensity > .01f) for (ring in 12 downTo 1) {
        val spread = ring.dp.toPx()
        drawRoundRect(accent.copy(alpha = intensity * .035f * (13 - ring) / 12f),
            Offset(-spread, -spread), Size(size.width + 2 * spread, size.height + 2 * spread),
            CornerRadius(18.dp.toPx() + spread), style = Stroke(2.dp.toPx()))
    }
}

@Composable internal fun SettingsSection(title: String) {
    Text(title, color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable internal fun SettingsItem(icon: String, title: String, description: String,
    state: String? = null, accent: Color = Ice, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "settingFocus")
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)
        .graphicsLayer { scaleX = 1f + .018f * glow; scaleY = 1f + .018f * glow; alpha = if (enabled) 1f else .45f }
        .focusHalo(glow, accent).clip(shape).background(Color(0xB0192B43))
        .border(1.dp, accent.copy(alpha = .18f + .55f * glow), shape)
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, onClick = onClick)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            GlassIcon(icon, color = accent)
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
