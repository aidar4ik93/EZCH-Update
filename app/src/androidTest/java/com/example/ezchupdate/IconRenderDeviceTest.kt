package com.example.ezchupdate

import android.graphics.Bitmap
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class IconRenderDeviceTest {
    @Test fun renderVerifiedZonaAdaptiveVectorIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "zona.apk")
        org.junit.Assume.assumeTrue("Push the verified Zona APK into this test app's external files directory", file.isFile)
        val entry = org.json.JSONObject(context.assets.open("apps.json").bufferedReader().use { it.readText() }).getJSONArray("apps")
            .let { apps -> (0 until apps.length()).map { apps.getJSONObject(it) }.single { it.getString("packageName") == "mobi.zona" } }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(entry.getString("sha256"), hash)
        val archive = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA)!!
        val info = archive.applicationInfo!!
        info.sourceDir = file.path; info.publicSourceDir = file.path
        val drawable = info.loadIcon(context.packageManager)
        assertTrue("Zona must load its real Adaptive Icon", drawable is android.graphics.drawable.AdaptiveIconDrawable)
        val bitmap = drawable.toBitmap(256, 256)
        assertEquals(256, bitmap.width); assertEquals(256, bitmap.height)
        assertTrue(bitmap.getPixel(128, 128) != 0)
        File(context.getExternalFilesDir(null), "zona-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderVerifiedCatalogIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "catalog-apks")
        org.junit.Assume.assumeTrue(directory.isDirectory)
        val output = File(context.cacheDir, "catalog-icons").apply { mkdirs() }
        val entries = org.json.JSONObject(context.assets.open("apps.json").bufferedReader().use { it.readText() }).getJSONArray("apps")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val apk = File(directory, entry.getString("apkPath").substringAfterLast('/'))
            if (!apk.isFile) continue
            val digest = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
            assertEquals(entry.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it) })
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)!!.applicationInfo!!
            info.sourceDir = apk.path; info.publicSourceDir = apk.path
            val bitmap = info.loadIcon(context.packageManager).toBitmap(128, 128)
            File(output, "${entry.getString("packageName")}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(output.listFiles().orEmpty().isNotEmpty())
    }
}
