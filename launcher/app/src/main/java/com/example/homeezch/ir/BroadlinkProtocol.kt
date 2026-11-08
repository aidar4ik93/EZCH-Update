package com.example.homeezch.ir

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BroadLink local protocol (UDP port 80), as published by the open python-broadlink project and used by
 * Home Assistant: hello/discovery, AES-128-CBC authorization (command 0x65) and the RM framing (command 0x6A)
 * used by RM mini 3 (B) and the RM4 family: [len+4 (u16 LE)][command (u32 LE)][data], answers carry the same
 * length prefix and the data from byte 6. Pure functions only — unit-tested on the JVM.
 *
 * Old RM mini / RM2 (no length prefix) is a different framing and is deliberately NOT implemented (ТЗ §3.2).
 */
object BroadlinkProtocol {
    val INIT_KEY: ByteArray = hex("097628343fe99e23765c1513accf8b02")
    val INIT_IV: ByteArray = hex("562e17996d093d28ddb3ba695a2e6f58")
    const val PORT = 80
    const val CMD_AUTH = 0x65
    const val CMD_RM = 0x6A
    const val RM_SEND = 0x02
    const val RM_ENTER_LEARNING = 0x03
    const val RM_CHECK_DATA = 0x04

    /** Device types with the RM (length-prefixed) framing. Names as the vendor reports them. */
    val RM_FRAMED: Map<Int, String> = mapOf(
        0x5F36 to "RM mini 3", 0x6507 to "RM mini 3", 0x6508 to "RM mini 3",
        0x51DA to "RM4 mini", 0x5209 to "RM4 TV mate", 0x520C to "RM4 mini", 0x520D to "RM4C mini",
        0x5211 to "RM4C mate", 0x5212 to "RM4 TV mate", 0x5216 to "RM4 mini", 0x521C to "RM4 mini",
        0x6070 to "RM4C mini", 0x610E to "RM4 mini", 0x610F to "RM4C mini", 0x62BC to "RM4 mini",
        0x62BE to "RM4C mini", 0x6364 to "RM4S", 0x648D to "RM4 mini", 0x6539 to "RM4C mini", 0x653A to "RM4 mini",
        0x520B to "RM4 pro", 0x5213 to "RM4 pro", 0x5218 to "RM4C pro", 0x6026 to "RM4 pro", 0x6184 to "RM4C pro",
        0x61A2 to "RM4 pro", 0x649B to "RM4 pro", 0x653C to "RM4 pro",
    )

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun checksum(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var sum = 0xBEAF
        for (i in from until to) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFFFF
    }
    private fun putLe16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun putLe32(b: ByteArray, at: Int, v: Int) { for (i in 0..3) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
    fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, key, pad16(plain))
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray = if (data.isEmpty()) data else aes(Cipher.DECRYPT_MODE, key, data.copyOf(data.size - data.size % 16))
    private fun pad16(p: ByteArray) = p.copyOf(p.size + (16 - p.size % 16) % 16)
    private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(INIT_IV)) }.doFinal(data)

    /** Hello packet (0x30 bytes) for discovery: local address/port and the time, checksum at 0x20. */
    fun helloPacket(localIp: ByteArray, localPort: Int, year: Int, month: Int, day: Int, hour: Int, minute: Int, isoWeekday: Int, tzHours: Int): ByteArray {
        val p = ByteArray(0x30)
        putLe32(p, 0x08, tzHours)
        putLe16(p, 0x0C, year); p[0x0E] = minute.toByte(); p[0x0F] = hour.toByte(); p[0x10] = (year % 100).toByte()
        p[0x11] = isoWeekday.toByte(); p[0x12] = day.toByte(); p[0x13] = month.toByte()
        for (i in 0..3) p[0x18 + i] = localIp[3 - i]                // address bytes reversed, as the device expects
        putLe16(p, 0x1C, localPort)
        p[0x26] = 6
        putLe16(p, 0x20, checksum(p))
        return p
    }

    data class Hello(val devType: Int, val mac: ByteArray, val name: String, val locked: Boolean)
    /** Parses a discovery answer; null when it is not a BroadLink hello reply. */
    fun parseHello(resp: ByteArray): Hello? {
        if (resp.size < 0x40) return null
        val name = if (resp.size > 0x40) String(resp.copyOfRange(0x40, minOf(resp.size, 0x7F)).takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8) else ""
        return Hello(le16(resp, 0x34), resp.copyOfRange(0x3A, 0x40), name.trim(), resp.size > 0x7F && resp[0x7F].toInt() != 0)
    }

    /** One encrypted command packet. [mac] = the 6 bytes exactly as in the hello reply (0x3A..0x3F). */
    fun commandPacket(devType: Int, command: Int, count: Int, mac: ByteArray, id: Int, key: ByteArray, payload: ByteArray): ByteArray {
        val head = ByteArray(0x38)
        hex("5aa5aa555aa5aa55").copyInto(head, 0)
        putLe16(head, 0x24, devType); putLe16(head, 0x26, command); putLe16(head, 0x28, count)
        mac.copyInto(head, 0x2A, 0, 6); putLe32(head, 0x30, id)
        putLe16(head, 0x34, checksum(payload))
        val packet = head + encrypt(key, payload)
        putLe16(packet, 0x20, checksum(packet))
        return packet
    }

    /** Error code of an answer (signed 16-bit at 0x22). 0 = OK. */
    fun errorCode(resp: ByteArray): Int = if (resp.size < 0x24) Int.MIN_VALUE else le16(resp, 0x22).toShort().toInt()
    fun errorText(code: Int): String = when (code) {
        0 -> "OK"; -1 -> "ошибка авторизации"; -2 -> "сессия закрыта контроллером"; -3 -> "контроллер не в сети"
        -4 -> "команда не поддерживается"; -5 -> "память контроллера заполнена/данных нет"; -6 -> "неверная структура"
        -7 -> "ключ управления устарел"; -8 -> "ошибка отправки"; -9 -> "ошибка записи"; -10 -> "ошибка чтения"
        Int.MIN_VALUE -> "неполный ответ"; else -> "ошибка контроллера $code"
    }
    /** check_data during learning: these codes only mean «сигнал ещё не принят» (as python-broadlink / Home Assistant treat them). */
    fun isNoDataYet(code: Int) = code == -5 || code == -10

    /** Verifies the checksum of an answer (0x20..0x21 excluded from the sum). */
    fun responseValid(resp: ByteArray): Boolean {
        if (resp.size < 0x30) return false
        var sum = 0xBEAF
        for (i in resp.indices) if (i != 0x20 && i != 0x21) sum += resp[i].toInt() and 0xFF
        return (sum and 0xFFFF) == le16(resp, 0x20)
    }

    /** Auth payload (0x50 bytes) for command 0x65. */
    fun authPayload(): ByteArray = ByteArray(0x50).also { p ->
        for (i in 0x04 until 0x14) p[i] = 0x31
        p[0x1E] = 0x01; p[0x2D] = 0x01
        "Test 1".toByteArray().copyInto(p, 0x30)
    }
    /** Auth answer → (session id, session key). */
    fun parseAuth(decrypted: ByteArray): Pair<Int, ByteArray>? =
        if (decrypted.size < 0x14) null else le32(decrypted, 0) to decrypted.copyOfRange(0x04, 0x14)

    /** RM framed request: [len(data)+4 : u16][command : u32][data]. */
    fun rmPayload(command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val p = ByteArray(6 + data.size)
        putLe16(p, 0, data.size + 4); putLe32(p, 2, command)
        data.copyInto(p, 6)
        return p
    }
    /** RM framed answer → data (bytes 6 .. len+2). */
    fun rmData(decrypted: ByteArray): ByteArray? {
        if (decrypted.size < 6) return null
        val len = le16(decrypted, 0)
        val end = minOf(decrypted.size, len + 2)
        return if (end < 6) ByteArray(0) else decrypted.copyOfRange(6, end)
    }

    fun macText(mac: ByteArray) = mac.reversed().joinToString(":") { "%02X".format(it) }
}
