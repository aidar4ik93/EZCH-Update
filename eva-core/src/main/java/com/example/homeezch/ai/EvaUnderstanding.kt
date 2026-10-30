package com.example.homeezch.ai

/**
 * EVA Understanding — the offline meaning layer of the EVA AI Core.
 *
 * The strict rules know exact phrasings; real speech is looser: «громко 20», «звук на двадцать»,
 * «сделай ярче», «открой его», «ютуп». This layer reads the meaning instead of the wording:
 *  1. slots — what is being changed (громкость, яркость, канал), which way (больше / меньше), the number,
 *     a pronoun («его», «это») that points to the app opened before;
 *  2. remarks about EVA herself («ударение неправильно», «имя своё»);
 *  3. the nearest known command: character-trigram similarity with a catalog of commands, so a
 *     one-word recognition slip still lands. A weak match is never executed: EVA asks «Вы имели в виду …?».
 * Pure Kotlin, no Android, no network: every rule is covered by JVM tests.
 */
object EvaUnderstanding {
    sealed interface Guess {
        /** Confident: run it. */
        data class Sure(val actions: List<Action>) : Guess
        /** Probably meant [phrase]: EVA asks first, «да» runs it. */
        data class Maybe(val actions: List<Action>, val phrase: String) : Guess
        /** Not a command, but EVA has something useful to say. */
        data class Answer(val text: String) : Guess
    }

    private val volume = listOf("громк", "громч", "погромч", "звук", "звуч", "громо", "тиш", "потиш", "тихо", "слышно")
    private val brightness = listOf("ярк", "ярч", "светле", "темне", "подсветк", "контраст")
    private val lightDevices = listOf("ламп", "люстр", "торшер", "освещ", "светильник", "гостин", "спальн", "кухн", "комнат")
    private val up = listOf("громч", "погромч", "больш", "побольш", "повыш", "прибав", "увелич", "добав", "повыс", "выше", "плюс", "ярч", "ярче",
        "светле", "подним", "сильнее")
    private val down = listOf("тиш", "потиш", "меньш", "поменьш", "пониж", "убав", "уменьш", "пониз", "сниз", "ниже", "минус", "темне", "опусти",
        "приглуш", "слабее")
    private val off = listOf("выключ", "отключ", "убери", "выруб", "без", "замолч")
    private val on = listOf("включ", "верни", "вруби")
    private val openVerbs = setOf("открой", "открою", "запусти", "включи", "верни", "вернись", "вернуть", "открыть", "запустить", "покажи", "зайди")
    private val pronouns = setOf("его", "ее", "её", "это", "то", "тот", "туда", "обратно", "приложение", "снова", "опять", "же", "назад",
        "в", "него", "нее", "неё", "предыдущее", "прошлое")

    /** Canonical command phrases. Only commands that are safe to run on a strong match are here. */
    private val catalog: List<Pair<String, List<Action>>> by lazy {
        listOf(
            "громче" to listOf(Action.Volume(step = 1)), "сделай громче" to listOf(Action.Volume(step = 1)),
            "тише" to listOf(Action.Volume(step = -1)), "сделай тише" to listOf(Action.Volume(step = -1)),
            "выключи звук" to listOf(Action.Volume(mute = true)), "включи звук" to listOf(Action.Volume(mute = false)),
            "пауза" to listOf(Action.Media(false)), "поставь на паузу" to listOf(Action.Media(false)),
            "продолжи" to listOf(Action.Media(true)), "продолжить просмотр" to listOf(Action.Media(true)),
            "следующий канал" to listOf(Action.ChannelStep(1)), "предыдущий канал" to listOf(Action.ChannelStep(-1)),
            "открой настройки" to listOf(Action.Settings), "настройки" to listOf(Action.Settings),
            "настройки звука" to listOf(Action.OpenSettings("sound")), "настройки изображения" to listOf(Action.OpenSettings("display")),
            "настройки wifi" to listOf(Action.OpenSettings("wifi")), "настройки интернета" to listOf(Action.OpenSettings("wifi")),
            "настройки bluetooth" to listOf(Action.OpenSettings("bluetooth")), "настройки памяти" to listOf(Action.OpenSettings("storage")),
            "какая погода" to listOf(Action.Weather), "погода" to listOf(Action.Weather),
            "проверь обновления" to listOf(Action.Updates), "обновления" to listOf(Action.Updates),
            "очисти кэш" to listOf(Action.ClearCache), "очисти память" to listOf(Action.ClearCache),
            "какие приложения установлены" to listOf(Action.ListApps),
            "открой последнее приложение" to listOf(Action.LastApp),
            "на главный экран" to listOf(Action.Navigate(Direction.HOME)), "домой" to listOf(Action.Navigate(Direction.HOME)),
            "назад" to listOf(Action.Navigate(Direction.BACK)),
            "вправо" to listOf(Action.Navigate(Direction.RIGHT)), "влево" to listOf(Action.Navigate(Direction.LEFT)),
            "вверх" to listOf(Action.Navigate(Direction.UP)), "вниз" to listOf(Action.Navigate(Direction.DOWN)),
            "включи hdmi 1" to listOf(Action.SwitchInput("hdmi", 1)), "включи hdmi 2" to listOf(Action.SwitchInput("hdmi", 2)),
            "включи hdmi 3" to listOf(Action.SwitchInput("hdmi", 3)), "эфирное телевидение" to listOf(Action.SwitchInput("tv")),
            "проверь интернет" to listOf(Action.Diagnose("network")), "проверь телевизор" to listOf(Action.Diagnose("performance")),
            "давай поиграем" to listOf(Action.PlayGame)
        )
    }

    fun guess(text: String, lastApp: String? = null, apps: List<AppTarget> = emptyList()): Guess? {
        val words = EvaLexicon.tokens(EvaLexicon.canonical(text))
        if (words.isEmpty() || words.size > 12) return null
        remark(EvaLexicon.tokens(text))?.let { return it }
        reference(words, lastApp)?.let { return it }
        slots(words)?.let { return it }
        nearest(words)?.let { return it }
        appByName(words, apps)?.let { return it }
        return null
    }

    private fun has(words: List<String>, stems: List<String>) = stems.any { EvaLexicon.hasStem(words, it) }

    /** «открой его», «верни то приложение», «включи обратно» → the app opened before. */
    fun reference(words: List<String>, lastApp: String?): Guess? {
        if (words.size > 5 || words.first() !in openVerbs) return null
        val rest = words.drop(1)
        if (rest.isEmpty() || !rest.all { it in pronouns }) return null
        if (rest.all { it in setOf("назад", "в") }) return null // «вернись назад» is navigation
        return Guess.Sure(listOf(if (lastApp != null) Action.Launch(lastApp) else Action.LastApp))
    }

    /** What, which way, how much. */
    private fun slots(words: List<String>): Guess? {
        val number = words.firstNotNullOfOrNull { it.trimEnd('%').toIntOrNull() }
        val isVolume = has(words, volume) && !has(words, lightDevices)
        val isBrightness = has(words, brightness) && !has(words, lightDevices) && !isVolume
        var more = has(words, up); var less = has(words, down)
        val too = words.any { it in setOf("слишком", "очень", "плохо", "не", "совсем") }
        // «слишком тихо» / «не слышно» asks for louder; «очень громко» for quieter.
        if (too && ("тихо" in words || "слышно" in words)) { more = true; less = false }
        if (too && "громко" in words) { more = false; less = true }
        if (isVolume) {
            if (number != null) return if (number in 0..100) Guess.Sure(listOf(Action.Volume(percent = number)))
                else Guess.Answer("Громкость бывает от 0 до 100. Скажите, например: «громкость 20»")
            if (more != less) return Guess.Sure(listOf(Action.Volume(step = if (more) 1 else -1)))
            if (has(words, off) && !has(words, on)) return Guess.Sure(listOf(Action.Volume(mute = true)))
            if (has(words, on)) return Guess.Sure(listOf(Action.Volume(mute = false)))
            return null
        }
        if (isBrightness) {
            // Android does not let apps change the TV backlight: the picture settings are the honest way.
            return Guess.Sure(listOf(Action.OpenSettings("display")))
        }
        if (has(words, listOf("канал")) && number == null) {
            if (has(words, listOf("след", "вперед", "дальш", "плюс"))) return Guess.Sure(listOf(Action.ChannelStep(1)))
            if (has(words, listOf("пред", "прошл", "минус", "обрат"))) return Guess.Sure(listOf(Action.ChannelStep(-1)))
        }
        return null
    }

    /** Remarks about EVA herself: answered, never turned into an action. */
    private fun remark(raw: List<String>): Guess? {
        val t = raw.joinToString(" ")
        if (raw.isEmpty() || raw.size > 10) return null
        if (has(raw, listOf("ударен")) || has(raw, listOf("неправильно", "непонятно", "невнятно", "коверкаешь", "картав")) &&
            has(raw, listOf("говоришь", "говорит", "произнос", "читаешь", "разговар", "голос", "речь")))
            return Guess.Answer("Простите. Говорю голосом синтезатора телевизора. В настройках Евы можно выбрать другой голос и скорость речи")
        if (has(raw, listOf("имя", "зовут")) && raw.size <= 4 && !has(raw, listOf("запомни")))
            return Guess.Answer("Меня зовут Ева. Я голосовой помощник этого телевизора")
        if (Regex("^(?:ты )?(?:меня )?(?:слышишь|слышно|понимаешь)(?: меня)?$").matches(t)) return Guess.Answer("Да, слышу. Скажите команду")
        if (Regex("^(?:ты )?(?:не то|не так|не это|неправильно|ошиблась|ошибка|не поняла|опять не то)$").matches(t))
            return Guess.Answer("Извините. Повторите, пожалуйста, чуть иначе, например: «громкость 20» или «открой YouTube»")
        if (Regex("^(?:молодец|умница|отлично|супер|класс|спасибо(?: ева)?)$").matches(t)) return Guess.Answer("Спасибо! Обращайтесь")
        if (Regex("^(?:да|нет|ага|угу|хорошо|ладно|ок|окей|понятно|ясно)$").matches(t)) return Guess.Answer("Хорошо. Что сделать?")
        if (Regex("^(?:спокойной ночи|доброй ночи|пока|до свидания|до встречи)$").matches(t)) return Guess.Answer("До встречи! Позовите, когда понадоблюсь")
        if (Regex("^(?:мне )?(?:грустно|скучно одному|плохо|устал|устала|я устал|я устала)$").matches(t)) return Guess.Answer("Могу включить что-нибудь спокойное. Скажите, например: «включи музыку для отдыха»")
        return null
    }

    /** The closest catalog command by sound-alike spelling. */
    private fun nearest(words: List<String>): Guess? {
        if (words.size > 4) return null
        val phrase = words.joinToString(" ")
        var best: Pair<String, List<Action>>? = null; var score = 0.0
        for (entry in catalog) {
            if (kotlin.math.abs(entry.first.split(' ').size - words.size) > 1) continue
            val s = similarity(phrase, entry.first)
            if (s > score) { score = s; best = entry }
        }
        val hit = best ?: return null
        return when {
            score >= SURE -> Guess.Sure(hit.second)
            score >= MAYBE -> Guess.Maybe(hit.second, hit.first)
            else -> null
        }
    }

    /** A bare app name («ютуп», «кинопоиск») means «open it»; a near miss is asked first. */
    private fun appByName(words: List<String>, apps: List<AppTarget>): Guess? {
        if (apps.isEmpty() || words.size > 3) return null
        val name = (if (words.first() in openVerbs) words.drop(1) else words).joinToString(" ")
        if (name.length < 3) return null
        return when (val m = matchApp(name, apps)) {
            is AppMatch.Unique -> Guess.Sure(listOf(Action.Launch(m.app.label)))
            is AppMatch.Suggested -> m.apps.singleOrNull()?.let { Guess.Maybe(listOf(Action.Launch(it.label)), "открой ${it.label}") }
            else -> null
        }
    }

    /** Dice coefficient over character trigrams of the padded phrases (0..1). */
    fun similarity(a: String, b: String): Double {
        fun grams(s: String): List<String> { val p = "  $s "; return (0..p.length - 3).map { p.substring(it, it + 3) } }
        val x = grams(a); val y = grams(b).toMutableList()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var common = 0
        for (g in x) { val i = y.indexOf(g); if (i >= 0) { common++; y.removeAt(i) } }
        return 2.0 * common / (x.size + grams(b).size)
    }

    /** A spoken question EVA cannot answer offline (no internet AI by design). */
    fun isQuestion(text: String): Boolean = Regex("^(?:а )?(?:кто|что|почему|зачем|как|какой|какая|какое|какие|сколько|где|когда|куда|откуда|чем|чей|расскажи|объясни|посоветуй|подскажи|скажи)(?:\\s|$)")
        .containsMatchIn(normalize(text))

    /** EVA's honest answer when nothing matched. */
    fun fallback(text: String): String {
        val heard = text.trim().take(60)
        return if (isQuestion(text)) "Чтобы я разговаривала на любые темы, скачайте мою нейросеть в настройках Евы: она работает без интернета и без ключей. А пока могу сказать погоду, время, посчитать или выполнить команду"
        else "Не поняла: «$heard». Скажите, например: «громкость 20», «открой YouTube» или «следующий канал»"
    }

    private const val SURE = 0.78
    private const val MAYBE = 0.55
}
