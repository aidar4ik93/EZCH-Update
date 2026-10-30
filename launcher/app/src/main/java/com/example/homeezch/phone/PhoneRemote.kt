package com.example.homeezch.phone

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.example.homeezch.HomeButtonService
import com.example.homeezch.MainActivity
import com.example.homeezch.R
import com.example.homeezch.ai.EvaAppService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Text for Ai EvA arriving from outside HOME's own microphone (the phone page). HomeEva consumes it. */
object EvaInbox {
    private val pending = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = pending
    @Volatile private var postedAt = 0L
    fun post(value: String) {
        pending.value?.let { com.example.homeezch.EzchDiag.log("PHONE:ОШИБКА", "команда «${it.take(80)}» не выполнена: её заменила новая, HOME не был на экране") }
        postedAt = android.os.SystemClock.elapsedRealtime(); pending.value = value
    }
    /** A command older than a minute is dropped: it must not fire by surprise when the owner returns HOME. */
    fun take(): String? {
        val value = pending.value; pending.value = null
        if (value != null && android.os.SystemClock.elapsedRealtime() - postedAt > STALE_MS) {
            com.example.homeezch.EzchDiag.log("PHONE:ОШИБКА", "команда «${value.take(80)}» устарела (HOME не открылся за минуту) и отброшена"); return null
        }
        return value
    }
    private const val STALE_MS = 60_000L
}

/**
 * «Телефон как пульт»: a tiny web server inside the launcher. The phone opens the page from the QR code
 * on the TV — no app to install. Dictation through the phone keyboard goes to Ai EvA (far better than the
 * remote's microphone), text can be typed into the TV's input field, links open on the TV.
 * Home network only, every request carries a random 128-bit pairing key; off until the owner opens it.
 */
object PhoneRemote {
    private const val PREFS = "ezch_phone_remote"
    private val ports = 8765..8770
    @Volatile private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "ezch-phone").apply { isDaemon = true } }
    private val limiter = RateLimiter(15)
    private val main = Handler(Looper.getMainLooper())
    /** HOME while it is resumed: D-pad keys are delivered straight into it. */
    @Volatile var home: WeakReference<MainActivity>? = null
    private val running = MutableStateFlow(false)
    val state: StateFlow<Boolean> = running

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun key(context: Context): String = prefs(context).getString("key", null) ?: newKey(context)
    fun newKey(context: Context): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString("key", key).apply()
        return key
    }

    /** The address for the QR code, or null when the TV has no home-network IPv4 address. */
    fun url(context: Context): String? {
        val port = server?.localPort ?: return null
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: return null
        return "http://$ip:$port/?k=${key(context)}"
    }

    fun enable(context: Context) { prefs(context).edit().putBoolean("enabled", true).apply(); start(context) }
    fun disable(context: Context) { prefs(context).edit().putBoolean("enabled", false).apply(); stop() }

    /** Idempotent; called when HOME starts and when the owner enables the feature. */
    fun start(context: Context) {
        if (!enabled(context) || server != null) return
        val app = context.applicationContext
        val socket = ports.firstNotNullOfOrNull { port -> runCatching { ServerSocket(port, 16) }.getOrNull() } ?: return
        server = socket
        running.value = true
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { workers.execute { handle(app, client) } }.onFailure { runCatching { client.close() } }
            }
            running.value = false
        }, "ezch-phone-accept").apply { isDaemon = true }.start()
    }
    fun stop() { runCatching { server?.close() }; server = null; running.value = false }

    private fun handle(context: Context, client: Socket) {
        client.use { s ->
            s.soTimeout = 4000
            val out = s.getOutputStream()
            if (!PhoneHttp.homeNetwork(s.inetAddress)) { out.write(PhoneHttp.text(403, "Только домашняя сеть")); return }
            if (!limiter.allow()) { out.write(PhoneHttp.text(429, "Слишком часто")); return }
            val request = runCatching { PhoneHttp.parse(s.getInputStream()) }.getOrNull()
                ?: run { out.write(PhoneHttp.text(400, "Неверный запрос")); return }
            val key = key(context)
            if (!PhoneHttp.authorized(request, key)) {
                out.write(PhoneHttp.text(403, "Отсканируйте QR-код на телевизоре: EZCH → 📱 Телефон")); return
            }
            val reply = when {
                request.method == "GET" && request.path == "/" -> {
                    val html = context.resources.openRawResource(R.raw.phone_remote).use { String(it.readBytes(), Charsets.UTF_8) }
                        .replace("__KEY__", key)
                    out.write(PhoneHttp.response(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))); return
                }
                request.method == "GET" && request.path == "/diag" -> {
                    // The owner reads the field report on his phone (same QR key as the remote page).
                    val text = com.example.homeezch.EzchDiag.report(context)
                    out.write(PhoneHttp.response(200, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))); return
                }
                request.method != "POST" -> PhoneHttp.text(404, "Нет такой страницы")
                request.path == "/api/say" -> onMain { say(context, request.body.trim().take(600)) }
                request.path == "/api/type" -> onMain { type(context, request.body.take(1000)) }
                request.path == "/api/key" -> onMain { key(context, request.body.trim()) }
                request.path == "/api/link" -> onMain { link(context, request.body) }
                else -> PhoneHttp.text(404, "Нет такой команды")
            }
            out.write(reply)
        }
    }

    /** Runs UI/accessibility work on the main thread and waits briefly for its message. */
    private fun onMain(action: () -> String): ByteArray {
        val latch = java.util.concurrent.CountDownLatch(1)
        var message = "Отправлено"
        main.post { message = runCatching(action).getOrElse { "Не получилось: ${it.message.orEmpty()}" }; latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return PhoneHttp.text(200, message)
    }

    private fun accessibility(): AccessibilityService? = EvaAppService.current ?: HomeButtonService.accessibility()

    private fun say(context: Context, text: String): String {
        if (text.isBlank()) return "Пустая команда"
        val eva = EvaAppService.current
        if (eva != null) { eva.submitCommand(text); return "Ева: «$text»" }
        EvaInbox.post(text)
        com.example.homeezch.EzchDiag.log("PHONE", "команда «${text.take(100)}», HOME ${if (home?.get() != null) "на экране" else "скрыт — открываю"}")
        if (home?.get() == null) {
            runCatching { context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                .onFailure { com.example.homeezch.EzchDiag.error("PHONE", "не удалось открыть HOME", it) }
            // Android 10+ silently blocks a background start unless EZCH is the default HOME: say so in the report.
            main.postDelayed({
                if (home?.get() == null && EvaInbox.text.value == text)
                    com.example.homeezch.EzchDiag.log("PHONE:ОШИБКА", "HOME не вышел на экран за 3 с — система не дала открыть EZCH из фона " +
                        "(EZCH не выбран домашним экраном?). Команда выполнится, когда откроете HOME")
            }, 3_000)
        }
        return "Ева: «$text»"
    }

    private fun type(context: Context, text: String): String {
        val root = accessibility()?.rootInActiveWindow
        val field = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root?.let { findEditable(it) }
        if (field != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Напечатано на ТВ"
        }
        if (accessibility() == null) return "Включите службу EZCH в «Специальных возможностях», чтобы печатать в приложениях"
        return "На экране нет поля ввода. Скажите это Еве: кнопка «Сказать Еве»"
    }
    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findEditable(child, depth + 1)?.let { return it } }
        return null
    }

    private fun key(context: Context, name: String): String {
        val audio = context.getSystemService(AudioManager::class.java)
        when (name) {
            "volup" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return "Громче" }
            "voldown" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return "Тише" }
            "mute" -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return "Звук переключён" }
            "playpause" -> {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                return "Пауза / продолжить"
            }
            "back" -> return if (accessibility()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true) "Назад"
                else home?.get()?.let { injectKey(it, KeyEvent.KEYCODE_BACK); "Назад" } ?: "Нужна служба EZCH в «Специальных возможностях»"
            "home" -> {
                if (accessibility()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) != true)
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(ComponentName(context, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Рабочий стол"
            }
        }
        val code = mapOf("up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT, "ok" to KeyEvent.KEYCODE_DPAD_CENTER)[name] ?: return "Неизвестная кнопка"
        // On HOME the real key goes straight into the launcher: exactly like the remote.
        home?.get()?.let { injectKey(it, code); return "OK" }
        // In other apps: move accessibility focus / click the focused element.
        val service = accessibility() ?: return "Стрелки в приложениях работают со службой EZCH в «Специальных возможностях»"
        val root = service.rootInActiveWindow ?: return "Экран недоступен"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            var node = focused
            while (node != null && !node.isClickable) node = node.parent
            return if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) "OK" else "Нечего нажать"
        }
        val direction = mapOf(KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT)[code]!!
        val next = focused?.focusSearch(direction)
        return if (next?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true) "OK" else "Дальше некуда"
    }
    private fun injectKey(activity: android.app.Activity, code: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun link(context: Context, value: String): String {
        val url = PhoneHttp.safeLink(value) ?: return "Нужна ссылка https://…"
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val host = uri.host.orEmpty().lowercase()
        if (host.endsWith("youtube.com") || host == "youtu.be") {
            listOf("com.google.android.youtube.tv", "com.google.android.youtube").firstOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }?.let { intent.setPackage(it) }
        }
        return try { context.startActivity(intent); "Открываю на ТВ" }
        catch (_: android.content.ActivityNotFoundException) { "На ТВ нет приложения для этой ссылки" }
    }
}
