package com.example.ezchupdate.install

import com.example.ezchupdate.data.RemoteApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import java.util.concurrent.ConcurrentHashMap

enum class InstallStage { RESOLVING, DOWNLOADING, VERIFYING, PREPARING }

data class InstallProgress(
    val stage: InstallStage,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null
)

internal class ApkDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    suspend fun download(directory: File, app: RemoteApp, progress: (InstallProgress) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw InstallException("Не удалось создать папку загрузки")
        // Never reuse a partial file, even when multiple attempts target the same app.
        val file = synchronized(liveDownloads) {
            cleanupInterrupted(directory)
            // Retain the .apk suffix for Android/OEM archive parsers.
            File.createTempFile("apk-", ".part.apk", directory).also { liveDownloads.add(it.absolutePath) }
        }
        try {
            app.sizeBytes?.let { DownloadPolicy.checkSize(it, DownloadPolicy.MAX_APK_BYTES) }
            progress(InstallProgress(InstallStage.RESOLVING))
            val url = try { withTimeout(60_000) { resolve(app) } } catch (timeout: TimeoutCancellationException) {
                throw InstallException("Не удалось получить ссылку на APK за минуту. Повторите загрузку", timeout)
            }
            withTimeout(20 * 60 * 1000L) { request(url) { connection ->
                val contentType = connection.contentType.orEmpty().lowercase()
                if (contentType.startsWith("text/") || contentType.contains("json")) {
                    throw InstallException("Источник вернул страницу вместо APK. Проверьте файл в каталоге")
                }
                val length = connection.contentLengthLong.takeIf { it >= 0 }
                if (length != null) DownloadPolicy.checkSize(length, DownloadPolicy.MAX_APK_BYTES, app.sizeBytes)
                val total = length ?: app.sizeBytes
                if (total != null && directory.usableSpace < total * 2 + STORAGE_RESERVE) {
                    throw InstallException("Недостаточно памяти для загрузки и установки. Освободите место на устройстве")
                }
                progress(InstallProgress(InstallStage.DOWNLOADING, 0, total))
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastReportTime = 0L
                var lastSpaceCheckBytes = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            received += count
                            if (received > DownloadPolicy.MAX_APK_BYTES || (total != null && received > total)) {
                                throw InstallException("Размер загружаемого APK не соответствует каталогу или ответу сервера")
                            }
                            if (received - lastSpaceCheckBytes >= 256 * 1024) {
                                if (directory.usableSpace < STORAGE_RESERVE) {
                                    throw InstallException("Закончилась свободная память. Освободите место и повторите установку")
                                }
                                lastSpaceCheckBytes = received
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastReportTime >= 100_000_000L) {
                                progress(InstallProgress(InstallStage.DOWNLOADING, received, total))
                                lastReportTime = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                DownloadPolicy.checkSize(received, DownloadPolicy.MAX_APK_BYTES, total)
                DownloadPolicy.checkDigest(app.sha256, digest.digest().joinToString("") { "%02x".format(it) })
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    val count = input.read(header)
                    DownloadPolicy.checkApkHeader(header.copyOf(count.coerceAtLeast(0)))
                }
                progress(InstallProgress(InstallStage.DOWNLOADING, received, total))
            } }
            return file
        } catch (timeout: TimeoutCancellationException) {
            release(file)
            throw InstallException("Загрузка не завершилась за 20 минут. Проверьте интернет и повторите установку", timeout)
        } catch (cancelled: CancellationException) {
            release(file)
            throw cancelled
        } catch (error: Exception) {
            release(file)
            currentCoroutineContext().ensureActive()
            throw usefulError(error)
        }
    }

    private suspend fun resolve(app: RemoteApp): URL {
        val source = DownloadPolicy.secureUrl(app.apkUrl)
        if (source.host.lowercase() !in setOf("disk.yandex.ru", "yadi.sk", "disk.yandex.com")) return source
        // Public-folder download uses the exact path, never an HTML share page.
        if (app.apkPath.isBlank()) throw InstallException("В каталоге не указан путь APK в общей папке Яндекс Диска")
        val key = URLEncoder.encode(app.apkUrl, "UTF-8")
        val path = URLEncoder.encode(app.apkPath, "UTF-8")
        val api = DownloadPolicy.secureUrl("https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=$key&path=$path")
        return request(api) { connection ->
            val payload = connection.inputStream.use { readBounded(it, DownloadPolicy.MAX_METADATA_BYTES) }
            val href = try { JSONObject(payload.toString(Charsets.UTF_8)).getString("href") } catch (error: Exception) {
                throw InstallException("Яндекс Диск не вернул ссылку на APK. Проверьте доступность общей папки", error)
            }
            DownloadPolicy.secureUrl(href)
        }
    }

    private suspend fun <T> request(initial: URL, consume: suspend (HttpURLConnection) -> T): T {
        var url = initial
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = openConnection(url).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "EZCH-Update/2 Android")
            }
            // Close a blocking socket when the parent job is cancelled, including during read().
            val response = coroutineScope {
                val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { connection.disconnect() }
                }
                try {
                    val status = connection.responseCode
                    when (status) {
                        301, 302, 303, 307, 308 -> Redirect(DownloadPolicy.redirect(url, connection.getHeaderField("Location"), redirects++))
                        200 -> Body(consume(connection))
                        404 -> throw InstallException("APK не найден. Обновите каталог или проверьте файл в общей папке")
                        403, 401 -> throw InstallException("Источник закрыл доступ к APK. Проверьте публичную ссылку")
                        429 -> throw InstallException("Источник временно ограничил загрузки. Повторите позже")
                        in 500..599 -> throw InstallException("Сервер загрузки недоступен (HTTP $status). Повторите позже")
                        else -> throw InstallException("Не удалось загрузить файл (HTTP $status)")
                    }
                } finally {
                    cancellationCloser.cancel()
                    connection.disconnect()
                }
            }
            when (response) {
                is Redirect -> url = response.url
                is Body<*> -> @Suppress("UNCHECKED_CAST") return response.value as T
            }
        }
    }

    private suspend fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > maximum) throw InstallException("Ответ сервера метаданных слишком большой")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun usefulError(error: Exception): Exception = when (error) {
        is InstallException -> error
        is SocketTimeoutException -> InstallException("Источник не ответил вовремя. Проверьте интернет и повторите загрузку", error)
        is UnknownHostException -> InstallException("Не удалось подключиться к источнику. Проверьте интернет", error)
        is SSLException -> InstallException("Не удалось проверить защищённое соединение. Проверьте дату устройства и интернет", error)
        else -> InstallException("Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}", error)
    }

    private data class Redirect(val url: URL)
    private data class Body<T>(val value: T)
    companion object {
        private const val STORAGE_RESERVE = 16L * 1024L * 1024L
        private val liveDownloads = ConcurrentHashMap.newKeySet<String>()

        fun release(file: File) {
            file.delete()
            liveDownloads.remove(file.absolutePath)
        }

        fun cleanupInterrupted(directory: File) = synchronized(liveDownloads) {
            directory.listFiles()?.filter { it.name.startsWith("apk-") && (it.extension == "part" || it.name.endsWith(".part.apk")) &&
                it.absolutePath !in liveDownloads }?.forEach { it.delete() }
        }
    }
}
