package com.example.homeezch.ai

import android.Manifest
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The production route, including Russian offline fallback when the vendor service refuses a session. */
class R22HybridReadyTest {
    @Test fun fiftyMicrophoneRequestsReachReadyOrReportBoundedFailure() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        check(ctx.packageName.endsWith(".debug"))
        ins.uiAutomation.grantRuntimePermission(ctx.packageName,Manifest.permission.RECORD_AUDIO)
        val prefs=AiPreferences.store(ctx);val enabled=AiPreferences.enabled(ctx);val network=AiPreferences.networkSpeech(ctx)
        prefs.edit().putBoolean("enabled",true).putBoolean("network_speech",true).commit()
        var input:HybridVoiceRecognizer?=null;var latch=CountDownLatch(1);var start=0L;var recorded=false
        val rows=JSONArray();val errors=JSONArray()
        ins.runOnMainSync {
            input=HybridVoiceRecognizer(ctx,{ message ->
                if (!recorded && (message=="Говорите…" || message.startsWith("Я слушаю"))) {
                    recorded=true;rows.put(SystemClock.elapsedRealtime()-start);latch.countDown()
                } else if (input?.isListening==false && !message.endsWith("…")) { errors.put(message);latch.countDown() }
            },{})
        }
        try {
            repeat(50) {
                latch=CountDownLatch(1);recorded=false
                ins.runOnMainSync { start=SystemClock.elapsedRealtime();input!!.start() }
                assertTrue("Readiness or error deadline",latch.await(12,TimeUnit.SECONDS))
                ins.runOnMainSync { input!!.cancel();assertFalse(input!!.isListening) }
                SystemClock.sleep(200)
            }
        } finally {
            ins.runOnMainSync { input?.destroy() }
            prefs.edit().putBoolean("enabled",enabled).putBoolean("network_speech",network).commit()
            val report=JSONObject().put("ready_ms",rows).put("errors",errors)
            ctx.getExternalFilesDir(null)?.let { File(it,"r22-hybrid.json").writeText(report.toString(2)) }
            println("R22_HYBRID=$report")
        }
        assertEquals("All requests should become ready through production fallback",50,rows.length())
    }
}
