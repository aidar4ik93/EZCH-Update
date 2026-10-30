package com.example.homeezch

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class R22HardwareKeysTest {
    @get:Rule val ui=createComposeRule()
    @Test fun editAndSavingDoNotSwallowHeldVolumeOrGearAndSettingsExceptionsStayContained() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        check(ctx.packageName.endsWith(".debug"))
        var passed=0
        ui.setContent {
            val request=remember { FocusRequester() }
            val press=remember { CenterPress() }
            Box(Modifier.fillMaxSize().onKeyEvent { passed++; false }
                .remoteInput(true,true,press,{},{},{},{},{})
                .focusRequester(request).focusable())
            LaunchedEffect(Unit) { request.requestFocus() }
        }
        ui.waitForIdle()
        ui.onRoot().performKeyInput {
            repeat(20) { pressKey(androidx.compose.ui.input.key.Key.VolumeUp); pressKey(androidx.compose.ui.input.key.Key.VolumeDown) }
        }
        ui.waitForIdle()
        assertTrue("Volume events must leave the editing handler",passed>=40)
        val audio=ctx.getSystemService(AudioManager::class.java)
        val original=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            val down=SystemClock.uptimeMillis()
            for (i in 0..4) {
                val e=KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP,i)
                ins.uiAutomation.injectInputEvent(e,true);SystemClock.sleep(90)
            }
            ins.uiAutomation.injectInputEvent(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_UP,0),true)
            assertTrue("Held + should change volume repeatedly",audio.getStreamVolume(AudioManager.STREAM_MUSIC)>=6)
            val second=SystemClock.uptimeMillis()
            for (i in 0..4) {
                ins.uiAutomation.injectInputEvent(KeyEvent(second,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN,i),true)
                SystemClock.sleep(90)
            }
            ins.uiAutomation.injectInputEvent(KeyEvent(second,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN,0),true)
            assertTrue("Held - should keep lowering",audio.getStreamVolume(AudioManager.STREAM_MUSIC)<=4)
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC,original,0) }
        val failing=object:ContextWrapper(ctx) {
            override fun startActivity(intent:Intent) { throw IllegalArgumentException("OEM route test") }
        }
        ins.runOnMainSync { RemoteButtons.execute(failing,RemoteAction.SETTINGS) }
        val button=RemoteButtonPress()
        ins.runOnMainSync {
            for (code in SYSTEM_VOLUME_KEYS) repeat(100) { i ->
                assertFalse(button.handle(ctx,KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,i),true))
            }
            assertEquals(RemoteAction.SETTINGS,RemoteButtons.action(ctx,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SETTINGS),true))
        }
    }
}
