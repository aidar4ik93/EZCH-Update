package com.example.homeezch

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homeezch.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice

class AiIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun registeredComponentsAreSystemProtected() {
        val pm = context.packageManager
        val voice = pm.getServiceInfo(ComponentName(context, EzchVoiceInteractionService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", voice.permission)
        assertTrue(voice.exported)
        assertNotEquals(0, voice.metaData.getInt("android.voice_interaction"))
        assertEquals("android.permission.BIND_VOICE_INTERACTION", pm.getServiceInfo(ComponentName(context, EzchVoiceSessionService::class.java), 0).permission)
        assertEquals("android.permission.BIND_SPEECH_RECOGNITION", pm.getServiceInfo(ComponentName(context, EzchRecognitionService::class.java), 0).permission)
        // VoiceActivity is the ASSIST / VOICE_COMMAND entry (EVA as the assist app on firmwares that list activities),
        // so the system must be able to start it: exported by design since 0.41.x.
        assertTrue(pm.getActivityInfo(ComponentName(context, VoiceActivity::class.java), 0).exported)
    }
    @Test fun offByDefaultAndFallbackPanelDoesNotRequestMicrophone() {
        AiPreferences.store(context).edit().clear().commit()
        assertFalse(AiPreferences.enabled(context))
        // Owner's choice 24.10.2026: Google recognition when online, offline Vosk as the fallback.
        assertTrue(AiPreferences.networkSpeech(context))
        val permissionBefore = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        // ActivityScenario closes the panel afterwards: a panel left on top broke the next HOME tests
        // (Desktop028, LauncherCompletion, PanelWorkflow: «No compose hierarchies found») in the full run.
        ActivityScenario.launch<VoiceActivity>(Intent(context, VoiceActivity::class.java)).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(permissionBefore, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        }
    }
    @Test fun missingAppsAndExternalNavigationFailClosed() = runBlocking {
        val executor = AndroidActionExecutor(context)
        val apps = executor.apps()
        assertTrue(apps.none { it.component.substringBefore('/') == context.packageName })
        assertFalse(executor.execute(Action.Launch("not-installed-ezch-test-ff31")).accepted)
        assertFalse(executor.execute(Action.Navigate(Direction.RIGHT)).accepted)
    }
    @Test fun updaterCanLaunchFromApplicationContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(UpdaterActivity::class.java.name, null, false)
        try {
            assertTrue(runBlocking { AndroidActionExecutor(context).execute(Action.Updates).accepted })
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull(activity)
            instrumentation.runOnMainSync { activity.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun diagnosticDoesNotClaimRemoteButtonOrAudioVerified() {
        val report = AndroidCapabilityProbe(context).report()
        assertTrue(report.contains("штатная кнопка: не проверена"))
        assertTrue(report.contains("это не проверка микрофона пульта"))
    }
    @Test fun assistantSettingsOpenOrStayInPanelWhenFirmwareHasNoScreen() {
        org.junit.Assume.assumeTrue(AndroidCapabilityProbe(context).roleAvailable())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<AssistantSettingsActivity>(Intent(context, AssistantSettingsActivity::class.java)).use { scenario ->
            val previousRole = AndroidCapabilityProbe(context).roleHeld()
            var opened = false
            scenario.onActivity { opened = AndroidCapabilityProbe(it).openAssistantSettings(it) }
            if (!opened) {
                assertTrue(device.wait(Until.hasObject(By.text("Голосовой помощник EVA")), 5000))
                assertEquals(previousRole, AndroidCapabilityProbe(context).roleHeld())
                return@use
            }
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
            val defaultApp = device.wait(Until.findObject(By.textContains("Default digital assistant app")), 5000)
            assertNotNull(defaultApp); defaultApp.click()
            val systemUi = device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile(".*permissioncontroller"))), 5000)
            val candidate = systemUi && device.wait(Until.hasObject(By.text("EZCH Launcher")), 5000)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "ai-role-dialog.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            if (!candidate) {
                val output = java.io.ByteArrayOutputStream(); device.dumpWindowHierarchy(output); fail(output.toString("UTF-8"))
            }
            assertTrue(systemUi)
            device.pressBack()
            device.pressBack()
        }
    }
}
