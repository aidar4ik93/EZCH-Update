package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class OptimizationPolicyTest {
    private val item = OptimizationPolicy.catalog.last()
    @Test fun protectsHomeAndSkipsAlreadyDisabledPackages() {
        val enabled = OptionalPackageState(item, 0, true)
        assertTrue(OptimizationPolicy.selectable(listOf(enabled), setOf(item.packageName)).isEmpty())
        assertTrue(OptimizationPolicy.selectable(listOf(enabled.copy(enabledState=3,enabled=false)), emptySet()).isEmpty())
    }
    @Test fun restorePreservesDefaultAndExplicitEnabledState() {
        assertTrue(OptimizationPolicy.script(listOf(OptionalPackageState(item,0,true)),emptySet(),"device",true).contains("pm default-state --user 0 ${item.packageName}"))
        assertTrue(OptimizationPolicy.script(listOf(OptionalPackageState(item,1,true)),emptySet(),"device",true).contains("pm enable --user 0 ${item.packageName}"))
    }
    @Test fun rejectsUnknownOrInjectedPackage() {
        val unsafe = item.copy(packageName="com.android.systemui; reboot")
        assertThrows(IllegalArgumentException::class.java) { OptimizationPolicy.script(listOf(OptionalPackageState(unsafe,0,true)),emptySet(),"device",false) }
    }
    @Test fun scriptIsBoundToDeviceAndRechecksHomeAtExecution() {
        val script = OptimizationPolicy.script(listOf(OptionalPackageState(item,0,true)),emptySet(),"device'build",false)
        assertTrue(script.contains("getprop ro.build.fingerprint"))
        assertTrue(script.contains("am get-current-user"))
        assertTrue(script.contains("Current HOME is protected"))
        assertFalse(script.contains("pm uninstall"))
        assertFalse(script.contains("pm clear"))
    }
    @Test fun catalogNeverListsCoreServices() {
        val core = setOf("com.google.android.gms", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.katniss", "com.android.vending", "com.google.android.webview", "com.android.tv.settings")
        assertTrue(OptimizationPolicy.catalog.none { it.packageName in core })
        assertTrue(OptimizationPolicy.catalog.any { it.packageName == "tv.anoki.acr.controller" && it.recommended })
    }
    @Test fun cleanupClearsOnlyCachesAndRestoresOwnerAnimations() {
        val prev = listOf("1.0", null, "0.75")
        val apply = OptimizationPolicy.cleanup("device", true, prev, false)
        assertTrue(apply.contains("pm trim-caches 999G"))
        assertTrue(apply.contains("am kill-all"))
        assertTrue(apply.contains("settings put global window_animation_scale 0.5"))
        assertFalse(apply.contains("pm clear")); assertFalse(apply.contains("pm uninstall")); assertFalse(apply.contains("disable"))
        val restore = OptimizationPolicy.cleanup("device", true, prev, true)
        assertTrue(restore.contains("settings put global window_animation_scale 1.0"))
        assertTrue(restore.contains("settings delete global transition_animation_scale"))
        assertTrue(restore.contains("settings put global animator_duration_scale 0.75"))
        assertFalse(OptimizationPolicy.cleanup("device", false, prev, false).contains("settings put"))
    }
    @Test fun cleanupRejectsInjectedAnimationValue() {
        assertThrows(IllegalArgumentException::class.java) {
            OptimizationPolicy.cleanup("device", true, listOf("1; reboot", null, null), true)
        }
    }
}
