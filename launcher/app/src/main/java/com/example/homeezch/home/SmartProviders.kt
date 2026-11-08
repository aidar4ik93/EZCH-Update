package com.example.homeezch.home

import android.content.Context
import com.example.homeezch.ai.HomeAssistantClient
import com.example.homeezch.ai.SmartHomeConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
    val providers: List<SmartProvider> = listOf(YandexProvider, TuyaProvider, HomeAssistantProvider, com.example.homeezch.ir.IrProvider)
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
