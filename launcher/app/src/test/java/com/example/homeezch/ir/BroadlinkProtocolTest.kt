package com.example.homeezch.ir

import com.example.homeezch.ir.BroadlinkProtocol.hex
import com.example.homeezch.ir.BroadlinkProtocol.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact vectors generated with the reference implementation (python-broadlink, Device.send_packet / scan /
 * auth / rmminib._send) for fixed inputs — the Kotlin port must produce the very same packets.
 */
class BroadlinkProtocolTest {
    private val mac = hex("f6e5d4c3b2a1")   // as found at 0x3A..0x3F of the hello reply

    @Test fun helloPacketMatchesReference() {
        val p = BroadlinkProtocol.helloPacket(byteArrayOf(192.toByte(), 168.toByte(), 1, 50), 40000, 2026, 11, 2, 14, 5, 1, 3)
        assertEquals("000000000000000003000000ea07050e1a01020b000000003201a8c0409c00005bc20000000006000000000000000000", toHex(p))
    }

    @Test fun rmSendPacketMatchesReference() {
        val payload = BroadlinkProtocol.rmPayload(BroadlinkProtocol.RM_SEND, byteArrayOf(0x26, 0x00, 0x01))
        val p = BroadlinkProtocol.commandPacket(0x520D, BroadlinkProtocol.CMD_RM, 0x9234, mac, 0x01020304, hex("00112233445566778899aabbccddeeff"), payload)
        assertEquals(
            "5aa5aa555aa5aa55000000000000000000000000000000000000000000000000e0d000000d526a003492f6e5d4c3b2a104030201dfbe0000" +
                "f90b0b4b5ae705e40b0987e47c761332", toHex(p))
    }

    @Test fun authPacketMatchesReference() {
        val p = BroadlinkProtocol.commandPacket(0x520D, BroadlinkProtocol.CMD_AUTH, 0x8001, mac, 0, BroadlinkProtocol.INIT_KEY, BroadlinkProtocol.authPayload())
        assertEquals(
            "5aa5aa555aa5aa55000000000000000000000000000000000000000000000000e9f100000d5265000180f6e5d4c3b2a100000000b2c30000" +
                "453452e7f92eda958344930835ef9a6d93b0b6da60530408ebba79410b080296f9f7cd7779b46f2513e2c5bbd4450e907fa1ba8fc5e0" +
                "169776e2620824fff3f85f6f64f7120b1f724ff1b048b76e3e30", toHex(p))
    }

    @Test fun encryptDecryptRoundTrip() {
        val key = hex("00112233445566778899aabbccddeeff")
        val plain = BroadlinkProtocol.rmPayload(BroadlinkProtocol.RM_CHECK_DATA)
        val back = BroadlinkProtocol.decrypt(key, BroadlinkProtocol.encrypt(key, plain))
        assertArrayEquals(plain, back.copyOf(plain.size))
    }

    @Test fun rmDataCutsFramingAndPadding() {
        val learned = byteArrayOf(0x26, 0x00, 0x0A, 0x01, 0x02)
        val framed = BroadlinkProtocol.rmPayload(BroadlinkProtocol.RM_CHECK_DATA, learned)
        val padded = framed.copyOf(32)                       // AES zero padding as it arrives
        assertArrayEquals(learned, BroadlinkProtocol.rmData(padded))
        assertNull(BroadlinkProtocol.rmData(ByteArray(3)))
    }

    @Test fun parseHelloReadsTypeMacNameAndLock() {
        val r = ByteArray(0x80)
        r[0x34] = 0x0D; r[0x35] = 0x52
        mac.copyInto(r, 0x3A)
        "Гостиная".toByteArray().copyInto(r, 0x40)
        r[0x7F] = 1
        val h = BroadlinkProtocol.parseHello(r)!!
        assertEquals(0x520D, h.devType)
        assertEquals("Гостиная", h.name)
        assertTrue(h.locked)
        assertEquals("A1:B2:C3:D4:E5:F6", BroadlinkProtocol.macText(h.mac))
        assertEquals("RM4C mini", BroadlinkProtocol.RM_FRAMED[h.devType])
        assertNull(BroadlinkProtocol.parseHello(ByteArray(0x20)))
    }

    @Test fun errorsAndChecksum() {
        val r = ByteArray(0x38)
        r[0x22] = 0xFB.toByte(); r[0x23] = 0xFF.toByte()       // -5
        assertEquals(-5, BroadlinkProtocol.errorCode(r))
        assertTrue(BroadlinkProtocol.isNoDataYet(-5))
        assertFalse(BroadlinkProtocol.isNoDataYet(-1))
        var sum = 0xBEAF
        for (i in r.indices) if (i != 0x20 && i != 0x21) sum += r[i].toInt() and 0xFF
        r[0x20] = (sum and 0xFF).toByte(); r[0x21] = ((sum shr 8) and 0xFF).toByte()
        assertTrue(BroadlinkProtocol.responseValid(r))
        r[0x30] = 1
        assertFalse(BroadlinkProtocol.responseValid(r))
    }

    @Test fun unknownTypesAreNotTreatedAsCompatible() {
        assertNull(BroadlinkProtocol.RM_FRAMED[0x2737])   // old RM mini: different framing, not supported
        assertNull(BroadlinkProtocol.RM_FRAMED[0x0000])
    }
}
