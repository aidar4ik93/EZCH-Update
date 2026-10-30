package com.example.homeezch

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.homeezch.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Run only against the isolated debug package; never changes the owner's release preferences. */
class AiProfileSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = ""
    @Before fun setup() {
        check(context.packageName.endsWith(".debug"))
        original = DesktopBackup.export(context)
        context.getSharedPreferences("ezch_launcher_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Profiles.save(context, listOf(DesktopProfile("owner", "Владелец", PinCredential.create("9274")), DesktopProfile("guest", "Гость")))
    }
    @After fun restore() { if (original.isNotEmpty()) DesktopBackup.restore(context, original) }
    @Test fun updateCommandCannotBypassOwnerPinOrGuestProfile() = runBlocking {
        assertFalse(AndroidActionExecutor(context).execute(Action.Updates).accepted)
        Profiles.save(context, listOf(DesktopProfile("owner", "Владелец"), DesktopProfile("guest", "Гость")))
        assertTrue(AiProfilePolicy.updatesAllowed(context))
        Profiles.switch(context, "guest")
        assertFalse(AndroidActionExecutor(context).execute(Action.Updates).accepted)
    }
    @Test fun voiceDiscoveryUsesActiveDesktopAndHiddenApps() {
        val available = AndroidActionExecutor(context).apps()
        org.junit.Assume.assumeTrue(available.isNotEmpty())
        val packageName = available.first().component.substringBefore('/')
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Главная", listOf(packageName))), hiddenApps = listOf(packageName)))
        assertFalse(AndroidActionExecutor(context).apps().any { it.component.substringBefore('/') == packageName })
        Profiles.switch(context, "guest")
        assertTrue(AndroidActionExecutor(context).apps().isEmpty())
        WorkspacePrefs.save(context, Workspace(listOf(AppShelf("main", "Главная", listOf(packageName)))))
        assertTrue(AndroidActionExecutor(context).apps().all { it.component.substringBefore('/') == packageName })
    }
    @Test fun settingsTicketIsSingleUseAndBoundToProfile() {
        val token = AiSettingsTicket.issue(context)
        assertFalse(AiSettingsTicket.consume(context, "invalid"))
        assertTrue(AiSettingsTicket.consume(context, token))
        assertFalse(AiSettingsTicket.consume(context, token))
        val other = AiSettingsTicket.issue(context)
        Profiles.switch(context, "guest")
        assertFalse(AiSettingsTicket.consume(context, other))
    }
    @Test fun settingsOpenedWithoutTrustedTicketRequirePin() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<AssistantSettingsActivity>(Intent(context, AssistantSettingsActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.text("PIN · Владелец")), 5000))
            assertFalse(device.hasObject(By.text("Включить помощник и кнопку в HOME")))
        }
    }
}
