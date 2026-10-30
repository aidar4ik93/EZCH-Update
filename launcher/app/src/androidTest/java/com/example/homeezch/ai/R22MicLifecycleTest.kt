package com.example.homeezch.ai

import android.Manifest
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Live silent microphone lifecycle; does not claim spoken command accuracy. Emulator/debug only. */
class R22MicLifecycleTest {
    @Test fun warmProviderReadyAndHundredRapidStopCycles() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val ctx=ins.targetContext
        check(ctx.packageName.endsWith(".debug"))
        ins.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        val prefs=AiPreferences.store(ctx)
        val enabled=AiPreferences.enabled(ctx); val network=AiPreferences.networkSpeech(ctx)
        prefs.edit().putBoolean("enabled",true).putBoolean("network_speech",true).commit()
        val ready=JSONArray(); val statuses=JSONArray()
        var latch=CountDownLatch(1); var started=0L; var input:AndroidVoiceRecognizer?=null
        try {
            ins.runOnMainSync {
                input=AndroidVoiceRecognizer(ctx,{ text ->
                    if (text=="Говорите…") { ready.put(SystemClock.elapsedRealtime()-started); latch.countDown() }
                    else if (!text.endsWith("…")) { statuses.put(text); latch.countDown() }
                },{},forceNetwork=true)
            }
            for (i in 0 until 50) {
                latch=CountDownLatch(1)
                ins.runOnMainSync { started=SystemClock.elapsedRealtime(); input!!.start() }
                assertTrue("No terminal status or readiness in 8 seconds",latch.await(8,TimeUnit.SECONDS))
                ins.runOnMainSync { input!!.cancel() }
                if (i==0 && ready.length()==0) break
                SystemClock.sleep(180)
            }
            repeat(100) {
                ins.runOnMainSync { input!!.start(); input!!.finish(); input!!.finish(); input!!.cancel() }
                SystemClock.sleep(50)
                ins.runOnMainSync { assertFalse("Listening stuck after cancel",input!!.isListening) }
            }
        } finally {
            ins.runOnMainSync { input?.destroy() }
            prefs.edit().putBoolean("enabled",enabled).putBoolean("network_speech",network).commit()
            val result=JSONObject().put("rapid_cycles",100).put("ready_ms",ready).put("terminal_status",statuses)
            ctx.getExternalFilesDir(null)?.let { File(it,"r22-mic.json").writeText(result.toString(2)) }
            println("R22_MIC=$result")
        }
        assumeTrue("System provider unavailable; readiness not covered",ready.length()>0)
        assertEquals("Every normal start must become ready",50,ready.length())
    }
}
