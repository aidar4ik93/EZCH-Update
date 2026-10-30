package com.example.ezchupdate

import android.app.Application
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.ezchupdate.data.*
import com.example.ezchupdate.install.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

/** Real PackageInstaller sessions with harmless fixture packages. */
class PremiumQueueDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val device = UiDevice.getInstance(instrument)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertTrue("Queue result timed out", condition())
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun runQueue(count: Int, cancelAt: Int? = null, restartAt: Int? = null) {
        check(context.packageName.endsWith(".debug"))
        // An interrupted previous test may leave sealed sessions owned by this debug app.
        // Abandon them as their actual owner before asserting a fresh sequential queue.
        context.packageManager.packageInstaller.mySessions.forEach {
            context.packageManager.packageInstaller.abandonSession(it.sessionId)
        }
        device.executeShellCommand("am force-stop com.google.android.packageinstaller")
        val files = (1..count).map { number ->
            File(context.cacheDir, "queuefixture$number.apk").also { file ->
                instrument.context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
                device.executeShellCommand("pm uninstall com.example.ezchqueuefixture$number")
            }
        }
        context.getSharedPreferences("install_queue", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit().clear().commit()
        InstallEvents.snapshot.value = null
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        @Suppress("DEPRECATION")
        val apps = files.map { file ->
            val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)!!
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: info.signatures!!.first()
            RemoteApp(file.name, info.packageName, 1, "1.0", "https://example.com/${file.name}", "/${file.name}", digest(file.readBytes()), file.length(), digest(signer.toByteArray()))
        }
        val source = JSONObject().put("apps", JSONArray().apply { apps.forEach { app -> put(JSONObject()
            .put("name", app.name).put("packageName", app.packageName).put("versionCode", app.versionCode)
            .put("versionName", app.versionName).put("apkUrl", app.apkUrl).put("apkPath", app.apkPath)
            .put("sha256", app.sha256).put("sizeBytes", app.sizeBytes).put("signerSha256", app.signerSha256)) } }).toString()
        var store = ViewModelStore()
        lateinit var model: CatalogViewModel
        val sessions = mutableListOf<Int>()
        fun createModel() {
            instrument.runOnMainSync {
                model = CatalogViewModel(context.applicationContext as Application, CatalogRepository(sourceReader = { source })) { target, app, _ ->
                    assertTrue("Only one installation may be pending", target.packageManager.packageInstaller.mySessions.none { it.isActive && it.isSealed })
                    AppInstaller().installFile(target, app, files[apps.indexOf(app)]).also { result -> result.getOrNull()?.let { sessions.add(it) } }
                }
                store.put("queue", model)
            }
            await { !model.uiState.value.isRefreshing }
        }
        try {
            createModel()
            instrument.runOnMainSync { model.onHostResumed(context); apps.forEach { model.toggleSelection(it.packageName) }; model.installSelected(context) }
            for (number in 1..count) {
                assertTrue("Must wait for this APK's actual system dialog", device.wait(Until.hasObject(By.text("EZCH Queue Fixture $number")), 25_000))
                device.waitForIdle()
                val pending = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(install|установить)"))), 25_000)
                assertNotNull("Expected confirmation $number/$count", pending)
                await { InstallEvents.snapshot.value?.status == PackageInstaller.STATUS_PENDING_USER_ACTION }
                assertEquals(number - 1, model.uiState.value.completedCount)
                assertEquals(number, sessions.size)
                if (restartAt == number) {
                    instrument.runOnMainSync { store.clear() }
                    store = ViewModelStore(); createModel()
                    instrument.runOnMainSync { model.onHostResumed(context) }
                    assertEquals(number, sessions.size)
                }
                if (cancelAt == number) {
                    val cancel = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(cancel|отмена)"))), 5000)
                    assertNotNull(cancel); cancel.click()
                } else { println("Confirming fixture $number, sessions=$sessions, completed=${model.uiState.value.completedCount}"); pending.click() }
                await { model.uiState.value.completedCount >= number }
                println("Completed fixture $number: ${model.uiState.value.message}")
            }
            assertEquals(count, model.uiState.value.completedCount)
            assertEquals(if (cancelAt == null) 0 else 1, model.uiState.value.failures.size)
            apps.forEachIndexed { index, app ->
                val installed = runCatching { context.packageManager.getPackageInfo(app.packageName, 0) }.isSuccess
                assertEquals(index + 1 != cancelAt, installed)
            }
        } finally {
            instrument.runOnMainSync { store.clear() }
            context.packageManager.packageInstaller.mySessions.forEach { runCatching { context.packageManager.packageInstaller.abandonSession(it.sessionId) } }
            apps.forEach { device.executeShellCommand("pm uninstall ${it.packageName}") }
            files.forEach { it.delete() }
            context.getSharedPreferences("install_queue", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("install_result", Context.MODE_PRIVATE).edit().clear().commit()
            InstallEvents.snapshot.value = null
            device.executeShellCommand("am force-stop com.google.android.packageinstaller")
        }
    }
    @Test fun tenApksWaitForEachResult() = runQueue(10)
    @Test fun threeApksWaitForEachSystemResult() = runQueue(3)
    @Test fun fiveApksRemainSequentialAcrossViewModelRecreation() = runQueue(5, restartAt = 2)
    @Test fun cancellationIsFailureAndQueueContinuesSequentially() = runQueue(3, cancelAt = 2)
}
