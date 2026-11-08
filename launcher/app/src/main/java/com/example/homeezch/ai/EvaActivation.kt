package com.example.homeezch.ai

/**
 * Claude R52 — the single gate every «open Eva and listen» request passes through.
 *
 * Cause of the double trigger (R52): one press of the remote's microphone/EVA button reached Eva by two independent
 * paths, each of which opened the panel and called listen():
 *  - both EZCH accessibility services (Ai EvA + HOME button) receive every key; a button assigned to EVA was executed
 *    by each of them, and the Ai EvA service additionally handled the voice key itself;
 *  - on Google TV the same press also opens Google Assistant, and [AssistantTakeover] opened Eva again ~0.5–1 s later;
 *  - the assistant session / VoiceActivity (ASSIST intent) can arrive for the same press as well.
 * The second listen() on a listening panel means «I have finished» — it cut the phrase or started a second session,
 * so the command ran twice or was lost.
 *
 * Rules (pure, unit-tested): one physical press (same key downTime) opens Eva once; an echo of that gesture from another
 * path within a short window is dropped; a new press from the same path is always accepted, so two identical commands
 * in two separate sessions both run. There is no general delay.
 */
class ActivationGate(private val echoMs: Long = 900L, private val takeoverEchoMs: Long = 2_000L) {
    enum class Source { KEY_SERVICE, REMOTE_BUTTON, ASSISTANT_TAKEOVER, VOICE_SESSION, VOICE_ACTIVITY, HOME_KEY, HOME_SPHERE, OTHER }
    data class Decision(val accepted: Boolean, val session: Int, val reason: String)

    private var lastAt = Long.MIN_VALUE / 2
    private var lastSource: Source? = null
    private var lastPress: Long? = null
    private val seenPresses = ArrayDeque<Long>()
    var session = 0; private set

    @Synchronized fun request(source: Source, now: Long, press: Long? = null): Decision {
        if (press != null && press in seenPresses) return Decision(false, session, "та же кнопка уже открыла Еву")
        val window = if (source == Source.ASSISTANT_TAKEOVER) takeoverEchoMs else echoMs
        // GPT R52 check: two different physical presses (both with a key downTime) are never one gesture, whatever path
        // each took. Only a request without its own press (assistant session, takeover, ASSIST intent) can be an echo.
        val distinctPresses = press != null && lastPress != null && press != lastPress
        val sameGesture = !distinctPresses && now - lastAt in 0..window &&
            (source != lastSource || (press != null && press == lastPress) || source == Source.ASSISTANT_TAKEOVER)
        if (sameGesture) return Decision(false, session, "эхо того же нажатия от $lastSource")
        lastAt = now; lastSource = source; lastPress = press
        if (press != null) { seenPresses.addLast(press); while (seenPresses.size > 16) seenPresses.removeFirst() }
        session++
        return Decision(true, session, "новая сессия")
    }
}

/** R52.2: counters + last events, readable with `adb shell dumpsys activity service <pkg>/.ai.EvaAppService`
 *  (Homatics suppresses app logcat with persist.log.tag, so tests need an adb-readable source). No speech text. */
internal object EvaStats {
    val counters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val events = ArrayDeque<String>()
    fun count(name: String, detail: String = "") {
        counters.getOrPut(name) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        synchronized(events) {
            events.addLast("${android.os.SystemClock.uptimeMillis()} $name $detail".trim())
            while (events.size > 60) events.removeFirst()
        }
    }
    fun dump(writer: java.io.PrintWriter) {
        writer.println("EVA_STATS " + counters.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" })
        synchronized(events) { events.forEach { writer.println("EVA_EVENT $it") } }
    }
}

internal object EvaActivation {
    private val gate = ActivationGate()
    /** true = this request opens Eva; false = a duplicate of a request already being served (logged, not executed). */
    fun request(source: ActivationGate.Source, press: Long? = null): Boolean {
        val decision = gate.request(source, android.os.SystemClock.uptimeMillis(), press)
        if (decision.accepted) { if (press != null) VoiceTiming.mark(VoiceTiming.Phase.KEY_DOWN, press); VoiceTiming.mark(VoiceTiming.Phase.ACTIVATION) }
        EvaStats.count(if (decision.accepted) "activation_accepted" else "activation_dropped", "${source.name} press=${press ?: "-"} s=${decision.session}")
        com.example.homeezch.EzchDiag.log("EVA:ВЫЗОВ", "${source.name} press=${press ?: "-"} → " +
            (if (decision.accepted) "сессия #${decision.session}" else "пропущен (${decision.reason}), сессия #${decision.session}"))
        return decision.accepted
    }
}

/** One recognition session delivers at most one result to the executor (late or repeated callbacks are dropped). */
internal class OneResultGate {
    private var session = 0
    private var delivered = -1
    @Synchronized fun begin(): Int { session++; return session }
    @Synchronized fun take(): Boolean { if (delivered == session) return false; delivered = session; return true }
    val current: Int @Synchronized get() = session
}
