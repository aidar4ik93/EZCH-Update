package com.example.homeezch.home

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YandexAuthProtocolTest {
    private fun pair() = JSONObject().put("session", "s".repeat(43)).put("userCode", "ABCD-EFGH")
        .put("verificationUrl", "https://oauth.yandex.ru/device").put("interval", 5).put("expiresIn", 300)
    private fun grant() = JSONObject().put("status", "authorized").put("accessToken", "t".repeat(48))
        .put("expiresIn", 3600).put("refreshTicket", "a".repeat(32) + "." + "b".repeat(96))
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun readsCodeWithoutClientCredentials() {
        val result = YandexAuthProtocol.pairing(pair())
        assertEquals("ABCD-EFGH", result.userCode)
        assertEquals(5, result.interval)
        assertEquals(300, result.expiresIn)
    }
    @Test fun qrCanOnlyOpenOfficialDevicePage() {
        listOf("http://oauth.yandex.ru/device", "https://oauth.yandex.ru.evil.com/device", "https://user:pass@oauth.yandex.ru/device",
            "https://oauth.yandex.ru:444/device", "https://oauth.yandex.ru/token", "https://oauth.yandex.ru/device?code=secret",
            "https://oauth.yandex.ru/device#token").forEach { url -> rejects { YandexAuthProtocol.pairing(pair().put("verificationUrl", url)) } }
    }
    @Test fun rejectsBadPairingAndPollingBounds() {
        listOf("interval" to 0, "interval" to -5, "expiresIn" to 0, "expiresIn" to 999999).forEach { (key, value) ->
            rejects { YandexAuthProtocol.pairing(pair().put(key, value)) }
        }
        rejects { YandexAuthProtocol.pairing(pair().put("session", "not-a-session")) }
        rejects { YandexAuthProtocol.pairing(pair().put("userCode", "<script>")) }
        rejects { YandexAuthProtocol.poll(JSONObject().put("status", "pending").put("retryAfter", 0)) }
        rejects { YandexAuthProtocol.poll(JSONObject().put("status", "unknown")) }
    }
    @Test fun pendingWaitAndGrantAreDistinct() {
        val pending = YandexAuthProtocol.poll(JSONObject().put("status", "pending").put("retryAfter", 10))
        assertNull(pending.grant); assertEquals(10, pending.retryAfter)
        assertEquals("t".repeat(48), YandexAuthProtocol.poll(grant()).grant!!.accessToken)
        assertNull(YandexAuthProtocol.grant(grant().put("refreshTicket", JSONObject.NULL)).refreshTicket)
    }
    @Test fun rejectsInvalidCredentialsAndRefreshEnvelopes() {
        rejects { YandexAuthProtocol.grant(grant().put("accessToken", "tiny")) }
        rejects { YandexAuthProtocol.grant(grant().put("accessToken", "t".repeat(40) + "\n")) }
        rejects { YandexAuthProtocol.grant(grant().put("expiresIn", -1)) }
        rejects { YandexAuthProtocol.grant(grant().put("refreshTicket", "plaintext-token")) }
    }
    @Test fun networkRetriesButDeniedAndExpiredStop() {
        assertTrue(YandexAuthProtocol.failure(503, "secret raw upstream message").retryable)
        assertTrue(YandexAuthProtocol.failure(429, "").retryable)
        assertFalse(YandexAuthProtocol.failure(410, "denied").retryable)
        assertFalse(YandexAuthProtocol.failure(401, "sign_in_again").retryable)
        assertFalse(YandexAuthProtocol.failure(403, "missing_permissions").retryable)
        assertFalse(YandexAuthProtocol.failure(400, "secret raw upstream message").message!!.contains("secret"))
    }
}
