package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaCoreTest {
    private var now = 1_000_000L
    private fun core(memory: EvaMemory = EvaMemory(InMemoryEvaMemoryStore())) = EvaCore(memory, clock = { now })
    private val apps = listOf(AppTarget("Zona", "mobi.zona/.Main"), AppTarget("NUM", "ru.yourok.num/.Main"),
        AppTarget("YouTube", "com.google.android.youtube.tv/.Main"), AppTarget("Lift", "io.lift.app/.TvMainActivity"))
    private fun exec(reply: EvaReply) = (reply as? EvaReply.Execute)?.actions

    // ---- lexicon ----
    @Test fun numberWordsBecomeDigits() {
        assertEquals("громкость 25", EvaLexicon.numbersToDigits("громкость двадцать пять"))
        assertEquals("яркость 40 процентов", EvaLexicon.numbersToDigits("яркость сорок процентов"))
        assertEquals("на 100", EvaLexicon.numbersToDigits("на сто"))
        assertEquals("включи hdmi 2", EvaLexicon.numbersToDigits("включи hdmi два"))
        assertEquals("15", EvaLexicon.numbersToDigits("пятнадцать"))
        assertEquals("выбери первый", EvaLexicon.numbersToDigits("выбери первый"))
    }
    @Test fun misheardSettingsIsNotAMissingApp() {
        // Vosk on the emulator heard «ева открой надстройки» (r13 device suite).
        assertEquals(listOf(Action.Settings), exec(core().handle("ева открой надстройки") { apps }))
        assertEquals(listOf(Action.Settings), exec(core().handle("открой настроики") { apps }))
        // A real app is never "corrected" away, and unknown names are reported, not launched.
        assertEquals(listOf(Action.Launch("zona")), exec(core().handle("открой zona") { apps })?.map { (it as Action.Launch).copy(name = it.name.lowercase()) })
        // Not installed: EVA says so instead of launching nothing.
        assertTrue((core().handle("открой кинопоиск") { apps } as EvaReply.Say).text.startsWith("Не нашла приложение"))
    }
    @Test fun recognitionSlipsAreRepaired() {
        assertEquals("сделай погромче", EvaLexicon.correct("сделай погромчи"))
        assertEquals("открой ютуб", EvaLexicon.correct("аткрой ютуб"))
        assertEquals("зона", EvaLexicon.correct("зона"))
        assertEquals("что с погодой", EvaLexicon.correct("что с погодай"))
    }
    @Test fun fillersAreDroppedButNeverEverything() {
        assertEquals("сделай погромче", EvaLexicon.simplify("ну сделай-ка чуть погромче пожалуйста"))
        assertEquals("еще", EvaLexicon.simplify("ещё"))
    }

    // ---- strict rules stay authoritative ----
    @Test fun knownCommandsKeepTheirMeaning() {
        val phrases = listOf("сделай потише", "громче", "громкость 30", "пауза", "продолжи", "следующий канал", "включи hdmi 1",
            "открой настройки звука", "открой youtube", "открой приложение нум", "найди мстители в нум", "включи свет в гостиной",
            "вправо", "влево 3 раза", "какая сегодня погода", "проверь обновления", "открой последнее приложение",
            "какие приложения установлены", "включи канал тнт", "запусти сценарий вечер", "выключи звук")
        val strict = LocalIntentResolver()
        phrases.forEach { p ->
            val expected = (strict.resolve(p) as Resolution.Plan).actions
            assertEquals(p, expected, exec(core().handle(p) { apps }))
        }
    }
    @Test fun freeQuestionsNeverBecomeActions() {
        listOf("кто такой пушкин", "почему небо голубое", "расскажи анекдот", "как приготовить борщ", "посоветуй фильм на вечер",
            "сколько лет луне", "что такое квантовый компьютер").forEach { p ->
            assertTrue(p, core().handle(p) { apps } is EvaReply.Say)
        }
    }
    @Test fun smallTalkIsAnsweredOffline() {
        assertTrue(core().handle("который час") is EvaReply.Say)
        assertTrue(core().handle("сколько будет два плюс два") is EvaReply.Say)
    }

    // ---- natural speech the strict rules missed ----
    @Test fun naturalPhrasingsAreUnderstood() {
        assertEquals(listOf(Action.Volume(step = 1)), exec(core().handle("ну ева сделай-ка чуть погромче пожалуйста")))
        assertEquals(listOf(Action.Volume(step = 1)), exec(core().handle("сделай погромчи")))
        assertEquals(listOf(Action.Volume(percent = 25)), exec(core().handle("громкость двадцать пять")))
        assertEquals(listOf(Action.SwitchInput("hdmi", 2)), exec(core().handle("ева а теперь включи hdmi два")))
        assertEquals(listOf(Action.Diagnose("performance")), exec(core().handle("Ева, что-то телевизор тормозит")))
        assertEquals(listOf(Action.Diagnose("performance")), exec(core().handle("всё виснет и лагает")))
        assertEquals(listOf(Action.Diagnose("network")), exec(core().handle("интернет тормозит")))
        assertEquals(listOf(Action.Diagnose("network")), exec(core().handle("проверь интернет")))
        assertEquals(listOf(Action.Optimize("ads")), exec(core().handle("убери рекламу")))
        assertEquals(listOf(Action.Updates), exec(core().handle("есть ли обновления")))
        assertEquals(listOf(Action.RecentInstall(2)), exec(core().handle("открой приложение, которое я вчера установил")))
        assertEquals(listOf(Action.RecentInstall(7)), exec(core().handle("запусти последнее установленное приложение")))
        assertEquals(listOf(Action.AssistantSetup(false)), exec(core().handle("сделай себя основным помощником")))
        assertEquals(listOf(Action.AssistantSetup(false)), exec(core().handle("чтобы кнопка микрофона открывала тебя")))
    }

    // ---- dialog context ----
    @Test fun followUpsReuseThePreviousAction() {
        val eva = core()
        assertEquals(listOf(Action.Volume(step = 1)), exec(eva.handle("громче")))
        assertEquals(listOf(Action.Volume(step = 1)), exec(eva.handle("ещё")))
        assertEquals(listOf(Action.Volume(step = 1)), exec(eva.handle("ещё раз")))
        assertEquals(listOf(Action.Volume(step = -1)), exec(eva.handle("наоборот")))
        assertEquals(listOf(Action.Volume(percent = 40)), exec(eva.handle("а на сорок")))
        assertEquals(listOf(Action.Volume(percent = 15)), exec(eva.handle("15 процентов")))
        assertEquals(listOf(Action.Navigate(Direction.RIGHT)), exec(eva.handle("вправо")))
        assertEquals(listOf(Action.Navigate(Direction.RIGHT, 3)), exec(eva.handle("ещё три раза")))
        assertEquals(listOf(Action.Navigate(Direction.LEFT, 3)), exec(eva.handle("обратно")))
    }
    @Test fun followUpsExpire() {
        val eva = core()
        exec(eva.handle("громче"))
        now += 5 * 60_000
        assertFalse(eva.handle("ещё") is EvaReply.Execute)
    }
    @Test fun halfSaidCommandsGetOneQuestion() {
        val eva = core()
        assertTrue(eva.handle("звук") is EvaReply.Clarify)
        assertEquals(listOf(Action.Volume(step = -1)), exec(eva.handle("потише")))
        assertTrue(eva.handle("канал") is EvaReply.Clarify)
        assertEquals(listOf(Action.ChannelStep(-1)), exec(eva.handle("второй")))
        assertTrue(eva.handle("свет") is EvaReply.Clarify)
        assertEquals("Хорошо, не буду", (eva.handle("нет") as EvaReply.Say).text)
    }
    @Test fun unrelatedAnswerDropsTheQuestion() {
        val eva = core()
        assertTrue(eva.handle("звук") is EvaReply.Clarify)
        assertEquals(listOf(Action.Launch("youtube")), exec(eva.handle("открой youtube")))
    }

    // ---- risk ----
    @Test fun handsFreeAsksBeforeListening() {
        val eva = core()
        val ask = eva.handle("настрой себя чтобы я мог разговаривать с тобой без пульта")
        assertTrue(ask is EvaReply.Confirm)
        assertEquals(listOf(Action.AssistantSetup(true)), exec(eva.handle("да")))
        assertTrue(eva.handle("включи режим без пульта") is EvaReply.Confirm)
        assertEquals("Хорошо, не буду", (eva.handle("не надо") as EvaReply.Say).text)
        assertTrue(eva.handle("отзывайся всегда на имя") is EvaReply.Confirm)
        now += 60_000
        assertFalse(eva.handle("да") is EvaReply.Execute)
    }

    // ---- cinema & memory ----
    @Test fun cinemaAsksWhereThenRemembers() {
        val eva = core()
        val q = eva.handle("включи кино") { apps }
        assertTrue(q is EvaReply.Clarify)
        assertTrue((q as EvaReply.Clarify).question.contains("Zona"))
        assertEquals(listOf(Action.Launch("NUM")), exec(eva.handle("нум") { apps }))
        assertTrue(eva.handle("запомни, что кино — это Zona") is EvaReply.Say)
        assertEquals(listOf(Action.Launch("zona")), exec(eva.handle("хочу посмотреть фильм") { apps }))
        assertEquals(listOf(Action.Launch("zona")), exec(eva.handle("открой кино") { apps }))
    }
    @Test fun cinemaWithOneAppJustOpensIt() {
        assertEquals(listOf(Action.Launch("Zona")), exec(core().handle("включи фильм") { listOf(apps[0], apps[2]) }))
        assertTrue(core().handle("включи кино") { listOf(apps[2]) } is EvaReply.Say)
    }
    @Test fun specificFilmIsNotGenericCinema() {
        // R30: a named film opens the film app, searches and starts it (not a search in whatever app is in front).
        val plan = exec(core().handle("включи фильм аватар") { apps })!!
        assertTrue(plan.first() is Action.Launch)
        assertEquals(listOf(Action.Search("аватар"), Action.Choose(1), Action.Watch), plan.drop(1))
    }
    @Test fun usageOrdersChoicesButNeverPicksAlone() {
        val eva = core()
        repeat(3) { eva.executed(Action.Launch("NUM"), true) }
        val q = eva.handle("включи кино") { apps } as EvaReply.Clarify
        assertTrue(q.question.startsWith("Где смотреть? 1. NUM"))
        assertEquals(listOf(Action.Launch("NUM")), exec(eva.handle("первый") { apps }))
    }
    @Test fun memoryCanBeViewedForgottenAndSwitchedOff() {
        val store = InMemoryEvaMemoryStore()
        val eva = core(EvaMemory(store))
        eva.handle("мой город казань")
        eva.handle("когда я говорю мультики открывай youtube")
        val said = (eva.handle("что ты обо мне помнишь") as EvaReply.Say).text
        assertTrue(said, said.contains("Казань") && said.contains("мультики"))
        assertEquals(listOf(Action.Launch("youtube")), exec(eva.handle("включи мультики")))
        assertTrue((eva.handle("забудь мультики") as EvaReply.Say).text.startsWith("Забыла"))
        eva.handle("не запоминай")
        assertFalse(EvaMemory(store).enabled)
        assertNull(EvaMemory(store).city)
        assertTrue((eva.handle("запомни что кино это zona") as EvaReply.Say).text.contains("выключена"))
        eva.handle("включи память")
        assertTrue(EvaMemory(store).enabled)
    }
    @Test fun memoryLimits() {
        val m = EvaMemory(InMemoryEvaMemoryStore())
        repeat(50) { assertTrue(m.remember("слово $it", "app$it")) }
        assertFalse(m.remember("ещё одно", "x"))
        assertFalse(m.remember("x".repeat(41), "y"))
    }

    @Test fun cancelResetsDialog() {
        val eva = core()
        exec(eva.handle("громче"))
        assertEquals(listOf(Action.Cancel), exec(eva.handle("отмена")))
        assertFalse(eva.handle("ещё") is EvaReply.Execute)
    }

    @Test fun everydaySynonymsAndChains() {
        assertEquals(listOf(Action.Volume(step = -1)), exec(core().handle("убавь чуток")))
        assertEquals(listOf(Action.Volume(step = -1)), exec(core().handle("сделай звук потише пожалуйста")))
        assertEquals(listOf(Action.Volume(step = 1)), exec(core().handle("погромче сделай")))
        assertEquals(listOf(Action.Volume(mute = false)), exec(core().handle("верни звук обратно")))
        assertEquals(listOf(Action.ClearCache), exec(core().handle("очисти память телевизора")))
        assertEquals(listOf(Action.PlayRequest("рамштайн")), exec(core().handle("поставь рамштайн")))
        assertEquals(listOf(Action.Launch("ютуб")), exec(core().handle("переключи на ютуб")))
        assertEquals(listOf(Action.Launch("youtube"), Action.Search("рамштайн")), exec(core().handle("включи ютуб и найди рамштайн")))
        // r22: «выключи телевизор» now works through the power menu, after a spoken «да».
        assertTrue(core().handle("выключи телевизор") is EvaReply.Confirm)
    }
    @Test fun appNamesInCaseForms() {
        val list = listOf(AppTarget("Lift", "io.lift.app/.M"), AppTarget("Zona", "mobi.zona/.M"), AppTarget("YouTube", "y/.Y"), AppTarget("NUM", "ru.yourok.num/.M"))
        listOf("лифте" to "Lift", "зону" to "Zona", "ютубе" to "YouTube", "нуме" to "NUM").forEach { (spoken, label) ->
            assertEquals(spoken, AppMatch.Unique(list.first { it.label == label }), matchApp(spoken, list))
        }
    }
    @Test fun navigationRoundTripsThroughStrictRules() {
        val plan = listOf(Action.Navigate(Direction.RIGHT, 3), Action.Navigate(Direction.DOWN), Action.Navigate(Direction.OK))
        val text = navigationText(plan)!!
        assertEquals(Resolution.Plan(plan), LocalIntentResolver().resolve(text))
        assertNull(navigationText(listOf(Action.Weather)))
    }
}
