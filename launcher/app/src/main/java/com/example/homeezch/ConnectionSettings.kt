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
    val status = rememberConnectionStatus()
    val network = status.wifi
    val bluetooth = status.bluetooth

    SettingsItem("Bluetooth", "Bluetooth", "Пульты, наушники и другие устройства.", bluetooth,
        modifier = firstModifier.testTag("settings-bluetooth")) {
        SettingsRouter.bluetooth(context)
    }
    if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        SettingsItem("Bluetooth", "Показывать состояние Bluetooth", "Разрешение нужно только для чтения состояния и подключений.") {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    SettingsItem("Сеть", "Wi-Fi и сеть", "Подключение Wi-Fi, Ethernet и параметры сети.", network,
        modifier = Modifier.testTag("settings-wifi")) { SettingsRouter.wifi(context) }
    SettingsItem("Экран", "Изображение", "Параметры экрана и изображения телевизора.",
        modifier = Modifier.testTag("settings-display")) { SettingsRouter.display(context) }
    SettingsItem("Звук", "Звук", "Громкость, вывод звука и параметры аудио.",
        modifier = Modifier.testTag("settings-sound")) { SettingsRouter.sound(context) }
}
