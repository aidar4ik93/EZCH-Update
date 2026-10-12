package com.example.homeezch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
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
    settingsModifier: Modifier = Modifier, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(226.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeatherSlot(refresh, weatherReady, homeReset)
            ClockSlot()
        }
        Spacer(Modifier.weight(1f))
        PanelLaunchButton(tv = false, modifier = settingsModifier, onClick = onSettings)
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
