package com.example.homeezch.ir

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Virtual remote categories (ТЗ §5). */
enum class IrCategory(val title: String, val icon: String) {
    TV("Телевизор", "📺"), AC("Кондиционер", "❄️"), STB("ТВ-приставка", "📦"), PROJECTOR("Проектор", "📽️"),
    AUDIO("Аудиосистема", "🔊"), LIGHT("Освещение", "💡"), FAN("Вентилятор", "🌀"), OTHER("Другое ИК-устройство", "🎛️");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

/**
 * What a button does. The owner picks it when naming a learned button; Ai EvA uses it for voice
 * («громче на саундбаре», «выключи кондиционер») — the spoken words below are the defaults, owner phrases add to them.
 */
enum class IrFunction(val title: String, val words: List<String>) {
    POWER("Питание (вкл/выкл)", listOf("питание")),
    POWER_ON("Включить", listOf("включи", "включить", "вруби")),
    POWER_OFF("Выключить", listOf("выключи", "выключить", "выруби", "отключи")),
    VOL_UP("Громче", listOf("громче", "прибавь звук", "погромче", "добавь громкость", "прибавь громкость")),
    VOL_DOWN("Тише", listOf("тише", "потише", "убавь звук", "убавь громкость")),
    MUTE("Без звука", listOf("выключи звук", "без звука", "заглуши", "отключи звук")),
    CH_UP("Канал +", listOf("следующий канал", "канал вперед", "переключи канал")),
    CH_DOWN("Канал −", listOf("предыдущий канал", "канал назад")),
    UP("Вверх", listOf("вверх")), DOWN("Вниз", listOf("вниз")), LEFT("Влево", listOf("влево")), RIGHT("Вправо", listOf("вправо")),
    OK("OK", listOf("ок", "окей")), BACK("Назад", listOf("назад")), HOME("Домой", listOf("домой")), MENU("Меню", listOf("меню")),
    INPUT("Источник", listOf("источник", "смени вход", "смени источник")),
    TEMP_UP("Температура +", listOf("теплее", "прибавь температуру")), TEMP_DOWN("Температура −", listOf("холоднее", "убавь температуру")),
    MODE("Режим", listOf("смени режим", "режим")), FAN_SPEED("Скорость", listOf("скорость", "смени скорость")),
    SWING("Поворот", listOf("поворот", "качание")),
    PLAY("Воспроизведение", listOf("играй", "воспроизведи", "продолжи")), PAUSE("Пауза", listOf("пауза", "поставь на паузу")),
    CUSTOM("Своя кнопка", emptyList());
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: CUSTOM }
}

/** A controller the owner connected. Only identifiers, no secrets (the session key is never stored). */
data class IrControllerRec(val driver: String, val mac: String, val host: String, val devType: Int, val name: String, val model: String)

/** One learned button. [code] = the raw signal exactly as the controller learned it. [phrases] = owner's own voice commands. */
data class IrButton(val id: String, val name: String, val function: IrFunction, val code: ByteArray, val phrases: List<String> = emptyList()) {
    override fun equals(other: Any?) = other is IrButton && other.id == id && other.name == name && other.function == function &&
        other.code.contentEquals(code) && other.phrases == phrases
    override fun hashCode() = id.hashCode()
}

data class IrDevice(
    val id: String, val name: String, val room: String, val category: IrCategory, val controller: String,
    val buttons: List<IrButton> = emptyList(),
)

/** One step of a scenario: press [buttonId] of [deviceId] [repeat] times, then wait [delayMs]. */
data class IrStep(val deviceId: String, val buttonId: String, val repeat: Int = 1, val delayMs: Long = 500)
data class IrScenario(val id: String, val name: String, val phrases: List<String>, val steps: List<IrStep>)

data class IrData(
    val controllers: List<IrControllerRec> = emptyList(),
    val devices: List<IrDevice> = emptyList(),
    val scenarios: List<IrScenario> = emptyList(),
) {
    fun device(id: String) = devices.firstOrNull { it.id == id }
    fun controller(mac: String) = controllers.firstOrNull { it.mac == mac }
    val rooms: List<String> get() = devices.map { it.room }.filter { it.isNotBlank() }.distinct()
}

object IrIds { fun newId(): String = UUID.randomUUID().toString() }

/** JSON form, used both for local storage and for export/import (ТЗ §1.10). Pure; JVM-tested. */
object IrJson {
    const val FORMAT = "ezch-ir"
    const val VERSION = 1

    fun write(d: IrData, includeControllers: Boolean = true): JSONObject = JSONObject()
        .put("format", FORMAT).put("version", VERSION)
        .put("controllers", JSONArray().apply {
            if (includeControllers) d.controllers.forEach { c ->
                put(JSONObject().put("driver", c.driver).put("mac", c.mac).put("host", c.host).put("devType", c.devType).put("name", c.name).put("model", c.model))
            }
        })
        .put("devices", JSONArray().apply {
            d.devices.forEach { v ->
                put(JSONObject().put("id", v.id).put("name", v.name).put("room", v.room).put("category", v.category.name).put("controller", v.controller)
                    .put("buttons", JSONArray().apply {
                        v.buttons.forEach { b ->
                            put(JSONObject().put("id", b.id).put("name", b.name).put("function", b.function.name)
                                .put("code", Base64.getEncoder().encodeToString(b.code)).put("phrases", JSONArray(b.phrases)))
                        }
                    }))
            }
        })
        .put("scenarios", JSONArray().apply {
            d.scenarios.forEach { s ->
                put(JSONObject().put("id", s.id).put("name", s.name).put("phrases", JSONArray(s.phrases)).put("steps", JSONArray().apply {
                    s.steps.forEach { st -> put(JSONObject().put("device", st.deviceId).put("button", st.buttonId).put("repeat", st.repeat).put("delay", st.delayMs)) }
                }))
            }
        })

    /** Parses stored or imported data; throws IllegalArgumentException on a foreign or damaged file. */
    fun read(o: JSONObject): IrData {
        require(o.optString("format") == FORMAT) { "Это не файл настроек ИК-пульта EZCH" }
        require(o.optInt("version", 0) in 1..VERSION) { "Файл создан более новой версией EZCH" }
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotBlank() } }
        val controllers = o.optJSONArray("controllers").objects().map {
            IrControllerRec(it.optString("driver", BroadlinkDriver.DRIVER_ID), it.getString("mac"), it.optString("host"), it.optInt("devType"), it.optString("name"), it.optString("model"))
        }
        val devices = o.optJSONArray("devices").objects().map { v ->
            IrDevice(v.getString("id"), v.getString("name"), v.optString("room"), IrCategory.of(v.optString("category")), v.optString("controller"),
                v.optJSONArray("buttons").objects().map { b ->
                    val code = runCatching { Base64.getDecoder().decode(b.optString("code")) }.getOrElse { throw IllegalArgumentException("Повреждён сигнал кнопки «${b.optString("name")}»") }
                    IrButton(b.getString("id"), b.getString("name"), IrFunction.of(b.optString("function")), code, strings(b.optJSONArray("phrases")))
                })
        }
        val scenarios = o.optJSONArray("scenarios").objects().map { s ->
            IrScenario(s.getString("id"), s.getString("name"), strings(s.optJSONArray("phrases")), s.optJSONArray("steps").objects().map {
                IrStep(it.getString("device"), it.getString("button"), it.optInt("repeat", 1).coerceIn(1, 20), it.optLong("delay", 500).coerceIn(0, 30_000))
            })
        }
        return IrData(controllers, devices, scenarios)
    }

    /** Import: devices/scenarios with the same id are replaced, new ones added; controllers kept unless the file has them. */
    fun merge(current: IrData, imported: IrData): IrData = IrData(
        controllers = (current.controllers.filter { c -> imported.controllers.none { it.mac == c.mac } } + imported.controllers),
        devices = current.devices.filter { d -> imported.devices.none { it.id == d.id } } + imported.devices,
        scenarios = current.scenarios.filter { s -> imported.scenarios.none { it.id == s.id } } + imported.scenarios,
    )

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * Ai EvA voice → IR. Pure; JVM-tested. Only commands that name something the owner created are taken
 * (a scenario phrase, a button phrase, or a device name + an action), so ordinary Eva commands, the TV's own
 * power control and «Дом с Алисой» keep working exactly as before.
 */
object IrVoice {
    sealed interface Match {
        data class Press(val deviceId: String, val buttonId: String, val say: String) : Match
        data class Scenario(val scenarioId: String, val say: String) : Match
    }

    fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("^\\s*(?:ева|eva)\\s+"), "").replace(Regex("\\s+"), " ").trim()

    private val filler = setOf("на", "у", "в", "во", "для", "пожалуйста", "мне", "кнопку", "нажми", "нажать", "ева", "а", "и", "сделай", "давай")
    private val rooms = listOf("гостин", "зал", "спальн", "кухн", "детск", "прихож", "коридор", "ванн", "кабинет", "балкон")

    /** How many leading letters of a name word must match a spoken word (handles «саундбар» → «на саундбаре»). */
    private fun stem(w: String) = if (w.length <= 4) w else w.take(maxOf(4, w.length - 2))

    fun match(text: String, data: IrData): Match? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        // 1. Scenario phrases and names («кино», «запусти сценарий кино»).
        data.scenarios.firstOrNull { s -> s.phrases.any { normalize(it) == t } }?.let { return Match.Scenario(it.id, "Сценарий «${it.name}»") }
        Regex("^(?:запусти|включи|выполни) (?:сценарий |сцену )?(.+)$").matchEntire(t)?.groupValues?.get(1)?.let { n ->
            data.scenarios.firstOrNull { normalize(it.name) == n }?.let { return Match.Scenario(it.id, "Сценарий «${it.name}»") }
        }
        // 2. Owner's own button phrases.
        for (d in data.devices) for (b in d.buttons) if (b.phrases.any { normalize(it) == t }) return Match.Press(d.id, b.id, "${d.name}: ${b.name}")
        // 3. A device named in the phrase + what to do with it.
        val words = t.split(' ')
        val candidates = data.devices.mapNotNull { d ->
            val nameWords = normalize(d.name).split(' ').filter { it.length >= 3 }
            if (nameWords.isEmpty()) return@mapNotNull null
            val used = nameWords.map { nw -> words.indexOfFirst { it.startsWith(stem(nw)) } }
            if (used.any { it < 0 }) null else d to used.toSet()
        }
        if (candidates.isEmpty()) return null
        val room = rooms.firstOrNull { r -> words.any { it.startsWith(r) } }
        val (device, used) = candidates.filter { (d, _) -> room == null || normalize(d.room).contains(room) || normalize(d.name).contains(room) }
            .maxByOrNull { it.second.size } ?: return null
        val rest = words.filterIndexed { i, w -> i !in used && w !in filler && (room == null || !w.startsWith(room)) }.joinToString(" ")
        if (rest.isEmpty()) return null
        device.buttons.firstOrNull { normalize(it.name) == rest }?.let { return Match.Press(device.id, it.id, "${device.name}: ${it.name}") }
        val function = IrFunction.entries.firstOrNull { f -> f.words.any { it == rest } } ?: return null
        val button = button(device, function) ?: return null
        return Match.Press(device.id, button.id, "${device.name}: ${button.name}")
    }

    /** Button for a function; «включи/выключи» fall back to the single power toggle when there are no separate buttons. */
    fun button(d: IrDevice, f: IrFunction): IrButton? = d.buttons.firstOrNull { it.function == f }
        ?: if (f == IrFunction.POWER_ON || f == IrFunction.POWER_OFF) d.buttons.firstOrNull { it.function == IrFunction.POWER } else null
}
