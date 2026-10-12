package com.example.homeezch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Includes hidden apps so hiding never makes an installed app inaccessible. */
@Composable
internal fun AllAppsDialog(
    apps: List<LaunchApp>, hidden: List<String>, saving: Boolean,
    onDismiss: () -> Unit, onLaunch: (LaunchApp) -> Unit, onToggle: (LaunchApp) -> Unit,
    removed: List<String> = emptyList(), onPlace: ((LaunchApp) -> Unit)? = null
) {
    val first = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.name.contains(query, ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = DesktopShape,
            color = androidx.compose.ui.graphics.Color(0xF0081222)) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Все приложения", fontSize = 24.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                Text("OK — открыть · Скрытые приложения тоже доступны здесь", color = Muted, fontSize = 12.sp)
                OutlinedTextField(query, { query = it.take(80) }, singleLine = true,
                    label = { Text("Поиск приложений") }, modifier = Modifier.fillMaxWidth())
                if (filtered.isEmpty()) Text("Ничего не найдено")
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(GridCells.Adaptive(150.dp * AppCardScale), Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(filtered, key = { _, app -> app.component?.packageName ?: "ezch-store" }) { index, app ->
                        val key = app.component?.packageName ?: "ezch-store"
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppTile(app, 140.dp,
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                onFocus = {}, onClick = { onLaunch(app) })
                            TextButton(enabled = !saving, onClick = { onToggle(app) }) {
                                Text(when { key in removed -> "Добавить на рабочий стол"
                                    key in hidden -> "Вернуть на рабочий стол"
                                    else -> "Скрыть с рабочего стола" }, fontSize = 11.sp)
                            }
                            onPlace?.let { place -> TextButton(enabled = !saving, onClick = { place(app) }) {
                                Text("Выбрать строку", fontSize = 11.sp)
                            } }
                        }
                    }
                }
                LaunchedEffect(apps.isNotEmpty()) {
                    if (filtered.isNotEmpty()) { withFrameNanos { }; first.requestFocus() }
                }
            }
        }
    }
}
