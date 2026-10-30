package com.example.homeezch.ai

/**
 * EVA Chat — free conversation through GigaChat (Сбер), the model behind Салют ТВ.
 *
 * The offline core still handles every TV command first (fast, no internet). Only what it cannot answer
 * («расскажи анекдот», «кто такой Пушкин», «что посмотреть вечером») goes to GigaChat, together with a short
 * dialog history and a set of TV functions: when the model decides that the owner wants an action, it calls
 * a function and EVA runs it through the same validated [Action]s ([PlanPreflight]) as a spoken command.
 * This file is pure Kotlin (request JSON, response parsing, persona, dialog memory) and is covered by JVM tests;
 * the network client lives in the launcher.
 */
object EvaChat {
    /** What the model answered. */
    sealed interface Result {
        data class Say(val text: String) : Result
        data class Act(val actions: List<Action>, val call: String) : Result
        data class Failed(val message: String) : Result
    }

    /** Facts EVA gives the model about «here and now»; no personal memory, no audio, no screen. */
    data class Context(val now: String, val city: String? = null, val lastApp: String? = null, val apps: List<String> = emptyList())

    fun persona(c: Context): String = buildString {
        append("Ты — Ева, голосовой помощник телевизора EZCH. Ты говоришь вслух, твой ответ сразу озвучивается. ")
        append("Ты женского рода, дружелюбная, с лёгким юмором, обращаешься на «вы». ")
        append("Отвечай по-русски коротко и разговорно: одно-три предложения, как Алиса или Салют. ")
        append("Никаких списков, заголовков, markdown, эмодзи, ссылок и английских слов без нужды. ")
        append("Если просят что-то сделать с телевизором — громкость, пауза, открыть приложение, включить видео, вход HDMI, настройки, канал, погода, умный дом — вызови подходящую функцию, а не описывай, как это сделать. ")
        append("Если просят посоветовать фильм или музыку, предложи один-два конкретных варианта и спроси, включить ли. ")
        append("Если не знаешь точного ответа или он мог устареть, честно скажи об этом. Не выдумывай факты, цены и новости. ")
        append("Не задавай больше одного вопроса за раз. ")
        append("Сейчас ${c.now}.")
        c.city?.takeIf { it.isNotBlank() }?.let { append(" Город владельца: $it.") }
        c.lastApp?.takeIf { it.isNotBlank() }?.let { append(" Последнее открытое приложение: $it.") }
        if (c.apps.isNotEmpty()) append(" Установленные приложения: ${c.apps.take(40).joinToString(", ")}.")
    }

    // ---- functions the model may call ----------------------------------------------------------

    private fun obj(vararg props: Pair<String, Any?>) = linkedMapOf(*props)
    private fun prop(type: String, description: String, enum: List<String>? = null) =
        obj("type" to type, "description" to description).apply { if (enum != null) put("enum", enum) }
    private fun fn(name: String, description: String, properties: Map<String, Any?>, required: List<String> = emptyList()) =
        obj("name" to name, "description" to description,
            "parameters" to obj("type" to "object", "properties" to properties, "required" to required))

    val functions: List<Map<String, Any?>> = listOf(
        fn("tv_volume", "Изменить громкость телевизора: точный процент, громче/тише или выключить/включить звук",
            obj("percent" to prop("integer", "Громкость от 0 до 100"), "change" to prop("string", "up — громче, down — тише", listOf("up", "down")),
                "mute" to prop("boolean", "true — выключить звук, false — вернуть звук"))),
        fn("tv_media", "Пауза или продолжение воспроизведения", obj("action" to prop("string", "pause или play", listOf("pause", "play"))), listOf("action")),
        fn("open_app", "Открыть установленное приложение по названию", obj("name" to prop("string", "Название приложения, например YouTube или Кинопоиск")), listOf("name")),
        fn("play_on_youtube", "Найти и сразу включить видео, песню, клип или фильм на YouTube", obj("query" to prop("string", "Что искать")), listOf("query")),
        fn("search_in_app", "Открыть приложение и найти в нём фильм или сериал", obj("app" to prop("string", "Приложение"), "query" to prop("string", "Название фильма или сериала")), listOf("app", "query")),
        fn("switch_input", "Переключить вход телевизора", obj("kind" to prop("string", "hdmi, av или tv (эфирное ТВ)", listOf("hdmi", "av", "tv")), "number" to prop("integer", "Номер входа, например 1 или 2")), listOf("kind")),
        fn("open_settings", "Открыть настройки телевизора или их раздел",
            obj("section" to prop("string", "Раздел настроек", listOf("general", "wifi", "bluetooth", "display", "sound", "storage", "apps")))),
        fn("tv_channel", "Следующий или предыдущий телеканал", obj("direction" to prop("string", "next или previous", listOf("next", "previous"))), listOf("direction")),
        fn("weather", "Узнать текущую погоду в городе владельца", obj()),
        fn("smart_home", "Управлять умным домом: свет, розетки, кондиционер, пылесос, сценарии",
            obj("op" to prop("string", "Действие", listOf("on", "off", "brightness", "set_temp", "scene", "status")),
                "target" to prop("string", "Устройство и комната, например «свет гостиная» или название сценария"),
                "value" to prop("integer", "Яркость в процентах или температура в градусах")), listOf("op"))
    )

    /** Model function call → validated EVA actions (null = the call was not acceptable). */
    fun actions(name: String, arguments: Any?): List<Action>? {
        val args: Map<*, *> = when (arguments) {
            is Map<*, *> -> arguments
            is String -> runCatching { EvaJson.parse(arguments) as? Map<*, *> }.getOrNull() ?: emptyMap<String, Any?>()
            else -> emptyMap<String, Any?>()
        }
        fun str(k: String) = (args[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        fun int(k: String) = when (val v = args[k]) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
        fun bool(k: String) = when (val v = args[k]) { is Boolean -> v; is String -> v.toBooleanStrictOrNull(); else -> null }
        val plan: List<Action> = when (name) {
            "tv_volume" -> listOf(when {
                int("percent") != null -> Action.Volume(percent = int("percent")!!.coerceIn(0, 100))
                bool("mute") != null -> Action.Volume(mute = bool("mute"))
                str("change") == "up" -> Action.Volume(step = 1)
                str("change") == "down" -> Action.Volume(step = -1)
                else -> return null
            })
            "tv_media" -> listOf(Action.Media(str("action") != "pause"))
            "open_app" -> listOf(Action.Launch(str("name") ?: return null))
            "play_on_youtube" -> listOf(Action.PlayOn("youtube", str("query") ?: return null, autoplay = true))
            "search_in_app" -> {
                val app = str("app") ?: return null; val q = str("query") ?: return null
                if (Regex("(?i)^(?:youtube|ютуб)$").matches(app)) listOf(Action.PlayOn("youtube", q, autoplay = false))
                else listOf(Action.Launch(app), Action.Search(q))
            }
            "switch_input" -> listOf(Action.SwitchInput(str("kind") ?: return null, int("number")))
            "open_settings" -> listOf(str("section")?.takeIf { it != "general" }?.let { Action.OpenSettings(it) } ?: Action.Settings)
            "tv_channel" -> listOf(Action.ChannelStep(if (str("direction") == "previous") -1 else 1))
            "weather" -> listOf(Action.Weather)
            "smart_home" -> {
                val op = str("op") ?: return null
                listOf(Action.Home(if (op == "on" && str("target")?.contains("сценар") == true) "scene" else op, str("target").orEmpty(), int("value")))
            }
            else -> return null
        }
        return (PlanPreflight.validate(Resolution.Plan(plan)) as? Resolution.Plan)?.actions
    }

    // ---- request / response ----------------------------------------------------------------------

    fun request(model: String, context: Context, history: List<Pair<String, String>>, text: String, maxTokens: Int = 300): String {
        val messages = mutableListOf<Map<String, Any?>>(obj("role" to "system", "content" to persona(context)))
        history.forEach { (role, content) -> messages += obj("role" to role, "content" to content) }
        messages += obj("role" to "user", "content" to text)
        return EvaJson.write(obj("model" to model, "messages" to messages, "functions" to functions, "function_call" to "auto",
            "temperature" to 0.7, "max_tokens" to maxTokens, "stream" to false))
    }

    fun parse(json: String): Result {
        val root = runCatching { EvaJson.parse(json, 200_000) as? Map<*, *> }.getOrNull() ?: return Result.Failed("GigaChat прислал непонятный ответ")
        (root["message"] as? String)?.takeIf { root["choices"] == null }?.let { return Result.Failed(errorText(it)) }
        val choice = (root["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return Result.Failed("GigaChat не ответил")
        val message = choice["message"] as? Map<*, *> ?: return Result.Failed("GigaChat не ответил")
        val call = message["function_call"] as? Map<*, *>
        if (call != null) {
            val name = call["name"] as? String ?: return Result.Failed("GigaChat вызвал неизвестную функцию")
            val actions = actions(name, call["arguments"]) ?: return Result.Say("Не получилось выполнить это действие. Скажите команду иначе")
            return Result.Act(actions, name)
        }
        val content = speakable(message["content"] as? String ?: "")
        if (choice["finish_reason"] == "blacklist") return Result.Say("Давайте сменим тему. Могу включить что-нибудь интересное")
        return if (content.isBlank()) Result.Failed("GigaChat ответил пустым сообщением") else Result.Say(content)
    }

    fun errorText(raw: String): String = when {
        raw.contains("token", true) && raw.contains("expired", true) -> "Ключ GigaChat устарел, повторяю вход"
        raw.contains("payment", true) || raw.contains("quota", true) || raw.contains("limit", true) -> "Закончился лимит GigaChat. Проверьте баланс в личном кабинете Сбера"
        else -> "GigaChat: ${raw.take(120)}"
    }

    /** Spoken answers only: no markdown, lists, links or emoji; at most about four sentences. */
    fun speakable(text: String, maxChars: Int = 600): String {
        var t = text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+"), "")
            .replace(Regex("[*_#`>|]"), "")
            .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
            .replace(Regex("\\s*\\n+\\s*"), " ").replace(Regex("\\s{2,}"), " ").trim()
        if (t.length > maxChars) {
            val cut = t.lastIndexOfAny(charArrayOf('.', '!', '?'), maxChars)
            t = if (cut > maxChars / 3) t.substring(0, cut + 1) else t.substring(0, maxChars).substringBeforeLast(' ') + "…"
        }
        return t
    }
}

/** Short dialog memory for the conversation (only text EVA said and heard; cleared after 5 minutes of silence). */
class EvaChatSession(private val clock: () -> Long = System::currentTimeMillis, private val maxMessages: Int = 12, private val idleMs: Long = 5 * 60_000L) {
    private val messages = ArrayDeque<Pair<String, String>>()
    private var lastAt = 0L
    fun history(): List<Pair<String, String>> { expire(); return messages.toList() }
    fun add(role: String, text: String) {
        expire()
        if (text.isBlank()) return
        messages.addLast(role to text.take(800)); lastAt = clock()
        while (messages.size > maxMessages || messages.sumOf { it.second.length } > 4000) messages.removeFirst()
    }
    fun clear() { messages.clear(); lastAt = 0L }
    /** Time of the last conversation turn (0 = none). */
    val lastTurnAt: Long get() = lastAt
    /** EVA's last chat answer asked something («Включить?») and the owner replies within [ms]. */
    fun awaitsReply(ms: Long = 90_000L): Boolean {
        expire()
        val last = messages.lastOrNull() ?: return false
        return last.first == "assistant" && clock() - lastAt <= ms && last.second.trimEnd().endsWith("?")
    }
    private fun expire() { if (lastAt != 0L && clock() - lastAt > idleMs) clear() }
}

/** Minimal JSON for the chat protocol (Android's org.json is not available in JVM unit tests). */
object EvaJson {
    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()
    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> b.append("null")
            is String -> { b.append('"'); v.forEach { c -> when {
                c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n"); c == '\r' -> b.append("\\r"); c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u%04x".format(c.code)); else -> b.append(c) } }; b.append('"') }
            is Boolean, is Int, is Long -> b.append(v.toString())
            is Number -> b.append(if (v.toDouble() == Math.rint(v.toDouble()) && kotlin.math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toString())
            is Map<*, *> -> { b.append('{'); var first = true
                v.forEach { (k, x) -> if (!first) b.append(','); first = false; write(b, k.toString()); b.append(':'); write(b, x) }; b.append('}') }
            is Iterable<*> -> { b.append('['); var first = true; v.forEach { if (!first) b.append(','); first = false; write(b, it) }; b.append(']') }
            else -> write(b, v.toString())
        }
    }

    fun parse(text: String, maxLength: Int = 200_000): Any? {
        require(text.length <= maxLength)
        val p = Parser(text); val v = p.value(0); p.ws(); require(p.i == text.length); return v
    }
    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            require(depth < 32); ws(); require(i < s.length)
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> { require(s.startsWith("true", i)); i += 4; true }
                'f' -> { require(s.startsWith("false", i)); i += 5; false }
                'n' -> { require(s.startsWith("null", i)); i += 4; null }
                else -> { val m = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").find(s, i); require(m != null && m.range.first == i)
                    i = m.range.last + 1; m.value.toDouble() }
            }
        }
        private fun obj(depth: Int): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws(); require(s[i] == ':'); i++
                m[k] = value(depth + 1); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        private fun arr(depth: Int): List<Any?> {
            i++; val l = mutableListOf<Any?>(); ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(depth + 1); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return l
            }
        }
        fun string(): String {
            require(s[i] == '"'); i++
            val b = StringBuilder()
            while (true) {
                require(i < s.length); val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> { val e = s[i++]; when (e) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e) } }
                    else -> b.append(c)
                }
            }
        }
    }
}
