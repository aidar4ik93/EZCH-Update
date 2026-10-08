package com.example.homeezch

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import java.io.File

class VideoWallpaperTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var previous = WallpaperChoice()
    @Before fun saveSettings() { previous = WallpaperSettings.load(context) }
    @After fun restoreSettings() { WallpaperSettings.save(context, previous) }
    @Test fun localVideoRendersAndDecoderIsReleasedOutsideHome() {
        val video = File(context.cacheDir, "decoder-test.mp4")
        instrumentation.context.assets.open("decoder-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        WallpaperSettings.save(context, WallpaperChoice(WallpaperMode.PREMIUM, true, Uri.fromFile(video).toString()))
        ui.setContent { LiveWallpaper(true) }
        ui.waitUntil(15000) { VideoDiagnostics.activePlayers.get() == 1 }
        val shot = File(context.cacheDir, "video-shot.png")
        val device = UiDevice.getInstance(instrumentation)
        ui.waitUntil(15000) {
            device.takeScreenshot(shot)
            BitmapFactory.decodeFile(shot.path)?.let { bitmap ->
                val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2); bitmap.recycle()
                android.graphics.Color.red(color) > 130 && android.graphics.Color.blue(color) > 130 && android.graphics.Color.green(color) < 60
            } == true
        }
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0, VideoDiagnostics.activePlayers.get())
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.waitUntil(15000) { VideoDiagnostics.activePlayers.get() == 1 }
        video.delete(); shot.delete()
    }
    @Test fun invalidVideoFallsBackAndDoesNotLeakDecoder() {
        WallpaperSettings.save(context, WallpaperChoice(WallpaperMode.PREMIUM, true, "file:///does-not-exist.mp4"))
        ui.setContent { LiveWallpaper(true) }
        ui.waitUntil(15000) { VideoDiagnostics.activePlayers.get() == 0 }
        ui.waitForIdle()
        assertEquals(0, VideoDiagnostics.activePlayers.get())
    }
    @Test fun linksCannotEscapeFileOperationsOnAndroid() {
        val root = File(context.cacheDir, "links-test").apply { mkdirs() }
        try {
            val outside = File(root, "keep.txt").apply { writeText("keep") }
            val source = File(root, "source").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(source, "link").toPath(), outside.toPath())
            assertTrue(runCatching { FileOperations.delete(source) }.isFailure)
            assertEquals("keep", outside.readText())
        } finally { root.deleteRecursively() }
    }
}
