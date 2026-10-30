package com.example.homeezch.ai

import org.junit.Assert.*
import org.junit.Test

class WeatherIntentTest {
    private val parser = LocalIntentResolver()
    @Test fun naturalRussianCurrentWeatherQuestions() {
        for (text in listOf("погода", "Ева, какая погода?", "какая сейчас погода", "какая погода сегодня", "расскажи о погоде", "скажи погоду", "сколько градусов на улице", "открой погоду"))
            assertEquals(text, Resolution.Plan(listOf(Action.Weather)), parser.resolve(text))
    }
    @Test fun politeVoicePhrasesAndActivationPrefix() {
        assertEquals(Resolution.Plan(listOf(Action.Weather)), parser.resolve("Эй, Ева, подскажи, пожалуйста, какая погода"))
        assertEquals(Resolution.Plan(listOf(Action.Weather)), parser.resolve("скажи какая погода пожалуйста"))
    }
    @Test fun weatherDoesNotConsumeUnrelatedOrFutureRequests() {
        for (text in listOf("погода завтра", "погода и удали приложение", "какая погода в Токио"))
            assertTrue(text, parser.resolve(text) is Resolution.Rejected)
        assertEquals(Resolution.Plan(listOf(Action.Search("погода"))), parser.resolve("найди погода"))
    }
    @Test fun weatherCanJoinBoundedLocalPlans() {
        assertEquals(Resolution.Plan(listOf(Action.Weather, Action.Navigate(Direction.HOME))), parser.resolve("погода и домой"))
    }
}
