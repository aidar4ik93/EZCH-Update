package com.example.homeezch

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Field diagnostics for the owner's TV (Haier): what started, what failed, what EVA heard and did.
 *
 * - Every event goes to a rolling log in app storage (max 2 × 512 KB), written on one background thread.
 * - A crash is written synchronously before the process dies, then the platform handler runs as usual.
 * - 10 s after start, after a crash on the next start and from the «Сохранить диагностику» button the full report
 *   (device snapshot + log) is saved as `Download/EZCH/EZCH_diagnostics.txt`; the phone page serves it at `/diag`.
 * Never written: API keys, PINs, tokens, the phone-remote key. Commands are logged as spoken (owner's own TV).
 */
object EzchDiag {
    private const val MAX_BYTES = 512 * 1024
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val thread by lazy { HandlerThread("ezch-diag").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    @Volatile private var dir: File? = null
    @Volatile private var app: Context? = null
    @Volatile private var started = false
    private val startedAt = SystemClock.elapsedRealtime()

    /** Idempotent; call from every entry point (HOME, voice activity, services). */
    fun init(context: Context, from: String) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (started) { log("START", "вход: $from"); return }
            started = true; app = appContext
            dir = File(appContext.filesDir, "diag").apply { mkdirs() }
        }
        HardwareInspection.schedule(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                writeNow("CRASH", "поток ${t.name}: ${e.stackTraceToString().take(6000)}")
                appContext.getSharedPreferences("ezch_diag", Context.MODE_PRIVATE).edit()
                    .putString("last_crash", "${time.format(Date())} ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}")
                    .putBoolean("export_pending", true).commit()
            }
            previous?.uncaughtException(t, e)
        }
        val crashed = appContext.getSharedPreferences("ezch_diag", Context.MODE_PRIVATE).getBoolean("export_pending", false)
        log("START", "процесс ${Process.myPid()} · ${versionLine(appContext)} · вход: $from" + if (crashed) " · прошлый запуск закончился падением" else "")
        handler.postDelayed({
            runCatching { log("SNAPSHOT", "\n" + snapshot(appContext)) }
            export(appContext, "автоматически при запуске")
            appContext.getSharedPreferences("ezch_diag", Context.MODE_PRIVATE).edit().putBoolean("export_pending", false).apply()
        }, 10_000)
    }

    fun log(tag: String, message: String) {
        val line = "${time.format(Date())} [$tag] $message"
        android.util.Log.i("EzchDiag", line.take(3000))
        if (dir == null) return
        runCatching { handler.post { append(line) } }
    }

    fun error(tag: String, message: String, error: Throwable? = null) =
        log("$tag:ОШИБКА", message + (error?.let { " — ${it.javaClass.simpleName}: ${it.message.orEmpty().take(400)}\n" + it.stackTraceToString().lines().take(8).joinToString("\n") } ?: ""))

    private fun writeNow(tag: String, message: String) = append("${time.format(Date())} [$tag] $message")

    @Synchronized private fun append(line: String) {
        val d = dir ?: return
        val file = File(d, "ezch-diag.log")
        runCatching {
            if (file.length() > MAX_BYTES) { File(d, "ezch-diag.1.log").delete(); file.renameTo(File(d, "ezch-diag.1.log")) }
            file.appendText(line + "\n")
        }
    }

    private fun versionLine(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "EZCH ${info.versionName} ($code)"
    }.getOrDefault("EZCH ?")

    private fun yes(value: Boolean) = if (value) "да" else "нет"

    /** Device and launcher state. Only presence flags for secrets, never the values. */
    fun snapshot(context: Context): String = buildString {
        fun line(name: String, value: () -> Any?) { append(name).append(": ").append(runCatching { value()?.toString() ?: "—" }.getOrElse { "ошибка ${it.javaClass.simpleName}" }).append('\n') }
        appendLine("=== Устройство ===")
        line("Автодиагностика оборудования") { HardwareInspection.cached(context) ?: "Запланирована при первом запуске" }
        line("Телевизор") { "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), бренд ${Build.BRAND}" }
        line("Android") { "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}, сборка ${Build.DISPLAY}" }
        line("Лаунчер") { versionLine(context) + ", пакет ${context.packageName}" }
        line("Работает с запуска") { "${(SystemClock.elapsedRealtime() - startedAt) / 1000} с; включён ${SystemClock.elapsedRealtime() / 60000} мин" }
        line("Память") {
            val m = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            "свободно ${m.availMem shr 20} из ${m.totalMem shr 20} МБ, мало памяти: ${yes(m.lowMemory)}; EZCH PSS ${Debug.getPss() shr 10} МБ"
        }
        line("Диск") { "свободно ${context.filesDir.usableSpace shr 20} МБ" }
        line("Сеть") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps == null) "нет подключения" else listOfNotNull(
                "Wi-Fi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "Ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                "интернет проверен".takeIf { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
                "${caps.linkDownstreamBandwidthKbps / 1000} Мбит/с"
            ).joinToString(", ")
        }
        appendLine("=== Система ===")
        line("HOME по умолчанию") {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.let { "${it.packageName}/${it.name}" }
        }
        line("Голосовой помощник (система)") { Settings.Secure.getString(context.contentResolver, "voice_interaction_service") ?: Settings.Secure.getString(context.contentResolver, "assistant") }
        line("Спец. возможности включены") { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.joinToString(", ") { it.substringAfterLast('/') } }
        line("Служба кнопки HOME EZCH") { yes(HomeButtonService.connected) }
        line("Служба Ai EvA (управление приложениями)") { yes(com.example.homeezch.ai.EvaAppService.current != null) }
        line("Синтезатор речи (система)") { Settings.Secure.getString(context.contentResolver, "tts_default_synth") ?: "по умолчанию" }
        line("Разрешение микрофона") { yes(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
        line("Установлены") {
            listOf("com.google.android.tts" to "Google TTS", "com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.googletv" to "YouTube GTV",
                "com.google.android.katniss" to "Google Ассистент", "com.example.ezchupdate" to "EZCH Update")
                .filter { (p, _) -> runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess }.joinToString { it.second }.ifEmpty { "ничего из списка" }
        }
        appendLine("=== Ева ===")
        val prefs = com.example.homeezch.ai.AiPreferences.store(context)
        line("Ева включена") { yes(com.example.homeezch.ai.AiPreferences.enabled(context)) }
        line("Озвучка ответов") { yes(prefs.getBoolean("tts", true)) + ", движок готов: " + yes(com.example.homeezch.ai.EvaTts.ready) +
            ", голос: " + prefs.getString("tts_voice_active", "—") + (if (prefs.getBoolean("tts_language_missing", false)) ", НЕТ РУССКОГО ГОЛОСА" else "") }
        line("Скорость речи") { prefs.getFloat("tts_rate", 1f) }
        line("Распознавание") { if (com.example.homeezch.ai.AiPreferences.networkSpeech(context)) "Google онлайн + Vosk офлайн" else "только офлайн Vosk" }
        line("«Ева» без рук") { yes(com.example.homeezch.ai.EvaEngine.handsFreeEnabled(context)) }
        line("Отчёт помощника") { "\n  " + com.example.homeezch.ai.AndroidCapabilityProbe(context).report().replace("\n", "\n  ") }
        line("ИИ ядро") { "EVA Core офлайн; разговор: " + if (com.example.homeezch.ai.GigaChatSettings.enabled(context)) "GigaChat ${com.example.homeezch.ai.GigaChatSettings.model(context)}" else if (com.example.homeezch.ai.GigaChatSettings.hasKey(context)) "GigaChat (ключ есть, согласие выключено)" else "GigaChat не подключён" }
        line("Последнее падение") { context.getSharedPreferences("ezch_diag", Context.MODE_PRIVATE).getString("last_crash", "не было") }
    }

    fun report(context: Context): String = buildString {
        appendLine("EZCH · диагностика · ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Отправьте этот файл Айдару/Claude. Ключей, PIN и паролей здесь нет.\n")
        append(snapshot(context))
        appendLine("\n=== Журнал (последние события, новые внизу) ===")
        val d = dir ?: File(context.filesDir, "diag")
        val lines = listOf(File(d, "ezch-diag.1.log"), File(d, "ezch-diag.log")).filter { it.exists() }
            .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        if (lines.isEmpty()) appendLine("пусто") else lines.takeLast(3000).forEach { appendLine(it) }
    }

    /** Saves the report where the owner can reach it; returns a human-readable place or the reason it failed. */
    fun export(context: Context, why: String): String {
        val text = runCatching { report(context) }.getOrElse { "Отчёт не собран: ${it.stackTraceToString()}" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val place = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val dirName = Environment.DIRECTORY_DOWNLOADS + "/EZCH/"
                val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(dirName, FILE), null)
                    ?.use { if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection, it.getLong(0)) else null }
                val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dirName)
                }) ?: error("MediaStore не создал файл")
                resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                "Загрузки → EZCH → $FILE"
            } else {
                val file = File(context.getExternalFilesDir(null), FILE); file.writeBytes(bytes)
                file.absolutePath
            }
        }.getOrElse { e ->
            // Last resort: app storage, still served to the phone at /diag.
            File(context.filesDir, "diag/$FILE").writeBytes(bytes)
            "память приложения (MediaStore: ${e.javaClass.simpleName}); откройте /diag с телефона"
        }
        log("EXPORT", "$why → $place, ${bytes.size / 1024} КБ")
        return place
    }

    const val FILE = "EZCH_diagnostics.txt"
}
