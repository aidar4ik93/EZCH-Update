package com.example.ezchupdate

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateStatusTest {
    private fun item(p: String, v: Long) = PendingUpdate(p, p, v, "")

    @Test fun keepsOnlyUpdatesNewerThanInstalled() {
        val items = listOf(item("a", 5), item("b", 3), item("c", 2))
        val installed = mapOf("a" to 4L, "b" to 3L) // c not installed: a new app, not an update
        assertEquals(listOf("a"), UpdateStatus.stillPending(items) { installed[it] }.map { it.packageName })
    }

    @Test fun installedUpdateDisappearsImmediately() {
        val items = listOf(item("a", 5))
        assertEquals(1, UpdateStatus.stillPending(items) { 4L }.size)
        assertEquals(0, UpdateStatus.stillPending(items) { 5L }.size)
    }
}
