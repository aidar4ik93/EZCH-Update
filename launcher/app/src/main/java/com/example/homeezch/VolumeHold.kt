package com.example.homeezch

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Claude r23: a held volume key changed the volume by one step only (Haier). While an accessibility service
 * filters key events, some firmware stops auto-repeating the re-injected key, so Android sees one press.
 * The services still pass every volume key on; this only adds the missing repeats, and stands down as soon
 * as it sees the system repeating by itself (a repeat event, or the volume moving without us).
 */
internal class VolumeRepeat {
    enum class Next { STOP, WAIT, STEP }
    var direction = 0; private set
    private var downTime = -1L
    private var probe: Int? = null
    private var expected: Int? = null
    private var steps = 0

    /** Returns true when this DOWN starts a new hold (both EZCH services may report the same press). */
    fun down(keyDownTime: Long, dir: Int): Boolean {
        if (direction == dir && downTime == keyDownTime) return false
        direction = dir; downTime = keyDownTime; probe = null; expected = null; steps = 0
        return true
    }
    fun stop() { direction = 0; downTime = -1L }

    /** Called with the current volume on each timer tick. */
    fun tick(volume: Int): Next {
        if (direction == 0) return Next.STOP
        val seen = probe
        if (seen == null) { probe = volume; return Next.WAIT }
        val own = expected
        if (own == null && volume != seen || own != null && volume != own || steps >= MAX_STEPS) { stop(); return Next.STOP }
        steps++
        return Next.STEP
    }
    /** The volume after our own step: anything else on the next tick means the system repeats too. */
    fun stepped(volume: Int) { expected = volume }

    companion object { const val MAX_STEPS = 100 }
}

internal object VolumeHold {
    private const val FIRST_DELAY_MS = 450L
    private const val PROBE_MS = 120L
    private const val STEP_MS = 110L
    private val handler = Handler(Looper.getMainLooper())
    private val state = VolumeRepeat()
    private var audio: AudioManager? = null

    private val tick = object : Runnable {
        override fun run() {
            val am = audio ?: return state.stop()
            when (state.tick(am.getStreamVolume(AudioManager.STREAM_MUSIC))) {
                VolumeRepeat.Next.STOP -> Unit
                VolumeRepeat.Next.WAIT -> handler.postDelayed(this, PROBE_MS)
                VolumeRepeat.Next.STEP -> {
                    runCatching {
                        am.adjustSuggestedStreamVolume(if (state.direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                    }
                    state.stepped(am.getStreamVolume(AudioManager.STREAM_MUSIC))
                    handler.postDelayed(this, STEP_MS)
                }
            }
        }
    }

    /** Observes a key in an accessibility service's onKeyEvent; never consumes it. */
    fun onKeyEvent(context: Context, event: KeyEvent) {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!state.down(event.downTime, dir)) return
            audio = context.applicationContext.getSystemService(AudioManager::class.java)
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, FIRST_DELAY_MS)
        } else {
            // A real repeat means the firmware repeats by itself; UP or cancel ends the hold.
            handler.removeCallbacks(tick)
            state.stop()
        }
    }
}
