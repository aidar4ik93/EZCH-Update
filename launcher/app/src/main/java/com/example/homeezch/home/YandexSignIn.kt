package com.example.homeezch.home

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The app secret stays in the EZCH auth service. Pairing sessions are short-lived bearer capabilities. */
internal class YandexAuthError(val retryable: Boolean, message: String) : IOException(message)
internal data class YandexPairing(val session: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)
internal data class YandexGrant(val accessToken: String, val expiresIn: Long, val refreshTicket: String?)
internal data class YandexPoll(val grant: YandexGrant?, val retryAfter: Int)

internal object YandexAuthProtocol {
    fun pairing(o: JSONObject): YandexPairing {
        val session = o.getString("session")
        require(session.matches(Regex("[A-Za-z0-9_-]{43}")))
        val code = o.getString("userCode")
        require(code.matches(Regex("[A-Za-z0-9-]{4,20}")))
        val url = o.getString("verificationUrl")
        val u = URI(url)
        require(u.scheme == "https" && u.host in setOf("oauth.yandex.ru", "oauth.yandex.com", "ya.ru") &&
            u.path == "/device" && u.userInfo == null && u.port == -1 && u.fragment == null && u.rawQuery == null)
        return YandexPairing(session, code, url, seconds(o, "interval", 1, 3600), seconds(o, "expiresIn", 1, 3600))
    }
    fun grant(o: JSONObject): YandexGrant {
        require(o.getString("status") == "authorized")
        val token = o.getString("accessToken")
        require(token.length in 20..4096 && token.none { it.isWhitespace() || it.isISOControl() })
        val expiry = o.getLong("expiresIn")
        require(expiry in 1..315360000)
        val ticket = o.optString("refreshTicket").takeIf { it.isNotBlank() && it != "null" }
        require(ticket == null || (ticket.length in 20..16384 && ticket.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))))
        return YandexGrant(token, expiry, ticket)
    }
    fun poll(o: JSONObject): YandexPoll = when (o.getString("status")) {
        "authorized" -> YandexPoll(grant(o), 0)
        "pending" -> YandexPoll(null, seconds(o, "retryAfter", 1, 3600))
        else -> error("Invalid auth response")
    }
    private fun seconds(o: JSONObject, key: String, min: Int, max: Int): Int =
        o.getInt(key).also { require(it in min..max) }
    fun failure(status: Int, code: String): YandexAuthError = when {
        status == 429 -> YandexAuthError(true, "Слишком много попыток. Подождите немного.")
        status >= 500 -> YandexAuthError(true, "Сервис подключения временно недоступен. Попробуем ещё раз.")
        code == "missing_permissions" -> YandexAuthError(false, "Яндексу нужно разрешить просмотр и управление умным домом.")
        code == "denied" -> YandexAuthError(false, "Вход отменён в Яндексе. Можно получить новый код.")
        status == 410 -> YandexAuthError(false, "Срок действия кода истёк. Получите новый код.")
        status == 401 -> YandexAuthError(false, "Подключите Дом с Алисой заново.")
        else -> YandexAuthError(false, "Не удалось подключить Яндекс. Попробуйте получить новый код.")
    }
}

internal class YandexSignIn(private val client: OkHttpClient) {
    private val endpoint = "https://ezch-yandex-auth.ezch-aidar.workers.dev/api/yandex/"
    suspend fun start(deviceId: String) = YandexAuthProtocol.pairing(post("start", JSONObject().put("deviceId", deviceId)))
    suspend fun poll(pair: YandexPairing) = YandexAuthProtocol.poll(post("poll", JSONObject(), pair.session))
    suspend fun cancel(pair: YandexPairing) { post("cancel", JSONObject(), pair.session) }
    suspend fun refresh(ticket: String) = YandexAuthProtocol.grant(post("refresh", JSONObject().put("refreshTicket", ticket)))

    private suspend fun post(action: String, data: JSONObject, session: String? = null): JSONObject {
        val request = Request.Builder().url(endpoint + action)
            .post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { session?.let { header("Authorization", "Bearer $it") } }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(YandexAuthError(true, "Нет связи с сервисом подключения. Проверьте интернет."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use { r ->
                            val body = r.body ?: throw IOException()
                            val bytes = body.byteStream().use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) { val count = stream.read(buffer); if (count < 0) break
                                    if (out.size() + count > 65536) throw IOException()
                                    out.write(buffer, 0, count)
                                }
                                out.toString("UTF-8")
                            }
                            val obj = runCatching { JSONObject(bytes) }.getOrNull()
                            if (!r.isSuccessful) throw YandexAuthProtocol.failure(r.code, obj?.optString("error").orEmpty())
                            obj ?: throw IOException()
                        }
                        continuation.resume(data)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e as? YandexAuthError ?: YandexAuthError(true, "Сервис подключения не ответил. Попробуем ещё раз."))
                    }
                }
            })
        }
    }
}
