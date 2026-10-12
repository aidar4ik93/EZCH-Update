package com.example.homeezch

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Device-local setup; all privileged changes require an explicit button press. */
class HomeSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeSetupPanel {
                    getSharedPreferences("home_setup", Context.MODE_PRIVATE).edit()
                        .putBoolean("setup_reviewed_v24", true).apply()
                    finish()
                }
            }
        }
    }
}

@Composable
private fun HomeSetupPanel(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(readHomeSetup(context)) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("home_setup", Context.MODE_PRIVATE)
        if (!state.effectiveHome && !prefs.getBoolean("home_system_prompt_v26", false)) {
            prefs.edit().putBoolean("home_system_prompt_v26", true).apply()
            val intent = HomeRole.requestIntent(context)
            if (intent != null) SettingsRouter.open(context, listOf(intent))
            else notice = "Прошивка не предоставляет штатный выбор HOME. Назначение не подтверждено."
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = readHomeSetup(context)
                resumed = true
            } else if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // A service may connect just after Settings returns. Poll only while this screen is visible.
    LaunchedEffect(resumed) {
        if (resumed) while (true) {
            state = readHomeSetup(context)
            delay(1000)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(28.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Настройка EZCH", style = MaterialTheme.typography.headlineSmall)
            Text("Состояние проверяется автоматически после возврата из настроек.")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Главный экран: " + when {
                    state.effectiveHome -> "Android открывает EZCH"
                    state.roleMismatch -> "роль получена, но Android выбирает другой экран"
                    else -> "назначение не подтверждено"
                })
                Button(onClick = {
                    val intent = HomeRole.requestIntent(context)
                    if (intent != null) SettingsRouter.open(context, listOf(intent))
                    else notice = "Прошивка ограничивает выбор HOME. Откройте системные настройки; назначение не подтверждено."
                }, enabled = !state.effectiveHome) { Text("Выбрать EZCH главным экраном") }
                Text("Служба кнопки Home: " + when {
                    state.serviceConnected -> "подключена к Android"
                    state.serviceEnabled -> "разрешена, но подключение не подтверждено"
                    else -> "выключена"
                })
                if (!state.effectiveHome) Text("Если телевизор удерживает штатный экран, включите службу EZCH. После этого проверьте кнопку Home на пульте: некоторые прошивки блокируют перенаправление.")
                Button(onClick = {
                    SettingsRouter.accessibility(context)
                    state = readHomeSetup(context)
                }) { Text(if (state.serviceConnected) "Настройки службы Home" else "Включить службу Home") }
                if (!state.serviceEnabled) {
                    Text("Если Android пишет «Ограниченная настройка», откройте свойства EZCH и разрешите ограниченные настройки, если такой пункт есть. Затем вернитесь и включите службу. Это подтверждение требует владельца телевизора.")
                    OutlinedButton(onClick = { SettingsRouter.appDetails(context) }) { Text("Открыть свойства EZCH") }
                }
                Text("Установка приложений: " + if (state.canInstall) "разрешена" else "нужно разрешение Android")
                Button(onClick = { SettingsRouter.installPermission(context) }, enabled = !state.canInstall) {
                    Text("Разрешить установку из EZCH")
                }
                if (state.homeAvailable && !state.effectiveHome) Text("Служба подключена. Это не подтверждение назначения EZCH системным HOME.")
                if (notice.isNotEmpty()) Text(notice)
            }
            Button(onClick = onClose) { Text("К рабочему столу") }
        }
    }
}
