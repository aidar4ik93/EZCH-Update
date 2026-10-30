package com.example.homeezch.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.viewinterop.AndroidView
import com.example.homeezch.DesktopProfile
import com.example.homeezch.Profiles
import com.example.homeezch.PinPrompt
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class VoiceActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        setTheme(android.R.style.Theme_NoDisplay)
        super.onCreate(state)
        com.example.homeezch.EzchDiag.init(this, "кнопка ассистента (VoiceActivity), служба Евы ${if (EvaAppService.current != null) "есть" else "нет"}")
        EvaEntry.open(this)
        finish()
    }
}

class AssistantSettingsActivity : ComponentActivity() {
    private lateinit var report: TextView
    private var probeJob: kotlinx.coroutines.Job? = null
    private var unlocked by mutableStateOf(false)
    private var guard by mutableStateOf<DesktopProfile?>(null)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        guard = Profiles.guard(this)
        unlocked = guard == null || AiSettingsTicket.consume(this, intent.getStringExtra("authorization"))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val profile = guard
                if (unlocked || profile == null) AndroidView(factory = { buildContent() })
                else PinPrompt(profile, ::finish) { unlocked = true }
            }
        }
    }
    private fun buildContent(): android.view.View {
        val store = AiPreferences.store(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(26, 16, 26, 16) }
        report = TextView(this).apply { textSize = 15f; setTextColor(android.graphics.Color.WHITE) }
        column.addView(TextView(this).apply { text = "Голосовой помощник EVA"; textSize = 22f; setTextColor(android.graphics.Color.CYAN) })
        column.addView(Switch(this).apply {
            text = "Включить помощник и кнопку в HOME"; isChecked = AiPreferences.enabled(this@AssistantSettingsActivity)
            setOnCheckedChangeListener { _, checked -> if (!checked) probeJob?.cancel(); store.edit().putBoolean("enabled", checked).apply(); refresh() }
        })
        column.addView(Switch(this).apply {
            text = "Отзываться на «Ева» без пульта, пока открыт рабочий стол\nИспользует микрофон только на рабочем столе."
            isChecked = EvaEngine.handsFreeEnabled(this@AssistantSettingsActivity)
            setOnCheckedChangeListener { _, checked -> EvaEngine.setHandsFree(this@AssistantSettingsActivity, checked) }
        })
        column.addView(Switch(this).apply {
            text = "Разрешить системный сетевой ввод речи\nВыбранный движок может передавать звук на свои серверы"; isChecked = AiPreferences.networkSpeech(this@AssistantSettingsActivity)
            setOnCheckedChangeListener { _, checked -> store.edit().putBoolean("network_speech", checked).apply() }
        })
        fun button(title: String, action: () -> Unit) { column.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } }) }
        column.addView(TextView(this).apply { text = "Кнопка микрофона на пульте открывает системного помощника. Чтобы она открывала Еву, выберите «EVA» основным помощником:"; setTextColor(android.graphics.Color.LTGRAY) })
        button("Кнопка микрофона → Ai EvA (выбрать помощника)") {
            if (!AndroidCapabilityProbe(this).openAssistantSettings(this)) Toast.makeText(this, "В прошивке нет экрана выбора помощника. Назначьте кнопку в «Кнопки пульта» или запускайте Еву с рабочего стола", Toast.LENGTH_LONG).show()
        }
        column.addView(Switch(this).apply {
            text = "Кнопка микрофона открывает Еву вместо Google Ассистента\nGoogle TV не даёт сменить помощника: Ева закрывает окно Ассистента и слушает сама. Нужна служба «EZCH» или «Ai EvA» в Спец. возможностях"
            isChecked = AssistantTakeover.enabled(this@AssistantSettingsActivity) || !AiPreferences.enabled(this@AssistantSettingsActivity) && store.getBoolean("mic_takeover", true)
            setOnCheckedChangeListener { _, checked -> AssistantTakeover.setEnabled(this@AssistantSettingsActivity, checked) }
        })
        button("Разрешить микрофон") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 72) }
        button("Управление приложениями · разрешить в Android") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        column.addView(Switch(this).apply {
            text = "Озвучивать ответы Евы"; isChecked = store.getBoolean("tts", true)
            setOnCheckedChangeListener { _, checked -> store.edit().putBoolean("tts", checked).apply() }
        })
        column.addView(Switch(this).apply {
            text = "Продолжать разговор без кнопки"
            isChecked = store.getBoolean("conversation_follow_up", true)
            setOnCheckedChangeListener { _, checked -> store.edit().putBoolean("conversation_follow_up", checked).apply() }
        })
        // Voice tempo: applied the next time the EVA panel starts its speech engine.
        fun rateLabel() = when (store.getFloat("tts_rate", 1.0f)) { in 0f..0.9f -> "медленная"; in 1.1f..2f -> "быстрая"; else -> "обычная" }
        val rateButton = Button(this).apply { isAllCaps = false }
        fun showRate() { rateButton.text = "Скорость речи Евы: ${rateLabel()} (нажмите, чтобы сменить)" }
        rateButton.setOnClickListener {
            val next = when (store.getFloat("tts_rate", 1.0f)) { in 0f..0.9f -> 1.0f; in 1.1f..2f -> 0.85f; else -> 1.15f }
            store.edit().putFloat("tts_rate", next).apply(); showRate(); EvaTts.refresh(this)
        }
        showRate(); column.addView(rateButton)
        // Voice: the TV's local Russian voices differ in stress and clarity; the owner picks the one he likes.
        val voiceButton = Button(this).apply { isAllCaps = false }
        fun showVoice() { voiceButton.text = "Голос Евы: ${EvaVoices.label(this@AssistantSettingsActivity)} (нажмите, чтобы сменить и послушать)" }
        voiceButton.setOnClickListener {
            EvaVoices.next(this); showVoice()
            EvaTts.refresh(this) { EvaTts.speak(EvaSpeak.prepare("Привет, я Ева. Так звучит мой голос. Включить ютуб или сделать громкость двадцать?")) { true } }
        }
        showVoice(); column.addView(voiceButton)
        // Personal memory (ТЗ §17): view and erase without voice.
        button("Что Ева помнит обо мне") { report.text = EvaBrain.core(this).memory.describe() }
        button("Стереть личную память Евы") { EvaBrain.core(this).memory.forgetAll(); EvaBrain.core(this).resetDialog(); report.text = "Личная память Евы стёрта" }
        button("Устройство и оптимизация") { startActivity(Intent(this, com.example.homeezch.DeviceOptimizationActivity::class.java)) }
        button("Сохранить диагностику (txt)") {
            report.text = "Сохраняю диагностику…"
            Thread {
                val place = com.example.homeezch.EzchDiag.export(this, "кнопка в настройках Евы")
                runOnUiThread { report.text = "Диагностика сохранена: $place\nС телефона: страница пульта → «Диагностика ТВ»" }
            }.start()
        }
        // EVA's own brain on the TV: a small model, no key, no account, works without internet once downloaded.
        column.addView(TextView(this).apply { text = "Мозг Евы на телевизоре · без ключа"; textSize = 19f; setTextColor(android.graphics.Color.CYAN) })
        column.addView(TextView(this).apply {
            text = "Команды телевизора Ева понимает сама. Чтобы она ещё и разговаривала (анекдоты, советы, вопросы), скачайте нейросеть один раз — " +
                "дальше она работает прямо на телевизоре, без интернета и без ключей. Ответ занимает несколько секунд, на слабых ТВ дольше."
            setTextColor(android.graphics.Color.LTGRAY)
        })
        column.addView(Switch(this).apply {
            text = "Разговаривать с помощью нейросети на телевизоре"; isChecked = store.getBoolean("llm_enabled", true)
            setOnCheckedChangeListener { _, checked -> store.edit().putBoolean("llm_enabled", checked).apply() }
        })
        val modelState = TextView(this).apply { textSize = 15f; setTextColor(android.graphics.Color.WHITE) }
        fun showModels() {
            val active = EvaModels.active(this)
            modelState.text = (if (active != null) "Сейчас: ${active.title}" else "Нейросеть не скачана") +
                "\nСвободно на телевизоре: ${EvaModels.freeBytes(this) / 1_000_000} МБ"
        }
        column.addView(modelState)
        // R28: the download lives in EvaModelDownloads (app process), with progress right under each model.
        val rows = mutableListOf<Triple<EvaModels.Model, TextView, ProgressBar>>()
        for (model in EvaModels.CATALOG) {
            column.addView(TextView(this).apply { text = "${model.title} · ${model.bytes / 1_000_000} МБ\n${model.note}"; setTextColor(android.graphics.Color.LTGRAY) })
            button("Скачать / выбрать «${model.title}»") {
                if (EvaModels.installed(this, model)) { EvaModels.select(this, model); showModels(); report.text = "Ева разговаривает с помощью «${model.title}»"; return@button }
                if (!EvaModelDownloads.start(this, model)) Toast.makeText(this, "Уже идёт загрузка — дождитесь её окончания", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "Скачиваю «${model.title}». Можно закрыть настройки — загрузка продолжится, ход виден на рабочем столе", Toast.LENGTH_LONG).show()
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
            val line = TextView(this).apply { textSize = 16f; setTextColor(android.graphics.Color.rgb(120, 200, 255)); visibility = android.view.View.GONE }
            column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24))
            column.addView(line)
            rows += Triple(model, line, bar)
            button("Удалить «${model.title}»") {
                if (EvaModelDownloads.busy && EvaModelDownloads.state.value?.modelId == model.id) EvaModelDownloads.cancel()
                EvaModels.delete(this, model); showModels(); report.text = "Модель удалена"
            }
        }
        lifecycleScope.launch {
            EvaModelDownloads.state.collect { st ->
                for ((model, line, bar) in rows) {
                    val mine = st != null && st.modelId == model.id
                    bar.visibility = if (mine && st!!.running) android.view.View.VISIBLE else android.view.View.GONE
                    line.visibility = if (mine) android.view.View.VISIBLE else android.view.View.GONE
                    if (!mine) continue
                    bar.progress = st!!.percent
                    val mb = model.bytes / 1_000_000
                    line.text = when {
                        st.done -> "✓ Готово: «${model.title}» скачана и выбрана. Спросите Еву, например: «расскажи анекдот»"
                        st.error != null -> "⚠ ${st.error}"
                        else -> "Скачиваю: ${st.percent} %  ·  ${mb * st.percent / 100} из $mb МБ"
                    }
                }
                if (st?.done == true || st?.error != null) showModels()
            }
        }
        showModels()
        // GigaChat: free conversation like Салют ТВ. Commands stay offline in EVA Core; only free questions go out.
        column.addView(TextView(this).apply { text = "Дополнительно · GigaChat (Сбер) по ключу владельца"; textSize = 17f; setTextColor(android.graphics.Color.CYAN) })
        column.addView(TextView(this).apply {
            text = "Команды телевизора Ева выполняет сама, без интернета. Свободные вопросы и беседу («расскажи анекдот», «что посмотреть вечером») " +
                "она передаёт GigaChat — нейросети Сбера, как в Салют ТВ. Нужен «Ключ авторизации»: developers.sber.ru → GigaChat API → " +
                "проект для физлица → «Получить ключ». Бесплатно: годовой лимит токенов. Отправляется только текст вопроса и короткий диалог."
            setTextColor(android.graphics.Color.LTGRAY)
        })
        val gigaKey = EditText(this).apply {
            hint = if (GigaChatSettings.hasKey(this@AssistantSettingsActivity)) "Ключ сохранён · введите новый, чтобы заменить" else "Ключ авторизации GigaChat"
            setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(gigaKey)
        var gigaModel = GigaChatSettings.model(this)
        val gigaModelButton = Button(this).apply { isAllCaps = false }
        fun showModel() { gigaModelButton.text = "Модель: $gigaModel (${mapOf("GigaChat-2-Pro" to "умнее, рекомендую", "GigaChat-2" to "быстрее", "GigaChat-2-Max" to "самая умная, медленнее")[gigaModel]}) — нажмите, чтобы сменить" }
        gigaModelButton.setOnClickListener { gigaModel = GigaChatSettings.MODELS[(GigaChatSettings.MODELS.indexOf(gigaModel) + 1) % GigaChatSettings.MODELS.size]; showModel() }
        showModel(); column.addView(gigaModelButton)
        val gigaConsent = Switch(this).apply {
            text = "Разрешить отправлять текст свободных вопросов в GigaChat\nЗвук, экран и личная память Евы не отправляются"
            isChecked = GigaChatSettings.consent(this@AssistantSettingsActivity)
        }
        column.addView(gigaConsent)
        button("Сохранить и проверить GigaChat") {
            if (!GigaChatSettings.save(this, gigaKey.text.toString(), gigaModel, gigaConsent.isChecked)) {
                report.text = "Не удалось зашифровать ключ в хранилище Android"; return@button
            }
            gigaKey.setText(""); gigaKey.hint = if (GigaChatSettings.hasKey(this)) "Ключ сохранён · введите новый, чтобы заменить" else "Ключ авторизации GigaChat"
            if (!GigaChatSettings.hasKey(this)) { report.text = "Введите ключ авторизации GigaChat"; return@button }
            probeJob?.cancel()
            probeJob = lifecycleScope.launch {
                report.text = "Проверяю GigaChat…"
                report.text = GigaChatClient(this@AssistantSettingsActivity).check() +
                    if (!gigaConsent.isChecked) "\nВключите разрешение выше, чтобы Ева отвечала на вопросы." else ""
            }
        }
        button("Удалить ключ GigaChat") {
            GigaChatSettings.forget(this); gigaConsent.isChecked = false; gigaKey.hint = "Ключ авторизации GigaChat"
            EvaBrain.core(this).chat.clear(); report.text = "Ключ GigaChat удалён. Ева отвечает только офлайн"
        }
        column.addView(TextView(this).apply { text = "Умный дом · Home Assistant"; textSize = 19f; setTextColor(android.graphics.Color.CYAN) })
        button("Открыть «Умный дом» и подключить Home Assistant") { startActivity(Intent(this, SmartHomeActivity::class.java)) }
        column.addView(Switch(this).apply {
            text = "English recognition (выключено — русский)"; isChecked = store.getString("language", "ru-RU") == "en-US"
            setOnCheckedChangeListener { _, checked -> store.edit().putString("language", if (checked) "en-US" else "ru-RU").apply() }
        })
        column.addView(TextView(this).apply { text = "Запуск: кнопка Евы в лаунчере или поддерживаемая кнопка микрофона. Значок не забирает фокус пульта. Нажатие значка — говорить, долгое нажатие — закрыть; автоматическое закрытие после 30 секунд бездействия. Для офлайн-речи нужна локальная модель."; setTextColor(android.graphics.Color.LTGRAY) })
        button("Проверить аудиовход · 3 секунды без сохранения звука") {
            probeJob?.cancel()
            probeJob = lifecycleScope.launch {
                report.text = "Говорите в микрофон пульта. Проверяю поток звука…"
                val sample = PcmAudioProbe(this@AssistantSettingsActivity).capture()
                store.edit().putString("pcm_probe", sample).apply(); refresh()
            }
        }
        button("Назначить EVA основным помощником") {
            if (!AndroidCapabilityProbe(this).openAssistantSettings(this)) Toast.makeText(this, "В прошивке нет экрана выбора голосового помощника. Запускайте EVA кнопкой на рабочем столе", Toast.LENGTH_LONG).show()
        }
        button("Доступ к медиасессиям · по желанию") {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure {
                Toast.makeText(this, "Системный экран недоступен", Toast.LENGTH_LONG).show()
            }
        }
        column.addView(TextView(this).apply { text = "Android выдаёт доступ к уведомлениям. EVA использует его только для управления плеером, содержимое уведомлений не читает."; setTextColor(android.graphics.Color.LTGRAY) })
        button("Проверить голос / запуск из EZCH") {
            val control = EvaAppService.current
            if (control != null) control.showMiniPanel()
            else Toast.makeText(this, "Включите службу Ai EvA в специальных возможностях", Toast.LENGTH_LONG).show()
        }
        button("Обновить диагностику") { refresh() }
        button("Очистить только диагностику EVA") { store.edit().remove("session_seen").remove("ru_result").remove("pcm_probe").apply(); refresh() }
        column.addView(report)
        button("Назад") { finish() }
        refresh()
        return ScrollView(this).apply { setBackgroundColor(android.graphics.Color.rgb(5, 10, 20)); addView(column) }
    }
    private fun refresh() { if (::report.isInitialized) report.text = AndroidCapabilityProbe(this).report() }
    override fun onResume() { super.onResume(); guard = Profiles.guard(this); refresh() }
    override fun onStop() { if (guard != null) unlocked = false; super.onStop() }
    override fun onPause() { probeJob?.cancel(); super.onPause() }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); refresh()
    }
}
