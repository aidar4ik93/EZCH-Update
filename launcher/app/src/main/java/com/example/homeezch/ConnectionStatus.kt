package com.example.homeezch
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle

internal data class ConnectionStatus(val wifi: String, val wifiConnected: Boolean,
    val bluetooth: String, val bluetoothConnected: Boolean)

internal fun readConnectionStatus(context: Context): ConnectionStatus {
    val wifi = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val caps = manager.allNetworks.mapNotNull(manager::getNetworkCapabilities)
        val wireless = caps.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        when {
            wireless != null -> if (wireless.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "Подключено" else "Подключено · без интернета"
            else -> "Не подключено"
        } to (wireless != null)
    }.getOrDefault("Статус неизвестен" to false)
    val bt = runCatching {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val allowed = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        when {
            adapter == null -> "Нет адаптера" to false
            !allowed -> "Статус неизвестен" to false
            !adapter.isEnabled -> "Выключен" to false
            else -> {
                val connected = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).any {
                    runCatching { adapter.getProfileConnectionState(it) == android.bluetooth.BluetoothAdapter.STATE_CONNECTED }.getOrDefault(false)
                } || listOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER).any {
                    runCatching { manager.getConnectedDevices(it).isNotEmpty() }.getOrDefault(false)
                }
                (if (connected) "Устройство подключено" else "Включён · подключение не подтверждено") to connected
            }
        }
    }.getOrDefault("Статус неизвестен" to false)
    return ConnectionStatus(wifi.first, wifi.second, bt.first, bt.second)
}

@Composable internal fun rememberConnectionStatus(): ConnectionStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readConnectionStatus(context)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { status = readConnectionStatus(context); delay(5000) }
        }
    }
    return status
}
