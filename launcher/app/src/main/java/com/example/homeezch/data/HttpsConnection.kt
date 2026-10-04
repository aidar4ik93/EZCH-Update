package com.example.homeezch.data

import java.net.HttpURLConnection
import java.net.URL

/** Applies the same HTTPS and redirect rules to catalogs, metadata, and APKs. */
object HttpsConnection {
    fun open(source: String, readTimeoutMs: Int = 30_000): HttpURLConnection {
        var url = URL(source)
        repeat(6) {
            require(url.protocol == "https") { "Для загрузки требуется HTTPS-ссылка" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "EZCH-Update/1.5")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Сервер не указал адрес перенаправления")
                    url = URL(url, location)
                    connection.disconnect()
                } else {
                    check(status in 200..299) { "Сервер загрузки вернул HTTP $status" }
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        error("Слишком много перенаправлений при загрузке")
    }
}
