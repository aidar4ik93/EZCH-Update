package com.example.homeezch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QuickControls(
    premium: Boolean,
    onPremiumChange: (Boolean) -> Unit,
    onWifi: () -> Unit,
    onBluetooth: () -> Unit,
    onTrimCache: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ControlChip("Wi-Fi", onWifi)
        ControlChip("Bluetooth", onBluetooth)
        ControlChip(if (premium) "Premium ✦" else "Lite ⚡") { onPremiumChange(!premium) }
        ControlChip("Очистить кеш EZCH", onTrimCache)
    }
}

@Composable
internal fun ControlChip(title: String, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(170), label = "controlFocus")
    val shape = DesktopShape
    Box(
        Modifier.height(42.dp).scale(scale)
            .background(Color(0xAD16273D), shape)
            .clip(DesktopShape).border(if (focused) 4.dp else 1.dp,
                if (focused) Color(0xFF48B9FF) else Color(0xFF40566D), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = action)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = Color.White, fontSize = 14.sp)
    }
}
