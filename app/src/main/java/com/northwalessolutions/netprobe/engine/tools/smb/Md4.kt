package com.northwalessolutions.netprobe.engine.tools.smb

/** MD4 per RFC 1320, for NTOWFv2 (spec 6.3). Verified against the RFC 1320 A.5 suite. */
object Md4 {
    private const val BLOCK = 64
    private const val LENGTH_AT = 56
    private const val R2 = 0x5a827999
    private const val R3 = 0x6ed9eba1
    private val ORDER2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val ORDER3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
    /** The register each step updates, cycling a, d, c, b (RFC 1320 3.4). */
    private val TARGET = intArrayOf(0, 3, 2, 1)
    private val S1 = intArrayOf(3, 7, 11, 19); private val S2 = intArrayOf(3, 5, 9, 13); private val S3 = intArrayOf(3, 9, 11, 15)

    fun digest(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val pad = ((LENGTH_AT - (input.size + 1) % BLOCK) + BLOCK) % BLOCK
        val msg = input + byteArrayOf(0x80.toByte()) + ByteArray(pad) + ByteArray(8) { ((bitLen ushr (8 * it)) and 0xff).toByte() }
        val h = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
        val x = IntArray(16)
        for (block in 0 until msg.size / BLOCK) {
            for (i in 0 until 16) {
                val o = block * BLOCK + i * 4
                x[i] = (msg[o].toInt() and 0xff) or ((msg[o + 1].toInt() and 0xff) shl 8) or ((msg[o + 2].toInt() and 0xff) shl 16) or ((msg[o + 3].toInt() and 0xff) shl 24)
            }
            val v = h.copyOf()     // a, b, c, d
            // Each step updates one of a, d, c, b in turn (index 0, 3, 2, 1) from the other three.
            fun step(round: Int, i: Int, k: Int, s: Int) {
                val t = TARGET[i % 4]
                val b = v[(t + 1) % 4]; val c = v[(t + 2) % 4]; val d = v[(t + 3) % 4]
                val f = when (round) { 1 -> (b and c) or (b.inv() and d); 2 -> (b and c) or (b and d) or (c and d); else -> b xor c xor d }
                val add = when (round) { 1 -> 0; 2 -> R2; else -> R3 }
                v[t] = Integer.rotateLeft(v[t] + f + x[k] + add, s)
            }
            for (i in 0 until 16) step(1, i, i, S1[i % 4])
            for (i in 0 until 16) step(2, i, ORDER2[i], S2[i % 4])
            for (i in 0 until 16) step(3, i, ORDER3[i], S3[i % 4])
            for (i in 0 until 4) h[i] += v[i]
        }
        return ByteArray(16) { i -> ((h[i / 4] ushr (8 * (i % 4))) and 0xff).toByte() }
    }
}
