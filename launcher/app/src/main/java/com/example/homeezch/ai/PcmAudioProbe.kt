package com.example.homeezch.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlin.math.abs

/** Explicit diagnostic opt-in. PCM stays in a small temporary buffer, then is zeroed. */
class PcmAudioProbe(private val context: Context) {
    @SuppressLint("MissingPermission") // Runtime permission checked immediately before capture.
    suspend fun capture(): String {
        if (!AiPreferences.enabled(context)) return "помощник выключен"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "нет разрешения микрофона"
        val audio = context.getSystemService(AudioManager::class.java) ?: return "AudioManager недоступен"
        val task = currentCoroutineContext()[Job]
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .setOnAudioFocusChangeListener { if (it < 0) task?.cancel() }.build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return "аудиофокус занят"
        try {
            return withContext(Dispatchers.IO) {
                val buffer = ShortArray(1600)
                var recorder: AudioRecord? = null
                try {
                    val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) return@withContext "PCM 16 кГц недоступен"
                    recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, 6400)).build()
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext "AudioRecord не инициализирован"
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext "запись не началась"
                    var frames = 0; var peak = 0; var route = "не определён"
                    val until = SystemClock.elapsedRealtime() + 3000
                    while (SystemClock.elapsedRealtime() < until) {
                        ensureActive()
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (read < 0) return@withContext "ошибка чтения $read"
                        frames += read
                        for (index in 0 until read) peak = maxOf(peak, abs(buffer[index].toInt()))
                        recorder.routedDevice?.let { route = "тип ${it.type}, id ${it.id}" }
                        delay(20)
                    }
                    "$frames отсчётов, peak=$peak, маршрут $route. Источник пульта требует ручного подтверждения"
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: RuntimeException) { "аудиовход недоступен"
                } finally { buffer.fill(0); runCatching { recorder?.stop() }; recorder?.release() }
            }
        } finally { audio.abandonAudioFocusRequest(focus) }
    }
}
