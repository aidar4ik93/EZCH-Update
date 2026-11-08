package com.example.homeezch.ai

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Claude r56 (owner, 04.11): «на Google TV кнопку микрофона нужно удерживать, пока говоришь; на Android TV — нажать
 * один раз». Google TV remotes send voice only while the button is held (push-to-talk); classic Android TV remotes
 * and TV-built-in microphones keep listening after one press. Eva adapts her prompts and retries to the box she runs on.
 */
internal object VoiceRemote {
    @Volatile private var googleTv: Boolean? = null
    /** Google TV = the Google TV home (launcherx) is installed. Android TV boxes and TVs without it use one press. */
    fun holdToTalk(context: Context): Boolean = googleTv ?: runCatching {
        context.packageManager.getPackageInfo("com.google.android.apps.tv.launcherx", 0); true
    }.getOrDefault(false).also { googleTv = it }

    private val AMBIENT = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    fun inputTypes(context: Context): List<Int> = runCatching {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map { it.type }.orEmpty()
    }.getOrDefault(emptyList())
    /** «Ева» without the remote needs a microphone that is open all the time (TV, USB/webcam). A box whose only
     *  microphone is in the remote hears nothing between presses, so hands-free there only costs CPU and memory. */
    fun hasAmbientMic(context: Context): Boolean = ambient(inputTypes(context), holdToTalk(context))
    /** Claude r56: on Google TV the voice remote itself is published as TYPE_BUILTIN_MIC (Homatics: inputs 15/25/17/28,
     *  no microphone in the box), and it streams only while the button is held. There BUILTIN_MIC is not ambient —
     *  otherwise the wake model ran all day on silence, costing CPU/RAM and a microphone hand-over on every press. */
    fun ambient(types: List<Int>, holdToTalk: Boolean): Boolean =
        types.any { it in AMBIENT && !(holdToTalk && it == AudioDeviceInfo.TYPE_BUILTIN_MIC) }

    fun listening(context: Context) = if (holdToTalk(context)) "Говорите, удерживая кнопку микрофона…" else "Говорите…"
    fun again(context: Context) = if (holdToTalk(context)) "Не расслышала. Удерживайте кнопку микрофона и повторите"
        else "Не расслышала. Нажмите кнопку микрофона и скажите ещё раз"
}
