package com.example.homeezch.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Call on the speech worker: vendor TTS calls may wait for a Binder connection. */
internal object EvaSpeechStyle {
    fun configure(context: Context, engine: TextToSpeech, locale: Locale): Boolean {
        engine.setLanguage(locale)
        val voices = engine.voices.orEmpty().filter {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val requested = AiPreferences.store(context).getString("tts_voice", null)
        // These local voices were exported and verified on Dongle_G_4K. Other engines use a local locale fallback.
        val preferred = listOfNotNull(requested) + if (locale.language == "ru")
            listOf("ru-ru-x-ruc-local", "ru-ru-x-dfc-local", "ru-ru-x-rue-local") else emptyList()
        val candidates = voices.sortedWith(compareBy<android.speech.tts.Voice> {
            preferred.indexOf(it.name).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE
        }.thenByDescending { it.quality })
        val selected = candidates.firstOrNull {
            engine.setVoice(it) == TextToSpeech.SUCCESS && engine.voice?.name == it.name
        }
        // 1.12 made Russian words run together on TV voices; normal tempo is clearer. Owner can tune «tts_rate».
        engine.setSpeechRate(AiPreferences.store(context).getFloat("tts_rate", 1.0f).coerceIn(0.7f, 1.4f))
        engine.setPitch(1f)
        // No installed local voice for the locale: still speak if the engine itself supports the language
        // (some TV engines expose no Voice list), instead of staying silent forever.
        val languageOk = selected != null || engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        AiPreferences.store(context).edit().putString("tts_voice_active", selected?.name ?: if (languageOk) "engine-default" else null)
            .putString("tts_voices", voices.map { it.name }.distinct().sorted().joinToString(","))
            .putBoolean("tts_language_missing", !languageOk).apply()
        android.util.Log.i("EvaTts", "voice=${selected?.name ?: "engine-default"} ready=$languageOk")
        return languageOk
    }
}

/** The TV's installed local voices for EVA's language, chosen by the owner in EVA settings. */
internal object EvaVoices {
    private fun all(context: Context) = AiPreferences.store(context).getString("tts_voices", "").orEmpty()
        .split(',').filter { it.isNotBlank() }
    private fun current(context: Context) = AiPreferences.store(context).let { it.getString("tts_voice", null) ?: it.getString("tts_voice_active", null) }
    fun label(context: Context): String {
        val voices = all(context); val now = current(context)
        val index = voices.indexOf(now)
        return when {
            voices.isEmpty() -> "стандартный (список голосов появится после первой фразы)"
            index < 0 -> "стандартный"
            else -> "№${index + 1} из ${voices.size}"
        }
    }
    /** Switches to the next installed voice and returns its name (null when the TV reports none). */
    fun next(context: Context): String? {
        val voices = all(context); if (voices.isEmpty()) return null
        val choice = voices[(voices.indexOf(current(context)) + 1).mod(voices.size)]
        AiPreferences.store(context).edit().putString("tts_voice", choice).apply()
        return choice
    }
}
