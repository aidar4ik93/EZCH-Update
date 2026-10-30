package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 29.10.2026: «подобие Джарвиса» — film wishes said any way end in one complete plan. */
class R30JarvisTest {
    private val apps = listOf(AppTarget("YouTube", "com.google.android.youtube.tv/.M"), AppTarget("lift", "io.lift.app/.M"),
        AppTarget("Zona", "mobi.zona/.M"), AppTarget("NUM", "ru.yourok.num/.M"), AppTarget("KinoTrend", "ru.tiardev.kinotrend/.M"))
    private fun exec(core: EvaCore, p: String) = (core.handle(p) { apps } as EvaReply.Execute).actions
    private fun playIn(app: String, q: String) = listOf(Action.Launch(app), Action.Search(q), Action.Choose(1), Action.Watch)

    @Test fun watchWishStartsTheFilm() {
        val core = EvaCore()
        core.memory.remember("кино", "lift")
        for (p in listOf("хочу посмотреть фильм аватар", "давай посмотрим аватар", "покажи аватар", "включи фильм аватар"))
            assertEquals(p, playIn("lift", "аватар"), exec(core, p))
        assertEquals(listOf(Action.Launch("lift"), Action.Search("аватар")), exec(core, "найди фильм аватар"))
        assertEquals(listOf(Action.Launch("lift"), Action.Search("космос")), exec(core, "хочу посмотреть что-нибудь про космос"))
    }
    @Test fun followUpsContinueTheSearch() {
        val core = EvaCore()
        core.memory.remember("кино", "lift")
        exec(core, "найди фильм аватар")
        assertEquals(listOf(Action.Launch("zona"), Action.Search("аватар")), exec(core, "а теперь в зоне"))
        assertEquals(listOf(Action.Choose(2), Action.Watch), exec(core, "не тот, второй"))
    }
    @Test fun ordinaryCommandsStayCommands() {
        val core = EvaCore()
        assertEquals(listOf(Action.Weather), exec(core, "покажи погоду"))
        assertEquals(listOf(Action.PlayOn("youtube", "рамштайн", true)), exec(core, "включи музыку рамштайн"))
        assertEquals(listOf(Action.PlayOn("youtube", "рамштайн", true)), exec(core, "открой ютуб и включи рамштайн"))
        assertEquals(listOf(Action.Volume(step = -1), Action.Launch("ютуб")), exec(core, "сделай потише и открой ютуб"))
        assertEquals(listOf(Action.Media(true)), exec(core, "продолжи смотреть"))
        assertTrue(core.handle("расскажи анекдот") { apps } !is EvaReply.Execute)
    }
}
