package com.example.homeezch

import kotlin.math.sign
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
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

private data class CardMenu(val key: String, val name: String, val source: Boolean)

// TV's default pivot scroll competes with shelf reveal and leaves fractional edge cards.
// Scroll only when the requested rectangle actually lies outside the viewport.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val DesktopBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size - containerSize
        return when {
            offset < 0f && trailing > 0f -> 0f
            offset < 0f -> offset
            trailing > 0f -> trailing
            else -> 0f
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    apps: List<LaunchApp>, refreshSources: Int, premium: Boolean,
    onPremiumChange: (Boolean) -> Unit,
    onLaunch: (LaunchApp) -> Unit,
    preferredFocus: String? = null, onFocused: (String) -> Unit = {},
    weatherReady: Boolean = true, appsReady: Boolean = true, homeReset: Int = 0, homeMenu: Int = 0,
    consumeReset: () -> Unit = {}, consumeMenu: () -> Unit = {}, voiceStart: Int = 0
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
    var revealedSourceIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var allApps by remember { mutableStateOf(false) }
    var addRow by remember { mutableStateOf(false) }
    var rowTitle by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf<LaunchApp?>(null) }
    var profilePicker by remember { mutableStateOf(false) }
    var activeProfile by remember { mutableStateOf(Profiles.active(context)) }
    var unlocked by remember { mutableStateOf(false) }
    var pinProfile by remember { mutableStateOf<DesktopProfile?>(null) }
    var guardedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var resumedForSettings by remember { mutableStateOf(true) }
    fun guarded(action: () -> Unit) {
        val guard = Profiles.guard(context)
        if (unlocked || guard == null) action() else { guardedAction = action; pinProfile = guard }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumedForSettings = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Keep file-picker result launchers registered while the system picker is open.
                unlocked = false; resumedForSettings = false; menu = null; editing = null
                addRow = false; placing = null; pinProfile = null; guardedAction = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(launcherSettings, unlocked, resumedForSettings) {
        if (launcherSettings && !unlocked && resumedForSettings) Profiles.guard(context)?.let {
            pinProfile = it; guardedAction = {}
        }
    }
    var renameRowId by remember { mutableStateOf<String?>(null) }
    var sourceSnapshot by remember { mutableStateOf(TvSourcesSnapshot(emptyList(), emptyList())) }
    var usbExpanded by remember { mutableStateOf(false) }
    var usbDetails by remember { mutableStateOf<List<UsbVolumeDetails>>(emptyList()) }
    LaunchedEffect(usbExpanded, sourceSnapshot.usbVolumes) {
        if (usbExpanded) usbDetails = withContext(Dispatchers.IO) {
            sourceSnapshot.usbVolumes.map { usbVolumeDetails(context, it) }
        } else usbDetails = emptyList()
    }
    var focusTarget by remember { mutableStateOf<String?>(null) }
    var focusRevision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    // Claude r24: card positions are kept as layout handles and read only when a card menu opens. A snapshot-state
    // map written from onGloballyPositioned recorded a state change for every visible card on every scroll frame.
    val cardCoords = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    fun cardBounds(key: String): Rect? = cardCoords[key]?.takeIf { it.isAttached }?.boundsInWindow()
    val settingsTrigger = remember { FocusRequester() }
    val sourcesTrigger = remember { requesters.getOrPut("sources-control") { FocusRequester() } }
    val settingsFirst = remember { FocusRequester() }
    val sourcesFirst = remember { FocusRequester() }
    val sourceList = rememberLazyListState()
    val sourceIds = remember(sourceSnapshot) { sourceSnapshot.entries.map { it.id } }
    val press = remember { CenterPress() }
    val pendingHorizontal = remember { FocusMemory() }
    val horizontalPress = remember { HorizontalPress() }
    val focusedKey = remember { FocusMemory() }
    fun onCardFocus(key: String) { if (pendingHorizontal.key == key) pendingHorizontal.key = null; if (focusedKey.key != key) { press.reset(); focusedKey.key = key; onFocused(key) } }

    fun request(key: String) {
        val folder = draft.rows.firstOrNull { it.isFolder && key in it.appKeys }
        inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        focusTarget = folder?.let { "folder:${it.id}" } ?: key; focusRevision++
    }
    val actualVoiceFocus = remember { FocusMemory() }
    var aiEnabled by remember { mutableStateOf(com.example.homeezch.ai.AiPreferences.enabled(context)) }
    var voiceOrigin by remember { mutableStateOf<String?>(null) }
    var voiceProfile by remember { mutableStateOf<String?>(null) }
    var voiceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var evaRequest by remember { mutableIntStateOf(0) }
    fun requestEva() = guarded {
        com.example.homeezch.ai.AiPreferences.store(context).edit().putBoolean("enabled", true).apply()
        aiEnabled = true
        voiceOrigin = focusedKey.key; voiceProfile = Profiles.active(context)
        evaRequest++
    }
    LaunchedEffect(voiceStart) { if (voiceStart > 0) requestEva() }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) aiEnabled = com.example.homeezch.ai.AiPreferences.enabled(context)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) voiceJob?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voiceJob?.cancel() }
    }
    suspend fun awaitVoiceFocus(key: String): Boolean {
        request(key)
        val deadline = android.os.SystemClock.elapsedRealtime() + 1000
        withFrameNanos { }
        while (actualVoiceFocus.key != key && android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(16)
        return actualVoiceFocus.key == key
    }
    suspend fun navigateVoice(action: com.example.homeezch.ai.Action.Navigate): com.example.homeezch.ai.Outcome {
        if (Profiles.active(context) != voiceProfile) return com.example.homeezch.ai.Outcome(false, "Профиль изменился — повторите команду")
        if (editing != null || saving || launcherSettings || sourcesPanel || menu != null || allApps || addRow ||
            openFolderId != null || profilePicker || pinProfile != null || placing != null)
            return com.example.homeezch.ai.Outcome(false, "Закройте редактор, папку и панели перед голосовой навигацией")
        val rows = saved.rows.filterNot { it.isFolder }.map { row -> row.appKeys.filter { it in byKey && it !in saved.hiddenApps } }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return com.example.homeezch.ai.Outcome(false, "Нет доступных карточек")
        repeat(action.count) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (Profiles.active(context) != voiceProfile) return com.example.homeezch.ai.Outcome(false, "Профиль изменился")
            val key = actualVoiceFocus.key ?: return com.example.homeezch.ai.Outcome(false, "Фокус не подтверждён")
            val r = rows.indexOfFirst { key in it }
            if (r < 0) return com.example.homeezch.ai.Outcome(false, "Выберите карточку приложения")
            val c = rows[r].indexOf(key)
            val target = when (action.direction) {
                com.example.homeezch.ai.Direction.LEFT -> rows[r][(c - 1).coerceAtLeast(0)]
                com.example.homeezch.ai.Direction.RIGHT -> rows[r][(c + 1).coerceAtMost(rows[r].lastIndex)]
                com.example.homeezch.ai.Direction.UP -> rows[(r - 1).coerceAtLeast(0)].let { it[c.coerceAtMost(it.lastIndex)] }
                com.example.homeezch.ai.Direction.DOWN -> rows[(r + 1).coerceAtMost(rows.lastIndex)].let { it[c.coerceAtMost(it.lastIndex)] }
                com.example.homeezch.ai.Direction.HOME -> rows.first().first()
                com.example.homeezch.ai.Direction.BACK -> return com.example.homeezch.ai.Outcome(true, "Вы уже на главном экране")
                com.example.homeezch.ai.Direction.OK -> {
                    val app = byKey[key] ?: return com.example.homeezch.ai.Outcome(false, "Карточка недоступна")
                    if (app.component == null && !com.example.homeezch.ai.AiProfilePolicy.updatesAllowed(context))
                        return com.example.homeezch.ai.Outcome(false, "Откройте обновления вручную с подтверждением владельца")
                    onLaunch(app)
                    return com.example.homeezch.ai.Outcome(false, "Запуск карточки запрошен; дальнейшая навигация остановлена")
                }
            }
            if (target == key) return com.example.homeezch.ai.Outcome(true, "Граница ряда")
            if (!awaitVoiceFocus(target)) return com.example.homeezch.ai.Outcome(false, "Фокус не подтверждён — команда остановлена")
            kotlinx.coroutines.delay(60)
        }
        return com.example.homeezch.ai.Outcome(true, "Фокус перемещён")
    }
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && aiEnabled) {
            val resolution = com.example.homeezch.ai.LocalIntentResolver().resolve(result.data?.getStringExtra("command").orEmpty())
            voiceJob?.cancel()
            voiceJob = scope.launch {
                try {
                    val origin = voiceOrigin
                    if (origin == null || Profiles.active(context) != voiceProfile || !awaitVoiceFocus(origin)) {
                        Toast.makeText(context, "Выберите карточку и повторите команду", Toast.LENGTH_SHORT).show(); return@launch
                    }
                    if (resolution is com.example.homeezch.ai.Resolution.Plan) {
                        val executor = com.example.homeezch.ai.AndroidActionExecutor(context, ::navigateVoice)
                        for (action in resolution.actions) {
                            if (action !is com.example.homeezch.ai.Action.Navigate) break
                            val outcome = executor.execute(action)
                            Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                            if (!outcome.accepted) break
                        }
                    }
                } finally { voiceJob = null }
            }
        }
    }
    fun reloadDesktop() {
        activeProfile = Profiles.active(context)
        saved = WorkspacePrefs.load(context).discover(byKey.keys.toList()); draft = saved
        editing = null; menu = null; openFolderId = null; launcherSettings = false
        unlocked = false; pendingHorizontal.key = null; focusedKey.key = null
        request(saved.rows.firstOrNull { !it.isFolder }?.appKeys?.firstOrNull { it in byKey && it !in saved.hiddenApps } ?: "all-apps-${saved.rows.first().id}")
        onPremiumChange(true)
    }
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
        guarded { editing = null; draft = saved; menu = card }
    }
    fun confirm() { val card = editing ?: return; commit(draft) { request(card.key) } }
    fun move(code: Int) {
        val card = editing ?: return
        val horizontal = when (code) { NativeKey.KEYCODE_DPAD_LEFT -> -1; NativeKey.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val vertical = when (code) { NativeKey.KEYCODE_DPAD_UP -> -1; NativeKey.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        if (horizontal == 0 && vertical == 0) return
        val next = if (card.key.startsWith("folder:")) draft.moveRow(card.key.removePrefix("folder:"), if (vertical != 0) vertical else horizontal)
            else if (card.source) draft.moveSource(card.key, horizontal, sourceIds)
            else draft.moveApp(card.key, horizontal, vertical, byKey.keys)
        if (next != draft) { draft = next; request(card.key) }
    }
    fun cancel() { val card = editing; draft = saved; editing = null; press.reset(); card?.let { request(it.key) } }

    LaunchedEffect(apps) {
        val reconciled = saved.discover(byKey.keys.toList())
        saved = reconciled
        if (editing == null) draft = reconciled else {
            draft = draft.discover(byKey.keys.toList())
            if (editing?.source == false && editing?.key?.startsWith("folder:") != true && editing?.key !in byKey) { editing = null; draft = reconciled }
        }
    }
    LaunchedEffect(refreshSources) {
        val previous = sourceSnapshot.entries.map { it.id }
        sourceSnapshot = withContext(Dispatchers.IO) { scanTvSources(context) }
        if (sourceSnapshot.usbVolumes.isEmpty()) usbExpanded = false
        if (context.getSharedPreferences("home_setup", android.content.Context.MODE_PRIVATE).getBoolean("setup_reviewed_v24", false)) {
            sourceSnapshot.entries.firstOrNull { it.tunerAutoStartEligible }?.let { tuner ->
                if (SourceSettings.consumeTunerStart(context) && !SettingsRouter.source(context, tuner)) notice = "Автозапуск ТВ ограничен прошивкой"
            }
        }
        if (editing?.source == true && sourceSnapshot.entries.none { it.id == editing?.key }) {
            editing = null; draft = saved; press.reset()
            saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }?.let(::request)
        } else if (sourcesPanel && focusedKey.key in previous && sourceSnapshot.entries.none { it.id == focusedKey.key }) {
            pendingHorizontal.key = null
            request("sources-control")
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
            placing = null; renameRowId = null
            editing = null; draft = saved; usbExpanded = false; press.reset(); pendingHorizontal.key = null
            desktopScroll.scrollTo(0)
            val row = saved.rows.first()
            request(row.appKeys.firstOrNull { it in byKey && it !in saved.hiddenApps } ?: "all-apps-${row.id}")
            withFrameNanos { }; consumeReset()
        }
    }
    LaunchedEffect(homeMenu, appsReady, saving) {
        if (homeMenu > 0 && appsReady && !saving && !launcherSettings && !sourcesPanel) {
            val selected = editing?.key ?: focusedKey.key ?: preferredFocus ?: saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }
            editing = null; draft = saved
            byKey[selected]?.let { openMenu(CardMenu(selected!!, it.name, false)) }
            saved.rows.firstOrNull { it.isFolder && "folder:${it.id}" == selected }?.let { openMenu(CardMenu("folder:${it.id}", it.title, false)) }
            sourceSnapshot.entries.firstOrNull { it.id == selected }?.let { openMenu(CardMenu(it.id, it.label, true)) }
            consumeMenu()
        }
    }
    // Tiles scroll themselves into view and then reattach the same logical focus target.
    BackHandler(!launcherSettings && !sourcesPanel && !allApps && menu == null && !addRow && openFolderId == null && !profilePicker && pinProfile == null && voiceJob?.isActive != true) {
        if (editing != null && !saving) cancel()
    }

    val shown = if (editing == null) saved else draft
    val visibleSources = remember(sourceSnapshot, shown.sourceOrder, shown.hiddenSources) {
        val sourceById = sourceSnapshot.entries.associateBy { it.id }
        (shown.sourceOrder + sourceIds).distinct().filterNot { it in shown.hiddenSources }.mapNotNull(sourceById::get)
    }
    fun closeSettings() {
        launcherSettings = false
        scope.launch { withFrameNanos { }; runCatching { settingsTrigger.requestFocus() } }
    }
    fun closeSources() {
        if (editing?.source == true) { draft = saved; editing = null; press.reset() }
        menu = null
        pendingHorizontal.key = null
        sourcesPanel = false
        scope.launch { withFrameNanos { }; runCatching { sourcesTrigger.requestFocus() } }
    }
    fun showSettings() {
        if (editing != null || saving) return
        guarded { press.reset(); menu = null; sourcesPanel = false; launcherSettings = true }
    }
    fun showSources() {
        if (editing != null || saving) return
        if (sourcesPanel) { closeSources(); return }
        press.reset(); menu = null; launcherSettings = false; sourcesPanel = true
    }
    BackHandler(voiceJob?.isActive == true) { voiceJob?.cancel() }
    BackHandler(sourcesPanel && menu == null) {
        if (editing?.source == true) { if (!saving) cancel() } else closeSources()
    }
    LaunchedEffect(sourcesPanel) { if (sourcesPanel) sourceList.scrollToItem(0) }
    // Claude r24: focusRevision changes on every D-pad press. Reading it as an effect key made every press
    // recompose the whole desktop (top bar, every shelf and tile); it is observed by snapshotFlow instead.
    val currentVisibleSources by rememberUpdatedState(visibleSources)
    LaunchedEffect(sourcesPanel, revealedSourceIds) { snapshotFlow { focusRevision }.collectLatest {
        val target = focusTarget
        if (sourcesPanel && (target == "sources-control" || target in revealedSourceIds)) {
            try {
                var index = currentVisibleSources.indexOfFirst { it.id == target }
                if (index < 0 && target != "sources-control") { withFrameNanos { }; withFrameNanos { }; index = currentVisibleSources.indexOfFirst { it.id == target } }
                if (index >= 0) sourceList.scrollToItem(index)
                withFrameNanos { }
                runCatching { requesters[target]?.requestFocus() }
            } finally {
                if (pendingHorizontal.key == target) pendingHorizontal.key = null
            }
        }
    } }
    fun input(card: CardMenu, click: () -> Unit): Modifier = Modifier
        .onFocusChanged { focus -> if (focus.isFocused) actualVoiceFocus.key = card.key else if (actualVoiceFocus.key == card.key) actualVoiceFocus.key = null }
        .onGloballyPositioned { cardCoords[card.key] = it }
        .focusRequester(requesters.getOrPut(card.key) { FocusRequester() })
        .remoteInput(moving = editing?.key == card.key || draft.rows.any { it.isFolder && "folder:${it.id}" == card.key && editing?.key in it.appKeys },
            blocked = saving || (editing != null && editing?.key != card.key && draft.rows.none { it.isFolder && "folder:${it.id}" == card.key && editing?.key in it.appKeys }), press = press,
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
                pendingHorizontal.key = null
                focusTarget = null
                focusRevision++
            }
            false
        } else {
            if (!saving && pendingHorizontal.key == null && key.action == NativeKey.ACTION_DOWN &&
                horizontalPress.accept(key.eventTime, key.repeatCount)) {
                // Do not advance from an unconfirmed destination: that skips cards.
                val current = focusedKey.key
                val index = keys.indexOf(current)
                if (index >= 0) {
                    val next = keys[(index + direction).coerceIn(0, keys.lastIndex)]
                    if (next != current) { pendingHorizontal.key = next; request(next) }
                }
            }
            true // Horizontal events never escape this shelf, including at either end.
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode in setOf(NativeKey.KEYCODE_VOICE_ASSIST, NativeKey.KEYCODE_ASSIST, NativeKey.KEYCODE_SEARCH)) {
            if (key.action == NativeKey.ACTION_DOWN && key.repeatCount == 0) requestEva()
            true
        } else false
    }) {
        CosmicBackground(premium)
        val desktopHeight = maxHeight
        // Fit whole rendered cards, including AppCardScale and room for the focus halo.
        // A fixed base width left a partial card at the right edge of a 16:9 desktop.
        val safeHorizontal = (maxWidth * .05f).coerceAtLeast(24.dp)
        // R25: gutter == gap, so a card resting at the edge never shows a sliver of the next one in the
        // gutter (GPT R24 note 4); 18 dp still holds the focus halo (~17 dp).
        val shelfGutter = 18.dp
        val shelfGap = 18.dp
        val usable = (maxWidth - safeHorizontal * 2 - shelfGutter * 2).coerceAtLeast(1.dp)
        val columns = ((usable + shelfGap) / (88.dp + shelfGap)).toInt().coerceIn(1, 8)
        val renderedCardWidth = ((usable - shelfGap * (columns - 1)) / columns)
            .coerceAtMost(125.dp * AppCardScale)
        val cardWidth = renderedCardWidth / AppCardScale
        val shelfWidth = renderedCardWidth * columns + shelfGap * (columns - 1) + shelfGutter * 2
        Column(Modifier.fillMaxSize().padding(horizontal = safeHorizontal, vertical = 22.dp)) {
            TopBar(refreshSources, weatherReady, homeReset, settingsModifier = Modifier.focusRequester(settingsTrigger), onVoice = {
                requestEva()
            }, onSettings = ::showSettings)
            TextButton(enabled = !saving && editing == null, onClick = { profilePicker = true }) {
                Text("Профиль: ${Profiles.list(context).firstOrNull { it.id == activeProfile }?.name ?: "Владелец"}", color = Muted, fontSize = 11.sp)
            }
            // R25: the microphone hint has its own slot above the shelves; floating at the bottom it covered
            // "Открыть разъёмы" and a tap there opened Accessibility (GPT R24 defect 3).
            Box(Modifier.fillMaxWidth().height((desktopHeight * .19f).coerceIn(52.dp, 110.dp)), contentAlignment = Alignment.CenterStart) {
                MicSetupHint(Modifier.widthIn(max = 560.dp))
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides DesktopBringIntoViewSpec) {
            Column(Modifier.weight(1f).verticalScroll(desktopScroll)) {
                shown.rows.forEach { row ->
                    key(row.id) {
                        val rowApps = remember(row, byKey, shown.hiddenApps) { row.appKeys.mapNotNull(byKey::get).filter { keyOf(it) !in shown.hiddenApps } }
                        val bringShelf = remember { BringIntoViewRequester() }
                        fun focusShelf(key: String) {
                            onCardFocus(key)
                            scope.launch {
                                withFrameNanos { }
                                bringShelf.bringIntoView()
                            }
                        }
                        Column(Modifier.width(shelfWidth).align(Alignment.CenterHorizontally)
                            .bringIntoViewRequester(bringShelf).testTag("shelf-${row.id}")) {
                        Text(row.title, color = Color.White, fontSize = 19.sp)
                        val listState = rememberLazyListState()
                        val currentRowApps by rememberUpdatedState(rowApps)
                        LaunchedEffect(row.id, row.isFolder) { snapshotFlow { focusRevision }.collectLatest {
                            fun keysNow() = (if (row.isFolder) listOf("folder:${row.id}") else currentRowApps.map(::keyOf)) + "all-apps-${row.id}"
                            val target = focusTarget
                            var rowKeys = keysNow()
                            var index = rowKeys.indexOf(target)
                            // The press may arrive in the same change as a new row layout (move, hide, folder):
                            // let that composition land (two frame turns), then look again.
                            if (index < 0 && target != null) { withFrameNanos { }; withFrameNanos { }; rowKeys = keysNow(); index = rowKeys.indexOf(target) }
                            if (index >= 0) {
                                try {
                                    val from = rowKeys.indexOf(focusedKey.key)
                                    if (listState.fullyVisible(index)) {
                                        // Focus first, then glide: the halo moves on the same frame as the key press
                                        // and the row scrolls ahead so the NEXT card is already in view.
                                        runCatching { requesters[target]?.requestFocus() }
                                        if (pendingHorizontal.key == target) pendingHorizontal.key = null
                                        val step = if (from >= 0 && from != index) (index - from).sign else 0
                                        val ahead = index + step
                                        if (step != 0 && ahead in rowKeys.indices && !listState.fullyVisible(ahead)) listState.revealCard(ahead)
                                    } else {
                                        listState.revealCard(index)
                                        withFrameNanos { }
                                        runCatching { requesters[target]?.requestFocus() }
                                    }
                                } finally {
                                    if (pendingHorizontal.key == target) pendingHorizontal.key = null
                                }
                            }
                        } }
                        val addRequester = remember(row.id) { requesters.getOrPut("all-apps-${row.id}") { FocusRequester() } }
                        LazyRow(modifier = rowNavigation((if (row.isFolder) listOf("folder:${row.id}") else rowApps.map(::keyOf)) + "all-apps-${row.id}").fillMaxWidth()
                            // Claude r24: each shelf is cached as one GPU layer; a frame re-renders only the shelf that
                            // changes (focus glide), the others are drawn as one image (RenderThread work on weak TVs).
                            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                            .testTag("app-row-${row.id}"), state = listState, horizontalArrangement = Arrangement.spacedBy(shelfGap),
                            contentPadding = PaddingValues(horizontal = shelfGutter, vertical = 18.dp)) {
                            if (row.isFolder) item("folder:${row.id}") {
                                val card = CardMenu("folder:${row.id}", row.title, false)
                                DesktopAddTile("${row.title} (${rowApps.size})", cardWidth, input(card) { openFolderId = row.id }.testTag("folder-${row.id}"),
                                    onFocus = { focusShelf(card.key) }, glyph = "▣") { if (editing == null && !saving) openFolderId = row.id }
                            }
                            else items(rowApps, key = ::keyOf) { app ->
                                val card = CardMenu(keyOf(app), app.name, false)
                                AppTile(app, cardWidth, input(card) { onLaunch(app) }.testTag("desktop-card-${card.key}").animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(160)), onFocus = { focusShelf(card.key) },
                                    onClick = { if (editing == null && !saving) onLaunch(app) })
                            }
                            item("all-apps-${row.id}") {
                                DesktopAddTile("Все приложения", cardWidth,
                                    modifier = Modifier.focusRequester(addRequester),
                                    onFocus = { focusShelf("all-apps-${row.id}") }) { if (editing == null) allApps = true }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                Text("Разъёмы", color = Color.White, fontSize = 19.sp)
                Row(Modifier.fillMaxWidth().then(rowNavigation(listOf("sources-control") + if (sourcesPanel) revealedSourceIds else emptyList())), verticalAlignment = Alignment.CenterVertically) {
                    PanelLaunchButton(tv = true, modifier = Modifier.padding(vertical = 10.dp)
                        .focusRequester(sourcesTrigger).onFocusChanged { if (it.isFocused) onCardFocus("sources-control") }, onClick = ::showSources)
                    SourceStrip(sourcesPanel, visibleSources, sourceList,
                        Modifier.weight(1f), onRevealed = { revealedSourceIds = it }) { source ->
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
        // Update notice lives at the bottom, out of the way of the top bar.
        Box(Modifier.align(Alignment.BottomStart).padding(start = safeHorizontal, bottom = 28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvaPackBanner(Modifier.widthIn(max = 520.dp))
                rememberUpdateSummary()?.let { UpdateBanner(it) }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = safeHorizontal, bottom = 24.dp)) {
            HomeEva(evaRequest, cardWidth * AppCardScale) { text ->
                voiceProfile = Profiles.active(context)
                voiceJob?.cancel()
                voiceJob = scope.launch {
                    val origin = focusedKey.key
                    if (origin != null) awaitVoiceFocus(origin)
                    val plan = com.example.homeezch.ai.LocalIntentResolver().resolve(text)
                    if (plan is com.example.homeezch.ai.Resolution.Plan) {
                        for (action in plan.actions) {
                            if (action !is com.example.homeezch.ai.Action.Navigate) break
                            val result = navigateVoice(action)
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            if (!result.accepted) break
                        }
                    }
                }
            }
        }
    }

    menu?.takeIf { it.key.startsWith("folder:") }?.let { card ->
        CardActionPopup(card.name, false, cardBounds(card.key), press,
            onDismiss = { menu = null; press.reset(); request(card.key) }, onMove = { begin(card) },
            onHide = { menu = null; commit(saved.removeRow(card.key.removePrefix("folder:"))) { request("all-apps-${saved.rows.first().id}") } },
            onInfo = { menu = null; renameRowId = card.key.removePrefix("folder:"); rowTitle = card.name; addRow = true },
            moveLabel = "Переместить папку", hideLabel = "Расформировать папку", infoLabel = "Переименовать папку")
    }
    menu?.takeIf { !it.source && !it.key.startsWith("folder:") }?.let { card ->
        CardActionPopup(card.name, card.source, cardBounds(card.key), press,
            onDismiss = { menu = null; press.reset(); request(card.key) },
            onMove = { begin(card) },
            onHide = {
                menu = null; press.reset()
                commit(saved.hideApp(card.key)) {
                    saved.rows.flatMap { it.appKeys }.firstOrNull { it in byKey && it !in saved.hiddenApps }?.let(::request)
                }
            }, onFavorite = { menu = null; press.reset(); commit(saved.favorite(card.key)) { request(card.key) } },
            onRemove = {
                menu = null; press.reset()
                commit(saved.removeApp(card.key)) {
                    val first = saved.rows.first()
                    request(first.appKeys.firstOrNull { it in byKey && it !in saved.hiddenApps } ?: "all-apps-${first.id}")
                }
            },
            onInfo = byKey[card.key]?.component?.let { component -> { menu = null
                SettingsRouter.open(context, listOf(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${component.packageName}"))))
            } })
    }
    menu?.takeIf { it.source }?.let { card ->
        CardActionPopup(card.name, true, cardBounds(card.key), press,
            onDismiss = { menu = null; press.reset(); request(card.key) }, onMove = { begin(card) },
            onHide = {
                menu = null; press.reset()
                commit(saved.hideSource(card.key)) {
                    visibleSources.firstOrNull { it.id != card.key }?.let { request(it.id) } ?: closeSources()
                }
            }, onAutoStart = if (sourceSnapshot.entries.any { it.id == card.key && it.tunerAutoStartEligible }) {
                { SourceSettings.setTunerAutoStart(context, !SourceSettings.tunerAutoStart(context)); menu = null; request(card.key) }
            } else null, autoStart = SourceSettings.tunerAutoStart(context))
    }
    if (usbExpanded) AlertDialog(onDismissRequest = { usbExpanded = false }, title = { Text("USB / накопители") },
        text = { Column { if (usbDetails.isEmpty()) Text("Читаем сведения о накопителях…")
            usbDetails.forEach { details ->
            TextButton(onClick = { usbExpanded = false; SettingsRouter.files(context, details.volume) }) {
                Column { Text(details.name); Text(details.capacity, fontSize = 11.sp) }
            }
        } } }, confirmButton = { TextButton(onClick = { usbExpanded = false }) { Text("Закрыть") } })
    var phoneRemoteDialog by remember { mutableStateOf(false) }
    if (phoneRemoteDialog) com.example.homeezch.phone.PhoneRemoteDialog { phoneRemoteDialog = false }
    if (launcherSettings) SidePanel("Настройки", PanelEdge.RIGHT, "settings-panel", settingsFirst,
        onDismiss = ::closeSettings) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("settings-list")) {
            ConnectionSettings(refreshSources, Modifier.focusRequester(settingsFirst))
                SettingsItem("Устройство", "Устройство и оптимизация", "Проверка оборудования, системные рекомендации и команды с отменой.") {
                    guarded { context.startActivity(Intent(context, DeviceOptimizationActivity::class.java)) }
                }
                SettingsSection("Главный экран и пульт")
                SettingsItem("Домой", "Настроить EZCH", "Основной лаунчер, службы EZCH и Ai EvA, микрофон — по шагам, с проверкой.",
                    remember { SetupWizard.progressLabel(context) }) { SetupWizard.open(context) }
                SettingsItem("Пульт", "Проверка и настройка кнопок", "Проверить реальные коды и назначить специальные кнопки пульта.") {
                    guarded { context.startActivity(Intent(context, RemoteSetupActivity::class.java)) }
                }
                SettingsItem("Голос", "Голосовой помощник EVA", "Настроить микрофон, системную роль и проверить совместимость.") {
                    guarded { context.startActivity(Intent(context, com.example.homeezch.ai.AssistantSettingsActivity::class.java)
                        .putExtra("authorization", com.example.homeezch.ai.AiSettingsTicket.issue(context))) }
                }
                SettingsItem("Пульт", "Телефон как пульт и микрофон", "QR-код: говорите с Евой через микрофон телефона, печатайте и отправляйте ссылки на ТВ.") {
                    phoneRemoteDialog = true
                }
                SettingsItem("Настройки", "Умный дом", "Home Assistant: свет, розетки, климат, датчики, сценарии. Голосом: «Ева, включи свет в гостиной».") {
                    context.startActivity(Intent(context, com.example.homeezch.ai.SmartHomeActivity::class.java))
                }
                SettingsItem("Домой", "Главный экран", "Открывать EZCH при нажатии Home.",
                    if (HomeRole.isHome(context)) "Назначен" else "Выбрать") { HomeRole.request(context) }
                val service = android.content.ComponentName(context, HomeButtonService::class.java)
                val helperEnabled = runCatching { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager)
                    .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { android.content.ComponentName.unflattenFromString(it.id) == service } }.getOrDefault(false)
                SettingsItem("Домой", "Кнопка Home", "Дополнительная служба: короткое нажатие возвращает домой, длинное открывает меню.",
                    if (helperEnabled) "Включена" else "По желанию") { HomeRole.request(context) }
                var autoHome by remember { mutableStateOf(HomeStartup.enabled(context)) }
                SettingsItem("Домой", "Рабочий стол после включения", "Один запуск после загрузки. На этой прошивке требуется подключённая служба «Кнопка Home».",
                    if (autoHome) "Включено" else "Выключено") {
                    guarded { autoHome = !autoHome; HomeStartup.setEnabled(context, autoHome) }
                }
                SettingsItem("Настройки", "Настройки телевизора", "Сеть, Bluetooth, звук, экран и другие параметры Android.") { SettingsRouter.settings(context) }
                var diagState by remember { mutableStateOf("Сохранить") }
                SettingsItem("Настройки", "Диагностика EZCH", "Отчёт о работе, ошибках и том, что не запустилось: Загрузки → EZCH → ${EzchDiag.FILE}. Ключей и PIN в нём нет.", diagState) {
                    diagState = "Сохраняю…"
                    Thread {
                        val place = EzchDiag.export(context, "кнопка «Диагностика EZCH»")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            diagState = "Сохранено"; Toast.makeText(context, "Диагностика: $place", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
                SettingsSection("Источники и автозапуск")
                val tunerAvailable = sourceSnapshot.entries.any { it.tunerAutoStartEligible }
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
                SettingsItem("Строки", "Добавить строку приложений", "Создать отдельную строку, например для фильмов или игр.", "Строк: ${saved.rows.size}", enabled = !saving && saved.rows.size < 40) { rowTitle = ""; renameRowId = null; newFolder = false; addRow = true }
                SettingsItem("Папка", "Создать папку", "Папка открывается отдельным списком. Наполняйте через «Все приложения» → «Выбрать строку».", enabled = !saving && saved.rows.size < 40) { rowTitle = ""; renameRowId = null; newFolder = true; addRow = true }
                SettingsSection("Профили и резервная копия")
                ProfileControls { activeProfile = Profiles.active(context); unlocked = false; launcherSettings = false }
                BackupControls(::reloadDesktop)
                SettingsSection("Об устройстве и поддержка")
                val memoryInfo = remember { android.app.ActivityManager.MemoryInfo().also {
                    context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
                } }
                Text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nОЗУ: ${"%.2f".format(memoryInfo.totalMem / 1073741824.0)} ГБ · EZCH ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
                Text(LocalRenderProfile.current.reason, color = Muted, fontSize = 12.sp)
                SettingsItem("Домой", "Поддержка EZCH", "Telegram-канал проекта.") {
                    SettingsRouter.open(context, listOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/+huiJDfK5jdA4NzQy"))))
                }
                saved.rows.forEachIndexed { index, row ->
                    SettingsSection("Строка: ${row.title}")
                    Row {
                        TextButton(enabled = !saving && index > 0, onClick = { commit(saved.moveRow(row.id, -1)) }) { Text("Выше") }
                        TextButton(enabled = !saving && index < saved.rows.lastIndex, onClick = { commit(saved.moveRow(row.id, 1)) }) { Text("Ниже") }
                        TextButton(enabled = !saving && saved.rows.size > 1 && (row.isFolder || saved.rows.count { !it.isFolder } > 1), onClick = { commit(saved.removeRow(row.id)) }) { Text("Убрать") }
                    }
                    TextButton(enabled = !saving, onClick = { renameRowId = row.id; rowTitle = row.title; addRow = true }) { Text(if (row.isFolder) "Переименовать папку" else "Переименовать строку") }
                }
                
        }
    }
    if (addRow) AlertDialog(onDismissRequest = { if (!saving) { addRow = false; newFolder = false } }, title = { Text(if (renameRowId != null) "Название строки / папки" else if (newFolder) "Новая папка" else "Новая строка") },
        text = { OutlinedTextField(rowTitle, { rowTitle = it.take(40) }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(enabled = rowTitle.isNotBlank() && !saving, onClick = {
            val rowId = renameRowId
            commit(if (rowId == null) saved.addRow(rowTitle.trim(), java.util.UUID.randomUUID().toString(), newFolder) else saved.renameRow(rowId, rowTitle)) { addRow = false; newFolder = false }
        }) { Text(if (renameRowId == null) "Добавить" else "Сохранить") } }, dismissButton = { TextButton(enabled = !saving, onClick = { addRow = false }) { Text("Отмена") } })
    if (allApps) AllAppsDialog(apps, saved.hiddenApps, saving,
        onDismiss = {
            allApps = false
            if (!launcherSettings) focusedKey.key?.let(::request)
        }, onLaunch = { app -> allApps = false; onLaunch(app) },
        onToggle = { app ->
            val key = keyOf(app)
            guarded { if (key in saved.hiddenApps || key in saved.removedApps) commit(saved.restoreApp(key)) else commit(saved.hideApp(key)) }
        }, removed = saved.removedApps, onPlace = { app -> guarded { placing = app } })
    openFolderId?.let { id ->
        val folder = saved.rows.firstOrNull { it.id == id && it.isFolder }
        if (folder != null) AllAppsDialog(folder.appKeys.filterNot { it in saved.hiddenApps }.mapNotNull(byKey::get), saved.hiddenApps, saving,
            onDismiss = { openFolderId = null; request("folder:$id") }, onLaunch = { app -> openFolderId = null; onLaunch(app) },
            onToggle = { app -> guarded { commit(saved.hideApp(keyOf(app))) } }, onPlace = { app -> guarded { placing = app } }, title = folder.title)
    }
    if (profilePicker) ProfilePicker({ profilePicker = false }, ::reloadDesktop)
    pinProfile?.let { profile -> PinPrompt(profile, { pinProfile = null; guardedAction = null; if (!unlocked) launcherSettings = false }) {
        pinProfile = null; unlocked = true; val action = guardedAction; guardedAction = null; action?.invoke()
    } }
    placing?.let { app -> AlertDialog(onDismissRequest = { if (!saving) placing = null },
        title = { Text("Строка для ${app.name}") }, text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                saved.rows.forEach { row -> TextButton(enabled = !saving, onClick = {
                    commit(saved.placeApp(keyOf(app), row.id)) { placing = null }
                }) { Text(row.title) } }
            }
        }, confirmButton = { TextButton(enabled = !saving, onClick = { placing = null }) { Text("Отмена") } }) }
}

@Composable
private fun DesktopAddTile(title: String, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, glyph: String = "+", action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val heavy = LocalRenderProfile.current.heavyEffects
    val scale by animateFloatAsState(if (focused) 1.075f else 1f, tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "allAppsFocus")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(if (heavy) 220 else 160), label = "allAppsHalo")
    Column(modifier.width(width * AppCardScale).height(94.dp * AppCardScale).graphicsLayer { scaleX = scale; scaleY = scale }
        .focusHalo({ glow }).clip(AppCardShape)
        .border(if (focused) 4.dp else 1.dp, if (focused) Color(0xFF48B9FF) else Color(0xFF34455B), AppCardShape)
        .onFocusChanged { focused = it.isFocused; if (focused) onFocus() }.background(Color(0x66101C29), AppCardShape)
        .clickable(onClick = action).padding(8.dp * AppCardScale), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        // R25: glyph + up to two label lines must fit the card's inner height (GPT R24 defect 2).
        Text(glyph, color = Color(0xFFB6D9FF), fontSize = 23.sp, lineHeight = 25.sp, maxLines = 1)
        Text(title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun LazyListState.fullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val start = info.viewportStartOffset + info.beforeContentPadding
    val end = info.viewportEndOffset - info.afterContentPadding
    return item.offset >= start - 1 && item.offset + item.size <= end + 1
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
    // 80 ms read as a jump on TVs; 190 ms ease-out glides and still keeps up with a held D-pad
    // (a new press cancels the running scroll and continues from the current position).
    if (delta != 0) animateScrollBy(delta.toFloat(), tween(190, easing = androidx.compose.animation.core.FastOutSlowInEasing))
}
