package com.example.homeezch.ai

/**
 * Ai EvA natural-language layer: many everyday phrasings → one verifiable action.
 * Runs before the strict command rules; returns null when the phrase is not one of these intents,
 * so unknown phrases go on to the meaning layer ([EvaUnderstanding]) instead of a wrong action.
 */
object EvaNlu {
    val SETTINGS_SECTIONS = setOf("wifi", "bluetooth", "display", "sound", "storage", "apps", "general")
    val HOME_OPS = setOf("on", "off", "toggle", "brightness", "temperature", "set_temp", "scene", "status", "lock", "unlock", "panel")

    private val ordinal = mapOf("один" to 1, "одна" to 1, "первый" to 1, "первого" to 1, "1" to 1,
        "два" to 2, "второй" to 2, "второго" to 2, "2" to 2, "три" to 3, "третий" to 3, "третьего" to 3, "3" to 3,
        "четыре" to 4, "четвертый" to 4, "четвертого" to 4, "4" to 4)

    private fun plan(vararg actions: Action) = Resolution.Plan(actions.toList())
    private fun any(text: String, vararg patterns: String) = patterns.any { Regex(it).containsMatchIn(text) }

    /** Rooms and device words in the forms people actually say. */
    private val rooms = listOf("гостин" to "гостиная", "зал" to "гостиная", "спальн" to "спальня", "кухн" to "кухня",
        "детск" to "детская", "прихож" to "прихожая", "коридор" to "коридор", "ванн" to "ванная", "туалет" to "туалет",
        "кабинет" to "кабинет", "балкон" to "балкон", "двор" to "двор", "улиц" to "улица")
    fun room(text: String): String = rooms.firstOrNull { text.contains(it.first) }?.second.orEmpty()

    fun understand(raw: String): Resolution? {
        val t = normalize(raw).trimEnd('.', '!', '?').replace(Regex("(?<!\\p{L})(?:пожалуйста|давай|можешь|мне)(?!\\p{L})"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        smartHome(t)?.let { return it }

        // Volume: «сделай потише», «убавь звук», «звук погромче», «прибавь громкость»
        if (any(t, "^(?:сделай |сделать )?(?:по)?тише$", "^(?:убавь|уменьши|понизь|снизь)(?: звук| громкость)?$",
                "^(?:звук|громкость) (?:по)?тише$", "^(?:звук|громкость) (?:убавь|меньше|ниже)$", "^слишком громко$"))
            return plan(Action.Volume(step = -1))
        if (any(t, "^(?:сделай |сделать )?(?:по)?громче$", "^(?:прибавь|увеличь|повысь|добавь)(?: звук| громкость)?$",
                "^(?:звук|громкость) (?:по)?громче$", "^(?:звук|громкость) (?:прибавь|больше|выше)$", "^(?:плохо|не) слышно$", "^слишком тихо$"))
            return plan(Action.Volume(step = 1))
        if (any(t, "^(?:выключи|отключи|убери|вырубить|выруби) (?:звук|громкость)$", "^замолчи$", "^тишина$", "^без звука$", "^mute$"))
            return plan(Action.Volume(mute = true))
        if (any(t, "^(?:верни|включи) (?:звук|громкость)$"))
            return plan(Action.Volume(mute = false))
        // R27: «сделай громкость (телевизора) на максимум», «звук на полную», «громкость на минимум».
        val volumeTarget = "^(?:сделай |поставь |установи |включи |выкрути |убавь |прибавь )?(?:громкость|звук)(?: (?:телевизора|телевизор|тв|приставки))?(?: на| в)? "
        if (any(t, volumeTarget + "(?:максимум|максимальн[а-я]*|полную|всю(?: катушку)?|сто процентов)$", "^(?:на )?(?:всю|полную) (?:громкость|катушку)$", "^(?:сделай )?(?:по)?громче (?:на|до) (?:максимум|максимального|полную)$"))
            return plan(Action.Volume(percent = 100))
        if (any(t, volumeTarget + "(?:минимум|минимальн[а-я]*|самую тихую|чуть слышно)$"))
            return plan(Action.Volume(percent = 5))
        Regex("^(?:сделай |поставь |установи )?(?:громкость|звук)(?: на)? (\\d{1,3})(?: %| процентов| процента)?$").matchEntire(t)?.let {
            val p = it.groupValues[1].toInt(); return if (p in 0..100) plan(Action.Volume(percent = p)) else null
        }

        // Playback
        if (any(t, "^(?:поставь |поставить )?(?:на )?паузу?$", "^(?:останови|приостанови|остановить)(?: видео| фильм| воспроизведение)?$",
                "^стоп (?:видео|фильм)$", "^замри$"))
            return plan(Action.Media(false))
        if (any(t, "^(?:продолжи|продолжай|продолжить|играй дальше|сними с паузы|сними паузу|сними с пауз|возобнови)(?: просмотр| воспроизведение| видео)?$",
                "^(?:включи|запусти) (?:дальше|воспроизведение)$"))
            return plan(Action.Media(true))

        // Channels
        if (any(t, "^(?:переключи |включи )?(?:на )?следующий канал$", "^(?:канал|каналы) (?:вперед|дальше|плюс)$", "^переключи канал$", "^следующий$"))
            return plan(Action.ChannelStep(1))
        if (any(t, "^(?:переключи |включи |верни )?(?:на )?(?:предыдущий|прошлый) канал$", "^(?:канал|каналы) (?:назад|минус)$", "^предыдущий$"))
            return plan(Action.ChannelStep(-1))

        // Inputs: «включи HDMI 1», «переключись на эйч ди эм ай два», «вход AV»
        val inputText = t.replace(Regex("(?:эйч ?ди ?эм ?ай|аш ?ди ?эм ?ай|хдми|hdmi)"), "hdmi").replace(Regex("(?<!\\p{L})(?:ав|эй ви)(?!\\p{L})"), "av")
        Regex("^(?:включи |переключи(?:сь)? |переключи телевизор |включи телевизор |открой |выбери )?(?:на )?(?:вход |источник |порт )?(hdmi|av)(?: ?(\\d|один|два|три|четыре|первый|второй|третий|четвертый))?$")
            .matchEntire(inputText)?.let { m ->
                val number = m.groupValues[2].takeIf { it.isNotBlank() }?.let { ordinal[it] ?: it.toIntOrNull() }
                return plan(Action.SwitchInput(m.groupValues[1], number))
            }
        if (any(t, "^(?:включи |переключи(?:сь)? |открой )?(?:на )?(?:эфирное |цифровое )?(?:тв|телевидение|эфир|тюнер)$"))
            return plan(Action.SwitchInput("tv"))

        // Settings sections
        val settingsMatch = Regex("^(?:(?:открой|покажи|зайди в|перейди в|включи) )?(?:настройк[иау]|параметры)(?: (.+))?$").matchEntire(t)
        if (settingsMatch != null) {
            val what = settingsMatch.groupValues[1]
            val section = when {
                what.isBlank() -> return plan(Action.Settings)
                any(what, "wi-?fi", "вай ?фай", "сет", "интернет") -> "wifi"
                any(what, "bluetooth", "блют", "блю тус") -> "bluetooth"
                any(what, "изображ", "картинк", "экран", "дисплe", "дисплей", "яркост") -> "display"
                any(what, "звук", "аудио") -> "sound"
                any(what, "памят", "хранилищ", "диск") -> "storage"
                any(what, "приложен") -> "apps"
                any(what, "телевизор", "тв", "систем", "устройств") -> "general"
                else -> return null
            }
            return plan(Action.OpenSettings(section))
        }

        // Apps and history
        if (any(t, "^какие (?:у меня )?(?:есть )?приложения(?: (?:есть|установлены|стоят))?$", "^(?:покажи|перечисли|список)(?: все)? (?:установленные )?приложени[яй]$",
                "^что (?:у меня )?установлено$", "^какие программы (?:есть|установлены)$"))
            return plan(Action.ListApps)
        if (any(t, "^(?:открой|запусти|верни|включи) (?:последнее|прошлое|предыдущее)(?: приложение)?$", "^(?:вернись|верни) (?:назад )?в (?:последнее|прошлое) приложение$",
                "^что я (?:смотрел|открывал) последн"))
            return plan(Action.LastApp)

        // Play
        if (any(t, "^(?:поиграй|поиграем|сыграем|давай поиграем|давай сыграем)(?: со мной| в игру)?$", "^(?:мне )?скучно$", "^загадай загадку$", "^во что поиграть$",
                "^(?:а )?(?:ты )?умеешь играть(?: в игр[уы])?$", "^давай (?:играть|в игру)$", "^сыграем в игру$"))
            return plan(Action.PlayGame)

        // R27 cleanup: «очисти мусор от телевизора», «почисти телевизор», «освободи память» → quick cleanup now.
        if (any(t, "^(?:очисти|почисти|вычисти|убери|удали|освободи)(?: (?:весь |все )?(?:мусор|память|оперативку|кэш|кеш|телевизор|приставку|систему|лишнее))+(?: (?:от|на|в|с) (?:телевизор[а-я]*|приставк[а-я]*))?$",
                "^(?:сделай|проведи|запусти) (?:очистку|чистку)(?: (?:телевизора|приставки|памяти))?$", "^(?:закрой|выключи) (?:фоновые|все) приложения$"))
            return plan(Action.ClearCache)
        // «ускорь телевизор», «оптимизируй приставку» → the optimization screen (cleanup + ads plan).
        if (any(t, "^(?:ускорь|оптимизируй|разгони)(?: (?:телевизор|приставку|систему|работу))?$", "^(?:сделай|проведи) оптимизацию$", "^(?:универсальная )?чистка$"))
            return plan(Action.Optimize("ads"))

        // R27 search: «открыл поиск и найди фильм мстители», «поиск мстителей».
        Regex("^(?:открой|открыл|открою|зайди в|включи) поиск(?: и| а)? (.+)$").matchEntire(t)?.let { m ->
            val rest = m.groupValues[1]
            understand(rest)?.let { return it }
            val q = rest.replace(Regex("^(?:найди|поищи|найти|покажи|ищи)\\s+"), "").replace(Regex("^(?:мне )?(?:фильм|сериал|мультфильм|мультик|видео|кино)\\s+"), "").trim()
            if (q.isNotEmpty()) return plan(Action.Search(q))
        }
        Regex("^поиск (?:фильма |сериала |мультфильма )?(.+)$").matchEntire(t)?.let { m -> return plan(Action.Search(m.groupValues[1])) }
        return null
    }

    private fun smartHome(t: String): Resolution? {
        val light = "(?:свет|светильник|лампу|лампы|лампа|люстру|люстра|освещение|подсветку|подсветка|торшер|бра)"
        val device = "(?:свет|светильник|лампу|люстру|освещение|подсветку|торшер|розетку|розетка|кондиционер|обогреватель|чайник|увлажнитель|вентилятор|бойлер|кофеварку|робот[а-я]* пылесос|пылесос)"
        Regex("^(?:включи|зажги|вруби|включить) ($device)(?: (?:в|на) (.+))?$").matchEntire(t)?.let { m ->
            return plan(Action.Home("on", target(m.groupValues[1], m.groupValues[2])))
        }
        Regex("^(?:выключи|погаси|выруби|отключи|выключить) ($device)(?: (?:в|на) (.+))?$").matchEntire(t)?.let { m ->
            return plan(Action.Home("off", target(m.groupValues[1], m.groupValues[2])))
        }
        Regex("^(?:сделай |поставь |установи )?(?:яркость(?: света)?|$light|свет)(?: (?:в|на) ([а-я]+))?(?: на| в)? (\\d{1,3})(?: %| процентов| процента)?$").matchEntire(t)?.let { m ->
            val v = m.groupValues[2].toInt(); if (v !in 0..100) return null
            return plan(Action.Home("brightness", target("свет", m.groupValues[1]), v))
        }
        Regex("^(?:приглуши|убавь|сделай тусклее|сделай темнее) (?:свет|освещение)(?: (?:в|на) (.+))?$").matchEntire(t)?.let { m ->
            return plan(Action.Home("brightness", target("свет", m.groupValues[1]), 30))
        }
        // Air conditioner / heater target temperature (IR via Яндекс Пульт or Tuya, or a smart AC).
        Regex("^(?:поставь|сделай|установи|включи) (?:кондиционер|обогреватель|температуру(?: на кондиционере)?)(?: на)? (\\d{2})(?: градус[а-я]*)?$").matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toInt(); if (v !in 16..30) return null
            return plan(Action.Home("set_temp", if (t.contains("обогреват")) "обогреватель" else "кондиционер", v))
        }
        // Robot vacuum.
        if (any(t, "^(?:запусти|начни|включи) (?:уборку|пылесос|робот[а-я]* пылесос)$", "^пропылесось(?: дома| квартиру)?$", "^(?:сделай|проведи) уборку$"))
            return plan(Action.Home("on", "пылесос"))
        if (any(t, "^(?:отправь|верни) (?:пылесос|робота) (?:на базу|на зарядку|домой)$", "^(?:останови|закончи|выключи) уборку$"))
            return plan(Action.Home("off", "пылесос"))
        Regex("^(?:какая|сколько) (?:сейчас )?(?:температура|градусов)(?: (?:дома|в доме|в квартире|в ([а-я]+)))$").matchEntire(t)?.let { m ->
            return plan(Action.Home("temperature", room(m.groupValues[1]).ifBlank { "дом" }))
        }
        Regex("^(?:запусти|включи|активируй) (?:сценарий|сцену|режим) (.+)$").matchEntire(t)?.let { m ->
            return plan(Action.Home("scene", m.groupValues[1].removeSuffix(" сценарий")))
        }
        Regex("^(?:запусти|включи) (.+) (?:сценарий|режим)$").matchEntire(t)?.let { m -> return plan(Action.Home("scene", m.groupValues[1])) }
        Regex("^(?:открой|отопри|разблокируй) (?:замок|дверь)(?: (.+))?$").matchEntire(t)?.let { m -> return plan(Action.Home("unlock", m.groupValues[1])) }
        Regex("^(?:закрой|запри|заблокируй) (?:замок|дверь)(?: (.+))?$").matchEntire(t)?.let { m -> return plan(Action.Home("lock", m.groupValues[1])) }
        if (any(t, "^(?:открой|покажи|включи) (?:умный дом|раздел умный дом|управление домом)$"))
            return plan(Action.Home("panel"))
        if (any(t, "^(?:что|какие устройства|где) (?:включено|включены|горит свет) дома$", "^(?:состояние|статус) (?:дома|умного дома)$", "^где горит свет$"))
            return plan(Action.Home("status"))
        return null
    }
    private fun target(device: String, place: String): String {
        val r = room(place)
        val d = device.replace(Regex("^(свет|светильник|лампу|лампы|лампа|люстру|люстра|освещение|подсветку|подсветка|торшер|бра)$"), "свет")
        return if (r.isBlank()) d else "$d $r"
    }
}
