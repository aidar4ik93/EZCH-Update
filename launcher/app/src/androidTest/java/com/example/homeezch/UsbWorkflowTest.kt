package com.example.homeezch
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UsbWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun isolateWeatherPermissionFromNavigation() {
        context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("weather_auto", false).putBoolean("weather_prompt_v09", true).commit()
    }
    private fun startHome() {
        // Launch from the test shell: background instrumentation must not depend on
        // Android allowing an application context to bring itself over Settings.
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.homeezch.MainActivity -f 0x14000000")
    }
    @Test fun copyAndMoveVerifyBytesWithoutOverwriting() {
        val root = File(context.cacheDir, "file-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "payload.txt").apply { writeText("Содержимое тестового файла") }
        val target = File(root, "target").apply { mkdirs() }
        try {
            val document = DocumentFile.fromFile(source); val folder = DocumentFile.fromFile(target)
            DocumentOperations.copy(context, document, folder)
            assertEquals(source.readText(), File(target, source.name).readText())
            assertTrue(runCatching { DocumentOperations.copy(context, document, folder) }.isFailure)
            assertTrue(source.exists())
            DocumentOperations.rename(document, "renamed.txt")
            DocumentOperations.move(context, document, folder)
            assertFalse(File(root, "renamed.txt").exists())
            assertTrue(File(target, "renamed.txt").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun hiddenAppsPersistAndCanBeRestored() {
        val before = WorkspacePrefs.load(context)
        try {
            val hidden = before.discover(listOf("org.example.hidden")).hideApp("org.example.hidden")
            assertTrue(WorkspacePrefs.save(context, hidden))
            assertTrue("org.example.hidden" in WorkspacePrefs.load(context).hiddenApps)
            assertTrue(WorkspacePrefs.save(context, hidden.copy(hiddenApps = emptyList())))
            assertTrue(WorkspacePrefs.load(context).hiddenApps.isEmpty())
        } finally { WorkspacePrefs.save(context, before) }
    }
    @Test fun fileBrowserAndUpdaterAreBundledAndOpenFromDesktop() {
        context.getSharedPreferences("home_setup", Context.MODE_PRIVATE).edit().putBoolean("home_prompt_v09", true).commit()
        startHome()
        device.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        val files = device.wait(Until.findObject(By.text("Файловый менеджер")), 10_000)
        assertNotNull(files); files.click()
        assertTrue(device.wait(Until.hasObject(By.text("Выбрать папку / USB")), 5000))
        startHome()
        val updater = device.wait(Until.findObject(By.text("EZCH Store")), 5000)
        assertNotNull(updater); updater.click()
        assertTrue(device.wait(Until.hasObject(By.text("Проверить обновления")), 15000))
    }
}
