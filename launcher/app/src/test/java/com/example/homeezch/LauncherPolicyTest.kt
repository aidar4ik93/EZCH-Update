package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class LauncherPolicyTest {
    @Test fun restoredOrderDropsUninstalledAppsAndAppendsNewApps() {
        assertEquals(listOf("b", "a", "new"), orderedKeys(listOf("gone", "b", "b"), listOf("a", "b", "new")))
    }
    @Test fun firstLaunchPreservesDiscoveryOrder() {
        assertEquals(listOf("tv", "video", "store"), orderedKeys(emptyList(), listOf("tv", "video", "store")))
    }
}
