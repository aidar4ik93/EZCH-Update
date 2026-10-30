package com.example.homeezch.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/** Home Assistant on the home network (URL + long-lived access token entered by the owner). */
internal object SmartHomeConnection {
    fun store(context: Context) = context.getSharedPreferences("eva_smart_home_v1", Context.MODE_PRIVATE)
    fun url(context: Context) = store(context).getString("url", "").orEmpty()
    fun token(context: Context) = store(context).getString("token", "").orEmpty()
    fun configured(context: Context) = validUrl(url(context)) && token(context).length in 20..400
    fun save(context: Context, url: String, token: String) =
        store(context).edit().putString("url", url.trim().trimEnd('/')).putString("token", token.trim()).apply()
    fun clear(context: Context) = store(context).edit().clear().apply()
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim()); val host = uri.host ?: return false
        uri.rawUserInfo == null && uri.rawQuery == null && (uri.scheme == "https" || (uri.scheme == "http" && LanAddress.privateHost(host)))
    }.getOrDefault(false)

    /** Finds Home Assistant (port 8123) on the TV's /24 network. */
    suspend fun discover(): String? = withContext(Dispatchers.IO) {
        val own = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }.firstOrNull { LanAddress.privateHost(it) && !it.startsWith("127.") } ?: return@withContext null
        val prefix = own.substringBeforeLast('.'); val gate = Semaphore(48)
        coroutineScope {
            (1..254).map { last -> async { gate.withPermit {
                val host = "$prefix.$last"
                runCatching { Socket().use { it.connect(InetSocketAddress(host, 8123), 250) }; host }.getOrNull()
            } } }.awaitAll().filterNotNull().firstOrNull()?.let { "http://$it:8123" }
        }
    }
}

internal class HomeAssistantClient(private val url: String, private val token: String) {
    companion object {
        private val client by lazy { OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build() }
        private val json = "application/json; charset=utf-8".toMediaType()
        fun of(context: Context) = if (SmartHomeConnection.configured(context))
            HomeAssistantClient(SmartHomeConnection.url(context), SmartHomeConnection.token(context)) else null
    }
    private fun request(path: String) = Request.Builder().url(url.trimEnd('/') + path).header("Authorization", "Bearer $token")

    suspend fun states(): List<HomeEntity>? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/api/states").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val array = JSONArray(r.body?.string().orEmpty())
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { o ->
                    val attributes = o.optJSONObject("attributes") ?: JSONObject()
                    HomeEntity(o.optString("entity_id"), attributes.optString("friendly_name", o.optString("entity_id")), o.optString("state"), attributes)
                }
            }
        }.getOrNull()
    }
    suspend fun state(id: String): HomeEntity? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/api/states/$id").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty()); val a = o.optJSONObject("attributes") ?: JSONObject()
                HomeEntity(o.optString("entity_id"), a.optString("friendly_name", id), o.optString("state"), a)
            }
        }.getOrNull()
    }
    /** One HTTP call, never retried blindly: the result is confirmed by reading the state back. */
    suspend fun call(domain: String, service: String, data: JSONObject): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/api/services/$domain/$service").post(data.toString().toRequestBody(json)).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/** Executes smart-home actions and reports only what Home Assistant confirms. */
internal class SmartHomeExecutor(private val context: Context) {
    suspend fun execute(action: Action.Home): Outcome {
        if (action.op == "panel") {
            context.startActivity(android.content.Intent(context, SmartHomeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return Outcome(true, "Открываю умный дом")
        }
        val ha = HomeAssistantClient.of(context)
            ?: return Outcome(false, "Умный дом не подключён. Откройте настройки Евы → Умный дом и подключите Home Assistant")
        val entities = ha.states() ?: return Outcome(false, "Home Assistant недоступен. Проверьте, что сервер включён и токен действителен")
        return when (action.op) {
            "on", "off" -> switch(ha, action, entities)
            "brightness" -> brightness(ha, action, entities)
            "temperature" -> {
                val sensor = SmartHomeMatcher.temperature(action.target, entities) ?: return Outcome(false, "Не нашла датчик температуры${room(action.target)}")
                val value = if (sensor.domain == "climate") sensor.attributes.optDouble("current_temperature") else sensor.state.toDoubleOrNull()
                if (value == null || value.isNaN()) Outcome(false, "Датчик «${sensor.name}» сейчас не передаёт данные")
                else Outcome(true, "${sensor.name}: ${"%.1f".format(java.util.Locale.forLanguageTag("ru"), value).removeSuffix(",0")}°")
            }
            "scene" -> {
                val scene = SmartHomeMatcher.scene(action.target, entities) ?: return Outcome(false, "Сценарий «${action.target}» не найден в Home Assistant")
                val ok = ha.call(scene.domain, "turn_on", JSONObject().put("entity_id", scene.id))
                Outcome(ok, if (ok) "Запускаю сценарий «${scene.name}»" else "Home Assistant не принял сценарий «${scene.name}»")
            }
            "panel" -> {
                context.startActivity(android.content.Intent(context, SmartHomeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                Outcome(true, "Открываю умный дом")
            }
            "status" -> {
                val lights = entities.filter { it.domain == "light" && it.state == "on" }
                Outcome(true, if (lights.isEmpty()) "Сейчас свет нигде не включён" else "Включено: ${lights.take(6).joinToString { it.name }}")
            }
            // Locks, alarms and doors are never operated by voice alone: no PIN, no action.
            "lock", "unlock" -> Outcome(false, "Замки и сигнализация голосом не управляются из соображений безопасности. Откройте раздел «Умный дом» и подтвердите действие")
            else -> Outcome(false, "Эта команда умного дома пока не поддерживается")
        }
    }
    private fun room(target: String) = SmartHomeMatcher.roomOf(target)?.let { " ($it)" }.orEmpty()

    private suspend fun switch(ha: HomeAssistantClient, action: Action.Home, entities: List<HomeEntity>): Outcome {
        val found = SmartHomeMatcher.find(action.target, entities)
        if (found.isEmpty()) return Outcome(false, "Не нашла устройство «${action.target}» в Home Assistant")
        if (found.any { it.domain in setOf("lock", "alarm_control_panel", "cover") }) return Outcome(false, "Это устройство требует подтверждения в разделе «Умный дом»")
        val on = action.op == "on"
        val ok = found.groupBy { it.domain }.all { (domain, list) ->
            ha.call(domain, if (on) "turn_on" else "turn_off", JSONObject().put("entity_id", JSONArray(list.map { it.id })))
        }
        if (!ok) return Outcome(false, "Home Assistant не выполнил команду")
        val confirmed = confirm(ha, found.map { it.id }) { it.state == if (on) "on" else "off" }
        val what = if (found.size == 1) "«${found.single().name}»" else "${found.size} устр."
        return Outcome(confirmed, if (confirmed) "${if (on) "Включила" else "Выключила"} $what" else "Команда отправлена, но состояние $what не подтвердилось")
    }
    private suspend fun brightness(ha: HomeAssistantClient, action: Action.Home, entities: List<HomeEntity>): Outcome {
        val lights = SmartHomeMatcher.find(action.target, entities).filter { it.domain == "light" }
        if (lights.isEmpty()) return Outcome(false, "Не нашла светильники с регулировкой яркости${room(action.target)}")
        val pct = action.value ?: 50
        val ok = ha.call("light", "turn_on", JSONObject().put("entity_id", JSONArray(lights.map { it.id })).put("brightness_pct", pct))
        if (!ok) return Outcome(false, "Home Assistant не изменил яркость")
        val confirmed = confirm(ha, lights.map { it.id }) { e ->
            val brightness = e.attributes.optInt("brightness", -1)
            if (pct == 0) e.state == "off" || (e.state == "on" && brightness == 0)
            else e.state == "on" && brightness >= 0 && kotlin.math.abs(brightness * 100 / 255 - pct) <= 3
        }
        return Outcome(confirmed, if (confirmed) "Яркость $pct%" else "Яркость отправлена, подтверждение от светильника не получено")
    }
    private suspend fun confirm(ha: HomeAssistantClient, ids: List<String>, check: (HomeEntity) -> Boolean): Boolean {
        repeat(6) {
            delay(250)
            if (ids.all { id -> ha.state(id)?.let(check) == true }) return true
        }
        return false
    }
}

/** Home network addresses only: plain http is allowed for a device inside the owner's LAN. */
internal object LanAddress {
    fun privateHost(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return host == "localhost"
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}
