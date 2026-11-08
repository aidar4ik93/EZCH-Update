package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/** Claude r58: adb key format (mincrypt RSAPublicKey) and the signature block adbd verifies. */
class LocalAdbTest {
    @Test fun publicKeyMatchesAdbFormat() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        val text = String(LocalAdb.androidPublicKey(key, "EZCH@tv")).trimEnd('\u0000')
        assertTrue(text.endsWith(" EZCH@tv"))
        val raw = java.util.Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val words = IntArray(64) { b.int }
        var n = BigInteger.ZERO
        for (i in 63 downTo 0) n = n.shiftLeft(32).add(BigInteger.valueOf(words[i].toLong() and 0xffffffffL))
        assertEquals(key.modulus, n)
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n * n0inv ≡ -1 (mod 2^32)
        assertEquals(r32.subtract(BigInteger.ONE), n.multiply(BigInteger.valueOf(n0inv)).mod(r32))
        repeat(64) { b.int } // rr
        assertEquals(65537, b.int)
    }

    @Test fun signatureBlockIsPkcs1Sha1() {
        val token = ByteArray(20) { it.toByte() }
        val block = LocalAdb.signatureBlock(token)
        assertEquals(256, block.size)
        assertEquals(0, block[0].toInt()); assertEquals(1, block[1].toInt())
        assertEquals(0, block[256 - 20 - 15 - 1].toInt())
        assertEquals(0x30, block[256 - 35].toInt())
        assertEquals(token.toList(), block.takeLast(20))
        assertTrue(block.slice(2 until 256 - 36).all { it == 0xff.toByte() })
    }

    @Test fun homeTakeoverDisablesOnlyStockScreensAboveEzch() {
        val own = "com.example.homeezch.usb"
        val homatics = listOf("com.google.android.apps.tv.launcherx" to 2, "com.google.android.tungsten.setupwraith" to 1,
            own to 0, "com.android.tv.settings" to -1000)
        assertEquals(listOf("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith"), HomeTakeover.toDisable(homatics, own))
        assertEquals(emptyList<String>(), HomeTakeover.toDisable(listOf(own to 0, "com.android.tv.settings" to -1000), own))
        assertEquals(emptyList<String>(), HomeTakeover.toDisable(listOf("com.other.launcher" to 5, own to 0), own))
        assertEquals(emptyList<String>(), HomeTakeover.toDisable(listOf("com.google.android.apps.tv.launcherx" to 2), own))
    }
}
