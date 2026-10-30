package com.example.homeezch.ai

/**
 * Text → clear Russian speech (ТЗ §9.3). A Russian TTS voice mangles Latin brand names, units,
 * symbols and quotes, so every spoken answer is rewritten into words first and split into short
 * phrases: the first phrase starts playing at once instead of waiting for a long synthesis.
 * The screen keeps the original text; only the voice gets this version.
 */
object EvaSpeak {
    /** Whole-word replacements, case-insensitive. Longer keys first so «YouTube Music» wins over «YouTube». */
    private val words = listOf(
        "Ai EvA" to "Ева", "EVA" to "Ева", "Eva" to "Ева",
        "YouTube Music" to "ютуб мьюзик", "YouTube" to "ютуб", "RuTube" to "рутуб", "VK Видео" to "вэ ка видео",
        "Google TV" to "гугл ти ви", "Google" to "гугл", "Android TV" to "андроид ти ви", "Android" to "андроид",
        "Home Assistant" to "хоум ассистант", "Smart Life" to "смарт лайф", "Tuya" to "туя", "Xiaomi" to "сяоми",
        "Wi-Fi" to "вай фай", "WiFi" to "вай фай", "Bluetooth" to "блютус", "HDMI" to "эйч ди эм ай",
        "USB" to "ю эс би", "AV" to "эй ви", "TV" to "ти ви", "ТВ" to "тэ вэ", "ADB" to "эй ди би", "API" to "эй пи ай",
        "PIN" to "пин", "OK" to "окей", "4K" to "четыре кей", "1080p" to "тысяча восемьдесят пэ", "720p" to "семьсот двадцать пэ",
        "Zona" to "зона", "NUM" to "нум", "Lift" to "лифт", "Lampa" to "лампа", "Prisma" to "призма", "VoKino" to "вокино",
        "KinoTrend" to "кинотренд", "SerialTrend" to "сериал тренд", "Lite HD TV" to "лайт эйч ди ти ви",
        "Task Killer" to "таск киллер", "TorrServe" to "торрсерв", "EZCH Update" to "изи-си-эйч апдейт",
        "Мбит/с" to "мегабит в секунду", "Кбит/с" to "килобит в секунду", "МБ" to "мегабайт", "ГБ" to "гигабайт",
        "КБ" to "килобайт", "мс" to "миллисекунд", "т.е." to "то есть", "т.д." to "так далее", "т.п." to "тому подобное"
    ).sortedByDescending { it.first.length }

    private val wordRegex = words.map { (from, to) ->
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) to to
    }

    /** Place names the TV's geocoder returns in Latin («Kazan»): read as Russians say them. */
    private val places = mapOf("kazan" to "Казань", "moscow" to "Москва", "saint petersburg" to "Санкт-Петербург",
        "st petersburg" to "Санкт-Петербург", "novosibirsk" to "Новосибирск", "yekaterinburg" to "Екатеринбург",
        "nizhny novgorod" to "Нижний Новгород", "samara" to "Самара", "ufa" to "Уфа", "chelyabinsk" to "Челябинск",
        "omsk" to "Омск", "rostov-on-don" to "Ростов-на-Дону", "krasnodar" to "Краснодар", "perm" to "Пермь",
        "voronezh" to "Воронеж", "volgograd" to "Волгоград", "naberezhnye chelny" to "Набережные Челны",
        "almetyevsk" to "Альметьевск", "nizhnekamsk" to "Нижнекамск", "yelabuga" to "Елабуга", "zelenodolsk" to "Зеленодольск",
        "tatarstan" to "Татарстан", "russia" to "Россия", "minsk" to "Минск", "kyiv" to "Киев", "almaty" to "Алматы",
        "tashkent" to "Ташкент", "sochi" to "Сочи", "kaliningrad" to "Калининград", "tyumen" to "Тюмень", "izhevsk" to "Ижевск",
        "ulyanovsk" to "Ульяновск", "cheboksary" to "Чебоксары", "orenburg" to "Оренбург", "saratov" to "Саратов")
    private val placeRegex = places.keys.sortedByDescending { it.length }.map { key ->
        Regex("(?<![\\p{L}])" + key.split(' ', '-').joinToString("[ -]") { Regex.escape(it) } + "(?![\\p{L}])", RegexOption.IGNORE_CASE) to places.getValue(key)
    }
    private val letterNames = mapOf('a' to "эй", 'b' to "би", 'c' to "си", 'd' to "ди", 'e' to "и", 'f' to "эф", 'g' to "джи",
        'h' to "эйч", 'i' to "ай", 'j' to "джей", 'k' to "кей", 'l' to "эл", 'm' to "эм", 'n' to "эн", 'o' to "оу", 'p' to "пи",
        'q' to "кью", 'r' to "ар", 's' to "эс", 't' to "ти", 'u' to "ю", 'v' to "ви", 'w' to "дабл ю", 'x' to "экс", 'y' to "уай", 'z' to "зед")
    private val digraphs = listOf("shch" to "щ", "sch" to "ш", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "yo" to "ё", "ye" to "е", "ee" to "и", "oo" to "у", "th" to "т", "ph" to "ф", "ck" to "к",
        "qu" to "кв", "tion" to "шн", "ou" to "ау")
    private val singles = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х",
        'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з")

    /** A Latin word the Russian voice would read with English rules («Kazan» → «КАзан»): spoken in Russian letters. */
    internal fun russianReading(word: String): String {
        if (word.length in 2..4 && word.all { it.isUpperCase() }) return word.lowercase().map { letterNames[it] ?: "" }.joinToString(" ")
        val w = word.lowercase(); val out = StringBuilder(); var i = 0
        while (i < w.length) {
            val pair = digraphs.firstOrNull { w.startsWith(it.first, i) }
            if (pair != null) { out.append(pair.second); i += pair.first.length; continue }
            val ch = w[i]
            out.append(if (ch == 'c' && i + 1 < w.length && w[i + 1] in "eiy") "с" else singles[ch] ?: ch.toString()); i++
        }
        return out.toString()
    }

    /** Spoken form of [text], cut into phrases of at most [maxChunk] characters. Empty list = nothing to say. */
    fun prepare(text: String, maxChunk: Int = 180): List<String> {
        var t = text
            .replace(Regex("https?://\\S+"), " ")                         // links are not read aloud
            .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), " ")         // emoji and pictograms
            .replace(Regex("[«»\"“”„*_#`|<>\\[\\]{}]"), " ")
        for ((regex, to) in placeRegex) t = t.replace(regex, to)
        for ((regex, to) in wordRegex) t = t.replace(regex, to)
        // Whatever Latin is left (an app or city name) is read in Russian letters, not with English stress.
        t = t.replace(Regex("(?<![\\p{L}\\p{N}])[A-Za-z][A-Za-z']*(?![\\p{L}\\p{N}])")) { russianReading(it.value.replace("'", "")) }
        t = t.replace(Regex("(\\d)\\s*%"), "$1 процентов").replace("%", " процентов")
            .replace(Regex("(\\d)\\s*°\\s*C?"), "$1 градусов").replace("°", " градусов")
            .replace("№", " номер ").replace("&", " и ").replace("+", " плюс ")
            .replace(Regex("\\s[—–-]\\s"), ", ").replace(Regex("[—–]"), ", ")
            .replace("…", ".").replace(Regex("\\.{2,}"), ".")
            .replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?;:])"), "$1").replace(Regex(",{2,}"), ",")
            .trim().trim(',', ' ')
        if (t.isBlank()) return emptyList()
        // Sentences first, then long sentences at commas, then hard cut on a space.
        val out = mutableListOf<String>()
        for (sentence in Regex("[^.!?;]+[.!?;]?").findAll(t).map { it.value.trim() }.filter { it.isNotBlank() }) {
            if (sentence.length <= maxChunk) { out += sentence; continue }
            var current = StringBuilder()
            for (part in sentence.split(Regex("(?<=,)\\s"))) {
                if (current.isNotEmpty() && current.length + part.length + 1 > maxChunk) { out += current.toString().trim(); current = StringBuilder() }
                var piece = part
                while (piece.length > maxChunk) {
                    val cut = piece.lastIndexOf(' ', maxChunk).takeIf { it > maxChunk / 2 } ?: maxChunk
                    out += piece.substring(0, cut).trim(); piece = piece.substring(cut).trim()
                }
                current.append(piece).append(' ')
            }
            if (current.isNotBlank()) out += current.toString().trim()
        }
        return out.filter { it.any(Char::isLetterOrDigit) }
    }
}
