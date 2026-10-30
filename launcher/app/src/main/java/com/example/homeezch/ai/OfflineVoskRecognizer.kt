package com.example.homeezch.ai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*

/** Native allocations live in a separate process, released when its last client unbinds. */
internal class OfflineVoskRecognizer(private val context: Context, private val status: (String) -> Unit,
    private val result: (String) -> Unit, private val amplitude: (Float) -> Unit = {},
    private val partial: (String) -> Unit = {}) : Recognizer {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var connection: ServiceConnection? = null
    private var closed = false
    private var server: Messenger? = null
    private var finishPending = false
    var isListening = false; private set
    private val timeout = Runnable { cancel(); status("Время ожидания офлайн-ввода истекло") }
    override fun start() {
        if (closed) return
        cancel()
        if (!AiPreferences.enabled(context)) { status("Помощник выключен"); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Разрешите микрофон в настройках EVA"); return
        }
        val token = generation
        VoiceTiming.mark(VoiceTiming.Phase.OFFLINE_START)
        isListening = true; status("Загружаю русскую офлайн-модель…")
        val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (token == generation && !closed && isListening) when (message.what) {
                OfflineSpeechProtocol.STATUS -> {
                    val terminal = message.data.getBoolean("terminal")
                    val text = message.data.getString("text").orEmpty()
                    if (terminal) cancel()
                    AiPreferences.store(context).edit().putString("speech_state", if (terminal) "error" else "listening").apply()
                    status(text)
                }
                OfflineSpeechProtocol.RESULT -> {
                    VoiceTiming.mark(VoiceTiming.Phase.OFFLINE_RESULT)
                    val text = message.data.getString("text").orEmpty()
                    cancel()
                    if (text.isNotBlank() && text.length <= 600) {
                        AiPreferences.store(context).edit().putString("speech_mode", "vosk-offline")
                            .putString("speech_state", "recognized").putBoolean("ru_result", true).apply()
                        result(text)
                    } else status("Речь не распознана")
                }
                OfflineSpeechProtocol.PARTIAL -> partial(message.data.getString("text").orEmpty().take(600))
                OfflineSpeechProtocol.AMPLITUDE -> amplitude(message.data.getFloat("level").coerceIn(0f, 1f))
            }
            true
        })
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (token != generation) return
                try {
                    server = Messenger(binder)
                    server?.send(Message.obtain(null, OfflineSpeechProtocol.START).apply { replyTo = replies })
                    if (finishPending) server?.send(Message.obtain(null, OfflineSpeechProtocol.FINISH))
                }
                catch (_: RemoteException) { failed() }
            }
            private fun failed() {
                if (token == generation && isListening) { cancel(); status("Офлайн-процесс остановлен. Повторите команду") }
            }
            override fun onServiceDisconnected(name: ComponentName) = failed()
            override fun onBindingDied(name: ComponentName) = failed()
            override fun onNullBinding(name: ComponentName) = failed()
        }
        connection = binding
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or
            (if (Build.VERSION.SDK_INT >= 30) Context.BIND_INCLUDE_CAPABILITIES else 0)
        val bound = runCatching { context.bindService(Intent(context, OfflineSpeechService::class.java), binding, flags) }.getOrDefault(false)
        if (!bound) { cancel(); status("Не удалось запустить офлайн-распознавание"); return }
        AiPreferences.store(context).edit().putString("speech_mode", "vosk-offline").putString("speech_state", "starting").apply()
        main.postDelayed(timeout, 60_000)
    }
    override fun finish() {
        if (!isListening || finishPending) return
        finishPending = true
        runCatching { server?.send(Message.obtain(null, OfflineSpeechProtocol.FINISH)) }
        status("Распознаю…")
        main.removeCallbacks(timeout); main.postDelayed(timeout, 2500)
    }
    override fun cancel() {
        generation++; isListening = false; server = null; finishPending = false; main.removeCallbacks(timeout)
        val binding = connection; connection = null
        if (binding != null) runCatching { context.unbindService(binding) }
        amplitude(0f)
    }
    override fun destroy() { cancel(); closed = true }
}
