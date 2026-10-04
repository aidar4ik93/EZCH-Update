package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

internal enum class TvSourceKind { TV, HDMI, AV, USB }

internal data class TvSourceEntry(
    val id: String,
    val label: String,
    val hint: String,
    val kind: TvSourceKind,
    val connected: Boolean? = null,
    val inputId: String? = null
)

internal data class TvSourcesSnapshot(
    val entries: List<TvSourceEntry>,
    val usbVolumes: List<StorageVolume>
)

/** Only reported inputs and mounted removable volumes are discovered here. */
internal fun scanTvSources(context: Context): TvSourcesSnapshot {
    val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    val inputs = runCatching { manager?.tvInputList.orEmpty() }
        .getOrDefault(emptyList())
        .distinctBy { it.id }
        .filterNot { runCatching { it.isHidden(context) }.getOrDefault(false) }
        .sortedWith(compareBy<TvInputInfo> { sourceKind(it.type)?.ordinal ?: Int.MAX_VALUE }
            .thenBy { it.id })

    val entries = inputs.mapNotNull { input ->
        val kind = sourceKind(input.type) ?: return@mapNotNull null
        val state = runCatching { manager?.getInputState(input.id) }.getOrNull()
        val connected = sourceConnectedState(state)
        val label = runCatching { input.loadCustomLabel(context)?.toString() }
            .getOrNull().orEmpty().trim().ifEmpty {
                runCatching { input.loadLabel(context)?.toString() }
                    .getOrNull().orEmpty().trim()
            }.ifEmpty {
                when (kind) {
                    TvSourceKind.TV -> "ТВ"
                    TvSourceKind.HDMI -> "HDMI"
                    TvSourceKind.AV -> "AV"
                    TvSourceKind.USB -> "USB / носитель"
                }
            }
        val hint = when {
            state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ->
                "Подключено · режим ожидания"
            connected == true && kind == TvSourceKind.TV -> "ТВ-источник доступен"
            connected == true -> "Подключено"
            connected == false -> "Не подключено"
            else -> "Состояние недоступно"
        }
        TvSourceEntry(input.id, label, hint, kind, connected, input.id)
    }.toMutableList()

    val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    val volumes = runCatching {
        storage?.storageVolumes.orEmpty().filter {
            it.isRemovable && isMountedRemovableState(it.state)
        }
    }.getOrDefault(emptyList())

    val usbDevices = runCatching { (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.size }.getOrNull()
    val mounted = volumes.isNotEmpty()
    val usbConnected = if (mounted || (usbDevices ?: 0) > 0) true else if (usbDevices != null) false else null
    entries += TvSourceEntry("usb-group", "USB × 2",
        when { mounted -> "Носителей: ${volumes.size}"; usbConnected == true -> "Устройство подключено";
            usbConnected == false -> "Не подключено"; else -> "Состояние недоступно" }, TvSourceKind.USB, usbConnected)
    // TV firmware may hide its physical inputs from third-party launchers.
    // Reference-layout placeholders retain an explicitly unknown connection state.
    val placeholders = manualSourceEntries(if (entries.none { it.kind == TvSourceKind.HDMI }) 3 else 0,
        entries.none { it.kind == TvSourceKind.TV }, entries.none { it.kind == TvSourceKind.AV }, 0)
    entries.addAll(placeholders)
    entries.sortWith(compareBy<TvSourceEntry> { it.kind.ordinal }.thenBy { it.label })
    return TvSourcesSnapshot(entries, volumes)
}

internal fun observeTvSources(context: Context, changed: () -> Unit): () -> Unit {
    val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = changed()
        override fun onInputAdded(inputId: String) = changed()
        override fun onInputRemoved(inputId: String) = changed()
        override fun onInputUpdated(inputId: String) = changed()
    }
    runCatching { manager?.registerCallback(callback, Handler(Looper.getMainLooper())) }
    val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
    val volumeCallback = if (Build.VERSION.SDK_INT >= 30) object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) = changed()
    } else null
    if (Build.VERSION.SDK_INT >= 30 && volumeCallback != null) runCatching { storage?.registerStorageVolumeCallback(context.mainExecutor, volumeCallback) }
    val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) = changed() }
    val media = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addDataScheme("file")
    }
    val usb = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
    runCatching { ContextCompat.registerReceiver(context, receiver, media, ContextCompat.RECEIVER_NOT_EXPORTED) }
    runCatching { ContextCompat.registerReceiver(context, receiver, usb, ContextCompat.RECEIVER_NOT_EXPORTED) }
    return {
        runCatching { manager?.unregisterCallback(callback) }
        if (Build.VERSION.SDK_INT >= 30 && volumeCallback != null) runCatching { storage?.unregisterStorageVolumeCallback(volumeCallback) }
        runCatching { context.unregisterReceiver(receiver) }
    }
}

internal fun newlyConnected(previous: Map<String, Boolean?>?, current: List<TvSourceEntry>): Set<String> =
    if (previous == null) emptySet() else current.filter { it.connected == true && previous[it.id] != true }.map { it.id }.toSet()

private fun sourceKind(type: Int): TvSourceKind? = when (type) {
    TvInputInfo.TYPE_TUNER -> TvSourceKind.TV
    TvInputInfo.TYPE_HDMI -> TvSourceKind.HDMI
    TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT,
    TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART -> TvSourceKind.AV
    else -> null
}

internal fun sourceConnectedState(state: Int?): Boolean? = when (state) {
    TvInputManager.INPUT_STATE_CONNECTED, TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> true
    TvInputManager.INPUT_STATE_DISCONNECTED -> false
    else -> null
}

internal fun isMountedRemovableState(state: String): Boolean =
    state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

/** These are user-declared slots, never evidence that a device or input is connected. */
internal fun manualSourceEntries(
    hdmiCount: Int,
    hasTv: Boolean,
    hasAv: Boolean,
    usbSlots: Int
): List<TvSourceEntry> = buildList {
    val hint = "Задан вручную · состояние неизвестно"
    if (hasTv) add(TvSourceEntry("manual-tv", "ТВ", hint, TvSourceKind.TV))
    repeat(hdmiCount.coerceIn(0, 8)) { index ->
        add(TvSourceEntry("manual-hdmi-${index + 1}", "HDMI ${index + 1}", hint, TvSourceKind.HDMI))
    }
    if (hasAv) add(TvSourceEntry("manual-av", "AV", hint, TvSourceKind.AV))
    repeat(usbSlots.coerceIn(0, 8)) { index ->
        add(TvSourceEntry("manual-usb-${index + 1}", "USB ${index + 1}", hint, TvSourceKind.USB))
    }
}

/** USB is handled by the UI's document picker; false lets the UI explain unavailability. */
internal fun openTvSource(context: Context, source: TvSourceEntry): Boolean {
    if (source.kind == TvSourceKind.USB) return false
    val inputId = source.inputId?.takeIf { it.isNotBlank() }
    if (inputId != null) {
        val uri = when (source.kind) {
            TvSourceKind.HDMI, TvSourceKind.AV -> TvContract.buildChannelUriForPassthroughInput(inputId)
            TvSourceKind.TV -> TvContract.buildChannelsUriForInput(inputId)
            TvSourceKind.USB -> return false
        }
        if (startTvActivity(context, Intent(Intent.ACTION_VIEW, uri))) return true
    }
    // This vendor action is not a public Settings SDK constant. Some TVs implement it.
    return startTvActivity(context, Intent("android.settings.TV_INPUT_SETTINGS")) ||
        startTvActivity(context, Intent(TvInputManager.ACTION_SETUP_INPUTS))
}

private fun startTvActivity(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
