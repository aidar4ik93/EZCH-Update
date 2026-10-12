package com.example.homeezch

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homeezch.install.InstallEvents
import com.example.homeezch.install.InstallSnapshot
import org.junit.Assert.*
import org.junit.Test

class InstallConfirmationPersistenceTest {
    @Test fun consumedConfirmationStaysConsumedAfterRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            try {
                InstallEvents.record(context, InstallSnapshot("fixture", 987,
                    PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("test.confirm")))
                InstallEvents.consumeConfirmation(context, 986)
                assertNotNull(InstallEvents.snapshot.value?.confirmationIntent)
                InstallEvents.consumeConfirmation(context, 987)
                InstallEvents.snapshot.value = null
                InstallEvents.restore(context)
                assertEquals(987, InstallEvents.snapshot.value?.sessionId)
                assertNull(InstallEvents.snapshot.value?.confirmationIntent)
            } finally {
                InstallEvents.consume(context, 987)
            }
        }
    }
}
