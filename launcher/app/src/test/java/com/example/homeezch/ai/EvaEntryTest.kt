package com.example.homeezch.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** GPT R27: calling Eva inside an app must not bring the desktop forward. */
class EvaEntryTest {
    @Test fun serviceWins() = assertEquals(EvaEntry.Route.SERVICE_PANEL, EvaEntry.route(serviceConnected = true, homeVisible = false))
    @Test fun desktopUsesItsSphere() = assertEquals(EvaEntry.Route.HOME_SPHERE, EvaEntry.route(serviceConnected = false, homeVisible = true))
    @Test fun insideAnAppStaysOverTheApp() = assertEquals(EvaEntry.Route.OVER_APP, EvaEntry.route(serviceConnected = false, homeVisible = false))
}
