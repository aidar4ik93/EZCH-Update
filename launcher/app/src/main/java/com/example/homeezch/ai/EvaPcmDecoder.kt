package com.example.homeezch.ai

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Streaming 16 kHz, mono, signed 16-bit PCM; native recognizers are confined to the caller thread. */
internal class EvaPcmDecoder(model: Model, private val onWake: () -> Unit = {}) : AutoCloseable {
    private val model = model
    private fun wakeRecognizer() = Recognizer(model, 16000f, "[\"ева\",\"эва\",\"ива\",\"[unk]\"]")
    private var wake: Recognizer? = wakeRecognizer()
    private var command: Recognizer? = null
    private val history = java.util.ArrayDeque<ByteArray>()
    private var historyBytes = 0
    private var bytes = 0L
    private var result: String? = null
    var wakeAudioMs: Long? = null; private set
    var candidateAudioMs: Long? = null; private set
    var lastRecognition: String = ""; private set
    val audioMs: Long get() = bytes / 32
    /** True once «Ева» was heard: from then on every chunk must be decoded (no energy gating). */
    val awake: Boolean get() = command != null
    /** Silence after speech without the wake word: drop stale wake context. */
    fun resetWake() { if (command == null) { wake?.reset(); history.clear(); historyBytes = 0 } }

    fun accept(pcm: ByteArray, count: Int): String? {
        require(count in 0..pcm.size && count % 2 == 0)
        result?.let { return it }
        bytes += count
        val listener = wake
        if (listener != null) {
            history.addLast(pcm.copyOf(count)); historyBytes += count
            // Count bytes rather than reads: AudioRecord's nonblocking chunks may be much shorter than 200 ms.
            while (historyBytes > 64000) {
                val first = history.removeFirst()
                val excess = historyBytes - 64000
                if (first.size > excess) {
                    history.addFirst(first.copyOfRange(excess, first.size)); historyBytes -= excess
                } else historyBytes -= first.size
            }
            val finished = listener.acceptWaveForm(pcm, count)
            val value = JSONObject(if (finished) listener.result else listener.partialResult)
            if (EvaSpeechText.wake(value.optString(if (finished) "text" else "partial"))) {
                candidateAudioMs = audioMs
                wake = null; listener.close()
                val next = Recognizer(model, 16000f)
                command = next
                for (chunk in history) if (next.acceptWaveForm(chunk, chunk.size)) capture(next.result)
                history.clear(); historyBytes = 0
                if (result == null) inspect(next.partialResult, false)
            } else if (finished) listener.reset()
        } else {
            val next = checkNotNull(command)
            if (next.acceptWaveForm(pcm, count)) {
                capture(next.result)
                if (wakeAudioMs == null) rejectCandidate()
            } else {
                inspect(next.partialResult, false)
                if (wakeAudioMs == null && audioMs - checkNotNull(candidateAudioMs) > 2000) rejectCandidate()
            }
        }
        return result
    }

    private fun inspect(json: String, finished: Boolean) {
        val raw = JSONObject(json).optString(if (finished) "text" else "partial")
        if (raw.isNotBlank()) lastRecognition = raw
        // The tiny wake grammar can force unrelated speech into «ива».
        // Confirm with the unconstrained decoder before notifying HOME or producing a command.
        if (wakeAudioMs == null && EvaSpeechText.wake(raw)) {
            wakeAudioMs = audioMs
            onWake()
        }
        if (finished && wakeAudioMs != null) {
            val text = EvaSpeechText.clean(raw)
            if (text.isNotBlank() && result == null) result = text
        }
    }

    private fun capture(json: String) = inspect(json, true)

    private fun rejectCandidate() {
        command?.close(); command = null
        wake = wakeRecognizer()
        history.clear(); historyBytes = 0
    }

    fun finish(): String {
        if (result == null) command?.let { capture(it.finalResult) }
        return result.orEmpty()
    }

    override fun close() {
        try { wake?.close() } finally { command?.close(); wake = null; command = null; history.clear() }
    }
}
