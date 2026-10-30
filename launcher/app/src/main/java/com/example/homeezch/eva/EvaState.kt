package com.example.homeezch.eva

/**
 * Visual states of EVA. Pure Kotlin so the state machine is covered by JVM unit tests.
 *
 * Base states (IDLE, OFFLINE, SLEEP) describe the launcher; interaction states
 * (LISTENING, THINKING, SPEAKING) describe the assistant; transient states
 * (APPEAR, SUCCESS, ERROR) play once and fall back to the base state.
 */
enum class EvaState { APPEAR, IDLE, LISTENING, THINKING, SPEAKING, SUCCESS, ERROR, OFFLINE, SLEEP }

sealed interface EvaEvent {
    /** Remote input or HOME resumed. */
    data object Wake : EvaEvent
    /** Launcher idle for a long time or left the foreground. */
    data object Sleep : EvaEvent
    data class Network(val online: Boolean) : EvaEvent
    data object ListenStart : EvaEvent
    /** Speech ended or a text command was submitted. */
    data object Processing : EvaEvent
    data object SpeakStart : EvaEvent
    data object SpeakDone : EvaEvent
    data class Result(val success: Boolean) : EvaEvent
    data object Cancel : EvaEvent
}

/**
 * Single owner of EVA's state so animations never conflict: every producer
 * (recognizer, executor, TTS, connectivity, idle timer) sends events here.
 */
class EvaStateMachine(private val clock: () -> Long) {
    var current: EvaState = EvaState.APPEAR
        private set
    var online: Boolean = true
        private set
    private var asleep = false
    private var until: Long = clock() + APPEAR_MS
    private var since: Long = clock()

    val base: EvaState get() = when {
        asleep -> EvaState.SLEEP
        !online -> EvaState.OFFLINE
        else -> EvaState.IDLE
    }

    private val interacting get() = current == EvaState.LISTENING || current == EvaState.THINKING || current == EvaState.SPEAKING

    private fun enter(state: EvaState, duration: Long = 0): EvaState {
        current = state; since = clock(); until = if (duration > 0) since + duration else 0
        return current
    }

    fun dispatch(event: EvaEvent): EvaState {
        tick()
        when (event) {
            EvaEvent.Wake -> { asleep = false; if (current == EvaState.SLEEP) enter(base) }
            EvaEvent.Sleep -> if (!interacting) { asleep = true; if (isRestingState()) enter(EvaState.SLEEP) }
            is EvaEvent.Network -> { online = event.online; if (isRestingState()) enter(base) }
            EvaEvent.ListenStart -> { asleep = false; enter(EvaState.LISTENING) }
            EvaEvent.Processing -> { asleep = false; enter(EvaState.THINKING) }
            EvaEvent.SpeakStart -> { asleep = false; enter(EvaState.SPEAKING) }
            EvaEvent.SpeakDone -> if (current == EvaState.SPEAKING) enter(base)
            is EvaEvent.Result -> enter(if (event.success) EvaState.SUCCESS else EvaState.ERROR,
                if (event.success) SUCCESS_MS else ERROR_MS)
            EvaEvent.Cancel -> if (current != EvaState.APPEAR) enter(base)
        }
        return current
    }

    /** Expires transient states and guards against lost recognizer callbacks. */
    fun tick(): EvaState {
        val now = clock()
        if (until > 0 && now >= until) enter(base)
        else if (interacting && now - since >= STUCK_MS) enter(base)
        return current
    }

    /** Milliseconds until [tick] can change the state, or -1 when nothing is scheduled. */
    fun nextDeadline(): Long {
        val now = clock()
        return when {
            until > 0 -> (until - now).coerceAtLeast(0)
            interacting -> (since + STUCK_MS - now).coerceAtLeast(0)
            else -> -1
        }
    }

    private fun isRestingState() = current == EvaState.IDLE || current == EvaState.OFFLINE || current == EvaState.SLEEP

    companion object {
        const val APPEAR_MS = 1600L
        const val SUCCESS_MS = 900L
        const val ERROR_MS = 1400L
        const val STUCK_MS = 20_000L
    }
}
