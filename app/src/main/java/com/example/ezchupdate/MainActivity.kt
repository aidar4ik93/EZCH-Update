package com.example.ezchupdate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ezchupdate.install.InstallEvents
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: CatalogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android confirmation is launched only by the resumed foreground Activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.uiState.collect { viewModel.openPreparedLauncher(this@MainActivity) }
                }
                launch {
                    while (isActive) {
                        delay(5 * 60 * 1000L)
                        viewModel.refreshCatalogIfIdle()
                    }
                }
                InstallEvents.snapshot.collect { snapshot ->
                    val confirmation = snapshot?.confirmationIntent ?: return@collect
                    try {
                        startActivity(Intent(confirmation))
                        InstallEvents.consumeConfirmation(snapshot.sessionId)
                    } catch (error: ActivityNotFoundException) {
                        InstallEvents.consumeConfirmation(snapshot.sessionId)
                        viewModel.onConfirmationLaunchFailed(
                            snapshot.sessionId,
                            error.message ?: "На устройстве нет системного установщика"
                        )
                    } catch (error: SecurityException) {
                        InstallEvents.consumeConfirmation(snapshot.sessionId)
                        viewModel.onConfirmationLaunchFailed(
                            snapshot.sessionId,
                            error.message ?: "Android не разрешил открыть подтверждение установки"
                        )
                    }
                }
            }
        }

        setContent {
            EzchTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.isLoading) {
                    if (!state.isLoading) {
                        intent.getStringExtra("selected_package")?.let { selected ->
                            intent.removeExtra("selected_package")
                            if (selected !in state.selectedPackages) viewModel.toggleSelection(selected)
                        }
                    }
                }
                CatalogScreen(
                    state = state,
                    onReload = viewModel::reload,
                    onToggle = viewModel::toggleSelection,
                    onInstall = { viewModel.installSelected(this) },
                    onLauncher = { viewModel.setupLauncher(this) },
                    onCancel = viewModel::cancelInstallation
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed(this)
    }
}

private val Turquoise = Color(0xFF00D9C0)
private val FocusCyan = Color(0xFF00E5FF)
private val MutedText = Color(0xFF8AA5CB)
private val ActionBlue = Color(0xFF258EFF)

@Composable
private fun EzchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = ActionBlue,
            onPrimary = Color.White,
            secondary = FocusCyan,
            background = Color(0xFF020711),
            surface = Color(0xFF091325),
            surfaceVariant = Color(0xFF102447),
            onSurfaceVariant = MutedText
        ),
        content = content
    )
}

@Composable
private fun CatalogScreen(
    state: CatalogUiState,
    onReload: () -> Unit,
    onToggle: (String) -> Unit,
    onInstall: () -> Unit,
    onLauncher: () -> Unit,
    onCancel: () -> Unit
) {
    val busy = state.installing != null || state.permissionRequested
    val canSelect = !state.isLoading && !busy
    val firstCardFocus = remember { FocusRequester() }
    val firstSelectable = state.rows.firstOrNull { it.updateAvailable }?.app?.packageName

    LaunchedEffect(state.isLoading, busy, firstSelectable) {
        if (!state.isLoading && firstSelectable != null && !busy) {
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
        CatalogBackdrop()
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("EZCH", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                            color = Color(0xFFE4F1FF))
                        Text(" Update", fontSize = 31.sp, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFAEC8F3))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Найдено приложений: ${state.rows.size}", fontSize = 14.sp, color = MutedText)
                }
                TvActionButton(
                    text = "Проверить обновления",
                    symbol = "⟳",
                    enabled = !state.isLoading && !busy,
                    onClick = onReload
                )
                Spacer(Modifier.width(16.dp))
                TvActionButton(
                    text = "Установить выбранные (${state.selectedPackages.size})",
                    symbol = "↓",
                    enabled = canSelect && state.selectedPackages.isNotEmpty(),
                    primary = true,
                    onClick = onInstall
                )
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Завершено: ${state.completedCount} из ${state.totalCount}",
                    color = MutedText,
                    fontSize = 14.sp
                )
                    Spacer(Modifier.weight(1f))
                    TvActionButton("Отменить очередь", enabled = true, onClick = onCancel)
                }
            }

            if (state.message != null || state.permissionRequested || state.installing != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (state.permissionRequested) {
                        "Разрешите установку в настройках Android — очередь продолжится после возврата"
                    } else {
                        state.message ?: "Устанавливается ${state.installing?.name.orEmpty()}"
                    },
                    color = if (state.failures.isNotEmpty() && !busy) Color(0xFFFFB779) else Turquoise,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.installing != null) {
                Spacer(Modifier.height(6.dp))
                if (state.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (state.downloadBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Text(
                        "${formatBytes(state.downloadBytes)} / ${formatBytes(state.totalBytes)}",
                        color = MutedText,
                        fontSize = 12.sp
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!busy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("EZCH Launcher • ваш рабочий стол", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Стеклянные карточки, живые обои и магазин. Установка и открытие одной кнопкой.", color = MutedText, fontSize = 12.sp)
                    }
                    val launcher = state.rows.firstOrNull { it.app.packageName == "com.example.homeezch.usb" }
                    TvActionButton(if (launcher?.installed == null) "Установить лаунчер" else if (launcher.updateAvailable) "Обновить лаунчер" else "Открыть лаунчер",
                        enabled = canSelect && launcher != null, symbol = "⌂", onClick = onLauncher)
                }
                Spacer(Modifier.height(8.dp))
                Text("Выберите приложения пультом и нажмите «Установить выбранные». Загрузка и настройка установки выполняются здесь.",
                    color = MutedText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            when {
                state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(34.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Проверяем каталог…", color = MutedText)
                    }
                }
                state.rows.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Не удалось загрузить каталог. Нажмите «Проверить обновления».", color = MutedText)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    items(state.rows, key = { it.app.packageName }) { row ->
                        AppCard(
                            row = row,
                            selected = row.app.packageName in state.selectedPackages,
                            installing = row.app.packageName == state.installing?.packageName,
                            enabled = canSelect && row.updateAvailable,
                            modifier = if (row.app.packageName == firstSelectable) {
                                Modifier.focusRequester(firstCardFocus)
                            } else Modifier,
                            onClick = { if (canSelect && row.updateAvailable) onToggle(row.app.packageName) }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun CatalogBackdrop() {
    Box(Modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(
            listOf(Color(0xFF061129), Color(0xFF01040A), Color(0xFF071329)),
            start = Offset.Zero, end = Offset(size.width, size.height)
        ))
        val accent = Color(0xFF155CDE)
        val beam = Path().apply {
            moveTo(size.width * 0.61f, size.height)
            lineTo(size.width * 0.88f, size.height * 0.39f)
            lineTo(size.width, size.height * 0.25f)
            lineTo(size.width, size.height * 0.31f)
            lineTo(size.width * 0.72f, size.height * 0.65f)
            close()
        }
        drawPath(beam, Brush.linearGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent)))
        drawLine(accent.copy(alpha = 0.45f), Offset(0f, size.height * 0.60f),
            Offset(size.width * 0.28f, size.height), strokeWidth = 1.3.dp.toPx())
        drawLine(accent.copy(alpha = 0.4f), Offset(size.width * 0.62f, size.height),
            Offset(size.width, size.height * 0.24f), strokeWidth = 1.dp.toPx())
        drawLine(accent.copy(alpha = 0.45f), Offset(0f, size.height * 0.37f),
            Offset(size.width * 0.31f, 0f), strokeWidth = 1.dp.toPx())
    }
        Text(
            "EZCH",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            color = Color(0xFF155CDE).copy(alpha = 0.09f),
            fontSize = 160.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun TvActionButton(
    text: String,
    enabled: Boolean,
    primary: Boolean = false,
    symbol: String? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = Modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(Brush.verticalGradient(listOf(
                if (primary) Color(0xFF123D9E) else Color(0xFF123264),
                if (primary) Color(0xFF071B4F) else Color(0xFF071329)
            )), shape)
            .border(if (focused) 2.dp else 1.dp,
                if (focused) FocusCyan else if (enabled) Color(0xFF356CDC) else Color(0xFF26375A), shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFE5F0FF),
            disabledContainerColor = Color(0x8810213F),
            disabledContentColor = Color(0xFF697EA0)
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (symbol != null) {
            Text(symbol, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun AppCard(
    row: AppRow,
    selected: Boolean,
    installing: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(140),
        label = "cardScale"
    )
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        focused -> FocusCyan
        selected -> Turquoise
        installing -> Color(0xFF64B5F6)
        else -> Color(0xFF264471)
    }
    val backgroundColor = when {
        focused -> Color(0xFF0B2E62)
        selected -> Color(0xFF092D35)
        else -> MaterialTheme.colorScheme.surface
    }

    // The clickable Card supplies the single DPAD focus target.
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = modifier
            .height(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .shadow(if (focused) 14.dp else 0.dp, shape, clip = false,
                ambientColor = FocusCyan, spotColor = FocusCyan)
            .background(Brush.linearGradient(listOf(backgroundColor, Color(0xFF050B14))), shape)
            .border(if (focused || selected) 2.dp else 1.dp, borderColor, shape),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFE6EDFF),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0xFFE6EDFF)
        )
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.app.name,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (row.installed != null && row.updateAvailable) {
                        "${row.installed.versionName ?: "?"} → ${row.app.versionName}"
                    } else row.installed?.versionName ?: row.app.versionName,
                    color = MutedText,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val statusColor = when {
                    installing -> Color(0xFF7CBDFF)
                    selected -> Turquoise
                    row.installed != null && !row.updateAvailable -> Color(0xFF31DBAD)
                    row.installed != null -> Color(0xFFFFCE68)
                    else -> Color(0xFF2EB6FF)
                }
                Text(
                    text = when {
                        installing -> "↓ Установка…"
                        selected -> "✓ Выбрано"
                        row.installed == null -> "↓ Установить"
                        row.updateAvailable -> "⟳ Доступно обновление"
                        else -> "✓ Актуально"
                    },
                    modifier = Modifier.height(20.dp)
                        .background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    color = statusColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AppIcon(row: AppRow) {
    val iconResource = bundledIcon(row.app.packageName)
    val context = LocalContext.current
    val installedIcon = remember(row.app.packageName, row.installed) {
        if (iconResource == null && row.installed != null) {
            runCatching {
                context.packageManager.getApplicationIcon(row.app.packageName).toBitmap(108, 108).asImageBitmap()
            }.getOrNull()
        } else null
    }
    val modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp))
    when {
        iconResource != null -> Image(
            painter = painterResource(iconResource),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        installedIcon != null -> Image(
            bitmap = installedIcon,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        else -> Box(modifier.background(Color(0xFF28414D)), contentAlignment = Alignment.Center) {
            Text(row.app.name.take(1).uppercase(), color = FocusCyan, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun bundledIcon(packageName: String): Int? = when (packageName) {
    "io.github.romanvht.byedpi" -> R.drawable.app_io_github_romanvht_byedpi
    "ru.tiardev.kinotrend" -> R.drawable.app_ru_tiardev_kinotrend
    "io.lift.app" -> R.drawable.app_io_lift_app
    "ru.yourok.num" -> R.drawable.app_ru_yourok_num
    "com.play.pandafref" -> R.drawable.app_com_play_pandafref
    "top.rootu.prisma" -> R.drawable.app_top_rootu_prisma
    "com.spocky.projengmenu" -> R.drawable.app_com_spocky_projengmenu
    "ru.vk.store.tv" -> R.drawable.app_ru_vk_store_tv
    "ru.rutube.app.tv" -> R.drawable.app_ru_rutube_app_tv
    "ru.twicker.serialtrend" -> R.drawable.app_ru_twicker_serialtrend
    "jp.snowlife01.android.appkiller2" -> R.drawable.app_jp_snowlife01_android_appkiller2
    "ru.yourok.torrserve" -> R.drawable.app_ru_yourok_torrserve
    "com.vk.tv" -> R.drawable.app_com_vk_tv
    "ru.vokino.web" -> R.drawable.app_ru_vokino_web
    "mobi.zona" -> R.drawable.app_mobi_zona
    "limehd.ru.lite" -> R.drawable.app_limehd_ru_lite
    "net.gtvbox.vimuhd" -> R.drawable.app_net_gtvbox_vimuhd
    "com.alphainventor.filemanager" -> R.drawable.app_com_alphainventor_filemanager
    else -> null
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024.0 * 1024.0))
