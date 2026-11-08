package com.example.homeezch

import org.junit.Assert.*
import org.junit.Test

class HomeWindowPolicyTest {
    private val pkg = "com.google.android.apps.tv.launcherx"
    private val homes = setOf("$pkg.MainActivity")

    @Test fun actualHomeStillRedirects() {
        assertTrue(isResolvedHomeWindow(pkg, "$pkg.MainActivity", homes))
    }
    @Test fun settingsInTheSamePackageStayOpen() {
        for (name in listOf("QuickSettingsActivity", "SettingsActivity", "AccountsActivity",
            "profile.core.AccountVerificationActivity"))
            assertFalse(isResolvedHomeWindow(pkg, "$pkg.$name", homes))
    }
    @Test fun dialogAndUnknownWindowAreNotHome() {
        for (name in listOf(null, "", "android.app.Dialog", "android.widget.FrameLayout"))
            assertFalse(isResolvedHomeWindow(pkg, name, homes))
    }
    @Test fun shortActivityNameAndAliasTargetAreAcceptedWhenResolved() {
        assertTrue(isResolvedHomeWindow(pkg, "$pkg.MainActivity", setOf(".MainActivity")))
        assertTrue(isResolvedHomeWindow(pkg, ".MainActivity", homes))
        assertTrue(isResolvedHomeWindow(pkg, "$pkg.RealHome", setOf("$pkg.HomeAlias", "$pkg.RealHome")))
    }
    @Test fun missingResolverDoesNotGuessFromPackageOrClassSuffix() {
        assertFalse(isResolvedHomeWindow(pkg, "$pkg.MainActivity", emptySet()))
        assertFalse(isResolvedHomeWindow(pkg, "another.package.MainActivity", homes))
    }
}
