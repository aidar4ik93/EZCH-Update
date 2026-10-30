package com.example.homeezch

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only: apply the wallpaper the user selected, keeping a restore copy. */
class ApprovedWallpaperDeviceTest {
    @Test fun applySelectedBirdAfterSavingWallpaperAndWorkspace() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("applyApprovedWallpaper") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "upgrade-verification").apply { mkdirs() }
        File(directory, "before-selected-bird.json").writeBytes(DesktopBackup.saveLocal(context).readBytes())
        val previous = WallpaperSettings.load(context)
        WallpaperSettings.save(context, previous.copy(moving = false, video = null, image = null))
        assertNull(WallpaperSettings.load(context).image)
        assertNull(WallpaperSettings.load(context).video)
        assertFalse(WallpaperSettings.load(context).moving)
        assertEquals(previous.mode, WallpaperSettings.load(context).mode)
        assertTrue(R.drawable.premium_blue_eagle != 0)
        File(directory, "selected-bird-backup.json").writeBytes(DesktopBackup.saveLocal(context).readBytes())
    }
}
