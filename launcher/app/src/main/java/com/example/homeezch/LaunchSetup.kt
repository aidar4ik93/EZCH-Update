package com.example.homeezch

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/** Never restore an old device's setup result or treat opening Settings as success. */
@Composable
internal fun LaunchSetup(onReady: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("home_setup", Context.MODE_PRIVATE)
        val state = readHomeSetup(context)
        if (!prefs.getBoolean("setup_reviewed_v24", false) &&
            !(state.effectiveHome && state.canInstall)) {
            HomeRole.request(context)
        }
        onReady()
    }
}
