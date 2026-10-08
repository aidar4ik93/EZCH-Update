package com.example.homeezch.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Public Drive downloads; no Google account, API key or browser required on TV. */
internal object GoogleDriveDownload {
    private val fileId = Regex("[A-Za-z0-9_-]{10,}")
    internal fun normalize(source: String): String {
        val uri = URI(source)
        if (uri.host != "drive.google.com") return source
        val id = Regex("/file/d/([A-Za-z0-9_-]+)").find(uri.path)?.groupValues?.get(1)
            ?: uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }?.substringAfter('=')
            ?: error("В каталоге нужна ссылка на APK Google Drive, а не на папку")
        require(fileId.matches(id)) { "Некорректный идентификатор APK Google Drive" }
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
    }

    fun open(source: String): HttpURLConnection {
        val url = normalize(source)
        var connection = HttpsConnection.open(url, 60_000)
        if (URI(url).host != "drive.usercontent.google.com") return connection
        if (connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
            val page = try {
                connection.inputStream.bufferedReader().use { reader ->
                    val page = StringBuilder()
                    val buffer = CharArray(2048)
                    while (page.length <= 32 * 1024) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        page.append(buffer, 0, count)
                    }
                    check(page.length <= 32 * 1024) { "Google Drive вернул слишком большую страницу вместо APK" }
                    page.toString()
                }
            } finally { connection.disconnect() }
            connection = HttpsConnection.open(confirmationUrl(page, url), 60_000)
            if (connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                connection.disconnect()
                error("Google Drive не выдал APK. Проверьте доступ по ссылке или повторите позже: лимит скачиваний")
            }
        }
        return connection
    }

    internal fun confirmationUrl(page: String, original: String): String {
        fun attributes(tag: String) = Regex("([\\w-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(tag)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].replace("&amp;", "&") }
        val form = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(page).firstOrNull { attributes(it.groupValues[1])["id"] == "download-form" }
            ?: error("Google Drive не выдал APK. Проверьте доступ по ссылке или повторите позже: лимит скачиваний")
        val props = attributes(form.groupValues[1])
        val action = URI(props["action"] ?: "")
        check(action.scheme == "https" && action.host == "drive.usercontent.google.com" && action.path == "/download" &&
            action.userInfo == null && action.port == -1 && props["method"].orEmpty().equals("get", true)) {
            "Некорректное подтверждение загрузки Google Drive"
        }
        val fields = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(form.groupValues[2])
            .map { attributes(it.value) }.filter { it["type"].orEmpty().equals("hidden", true) }
            .mapNotNull { attrs -> attrs["name"]?.let { it to attrs["value"].orEmpty() } }.toList()
        val values = fields.toMap()
        val expectedId = URI(original).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
        check(fields.size == values.size && values["id"] == expectedId && expectedId != null &&
            values["export"] == "download" && values["confirm"] == "t" &&
            values.keys.all { it in setOf("id", "export", "confirm", "uuid", "resourcekey") }) {
            "Подтверждение Google Drive не соответствует выбранному APK"
        }
        return action.toString() + "?" + fields.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
        }
    }
}
