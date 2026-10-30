package com.example.homeezch.ai

import org.json.JSONObject

/** One Home Assistant entity as Ai EvA sees it. */
data class HomeEntity(val id: String, val name: String, val state: String, val attributes: JSONObject) {
    val domain: String get() = id.substringBefore('.')
    val on: Boolean get() = state == "on" || state == "open" || state == "unlocked" || state == "heat" || state == "cool"
}

/** Pure matching logic (JVM-tested): spoken target → the most likely entity. */
object SmartHomeMatcher {
    private val deviceWords = mapOf(
        "свет" to listOf("light", "switch"), "розетк" to listOf("switch"), "кондиционер" to listOf("climate"),
        "обогреватель" to listOf("climate", "switch"), "чайник" to listOf("switch"), "увлажнитель" to listOf("humidifier", "switch"),
        "вентилятор" to listOf("fan", "switch"), "бойлер" to listOf("water_heater", "switch"), "кофеварк" to listOf("switch"),
        "пылесос" to listOf("vacuum"))
    private val roomWords = mapOf("гостиная" to listOf("гостин", "зал", "living"), "спальня" to listOf("спальн", "bedroom"),
        "кухня" to listOf("кухн", "kitchen"), "детская" to listOf("детск", "kids", "child"), "прихожая" to listOf("прихож", "hall"),
        "коридор" to listOf("коридор", "corridor", "hall"), "ванная" to listOf("ванн", "bath"), "туалет" to listOf("туалет", "toilet", "wc"),
        "кабинет" to listOf("кабинет", "office", "study"), "балкон" to listOf("балкон", "balcony"), "двор" to listOf("двор", "yard"),
        "улица" to listOf("улиц", "outdoor", "outside"))

    fun domainsFor(target: String): List<String> =
        deviceWords.entries.firstOrNull { target.contains(it.key) }?.value ?: listOf("light", "switch")
    fun roomOf(target: String): String? = roomWords.keys.firstOrNull { target.contains(it) }

    private fun mentions(entity: HomeEntity, words: List<String>) =
        words.any { w -> normalize(entity.name).contains(w) || entity.id.lowercase().contains(w) }

    /** All entities the phrase refers to: «свет в гостиной» → every light in the living room; «свет» → every light. */
    fun find(target: String, entities: List<HomeEntity>): List<HomeEntity> {
        val domains = domainsFor(target)
        val room = roomOf(target)
        val device = deviceWords.keys.firstOrNull { target.contains(it) }
        var pool = entities.filter { it.domain in domains && it.state != "unavailable" }
        // A switch counts as «свет» only when its name says so (avoid switching sockets as lights).
        if (device == "свет") pool = pool.filter { it.domain == "light" || mentions(it, listOf("свет", "ламп", "light", "lamp", "люстр")) }
        // A named device («чайник», «розетка») must be named so in Home Assistant; dedicated domains (climate, vacuum) need no name.
        else if (device != null) pool = pool.filter { mentions(it, listOf(device, if (device == "розетк") "socket" else device)) || it.domain != "switch" }
        if (room != null) pool = pool.filter { mentions(it, roomWords.getValue(room)) }
        return pool
    }
    fun scene(name: String, entities: List<HomeEntity>): HomeEntity? {
        val wanted = normalize(name).removePrefix("сценарий ").trim()
        val scenes = entities.filter { it.domain == "scene" || it.domain == "script" }
        return scenes.firstOrNull { normalize(it.name) == wanted }
            ?: scenes.firstOrNull { normalize(it.name).contains(wanted) || wanted.contains(normalize(it.name)) }
            ?: scenes.firstOrNull { s -> wanted.split(' ').filter { it.length >= 4 }.any { normalize(s.name).contains(it.take(5)) } }
    }
    fun temperature(target: String, entities: List<HomeEntity>): HomeEntity? {
        val room = roomOf(target)
        val sensors = entities.filter { (it.domain == "sensor" && it.attributes.optString("device_class") == "temperature") ||
            (it.domain == "climate" && it.attributes.has("current_temperature")) }
            .filter { it.state != "unavailable" }
        val inRoom = if (room != null) sensors.filter { mentions(it, roomWords.getValue(room)) } else sensors
        // «дома» → prefer indoor sensors over outdoor ones.
        return inRoom.firstOrNull { !mentions(it, listOf("улиц", "outdoor", "outside", "двор")) } ?: inRoom.firstOrNull()
    }
}

