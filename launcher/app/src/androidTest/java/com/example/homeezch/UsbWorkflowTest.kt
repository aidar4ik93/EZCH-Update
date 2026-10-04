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
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UsbWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun copyAndMoveVerifyBytesWithoutOverwriting() {
        val root = File(context.cacheDir, "file-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "payload.txt").apply { writeText("Содержимое тестового файла") }
        val target = File(root, "target").apply { mkdirs() }
        try {
            val document = DocumentFile.fromFile(source); val folder = DocumentFile.fromFile(target)
            FileOperations.copy(context, document, folder)
            assertEquals(source.readText(), File(target, source.name).readText())
            assertTrue(runCatching { FileOperations.copy(context, document, folder) }.isFailure)
            assertTrue(source.exists())
            FileOperations.rename(document, "renamed.txt")
            FileOperations.move(context, document, folder)
            assertFalse(File(root, "renamed.txt").exists())
            assertTrue(File(target, "renamed.txt").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun hiddenAppsPersistAndCanBeRestored() {
        val preferences = LauncherPreferences(context)
        val before = preferences.hiddenPackages
        try {
            preferences.hiddenPackages = listOf("org.example.hidden")
            assertEquals(listOf("org.example.hidden"), LauncherPreferences(context).hiddenPackages)
            preferences.hiddenPackages = emptyList()
            assertTrue(LauncherPreferences(context).hiddenPackages.isEmpty())
        } finally { preferences.hiddenPackages = before }
    }
    @Test fun homeClosesAppMenuAndReturnsToDesktop() {
        context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("seen", true).commit()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("EZCH Update")), 10_000))
        val first = findTvApps(context.packageManager, context.packageName).firstOrNull()
        assertNotNull("An emulator launchable app is required", first)
        var card = device.wait(Until.findObject(By.desc(first!!.name)), 5000)
        assertNotNull(card); card.longClick()
        assertTrue(device.wait(Until.hasObject(By.text("Скрыть")), 5000))
        context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.SHOW_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertTrue(device.wait(Until.gone(By.text("Скрыть")), 5000))
        assertTrue(device.wait(Until.hasObject(By.text("Файловый менеджер")), 5000))
        card = device.wait(Until.findObject(By.desc(first.name)), 5000)
        assertNotNull(card); card.longClick()
        assertTrue(device.wait(Until.hasObject(By.text("Переместить")), 5000))
    }
    @Test fun fileBrowserAndUpdaterAreBundledAndOpenFromDesktop() {
        context.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("seen", true).commit()
        context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.SHOW_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val files = device.wait(Until.findObject(By.text("Файловый менеджер")), 10_000)
        assertNotNull(files); files.click()
        assertTrue(device.wait(Until.hasObject(By.text("Выбрать папку / USB")), 5000))
        context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.SHOW_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val updater = device.wait(Until.findObject(By.text("EZCH Update")), 5000)
        assertNotNull(updater); updater.click()
        assertTrue(device.wait(Until.hasObject(By.text("Проверить обновления")), 5000))
    }
}
