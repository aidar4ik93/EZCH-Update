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

    private var homeReset by mutableIntStateOf(0)
    private var homeMenu by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        if (observing) { unregisterReceiver(changes); observing = false }
        runCatching { inputManager?.unregisterCallback(inputs) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        immersiveDesktop()
        sourceGeneration++
        appGeneration++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WeeklyCleanup.schedule(this)
        if (intent.getBooleanExtra("ezch.home.menu", false)) homeMenu++ else homeReset++

        setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Color(0xFF80B8FF), surface = Color(0xFF142137), background = Color(0xFF050A14)
            )) {
            var installedApps by remember {
                mutableStateOf<List<LaunchApp>>(emptyList())
            }

            var weatherReady by remember { mutableStateOf(false) }
            var appsReady by remember { mutableStateOf(false) }
            var storeOpen by remember { mutableStateOf(false) }
            var lastFocused by remember { mutableStateOf<String?>(null) }
            var premium by remember { mutableStateOf(LauncherPrefs.isPremium(this@MainActivity)) }
            val storedOrder = remember { LauncherPrefs.loadOrder(this@MainActivity) }

            LaunchedEffect(appGeneration) {
                installedApps = withContext(Dispatchers.IO) {
                    findTvApps(packageManager, packageName)
                }
                appsReady = true
            }

            LaunchedEffect(homeReset) { if (homeReset > 0) { storeOpen = false; lastFocused = null } }
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
                    val all = installedApps + LaunchApp("EZCH Store", null) + LaunchApp("Файловый менеджер", ComponentName(this@MainActivity, FileBrowserActivity::class.java))
                    val byKey = all.associateBy { it.component?.flattenToString() ?: "ezch-store" }
                    orderedKeys(storedOrder, byKey.keys.toList()).mapNotNull(byKey::get)
                }

                HomeScreen(
                    apps = visibleApps, appsReady = appsReady, homeReset = homeReset, homeMenu = homeMenu,
                    consumeReset = { homeReset = 0 }, consumeMenu = { homeMenu = 0 },
                    weatherReady = weatherReady,
                    preferredFocus = lastFocused, onFocused = { lastFocused = it },
                    refreshSources = sourceGeneration,
                    premium = premium,
                    onPremiumChange = {
                        premium = it
                        LauncherPrefs.setPremium(this@MainActivity, it)
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

@Composable
internal fun AppTile(
    app: LaunchApp,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {

    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(170), label = "appFocus")
    val image = rememberAppArtwork(app.component)
    val shape = DesktopShape

    Box(
        modifier = modifier
            .width(width)
            .height(94.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(
                if (focused) Color(0xFF22466D) else if (app.component == null) Color(0xFF07385F) else panel, shape
            )
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) blue else Color(0xFF34455B),
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
                modifier = Modifier.fillMaxWidth().height(62.dp).padding(top = 9.dp)
            )

        } else {

            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (app.component == null) "▣" else "◈",
                    fontSize = 38.sp,
                    color = blue
                )
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xF0000713)
                        )
                    )
                )
        )

        Text(
            app.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
    }
}

@Composable
internal fun PortTile(
    title: String,
    width: Dp,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    connected: Boolean = false,
    status: String? = null,
    kind: TvSourceKind? = null,
    modifier: Modifier = Modifier
) {

    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(170), label = "portFocus")
    val pulse = remember { Animatable(0f) }
    val connectionGlow by animateFloatAsState(if (connected) .32f else .14f, tween(900), label = "connectionGlow")
    var previous by remember { mutableStateOf(connected) }
    val accent = if (kind == TvSourceKind.HDMI && Regex("(?:^|\\D)2(?:\\D|$)").containsMatchIn(title)) Gold else Ice
    LaunchedEffect(connected) {
        if (connected && !previous) {
            pulse.animateTo(0.65f, tween(450))
            pulse.animateTo(0f, tween(2200))
        } else pulse.snapTo(0f)
        previous = connected
    }

    Column(
        modifier = modifier
            .width(width)
            .height(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(DesktopShape)
            .background(
                Brush.verticalGradient(listOf(
                    accent.copy(alpha = connectionGlow + pulse.value * 0.45f),
                    Color(0xB0081222))),
                DesktopShape
            )
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) blue else accent.copy(alpha = if (connected) 0.75f else 0.32f),
                DesktopShape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (focused) onFocus()
            }
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        GlassPortGlyph(kind ?: TvSourceKind.TV, accent, if (connected) .45f + pulse.value else 0f, Modifier.width(64.dp).height(36.dp))
        Spacer(Modifier.height(5.dp))

        Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 12.sp, color = Color.White, maxLines = 1)
            if (connected) Text("● Подключено", fontSize = 9.sp, color = Color(0xFF93F5D0))
            if (!status.isNullOrBlank()) Text(
                if (status == "Состояние неизвестно") "Нет данных" else status, fontSize = 9.sp,
                color = if (connected) Color(0xFF93F5D0) else Color.LightGray,
                maxLines = 1
            )
        }
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

    val pm = LocalContext.current.packageManager
    val key = component?.flattenToString() ?: "ezch-store"

    val artwork by produceState<ImageBitmap?>(
        initialValue = component?.let { artCache.get(key) },
        key1 = key
    ) {

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
