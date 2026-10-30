package com.example.homeezch.ai

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.speech.SpeechRecognizer

object AiPreferences {
    fun store(context: Context) = context.getSharedPreferences("ezch_ai_v1", Context.MODE_PRIVATE)
    fun enabled(context: Context) = store(context).getBoolean("enabled", false)
    // Owner's choice (24.10.2026): Google recognition when online, offline Vosk as the fallback.
    fun networkSpeech(context: Context) = store(context).getBoolean("network_speech", true)
}
class AndroidCapabilityProbe(private val context: Context) : CapabilityProbe {
    fun roleAvailable(): Boolean = Build.VERSION.SDK_INT >= 29 && runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)
    fun roleHeld(): Boolean = roleAvailable() && runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
    }.getOrDefault(false)
    fun requestRole(activity: Activity): Boolean = if (Build.VERSION.SDK_INT < 29 || !roleAvailable()) false else runCatching {
        activity.startActivityForResult(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 71)
        true
    }.getOrDefault(false)
    fun openAssistantSettings(activity: Activity): Boolean {
        // Some TV firmwares lack voice settings; their general default-app list
        // still links to that missing screen and crashes on the assistant row.
        // Only open the public voice settings, without granting extra privileges.
        return try {
            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
    override fun report(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val inputs = runCatching { audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.joinToString { "тип ${it.type}" } }.getOrNull().orEmpty()
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val local = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val prefs = AiPreferences.store(context)
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "Роль: ${if (!roleAvailable()) "недоступна — запуск из EZCH" else if (roleHeld()) "назначена" else "не назначена"}\n" +
            "Разрешение микрофона: ${if (mic) "есть" else "нет"}\nЛокальный движок: ${if (local) "доступен; RU требует теста" else "не обнаружен"}\n" +
            "Аудиовходы: ${inputs.ifEmpty { "не обнаружены" }} (это не проверка микрофона пульта)\n" +
            "Вызов системной сессии: ${if (prefs.contains("session_seen")) "наблюдался" else "не наблюдался"}; штатная кнопка: не проверена\n" +
            "Русская речь: ${if (prefs.getBoolean("ru_result", false)) "получен текст; источник аудио не подтверждён" else "не проверена"}\n" +
            "Проверка PCM: ${prefs.getString("pcm_probe", "не проведена")}\n" +
            "STT: ${prefs.getString("speech_mode", "не запускался")} / ${prefs.getString("speech_state", "не запускался")}; код ошибки: ${if (prefs.contains("last_speech_error")) prefs.getInt("last_speech_error", 0).toString() else "нет"}\n" +
            "Управление приложениями: ${if (EvaAppService.current != null) "подключено" else "включите отдельную службу Ai EvA в специальных возможностях"}\n" +
            "Понимание команд: EVA Core офлайн. Фоновое прослушивание: отключено."
    }
}
