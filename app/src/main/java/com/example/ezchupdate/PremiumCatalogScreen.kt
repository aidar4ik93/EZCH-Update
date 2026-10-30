package com.example.ezchupdate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ezchupdate.data.RemoteApp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val CinemaRed = Color(0xFFFF3658)
private val CinemaMuted = Color(0xFFB8B6C2)
private val CinemaPurple = Color(0xFF9864ED)
private val categories = listOf("Главная", "Приложения", "Фильмы и ТВ", "Видеосервисы", "Инструменты", "Мои приложения")

@Composable internal fun PremiumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = CinemaRed, secondary = CinemaPurple,
        background = Color.Black, surface = Color(0xFF101014), surfaceVariant = Color(0xFF17171C),
        onSurface = Color.White, onSurfaceVariant = CinemaMuted), content = content)
}

internal fun category(app: RemoteApp): String = CuratedCatalog.section(app.packageName)

@Composable internal fun PremiumCatalogScreen(state: CatalogUiState, notificationRequest: Int = 0, onReload: () -> Unit,
    onToggle: (String) -> Unit, onInstall: () -> Unit, onLauncher: () -> Unit, onCancel: () -> Unit, onInstallAll: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { UpdateSettings(context) }
    var mode by remember { mutableStateOf(settings.appearance) }
    val lite = remember(mode) { settings.lite() }
    var section by rememberSaveable { mutableStateOf("Главная") }
    LaunchedEffect(notificationRequest) { if (notificationRequest > 0) section = "Обновления" }
    var actionError by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(settings.prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty()) }
    var details by remember { mutableStateOf<AppRow?>(null) }
    var previousFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val initialFocus = remember { FocusRequester() }
    var focusInitialized by remember { mutableStateOf(false) }
    var splash by rememberSaveable { mutableStateOf(true) }
    var time by remember { mutableStateOf(Date()) }
    val busy = state.installing != null || state.permissionRequested
    val rows = state.rows.filter { row ->
        (query.isBlank() || row.app.name.contains(query, true) || row.app.packageName.contains(query, true)) && when (section) {
            "Мои приложения" -> row.installed != null
            "Обновления" -> row.installed != null && row.updateAvailable
            "Фильмы и ТВ", "Видеосервисы", "Инструменты" -> category(row.app) == section
            else -> true
        }
    }
    LaunchedEffect(state.isLoading) { if (!state.isLoading) splash = false }
    LaunchedEffect(Unit) { while (true) { delay(30_000); time = Date() } }
    LaunchedEffect(splash, details, section, rows.isEmpty()) {
        if (!splash && details == null) {
            if (previousFocus != null) runCatching { previousFocus?.requestFocus() }
            else if (!focusInitialized && rows.isNotEmpty()) {
                runCatching { initialFocus.requestFocus() }; focusInitialized = true
            }
        }
    }
    BackHandler(searching || section != "Главная") {
        if (searching) { searching = false; query = "" } else section = "Главная"
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (!lite) CinemaGlow()
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            append("EZCH ")
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = CinemaRed)); append("UPDATE"); pop()
                        }, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("${state.rows.size} приложений", color = CinemaMuted, fontSize = 12.sp)
                    }
                    CinemaButton("Поиск", symbol = "Поиск", onClick = { searching = !searching })
                    Spacer(Modifier.width(8.dp))
                    CinemaButton(if (state.isRefreshing) "Проверяем…" else "Проверить обновления", symbol = "Обновления", enabled = !busy && !state.isRefreshing, onClick = onReload)
                    Spacer(Modifier.width(8.dp))
                    CinemaButton("Настройки", symbol = "Настройки", onClick = { section = "Настройки" })
                    Spacer(Modifier.width(14.dp))
                    Text(SimpleDateFormat("HH:mm\ndd MMM", Locale.forLanguageTag("ru")).format(time), color = CinemaMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(154.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        categories.forEach { item ->
                            CinemaButton(item, symbol = item, primary = section == item, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), onClick = { section = item; previousFocus = null })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (lite) "LITE" else "CINEMA PREMIUM", color = CinemaRed, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (searching) {
                            OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                                label = { Text("Название приложения") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                        if (section == "Настройки") {
                            SettingsPanel(state, settings, mode, onMode = { mode = it; settings.appearance = it })
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(section, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                val updateCount = state.rows.count { it.installed != null && it.updateAvailable }
                                if (section != "Обновления" && updateCount > 0) {
                                    CinemaButton("Обновления ($updateCount)", onClick = { section = "Обновления"; previousFocus = null })
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (!busy && updateCount > 0 && state.selectedPackages.isEmpty()) {
                                    CinemaButton("Обновить всё ($updateCount)", primary = true, onClick = onInstallAll)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (busy) CinemaButton("Отменить очередь", onClick = onCancel)
                                else CinemaButton("Установить выбранные (${state.selectedPackages.size})",
                                    primary = true, enabled = state.selectedPackages.isNotEmpty(), onClick = onInstall)
                            }
                            if (section != "Загрузки") Text("OK — отметить для установки  ·  удерживайте OK — сведения", color = CinemaMuted, fontSize = 11.sp)
                            if (state.message != null) Text(state.message, fontSize = 12.sp, color = CinemaMuted,
                                modifier = Modifier.padding(vertical = 6.dp), maxLines = if (section == "Загрузки") 8 else 2, overflow = TextOverflow.Ellipsis)
                            if (busy) {
                                Text("Приложение ${state.completedCount + 1}/${state.totalCount} • ${state.installing?.name ?: "разрешение Android"}", color = CinemaRed, fontSize = 12.sp)
                                if (state.totalBytes > 0) LinearProgressIndicator(progress = { (state.downloadBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            }
                            if (section == "Загрузки") {
                                Text("Завершено: ${state.completedCount} из ${state.totalCount}.\nОшибок: ${state.failures.size}.\nСледующий APK устанавливается после результата Android.\nОчередь сохраняется при перезапуске.", color = CinemaMuted, modifier = Modifier.padding(top = 18.dp))
                                state.rows.filter { it.app.packageName in state.selectedPackages }.forEach { Text("• ${it.app.name}", color = Color.White, fontSize = 14.sp) }
                                if (!busy && state.selectedPackages.isNotEmpty()) CinemaButton("Повторить выбранные", onClick = onInstall)
                                Text("История установок", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                                com.example.ezchupdate.install.InstallQueueStore(context).history().take(5).forEach { Text(it, color = CinemaMuted, fontSize = 12.sp) }
                            } else if (rows.isEmpty()) {
                                Text(if (state.isLoading) "Загрузка каталога…" else "В этом разделе приложений нет", color = CinemaMuted, modifier = Modifier.padding(24.dp))
                            } else {
                                key(section) { LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    itemsIndexed(rows, key = { _, row -> row.app.packageName }) { index, row ->
                                        val requester = remember(row.app.packageName) { FocusRequester() }
                                        val focusTarget = if (index == 0) initialFocus else requester
                                        PremiumCard(row, row.app.packageName in state.selectedPackages,
                                            row.app.packageName == state.installing?.packageName, lite,
                                            modifier = Modifier.focusRequester(focusTarget),
                                            // Short OK marks an app for installation; apps with nothing to install open details.
                                            onClick = {
                                                previousFocus = focusTarget
                                                if (row.updateAvailable && !busy) onToggle(row.app.packageName) else details = row
                                            },
                                            onLongClick = { previousFocus = focusTarget; details = row })
                                    }
                                } }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = splash, exit = fadeOut(tween(if (lite) 0 else 180))) { CinemaSplash(lite) }
        }
    }
    actionError?.let { error -> AlertDialog(onDismissRequest = { actionError = null }, title = { Text("Приложение") },
        text = { Text(error) }, confirmButton = { CinemaButton("Понятно", onClick = { actionError = null }) }) }
    details?.let { selected ->
        val row = state.rows.firstOrNull { it.app.packageName == selected.app.packageName } ?: selected
        AlertDialog(onDismissRequest = { details = null }, title = { Text(row.app.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppIcon(row, Modifier.size(72.dp))
                    Text("Версия ${row.app.versionName} • ${formatBytes(row.app.sizeBytes ?: 0)}\n${category(row.app)}", modifier = Modifier.padding(top = 12.dp))
                    if (!row.app.installable) Text(CuratedCatalog.reason(row.app.packageName), color = CinemaRed, fontSize = 13.sp)
                    Text(if (row.installed == null) { if (row.app.installable) "Доступно для установки" else "Проверенного APK нет" } else "Установлена версия ${row.installed.versionName ?: row.installed.versionCode}", color = CinemaMuted)
                    if (row.app.installable) Text("Источник: ${java.net.URI(row.app.apkUrl).host}\nSHA-256 и сертификат проверяются перед установкой.", fontSize = 12.sp, color = CinemaMuted, modifier = Modifier.padding(top = 12.dp))
                    if (row.app.installable) Text("${row.app.packageName}\nSHA-256: ${row.app.sha256}\nСертификат: ${row.app.signerSha256}", fontSize = 10.sp, color = CinemaMuted)
                    CinemaButton(if (row.app.packageName in favorites) "★ Убрать из избранного" else "☆ В избранное", onClick = {
                        favorites = if (row.app.packageName in favorites) favorites - row.app.packageName else favorites + row.app.packageName
                        settings.prefs.edit().putStringSet("favorites", favorites).apply()
                    })
                }
            }, confirmButton = {
                if (row.updateAvailable) CinemaButton(if (row.app.packageName in state.selectedPackages) "Убрать из очереди" else if (row.installed == null) "Выбрать для установки" else "Выбрать обновление", enabled = !busy,
                    onClick = { onToggle(row.app.packageName); details = null })
                else if (row.installed != null) CinemaButton("Открыть", onClick = {
                    val pm = context.packageManager
                    val intent = pm.getLeanbackLaunchIntentForPackage(row.app.packageName) ?: pm.getLaunchIntentForPackage(row.app.packageName)
                    if (intent != null) runCatching { context.startActivity(intent); details = null }
                        .onFailure { actionError = "Не удалось открыть приложение" }
                    else actionError = "У приложения нет экрана запуска для этого устройства"
                })
                else CuratedCatalog.officialUrl(row.app.packageName)?.let { url ->
                    CinemaButton("Официальный источник", onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            .onFailure { actionError = "На устройстве нет магазина или браузера" }
                    })
                }
            }, dismissButton = { CinemaButton("Назад", onClick = { details = null }) })
    }
}

@Composable private fun PremiumCard(row: AppRow, selected: Boolean, installing: Boolean, lite: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // TV remotes: OK held ≥ 500 ms opens details once; a short press toggles on release.
    var pressedAt by remember { mutableLongStateOf(0L) }
    var longFired by remember { mutableStateOf(false) }
    val okKeys = remember { setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A) }
    val pressModifier = Modifier.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.keyCode !in okKeys) return@onPreviewKeyEvent false
        when (native.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                if (native.repeatCount == 0) { pressedAt = native.eventTime; longFired = false }
                else if (!longFired && (native.isLongPress || native.eventTime - pressedAt >= 500)) { longFired = true; onLongClick() }
            }
            android.view.KeyEvent.ACTION_UP -> {
                if (!longFired && !native.isCanceled && pressedAt != 0L) {
                    if (native.eventTime - pressedAt >= 500) onLongClick() else onClick()
                }
                pressedAt = 0L; longFired = false
            }
        }
        true
    }
    val scale by animateFloatAsState(if (focused && !lite) 1.06f else 1f, tween(if (lite) 0 else 150), label = "cardFocus")
    val shape = RoundedCornerShape(16.dp)
    Card(onClick = onClick, modifier = modifier.then(pressModifier).height(138.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .shadow(if (focused && !lite) 12.dp else 0.dp, shape, ambientColor = CinemaRed, spotColor = CinemaRed)
        .border(if (focused) 2.dp else 1.dp, if (focused || selected) CinemaRed else Color(0xFF622032), shape),
        colors = CardDefaults.cardColors(contentColor = Color.White, containerColor = if (focused) Color(0xFF2A171F) else Color(0xFF17171C))) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row, Modifier.size(54.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.app.name, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val version = if (row.installed != null && !row.updateAvailable) row.installed.versionName ?: row.app.versionName else row.app.versionName
                Text(if (row.app.installable) "$version • ${formatBytes(row.app.sizeBytes ?: 0)}" else "Ожидает проверки", color = CinemaMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Text(when { installing -> "Подготовка к установке"; !row.app.installable && row.installed == null -> "Нет проверенного APK"; selected -> "✓ Выбрано"; row.installed == null -> "Установить"; row.updateAvailable -> "Обновить"; else -> "Установлено • Открыть" }, color = if (selected || row.updateAvailable) CinemaRed else CinemaMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable internal fun CinemaButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, symbol: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(onClick, modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (primary) CinemaRed else Color(0xFF34343D), shape),
        enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(containerColor = if (primary) Color(0xFF8D1735) else Color(0xFF17171C), contentColor = Color.White, disabledContentColor = CinemaMuted), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        if (symbol != null) {
            CinemaSymbol(symbol, if (primary || focused) Color.White else CinemaRed)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun CinemaGlow() {
    Canvas(Modifier.fillMaxSize()) {
        val curve = Path().apply {
            moveTo(size.width * 0.60f, size.height)
            cubicTo(size.width * 0.82f, size.height * 0.73f, size.width * 0.87f, size.height * 1.07f, size.width * 1.08f, size.height * 0.78f)
        }
        drawPath(curve, CinemaRed.copy(alpha = 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 70f))
        drawPath(curve, CinemaRed.copy(alpha = 0.65f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        drawArc(CinemaRed.copy(alpha = 0.3f), 5f, 150f, false,
            topLeft = androidx.compose.ui.geometry.Offset(-size.width * 0.12f, -size.height * 0.6f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.53f, size.height),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        drawCircle(Brush.radialGradient(listOf(CinemaRed.copy(alpha = 0.12f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.5f), radius = size.height * 0.75f), radius = size.height * 0.75f, center = androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.5f))
        drawCircle(Brush.radialGradient(listOf(CinemaRed.copy(alpha = 0.08f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.35f), radius = size.height * 0.65f), radius = size.height * 0.65f, center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.35f))
    }
}

@Composable internal fun CinemaSplash(lite: Boolean) {
    val phase = if (lite) 0f else {
        val transition = rememberInfiniteTransition(label = "fourDots")
        val animated by transition.animateFloat(0f, 4f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sequentialDots")
        animated
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (!lite) CinemaGlow()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("EZCH", fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text("CINEMA PREMIUM", color = CinemaRed, fontSize = 13.sp, letterSpacing = 5.sp)
            Spacer(Modifier.height(24.dp))
            Text("EZCH UPDATE", fontSize = 24.sp, letterSpacing = 2.sp)
            Text("Твой центр приложений.", color = CinemaMuted, fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { index ->
                    val distance = kotlin.math.abs(phase - index - 0.5f)
                    val pulse = if (lite) 0f else (1f - distance * 2f).coerceIn(0f, 1f)
                    Box(Modifier.size(9.dp).graphicsLayer { scaleX = 1f + pulse * 0.35f; scaleY = scaleX }
                        .shadow(if (pulse > 0.2f) 8.dp else 0.dp, androidx.compose.foundation.shape.CircleShape, ambientColor = CinemaRed, spotColor = CinemaRed)
                        .background(CinemaRed.copy(alpha = 0.2f + 0.8f * pulse), androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
    }
}

@Composable private fun SettingsPanel(state: CatalogUiState, settings: UpdateSettings, mode: String, onMode: (String) -> Unit) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(settings.autoCheck) }
    var download by remember { mutableStateOf(settings.autoDownload) }
    var interval by remember { mutableStateOf(settings.intervalHours) }
    var notifications by remember { mutableStateOf(settings.notifications) }
    var exportError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Оформление", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Auto", "Premium", "Lite").forEach { value -> CinemaButton(if (value == "Auto") "Автоматически" else value, primary = mode == value, onClick = { onMode(value) }) }
        }
        Text("Автоматический режим учитывает память устройства. Lite уменьшает анимации и свечение.", color = CinemaMuted, fontSize = 12.sp)
        CinemaButton("Автоматически проверять: ${if (auto) "включено" else "выключено"}", onClick = { auto = !auto; settings.autoCheck = auto })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1L, 6L, 24L).forEach { hours -> CinemaButton("Каждые $hours ч", primary = interval == hours, enabled = auto, onClick = { interval = hours; settings.intervalHours = hours }) } }
        CinemaButton("Скачивать обновления заранее: ${if (download) "да" else "нет"}", onClick = { download = !download; settings.autoDownload = download })
        Text("Фоновая работа учитывает сеть, заряд и хранилище. Установка требует вашего действия и подтверждения Android.", color = CinemaMuted, fontSize = 12.sp)
        CinemaButton("Напоминать об обновлениях: ${if (notifications) "да" else "нет"}", onClick = { notifications = !notifications; settings.notifications = notifications })
        Text(UpdateNotifier(context).permissionStatus(), color = CinemaMuted, fontSize = 12.sp)
        Text("Проверка работает и после закрытия каталога. Android может отложить её во сне или после принудительной остановки приложения. Уведомление открывает список обновлений.", color = CinemaMuted, fontSize = 12.sp)
        CinemaButton("Настройки уведомлений Android", onClick = {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                .onFailure { exportError = "Настройки уведомлений недоступны в этой оболочке" }
        })
        if (Build.VERSION.SDK_INT >= 33) CinemaButton("Разрешить уведомления Android", onClick = { (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 72) })
        Text("Информация об устройстве", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val memory = remember { android.app.ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) } }
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        val lastSync = settings.prefs.getLong("last_sync", 0)
        Text("${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nRAM: ${formatBytes(memory.totalMem)} • свободно: ${formatBytes(StatFs(context.filesDir.path).availableBytes)}\nEZCH Update $version\nПриложений: ${state.rows.size} • обновлений: ${state.rows.count { it.installed != null && it.updateAvailable }}\nСинхронизация: ${if (lastSync == 0L) "ещё не завершена" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru")).format(Date(lastSync))}", color = CinemaMuted, fontSize = 13.sp)
        Text("Последние установки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        com.example.ezchupdate.install.InstallQueueStore(context).history().take(8).forEach { Text(it, color = CinemaMuted, fontSize = 12.sp) }
        Text("Диагностика", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Подпись и SHA-256 проверяются до передачи APK Android. Отсутствие блокировки Play Protect требует отдельного теста на вашем Google TV.", color = CinemaMuted, fontSize = 12.sp)
        CinemaButton("Экспортировать технический отчёт", onClick = {
            runCatching {
                val directory = File(context.cacheDir, "reports").apply { mkdirs() }
                val file = File(directory, "ezch-diagnostics.txt")
                // Whitelist fields; never export URLs, intents, account data, or raw server errors.
                file.writeText("EZCH Update $version\nAndroid API ${Build.VERSION.SDK_INT}\nModel ${Build.MANUFACTURER} ${Build.MODEL}\nRAM ${memory.totalMem}\nFree ${StatFs(context.filesDir.path).availableBytes}\nCatalog ${state.rows.size}\nUpdates ${state.rows.count { it.installed != null && it.updateAvailable }}\nCompleted ${state.completedCount}/${state.totalCount}\nFailures ${state.failures.size}\nLast sync $lastSync\nPlay Protect: not verified\n", Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Экспорт отчёта"))
            }.onFailure { exportError = "На устройстве нет приложения для отправки отчёта" }
        })
        exportError?.let { Text(it, color = CinemaRed) }
    }
}

internal fun formatBytes(bytes: Long): String = String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024.0 * 1024.0))
