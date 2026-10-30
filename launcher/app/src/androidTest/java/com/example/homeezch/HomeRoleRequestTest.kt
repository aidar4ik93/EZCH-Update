package com.example.homeezch

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/** Does not choose a role holder or modify the owner's default HOME. */
class HomeRoleRequestTest {
    companion object {
        @JvmStatic @BeforeClass fun suppressAutomaticPrompt() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            context.getSharedPreferences("home_setup", Context.MODE_PRIVATE).edit()
                .putBoolean("home_system_prompt_v26", true).commit()
        }
    }
    @get:Rule val ui = createAndroidComposeRule<HomeSetupActivity>()
    @Test fun requestShowsSystemUiAndCancellationDoesNotClaimSuccess() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertFalse("Test package must not be default HOME", HomeRole.isHome(ui.activity))
        val supported = HomeRole.requestIntent(ui.activity) != null
        ui.onNodeWithText("Выбрать EZCH главным экраном").performClick()
        if (!supported) {
            ui.onNodeWithText("Прошивка не предоставляет штатный выбор HOME. Назначение не подтверждено.").assertExists()
            assertFalse(HomeRole.isHome(ui.activity))
            return
        }
        ui.waitUntil(10000) { device.currentPackageName != ui.activity.packageName }
        val hierarchy = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(hierarchy)
        assertTrue("A role/home chooser must expose UI", hierarchy.size() > 1000)
        java.io.File(ui.activity.getExternalFilesDir(null), "home-choice-before.xml").writeBytes(hierarchy.toByteArray())
        println("HOME chooser foreground: ${device.currentPackageName}")
        device.pressBack()
        android.os.SystemClock.sleep(1000)
        val after = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(after)
        java.io.File(ui.activity.getExternalFilesDir(null), "home-choice-after.xml").writeBytes(after.toByteArray())
        println("HOME cancel foreground: ${device.currentPackageName}")
        // Some TV firmware sends Back to its stock HOME instead of the caller.
        // Reopening the helper must still report the actual resolver, never success.
        if (device.currentPackageName != ui.activity.packageName) ui.runOnUiThread {
            ui.activity.startActivity(android.content.Intent(ui.activity, HomeSetupActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        ui.waitUntil(10000) { device.currentPackageName == ui.activity.packageName }
        ui.onNodeWithText("Главный экран: назначение не подтверждено").assertExists()
        assertFalse(HomeRole.isHome(ui.activity))
    }
}
