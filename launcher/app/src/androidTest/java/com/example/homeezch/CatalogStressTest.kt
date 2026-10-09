package com.example.homeezch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.homeezch.data.CatalogRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Delayed/offline network, thousands of selections and repeated refresh requests. */
class CatalogStressTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("Timed out waiting for catalog state", condition())
    }
    @Test fun localCatalogRemainsInteractiveDuringSlowRefreshAndDoesNotLoseSelection() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val requests = AtomicInteger()
        val local = app.assets.open("apps.json").bufferedReader().use { it.readText() }
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir() = File(app.cacheDir, "catalog-stress-local").apply { mkdirs() }
        }
        File(isolated.filesDir, "catalog.json").delete()
        val repository = CatalogRepository(isolated, sourceReader = {
            requests.incrementAndGet(); entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            local
        })
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        try {
            instrumentation.runOnMainSync { model = CatalogViewModel(app, repository); store.put("catalog", model) }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            await { !model.uiState.value.isLoading && model.uiState.value.rows.isNotEmpty() }
            assertTrue(model.uiState.value.isRefreshing)
            val key = model.uiState.value.rows.first { it.updateAvailable }.app.packageName
            instrumentation.runOnMainSync {
                repeat(2001) { model.toggleSelection(key) }
                repeat(100) { model.reload(); model.refreshCatalogIfIdle() }
            }
            assertEquals(setOf(key), model.uiState.value.selectedPackages)
            assertEquals(1, requests.get())
            release.countDown()
            await { !model.uiState.value.isRefreshing }
            assertEquals(setOf(key), model.uiState.value.selectedPackages)
            instrumentation.runOnMainSync { repeat(100) { model.refreshCatalogIfIdle() } }
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { store.clear() }
        }
    }
    @Test fun damagedCacheAndOfflineNetworkStillProvideSelectableCatalog() {
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir() = File(app.cacheDir, "catalog-stress-corrupt").apply { mkdirs() }
        }
        File(isolated.filesDir, "catalog.json").writeText("not JSON")
        val repository = CatalogRepository(isolated, sourceReader = { throw java.io.IOException("offline") })
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        try {
            instrumentation.runOnMainSync { model = CatalogViewModel(app, repository); store.put("catalog", model) }
            await { !model.uiState.value.isRefreshing }
            assertFalse(model.uiState.value.isLoading)
            assertTrue(model.uiState.value.rows.isNotEmpty())
            assertTrue(model.uiState.value.message.orEmpty().contains("сохранённая версия"))
            val key = model.uiState.value.rows.first { it.updateAvailable }.app.packageName
            instrumentation.runOnMainSync { model.toggleSelection(key) }
            assertTrue(key in model.uiState.value.selectedPackages)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
    @Test fun pendingLauncherSetupCannotOpenOlderInstalledVersion() {
        val prefs = app.getSharedPreferences("launcher_setup", Context.MODE_PRIVATE)
        val previous = prefs.all
        val repository = CatalogRepository(app, sourceReader = { throw java.io.IOException("offline") })
        val store = ViewModelStore()
        lateinit var model: CatalogViewModel
        val opens = AtomicInteger()
        val host = object : ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) { opens.incrementAndGet() }
        }
        try {
            prefs.edit().putBoolean("open_after_install", true).putLong("target_version", Long.MAX_VALUE).commit()
            instrumentation.runOnMainSync { model = CatalogViewModel(app, repository); store.put("catalog", model) }
            await { !model.uiState.value.isRefreshing }
            instrumentation.runOnMainSync { repeat(100) { model.openPreparedLauncher(host) } }
            assertEquals(0, opens.get())
            assertTrue(prefs.getBoolean("open_after_install", false))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            val edit = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is String -> edit.putString(key, value)
            } }
            edit.commit()
        }
    }
}

