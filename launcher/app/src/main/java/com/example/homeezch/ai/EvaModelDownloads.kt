package com.example.homeezch.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * R28 (owner 29.10.2026): the extended EVA package downloads in the app process, not in the settings screen,
 * so leaving the screen does not stop it, and its progress is visible both in Eva's settings (next to the model)
 * and on the desktop. One download at a time.
 */
internal object EvaModelDownloads {
    data class State(val modelId: String, val title: String, val percent: Int, val done: Boolean = false,
                     val error: String? = null, val at: Long = System.currentTimeMillis()) {
        val running get() = !done && error == null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    /** Starts (or continues — the file resumes from where it stopped) the download. false = another is running. */
    fun start(context: Context, model: EvaModels.Model): Boolean {
        if (busy) return false
        val app = context.applicationContext
        _state.value = State(model.id, model.title, 0)
        com.example.homeezch.EzchDiag.log("LLM", "Скачиваю «${model.title}»")
        job = scope.launch {
            var lastLogged = -10
            val error = EvaModels.download(app, model) { p ->
                _state.value = State(model.id, model.title, p)
                if (p >= lastLogged + 10) { lastLogged = p - p % 10; com.example.homeezch.EzchDiag.log("LLM", "«${model.title}»: $p %") }
            }
            if (error == null) {
                EvaModels.select(app, model)
                runCatching { EvaBrain.core(app).chat.clear() }
                _state.value = State(model.id, model.title, 100, done = true)
                com.example.homeezch.EzchDiag.log("LLM", "«${model.title}» скачана и выбрана")
            } else {
                _state.value = State(model.id, model.title, _state.value?.percent ?: 0, error = error)
                com.example.homeezch.EzchDiag.log("LLM", "«${model.title}»: $error")
            }
        }
        return true
    }
    fun cancel() { job?.cancel(); _state.value = _state.value?.copy(error = "Загрузка остановлена. Нажмите «Скачать», чтобы продолжить с того же места") }
}
