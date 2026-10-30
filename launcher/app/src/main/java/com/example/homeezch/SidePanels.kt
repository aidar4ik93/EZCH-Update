package com.example.homeezch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

internal enum class PanelEdge { LEFT, RIGHT }

/** A modal window isolates remote focus from the desktop while a panel is open. */
@Composable
internal fun SidePanel(
    title: String, edge: PanelEdge, tag: String,
    firstFocus: FocusRequester, closeModifier: Modifier = Modifier,
    onBackWhileEditing: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val editingBack by rememberUpdatedState(onBackWhileEditing)
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var leftActivity by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) leftActivity = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && leftActivity) {
                leftActivity = false
                val target = returnFocus
                returnFocus = null
                if (target != null) scope.launch {
                    inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                    withFrameNanos { }; withFrameNanos { }
                    runCatching { target.requestFocus() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun close() { visible.targetState = false }
    // The dialog callback must see an edit started after the panel was opened.
    fun back() { val cancelEdit = editingBack; if (cancelEdit != null) cancelEdit() else close() }
    LaunchedEffect(visible.currentState, visible.targetState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismiss()
    }
    Dialog(onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("$tag-overlay")) {
            val panelWidth = (maxWidth * .46f).coerceIn(300.dp, 420.dp).coerceAtMost(maxWidth)
            Box(Modifier.fillMaxSize().background(Color(0x88000611))
                .focusProperties { canFocus = false }.clickable(onClick = ::close)
                .testTag("$tag-scrim"))
            AnimatedVisibility(visibleState = visible,
                modifier = Modifier.align(if (edge == PanelEdge.LEFT) Alignment.CenterStart else Alignment.CenterEnd),
                enter = slideInHorizontally(tween(240)) { if (edge == PanelEdge.LEFT) -it else it },
                exit = slideOutHorizontally(tween(200)) { if (edge == PanelEdge.LEFT) -it else it }) {
                val shape = if (edge == PanelEdge.LEFT) RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                    else RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                Surface(Modifier.width(panelWidth).fillMaxHeight().testTag(tag),
                    shape = shape, color = Color(0xF5102035), shadowElevation = 16.dp) {
                    Column(Modifier.fillMaxSize().focusGroup().padding(
                        start = if (edge == PanelEdge.LEFT) (maxWidth * .05f).coerceAtLeast(22.dp) else 22.dp,
                        end = if (edge == PanelEdge.RIGHT) (maxWidth * .05f).coerceAtLeast(22.dp) else 22.dp,
                        top = 16.dp, bottom = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = ::close, modifier = closeModifier.testTag("$tag-close")) {
                                Text("Закрыть", color = Ice, fontSize = 12.sp)
                            }
                        }
                        CompositionLocalProvider(LocalSettingsReturnFocus provides { returnFocus = it }) { content() }
                        Text("↑ ↓ выбор  ·  OK открыть  ·  Back закрыть", color = Muted, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PanelLaunchButton(tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "panelButtonFocus")
    val label = if (tv) "Открыть разъёмы" else "Открыть настройки"
    Column(modifier.size(if (tv) 112.dp else 62.dp, if (tv) 94.dp else 62.dp)
        .testTag(if (tv) "sources-launcher" else "settings-launcher")
        .semantics { contentDescription = label }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(DesktopShape)
        .background(Brush.verticalGradient(listOf(Color(0xC525384D), Color(0xB0081424))), DesktopShape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Ice else Color(0xFF354C62), DesktopShape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClickLabel = label, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (tv) {
            PortIcon(TvSourceKind.TV, color = Ice)
            Text("ТВ", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
        } else GlassIcon("Настройки", color = Ice)
    }
}

