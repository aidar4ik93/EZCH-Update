package com.example.homeezch.ai

import android.os.SystemClock
import android.util.Log

/** Local phase timestamps only. Never log transcripts, audio or the content of a reply. */
internal object VoiceTiming {
    enum class Phase {
        PANEL_REQUESTED, PANEL_READY, MIC_REQUESTED, SYSTEM_START, SYSTEM_READY, SYSTEM_SPEECH_BEGIN, SYSTEM_SPEECH_END, SYSTEM_RESULT, SYSTEM_RELEASED,
        OFFLINE_START, OFFLINE_RESULT, COMMAND_RECEIVED, ACTION_READY, TTS_REQUESTED, TTS_STARTED
    }
    fun mark(phase: Phase) { Log.i("EvaTiming", "${phase.name} t=${SystemClock.elapsedRealtime()}") }
}
