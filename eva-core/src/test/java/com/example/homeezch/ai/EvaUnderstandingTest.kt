package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Phrases taken from the owner's Haier diagnostics (25.10.2026) and everyday speech. */
class EvaUnderstandingTest {
    private var now = 1_000_000L
    private fun core() = EvaCore(EvaMemory(InMemoryEvaMemoryStore()), clock = { now })
    private val apps = listOf(AppTarget("YouTube", "com.google.android.youtube.tv/.Main"), AppTarget("Zona", "mobi.zona/.Main"),
        AppTarget("Кинопоиск", "ru.kinopoisk.tv/.Main"), AppTarget("NUM", "ru.yourok.num/.Main"))
    private fun exec(reply: EvaReply) = (reply as? EvaReply.Execute)?.actions

    @Test fun volumeWithNumberInLooseWords() {
        listOf("громко 20", "громко двадцать", "звук 20", "звук на двадцать", "поставь звук на 20 процентов",
            "сделай громкость двадцать", "громкость на двадцать", "ну громкость 20 пожалуйста").forEach { p ->
            assertEquals(p, listOf(Action.Volume(percent = 20)), exec(core().handle(p) { apps }))
        }
        assertTrue(core().handle("громкость 300") is EvaReply.Say)
    }

    @Test fun volumeDirectionInLooseWords() {
        listOf("слишком тихо", "очень тихо", "звук побольше", "прибавь звук немного", "звук выше").forEach { p ->
            assertEquals(p, listOf(Action.Volume(step = 1)), exec(core().handle(p) { apps }))
        }
        listOf("слишком громко", "очень громко", "звук поменьше", "звук ниже", "убавь звук").forEach { p ->
            assertEquals(p, listOf(Action.Volume(step = -1)), exec(core().handle(p) { apps }))
        }
    }

    @Test fun brightnessOpensPictureSettings() {
        listOf("сделай ярче", "сделай яркич", "яркость больше", "сделай темнее", "убавь яркость экрана").forEach { p ->
            assertEquals(p, listOf(Action.OpenSettings("display")), exec(core().handle(p) { apps }))
        }
        // Room light stays smart home, not the TV picture.
        assertEquals(listOf(Action.Home("on", "свет гостиная")), exec(core().handle("включи свет в гостиной") { apps }))
    }

    @Test fun pronounOpensThePreviousApp() {
        val c = core()
        assertEquals(listOf(Action.LastApp), exec(c.handle("открой его") { apps }))
        c.executed(Action.Launch("youtube"), true)
        assertEquals(listOf(Action.Launch("youtube")), exec(c.handle("открой его") { apps }))
        assertEquals(listOf(Action.Launch("youtube")), exec(c.handle("включи его снова") { apps }))
        assertEquals(listOf(Action.Launch("youtube")), exec(c.handle("верни то приложение") { apps }))
    }

    @Test fun remarksAboutEvaAreAnswered() {
        listOf("ударение неправильно", "ты неправильно говоришь", "имя своё", "как тебя зовут", "ты меня слышишь", "не то").forEach { p ->
            val reply = core().handle(p) { apps }
            assertTrue("$p → $reply", reply is EvaReply.Say)
        }
        assertTrue((core().handle("ударение неправильно") as EvaReply.Say).text.contains("голос"))
        assertTrue((core().handle("имя своё") as EvaReply.Say).text.contains("Ева"))
    }

    @Test fun bareAppNameOpensIt() {
        assertEquals(listOf(Action.Launch("YouTube")), exec(core().handle("ютуб") { apps }))
        assertEquals(listOf(Action.Launch("Кинопоиск")), exec(core().handle("кинопоиск") { apps }))
    }

    @Test fun weakGuessIsAskedThenRun() {
        val c = core()
        val reply = c.handle("следующий канан") { apps }
        assertTrue("$reply", reply is EvaReply.Confirm || reply is EvaReply.Execute)
        if (reply is EvaReply.Confirm) assertEquals(listOf(Action.ChannelStep(1)), exec(c.handle("да") { apps }))
    }

    @Test fun unknownAppIsReportedNotLaunched() {
        val reply = core().handle("открой абракадабру") { apps }
        assertTrue("$reply", reply is EvaReply.Say || reply is EvaReply.Confirm)
    }

    @Test fun freeQuestionsGetAnHonestOfflineAnswer() {
        listOf("кто такой пушкин", "почему небо голубое", "посоветуй фильм на вечер", "сколько лет луне").forEach { p ->
            val reply = core().handle(p) { apps }
            assertTrue("$p → $reply", reply is EvaReply.Say)
            assertTrue((reply as EvaReply.Say).text.contains("без интернета"))
        }
        val gibberish = core().handle("бла бла трактор сапоги") { apps }
        assertTrue(gibberish is EvaReply.Say && gibberish.text.startsWith("Не поняла"))
    }

    @Test fun nBestPicksTheUnderstoodHypothesis() {
        val c = core()
        assertEquals("громкость 20", c.pick(listOf("громкий 20 два", "громкость 20")) { apps })
        assertEquals("открой YouTube", c.pick(listOf("открой ютуп чик", "открой YouTube")) { apps })
        assertEquals("включи hdmi 2", c.pick(listOf("включи hdmi 2", "включи эйч ди")) { apps })
        assertEquals("одно", c.pick(listOf("одно")) { apps })
    }

    @Test fun similarityIsSane() {
        assertTrue(EvaUnderstanding.similarity("следующий канан", "следующий канал") > 0.78)
        assertFalse(EvaUnderstanding.similarity("привет", "вправо") > 0.55)
    }

    @Test fun noAppIsLaunchedByAccident() {
        // Everyday sentences must not become actions through fuzzy matching.
        listOf("расскажи анекдот", "как приготовить борщ", "что такое квантовый компьютер", "мне грустно", "я пошёл спать").forEach { p ->
            assertEquals(p, null, exec(core().handle(p) { apps }))
        }
    }
}

class EvaSpeakPlacesTest {
    private fun say(text: String) = EvaSpeak.prepare(text).joinToString(" ")
    @org.junit.Test fun latinCityAndNamesAreReadInRussian() {
        org.junit.Assert.assertEquals("Казань. Сейчас плюс 5 градусов. Малооблачно.", say("Kazan. Сейчас плюс 5 градусов. Малооблачно."))
        org.junit.Assert.assertEquals("Санкт-Петербург", say("Saint Petersburg"))
        org.junit.Assert.assertEquals("Открываю кинопоиск", say("Открываю Kinopoisk"))
        org.junit.Assert.assertEquals("Открываю зона", say("Открываю Zona"))
        org.junit.Assert.assertEquals("эс ти эс", say("STS"))
    }
}
