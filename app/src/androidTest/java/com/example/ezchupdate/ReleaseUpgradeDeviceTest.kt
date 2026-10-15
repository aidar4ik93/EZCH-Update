package com.example.ezchupdate

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.ezchupdate.data.*
import com.example.ezchupdate.install.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern

/** Run explicitly on a test emulator with an older signed EZCH already installed. */
class ReleaseUpgradeDeviceTest {
    @Test fun signedReleaseUpgradeUsesSystemConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val filename = arguments.getString("releaseApk")
        org.junit.Assume.assumeNotNull(filename)
        val expectedHash = arguments.getString("releaseSha256") ?: error("Provide pinned release hash")
        val file = File(context.getExternalFilesDir(null), filename)
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, hash)
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)!!
        val previous = context.packageManager.getPackageInfo(info.packageName, 0).versionCodeCompat()
        assertTrue("Install an older signed release first", previous < info.versionCodeCompat())
        val app = RemoteApp("EZCH Update Premium", info.packageName, info.versionCodeCompat(), info.versionName!!,
            "https://example.com/release.apk", "/release.apk", hash, file.length(),
            "ea0e9a1aad77b1fb42f32644a7386131ceed43e07464dcde22754691e70cc602")
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        device.executeShellCommand("am start -W -n ${context.packageName}/com.example.ezchupdate.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        InstallEvents.snapshot.value = null
        val result = AppInstaller().installFile(context, app, file)
        assertTrue(result.toString(), result.isSuccess)
        val install = device.wait(Until.findObject(By.text(Pattern.compile("(?i)(install|установить|update|обновить)"))), 20_000)
        assertNotNull("Signed release must ask for system confirmation", install)
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallEvents.snapshot.value?.status)
        install.click()
        val deadline = System.currentTimeMillis() + 30_000
        while (InstallEvents.snapshot.value?.status == PackageInstaller.STATUS_PENDING_USER_ACTION && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallEvents.snapshot.value?.status)
        assertEquals(app.versionCode, context.packageManager.getPackageInfo(app.packageName, 0).versionCodeCompat())
        InstallEvents.consume(context, result.getOrThrow())
    }
}
