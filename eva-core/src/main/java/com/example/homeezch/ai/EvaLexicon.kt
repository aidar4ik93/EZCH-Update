package com.example.homeezch.ai

/**
 * EVA AI Core — lexical layer. Turns what speech recognition actually produces into text the
 * intent rules understand: filler words, Russian number words, one-letter recognition slips.
 * Pure Kotlin, no Android: every rule is covered by JVM tests.
 */
object EvaLexicon {
    /** Words that carry no command meaning in «ну ева сделай-ка чуть погромче пожалуйста». */
    private val fillers = setOf("а", "ну", "так", "слушай", "послушай", "ева", "эва", "ива", "eva", "пожалуйста", "плиз",
        "можешь", "можно", "мне", "давай", "давайка", "будь", "добра", "любезна", "ка", "чуть", "чуточку", "немного",
        "немножко", "капельку", "слегка", "чуток", "чутка", "малость", "маленько", "еще", "ещё", "теперь", "там", "тогда", "лучше", "просто", "вот", "это", "же",
        "уже", "сейчас", "срочно", "быстро", "быстрее", "эй", "окей", "ок", "хорошо", "please")

    /** Command words the rules depend on: a recognition slip of one letter is corrected to these. */
    private val vocabulary = listOf("громче", "погромче", "тише", "потише", "громкость", "включи", "выключи", "открой",
        "запусти", "найди", "настройки", "следующий", "предыдущий", "канал", "пауза", "продолжи", "погода", "погоду",
        "обновления", "рекламу", "реклама", "замолчи", "паузу", "продолжай", "процентов", "процента", "пожалуйста", "прошлый",
        "слишком", "градусов", "градуса", "предыдущий", "следующий", "погодой", "сколько", "останови", "назад", "телевизор", "приложение", "приложения", "установил", "помощник",
        "помощником", "тормозит", "интернет", "яркость", "переключи", "вправо", "влево", "вверх", "вниз", "назад")

    private val units = mapOf("ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9)
    private val teens = mapOf("десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19)
    private val tens = mapOf("двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
        "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90)
    private val hundreds = mapOf("сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900)

    fun tokens(text: String): List<String> =
        normalize(text.replace('-', ' ')).split(Regex("[^\\p{L}\\p{N}%]+")).filter { it.isNotEmpty() }

    /** «двадцать пять» → «25», «сто» → «100». Ordinals («первый») are left alone. */
    private val numberWords by lazy { (units.keys + teens.keys + tens.keys + hundreds.keys).toList() }
    /** «шестьдисят», «пятнодцать», «двадцоть» → the number word recognition meant (long words, one slip). */
    fun fixNumberWord(word: String): String {
        if (word.length < 6 || word in numberWords || word.any { it.isDigit() }) return word
        return numberWords.firstOrNull { it.length >= 6 && kotlin.math.abs(it.length - word.length) <= 1 && distance(it, word) == 1 } ?: word
    }

    fun numbersToDigits(text: String): String {
        val words = tokens(text).map(::fixNumberWord)
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            var value = -1; var j = i
            hundreds[words[j]]?.let { value = it; j++ }
            if (j < words.size) {
                val t = tens[words[j]]; val teen = teens[words[j]]; val u = units[words[j]]
                when {
                    teen != null -> { value = maxOf(value, 0) + teen; j++ }
                    t != null -> { value = maxOf(value, 0) + t; j++
                        units[words.getOrNull(j)]?.takeIf { it > 0 }?.let { value += it; j++ } }
                    u != null && (value < 0 || u > 0) -> { value = maxOf(value, 0) + u; j++ }
                }
            }
            if (value >= 0 && j > i) { out += value.toString(); i = j } else { out += words[i]; i++ }
        }
        return out.joinToString(" ")
    }

    /**
     * Fixes one-letter recognition slips in command words: «громчи» → «громче», «аткрой» → «открой».
     * A same-length substitution wins over an insertion/deletion: «погодай» → «погодой», not «погода».
     */
    fun correct(text: String): String = tokens(text).joinToString(" ") { word ->
        if (word.length < 5 || word in vocabulary || word.any { it.isDigit() }) word
        else vocabulary.filter { it.length >= 5 && kotlin.math.abs(it.length - word.length) <= 1 && distance(it, word) == 1 }
            .minByOrNull { kotlin.math.abs(it.length - word.length) } ?: word
    }

    /** Removes fillers but never empties the phrase: a lone «ещё» stays for the dialog layer. */
    fun simplify(text: String): String {
        val words = tokens(text)
        val kept = words.filter { it !in fillers }
        return (kept.ifEmpty { words }).joinToString(" ")
    }

    /** Full lexical pass used when the strict rules did not understand the raw phrase. */
    fun canonical(text: String): String = simplify(numbersToDigits(correct(text)))

    /** Prefix match with tolerance for one slip in long stems: «тормоз» matches «тормазит». */
    fun hasStem(words: List<String>, stem: String): Boolean {
        if (' ' in stem) return words.joinToString(" ").contains(stem)
        return words.any { w ->
            w.startsWith(stem) || stem.length >= 6 && w.length >= stem.length && distance(w.substring(0, stem.length), stem) <= 1
        }
    }

    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous[b.length]
    }
}
