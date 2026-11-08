package com.example.homeezch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

/**
 * Frosted glass for panels and menus, built so that it costs (almost) nothing per frame on weak TV GPUs.
 *
 * Live blur of whatever lies under a panel would re-blur the screen on every frame (6–10 ms on a Mali-G31).
 * Instead, when a panel opens the desktop is copied ONCE by the GPU straight into a 1/8-size bitmap
 * (PixelCopy scales while copying), blurred on a background thread (~1 ms for 240×135) and then drawn
 * stretched under the panel: one small texture, bilinear filtering does the rest of the softening.
 * The desktop cannot change under a modal panel, so the picture stays correct while the panel is open.
 */
internal object GlassBackdrop {
    private const val SCALE = 8
    /** Glass tint, baked into the copy: the panel then fills its area ONCE (fill-rate is what a TV GPU lacks). */
    const val TINT: Int = -0x4FF4E9DA // 0xB00B1626: dark enough for white text over the bright eagle
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ezch-glass").apply { priority = Thread.NORM_PRIORITY - 1 } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    tailrec fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    /** Copies the activity window, blurs the copy and returns it on the main thread; silent no-op on failure. */
    fun capture(activity: Activity, onReady: (Glass) -> Unit) {
        val window = activity.window ?: return
        val decor = window.decorView
        val width = decor.width; val height = decor.height
        if (width <= 0 || height <= 0) return
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val bitmap = runCatching { Bitmap.createBitmap((width / SCALE).coerceAtLeast(2), (height / SCALE).coerceAtLeast(2), Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return
        runCatching {
            PixelCopy.request(window, bitmap, { result ->
                if (result != PixelCopy.SUCCESS) { bitmap.recycle(); return@request }
                worker.execute {
                    runCatching { blur(bitmap, 5, TINT) }
                    val glass = Glass(bitmap.asImageBitmap(), Offset(origin[0].toFloat(), origin[1].toFloat()), width, height)
                    main.post { onReady(glass) }
                }
            }, main)
        }.onFailure { bitmap.recycle() }
    }

    /** Three box passes ≈ gaussian. Pure arithmetic on a tiny bitmap. */
    fun blur(bitmap: Bitmap, radius: Int, tintColor: Int) {
        val w = bitmap.width; val h = bitmap.height
        val a = IntArray(w * h); val b = IntArray(w * h)
        bitmap.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) { boxPass(a, b, w, h, radius, horizontal = true); boxPass(b, a, w, h, radius, horizontal = false) }
        tint(a, tintColor)
        bitmap.setPixels(a, 0, w, 0, 0, w, h)
    }

    /** Source-over of an ARGB [tint] on opaque pixels. */
    internal fun tint(pixels: IntArray, tint: Int) {
        val ta = (tint ushr 24) and 255; val keep = 255 - ta
        val tr = ((tint shr 16) and 255) * ta; val tg = ((tint shr 8) and 255) * ta; val tb = (tint and 255) * ta
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (((p shr 16) and 255) * keep + tr) / 255
            val g = (((p shr 8) and 255) * keep + tg) / 255
            val bl = ((p and 255) * keep + tb) / 255
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    internal fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int { val c = i.coerceIn(0, length - 1); return if (horizontal) src[line * w + c] else src[c * w + line] }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = at(i); sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            for (i in 0 until length) {
                val out = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1); val remove = at(i - r)
                sr += ((add shr 16) and 255) - ((remove shr 16) and 255)
                sg += ((add shr 8) and 255) - ((remove shr 8) and 255)
                sb += (add and 255) - (remove and 255)
            }
        }
    }
}

/** A blurred, reduced copy of the screen area that starts at [origin] (screen pixels) and is [width]×[height]. */
internal class Glass(val image: ImageBitmap, val origin: Offset, val width: Int, val height: Int)

/** Captures the desktop once for the lifetime of the calling composable (a panel, menu or dialog). */
@Composable
internal fun rememberGlass(): State<Glass?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Glass?>(null) }
    DisposableEffect(Unit) {
        var alive = true
        GlassBackdrop.activity(context)?.let { activity -> GlassBackdrop.capture(activity) { if (alive) state.value = it } }
        onDispose { alive = false }
    }
    return state
}

/**
 * Frosted-glass surface: blurred desktop + tint + top light + thin bright rim. Drawn in its own layer,
 * so scrolling the panel's content does not redraw the glass. Before the copy is ready (one or two frames)
 * the denser [fallback] tint is shown, then the glass fades in.
 */
@Composable
internal fun GlassSurface(glass: Glass?, shape: Shape, modifier: Modifier = Modifier,
    fallback: Color = Color(0xF2102035), content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    // Read while drawing: the glass sample follows the panel while it slides in, then stays still.
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(if (glass != null) 1f else 0f) }
    LaunchedEffect(glass != null) { if (glass != null) reveal.animateTo(1f, tween(140)) }
    // Like a Surface: touches/mouse clicks on the glass never fall through to the scrim behind it.
    Box(modifier.clip(shape).pointerInput(Unit) { }.onGloballyPositioned { coordinates ->
        val window = IntArray(2).also(view::getLocationOnScreen)
        topLeft = coordinates.positionInWindow() + Offset(window[0].toFloat(), window[1].toFloat())
    }, propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().graphicsLayer { }.drawBehind {
            val shown = reveal.value
            if (shown < 1f) drawRect(fallback.copy(alpha = fallback.alpha * (1f - shown)))
            if (glass != null && shown > 0f) {
                // The whole (tiny) copy is stretched to screen size and shifted under the panel; the clip limits
                // the GPU work to the panel's own pixels, and sub-pixel shifts keep the glass still while sliding.
                translate(glass.origin.x - topLeft.x, glass.origin.y - topLeft.y) {
                    drawImage(glass.image, IntOffset.Zero, IntSize(glass.image.width, glass.image.height),
                        dstSize = IntSize(glass.width, glass.height), alpha = shown, filterQuality = FilterQuality.Low)
                }
            }
            // Light from above and a thin bright rim: what makes it read as glass rather than a dark box.
            // Only the top fifth gets the light, so this is a small extra fill, not a second full layer.
            val lit = size.height.coerceAtMost(260.dp.toPx()) * .5f
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .11f), 1f to Color.Transparent, endY = lit),
                size = androidx.compose.ui.geometry.Size(size.width, lit))
            drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = .20f), style = Stroke(1.2.dp.toPx()))
        })
        content()
    }
}

