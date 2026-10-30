package com.example.homeezch.ai

import java.util.Calendar

/**
 * Offline answers to everyday questions that are not TV commands: time, date, arithmetic,
 * «кто ты» / «что ты умеешь», greetings. Works without the internet. Returns null when the phrase is not one of these.
 * Pure: no Android dependency, the clock is a parameter for tests.
 */
object EvaSmallTalk {
    private val months = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    private val weekdays = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
    private val words = mapOf("ноль" to 0.0, "один" to 1.0, "одна" to 1.0, "два" to 2.0, "две" to 2.0, "три" to 3.0,
        "четыре" to 4.0, "пять" to 5.0, "шесть" to 6.0, "семь" to 7.0, "восемь" to 8.0, "девять" to 9.0,
        "десять" to 10.0, "сто" to 100.0, "тысяча" to 1000.0)

    fun answer(raw: String, now: Calendar = Calendar.getInstance()): String? {
        val t = EvaSpeechText.clean(raw).trimEnd('?', '!', '.', ' ')
        if (t.isBlank() || t.length > 120) return null
        time(t, now)?.let { return it }
        date(t, now)?.let { return it }
        math(t)?.let { return it }
        return chat(t)
    }

    private fun has(t: String, vararg patterns: String) = patterns.any { Regex("(?<!\\p{L})$it(?!\\p{L})").containsMatchIn(t) }

    private fun time(t: String, now: Calendar): String? {
        val ask = Regex("^(?:а )?(?:(?:скажи|подскажи|скажите|подскажите) )?(?:который (?:сейчас )?час|сколько (?:сейчас )?(?:времени|время)|(?:какое )?(?:сейчас )?время|время сейчас)(?: сейчас)?$")
        if (!ask.matches(t)) return null
        val h = now.get(Calendar.HOUR_OF_DAY); val m = now.get(Calendar.MINUTE)
        return "Сейчас %d:%02d".format(h, m)
    }

    private fun date(t: String, now: Calendar): String? {
        val day = now.get(Calendar.DAY_OF_MONTH); val month = months[now.get(Calendar.MONTH)]
        val weekday = weekdays[now.get(Calendar.DAY_OF_WEEK) - 1]
        return when {
            has(t, "какой (?:сегодня )?день недели", "какой сегодня день", "какой день сегодня") -> "Сегодня $weekday, $day $month"
            has(t, "какое (?:сегодня )?число", "какое число сегодня", "какая (?:сегодня )?дата", "сегодняшняя дата") -> "Сегодня $day $month, $weekday"
            has(t, "какой (?:сейчас )?год", "какой год сейчас") -> "Сейчас ${now.get(Calendar.YEAR)} год"
            has(t, "какой (?:сейчас )?месяц", "какой месяц сейчас") -> "Сейчас ${monthName(now.get(Calendar.MONTH))}"
            else -> null
        }
    }
    private fun monthName(index: Int) = arrayOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль",
        "август", "сентябрь", "октябрь", "ноябрь", "декабрь")[index]

    private fun number(s: String): Double? = s.replace(',', '.').toDoubleOrNull() ?: words[s]

    /** «сколько будет 25 умножить на 4», «2+2», «100 разделить на 8», «15 процентов от 200». */
    private fun math(t: String): String? {
        var s = t.replace(Regex("^(?:а )?(?:сколько(?: будет| это| получится)?|посчитай|вычисли|сосчитай|реши)\\s*"), "")
            .replace(Regex("\\s*(?:будет|равно)$"), "").trim()
        val percent = Regex("^([\\d.,]+|\\p{L}+) (?:процент(?:а|ов)?|%) от ([\\d.,]+|\\p{L}+)$").find(s)
        if (percent != null) {
            val p = number(percent.groupValues[1]) ?: return null
            val of = number(percent.groupValues[2]) ?: return null
            return "${format(p)} процентов от ${format(of)} — это ${format(of * p / 100)}"
        }
        s = s.replace(Regex("(?<=\\d)\\s*([+*/×÷x-])\\s*(?=\\d)"), " $1 ")
        val m = Regex("^([\\d.,]+|\\p{L}+) (плюс|\\+|минус|-|умножить на|умножить|умноженное на|помножить на|на|x|×|\\*|разделить на|делить на|поделить на|поделённое на|деленное на|/|÷) ([\\d.,]+|\\p{L}+)$").find(s)
            ?: return null
        val a = number(m.groupValues[1]) ?: return null
        val b = number(m.groupValues[3]) ?: return null
        val op = m.groupValues[2]
        // Bare «на» (e.g. «два на два») is multiplication only when there was an explicit «сколько будет».
        if (op == "на" && s == t) return null
        val result = when (op) {
            "плюс", "+" -> a + b
            "минус", "-" -> a - b
            "разделить на", "делить на", "поделить на", "поделённое на", "деленное на", "/", "÷" ->
                if (b == 0.0) return "На ноль делить нельзя" else a / b
            else -> a * b
        }
        return "${format(a)} ${sign(op)} ${format(b)} = ${format(result)}"
    }
    private fun sign(op: String) = when (op) {
        "плюс", "+" -> "+"; "минус", "-" -> "−"
        "разделить на", "делить на", "поделить на", "поделённое на", "деленное на", "/", "÷" -> "÷"
        else -> "×"
    }
    internal fun format(v: Double): String =
        if (kotlin.math.abs(v - Math.rint(v)) < 1e-9 && kotlin.math.abs(v) < 1e15) Math.rint(v).toLong().toString()
        else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').replace('.', ',')

    private fun chat(t: String): String? = when {
        has(t, "кто ты", "ты кто", "как тебя зовут", "как твое имя", "представься") ->
            "Я Ева, голосовой помощник телевизора EZCH. Могу открыть приложение, найти фильм, переключить вход, изменить громкость, рассказать погоду, время и посчитать"
        has(t, "что ты умеешь", "что ты можешь", "помощь", "какие (?:есть )?команды", "что умеешь", "что (?:ещё|еще) (?:ты )?умеешь", "ты что (?:ещё|еще) умеешь",
            "что (?:ещё|еще) (?:ты )?можешь") ->
            "Скажите, например: «открой YouTube», «найди Интерстеллар в Кинопоиске», «громкость 20», «включи HDMI 2», «какая погода», «который час», «сколько будет 25 на 4»"
        Regex("^(?:привет|здравствуй|здравствуйте|добрый (?:день|вечер)|доброе утро|хай)$").matches(t) -> "Привет! Чем помочь?"
        Regex("^(?:спасибо|спасибо большое|благодарю|молодец|умница)$").matches(t) -> "Пожалуйста!"
        Regex("^(?:как дела|как ты|как поживаешь)$").matches(t) -> "Всё хорошо, готова помочь. Что включить?"
        // R27: greetings said in one breath: «привет как дела», «привет ева как дела», «здравствуй ева».
        Regex("^(?:привет|приветик|здравствуй|здравствуйте|хай|добрый (?:день|вечер)|доброе утро)(?: ева)?(?: (?:как (?:дела|ты|поживаешь|жизнь)|как у тебя дела))?$").matches(t) ->
            if (t.contains("как ")) "Привет! Всё хорошо, спасибо. Чем помочь?" else "Привет! Чем помочь?"
        has(t, "пора тебе развиваться", "развивайся", "ты (?:глупая|тупая|плохо понимаешь)", "учись") ->
            "Стараюсь! Я становлюсь умнее с каждым обновлением EZCH. Скажите, что мне стоит научиться делать"
        has(t, "(?:тебе|для твоей работы|для работы) нужен интернет", "ты работаешь без интернета", "(?:ты )?умеешь без интернета") ->
            "Команды телевизора, время, счёт и разговор с моей нейросетью работают без интернета. Интернет нужен для погоды и самого точного распознавания речи"
        else -> null
    }
}
