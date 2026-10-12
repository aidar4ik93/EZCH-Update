package com.example.homeezch

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
internal fun ConnectionSettings(refresh: Int, firstModifier: Modifier = Modifier) {
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

    SettingsItem("Bluetooth", "Bluetooth", "Пульты, наушники и другие устройства.", bluetooth,
        modifier = firstModifier.testTag("settings-bluetooth")) {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else SettingsRouter.bluetooth(context)
    }
    SettingsItem("Сеть", "Wi-Fi и сеть", "Подключение Wi-Fi, Ethernet и параметры сети.", network,
        modifier = Modifier.testTag("settings-wifi")) { SettingsRouter.wifi(context) }
    SettingsItem("Экран", "Изображение", "Параметры экрана и изображения телевизора.",
        modifier = Modifier.testTag("settings-display")) { SettingsRouter.display(context) }
    SettingsItem("Звук", "Звук", "Громкость, вывод звука и параметры аудио.",
        modifier = Modifier.testTag("settings-sound")) { SettingsRouter.sound(context) }
}
