package com.example.homeezch.ai

/** Adapted from eva-power. Match complete commands, never keywords in a film/title. */
object PowerIntentParser {
    private const val TARGET = "(?:телевизор|телевизора|тв|телек|телик|экран|приставку|приставка|бокс|устройство|систему|андроид|себя)"
    private const val OFF = "(?:выключи|выключить|выключись|отключи|отключись|выруби|вырубись|погаси|усни|засыпай|усыпи)"
    private val off = Regex("$OFF(?: $TARGET)?|выключи все|спокойной ночи|пора спать|спать|сон|(?:переведи|перевести) $TARGET в спящий режим|(?:усыпи|спящий режим|в спящий режим|режим сна) $TARGET")
    private val reboot = Regex("(?:перезагрузи|перезагрузить|перезагрузись|перезапусти|перезапустить|перезапустись|ребутни|перегрузи|перезагрузка|рестарт|reboot|restart)(?: $TARGET)?|сделай рестарт")
    private val on = Regex("(?:включи|вруби|разбуди|запусти) $TARGET|проснись")
    fun parse(text: String): Action.Power? {
        if (text.length > 200) return null
        val t = text.lowercase(java.util.Locale.ROOT).replace('ё', 'е').replace(Regex("[,.!?«»\"…]"), " ")
            .replace(Regex("\\s+"), " ").trim().removePrefix("ева ").removePrefix("пожалуйста ").removeSuffix(" пожалуйста")
        if (t in setOf("отмени таймер", "отключи таймер", "убери таймер", "сбрось таймер", "отмени таймер сна", "отмени выключение", "не выключай", "не выключайся", "не засыпай")) return Action.Power("cancel_timer")
        if (t in setOf("сколько осталось до выключения", "сколько до выключения", "сколько осталось до сна", "сколько осталось на таймере", "когда выключишься")) return Action.Power("timer_left")
        val delayed = Regex("(.+?) через (.+)").matchEntire(t)
        if (delayed != null && (off.matches(delayed.groupValues[1]) || delayed.groupValues[1] in setOf("поставь таймер сна", "включи таймер сна", "таймер сна"))) {
            val minutes = duration(delayed.groupValues[2]) ?: return null
            return Action.Power("timer", minutes)
        }
        return when {
            reboot.matches(t) -> Action.Power("reboot")
            on.matches(t) -> Action.Power("wake")
            off.matches(t) -> Action.Power(if (t.contains("спящий режим")) "sleep" else "shutdown")
            else -> null
        }
    }
    private fun duration(t: String): Int? {
        when (t) { "полчаса" -> return 30; "час", "один час" -> return 60; "полтора часа", "полторачаса" -> return 90; "пару часов" -> return 120 }
        val m = Regex("(\\d+|одну|один|две|два|три|четыре|пять|десять|пятнадцать|двадцать|тридцать|сорок|сорок пять) (минуту|минуты|минут|час|часа|часов)").matchEntire(t) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: mapOf("одну" to 1L, "один" to 1L, "две" to 2L, "два" to 2L, "три" to 3L, "четыре" to 4L, "пять" to 5L, "десять" to 10L, "пятнадцать" to 15L, "двадцать" to 20L, "тридцать" to 30L, "сорок" to 40L, "сорок пять" to 45L)[m.groupValues[1]] ?: return null
        if (n !in 1..1440) return null
        val minutes = n * if (m.groupValues[2].startsWith("час")) 60 else 1
        return minutes.takeIf { it in 1..1440 }?.toInt()
    }
}
