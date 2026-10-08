package com.example.homeezch

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** A visible setup prompt; completion is distinct from actually receiving HOME. */
@Composable
internal fun LaunchSetup(onReady: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("home_setup", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!HomeRole.isHome(context) && !prefs.getBoolean("home_prompt_v09", false)) }
    var waiting by remember { mutableStateOf(false) }
    fun finish() {
        prefs.edit().putBoolean("home_prompt_v09", true).apply()
        show = false; waiting = false
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    LaunchedEffect(show, waiting) { if (!show && !waiting) onReady() }
    if (show) AlertDialog(
        onDismissRequest = { if (!waiting) finish() },
        title = { Text("Сделать EZCH главным экраном?") },
        text = { Text("Выберите EZCH Launcher в следующем системном окне и подтвердите. Установщик и файловый менеджер уже внутри. Если прошивка скрывает выбор HOME, настройте службу кнопки Home в настройках EZCH. Назначение можно повторить позже.") },
        confirmButton = { TextButton(enabled = !waiting, onClick = {
            HomeAccessibility.enable(context)
            val intent = HomeRole.requestIntent(context)
            if (intent == null) {
                SettingsRouter.accessibility(context)
                finish()
            } else {
                waiting = true
                runCatching { request.launch(intent) }.onFailure { waiting = false; HomeRole.request(context); finish() }
            }
        }) { Text("Назначить главным") } },
        dismissButton = { TextButton(enabled = !waiting, onClick = { finish() }) { Text("Позже") } }
    )
}
