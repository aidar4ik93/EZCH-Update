package com.example.homeezch.ai

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.view.View
import android.view.KeyEvent
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** One shared reference UI for the in-launcher Activity and the system assistant. */
class VoicePanel(private val context: Context, private val homeResult: ((String) -> Unit)? = null,
    private val close: () -> Unit, compact: Boolean = false, private val mini: Boolean = true,
    private val onActivity: () -> Unit = {}, private val onStatusText: (String) -> Unit = {}) : OverlayPresenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pendingChoice: CancellableContinuation<AppTarget?>? = null
    private var options: List<AppTarget> = emptyList()
    private var executing = false
    /** Installed apps for the core («включи кино» → which film apps exist); loaded off the main thread. */
    @Volatile private var appsCache: List<AppTarget> = emptyList()
    private var ttsReady = false
    private var pendingReply: Pair<String, Boolean>? = null
    private var listeningLater: Job? = null
    private var conversationTimeout: Job? = null
    private var speakingNow = false
    private var finalizingRecognition = false
    @Volatile private var utterancePrefix = ""
    private var followUpWindow = false
    @Volatile private var destroyed = false
    // Speech goes through the launcher-wide warm engine (EvaTts): its own worker thread, never the UI thread.
    private val speechGeneration = java.util.concurrent.atomic.AtomicInteger()
    private fun stopSpeech() {
        speechGeneration.incrementAndGet(); followUpId = null; speakingNow = false; utterancePrefix = ""; lastUtteranceId = null; completedUtteranceId = null
        listeningLater?.cancel(); conversationTimeout?.cancel(); followUpWindow = false
        EvaTts.stop()
    }
    private val sphere = (if (mini) EvaMiniView(context) else EvaSphereView(context)).apply { this.compact = compact }
    private val textInput = EditText(context).apply { hint = "Команда Еве"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
    private val editor = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
        setPadding(20, 12, 20, 12); setBackgroundColor(0xF0080712.toInt())
        addView(textInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(context).apply { text = "Выполнить"; isAllCaps = false; setOnClickListener { val command = textInput.text.toString(); hideEditor(); submit(command) } })
        addView(Button(context).apply { text = "Закрыть"; isAllCaps = false; setOnClickListener { hideEditor() } })
    }
    private val root = FrameLayout(context).apply {
        addView(sphere, FrameLayout.LayoutParams(-1, -1))
        if (!mini) addView(editor, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }
    // Several recognition hypotheses: EVA Core picks the one it understands («громкость 20» over «громко 20 два»).
    private val recognizer = HybridVoiceRecognizer(context, ::recognitionStatus, ::submit,
        amplitude = { sphere.amplitude = it }, partial = { sphere.transcript = it },
        choose = { hypotheses -> EvaBrain.core(context).pick(hypotheses) { appsCache } })
    val view: android.view.View get() = root
    private val appsReady = scope.async(Dispatchers.IO) {
        runCatching { EvaBrain.core(context.applicationContext) } // Load local memory preferences before the first command, off UI.
        runCatching { AndroidActionExecutor(context).apps() }.getOrDefault(emptyList()).also { appsCache = it }
    }
    fun setCompact(value: Boolean) { sphere.compact = value }
    fun handleKey(code: Int, event: KeyEvent): Boolean = if (editor.visibility == View.VISIBLE) false else sphere.onKeyDown(code, event)
    /** Sphere animation for this panel's speech (forwarded by EvaTts while this panel is the active one). */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { sphere.post {
            if (!destroyed && id?.startsWith("$utterancePrefix-") == true) { speakingNow = true; sphere.busy = true }
        } }
        override fun onDone(id: String?) {
            sphere.post {
                if (destroyed || id?.startsWith("$utterancePrefix-") != true) return@post
                completedUtteranceId = id
                if (id != lastUtteranceId) return@post
                speakingNow = false; sphere.busy = executing; sphere.amplitude = 0f
                // EVA asked a question («Громче или тише?», «Вы имели в виду…?»): listen for the answer without another button press.
                if (id != null && id == followUpId) { followUpId = null; if (!recognizer.isListening) startFollowUp() }
            }
        }
        @Deprecated("Platform callback") override fun onError(id: String?) { sphere.post {
            if (!destroyed && id?.startsWith("$utterancePrefix-") == true) {
                speakingNow = false; followUpId = null; sphere.busy = false; sphere.amplitude = 0f
            }
        } }
        override fun onBeginSynthesis(id: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) { format = audioFormat }
        private var format = android.media.AudioFormat.ENCODING_PCM_16BIT
        override fun onAudioAvailable(id: String?, audio: ByteArray?) {
            val bytes = audio ?: return
            if (format != android.media.AudioFormat.ENCODING_PCM_16BIT || bytes.size < 2) return
            var sum = 0.0
            for (i in 0 until bytes.size - 1 step 2) { val v = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toDouble(); sum += v*v }
            val level = (sqrt(sum / (bytes.size / 2)) / 8000).toFloat().coerceIn(0f, 1f)
            sphere.post { if (!destroyed && id?.startsWith("$utterancePrefix-") == true) sphere.amplitude = level }
        }
    }
    init {
        VoiceTiming.mark(VoiceTiming.Phase.PANEL_READY)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
        sphere.onListen = ::listen
        sphere.onText = ::textCommand
        sphere.onSettings = { context.startActivity(Intent(context, AssistantSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (mini) sphere.setOnLongClickListener { close(); true } else sphere.requestFocus()
        status(if (AiPreferences.enabled(context)) "Нажмите OK" else "Ева выключена · Меню — настройки")
        if (AiPreferences.store(context).getBoolean("tts", true)) {
            EvaTts.listener = progress
            EvaTts.warm(context) { ready ->
                sphere.post {
                    if (!destroyed) {
                        ttsReady = ready
                        com.example.homeezch.EzchDiag.log("TTS", if (ready) "голос готов: ${AiPreferences.store(context).getString("tts_voice_active", "?")}" else "голос НЕ готов (нет русского голоса или движок не ответил)")
                        // Never mute silently: tell the owner once why EVA cannot speak.
                        if (!ready) { pendingReply = null; status("Ева не может говорить: на телевизоре нет русского голоса. Установите «Синтезатор речи Google» и русский голос в настройках") }
                        pendingReply?.let { pendingReply = null; answer(it.first, it.second) }
                    }
                }
            }
        }
    }
    override fun status(message: String) { sphere.message = message; onActivity(); onStatusText(message); if (!executing && !message.endsWith("…") && !message.startsWith("Подключаю")) sphere.busy = false }
    private fun recognitionStatus(message: String) {
        finalizingRecognition = recognizer.isListening && message == "Распознаю…"
        if (followUpWindow && message == "Слушаю…") {
            conversationTimeout?.cancel(); followUpWindow = false
        }
        if (followUpWindow && !recognizer.isListening && !message.endsWith("…")) {
            conversationTimeout?.cancel(); followUpWindow = false; close(); return
        }
        com.example.homeezch.EzchDiag.log("MIC", message)
        status(message)
        if (AiPreferences.enabled(context) && !recognizer.isListening && !message.endsWith("…") && !message.startsWith("Подключаю")) answer(message)
    }
    fun listen() {
        VoiceTiming.mark(VoiceTiming.Phase.MIC_REQUESTED)
        // Second press of OK / the mic button while EVA listens means «I have finished»: recognize now, do not restart.
        if (recognizer.isListening && !finalizingRecognition) {
            finalizingRecognition = true; recognizer.finish(); return
        }
        // A new mic press while a provider finalizes starts a fresh phrase; BACK is never required.
        finalizingRecognition = false
        if (pendingChoice?.isActive == true) {
            val interrupted = speakingNow
            pendingReply = null; stopSpeech(); sphere.busy = true
            startMicrophone(interrupted); return
        }
        val interrupted = speakingNow
        cancel(false); sphere.transcript = ""; sphere.busy = true
        startMicrophone(interrupted)
    }
    private fun startMicrophone(interrupted: Boolean) {
        if (interrupted) {
            val token = speechGeneration.get()
            EvaTts.stop { sphere.post {
                if (destroyed || speechGeneration.get() != token) return@post
                listeningLater = scope.launch {
                    delay(180) // Let the loudspeaker tail decay before opening the microphone.
                    if (!destroyed && speechGeneration.get() == token) recognizer.start()
                }
            } }
        } else recognizer.start()
    }
    private fun textCommand() {
        if (mini) { status("Компактный голосовой режим"); return }
        recognizer.cancel(); stopSpeech(); sphere.busy = false
        editor.visibility = View.VISIBLE; textInput.requestFocus()
        textInput.post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun hideEditor() { context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(textInput.windowToken, 0); editor.visibility = View.GONE; sphere.requestFocus() }
    fun submit(text: String) {
        conversationTimeout?.cancel(); followUpWindow = false
        if (!appsReady.isCompleted) {
            job?.cancel()
            job = scope.launch { appsReady.await(); ensureActive(); if (!destroyed) submit(text) }
            return
        }
        VoiceTiming.mark(VoiceTiming.Phase.COMMAND_RECEIVED)
        val reply = normalize(text).replace(Regex("^(?:ева|eva)[, ]+"), "").trimEnd('.', '!', '?')
        if (pendingChoice?.isActive == true && reply in setOf("нет", "не надо", "no")) {
            pendingChoice?.resume(null); pendingChoice = null; options = emptyList(); return
        }
        if (pendingChoice?.isActive == true && options.size == 1 && reply in setOf("да", "да открой", "открой", "yes")) {
            pendingChoice?.resume(options.single()); pendingChoice = null; options = emptyList(); return
        }
        // An ordinal must resolve a pending question rather than cancel it first.
        val resolution = LocalIntentResolver().resolve(text)
        if (pendingChoice?.isActive == true && resolution is Resolution.Plan && resolution.actions.singleOrNull() is Action.Choose) {
            val index = (resolution.actions.single() as Action.Choose).index
            val chosen = options.getOrNull(index - 1) ?: run { status("Варианта $index нет"); return }
            pendingChoice?.resume(chosen); pendingChoice = null; options = emptyList(); return
        }
        job?.cancel(); clearChoice(); recognizer.cancel(); stopSpeech(); sphere.transcript = text
        if (!AiPreferences.enabled(context)) { com.example.homeezch.EzchDiag.log("EVA", "команда «${text.take(100)}», но Ева выключена"); status("Помощник выключен"); return }
        // EVA AI Core (offline): dialog context («ещё», «наоборот»), memory, clarifying questions, meaning frames
        // («телевизор тормозит») and the meaning layer («громко 20», «открой его») on top of the strict rules.
        val decision = runCatching { EvaBrain.core(context).handle(text) { appsCache } }.getOrElse {
            com.example.homeezch.EzchDiag.error("EVA", "ядро не разобрало «$text»", it); EvaReply.Say("Не поняла команду")
        }
        com.example.homeezch.EzchDiag.log("EVA", "«${text.take(200)}» → ${decision.toString().take(300)}")
        when (decision) {
            is EvaReply.Say -> answer(decision.text)
            is EvaReply.Clarify -> answer(decision.question, expectReply = true)
            is EvaReply.Confirm -> answer(decision.question, expectReply = true)
            is EvaReply.Execute -> runPlan(decision.actions)
            is EvaReply.Chat -> chatTurn(decision.text, decision.offline)
        }
    }
    private val llm by lazy { EvaLlmClient(context) }
    /**
     * A free conversation turn. Brain: GigaChat when the owner gave it a key, otherwise EVA's own model on the TV
     * (no key, works offline). Sentences are spoken as soon as each is ready; an action request runs like a command.
     */
    private fun chatTurn(text: String, offline: String) {
        val online = GigaChatSettings.enabled(context)
        if (!online && !EvaLocalBrain.available(context)) { answer(offline); return }
        val core = EvaBrain.core(context)
        val profile = com.example.homeezch.Profiles.active(context)
        job = scope.launch {
            executing = true; sphere.busy = true; status("Думаю…")
            val started = android.os.SystemClock.elapsedRealtime()
            val token = speechGeneration.get()
            var spokenAny = false
            // GPT r22: one turn prefix keeps progress/barge-in callbacks valid across all sentence IDs.
            val streamPrefix = "eva-llm-${System.identityHashCode(this@VoicePanel)}-$token-${android.os.SystemClock.elapsedRealtimeNanos()}"
            utterancePrefix = streamPrefix
            val streamSpeechReady = ttsReady
            var lastUtterance: String? = null
            var part = 0
            val speakSentence: (String) -> Unit = { sentence ->
                val phrases = EvaSpeak.prepare(sentence)
                if (!destroyed && speechGeneration.get() == token && AiPreferences.store(context).getBoolean("tts", true) && streamSpeechReady && ttsReady && phrases.isNotEmpty()) {
                    EvaTts.listener = progress
                    // Each streamed sentence gets its own utterance ids; the first one interrupts, the rest queue up.
                    val prefix = "$streamPrefix-${part++}"
                    EvaTts.speak(phrases, prefix, append = spokenAny) { !destroyed && speechGeneration.get() == token }
                    lastUtterance = "$prefix-${phrases.size - 1}"; lastUtteranceId = lastUtterance
                    spokenAny = true
                }
                sphere.transcript = sentence; onStatusText(sentence); onActivity()
            }
            val result = try {
                val chatContext = EvaChat.Context(
                    now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale("ru")).format(java.util.Date()),
                    city = withContext(Dispatchers.IO) { com.example.homeezch.WeatherAccess.loadPoint(context)?.city }
                        ?.let { EvaSpeak.prepare(it).joinToString(" ") },
                    lastApp = core.lastApp, apps = appsCache.map { it.label })
                if (online) GigaChatClient(context).reply(text, core.chat.history(), chatContext)
                else EvaLocalBrain.reply(context, llm, text, core.chat.history(), chatContext, onSentence = speakSentence,
                    onLoading = { status("Просыпаюсь…") })
            } finally { executing = false; sphere.busy = false; sphere.amplitude = 0f }
            ensureActive()
            if (destroyed || !AiPreferences.enabled(context) || com.example.homeezch.Profiles.active(context) != profile) return@launch
            com.example.homeezch.EzchDiag.log(if (online) "GIGACHAT" else "LLM", "«${text.take(120)}» → ${result.toString().take(240)} за ${android.os.SystemClock.elapsedRealtime() - started} мс")
            when (result) {
                is EvaChat.Result.Say -> {
                    core.chat.add("user", text); core.chat.add("assistant", result.text)
                    // Conversation mode (GPT r22 setting) or EVA asked back: listen right after the last sentence has been said.
                    val expect = AiPreferences.store(context).getBoolean("conversation_follow_up", true) || result.text.trimEnd().endsWith("?")
                    // Already spoken sentence by sentence (on-device brain); otherwise speak the whole answer now.
                    if (spokenAny) {
                        status(result.text)
                        if (expect) {
                            followUpId = lastUtterance
                            // GPT r22: synthesis may finish before the model sends DONE.
                            if (lastUtterance != null && completedUtteranceId == lastUtterance) {
                                followUpId = null; startFollowUp()
                            }
                        }
                    }
                    else answer(EvaChat.speakable(result.text), expectReply = expect)
                }
                is EvaChat.Result.Act -> {
                    core.chat.add("user", text); core.chat.add("assistant", "Выполняю: ${result.call}")
                    runPlan(result.actions)
                }
                is EvaChat.Result.Failed -> answer(result.message)
            }
        }
    }
    private fun runPlan(plan: List<Action>) {
        if (plan.any { it == Action.Dismiss }) { cancel(false); close(); return }
        if (plan.any { it == Action.Cancel }) { cancel(); return }
        if (homeResult != null && plan.all { it is Action.Navigate }) {
            // HOME moves its own focus; follow-ups («ещё три раза») are re-spelled as a strict command.
            navigationText(plan)?.let { homeResult.invoke(it); return }
        }
        val brain = EvaBrain.core(context)
        job = scope.launch {
            executing = true; sphere.busy = true
            try {
                val executor = AndroidActionExecutor(context, chooseApp = ::choose)
                for (action in plan) {
                    ensureActive(); status(when(action) { is Action.Search -> "Ищу ${action.query}"; is Action.Launch -> "Открываю ${action.name}"; is Action.PlayOn -> "Ищу «${action.query}» на YouTube"; Action.Weather -> "Узнаю погоду…"; is Action.Diagnose -> "Проверяю телевизор…"; else -> "Выполняю…" })
                    val outcome = try { executor.execute(action) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                        // A firmware refusing an intent must not kill the panel: report it honestly instead.
                        com.example.homeezch.EzchDiag.error("ACTION", "$action упало", e)
                        Outcome(false, "Не получилось: телевизор не выполнил команду")
                    }
                    com.example.homeezch.EzchDiag.log(if (outcome.accepted) "ACTION" else "ACTION:НЕ ВЫПОЛНЕНО", "$action → ${outcome.message}")
                    VoiceTiming.mark(VoiceTiming.Phase.ACTION_READY)
                    brain.executed(action, outcome.accepted)
                    ensureActive(); status(outcome.message)
                    if (!outcome.accepted) { answer(outcome.message); return@launch }
                }
                answer(sphere.message)
            } finally { executing = false; sphere.busy = false; sphere.amplitude = 0f }
        }
    }
    /** Utterance id of the last phrase of a question; when it has been spoken EVA listens for the answer. */
    @Volatile private var followUpId: String? = null
    private var lastUtteranceId: String? = null
    private var completedUtteranceId: String? = null
    private fun startFollowUp() {
        listen()
        followUpWindow = true
        conversationTimeout = scope.launch {
            delay(6000)
            if (followUpWindow && !destroyed) { recognizer.cancel(); followUpWindow = false; close() }
        }
    }
    private fun answer(text: String, expectReply: Boolean = false) {
        status(text); sphere.busy = false; sphere.amplitude = 0f
        followUpId = null
        val speaking = AiPreferences.store(context).getBoolean("tts", true)
        if (speaking) {
            if (ttsReady) {
                val token = speechGeneration.get()
                utterancePrefix = "eva-${System.identityHashCode(this)}-$token-${android.os.SystemClock.elapsedRealtimeNanos()}"
                // Spoken form: brands/units in Russian words, short phrases so the first one starts at once.
                val phrases = EvaSpeak.prepare(text)
                lastUtteranceId = if (phrases.isNotEmpty()) "$utterancePrefix-${phrases.size - 1}" else null
                if (expectReply) followUpId = lastUtteranceId
                EvaTts.listener = progress
                EvaTts.speak(phrases, utterancePrefix) { !destroyed && speechGeneration.get() == token }
                return
            } else pendingReply = text to expectReply
        }
        if (expectReply && !speaking) {
            val token = speechGeneration.get()
            listeningLater = scope.launch { delay(180); if (!destroyed && speechGeneration.get() == token) startFollowUp() }
        }
    }
    private suspend fun choose(apps: List<AppTarget>): AppTarget? = suspendCancellableCoroutine { continuation ->
        pendingChoice = continuation; options = apps
        answer(if (apps.size == 1) "Открыть ${apps.single().label}? Скажите «да» или «нет»" else
            "Какое приложение открыть? " + apps.mapIndexed { i, app -> "${i + 1}. ${app.label}" }.joinToString(". "), expectReply = true)
        continuation.invokeOnCancellation { options = emptyList() }
    }
    private fun clearChoice() { pendingChoice?.cancel(); pendingChoice = null; options = emptyList(); sphere.onListen = ::listen }
    fun pause() { recognizer.cancel(); if (!executing) stopSpeech() }
    fun cancel(showStatus: Boolean = true) { pendingReply = null; job?.cancel(); clearChoice(); recognizer.cancel(); stopSpeech(); sphere.busy = false; sphere.amplitude = 0f; if (showStatus) status("Отменено") }
    fun destroy() { destroyed = true; llm.close(); cancel(false); hideEditor(); recognizer.destroy(); if (EvaTts.listener === progress) EvaTts.listener = null; scope.cancel() }
}
