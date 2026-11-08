package com.example.homeezch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBackdropTest {
    private fun rgb(p: Int) = Triple((p shr 16) and 255, (p shr 8) and 255, p and 255)

    @Test fun uniformImageStaysUniformAndOpaque() {
        val w = 20; val h = 10
        val src = IntArray(w * h) { 0xFF336699.toInt() }; val dst = IntArray(w * h)
        GlassBackdrop.boxPass(src, dst, w, h, 5, horizontal = true)
        GlassBackdrop.boxPass(dst, src, w, h, 5, horizontal = false)
        assertTrue(src.all { it == 0xFF336699.toInt() })
    }

    @Test fun blurSpreadsABrightLineAndKeepsEnergy() {
        val w = 31; val h = 1
        val src = IntArray(w) { if (it == 15) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }; val dst = IntArray(w)
        GlassBackdrop.boxPass(src, dst, w, h, 2, horizontal = true)
        val reds = dst.map { rgb(it).first }
        assertEquals(51, reds[15]); assertEquals(51, reds[13]); assertEquals(0, reds[12])
        assertTrue(dst.all { (it ushr 24) == 255 })
    }

    @Test fun tintIsSourceOver() {
        val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        GlassBackdrop.tint(px, GlassBackdrop.TINT)
        val a = (GlassBackdrop.TINT ushr 24) and 255
        assertEquals(0xB0, a)
        val (r0, _, _) = rgb(px[0]); val (r1, _, b1) = rgb(px[1])
        assertEquals((255 * (255 - a) + 0x0B * a) / 255, r0)
        assertEquals((0x0B * a) / 255, r1); assertEquals((0x26 * a) / 255, b1)
    }
}
