package com.example.homeezch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/** Launch supported public settings routes; denied OEM activities are skipped. */
internal object SettingsRouter {
    internal fun open(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                Log.i("EZCH.Navigation", "Unsupported action: ${intent.action}")
            } catch (e: SecurityException) {
                Log.w("EZCH.Navigation", "Action denied: ${intent.action}", e)
            }
        }
        Toast.makeText(context, "Этот экран недоступен на вашем устройстве", Toast.LENGTH_LONG).show()
        return false
    }

    fun bluetooth(context: Context): Boolean {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(context, "Bluetooth недоступен · открываем настройки", Toast.LENGTH_LONG).show()
            return settings(context)
        }
        val candidates = listOf(
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.android.tv.settings"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.google.android.tv.settings"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent("android.settings.CONNECTED_DEVICE_SETTINGS"),
            Intent("android.bluetooth.devicepicker.action.LAUNCH"),
            Intent(Settings.ACTION_SETTINGS)
        )
        return open(context, candidates)
    }
    fun accessibility(context: Context): Boolean {
        if (HomeAccessibility.enable(context)) {
            Toast.makeText(context, "Служба EZCH для кнопки Home включена", Toast.LENGTH_LONG).show()
            return true
        }
        Toast.makeText(context, "Подтвердите включение службы EZCH для кнопки Home в системном окне Android.", Toast.LENGTH_LONG).show()
        val service = android.content.ComponentName(context, HomeButtonService::class.java).flattenToString()
        return open(context, listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME", android.content.ComponentName(context, HomeButtonService::class.java)),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)
        ))
    }
    fun files(context: Context, volume: android.os.storage.StorageVolume? = null): Boolean {
        return open(context, listOf(Intent(context, FileBrowserActivity::class.java).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) volume?.directory?.let { putExtra("path", it.absolutePath) }
        }))
    }
    fun wifi(context: Context) = open(context, listOf(
        Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    ))
    fun display(context: Context) = open(context, listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    fun sound(context: Context) = open(context, listOf(Intent(Settings.ACTION_SOUND_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    fun storage(context: Context) = open(context, listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
    fun settings(context: Context) = open(context, listOf(Intent(Settings.ACTION_SETTINGS)))
    fun source(context: Context, source: TvSourceEntry): Boolean = if (source.id.startsWith("manual-")) open(context, listOf(
        Intent("android.settings.TV_INPUT_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS)
    )) else open(context, listOf(
        Intent(Intent.ACTION_VIEW, if (source.kind == TvSourceKind.TV)
            TvContract.Channels.CONTENT_URI else TvContract.buildChannelUriForPassthroughInput(source.id))
    ))
}
