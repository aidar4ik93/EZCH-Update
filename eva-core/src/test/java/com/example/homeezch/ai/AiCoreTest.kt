package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class AiCoreTest {
    @Test fun miniAssistantFitsTwoThirdsOfApplicationTile() {
        for (width in listOf(88f, 101.5f, 125f)) assertEquals(width * 2f / 3f, evaMiniSideDp(width), .001f)
    }
    private val parser = LocalIntentResolver()
    private fun plan(text: String) = (parser.resolve(text) as Resolution.Plan).actions
    @Test fun assistantNamePrefixesKeepCommandsBounded() {
        for (name in listOf("EVA", "Ева", "EZCH", "ЭЗЧ")) {
            assertEquals(listOf(Action.Launch("зона")), plan("$name, открой Зона"))
            assertEquals(listOf(Action.Navigate(Direction.RIGHT, 3)), plan("$name вправо три раза"))
            assertTrue(parser.resolve("$name, вправо 100 раз") is Resolution.Rejected)
        }
        assertTrue(parser.resolve("Евангелина вправо") is Resolution.Rejected)
    }
    @Test fun russianRepeatsAndChains() {
        assertEquals(listOf(Action.Navigate(Direction.RIGHT, 3)), plan(" Вправо три раза "))
        assertEquals(listOf(Action.Navigate(Direction.DOWN, 2)), plan("Два раза вниз"))
        assertEquals(listOf(Action.Navigate(Direction.UP), Action.Navigate(Direction.RIGHT), Action.Navigate(Direction.OK)), plan("Вверх, вправо и ОК"))
    }
    @Test fun rejectUnboundedAndPartialPhrases() {
        for (text in listOf("вправо 0 раз", "влево 100 раз", "пять раз вправо три раза", "вправо и установи apk", "громкость 101", "громкость -1", "", "пауза; удали приложение"))
            assertTrue(text, parser.resolve(text) is Resolution.Rejected)
    }
    @Test fun launchLabelIsAtomic() {
        assertEquals(listOf(Action.Launch("кино и тв")), plan("Открой Кино и ТВ"))
        assertEquals(listOf(Action.Launch("youtube"), Action.Search("фильм")), plan("открой YouTube, найди фильм"))
    }
    @Test fun cancellationCannotBeExecutedAsSuccess() {
        assertEquals(listOf(Action.Cancel), plan("СТОП"))
        assertTrue(plan("вправо и стоп").contains(Action.Cancel))
        assertEquals(listOf(Action.Dismiss), plan("Ева, закройся"))
        assertTrue(parser.resolve("не закройся") is Resolution.Rejected)
    }
    @Test fun mediaAndVolumeAreTyped() {
        assertEquals(listOf(Action.Volume(percent = 20)), plan("громкость 20"))
        assertEquals(listOf(Action.Volume(mute = true)), plan("выключи звук"))
        assertEquals(listOf(Action.Media(false)), plan("пауза"))
        assertEquals(listOf(Action.Media(true)), plan("продолжи"))
        assertEquals(listOf(Action.Updates), plan("проверь обновления"))
    }
    @Test fun ambiguityDoesNotLaunchFirstMatch() {
        val apps = listOf(AppTarget("Zona", "a/A"), AppTarget("Zona", "b/B"), AppTarget("YouTube", "c/C"))
        assertTrue(matchApp("зона", apps) is AppMatch.Ambiguous)
        assertEquals(AppMatch.Unique(apps[2]), matchApp("Ютуб", apps))
        assertEquals(AppMatch.Missing, matchApp("Lampa", apps))
        assertEquals(AppMatch.Unique(apps[2]), matchApp("youtube", apps + apps[2]))
    }
    @Test fun spokenNamesMatchLocalizedAndCustomizedInstalledApps() {
        val num = AppTarget("Медиатека", "ru.yourok.num/.activity.collections.CollectionsActivity")
        val zona = AppTarget("Зона", "mobi.zona/ru.zona.app.android.MainActivity")
        for (name in listOf("нум", "NUM", "приложение нум", "эн ю эм"))
            assertEquals(name, AppMatch.Unique(num), matchApp(name, listOf(num, zona)))
        assertEquals(AppMatch.Unique(zona), matchApp("приложение зона", listOf(num, zona)))
        assertEquals(AppMatch.Unique(zona), matchApp("Zona", listOf(zona)))
        assertEquals(AppMatch.Missing, matchApp("нум", listOf(zona)))
        assertEquals(AppMatch.Missing, matchApp("", listOf(num, zona)))
        assertTrue(matchApp("зона", listOf(zona, AppTarget("Zona", "other/App"))) is AppMatch.Ambiguous)
    }
    @Test fun launchHandlesApplicationWordWithSpeechPunctuation() {
        val num = AppTarget("NUM", "ru.yourok.num/Main")
        for (phrase in listOf("Ева, открой приложение нум", "открой приложение, нум", "открой приложение «Нум»", "открой, пожалуйста приложение нум")) {
            // A pause after the verb may be rendered as a comma by the speech provider.
            val launch = plan(phrase).single() as Action.Launch
            assertEquals(phrase, AppMatch.Unique(num), matchApp(launch.name, listOf(num)))
        }
    }
    @Test fun spokenHdTvLetterVariantsResolveSameInstalledApplication() {
        val lite = AppTarget("Лайт HD TV", "limehd.ru.lite/Main")
        for (query in listOf("лайт ажд тв", "лайт аж ди тв", "лайт ажди тв", "лайт ашдтв", "лайт эйчди тв", "приложение лайт hd tv"))
            assertEquals(query, AppMatch.Unique(lite), matchApp(query, listOf(lite)))
    }
    @Test fun misspelledApplicationNeedsConfirmation() {
        val kino = AppTarget("KinoTrend", "ru.tiardev.kinotrend/Main")
        val apps = listOf(kino, AppTarget("NUM", "ru.yourok.num/Main"))
        assertEquals(AppMatch.Suggested(listOf(kino)), matchApp("кинатрент", apps))
        assertEquals(AppMatch.Missing, matchApp("неизвестная программа", apps))
        assertEquals(AppMatch.Missing, matchApp("x".repeat(200), apps))
        assertEquals(AppMatch.Unique(kino), matchApp("кино тренд", apps))
    }
    @Test fun russianNamesCoverApplicationsObservedOnTv() {
        val fixtures = listOf(
            "Лайт HD TV" to "лайт hd tv", "Лайт HD TV" to "лайт эйч ди тв", "Лайт HD TV" to "лайт ашд тв",
            "IPTV Pro" to "ай пи ти ви про", "Lift" to "лифт", "LAMPA" to "лампа",
            "PRISMA" to "призма", "KinoTrend" to "кино тренд", "SerialTrend" to "сериал тренд",
            "VoKino" to "во кино", "RUTUBE" to "рутуб", "RuStore" to "рустор",
            "VK Видео" to "вк видео", "TorrServe MatriX" to "торрсерв", "Task Killer TV" to "таск киллер",
            "Медиаплеер Виму" to "виму", "Файловый менеджер +" to "файловый менеджер",
            "AniLiberty" to "ани либерти", "ByeByeDPI" to "бай бай дпи", "Google Play" to "гугл плей",
            "YouTube Music" to "ютуб музыка"
        )
        val apps = fixtures.map { it.first }.distinct().mapIndexed { index, label -> AppTarget(label, "fixture$index/Main") }
        fixtures.forEach { (label, spoken) ->
            val target = apps.single { it.label == label }
            for (query in listOf(spoken, "приложение $spoken")) assertEquals(query, AppMatch.Unique(target), matchApp(query, apps))
        }
        assertEquals(AppMatch.Missing, matchApp("неизвестное приложение", apps))
        assertEquals(AppMatch.Missing, matchApp("нум или зона", apps))
    }
    @Test fun actionRegistryFailsClosed() {
        assertFalse(LocalActionRegistry.allowed(Action.Navigate(Direction.RIGHT, 11)))
        assertFalse(LocalActionRegistry.allowed(Action.Launch("")))
        assertFalse(LocalActionRegistry.allowed(Action.Volume(percent = 101)))
        assertFalse(LocalActionRegistry.allowed(Action.Search("")))
        assertFalse(LocalActionRegistry.allowed(Action.Choose(0)))
        assertFalse(LocalActionRegistry.allowed(Action.Quality("8k")))
    }
    @Test fun appControlChainsRetainQueryAndOrdinal() {
        assertEquals(listOf(Action.Launch("num"), Action.Search("мстители"), Action.Quality("4k")), plan("Ева, открой NUM, найди Мстители, выбери 4K"))
        assertEquals(listOf(Action.Launch("lite hd tv"), Action.PlayRequest("тнт")), plan("Ева, открой Lite HD TV, включи ТНТ"))
        assertEquals(listOf(Action.Search("бриллиантовая рука")), plan("найди фильм Бриллиантовая рука"))
        assertEquals(listOf(Action.Choose(5)), plan("Пятый"))
        assertEquals(listOf(Action.Watch), plan("Смотреть"))
        assertEquals(listOf(Action.ClearCache), plan("Очисти кэш"))
    }
    @Test fun channelRoutingDoesNotBreakAppsMoviesOrSound() {
        val apps = listOf(AppTarget("NUM", "ru.yourok.num/A"), AppTarget("YouTube", "youtube/A"))
        assertEquals(Action.Launch("приложение нум"), routePlayRequest(Action.PlayRequest("приложение нум"), apps))
        assertEquals(Action.PlayChannel("тнт"), routePlayRequest(Action.PlayRequest("тнт"), apps))
        assertEquals(listOf(Action.PlayChannel("тнт4")), plan("Ева, включи канал ТНТ4"))
        assertEquals(listOf(Action.Volume(mute = false)), plan("включи звук"))
        assertEquals(listOf(Action.Search("бриллиантовая рука"), Action.Watch), plan("включи фильм Бриллиантовая рука"))
        assertFalse(LocalActionRegistry.allowed(Action.PlayChannel("")))
        assertFalse(LocalActionRegistry.allowed(Action.PlayRequest("x".repeat(101))))
    }
}
