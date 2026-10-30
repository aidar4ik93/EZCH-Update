package com.example.homeezch.ai

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** A pinned signed-APK asset, installed once atomically in private storage. No network or UI work. */
internal object OfflineModelStore {
    const val SHA256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
    private const val PREFIX = "vosk-model-small-ru-0.22/"
    @Synchronized fun ensureInstalled(context: Context, cancelled: () -> Boolean = { false }): File {
        val parent = File(context.filesDir, "eva-models").apply { mkdirs() }
        val destination = File(parent, "ru-0.22")
        val required = listOf("am/final.mdl", "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst")
        if (File(destination, ".ready").takeIf { it.isFile }?.readText() == SHA256 &&
            required.all { File(destination, it).isFile }) return destination
        check(StatFs(parent.path).availableBytes > 220L * 1024 * 1024) { "Для русской офлайн-модели нужно 220 МБ свободного места" }
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("eva-ru-0.22.zip").use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                check(!cancelled()) { "Загрузка модели отменена" }
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Контрольная сумма офлайн-модели не совпадает" }
        val staging = File(parent, ".ru-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L; var entries = 0
            ZipInputStream(context.assets.open("eva-ru-0.22.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!cancelled()) { "Загрузка модели отменена" }
                    check(++entries <= 1000 && entry.name.startsWith(PREFIX)) { "Некорректный архив модели" }
                    val relative = entry.name.removePrefix(PREFIX)
                    if (relative.isBlank()) continue
                    val target = File(staging, relative).canonicalFile
                    check(target.path.startsWith(staging.canonicalPath + File.separator)) { "Недопустимый путь модели" }
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled()) { "Загрузка модели отменена" }
                            val count = zip.read(buffer); if (count < 0) break
                            total += count
                            check(total <= 180L * 1024 * 1024) { "Модель превышает допустимый размер" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(required.all { File(staging, it).isFile }) { "Архив модели неполон" }
            check(!cancelled()) { "Загрузка модели отменена" }
            File(staging, ".ready").writeText(SHA256)
            if (destination.exists()) check(destination.deleteRecursively()) { "Не удалось заменить повреждённую модель" }
            check(staging.renameTo(destination)) { "Не удалось сохранить модель" }
            return destination
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }
}
