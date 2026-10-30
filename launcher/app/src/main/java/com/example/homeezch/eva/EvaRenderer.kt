package com.example.homeezch.eva

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the EVA energy sphere: intersecting translucent 3D ribbons blended
 * additively, a dark core, bloom, state effects and the platform ring.
 *
 * Works on a plain [Canvas], so the same code serves Compose (home screen)
 * and Views (voice panel / system assistant session). Allocation-free per frame:
 * paths, buffers and unit-radius shaders are created once and reused.
 */
class EvaRenderer {
    private var miniBitmap: Bitmap? = null
    private var miniCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; xfermode = add }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = add
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val coreShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xF2020410.toInt(), 0xCC07061E.toInt(), 0x6610083A, 0x00000000),
        floatArrayOf(0f, .55f, .82f, 1f), Shader.TileMode.CLAMP)
    private val bloomShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(0x00000000, 0x00000000, 0xFF2A6EFF.toInt(), 0x667945FF, 0x00000000),
        floatArrayOf(0f, .58f, .70f, .82f, 1f), Shader.TileMode.CLAMP)
    private val platformShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xFF00DFFF.toInt(), 0xAA168BFF.toInt(), 0x557945FF, 0x00000000),
        floatArrayOf(0f, .35f, .65f, 1f), Shader.TileMode.CLAMP)

    private var cap = 0
    private var ax = FloatArray(0); private var ay = FloatArray(0); private var az = FloatArray(0)
    private var bx = FloatArray(0); private var by = FloatArray(0); private var bz = FloatArray(0)
    private var px = FloatArray(0); private var py = FloatArray(0)
    private var qx = FloatArray(0); private var qy = FloatArray(0)
    private val rgb = FloatArray(3)
    private val m = FloatArray(9)

    private fun ensure(n: Int) {
        if (cap >= n) return
        cap = n
        ax = FloatArray(n); ay = FloatArray(n); az = FloatArray(n)
        bx = FloatArray(n); by = FloatArray(n); bz = FloatArray(n)
        px = FloatArray(n); py = FloatArray(n); qx = FloatArray(n); qy = FloatArray(n)
    }

    /**
     * @param platform draw the glowing pedestal under the orb (home screen); the voice
     * panel and small icons pass false.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, a: EvaAnimator, state: EvaState, q: EvaQuality, platform: Boolean) {
        // Rasterise the tiny icon into a reused transparent buffer. On software GPU
        // drivers, submitting each changing path to HWUI costs far more than drawing
        // these few pixels. Only the icon is buffered, never the HOME background.
        if (canvas.isHardwareAccelerated && !platform && width in 1f..192f && height in 1f..192f) {
            val w = kotlin.math.ceil(width).toInt()
            val h = kotlin.math.ceil(height).toInt()
            if (miniBitmap?.width != w || miniBitmap?.height != h) {
                miniBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                miniCanvas = Canvas(checkNotNull(miniBitmap))
            }
            val bitmap = checkNotNull(miniBitmap)
            bitmap.eraseColor(Color.TRANSPARENT)
            drawContent(checkNotNull(miniCanvas), width, height, a, state, q, platform)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        } else {
            drawContent(canvas, width, height, a, state, q, platform)
        }
    }

    private fun drawContent(canvas: Canvas, width: Float, height: Float, a: EvaAnimator, state: EvaState, q: EvaQuality, platform: Boolean) {
        if (width <= 0f || height <= 0f) return
        val showPlatform = platform && q.platform
        val cx = width / 2f
        val baseR = if (showPlatform) min(width, height * 1.0f) * .30f else min(width, height) * .34f
        val cy = if (showPlatform) height * .42f else height / 2f
        val r = baseR * a.scale * a.breathing(state)
        val unit = baseR / 130f
        val glow = a.glow

        if (showPlatform) drawPlatform(canvas, cx, cy + baseR * 1.24f, baseR, a, state, unit)

        // Dark core: the "black hole" centre of the reference design.
        radial(canvas, coreShader, corePaint, cx, cy, r * 1.02f, r * 1.02f, .55f + .45f * a.appear)

        val ribbons = min(q.ribbons, a.phase.size)
        val n = q.segments
        ensure(n + 1)
        // A tiny HOME sphere does not need hundreds of separately tessellated coloured
        // paths. Keep the same moving 3D ribbons, with one surface and two edges each.
        val compact = !platform && baseR <= 64f
        for (i in 0 until ribbons) drawRibbon(canvas, i, cx, cy, r, unit, n, a, glow,
            if (compact) 1 else q.glowPasses, compact)

        if (q.particles > 0 && a.particles > .01f) drawParticles(canvas, cx, cy, r, unit, a, (q.particles * a.particles).toInt())
        if (state == EvaState.LISTENING || a.level > .02f && state != EvaState.SPEAKING) drawListening(canvas, cx, cy, r, unit, a)
        if (a.waves > .01f) drawSpeaking(canvas, cx, cy, r, unit, a)
        if (a.pulse > 0f) {
            val t = 1f - a.pulse
            line.strokeWidth = unit * (3f + 4f * a.pulse)
            line.color = if (a.pulseError) argb(.75f * a.pulse, 1f, .24f, .5f) else argb(.75f * a.pulse, 0f, .87f, 1f)
            canvas.drawCircle(cx, cy, r * (1.02f + .6f * t), line)
        }
        if (q.bloom) {
            radial(canvas, bloomShader, glowPaint, cx, cy, r * 1.45f, r * 1.45f, .32f * glow)
        }
    }

    private fun drawRibbon(canvas: Canvas, i: Int, cx: Float, cy: Float, r: Float, unit: Float, n: Int,
        a: EvaAnimator, glow: Float, passes: Int, compact: Boolean) {
        // Each ribbon is a flat annulus: it precesses in the screen plane (phase), nods in depth
        // and keeps its own tilt, so ellipses of different eccentricity cross the sphere.
        val nod = .30f * sin(a.time * (.4f + .13f * i) + i * 1.7f)
        rotation(a.phase[i] + OFFSET[i], TILT[i] + nod)
        val inner = r * (1f - min(.9f, WIDTH[i] * .55f * a.spread))
        for (k in 0..n) {
            val ang = k.toFloat() / n * TWO_PI
            val cs = cos(ang); val sn = sin(ang)
            // Edge A = inner rim, edge B = outer rim of the band.
            transform(cs * inner, 0f, sn * inner); ax[k] = tx; ay[k] = ty; az[k] = tz
            transform(cs * r, 0f, sn * r); bx[k] = tx; by[k] = ty; bz[k] = tz
            val fa = 1f + az[k] / r * .18f; val fb = 1f + bz[k] / r * .18f
            px[k] = cx + ax[k] * fa; py[k] = cy + ay[k] * fa
            qx[k] = cx + bx[k] * fb; qy[k] = cy + by[k] * fb
        }
        val hue = HUE[i] + a.time * .03f
        // Translucent band surface, brighter where it is seen edge-on.
        var s = 0
        while (s < n) {
            val e = min(s + if (compact) n else FILL_CHUNK, n)
            val mid = (s + e) / 2
            val face = facing(mid)
            val depth = depthWeight((az[mid] + bz[mid]) * .5f / r)
            a.color(mid.toFloat() / n + hue, rgb)
            path.rewind()
            path.moveTo(px[s], py[s])
            for (k in s + 1..e) path.lineTo(px[k], py[k])
            for (k in e downTo s) path.lineTo(qx[k], qy[k])
            path.close()
            fill.color = argb((.16f + .30f * (1f - face)) * depth * glow, rgb[0], rgb[1], rgb[2])
            canvas.drawPath(path, fill)
            s = e
        }
        // Edges: wide coloured glow, mid glow and a hot near-white core.
        for (edge in 0..1) {
            val xs = if (edge == 0) px else qx
            val ys = if (edge == 0) py else qy
            val zs = if (edge == 0) az else bz
            s = 0
            while (s < n) {
                val e = min(s + if (compact) n else EDGE_CHUNK, n)
                val mid = (s + e) / 2
                val depth = depthWeight(zs[mid] / r)
                a.color(mid.toFloat() / n + hue, rgb)
                path.rewind()
                path.moveTo(xs[s], ys[s])
                for (k in s + 1..e) path.lineTo(xs[k], ys[k])
                val first = PASSES.size - passes.coerceIn(1, PASSES.size)
                for (p in first until PASSES.size) {
                    val hot = p == PASSES.size - 1
                    val white = if (hot) .55f * depth else 0f
                    line.strokeWidth = unit * PASS_WIDTH[p]
                    line.color = argb(PASSES[p] * depth * glow,
                        rgb[0] + (1f - rgb[0]) * white, rgb[1] + (1f - rgb[1]) * white, rgb[2] + (1f - rgb[2]) * white)
                    canvas.drawPath(path, line)
                }
                s = e
            }
        }
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, r: Float, unit: Float, a: EvaAnimator, count: Int) {
        for (j in 0 until count) {
            val f1 = frac(j * .618034f); val f2 = frac(j * .371f + .13f); val f3 = frac(j * .7548f + .4f)
            val ang = a.time * (.7f + 1.4f * f2) + j * 2.399f
            val orbit = r * (1.04f + .38f * f1)
            val tilt = (f3 - .5f) * 1.6f
            val x = cos(ang) * orbit
            val y = sin(ang) * orbit * (.35f + .5f * abs(tilt)) + tilt * r * .25f
            val front = .45f + .55f * (sin(ang) * .5f + .5f)
            a.color(.55f + f1 * .4f, rgb)
            fill.color = argb(.85f * front * a.particles, rgb[0], rgb[1], rgb[2])
            canvas.drawCircle(cx + x, cy + y, unit * (1.3f + 2.2f * f3), fill)
        }
    }

    private fun drawListening(canvas: Canvas, cx: Float, cy: Float, r: Float, unit: Float, a: EvaAnimator) {
        val lv = a.level
        for (ring in 0..1) {
            val t = frac(a.time * .9f + ring * .5f)
            line.strokeWidth = unit * (2.2f - t)
            line.color = argb((.18f + .5f * lv) * (1f - t), 0f, .87f, 1f)
            canvas.drawCircle(cx, cy, r * (1.04f + .30f * t + .10f * lv), line)
        }
    }

    private fun drawSpeaking(canvas: Canvas, cx: Float, cy: Float, r: Float, unit: Float, a: EvaAnimator) {
        val amp = a.waves * (.35f + .65f * a.level)
        line.strokeWidth = unit * 3.2f
        for (side in 0..1) {
            val dir = if (side == 0) -1f else 1f
            for (b in 0 until 7) {
                val x = cx + dir * (r * 1.22f + b * unit * 8.5f)
                val wave = abs(sin(a.time * 7.5f + b * .9f + side))
                val half = unit * (4f + 46f * amp * wave) * (1f - b / 9f)
                a.color(.1f + b * .07f, rgb)
                line.color = argb(.85f * a.waves * (1f - b / 8f), rgb[0], rgb[1], rgb[2])
                canvas.drawLine(x, cy - half, x, cy + half, line)
            }
        }
        val t = frac(a.time * .8f)
        line.strokeWidth = unit * 2f
        line.color = argb(.35f * a.waves * (1f - t), .08f, .55f, 1f)
        canvas.drawCircle(cx, cy, r * (1.05f + .45f * t), line)
    }

    private fun drawPlatform(canvas: Canvas, cx: Float, py0: Float, baseR: Float, a: EvaAnimator, state: EvaState, unit: Float) {
        val rx = baseR * 1.08f
        val ry = baseR * .17f
        val boost = if (state == EvaState.LISTENING) .25f * a.level else 0f
        val power = (.35f + .65f * a.appear) * (.55f + .45f * a.glow) + boost
        radial(canvas, platformShader, glowPaint, cx, py0, rx * 1.25f, ry * 1.6f, .55f * power)
        line.strokeWidth = unit * 2.4f
        line.color = argb(.6f * power, 0f, .87f, 1f)
        canvas.drawOval(cx - rx, py0 - ry, cx + rx, py0 + ry, line)
        line.strokeWidth = unit * 1.4f
        line.color = argb(.55f * power, .47f, .27f, 1f)
        canvas.drawOval(cx - rx * .72f, py0 - ry * .72f, cx + rx * .72f, py0 + ry * .72f, line)
        line.strokeWidth = unit * 7f
        line.color = argb(.12f * power, .09f, .55f, 1f)
        canvas.drawOval(cx - rx, py0 - ry, cx + rx, py0 + ry, line)
    }

    private val local = android.graphics.Matrix()

    /** Unit-radius gradient mapped onto an ellipse through the shader's local matrix. */
    private fun radial(canvas: Canvas, shader: RadialGradient, paint: Paint, x: Float, y: Float, rx: Float, ry: Float, alpha: Float) {
        if (rx <= 0f || ry <= 0f) return
        local.setScale(rx, ry); local.postTranslate(x, y)
        shader.setLocalMatrix(local)
        paint.shader = shader
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
    }

    // --- math helpers (no allocation) ---
    private var tx = 0f; private var ty = 0f; private var tz = 0f

    private val ra = FloatArray(9); private val rb = FloatArray(9)

    /** M = Rz(spin) · Rx(tilt): ring normal tilted toward the viewer, rotated in the screen plane. */
    private fun rotation(spin: Float, tilt: Float) {
        rotX(tilt, ra)
        rotZ(spin, rb)
        multiply(rb, ra, m)
    }

    private fun rotX(t: Float, o: FloatArray) { val c = cos(t); val s = sin(t)
        o[0] = 1f; o[1] = 0f; o[2] = 0f; o[3] = 0f; o[4] = c; o[5] = -s; o[6] = 0f; o[7] = s; o[8] = c }
    private fun rotZ(t: Float, o: FloatArray) { val c = cos(t); val s = sin(t)
        o[0] = c; o[1] = -s; o[2] = 0f; o[3] = s; o[4] = c; o[5] = 0f; o[6] = 0f; o[7] = 0f; o[8] = 1f }
    private fun multiply(x: FloatArray, y: FloatArray, o: FloatArray) {
        for (row in 0..2) for (col in 0..2)
            o[row * 3 + col] = x[row * 3] * y[col] + x[row * 3 + 1] * y[3 + col] + x[row * 3 + 2] * y[6 + col]
    }

    private fun transform(x: Float, y: Float, z: Float) {
        tx = m[0] * x + m[1] * y + m[2] * z
        ty = m[3] * x + m[4] * y + m[5] * z
        tz = m[6] * x + m[7] * y + m[8] * z
    }

    private fun facing(k: Int): Float {
        val k2 = k + 1
        val e1x = bx[k] - ax[k]; val e1y = by[k] - ay[k]; val e1z = bz[k] - az[k]
        val e2x = ax[k2] - ax[k]; val e2y = ay[k2] - ay[k]; val e2z = az[k2] - az[k]
        val nx = e1y * e2z - e1z * e2y
        val ny = e1z * e2x - e1x * e2z
        val nz = e1x * e2y - e1y * e2x
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len < 1e-6f) 0f else abs(nz) / len
    }

    companion object {
        private const val TWO_PI = 6.2831855f
        private const val FILL_CHUNK = 3
        private const val EDGE_CHUNK = 6
        private val TILT = floatArrayOf(.62f, 1.28f, .45f, 1.00f, 1.12f, .80f)
        private val OFFSET = floatArrayOf(0f, 2.2f, 1.7f, 1.1f, 2.8f, .5f)
        private val HUE = floatArrayOf(0f, .30f, .62f, .82f, .45f, .15f)
        private val WIDTH = floatArrayOf(.46f, .38f, .34f, .22f, .10f, .08f)
        /** Alpha of wide glow, mid glow and hot core passes. */
        private val PASSES = floatArrayOf(.05f, .20f, .85f)
        private val PASS_WIDTH = floatArrayOf(12f, 4.5f, 1.3f)

        private fun depthWeight(z: Float) = .45f + .55f * (z.coerceIn(-1f, 1f) * .5f + .5f)
        private fun frac(v: Float) = v - kotlin.math.floor(v)
        fun argb(alpha: Float, r: Float, g: Float, b: Float): Int = Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(), (r.coerceIn(0f, 1f) * 255).toInt(),
            (g.coerceIn(0f, 1f) * 255).toInt(), (b.coerceIn(0f, 1f) * 255).toInt())
    }
}
