package com.example.homeezch

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors

internal data class CleanerSnapshot(
    val availableMb: Long,
    val totalMb: Long,
    val cacheBytes: Long,
    val protectedPackages: Set<String>
)

internal data class CleanerResult(val freedBytes: Long)

internal data class ProtectionState(
    val userPackages: Set<String> = emptySet(),
    val permanentPackages: Set<String> = emptySet(),
    val lineagePins: Map<String, Set<String>> = emptyMap(),
    val corrupted: Boolean = false
)

/** These are protection identities, not an allowlist of trusted APK certificates. */
internal object ProtectionPolicy {
    val mandatoryPackages = setOf(
        "io.github.romanvht.byedpi",
        "io.github.dovecoteescapee.byedpi",
        "ru.yourok.torrserve"
    )

    fun mandatoryReason(packageName: String, label: String): String? {
        val pkg = packageName.lowercase(Locale.ROOT)
        val name = label.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return when {
            pkg.contains("byedpi") || name.contains("byedpi") ->
                "ByeDPI защищён: работающий обход блокировок"
            pkg.contains("torrserve") || name.contains("torrserve") || name.contains("торрсерв") ->
                "TorrServe защищён: работающий сервер просмотра"
            else -> null
        }
    }

    fun reason(packageName: String, label: String, signers: Set<String>?, state: ProtectionState): String? {
        mandatoryReason(packageName, label)?.let { return it }
        if (state.corrupted) return "Защита сохранённых приложений требует проверки"
        if (packageName in state.permanentPackages) return "Ранее закреплённое служебное приложение"
        if (packageName in state.userPackages) return "Защищено вами"
        if (signers.isNullOrEmpty()) return "Подпись приложения не определена — исключено из очистки"
        val pinned = state.lineagePins.values.flatten().toSet()
        if (signers.any { it in pinned }) return "Подпись закреплённого приложения — защищено"
        return null
    }

    fun protect(state: ProtectionState, packageName: String, signers: Set<String>?, permanent: Boolean): ProtectionState {
        if (state.corrupted || !LauncherPolicy.validPackage(packageName)) return state
        val oldPins = state.lineagePins[packageName].orEmpty()
        val updatedPins = (oldPins + signers.orEmpty()).filter(::validFingerprint).toSet()
        return state.copy(
            userPackages = if (permanent) state.userPackages else state.userPackages + packageName,
            permanentPackages = if (permanent) state.permanentPackages + packageName else state.permanentPackages,
            lineagePins = if (updatedPins.isEmpty()) state.lineagePins
                else state.lineagePins + (packageName to updatedPins)
        )
    }

    fun unprotect(state: ProtectionState, packageName: String, label: String): ProtectionState {
        if (state.corrupted || mandatoryReason(packageName, label) != null || packageName in state.permanentPackages) return state
        return state.copy(userPackages = state.userPackages - packageName,
            lineagePins = state.lineagePins - packageName)
    }

    fun validFingerprint(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

internal object ProtectionJson {
    fun encode(state: ProtectionState): String = JSONObject().apply {
        put("version", 1)
        put("users", JSONArray(state.userPackages.toList()))
        put("permanent", JSONArray(state.permanentPackages.toList()))
        put("pins", JSONObject().apply {
            state.lineagePins.forEach { (pkg, pins) -> put(pkg, JSONArray(pins.toList())) }
        })
    }.toString()

    /** Corruption never silently discards protections and opens all apps to suggestions. */
    fun decode(raw: String?): ProtectionState {
        if (raw == null) return ProtectionState()
        if (raw.length > 256 * 1024) return ProtectionState(corrupted = true)
        return runCatching {
            val value = JSONObject(raw)
            require(value.optInt("version", -1) == 1)
            fun packageSet(key: String): Set<String> {
                val array = value.getJSONArray(key)
                require(array.length() <= 512)
                return (0 until array.length()).map { index ->
                    val pkg = array.get(index) as? String ?: error("Invalid package")
                    require(LauncherPolicy.validPackage(pkg))
                    pkg
                }.toSet()
            }
            val users = packageSet("users")
            val permanent = packageSet("permanent")
            val pinObject = value.getJSONObject("pins")
            require(pinObject.length() <= 512)
            val pins = pinObject.keys().asSequence().associateWith { pkg ->
                require(LauncherPolicy.validPackage(pkg))
                val array = pinObject.getJSONArray(pkg)
                require(array.length() in 1..64)
                (0 until array.length()).map { index ->
                    val cert = array.get(index) as? String ?: error("Invalid fingerprint")
                    require(ProtectionPolicy.validFingerprint(cert))
                    cert
                }.toSet()
            }
            require(pins.keys.all { it in users || it in permanent })
            ProtectionState(users, permanent, pins)
        }.getOrElse { ProtectionState(corrupted = true) }
    }
}

internal object OwnCachePolicy {
    private val excludedParts = setOf("download", "downloads", "pending", "install", "installer", "apks", "verified", "preferences")

    fun deletable(relativeParts: List<String>, lastModified: Long, now: Long, minimumAgeMs: Long): Boolean {
        if (relativeParts.isEmpty() || lastModified <= 0 || lastModified > now || now - lastModified < minimumAgeMs) return false
        val parts = relativeParts.map { it.lowercase(Locale.ROOT) }
        if (parts.any { it in excludedParts || it.contains("download") }) return false
        val name = parts.last()
        return !name.endsWith(".apk") && !name.endsWith(".part") && !name.endsWith(".partial") &&
            !name.endsWith(".tmp") && !name.endsWith(".lock") && name != ".keep"
    }
}

/** Only this launcher's explicitly temporary cache is eligible; no third-party cleanup APIs. */
internal class CleanerController(context: Context) : ComponentCallbacks2, AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("cleaner_protection", Context.MODE_PRIVATE)
    private var protectionState = ProtectionJson.decode(runCatching { preferences.getString("policy", null) }
        .getOrElse { "invalid" })
    private val cacheRoot = File(appContext.cacheDir, "launcher-temp")
    private val trimPending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "launcher-cache-trim").apply { isDaemon = true }
    }

    init { appContext.registerComponentCallbacks(this) }

    fun snapshot(): CleanerSnapshot {
        val memory = ActivityManager.MemoryInfo()
        runCatching { (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory) }
        val currentProtected = findTvApps(appContext.packageManager, appContext.packageName)
            .filter { protectedReason(it) != null }.map { it.packageName }.toSet()
        val state = synchronized(this) { protectionState }
        return CleanerSnapshot(memory.availMem / 1_048_576, memory.totalMem / 1_048_576,
            eligibleFiles(10 * 60_000L).sumOf { it.length() },
            ProtectionPolicy.mandatoryPackages + state.userPackages + state.permanentPackages + currentProtected)
    }

    fun cleanOwnCache(): CleanerResult = cleanOwnCache(10 * 60_000L)

    @Synchronized
    fun protectedReason(app: LaunchApp): String? {
        val signers = signingLineage(app.packageName)
        if (ProtectionPolicy.mandatoryReason(app.packageName, app.name) != null) {
            save(ProtectionPolicy.protect(protectionState, app.packageName, signers, permanent = true))
        } else if (app.packageName in protectionState.userPackages || app.packageName in protectionState.permanentPackages) {
            // Android-reported signing history carries legitimate rotations forward.
            save(ProtectionPolicy.protect(protectionState, app.packageName, signers,
                permanent = app.packageName in protectionState.permanentPackages))
        }
        return ProtectionPolicy.reason(app.packageName, app.name, signers, protectionState)
    }

    @Synchronized
    fun protect(app: LaunchApp) {
        save(ProtectionPolicy.protect(protectionState, app.packageName, signingLineage(app.packageName),
            permanent = ProtectionPolicy.mandatoryReason(app.packageName, app.name) != null))
    }

    @Synchronized
    fun unprotect(app: LaunchApp) { save(ProtectionPolicy.unprotect(protectionState, app.packageName, app.name)) }

    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return@runCatching false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        else appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Suggestions only. Unknown/absent usage records and protected apps are omitted. */
    fun rareApps(apps: List<LaunchApp>): List<LaunchApp> {
        if (!hasUsageAccess()) return emptyList()
        val now = System.currentTimeMillis()
        val cutoff = now - 30 * 86_400_000L
        val usage = runCatching {
            val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching null
            manager.queryAndAggregateUsageStats(now - 90 * 86_400_000L, now)
        }.getOrNull() ?: return emptyList()
        val recent = LauncherPreferences(appContext).recentPackages.toSet()
        return apps.filter { app ->
            val lastUsed = usage[app.packageName]?.lastTimeUsed
            lastUsed != null && lastUsed in 1 until cutoff && app.packageName !in recent && protectedReason(app) == null
        }.take(12)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) queueTrim()
    }

    override fun onLowMemory() = queueTrim()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            appContext.unregisterComponentCallbacks(this)
            worker.shutdown()
        }
    }

    private fun queueTrim() {
        if (closed.get() || !trimPending.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                try { cleanOwnCache(60 * 60_000L) } finally { trimPending.set(false) }
            }
        }.onFailure { trimPending.set(false) }
    }

    private fun cleanOwnCache(minimumAgeMs: Long): CleanerResult {
        var freed = 0L
        eligibleFiles(minimumAgeMs).forEach { file ->
            val bytes = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) freed += bytes
        }
        return CleanerResult(freed)
    }

    private fun eligibleFiles(minimumAgeMs: Long): List<File> = runCatching {
        if (!cacheRoot.isDirectory || Files.isSymbolicLink(cacheRoot.toPath())) return@runCatching emptyList()
        val root = cacheRoot.canonicalFile.toPath()
        val now = System.currentTimeMillis()
        Files.walk(root).use { stream ->
            stream.filter { path ->
                !Files.isSymbolicLink(path) && Files.isRegularFile(path) &&
                    path.toFile().canonicalFile.toPath().startsWith(root) &&
                    OwnCachePolicy.deletable(root.relativize(path).map { it.toString() },
                        path.toFile().lastModified(), now, minimumAgeMs)
            }.map { it.toFile() }.limit(5_000).collect(Collectors.toList())
        }
    }.getOrDefault(emptyList())

    private fun save(state: ProtectionState) {
        if (state == protectionState || state.corrupted) return
        protectionState = state
        preferences.edit().putString("policy", ProtectionJson.encode(state)).apply()
    }

    @Suppress("DEPRECATION")
    private fun signingLineage(packageName: String): Set<String>? = runCatching {
        val info = appContext.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return@runCatching null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }?.toSet()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
