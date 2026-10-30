package com.example.homeezch.ai

/**
 * Pure decision logic (JVM-tested): a film plays for [dimAfterMs] → dim; paused/stopped for
 * [restoreAfterMs] → restore. Short sounds (menu clicks, Ai EvA speaking) never touch the lights.
 */
class CinemaPolicy(private val dimAfterMs: Long = 4_000, private val restoreAfterMs: Long = 2_500) {
    enum class Decision { NONE, DIM, RESTORE }
    var dimmed = false; private set
    private var playingSince = -1L
    private var quietSince = -1L

    fun update(now: Long, playing: Boolean): Decision {
        if (playing) {
            quietSince = -1L
            if (playingSince < 0) playingSince = now
            if (!dimmed && now - playingSince >= dimAfterMs) { dimmed = true; return Decision.DIM }
        } else {
            playingSince = -1L
            if (quietSince < 0) quietSince = now
            if (dimmed && now - quietSince >= restoreAfterMs) { dimmed = false; return Decision.RESTORE }
        }
        return Decision.NONE
    }
    fun reset() { dimmed = false; playingSince = -1L; quietSince = -1L }

    companion object {
        /** Music and radio players: sound without a film, the lights stay as they are. */
        val AUDIO_APPS = setOf("com.spotify.tv.android", "com.spotify.music", "ru.yandex.music", "com.yandex.music",
            "com.zvooq.openplay", "com.vk.music", "deezer.android.tv", "com.apple.android.music", "fm.radio", "com.google.android.apps.youtube.music")
        fun eligible(foreground: String?, ownPackage: String): Boolean =
            foreground == null || (foreground != ownPackage && foreground !in AUDIO_APPS &&
                !foreground.startsWith("com.android.systemui") && !foreground.startsWith("com.google.android.katniss"))

        /** Restore only if nobody changed the light by hand while the film was on. */
        fun untouched(currentPct: Int?, dimPct: Int): Boolean = currentPct == null || kotlin.math.abs(currentPct - dimPct) <= 3
    }
}
