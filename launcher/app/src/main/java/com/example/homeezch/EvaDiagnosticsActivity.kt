package com.example.homeezch

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** User-triggered capability probe. It does not execute recognized commands. */
class EvaDiagnosticsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var speechPending = false
    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }
    private val speechRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { reply ->
        speechPending = false
        val words = reply.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { it.isNotBlank() }
        result.text = when {
            reply.resultCode != Activity.RESULT_OK -> "Распознавание отменено или недоступно."
            words == null -> "Распознаватель не вернул текст."
            else -> "Получен текст: $words\nКоманда не выполнялась."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechPending = savedInstanceState?.getBoolean("speechPending") ?: false
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        fun label(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 19f
            setTextColor(android.graphics.Color.WHITE)
            panel.addView(this)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value
            setOnClickListener { action() }
            panel.addView(this)
        }
        label("Ai EvA — проверка возможностей ТВ")
        status = label("")
        result = label(savedInstanceState?.getString("result") ?: "Речь ещё не проверена.")
        val first = button("Обновить состояние") { refreshStatus() }
        button("Выбрать Ai EvA системным ассистентом") { requestAssistant() }
        button("Проверить распознавание русского") {
            if (!speechPending) AlertDialog.Builder(this)
                .setTitle("Системный распознаватель")
                .setMessage("Будет открыт установленный распознаватель речи. Он может передавать звук в свой облачный сервис. Для проверки офлайн отключите сеть на ТВ. Продолжить?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продолжить") { _, _ -> recognize() }
                .show()
        }
        button("Настройки ассистента / вернуть прежний") {
            runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { result.text = "Прошивка не предоставляет этот экран настроек." }
        }
        button("Назад") { finish() }
        label("Назначение роли не доказывает работу кнопки 🎤. После выбора выйдите на главный экран и нажмите её на пульте. Панель «системная сессия получена» подтверждает только вызов Ai EvA. Источник звука нужно проверить отдельно.")
        setContentView(ScrollView(this).apply { addView(panel) })
        first.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("speechPending", speechPending)
        outState.putString("result", result.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val active = runCatching {
            VoiceInteractionService.isActiveService(
                this, ComponentName(this, EvaVoiceService::class.java)
            )
        }.getOrDefault(false)
        val role = if (Build.VERSION.SDK_INT >= 29) runCatching {
            val manager = getSystemService(RoleManager::class.java)
            when {
                manager == null || !manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) -> "недоступна"
                manager.isRoleHeld(RoleManager.ROLE_ASSISTANT) -> "назначена приложению"
                else -> "доступна, не назначена"
            }
        }.getOrDefault("не удалось проверить") else "RoleManager отсутствует (Android < 10)"
        val speech = runCatching {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(packageManager) != null
        }.getOrDefault(false)
        status.text = "Android: ${Build.VERSION.RELEASE}\nРоль ассистента: $role\n" +
            "Служба Ai EvA активна: $active\nЭкран распознавания найден: $speech"
    }

    private fun requestAssistant() {
        if (Build.VERSION.SDK_INT < 29) {
            result.text = "На этой версии выберите ассистента вручную в системных настройках."
            return
        }
        runCatching {
            val manager = getSystemService(RoleManager::class.java)
            if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                result.text = "Роль ассистента недоступна на этой прошивке."
            } else if (manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                refreshStatus()
                result.text = "Роль уже назначена. Проверьте отдельно, активна ли служба Ai EvA."
            } else {
                roleRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            }
        }.onFailure { result.text = "Системный выбор ассистента недоступен." }
    }

    private fun recognize() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        speechPending = true
        runCatching { speechRequest.launch(intent) }.onFailure {
            speechPending = false
            result.text = "Не удалось открыть распознаватель речи."
        }
    }
}
