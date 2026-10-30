package com.example.homeezch

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Read-only preference audit. Also runs against the installed 0.27 APK without new launcher classes. */
class UpgradeAuditTest {
    @Test fun capturePreferencesWithoutChangingOwnersSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stage = InstrumentationRegistry.getArguments().getString("snapshot")
        org.junit.Assume.assumeNotNull(stage)
        require(stage in listOf("before", "after"))
        val root = JSONObject()
        listOf("ezch_launcher_prefs", "launcher_preferences", "wallpaper", "source_settings", "home_setup").forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach { (key, value) ->
                values.put(key, if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value)
            }
            root.put(name, values)
        }
        val directory = File(context.getExternalFilesDir(null), "upgrade-verification").apply { mkdirs() }
        val file = File(directory, "$stage.json")
        file.writeText(root.toString(2))
        assertTrue(file.length() > 0)
        if (stage == "after") {
            assertEquals(File(directory, "before.json").readText(), file.readText())
            // Reflection keeps the before-audit runnable on 0.27, which has no backup class.
            val exporter = Class.forName("com.example.homeezch.DesktopBackup", true, context.classLoader)
            val saved = exporter.getDeclaredMethod("saveLocal", Context::class.java)
                .invoke(exporter.getDeclaredField("INSTANCE").get(null), context) as File
            File(directory, "portable-backup.json").writeBytes(saved.readBytes())
        }
    }
}
