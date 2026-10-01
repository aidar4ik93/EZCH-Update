package com.example.ezchupdate.install

import java.net.URI
import java.net.URL
import java.util.Locale

/** Rules shared by APK and metadata requests; redirects never weaken HTTPS. */
internal object DownloadPolicy {
    const val MAX_APK_BYTES = 1024L * 1024L * 1024L
    const val MAX_METADATA_BYTES = 128L * 1024L
    const val MAX_REDIRECTS = 5

    fun secureUrl(value: String): URL {
        val uri = try { URI(value) } catch (_: Exception) {
            throw InstallException("Некорректная ссылка на файл в каталоге")
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)
        ) throw InstallException("Для загрузки требуется прямая HTTPS-ссылка без логина и пароля")
        val host = uri.host.lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" ||
            host == "[::1]" || host == "0.0.0.0"
        ) throw InstallException("Ссылка на APK не должна вести на локальный адрес")
        return uri.toURL()
    }

    fun redirect(current: URL, location: String?, count: Int): URL {
        if (count >= MAX_REDIRECTS) throw InstallException("Слишком много перенаправлений при загрузке")
        if (location.isNullOrBlank()) throw InstallException("Сервер не указал адрес перенаправления")
        return secureUrl(current.toURI().resolve(location).toASCIIString())
    }

    fun checkSize(actual: Long, maximum: Long, expected: Long? = null) {
        if (actual <= 0) throw InstallException("Сервер вернул пустой файл")
        if (actual > maximum) throw InstallException("Файл превышает допустимый размер")
        if (expected != null && actual != expected) {
            throw InstallException("Размер APK не совпадает с каталогом. Обновите каталог и повторите загрузку")
        }
    }

    fun checkApkHeader(header: ByteArray) {
        if (header.size < 4 || header[0] != 0x50.toByte() || header[1] != 0x4b.toByte() ||
            header[2] != 0x03.toByte() || header[3] != 0x04.toByte()
        ) throw InstallException("По ссылке получен не APK, а страница или повреждённый файл")
    }

    fun checkDigest(expected: String?, actual: String) {
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            throw InstallException("Контрольная сумма APK не совпадает. Обновите каталог и повторите загрузку")
        }
    }

    fun compatibleSigners(installed: Set<String>, incoming: Set<String>, history: Set<String>): Boolean {
        if (installed.isEmpty() || incoming.isEmpty()) return false
        // Multiple signers cannot rotate; all current signers must match.
        if (installed.size > 1 || incoming.size > 1) return installed == incoming
        return installed == incoming || history.containsAll(installed)
    }
}

class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)
