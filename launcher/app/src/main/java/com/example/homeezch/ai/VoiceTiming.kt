package com.example.homeezch.ai

import android.os.SystemClock
import android.util.Log

/** Local phase timestamps only. Never log transcripts, audio or the content of a reply.
 *  Claude r55: also kept in a small ring buffer (uptime ms) and printed by `dumpsys activity service …EvaAppService`
 *  as EVA_TIMING lines — on Homatics app logcat is suppressed (persist.log.tag), so this is how voice latency is measured. */
internal object VoiceTiming {
    enum class Phase {
        KEY_DOWN, SESSION_NEW, SESSION_PREPARE, SESSION_SHOW, ACTIVATION, WAKE_COMMAND,
        PANEL_REQUESTED, PANEL_READY, MIC_REQUESTED, SYSTEM_START, SYSTEM_READY, SYSTEM_SPEECH_BEGIN, SYSTEM_SPEECH_END, SYSTEM_RESULT, SYSTEM_RELEASED,
        OFFLINE_START, OFFLINE_RESULT, COMMAND_RECEIVED, ACTION_READY, TTS_REQUESTED, TTS_STARTED
    }
    private const val MAX = 400
    private val ring = ArrayDeque<String>(MAX)
    fun mark(phase: Phase, at: Long = SystemClock.uptimeMillis()) {
        Log.i("EvaTiming", "${phase.name} t=$at")
        synchronized(ring) { if (ring.size >= MAX) ring.removeFirst(); ring.addLast("${phase.name} $at") }
    }
    fun dump(writer: java.io.PrintWriter) {
        writer.println("EVA_TIMING_NOW ${SystemClock.uptimeMillis()}")
        synchronized(ring) { ring.forEach { writer.println("EVA_TIMING $it") } }
    }
}
