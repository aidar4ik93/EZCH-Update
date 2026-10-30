package com.example.homeezch.eva

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Target look of a state. The animator eases toward it, so state changes never jump. */
data class EvaLook(
    val spin: Float,
    val glow: Float,
    val scale: Float,
    val spread: Float,
    val particles: Float,
    val waves: Float,
    val palette: Int,
    /** Frame cap; 0 = no continuous animation once the transition has settled. */
    val fps: Int
) {
    companion object {
        const val EVA = 0; const val CYAN = 1; const val VIOLET = 2; const val ERROR = 3; const val DIM = 4

        fun of(state: EvaState): EvaLook = when (state) {
            EvaState.APPEAR -> EvaLook(1.8f, 1.15f, 1f, 1f, .3f, 0f, EVA, 60)
            EvaState.IDLE -> EvaLook(1f, .92f, 1f, 1f, 0f, 0f, EVA, 30)
            EvaState.LISTENING -> EvaLook(1.4f, 1.1f, 1.03f, 1.1f, 0f, 0f, CYAN, 60)
            EvaState.THINKING -> EvaLook(3.4f, 1.02f, .96f, .82f, 1f, 0f, VIOLET, 60)
            EvaState.SPEAKING -> EvaLook(1.6f, 1.15f, 1.02f, 1.05f, .15f, 1f, EVA, 60)
            EvaState.SUCCESS -> EvaLook(2f, 1.35f, 1.05f, 1.1f, .25f, 0f, CYAN, 60)
            EvaState.ERROR -> EvaLook(.7f, 1.1f, .98f, 1f, 0f, 0f, ERROR, 60)
            EvaState.OFFLINE -> EvaLook(.45f, .5f, .97f, .9f, 0f, 0f, DIM, 20)
            EvaState.SLEEP -> EvaLook(.12f, .28f, .95f, .9f, 0f, 0f, DIM, 0)
        }

        /** Six-stop ribbon gradients (RGB 0..1), aligned with the EZCH design tokens. */
        val PALETTES: Array<FloatArray> = arrayOf(
            rgb(0x168BFF, 0x00DFFF, 0x3C78FF, 0x7945FF, 0xD34BFF, 0x7945FF),
            rgb(0x00DFFF, 0x7FF3FF, 0x168BFF, 0x00DFFF, 0x7945FF, 0x168BFF),
            rgb(0x7945FF, 0xD34BFF, 0x5B3BFF, 0x9A55FF, 0xD34BFF, 0x168BFF),
            rgb(0xFF3D7F, 0xD34BFF, 0xFF5C8A, 0x7945FF, 0xFF3D7F, 0xD34BFF),
            rgb(0x2A4C8C, 0x1E6C8C, 0x2E3F7A, 0x40307A, 0x5A2E7A, 0x40307A)
        )

        private fun rgb(vararg hex: Int) = FloatArray(hex.size * 3).also { out ->
            hex.forEachIndexed { i, c ->
                out[i * 3] = ((c shr 16) and 0xFF) / 255f
                out[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
                out[i * 3 + 2] = (c and 0xFF) / 255f
            }
        }
    }
}

/**
 * Eased animation parameters shared by the Compose and View renderers.
 * Deterministic: the same sequence of [step] calls always yields the same frame.
 */
class EvaAnimator(ribbons: Int = MAX_RIBBONS) {
    var time = 0f; private set
    val phase = FloatArray(ribbons)
    var glow = 0f; private set
    var scale = .2f; private set
    var spread = 1f; private set
    var particles = 0f; private set
    var waves = 0f; private set
    var level = 0f; private set
    /** 0..1 appear progress; drives the "Появление → Готов к диалогу" sequence. */
    var appear = 0f; private set
    /** 1 at the start of SUCCESS/ERROR, decays to 0: the expanding pulse ring. */
    var pulse = 0f; private set
    var pulseError = false; private set
    val palette: FloatArray = EvaLook.PALETTES[EvaLook.EVA].copyOf()
    private var lastState: EvaState? = null

    /** True while values still move toward the target; renderers may stop frames afterwards. */
    var settling = true; private set

    fun step(dt: Float, state: EvaState, inputLevel: Float) {
        val d = dt.coerceIn(0f, .1f)
        val look = EvaLook.of(state)
        if (state != lastState) {
            if (state == EvaState.SUCCESS || state == EvaState.ERROR) { pulse = 1f; pulseError = state == EvaState.ERROR }
            lastState = state
        }
        time += d
        for (i in phase.indices) {
            phase[i] = ((phase[i] + d * look.spin * SPEEDS[i % SPEEDS.size] * TWO_PI) % TWO_PI)
        }
        val k = 1f - exp(-d * 4.5f)
        val before = glow + scale + spread + particles + waves
        appear = (appear + d / (EvaStateMachine.APPEAR_MS / 1000f)).coerceAtMost(1f)
        glow += (look.glow - glow) * k
        scale += (look.scale * appearScale() - scale) * k
        spread += (look.spread - spread) * k
        particles += (look.particles - particles) * k
        waves += (look.waves - waves) * k
        level += (inputLevel.coerceIn(0f, 1f) - level) * (1f - exp(-d * 12f))
        pulse = (pulse - d / .9f).coerceAtLeast(0f)
        val target = EvaLook.PALETTES[look.palette]
        var paletteDelta = 0f
        for (i in palette.indices) {
            val delta = (target[i] - palette[i]) * k
            palette[i] += delta; paletteDelta += kotlin.math.abs(delta)
        }
        val after = glow + scale + spread + particles + waves
        settling = kotlin.math.abs(after - before) > 1e-4f || paletteDelta > 1e-4f || pulse > 0f || appear < 1f
    }

    /** Overshoot curve: the orb grows from a seed, slightly overshoots and settles. */
    private fun appearScale(): Float {
        val t = appear
        return if (t >= 1f) 1f else (.2f + .8f * (1f - (1f - t) * (1f - t) * (1f - t)) + .06f * sin(t * PI.toFloat()))
    }

    /** Gentle breathing on top of the eased scale; listening adds the microphone level. */
    fun breathing(state: EvaState): Float = when (state) {
        EvaState.LISTENING -> 1f + .12f * level + .015f * sin(time * 6f)
        EvaState.SPEAKING -> 1f + .05f * level + .02f * sin(time * 9f)
        EvaState.SLEEP -> 1f
        else -> 1f + .018f * sin(time * 1.3f)
    }

    fun color(u: Float, out: FloatArray) {
        val n = palette.size / 3
        var x = (u % 1f); if (x < 0) x += 1f
        val s = x * n
        val i = s.toInt() % n
        val j = (i + 1) % n
        var t = s - s.toInt(); t = t * t * (3 - 2 * t)
        out[0] = palette[i * 3] + (palette[j * 3] - palette[i * 3]) * t
        out[1] = palette[i * 3 + 1] + (palette[j * 3 + 1] - palette[i * 3 + 1]) * t
        out[2] = palette[i * 3 + 2] + (palette[j * 3 + 2] - palette[i * 3 + 2]) * t
    }

    companion object {
        const val MAX_RIBBONS = 6
        private const val TWO_PI = 6.2831855f
        /** Independent per-ribbon speeds and directions (rev/s at spin = 1). */
        val SPEEDS = floatArrayOf(.10f, -.075f, .065f, -.09f, .085f, -.055f)
    }
}
