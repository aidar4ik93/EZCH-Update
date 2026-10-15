package com.example.ezchupdate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ezchupdate.install.InstallEvents
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: CatalogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateSettings(this).schedule()

        // Android confirmation is launched only by the resumed foreground Activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.uiState.collect { viewModel.openPreparedLauncher(this@MainActivity) }
                }
                launch {
                    while (isActive) {
                        delay(5 * 60 * 1000L)
                        viewModel.refreshCatalogIfIdle()
                    }
                }
                InstallEvents.snapshot.collect { snapshot ->
                    val confirmation = snapshot?.confirmationIntent ?: return@collect
                    try {
                        startActivity(Intent(confirmation))
                        InstallEvents.consumeConfirmation(this@MainActivity, snapshot.sessionId)
                    } catch (error: ActivityNotFoundException) {
                        InstallEvents.consumeConfirmation(this@MainActivity, snapshot.sessionId)
                        viewModel.onConfirmationLaunchFailed(
                            snapshot.sessionId,
                            error.message ?: "На устройстве нет системного установщика"
                        )
                    } catch (error: SecurityException) {
                        InstallEvents.consumeConfirmation(this@MainActivity, snapshot.sessionId)
                        viewModel.onConfirmationLaunchFailed(
                            snapshot.sessionId,
                            error.message ?: "Android не разрешил открыть подтверждение установки"
                        )
                    }
                }
            }
        }

        setContent {
            PremiumTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.isLoading) {
                    if (!state.isLoading) {
                        intent.getStringExtra("selected_package")?.let { selected ->
                            intent.removeExtra("selected_package")
                            if (selected !in state.selectedPackages) viewModel.toggleSelection(selected)
                        }
                    }
                }
                PremiumCatalogScreen(
                    state = state,
                    onReload = viewModel::reload,
                    onToggle = viewModel::toggleSelection,
                    onInstall = { viewModel.installSelected(this) },
                    onLauncher = { viewModel.setupLauncher(this) },
                    onCancel = viewModel::cancelInstallation
                )
            }
        }
    }

    override fun onPause() {
        viewModel.onHostPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onHostResumed(this)
    }
}
