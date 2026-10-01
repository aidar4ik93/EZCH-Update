package com.example.ezchupdate

import android.content.Context
import android.content.pm.PackageInstaller
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ezchupdate.data.CatalogRepository
import com.example.ezchupdate.data.InstalledApp
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

data class AppRow(val app: RemoteApp, val installed: InstalledApp?) {
    val updateAvailable: Boolean get() = installed == null || installed.versionCode < app.versionCode
}

data class CatalogUiState(
    val rows: List<AppRow> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val installing: RemoteApp? = null,
    val message: String? = null
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CatalogRepository()
    private val installer = AppInstaller()
    private val installQueue = ArrayDeque<RemoteApp>()
    private var installContext: Context? = null

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState

    init {
        viewModelScope.launch {
            InstallEvents.results.collect(::handleInstallResult)
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val beforeLoad = _uiState.value
            _uiState.value = beforeLoad.copy(isLoading = true, message = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.load().map { app ->
                        AppRow(app, repository.installedApp(getApplicationPackageManager(), app.packageName))
                    }
                }
            }.onSuccess { rows ->
                val current = _uiState.value
                _uiState.value = current.copy(
                    rows = rows,
                    selectedPackages = current.selectedPackages.intersect(rows.map { it.app.packageName }.toSet()),
                    isLoading = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = error.message ?: "Не удалось загрузить каталог"
                )
            }
        }
    }

    fun toggleSelection(packageName: String) {
        val selected = _uiState.value.selectedPackages.toMutableSet()
        if (!selected.add(packageName)) selected.remove(packageName)
        _uiState.value = _uiState.value.copy(selectedPackages = selected)
    }

    fun hasSelection(): Boolean = _uiState.value.selectedPackages.isNotEmpty()

    fun needsInstallPermission(context: Context): Boolean =
        hasSelection() && !installer.canRequestPackageInstalls(context)

    fun openUnknownSourcesSettings(context: Context) {
        context.startActivity(installer.unknownSourcesSettingsIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun installSelected(context: Context) {
        if (!installer.canRequestPackageInstalls(context)) {
            _uiState.value = _uiState.value.copy(
                message = "Разрешите EZCH Update устанавливать приложения в настройках Android"
            )
            return
        }
        if (installQueue.isNotEmpty() || _uiState.value.installing != null) return

        val selected = _uiState.value.selectedPackages
        installQueue.addAll(_uiState.value.rows.map { it.app }.filter { it.packageName in selected })
        if (installQueue.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "Сначала выберите приложения")
            return
        }
        installContext = context.applicationContext
        installNext()
    }

    private fun installNext() {
        val context = installContext ?: return
        val next = if (installQueue.isEmpty()) null else installQueue.removeFirst()
        if (next == null) {
            installContext = null
            _uiState.value = _uiState.value.copy(
                installing = null,
                selectedPackages = emptySet(),
                message = "Очередь установки завершена"
            )
            reload()
            return
        }

        _uiState.value = _uiState.value.copy(installing = next, message = "Скачивание ${next.name}")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.install(context, next) }
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    installing = null,
                    message = "${next.name}: ${error.message ?: "ошибка установки"}"
                )
                installNext()
            }
        }
    }

    private fun handleInstallResult(result: com.example.ezchupdate.install.InstallResult) {
        val current = _uiState.value.installing ?: return
        if (current.packageName != result.packageName) return

        val message = if (result.status == PackageInstaller.STATUS_SUCCESS) {
            "${current.name} установлен"
        } else {
            "${current.name}: ${result.message ?: "Android отклонил установку"}"
        }
        _uiState.value = _uiState.value.copy(installing = null, message = message)
        installNext()
    }

    private fun getApplicationPackageManager() = getApplication<Application>().packageManager
}
