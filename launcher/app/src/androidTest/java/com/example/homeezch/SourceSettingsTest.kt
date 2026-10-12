package com.example.homeezch
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
class SourceSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
    private lateinit var before: Map<String, *>
    @Before fun isolate() { before = prefs.all; prefs.edit().clear().commit() }
    @After fun restore() {
        val edit = prefs.edit().clear()
        before.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Int -> edit.putInt(k,v); is Boolean -> edit.putBoolean(k,v) } }
        edit.commit()
    }
    @Test fun noManualInputsWithoutExplicitCreationAndDamagedPreferencesRecover() {
        assertTrue(SourceSettings.manual(context).isEmpty())
        prefs.edit().putString("manual", "broken").commit()
        SourceSettings.add(context,TvSourceKind.HDMI)
        val manual = SourceSettings.manual(context).single()
        assertFalse(manual.connected); assertTrue(manual.label.contains("вручную"))
        SourceSettings.clear(context); assertTrue(SourceSettings.manual(context).isEmpty())
    }
    @Test fun tunerStartsOnlyWhenEnabledAndOnlyOncePerBoot() {
        assertFalse(SourceSettings.consumeTunerStart(context))
        SourceSettings.setTunerAutoStart(context,true)
        assertTrue(SourceSettings.consumeTunerStart(context))
        assertFalse(SourceSettings.consumeTunerStart(context))
        assertTrue(SourceSettings.tunerAutoStart(context))
        SourceSettings.setTunerAutoStart(context,false)
        assertFalse(SourceSettings.consumeTunerStart(context))
    }
}
