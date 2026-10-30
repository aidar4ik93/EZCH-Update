package com.example.homeezch.eva

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI shows: state, live input level and the latest dialogue line. */
data class EvaSignal(val state: EvaState, val message: String = "", val user: String = "")

/**
 * Process-wide EVA state. HOME, the voice panel and the system assistant session
 * live in one process, so one state machine keeps all orbs in sync.
 * Must be used from the main thread.
 */
object EvaBus {
    private val handler = Handler(Looper.getMainLooper())
    private val machine = EvaStateMachine { SystemClock.elapsedRealtime() }
    private val _signal = MutableStateFlow(EvaSignal(machine.current))
    val signal: StateFlow<EvaSignal> = _signal.asStateFlow()

    /** Microphone/TTS level 0..1. Read every frame by renderers; never stored. */
    @Volatile var level: Float = 0f
        private set

    private val ticker = Runnable { publish(machine.tick()); schedule() }

    init { schedule() }

    fun send(event: EvaEvent, message: String? = null, user: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { send(event, message, user) }; return }
        if (event is EvaEvent.Cancel || event is EvaEvent.Result || event is EvaEvent.SpeakDone) level = 0f
        publish(machine.dispatch(event), message, user)
        schedule()
    }

    /** SpeechRecognizer RMS in dB (≈ -2..10) or a TTS progress pulse already in 0..1. */
    fun rms(db: Float) { level = ((db + 2f) / 12f).coerceIn(0f, 1f) }
    fun pulse(value: Float) { level = value.coerceIn(0f, 1f) }

    fun say(message: String) { _signal.value = _signal.value.copy(message = message) }

    private fun publish(state: EvaState, message: String? = null, user: String? = null) {
        val old = _signal.value
        val next = old.copy(state = state, message = message ?: old.message, user = user ?: old.user)
        if (next != old) _signal.value = next
    }

    private fun schedule() {
        handler.removeCallbacks(ticker)
        val wait = machine.nextDeadline()
        if (wait >= 0) handler.postDelayed(ticker, wait + 16)
    }

    val online: Boolean get() = machine.online
}
