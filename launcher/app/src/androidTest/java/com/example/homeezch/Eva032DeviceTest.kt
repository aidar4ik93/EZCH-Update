package com.example.homeezch

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.homeezch.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Eva032DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun miniArtworkFitsTileAndKeepsCornersTransparent() {
        instrumentation.runOnMainSync {
            val side = (evaMiniSideDp(101.5f) * context.resources.displayMetrics.density).toInt()
            val view = EvaMiniView(context)
            view.layout(0, 0, side, side)
            val picture = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(picture))
            assertEquals(side, view.width)
            assertEquals(side, view.height)
            assertEquals(0, android.graphics.Color.alpha(picture.getPixel(0, 0)))
            // The ribbon sphere is open in the middle: the artwork must be visible somewhere inside the tile.
            val pixels = IntArray(side * side).also { picture.getPixels(it, 0, side, 0, 0, side, side) }
            assertTrue(pixels.count { android.graphics.Color.alpha(it) > 0 } > side * side / 50)
            File(context.getExternalFilesDir(null), "eva-033-mini-artwork.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            picture.recycle()
        }
    }
    @Test fun optionalControlPermissionIsSeparateFromHome() {
        val info = context.packageManager.getServiceInfo(android.content.ComponentName(context, EvaAppService::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.permission)
        assertNotEquals(0, info.metaData.getInt("android.accessibilityservice"))
        assertFalse(AndroidCapabilityProbe(context).report().contains("штатная кнопка: проверена"))
    }
    @Test fun executeRequestedDeviceCommand() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val text = args.getString("evaCommand") ?: return@runBlocking
        assertTrue("Ева должна быть включена владельцем", AiPreferences.enabled(context))
        repeat(30) { if (EvaAppService.current == null) delay(200) }
        assertNotNull("Включите отдельную службу Ai EvA в Android", EvaAppService.current)
        val parsed = LocalIntentResolver().resolve(text)
        assertTrue(parsed.toString(), parsed is Resolution.Plan)
        val results = withContext(Dispatchers.Main) {
            val executor = AndroidActionExecutor(context)
            val list = mutableListOf<String>()
            for (action in (parsed as Resolution.Plan).actions) {
                val result = executor.execute(action)
                list.add("$action => ${result.accepted}: ${result.message}")
                if (!result.accepted) break
            }
            list
        }
        val output = results.joinToString("\n")
        File(context.getExternalFilesDir(null), "eva-device-command.txt").writeText(output)
        println(output)
        if (args.getString("expectSuccess") == "true") assertTrue(output, results.last().contains("=> true:"))
    }
}
