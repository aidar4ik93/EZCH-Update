package com.example.homeezch.ai

import java.util.Locale

enum class Direction { LEFT, RIGHT, UP, DOWN, OK, BACK, HOME }
fun evaMiniSideDp(cardWidthDp: Float): Float {
    require(cardWidthDp.isFinite() && cardWidthDp > 0f)
    return cardWidthDp / 1.5f
}
sealed interface Action {
    data class Navigate(val direction: Direction, val count: Int = 1) : Action
    data class Launch(val name: String) : Action
    data class PlayRequest(val name: String) : Action
    data class PlayChannel(val name: String) : Action
    data class Volume(val step: Int = 0, val percent: Int? = null, val mute: Boolean? = null) : Action
    data class Media(val play: Boolean) : Action
    data class Search(val query: String) : Action
    data class Choose(val index: Int) : Action
    data class Quality(val value: String) : Action
    data object Watch : Action
    data object ClearCache : Action
    data object Settings : Action
    data object Updates : Action
    data object Weather : Action
    data object Cancel : Action
    data object Dismiss : Action
    /** System settings section: wifi, bluetooth, display, sound, storage, apps, general. */
    data class OpenSettings(val section: String) : Action
    /** TV input: kind = hdmi / av / tv, number = 1-based or null for «любой/первый». */
    data class SwitchInput(val kind: String, val number: Int? = null) : Action
    /** +1 next channel, -1 previous channel (via the active player's media session). */
    data class ChannelStep(val delta: Int) : Action
    data object ListApps : Action
    data object LastApp : Action
    data object PlayGame : Action
    /** Smart home: op = on / off / toggle / brightness / temperature / scene / status / lock / unlock. */
    data class Home(val op: String, val target: String = "", val value: Int? = null) : Action
    /** Play (autoplay) or show results for [query] in [app]; «youtube» starts the first video by itself. */
    data class PlayOn(val app: String, val query: String, val autoplay: Boolean = true) : Action
    /** «телевизор тормозит», «интернет плохо работает»: read-only health check with safe advice. topic: performance / network. */
    data class Diagnose(val topic: String = "performance") : Action
    /** «открой приложение, которое я вчера установил»: newest launchable app installed within [days]. */
    data class RecentInstall(val days: Int = 7) : Action
    /** «сделай себя основным помощником» (false) / «чтобы я говорил с тобой без пульта» (true, hands-free «Ева»). */
    data class AssistantSetup(val handsFree: Boolean) : Action
    /** «убери рекламу»: honest report; system-level changes belong to the EZCH Optimizer. kind: ads. */
    data class Optimize(val kind: String) : Action
    /** «перезагрузи телевизор» / «выключи приставку»: op = reboot / shutdown / sleep, through the system power menu, after «да». */
    data class Power(val op: String) : Action
}
sealed interface Resolution {
    data class Plan(val actions: List<Action>) : Resolution
    data class Rejected(val message: String) : Resolution
}
interface IntentResolver { fun resolve(text: String): Resolution }
fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е').trim().replace(Regex("\\s+"), " ")

/** Exact bounded rules. Unknown words never become a partial action. */
class LocalIntentResolver : IntentResolver {
    private val numbers = mapOf("один" to 1, "два" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10)
    override fun resolve(text: String): Resolution {
        if (text.isBlank() || text.length > 600 || text.any { it.isISOControl() && !it.isWhitespace() })
            return Resolution.Rejected("Команда пуста или слишком длинная")
        return PlanPreflight.validate(resolveUnchecked(text))
    }
    private fun resolveUnchecked(text: String): Resolution {
        val clean = normalize(EvaSpeechText.clean(text)).replace(Regex("^(?:(?:эй|слушай)[, ]+)?(?:eva|ева|ezch|эзч)[, ]+"), "").trimEnd('.', '!', '?')
            .replace(Regex("^(открой|запусти)[,:;]\\s*")) { "${it.groupValues[1]} " }
        if (weatherIntent(clean)) return Resolution.Plan(listOf(Action.Weather))
        EvaNlu.understand(clean)?.let { return it }
        EvaMedia.understand(clean)?.let { return it }
        // «в приложении Y» is explicit; otherwise the LAST «в/во» separates the app, so titles that
        // contain «в» survive: «найди Человек в железе в Zona» → Zona + «человек в железе».
        val searchVerb = "(?:найди|поищи|найти)"
        val kind = "(?:фильм |сериал |мультфильм |канал |видео )?"
        (Regex("^$searchVerb $kind(.+?) (?:в|во|на) приложении (.+)$").matchEntire(clean)
            ?: Regex("^$searchVerb $kind(.+) (?:в|во) ((?:\\S+)(?: \\S+){0,3})$").matchEntire(clean))?.let {
            return Resolution.Plan(listOf(Action.Launch(it.groupValues[2]), Action.Search(it.groupValues[1])))
        }
        Regex("^(?:в|во) (?:приложении )?(.+?),? $searchVerb $kind(.+)$").matchEntire(clean)?.let {
            return Resolution.Plan(listOf(Action.Launch(it.groupValues[1]), Action.Search(it.groupValues[2])))
        }
        if (clean in setOf("закройся", "закрой помощника", "close assistant")) return Resolution.Plan(listOf(Action.Dismiss))
        val chain = Regex("^(.+?)(?:,\\s*| и | затем )(?=(?:найди|включи|выбери|смотреть) )").find(clean)
        if (chain != null && (clean.startsWith("открой ") || clean.startsWith("запусти "))) {
            val first = resolve(chain.groupValues[1])
            val rest = resolve(clean.substring(chain.range.last + 1))
            if (first is Resolution.Plan && rest is Resolution.Plan && first.actions.size + rest.actions.size <= 5)
                return Resolution.Plan(first.actions + rest.actions)
            return Resolution.Rejected("Не удалось разобрать всю цепочку")
        }
        Regex("^(?:найди|найти|search|find) (.+)$").matchEntire(clean)?.let {
            val query = it.groupValues[1].replace(Regex("^(?:фильм|сериал|канал) "), "")
            val quality = Regex("^(.+?)(?:,? и |, ?)(?:выбери|качество) (4k|1080p|720p)$").matchEntire(query)
            return Resolution.Plan(if (quality != null) listOf(Action.Search(quality.groupValues[1]), Action.Quality(quality.groupValues[2])) else listOf(Action.Search(query)))
        }
        Regex("^(?:включи |поставь |смотри )?канал (.+)$").matchEntire(clean)?.let {
            return Resolution.Plan(listOf(Action.PlayChannel(it.groupValues[1])))
        }
        if (clean.startsWith("включи ") && clean != "включи звук") {
            val name = clean.removePrefix("включи ")
            val movie = Regex("^(?:фильм|сериал) (.+)$").matchEntire(name)
            return Resolution.Plan(if (movie != null) listOf(Action.Search(movie.groupValues[1]), Action.Watch)
                else listOf(Action.PlayRequest(name)))
        }
        if (clean in listOf("стоп", "отмена", "прекрати")) return Resolution.Plan(listOf(Action.Cancel))
        // App labels may contain conjunctions; a launch phrase is atomic.
        if (clean.startsWith("открой ") || clean.startsWith("запусти ")) {
            if (clean in listOf("открой ezch update", "открой обновления")) return Resolution.Plan(listOf(Action.Updates))
            if (clean == "открой настройки") return Resolution.Plan(listOf(Action.Settings))
            return Resolution.Plan(listOf(Action.Launch(clean.substringAfter(' ').trim())))
        }
        val parts = clean.split(Regex("\\s*(?:,| затем | и )\\s*"))
        if (parts.size > 5) return Resolution.Rejected("Не больше пяти действий за команду")
        val actions = parts.map { parse(it) ?: return Resolution.Rejected("Команда недоступна. Скажите «вправо», «открой YouTube», «громче» или «пауза»") }
        if (actions.sumOf { (it as? Action.Navigate)?.count ?: 1 } > 20) return Resolution.Rejected("Слишком длинная команда")
        return Resolution.Plan(actions)
    }
    private fun parse(text: String): Action? {
        if (weatherIntent(text)) return Action.Weather
        when (text) {
            "смотреть", "начни просмотр", "запусти воспроизведение", "play" -> return Action.Watch
            "очисти кэш", "очисти кеш" -> return Action.ClearCache
            "стоп", "отмена" -> return Action.Cancel
            "проверь обновления" -> return Action.Updates
            "громче", "сделай громче" -> return Action.Volume(step = 1)
            "тише", "сделай тише" -> return Action.Volume(step = -1)
            "выключи звук", "без звука" -> return Action.Volume(mute = true)
            "верни звук", "включи звук" -> return Action.Volume(mute = false)
            "пауза", "поставь на паузу" -> return Action.Media(false)
            "продолжи", "продолжить", "воспроизведение" -> return Action.Media(true)
        }
        val ordinals = mapOf("первый" to 1, "второй" to 2, "третий" to 3, "четвертый" to 4, "пятый" to 5)
        ordinals[text.removePrefix("выбери ").removeSuffix(" вариант")]?.let { return Action.Choose(it) }
        Regex("(?:выбери |качество )?(4k|1080p|720p)").matchEntire(text)?.let { return Action.Quality(it.groupValues[1]) }
        Regex("громкость(?: на)? (\\d{1,3})(?: процентов)?").matchEntire(text)?.let {
            val percent = it.groupValues[1].toInt()
            return if (percent in 0..100) Action.Volume(percent = percent) else null
        }
        val dirs = mapOf("влево" to Direction.LEFT, "вправо" to Direction.RIGHT, "вверх" to Direction.UP,
            "вниз" to Direction.DOWN, "ок" to Direction.OK, "выбрать" to Direction.OK,
            "назад" to Direction.BACK, "домой" to Direction.HOME, "на главный экран" to Direction.HOME)
        dirs[text]?.let { return Action.Navigate(it) }
        val match = Regex("(?:(\\d+|${numbers.keys.joinToString("|")}) раз[а]? )?(влево|вправо|вверх|вниз|назад)(?: (\\d+|${numbers.keys.joinToString("|")}) раз[а]?)?").matchEntire(text) ?: return null
        if (match.groupValues[1].isNotEmpty() && match.groupValues[3].isNotEmpty()) return null
        val value = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val count = value.toIntOrNull() ?: numbers[value] ?: return null
        return if (count in 1..10) Action.Navigate(dirs.getValue(match.groupValues[2]), count) else null
    }
    private fun weatherIntent(text: String): Boolean {
        val value = normalize(text.replace(',', ' ')).removePrefix("пожалуйста ").removeSuffix(" пожалуйста")
            .removePrefix("скажи ").removePrefix("подскажи ").removePrefix("пожалуйста ")
        return value in setOf("погода", "погоду", "какая погода", "как погода", "какая сейчас погода", "какая сегодня погода",
            "погода сегодня", "погода сейчас", "погода на сегодня", "какая погода сегодня", "какая погода сейчас",
            "что с погодой", "расскажи о погоде", "расскажи погоду", "покажи погоду", "открой погоду",
            "сколько градусов", "сколько градусов на улице", "сколько сейчас градусов", "сколько градусов сейчас",
            "какая температура", "температура на улице", "какая температура на улице", "какая погода на улице")
    }
}

data class AppTarget(val label: String, val component: String)
/** Installed application names take precedence over a channel with the same spoken name. */
fun routePlayRequest(action: Action.PlayRequest, apps: List<AppTarget>): Action = when {
    matchApp(action.name, apps) != AppMatch.Missing -> Action.Launch(action.name)
    EvaMedia.looksLikeChannel(action.name) -> Action.PlayChannel(action.name)
    // «включи рамштайн»: not an app, not a TV channel → the song/video on YouTube.
    else -> Action.PlayOn("youtube", EvaMedia.cleanQuery(action.name), autoplay = true)
}
sealed interface AppMatch {
    data class Unique(val app: AppTarget) : AppMatch
    data class Ambiguous(val apps: List<AppTarget>) : AppMatch
    data class Suggested(val apps: List<AppTarget>) : AppMatch
    data object Missing : AppMatch
}
fun matchApp(query: String, apps: List<AppTarget>): AppMatch {
    val groups = mapOf(
        "youtube" to listOf("ютуб", "ютюб", "ю туб"),
        "zona" to listOf("зона", "зоне"),
        "num" to listOf("нум", "ну", "ньюм", "эн ю эм", "эн у эм"),
        "lampa" to listOf("лампа"),
        "lift" to listOf("лифт"),
        "prisma" to listOf("призма"),
        "kinotrend" to listOf("кинотренд", "кино тренд", "кин тренд", "kino trend"),
        "serialtrend" to listOf("сериал тренд", "сериал трендс", "сериалтренд", "serial trend"),
        "vokino" to listOf("вокино", "во кино"),
        "lite hd tv" to listOf("лайт hd tv", "лайт hd тв", "лайт эйч ди тв", "лайт аш ди тв", "лайт ашд тв", "лайт хд тв", "лайт тв"),
        "iptv pro" to listOf("иптв про", "ип тиви про", "ай пи ти ви про", "ip tv pro", "iptvпро"),
        "rutube" to listOf("рутуб", "рутюб", "ру туб"),
        "rustore" to listOf("рустор", "ру стор"),
        "vk видео" to listOf("вк видео", "вэ ка видео", "ви кей видео"),
        "torrserve matrix" to listOf("торрсерв", "торсерв", "торрсервер", "торр серв", "torrserve", "торрсерв матрикс"),
        "task killer tv" to listOf("таск киллер", "таск киллер тв", "task killer"),
        "медиаплеер виму" to listOf("виму", "vimu", "виму плеер"),
        "файловый менеджер +" to listOf("файловый менеджер", "файловый менеджер плюс"),
        "aniliberty" to listOf("анилиберти", "ани либерти"),
        "byebyedpi" to listOf("бай бай дпи", "байбайдпи", "bye bye dpi"),
        "google play" to listOf("гугл плей", "гугл плай", "плей маркет"),
        "youtube music" to listOf("ютуб музыка", "ютуб мьюзик", "ютюб мьюзик")
    )
    val aliases = groups.flatMap { (name, variants) -> variants.map { it to name } }.toMap()
    fun canonical(value: String): String {
        val text = normalize(value.replace(Regex("[«»\"“”.,!?:;]"), " "))
            .replace(Regex("^(?:пожалуйста )?(?:приложение|приложения|приложенье|приложении) "), "")
            .removePrefix("пожалуйста ")
        if (Regex("^(?:лайт|lite)\\s*(?:hd|хд|ашд|ажд|ашди|ажди|эйчди|аш ди|аж ди|эйч ди)(?:\\s*(?:tv|тв|ти ви|тиви))?$").matches(text)) return "lite hd tv"
        aliases[text]?.let { return it }
        // Spoken case forms: «в лифте», «открой зону», «на ютубе», «в нуме».
        for (ending in listOf("ах", "ом", "ой", "е", "у", "а", "и")) {
            if (!text.endsWith(ending) || text.length < ending.length + 3 || ' ' in text) continue
            val stem = text.dropLast(ending.length)
            (aliases[stem] ?: aliases[stem + "а"])?.let { return it }
        }
        return text
    }
    val name = canonical(query)
    if (name.isBlank()) return AppMatch.Missing
    val unique = apps.distinctBy { it.component }
    // Only match installed, profile-approved components; package identity survives
    // localized or customized labels without hard-coding an activity to launch.
    val knownPackage = mapOf("num" to "ru.yourok.num", "zona" to "mobi.zona")[name]
    val exact = unique.filter { canonical(it.label) == name || knownPackage != null && it.component.substringBefore('/') == knownPackage }
    val found = exact.ifEmpty { unique.filter { canonical(it.label).contains(name) } }
    if (found.isNotEmpty()) return if (found.size == 1) AppMatch.Unique(found.single()) else AppMatch.Ambiguous(found)
    val spoken = phoneticAppName(name)
    if (spoken.length !in 4..60) return AppMatch.Missing
    val suggestions = unique.map { it to appNameDistance(spoken, phoneticAppName(canonical(it.label))) }
        .filter { (_, distance) -> distance <= if (spoken.length < 7) 1 else 2 }
    val closest = suggestions.minOfOrNull { it.second } ?: return AppMatch.Missing
    return AppMatch.Suggested(suggestions.filter { it.second == closest }.map { it.first })
}

private fun phoneticAppName(value: String): String {
    val letters = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ы' to "y", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to "")
    return buildString { normalize(value).forEach { letter -> if (letter.isLetterOrDigit()) append(letters[letter] ?: letter.toString()) } }
}
private fun appNameDistance(a: String, b: String): Int {
    if (b.length > 60 || kotlin.math.abs(a.length - b.length) > 2) return 99
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val next = IntArray(b.length + 1); next[0] = i + 1
        for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        previous = next
    }
    return previous[b.length]
}
data class Outcome(val accepted: Boolean, val message: String)
interface ActionExecutor { suspend fun execute(action: Action): Outcome }
interface ActionRegistry { fun allowed(action: Action): Boolean }
object LocalActionRegistry : ActionRegistry {
    override fun allowed(action: Action) = when (action) {
        is Action.Navigate -> action.count in 1..10
        is Action.Launch -> action.name.isNotBlank() && action.name.length <= 100
        is Action.PlayRequest -> action.name.isNotBlank() && action.name.length <= 100
        is Action.PlayChannel -> action.name.isNotBlank() && action.name.length <= 100
        is Action.Volume -> action.step in -1..1 && (action.percent == null || action.percent in 0..100)
        is Action.Search -> action.query.isNotBlank() && action.query.length <= 200
        is Action.Choose -> action.index in 1..20
        is Action.Quality -> action.value in setOf("4k", "1080p", "720p")
        is Action.OpenSettings -> action.section in EvaNlu.SETTINGS_SECTIONS
        is Action.SwitchInput -> action.kind in setOf("hdmi", "av", "tv") && (action.number == null || action.number in 1..8)
        is Action.ChannelStep -> action.delta == 1 || action.delta == -1
        is Action.PlayOn -> action.app == "youtube" && action.query.isNotBlank() && action.query.length <= 200
        is Action.Home -> action.op in EvaNlu.HOME_OPS && action.target.length <= 80 && (action.value == null || action.value in 0..100)
        is Action.Diagnose -> action.topic in setOf("performance", "network")
        is Action.RecentInstall -> action.days in 1..60
        is Action.Optimize -> action.kind == "ads"
        is Action.Power -> action.op in setOf("reboot", "shutdown", "sleep")
        else -> true
    }
}
interface VoiceInput {
    fun start(); fun cancel(); fun destroy()
    /** Second press while listening: «I have finished speaking» — recognize what was said instead of restarting. */
    fun finish() {}
}
interface Recognizer : VoiceInput
interface CapabilityProbe { fun report(): String }
interface OverlayPresenter { fun status(message: String) }
interface MediaAdapter { suspend fun setPlaying(play: Boolean): Outcome }
