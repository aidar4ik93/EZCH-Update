package com.example.homeezch

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Emulator-only: actual Handler + AudioManager; not a claim about Haier/Homatics remote hardware. */
class VolumeHoldDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun event(time: Long, action: Int, repeat: Int = 0) = KeyEvent(time, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_VOLUME_UP, repeat)
    private fun deliver(event: KeyEvent, twice: Boolean = false) = inst.runOnMainSync {
        VolumeHold.onKeyEvent(context,event)
        if (twice) VolumeHold.onKeyEvent(context,event)
    }
    private fun fixture(action: (AudioManager) -> Unit) {
        check(context.packageName.endsWith(".debug"))
        val am=context.getSystemService(AudioManager::class.java)
        assumeFalse(am.isVolumeFixed)
        val before=am.getStreamVolume(AudioManager.STREAM_MUSIC)
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC,0,0); action(am) }
        finally { deliver(event(SystemClock.uptimeMillis(),KeyEvent.ACTION_UP)); am.setStreamVolume(AudioManager.STREAM_MUSIC,before,0) }
    }
    @Test fun fallbackRepeatsAndStopsOnKeyUp() = fixture { am ->
        val time=SystemClock.uptimeMillis()
        deliver(event(time,KeyEvent.ACTION_DOWN),true)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0)
        Thread.sleep(1150)
        deliver(event(time,KeyEvent.ACTION_UP),true)
        val released=am.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertTrue("Fallback must produce several real steps",released>=4)
        Thread.sleep(450)
        assertEquals("No steps after release",released,am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    @Test fun nativeRepeatDisablesFallbackWithoutDoubling() = fixture { am ->
        val time=SystemClock.uptimeMillis()
        deliver(event(time,KeyEvent.ACTION_DOWN),true)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0)
        Thread.sleep(250)
        deliver(event(time,KeyEvent.ACTION_DOWN,1),true)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0)
        Thread.sleep(850)
        assertEquals("Only the two system steps are allowed",2,am.getStreamVolume(AudioManager.STREAM_MUSIC))
        deliver(event(time,KeyEvent.ACTION_UP),true)
    }
    @Test fun shortClickDoesNotStartTimerSteps() = fixture { am ->
        val time=SystemClock.uptimeMillis()
        deliver(event(time,KeyEvent.ACTION_DOWN),true)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0)
        Thread.sleep(100)
        deliver(event(time,KeyEvent.ACTION_UP),true)
        Thread.sleep(650)
        assertEquals(1,am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
