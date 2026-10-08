package com.example.homeezch

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TopBar(refresh: Int, weatherReady: Boolean, homeReset: Int = 0, onSettings: () -> Unit) {
    val context = LocalContext.current
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SettingsRouter.bluetooth(context)
        else Toast.makeText(context, "Разрешите доступ к устройствам поблизости для Bluetooth", Toast.LENGTH_LONG).show()
    }
    var bluetoothTick by remember { mutableIntStateOf(0) }
    var networkRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10000L); bluetoothTick++ } }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkRevision++ }
            override fun onLost(network: Network) { networkRevision++ }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { networkRevision++ }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback); manager != null }.getOrDefault(false)
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    val network = remember(networkRevision, refresh) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                capabilities == null -> "Нет сети"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Подключено"
            }
        }.getOrDefault("—")
    }
    val bluetooth = remember(refresh, bluetoothTick) {
        val permitted = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!permitted) "Нет доступа" else runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            when { adapter == null -> "Нет адаптера"; !adapter.isEnabled -> "Выключен"; else -> "Включён" }
        }.getOrDefault("—")
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.width(226.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WeatherSlot(refresh, weatherReady, homeReset)
            ClockSlot()
        }
        Row(Modifier.background(Brush.verticalGradient(listOf(Color(0xC525384D),Color(0xB0081424))), RoundedCornerShape(15.dp))
            .border(1.dp, Color(0xFF354C62), RoundedCornerShape(15.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            TopControl("Bluetooth", bluetooth) {
                if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                else SettingsRouter.bluetooth(context)
            }
            TopControl("Сеть", network) { SettingsRouter.wifi(context) }
            TopControl("Экран", null) { SettingsRouter.display(context) }
            TopControl("Звук", null) { SettingsRouter.sound(context) }
            TopControl("Хранилище", null) { SettingsRouter.files(context) }
            TopControl("Настройки", null, onSettings)
        }
        Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
            Text("EZCH", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Launcher", color = Color(0xFFB6C6DA), fontSize = 13.sp)
        }
    }

}

@Composable
private fun TopControl(title: String, status: String?, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if(focused) 1.08f else 1f, tween(160), label="topFocus")
    Column(Modifier.width(58.dp).height(62.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(DesktopShape).background(if(focused) Color(0xFF163A50) else Color.Transparent, DesktopShape)
        .border(if(focused) 4.dp else 0.dp, if(focused) Color(0xFF48B9FF) else Color.Transparent, DesktopShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = action).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        GlassIcon(title)
        Text(title, fontSize=9.sp, color=Color.White, maxLines=1)
        if(status != null) Text(status, fontSize=7.sp, color=Color(0xFFB6C6DA), maxLines=1)
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
