package com.example.homeezch.ai

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Separately opted-in service: existing HOME permission is never silently expanded. */
class EvaAppService : AccessibilityService() {
    companion object {
        @Volatile var current: EvaAppService? = null; private set
        val panelVisibility = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    private val controller by lazy {
        AppControlEngine(object : UiEnvironment {
            override val enabled get() = AiPreferences.enabled(this@EvaAppService)
            override val profileId get() = com.example.homeezch.Profiles.active(this@EvaAppService)
            override val activePackage get() = applicationPackage()
            override val globalActions get() = if (android.os.Build.VERSION.SDK_INT >= 30)
                systemActions.map { it.id }.toSet() else emptySet<Int>()
            override fun root() = controlRoot()
            override fun imeRoot() = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root
            override fun allowed(packageName: String) = packageName != this@EvaAppService.packageName &&
                !packageName.startsWith("com.android.") && !packageName.contains("permissioncontroller") &&
                !packageName.contains("packageinstaller") && AiProfilePolicy.appAllowed(this@EvaAppService, packageName)
            override fun globalAction(action: Int) = performGlobalAction(action)
            override fun isPlaying(packageName: String) = AndroidMediaAdapter(this@EvaAppService).isPlaying(packageName)
            override fun tap(bounds: android.graphics.Rect): Boolean {
                if (serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) return false
                val path = android.graphics.Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                return dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
            }
            override suspend fun openPackage(packageName: String): Boolean {
                val before = profileId
                val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AndroidActionExecutor(this@EvaAppService).apps().firstOrNull { it.component.substringBefore('/') == packageName }
                } ?: return false
                if (!enabled || profileId != before || !allowed(packageName)) return false
                val component = android.content.ComponentName.unflattenFromString(target.component) ?: return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
            }
        })
    }
    @Volatile private var lastDeepClean: String? = null
    @Volatile private var lastAdb: String? = null
    private val panelHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var miniPanel: VoicePanel? = null
    val panelVisible: Boolean get() = miniPanel != null
    fun submitCommand(text: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { panelHandler.post { submitCommand(text) }; return }
        if (text.isBlank()) { showMiniPanel(); return }
        showMiniPanel(listen = false)
        miniPanel?.submit(text)
    }
    private val dismissPanel = Runnable { hideMiniPanel() }
    private fun refreshPanelTimeout() { panelHandler.removeCallbacks(dismissPanel); panelHandler.postDelayed(dismissPanel, 30_000) }
    fun showMiniPanel(cardWidthDp: Float? = null, listen: Boolean = true) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { panelHandler.post { showMiniPanel(cardWidthDp, listen) }; return }
        if (miniPanel == null) {
            val metrics = resources.displayMetrics
            val tileDp = cardWidthDp ?: (((metrics.widthPixels / metrics.density - 148f) / 8f).coerceIn(88f, 125f) * com.example.homeezch.AppCardScale)
            val miniWidth = (evaMiniSideDp(tileDp) * metrics.density).toInt().coerceAtLeast(1)
            val miniHeight = (evaMiniSideDp(94f * com.example.homeezch.AppCardScale) * metrics.density).toInt().coerceAtLeast(1)
            val presenter = VoicePanel(this, close = ::hideMiniPanel, mini = true, onActivity = ::refreshPanelTimeout)
            val layout = android.view.WindowManager.LayoutParams(miniWidth, miniHeight,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                // GPT r22: match the desktop overscan margin for the external orb.
                x = maxOf(metrics.widthPixels * .05f, 24 * metrics.density).toInt(); y = (28 * metrics.density).toInt()
                title = "Ai EvA compact"
            }
            try { getSystemService(android.view.WindowManager::class.java).addView(presenter.view, layout); miniPanel = presenter; panelVisibility.value = true }
            catch (_: RuntimeException) { presenter.destroy(); return }
        }
        refreshPanelTimeout()
        if (AiPreferences.enabled(this) && listen) {
            val expected = miniPanel
            EvaEngine.freeMicrophone { if (miniPanel === expected && AiPreferences.enabled(this)) miniPanel?.listen() }
        } else if (!AiPreferences.enabled(this)) miniPanel?.status("Ева выключена")
    }
    fun hideMiniPanel() {
        panelHandler.removeCallbacks(dismissPanel)
        val presenter = miniPanel ?: return
        miniPanel = null
        panelVisibility.value = false
        presenter.destroy()
        panelHandler.removeCallbacks(dismissPanel)
        runCatching { getSystemService(android.view.WindowManager::class.java).removeView(presenter.view) }
    }
    override fun onServiceConnected() { current = this; com.example.homeezch.EzchDiag.init(this, "служба Ai EvA подключена") }
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        val prefs = AiPreferences.store(this)
        EvaStats.dump(writer)
        VoiceTiming.dump(writer)
        writer.println("EVA_MIC holdToTalk=${VoiceRemote.holdToTalk(this)} ambient=${VoiceRemote.hasAmbientMic(this)} inputs=${VoiceRemote.inputTypes(this)} handsFree=${EvaEngine.handsFreeEnabled(this)}")
        writer.println("EVA mini; profile=${com.example.homeezch.Profiles.active(this)}; enabled=${AiPreferences.enabled(this)}")
        writer.println("speech=${prefs.getString("speech_mode", "none")}/${prefs.getString("speech_state", "none")}; error=${prefs.getInt("last_speech_error", 0)}")
        val apps = AndroidActionExecutor(this).apps()
        writer.println("Available launch targets: ${apps.size}")
        apps.forEach { writer.println("  ${it.label}: ${it.component}") }
        // Claude r57: box test of the deep clean — dry run lists what would be stopped; the real run reports to EzchDiag.
        if (args?.firstOrNull() == "--deepclean-dry") {
            writer.println("DEEPCLEAN_RECENT ${DeepClean.recent()}")
            writer.println("DEEPCLEAN_PLAN ${DeepClean.plan(this, applicationPackage())}")
        }
        if (args?.firstOrNull() == "--deepclean") {
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val r = DeepClean.run(this@EvaAppService)
                lastDeepClean = DeepClean.message(r)
            }
            writer.println("DEEPCLEAN_STARTED")
        }
        writer.println("DEEPCLEAN_LAST ${lastDeepClean}")
        // Claude r58: box test of the local ADB client (the first call shows Android's «Разрешить отладку?»).
        if (args?.firstOrNull() == "--localadb") {
            val cmd = args.drop(1).joinToString(" ").ifBlank { "id" }
            Thread({ lastAdb = com.example.homeezch.LocalAdb.shell(this, cmd)?.trim()?.take(300) ?: ("FAIL " + com.example.homeezch.LocalAdb.lastError) }, "ezch-adb-test").start()
            writer.println("LOCALADB_STARTED")
        }
        writer.println("LOCALADB_LAST ${lastAdb?.replace('\n', '|')} granted=${com.example.homeezch.LocalAdb.granted(this)} reachable=${com.example.homeezch.LocalAdb.reachable()}")
        if (args?.firstOrNull() == "--match") args.drop(1).take(30).forEach { name ->
            writer.println("Match [$name]: ${matchApp(name, apps)}")
        }
    }
    // No background tree scanning: only the window-change event is used, to replace Google Assistant with EVA.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) DeepClean.onWindow(event.packageName?.toString())
        AssistantTakeover.onEvent(this, event)
    }
    /** Claude r57: progress of a long action (deep clean) on Eva's mini panel. */
    fun panelStatus(text: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { panelHandler.post { panelStatus(text) }; return }
        miniPanel?.status(text)
    }
    override fun onInterrupt() { controller.clear(); hideMiniPanel() }
    override fun onUnbind(intent: Intent?): Boolean { hideMiniPanel(); current = null; controller.clear(); return super.onUnbind(intent) }
    override fun onDestroy() { hideMiniPanel(); current = null; super.onDestroy() }
    fun applicationPackage(): String? = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.packageName?.toString() }
    private val remoteButtons = com.example.homeezch.RemoteButtonPress()
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // R52: remote use in HOME's dialogs (settings panel, menus) pauses hands-free too — Activity.onUserInteraction
        // never sees keys sent to a dialog window, so the «microphone in use» overlay stayed on while scrolling settings.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode !in setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH))
            EvaEngine.pauseForInput()
        // GPT r22: learned quick-launch keys work with Ai EvA accessibility service as well.
        if (com.example.homeezch.RemoteButtons.learning) { remoteButtons.reset(); return false }
        // Claude r23: a held volume key must keep stepping while this service filters keys.
        if (event.keyCode in com.example.homeezch.SYSTEM_VOLUME_KEYS) { com.example.homeezch.VolumeHold.onKeyEvent(this, event); return false }
        if (event.keyCode !in setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH) &&
            remoteButtons.handle(this, event, defaults = false)) return true
        if (event.keyCode !in setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH) || !AiPreferences.enabled(this)) return false
        // R52: one press opens Eva once, even when the same press also reaches her by another path.
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            EvaActivation.request(ActivationGate.Source.KEY_SERVICE, event.downTime)) runCatching { showMiniPanel() }
        return true
    }
    private fun controlRoot(): AccessibilityNodeInfo? {
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }
        return roots.firstNotNullOfOrNull { it.root?.takeIf { node -> node.packageName?.toString() != packageName } }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }
    suspend fun confirmWindow(packageName: String) = controller.confirmWindow(packageName)
    /** R30: the remote's OK for the app in front (YouTube for TV draws its results without accessibility nodes). */
    fun pressOk(): Boolean = android.os.Build.VERSION.SDK_INT >= 33 &&
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)

    suspend fun execute(action: Action) = controller.execute(action)
}
