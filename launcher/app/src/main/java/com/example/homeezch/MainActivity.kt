package com.example.homeezch

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var generation by mutableIntStateOf(0)
    private var homeRequest by mutableIntStateOf(0)
    private var menuRequest by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); generation++ }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == APP_MENU) menuRequest++ else homeRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ice, background = Night)) {
                Home(generation, homeRequest, menuRequest,
                    { startActivity(Intent(this, UpdaterActivity::class.java)) },
                    { startActivity(Intent(this, FileBrowserActivity::class.java)) })
            }
        }
    }
    private fun chooseHome() {
        val requested = if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            roles?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) && !it.isRoleHeld(RoleManager.ROLE_HOME) }
                ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else null
        runCatching { startActivity(requested ?: Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }
    @Composable private fun Home(generation: Int, home: Int, menu: Int, openStore: () -> Unit, openFiles: () -> Unit) {
        val preferences = remember { LauncherPreferences(this) }
        var apps by remember { mutableStateOf<List<LaunchApp>>(emptyList()) }
        var hidden by remember { mutableStateOf(preferences.hiddenPackages) }
        var order by remember { mutableStateOf(preferences.appOrder) }
        var showAll by remember { mutableStateOf(false) }
        var setup by remember { mutableStateOf(!getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("seen", false)) }
        var selected by remember { mutableStateOf<LaunchApp?>(null) }
        var edited by remember { mutableStateOf<LaunchApp?>(null) }
        var moveMode by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var clock by remember { mutableStateOf("") }
        var sourceRevision by remember { mutableIntStateOf(0) }
        var sourcePrevious by remember { mutableStateOf<Map<String, Boolean?>?>(null) }
        var sourceHighlights by remember { mutableStateOf<Set<String>>(emptySet()) }
        var usbMenu by remember { mutableStateOf(false) }
        var sourceSnapshot by remember { mutableStateOf<TvSourcesSnapshot?>(null) }
        DisposableEffect(Unit) { val stop = observeTvSources(this@MainActivity) { sourceRevision++ }; onDispose(stop) }
        LaunchedEffect(generation, sourceRevision) {
            val snapshot = withContext(Dispatchers.IO) { scanTvSources(this@MainActivity) }
            sourceHighlights = (sourceHighlights + newlyConnected(sourcePrevious, snapshot.entries))
                .intersect(snapshot.entries.filter { it.connected == true }.map { it.id }.toSet())
            sourcePrevious = snapshot.entries.associate { it.id to it.connected }
            sourceSnapshot = snapshot
        }
        LaunchedEffect(sourceHighlights) { if (sourceHighlights.isNotEmpty()) { delay(4000); sourceHighlights = emptySet() } }
        val scroll = rememberScrollState()
        val appScroll = rememberLazyListState()
        val first = remember { FocusRequester() }
        val ordered = remember(apps, order, hidden, showAll) {
            val byPackage = apps.associateBy { it.packageName }
            LauncherPolicy.orderPackages(apps.map { it.packageName }, order).mapNotNull(byPackage::get)
                .filter { showAll || it.packageName !in hidden }
        }
        LaunchedEffect(generation) { apps = withContext(Dispatchers.IO) { findTvApps(packageManager, packageName) } }
        LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(1000) } }
        LaunchedEffect(home) {
            if (home == 0) return@LaunchedEffect
            edited = null; moveMode = false; setup = false; showAll = false; usbMenu = false
            scroll.scrollTo(0); appScroll.scrollToItem(0); delay(150); runCatching { first.requestFocus() }
        }
        LaunchedEffect(menu) { if (menu > 0) { edited = selected ?: ordered.firstOrNull(); moveMode = false } }
        LaunchedEffect(ordered.firstOrNull()?.packageName, edited, setup) {
            if (edited == null && !setup) { delay(100); runCatching { first.requestFocus() } }
        }
        BackHandler(showAll) { showAll = false }
        Box(Modifier.fillMaxSize()) {
            CosmicBackground(preferences.lite)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(36.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("EZCH", color = Color.White, fontSize = 28.sp)
                    Spacer(Modifier.weight(1f))
                    Text(clock, color = Color.White, fontSize = 30.sp)
                    Spacer(Modifier.weight(1f))
                    TvAction("Настройка") { setup = true }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvAction("EZCH Update", onClick = openStore)
                    TvAction("Файловый менеджер", onClick = openFiles)
                    TvAction(if (showAll) "Рабочий стол" else "Все приложения") { showAll = !showAll }
                }
                Spacer(Modifier.height(18.dp))
                SectionHeading(if (showAll) "Все приложения" else "Приложения", "Долгое OK: переместить / скрыть")
                if (ordered.isEmpty()) InfoPanel("Здесь появятся установленные приложения. Откройте EZCH Update для установки.")
                LazyRow(state = appScroll, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(5.dp)) {
                    items(ordered, key = { it.packageName }) { app ->
                        Column {
                            AppCard(app, preferences.lite,
                                modifier = if (app == ordered.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                                onFocus = { selected = app }, onMenu = { selected = app; edited = app; moveMode = false },
                                onClick = {
                                    runCatching { startActivity(Intent(Intent.ACTION_MAIN).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); preferences.recordLaunch(app.packageName) }
                                        .onFailure { message = "Не удалось открыть ${app.name}" }
                                })
                            if (app.packageName in hidden) Text("Скрыто", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                message?.let { InfoPanel(it) }
                Spacer(Modifier.height(18.dp))
                sourceSnapshot?.let { snapshot ->
                    SectionHeading("Источники")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(snapshot.entries, key = { it.id }) { source -> GlassSourceCard(source, source.id in sourceHighlights) {
                            if (source.kind == TvSourceKind.USB) usbMenu = true
                            else if (!openTvSource(this@MainActivity, source)) message = "Источник недоступен на этом телевизоре"
                        } }
                    }
                }
                Spacer(Modifier.height(20.dp))
                val recommendations by produceState<RecommendationsSnapshot?>(null, generation) { value = withContext(Dispatchers.IO) { readRecommendations(this@MainActivity) } }
                recommendations?.let { snapshot ->
                    if (snapshot.watchNext.isNotEmpty()) {
                        SectionHeading("Продолжить просмотр")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(snapshot.watchNext, key = { it.id }) { card ->
                                GlassCard(Modifier.width(180.dp).height(100.dp), onClick = { if (!openMediaCard(this@MainActivity, card)) message = "Не удалось открыть фильм" }) {
                                    Poster(card.posterUri, Modifier.fillMaxSize())
                                    Text(card.title, Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (usbMenu) AlertDialog(onDismissRequest = { usbMenu = false }, title = { Text("USB / накопители") },
            text = { Column {
                val volumes = sourceSnapshot?.usbVolumes.orEmpty()
                if (volumes.isEmpty()) Text("Накопители не обнаружены. Устройства без файлового хранилища отмечаются как подключённые.")
                volumes.forEach { volume -> Text(volume.getDescription(this@MainActivity), modifier = Modifier.padding(vertical = 4.dp)) }
                Text("Доступ к файлам открывается во встроенном менеджере.")
            } }, confirmButton = { TextButton(onClick = { usbMenu = false; openFiles() }) { Text("Открыть файлы") } },
            dismissButton = { TextButton(onClick = { usbMenu = false }) { Text("Закрыть") } })
        edited?.let { app ->
            AlertDialog(onDismissRequest = { edited = null; moveMode = false }, title = { Text(app.name) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (moveMode) {
                        Text("Выберите новое положение, затем нажмите «Готово»")
                        fun move(delta: Int) {
                            val packages = LauncherPolicy.orderPackages(apps.map { it.packageName }, order).toMutableList()
                            val from = packages.indexOf(app.packageName)
                            val to = (from + delta).coerceIn(0, packages.lastIndex)
                            if (from >= 0) { packages.removeAt(from); packages.add(to, app.packageName); order = packages; preferences.appOrder = packages }
                        }
                        Row { TextButton(onClick = { move(-1) }) { Text("Влево") }; TextButton(onClick = { move(1) }) { Text("Вправо") } }
                    } else {
                        TextButton(onClick = { moveMode = true }) { Text("Переместить") }
                        TextButton(onClick = {
                            hidden = if (app.packageName in hidden) hidden - app.packageName else hidden + app.packageName
                            preferences.hiddenPackages = hidden; edited = null
                        }) { Text(if (app.packageName in hidden) "Показать на рабочем столе" else "Скрыть") }
                    }
                } }, confirmButton = { TextButton(onClick = { edited = null; moveMode = false }) { Text("Готово") } })
        }
        if (setup) AlertDialog(onDismissRequest = { setup = false }, title = { Text("Первый запуск") },
            text = { Column {
                Text("Файловый менеджер и EZCH Update уже встроены. Выберите EZCH домашним экраном. Если ТВ не предлагает выбор, включите обработку Home в настройках ниже.")
                TextButton(onClick = ::chooseHome) { Text("Выбрать домашний экран") }
                TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { message = "Настройки кнопки Home недоступны" } }) { Text("Настроить кнопку Home") }
                Text("Долгое OK открывает действия карточки. Скрытые приложения доступны в «Все приложения».")
            } }, confirmButton = { TextButton(onClick = { getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("seen", true).apply(); setup = false }) { Text("Начать") } })
    }
    companion object { const val SHOW_HOME = "com.example.homeezch.SHOW_HOME"; const val APP_MENU = "com.example.homeezch.APP_MENU" }
}
