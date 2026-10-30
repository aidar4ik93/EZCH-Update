package com.example.homeezch

/** Android scanCode is the Linux key code, not the HID MSC_SCAN usage from getevent. */
internal data class RemoteButtonId(val device: String, val key: Int, val scan: Int) {
    // BT21 reports several distinct HID usages as Linux KEY_UNKNOWN (240).
    // Never bind that collapsed code: every one of those buttons would launch the same app.
    fun assignable(): Boolean = device.isNotBlank() && (key != 0 || scan > 0) &&
        !(key == 0 && scan == 240) &&
        key !in setOf(3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 82, 85, 86, 87, 88,
            89, 90, 91, 126, 127, 164, 219, 231) // HOME, navigation, media, volume, voice
    fun storageKey(): String = "$device:$key:$scan"
}
