package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class AppUiRulesTest {
    @Test fun numberedChannelMustNotConfuseTntWithTnt4() {
        assertTrue(exactResultTitle("19. ТНТ", "тнт"))
        assertFalse(exactResultTitle("24. ТНТ4", "ТНТ"))
        assertTrue(relevantResultTitle("23. Че", "че"))
        assertFalse(relevantResultTitle("19. ТНТ", ""))
    }
    @Test fun multiwordMovieSearchRequiresEveryWord() {
        assertTrue(relevantResultTitle("Бриллиантовая рука (1968)", "бриллиантовая рука"))
        assertFalse(relevantResultTitle("Рука, качающая колыбель", "бриллиантовая рука"))
        assertFalse(relevantResultTitle("Небриллиантовая рука", "бриллиантовая рука"))
        assertTrue(relevantResultTitle("Бриллиантовая рука", "бриллиантовую руку"))
        assertTrue(relevantResultTitle("  Шёпот за окном  ", "шепот за окном"))
    }
    @Test fun searchLabelMayEndWithEllipsis() {
        assertTrue(uiLabel("Поиск...") in searchLabels)
        assertTrue(uiLabel("Search…") in searchLabels)
    }
}
