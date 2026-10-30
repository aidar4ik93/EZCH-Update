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
    /** Resolve an exported route, excluding the TV firmware's silent no-op handler. */
    internal fun supportedIntent(context: Context, intent: Intent): Intent? {
        val pm = context.packageManager
        val handlers = runCatching {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrElse { Log.w("EZCH.Navigation", "Cannot resolve settings route", it); return null }
        val info = handlers.firstOrNull {
            val activity = it.activityInfo
            activity != null && activity.enabled && activity.applicationInfo.enabled &&
                (activity.exported || activity.packageName == context.packageName) &&
                !activity.name.endsWith(".EmptyStubActivity") &&
                (activity.permission.isNullOrEmpty() || context.checkSelfPermission(activity.permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
        }?.activityInfo ?: return null
        return Intent(intent).setComponent(android.content.ComponentName(info.packageName, info.name))
    }

    internal fun open(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            // Package visibility must not prevent a valid file, TV-source or web
            // VIEW intent: Android may launch its handler even when PM hides it.
            try {
                val supported = if (intent.action == Intent.ACTION_VIEW) Intent(intent)
                    else supportedIntent(context, intent) ?: continue
                context.startActivity(supported.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                EzchDiag.log("ROUTE", "нет экрана для ${intent.action} ${intent.`package`.orEmpty()}")
                Log.i("EZCH.Navigation", "Unsupported action: ${intent.action}")
            } catch (e: SecurityException) {
                EzchDiag.log("ROUTE:ОШИБКА", "запрещено ${intent.action}: ${e.message.orEmpty().take(200)}")
                Log.w("EZCH.Navigation", "Action denied: ${intent.action}", e)
            } catch (e: RuntimeException) {
                Log.w("EZCH.Navigation", "Settings route failed: ${intent.action}", e)
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
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.android.tv.settings"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.google.android.tv.settings"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent("android.settings.CONNECTED_DEVICE_SETTINGS"),
            Intent("com.android.tv.settings.CONNECTED_DEVICES"),
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
            Intent(Settings.ACTION_SETTINGS)
        )
        return open(context, candidates)
    }
    fun accessibility(context: Context): Boolean {
        Toast.makeText(context, "Подтвердите включение службы EZCH для кнопки Home в системном окне Android.", Toast.LENGTH_LONG).show()
        val service = android.content.ComponentName(context, HomeButtonService::class.java).flattenToString()
        return open(context, listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME", service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)
        ))
    }
    fun appDetails(context: Context): Boolean = open(context, listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), Intent(Settings.ACTION_SETTINGS)
    ))
    fun installPermission(context: Context): Boolean = open(context, listOf(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS)
    ))
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
    /**
     * «Открой настройки» means the main settings screen. Without CLEAR_TASK Android resumes the old settings
     * task on whatever page was left open (R14 on API33: it came back on Accessibility).
     */
    fun settings(context: Context) = open(context, listOf(
        Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings"),
        Intent(Settings.ACTION_SETTINGS).setPackage("com.google.android.tv.settings"),
        Intent(Settings.ACTION_SETTINGS)
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) })
    fun source(context: Context, source: TvSourceEntry): Boolean = if (source.id.startsWith("manual-")) open(context, listOf(
        Intent("android.settings.TV_INPUT_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS)
    )) else open(context, listOf(
        Intent(Intent.ACTION_VIEW, if (source.kind == TvSourceKind.TV)
            TvContract.Channels.CONTENT_URI else TvContract.buildChannelUriForPassthroughInput(source.id))
    ))
}
