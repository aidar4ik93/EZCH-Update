package com.example.homeezch.home

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YandexSignInTest {
    @Test fun realClientSendsOnlyDeviceIdThenSessionBearer() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val buffer = Buffer(); request.body!!.writeTo(buffer); bodies += JSONObject(buffer.readUtf8())
            val json = when (request.url.encodedPath.substringAfterLast('/')) {
                "start" -> JSONObject().put("session", "s".repeat(43)).put("userCode", "ABCD-EFGH")
                    .put("verificationUrl", "https://oauth.yandex.ru/device").put("interval", 5).put("expiresIn", 300).toString()
                "poll" -> """{"status":"pending","retryAfter":10}"""
                "cancel" -> """{"status":"cancelled"}"""
                else -> """{"status":"authorized","accessToken":"${"t".repeat(48)}","expiresIn":3600,"refreshTicket":null}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody()).build()
        }.build()
        val api = YandexSignIn(client)
        val pair = api.start("device-1234")
        assertNull(api.poll(pair).grant)
        api.cancel(pair)
        api.refresh("encrypted-ticket")
        assertEquals(setOf("deviceId"), bodies[0].keys().asSequence().toSet())
        assertNull(requests[0].header("Authorization"))
        assertEquals("Bearer " + "s".repeat(43), requests[1].header("Authorization"))
        assertEquals("Bearer " + "s".repeat(43), requests[2].header("Authorization"))
        assertEquals(0, bodies[1].length())
        assertEquals(setOf("refreshTicket"), bodies[3].keys().asSequence().toSet())
        assertTrue(requests.all { it.url.isHttps })
        client.dispatcher.executorService.shutdown()
    }
    @Test fun upstreamBodyIsNeverShownToUser() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Error")
                .body("""{"error":"INTERNAL_SECRET_FROM_UPSTREAM"}""".toResponseBody()).build()
        }).build()
        val error = runCatching { YandexSignIn(client).start("device-1234") }.exceptionOrNull()
        assertTrue(error is YandexAuthError)
        assertTrue((error as YandexAuthError).retryable)
        assertFalse(error.message!!.contains("INTERNAL_SECRET"))
        client.dispatcher.executorService.shutdown()
    }
}
