package com.example.homeezch

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

internal enum class TvSourceKind { TV, HDMI, AV, USB }

internal data class TvSourceEntry(
    val id: String,
    val label: String,
    val hint: String,
    val kind: TvSourceKind,
    val connected: Boolean = false,
    val detail: String? = null
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

    if (inputs.any { it.type == TvInputInfo.TYPE_TUNER }) {
        entries += TvSourceEntry(
            inputs.first { it.type == TvInputInfo.TYPE_TUNER }.id, "ТВ", "ТВ-источник, объявленный системой",
            TvSourceKind.TV,
            detail = "Тюнер доступен"
        )
    }

    // CEC children describe attached devices, not extra physical HDMI ports.
    inputs.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
        .distinctBy { it.id }
        .sortedBy { it.id }
        .forEachIndexed { index, input ->
            val systemLabel = runCatching {
                input.loadLabel(context).toString()
            }.getOrDefault("")

            entries += TvSourceEntry(
                input.id,
                if (systemLabel.contains("HDMI", true)) systemLabel
                else "HDMI ${index + 1}",
                "Обнаруженный системой HDMI-вход",
                TvSourceKind.HDMI,
                connected = runCatching {
                    manager?.getInputState(input.id) in listOf(TvInputManager.INPUT_STATE_CONNECTED, TvInputManager.INPUT_STATE_CONNECTED_STANDBY)
                }.getOrDefault(false),
                detail = runCatching {
                    if (manager?.getInputState(input.id) == TvInputManager.INPUT_STATE_CONNECTED)
                        "Устройство обнаружено" else when (manager?.getInputState(input.id)) {
                            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Устройство в ожидании"
                            TvInputManager.INPUT_STATE_DISCONNECTED -> "Нет подключения"
                            else -> "Состояние неизвестно"
                        }
                }.getOrDefault(null)
            )
        }

    inputs.filter {
        it.type == TvInputInfo.TYPE_COMPOSITE ||
        it.type == TvInputInfo.TYPE_COMPONENT
    }.distinctBy { it.id }.forEachIndexed { index, input ->
        entries += TvSourceEntry(
            input.id,
            if (index == 0) "AV" else "AV ${index + 1}",
            "Аналоговый видеоисточник",
            TvSourceKind.AV,
            connected = runCatching {
                manager?.getInputState(input.id) in listOf(TvInputManager.INPUT_STATE_CONNECTED, TvInputManager.INPUT_STATE_CONNECTED_STANDBY)
            }.getOrDefault(false),
            detail = runCatching { when (manager?.getInputState(input.id)) {
                TvInputManager.INPUT_STATE_CONNECTED -> "Подключено"
                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "Ожидание"
                TvInputManager.INPUT_STATE_DISCONNECTED -> "Не подключено"
                else -> "Статус неизвестен"
            } }.getOrDefault("Статус неизвестен")
        )
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
    return TvSourcesSnapshot(entries.sortedBy { when (it.kind) { TvSourceKind.TV -> 0; TvSourceKind.HDMI -> 1; TvSourceKind.AV -> 2; TvSourceKind.USB -> 3 } }, volumes)
}
