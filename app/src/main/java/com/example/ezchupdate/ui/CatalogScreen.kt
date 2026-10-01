package com.example.ezchupdate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ezchupdate.AppRow
import com.example.ezchupdate.CatalogUiState
import java.util.Locale

internal val ElectricBlue = Color(0xFF13AFFF)
private val MutedText = Color(0xFF91AFCB)
private val SuccessGreen = Color(0xFF57E9AC)
private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun EzchTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
            primary = ElectricBlue,
            secondary = Color(0xFF6AC6FF),
            background = Color(0xFF050C17),
            surface = Color(0xFF101E2C),
            onSurface = Color(0xFFF7FAFF),
            onSurfaceVariant = MutedText
        )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onReload: () -> Unit,
    onToggle: (String) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val firstCardFocus = remember { FocusRequester() }
    val reloadFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val inputModeManager = LocalInputModeManager.current
    val windowInfo = LocalWindowInfo.current
    var initialFocusPlaced by remember { mutableStateOf(false) }
    var screenHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.rows, windowInfo.isWindowFocused, inputModeManager.inputMode) {
        if (windowInfo.isWindowFocused && !state.isLoading && (!initialFocusPlaced || !screenHasFocus)) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (state.rows.isNotEmpty()) {
                gridState.scrollToItem(0)
                withFrameNanos { }
                runCatching { firstCardFocus.requestFocus() }.onFailure {
                    runCatching { reloadFocus.requestFocus() }
                }
            } else {
                withFrameNanos { }
                runCatching { reloadFocus.requestFocus() }
            }
            initialFocusPlaced = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 1100.dp
        val narrow = maxWidth < 760.dp
        MountainBackground()
        Column(
            modifier = Modifier.fillMaxSize().onFocusChanged { screenHasFocus = it.hasFocus }.focusGroup().padding(
                horizontal = if (compact) 28.dp else 40.dp,
                vertical = if (compact) 20.dp else 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)
        ) {
            CatalogHeader(state, compact, narrow, reloadFocus, onReload, onInstall)
            if (state.message != null) Notice(state.message, state.isError, compact)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (narrow) 2 else 4
                val gap = if (compact) 10.dp else 14.dp
                val cardHeight = ((maxHeight - gap * 2 - 8.dp) / 3).coerceIn(100.dp, 150.dp)
                when {
                    state.rows.isNotEmpty() -> LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        itemsIndexed(state.rows, key = { _, row -> row.app.packageName }) { index, row ->
                            AppCard(
                                row = row,
                                selected = row.app.packageName in state.selectedPackages,
                                compact = compact,
                                height = cardHeight,
                                modifier = if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                                onClick = { onToggle(row.app.packageName) }
                            )
                        }
                    }
                    state.isLoading -> EmptyCatalog("Проверяем каталог", "Загружаем приложения и проверяем версии на устройстве", true)
                    state.isError -> EmptyCatalog("Каталог недоступен", "Проверьте подключение и нажмите «Проверить обновления»", false)
                    else -> EmptyCatalog("В каталоге пока нет приложений", "Нажмите «Проверить обновления», чтобы загрузить каталог", false)
                }
            }
            QueueFooter(state, compact, onCancel, onConfirm)
        }
    }
}

@Composable
private fun CatalogHeader(state: CatalogUiState, compact: Boolean, narrow: Boolean, reloadFocus: FocusRequester, onReload: () -> Unit, onInstall: () -> Unit) {
    if (narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Branding(compact = true, showDescription = false)
            HeaderActions(state, true, reloadFocus, onReload, onInstall)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Branding(compact, showDescription = true)
            Spacer(Modifier.weight(1f))
            HeaderActions(state, compact, reloadFocus, onReload, onInstall)
        }
    }
}

@Composable
private fun Branding(compact: Boolean, showDescription: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text("EZ", style = TextStyle(fontSize = if (compact) 34.sp else 44.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = Color.White)
                Text("CH", style = TextStyle(fontSize = if (compact) 34.sp else 44.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, brush = Brush.linearGradient(listOf(Color(0xFF80D8FF), Color(0xFF087CFF)))))
            }
            Text("Update", fontSize = if (compact) 16.sp else 20.sp, color = Color(0xFFADCEEF), letterSpacing = 1.5.sp)
        }
        if (showDescription) {
            Spacer(Modifier.width(if (compact) 18.dp else 24.dp))
            Box(Modifier.width(1.dp).height(if (compact) 46.dp else 56.dp).background(MutedText.copy(alpha = 0.45f)))
            Text(
                "Установка и обновление\nприложений для Android TV",
                modifier = Modifier.padding(start = if (compact) 16.dp else 22.dp).width(if (compact) 171.dp else 210.dp),
                fontSize = if (compact) 13.sp else 16.sp,
                lineHeight = if (compact) 19.sp else 23.sp,
                color = Color(0xFFACCAE7)
            )
        }
    }
}

@Composable
private fun HeaderActions(state: CatalogUiState, compact: Boolean, reloadFocus: FocusRequester, onReload: () -> Unit, onInstall: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        TvButton(
            text = if (state.isLoading) "Проверяем…" else "Проверить обновления",
            icon = Glyph.Refresh,
            modifier = Modifier.focusRequester(reloadFocus),
            compact = compact,
            enabled = !state.isLoading && state.installing == null,
            onClick = onReload
        )
        TvButton(
            text = "Установить выбранные (${state.selectedPackages.size})",
            icon = Glyph.Download,
            compact = compact,
            primary = true,
            enabled = state.selectedPackages.isNotEmpty() && state.installing == null && !state.isLoading,
            onClick = onInstall
        )
    }
}

@Composable
private fun AppCard(row: AppRow, selected: Boolean, compact: Boolean, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val status = when {
        row.installed == null -> "Установить"
        row.updateAvailable -> "Обновление"
        else -> "Установлено"
    }
    val statusColor = when {
        row.installed != null && !row.updateAvailable -> SuccessGreen
        row.updateAvailable && row.installed != null -> ElectricBlue
        else -> Color(0xFFB8D2EB)
    }
    val border = if (focused) ElectricBlue else if (selected) Color(0xFF358DD3) else Color(0xFF2C4055)
    Row(
        modifier = modifier.fillMaxWidth().height(height)
            .onFocusChanged { focused = it.isFocused }
            .clip(CardShape)
            .background(Brush.verticalGradient(if (focused) listOf(Color(0xFF122D4C), Color(0xFF081B31)) else listOf(Color(0xED12202D), Color(0xF309121B))))
            .border(if (focused) 2.5.dp else 1.dp, border, CardShape)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.app.name}, версия ${row.app.versionName}"
                stateDescription = "$status${if (selected) ", выбрано" else ""}"
                this.selected = selected
            }
            .clickable(role = Role.Checkbox, onClickLabel = if (selected) "Убрать из очереди" else "Выбрать приложение", onClick = onClick)
            .padding(if (compact) 13.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
    ) {
        AppIcon(row.app, size = if (compact) 54.dp else 68.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.app.name, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = if (compact) 16.sp else 19.sp, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(3.dp))
                SelectionMark(selected, if (compact) 16.dp else 20.dp)
            }
            Text("v${row.app.versionName}", fontSize = if (compact) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedText)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GlyphIcon(if (row.installed == null) Glyph.Download else if (row.updateAvailable) Glyph.Up else Glyph.Check, 10.dp, statusColor)
                Text(status, fontSize = if (compact) 9.sp else 11.sp, color = statusColor, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(4.dp))
            .background(if (selected) ElectricBlue else Color.Transparent)
            .border(if (selected) 0.dp else 1.5.dp, Color(0xFF4B647D), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (selected) GlyphIcon(Glyph.Check, size * 0.7f, Color(0xFF051524))
    }
}

@Composable
private fun Notice(message: String, error: Boolean, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (error) Color(0xFF4B2426).copy(alpha = 0.82f) else Color(0xFF18344D).copy(alpha = 0.82f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlyphIcon(if (error) Glyph.Alert else Glyph.Info, 14.dp, if (error) Color(0xFFFFB4AC) else Color(0xFFB8DBF7))
        Text(message, fontSize = if (compact) 11.sp else 13.sp, color = if (error) Color(0xFFFFD5CE) else Color(0xFFCAE5FC), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyCatalog(title: String, description: String, loading: Boolean) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(32.dp), color = ElectricBlue, strokeWidth = 3.dp)
        else GlyphIcon(Glyph.Info, 36.dp, MutedText)
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        Text(description, color = MutedText, fontSize = 14.sp)
    }
}

@Composable
private fun QueueFooter(state: CatalogUiState, compact: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val active = state.installing != null
    Row(
        Modifier.fillMaxWidth().height(if (compact) 72.dp else 88.dp).clip(CardShape)
            .background(Brush.horizontalGradient(listOf(Color(0xF0132333), Color(0xF009121B))))
            .border(1.dp, Color(0xFF2C4055), CardShape)
            .padding(horizontal = if (compact) 18.dp else 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)
    ) {
        GlyphIcon(if (active) Glyph.Download else Glyph.Info, if (compact) 26.dp else 32.dp, ElectricBlue)
        if (active) {
            Column(Modifier.width(if (compact) 206.dp else 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Установка приложений", color = Color(0xFFB7D1EB), fontSize = if (compact) 12.sp else 15.sp)
                if (state.installProgress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = ElectricBlue, trackColor = Color(0xFF2C3F55))
                } else {
                    LinearProgressIndicator(progress = { state.installProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = ElectricBlue, trackColor = Color(0xFF2C3F55))
                }
            }
            Box(Modifier.width(1.dp).height(34.dp).background(Color(0xFF26384C)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${state.queueIndex}/${state.queueTotal}   ${state.installing?.name.orEmpty()}", color = Color(0xFFD8E9FA), fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val transfer = if (state.downloadedBytes > 0) " • ${megabytes(state.downloadedBytes)}${state.totalBytes?.let { " / ${megabytes(it)}" }.orEmpty()} МБ" else ""
                Text("${state.installStage ?: "Подготовка"}$transfer", color = MutedText, fontSize = if (compact) 11.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.pendingSessionId != null) TvButton("Подтвердить", Glyph.Check, compact = true, primary = true, onClick = onConfirm)
            TvButton(if (compact) "Отменить" else "Отменить все", Glyph.Close, compact = compact, onClick = onCancel)
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (state.selectedPackages.isEmpty()) "Выберите приложения для установки" else "Выбрано приложений: ${state.selectedPackages.size}", color = Color(0xFFD4E9FC), fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Medium)
                Text("Пульт: стрелки — перемещение, ОК — выбор", color = MutedText, fontSize = if (compact) 11.sp else 13.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (state.isOffline) "Сохранённый каталог" else "Приложений: ${state.rows.size}  •  Обновлений: ${state.rows.count { it.installed != null && it.updateAvailable }}", color = if (state.isOffline) Color(0xFFFFCF84) else MutedText, fontSize = if (compact) 11.sp else 13.sp)
                state.lastChecked?.let { Text(it, color = MutedText.copy(alpha = 0.75f), fontSize = if (compact) 10.sp else 12.sp, maxLines = 1) }
            }
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale("ru"), "%.1f", bytes / (1024.0 * 1024.0))

@Composable
private fun TvButton(text: String, icon: Glyph, modifier: Modifier = Modifier, compact: Boolean = false, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val colors = when {
        !enabled -> listOf(Color(0xFF17283B), Color(0xFF142234))
        primary -> listOf(Color(0xFF329EFF), Color(0xFF086DEC))
        focused -> listOf(Color(0xFF2A4765), Color(0xFF233C55))
        else -> listOf(Color(0xFF1C3046), Color(0xFF17273B))
    }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }.clip(shape).background(Brush.verticalGradient(colors))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xFF7BD9FF) else if (primary && enabled) Color(0xFF439EFF) else Color(0xFF344D67), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 15.dp else 20.dp, vertical = if (compact) 15.dp else 19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        val textColor = if (enabled) Color.White else MutedText.copy(alpha = 0.6f)
        GlyphIcon(icon, if (compact) 18.dp else 22.dp, textColor)
        Text(text, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 12.sp else 15.sp, maxLines = 1)
    }
}

private enum class Glyph { Refresh, Download, Up, Check, Close, Info, Alert }

@Composable
private fun GlyphIcon(glyph: Glyph, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.11f).coerceAtLeast(1f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        when (glyph) {
            Glyph.Download -> { line(0.5f, 0.12f, 0.5f, 0.68f); line(0.28f, 0.46f, 0.5f, 0.68f); line(0.72f, 0.46f, 0.5f, 0.68f); line(0.17f, 0.88f, 0.83f, 0.88f) }
            Glyph.Up -> { line(0.5f, 0.85f, 0.5f, 0.15f); line(0.23f, 0.42f, 0.5f, 0.15f); line(0.77f, 0.42f, 0.5f, 0.15f) }
            Glyph.Check -> { line(0.18f, 0.5f, 0.4f, 0.73f); line(0.4f, 0.73f, 0.82f, 0.27f) }
            Glyph.Close -> { line(0.22f, 0.22f, 0.78f, 0.78f); line(0.78f, 0.22f, 0.22f, 0.78f) }
            Glyph.Refresh -> {
                drawArc(color, 40f, 280f, false, Offset(w * 0.16f, h * 0.16f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.68f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(0.81f, 0.1f, 0.81f, 0.38f); line(0.81f, 0.38f, 0.53f, 0.38f)
            }
            Glyph.Info, Glyph.Alert -> {
                drawCircle(color, w * 0.4f, style = Stroke(stroke * 0.75f))
                line(0.5f, 0.45f, 0.5f, 0.69f)
                drawCircle(color, stroke * 0.5f, Offset(w * 0.5f, h * 0.29f))
            }
        }
    }
}

@Composable
private fun MountainBackground() {
    Canvas(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF061225), Color(0xFF122640), Color(0xFF03080F))))) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0xFF2D5381).copy(alpha = 0.6f), Color.Transparent), center = Offset(w * 0.03f, h * 0.05f), radius = w * 0.65f))
        fun ridge(points: List<Pair<Float, Float>>, color: Color) {
            val path = Path().apply {
                moveTo(0f, h)
                points.forEach { (x, y) -> lineTo(w * x, h * y) }
                lineTo(w, h)
                close()
            }
            drawPath(path, color)
        }
        ridge(listOf(0f to .38f, .07f to .32f, .13f to .37f, .23f to .24f, .27f to .29f, .32f to .23f, .39f to .34f, .44f to .21f, .46f to .23f, .49f to .19f, .53f to .31f, .61f to .25f, .65f to .28f, .7f to .19f, .73f to .22f, .78f to .35f, .86f to .24f, .9f to .3f, 1f to .22f), Color(0xFF0A192B))
        ridge(listOf(0f to .47f, .12f to .42f, .18f to .47f, .3f to .38f, .38f to .46f, .51f to .36f, .59f to .45f, .65f to .35f, .78f to .47f, .87f to .39f, 1f to .48f), Color(0xFF081422))
        ridge(listOf(0f to .74f, .1f to .67f, .17f to .7f, .26f to .62f, .38f to .71f, .5f to .6f, .66f to .68f, .76f to .62f, .89f to .7f, 1f to .61f), Color(0xFF050D17))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD902060B))))
    }
}
