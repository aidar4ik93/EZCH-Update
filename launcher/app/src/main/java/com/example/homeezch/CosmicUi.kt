package com.example.homeezch

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

internal val Ice = Color(0xFF89C9FF)
internal val Gold = Color(0xFFE6C58B)
internal val Muted = Color(0xFF99AEC4)
internal val Glass = Color(0xBD111E30)
internal val Night = Color(0xFF030711)

internal object ArtworkCache {
    private val images = object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(key: String) = images.get(key)
    fun put(key: String, value: ImageBitmap) { images.put(key, value) }
    fun clear() = images.evictAll()
}

@Composable
internal fun CosmicBackground(lite: Boolean) {
    val context = LocalContext.current
    val wallpaper by produceState<ImageBitmap?>(null, lite) {
        value = if (lite) null else withContext(Dispatchers.IO) {
            val id = context.resources.getIdentifier("cosmic_wallpaper", "drawable", context.packageName)
            if (id == 0) null else runCatching {
                BitmapFactory.decodeResource(context.resources, id,
                    BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(Night)) {
        if (wallpaper != null) Image(wallpaper!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF102640), Night, Color.Black)))
            if (!lite) {
                val center = Offset(size.width * .8f, size.height * .16f)
                val radius = size.height * .24f
                drawCircle(Brush.radialGradient(listOf(Color(0xFF172B47), Color(0xFF000309)), center, radius), radius, center)
                rotate(-14f, center) {
                    listOf(Ice, Gold).forEachIndexed { i, color ->
                        drawOval(color.copy(alpha = .55f), Offset(size.width * (.53f - i * .01f), size.height * .08f),
                            Size(size.width * (.55f + i * .02f), size.height * (.13f + i * .02f)), style = Stroke(2f))
                    }
                }
                repeat(60) { i ->
                    drawCircle(Color.White.copy(alpha = .15f + (i % 4) * .09f), (i % 2 + 1).toFloat(),
                        Offset(size.width * ((i * 59 + 7) % 101) / 101f, size.height * ((i * 23 + 3) % 54) / 150f))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x05000000), Color(0x4802070E), Color(0xEF00030A)))))
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    lite: Boolean = false,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !lite) 1.035f else 1f, tween(if (lite) 0 else 120), label = "focus")
    val shape = RoundedCornerShape(13.dp)
    Box(modifier.scale(scale).clip(shape)
        .background(if (focused) Color(0xED1D3B56) else Glass)
        .border(if (focused) 2.dp else 1.dp, if (focused) Ice else Color(0x504E6F91), shape)
        .onFocusChanged { focused = it.isFocused; if (focused) onFocus() }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) { content(focused) }
}

@Composable
internal fun TvAction(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    autoFocus: Boolean = false,
    onClick: () -> Unit
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { requester.requestFocus() } }
    GlassCard(modifier.then(if (autoFocus) Modifier.focusRequester(requester) else Modifier), onClick = onClick) { focused ->
        Text(text, Modifier.padding(horizontal = 15.dp, vertical = 10.dp), color = if (selected || focused) Ice else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun AppCard(app: LaunchApp, lite: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onMenu: () -> Unit, onClick: () -> Unit) {
    val image = appArtwork(app.component)
    GlassCard(modifier.width(136.dp).height(88.dp).semantics { contentDescription = app.name }, lite, onFocus, onMenu, onClick) { focused ->
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(app.name.take(2).uppercase(), color = Ice, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xED010610)))))
        Text(app.name, Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (focused) Box(Modifier.fillMaxSize().border(2.dp, Ice, RoundedCornerShape(13.dp)))
    }
}

@Composable
internal fun SourceGlyph(kind: TvSourceKind, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.7.dp.toPx())
        when (kind) {
            TvSourceKind.HDMI -> {
                drawRoundRect(color, Offset(w * .1f, h * .35f), Size(w * .8f, h * .4f), androidx.compose.ui.geometry.CornerRadius(3f), style = stroke)
                repeat(4) { drawLine(color, Offset(w * (.25f + it * .16f), h * .47f), Offset(w * (.25f + it * .16f), h * .61f), 1.dp.toPx()) }
            }
            TvSourceKind.TV -> {
                drawRoundRect(color, Offset(w * .1f, h * .2f), Size(w * .8f, h * .55f), androidx.compose.ui.geometry.CornerRadius(3f), style = stroke)
                drawLine(color, Offset(w * .35f, h * .9f), Offset(w * .65f, h * .9f), 1.7.dp.toPx())
            }
            TvSourceKind.AV -> { drawCircle(color, w * .25f, Offset(w * .5f, h * .5f), style = stroke); drawCircle(color, w * .08f, Offset(w * .5f, h * .5f)) }
            TvSourceKind.USB -> {
                drawLine(color, Offset(w * .5f, h * .85f), Offset(w * .5f, h * .15f), 1.7.dp.toPx())
                drawLine(color, Offset(w * .5f, h * .55f), Offset(w * .25f, h * .35f), 1.7.dp.toPx())
                drawLine(color, Offset(w * .5f, h * .65f), Offset(w * .75f, h * .4f), 1.7.dp.toPx())
                drawCircle(color, w * .06f, Offset(w * .25f, h * .35f)); drawCircle(color, w * .07f, Offset(w * .5f, h * .85f))
                drawRect(color, Offset(w * .68f, h * .26f), Size(w * .14f, h * .14f))
                drawLine(color, Offset(w * .4f, h * .27f), Offset(w * .5f, h * .15f), 1.7.dp.toPx())
                drawLine(color, Offset(w * .6f, h * .27f), Offset(w * .5f, h * .15f), 1.7.dp.toPx())
            }
        }
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotEmpty()) Text(detail, color = Muted, fontSize = 11.sp)
    }
}

@Composable
internal fun InfoPanel(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass).border(1.dp, Color(0x304E6F91), RoundedCornerShape(12.dp)).padding(17.dp),
        color = Muted, fontSize = 13.sp)
}

@Composable
private fun appArtwork(component: ComponentName): ImageBitmap? {
    val context = LocalContext.current
    val key = component.flattenToString()
    val image by produceState(ArtworkCache.get(key), key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val banner = runCatching { pm.getActivityBanner(component) }.getOrNull()
                val drawable = banner ?: pm.getActivityIcon(component)
                val bitmap = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888)
                drawable.setBounds(if (banner == null) 84 else 0, if (banner == null) 25 else 0, if (banner == null) 172 else 256, if (banner == null) 113 else 144)
                drawable.draw(android.graphics.Canvas(bitmap))
                bitmap.asImageBitmap().also { ArtworkCache.put(key, it) }
            }.getOrNull()
        }
    }
    return image
}

@Composable
internal fun Poster(uri: String?, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(uri?.let(ArtworkCache::get), uri) {
        if (uri != null && value == null) value = withContext(Dispatchers.IO) { loadPoster(context, uri) }
    }
    if (image != null) Image(image!!, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF192C42), Color(0xFF090E19)))), contentAlignment = Alignment.Center) {
        Text("▶", color = Ice.copy(alpha = .5f), fontSize = 30.sp)
    }
}

private fun loadPoster(context: Context, source: String): ImageBitmap? = runCatching {
    val uri = Uri.parse(source)
    val bytes = when (uri.scheme) {
        "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() }
        "https" -> {
            val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(context.cacheDir, "launcher-temp/posters/$hash").apply { parentFile?.mkdirs() }
            if (file.isFile) file.readBytes() else {
                val connection = com.example.homeezch.data.HttpsConnection.open(source, 10000)
                try { connection.inputStream.use { it.readBytesLimited() }.also { data ->
                    // Keep the expendable image cache bounded on small TVs.
                    if ((file.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L) < 16 * 1024 * 1024) file.writeBytes(data)
                } } finally { connection.disconnect() }
            }
        }
        else -> null
    } ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 320 && bounds.outHeight / it <= 320 }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { ArtworkCache.put(source, it) }
}.getOrNull()

private fun java.io.InputStream.readBytesLimited(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; check(output.size() + count <= 4 * 1024 * 1024); output.write(buffer, 0, count) }
    return output.toByteArray()
}
