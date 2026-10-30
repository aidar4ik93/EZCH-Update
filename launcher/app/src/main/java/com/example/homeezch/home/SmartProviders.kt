package com.example.homeezch.home

import android.content.Context
import com.example.homeezch.ai.HomeAssistantClient
import com.example.homeezch.ai.SmartHomeConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

interface SmartProvider {
    val id: String
    val title: String
    fun connected(context: Context): Boolean
    suspend fun devices(context: Context): List<SmartDevice>?
    suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean
}

internal val smartHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).build()
}
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * «Дом с Алисой»: one sign-in brings every device linked there — Яндекс Пульт IR remotes, Яндекс lamps
 * and sockets, and also Xiaomi (Mi Home) and Tuya / Smart Life accounts linked inside the Яндекс app.
 * Sign-in is the OAuth device flow: the TV shows a short code, the owner confirms it on the phone.
 * No IP address, no token copying. Needs an OAuth app id (oauth.yandex.ru, scopes iot:view + iot:control).
 */
object YandexProvider : SmartProvider {
    override val id = "yandex"
    override val title = "Яндекс · Дом с Алисой"
    private fun prefs(c: Context) = c.getSharedPreferences("ezch_yandex_home", Context.MODE_PRIVATE)
    /** EZCH's own OAuth app (oauth.yandex.ru → scopes iot:view, iot:control, «вход по коду»). Empty until registered. */
    private const val EZCH_CLIENT_ID = ""
    private const val EZCH_CLIENT_SECRET = ""
    fun clientId(c: Context) = prefs(c).getString("client_id", null)?.takeIf { it.isNotBlank() } ?: EZCH_CLIENT_ID
    fun clientSecret(c: Context) = prefs(c).getString("client_secret", null)?.takeIf { it.isNotBlank() } ?: EZCH_CLIENT_SECRET
    fun setApp(c: Context, id: String, secret: String) = prefs(c).edit().putString("client_id", id.trim()).putString("client_secret", secret.trim()).apply()
    fun token(c: Context) = prefs(c).getString("token", "").orEmpty()
    fun setToken(c: Context, token: String) = prefs(c).edit().putString("token", token.trim()).apply()
    fun disconnect(c: Context) = prefs(c).edit().remove("token").apply()
    override fun connected(context: Context) = token(context).length >= 20

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)

    /** Step 1: a code to show on the TV (https://ya.ru/device). */
    suspend fun startSignIn(c: Context): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            check(clientId(c).isNotBlank()) { "Не задан идентификатор приложения Яндекс OAuth" }
            val body = FormBody.Builder().add("client_id", clientId(c)).add("device_id", deviceId(c)).add("device_name", "EZCH TV").build()
            smartHttp.newCall(Request.Builder().url("https://oauth.yandex.ru/device/code").post(body).build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                check(r.isSuccessful) { o.optString("error_description", "Яндекс отклонил запрос") }
                DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://ya.ru/device"),
                    o.optInt("interval", 5), o.optInt("expires_in", 300))
            }
        }
    }
    /** Step 2: poll until the owner confirms. Null = still waiting; failure = expired/denied. */
    suspend fun pollToken(c: Context, code: DeviceCode): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder().add("grant_type", "device_code").add("code", code.deviceCode)
                .add("client_id", clientId(c)).add("client_secret", clientSecret(c)).build()
            smartHttp.newCall(Request.Builder().url("https://oauth.yandex.ru/token").post(body).build()).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                val token = o.optString("access_token")
                when {
                    token.isNotBlank() -> { setToken(c, token); token }
                    o.optString("error") == "authorization_pending" -> null
                    else -> throw IllegalStateException(o.optString("error_description", "Вход не подтверждён"))
                }
            }
        }
    }
    private fun deviceId(c: Context): String = prefs(c).getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs(c).edit().putString("device_id", it).apply()
    }

    override suspend fun devices(context: Context): List<SmartDevice>? = withContext(Dispatchers.IO) {
        if (!connected(context)) return@withContext null
        runCatching {
            smartHttp.newCall(Request.Builder().url("https://api.iot.yandex.net/v1.0/user/info")
                .header("Authorization", "Bearer ${token(context)}").get().build()).execute().use { r ->
                if (r.code == 401) { disconnect(context); return@use null }
                if (!r.isSuccessful) return@use null
                YandexMapper.parse(JSONObject(r.body?.string().orEmpty()))
            }
        }.getOrNull()
    }

    override suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = if (device.id.startsWith("scenario:"))
                Request.Builder().url("https://api.iot.yandex.net/v1.0/scenarios/${device.id.removePrefix("scenario:")}/actions").post("".toRequestBody(JSON_TYPE))
            else Request.Builder().url("https://api.iot.yandex.net/v1.0/devices/actions")
                .post((YandexMapper.action(device, command) ?: return@runCatching false).toString().toRequestBody(JSON_TYPE))
            smartHttp.newCall(request.header("Authorization", "Bearer ${token(context)}").build()).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val o = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                // Device actions report a per-capability status; "DONE" is the only success.
                val caps = o.optJSONArray("devices")?.optJSONObject(0)?.optJSONArray("capabilities")
                caps == null || (0 until caps.length()).all { caps.optJSONObject(it)?.optJSONObject("state")?.optJSONObject("action_result")?.optString("status", "DONE") == "DONE" }
            }
        }.getOrDefault(false)
    }
}

/**
 * Tuya / Smart Life cloud directly (for homes without Яндекс). Needs a free Tuya IoT project:
 * Access ID + Secret, data centre, and the Smart Life account linked to the project (its UID).
 * Tuya IR hubs (универсальный ИК-пульт Tuya) are listed as remotes; learned keys use the IR API.
 */
object TuyaProvider : SmartProvider {
    override val id = "tuya"
    override val title = "Tuya · Smart Life"
    val REGIONS = linkedMapOf("Европа" to "https://openapi.tuyaeu.com", "Америка" to "https://openapi.tuyaus.com",
        "Китай" to "https://openapi.tuyacn.com", "Индия" to "https://openapi.tuyain.com")
    private fun prefs(c: Context) = c.getSharedPreferences("ezch_tuya_home", Context.MODE_PRIVATE)
    data class Keys(val accessId: String, val secret: String, val endpoint: String, val uid: String)
    fun keys(c: Context) = prefs(c).let { Keys(it.getString("id", "").orEmpty(), it.getString("secret", "").orEmpty(),
        it.getString("endpoint", REGIONS.values.first()).orEmpty(), it.getString("uid", "").orEmpty()) }
    fun save(c: Context, k: Keys) = prefs(c).edit().putString("id", k.accessId.trim()).putString("secret", k.secret.trim())
        .putString("endpoint", k.endpoint).putString("uid", k.uid.trim()).remove("token").apply()
    fun disconnect(c: Context) = prefs(c).edit().clear().apply()
    override fun connected(context: Context) = keys(context).let { it.accessId.length >= 10 && it.secret.length >= 16 && it.uid.isNotBlank() }

    private fun call(c: Context, method: String, path: String, body: String = "", withToken: Boolean = true): JSONObject? {
        val k = keys(c)
        val token = if (withToken) accessToken(c) ?: return null else ""
        val t = System.currentTimeMillis().toString()
        val sts = TuyaMapper.stringToSign(method, TuyaMapper.sha256(body), path)
        val sign = TuyaMapper.sign(k.secret, k.accessId, token, t, sts)
        val builder = Request.Builder().url(k.endpoint + path).header("client_id", k.accessId).header("sign", sign)
            .header("t", t).header("sign_method", "HMAC-SHA256")
        if (withToken) builder.header("access_token", token)
        if (method == "POST") builder.post(body.toRequestBody(JSON_TYPE)) else builder.get()
        return smartHttp.newCall(builder.build()).execute().use { r -> runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() }
    }
    private fun accessToken(c: Context): String? {
        val p = prefs(c)
        if (p.getLong("token_until", 0) > System.currentTimeMillis()) p.getString("token", null)?.let { return it }
        val o = call(c, "GET", "/v1.0/token?grant_type=1", withToken = false) ?: return null
        if (!o.optBoolean("success")) return null
        val r = o.optJSONObject("result") ?: return null
        val token = r.optString("access_token")
        p.edit().putString("token", token).putLong("token_until", System.currentTimeMillis() + (r.optLong("expire_time", 3600) - 120) * 1000).apply()
        return token
    }

    /** Checks the keys right after the owner enters them: «Подключено» or the cloud's own error text. */
    suspend fun test(c: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val o = call(c, "GET", "/v1.0/token?grant_type=1", withToken = false) ?: return@runCatching "Облако Tuya не отвечает"
            if (!o.optBoolean("success")) return@runCatching "Tuya: ${o.optString("msg", "ключи не подошли")}"
            val n = devices(c)?.size
            if (n == null) "Ключи верны, но список устройств не получен: проверьте UID аккаунта Smart Life" else "Подключено. Устройств: $n"
        }.getOrElse { "Ошибка сети: ${it.message}" }
    }

    override suspend fun devices(context: Context): List<SmartDevice>? = withContext(Dispatchers.IO) {
        if (!connected(context)) return@withContext null
        runCatching {
            val o = call(context, "GET", "/v1.0/users/${keys(context).uid}/devices") ?: return@runCatching null
            if (!o.optBoolean("success")) return@runCatching null
            TuyaMapper.parse(o.optJSONArray("result") ?: JSONArray())
        }.getOrNull()
    }

    /** Learned keys of a Tuya IR hub remote, if the device is a hub (shown in «Умный пульт»). */
    suspend fun remotes(context: Context, hub: SmartDevice): List<SmartDevice> = withContext(Dispatchers.IO) {
        val ir = hub.infrared ?: return@withContext emptyList()
        runCatching {
            val o = call(context, "GET", "/v2.0/infrareds/$ir/remotes") ?: return@runCatching emptyList()
            val arr = o.optJSONArray("result") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { r ->
                val remoteId = r.optString("remote_id")
                val keysJson = call(context, "GET", "/v2.0/infrareds/$ir/remotes/$remoteId/keys")?.optJSONObject("result")
                val keys = keysJson?.optJSONArray("key_list")?.let { k -> (0 until k.length()).mapNotNull { k.optJSONObject(it) }
                    .associate { it.optString("key_name", it.optString("key")) to it.optString("key") } }.orEmpty()
                val category = r.optString("category_id")
                SmartDevice("tuya", remoteId, r.optString("remote_name", "ИК-пульт"), hub.room,
                    if (category == "5") DeviceType.AC else if (category == "2") DeviceType.TV else DeviceType.REMOTE,
                    buttons = keys, canPower = true, infrared = ir, mode = category)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val ir = device.infrared
            val o = if (ir != null && ir != device.id) {
                // A remote behind a Tuya IR hub.
                val body = when (command) {
                    is DeviceCommand.Button -> JSONObject().put("category_id", device.mode?.toIntOrNull() ?: 999).put("key", command.id)
                    is DeviceCommand.Power -> JSONObject().put("category_id", device.mode?.toIntOrNull() ?: 999).put("key", if (command.on) "PowerOn" else "PowerOff")
                    is DeviceCommand.Temperature -> JSONObject().put("code", "temp").put("value", command.celsius)
                    else -> return@runCatching false
                }
                val path = if (command is DeviceCommand.Temperature) "/v2.0/infrareds/$ir/air-conditioners/${device.id}/command"
                    else "/v2.0/infrareds/$ir/remotes/${device.id}/command"
                call(context, "POST", path, body.toString())
            } else call(context, "POST", "/v1.0/devices/${device.id}/commands", (TuyaMapper.commands(device, command) ?: return@runCatching false).toString())
            o?.optBoolean("success") == true
        }.getOrDefault(false)
    }
}

/** Home Assistant (for advanced homes; Xiaomi Home and Tuya integrations also come through it). */
object HomeAssistantProvider : SmartProvider {
    override val id = "ha"
    override val title = "Home Assistant"
    override fun connected(context: Context) = SmartHomeConnection.configured(context)
    private fun type(domain: String) = when (domain) {
        "light" -> DeviceType.LIGHT; "switch" -> DeviceType.SOCKET; "climate" -> DeviceType.AC; "vacuum" -> DeviceType.VACUUM
        "media_player" -> DeviceType.TV; "cover" -> DeviceType.CURTAIN; "humidifier" -> DeviceType.HUMIDIFIER; "fan" -> DeviceType.FAN
        "sensor" -> DeviceType.SENSOR; "scene", "script" -> DeviceType.SCENE; "remote" -> DeviceType.REMOTE; else -> DeviceType.OTHER
    }
    override suspend fun devices(context: Context): List<SmartDevice>? {
        val ha = HomeAssistantClient.of(context) ?: return null
        return ha.states()?.filter { it.domain in setOf("light", "switch", "climate", "vacuum", "media_player", "cover", "humidifier", "fan", "scene", "script", "remote") ||
            (it.domain == "sensor" && it.attributes.optString("device_class") in setOf("temperature", "humidity")) }
            ?.map { e ->
                SmartDevice("ha", e.id, e.name, "", type(e.domain), online = e.state != "unavailable", on = e.on,
                    brightness = e.attributes.optInt("brightness", -1).takeIf { it >= 0 }?.let { it * 100 / 255 },
                    temperature = e.attributes.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() },
                    reading = if (e.domain == "sensor") "${e.state}${e.attributes.optString("unit_of_measurement")}" else null,
                    canPower = e.domain !in setOf("sensor", "scene", "script"))
            }
    }
    override suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean {
        val ha = HomeAssistantClient.of(context) ?: return false
        val domain = device.id.substringBefore('.')
        val data = JSONObject().put("entity_id", device.id)
        val (d, s) = when (command) {
            is DeviceCommand.Power -> if (domain in setOf("scene", "script")) domain to "turn_on" else domain to if (command.on) "turn_on" else "turn_off"
            is DeviceCommand.Brightness -> { data.put("brightness_pct", command.percent); "light" to "turn_on" }
            is DeviceCommand.Temperature -> { data.put("temperature", command.celsius); "climate" to "set_temperature" }
            is DeviceCommand.Mode -> { data.put("hvac_mode", command.mode); "climate" to "set_hvac_mode" }
            is DeviceCommand.Vacuum -> "vacuum" to when (command.op) { "start" -> "start"; "dock" -> "return_to_base"; else -> "pause" }
            is DeviceCommand.Curtain -> "cover" to if (command.open) "open_cover" else "close_cover"
            is DeviceCommand.Button -> { data.put("command", command.id); "remote" to "send_command" }
        }
        return ha.call(d, s, data)
    }
}

/** All ecosystems together: the «Умный дом» screen and Ai EvA voice commands use only this. */
object SmartHub {
    val providers: List<SmartProvider> = listOf(YandexProvider, TuyaProvider, HomeAssistantProvider)
    fun anyConnected(context: Context) = providers.any { it.connected(context) }
    @Volatile var cache: List<SmartDevice> = emptyList(); private set

    suspend fun devices(context: Context): List<SmartDevice> = coroutineScope {
        val lists = providers.filter { it.connected(context) }.map { p -> async { runCatching { p.devices(context) }.getOrNull().orEmpty() } }.awaitAll()
        val all = lists.flatten()
        // Tuya IR hubs expand into their learned remotes.
        val tuyaRemotes = all.filter { it.provider == "tuya" && it.infrared == it.id }.flatMap { TuyaProvider.remotes(context, it) }
        (all + tuyaRemotes).also { cache = it }
    }
    suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean =
        providers.firstOrNull { it.id == device.provider }?.send(context, device, command) ?: false
}
