package com.example.ezchupdate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EzchTheme {
                val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<CatalogViewModel>()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CatalogScreen(
                    state = state,
                    onReload = viewModel::reload,
                    onToggle = viewModel::toggleSelection,
                    onInstall = {
                        if (viewModel.needsInstallPermission(this)) {
                            viewModel.openUnknownSourcesSettings(this)
                        } else {
                            viewModel.installSelected(this)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EzchTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF80CBC4),
        secondary = Color(0xFFB39DDB),
        background = Color(0xFF08111B),
        surface = Color(0xFF12202D),
        surfaceVariant = Color(0xFF203342)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun CatalogScreen(
    state: CatalogUiState,
    onReload: () -> Unit,
    onToggle: (String) -> Unit,
    onInstall: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("EZCH Update", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Каталог приложений для Android TV", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReload, enabled = !state.isLoading && state.installing == null) {
                        Text("Проверить обновления")
                    }
                    Button(
                        onClick = onInstall,
                        enabled = state.selectedPackages.isNotEmpty() && state.installing == null
                    ) {
                        Text("Установить выбранные (${state.selectedPackages.size})")
                    }
                }
            }

            state.installing?.let { app ->
                Spacer(Modifier.height(18.dp))
                Text("Выполняется: ${app.name}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            state.message?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(message, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(22.dp))

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Каталог пока пуст")
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(state.rows, key = { it.app.packageName }) { row ->
                        AppCard(
                            row = row,
                            selected = row.app.packageName in state.selectedPackages,
                            onClick = { onToggle(row.app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(row: AppRow, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(140),
        label = "cardScale"
    )
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondary
        else -> Color.Transparent
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .height(190.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clip(CardDefaults.shape)
            .border(3.dp, borderColor, CardDefaults.shape)
            .focusable(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = row.app.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Column {
                Text("Версия: ${row.app.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val status = when {
                    row.installed == null -> "Не установлено"
                    row.updateAvailable -> "Доступно обновление"
                    else -> "Установлено"
                }
                Text(
                    status,
                    color = if (row.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                if (selected) {
                    Spacer(Modifier.height(6.dp))
                    Text("Выбрано", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
