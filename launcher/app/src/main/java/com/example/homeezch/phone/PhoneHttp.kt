package com.example.homeezch.phone

import java.io.InputStream
import java.net.InetAddress
import java.net.URLDecoder

/** Pure, JVM-tested HTTP pieces of the phone remote: request parsing, auth, address checks. */
data class PhoneRequest(val method: String, val path: String, val query: Map<String, String>, val body: String)

object PhoneHttp {
    const val MAX_BODY = 4096
    private const val MAX_HEADER = 8192

    /** Reads one HTTP/1.1 request. Null for malformed, oversized or non-GET/POST requests. */
    fun parse(input: InputStream): PhoneRequest? {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.length > MAX_HEADER) return null
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        val lines = head.toString().split("\r\n", "\n").filter { it.isNotEmpty() }
        val request = lines.firstOrNull()?.split(' ') ?: return null
        if (request.size < 2) return null
        val method = request[0].uppercase()
        if (method != "GET" && method != "POST") return null
        val target = request[1]
        val length = lines.drop(1).firstOrNull { it.lowercase().startsWith("content-length:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return null; read += n }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }
        return PhoneRequest(method, path, query, String(body, Charsets.UTF_8))
    }
    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")

    /** Constant-time comparison of the pairing key. */
    fun authorized(request: PhoneRequest, key: String): Boolean {
        val given = request.query["k"] ?: return false
        if (given.length != key.length || key.isEmpty()) return false
        var diff = 0
        for (i in key.indices) diff = diff or (given[i].code xor key[i].code)
        return diff == 0
    }

    /** Only the home network (and the device itself) may talk to the TV. */
    fun homeNetwork(address: InetAddress?): Boolean =
        address != null && (address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress)

    fun response(status: Int, contentType: String, body: ByteArray): ByteArray {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 429 to "Too Many Requests")[status] ?: "OK"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }
    fun text(status: Int, text: String) = response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    /** Accepts only web links for «открыть на ТВ». */
    fun safeLink(value: String): String? {
        val v = value.trim()
        if (v.length > 2000 || v.any { it.isWhitespace() }) return null
        val lower = v.lowercase()
        return v.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
    }
}

/** Sliding one-second window; protects the TV from a flood of requests. */
class RateLimiter(private val perSecond: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val stamps = ArrayDeque<Long>()
    @Synchronized fun allow(): Boolean {
        val now = clock()
        while (stamps.isNotEmpty() && now - stamps.first() >= 1000) stamps.removeFirst()
        if (stamps.size >= perSecond) return false
        stamps.addLast(now); return true
    }
}
