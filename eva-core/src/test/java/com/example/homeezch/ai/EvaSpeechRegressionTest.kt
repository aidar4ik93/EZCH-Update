package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class EvaSpeechRegressionTest {
    @Test fun recordedAsrVariantsMatchOnlyInstalledTargets() {
        val num = AppTarget("NUM", "ru.yourok.num/.Main")
        val zona = AppTarget("Zona", "mobi.zona/.Main")
        assertEquals(AppMatch.Unique(num), matchApp("приложении ну", listOf(num, zona)))
        assertEquals(AppMatch.Unique(zona), matchApp("зоне", listOf(num, zona)))
        assertEquals(AppMatch.Missing, matchApp("ну", listOf(zona)))
    }
    @Test fun wakeDoesNotFireInsideOtherWords() {
        assertTrue(EvaSpeechText.wake("Ева, открой NUM"))
        assertTrue(EvaSpeechText.wake("эва"))
        assertFalse(EvaSpeechText.wake("левая карточка"))
        assertFalse(EvaSpeechText.wake("дерево слева"))
        assertFalse(EvaSpeechText.wake("деваться некуда"))
    }
    @Test fun fillerDoesNotBreakApplicationNames() {
        val resolution = LocalIntentResolver().resolve("Ева, открой приложение нум, пожалуйста") as Resolution.Plan
        val launch = resolution.actions.single() as Action.Launch
        assertTrue(matchApp(launch.name, listOf(AppTarget("NUM", "ru.yourok.num/.Main"))) is AppMatch.Unique)
        assertEquals("открой приложение нум", EvaSpeechText.clean("Ева, открой приложение нум пожалуйста"))
    }
    @Test fun scopedSearchPreservesTargetAndTitle() {
        val plan = LocalIntentResolver().resolve("найди фильм Бриллиантовая рука в Lift") as Resolution.Plan
        assertEquals(listOf(Action.Launch("lift"), Action.Search("бриллиантовая рука")), plan.actions)
    }
    @Test fun channelCommandRemainsOrderedAfterLaunch() {
        val plan = LocalIntentResolver().resolve("Ева, открой приложение лайт ашд тв и включи канал ТНТ") as Resolution.Plan
        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.first() is Action.Launch)
        assertEquals(Action.PlayChannel("тнт"), plan.actions.last())
    }
    @Test fun scopedSearchKeepsTitlesContainingPrepositionV() {
        val plan = LocalIntentResolver().resolve("Ева, найди Человек в железе в Zona") as Resolution.Plan
        assertEquals(listOf(Action.Launch("zona"), Action.Search("человек в железе")), plan.actions)
        val explicit = LocalIntentResolver().resolve("найди фильм мстители в приложении нум") as Resolution.Plan
        assertEquals(listOf(Action.Launch("нум"), Action.Search("мстители")), explicit.actions)
        val front = LocalIntentResolver().resolve("в нум найди мстители") as Resolution.Plan
        assertEquals(listOf(Action.Launch("нум"), Action.Search("мстители")), front.actions)
    }
}
