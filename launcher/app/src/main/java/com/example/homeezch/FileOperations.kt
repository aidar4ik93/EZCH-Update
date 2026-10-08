package com.example.homeezch
import java.io.File
internal object FileOperations {
    fun child(parent: File, name: String): File {
        require(name.isNotBlank() && name !in listOf(".","..") && '/' !in name && '\\' !in name) { "Недопустимое имя" }
        return File(parent,name).also { require(it.canonicalFile.parentFile == parent.canonicalFile) { "Недопустимый путь" } }
    }
    fun validateTree(source: File) {
        require(source.walkTopDown().none { java.nio.file.Files.isSymbolicLink(it.toPath()) }) { "Операции с символическими ссылками не поддерживаются" }
    }
    fun delete(source: File) { validateTree(source); check(source.deleteRecursively()) { "Не удалось удалить" } }
    fun rename(source: File, name: String) {
        val target = child(requireNotNull(source.parentFile),name)
        require(!target.exists()) { "Файл с таким именем уже существует" }
        check(source.renameTo(target)) { "Не удалось переименовать" }
    }
    fun copy(source: File, directory: File, move: Boolean) {
        require(source.exists() && directory.isDirectory) { "Источник или папка недоступны" }
        validateTree(source)
        val src=source.canonicalFile;val target=child(directory,source.name).canonicalFile
        require(src != target && !target.toPath().startsWith(src.toPath())) { "Нельзя копировать папку в себя" }
        require(!target.exists()) { "Файл с таким именем уже существует" }
        try {
            if (src.isDirectory) check(src.copyRecursively(target,false)) { "Не удалось скопировать папку" }
            else src.copyTo(target,false)
        } catch(e: Exception) { target.deleteRecursively();throw e }
        // Verify every copied byte before a move is allowed to remove the original.
        try {
            src.walkTopDown().filter { it.isFile }.forEach { original ->
                val copied = if (src.isDirectory) File(target, original.relativeTo(src).path) else target
                check(original.length() == copied.length() && digest(original).contentEquals(digest(copied))) { "Проверка копии не пройдена" }
            }
        } catch (e: Exception) { target.deleteRecursively(); throw e }
        if(move) check(src.deleteRecursively()) { "Копия создана, но оригинал удалить не удалось" }
    }
    private fun digest(file: File): ByteArray {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest()
    }
}
