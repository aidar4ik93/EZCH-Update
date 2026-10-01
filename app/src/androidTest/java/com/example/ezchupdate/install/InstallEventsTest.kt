package com.example.ezchupdate.install

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android preferences and package sessions; no third-party APK or network required. */
@RunWith(AndroidJUnit4::class)
class InstallEventsTest {
    private lateinit var context: Context
    private val createdSessions = mutableListOf<Int>()
    private val packageName = "org.example.ezch.journaltest"

    @Before fun setUp() {
        forgetProcessMemory()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "journal-test-${UUID.randomUUID()}"
        context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$prefix-$name", mode)
            override fun getCacheDir(): File = File(target.cacheDir, prefix).apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        createdSessions.forEach { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
        context.getSharedPreferences("installation_journal", Context.MODE_PRIVATE).edit().clear().commit()
        context.cacheDir.deleteRecursively()
        forgetProcessMemory()
    }

    @Test fun pendingConfirmationAndCorrelationFieldsReachDiskAndReload() {
        val submission = createOwnedSession()
        InstallEvents.started(context, submission, 42)
        val confirmation = Intent("android.content.pm.action.CONFIRM_INSTALL")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, submission.sessionId)
            .setData(Uri.parse("package:$packageName"))
        InstallEvents.pending(context, submission, confirmation)
        val journal = JSONObject(context.getSharedPreferences("installation_journal", Context.MODE_PRIVATE).getString("journal", null)!!)
        val saved = journal.getJSONArray("results").getJSONObject(0)
        assertEquals(42L, saved.getLong("version"))
        assertTrue(saved.getLong("startedAt") > 0)
        // Simulate a new process reading the durable journal without submitting an actual APK.
        forgetProcessMemory()
        assertEquals(listOf(submission), InstallEvents.activeSessions(context))
        val restored = AppInstaller().confirmationIntent(context, submission.sessionId)
        assertNotNull(restored)
        assertEquals(submission.sessionId, restored!!.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
        assertEquals(confirmation.action, restored.action)
    }

    @Test fun restartAbandonsUnsealedSessionEvenWhenJournalWasMarkedCommitted() {
        val submission = createOwnedSession()
        InstallEvents.started(context, submission, 42)
        InstallEvents.markCommitted(context, submission.sessionId)
        val downloads = File(context.cacheDir, "downloads").apply { mkdirs() }
        val partial = File(downloads, "apk-old.part").apply { writeText("incomplete download") }
        forgetProcessMemory()
        val result = InstallEvents.observe(context).value.single()
        assertEquals(submission.sessionId, result.sessionId)
        assertEquals(42L, result.versionCode)
        assertTrue(result.startedAt > 0)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, result.status)
        assertTrue(result.terminal)
        assertTrue(InstallEvents.activeSessions(context).isEmpty())
        assertNull(context.packageManager.packageInstaller.getSessionInfo(submission.sessionId))
        assertFalse(partial.exists())
    }

    @Test fun cancelAbandonsOnlyOwnedSessionAndRetainsResultUntilAcknowledged() {
        val submission = createOwnedSession()
        InstallEvents.observe(context)
        InstallEvents.started(context, submission, 42)
        AppInstaller().cancel(context, submission.sessionId)
        val result = InstallEvents.observe(context).value.single()
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, result.status)
        assertEquals(submission.sessionId, result.sessionId)
        assertEquals(42L, result.versionCode)
        assertNull(context.packageManager.packageInstaller.getSessionInfo(submission.sessionId))
        forgetProcessMemory()
        assertEquals(result, InstallEvents.observe(context).value.single())
        InstallEvents.acknowledge(context, submission.sessionId)
        forgetProcessMemory()
        assertTrue(InstallEvents.observe(context).value.isEmpty())
    }

    @Test fun receiverIgnoresMismatchedSessionAndAcceptsOnlyCorrelatedFinalStatus() {
        val submission = createOwnedSession()
        InstallEvents.observe(context)
        InstallEvents.started(context, submission, 42)
        val callback = Intent().putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, packageName)
            .putExtra(InstallResultReceiver.EXTRA_OWN_SESSION_ID, submission.sessionId)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, submission.sessionId + 1)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_STORAGE)
        InstallResultReceiver().onReceive(context, callback)
        assertTrue(InstallEvents.observe(context).value.isEmpty())
        assertEquals(listOf(submission), InstallEvents.activeSessions(context))
        callback.putExtra(PackageInstaller.EXTRA_SESSION_ID, submission.sessionId)
        InstallResultReceiver().onReceive(context, callback)
        val result = InstallEvents.observe(context).value.single()
        assertEquals(submission.sessionId, result.sessionId)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, result.status)
        assertTrue(result.message!!.contains("памяти"))
        assertTrue(InstallEvents.activeSessions(context).isEmpty())
    }

    @Test fun failedJournalWritesRefuseNewSubmissionButDoNotCrashFinalCallback() {
        val durableContext = context
        val noSpaceContext = object : ContextWrapper(durableContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val preferences = durableContext.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit() = false
                        }
                    }
                }
            }
        }
        val submission = createOwnedSession()
        InstallEvents.observe(durableContext)
        try {
            InstallEvents.started(noSpaceContext, submission, 42)
            throw AssertionError("A new session must require a durable journal")
        } catch (_: InstallException) { }
        val callback = Intent().putExtra(InstallResultReceiver.EXTRA_CATALOG_PACKAGE, packageName)
            .putExtra(InstallResultReceiver.EXTRA_OWN_SESSION_ID, submission.sessionId)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, submission.sessionId)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_STORAGE)
        InstallResultReceiver().onReceive(noSpaceContext, callback)
        val retained = InstallEvents.observe(noSpaceContext).value.single()
        assertTrue(retained.terminal)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, retained.status)
        assertTrue(retained.message!!.contains("сохранить"))
        assertTrue(InstallEvents.activeSessions(noSpaceContext).isEmpty())
    }

    private fun createOwnedSession(): InstallSubmission {
        val id = context.packageManager.packageInstaller.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) })
        createdSessions.add(id)
        return InstallSubmission(id, packageName)
    }

    @Suppress("UNCHECKED_CAST")
    private fun forgetProcessMemory() {
        // Deliberately reset only process memory; production offers no journal reset endpoint.
        fun field(name: String) = InstallEvents::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("initialized").setBoolean(InstallEvents, false)
        field("recovered").setBoolean(InstallEvents, false)
        (field("active").get(InstallEvents) as MutableMap<*, *>).clear()
        (field("confirmations").get(InstallEvents) as MutableMap<*, *>).clear()
        (field("retained").get(InstallEvents) as MutableStateFlow<List<InstallResult>>).value = emptyList()
    }
}
