package com.example.ezchupdate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ezchupdate.data.CatalogRepository
import com.example.ezchupdate.data.CatalogSource
import com.example.ezchupdate.data.InstalledApp
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallEvents
import com.example.ezchupdate.install.InstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppRow(val app: RemoteApp, val installed: InstalledApp?) {
    val updateAvailable: Boolean get() = installed == null || installed.versionCode < app.versionCode
}
data class CatalogUiState(
    val rows: List<AppRow> = emptyList(), val selectedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true, val installing: RemoteApp? = null, val message: String? = null,
    val isError: Boolean = false, val isOffline: Boolean = false, val lastChecked: String? = null,
    val installProgress: Float? = null, val downloadedBytes: Long = 0, val totalBytes: Long? = null,
    val queueIndex: Int = 0, val queueTotal: Int = 0, val installStage: String? = null,
    val pendingSessionId: Int? = null
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = CatalogRepository(application)
    private val installer = AppInstaller()
    private val preferences = application.getSharedPreferences("installation_queue", Context.MODE_PRIVATE)
    private var queue: InstallationQueue? = null
    private var sessionId: Int? = null
    private var downloadJob: Job? = null
    private var reloadJob: Job? = null
    private var refreshJob: Job? = null
    @Volatile private var generation = 0
    private var handlingResult = false
    private var queueStartedAt = 0L
    private val _uiState = MutableStateFlow(CatalogUiState(selectedPackages = preferences.getStringSet("selection", emptySet()).orEmpty().toSet()))
    val uiState: StateFlow<CatalogUiState> = _uiState

    init {
        restoreQueue()
        viewModelScope.launch {
            uiState.map { it.selectedPackages }.distinctUntilChanged().collect {
                preferences.edit().putStringSet("selection", it).commit()
            }
        }
        viewModelScope.launch {
            InstallEvents.observe(appContext).collect { results ->
                results.firstOrNull { it.sessionId == sessionId }?.let { handleInstallResult(it) }
            }
        }
        reload()
    }

    fun reload() {
        if (reloadJob?.isActive == true) return
        reloadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val loaded = withContext(Dispatchers.IO) { repository.loadCatalog() }
                val rows = withContext(Dispatchers.IO) { rowsFor(loaded.apps) }
                val available = rows.filter { it.updateAvailable }.map { it.app.packageName }.toSet()
                _uiState.update {
                    it.copy(rows = rows, selectedPackages = it.selectedPackages.intersect(available), isLoading = false,
                        isOffline = loaded.source != CatalogSource.NETWORK,
                        lastChecked = if (loaded.source == CatalogSource.NETWORK) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) else it.lastChecked,
                        message = loaded.warning ?: it.message, isError = if (loaded.warning != null) true else it.isError)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, message = error.message ?: "Не удалось загрузить каталог", isError = true) }
            }
        }
    }

    fun refreshInstalled() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { rowsFor(_uiState.value.rows.map { it.app }) }
            val available = rows.filter { it.updateAvailable }.map { it.app.packageName }.toSet()
            _uiState.update { it.copy(rows = rows, selectedPackages = it.selectedPackages.intersect(available)) }
            val activeId = sessionId
            val current = queue?.current
            if (activeId != null && current != null && !handlingResult) {
                val retained = InstallEvents.observe(appContext).value.firstOrNull { it.sessionId == activeId }
                if (retained != null) handleInstallResult(retained)
                else if (appContext.packageManager.packageInstaller.getSessionInfo(activeId) == null) {
                    val installed = repository.installedApp(appContext.packageManager, current.packageName)
                    finishCurrent(if (installed != null && installed.versionCode >= current.versionCode) null
                        else "Android завершил сеанс без результата. Повторите установку")
                }
            }
        }
    }

    fun toggleSelection(packageName: String) {
        if (_uiState.value.installing != null) return
        val row = _uiState.value.rows.firstOrNull { it.app.packageName == packageName } ?: return
        if (!row.updateAvailable) {
            _uiState.update { it.copy(message = "${row.app.name}: установлена актуальная или более новая версия", isError = false) }
            return
        }
        _uiState.update {
            val selected = it.selectedPackages.toMutableSet()
            if (!selected.add(packageName)) selected.remove(packageName)
            it.copy(selectedPackages = selected, message = null, isError = false)
        }
    }
    fun hasSelection() = _uiState.value.selectedPackages.isNotEmpty()
    fun needsInstallPermission(context: Context) = hasSelection() && !installer.canRequestPackageInstalls(context)
    fun unknownSourcesSettingsIntent(context: Context): Intent {
        preferences.edit().putStringSet("selection", _uiState.value.selectedPackages).commit()
        return installer.unknownSourcesSettingsIntent(context)
    }
    fun openUnknownSourcesSettings(context: Context) { context.startActivity(unknownSourcesSettingsIntent(context)) }
    fun permissionDenied() {
        _uiState.update { it.copy(message = "Разрешение не предоставлено. Выбранные приложения сохранены; разрешите установку в настройках Android", isError = true) }
    }
    fun installSelected(context: Context) {
        if (_uiState.value.installing != null || _uiState.value.isLoading) return
        if (!installer.canRequestPackageInstalls(context)) { permissionDenied(); return }
        val selected = _uiState.value.selectedPackages
        val apps = _uiState.value.rows.filter { it.updateAvailable && it.app.packageName in selected }.map { it.app }
        if (apps.isEmpty()) {
            _uiState.update { it.copy(message = "Сначала выберите приложения для установки", isError = false) }; return
        }
        generation++
        queueStartedAt = System.currentTimeMillis()
        queue = InstallationQueue.create(apps, appContext.packageName)
        _uiState.update { it.copy(queueTotal = apps.size, message = null, isError = false) }
        installNext()
    }
    fun cancelInstall() {
        val activeQueue = queue ?: return
        val activeId = sessionId
        generation++
        sessionId = null
        queue = null
        downloadJob?.cancel()
        downloadJob = null
        preferences.edit().remove("queue").commit()
        val outstanding = activeQueue.apps.drop(activeQueue.index).map { it.packageName }.toSet()
        _uiState.update { clearProgress(it).copy(selectedPackages = outstanding,
            message = "Очередь отменена. Установлено: ${activeQueue.succeeded}. Завершённые установки сохранены", isError = false) }
        val ownedSessions = InstallEvents.activeSessions(appContext).map { it.sessionId }.toSet() + listOfNotNull(activeId)
        ownedSessions.forEach { installer.cancel(appContext, it) }
        refreshInstalled()
    }

    private fun installNext() {
        val activeQueue = queue ?: return
        val current = activeQueue.current
        if (current == null) {
            queue = null
            sessionId = null
            preferences.edit().remove("queue").commit()
            val failures = activeQueue.failures
            val detail = failures.entries.joinToString("; ") { (pkg, reason) -> "${activeQueue.apps.first { it.packageName == pkg }.name}: $reason" }
            _uiState.update { clearProgress(it).copy(selectedPackages = failures.keys.toSet(),
                message = "Установлено: ${activeQueue.succeeded} из ${activeQueue.apps.size}." +
                    if (failures.isEmpty()) " Очередь завершена" else " Ошибки: $detail", isError = failures.isNotEmpty()) }
            refreshInstalled()
            return
        }
        sessionId = null
        persistQueue()
        val operation = generation
        _uiState.update { it.copy(installing = current, queueIndex = activeQueue.index + 1,
            queueTotal = activeQueue.apps.size, installStage = "Получение ссылки на APK…", installProgress = null,
            downloadedBytes = 0, totalBytes = null, pendingSessionId = null) }
        downloadJob = viewModelScope.launch {
            try {
                val submission = installer.install(appContext, current) { progress ->
                    if (generation == operation) _uiState.update {
                        it.copy(installStage = when (progress.stage.name) {
                            "RESOLVING" -> "Получение ссылки на APK…"
                            "DOWNLOADING" -> "Скачивание…"
                            "VERIFYING" -> "Проверка APK и подписи…"
                            else -> "Подготовка установки…"
                        }, downloadedBytes = progress.bytesDownloaded, totalBytes = progress.totalBytes,
                            installProgress = progress.totalBytes?.takeIf { total -> total > 0 }?.let { total ->
                                (progress.bytesDownloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) })
                    }
                }
                if (generation != operation) { installer.cancel(appContext, submission.sessionId); return@launch }
                sessionId = submission.sessionId
                persistQueue()
                _uiState.update { it.copy(installStage = "Ожидание результата Android…", installProgress = null) }
                InstallEvents.observe(appContext).value.firstOrNull { it.sessionId == sessionId }?.let { handleInstallResult(it) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (generation == operation) finishCurrent(error.message ?: "Не удалось установить приложение")
            }
        }
    }
    private suspend fun handleInstallResult(result: InstallResult) {
        val current = queue?.current ?: return
        if (sessionId != result.sessionId || current.packageName != result.packageName || handlingResult) return
        if (result.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _uiState.update { it.copy(pendingSessionId = result.sessionId,
                installStage = "Подтвердите установку в окне Android", installProgress = null) }; return
        }
        handlingResult = true
        val operation = generation
        try {
            var error: String? = result.message ?: "Android отклонил установку"
            if (result.status == PackageInstaller.STATUS_SUCCESS) {
                var verified = false
                for (attempt in 0 until 8) {
                    val installed = withContext(Dispatchers.IO) { repository.installedApp(appContext.packageManager, current.packageName) }
                    if (installed != null && installed.versionCode >= current.versionCode) { verified = true; break }
                    delay(250)
                }
                error = if (verified) null else "Android сообщил об успехе, но новая версия не найдена. Проверьте приложение"
            }
            if (operation == generation) finishCurrent(error)
        } finally { handlingResult = false }
    }
    private fun finishCurrent(error: String?) {
        val activeQueue = queue ?: return
        val current = activeQueue.current ?: return
        val completedSession = sessionId
        sessionId = null
        activeQueue.finish(current.packageName, error)
        persistQueue()
        if (completedSession != null) InstallEvents.acknowledge(appContext, completedSession)
        _uiState.update { it.copy(pendingSessionId = null,
            selectedPackages = if (error == null) it.selectedPackages - current.packageName else it.selectedPackages) }
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { rowsFor(_uiState.value.rows.map { it.app }) }
            _uiState.update { it.copy(rows = rows) }
        }
        installNext()
    }
    private fun rowsFor(apps: List<RemoteApp>) = apps.map { AppRow(it, repository.installedApp(appContext.packageManager, it.packageName)) }
    private fun clearProgress(state: CatalogUiState) = state.copy(installing = null, pendingSessionId = null,
        installStage = null, installProgress = null, downloadedBytes = 0, totalBytes = null, queueIndex = 0, queueTotal = 0)
    private fun persistQueue() {
        val current = queue ?: return
        val apps = JSONArray()
        current.apps.forEach { app ->
            apps.put(JSONObject().put("name", app.name).put("packageName", app.packageName).put("versionCode", app.versionCode)
                .put("versionName", app.versionName).put("apkUrl", app.apkUrl).put("apkPath", app.apkPath)
                .put("iconUrl", app.iconUrl).put("sha256", app.sha256).put("sizeBytes", app.sizeBytes))
        }
        val failures = JSONObject()
        current.failures.forEach { (pkg, message) -> failures.put(pkg, message) }
        preferences.edit().putString("queue", JSONObject().put("apps", apps).put("index", current.index)
            .put("succeeded", current.succeeded).put("failures", failures).put("sessionId", sessionId)
            .put("startedAt", queueStartedAt).toString()).commit()
    }
    private fun restoreQueue() {
        val saved = preferences.getString("queue", null) ?: return
        try {
            val json = JSONObject(saved)
            val array = json.getJSONArray("apps")
            val apps = List(array.length()) { index ->
                val app = array.getJSONObject(index)
                RemoteApp(app.getString("name"), app.getString("packageName"), app.getLong("versionCode"), app.getString("versionName"),
                    app.getString("apkUrl"), app.getString("apkPath"), app.optString("iconUrl").takeIf { it.isNotBlank() },
                    app.optString("sha256").takeIf { it.isNotBlank() }, app.optLong("sizeBytes").takeIf { it > 0 })
            }
            val failures = linkedMapOf<String, String>()
            json.getJSONObject("failures").let { entries -> entries.keys().forEach { failures[it] = entries.getString(it) } }
            val restored = InstallationQueue(apps, json.getInt("index"), json.getInt("succeeded"), failures)
            val active = restored.current
            queueStartedAt = json.optLong("startedAt", 0)
            val savedSession = (if (json.isNull("sessionId")) null else json.getInt("sessionId"))
                ?: InstallEvents.activeSessions(appContext).firstOrNull { it.packageName == active?.packageName }?.sessionId
                ?: InstallEvents.observe(appContext).value.lastOrNull {
                    it.packageName == active?.packageName && it.versionCode == active?.versionCode &&
                        queueStartedAt > 0 && it.startedAt >= queueStartedAt
                }?.sessionId
            if (active != null && savedSession != null) {
                queue = restored
                sessionId = savedSession
                _uiState.update { it.copy(installing = active, queueIndex = restored.index + 1, queueTotal = apps.size,
                    selectedPackages = apps.drop(restored.index).map { item -> item.packageName }.toSet(), installStage = "Восстановление результата установки…") }
            } else {
                preferences.edit().remove("queue").commit()
                _uiState.update { it.copy(selectedPackages = apps.drop(restored.index).map { item -> item.packageName }.toSet(),
                    message = "Предыдущая очередь была прервана. Выбор сохранён; нажмите «Установить выбранные»", isError = true) }
            }
        } catch (_: Exception) {
            preferences.edit().remove("queue").commit()
            _uiState.update { it.copy(message = "Не удалось восстановить прежнюю очередь. Выберите приложения заново", isError = true) }
        }
    }
}
