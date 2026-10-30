package com.example.homeezch.ai

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import com.example.homeezch.eva.EvaAnimator
import com.example.homeezch.eva.EvaBus
import com.example.homeezch.eva.EvaQuality
import com.example.homeezch.eva.EvaRenderer
import com.example.homeezch.eva.EvaState
import com.example.homeezch.eva.GraphicsTier

/**
 * Compact Ai EvA: the animated ribbon sphere (the chosen «вариант 4» design), not a static icon.
 * Fits within two thirds of an application tile, no backdrop. The state is derived from what the
 * voice panel reports: listening (live mic level), thinking (busy), speaking (TTS level), resting.
 * Frames come from Choreographer only while visible; at rest it breathes at a low frame rate.
 */
class EvaMiniView(context: Context) : EvaSphereView(context) {
    private val quality = miniQuality(context)
    private val animator = EvaAnimator(quality.ribbons)
    private val renderer = EvaRenderer()
    private var lastFrame = 0L
    private var running = false
    private var ready = false

    private val state: EvaState get() = when {
        amplitude > .015f && busy -> EvaState.LISTENING
        busy -> EvaState.THINKING
        amplitude > .015f -> EvaState.SPEAKING
        else -> EvaState.IDLE
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val current = state
            val fps = if (current == EvaState.IDLE) quality.idleFps else quality.maxFps
            val gap = if (fps <= 0) Long.MAX_VALUE else 1_000_000_000L / fps - 2_000_000L
            if (lastFrame == 0L) lastFrame = now
            if (now - lastFrame >= gap || animator.settling) {
                animator.step((now - lastFrame) / 1e9f, current, maxOf(amplitude, EvaBus.level))
                lastFrame = now
                invalidate()
            }
            if (fps > 0 || animator.settling) Choreographer.getInstance().postFrameCallback(this) else running = false
        }
    }

    init { isFocusable = false; isFocusableInTouchMode = false; ready = true }

    private fun start() {
        if (!ready || running || !isAttachedToWindow || windowVisibility != VISIBLE || visibility != VISIBLE) return
        running = true; lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }
    private fun stop() { if (ready) { running = false; Choreographer.getInstance().removeFrameCallback(frame) } }

    // Property changes (busy/amplitude/message) call invalidate(): make sure the clock is running.
    override fun invalidate() { super.invalidate(); if (ready && !running) post { start() } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true; letterSpacing = .06f
    }
    private var shaderWidth = -1f

    override fun onDraw(canvas: Canvas) {
        contentDescription = "Ai EvA. $message"
        // The sphere and the «Ai EvA» name form one block: sphere in the upper 80 %, the name right under it.
        val h = height.toFloat(); val w = width.toFloat()
        val sphereH = h * .82f
        renderer.draw(canvas, w, sphereH, animator, state, quality, false)
        label.textSize = (h * .15f).coerceAtLeast(9f)
        val y = h * .97f
        if (shaderWidth != w) {
            shaderWidth = w
            label.shader = android.graphics.LinearGradient(w * .25f, 0f, w * .75f, 0f,
                intArrayOf(android.graphics.Color.rgb(0, 223, 255), android.graphics.Color.rgb(170, 120, 255)), null, android.graphics.Shader.TileMode.CLAMP)
        }
        label.alpha = (170 + 85 * animator.glow.coerceIn(0f, 1f)).toInt()
        canvas.drawText("Ai EvA", w / 2f, y, label)
    }

    companion object {
        /**
         * At rest the sphere is ONE frozen frame (idleFps = 0): no Choreographer frames, no RenderThread work,
         * HOME stays at ~0 % CPU (night measurement: a 12 fps idle sphere cost 24–69 % of a core on API33).
         * It comes alive only while listening / thinking / speaking, then settles and stops again.
         */
        fun miniQuality(context: Context): EvaQuality =
            if (com.example.homeezch.deviceLowRam(context)) EvaQuality.of(GraphicsTier.STATIC)
            else EvaQuality.of(GraphicsTier.AMBIENT).copy(idleFps = 0, particles = 8)
    }
}
