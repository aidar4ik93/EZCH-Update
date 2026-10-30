package com.example.homeezch.phone

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PhoneRemoteTest {
    private fun req(raw: String) = PhoneHttp.parse(raw.byteInputStream(Charsets.UTF_8))

    @Test fun parsesPostWithBodyAndKey() {
        val body = "найди Интерстеллар"
        val bytes = body.toByteArray(Charsets.UTF_8).size
        val r = req("POST /api/say?k=abc123 HTTP/1.1\r\nHost: tv\r\nContent-Length: $bytes\r\n\r\n$body")!!
        assertEquals("POST", r.method); assertEquals("/api/say", r.path); assertEquals("abc123", r.query["k"]); assertEquals(body, r.body)
    }
    @Test fun rejectsOversizedAndUnknownMethods() {
        assertNull(req("POST /api/say?k=a HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"))
        assertNull(req("DELETE / HTTP/1.1\r\n\r\n"))
        assertNull(req("GET / HTTP/1.1\r\n" + "X: " + "a".repeat(9000) + "\r\n\r\n"))
    }
    @Test fun keyMustMatchExactly() {
        val key = "0123456789abcdef0123456789abcdef"
        assertTrue(PhoneHttp.authorized(req("GET /?k=$key HTTP/1.1\r\n\r\n")!!, key))
        assertFalse(PhoneHttp.authorized(req("GET /?k=${key.dropLast(1)}0 HTTP/1.1\r\n\r\n")!!, key))
        assertFalse(PhoneHttp.authorized(req("GET / HTTP/1.1\r\n\r\n")!!, key))
        assertFalse(PhoneHttp.authorized(req("GET /?k= HTTP/1.1\r\n\r\n")!!, ""))
    }
    @Test fun onlyHomeNetwork() {
        assertTrue(PhoneHttp.homeNetwork(InetAddress.getByName("192.168.1.20")))
        assertTrue(PhoneHttp.homeNetwork(InetAddress.getByName("10.0.0.5")))
        assertTrue(PhoneHttp.homeNetwork(InetAddress.getByName("127.0.0.1")))
        assertFalse(PhoneHttp.homeNetwork(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun linksAreWebOnly() {
        assertEquals("https://youtu.be/x", PhoneHttp.safeLink(" https://youtu.be/x "))
        assertNull(PhoneHttp.safeLink("intent://evil#Intent;end"))
        assertNull(PhoneHttp.safeLink("file:///sdcard/a"))
        assertNull(PhoneHttp.safeLink("javascript:alert(1)"))
    }
    @Test fun rateLimiter() {
        var now = 0L
        val limiter = RateLimiter(3) { now }
        assertTrue(limiter.allow()); assertTrue(limiter.allow()); assertTrue(limiter.allow()); assertFalse(limiter.allow())
        now = 1000; assertTrue(limiter.allow())
    }
    @Test fun qrStructure() {
        val q = QrCode.encode("http://192.168.1.82:8765/?k=0123456789abcdef0123456789abcdef")
        assertEquals(q.version * 4 + 17, q.size)
        // Finder pattern corners are dark, their separators light.
        for ((x, y) in listOf(0 to 0, q.size - 1 to 0, 0 to q.size - 1)) assertTrue(q.modules[y][x])
        assertFalse(q.modules[7][7]); assertTrue(q.modules[q.size - 8][8]) // separator, dark module
    }
}
