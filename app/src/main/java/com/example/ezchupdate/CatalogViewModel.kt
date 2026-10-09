package com.example.ezchupdate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ezchupdate.data.CatalogRepository
import com.example.ezchupdate.data.InstalledApp
import com.example.ezchupdate.data.RemoteApp
import com.example.ezchupdate.install.AppInstaller
import com.example.ezchupdate.install.InstallEvents
import com.example.ezchupdate.install.InstallQueueState
import com.example.ezchupdate.install.InstallQueueStore
import com.example.ezchupdate.install.InstallSnapshot
import com.example.ezchupdate.install.InstallFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

data class AppRow(val app: RemoteApp, val installed: InstalledApp?) {
    val updateAvailable: Boolean get() = installed == null || installed.versionCode < app.versionCode
}

data class CatalogUiState(
    val rows: List<AppRow> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val installing: RemoteApp? = null,
    val message: String? = null,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val failures: List<String> = emptyList(),
    val permissionRequested: Boolean = false
)

class CatalogViewModel @JvmOverloads constructor(application: Application,
    private val repository: CatalogRepository = CatalogRepository(application.applicationContext)
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val installer = AppInstaller()
    private val queueStore = InstallQueueStore(appContext)
    private var queue = queueStore.load() ?: InstallQueueState()
    private var needsRecovery = queue.current != null
    private var downloadRunning = false
    private var operationId = 0
    private var loadRunning = false
    private var lastRefreshAt = 0L
    private val stopDownload = AtomicBoolean(false)
    private val setupPrefs = appContext.getSharedPreferences("launcher_setup", Context.MODE_PRIVATE)

    fun setupLauncher(context: Context) {
        if (isBusy() || _uiState.value.isLoading) return
        val row = _uiState.value.rows.firstOrNull { it.app.packageName == "com.example.homeezch.usb" }
        if (row == null) {
            _uiState.value = _uiState.value.copy(message = "Лаунчер отсутствует в каталоге. Обновите каталог.")
            return
        }
        if (!row.updateAvailable) {
            openLauncher(context)
            return
        }
        setupPrefs.edit().putBoolean("open_after_install", true)
            .putLong("target_version", row.app.versionCode).apply()
        _uiState.value = _uiState.value.copy(selectedPackages = setOf(row.app.packageName))
        installSelected(context)
    }

    private fun openLauncher(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(
            android.content.ComponentName("com.example.homeezch.usb", "com.example.homeezch.MainActivity")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        setupPrefs.edit().remove("open_after_install").apply()
        true
    } catch (error: Exception) {
        _uiState.value = _uiState.value.copy(message = "Не удалось открыть лаунчер: ${error.message.orEmpty()}")
        false
    }

    fun openPreparedLauncher(context: Context) {
        if (!isBusy() && setupPrefs.getBoolean("open_after_install", false) &&
            installedApp("com.example.homeezch.usb")?.versionCode?.let {
                it >= setupPrefs.getLong("target_version", Long.MAX_VALUE)
            } == true) openLauncher(context)
    }

    private val _uiState = MutableStateFlow(
        CatalogUiState(
            selectedPackages = (queue.remaining + listOfNotNull(queue.current)).map { it.packageName }.toSet(),
            installing = queue.current,
            message = queue.message,
            completedCount = queue.completedCount,
            totalCount = queue.totalCount,
            failures = queue.failures,
            permissionRequested = queue.waitingForPermission
        )
    )
    val uiState: StateFlow<CatalogUiState> = _uiState

    init {
        InstallEvents.restore(appContext)
        viewModelScope.launch { InstallEvents.snapshot.collect(::handleInstallSnapshot) }
        loadCatalog(clearMessage = queue.totalCount == 0)
    }

    fun reload() {
        if (isBusy()) return
        loadCatalog(clearMessage = true)
    }

    fun refreshCatalogIfIdle() {
        if (!isBusy() && android.os.SystemClock.elapsedRealtime() - lastRefreshAt >= 5 * 60 * 1000L)
            loadCatalog(clearMessage = false)
    }

    private fun loadCatalog(clearMessage: Boolean) {
        if (loadRunning) return
        loadRunning = true
        lastRefreshAt = android.os.SystemClock.elapsedRealtime()
        _uiState.value = _uiState.value.copy(
            isLoading = _uiState.value.rows.isEmpty(),
            isRefreshing = true,
            message = if (clearMessage) null else _uiState.value.message
        )
        viewModelScope.launch {
            try {
                if (_uiState.value.rows.isEmpty()) {
                    val local = withContext(Dispatchers.IO) {
                        repository.loadLocal()?.map { app -> AppRow(app, installedApp(app.packageName)) }
                    }
                    if (!local.isNullOrEmpty()) _uiState.value = _uiState.value.copy(rows = local, isLoading = false)
                }
                val rows = withContext(Dispatchers.IO) {
                    repository.load().map { app -> AppRow(app, installedApp(app.packageName)) }
                }
                val selectable = rows.filter { it.updateAvailable }.map { it.app.packageName }.toSet()
                val cachedWarning = "Не удалось обновить каталог. Показана сохранённая версия; для загрузки APK нужен интернет."
                val message = _uiState.value.message?.lines()?.filterNot { it == cachedWarning }
                    ?.joinToString("\n")?.takeIf { it.isNotBlank() }
                _uiState.value = _uiState.value.copy(
                    rows = rows,
                    selectedPackages = if (isBusy()) _uiState.value.selectedPackages
                    else _uiState.value.selectedPackages.intersect(selectable),
                    isLoading = false,
                    isRefreshing = false,
                    message = if (repository.offline) listOfNotNull(message, cachedWarning).distinct().joinToString("\n")
                        else message
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = error.message ?: "Не удалось загрузить каталог"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    message = listOfNotNull(_uiState.value.message, failure).distinct().joinToString("\n")
                )
            } finally {
                loadRunning = false
            }
        }
    }

    fun toggleSelection(packageName: String) {
        if (isBusy() || _uiState.value.isLoading) return
        val row = _uiState.value.rows.firstOrNull { it.app.packageName == packageName } ?: return
        if (!row.updateAvailable) return
        val selected = _uiState.value.selectedPackages.toMutableSet()
        if (!selected.add(packageName)) selected.remove(packageName)
        _uiState.value = _uiState.value.copy(selectedPackages = selected)
    }

    fun hasSelection(): Boolean = _uiState.value.selectedPackages.isNotEmpty()

    fun needsInstallPermission(context: Context): Boolean =
        hasSelection() && !installer.canRequestPackageInstalls(context)

    fun openUnknownSourcesSettings(context: Context) {
        if (isBusy() || _uiState.value.isLoading || !prepareQueue(waitingForPermission = true)) return
        try {
            context.startActivity(installer.unknownSourcesSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: Exception) {
                stopWaitingForPermission("Не удалось открыть настройки установки: ${error.message.orEmpty()}")
            }
        }
    }

    fun installSelected(context: Context) {
        if (isBusy() || _uiState.value.isLoading) return
        if (!installer.canRequestPackageInstalls(context)) {
            openUnknownSourcesSettings(context)
            return
        }
        if (prepareQueue(waitingForPermission = false)) installNext()
    }

    /** Continues only work explicitly requested before opening Android settings. */
    fun onHostResumed(context: Context) {
        if (queue.waitingForPermission) {
            if (installer.canRequestPackageInstalls(context)) {
                saveQueue(queue.copy(waitingForPermission = false))
                _uiState.value = _uiState.value.copy(permissionRequested = false)
                installNext()
            } else {
                stopWaitingForPermission("Разрешение на установку не предоставлено. Выбранные приложения сохранены.")
            }
        } else if (needsRecovery) {
            needsRecovery = false
            recoverInstallation()
        } else if (queue.current == null && queue.remaining.isNotEmpty()) {
            installNext()
        }
        refreshInstalledRows()
        refreshCatalogIfIdle()
        openPreparedLauncher(context)
    }

    private fun prepareQueue(waitingForPermission: Boolean): Boolean {
        val selected = _uiState.value.selectedPackages
        val applications = _uiState.value.rows
            .filter { it.updateAvailable && it.app.packageName in selected }
            .map { it.app }
            .sortedBy { it.packageName == appContext.packageName }
        if (applications.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "Сначала выберите приложения с доступными обновлениями")
            return false
        }
        InstallEvents.snapshot.value?.let { InstallEvents.consume(appContext, it.sessionId) }
        stopDownload.set(false)
        saveQueue(
            InstallQueueState(
                remaining = applications,
                totalCount = applications.size,
                waitingForPermission = waitingForPermission,
                message = if (waitingForPermission) "Разрешите EZCH Update устанавливать приложения и вернитесь сюда" else null
            )
        )
        _uiState.value = _uiState.value.copy(
            totalCount = applications.size,
            completedCount = 0,
            failures = emptyList(),
            downloadBytes = 0,
            totalBytes = 0,
            permissionRequested = waitingForPermission,
            message = queue.message
        )
        return true
    }

    private fun stopWaitingForPermission(message: String) {
        setupPrefs.edit().remove("open_after_install").apply()
        saveQueue(InstallQueueState(message = message))
        _uiState.value = _uiState.value.copy(
            installing = null,
            permissionRequested = false,
            completedCount = 0,
            totalCount = 0,
            message = message
        )
    }

    private fun installNext() {
        if (queue.current != null || queue.waitingForPermission || downloadRunning) return
        val next = queue.remaining.firstOrNull()
        if (next == null) {
            finishQueue()
            return
        }
        val installed = installedApp(next.packageName)
        saveQueue(queue.copy(current = next, remaining = queue.remaining.drop(1), sessionId = null))
        _uiState.value = _uiState.value.copy(installing = next, downloadBytes = 0, totalBytes = 0)
        if (installed != null && installed.versionCode >= next.versionCode) {
            completeCurrent(success = true, message = "${next.name}: актуальная версия уже установлена")
            return
        }
        val operation = ++operationId
        val message = "Скачивание ${queue.completedCount + 1}/${queue.totalCount}: ${next.name}"
        saveQueue(queue.copy(message = message))
        _uiState.value = _uiState.value.copy(message = message)
        downloadRunning = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                installer.install(appContext, next) { bytes, total ->
                    if (stopDownload.get()) throw InterruptedIOException("Установка отменена")
                    viewModelScope.launch {
                        if (operation == operationId && queue.current?.packageName == next.packageName) {
                            _uiState.value = _uiState.value.copy(downloadBytes = bytes, totalBytes = total.coerceAtLeast(0))
                        }
                    }
                }
            }
            downloadRunning = false
            if (operation != operationId || queue.current?.packageName != next.packageName) {
                result.getOrNull()?.let(::abandonSession)
                if (queue.current == null) installNext()
                return@launch
            }
            result.fold(
                onSuccess = { sessionId ->
                    saveQueue(queue.copy(sessionId = sessionId))
                    if (queue.cancelRequested) {
                        abandonSession(sessionId)
                        completeCurrent(false, "${next.name}: установка отменена")
                    } else {
                        _uiState.value = _uiState.value.copy(message = "Подтвердите установку ${next.name} в окне Android")
                        handleInstallSnapshot(InstallEvents.snapshot.value)
                    }
                },
                onFailure = { error ->
                    completeCurrent(false, "${next.name}: ${if (queue.cancelRequested) "установка отменена" else error.message ?: "ошибка установки"}")
                }
            )
        }
    }

    private fun handleInstallSnapshot(result: InstallSnapshot?) {
        result ?: return
        val current = queue.current ?: return
        if (current.packageName != result.packageName) return
        // commit may deliver its callback before the IO call returns its session ID.
        // Waiting for that ID also rejects late callbacks from an earlier attempt.
        if (queue.sessionId == null && downloadRunning) return
        if (queue.sessionId != null && queue.sessionId != result.sessionId) return
        if (result.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            saveQueue(queue.copy(sessionId = result.sessionId))
            _uiState.value = _uiState.value.copy(message = "Подтвердите установку ${current.name} в окне Android")
            return
        }
        val success = result.status == PackageInstaller.STATUS_SUCCESS
        completeCurrent(
            success,
            if (success) "${current.name} установлен" else "${current.name}: ${InstallFailureMessage.describe(result.status, result.message)}"
        )
        InstallEvents.consume(appContext, result.sessionId)
    }

    fun onConfirmationLaunchFailed(sessionId: Int, message: String) {
        val current = queue.current ?: return
        if (queue.sessionId == null && downloadRunning) {
            val pending = InstallEvents.snapshot.value ?: return
            if (pending.sessionId != sessionId || pending.packageName != current.packageName) return
            abandonSession(sessionId)
            InstallEvents.record(
                appContext,
                InstallSnapshot(current.packageName, sessionId, PackageInstaller.STATUS_FAILURE, message)
            )
            return
        }
        if (queue.sessionId != sessionId) return
        abandonSession(sessionId)
        InstallEvents.consume(appContext, sessionId)
        completeCurrent(false, "${current.name}: $message")
    }

    fun cancelInstallation() {
        if (queue.waitingForPermission) {
            stopWaitingForPermission("Установка отменена. Выбранные приложения сохранены.")
            return
        }
        val current = queue.current ?: return
        stopDownload.set(true)
        saveQueue(queue.copy(remaining = emptyList(), cancelRequested = true))
        val sessionId = queue.sessionId
        if (sessionId != null) {
            abandonSession(sessionId)
            InstallEvents.consume(appContext, sessionId)
            completeCurrent(false, "${current.name}: установка отменена")
        } else {
            _uiState.value = _uiState.value.copy(message = "Отмена ${current.name}; завершается текущая операция скачивания")
        }
    }

    private fun recoverInstallation() {
        val current = queue.current ?: return
        val event = InstallEvents.snapshot.value
        if (event?.packageName == current.packageName) {
            handleInstallSnapshot(event)
            if (queue.current == null || queue.current?.packageName != current.packageName) return
        }
        val packageInstaller = appContext.packageManager.packageInstaller
        val session = queue.sessionId?.let { packageInstaller.getSessionInfo(it) }
            ?: packageInstaller.mySessions.lastOrNull { it.appPackageName == current.packageName && it.isSealed }
        if (session != null) {
            if (!session.isSealed) {
                abandonSession(session.sessionId)
                completeCurrent(false, "${current.name}: предыдущая передача APK прервана; выберите приложение для повтора")
                return
            }
            saveQueue(queue.copy(sessionId = session.sessionId))
            if (queue.cancelRequested) {
                cancelInstallation()
            } else {
                _uiState.value = _uiState.value.copy(
                    message = "Android ещё обрабатывает ${current.name}. Если окно установки не появилось, отмените и повторите установку."
                )
            }
            return
        }
        val installed = installedApp(current.packageName)
        val success = installed != null && installed.versionCode >= current.versionCode
        completeCurrent(
            success,
            if (success) "${current.name} установлен" else "${current.name}: предыдущая установка прервана; выберите приложение для повтора"
        )
    }

    private fun completeCurrent(success: Boolean, message: String) {
        val current = queue.current ?: return
        if (!success && current.packageName == "com.example.homeezch.usb") {
            setupPrefs.edit().remove("open_after_install").apply()
        }
        operationId++
        needsRecovery = false
        val failures = if (success) queue.failures else queue.failures + message
        saveQueue(
            queue.copy(
                current = null,
                sessionId = null,
                completedCount = queue.completedCount + 1,
                successCount = queue.successCount + if (success) 1 else 0,
                failures = failures,
                message = message
            )
        )
        _uiState.value = _uiState.value.copy(
            installing = null,
            completedCount = queue.completedCount,
            failures = failures,
            message = message,
            selectedPackages = _uiState.value.selectedPackages - current.packageName
        )
        refreshInstalledRows()
        if (!downloadRunning) installNext()
    }

    private fun finishQueue() {
        val cancelled = (queue.totalCount - queue.completedCount).coerceAtLeast(0)
        val message = buildString {
            append("Установлено: ${queue.successCount}. Ошибок: ${queue.failures.size}.")
            if (cancelled > 0) append(" Отменено: $cancelled.")
            if (queue.failures.isNotEmpty()) append("\n${queue.failures.joinToString("\n")}")
        }
        saveQueue(queue.copy(waitingForPermission = false, message = message))
        _uiState.value = _uiState.value.copy(
            installing = null,
            permissionRequested = false,
            selectedPackages = emptySet(),
            message = message,
            downloadBytes = 0,
            totalBytes = 0
        )
        loadCatalog(clearMessage = false)
    }

    private fun refreshInstalledRows() {
        val rows = _uiState.value.rows
        if (rows.isEmpty()) return
        val refreshed = rows.map { it.copy(installed = installedApp(it.app.packageName)) }
        _uiState.value = _uiState.value.copy(
            rows = refreshed,
            selectedPackages = if (isBusy()) _uiState.value.selectedPackages
            else _uiState.value.selectedPackages.intersect(refreshed.filter { it.updateAvailable }.map { it.app.packageName }.toSet())
        )
    }

    private fun installedApp(packageName: String): InstalledApp? =
        repository.installedApp(appContext.packageManager, packageName)

    private fun abandonSession(sessionId: Int) {
        runCatching { appContext.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private fun isBusy(): Boolean = queue.current != null || queue.waitingForPermission || downloadRunning

    private fun saveQueue(value: InstallQueueState) {
        queue = value
        queueStore.save(value)
    }
}
