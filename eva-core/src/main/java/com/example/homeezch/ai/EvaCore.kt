package com.example.homeezch.ai

/** What EVA does with one utterance. The launcher speaks/executes it; the core never touches Android. */
sealed interface EvaReply {
    /** Validated plan, run without questions (ordinary commands never ask «да/нет»). */
    data class Execute(val actions: List<Action>) : EvaReply
    /** A risky/privacy-sensitive plan: EVA asks first; «да» in the next turn runs it. */
    data class Confirm(val actions: List<Action>, val question: String) : EvaReply
    /** Not sure what was meant: one short question; the next answer («громче», «второй») resolves it. */
    data class Clarify(val question: String) : EvaReply
    /** Answered offline by the core itself (memory, small talk, limits). */
    data class Say(val text: String) : EvaReply
    /** Not a command the core knows: a free conversation turn for GigaChat; [offline] is said when it is unavailable. */
    data class Chat(val text: String, val offline: String) : EvaReply
}

/**
 * EVA AI Core (ТЗ §7–8): independent offline brain of Ai EvA.
 *
 * Route of one utterance: pending question → memory commands → dialog follow-ups («ещё», «наоборот»,
 * «а на 40») → personal aliases and intents the strict rules would misroute → strict rules
 * ([LocalIntentResolver], raw then lexically repaired text) → meaning frames («телевизор тормозит»,
 * «убери рекламу») → clarifying question for half-said commands → offline small talk → meaning layer
 * ([EvaUnderstanding]: slots, pronouns, nearest command) → an honest «не поняла» with examples.
 * Works fully offline: no cloud models, no home PC. Never guesses a risky action; never sends memory anywhere.
 */
class EvaCore(
    val memory: EvaMemory = EvaMemory(InMemoryEvaMemoryStore()),
    private val resolver: IntentResolver = LocalIntentResolver(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    // R30 «как Джарвис»: what was searched last and where, so «а теперь в зоне», «не тот, второй» continue it.
    private var lastQuery: String? = null
    private var lastQueryAt = 0L
    private var lastCinema: String? = null
    private class Pending(val options: List<Action>, val stems: List<List<String>>, val apps: List<AppTarget>,
        val confirm: Boolean, val at: Long)
    private var pending: Pending? = null
    private var last: List<Action> = emptyList()
    private var lastAt = 0L
    /** Free conversation (GigaChat) is configured and allowed by the owner; set by the launcher. */
    @Volatile var chatEnabled: () -> Boolean = { false }
    /** Short dialog memory for GigaChat; cleared with the dialog. */
    val chat = EvaChatSession(clock)
    /** The app EVA opened last: «открой его», «верни то приложение». */
    var lastApp: String? = null
        private set

    /** Known film/TV apps for «включи кино» (canonical names as [matchApp] understands them). */
    private val cinemaApps = listOf("zona", "num", "lampa", "lift", "kinotrend", "serialtrend", "vokino", "prisma",
        "кинопоиск", "okko", "ivi", "wink", "кион", "premier", "start", "rutube", "hdrezka", "filmix")

    // R28 riddle game: EVA asks, waits for the user's answer, then says whether it is right.
    private class Game(val riddle: EvaRiddles.Riddle, var tries: Int = 0, var hinted: Boolean = false, val at: Long, val offerMore: Boolean = false)
    private var game: Game? = null
    private var riddleIndex = -1

    private fun newRiddle(now: Long, intro: String = "Давай! Загадка: "): EvaReply {
        val all = EvaRiddles.ALL
        var i = ((now / 1000) % all.size).toInt()
        if (i == riddleIndex) i = (i + 1) % all.size
        riddleIndex = i
        val r = all[i]; game = Game(r, at = now)
        return EvaReply.Clarify("$intro${r.question} Как думаешь, что это?")
    }

    fun handle(text: String, apps: () -> List<AppTarget> = { emptyList() }): EvaReply {
        val now = clock()
        val g = game
        if (g != null && now - g.at <= GAME_MS && text.isNotBlank()) {
            game = null
            val said = text.trim()
            if (g.offerMore) {
                if (EvaRiddles.wantsMore(said)) return newRiddle(now, "Слушай: ")
                if (EvaRiddles.wantsStop(said) || Regex("^(?:нет|не надо|нет спасибо)$").matches(normalize(said))) return EvaReply.Say("Хорошо, поиграем в другой раз!")
            } else {
                val r = g.riddle
                if (EvaRiddles.correct(r, said)) {
                    game = Game(r, at = now, offerMore = true)
                    return EvaReply.Clarify("Верно! Это ${r.answer}. Молодец! Загадать ещё?")
                }
                if (EvaRiddles.wantsStop(said)) return EvaReply.Say("Хорошо! Ответ был: ${r.answer}. Поиграем в другой раз")
                if (EvaRiddles.wantsGiveUp(said)) {
                    game = Game(r, at = now, offerMore = true)
                    return EvaReply.Clarify("Ответ: ${r.answer}. Загадать ещё?")
                }
                if (EvaRiddles.wantsHint(said) && !g.hinted) {
                    game = Game(r, g.tries, hinted = true, at = now)
                    return EvaReply.Clarify("Подсказка: ${r.hint}. Что это?")
                }
                // A real command in the middle of the game ends the game and is carried out.
                val decided = decide(text, apps, now)
                if (decided is EvaReply.Execute && decided.actions.none { it is Action.PlayGame }) return decided
                // «который час», «какая погода» mid-game: answer the question; the game ends.
                if (decided is EvaReply.Say && EvaSmallTalk.answer(text) != null) return decided
                if (g.tries == 0) {
                    game = Game(r, tries = 1, hinted = g.hinted, at = now)
                    return EvaReply.Clarify(if (g.hinted) "Нет, не угадал. Попробуй ещё раз!" else "Нет, не угадал. Попробуй ещё раз или скажи «подскажи»")
                }
                game = Game(r, at = now, offerMore = true)
                return EvaReply.Clarify("Не угадал. Правильный ответ: ${r.answer}. Загадать ещё?")
            }
        }
        val reply = chain(text, apps, now) ?: decide(text, apps, now)
        if (reply is EvaReply.Execute && reply.actions.isNotEmpty() && reply.actions.all { it is Action.PlayGame }) return newRiddle(now)
        return reply
    }

    /**
     * R30: two commands in one breath — «сделай потише и открой ютуб», «выключи звук, а потом открой зону».
     * Used only when each part alone is a plain command and the whole phrase is not one media request
     * («открой ютуб и включи рамштайн» stays a single YouTube play).
     */
    private fun chain(text: String, apps: () -> List<AppTarget>, now: Long): EvaReply? {
        val t = normalize(text).trimEnd('.', '!', '?')
        val parts = t.split(Regex(",? (?:и|а потом|потом|затем|и потом|а затем) |, ")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size !in 2..3) return null
        val whole = resolver.resolve(t)
        if (whole is Resolution.Plan && (whole.actions.size >= 2 || whole.actions.any { it is Action.PlayOn || it is Action.Search || it is Action.PlayRequest })) return null
        if (EvaMedia.understand(t) != null) return null
        val plans = parts.map { p -> (resolver.resolve(p) as? Resolution.Plan)?.actions ?: return null }
        if (plans.any { a -> a.any { it is Action.PlayRequest } }) return null
        return remember(EvaReply.Execute(plans.flatten()), now)
    }

    private fun decide(text: String, apps: () -> List<AppTarget>, now: Long): EvaReply {
        if (text.length > 600 || text.any { it.isISOControl() && !it.isWhitespace() }) return EvaReply.Say("Команда слишком длинная")
        val raw = trimEdges(EvaSpeechText.clean(text).trimEnd('.', '!', '?', ' '))
        if (raw.isBlank()) return EvaReply.Say("Я слушаю. Скажите команду")
        val words = EvaLexicon.tokens(raw)

        pending?.let { p ->
            pending = null
            if (now - p.at <= PENDING_MS) answerPending(p, words)?.let { return remember(it, now, confirmed = p.confirm) }
        }
        memoryCommand(words)?.let { return it }
        // GigaChat asked «Включить?» a moment ago: «да», «давай второй», «нет, другой» continue that conversation.
        if (chatEnabled() && chat.awaitsReply() && chat.lastTurnAt >= lastAt && words.size <= 6 &&
            resolver.resolve(raw) !is Resolution.Plan) return EvaReply.Chat(raw, "Не поняла. Повторите, пожалуйста")
        followUp(EvaLexicon.tokens(EvaLexicon.canonical(raw)), words, now)?.let { return remember(it, now) }
        // «открой его»: a pronoun is not an app name.
        (EvaUnderstanding.reference(EvaLexicon.tokens(EvaLexicon.canonical(raw)), lastApp) as? EvaUnderstanding.Guess.Sure)
            ?.let { return remember(EvaReply.Execute(it.actions), now) }
        personal(words)?.let { return remember(it, now) }
        film(normalize(raw).replace(Regex("[,.!?-]"), " ").replace(Regex("\\s+"), " ").trim(), apps, now)?.let { return remember(it, now) }
        cinema(EvaLexicon.tokens(EvaLexicon.simplify(raw)), apps, now)?.let { return remember(it, now) }
        preFrames(words, now)?.let { return remember(it, now) }
        power(EvaLexicon.tokens(EvaLexicon.simplify(EvaLexicon.correct(raw))))?.let { return remember(it, now) }
        limits(words)?.let { return it }

        // «включи ютуб и найди рамштайн»: a chain, not a song called «ютуб и найди рамштайн».
        Regex("^включи (.+?)(?:,| и | затем ) ?((?:найди|поищи|выбери) .+)$").matchEntire(normalize(raw))?.let {
            val chain = resolver.resolve("открой ${it.groupValues[1]}, ${it.groupValues[2]}")
            if (chain is Resolution.Plan) return remember(EvaReply.Execute(chain.actions), now)
        }
        val strict = resolver.resolve(raw)
        val canonical = EvaLexicon.canonical(raw)
        if (strict is Resolution.Plan) {
            // Recognizers hear «открой надстройки», «настроики»: the strict rules would launch a missing app.
            // When no such app is installed and the corrected phrase means something else, the correction wins.
            val launches = strict.actions.filterIsInstance<Action.Launch>()
            // «поставь громкость 15 процентав»: a recognition slip turned a command into «включи <что-то>» on YouTube.
            if (strict.actions.any { it is Action.PlayRequest || it is Action.PlayChannel } && canonical != normalize(raw)) {
                val repaired = resolver.resolve(canonical)
                if (repaired is Resolution.Plan && repaired.actions.none { it is Action.PlayRequest || it is Action.PlayChannel }) return remember(EvaReply.Execute(repaired.actions), now)
                (EvaUnderstanding.guess(canonical, lastApp) as? EvaUnderstanding.Guess.Sure)?.let { g ->
                    if (g.actions.none { it is Action.Launch }) return remember(EvaReply.Execute(g.actions), now)
                }
            }
            if (launches.isNotEmpty() && canonical != normalize(raw)) {
                val installed = apps()
                if (launches.any { matchApp(it.name, installed) == AppMatch.Missing }) {
                    val repaired = resolver.resolve(canonical)
                    if (repaired is Resolution.Plan && repaired.actions != strict.actions) return remember(EvaReply.Execute(repaired.actions), now)
                }
            }
            // «открой ютуп»-like slips of a single app name: ask about the closest installed app instead of failing.
            if (launches.size == 1 && strict.actions.size == 1) {
                val installed = apps()
                if (installed.isNotEmpty() && matchApp(launches.single().name, installed) == AppMatch.Missing)
                    return EvaReply.Say("Не нашла приложение «${launches.single().name}». Скажите название ещё раз или откройте «Все приложения»")
            }
            return remember(EvaReply.Execute(strict.actions), now)
        }
        if (canonical != normalize(raw)) {
            val repaired = resolver.resolve(canonical)
            if (repaired is Resolution.Plan) return remember(EvaReply.Execute(repaired.actions), now)
        }
        rephrase(canonical)?.let { alt ->
            val again = resolver.resolve(alt)
            if (again is Resolution.Plan) return remember(EvaReply.Execute(again.actions), now)
        }
        val canonWords = EvaLexicon.tokens(canonical)
        postFrames(words)?.let { return remember(it, now) }
        postFrames(canonWords)?.let { return remember(it, now) }
        clarify(canonWords, now)?.let { return it }
        EvaSmallTalk.answer(raw)?.let { return EvaReply.Say(it) }
        when (val guess = EvaUnderstanding.guess(raw, lastApp, apps())) {
            is EvaUnderstanding.Guess.Sure -> return remember(EvaReply.Execute(guess.actions), now)
            is EvaUnderstanding.Guess.Answer -> return EvaReply.Say(guess.text)
            is EvaUnderstanding.Guess.Maybe -> {
                val valid = PlanPreflight.validate(Resolution.Plan(guess.actions))
                if (valid is Resolution.Plan) {
                    pending = Pending(valid.actions, emptyList(), emptyList(), confirm = true, at = now)
                    return EvaReply.Confirm(valid.actions, "Вы имели в виду «${guess.phrase}»? Скажите «да» или повторите команду")
                }
            }
            null -> {}
        }
        if (chatEnabled()) return EvaReply.Chat(raw, EvaUnderstanding.fallback(raw))
        return EvaReply.Say(EvaUnderstanding.fallback(raw))
    }

    /**
     * Speech recognizers return several hypotheses («громко 20», «громкость 20», «громко двадцать»).
     * Picks the one EVA understands best; side-effect free (does not touch the dialog state).
     */
    fun pick(hypotheses: List<String>, apps: () -> List<AppTarget> = { emptyList() }): String {
        val list = hypotheses.map { it.trim() }.filter { it.isNotBlank() && it.length <= 600 }.distinct().take(6)
        if (list.size <= 1) return list.firstOrNull() ?: hypotheses.firstOrNull().orEmpty()
        val installed by lazy { apps() }
        fun score(text: String): Int {
            val clean = EvaSpeechText.clean(text)
            if (memory.alias(EvaLexicon.simplify(clean)) != null) return 4
            val strict = resolver.resolve(clean)
            if (strict is Resolution.Plan) {
                val launches = strict.actions.filterIsInstance<Action.Launch>()
                // «открой его» resolves strictly to a missing app: not a good reading.
                return if (launches.isEmpty() || launches.all { matchApp(it.name, installed) != AppMatch.Missing }) 4 else 1
            }
            if (resolver.resolve(EvaLexicon.canonical(clean)) is Resolution.Plan) return 3
            if (EvaSmallTalk.answer(clean) != null) return 3
            return when (EvaUnderstanding.guess(clean, lastApp, installed)) {
                is EvaUnderstanding.Guess.Sure -> 3
                is EvaUnderstanding.Guess.Answer -> 2
                is EvaUnderstanding.Guess.Maybe -> 1
                null -> 0
            }
        }
        var best = list.first(); var top = score(best)
        for (candidate in list.drop(1)) { if (top >= 4) break; val s = score(candidate); if (s > top) { top = s; best = candidate } }
        return best
    }

    /** Spoken edges that carry no meaning: «а …», «ну …», «… быстрее», «… давай», «… ну». Only the edges, never the middle. */
    private fun trimEdges(text: String): String {
        val words = text.split(' ').filter { it.isNotBlank() }.toMutableList()
        val lead = setOf("а", "ну", "эй", "слушай", "так", "вот", "ой", "алло")
        val tail = setOf("ну", "а", "быстрее", "быстро", "давай", "пожалуйста", "уже", "же", "ладно", "плиз", "срочно", "ок", "окей", "так", "вот", "сейчас",
            "быстрие", "пажалуйста")
        while (words.size > 1 && words.first().trim(',', '.').lowercase() in lead) words.removeAt(0)
        while (words.size > 1 && words.last().trim(',', '.', '!').lowercase() in tail) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim(',', ' ')
    }

    /** The launcher reports results so successful app launches teach the preference order. */
    fun executed(action: Action, accepted: Boolean) {
        if (accepted && action is Action.Launch) { memory.recordUse(action.name); lastApp = action.name }
    }

    /** Forget the dialog (on «отмена», profile change or panel close); personal memory stays. */
    fun resetDialog() { pending = null; last = emptyList(); lastAt = 0L; chat.clear() }

    // ---- dialog ------------------------------------------------------------------------------

    private fun remember(reply: EvaReply, now: Long, confirmed: Boolean = false): EvaReply {
        val checked = when (reply) {
            is EvaReply.Execute -> {
                val valid = PlanPreflight.validate(Resolution.Plan(reply.actions))
                if (valid is Resolution.Rejected) return EvaReply.Say(valid.message)
                val actions = (valid as Resolution.Plan).actions
                if (actions.any { it == Action.Cancel || it == Action.Dismiss }) { resetDialog(); return EvaReply.Execute(actions) }
                if (!confirmed) confirmation(actions)?.let { question ->
                    pending = Pending(actions, emptyList(), emptyList(), confirm = true, at = now)
                    return EvaReply.Confirm(actions, question)
                }
                EvaReply.Execute(actions)
            }
            else -> reply
        }
        if (checked is EvaReply.Execute) {
            last = checked.actions; lastAt = now
            checked.actions.filterIsInstance<Action.Search>().lastOrNull()?.let { lastQuery = it.query; lastQueryAt = now }
            checked.actions.filterIsInstance<Action.Launch>().lastOrNull()?.let { if (isCinemaName(it.name)) lastCinema = it.name }
        }
        return checked
    }

    /** Only actions that change privacy or security ask first (ТЗ §18.9). */
    private fun confirmation(actions: List<Action>): String? = when {
        actions.any { it is Action.Power } -> when ((actions.first { it is Action.Power } as Action.Power).op) {
            "reboot" -> "Перезагрузить телевизор? Скажите «да» или «нет»"
            "shutdown" -> "Выключить телевизор? Скажите «да» или «нет»"
            else -> "Перевести телевизор в спящий режим? Скажите «да» или «нет»"
        }
        actions.any { it is Action.AssistantSetup && it.handsFree } ->
            "Включить отклик на «Ева» без пульта? Микрофон будет слушать, только пока открыт рабочий стол EZCH. Скажите «да» или «нет»"
        else -> null
    }

    private val yes = setOf("да", "давай", "конечно", "ага", "угу", "включай", "подтверждаю", "согласна", "согласен", "хорошо", "ок", "окей", "yes", "верно", "точно")
    private val no = setOf("нет", "не", "не надо", "не нужно", "отмена", "отмени", "стоп", "no", "передумал", "передумала")

    private fun answerPending(p: Pending, words: List<String>): EvaReply? {
        val phrase = words.joinToString(" ")
        if (phrase in no || words.firstOrNull() == "нет") return EvaReply.Say("Хорошо, не буду")
        if (p.confirm) return if (words.size <= 3 && words.any { it in yes }) EvaReply.Execute(p.options) else null
        ordinal(words)?.let { i -> return p.options.getOrNull(i - 1)?.let { EvaReply.Execute(listOf(it)) } ?: EvaReply.Say("Варианта $i нет") }
        if (words.size == 1 && words[0] in yes && p.options.size == 1) return EvaReply.Execute(p.options)
        if (p.apps.isNotEmpty()) {
            (matchApp(EvaLexicon.simplify(phrase), p.apps) as? AppMatch.Unique)?.let { m ->
                return EvaReply.Execute(listOf(Action.Launch(m.app.label)))
            }
        }
        p.stems.forEachIndexed { i, stems -> if (stems.any { EvaLexicon.hasStem(words, it) }) return EvaReply.Execute(listOf(p.options[i])) }
        return null
    }

    private fun ordinal(words: List<String>): Int? {
        val map = mapOf("перв" to 1, "втор" to 2, "трет" to 3, "четверт" to 4, "пят" to 5)
        if (words.size > 3) return null
        words.forEach { w -> w.toIntOrNull()?.takeIf { it in 1..9 }?.let { return it }
            map.entries.firstOrNull { (stem, _) -> w.startsWith(stem) && w.length <= stem.length + 3 }?.let { return it.value } }
        return null
    }

    private fun followUp(canon: List<String>, words: List<String>, now: Long): EvaReply? {
        val previous = last.singleOrNull() ?: return null
        if (now - lastAt > FOLLOW_UP_MS) return null
        val repeatWords = setOf("еще", "ещё", "раз", "повтори", "повтори-ка", "сильнее", "дальше", "немного", "и", "чуть", "снова", "опять")
        if (words.isNotEmpty() && words.all { it in repeatWords } && words.any { it != "и" && it != "раз" }) {
            return when (previous) {
                is Action.Volume -> if (previous.step != 0) EvaReply.Execute(listOf(previous)) else null
                is Action.Navigate, is Action.ChannelStep -> EvaReply.Execute(listOf(previous))
                else -> null
            }
        }
        if (canon.isNotEmpty() && canon.size <= 3 && canon.any { it == "наоборот" || it == "обратно" } &&
            canon.all { it in setOf("наоборот", "обратно", "сделай", "давай", "нет", "верни", "назад") }) {
            return when (previous) {
                is Action.Volume -> if (previous.step != 0) EvaReply.Execute(listOf(Action.Volume(step = -previous.step))) else null
                is Action.ChannelStep -> EvaReply.Execute(listOf(Action.ChannelStep(-previous.delta)))
                is Action.Navigate -> opposite(previous.direction)?.let { EvaReply.Execute(listOf(Action.Navigate(it, previous.count))) }
                else -> null
            }
        }
        // «а на 40», «тридцать процентов», «ещё три раза» → same action, new number.
        val rest = canon.filter { it !in setOf("на", "до", "процентов", "процента", "процент", "%", "раза", "раз") }
        val n = rest.singleOrNull()?.toIntOrNull() ?: return null
        return when (previous) {
            is Action.Volume -> if (n in 0..100) EvaReply.Execute(listOf(Action.Volume(percent = n))) else null
            is Action.Home -> if (previous.op == "brightness" && n in 0..100) EvaReply.Execute(listOf(previous.copy(value = n))) else null
            is Action.Navigate -> if (n in 1..10) EvaReply.Execute(listOf(previous.copy(count = n))) else null
            else -> null
        }
    }
    private fun opposite(d: Direction) = when (d) {
        Direction.LEFT -> Direction.RIGHT; Direction.RIGHT -> Direction.LEFT
        Direction.UP -> Direction.DOWN; Direction.DOWN -> Direction.UP; else -> null
    }

    // ---- memory (ТЗ §17) ------------------------------------------------------------------------

    private fun memoryCommand(words: List<String>): EvaReply? {
        val t = words.joinToString(" ")
        (Regex("^(?:запомни|запиши|учти)(?: что)? (.{1,40}?) (?:это|значит|означает|равно) (.{1,60})$").matchEntire(t)
            ?: Regex("^когда я (?:говорю|скажу) (.{1,40}?) (?:открывай|включай|запускай|открой|включи|запусти|это) (.{1,60})$").matchEntire(t))
            ?.let { return aliasReply(it.groupValues[1], it.groupValues[2]) }
        Regex("^(?:запомни )?(?:что )?(?:мой|наш) город (.{2,40})$").matchEntire(t)?.let {
            if (!memory.enabled) return EvaReply.Say("Персональная память выключена")
            memory.city = it.groupValues[1]; return EvaReply.Say("Запомнила ваш город: ${it.groupValues[1].replaceFirstChar(Char::uppercase)}")
        }
        if (Regex("^что ты (?:обо мне |про меня )?(?:помнишь|запомнила|знаешь обо мне|знаешь про меня)$").matches(t) || t == "что ты помнишь обо мне")
            return EvaReply.Say(memory.describe())
        if (Regex("^(?:забудь (?:все|всё)(?: обо мне| про меня| что запомнила)?|сотри (?:мою )?память|удали (?:мои )?данные)$").matches(t)) {
            memory.forgetAll(); resetDialog(); return EvaReply.Say("Готово, всё забыла")
        }
        Regex("^забудь(?: что| про)? (.{1,40})$").matchEntire(t)?.let {
            val key = it.groupValues[1].removeSuffix(" это")
            return EvaReply.Say(if (memory.forget(key)) "Забыла «$key»" else "Я не запоминала «$key»")
        }
        if (Regex("^(?:не запоминай(?: ничего)?|(?:выключи|отключи) (?:память|персонализацию|запоминание))$").matches(t)) {
            memory.setEnabled(false); return EvaReply.Say("Персональную память выключила и всё стёрла")
        }
        if (Regex("^(?:включи) (?:память|персонализацию|запоминание)$").matches(t)) {
            memory.setEnabled(true); return EvaReply.Say("Персональная память включена. Например, скажите: «запомни, что кино — это Zona»")
        }
        return null
    }
    private fun aliasReply(spoken: String, target: String): EvaReply {
        val k = spoken.trim(); val v = target.removePrefix("приложение ").trim()
        if (!memory.enabled) return EvaReply.Say("Персональная память выключена. Скажите «включи память»")
        return EvaReply.Say(if (memory.remember(k, v)) "Запомнила: «$k» — это $v" else "Не получилось запомнить: фраза слишком длинная или память заполнена")
    }

    private fun personal(words: List<String>): EvaReply? {
        if (words.isEmpty()) return null
        val verbs = setOf("открой", "запусти", "включи", "поставь")
        val obj = (if (words.first() in verbs) words.drop(1) else words).joinToString(" ")
        val target = memory.alias(obj) ?: return null
        return EvaReply.Execute(listOf(Action.Launch(target)))
    }

    // ---- intents the strict rules do not know -----------------------------------------------------

    private fun cinema(words: List<String>, apps: () -> List<AppTarget>, now: Long): EvaReply? {
        val t = words.joinToString(" ")
        val generic = Regex("^(?:(?:включи|поставь|открой|запусти|хочу|хочу посмотреть|посмотреть|посмотрим|смотреть|будем смотреть|пойдем смотреть|глянем)(?: какое| какой)? )?(?:кино|фильм|фильмы|киношку|кинчик|кинотеатр|что нибудь посмотреть|что нибудь)$")
        if (!generic.matches(t) || t == "что нибудь" || t == "фильмы" && words.size == 1) return null
        memory.alias("кино")?.let { return EvaReply.Execute(listOf(Action.Launch(it))) }
        val installed = apps()
        val found = cinemaApps.flatMap { name ->
            when (val m = matchApp(name, installed)) { is AppMatch.Unique -> listOf(m.app); is AppMatch.Ambiguous -> m.apps; else -> emptyList() }
        }.distinctBy { it.component }.sortedByDescending { memory.uses(it.label) }.take(4)
        return when (found.size) {
            0 -> EvaReply.Say("Не нашла приложений для фильмов. Установите, например, Zona или NUM через EZCH Update")
            1 -> EvaReply.Execute(listOf(Action.Launch(found.single().label)))
            else -> {
                pending = Pending(found.map { Action.Launch(it.label) }, emptyList(), found, confirm = false, at = now)
                EvaReply.Clarify("Где смотреть? " + found.mapIndexed { i, a -> "${i + 1}. ${a.label}" }.joinToString(". ") +
                    ". Чтобы не спрашивала, скажите: «запомни, что кино — это ${found.first().label}»")
            }
        }
    }

    private fun has(words: List<String>, vararg stems: String) = stems.any { EvaLexicon.hasStem(words, it) }

    private fun isCinemaName(name: String): Boolean { val n = normalize(name); return cinemaApps.any { n.contains(it) } || EvaMedia.cinemaKey(n) != null }

    /** The film app to use when the owner did not name one: «кино» alias → the film app in use → the most used one. */
    private fun filmApp(apps: () -> List<AppTarget>): String? {
        memory.alias("кино")?.let { return it }
        lastCinema?.let { return it }
        lastApp?.takeIf(::isCinemaName)?.let { return it }
        val installed = apps()
        return cinemaApps.flatMap { name ->
            when (val m = matchApp(name, installed)) { is AppMatch.Unique -> listOf(m.app); is AppMatch.Ambiguous -> m.apps; else -> emptyList() }
        }.distinctBy { it.component }.maxByOrNull { memory.uses(it.label) }?.label
    }

    /**
     * R30 «как Джарвис» (owner 29.10.2026): film wishes said any way — «хочу посмотреть фильм аватар», «давай посмотрим аватар»,
     * «покажи аватар», «что-нибудь про космос», «а теперь в зоне», «не тот, второй» — become one complete plan:
     * open the film app, search, choose and start, without asking where when the app is known.
     */
    private fun film(t: String, apps: () -> List<AppTarget>, now: Long): EvaReply? {
        val kind = "(?:фильм|фильмы|кино|киношку|сериал|сериалы|мультик|мультики|мультфильм|мультфильмы|аниме|документалку|передачу)"
        // «а теперь в зоне», «а в лифте», «поищи там же в нуме», «то же самое в кинотренде»
        Regex("^(?:а |и )?(?:теперь |давай |то же самое |тоже |поищи |найди |посмотри |там же )*(?:в|на|во) (.+)$").matchEntire(t)?.let { m ->
            val app = EvaMedia.cinemaKey(m.groupValues[1]) ?: return@let
            val q = lastQuery?.takeIf { now - lastQueryAt < 10 * 60_000 } ?: return EvaReply.Execute(listOf(Action.Launch(app)))
            return EvaReply.Execute(listOf(Action.Launch(app), Action.Search(q)))
        }
        // «не тот, второй», «другой», «следующий фильм», «давай третий»
        if (lastQuery != null && now - lastQueryAt < 10 * 60_000) {
            Regex("^(?:нет |не тот |не этот |не то |другой |давай )*(первый|второй|третий|четвертый|пятый)(?: фильм| вариант| сериал)?$").matchEntire(t)?.let { m ->
                if (!t.startsWith("не") && !t.startsWith("другой") && !t.startsWith("нет")) return@let
                val n = mapOf("первый" to 1, "второй" to 2, "третий" to 3, "четвертый" to 4, "пятый" to 5)[m.groupValues[1]] ?: return@let
                return EvaReply.Execute(listOf(Action.Choose(n), Action.Watch))
            }
            if (Regex("^(?:не тот|не этот|не то|другой|другой фильм|не тот фильм)$").matches(t)) return EvaReply.Execute(listOf(Action.Choose(2), Action.Watch))
        }
        // «продолжи смотреть», «включи фильм, который смотрел», «какой-нибудь фильм»
        if (Regex("^(?:продолжи|продолжим|давай продолжим|продолжаем) (?:смотреть|просмотр|фильм|сериал)$").matches(t)) return EvaReply.Execute(listOf(Action.Media(true)))
        if (Regex("^(?:включи|открой|верни|поставь)? ?(?:последний|прошлый|тот|мой) (?:фильм|сериал)(?: который| что)? ?(?:я )?(?:смотрел|смотрела|смотрели)?$").matches(t) && t.contains(' '))
            return EvaReply.Execute(listOf(Action.LastApp))
        if (Regex("^(?:включи|поставь|запусти|открой|найди) (?:какой нибудь|какое нибудь|любой|любое|что нибудь) (?:фильм|кино|сериал|мультик)$").matches(t))
            return filmApp(apps)?.let { EvaReply.Execute(listOf(Action.Launch(it))) }
        // «что-нибудь про космос», «фильм про войну», «хочу посмотреть комедию»
        val about = Regex("^(?:(?:хочу|давай|можно) )?(?:посмотреть |посмотрим |найди |включи |поставь |покажи )?(?:что нибудь|что то|$kind|кинцо)(?: интересное| хорошее| новое)? (?:про|о|об|на тему) (.+)$").matchEntire(t)
        if (about != null) {
            val app = filmApp(apps) ?: return EvaReply.Say("Не нашла приложений для фильмов. Установите, например, Zona или Lift через EZCH Update")
            return EvaReply.Execute(listOf(Action.Launch(app), Action.Search(EvaMedia.cleanQuery(about.groupValues[1]))))
        }
        // «хочу посмотреть фильм аватар», «давай посмотрим аватар», «покажи аватар», «включи фильм аватар», «найди сериал …»
        val m = Regex("^(?:я )?(хочу посмотреть|хочу смотреть|давай посмотрим|давай смотреть|посмотрим|покажи|покажи мне|включи|поставь|запусти|найди|найди мне|поищи)(?: мне)? (?:(?:$kind) (.+)|(.+))$").matchEntire(t) ?: return null
        val verb = m.groupValues[1]; val withKind = m.groupValues[2].isNotEmpty()
        val kindWord = Regex("^(?:я )?[^ ]+(?: [^ ]+)?? (мультик|мультики|мультфильм|мультфильмы|аниме) ").find(t)?.groupValues?.get(1)
        val q = (if (kindWord != null && withKind) "$kindWord " else "") + EvaMedia.cleanQuery(m.groupValues[2].ifEmpty { m.groupValues[3] })
        // Without «фильм/сериал» only the watching verbs mean a film («включи рамштайн» stays music).
        if (!withKind && verb !in setOf("хочу посмотреть", "хочу смотреть", "давай посмотрим", "давай смотреть", "посмотрим", "покажи", "покажи мне")) return null
        // «покажи погоду», «покажи приложения»: a phrase the command rules know is not a film title.
        if (!withKind && (resolver.resolve(t) is Resolution.Plan || EvaSmallTalk.answer(t) != null)) return null
        if (q.isEmpty() || q.split(' ').size > 6 || Regex("(?:^| )(?:в|на|во|через) [^ ]+$").containsMatchIn(q)) return null
        if (Regex("^(?:что нибудь|что то|какой нибудь|какое нибудь|любой|любое)$").matches(q) || Regex("^$kind$").matches(q)) return null
        val app = filmApp(apps) ?: return EvaReply.Say("Не нашла приложений для фильмов. Установите, например, Zona или Lift через EZCH Update")
        val find = verb.startsWith("найди") || verb == "поищи"
        return EvaReply.Execute(if (find) listOf(Action.Launch(app), Action.Search(q)) else listOf(Action.Launch(app), Action.Search(q), Action.Choose(1), Action.Watch))
    }

    /** Phrases the strict rules would turn into a wrong launch («открой приложение, которое…»). */
    private fun preFrames(words: List<String>, now: Long): EvaReply? {
        if (has(words, "установил", "установлен", "скачал", "скачан", "загрузил", "поставил", "новое прилож", "последнее установ") &&
            has(words, "последн", "недавн", "вчера", "сегодня", "позавчера", "новое", "свеж", "только что", "на этой неделе")) {
            val days = when { has(words, "позавчера") -> 3; has(words, "вчера") -> 2; has(words, "сегодня") -> 1; has(words, "месяц") -> 31; else -> 7 }
            return EvaReply.Execute(listOf(Action.RecentInstall(days)))
        }
        if (has(words, "без пульта", "без кнопк", "хэндс", "хендс", "hands", "handsfree") ||
            has(words, "отзыв", "реагир", "отклик", "слыша", "слуша") && has(words, "имя", "слово", "всегда", "постоянно")) {
            if (has(words, "выключ", "отключ", "не слуша", "перестань", "не надо")) return null
            return EvaReply.Execute(listOf(Action.AssistantSetup(handsFree = true)))
        }
        if (has(words, "помощник", "ассистент") && has(words, "основн", "главн", "умолчан", "назнач", "вместо", "сделай себя", "поставь себя", "стань") ||
            has(words, "кнопк") && has(words, "микрофон") && has(words, "открыв", "запуск", "вызыв", "реагир", "работ", "ева", "тебя", "себя"))
            return EvaReply.Execute(listOf(Action.AssistantSetup(handsFree = false)))
        return null
    }

    /** «перезагрузи телевизор», «перезапусти приставку», «выключи телек», «усыпи телевизор»: asked first, then the power menu. */
    private fun power(words: List<String>): EvaReply? {
        if (words.isEmpty() || words.size > 6) return null
        val device = setOf("телевизор", "телевизора", "тв", "телек", "приставку", "приставка", "систему", "андроид", "бокс", "себя", "устройство")
        val rest = words.filter { it !in device && it !in setOf("мне", "ты", "весь", "полностью", "сейчас", "срочно") }
        val verb = rest.singleOrNull() ?: rest.joinToString(" ")
        val op = when {
            Regex("^(?:перезагрузи|перезагрузить|перезагрузись|перезапусти|перезапустить|перезапустись|ребутни|перегрузи|перезагрузка|рестарт)$").matches(verb) -> "reboot"
            Regex("^(?:выключи|выключить|выключись|отключи|отключись|выруби|вырубись|выключение)$").matches(verb) && words.any { it in device } -> "shutdown"
            Regex("^(?:выключись|вырубись|отключись)$").matches(verb) -> "shutdown"
            Regex("^(?:усыпи|спящий режим|в спящий режим|переведи в спящий режим|режим сна|спать)$").matches(verb) && words.any { it in device } -> "sleep"
            else -> return null
        }
        return EvaReply.Execute(listOf(Action.Power(op)))
    }

    /** Things a launcher cannot do on Android TV: an honest answer instead of a wrong action («включи субтитры» is not a YouTube video). */
    private fun limits(words: List<String>): EvaReply? {
        if (has(words, "субтитр")) return EvaReply.Say("Субтитры включаются в самом плеере: нажмите OK на пульте и выберите «Субтитры»")
        if (has(words, "перемот", "промотай", "отмотай")) return EvaReply.Say("Перематывать голосом я пока не умею: используйте стрелки влево и вправо на пульте")
        if (has(words, "таймер", "через час", "через полчаса", "через 30 минут", "через 10 минут") && has(words, "выключ", "сна", "таймер"))
            return EvaReply.Say("Таймер выключения задаётся в настройках телевизора: «Настройки → Система → Таймер сна»")
        if (has(words, "серия", "серию") && has(words, "следующ", "предыдущ")) return EvaReply.Say("Следующую серию выбирают в самом приложении. Скажите «вправо» и «ок», и я помогу пультом")
        return null
    }

    /** Everyday synonyms of strict verbs: «поставь рамштайн», «включи ютуб и найди…», «переключи на ютуб». */
    private fun rephrase(t: String): String? {
        Regex("^(?:поставь|врубай|вруби|сыграй|проиграй) (.+)$").matchEntire(t)?.let { return "включи ${it.groupValues[1]}" }
        Regex("^(?:переключи(?:сь)? на|перейди в|зайди в) (.+)$").matchEntire(t)?.let { return "открой ${it.groupValues[1]}" }
        return null
    }

    /** Meaning frames checked only after the strict rules found no command. */
    private fun postFrames(words: List<String>): EvaReply? {
        val objects = has(words, "свет", "ламп", "люстр", "яркост", "кондиционер", "температур")
        if (!objects && words.size <= 5) {
            val down = has(words, "тиш", "потиш", "убав", "уменьш", "понизь", "снизь", "приглуш", "оглуш", "орет", "орёт")
            val up = has(words, "громч", "погромч", "прибав", "увелич", "повыс", "не слышно", "плохо слышно")
            if (down != up) return EvaReply.Execute(listOf(Action.Volume(step = if (up) 1 else -1)))
            if (has(words, "звук", "громкост") && has(words, "верни", "включи", "обратно")) return EvaReply.Execute(listOf(Action.Volume(mute = false)))
        }
        if (has(words, "очист", "освобод", "почист") && has(words, "памят", "кэш", "кеш", "оперативк", "озу"))
            return EvaReply.Execute(listOf(Action.ClearCache))
        val net = has(words, "интернет", "сеть", "wifi", "вайфай", "вай фай", "буфериз", "подгруж")
        if (has(words, "тормоз", "лагает", "лагу", "лаги", "виснет", "завис", "подвис", "глюч", "тупит", "фризит", "медленно работ", "медленно груз", "долго груз", "долго откры"))
            return EvaReply.Execute(listOf(Action.Diagnose(if (net) "network" else "performance")))
        if (net && has(words, "провер", "не работ", "нет ", "плохо", "пропал", "отвал", "медлен", "нету"))
            return EvaReply.Execute(listOf(Action.Diagnose("network")))
        if (words.joinToString(" ").let { it == "нет интернета" || it == "интернета нет" }) return EvaReply.Execute(listOf(Action.Diagnose("network")))
        if (has(words, "диагност", "проверь телевизор", "проверь систему", "проверь приставку", "что с телевизором"))
            return EvaReply.Execute(listOf(Action.Diagnose("performance")))
        if (has(words, "реклам")) return EvaReply.Execute(listOf(Action.Optimize("ads")))
        if (has(words, "обнов") && !has(words, "найди", "фильм", "сериал") && words.size <= 6) return EvaReply.Execute(listOf(Action.Updates))
        return null
    }

    /** Half-said commands get one short question instead of a guess or a refusal. */
    private fun clarify(words: List<String>, now: Long): EvaReply? {
        val set = words.toSet()
        fun ask(question: String, options: List<Action>, stems: List<List<String>>): EvaReply {
            pending = Pending(options, stems, emptyList(), confirm = false, at = now); return EvaReply.Clarify(question)
        }
        return when {
            set.isNotEmpty() && set.all { it in setOf("звук", "громкость", "звука", "громкости") } ->
                ask("Сделать громче или тише?", listOf(Action.Volume(step = 1), Action.Volume(step = -1)),
                    listOf(listOf("громч", "погромч", "больш", "прибав", "выше"), listOf("тиш", "потиш", "меньш", "убав", "ниже")))
            set.isNotEmpty() && set.all { it in setOf("канал", "каналы", "переключи", "переключи канал") } && "канал" in set ->
                ask("Следующий или предыдущий канал?", listOf(Action.ChannelStep(1), Action.ChannelStep(-1)),
                    listOf(listOf("след", "вперед", "дальш", "плюс"), listOf("пред", "прошл", "назад", "минус")))
            set == setOf("свет") ->
                ask("Включить или выключить свет?", listOf(Action.Home("on", "свет"), Action.Home("off", "свет")),
                    listOf(listOf("включ", "зажг"), listOf("выключ", "погас", "отключ")))
            set == setOf("открой") || set == setOf("запусти") -> EvaReply.Say("Что открыть? Назовите приложение")
            set == setOf("найди") || set == setOf("поищи") -> EvaReply.Say("Что найти? Назовите фильм или канал")
            else -> null
        }
    }

    private companion object {
        const val PENDING_MS = 45_000L
        const val GAME_MS = 120_000L
        const val FOLLOW_UP_MS = 120_000L
    }
}

/** Spells navigation actions as a strict command for HOME's own focus engine: «вправо 3 раза, вниз». */
fun navigationText(actions: List<Action>): String? {
    if (actions.isEmpty() || actions.any { it !is Action.Navigate }) return null
    return actions.filterIsInstance<Action.Navigate>().joinToString(", ") { n ->
        val word = when (n.direction) {
            Direction.LEFT -> "влево"; Direction.RIGHT -> "вправо"; Direction.UP -> "вверх"; Direction.DOWN -> "вниз"
            Direction.OK -> "ок"; Direction.BACK -> "назад"; Direction.HOME -> "домой"
        }
        if (n.count > 1 && n.direction !in setOf(Direction.OK, Direction.HOME)) "$word ${n.count} раза" else word
    }
}
