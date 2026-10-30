package com.example.homeezch.ai

/** Flat key/value persistence; the launcher backs it with SharedPreferences, tests with a map. */
interface EvaMemoryStore {
    fun read(): Map<String, String>
    fun write(values: Map<String, String>)
}

class InMemoryEvaMemoryStore(initial: Map<String, String> = emptyMap()) : EvaMemoryStore {
    private var values = initial.toMap()
    override fun read() = values
    override fun write(values: Map<String, String>) { this.values = values.toMap() }
}

/**
 * EVA personal memory (ТЗ §17): only what the owner allowed, stored on the TV, viewable, deletable,
 * switchable off. Never sent to cloud models. Holds spoken aliases («кино» → «зона»), the city and
 * how often each app is opened by voice (used to order choices, never to act on a guess).
 */
class EvaMemory(private val store: EvaMemoryStore) {
    private var values: MutableMap<String, String> = store.read().toMutableMap()
    private fun save() = store.write(values)

    val enabled: Boolean get() = values["off"] != "1"
    fun setEnabled(on: Boolean) {
        if (on) values.remove("off") else { values.keys.removeAll { it != "off" }; values["off"] = "1" }
        save()
    }

    fun alias(spoken: String): String? = if (!enabled) null else values["alias.${key(spoken)}"]
    fun aliases(): Map<String, String> = values.filterKeys { it.startsWith("alias.") }.mapKeys { it.key.removePrefix("alias.") }

    /** False when memory is off or the limits are exceeded. */
    fun remember(spoken: String, target: String): Boolean {
        val k = key(spoken); val v = normalize(target)
        if (!enabled || k.isBlank() || v.isBlank() || k.length > 40 || v.length > 60 || k == v) return false
        if ("alias.$k" !in values && aliases().size >= 50) return false
        values["alias.$k"] = v; save(); return true
    }
    fun forget(spoken: String): Boolean = (values.remove("alias.${key(spoken)}") != null).also { if (it) save() }
    fun forgetAll() { val off = values["off"]; values.clear(); off?.let { values["off"] = it }; save() }

    var city: String?
        get() = values["city"]
        set(value) { if (value == null) values.remove("city") else if (enabled) values["city"] = normalize(value).take(60); save() }

    fun recordUse(app: String) {
        if (!enabled) return
        val k = "use.${key(app)}"
        values[k] = ((values[k]?.toIntOrNull() ?: 0) + 1).coerceAtMost(9999).toString()
        if (values.keys.count { it.startsWith("use.") } > 100)
            values.keys.filter { it.startsWith("use.") }.minByOrNull { values[it]?.toIntOrNull() ?: 0 }?.let { values.remove(it) }
        save()
    }
    fun uses(app: String): Int = values["use.${key(app)}"]?.toIntOrNull() ?: 0

    /** What EVA would say for «что ты обо мне помнишь». */
    fun describe(): String {
        if (!enabled) return "Персональная память выключена. Скажите «включи память», чтобы я запоминала предпочтения"
        val parts = mutableListOf<String>()
        city?.let { parts += "ваш город — ${it.replaceFirstChar(Char::uppercase)}" }
        aliases().forEach { (k, v) -> parts += "«$k» — это $v" }
        val top = values.filterKeys { it.startsWith("use.") }.entries.sortedByDescending { it.value.toIntOrNull() ?: 0 }
            .take(3).map { it.key.removePrefix("use.") }
        if (top.isNotEmpty()) parts += "чаще всего вы открываете: ${top.joinToString()}"
        return if (parts.isEmpty()) "Пока ничего не запомнила. Скажите, например: «запомни, что кино — это Zona»"
            else "Я помню: " + parts.joinToString("; ")
    }

    private fun key(value: String) = normalize(value).trim('«', '»', '"', ' ')
}
