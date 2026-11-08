package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude r57: what the deep clean may stop. Names are the real heavy apps found on Homatics (r106 meminfo). */
class DeepCleanTest {
    private val keep = setOf("com.example.homeezch.usb", "io.github.romanvht.byedpi")

    @Test fun protectsSystemServersAndTunnels() {
        listOf("com.google.android.tts", "com.google.android.apps.tv.launcherx", "com.google.android.katniss", "ru.yourok.torrserve",
            "com.android.tv.settings", "com.google.android.gms", "com.google.android.inputmethod.latin", "io.github.romanvht.byedpi",
            "com.google.android.apps.mediashell", "com.example.homeezch.usb", "android")
            .forEach { assertTrue(it, DeepClean.isProtected(it, keep)) }
        listOf("limehd.ru.lite", "mobi.zona", "com.seena.app", "com.google.android.youtube.tv", "org.mozilla.tv.firefox")
            .forEach { assertFalse(it, DeepClean.isProtected(it, keep)) }
    }

    @Test fun mostRecentFirstWithoutForegroundOrStopped() {
        val recent = listOf("mobi.zona", "limehd.ru.lite", "com.google.android.apps.tv.launcherx", "com.seena.app", "com.google.android.youtube.tv")
        val picked = DeepClean.pick(recent, foreground = "com.google.android.youtube.tv", exact = keep, stopped = { it == "mobi.zona" })
        assertEquals(listOf("com.seena.app", "limehd.ru.lite"), picked)
    }

    @Test fun respectsLimit() {
        val recent = (1..20).map { "app.n$it" }
        assertEquals(DeepClean.MAX_APPS, DeepClean.pick(recent, null, emptySet(), { false }).size)
        assertEquals("app.n20", DeepClean.pick(recent, null, emptySet(), { false }).first())
    }

    @Test fun windowTrackerKeepsOrderWithoutDuplicates() {
        DeepClean.onWindow("a.one"); DeepClean.onWindow("b.two"); DeepClean.onWindow("a.one")
        val r = DeepClean.recent()
        assertEquals(listOf("b.two", "a.one"), r.takeLast(2))
    }
}

class DeepCleanRunningTest {
    @Test fun runningFromPsSkipsPlayingProtectedAndForeground() {
        val ps = "init\nsystem_server\nlimehd.ru.lite\nlimehd.ru.lite:AppMetrica\nmobi.zona\ncom.google.android.tts\nru.yourok.torrserve\ncom.seena.app\ncom.google.android.youtube.tv\n"
        val pkgs = "package:limehd.ru.lite\npackage:mobi.zona\npackage:com.google.android.tts\npackage:ru.yourok.torrserve\npackage:com.seena.app\npackage:com.google.android.youtube.tv\n"
        val media = "  Sessions Stack:\n    YouTube com.google.android.youtube.tv/x (userId=0)\n      package=com.seena.app\n      state=PlaybackState {state=3, position=10}\n"
        assertEquals(listOf("limehd.ru.lite", "mobi.zona"),
            DeepClean.running(ps, pkgs, media, foreground = "com.google.android.youtube.tv", exact = emptySet()))
        // A vendor service without a launcher icon (DolbyAudioService on Homatics) is never stopped.
        assertEquals(listOf("mobi.zona"),
            DeepClean.running(ps, pkgs, media, "com.google.android.youtube.tv", emptySet(), launchable = { it != "limehd.ru.lite" }))
    }
}
