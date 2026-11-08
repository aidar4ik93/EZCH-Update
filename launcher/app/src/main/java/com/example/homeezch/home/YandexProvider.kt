package com.example.homeezch.home

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** QR login uses the EZCH broker; device discovery and commands go directly to Yandex. */
object YandexProvider : SmartProvider {
    override val id = "yandex"
    override val title = "Яндекс · Дом с Алисой"
    private fun prefs(c: Context) = c.getSharedPreferences("ezch_yandex_home", Context.MODE_PRIVATE)
    private val api by lazy { YandexSignIn(smartHttp) }
    private val refreshLock = Mutex()
    private val generation = AtomicLong()
    private fun token(c: Context) = prefs(c).getString("token", "").orEmpty()
    override fun connected(context: Context) = token(context).length >= 20
    internal fun observeConnection(c: Context, onChange: () -> Unit): () -> Unit {
        val p = prefs(c)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "token") onChange()
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onChange()
        return { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Synchronized fun disconnect(c: Context) {
        generation.incrementAndGet()
        prefs(c).edit().remove("token").remove("refresh_ticket").remove("token_until")
            .remove("client_id").remove("client_secret").apply()
    }
    internal suspend fun startSignIn(c: Context): YandexPairing = api.start(deviceId(c))
    internal suspend fun poll(pair: YandexPairing) = api.poll(pair)
    internal suspend fun cancel(pair: YandexPairing) = api.cancel(pair)
    @Synchronized internal fun accept(c: Context, grant: YandexGrant) {
        generation.incrementAndGet()
        save(c, grant)
    }
    private fun save(c: Context, grant: YandexGrant) {
        prefs(c).edit().putString("token", grant.accessToken).putString("refresh_ticket", grant.refreshTicket)
            .putLong("token_until", System.currentTimeMillis() + grant.expiresIn * 1000L)
            .remove("client_id").remove("client_secret").apply()
    }
    @Synchronized private fun deviceId(c: Context): String = prefs(c).getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs(c).edit().putString("device_id", it).apply() }

    private suspend fun validToken(c: Context, rejected: String? = null): String? = refreshLock.withLock {
        val existing = token(c)
        if (existing.isBlank()) return@withLock null
        // Another request may already have refreshed the rejected token.
        if (rejected != null && existing != rejected) return@withLock existing
        if (rejected == null && prefs(c).getLong("token_until", Long.MAX_VALUE) > System.currentTimeMillis() + 60000) return@withLock existing
        val ticket = prefs(c).getString("refresh_ticket", null)
        if (ticket.isNullOrBlank()) {
            if (rejected != null) disconnect(c)
            return@withLock if (rejected == null) existing else null
        }
        val revision = generation.get()
        try {
            val grant = api.refresh(ticket)
            coroutineContext.ensureActive()
            synchronized(this) {
                if (generation.get() != revision) return@withLock null
                save(c, grant)
            }
            grant.accessToken
        } catch (e: CancellationException) { throw e }
        catch (e: YandexAuthError) {
            synchronized(this) { if (!e.retryable && generation.get() == revision) disconnect(c) }
            null
        }
    }
    private suspend fun request(c: Context, builder: Request.Builder): Pair<Int, JSONObject?>? {
        var bearer = validToken(c) ?: return null
        repeat(2) { attempt ->
            coroutineContext.ensureActive()
            val result = smartHttp.newCall(builder.header("Authorization", "Bearer $bearer").build()).execute().use { r ->
                r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
            }
            if (result.first != 401) return result
            if (attempt == 0) bearer = validToken(c, bearer) ?: return null
            else synchronized(this) { if (token(c) == bearer) disconnect(c) }
        }
        return null
    }
    override suspend fun devices(context: Context): List<SmartDevice>? = withContext(Dispatchers.IO) {
        try {
            val result = request(context, Request.Builder().url("https://api.iot.yandex.net/v1.0/user/info").get()) ?: return@withContext null
            if (result.first in 200..299) result.second?.let(YandexMapper::parse) else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    override suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = "application/json; charset=utf-8".toMediaType()
            val builder = if (device.id.startsWith("scenario:"))
                Request.Builder().url("https://api.iot.yandex.net/v1.0/scenarios/${device.id.removePrefix("scenario:")}/actions").post("".toRequestBody(json))
            else Request.Builder().url("https://api.iot.yandex.net/v1.0/devices/actions")
                .post((YandexMapper.action(device, command) ?: return@withContext false).toString().toRequestBody(json))
            val result = request(context, builder) ?: return@withContext false
            if (result.first !in 200..299) return@withContext false
            val o = result.second ?: return@withContext false
            val caps = o.optJSONArray("devices")?.optJSONObject(0)?.optJSONArray("capabilities")
            caps == null || (0 until caps.length()).all { caps.optJSONObject(it)?.optJSONObject("state")?.optJSONObject("action_result")?.optString("status", "DONE") == "DONE" }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
}
