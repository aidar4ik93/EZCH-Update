package com.example.homeezch

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** The opening hold belongs to the card, never to the first menu action. */
@Composable
internal fun CardActionPopup(name: String, source: Boolean, bounds: Rect?, press: CenterPress,
    onDismiss: () -> Unit, onMove: () -> Unit, onHide: () -> Unit) {
    var waitingForRelease by remember { mutableStateOf(press.isPressed) }
    val first = remember { FocusRequester() }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val position = remember(bounds, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val card = bounds ?: Rect(anchorBounds.left.toFloat(), anchorBounds.top.toFloat(),
                    anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat())
                val x = (card.center.x - popupContentSize.width / 2).toInt()
                    .coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val y = (card.top.toInt() - popupContentSize.height - gap)
                    .coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.width(280.dp)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val center = key.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
                if (center && waitingForRelease) {
                    if (key.action == KeyEvent.ACTION_UP) { waitingForRelease = false; press.reset() }
                    else if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        waitingForRelease = false; press.reset(); return@onPreviewKeyEvent false
                    }
                    true
                } else center && key.action == KeyEvent.ACTION_DOWN && key.repeatCount > 0
            }
            .background(Brush.verticalGradient(listOf(Color(0xC8233853), Color(0xC8101C30))), shape)
            .border(1.dp, Color(0x668DC6FF), shape).padding(12.dp)) {
            Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
            CardMenuAction(if (source) "Переместить разъём" else "Переместить приложение", "↔",
                Modifier.focusRequester(first), onMove)
            Spacer(Modifier.height(5.dp))
            CardMenuAction("Скрыть с рабочего стола", "⊖", Modifier, onHide)
        }
        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    }
}

@Composable
private fun CardMenuAction(title: String, icon: String, modifier: Modifier, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    TextButton(onClick = action, modifier = modifier.fillMaxWidth().height(46.dp)
        .onFocusChanged { focused = it.isFocused }
        .background(if (focused) Color(0x55589CDB) else Color.Transparent, shape)
        .border(1.dp, if (focused) Color(0xFFB6E2FF) else Color.Transparent, shape), shape = shape,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
