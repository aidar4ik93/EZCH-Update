package com.example.homeezch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ComponentName
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.CancellationException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val blue = Color(0xFF48B9FF)
private val panel = Color(0xFF131F31)

internal data class LaunchApp(
    val name: String,
    val component: ComponentName?
)

// Ограничиваем кеш изображений шестью мегабайтами.
private val artCache = object :
    LruCache<String, ImageBitmap>(6 * 1024 * 1024) {

    override fun sizeOf(
        key: String,
        value: ImageBitmap
    ): Int = value.width * value.height * 4
}

class MainActivity : ComponentActivity() {
    private val remotePress = RemoteButtonPress()
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        remotePress.handle(this, event, defaults = true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        remotePress.handle(this, event, defaults = true) || super.onKeyUp(keyCode, event)

    private val renderMonitor by lazy { LauncherRenderMonitor(this) }

    private var homeReset by mutableIntStateOf(0)
    private var homeMenu by mutableIntStateOf(0)
    private var voiceStart by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("ezch.voice.start", false)) voiceStart++
        if (intent.getBooleanExtra("ezch.home.menu", false)) homeMenu++ else homeReset++
    }
    private var sourceGeneration by mutableIntStateOf(0)
    private var appGeneration by mutableIntStateOf(0)

    private val inputManager by lazy { getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager }
    private var observing = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action?.startsWith("android.intent.action.PACKAGE_") == true) {
                appGeneration++
                artCache.evictAll()
            } else sourceGeneration++
        }
    }
    private val inputs = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) { sourceGeneration++ }
        override fun onInputRemoved(inputId: String) { sourceGeneration++ }
        override fun onInputStateChanged(inputId: String, state: Int) { sourceGeneration++ }
        override fun onInputUpdated(inputId: String) { sourceGeneration++ }
    }
    override fun onStart() {
        super.onStart()
        renderMonitor.start()
        runCatching { inputManager?.registerCallback(inputs, Handler(Looper.getMainLooper())) }
        // These broadcasts originate from the system, not from an app-owned action.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, changes, filter, ContextCompat.RECEIVER_EXPORTED)
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, changes, media, ContextCompat.RECEIVER_EXPORTED)
        val usb = IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, changes, usb, ContextCompat.RECEIVER_EXPORTED)
        observing = true
    }
    override fun onStop() {
        com.example.homeezch.phone.PhoneRemote.home = null
        remotePress.reset()
        renderMonitor.stop()
        if (observing) { unregisterReceiver(changes); observing = false }
        runCatching { inputManager?.unregisterCallback(inputs) }
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= 10) com.example.homeezch.ai.EvaEngine.release()
        if (level in 10..19 || level >= 40) {
            artCache.evictAll()
            renderMonitor.memoryPressure()
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.homeezch.phone.PhoneRemote.home = java.lang.ref.WeakReference(this)
        HomeStartup.shown(this)
        immersiveDesktop()
        sourceGeneration++
        appGeneration++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EzchDiag.init(this, "HOME")
        WeeklyCleanup.schedule(this)
        com.example.homeezch.phone.PhoneRemote.start(this)
        com.example.homeezch.ai.CinemaMode.start(this)
        if (intent.getBooleanExtra("ezch.voice.start", false)) voiceStart++
        if (intent.getBooleanExtra("ezch.home.menu", false)) homeMenu++ else homeReset++
        // Claude r24: the desktop and the store always paint an opaque wallpaper over the whole screen, so the
        // theme's light window background was one more full-screen fill for the GPU on every frame.
        window.setBackgroundDrawable(null)

        setContent {
            val profile by renderMonitor.profile.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalRenderProfile provides profile) {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Color(0xFF80B8FF), surface = Color(0xFF142137), background = Color(0xFF050A14)
            )) {
            var installedApps by remember {
                mutableStateOf<List<LaunchApp>>(emptyList())
            }

            var weatherReady by remember { mutableStateOf(false) }
            var appsReady by remember { mutableStateOf(false) }
            var storeOpen by remember { mutableStateOf(false) }
            val lastFocused = remember { FocusMemory() }
            val premium = profile.premium
            val storedOrder = remember { LauncherPrefs.loadOrder(this@MainActivity) }

            LaunchedEffect(appGeneration) {
                installedApps = withContext(Dispatchers.IO) {
                    findTvApps(packageManager, packageName)
                }
                appsReady = true
            }

            LaunchedEffect(homeReset) { if (homeReset > 0) { storeOpen = false; lastFocused.key = null } }
            LaunchedEffect(homeMenu) { if (homeMenu > 0) storeOpen = false }
            BackHandler(storeOpen) {
                storeOpen = false
            }

            if (storeOpen) {
                StoreScreen {
                    storeOpen = false
                }
            } else {

                val visibleApps = remember(installedApps, storedOrder) {
                    val all = installedApps + LaunchApp("Магазин EZCH", null) + LaunchApp("Файловый менеджер", ComponentName(this@MainActivity, FileBrowserActivity::class.java))
                    val byKey = all.associateBy { it.component?.flattenToString() ?: "ezch-store" }
                    orderedKeys(storedOrder, byKey.keys.toList()).mapNotNull(byKey::get)
                }

                HomeScreen(
                    apps = visibleApps, appsReady = appsReady, homeReset = homeReset, homeMenu = homeMenu, voiceStart = voiceStart,
                    consumeReset = { homeReset = 0 }, consumeMenu = { homeMenu = 0 },
                    weatherReady = weatherReady,
                    preferredFocus = lastFocused.key, onFocused = { lastFocused.key = it },
                    refreshSources = sourceGeneration,
                    premium = premium,
                    onPremiumChange = {
                        renderMonitor.refresh()
                    },
                    onLaunch = { app ->

                        val component = app.component

                        if (component == null) {
                            openUpdater(this@MainActivity)
                        } else {
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_MAIN).apply {
                                        this.component = component
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                                com.example.homeezch.ai.RecentApps.record(this@MainActivity, component.flattenToString())
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "Не удалось открыть ${app.name}", Toast.LENGTH_LONG).show()
                                appGeneration++
                            }
                        }
                    }
                )
            }
            LaunchSetup { weatherReady = true }
            }
            }
        }
    }
}

@Composable
internal fun AppTile(
    app: LaunchApp,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {

    var focused by remember { mutableStateOf(false) }

    val heavy = LocalRenderProfile.current.heavyEffects
    // Focus motion is a cheap RenderThread transform: animate it on every device (1.5–2 GB TVs too);
    // only the multi-ring glow is reduced on weak hardware. Snapping here made the desktop feel jerky.
    val scale by animateFloatAsState(if (focused) 1.075f else 1f, tween(150, easing = FastOutSlowInEasing), label = "appFocus")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(if (heavy) 220 else 160), label = "appHalo")
    val iconScale by animateFloatAsState(if (focused) 1.06f else 1f, tween(150, easing = FastOutSlowInEasing), label = "iconFocus")
    val image = rememberAppArtwork(app.component)
    val shape = AppCardShape

    Box(
        modifier = modifier
            .width(width * AppCardScale)
            .height(94.dp * AppCardScale)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusHalo({ glow })
            .clip(shape)
            .background(
                if (app.component == null) Color(0xFF07385F).copy(alpha = .4f) else panel.copy(alpha = .4f), shape
            )
            .border(
                if (focused) 1.5.dp else 1.dp,
                if (focused) Ice.copy(alpha = .8f) else Color(0xFF34455B),
                shape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (focused) onFocus()
            }
            .clickable { onClick() }
    ) {

        if (image != null) {

            Image(
                bitmap = image,
                contentDescription = app.name,
                contentScale = ContentScale.Fit,
                // Claude r24: scaled while drawing, not in its own layer: one RenderNode less per card on every frame.
                modifier = Modifier.fillMaxWidth().height(55.dp * AppCardScale).padding(top = 7.dp * AppCardScale)
                    .drawWithContent { val s = iconScale; scale(s, s) { this@drawWithContent.drawContent() } }
            )

        } else if (app.component == null) {
            // Logo is decoding in the background (one frame or two); keep the card light meanwhile.
            Spacer(Modifier.fillMaxWidth().height(55.dp * AppCardScale))
        } else {

            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (app.component == null) "▣" else "◈",
                    fontSize = 32.sp,
                    color = blue
                )
            }
        }

        // Same shaded rounded card without a separate clip layer (the brush is drawn with the card shape).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x66000713)
                        )
                    ),
                    shape
                )
        )

        Text(
            app.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (' ' !in app.name.trim() && app.name.length > 9) 10.sp else 11.sp,
            lineHeight = 12.sp,
            maxLines = if (' ' in app.name.trim()) 2 else 1,
            softWrap = ' ' in app.name.trim(),
            overflow = TextOverflow.Ellipsis,
            // Centered and inset past the oval corners: left-aligned labels were clipped («ift», «auncher»).
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth().padding(start = 11.dp * AppCardScale, end = 11.dp * AppCardScale, bottom = 7.dp * AppCardScale)
        )
    }
}

@Composable
internal fun StoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<List<StoreEntry>>(emptyList()) }
    var message by remember { mutableStateOf("Загрузка каталога GitHub…") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        message = "Загрузка каталога…"
        try { catalog = fetchStoreCatalog(); message = "Приложений: ${catalog.size}" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "Каталог недоступен. Проверьте сеть и повторите загрузку." }
    }
    Box(Modifier.fillMaxSize()) {
        CosmicBackground()
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Text("EZCH Store", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color.LightGray, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlChip("← На главную", onBack)
                ControlChip("Обновить каталог") { retry++ }
                ControlChip("Открыть установщик") { openUpdater(context) }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                catalog.forEach { entry ->
                    var focused by remember(entry.packageName) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(Color(0xAE172A43), RoundedCornerShape(12.dp))
                            .border(if (focused) 4.dp else 1.dp,
                                if (focused) blue else Color(0xFF40566D), RoundedCornerShape(12.dp))
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { openUpdater(context, entry.packageName) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = Color.White, fontSize = 18.sp)
                            Text("Версия ${entry.versionName}", color = Color.LightGray, fontSize = 13.sp)
                        }
                        Text("Выбрать", color = blue)
                    }
                }
                Text("Установка через EZCH Update. Кнопка Назад вернёт в рабочий стол.", color = Color.LightGray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun CosmicBackground(premium: Boolean = true) { LiveWallpaper(premium) }

@Composable
private fun rememberAppArtwork(
    component: ComponentName?
): ImageBitmap? {

    val context = LocalContext.current
    val pm = context.packageManager
    val key = component?.flattenToString() ?: "ezch-store"

    val artwork by produceState<ImageBitmap?>(
        initialValue = artCache.get(key),
        key1 = key
    ) {

        // The EZCH store logo is a 1024×1024 PNG: decode it once, off the main thread, at 128 px.
        // painterResource() decoded the full 4 MB bitmap in composition for every card — a scroll stall.
        if (component == null && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ezch_brand,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()?.also { artCache.put(key, it) }
                }.getOrNull()
            }
        }
        if (component != null && value == null) {

            value = withContext(Dispatchers.IO) {

                runCatching {

                    val banner: android.graphics.drawable.Drawable? = null
                    val drawable =
                        banner ?: pm.getActivityIcon(component)

                    val bitmap = Bitmap.createBitmap(
                        128,
                        128,
                        Bitmap.Config.ARGB_8888
                    )

                    val canvas = android.graphics.Canvas(bitmap)

                    drawable.mutate()

                    if (banner != null) {
                        drawable.setBounds(0, 0, 256, 144)
                    } else {
                        drawable.setBounds(0, 0, 128, 128)
                    }

                    drawable.draw(canvas)
                    // Upload the texture now (background), not on the first frame the card scrolls into view.
                    bitmap.prepareToDraw()

                    bitmap.asImageBitmap().also {
                        artCache.put(key, it)
                    }

                }.getOrNull()
            }
        }
    }

    return artwork
}

@Suppress("DEPRECATION")
internal fun findTvApps(
    pm: PackageManager,
    ownPackage: String
): List<LaunchApp> {

    val result = linkedMapOf<String, LaunchApp>()

    listOf(
        Intent.CATEGORY_LEANBACK_LAUNCHER,
        Intent.CATEGORY_LAUNCHER
    ).forEach { category ->

        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(category)

        pm.queryIntentActivities(query, 0).forEach { info ->

            val activity = info.activityInfo

            if (
                activity.exported &&
                activity.packageName != ownPackage &&
                !result.containsKey(activity.packageName)
            ) {

                result[activity.packageName] = LaunchApp(
                    info.loadLabel(pm).toString(),
                    ComponentName(
                        activity.packageName,
                        activity.name
                    )
                )
            }
        }
    }

    val priority = mapOf(
        "serialtrend" to 1,
        "лайт hd tv" to 2,
        "panda vision" to 3,
        "lift" to 4
    )

    return result.values.sortedWith(
        compareBy<LaunchApp> {
            priority[it.name.lowercase()] ?: 100
        }.thenBy {
            it.name.lowercase()
        }
    )
}
