package com.example.ezchupdate.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException

data class RemoteApp(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkPath: String,
    val iconUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null
)

data class InstalledApp(val versionCode: Long, val versionName: String?)

enum class CatalogSource { NETWORK, CACHE, BUNDLED }

data class CatalogLoadResult(
    val apps: List<RemoteApp>,
    val source: CatalogSource,
    val warning: String? = null
)

/** Reject the complete document before publishing or caching any of its entries. */
object CatalogParser {
    const val MAX_BYTES = 1_048_576
    private const val MAX_APPS = 500
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun parse(source: String): List<RemoteApp> {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Каталог слишком большой" }
        val root = try {
            val tokens = JSONTokener(source.removePrefix("\uFEFF"))
            val value = tokens.nextValue() as? JSONObject
                ?: throw IllegalArgumentException("Корень каталога должен быть объектом")
            require(tokens.nextClean() == '\u0000') { "После каталога содержатся лишние данные" }
            value
        } catch (error: Exception) {
            throw IllegalArgumentException("Каталог содержит некорректный JSON", error)
        }
        val entries = root.optJSONArray("apps")
            ?: throw IllegalArgumentException("В каталоге отсутствует массив apps")
        require(entries.length() in 1..MAX_APPS) { "В каталоге должно быть от 1 до $MAX_APPS приложений" }
        val packages = mutableSetOf<String>()
        return List(entries.length()) { index ->
            val item = entries.optJSONObject(index)
                ?: throw IllegalArgumentException("Запись ${index + 1} в каталоге должна быть объектом")
            val label = "Запись ${index + 1}"
            val name = text(item, "name", label, 160)
            val packageName = text(item, "packageName", label, 255)
            require(packagePattern.matches(packageName)) { "$label: некорректный packageName" }
            require(packages.add(packageName)) { "$label: пакет $packageName повторяется" }
            val apkPath = text(item, "apkPath", label, 1024)
            require(apkPath.startsWith('/') && !apkPath.contains('\\') &&
                apkPath.split('/').none { it == ".." || it == "." }) { "$label: некорректный apkPath" }
            val sha256 = optionalText(item, "sha256", label, 64)?.also {
                require(Regex("^[a-fA-F0-9]{64}$").matches(it)) { "$label: некорректный SHA-256" }
            }
            RemoteApp(
                name = name,
                packageName = packageName,
                versionCode = positiveInteger(item, "versionCode", label),
                versionName = text(item, "versionName", label, 128),
                apkUrl = httpsUrl(text(item, "apkUrl", label, 4096), label, "apkUrl"),
                apkPath = apkPath,
                iconUrl = optionalText(item, "iconUrl", label, 4096)?.let { httpsUrl(it, label, "iconUrl") },
                sha256 = sha256?.lowercase(),
                sizeBytes = if (item.has("sizeBytes") && !item.isNull("sizeBytes")) {
                    positiveInteger(item, "sizeBytes", label)
                } else null
            )
        }
    }

    private fun text(item: JSONObject, key: String, label: String, maxLength: Int): String {
        val value = item.opt(key)
        require(value is String && value.isNotBlank() && value.length <= maxLength &&
            value == value.trim() && value.none { it.isISOControl() }) { "$label: некорректное поле $key" }
        return value
    }

    private fun optionalText(item: JSONObject, key: String, label: String, maxLength: Int): String? =
        if (!item.has(key) || item.isNull(key)) null else text(item, key, label, maxLength)

    private fun positiveInteger(item: JSONObject, key: String, label: String): Long {
        val value = item.opt(key)
        require(value is Number && value !is Float && value !is Double) { "$label: $key должен быть целым числом" }
        return value.toString().toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("$label: $key должен быть положительным целым числом")
    }

    fun httpsUrl(value: String, label: String = "Каталог", key: String = "URL"): String {
        val uri = try { URI(value) } catch (_: Exception) { null }
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            value.none { it.isWhitespace() || it.isISOControl() }) { "$label: $key должен быть HTTPS-ссылкой" }
        return value
    }
}

/** Injectable source selection lets tests verify offline behavior without Android or a server. */
class CatalogLoader(
    private val remote: () -> String,
    private val cached: () -> String?,
    private val bundled: () -> String?,
    private val cacheWriter: (String) -> Unit
) {
    fun load(): CatalogLoadResult {
        try {
            val source = remote()
            val apps = CatalogParser.parse(source)
            val cacheError = runCatching { cacheWriter(source) }.exceptionOrNull()
            return CatalogLoadResult(apps, CatalogSource.NETWORK,
                if (cacheError == null) null else "Каталог обновлён, но не удалось сохранить его для работы без сети")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (networkError: Exception) {
            for ((reader, source) in listOf(cached to CatalogSource.CACHE, bundled to CatalogSource.BUNDLED)) {
                val apps = runCatching { reader()?.let(CatalogParser::parse) }.getOrNull() ?: continue
                return CatalogLoadResult(apps, source,
                    if (source == CatalogSource.CACHE) "Не удалось обновить каталог. Показан сохранённый каталог"
                    else "Не удалось обновить каталог. Показан каталог из приложения")
            }
            throw IllegalStateException("Не удалось загрузить каталог. Проверьте подключение к интернету", networkError)
        }
    }
}

class CatalogRepository(
    context: Context? = null,
    private val catalogUrl: String = "https://raw.githubusercontent.com/aidar4ik93/EZCH-Update/main/apps.json"
) {
    private val appContext = context?.applicationContext
    private val cacheFile = appContext?.let { AtomicFile(File(it.filesDir, "catalog-v1.json")) }
    private val loader = CatalogLoader(
        remote = { readText(catalogUrl) },
        cached = { cacheFile?.openRead()?.use(::readLimitedText) },
        bundled = { appContext?.assets?.open("apps.json")?.use(::readLimitedText) },
        cacheWriter = ::writeCache
    )

    fun load(): List<RemoteApp> = loadCatalog().apps
    fun loadCatalog(): CatalogLoadResult = loader.load()

    fun installedApp(packageManager: PackageManager, packageName: String): InstalledApp? = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        InstalledApp(packageInfo.versionCodeCompat(), packageInfo.versionName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun writeCache(source: String) {
        val file = cacheFile ?: return
        val stream = file.startWrite()
        try {
            stream.write(source.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun readText(initialUrl: String): String {
        var url = CatalogParser.httpsUrl(initialUrl)
        repeat(6) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (connection.responseCode) {
                    in 200..299 -> {
                        require(connection.contentLengthLong <= CatalogParser.MAX_BYTES) { "Каталог слишком большой" }
                        return connection.inputStream.use(::readLimitedText)
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: error("Пустое перенаправление каталога")
                        url = CatalogParser.httpsUrl(URL(URL(url), location).toExternalForm())
                    }
                    else -> error("Не удалось загрузить каталог: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Слишком много перенаправлений каталога")
    }

    private fun readLimitedText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= CatalogParser.MAX_BYTES) { "Каталог слишком большой" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
} else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
}
