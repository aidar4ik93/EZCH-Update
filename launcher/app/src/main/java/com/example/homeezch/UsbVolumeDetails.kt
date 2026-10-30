package com.example.homeezch

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageVolume
import java.io.File
import java.util.Locale

internal data class UsbVolumeDetails(val volume: StorageVolume, val name: String, val capacity: String)

internal fun capacityLabel(total: Long, available: Long): String {
    if (total <= 0 || available < 0 || available > total) return "Объём недоступен"
    fun gib(bytes: Long) = String.format(Locale.forLanguageTag("ru"), "%.1f", bytes / 1073741824.0)
    return "Свободно ${gib(available)} из ${gib(total)} ГБ"
}

/** Uses public paths only; a storage description does not imply permission to read it. */
internal fun usbVolumeDetails(context: Context, volume: StorageVolume): UsbVolumeDetails {
    val name = runCatching { volume.getDescription(context) }.getOrDefault("Накопитель")
    val capacity = runCatching {
        val path = if (Build.VERSION.SDK_INT >= 30) volume.directory else {
            val uuid = volume.uuid
            if (uuid == null) null else context.getExternalFilesDirs(null).filterNotNull()
                .map { File(it.path.substringBefore("/Android/")) }
                .firstOrNull { it.name.equals(uuid, ignoreCase = true) }
        }
        if (path == null || !path.canRead()) "Объём недоступен — разрешите доступ к файлам"
        else StatFs(path.path).let { capacityLabel(it.totalBytes, it.availableBytes) }
    }.getOrDefault("Объём недоступен")
    return UsbVolumeDetails(volume, name, capacity)
}
