package com.example.homeezch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun CompactSourceCard(source: TvSourceEntry, premium: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = if (source.kind == TvSourceKind.HDMI && source.label.contains("2")) Gold else Ice
    val glow = animateFloatAsState(if (source.connected) .5f else 0f, tween(300), label = "inputConnection")
    val scale = animateFloatAsState(if (focused) 1.06f else 1f, tween(if (premium) 160 else 80), label = "inputFocus")
    Column(modifier.size(112.dp, 94.dp).testTag("source-${source.id}")
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .focusHalo({ if (premium) glow.value else 0f }, accent)
        .clip(DesktopShape).background(Color(0x99101C29))
        .border(if (focused) 3.dp else 1.dp, if (focused) Ice else Color(0xFF354C62), DesktopShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PortIcon(source.kind, color = accent)
        Text(source.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
        Text(source.detail ?: source.hint, color = if (source.connected) accent else Muted,
            fontSize = 8.sp, maxLines = 2)
    }
}
