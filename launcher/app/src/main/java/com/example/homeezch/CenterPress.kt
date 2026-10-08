package com.example.homeezch

/** One physical press produces one action, including across item recomposition. */
internal class CenterPress {
    enum class Action { NONE, CLICK, LONG_PRESS, CONFIRM }
    private var pressedKey: Int? = null
    private var startedAt = 0L
    private var longPressed = false
    private var editingAtDown = false
    fun reset() { pressedKey = null; longPressed = false }
    fun handle(key: Int, down: Boolean, repeat: Int, time: Long, canceled: Boolean, moving: Boolean, longSignal: Boolean = false): Action {
        if (down) {
            if (repeat == 0) {
                pressedKey = key; startedAt = time; longPressed = false; editingAtDown = moving
            } else if (pressedKey == key && !editingAtDown && !longPressed && (longSignal || time - startedAt >= 450L)) {
                longPressed = true; return Action.LONG_PRESS
            }
            return Action.NONE
        }
        if (pressedKey != key) return Action.NONE
        val result = when {
            canceled || longPressed -> Action.NONE
            editingAtDown && moving -> Action.CONFIRM
            !editingAtDown && moving -> Action.NONE
            !editingAtDown && (longSignal || time - startedAt >= 450L) -> Action.LONG_PRESS
            !editingAtDown -> Action.CLICK
            else -> Action.NONE
        }
        reset()
        return result
    }
}
