package com.example.ezchupdate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.ui.CatalogScreen
import com.example.ezchupdate.ui.EzchTheme

class MainActivity : ComponentActivity() {
    private val catalogViewModel: CatalogViewModel by viewModels()
    private val installer = AppInstaller()
    private var awaitingInstallPermission = false
    private var launchedConfirmationSession: Int? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (catalogViewModel.needsInstallPermission(this)) catalogViewModel.permissionDenied()
            else catalogViewModel.installSelected(this)
        }
        catalogViewModel.refreshInstalled()
    }

    private val confirmationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        catalogViewModel.refreshInstalled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingInstallPermission = savedInstanceState?.getBoolean("awaiting_install_permission") ?: false
        launchedConfirmationSession = savedInstanceState?.getInt("confirmation_session", -1)?.takeIf { it >= 0 }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            EzchTheme {
                val state by catalogViewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
                LaunchedEffect(state.pendingSessionId, lifecycleState) {
                    val sessionId = state.pendingSessionId
                    if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && sessionId != null && sessionId != launchedConfirmationSession) {
                        launchConfirmation(sessionId)
                    }
                }
                CatalogScreen(
                    state = state,
                    onReload = catalogViewModel::reload,
                    onToggle = catalogViewModel::toggleSelection,
                    onInstall = ::startInstallation,
                    onCancel = catalogViewModel::cancelInstall,
                    onConfirm = { state.pendingSessionId?.let(::launchConfirmation) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        catalogViewModel.refreshInstalled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaiting_install_permission", awaitingInstallPermission)
        outState.putInt("confirmation_session", launchedConfirmationSession ?: -1)
        super.onSaveInstanceState(outState)
    }

    private fun startInstallation() {
        if (catalogViewModel.needsInstallPermission(this)) {
            try {
                awaitingInstallPermission = true
                permissionLauncher.launch(catalogViewModel.unknownSourcesSettingsIntent(this))
            } catch (_: Exception) {
                awaitingInstallPermission = false
                catalogViewModel.permissionDenied()
            }
        } else catalogViewModel.installSelected(this)
    }

    private fun launchConfirmation(sessionId: Int) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val confirmation = installer.confirmationIntent(this, sessionId) ?: return
        try {
            launchedConfirmationSession = sessionId
            confirmationLauncher.launch(confirmation)
        } catch (error: Exception) {
            installer.confirmationLaunchFailed(this, sessionId, error.message)
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
