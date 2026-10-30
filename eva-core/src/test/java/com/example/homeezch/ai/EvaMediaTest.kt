package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class EvaMediaTest {
    private fun plan(text: String) = (LocalIntentResolver().resolve(text) as? Resolution.Plan)?.actions

    @Test fun appThenSearchWithoutCommas() {
        val expected = listOf(Action.Launch("лифт"), Action.Search("мстители"))
        for (q in listOf("открой кинотеатр лифт и найди мне фильм мстители", "Ева открой кинотеатр Лифт найди мне фильм Мстители",
                "открой лифт найди мстители", "открой лифт, найди фильм мстители", "открой онлайн кинотеатр лифт найди мстители"))
            assertEquals(q, expected, plan(q))
        assertEquals(listOf(Action.Launch("лифт"), Action.Search("мстители финал"), Action.Choose(1), Action.Watch), plan("открой лифт и включи фильм мстители финал"))
    }
    @Test fun youtubePlaysByItself() {
        val rammstein = listOf(Action.PlayOn("youtube", "рамштайн", true))
        for (q in listOf("открой ютуб поставь рамштайн", "открой youtube и включи рамштайн", "открой ютуб поставь мне музыку рамштайн",
                "включи рамштайн на ютубе", "поставь музыку рамштайн", "включи песню рамштайн", "хочу послушать группу рамштайн"))
            assertEquals(q, rammstein, plan(q))
        assertEquals(listOf(Action.PlayOn("youtube", "рецепт борща", false)), plan("найди рецепт борща на ютубе"))
        assertEquals(listOf(Action.PlayOn("youtube", "музыка", true)), plan("включи музыку"))
        // «Ева, включи рамштайн»: not an app, not a channel → YouTube at execution time.
        assertEquals(listOf(Action.PlayRequest("рамштайн")), plan("Ева включи рамштайн"))
    }
    @Test fun channelsStayChannels() {
        assertEquals(listOf(Action.Launch("lite hd tv"), Action.PlayRequest("тнт")), plan("Ева, открой Lite HD TV, включи ТНТ"))
        assertEquals(Action.PlayChannel("первый канал"), routePlayRequest(Action.PlayRequest("первый канал"), emptyList()))
        assertEquals(Action.PlayOn("youtube", "рамштайн", true), routePlayRequest(Action.PlayRequest("рамштайн"), emptyList()))
    }
    @Test fun plainAppStillLaunches() {
        assertEquals(listOf(Action.Launch("лифт")), plan("открой кинотеатр лифт"))
        assertEquals(listOf(Action.Launch("youtube")), plan("открой youtube"))
    }
    @Test fun videoIdFromResultsPage() {
        assertEquals("dQw4w9WgXcQ", EvaMedia.firstVideoId("""…{"videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail"…"videoId":"zzzzzzzzzzz""""))
    }
}
