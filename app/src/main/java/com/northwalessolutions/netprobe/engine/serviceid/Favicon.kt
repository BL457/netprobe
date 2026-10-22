package com.northwalessolutions.netprobe.engine.serviceid

import java.util.Base64

/** MurmurHash3, x86 32-bit variant (Austin Appleby, public domain), written out here so no dependency is needed. */
object Murmur3 {
    private const val C1 = -0x3361d2af      // 0xcc9e2d51
    private const val C2 = 0x1b873593
    private const val N = -0x19ab949c       // 0xe6546b64

    fun hash32(data: ByteArray, seed: Int = 0): Int {
        var h = seed
        val blocks = data.size / 4
        for (i in 0 until blocks) {
            val o = i * 4
            var k = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or ((data[o + 3].toInt() and 0xff) shl 24)
            k *= C1; k = Integer.rotateLeft(k, 15); k *= C2
            h = h xor k; h = Integer.rotateLeft(h, 13); h = h * 5 + N
        }
        var k1 = 0
        val tail = blocks * 4
        when (data.size and 3) {
            3 -> { k1 = k1 xor ((data[tail + 2].toInt() and 0xff) shl 16); k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tail].toInt() and 0xff) }
            2 -> { k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8); k1 = k1 xor (data[tail].toInt() and 0xff) }
            1 -> k1 = k1 xor (data[tail].toInt() and 0xff)
        }
        if (data.size and 3 != 0) { k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h = h xor k1 }
        h = h xor data.size
        h = h xor (h ushr 16); h *= -0x7a143595      // 0x85ebca6b
        h = h xor (h ushr 13); h *= -0x3d4d51cb      // 0xc2b2ae35
        h = h xor (h ushr 16)
        return h
    }
}

/** The favicon hash Shodan indexes by (spec 5). */
object Favicon {
    private const val LINE = 76

    /** Standard base64 with a newline after every 76 characters and at the end, as Python's base64.encodebytes writes it. */
    fun shodanText(body: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(body)
        val sb = StringBuilder()
        var i = 0
        while (i < b64.length) { sb.append(b64, i, minOf(i + LINE, b64.length)).append('\n'); i += LINE }
        return sb.toString()
    }

    /** MurmurHash3 x86 32-bit, seed 0, of [shodanText], as the signed 32-bit integer Shodan prints. */
    fun shodanHash(body: ByteArray): Int = Murmur3.hash32(shodanText(body).toByteArray(Charsets.US_ASCII))
}
