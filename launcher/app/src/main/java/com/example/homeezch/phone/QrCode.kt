package com.example.homeezch.phone

/**
 * Minimal QR Code encoder (byte mode, error correction M, versions 1–10, automatic mask), enough for
 * a LAN address with a pairing key. Pure Kotlin, no dependencies; follows ISO/IEC 18004 (algorithm after
 * Project Nayuki's reference implementation, MIT). [modules][y][x] == true is a dark module.
 */
class QrCode private constructor(val version: Int) {
    val size = version * 4 + 17
    val modules = Array(size) { BooleanArray(size) }
    private val function = Array(size) { BooleanArray(size) }

    companion object {
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        fun encode(text: String): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..10).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + bytes.size * 8 <= dataCodewords(v) * 8 }
                ?: throw IllegalArgumentException("Text too long for QR v10-M")
            val bits = ArrayList<Boolean>()
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
            put(4, 4); put(bytes.size, if (version < 10) 8 else 16)
            bytes.forEach { put(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
            val data = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 } }
            val qr = QrCode(version)
            qr.drawFunctionPatterns()
            qr.drawCodewords(qr.addEccAndInterleave(data))
            var best = 0; var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                qr.applyMask(mask); qr.drawFormatBits(mask)
                val p = qr.penalty()
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                qr.applyMask(mask) // undo (XOR)
            }
            qr.applyMask(best); qr.drawFormatBits(best)
            return qr
        }

        private fun rawModules(v: Int): Int {
            var r = (16 * v + 128) * v + 64
            if (v >= 2) { val n = v / 7 + 2; r -= (25 * n - 10) * n - 55; if (v >= 7) r -= 36 }
            return r
        }
        internal fun dataCodewords(v: Int) = rawModules(v) / 8 - ECC_PER_BLOCK[v] * BLOCKS[v]

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
            return z
        }
        private fun rsDivisor(degree: Int): IntArray {
            val r = IntArray(degree); r[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in r.indices) { r[j] = gfMul(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
                root = gfMul(root, 0x02)
            }
            return r
        }
        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val r = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor r[0]
                System.arraycopy(r, 1, r, 0, r.size - 1); r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
            }
            return r
        }
    }

    private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        val align = alignmentPositions()
        val n = align.size
        for (i in 0 until n) for (j in 0 until n) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        drawFormatBits(0)
        if (version >= 7) {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3; val b = i / 3
                set(a, b, bit); set(b, a, bit)
            }
        }
    }
    private fun finder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)); val xx = x + dx; val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
        }
    }
    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val n = version / 7 + 2
        val step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
        val r = IntArray(n); r[0] = 6
        var pos = size - 7
        for (i in n - 1 downTo 1) { r[i] = pos; pos -= step }
        return r
    }
    private fun drawFormatBits(mask: Int) {
        val data = (0 shl 3) or mask // error correction level M = 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }
    private fun addEccAndInterleave(data: IntArray): IntArray {
        val blocks = BLOCKS[version]; val eccLen = ECC_PER_BLOCK[version]
        val raw = rawModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLen = raw / blocks
        val divisor = rsDivisor(eccLen)
        val list = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until blocks) {
            val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + len); k += len
            val ecc = rsRemainder(dat, divisor)
            val block = IntArray(shortLen + 1)
            System.arraycopy(dat, 0, block, 0, dat.size)
            // Short blocks carry a placeholder byte at index shortLen-eccLen, skipped while interleaving.
            System.arraycopy(ecc, 0, block, block.size - eccLen, eccLen)
            list += block
        }
        val out = ArrayList<Int>()
        for (i in 0 until shortLen + 1) for (j in 0 until blocks) {
            if (i != shortLen - eccLen || j >= shortBlocks) out += list[j][i]
        }
        return out.toIntArray()
    }
    private fun drawCodewords(data: IntArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x] && i < data.size * 8) {
                    modules[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                    i++
                }
            }
            right -= 2
        }
    }
    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
        }
    }
    /** ISO penalty (runs, 2×2 blocks, finder-like patterns, balance); only picks the most scannable mask. */
    private fun penalty(): Int {
        var p = 0
        fun line(get: (Int) -> Boolean) {
            var run = 1
            for (i in 1 until size) {
                if (get(i) == get(i - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
            }
            val s = (0 until size).map { if (get(it)) '1' else '0' }.joinToString("")
            p += 40 * (Regex("(?=10111010000)").findAll(s).count() + Regex("(?=00001011101)").findAll(s).count())
        }
        for (y in 0 until size) line { modules[y][it] }
        for (x in 0 until size) line { modules[it][x] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        p += k * 10
        return p
    }
}
