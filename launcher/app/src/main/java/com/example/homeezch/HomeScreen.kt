package com.example.homeezch

import android.content.Intent
import android.view.KeyEvent as NativeKey
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CardMenu(val key: String, val name: String, val source: Boolean)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun HomeScreen(
    apps: List<LaunchApp>, refreshSources: Int, premium: Boolean,
    onPremiumChange: (Boolean) -> Unit,
    onLaunch: (LaunchApp) -> Unit,
    preferredFocus: String? = null, onFocused: (String) -> Unit = {},
    weatherReady: Boolean = true, appsReady: Boolean = true, homeReset: Int = 0, homeMenu: Int = 0,
    consumeReset: () -> Unit = {}, consumeMenu: () -> Unit = {}
) {
    val context = LocalContext.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val desktopScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun keyOf(app: LaunchApp) = app.component?.packageName ?: "ezch-store"
    val byKey = remember(apps) { apps.associateBy(::keyOf) }
    var saved by remember { mutableStateOf(WorkspacePrefs.load(context).discover(byKey.keys.toList())) }
    var draft by remember { mutableStateOf(saved) }
    var editing by remember { mutableStateOf<CardMenu?>(null) }
    var menu by remember { mutableStateOf<CardMenu?>(null) }
    var saving by remember { mutableStateOf(false) }
    var launcherSettings by remember { mutableStateOf(false) }
    var sourcesPanel by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf(false) }
    var addRow by remember { mutableStateOf(false) }
    var rowTitle by remember { mutableStateOf("") }
    var sourceSnapshot by remember { mutableStateOf(TvSourcesSnapshot(emptyList(), emptyList())) }
    var usbExpanded by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    var focusRevision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    val settingsTrigger = remember { FocusRequester() }
    val sourcesTrigger = remember { FocusRequester() }
    val settingsFirst = remember { FocusRequester() }
    val sourcesFirst = remember { FocusRequester() }
    val sourceList = rememberLazyListState()
    val sourceIds = remember(sourceSnapshot) { sourceSnapshot.entries.map { it.id } }
    val press = remember { CenterPress() }
    var pendingHorizontal by remember { mutableStateOf<String?>(null) }
    val horizontalPress = remember { HorizontalPress() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    fun onCardFocus(key: String) { if (pendingHorizontal == key) pendingHorizontal = null; if (focusedKey != key) { press.reset(); focusedKey = key; onFocused(key) } }

    fun request(key: String) { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard); focusTarget = key; focusRevision++ }
    fun commit(next: Workspace, after: () -> Unit = {}) {
        if (saving) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { WorkspacePrefs.save(context, next) }.getOrDefault(false) }
            saving = false
            if (ok) { saved = next; draft = next; editing = null; press.reset(); after() }
            else Toast.makeText(context, "Не удалось сохранить порядок. Повторите или отмените.", Toast.LENGTH_LONG).show()
        }
    }
    fun begin(card: CardMenu) { draft = saved; editing = card; menu = null; notice = ""; press.reset(); request(card.key) }
    fun openMenu(card: CardMenu) {
        if (saving) return
        editing = null; draft = saved; menu = card
    }
    fun confirm() { val card = editing ?: return; commit(draft) { request(card.key) } }
    fun move(code: Int) {
        val card = editing ?: return
        val horizontal = when (code) { NativeKey.KEYCODE_DPAD_LEFT -> -1; NativeKey.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val vertical = when (code) { NativeKey.KEYCODE_DPAD_UP -> -1; NativeKey.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        if (horizontal == 0 && vertical == 0) return
        val next = if (card.source) draft.moveSource(card.key, horizontal, sourceIds)
            else draft.moveApp(card.key, horizontal, vertical, byKey.keys)
        if (next != draft) { draft = next; request(card.key) }
    }
    fun cancel() { val card = editing; draft = saved; editing = null; press.reset(); card?.let { request(it.key) } }

    LaunchedEffect(apps) {
        val reconciled = saved.discover(byKey.keys.toList())
        saved = reconciled
        if (editing == null) draft = reconciled else {
            draft = draft.discover(byKey.keys.toList())
            if (editing?.source == false && editing?.key !in byKey) { editing = null; draft = reconciled }
        }
    }
    LaunchedEffect(refreshSources) {
        sourceSnapshot = withContext(Dispatchers.IO) { scanTvSources(context) }
        if (sourceSnapshot.usbVolumes.isEmpty()) usbExpanded = false
        if (context.getSharedPreferences("home_setup", android.content.Context.MODE_PRIVATE).getBoolean("setup_reviewed_v24", false)) {
            sourceSnapshot.entries.firstOrNull { it.kind == TvSourceKind.TV && !it.id.startsWith("manual-") }?.let { tuner ->
                if (SourceSettings.consumeTunerStart(context) && !SettingsRouter.source(context, tuner)) notice = "Автозапуск ТВ ограничен прошивкой"
            }
        }
        if (editing?.source == true && sourceSnapshot.entries.none { it.id == editing?.key }) {
            editing = null; draft = saved; press.reset()
            saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }?.let(::request)
        }
    }
    LaunchedEffect(appsReady, byKey.isNotEmpty()) {
        if (appsReady && byKey.isNotEmpty()) {
            withFrameNanos { }
            val visible = draft.rows.flatMap { it.appKeys }.filter { it in byKey && it !in draft.hiddenApps }
            (preferredFocus?.takeIf { it in visible } ?: visible.firstOrNull())?.let(::request)
        }
    }
    LaunchedEffect(homeReset, appsReady, saving) {
        if (homeReset > 0 && appsReady && !saving) {
            menu = null; launcherSettings = false; sourcesPanel = false; allApps = false; addRow = false
            editing = null; draft = saved; usbExpanded = false; press.reset(); pendingHorizontal = null
            desktopScroll.scrollTo(0)
            val row = saved.rows.first()
            request(row.appKeys.firstOrNull { it in byKey && it !in saved.hiddenApps } ?: "all-apps-${row.id}")
            withFrameNanos { }; consumeReset()
        }
    }
    LaunchedEffect(homeMenu, appsReady, saving) {
        if (homeMenu > 0 && appsReady && !saving && !launcherSettings && !sourcesPanel) {
            val selected = editing?.key ?: focusedKey ?: preferredFocus ?: saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }
            editing = null; draft = saved
            byKey[selected]?.let { menu = CardMenu(selected!!, it.name, false) }
            sourceSnapshot.entries.firstOrNull { it.id == selected }?.let { menu = CardMenu(it.id, it.label, true) }
            consumeMenu()
        }
    }
    // Tiles scroll themselves into view and then reattach the same logical focus target.
    BackHandler(!launcherSettings && !sourcesPanel && !allApps && menu == null && !addRow) {
        if (editing != null && !saving) cancel()
    }

    val shown = if (editing == null) saved else draft
    val visibleSources = remember(sourceSnapshot, shown.sourceOrder, shown.hiddenSources) {
        val sourceById = sourceSnapshot.entries.associateBy { it.id }
        (shown.sourceOrder + sourceIds).distinct().filterNot { it in shown.hiddenSources }.mapNotNull(sourceById::get)
    }
    fun closeSettings() {
        launcherSettings = false
        scope.launch { withFrameNanos { }; settingsTrigger.requestFocus() }
    }
    fun closeSources() {
        if (editing?.source == true) { draft = saved; editing = null; press.reset() }
        menu = null
        sourcesPanel = false
        scope.launch { withFrameNanos { }; sourcesTrigger.requestFocus() }
    }
    fun showSettings() {
        if (editing != null || saving) return
        press.reset(); menu = null; sourcesPanel = false; launcherSettings = true
    }
    fun showSources() {
        if (editing != null || saving) return
        if (sourcesPanel) { closeSources(); return }
        press.reset(); menu = null; launcherSettings = false; sourcesPanel = true
    }
    BackHandler(sourcesPanel && menu == null) {
        if (editing?.source == true) { if (!saving) cancel() } else closeSources()
    }
    LaunchedEffect(focusRevision, sourcesPanel) {
        if (sourcesPanel) {
            val index = visibleSources.indexOfFirst { it.id == focusTarget }
            if (index >= 0) {
                sourceList.scrollToItem(index)
                withFrameNanos { }
                requesters[focusTarget]?.requestFocus()
            }
        }
    }
    fun input(card: CardMenu, click: () -> Unit): Modifier = Modifier
        .onGloballyPositioned { cardBounds[card.key] = it.boundsInWindow() }
        .focusRequester(requesters.getOrPut(card.key) { FocusRequester() })
        .remoteInput(moving = editing?.key == card.key, blocked = saving || (editing != null && editing?.key != card.key), press = press,
            onClick = click, onLong = { openMenu(card) }, onMenu = { openMenu(card) },
            onConfirm = ::confirm, onMove = ::move)

    fun rowNavigation(keys: List<String>): Modifier = Modifier.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val direction = when (key.keyCode) {
            NativeKey.KEYCODE_DPAD_LEFT -> -1
            NativeKey.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (direction == 0 || editing != null) {
            if (editing == null && key.action == NativeKey.ACTION_DOWN &&
                key.keyCode in listOf(NativeKey.KEYCODE_DPAD_UP, NativeKey.KEYCODE_DPAD_DOWN)) {
                pendingHorizontal = null
                focusTarget = null
                focusRevision++
            }
            false
        } else {
            if (!saving && pendingHorizontal == null && key.action == NativeKey.ACTION_DOWN &&
                horizontalPress.accept(key.eventTime, key.repeatCount)) {
                // Do not advance from an unconfirmed destination: that skips cards.
                val current = focusedKey
                val index = keys.indexOf(current)
                if (index >= 0) {
                    val next = keys[(index + direction).coerceIn(0, keys.lastIndex)]
                    if (next != current) { pendingHorizontal = next; request(next) }
                }
            }
            true // Horizontal events never escape this shelf, including at either end.
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CosmicBackground(premium)
        val desktopHeight = maxHeight
        val cardWidth = ((maxWidth - 64.dp - 12.dp * 7) / 8).coerceIn(88.dp, 125.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
            TopBar(refreshSources, weatherReady, homeReset, settingsModifier = Modifier.focusRequester(settingsTrigger), onSettings = ::showSettings)
            Spacer(Modifier.height((desktopHeight * .19f).coerceIn(42.dp, 110.dp)))
            Column(Modifier.weight(1f).verticalScroll(desktopScroll)) {
                shown.rows.forEach { row ->
                    key(row.id) {
                        val rowApps = remember(row, byKey, shown.hiddenApps) { row.appKeys.mapNotNull(byKey::get).filter { keyOf(it) !in shown.hiddenApps } }
                        Text(row.title, color = Color.White, fontSize = 19.sp)
                        val listState = rememberLazyListState()
                        LaunchedEffect(focusRevision) {
                            val rowKeys = rowApps.map(::keyOf) + "all-apps-${row.id}"
                            val target = focusTarget
                            val index = rowKeys.indexOf(target)
                            if (index >= 0) {
                                try {
                                    listState.revealCard(index)
                                    withFrameNanos { }
                                    runCatching { requesters[target]?.requestFocus() }
                                } finally {
                                    if (pendingHorizontal == target) pendingHorizontal = null
                                }
                            }
                        }
                        val addRequester = remember(row.id) { requesters.getOrPut("all-apps-${row.id}") { FocusRequester() } }
                        LazyRow(modifier = rowNavigation(rowApps.map(::keyOf) + "all-apps-${row.id}"), state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp)) {
                            items(rowApps, key = ::keyOf) { app ->
                                val card = CardMenu(keyOf(app), app.name, false)
                                AppTile(app, cardWidth, input(card) { onLaunch(app) }.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(160)), onFocus = { onCardFocus(card.key) },
                                    onClick = { if (editing == null && !saving) onLaunch(app) })
                            }
                            item("all-apps-${row.id}") {
                                DesktopAddTile("Все приложения", cardWidth,
                                    modifier = Modifier.focusRequester(addRequester),
                                    onFocus = { onCardFocus("all-apps-${row.id}") }) { if (editing == null) allApps = true }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
                Text("Разъёмы", color = Color.White, fontSize = 19.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelLaunchButton(tv = true, modifier = Modifier.padding(vertical = 10.dp)
                        .focusRequester(sourcesTrigger), onClick = ::showSources)
                    SourceStrip(sourcesPanel, visibleSources, sourceList,
                        Modifier.weight(1f).then(rowNavigation(visibleSources.map { it.id }))) { source ->
                        val card = CardMenu(source.id, source.label, true)
                        fun activate() {
                            if (source.kind == TvSourceKind.USB) {
                                if (sourceSnapshot.usbVolumes.size == 1) SettingsRouter.files(context, sourceSnapshot.usbVolumes.first())
                                else if (sourceSnapshot.usbVolumes.isEmpty()) SettingsRouter.files(context)
                                else usbExpanded = true
                            } else if (!SettingsRouter.source(context, source)) notice = "Прошивка не разрешила переключение входа"
                        }
                        CompactSourceCard(source, premium,
                            input(card, ::activate).onFocusChanged { if (it.isFocused) onCardFocus(card.key) }) {
                            if (editing == null && !saving) activate()
                        }
                    }
                }
                if (editing != null || notice.isNotEmpty()) Text(
                    if (saving) "Сохраняем…" else editing?.let {
                        "${it.name}  ·  ← → переместить${if (it.source) "" else "  ·  ↑ ↓ другая строка"}  ·  OK сохранить  ·  Back отменить"
                    } ?: notice,
                    color = Color(0xFFD7B477), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    menu?.takeIf { !it.source }?.let { card ->
        CardActionPopup(card.name, card.source, cardBounds[card.key], press,
            onDismiss = { menu = null; press.reset(); request(card.key) },
            onMove = { begin(card) },
            onHide = {
                menu = null; press.reset()
                commit(saved.hideApp(card.key)) {
                    saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }?.let(::request)
                }
            }, onFavorite = { menu = null; press.reset(); commit(saved.favorite(card.key)) { request(card.key) } },
            onInfo = byKey[card.key]?.component?.let { component -> { menu = null
                SettingsRouter.open(context, listOf(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${component.packageName}"))))
            } })
    }
    menu?.takeIf { it.source }?.let { card ->
        CardActionPopup(card.name, true, cardBounds[card.key], press,
            onDismiss = { menu = null; press.reset(); request(card.key) }, onMove = { begin(card) },
            onHide = {
                menu = null; press.reset()
                commit(saved.hideSource(card.key)) {
                    visibleSources.firstOrNull { it.id != card.key }?.let { request(it.id) } ?: closeSources()
                }
            }, onAutoStart = if (sourceSnapshot.entries.any { it.id == card.key && it.kind == TvSourceKind.TV && !it.id.startsWith("manual-") }) {
                { SourceSettings.setTunerAutoStart(context, !SourceSettings.tunerAutoStart(context)); menu = null; request(card.key) }
            } else null, autoStart = SourceSettings.tunerAutoStart(context))
    }
    if (usbExpanded) AlertDialog(onDismissRequest = { usbExpanded = false }, title = { Text("USB / накопители") },
        text = { Column { sourceSnapshot.usbVolumes.forEach { volume ->
            TextButton(onClick = { usbExpanded = false; SettingsRouter.files(context, volume) }) { Text(volume.getDescription(context)) }
        } } }, confirmButton = { TextButton(onClick = { usbExpanded = false }) { Text("Закрыть") } })
    if (launcherSettings) SidePanel("Настройки", PanelEdge.RIGHT, "settings-panel", settingsFirst,
        onDismiss = ::closeSettings) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("settings-list")) {
            ConnectionSettings(refreshSources, Modifier.focusRequester(settingsFirst))
                SettingsSection("Главный экран и пульт")
                SettingsItem("Домой", "Главный экран", "Открывать EZCH при нажатии Home.",
                    if (HomeRole.isHome(context)) "Назначен" else "Выбрать") { HomeRole.request(context) }
                val service = android.content.ComponentName(context, HomeButtonService::class.java)
                val helperEnabled = runCatching { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager)
                    .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { android.content.ComponentName.unflattenFromString(it.id) == service } }.getOrDefault(false)
                SettingsItem("Домой", "Кнопка Home", "Дополнительная служба: короткое нажатие возвращает домой, длинное открывает меню.",
                    if (helperEnabled) "Включена" else "По желанию") { HomeRole.request(context) }
                SettingsItem("Настройки", "Настройки телевизора", "Сеть, Bluetooth, звук, экран и другие параметры Android.") { SettingsRouter.settings(context) }
                SettingsSection("Источники и автозапуск")
                val tunerAvailable = sourceSnapshot.entries.any { it.kind == TvSourceKind.TV && !it.id.startsWith("manual-") }
                var autoTuner by remember { mutableStateOf(SourceSettings.tunerAutoStart(context)) }
                SettingsItem("Домой", "Запускать ТВ при включении", "Один раз после загрузки устройства. Только для доступного системного тюнера.",
                    if (autoTuner) "Включено" else "Выключено", enabled = tunerAvailable) {
                    autoTuner = !autoTuner; SourceSettings.setTunerAutoStart(context, autoTuner)
                }
                SettingsItem("Экран", "Добавить HDMI вручную", "Ярлык системных настроек. Наличие порта и подключения не подтверждается.") {
                    SourceSettings.add(context, TvSourceKind.HDMI); sourceSnapshot = scanTvSources(context)
                }
                SettingsItem("Экран", "Добавить AV вручную", "Для прошивок, скрывающих список входов.") {
                    SourceSettings.add(context, TvSourceKind.AV); sourceSnapshot = scanTvSources(context)
                }
                SettingsItem("Экран", "Убрать ручные источники", "Удаляет только добавленные вручную ярлыки.") {
                    SourceSettings.clear(context); sourceSnapshot = scanTvSources(context)
                }
                SettingsItem("Экран", "Восстановить скрытые источники", "Вернуть карточки разъёмов.", "Скрыто: ${saved.hiddenSources.size}") {
                    commit(saved.copy(hiddenSources = emptyList()))
                }
                SettingsSection("Оформление и обои")
                WallpaperControls { onPremiumChange(true) }
                SettingsSection("Приложения и рабочий стол")
                SettingsItem("Папка", "Файловый менеджер / USB", "Открыть накопитель, скопировать файлы или установить APK.") { context.startActivity(Intent(context, FileBrowserActivity::class.java)) }
                SettingsItem("Строки", "Все приложения / восстановить скрытые", "Вернуть скрытые значки на рабочий стол.", "Скрыто: ${saved.hiddenApps.size}") { allApps = true }
                SettingsItem("Строки", "Добавить строку приложений", "Создать отдельную строку, например для фильмов или игр.", "Строк: ${saved.rows.size}") { rowTitle = ""; addRow = true }
                SettingsSection("Об устройстве и поддержка")
                val memoryInfo = remember { android.app.ActivityManager.MemoryInfo().also {
                    context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
                } }
                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nОЗУ: ${"%.2f".format(memoryInfo.totalMem / 1073741824.0)} ГБ · EZCH 0.26", color = Muted, fontSize = 12.sp)
                SettingsItem("Домой", "Поддержка EZCH", "Telegram-канал проекта.") {
                    SettingsRouter.open(context, listOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/+huiJDfK5jdA4NzQy"))))
                }
                saved.rows.forEachIndexed { index, row ->
                    SettingsSection("Строка: ${row.title}")
                    Row {
                        TextButton(enabled = !saving && index > 0, onClick = { commit(saved.moveRow(row.id, -1)) }) { Text("Выше") }
                        TextButton(enabled = !saving && index < saved.rows.lastIndex, onClick = { commit(saved.moveRow(row.id, 1)) }) { Text("Ниже") }
                        TextButton(enabled = !saving && saved.rows.size > 1, onClick = { commit(saved.removeRow(row.id)) }) { Text("Убрать") }
                    }
                }
                
        }
    }
    if (addRow) AlertDialog(onDismissRequest = { if (!saving) addRow = false }, title = { Text("Новая строка") },
        text = { OutlinedTextField(rowTitle, { rowTitle = it.take(40) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(enabled = rowTitle.isNotBlank() && !saving, onClick = {
            commit(saved.addRow(rowTitle.trim(), java.util.UUID.randomUUID().toString())) { addRow = false }
        }) { Text("Добавить") } }, dismissButton = { TextButton(enabled = !saving, onClick = { addRow = false }) { Text("Отмена") } })
    if (allApps) AllAppsDialog(apps, saved.hiddenApps, saving,
        onDismiss = {
            allApps = false
            if (!launcherSettings) focusedKey?.let(::request)
        }, onLaunch = { app -> allApps = false; onLaunch(app) },
        onToggle = { app ->
            val key = keyOf(app)
            if (key in saved.hiddenApps) commit(saved.restoreApp(key)) else commit(saved.hideApp(key))
        })
}

@Composable
private fun DesktopAddTile(title: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "allAppsFocus")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "allAppsHalo")
    Column(modifier.width(width * AppCardScale).height(94.dp * AppCardScale).graphicsLayer { scaleX = scale; scaleY = scale }
        .focusHalo({ glow }).clip(AppCardShape)
        .border(if (focused) 4.dp else 1.dp, if (focused) Color(0xFF48B9FF) else Color(0xFF34455B), AppCardShape)
        .onFocusChanged { focused = it.isFocused; if (focused) onFocus() }.background(Color(0x66101C29), AppCardShape)
        .clickable(onClick = action).padding(8.dp * AppCardScale), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("+", color = Color(0xFFB6D9FF), fontSize = 25.sp)
        Text(title, color = Color.White, fontSize = 11.sp)
    }
}

/** Short adjacent scroll; initial restoration and long moves attach immediately. */
private suspend fun LazyListState.revealCard(index: Int) {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) { scrollToItem(index); return }
    val start = info.viewportStartOffset + info.beforeContentPadding
    val end = info.viewportEndOffset - info.afterContentPadding
    val target = items.firstOrNull { it.index == index }
    val delta = if (target != null) {
        when {
            target.offset < start -> target.offset - start
            target.offset + target.size > end -> target.offset + target.size - end
            else -> 0
        }
    } else if (index == items.last().index + 1) {
        val last = items.last()
        last.offset + last.size * 2 + info.mainAxisItemSpacing - end
    } else if (index == items.first().index - 1) {
        val first = items.first()
        first.offset - first.size - info.mainAxisItemSpacing - start
    } else { scrollToItem(index); return }
    if (delta != 0) animateScrollBy(delta.toFloat(), tween(80))
}
