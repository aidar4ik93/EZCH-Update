package com.example.homeezch

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Claude r58 (owner, 04.11: «надо заложить в лаунчере по умолчанию»). A tiny client of the box's own ADB daemon
 * (network debugging, 127.0.0.1:5555 — enabled on Homatics and most TV boxes), the way Shizuku/LADB work.
 * It runs a shell command with the `shell` user's rights, which an ordinary app never has: make EZCH the only HOME
 * (Google TV keeps its launcher above any role), and a real `am force-stop` for Eva's deep clean.
 *
 * The first connection shows Android's own «Разрешить отладку?» dialog with EZCH's key — that is the owner's consent.
 * The key pair is generated on the box and never leaves it (filesDir/adbkey). Nothing here works without that consent.
 */
internal object LocalAdb {
    private const val A_CNXN = 0x4e584e43; private const val A_AUTH = 0x48545541; private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f; private const val A_WRTE = 0x45545257; private const val A_CLSE = 0x45534c43
    private const val VERSION = 0x01000001; private const val MAX_DATA = 256 * 1024
    private const val AUTH_TOKEN = 1; private const val AUTH_SIGNATURE = 2; private const val AUTH_RSAPUBLICKEY = 3
    const val PORT = 5555

    @Volatile var lastError: String? = null; private set

    /** SHA-1 DigestInfo prefix: adbd verifies RSA_sign(NID_sha1) of the 20-byte token. */
    private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** True once the owner allowed EZCH's key (a command succeeded): later calls show no dialog. */
    fun granted(context: Context) = context.applicationContext.getSharedPreferences("ezch_local_adb", Context.MODE_PRIVATE).getBoolean("granted", false)

    /** Is the daemon listening at all? Cheap; no dialog. */
    fun reachable(timeoutMs: Int = 1500): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs); true }
    }.getOrDefault(false)

    @Synchronized private fun keys(context: Context): KeyPair {
        val dir = File(context.filesDir, "adbkey").apply { mkdirs() }
        val priv = File(dir, "key.pk8"); val pub = File(dir, "key.x509")
        if (priv.isFile && pub.isFile) runCatching {
            val f = KeyFactory.getInstance("RSA")
            return KeyPair(f.generatePublic(X509EncodedKeySpec(pub.readBytes())), f.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded); pub.writeBytes(pair.public.encoded)
        return pair
    }

    /** Android's adb public key format (mincrypt RSAPublicKey, little-endian words), base64 + " name". JVM-tested. */
    fun androidPublicKey(key: RSAPublicKey, name: String): ByteArray {
        val words = 64
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words); buf.putInt(n0inv.toInt())
        fun putBig(v: BigInteger) { var x = v; repeat(words) { buf.putInt(x.mod(r32).toLong().toInt()); x = x.shiftRight(32) } }
        putBig(n); putBig(rr); buf.putInt(key.publicExponent.toInt())
        val b64 = java.util.Base64.getEncoder().encodeToString(buf.array())
        return ("$b64 $name").toByteArray() + 0.toByte()
    }

    /** EMSA-PKCS1-v1_5 block built by hand (00 01 FF… 00 DigestInfo token) + raw RSA, exactly as adbd's RSA_verify expects. */
    fun signatureBlock(token: ByteArray, size: Int = 256): ByteArray {
        val t = SHA1_PREFIX + token
        val block = ByteArray(size) { 0xff.toByte() }
        block[0] = 0; block[1] = 1; block[size - t.size - 1] = 0
        System.arraycopy(t, 0, block, size - t.size, t.size)
        return block
    }
    private fun sign(pair: KeyPair, token: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, pair.private) }.doFinal(signatureBlock(token))

    private fun write(out: OutputStream, m: Msg) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(m.cmd).putInt(m.arg0).putInt(m.arg1).putInt(m.data.size).putInt(m.data.sumOf { it.toInt() and 0xff }).putInt(m.cmd.inv())
        out.write(h.array()); out.write(m.data); out.flush()
    }
    private fun read(input: DataInputStream): Msg {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        check(len in 0..MAX_DATA) { "adb: bad length" }
        val data = ByteArray(len); input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    /**
     * Runs one shell command; returns its output, or null (daemon off, consent not given within [consentMs], error).
     * Call off the main thread.
     */
    fun shell(context: Context, command: String, consentMs: Int = 30_000, timeoutMs: Int = 20_000): String? = runCatching {
        val pair = keys(context.applicationContext)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
            socket.soTimeout = 3000
            val out = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream().buffered())
            write(out, Msg(A_CNXN, VERSION, MAX_DATA, "host::features=shell_v2,cmd\u0000".toByteArray()))
            var sentKey = false; var signed = false
            while (true) {
                val m = read(input)
                when (m.cmd) {
                    A_CNXN -> break
                    A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                        if (!signed) { signed = true; write(out, Msg(A_AUTH, AUTH_SIGNATURE, 0, sign(pair, m.data))) }
                        else if (!sentKey) {
                            sentKey = true
                            write(out, Msg(A_AUTH, AUTH_RSAPUBLICKEY, 0, androidPublicKey(pair.public as RSAPublicKey, "EZCH@tv")))
                            socket.soTimeout = consentMs // Android shows «Разрешить отладку?» now.
                        } else error("adb: key rejected")
                    }
                    else -> error("adb: unexpected ${m.cmd}")
                }
            }
            socket.soTimeout = timeoutMs
            val local = 1
            write(out, Msg(A_OPEN, local, 0, "shell:$command\u0000".toByteArray()))
            val result = ByteArrayOutputStream()
            while (true) {
                val m = read(input)
                when (m.cmd) {
                    A_OKAY -> Unit
                    A_WRTE -> { result.write(m.data); write(out, Msg(A_OKAY, local, m.arg0, ByteArray(0))) }
                    A_CLSE -> break
                }
            }
            lastError = null
            context.applicationContext.getSharedPreferences("ezch_local_adb", Context.MODE_PRIVATE).edit().putBoolean("granted", true).apply()
            result.toString("UTF-8")
        }
    }.getOrElse { lastError = it.javaClass.simpleName + ": " + it.message; null }
}
