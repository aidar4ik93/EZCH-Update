package com.example.homeezch

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

internal enum class TvSourceKind { TV, HDMI, AV, USB, OTHER }

internal data class TvSourceEntry(
    val id: String,
    val label: String,
    val hint: String,
    val kind: TvSourceKind,
    val connected: Boolean = false,
    val detail: String? = null,
    val tunerAutoStartEligible: Boolean = false
)

internal data class TvSourcesSnapshot(
    val entries: List<TvSourceEntry>,
    val usbVolumes: List<StorageVolume>
)

internal fun scanTvSources(context: Context): TvSourcesSnapshot {
    val entries = mutableListOf<TvSourceEntry>()

    val manager =
        context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    val inputs: List<TvInputInfo> = runCatching {
        manager?.tvInputList.orEmpty()
    }.getOrDefault(emptyList())

    // Read each state once: separate calls can disagree during cable hot-plug.
    val roots = inputs.filter { it.parentId == null }.distinctBy { it.id }.sortedBy { it.id }
    val counters = mutableMapOf<TvSourceKind, Int>()
    roots.forEach { input ->
        val kind = when (input.type) {
            TvInputInfo.TYPE_TUNER -> TvSourceKind.TV
            TvInputInfo.TYPE_HDMI -> TvSourceKind.HDMI
            TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT -> TvSourceKind.AV
            else -> TvSourceKind.OTHER
        }
        val number = (counters[kind] ?: 0) + 1
        counters[kind] = number
        val systemLabel = runCatching { input.loadLabel(context).toString() }.getOrDefault("")
        val systemProvider = runCatching {
            input.serviceInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
        val softwareTv = kind == TvSourceKind.TV && !systemProvider
        val label = when (kind) {
            TvSourceKind.TV -> if (softwareTv) systemLabel.ifBlank { "ТВ-приложение" } else if (number == 1) "ТВ" else "ТВ $number"
            TvSourceKind.HDMI -> if (systemLabel.contains("HDMI", true)) systemLabel else "HDMI $number"
            TvSourceKind.AV -> if (number == 1) "AV" else "AV $number"
            else -> systemLabel.ifBlank { "Системный источник $number" }
        }
        val state = runCatching { manager?.getInputState(input.id) }.getOrNull()
        val connection = inputConnection(state)
        entries += TvSourceEntry(input.id, label, "Источник, объявленный системой", kind,
            connected = kind != TvSourceKind.TV && connection.first,
            detail = if (softwareTv) "ТВ-приложение · не физический тюнер"
                else if (kind == TvSourceKind.TV) "Статус ТВ-сигнала неизвестен" else connection.second,
            tunerAutoStartEligible = kind == TvSourceKind.TV && systemProvider)
    }

    val storage =
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    val volumes: List<StorageVolume> = runCatching {
        storage?.storageVolumes.orEmpty().filter {
            it.isRemovable && (
                it.state == Environment.MEDIA_MOUNTED ||
                it.state == Environment.MEDIA_MOUNTED_READ_ONLY
            )
        }
    }.getOrDefault(emptyList())

    if (volumes.isNotEmpty()) {
        entries += TvSourceEntry(
            "external-storage",
            "USB / носители ×${volumes.size}",
            "Подключённые внешние накопители; список может включать SD",
            TvSourceKind.USB,
            connected = true,
            detail = if (volumes.size == 1) volumes.first().getDescription(context)
                     else "${volumes.size} носителя"
        )
    }

    entries += SourceSettings.manual(context)
    if (entries.none { it.kind == TvSourceKind.USB }) {
        val devices = runCatching { (context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).deviceList.size }.getOrDefault(0)
        entries += TvSourceEntry("external-storage", "USB", "Файловый менеджер", TvSourceKind.USB,
            connected = false, detail = if (devices > 0) "USB-устройств: $devices · накопитель не смонтирован" else "Нет накопителя")
    }
    return TvSourcesSnapshot(entries.sortedBy { when (it.kind) { TvSourceKind.TV -> 0; TvSourceKind.HDMI -> 1; TvSourceKind.AV -> 2; TvSourceKind.USB -> 3; TvSourceKind.OTHER -> 4 } }, volumes)
}

/** Unknown firmware state never receives a confirmed connection glow. */
internal fun inputConnection(state: Int?): Pair<Boolean, String> = when (state) {
    TvInputManager.INPUT_STATE_CONNECTED -> true to "Подключено"
    TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> true to "Ожидание"
    TvInputManager.INPUT_STATE_DISCONNECTED -> false to "Не подключено"
    else -> false to "Статус неизвестен"
}
