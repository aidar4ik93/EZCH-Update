package com.example.homeezch.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import com.example.homeezch.llm.EvaLlmProtocol
import com.example.homeezch.llm.EvaLlmService
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * EVA's own brain on the TV: a small chat model (GGUF) run by llama.cpp in the ":llm" process.
 * No keys, no account, works without internet once the model file is on the TV.
 * TV commands are still handled first by EVA Core (instant, offline); the model only talks.
 */
internal object EvaModels {
    data class Model(val id: String, val title: String, val file: String, val url: String, val bytes: Long, val sha256: String?, val note: String)

    /** Filled from the owner's network check (claude-build-logs/r22-models.json); first = default. */
    val CATALOG: List<Model> = EvaModelCatalog.MODELS

    private fun dir(context: Context) = File(context.filesDir, "llm").apply { mkdirs() }
    fun file(context: Context, m: Model) = File(dir(context), m.file)
    fun installed(context: Context, m: Model) = file(context, m).let { it.isFile && it.length() == m.bytes }
    fun active(context: Context): Model? {
        val id = AiPreferences.store(context).getString("llm_model", null)
        return CATALOG.firstOrNull { it.id == id && installed(context, it) } ?: CATALOG.firstOrNull { installed(context, it) }
    }
    fun select(context: Context, m: Model) = AiPreferences.store(context).edit().putString("llm_model", m.id).apply()
    fun delete(context: Context, m: Model) { file(context, m).delete(); File(dir(context), m.file + ".part").delete() }
    fun freeBytes(context: Context) = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)

    /** Resumable download (Range) with size and SHA-256 check. [progress] gets 0..100. Returns null on success or an error text. */
    suspend fun download(context: Context, m: Model, progress: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val target = file(context, m); if (installed(context, m)) return@withContext null
        if (freeBytes(context) < m.bytes + 200L * 1024 * 1024) return@withContext "Мало места: нужно ${m.bytes / 1_000_000 + 200} МБ свободных"
        val part = File(dir(context), m.file + ".part")
        var lastError = "Не удалось скачать"
        for (url in listOf(m.url, m.url.replace("https://huggingface.co/", "https://hf-mirror.com/"))) {
            for (attempt in 0 until 4) {
                try {
                    val have = if (part.exists()) part.length() else 0L
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "EZCH-EVA")
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code !in listOf(200, 206)) { lastError = "Сервер ответил $code"; c.disconnect(); break }
                    val append = code == 206 && have > 0
                    c.inputStream.use { input ->
                        java.io.FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(1 shl 16); var done = if (append) have else 0L; var shown = -1
                            while (true) {
                                ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val p = (done * 100 / m.bytes).toInt().coerceIn(0, 100); if (p != shown) { shown = p; progress(p) }
                            }
                        }
                    }
                    c.disconnect()
                    if (part.length() != m.bytes) { lastError = "Файл скачан не полностью"; continue }
                    if (m.sha256 != null && sha256(part) != m.sha256) { part.delete(); lastError = "Файл модели повреждён, скачайте ещё раз"; continue }
                    if (!part.renameTo(target)) return@withContext "Не удалось сохранить модель"
                    return@withContext null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { lastError = "Обрыв загрузки: ${e.javaClass.simpleName}"; delay(2000L * (attempt + 1)) }
            }
        }
        lastError
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Connection to the ":llm" process; one request at a time. */
internal class EvaLlmClient(private val context: Context) {
    private var messenger: Messenger? = null
    private var connection: ServiceConnection? = null

    private suspend fun connect(): Messenger? = messenger ?: suspendCancellableCoroutine { cont ->
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { messenger = Messenger(binder); if (cont.isActive) cont.resume(messenger) }
            override fun onServiceDisconnected(name: ComponentName) { messenger = null }
            override fun onNullBinding(name: ComponentName) { if (cont.isActive) cont.resume(null) }
        }
        connection = c
        val ok = runCatching { context.applicationContext.bindService(Intent(context, EvaLlmService::class.java), c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }.getOrDefault(false)
        if (!ok && cont.isActive) cont.resume(null)
    }

    data class Outcome(val text: String, val error: String?, val tokens: Int, val firstMs: Long, val totalMs: Long)

    /** Streams pieces to [onPiece] (main thread); returns the whole answer. */
    suspend fun generate(path: String, roles: Array<String>, contents: Array<String>, maxTokens: Int, onPiece: (String) -> Unit,
                         onLoading: () -> Unit = {}): Outcome {
        val target = connect() ?: return Outcome("", "Служба нейросети не запустилась", 0, -1, 0)
        return withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine { cont ->
                val text = StringBuilder()
                val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
                    when (m.what) {
                        EvaLlmProtocol.PIECE -> { val p = m.data.getString("text").orEmpty(); text.append(p); onPiece(p) }
                        EvaLlmProtocol.LOADING -> onLoading()
                        EvaLlmProtocol.DONE -> if (cont.isActive) cont.resume(Outcome(text.toString(), null, m.data.getInt("tokens"), m.data.getLong("firstMs"), m.data.getLong("totalMs")))
                        EvaLlmProtocol.ERROR -> if (cont.isActive) cont.resume(Outcome(text.toString(), m.data.getString("text") ?: "Ошибка нейросети", 0, -1, 0))
                    }
                    true
                })
                val msg = Message.obtain(null, EvaLlmProtocol.GENERATE).apply {
                    replyTo = replies
                    data = Bundle().apply {
                        putString("path", path); putStringArray("roles", roles); putStringArray("contents", contents)
                        putInt("maxTokens", maxTokens); putFloat("temperature", 0.5f); putInt("nCtx", 2048)
                    }
                }
                try { target.send(msg) } catch (e: RemoteException) { messenger = null; if (cont.isActive) cont.resume(Outcome("", "Служба нейросети перезапускается, повторите", 0, -1, 0)) }
                cont.invokeOnCancellation { runCatching { target.send(Message.obtain(null, EvaLlmProtocol.STOP)) } }
            }
        } ?: run { runCatching { target.send(Message.obtain(null, EvaLlmProtocol.STOP)) }; Outcome("", "Нейросеть думает слишком долго", 0, -1, 90_000) }
    }

    fun close() { connection?.let { runCatching { context.applicationContext.unbindService(it) } }; connection = null; messenger = null }
}

/** The conversation turn with the on-device model: persona, short history, sentence-by-sentence output. */
internal object EvaLocalBrain {
    fun available(context: Context) = EvaModels.active(context) != null && AiPreferences.store(context).getBoolean("llm_enabled", true)

    fun persona(c: EvaChat.Context): String = buildString {
        append("Ты Ева — голосовой помощник телевизора. Отвечай по-русски, коротко: одно-два предложения, просто и дружелюбно. ")
        append("О себе говори в женском роде. Без списков, смайликов и ссылок. Если не знаешь точно — честно скажи. ")
        append("Если просят включить музыку, видео или фильм, ответь одной строкой: ВКЛЮЧИТЬ: <что искать на YouTube>. ")
        append("Сейчас ${c.now}."); c.city?.let { append(" Город: $it.") }
    }

    /** Splits streamed text into speakable sentences as soon as each one is complete. */
    class Sentences(private val onSentence: (String) -> Unit) {
        private val buf = StringBuilder()
        fun add(piece: String) {
            buf.append(piece)
            while (true) {
                val m = Regex("[.!?…](?=\\s|$)").find(buf) ?: break
                if (m.range.last + 1 >= buf.length && !buf.endsWith(" ")) break  // wait: «т.» may continue
                val s = buf.substring(0, m.range.last + 1).trim(); buf.delete(0, m.range.last + 1)
                if (s.isNotEmpty() && !s.startsWith("ВКЛЮЧИТЬ")) onSentence(s)
            }
        }
        fun flush() { val s = buf.toString().trim(); buf.clear(); if (s.isNotEmpty() && !s.startsWith("ВКЛЮЧИТЬ")) onSentence(s) }
    }

    suspend fun reply(context: Context, client: EvaLlmClient, text: String, history: List<Pair<String, String>>, c: EvaChat.Context,
                      onSentence: (String) -> Unit, onLoading: () -> Unit): EvaChat.Result {
        val model = EvaModels.active(context) ?: return EvaChat.Result.Failed("Нейросеть Евы не скачана. Скачайте её в настройках Евы")
        val turns = history.takeLast(6)
        val roles = arrayOf("system") + turns.map { it.first } + "user"
        val contents = arrayOf(persona(c)) + turns.map { it.second } + text
        val sentences = Sentences { s -> onSentence(EvaChat.speakable(s)) }
        val out = client.generate(EvaModels.file(context, model).path, roles, contents, maxTokens = 110,
            onPiece = { sentences.add(it) }, onLoading = onLoading)
        com.example.homeezch.EzchDiag.log("LLM", "${model.id}: токенов ${out.tokens}, первое слово ${out.firstMs} мс, всего ${out.totalMs} мс${out.error?.let { ", ошибка: $it" } ?: ""}")
        if (out.error != null && out.text.isBlank()) return EvaChat.Result.Failed(out.error)
        val full = out.text.trim()
        Regex("ВКЛЮЧИТЬ:\\s*(.{2,80})").find(full)?.let { m ->
            val q = m.groupValues[1].lineSequence().first().trim().trim('«', '»', '"', '.')
            if (q.isNotBlank()) return EvaChat.Result.Act(listOf(Action.PlayOn("youtube", q, autoplay = true)), "play_on_youtube")
        }
        sentences.flush()
        val spoken = EvaChat.speakable(full)
        return if (spoken.isBlank()) EvaChat.Result.Failed("Нейросеть не нашла ответа") else EvaChat.Result.Say(spoken)
    }
}
