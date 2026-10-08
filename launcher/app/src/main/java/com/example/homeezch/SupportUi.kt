package com.example.homeezch

import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Ice = Color(0xFF72C7FF)
internal val Gold = Color(0xFFFFC474)
internal val Muted = Color(0xFFB6C6DA)
internal val Night = Color(0xFF050A14)
internal fun android.app.Activity.immersiveDesktop() {
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}
@Composable internal fun InfoPanel(message: String) { Text(message, color = Muted) }

@Composable internal fun TvAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
}
