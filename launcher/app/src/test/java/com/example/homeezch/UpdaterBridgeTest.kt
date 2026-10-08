package com.example.homeezch
import org.junit.Assert.assertEquals
import org.junit.Test
class UpdaterBridgeTest {
    @Test fun qaUsesIsolatedInstallerFirst() { assertEquals(listOf("com.example.ezchupdate.qa", "com.example.ezchupdate"), updaterPackages("com.example.homeezch.qa")) }
    @Test fun productionNeverOpensQaInstaller() { assertEquals(listOf("com.example.ezchupdate"), updaterPackages("com.example.homeezch")) }
}
