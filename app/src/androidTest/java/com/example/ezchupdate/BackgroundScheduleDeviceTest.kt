package com.example.ezchupdate

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackgroundScheduleDeviceTest {
    @Test fun onePeriodicCheckPersistsAndCanBeDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = UpdateSettings(context)
        val previous = settings.autoCheck
        val manager = WorkManager.getInstance(context)
        try {
            settings.autoCheck = true
            repeat(4) { settings.schedule() }
            val running = manager.getWorkInfosForUniqueWork("ezch-catalog-check").get(10, TimeUnit.SECONDS)
                .filterNot { it.state.isFinished }
            assertEquals("Reopening must not schedule duplicate periodic checks", 1, running.size)
            settings.autoCheck = false
            manager.cancelUniqueWork("ezch-catalog-check").result.get(10, TimeUnit.SECONDS)
            assertTrue(manager.getWorkInfosForUniqueWork("ezch-catalog-check").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        } finally { settings.autoCheck = previous }
    }
}
