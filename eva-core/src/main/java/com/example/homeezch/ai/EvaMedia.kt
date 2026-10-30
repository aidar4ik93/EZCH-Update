package com.example.homeezch.ai

/**
 * Media phrases the way speech recognition really delivers them — without commas or «и», with
 * fillers («мне», «сразу», «кинотеатр»): «открой кинотеатр лифт найди мне фильм мстители»,
 * «открой ютуб поставь рамштайн», «включи музыку рамштайн», «включи рамштайн на ютубе».
 * Pure (JVM-tested); runs after EvaNlu and before the older search rules.
 */
object EvaMedia {
    private const val YT = "(?:ютуб|ютубе|ютюб|ютьюб|ю туб|youtube|you tube)"
    private const val PLAY = "(?:поставь|включи|воспроизведи|запусти|играй|сыграй|проиграй)"
    private const val FIND = "(?:найди|поищи|найти|покажи|ищи)"
    private const val OPEN = "(?:открой|запусти|зайди в|перейди в|включи приложение)"
    /** R29: «включи ютуб …» opens the app too — only for YouTube, so «включи рамштайн» stays a play request. */
    private const val OPEN_YT = "(?:открой|запусти|зайди в|перейди в|включи|включи приложение|зайди на)"
    private val ORDINAL = mapOf("первый" to 1, "первое" to 1, "первую" to 1, "1" to 1, "второй" to 2, "второе" to 2, "вторую" to 2, "2" to 2,
        "третий" to 3, "третье" to 3, "третью" to 3, "3" to 3, "четвертый" to 4, "четвертое" to 4, "четвертую" to 4, "4" to 4,
        "пятый" to 5, "пятое" to 5, "пятую" to 5, "5" to 5)
    private const val KIND = "(?:фильм|фильмы|сериал|сериалы|мультфильм|мультик|мультики|кино|видео|ролик|клип|клипы|песню|песни|музыку|трек|альбом|группу|исполнителя)"
    private const val APP_WORDS = "(?:онлайн[- ]?)?(?:кинотеатр|приложение|программу|сервис|плеер)"

    /** Removes leading content words and trailing fillers from a spoken title. */
    fun cleanQuery(raw: String): String = raw.trim()
        .replace(Regex("^(?:мне |нам |сразу |там |пожалуйста |скорее )+"), "")
        .replace(Regex("^$KIND "), "")
        .replace(Regex("^(?:группы|группу|исполнителя|певца|певицы) "), "")
        .replace(Regex("(?: (?:пожалуйста|сразу|там|быстро|скорее))+$"), "")
        .trim()

    /** «light hd tv», «лайт хд тв», «лайт эйч ди тв» → the installed app's own name «лайт hd tv». */
    fun tvAppName(raw: String): String = appName(raw).replace(Regex("(?<!\\p{L})(?:light|лайт|лаит|лайф|white|уайт)(?!\\p{L})"), "лайт")
        .replace(Regex("(?<!\\p{L})(?:хд|эйч ди|аш ди|hd)(?!\\p{L})"), "hd").replace(Regex("(?<!\\p{L})(?:тв|ти ви|tv)(?!\\p{L})"), "tv").trim()

    fun appName(raw: String): String = raw.trim().replace(Regex("^$APP_WORDS "), "").trim()

    private fun isYoutube(app: String) = Regex("^$YT$").matches(app.trim())

    fun understand(t: String): Resolution? {
        // R29 «поставь первое видео», «включи первый фильм», «запусти второй»: pick a result on screen and start it.
        Regex("^(?:$PLAY|выбери|открой|давай|смотреть)? ?(первый|первое|первую|второй|второе|вторую|третий|третье|третью|четвертый|четвертое|четвертую|пятый|пятое|пятую|[1-5])(?: (?:$KIND|результат|вариант|по счету|номер))?(?: (?:из списка|сверху|в списке))?$").matchEntire(t)?.let {
            val n = ORDINAL[it.groupValues[1]] ?: return null
            // A bare «первый» is the older choose-only command; a verb or a noun means «and start it».
            val start = t.contains(' ') && !t.startsWith("выбери")
            return if (start) plan(Action.Choose(n), Action.Watch) else plan(Action.Choose(n))
        }
        // R29 cinema apps by spoken name: «включи лифт найди аватар», «включи фильм аватар в лифте».
        Regex("^включи ($CINEMA),?(?: и| затем| потом)? ($PLAY|$FIND)(?: мне)?(?: $KIND)? (.+)$").matchEntire(t)?.let {
            val app = cinemaApp(it.groupValues[1]) ?: return null; val q = cleanQuery(it.groupValues[3]); if (q.isEmpty() || ',' in q) return null
            val play = Regex("^$PLAY$").matches(it.groupValues[2])
            return if (play) plan(Action.Launch(app), Action.Search(q), Action.Choose(1), Action.Watch) else plan(Action.Launch(app), Action.Search(q))
        }
        Regex("^($PLAY|$FIND)(?: мне)?(?: $KIND)? (.+?) (?:в|на|во|через) ($CINEMA)$").matchEntire(t)?.let {
            val app = cinemaApp(it.groupValues[3]) ?: return null; val q = cleanQuery(it.groupValues[2]); if (q.isEmpty()) return null
            val play = Regex("^$PLAY$").matches(it.groupValues[1])
            return if (play) plan(Action.Launch(app), Action.Search(q), Action.Choose(1), Action.Watch) else plan(Action.Launch(app), Action.Search(q))
        }
        // R31 «открой Лайт HD TV канал ТНТ» (GPT R27: the whole phrase was taken as an app name).
        Regex("^(?:$OPEN|включи) (.+?),? (?:и )?(?:(?:включи|поставь|переключи на|найди) )?(?:канал|телеканал) (.+)$").matchEntire(t)?.let {
            val app = tvAppName(it.groupValues[1]); val ch = it.groupValues[2].trim(); if (app.isEmpty() || ch.isEmpty()) return null
            return plan(Action.Launch(app), Action.PlayChannel(ch))
        }
        // R29 «открой музыку рамштайн через ютуб», «включи рамштайн через youtube», «рамштайн на ютубе»
        Regex("^(?:(?:$PLAY|$OPEN|$FIND) )?(?:мне )?(.+?) (?:через|по|на|в|во) $YT$").matchEntire(t)?.let {
            val q = cleanQuery(it.groupValues[1])
            if (q.isEmpty() || Regex("^$YT$").matches(q) || Regex("^(?:переключи(?:сь)?|перейди|зайди|вернись|иди|открой|включи|запусти)$").matches(q)) return null
            return plan(Action.PlayOn("youtube", q, autoplay = !Regex("^$FIND ").containsMatchIn(t)))
        }
        // R29 «включи ютуб (и) поставь музыку рамштайн» — without «и», the way speech arrives.
        Regex("^$OPEN_YT $YT,?(?: и| затем| потом| а потом)? ($PLAY|$FIND)(?: мне)? (.+)$").matchEntire(t)?.let {
            val q = cleanQuery(it.groupValues[2]); if (q.isEmpty() || ',' in q) return null
            return if (Regex("^$PLAY$").matches(it.groupValues[1])) plan(Action.PlayOn("youtube", q, autoplay = true))
                else plan(Action.Launch("youtube"), Action.Search(q))
        }
        // «открой ютуб (и) поставь/найди X»
        Regex("^$OPEN $YT,?(?: и| затем| потом| а потом)? ($PLAY|$FIND)(?: мне)? (.+)$").matchEntire(t)?.let {
            val q = cleanQuery(it.groupValues[2]); if (q.isEmpty() || ',' in q) return null
            return if (Regex("^$PLAY$").matches(it.groupValues[1])) plan(Action.PlayOn("youtube", q, autoplay = true))
                else plan(Action.Launch("youtube"), Action.Search(q))
        }
        // «включи X на ютубе», «найди X в ютубе»
        Regex("^($PLAY|$FIND) (.+?) (?:на|в|во) $YT$").matchEntire(t)?.let {
            val q = cleanQuery(it.groupValues[2]); if (q.isEmpty()) return null
            return plan(Action.PlayOn("youtube", q, autoplay = Regex("^$PLAY$").matches(it.groupValues[1])))
        }
        // «включи музыку рамштайн», «поставь песню …», «хочу послушать …»
        Regex("^(?:$PLAY|хочу послушать|хочу слушать|послушать) (?:музыку|песню|песни|трек|клип|альбом|группу|исполнителя) (.+)$").matchEntire(t)?.let {
            val q = cleanQuery(it.groupValues[1]); if (q.isEmpty()) return null
            return plan(Action.PlayOn("youtube", q, autoplay = true))
        }
        if (Regex("^(?:$PLAY|хочу послушать) (?:какую-нибудь |любую )?музыку$").matches(t))
            return plan(Action.PlayOn("youtube", "музыка", autoplay = true))
        // «открой (кинотеатр) лифт (и) найди (мне) (фильм) мстители» → app + search (+ start for «включи»)
        Regex("^$OPEN ((?:$APP_WORDS )?[^\\s,]+(?: [^\\s,]+){0,2}?),?(?: и| затем| потом| а потом)? ($PLAY|$FIND)(?: мне)?(?: $KIND)? (.+)$").matchEntire(t)?.let {
            val app = appName(it.groupValues[1]); val q = cleanQuery(it.groupValues[3])
            // Longer chains («…, выбери 4k») stay with the general chain parser.
            if (app.isEmpty() || q.isEmpty() || ',' in q) return null
            val play = Regex("^$PLAY$").matches(it.groupValues[2])
            if (isYoutube(app)) return if (play) plan(Action.PlayOn("youtube", q, autoplay = true)) else plan(Action.Launch("youtube"), Action.Search(q))
            Regex("^(?:канал|телеканал) (.+)$").matchEntire(q)?.let { c -> if (play) return plan(Action.Launch(app), Action.PlayChannel(c.groupValues[1])) }
            // «открой lite hd tv включи тнт»: a TV channel inside the app stays a channel request.
            if (play && looksLikeChannel(q)) return plan(Action.Launch(app), Action.PlayRequest(q))
            // R29: «включи фильм X» in a film app starts the first result, not just the search page.
            return if (play) plan(Action.Launch(app), Action.Search(q), Action.Choose(1), Action.Watch) else plan(Action.Launch(app), Action.Search(q))
        }
        // «открой кинотеатр лифт» → just the app
        Regex("^$OPEN $APP_WORDS (.+)$").matchEntire(t)?.let { return plan(Action.Launch(appName(it.groupValues[1]))) }
        return null
    }
    private fun plan(vararg actions: Action) = Resolution.Plan(actions.toList())

    /** Spoken forms of the owner's film apps → the name the launcher matches against installed apps. */
    private val CINEMA_NAMES = linkedMapOf(
        "lift" to listOf("лифт", "лифте", "лифта", "lift"),
        "zona" to listOf("зона", "зону", "зоне", "зоны", "zona"),
        "kinotrend" to listOf("кинотренд", "кинотренде", "кино тренд", "кино тренде", "kinotrend"),
        "serialtrend" to listOf("сериал тренд", "сериал тренде", "сериалтренд", "serialtrend", "serial trend"),
        "vokino" to listOf("вокино", "во кино", "vokino"),
        "num" to listOf("нум", "num"),
        "lampa" to listOf("лампа", "лампе", "lampa"),
        "prisma" to listOf("призма", "призме", "prisma"),
        "кинопоиск" to listOf("кинопоиск", "кинопоиске"),
        "okko" to listOf("окко", "okko"),
        "rutube" to listOf("рутуб", "рутубе", "rutube"))
    private val CINEMA = "(?:(?:кинотеатр|приложение) )?(?:" + CINEMA_NAMES.values.flatten().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")"
    /** R30: canonical film-app key for a spoken name («зоне» → zona), or null when it is not one of them. */
    fun cinemaKey(spoken: String): String? = cinemaApp(spoken.trim())
    private fun cinemaApp(spoken: String): String? {
        val w = spoken.replace(Regex("^(?:кинотеатр|приложение) "), "").trim()
        return CINEMA_NAMES.entries.firstOrNull { w in it.value }?.key
    }

    /** Russian TV channels by spoken name: these stay channels; anything else to «включи» goes to YouTube. */
    private val CHANNELS = Regex("^(?:канал |телеканал )?(?:первый|первый канал|россия(?: 1| 24| к)?|нтв|стс|тнт|тнт4|тнт 4|рен тв|рен|пятница|домашний|звезда|мир|тв3|тв 3|муз тв|ю|карусель|2х2|спас|матч|матч тв|матч премьер|отр|че|суббота|пятый|пятый канал|5 канал|360|дисней|солнце|ru tv|ру тв|бокс тв)$")
    fun looksLikeChannel(name: String) = CHANNELS.matches(name.trim())

    /** First video id on a YouTube results page (no API key; the page embeds `"videoId":"…"`). */
    /** R29: first YouTube video id among plain or URL-encoded links (Bing video results), e.g. youtube.com%2Fwatch%3Fv%3D… */
    fun youtubeIdInLinks(html: String): String? =
        Regex("(?i)(?:youtube\\.com|youtu\\.be)(?:/|%2f)(?:watch(?:\\?|%3f)v(?:=|%3d)|embed(?:/|%2f))?([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])").findAll(html)
            .map { it.groupValues[1] }.firstOrNull { it.any(Char::isDigit) || it.any(Char::isUpperCase) || it.contains('_') || it.contains('-') }

    fun firstVideoId(html: String): String? =
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
}
