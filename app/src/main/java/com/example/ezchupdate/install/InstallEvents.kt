package com.example.ezchupdate.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.example.ezchupdate.data.versionCodeCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A small durable journal: callbacks are retained until the queue acknowledges them. */
object InstallEvents {
    private val lock = Any()
    private val retained = MutableStateFlow<List<InstallResult>>(emptyList())
    private val active = linkedMapOf<Int, ActiveSession>()
    private val confirmations = mutableMapOf<Int, String>()
    private var initialized = false
    private var recovered = false

    fun observe(context: Context): StateFlow<List<InstallResult>> = synchronized(lock) {
        initialize(context)
        if (!recovered) {
            recovered = true
            ApkDownloader.cleanupInterrupted(File(context.cacheDir, "downloads"))
            recoverSessions(context)
        }
        retained.asStateFlow()
    }

    fun activeSessions(context: Context): List<InstallSubmission> = synchronized(lock) {
        initialize(context)
        active.values.map { InstallSubmission(it.sessionId, it.packageName) }
    }

    fun acknowledge(context: Context, sessionId: Int) = synchronized(lock) {
        initialize(context)
        // Pending confirmation must survive process death and cannot be acknowledged as final.
        retained.value = retained.value.filterNot { it.sessionId == sessionId && it.terminal }
        persist(context)
    }

    internal fun activeSession(context: Context, sessionId: Int): InstallSubmission? = synchronized(lock) {
        initialize(context)
        active[sessionId]?.let { InstallSubmission(it.sessionId, it.packageName) }
    }

    internal fun started(context: Context, submission: InstallSubmission, versionCode: Long) = synchronized(lock) {
        initialize(context)
        active[submission.sessionId] = ActiveSession(submission.sessionId, submission.packageName, versionCode, false, System.currentTimeMillis())
        persist(context, required = true)
    }

    internal fun markCommitted(context: Context, sessionId: Int) = synchronized(lock) {
        initialize(context)
        active[sessionId]?.let { active[sessionId] = it.copy(committed = true) }
        persist(context, required = true)
    }

    internal fun pending(context: Context, submission: InstallSubmission, intent: Intent) = synchronized(lock) {
        initialize(context)
        if (!active.containsKey(submission.sessionId)) return@synchronized
        confirmations[submission.sessionId] = intent.toUri(Intent.URI_INTENT_SCHEME)
        val session = active.getValue(submission.sessionId)
        addResult(InstallResult(submission.sessionId, submission.packageName, PackageInstaller.STATUS_PENDING_USER_ACTION,
            "Подтвердите установку в системном окне Android", session.versionCode, session.startedAt))
        if (!persist(context)) {
            addResult(retained.value.last().copy(message = "Подтвердите установку в Android. Не удалось сохранить состояние; освободите память устройства"))
        }
    }

    internal fun confirmation(context: Context, sessionId: Int): Intent? = synchronized(lock) {
        initialize(context)
        confirmations[sessionId]?.let { uri -> runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() }
    }

    internal fun record(context: Context, result: InstallResult) = synchronized(lock) {
        initialize(context)
        // A duplicate pending callback must not replace a final result.
        if (!result.terminal && retained.value.any { it.sessionId == result.sessionId && it.terminal }) return@synchronized
        val session = active[result.sessionId]
        val correlated = result.copy(versionCode = result.versionCode ?: session?.versionCode,
            startedAt = result.startedAt.takeIf { it > 0 } ?: session?.startedAt ?: 0)
        if (result.terminal) {
            active.remove(result.sessionId)
            confirmations.remove(result.sessionId)
        }
        addResult(correlated)
        if (!persist(context) && correlated.status != PackageInstaller.STATUS_SUCCESS) {
            addResult(correlated.copy(message = listOfNotNull(correlated.message,
                "Не удалось сохранить результат установки. Освободите память устройства").joinToString(". ")))
        }
    }

    private fun addResult(result: InstallResult) {
        retained.value = (retained.value.filterNot { it.sessionId == result.sessionId } + result).takeLast(32)
    }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val journal = JSONObject(raw)
            val sessions = journal.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until sessions.length()) {
                val item = sessions.getJSONObject(i)
                val session = ActiveSession(item.getInt("id"), item.getString("package"), item.getLong("version"), item.optBoolean("committed"), item.optLong("startedAt"))
                active[session.sessionId] = session
                item.optString("confirmation").takeIf { it.isNotBlank() }?.let { confirmations[session.sessionId] = it }
            }
            val results = journal.optJSONArray("results") ?: JSONArray()
            retained.value = List(results.length()) { i ->
                val item = results.getJSONObject(i)
                InstallResult(item.getInt("id"), item.getString("package"), item.getInt("status"),
                    item.optString("message").takeIf { it.isNotBlank() },
                    item.optLong("version").takeIf { it > 0 }, item.optLong("startedAt"))
            }
        }.onFailure {
            active.clear()
            confirmations.clear()
            retained.value = emptyList()
        }
    }

    private fun persist(context: Context, required: Boolean = false): Boolean {
        val sessions = JSONArray()
        active.values.forEach { session ->
            sessions.put(JSONObject().put("id", session.sessionId).put("package", session.packageName)
                .put("version", session.versionCode).put("committed", session.committed)
                .put("startedAt", session.startedAt)
                .put("confirmation", confirmations[session.sessionId].orEmpty()))
        }
        val results = JSONArray()
        retained.value.forEach { result ->
            results.put(JSONObject().put("id", result.sessionId).put("package", result.packageName)
                .put("status", result.status).put("message", result.message.orEmpty())
                .put("version", result.versionCode).put("startedAt", result.startedAt))
        }
        // commit rather than apply: journal ownership must reach disk before session.commit().
        val saved = runCatching {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY, JSONObject().put("sessions", sessions).put("results", results).toString()).commit()
        }.getOrDefault(false)
        if (!saved && required) throw InstallException("Не удалось сохранить состояние установки. Освободите память устройства")
        return saved
    }

    @Suppress("DEPRECATION")
    private fun recoverSessions(context: Context) {
        val installer = context.packageManager.packageInstaller
        active.values.toList().forEach { session ->
            val info = installer.getSessionInfo(session.sessionId)
            if (!session.committed || info?.isSealed == false) {
                // A process killed while writing cannot safely submit an incomplete APK.
                runCatching { installer.abandonSession(session.sessionId) }
                record(context, InstallResult(session.sessionId, session.packageName, PackageInstaller.STATUS_FAILURE_ABORTED,
                    "Подготовка установки была прервана. Повторите установку"))
            } else if (info == null) {
                val version = try { context.packageManager.getPackageInfo(session.packageName, 0).versionCodeCompat() }
                    catch (_: PackageManager.NameNotFoundException) { -1L }
                record(context, InstallResult(session.sessionId, session.packageName,
                    if (version >= session.versionCode) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE_ABORTED,
                    if (version >= session.versionCode) null else "Системная установка была прервана. Повторите установку"))
            }
        }
    }

    private data class ActiveSession(val sessionId: Int, val packageName: String, val versionCode: Long,
        val committed: Boolean, val startedAt: Long)
    private const val PREFERENCES = "installation_journal"
    private const val KEY = "journal"
}
