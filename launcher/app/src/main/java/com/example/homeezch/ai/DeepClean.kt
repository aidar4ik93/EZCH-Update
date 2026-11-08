package com.example.homeezch.ai

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Claude r57 (owner, 04.11): «сделай крутую очистку Евы».
 *
 * Since Android 14, killBackgroundProcesses() only affects the caller's own app (all apps, any targetSdk), so the old
 * «очистка» and Task Killer TV free nothing on Homatics. What really frees memory without root or a computer:
 *  1. Eva's own memory: speech models, artwork and halo caches (the launcher is the biggest resident app on the box);
 *  2. «Остановить» (force stop) for apps the owner used and left in the background — done the way a person would:
 *     the system App info screen + the button, by the Ai EvA accessibility service, only in the Settings app;
 *  3. killBackgroundProcesses as before (still effective on Android 13 and older).
 * Never stopped: the app in front, keyboards, launchers, assistants, accessibility/VPN apps, servers (TorrServe…),
 * system components, and whatever the owner listed in `deep_clean_keep`.
 * The result reports the measured free RAM before/after — no invented numbers.
 */
internal object DeepClean {
    const val MAX_APPS = 8
    private const val MB = 1024L * 1024L

    /** Apps seen in front since the accessibility service connected, most recent last. Filled by [onWindow]. */
    private val seen = LinkedHashSet<String>()
    @Synchronized fun onWindow(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        seen.remove(packageName); seen.add(packageName)
        while (seen.size > 40) seen.remove(seen.first())
    }
    @Synchronized fun recent(): List<String> = seen.toList()

    private val PROTECTED_PREFIXES = listOf("android", "com.android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.tv.", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.katniss",
        "com.google.android.apps.googleassistant", "com.google.android.inputmethod", "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.google.android.ext.", "com.google.android.tts", "com.google.android.apps.tv.dreamx",
        "com.google.android.backdrop", "com.google.android.apps.mediashell", "com.google.android.webview", "com.droidlogic", "com.amlogic", "com.nes.", "com.sei.")
    /** Background servers and network tunnels: stopping them breaks what the owner is using on other devices. */
    private val PROTECTED_WORDS = listOf("torrserv", "vpn", "proxy", "dpi", "server", "tunnel", "keyboard", "launcher", "remote", "accessib", "ezch")

    fun isProtected(pkg: String, exact: Set<String>): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return pkg in exact || PROTECTED_PREFIXES.any { p == it || p.startsWith(it) } || PROTECTED_WORDS.any { it in p }
    }

    /** Pure choice (JVM-tested): most recently used first, without the app in front, protected or already stopped ones. */
    fun pick(recent: List<String>, foreground: String?, exact: Set<String>, stopped: (String) -> Boolean, max: Int = MAX_APPS): List<String> =
        recent.asReversed().asSequence().distinct()
            .filter { it != foreground && !isProtected(it, exact) && !stopped(it) }
            .take(max).toList()

    private val FORCE_STOP = setOf("принудительно остановить", "остановить принудительно", "принудительная остановка",
        "остановить", "force stop", "force-stop", "stop")
    private val CONFIRM = setOf("ок", "ok", "да", "остановить", "принудительно остановить", "force stop", "yes")

    data class Result(val beforeMb: Long, val afterMb: Long, val totalMb: Long, val stopped: List<String>, val skipped: Int, val deep: Boolean)

    fun freeMb(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L to 0L
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return info.availMem / MB to info.totalMem / MB
    }

    /** Packages that must never be stopped on this box (resolved at run time). */
    private fun systemKeep(context: Context): Set<String> {
        val pm = context.packageManager
        val keep = mutableSetOf(context.packageName)
        fun intentPkgs(intent: Intent) = runCatching { pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        keep += intentPkgs(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        keep += runCatching { pm.queryIntentServices(Intent("android.net.VpnService"), 0).map { it.serviceInfo.packageName } }.getOrDefault(emptyList())
        val cr = context.contentResolver
        Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { keep += it }
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.forEach { keep += it.substringBefore('/') }
        runCatching { Settings.Secure.getString(cr, "voice_interaction_service")?.substringBefore('/')?.let { keep += it } }
        keep += AiPreferences.store(context).getStringSet("deep_clean_keep", emptySet()).orEmpty()
        return keep
    }

    private fun isStopped(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(true) // not visible / not installed → nothing to stop

    fun label(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * Pure (JVM-tested): background apps that really run, from `ps -A -o NAME=`, `pm list packages` and
     * `dumpsys media_session` — apps playing right now (state=3) and protected ones are left alone.
     */
    fun running(ps: String, packages: String, media: String, foreground: String?, exact: Set<String>,
                launchable: (String) -> Boolean = { true }): List<String> {
        val installed = packages.lineSequence().map { it.trim().removePrefix("package:") }.filter { it.isNotEmpty() }.toSet()
        val playing = mutableSetOf<String>()
        var current: String? = null
        media.lineSequence().forEach { l ->
            Regex("package=([A-Za-z0-9_.]+)").find(l)?.let { current = it.groupValues[1] }
            if ("state=PlaybackState {state=3" in l) current?.let { playing += it }
        }
        return ps.lineSequence().map { it.trim().substringBefore(':') }.filter { it in installed }.distinct()
            // Only apps the owner can open from a launcher: vendor services (DolbyAudioService, FService stub…) never.
            .filter { it != foreground && it !in playing && !isProtected(it, exact) && launchable(it) }.toList()
    }

    private const val SPLIT = "---EZCH---"
    /** Deep clean through the box's own ADB: real running list + `am force-stop`, invisible and instant. */
    private fun viaAdb(context: Context, foreground: String?): List<String>? {
        if (!com.example.homeezch.LocalAdb.granted(context)) return null
        val out = com.example.homeezch.LocalAdb.shell(context, "ps -A -o NAME=; echo $SPLIT; pm list packages; echo $SPLIT; dumpsys media_session", consentMs = 3000)
            ?: return null
        val parts = out.split(SPLIT)
        if (parts.size < 3) return null
        val pm = context.packageManager
        val targets = running(parts[0], parts[1], parts[2], foreground, systemKeep(context)) { pkg ->
            runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null
        }.take(20)
        if (targets.isEmpty()) return emptyList()
        com.example.homeezch.LocalAdb.shell(context, targets.joinToString("; ") { "am force-stop $it" }, consentMs = 3000) ?: return null
        return targets
    }

    /** What a deep clean would stop now — for the owner's «что почистишь?» and for the box test (dumpsys … --deepclean-dry). */
    fun plan(context: Context, foreground: String?): List<String> =
        pick(recent(), foreground, systemKeep(context), { isStopped(context, it) })

    suspend fun run(context: Context, progress: (String) -> Unit = {}): Result {
        val app = context.applicationContext
        val (before, total) = freeMb(app)
        // 1. Eva's own memory — speech model (tens of MB) and the desktop's image caches.
        progress("Освобождаю память Евы…")
        withContext(Dispatchers.Main) {
            EvaEngine.release()
            com.example.homeezch.phone.PhoneRemote.home?.get()?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
        // 2. Apps left in the background — force stop through App info (Android 14 leaves no other way without root).
        val service = EvaAppService.current
        val stopped = mutableListOf<String>()
        var skipped = 0
        val adb = withContext(Dispatchers.IO) { runCatching { viaAdb(app, service?.applicationPackage()) }.getOrNull() }
        if (adb != null) {
            progress("Останавливаю фоновые приложения…")
            adb.forEach { stopped += label(app, it) }
            EvaStats.count("deep_clean_adb")
        } else if (service != null) {
            val foreground = service.applicationPackage()
            val targets = plan(app, foreground)
            for ((index, pkg) in targets.withIndex()) {
                val name = label(app, pkg)
                progress("Останавливаю $name (${index + 1} из ${targets.size})…")
                if (forceStop(service, pkg)) { stopped += name; EvaStats.count("deep_clean_stopped") } else skipped++
            }
            if (targets.isNotEmpty()) leaveSettings(service, foreground)
        }
        // 3. Cached processes — still effective on Android 13 and older.
        withContext(Dispatchers.IO) {
            val am = app.getSystemService(ActivityManager::class.java)
            runCatching { app.packageManager.getInstalledApplications(0) }.getOrDefault(emptyList())
                .map { it.packageName }.filter { it != app.packageName }
                .forEach { runCatching { am?.killBackgroundProcesses(it) } }
            Runtime.getRuntime().gc()
        }
        delay(1500)
        val after = freeMb(app).first
        com.example.homeezch.EzchDiag.log("HARDWARE", "Глубокая очистка: $before → $after МБ, остановлено ${stopped.size}, пропущено $skipped")
        return Result(before, after, total, stopped, skipped, adb != null || service != null)
    }

    fun message(r: Result): String {
        val freed = (r.afterMb - r.beforeMb).coerceAtLeast(0)
        val head = if (freed >= 10) "Освободила $freed МБ." else "Память уже была почти свободна."
        val apps = when {
            r.stopped.isNotEmpty() -> " Остановила: ${r.stopped.take(6).joinToString(", ")}" +
                (if (r.stopped.size > 6) " и ещё ${r.stopped.size - 6}." else ".")
            r.deep -> " Фоновых приложений для остановки не было."
            else -> " Чтобы я останавливала фоновые приложения, включите «Ai EvA — управление приложениями» в специальных возможностях."
        }
        val skip = if (r.skipped > 0) " Не удалось остановить: ${r.skipped}." else ""
        return "$head$apps$skip Свободно ${r.afterMb} из ${r.totalMb} МБ."
    }

    // ---- Settings automation (only the Settings package is ever touched) ----

    private fun settingsPackage(context: Context, pkg: String): String? = runCatching {
        context.packageManager.resolveActivity(detailsIntent(pkg), 0)?.activityInfo?.packageName
    }.getOrNull()
    private fun detailsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

    private suspend fun forceStop(service: AccessibilityService, pkg: String): Boolean {
        val settings = settingsPackage(service, pkg) ?: return false
        if (runCatching { service.startActivity(detailsIntent(pkg)) }.isFailure) return false
        // The button: enabled only while the app is not stopped.
        val button = waitFor(service, settings, FORCE_STOP, 3500) ?: return false
        if (!click(button, settings)) return false
        delay(350)
        // Confirmation dialog («Остановить приложение?» → ОК).
        val ok = waitFor(service, settings, CONFIRM, 2500, exclude = button) ?: return false
        if (!click(ok, settings)) return false
        // Done when the app reports stopped (the system sets FLAG_STOPPED).
        val until = SystemClock.elapsedRealtime() + 2500
        while (SystemClock.elapsedRealtime() < until) { if (isStopped(service, pkg)) return true; delay(150) }
        return isStopped(service, pkg)
    }

    private suspend fun leaveSettings(service: AccessibilityService, foreground: String?) {
        repeat(4) {
            val top = (service as? EvaAppService)?.applicationPackage()
            val settings = settingsPackage(service, service.packageName)
            if (top == null || top != settings) return
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); delay(400)
        }
        if (foreground == null) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private suspend fun waitFor(service: AccessibilityService, pkg: String, labels: Set<String>, timeout: Long,
                                exclude: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            for (window in service.windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                find(root, pkg, labels, 0)?.takeIf { it != exclude }?.let { return it }
            }
            delay(150)
        }
        return null
    }

    /** Ordered by label priority, so «Принудительно остановить» wins over a plain «Остановить» elsewhere. */
    private fun find(root: AccessibilityNodeInfo, pkg: String, labels: Set<String>, depth: Int): AccessibilityNodeInfo? {
        val all = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        fun walk(node: AccessibilityNodeInfo, d: Int) {
            if (d > 25 || node.packageName?.toString() != pkg) return
            val text = (node.text ?: node.contentDescription)?.toString()?.lowercase(Locale.ROOT)?.trim()?.trimEnd('?', '!', '.')
            if (text != null && text in labels && node.isVisibleToUser) all += text to node
            for (i in 0 until node.childCount.coerceAtMost(120)) node.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(root, depth)
        val order = labels.toList()
        return all.sortedBy { order.indexOf(it.first) }.firstOrNull()?.second
    }

    /** Clicks the node or its nearest clickable parent; a disabled button means the app is already stopped. */
    private fun click(node: AccessibilityNodeInfo, pkg: String): Boolean {
        var c: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = c ?: return false
            if (n.packageName?.toString() != pkg) return false
            if (n.isClickable) return n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = n.parent
        }
        return false
    }
}
