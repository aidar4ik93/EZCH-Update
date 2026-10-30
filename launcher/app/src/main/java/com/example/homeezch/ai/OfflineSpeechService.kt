package com.example.homeezch.ai

import android.app.Service
import android.content.Intent
import android.os.*

internal object OfflineSpeechProtocol {
    const val FINISH = 6; const val START = 1; const val STATUS = 2; const val RESULT = 3; const val PARTIAL = 4; const val AMPLITUDE = 5
}

/** Non-exported bound service; its microphone capability comes from the visible client. */
class OfflineSpeechService : Service() {
    private var engine: OfflineVoskSession? = null
    private val channel = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == OfflineSpeechProtocol.FINISH) engine?.finish()
        if (message.what == OfflineSpeechProtocol.START) {
            val client = message.replyTo
            if (client != null) {
                fun send(code: Int, text: String = "", level: Float = 0f, terminal: Boolean = false) {
                    runCatching { client.send(Message.obtain(null, code).apply {
                        data = Bundle().apply { putString("text", text.take(600)); putFloat("level", level); putBoolean("terminal", terminal) }
                    }) }
                }
                if (engine != null) send(OfflineSpeechProtocol.STATUS, "Другая сессия EVA ещё завершается. Повторите команду", terminal = true)
                else {
                    engine = OfflineVoskSession(this,
                        status = { send(OfflineSpeechProtocol.STATUS, it, terminal = engine?.isListening != true) },
                        result = { send(OfflineSpeechProtocol.RESULT, it) },
                        amplitude = { send(OfflineSpeechProtocol.AMPLITUDE, level = it) },
                        partial = { send(OfflineSpeechProtocol.PARTIAL, it) })
                    engine?.start()
                }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = channel.binder
    override fun onUnbind(intent: Intent): Boolean { endProcess(); return false }
    override fun onDestroy() { endProcess(); super.onDestroy() }
    private fun endProcess() {
        engine?.destroy(); engine = null
        // Confined to the private speech process; never terminate the launcher.
        val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName.orEmpty()
        if (name == "$packageName:eva_speech") android.os.Process.killProcess(android.os.Process.myPid())
    }
}
