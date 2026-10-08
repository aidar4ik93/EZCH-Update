package com.example.homeezch

import android.view.KeyEvent as NativeKey
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

internal fun Modifier.remoteInput(
    moving: Boolean, blocked: Boolean, press: CenterPress,
    onClick: () -> Unit, onMenu: () -> Unit, onLong: () -> Unit,
    onConfirm: () -> Unit, onMove: (Int) -> Unit
): Modifier = composed {
    val currentClick by rememberUpdatedState(onClick)
    val currentLong by rememberUpdatedState(onLong)
    val currentConfirm by rememberUpdatedState(onConfirm)
    onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val center = key.keyCode == NativeKey.KEYCODE_DPAD_CENTER || key.keyCode == NativeKey.KEYCODE_ENTER || key.keyCode == NativeKey.KEYCODE_NUMPAD_ENTER
        when {
            key.keyCode == NativeKey.KEYCODE_BACK -> { press.reset(); false }
            blocked -> { press.reset(); true }
            key.keyCode == NativeKey.KEYCODE_MENU -> {
                press.reset()
                if (key.action == NativeKey.ACTION_DOWN && key.repeatCount == 0) onMenu()
                true
            }
            center -> {
                when (press.handle(key.keyCode, key.action == NativeKey.ACTION_DOWN, key.repeatCount,
                    key.eventTime, key.isCanceled, moving, longSignal = key.isLongPress)) {
                    CenterPress.Action.CLICK -> currentClick()
                    CenterPress.Action.LONG_PRESS -> currentLong()
                    CenterPress.Action.CONFIRM -> currentConfirm()
                    CenterPress.Action.NONE -> Unit
                }
                true
            }
            moving -> {
                if (key.action == NativeKey.ACTION_DOWN) onMove(key.keyCode)
                true
            }
            else -> false
        }
    }
}
