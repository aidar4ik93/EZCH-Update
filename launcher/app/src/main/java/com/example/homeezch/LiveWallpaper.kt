package com.example.homeezch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

internal enum class WallpaperMode { AUTO, PREMIUM, LITE }
internal object VideoDiagnostics { val activePlayers = java.util.concurrent.atomic.AtomicInteger(0) }
internal data class WallpaperChoice(val mode: WallpaperMode = WallpaperMode.AUTO, val moving: Boolean = false, val video: String? = null)
internal object WallpaperSettings {
    private const val FILE = "wallpaper"
    fun load(context: Context): WallpaperChoice {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val fallback = if (context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE).getBoolean("lite", false)) "LITE" else "AUTO"
        return WallpaperChoice(runCatching { WallpaperMode.valueOf(p.getString("mode", fallback)!!) }.getOrDefault(WallpaperMode.AUTO),
            p.getBoolean("moving", false), p.getString("video", null))
    }
    fun save(context: Context, value: WallpaperChoice) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        .putString("mode", value.mode.name).putBoolean("moving", value.moving).putString("video", value.video).apply()
}

@Composable internal fun WallpaperControls(onChange: () -> Unit) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(WallpaperSettings.load(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(next: WallpaperChoice) { choice = next; WallpaperSettings.save(context, next); onChange() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            save(choice.copy(moving = true, video = uri.toString()))
            error = null
        }.onFailure { error = "Видео недоступно. Выберите файл во внутренней памяти или на USB." }
    }
    TextButton(onClick = { save(choice.copy(mode = WallpaperMode.entries[(choice.mode.ordinal + 1) % 3])) }) {
        Text("Режим: " + when (choice.mode) { WallpaperMode.AUTO -> "Авто"; WallpaperMode.PREMIUM -> "Premium"; WallpaperMode.LITE -> "Lite · чёрный фон" })
    }
    TextButton(onClick = { save(choice.copy(moving = true, video = null)) }) { Text("Живые обои · Космос") }
    TextButton(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Выбрать видеообои / USB") }
    TextButton(onClick = { save(choice.copy(moving = false, video = null)) }) { Text("Вернуть исходный фон") }
    error?.let { Text(it) }
}

internal fun shouldUseLite(mode: WallpaperMode, lowRam: Boolean, savingPower: Boolean) =
    mode == WallpaperMode.LITE || (mode == WallpaperMode.AUTO && (lowRam || savingPower))

@Composable internal fun LiveWallpaper(premium: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var choice by remember { mutableStateOf(WallpaperSettings.load(context)) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    var powerSaving by remember { mutableStateOf(false) }
    val memory = remember { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    val lowRam = remember { val info = ActivityManager.MemoryInfo(); memory.getMemoryInfo(info); memory.isLowRamDevice || info.totalMem < 2L * 1024 * 1024 * 1024 }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            choice = WallpaperSettings.load(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> choice = WallpaperSettings.load(context) }
        val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val info = ActivityManager.MemoryInfo(); memory.getMemoryInfo(info)
            powerSaving = power.isPowerSaveMode || info.lowMemory || (android.os.Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            delay(5000)
        }
    }
    if (!premium || shouldUseLite(choice.mode, lowRam, powerSaving)) {
        Box(Modifier.fillMaxSize().background(Color.Black)); return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (choice.moving && choice.video == null && resumed && !powerSaving) {
            val transition = rememberInfiniteTransition(label = "cosmos")
            val motion by transition.animateFloat(1f, 1.025f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "slowOrbit")
            Image(painterResource(R.drawable.cosmic_wallpaper), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = motion; scaleY = motion })
        } else Image(painterResource(R.drawable.cosmic_wallpaper), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (choice.moving && choice.video != null && resumed && !powerSaving) key(choice.video, resumeGeneration) {
            VideoLayer(Uri.parse(choice.video))
        }
        Box(Modifier.fillMaxSize().background(Color(0x3301050C)))
    }
}

/** Owns decoder and Surface only while HOME is resumed. Static wallpaper stays under the first frame. */
@Composable private fun VideoLayer(uri: Uri) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    fun release() {
        val owned = player; player = null
        if (owned != null) { runCatching { owned.release() }; VideoDiagnostics.activePlayers.decrementAndGet() }
        surface?.release(); surface = null
    }
    val timeout = remember(uri) { Runnable { if (!ready) { failed = true; release() } } }
    DisposableEffect(lifecycle, uri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                handler.removeCallbacks(timeout); ready = false; release()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(uri) { onDispose { handler.removeCallbacks(timeout); release() } }
    LaunchedEffect(failed) { if (failed) android.widget.Toast.makeText(context, "Видео недоступно — восстановлен исходный фон", android.widget.Toast.LENGTH_LONG).show() }
    if (failed) return
    AndroidView(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (ready) 1f else 0f }, factory = {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                fun crop() {
                    val p = player ?: return
                    if (p.videoWidth == 0 || p.videoHeight == 0 || width == 0 || height == 0) return
                    val ratio = p.videoWidth.toFloat() / p.videoHeight
                    val screen = width.toFloat() / height
                    setTransform(Matrix().apply { setScale(if (ratio > screen) ratio / screen else 1f, if (ratio < screen) screen / ratio else 1f, width / 2f, height / 2f) })
                }
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    runCatching {
                        surface = Surface(texture)
                        val p = MediaPlayer()
                        player = p; VideoDiagnostics.activePlayers.incrementAndGet()
                            p.setSurface(surface); p.setVolume(0f, 0f); p.isLooping = true
                            p.setOnPreparedListener { crop(); it.start() }
                            p.setOnVideoSizeChangedListener { _, _, _ -> crop() }
                            p.setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { ready = true; handler.removeCallbacks(timeout) }; false }
                            p.setOnErrorListener { _, _, _ -> failed = true; release(); true }
                            p.setDataSource(context, uri); p.prepareAsync()
                        handler.postDelayed(timeout, 12000)
                    }.onFailure { failed = true; release() }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { handler.removeCallbacks(timeout); release(); return true }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }
        }
    })
}
