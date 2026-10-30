package com.example.homeezch.ai

import android.os.SystemClock
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

class R22TtsTest {
    @Test fun fiftyStartsThenAppendAndInterrupt() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName.endsWith(".debug"))
        val warm=CountDownLatch(1); var ok=false
        EvaTts.warm(ctx) { ok=it; warm.countDown() }
        assertTrue("TTS init deadline",warm.await(8,TimeUnit.SECONDS)); assertTrue("Russian voice available",ok)
        val samples=JSONArray(); val startedIds=Collections.synchronizedList(mutableListOf<String>())
        var start=0L; var heard=CountDownLatch(1); var done=CountDownLatch(2)
        EvaTts.listener=object:UtteranceProgressListener() {
            override fun onStart(id:String?) {
                if (id==null) return
                startedIds.add(id)
                if (id.startsWith("r22-start")) { samples.put(SystemClock.elapsedRealtime()-start); heard.countDown() }
            }
            override fun onDone(id:String?) { if (id?.startsWith("r22-queue") == true) done.countDown() }
            @Deprecated("Platform callback") override fun onError(id:String?) { heard.countDown() }
        }
        try {
            repeat(50) { i ->
                heard=CountDownLatch(1); start=SystemClock.elapsedRealtime()
                EvaTts.speak(listOf("Да."),"r22-start-$i") { true }
                assertTrue("No TTS start",heard.await(3,TimeUnit.SECONDS))
                val stopped=CountDownLatch(1);EvaTts.stop { stopped.countDown() }
                assertTrue("Stop deadline",stopped.await(2,TimeUnit.SECONDS))
            }
            EvaTts.speak(listOf("Первое предложение."),"r22-queue-first") { true }
            EvaTts.speak(listOf("Второе предложение."),"r22-queue-next",append=true) { true }
            assertTrue("Append must finish both sentences",done.await(12,TimeUnit.SECONDS))
            assertTrue(startedIds.indexOf("r22-queue-first-0") < startedIds.indexOf("r22-queue-next-0"))
            val stopped=CountDownLatch(1);EvaTts.stop { stopped.countDown() }
            assertTrue(stopped.await(2,TimeUnit.SECONDS))
            assertEquals(50,samples.length())
        } finally {
            EvaTts.stop(); EvaTts.listener=null
            val report=JSONObject().put("start_ms",samples).put("started_ids",JSONArray(startedIds))
            ctx.getExternalFilesDir(null)?.let { File(it,"r22-tts.json").writeText(report.toString(2)) }
            println("R22_TTS=$report")
        }
    }
}
