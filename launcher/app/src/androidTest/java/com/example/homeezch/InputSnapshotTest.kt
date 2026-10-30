package com.example.homeezch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class InputSnapshotTest {
    @Test fun inputSnapshotMatchesActualFirmwareAndNeverTreatsAppTunerAsHardware() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val actual = runCatching { manager?.tvInputList.orEmpty() }.getOrDefault(emptyList())
        val snapshot = scanTvSources(context)
        for (source in snapshot.entries.filterNot { it.kind == TvSourceKind.USB || it.id.startsWith("manual-") }) {
            val input = actual.single { it.id == source.id }
            assertNull(input.parentId)
            if (source.kind == TvSourceKind.HDMI) assertEquals(TvInputInfo.TYPE_HDMI, input.type)
            if (input.type == TvInputInfo.TYPE_TUNER && input.serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                assertFalse(source.connected)
                assertFalse(source.tunerAutoStartEligible)
                assertEquals("ТВ-приложение · не физический тюнер", source.detail)
            }
        }
        if (actual.none { it.type == TvInputInfo.TYPE_HDMI }) {
            assertTrue(snapshot.entries.none { it.kind == TvSourceKind.HDMI && !it.id.startsWith("manual-") })
        }
    }
}
