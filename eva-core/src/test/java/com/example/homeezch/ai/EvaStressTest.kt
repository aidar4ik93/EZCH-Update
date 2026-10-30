package com.example.homeezch.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Hard stress of EVA Core (round 22): thousands of generated spoken variants — fillers, number words, polite forms,
 * recognition slips — must give the expected action, and everyday chatter must never become an action.
 * Also a speed budget: the core must answer in well under a frame on a TV CPU.
 */
class EvaStressTest {
    private val apps = listOf(AppTarget("YouTube", "com.google.android.youtube.tv/.Main"), AppTarget("Кинопоиск", "ru.kinopoisk/.Main"),
        AppTarget("Zona", "mobi.zona/.Main"), AppTarget("NUM", "ru.yourok.num/.Main"), AppTarget("RuTube", "ru.rutube/.Main"))
    private val rnd = Random(22)
    private val prefixes = listOf("", "ева ", "ева, ", "ну ", "слушай ", "а ", "эй ева ", "ева пожалуйста ")
    private val suffixes = listOf("", " пожалуйста", " быстрее", " давай", " ну", " а")
    private val numberWords = mapOf(5 to "пять", 10 to "десять", 15 to "пятнадцать", 20 to "двадцать", 25 to "двадцать пять", 30 to "тридцать",
        40 to "сорок", 45 to "сорок пять", 50 to "пятьдесят", 60 to "шестьдесят", 70 to "семьдесят", 80 to "восемьдесят", 100 to "сто")

    private fun slip(text: String): String {
        // one-letter recognition slip in a long word, sometimes
        if (rnd.nextInt(5) != 0) return text
        val words = text.split(' ').toMutableList()
        val i = words.indices.filter { words[it].length >= 7 && words[it].all(Char::isLetter) }.randomOrNull(rnd) ?: return text
        val w = words[i]; val k = 2 + rnd.nextInt(w.length - 3)
        val swap = mapOf('е' to 'и', 'и' to 'е', 'о' to 'а', 'а' to 'о')
        words[i] = w.substring(0, k) + (swap[w[k]] ?: w[k]) + w.substring(k + 1)
        return words.joinToString(" ")
    }
    private fun dress(core: String) = slip(prefixes.random(rnd) + core + suffixes.random(rnd))

    private fun cases(): List<Pair<String, List<Action>>> {
        val out = mutableListOf<Pair<String, List<Action>>>()
        for ((n, w) in numberWords) {
            val v = listOf(Action.Volume(percent = n))
            listOf("громкость $n", "громкость $w", "громкость на $w", "звук $w", "звук на $w", "громко $w", "поставь громкость $w процентов",
                "сделай громкость $w", "сделай звук на $w").forEach { out += it to v }
        }
        val up = listOf(Action.Volume(step = 1)); val down = listOf(Action.Volume(step = -1))
        listOf("громче", "погромче", "сделай громче", "сделай погромче", "прибавь звук", "прибавь громкость", "звук погромче", "слишком тихо",
            "очень тихо", "не слышно", "увеличь громкость", "звук побольше").forEach { out += it to up }
        listOf("тише", "потише", "сделай тише", "сделай потише", "убавь звук", "убавь громкость", "звук потише", "слишком громко",
            "очень громко", "уменьши громкость", "звук поменьше").forEach { out += it to down }
        listOf("выключи звук", "без звука", "замолчи").forEach { out += it to listOf(Action.Volume(mute = true)) }
        listOf("пауза", "поставь на паузу", "останови видео", "стоп видео").forEach { out += it to listOf(Action.Media(false)) }
        listOf("продолжи", "продолжай", "сними с паузы", "продолжить просмотр").forEach { out += it to listOf(Action.Media(true)) }
        listOf("следующий канал", "переключи на следующий канал", "канал вперед").forEach { out += it to listOf(Action.ChannelStep(1)) }
        listOf("предыдущий канал", "прошлый канал", "канал назад").forEach { out += it to listOf(Action.ChannelStep(-1)) }
        for ((n, w) in listOf(1 to "один", 2 to "два", 3 to "три")) listOf("включи hdmi $n", "включи эйч ди эм ай $w", "переключи на hdmi $w", "вход hdmi $n")
            .forEach { out += it to listOf(Action.SwitchInput("hdmi", n)) }
        listOf("открой настройки", "открой надстройки", "настройки").forEach { out += it to listOf(Action.Settings) }
        listOf("какая погода", "какая сегодня погода", "что с погодой", "сколько градусов на улице").forEach { out += it to listOf(Action.Weather) }
        listOf("сделай ярче", "сделай темнее", "яркость больше").forEach { out += it to listOf(Action.OpenSettings("display")) }
        listOf("вправо", "влево", "вверх", "вниз").zip(listOf(Direction.RIGHT, Direction.LEFT, Direction.UP, Direction.DOWN))
            .forEach { (p, d) -> out += p to listOf(Action.Navigate(d)) }
        return out
    }
    private val chatter = listOf("я пошёл на кухню", "как прошёл твой день", "мне грустно сегодня", "кто такой пушкин", "почему небо голубое",
        "расскажи анекдот", "что такое квантовый компьютер", "посоветуй фильм на вечер", "сколько лет луне", "какой самый высокий дом",
        "я люблю пиццу", "завтра будет понедельник", "у меня болит голова", "где мои ключи", "мама звонила", "надо купить хлеб",
        "интересно сколько стоит машина", "ты умная", "давай дружить", "что ты думаешь о погоде в мире", "хочу в отпуск",
        "как приготовить борщ", "сколько километров до москвы", "кто выиграл чемпионат", "а ты знаешь что такое любовь",
        "спой песню", "расскажи сказку", "какая столица франции", "переведи слово кошка", "напиши стих")

    @Test fun thousandsOfSpokenVariantsGiveTheRightAction() {
        val base = cases()
        var total = 0; var ok = 0; val misses = mutableListOf<String>()
        val start = System.nanoTime()
        repeat(55) {
            for ((core, expected) in base) {
                val phrase = dress(core)
                val reply = EvaCore().handle(phrase) { apps }
                total++
                val got = (reply as? EvaReply.Execute)?.actions ?: (reply as? EvaReply.Confirm)?.actions
                if (got == expected) ok++ else if (misses.size < 40) misses += "«$phrase» → $reply"
            }
        }
        val perCallMs = (System.nanoTime() - start) / 1e6 / total
        val accuracy = ok * 100.0 / total
        println("EVA stress: $total фраз, верно ${"%.2f".format(accuracy)} %, ${"%.3f".format(perCallMs)} мс на фразу")
        misses.take(15).forEach { println("  промах: $it") }
        assertTrue("точность $accuracy % (${misses.take(5)})", accuracy >= 97.0)
        assertTrue("слишком медленно: $perCallMs мс", perCallMs < 20.0)
    }

    @Test fun everydayChatterNeverBecomesAnAction() {
        var actions = 0; val wrong = mutableListOf<String>()
        repeat(40) {
            for (c in chatter) {
                val phrase = prefixes.random(rnd) + c
                val reply = EvaCore().handle(phrase) { apps }
                if (reply is EvaReply.Execute) { actions++; if (wrong.size < 10) wrong += "«$phrase» → $reply" }
            }
        }
        println("EVA chatter: ${40 * chatter.size} фраз, ложных действий: $actions")
        assertTrue("ложные действия: $wrong", actions == 0)
    }
}
