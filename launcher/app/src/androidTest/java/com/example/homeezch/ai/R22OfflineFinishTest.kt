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

class R22OfflineFinishTest {
    @Test fun offlineSecondPressFinishesAndNextPhraseStartsWithoutBack() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        check(ctx.packageName.endsWith(".debug"))
        ins.uiAutomation.grantRuntimePermission(ctx.packageName,Manifest.permission.RECORD_AUDIO)
        val prefs=AiPreferences.store(ctx);val enabled=AiPreferences.enabled(ctx)
        prefs.edit().putBoolean("enabled",true).commit()
        var input:OfflineVoskRecognizer?=null
        var ready=CountDownLatch(1);var terminal=CountDownLatch(1)
        val rows=JSONArray();var start=0L;var finish=0L;var readyAt=0L
        ins.runOnMainSync {
            input=OfflineVoskRecognizer(ctx,{ message ->
                if (message.startsWith("Я слушаю")) { readyAt=SystemClock.elapsedRealtime();ready.countDown() }
                if (input?.isListening==false) terminal.countDown()
            },{ terminal.countDown() })
        }
        try {
            repeat(5) { i ->
                ready=CountDownLatch(1);terminal=CountDownLatch(1)
                ins.runOnMainSync { start=SystemClock.elapsedRealtime();input!!.start() }
                assertTrue("Offline microphone readiness",ready.await(20,TimeUnit.SECONDS))
                ins.runOnMainSync { finish=SystemClock.elapsedRealtime();input!!.finish() }
                assertTrue("finish IPC must end the phrase without BACK",terminal.await(4,TimeUnit.SECONDS))
                rows.put(JSONObject().put("i",i).put("ready_ms",readyAt-start).put("finish_ms",SystemClock.elapsedRealtime()-finish))
                ins.runOnMainSync { assertFalse(input!!.isListening) }
                SystemClock.sleep(200)
            }
        } finally {
            ins.runOnMainSync { input?.destroy() }
            prefs.edit().putBoolean("enabled",enabled).commit()
            ctx.getExternalFilesDir(null)?.let { File(it,"r22-offline-finish.json").writeText(rows.toString(2)) }
        }
    }
}
