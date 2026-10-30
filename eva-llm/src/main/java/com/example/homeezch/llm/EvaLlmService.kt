package com.example.homeezch.llm

import android.app.Service
import android.content.Intent
import android.os.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/** Messages between the launcher and the ":llm" process. */
object EvaLlmProtocol {
    const val GENERATE = 1; const val STOP = 2; const val UNLOAD = 3
    const val PIECE = 10; const val DONE = 11; const val ERROR = 12; const val LOADING = 13
}

/**
 * Runs the on-device model in its own process. One generation at a time; a new request stops the old one.
 * The model stays loaded for [IDLE_MS] after the last answer, then the process gives the memory back.
 */
class EvaLlmService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "eva-llm").apply { priority = Thread.NORM_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var handle = 0L
    @Volatile private var modelPath: String? = null
    @Volatile private var generation = 0
    private val idle = Runnable { worker.execute { unload() } }

    private fun unload() {
        val h = handle; handle = 0L; modelPath = null
        if (h != 0L) EvaLlmNative.free(h)
    }

    private val incoming = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            EvaLlmProtocol.GENERATE -> { generation++; if (handle != 0L) EvaLlmNative.stop(handle); start(msg.data, msg.replyTo, generation) }
            EvaLlmProtocol.STOP -> { generation++; if (handle != 0L) EvaLlmNative.stop(handle) }
            EvaLlmProtocol.UNLOAD -> { generation++; if (handle != 0L) EvaLlmNative.stop(handle); worker.execute { unload() } }
        }
        true
    }
    private val messenger = Messenger(incoming)

    private fun send(to: Messenger?, what: Int, token: Int, fill: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, what); m.arg1 = token; m.data = Bundle().apply(fill)
        runCatching { to?.send(m) }
    }

    private fun start(data: Bundle, replyTo: Messenger?, token: Int) {
        main.removeCallbacks(idle)
        val path = data.getString("path").orEmpty()
        val roles = data.getStringArray("roles") ?: emptyArray()
        val contents = data.getStringArray("contents") ?: emptyArray()
        val maxTokens = data.getInt("maxTokens", 160)
        val temperature = data.getFloat("temperature", 0.6f)
        val nCtx = data.getInt("nCtx", 2048)
        worker.execute {
            if (token != generation) return@execute
            try {
                if (!EvaLlmNative.ensureLibrary()) { send(replyTo, EvaLlmProtocol.ERROR, token) { putString("text", "Движок нейросети не загрузился: ${EvaLlmNative.loadError}") }; return@execute }
                val started = SystemClock.elapsedRealtime()
                if (handle == 0L || modelPath != path) {
                    unload()
                    send(replyTo, EvaLlmProtocol.LOADING, token)
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    handle = EvaLlmNative.load(path, nCtx, threads)
                    if (handle == 0L) { send(replyTo, EvaLlmProtocol.ERROR, token) { putString("text", "Не удалось загрузить модель") }; return@execute }
                    modelPath = path
                }
                val loadedAt = SystemClock.elapsedRealtime()
                var first = 0L
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                val n = EvaLlmNative.generate(handle, roles, contents, maxTokens, temperature, object : EvaLlmNative.Sink {
                    override fun onBytes(bytes: ByteArray): Boolean {
                        if (token != generation) return false
                        if (first == 0L) first = SystemClock.elapsedRealtime()
                        val out = CharBuffer.allocate(bytes.size + 4)
                        decoder.decode(ByteBuffer.wrap(bytes), out, false); out.flip()
                        val text = out.toString()
                        if (text.isNotEmpty()) send(replyTo, EvaLlmProtocol.PIECE, token) { putString("text", text) }
                        return token == generation
                    }
                })
                val done = SystemClock.elapsedRealtime()
                if (n < 0) send(replyTo, EvaLlmProtocol.ERROR, token) { putString("text", "Ошибка нейросети ($n)") }
                else send(replyTo, EvaLlmProtocol.DONE, token) {
                    putInt("tokens", n); putLong("loadMs", loadedAt - started)
                    putLong("firstMs", if (first == 0L) -1 else first - loadedAt); putLong("totalMs", done - started)
                }
            } catch (e: Throwable) {
                send(replyTo, EvaLlmProtocol.ERROR, token) { putString("text", "Ошибка нейросети: ${e.javaClass.simpleName}") }
            } finally {
                main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { generation++; worker.execute { unload() }; worker.shutdown(); super.onDestroy() }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The TV is short of memory: give the model back unless it is answering right now.
        if (level >= TRIM_MEMORY_RUNNING_LOW) worker.execute { unload() }
    }

    private companion object { const val IDLE_MS = 3 * 60_000L }
}
