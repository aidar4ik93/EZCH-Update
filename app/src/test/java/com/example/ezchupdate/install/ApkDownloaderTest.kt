package com.example.ezchupdate.install

import com.example.ezchupdate.data.RemoteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApkDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x01, 0x02)
    private val app = RemoteApp("Test TV", "org.example.tv", 2, "2.0", "https://example.com/app.apk", "/app.apk")

    @Test fun byteProgressAndHashReflectTheActualStream() = runBlocking {
        val directory = temporary.newFolder()
        val stale = directory.resolve("apk-interrupted.part").apply { writeText("partial") }
        val connection = FakeConnection(URL(app.apkUrl), ByteArrayInputStream(bytes), bytes.size.toLong())
        val reports = mutableListOf<InstallProgress>()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = ApkDownloader { connection }.download(directory, app.copy(sizeBytes = bytes.size.toLong(), sha256 = expected), reports::add)
        assertFalse(stale.exists())
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(InstallProgress(InstallStage.DOWNLOADING, bytes.size.toLong(), bytes.size.toLong()), reports.last())
        assertTrue(connection.disconnected)
        ApkDownloader.release(file)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun shortResponseAndHashMismatchNeverLeaveAnApk() = runBlocking {
        val directory = temporary.newFolder()
        val short = FakeConnection(URL(app.apkUrl), ByteArrayInputStream(bytes), bytes.size.toLong() + 1)
        reject { ApkDownloader { short }.download(directory, app) {} }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val wrongHash = FakeConnection(URL(app.apkUrl), ByteArrayInputStream(bytes), bytes.size.toLong())
        reject { ApkDownloader { wrongHash }.download(directory, app.copy(sha256 = "0".repeat(64))) {} }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun redirectToHttpIsRejectedBeforeAnotherConnectionIsOpened() = runBlocking {
        val directory = temporary.newFolder()
        var opens = 0
        val redirect = FakeConnection(URL(app.apkUrl), ByteArrayInputStream(bytes), bytes.size.toLong(), 302, "http://example.com/file.apk")
        reject { ApkDownloader { opens++; redirect }.download(directory, app) {} }
        assertEquals(1, opens)
        assertTrue(redirect.disconnected)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellingABlockedReadDisconnectsAndDeletesPartialFile() = runBlocking {
        val directory = temporary.newFolder()
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val blocked = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("Test socket did not close")
                throw IOException("Socket closed")
            }
        }
        val connection = FakeConnection(URL(app.apkUrl), blocked, null, onDisconnect = { closed.countDown() })
        val job = launch(Dispatchers.IO) { ApkDownloader { connection }.download(directory, app) {} }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(connection.disconnected)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected InstallException") } catch (_: InstallException) { }
    }

    private class FakeConnection(
        url: URL,
        private val input: InputStream,
        private val length: Long?,
        private val status: Int = 200,
        private val location: String? = null,
        private val onDisconnect: () -> Unit = {}
    ) : HttpURLConnection(url) {
        @Volatile var disconnected = false
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
        override fun connect() { }
        override fun getResponseCode() = status
        override fun getContentType() = "application/octet-stream"
        override fun getContentLengthLong() = length ?: -1L
        override fun getInputStream() = input
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }
}
