package com.example.homeezch.home

import org.json.JSONArray
import org.json.JSONObject

/** What a device is, independent of the ecosystem (Yandex, Tuya, Home Assistant). */
enum class DeviceType(val title: String, val icon: String, val color: Long) {
    LIGHT("Свет", "💡", 0xFFFFC94D), SOCKET("Розетка", "🔌", 0xFF7BE07B), AC("Кондиционер", "❄️", 0xFF38D1FF),
    VACUUM("Пылесос", "🧹", 0xFFB57BFF), TV("Телевизор", "📺", 0xFF5AA9FF), REMOTE("ИК-пульт", "🎛️", 0xFFFF9F43),
    CURTAIN("Шторы", "🪟", 0xFF8BD3DD), HUMIDIFIER("Увлажнитель", "💧", 0xFF4DB6FF), KETTLE("Чайник", "☕", 0xFFFF7A59),
    HEATER("Обогреватель", "🔥", 0xFFFF5E5E), FAN("Вентилятор", "🌀", 0xFF2EE6C5), SENSOR("Датчик", "🌡️", 0xFF9AA8BD),
    SCENE("Сценарий", "✨", 0xFFFF6FAE), OTHER("Устройство", "🏠", 0xFF9F8CFF)
}

/** One controllable thing. [provider] = "yandex" | "tuya" | "ha"; [buttons] are learned IR buttons (name → id). */
data class SmartDevice(
    val provider: String, val id: String, val name: String, val room: String, val type: DeviceType,
    val online: Boolean = true, val on: Boolean? = null, val brightness: Int? = null,
    val temperature: Double? = null, val mode: String? = null, val reading: String? = null,
    val buttons: Map<String, String> = emptyMap(), val canPower: Boolean = true,
    /** Tuya: the status code that switches power (switch_led / switch_1 / power_go…). Yandex/HA: unused. */
    val powerCode: String? = null, val infrared: String? = null,
)

/** Commands the UI and Ai EvA can send; each provider translates them to its own protocol. */
sealed interface DeviceCommand {
    data class Power(val on: Boolean) : DeviceCommand
    data class Brightness(val percent: Int) : DeviceCommand
    data class Temperature(val celsius: Int) : DeviceCommand
    data class Mode(val mode: String) : DeviceCommand
    data class Vacuum(val op: String) : DeviceCommand // start / pause / dock
    data class Button(val id: String) : DeviceCommand
    data class Curtain(val open: Boolean) : DeviceCommand
}

/** Yandex Smart Home (Дом с Алисой) IoT API ↔ [SmartDevice]. Pure, JVM-tested. Covers Яндекс Пульт IR devices. */
object YandexMapper {
    fun type(t: String): DeviceType = when {
        t.startsWith("devices.types.light") -> DeviceType.LIGHT
        t == "devices.types.socket" || t == "devices.types.switch" -> DeviceType.SOCKET
        t.startsWith("devices.types.thermostat.ac") -> DeviceType.AC
        t.startsWith("devices.types.thermostat") -> DeviceType.HEATER
        t.startsWith("devices.types.vacuum_cleaner") -> DeviceType.VACUUM
        t.startsWith("devices.types.media_device.tv") -> DeviceType.TV
        t.startsWith("devices.types.media_device") -> DeviceType.REMOTE
        t.startsWith("devices.types.openable") -> DeviceType.CURTAIN
        t.startsWith("devices.types.humidifier") || t.startsWith("devices.types.purifier") -> DeviceType.HUMIDIFIER
        t.startsWith("devices.types.cooking") -> DeviceType.KETTLE
        t.startsWith("devices.types.ventilation") -> DeviceType.FAN
        t.startsWith("devices.types.sensor") -> DeviceType.SENSOR
        else -> DeviceType.OTHER
    }

    /** GET /v1.0/user/info → devices with room names; scenarios become SCENE entries. */
    fun parse(json: JSONObject): List<SmartDevice> {
        val rooms = HashMap<String, String>()
        json.optJSONArray("rooms")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { rooms[it.optString("id")] = it.optString("name") } }
        val out = ArrayList<SmartDevice>()
        val devices = json.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val caps = d.optJSONArray("capabilities") ?: JSONArray()
            var on: Boolean? = null; var brightness: Int? = null; var temp: Double? = null; var mode: String? = null
            var power = false; val buttons = LinkedHashMap<String, String>()
            for (c in 0 until caps.length()) {
                val cap = caps.optJSONObject(c) ?: continue
                val state = cap.optJSONObject("state"); val params = cap.optJSONObject("parameters")
                when (cap.optString("type")) {
                    "devices.capabilities.on_off" -> { power = true; if (state != null && state.has("value")) on = state.optBoolean("value") }
                    "devices.capabilities.range" -> when (params?.optString("instance")) {
                        "brightness" -> brightness = state?.optInt("value")
                        "temperature" -> temp = state?.optDouble("value")
                    }
                    "devices.capabilities.mode" -> if (params?.optString("instance") in setOf("thermostat", "cleanup_mode", "work_speed")) mode = state?.optString("value")
                    // Яндекс Пульт: buttons learned from any IR remote.
                    "devices.capabilities.custom.button" -> {
                        val id = params?.optString("instance").orEmpty(); val name = params?.optString("instance_name").orEmpty()
                        if (id.isNotEmpty()) buttons[name.ifEmpty { id }] = id
                    }
                }
            }
            val props = d.optJSONArray("properties") ?: JSONArray()
            var reading: String? = null
            for (p in 0 until props.length()) {
                val prop = props.optJSONObject(p) ?: continue
                val inst = prop.optJSONObject("parameters")?.optString("instance"); val v = prop.optJSONObject("state")?.opt("value")
                if (v != null && inst == "temperature") { temp = temp ?: (v as? Number)?.toDouble(); reading = "${v}°" }
                if (v != null && inst == "humidity" && reading == null) reading = "$v %"
            }
            var type = type(d.optString("type"))
            if (type == DeviceType.OTHER && buttons.isNotEmpty()) type = DeviceType.REMOTE
            out += SmartDevice("yandex", d.optString("id"), d.optString("name"), rooms[d.optString("room")].orEmpty(), type,
                online = d.optString("state", "online") != "offline", on = on, brightness = brightness, temperature = temp,
                mode = mode, reading = reading, buttons = buttons, canPower = power)
        }
        json.optJSONArray("scenarios")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { s ->
                if (s.optBoolean("is_active", true)) out += SmartDevice("yandex", "scenario:" + s.optString("id"), s.optString("name"), "", DeviceType.SCENE, canPower = false)
            }
        }
        return out
    }

    /** POST /v1.0/devices/actions body. Null when the command does not apply. */
    fun action(device: SmartDevice, command: DeviceCommand): JSONObject? {
        fun cap(type: String, instance: String, value: Any) = JSONObject().put("type", type)
            .put("state", JSONObject().put("instance", instance).put("value", value))
        val a = when (command) {
            is DeviceCommand.Power -> cap("devices.capabilities.on_off", "on", command.on)
            is DeviceCommand.Brightness -> cap("devices.capabilities.range", "brightness", command.percent.coerceIn(1, 100))
            is DeviceCommand.Temperature -> cap("devices.capabilities.range", "temperature", command.celsius.coerceIn(16, 32))
            is DeviceCommand.Mode -> cap("devices.capabilities.mode", if (device.type == DeviceType.VACUUM) "cleanup_mode" else "thermostat", command.mode)
            is DeviceCommand.Button -> cap("devices.capabilities.custom.button", command.id, true)
            is DeviceCommand.Vacuum -> when (command.op) {
                "start" -> cap("devices.capabilities.on_off", "on", true)
                "dock" -> cap("devices.capabilities.on_off", "on", false)
                else -> cap("devices.capabilities.toggle", "pause", true)
            }
            is DeviceCommand.Curtain -> cap("devices.capabilities.on_off", "on", command.open)
        }
        return JSONObject().put("devices", JSONArray().put(JSONObject().put("id", device.id).put("actions", JSONArray().put(a))))
    }
}

/** Tuya / Smart Life cloud (OpenAPI) ↔ [SmartDevice]. Pure, JVM-tested. */
object TuyaMapper {
    private val POWER_CODES = listOf("switch_led", "switch", "switch_1", "power", "power_go", "switch_on")
    fun type(category: String): DeviceType = when (category) {
        "dj", "dd", "fwd", "dc", "xdd", "fsd", "tgq", "tgkg" -> DeviceType.LIGHT
        "cz", "pc", "kg", "tdq" -> DeviceType.SOCKET
        "kt", "ktkzq", "infrared_ac" -> DeviceType.AC
        "sd" -> DeviceType.VACUUM
        "cl", "clkg" -> DeviceType.CURTAIN
        "jsq", "kj" -> DeviceType.HUMIDIFIER
        "bh", "kettle" -> DeviceType.KETTLE
        "qn", "rs" -> DeviceType.HEATER
        "fs", "fsd2" -> DeviceType.FAN
        "wsdcg", "ldcg", "co2bj", "mcs", "pir" -> DeviceType.SENSOR
        "wnykq", "hwktwkq", "qt", "infrared_tv", "infrared_stb" -> DeviceType.REMOTE
        else -> DeviceType.OTHER
    }

    /** Device list item (GET /v1.0/users/{uid}/devices or iot-01 associated-users). */
    fun parse(array: JSONArray): List<SmartDevice> {
        val out = ArrayList<SmartDevice>()
        for (i in 0 until array.length()) {
            val d = array.optJSONObject(i) ?: continue
            val status = HashMap<String, Any?>()
            d.optJSONArray("status")?.let { s -> for (k in 0 until s.length()) s.optJSONObject(k)?.let { status[it.optString("code")] = it.opt("value") } }
            val power = POWER_CODES.firstOrNull { status.containsKey(it) }
            val brightRaw = (status["bright_value_v2"] ?: status["bright_value"]) as? Number
            val brightness = brightRaw?.let { v -> if (status.containsKey("bright_value_v2")) (v.toInt() / 10) else (v.toInt() * 100 / 255) }
            val temp = (status["temp_set"] as? Number)?.toDouble()
            val current = (status["va_temperature"] ?: status["temp_current"]) as? Number
            val category = d.optString("category")
            out += SmartDevice("tuya", d.optString("id"), d.optString("name"), d.optString("room_name", ""), type(category),
                online = d.optBoolean("online", true), on = power?.let { status[it] as? Boolean }, brightness = brightness,
                temperature = temp, mode = status["mode"] as? String, reading = current?.let { "${it}°" },
                canPower = power != null, powerCode = power,
                infrared = if (category in setOf("wnykq", "hwktwkq", "qt")) d.optString("id") else null)
        }
        return out
    }

    /** POST /v1.0/devices/{id}/commands body (DP commands). */
    fun commands(device: SmartDevice, command: DeviceCommand): JSONObject? {
        val list = JSONArray()
        when (command) {
            is DeviceCommand.Power -> list.put(JSONObject().put("code", device.powerCode ?: return null).put("value", command.on))
            is DeviceCommand.Brightness -> list.put(JSONObject().put("code", "bright_value_v2").put("value", (command.percent.coerceIn(1, 100) * 10)))
            is DeviceCommand.Temperature -> list.put(JSONObject().put("code", "temp_set").put("value", command.celsius))
            is DeviceCommand.Mode -> list.put(JSONObject().put("code", "mode").put("value", command.mode))
            is DeviceCommand.Vacuum -> when (command.op) {
                "start" -> list.put(JSONObject().put("code", "power_go").put("value", true))
                "dock" -> list.put(JSONObject().put("code", "switch_charge").put("value", true))
                else -> list.put(JSONObject().put("code", "pause").put("value", true))
            }
            is DeviceCommand.Curtain -> list.put(JSONObject().put("code", "control").put("value", if (command.open) "open" else "close"))
            is DeviceCommand.Button -> return null
        }
        return JSONObject().put("commands", list)
    }

    /** Tuya OpenAPI v2 signature: HMAC-SHA256(clientId + accessToken + t + nonce + stringToSign), upper-case hex. */
    fun stringToSign(method: String, bodySha256: String, url: String) = "${method.uppercase()}\n$bodySha256\n\n$url"
    fun sign(secret: String, clientId: String, accessToken: String, t: String, stringToSign: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal((clientId + accessToken + t + stringToSign).toByteArray()).joinToString("") { "%02X".format(it) }
    }
    fun sha256(body: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Spoken target → device across all connected ecosystems («свет в гостиной», «кондиционер», «пылесос»). */
object SmartMatcher {
    private val typeWords = listOf(
        DeviceType.LIGHT to listOf("свет", "ламп", "люстр", "подсветк", "торшер", "светильник"),
        DeviceType.AC to listOf("кондиционер", "сплит", "кондей"),
        DeviceType.VACUUM to listOf("пылесос", "робот"),
        DeviceType.TV to listOf("телевизор", "тв"),
        DeviceType.SOCKET to listOf("розетк"),
        DeviceType.CURTAIN to listOf("штор", "жалюзи", "занавес"),
        DeviceType.HUMIDIFIER to listOf("увлажнител", "очистител"),
        DeviceType.KETTLE to listOf("чайник"),
        DeviceType.HEATER to listOf("обогревател", "радиатор", "батаре"),
        DeviceType.FAN to listOf("вентилятор"),
    )
    private val rooms = listOf("гостин", "зал", "спальн", "кухн", "детск", "прихож", "коридор", "ванн", "кабинет", "балкон")

    fun find(target: String, devices: List<SmartDevice>): List<SmartDevice> {
        val t = target.lowercase().replace('ё', 'е')
        // An exact device name wins: «Люстра над диваном», «Пульт Samsung».
        devices.filter { it.name.isNotBlank() && t.contains(it.name.lowercase().replace('ё', 'е')) }.takeIf { it.isNotEmpty() }?.let { return it }
        val type = typeWords.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.first
        val room = rooms.firstOrNull { t.contains(it) }
        return devices.filter { d ->
            (type == null || d.type == type) && d.type != DeviceType.SCENE && d.type != DeviceType.SENSOR &&
                (room == null || d.room.lowercase().contains(room) || d.name.lowercase().contains(room))
        }.takeIf { type != null || room != null }.orEmpty()
    }
}
