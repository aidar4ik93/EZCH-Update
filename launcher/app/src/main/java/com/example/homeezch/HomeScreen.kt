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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    var allApps by remember { mutableStateOf(false) }
    var addRow by remember { mutableStateOf(false) }
    var rowTitle by remember { mutableStateOf("") }
    var sourceSnapshot by remember { mutableStateOf(TvSourcesSnapshot(emptyList(), emptyList())) }
    var usbExpanded by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    var focusRevision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
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
            if (ok) { saved = next; draft = next; editing = null; after() }
            else Toast.makeText(context, "Не удалось сохранить порядок. Повторите или отмените.", Toast.LENGTH_LONG).show()
        }
    }
    fun begin(card: CardMenu) { draft = saved; editing = card; menu = null; notice = ""; press.reset(); request(card.key) }
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
    fun cancel() { val card = editing; draft = saved; editing = null; card?.let { request(it.key) } }

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
            menu = null; launcherSettings = false; allApps = false; addRow = false
            editing = null; draft = saved; usbExpanded = false; press.reset(); pendingHorizontal = null
            desktopScroll.scrollTo(0)
            val row = saved.rows.first()
            request(row.appKeys.firstOrNull { it in byKey && it !in saved.hiddenApps } ?: "all-apps-${row.id}")
            withFrameNanos { }; consumeReset()
        }
    }
    LaunchedEffect(homeMenu, appsReady, saving) {
        if (homeMenu > 0 && appsReady && !saving) {
            val selected = editing?.key ?: focusedKey ?: preferredFocus ?: saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }
            editing = null; draft = saved
            byKey[selected]?.let { menu = CardMenu(selected!!, it.name, false) }
            sourceSnapshot.entries.firstOrNull { it.id == selected }?.let { menu = CardMenu(it.id, it.label, true) }
            consumeMenu()
        }
    }
    // Tiles scroll themselves into view and then reattach the same logical focus target.
    BackHandler(!launcherSettings && !allApps && menu == null && !addRow) {
        if (editing != null && !saving) cancel()
    }

    val shown = if (editing == null) saved else draft
    fun input(card: CardMenu, click: () -> Unit): Modifier = Modifier
        .focusRequester(requesters.getOrPut(card.key) { FocusRequester() })
        .remoteInput(moving = editing?.key == card.key, blocked = saving || (editing != null && editing?.key != card.key), press = press,
            onClick = click, onLong = { if (editing == null) menu = card }, onMenu = { if (editing == null) menu = card },
            onConfirm = ::confirm, onMove = ::move)

    fun rowNavigation(keys: List<String>): Modifier = Modifier.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val direction = when (key.keyCode) {
            NativeKey.KEYCODE_DPAD_LEFT -> -1
            NativeKey.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        if (direction == 0 || editing != null) false else {
            if (!saving && key.action == NativeKey.ACTION_DOWN &&
                horizontalPress.accept(key.eventTime, key.repeatCount)) {
                val current = pendingHorizontal?.takeIf { it in keys } ?: focusedKey
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
            TopBar(refreshSources, weatherReady, homeReset, onSettings = { launcherSettings = true })
            Spacer(Modifier.height((desktopHeight * .19f).coerceIn(42.dp, 110.dp)))
            Column(Modifier.weight(1f).verticalScroll(desktopScroll)) {
                shown.rows.forEach { row ->
                    key(row.id) {
                        val rowApps = remember(row, byKey, shown.hiddenApps) { row.appKeys.mapNotNull(byKey::get).filter { keyOf(it) !in shown.hiddenApps } }
                        Text(row.title, color = Color.White, fontSize = 19.sp)
                        val listState = rememberLazyListState()
                        LaunchedEffect(focusRevision) {
                            val rowKeys = rowApps.map(::keyOf) + "all-apps-${row.id}"
                            val index = rowKeys.indexOf(focusTarget)
                            if (index >= 0) {
                                listState.revealCard(index)
                                withFrameNanos { }
                                runCatching { requesters[focusTarget]?.requestFocus() }
                            }
                        }
                        val addRequester = remember(row.id) { requesters.getOrPut("all-apps-${row.id}") { FocusRequester() } }
                        LazyRow(modifier = rowNavigation(rowApps.map(::keyOf) + "all-apps-${row.id}"), state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 10.dp)) {
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
                val visibleSources = remember(sourceSnapshot, shown.sourceOrder, shown.hiddenSources) {
                    val sourceById = sourceSnapshot.entries.associateBy { it.id }
                    (shown.sourceOrder + sourceIds).distinct().filterNot { it in shown.hiddenSources }.mapNotNull(sourceById::get)
                }
                val portsState = rememberLazyListState()
                LaunchedEffect(focusRevision) {
                    val index = visibleSources.indexOfFirst { it.id == focusTarget }
                    if (index >= 0) {
                        portsState.revealCard(index)
                        withFrameNanos { }
                        runCatching { requesters[focusTarget]?.requestFocus() }
                    }
                }
                fun openSource(source: TvSourceEntry) {
                    if (source.kind == TvSourceKind.USB) usbExpanded = !usbExpanded
                    else if (!SettingsRouter.source(context, source)) notice = "Прошивка не разрешила переключение входа"
                }
                LazyRow(modifier = rowNavigation(visibleSources.map { it.id }), state = portsState, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 10.dp)) {
                    items(visibleSources, key = { it.id }) { source ->
                        val card = CardMenu(source.id, source.label, true)
                        PortTile(source.label, cardWidth, onFocus = { onCardFocus(card.key) },
                            onClick = { if (editing == null && !saving) openSource(source) },
                            connected = source.connected, kind = source.kind, status = source.detail,
                            modifier = input(card) { openSource(source) }.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(160)))
                    }
                }
                if (visibleSources.isEmpty()) {
                    Text(if (sourceSnapshot.entries.isEmpty()) "Доступные ТВ-входы и накопители не обнаружены"
                        else "Все источники скрыты. Восстановить можно в настройках EZCH.",
                        color = Color(0xFFB6C6DA), fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
                if (usbExpanded && sourceSnapshot.usbVolumes.isEmpty()) ControlChip("Открыть файловый менеджер") { SettingsRouter.files(context) }
                if (usbExpanded) sourceSnapshot.usbVolumes.forEach { volume ->
                    ControlChip(volume.getDescription(context)) {
                        if (!SettingsRouter.files(context, volume)) notice = "Файловый менеджер недоступен"
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

    menu?.let { card ->
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(card.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { begin(card) }) { Text("Переместить") }
                TextButton(onClick = {
                    menu = null
                    commit(if (card.source) saved.hideSource(card.key) else saved.hideApp(card.key)) {
                        saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }?.let(::request)
                    }
                }) { Text("Скрыть с рабочего стола") }
            }
        }, confirmButton = { TextButton(onClick = { menu = null; request(card.key) }) { Text("Закрыть") } })
    }
    if (launcherSettings) AlertDialog(onDismissRequest = { launcherSettings = false },
        title = { Text("Настройки EZCH") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { HomeRole.request(context) }) { Text("Назначить EZCH главным экраном") }
                TextButton(onClick = { SettingsRouter.accessibility(context) }) { Text("Служба EZCH — кнопка Home") }
                Text("Включается по желанию в специальных возможностях. Назначение HOME — отдельная кнопка выше.", fontSize = 12.sp)
                TextButton(onClick = { SettingsRouter.settings(context) }) { Text("Системные настройки") }
                WallpaperControls { onPremiumChange(true) }
                TextButton(onClick = { context.startActivity(Intent(context, FileBrowserActivity::class.java)) }) { Text("Файловый менеджер / USB") }
                TextButton(onClick = { launcherSettings = false; allApps = true }) { Text("Все приложения / восстановить скрытые") }
                TextButton(onClick = { launcherSettings = false; rowTitle = ""; addRow = true }) { Text("Добавить строку приложений") }
                saved.rows.forEachIndexed { index, row ->
                    Text(row.title, color = Color(0xFFD7B477))
                    Row {
                        TextButton(enabled = !saving && index > 0, onClick = { commit(saved.moveRow(row.id, -1)) }) { Text("Выше") }
                        TextButton(enabled = !saving && index < saved.rows.lastIndex, onClick = { commit(saved.moveRow(row.id, 1)) }) { Text("Ниже") }
                        TextButton(enabled = !saving && saved.rows.size > 1, onClick = { commit(saved.removeRow(row.id)) }) { Text("Убрать") }
                    }
                }
                if (saved.hiddenSources.isNotEmpty()) TextButton(enabled = !saving,
                    onClick = { commit(saved.copy(hiddenSources = emptyList())) }) { Text("Восстановить скрытые источники") }
            }
        }, confirmButton = { TextButton(onClick = { launcherSettings = false }) { Text("Закрыть") } })
    if (addRow) AlertDialog(onDismissRequest = { if (!saving) addRow = false }, title = { Text("Новая строка") },
        text = { OutlinedTextField(rowTitle, { rowTitle = it.take(40) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(enabled = rowTitle.isNotBlank() && !saving, onClick = {
            commit(saved.addRow(rowTitle.trim(), java.util.UUID.randomUUID().toString())) { addRow = false }
        }) { Text("Добавить") } }, dismissButton = { TextButton(enabled = !saving, onClick = { addRow = false }) { Text("Отмена") } })
    if (allApps) AlertDialog(onDismissRequest = { allApps = false }, title = { Text("Все приложения") }, text = {
        LazyColumn(Modifier.heightIn(max = 330.dp)) {
            items(apps, key = ::keyOf) { app ->
                val key = keyOf(app)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { allApps = false; onLaunch(app) }) { Text(app.name) }
                    TextButton(enabled = !saving, onClick = {
                        if (key in saved.hiddenApps) commit(saved.restoreApp(key)) else commit(saved.hideApp(key))
                    }) { Text(if (key in saved.hiddenApps) "Вернуть" else "Скрыть") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { allApps = false }) { Text("Закрыть") } })
}

@Composable
private fun DesktopAddTile(title: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "allAppsFocus")
    Column(modifier.width(width).height(94.dp).scale(scale).clip(DesktopShape)
        .border(if (focused) 4.dp else 1.dp, if (focused) Color(0xFF48B9FF) else Color(0xFF34455B), DesktopShape)
        .onFocusChanged { focused = it.isFocused; if (focused) onFocus() }.background(Color(0xB0101C29), DesktopShape)
        .clickable(onClick = action).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("+", color = Color(0xFFB6D9FF), fontSize = 30.sp)
        Text(title, color = Color.White, fontSize = 11.sp)
    }
}

/** Short adjacent scroll; initial restoration and long moves attach immediately. */
private suspend fun LazyListState.revealCard(index: Int) {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) { scrollToItem(index); return }
    val target = items.firstOrNull { it.index == index }
    val delta = if (target != null) {
        when {
            target.offset < info.viewportStartOffset -> target.offset - info.viewportStartOffset
            target.offset + target.size > info.viewportEndOffset -> target.offset + target.size - info.viewportEndOffset
            else -> 0
        }
    } else if (index == items.last().index + 1) {
        val last = items.last()
        last.offset + last.size * 2 + info.mainAxisItemSpacing - info.viewportEndOffset
    } else if (index == items.first().index - 1) {
        val first = items.first()
        first.offset - first.size - info.mainAxisItemSpacing - info.viewportStartOffset
    } else { scrollToItem(index); return }
    if (delta != 0) animateScrollBy(delta.toFloat(), tween(80))
}
