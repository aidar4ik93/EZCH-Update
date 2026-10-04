package com.example.homeezch

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

internal object FileOperations {
    fun checkedName(name: String): String {
        require(name.isNotBlank() && name.length <= 200 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 }) { "Недопустимое имя файла" }
        return name
    }
    /** Copy only regular files. Existing content is never overwritten. */
    fun copy(context: Context, source: DocumentFile, folder: DocumentFile): DocumentFile {
        require(source.isFile && folder.isDirectory && source.exists()) { "Выберите файл и папку назначения" }
        val name = checkedName(source.name ?: error("Файл без имени"))
        require(folder.findFile(name) == null) { "В этой папке уже есть файл $name" }
        val temporaryName = "ezch-copy-${java.util.UUID.randomUUID()}"
        val target = folder.createFile(source.type ?: "application/octet-stream", temporaryName) ?: error("Нет доступа для записи")
        try {
            val expected = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val input = context.contentResolver.openInputStream(source.uri) ?: error("Не удалось открыть файл")
            input.use {
                val output = context.contentResolver.openOutputStream(target.uri, "w") ?: error("Не удалось создать файл")
                output.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = it.read(buffer); if (count < 0) break
                        size += count
                        check(size <= 1024L * 1024 * 1024) { "Файл превышает 1 ГБ" }
                        out.write(buffer, 0, count); expected.update(buffer, 0, count)
                    }
                }
            }
            val actual = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            context.contentResolver.openInputStream(target.uri)?.use { inputCheck ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = inputCheck.read(buffer); if (count < 0) break; copied += count; actual.update(buffer, 0, count) }
            } ?: error("Не удалось проверить копию")
            check(copied == size && actual.digest().contentEquals(expected.digest())) { "Копия записана не полностью" }
            check(folder.findFile(name) == null) { "В этой папке уже есть файл $name" }
            check(target.renameTo(name) && target.name == name) { "Не удалось сохранить исходное имя файла" }
            return target
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }
    fun move(context: Context, source: DocumentFile, folder: DocumentFile) {
        copy(context, source, folder)
        check(source.delete()) { "Копия готова; исходный файл удалить не удалось" }
    }
    fun rename(source: DocumentFile, name: String) {
        val safe = checkedName(name)
        require(source.parentFile?.findFile(safe)?.let { it.uri != source.uri } != true) { "Такое имя уже существует" }
        check(source.renameTo(safe)) { "Не удалось переименовать файл" }
    }
}
