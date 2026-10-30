package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner 29.10.2026: music on YouTube starts at once; film apps open, search, and start the chosen result. */
class R29MediaTest {
    private val apps = listOf(AppTarget("YouTube", "com.google.android.youtube.tv/.M"), AppTarget("Lift", "io.lift.app/.M"),
        AppTarget("Zona", "mobi.zona/.M"), AppTarget("KinoTrend", "ru.tiardev.kinotrend/.M"), AppTarget("SerialTrend", "ru.twicker.serialtrend/.M"))
    private fun run(p: String) = (EvaCore().handle(p) { apps } as EvaReply.Execute).actions
    private val rammstein = listOf(Action.PlayOn("youtube", "рамштайн", autoplay = true))

    @Test fun youtubeMusicPlays() {
        for (p in listOf("включи YouTube поставь музыку Рамштайн", "включи ютуб поставь музыку рамштайн", "открой музыку Рамштайн через YouTube",
                "включи рамштайн через ютуб", "рамштайн на ютубе", "открой ютуб и включи рамштайн", "включи музыку рамштайн"))
            assertEquals(p, rammstein, run(p))
    }
    @Test fun filmAppsSearch() {
        assertEquals(listOf(Action.Launch("лифт"), Action.Search("аватар")), run("открой лифт найди мне фильм аватар"))
        assertEquals(listOf(Action.Launch("lift"), Action.Search("аватар")), run("включи лифт найди аватар"))
        assertEquals(listOf(Action.Launch("zona"), Action.Search("аватар")), run("найди аватар в зоне"))
        assertEquals(listOf(Action.Launch("сериал тренд"), Action.Search("аватар")), run("открой сериал тренд найди аватар"))
    }
    @Test fun playFilmAndPickResult() {
        assertEquals(listOf(Action.Launch("lift"), Action.Search("аватар"), Action.Choose(1), Action.Watch), run("включи фильм аватар в лифте"))
        assertEquals(listOf(Action.Choose(1), Action.Watch), run("поставь первый фильм"))
        assertEquals(listOf(Action.Choose(1), Action.Watch), run("поставь первое видео"))
        assertEquals(listOf(Action.Choose(2), Action.Watch), run("запусти второй фильм"))
        assertEquals(listOf(Action.Choose(2)), run("выбери второй"))
    }
    @Test fun youtubeIdFromSearchEngineLinks() {
        assertEquals("NeQM1c-XCDc", EvaMedia.youtubeIdInLinks("<a href=\"https://www.youtube.com/watch?v=NeQM1c-XCDc\">Rammstein</a>"))
        assertEquals("StZcUAPRRac", EvaMedia.youtubeIdInLinks("murl&quot;:&quot;https%3a%2f%2fwww.youtube.com%2fwatch%3fv%3dStZcUAPRRac&quot;"))
        assertEquals(null, EvaMedia.youtubeIdInLinks("<a href=\"https://www.youtube.com/channel\">no video</a>"))
    }
}
