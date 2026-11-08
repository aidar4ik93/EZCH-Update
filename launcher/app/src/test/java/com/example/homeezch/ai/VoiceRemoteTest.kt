package com.example.homeezch.ai

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Claude r56: the Google TV voice remote is published as BUILTIN_MIC and is not an always-open microphone. */
class VoiceRemoteTest {
    private val homatics = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, 17, 28)
    @Test fun googleTvRemoteMicIsNotAmbient() = assertFalse(VoiceRemote.ambient(homatics, holdToTalk = true))
    @Test fun androidTvBuiltinMicIsAmbient() = assertTrue(VoiceRemote.ambient(homatics, holdToTalk = false))
    @Test fun usbMicIsAmbientEverywhere() = assertTrue(VoiceRemote.ambient(homatics + AudioDeviceInfo.TYPE_USB_DEVICE, holdToTalk = true))
    @Test fun noInputs() = assertFalse(VoiceRemote.ambient(emptyList(), holdToTalk = false))
}
