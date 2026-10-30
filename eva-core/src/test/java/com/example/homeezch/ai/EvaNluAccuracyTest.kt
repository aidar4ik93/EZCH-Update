package com.example.homeezch.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ai EvA intent accuracy on ≥500 phrasings (criteria 18.3: ≥95 % correct intent, 0 false actions on negatives).
 * Phrases combine real wording variants with the prefixes people use («Ева,», «пожалуйста», «а можешь»).
 */
class EvaNluAccuracyTest {
    private val resolver = LocalIntentResolver()
    private val prefixes = listOf("", "ева, ", "ева ", "пожалуйста, ", "ева, пожалуйста, ")
    private data class Case(val text: String, val expect: (List<Action>) -> Boolean, val label: String)

    private fun cases(): List<Case> {
        val out = mutableListOf<Case>()
        fun add(label: String, phrases: List<String>, expect: (List<Action>) -> Boolean) =
            phrases.forEach { p -> prefixes.forEach { pre -> out += Case(pre + p, expect, label) } }

        add("volume-down", listOf("сделай потише", "потише", "тише", "убавь звук", "уменьши громкость", "звук тише", "слишком громко", "сделай тише")) { it == listOf(Action.Volume(step = -1)) }
        add("volume-up", listOf("сделай погромче", "погромче", "громче", "прибавь звук", "увеличь громкость", "звук громче", "плохо слышно", "слишком тихо")) { it == listOf(Action.Volume(step = 1)) }
        add("mute", listOf("выключи звук", "убери звук", "замолчи", "без звука", "отключи звук")) { it == listOf(Action.Volume(mute = true)) }
        add("volume-%", listOf("громкость 30", "громкость на 30", "сделай громкость 30 процентов", "звук на 30")) { it == listOf(Action.Volume(percent = 30)) }
        add("pause", listOf("пауза", "поставь на паузу", "на паузу", "останови видео", "приостанови", "стоп видео")) { it == listOf(Action.Media(false)) }
        add("play", listOf("продолжи", "продолжай", "сними с паузы", "играй дальше", "возобнови просмотр")) { it == listOf(Action.Media(true)) }
        add("channel+", listOf("следующий канал", "переключи на следующий канал", "переключи канал", "канал вперед", "каналы дальше")) { it == listOf(Action.ChannelStep(1)) }
        add("channel-", listOf("предыдущий канал", "прошлый канал", "верни предыдущий канал", "канал назад")) { it == listOf(Action.ChannelStep(-1)) }
        add("hdmi1", listOf("включи hdmi 1", "переключи на hdmi 1", "включи телевизор на hdmi 1", "вход hdmi 1", "эйч ди эм ай один", "переключись на hdmi первый")) { it == listOf(Action.SwitchInput("hdmi", 1)) }
        add("hdmi2", listOf("включи hdmi 2", "hdmi два", "источник hdmi 2")) { it == listOf(Action.SwitchInput("hdmi", 2)) }
        add("av", listOf("включи av", "переключи на av")) { it == listOf(Action.SwitchInput("av", null)) }
        add("tv", listOf("включи тв", "переключи на эфирное тв", "включи телевидение")) { it == listOf(Action.SwitchInput("tv", null)) }
        add("settings-display", listOf("открой настройки изображения", "открой настройки экрана", "покажи настройки картинки")) { it == listOf(Action.OpenSettings("display")) }
        add("settings-wifi", listOf("открой настройки wi-fi", "открой настройки вай фай", "открой настройки сети")) { it == listOf(Action.OpenSettings("wifi")) }
        add("settings-bt", listOf("открой настройки bluetooth", "открой настройки блютуз")) { it == listOf(Action.OpenSettings("bluetooth")) }
        add("settings-sound", listOf("открой настройки звука")) { it == listOf(Action.OpenSettings("sound")) }
        add("settings", listOf("открой настройки", "открой настройки телевизора")) { it.single().let { a -> a == Action.Settings || a == Action.OpenSettings("general") } }
        add("updates", listOf("проверь обновления")) { it == listOf(Action.Updates) }
        add("list-apps", listOf("какие приложения установлены", "какие приложения есть", "покажи установленные приложения", "что у меня установлено")) { it == listOf(Action.ListApps) }
        add("last-app", listOf("открой последнее приложение", "запусти последнее приложение", "открой последнее", "верни последнее приложение")) { it == listOf(Action.LastApp) }
        add("game", listOf("поиграй со мной", "давай поиграем", "мне скучно", "загадай загадку")) { it == listOf(Action.PlayGame) }
        add("weather", listOf("какая сегодня погода", "погода", "сколько градусов на улице")) { it == listOf(Action.Weather) }
        add("launch", listOf("открой youtube", "запусти кинопоиск", "открой приложение нум", "запусти zona", "открой ютуб")) { it.single() is Action.Launch }
        add("search", listOf("найди фильм аватар", "найди аватар", "найди мстители в нум")) { it.any { a -> a is Action.Search } }
        add("light-on", listOf("включи свет в гостиной", "зажги свет в гостиной", "включи люстру в гостиной")) { it == listOf(Action.Home("on", "свет гостиная")) }
        add("light-off", listOf("выключи свет в спальне", "погаси свет в спальне")) { it == listOf(Action.Home("off", "свет спальня")) }
        add("light-all", listOf("выключи свет", "погаси свет")) { it == listOf(Action.Home("off", "свет")) }
        add("brightness", listOf("сделай освещение на 30 процентов", "яркость 30", "яркость света 30 процентов", "свет на 30 процентов")) { it.singleOrNull().let { a -> a is Action.Home && a.op == "brightness" && a.value == 30 } }
        add("home-temp", listOf("какая температура дома", "сколько градусов дома", "какая температура в доме")) { it == listOf(Action.Home("temperature", "дом")) }
        add("scene", listOf("запусти вечерний сценарий", "запусти сценарий вечер", "включи режим кино")) { it.singleOrNull().let { a -> a is Action.Home && a.op == "scene" } }
        add("socket", listOf("включи розетку на кухне", "выключи чайник")) { it.singleOrNull().let { a -> a is Action.Home && a.op in setOf("on", "off") } }
        add("lock", listOf("открой замок", "отопри дверь")) { it == listOf(Action.Home("unlock", "")) }
        add("nav", listOf("вправо", "влево 3 раза", "вниз", "домой")) { it.single() is Action.Navigate }
        return out
    }

    /** Free questions must NOT become an action: they go to the conversational AI. */
    private val negatives = listOf("кто такой пушкин", "расскажи анекдот", "сколько будет два плюс два", "что такое квантовый компьютер",
        "как дела", "почему небо голубое", "кто выиграл чемпионат мира", "переведи привет на английский",
        "сколько лет луне", "посоветуй фильм на вечер", "что посмотреть сегодня", "как приготовить борщ", "какой сегодня день",
        "расскажи про космос", "ты кто", "как тебя зовут", "что ты умеешь", "спасибо", "привет", "доброе утро")

    @Test fun intentAccuracyAtLeast95Percent() {
        val all = cases()
        assertTrue("набор должен содержать ≥500 фраз, сейчас ${all.size}", all.size >= 500)
        val failures = all.filterNot { c ->
            val r = resolver.resolve(c.text)
            r is Resolution.Plan && c.expect(r.actions)
        }
        val accuracy = 100.0 * (all.size - failures.size) / all.size
        println("Ai EvA NLU: ${all.size} фраз, точность ${"%.1f".format(accuracy)}%")
        failures.groupBy { it.label }.forEach { (label, list) ->
            println("  FAIL $label (${list.size}): ${list.take(3).joinToString { "«${it.text}» → ${resolver.resolve(it.text)}" }}")
        }
        assertTrue("точность $accuracy% < 95%", accuracy >= 95.0)
    }

    @Test fun freeQuestionsNeverBecomeActions() {
        val wrong = negatives.flatMap { q -> prefixes.map { it + q } }.filter { resolver.resolve(it) is Resolution.Plan }
        assertTrue("ложные действия: $wrong", wrong.isEmpty())
    }
}
