package com.example.ezchupdate

import com.example.ezchupdate.data.RemoteApp
import org.junit.Assert.*
import org.junit.Test

class InstallationQueueTest {
    private fun app(pkg: String) = RemoteApp(pkg, pkg, 14, "1.4", "https://example.org/app.apk", "/app.apk")
    @Test fun selfUpdateRunsLast() {
        val queue = InstallationQueue.create(listOf(app("com.own.app"), app("com.other.one"), app("com.other.two")), "com.own.app")
        assertEquals(listOf("com.other.one", "com.other.two", "com.own.app"), queue.apps.map { it.packageName })
    }
    @Test fun laterSuccessDoesNotHideEarlierFailure() {
        val queue = InstallationQueue(listOf(app("com.one.app"), app("com.two.app")))
        queue.finish("com.one.app", "signature mismatch")
        assertEquals("com.two.app", queue.current?.packageName)
        queue.finish("com.two.app", null)
        assertTrue(queue.complete)
        assertEquals(1, queue.succeeded)
        assertEquals("signature mismatch", queue.failures["com.one.app"])
    }
    @Test(expected = IllegalStateException::class) fun staleResultCannotAdvanceQueue() {
        InstallationQueue(listOf(app("com.one.app"), app("com.two.app"))).finish("com.two.app", null)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicatePackagesRejected() {
        InstallationQueue(listOf(app("com.one.app"), app("com.one.app")))
    }
    @Test fun restoredQueueRetainsPositionAndFailures() {
        val queue = InstallationQueue(listOf(app("com.one.app"), app("com.two.app")), 1, 0, linkedMapOf("com.one.app" to "cancelled"))
        assertEquals("com.two.app", queue.current?.packageName)
        queue.finish("com.two.app", null)
        assertEquals(1, queue.succeeded)
        assertTrue(queue.complete)
        assertEquals(1, queue.failures.size)
    }
}
