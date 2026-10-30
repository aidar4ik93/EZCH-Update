package com.example.homeezch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TopBar(refresh: Int, weatherReady: Boolean, homeReset: Int = 0,
    settingsModifier: Modifier = Modifier, onVoice: (() -> Unit)? = null, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val heavy = LocalRenderProfile.current.heavyEffects
    val status = rememberConnectionStatus()
    val trigger = remember { FocusRequester() }
    LaunchedEffect(homeReset) { expanded = false }
    LaunchedEffect(expanded) {
        val target = if (expanded) 3 else 0
        while (count != target) { if (count < target) count++ else count--; delay(if (heavy) 90 else 80) }
    }
    BackHandler(expanded) { expanded = false; trigger.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compactBar = maxWidth < 800.dp
    val quickWidth = if (compactBar) 76.dp else 112.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(290.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeatherSlot(refresh, weatherReady, homeReset)
            ClockSlot()
        }
        Spacer(Modifier.weight(1f))
        // Owner's choice (24.10): the top bar stays clean — Ai EvA lives in the sphere and in Settings,
        // the phone remote in Settings, the update notice at the bottom of the desktop.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Настройки EZCH", "Wi-Fi", "Bluetooth").forEachIndexed { index, label ->
                AnimatedVisibility(index >= 3 - count,
                    enter = fadeIn(tween(if (heavy) 220 else 100)) + slideInHorizontally(tween(if (heavy) 220 else 100)) { -it / 10 },
                    exit = fadeOut(tween(if (heavy) 180 else 100)) + slideOutHorizontally(tween(if (heavy) 180 else 100)) { -it / 10 }) {
                    QuickSettingTile(label, when(index) { 1 -> status.wifi; 2 -> status.bluetooth; else -> "Открыть" },
                        when(index) { 1 -> status.wifiConnected; 2 -> status.bluetoothConnected; else -> false }, quickWidth) {
                        when(index) { 1 -> SettingsRouter.wifi(context); 2 -> SettingsRouter.bluetooth(context); else -> { expanded = false; onSettings() } }
                    }
                }
            }
            PanelLaunchButton(tv = false, modifier = settingsModifier.focusRequester(trigger), onClick = { expanded = !expanded })
        }
    }
    }
}

@Composable
private fun ClockSlot() {
    var now by remember { mutableStateOf(Date()) }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE, d MMM", Locale.forLanguageTag("ru")) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60000L - System.currentTimeMillis() % 60000L)
        }
    }
    Column {
        Text(clock.format(now), fontSize = 24.sp, color = Color.White,
            style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.width(96.dp))
        Text(date.format(now), fontSize = 10.sp, color = Color(0xFFB6C6DA))
    }
}

@Composable private fun QuickSettingTile(label: String, status: String, connected: Boolean, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.size(width, 94.dp).testTag("quick-$label")
        .focusHalo(if (connected) .4f else 0f).background(Color(0x99101C29), DesktopShape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Ice else Color(0xFF354C62), DesktopShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        GlassIcon(if (label == "Wi-Fi") "Сеть" else if (label == "Bluetooth") "Bluetooth" else "Настройки", color = Ice)
        Text(label, color = Color.White, fontSize = 11.sp)
        Text(status, color = Muted, fontSize = 8.sp, maxLines = 2)
    }
}
