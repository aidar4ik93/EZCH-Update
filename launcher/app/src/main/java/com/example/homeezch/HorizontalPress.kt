package com.example.homeezch

/** Keep fresh presses; cap held-button repeats to one tile per 100 ms. */
internal class HorizontalPress {
    private var lastAccepted = Long.MIN_VALUE
    fun accept(time: Long, repeat: Int): Boolean {
        if (repeat > 0 && lastAccepted != Long.MIN_VALUE && time - lastAccepted in 0 until 100) return false
        lastAccepted = time
        return true
    }
}
