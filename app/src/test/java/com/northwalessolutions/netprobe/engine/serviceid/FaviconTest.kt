package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

/** The favicon hash of Phase 3E spec 5: MurmurHash3 x86 32-bit, seed 0, over the MIME-style base64 text, as a signed 32-bit integer. */
class FaviconTest {
    /** An independent, slow MurmurHash3_x86_32 written from the algorithm's published constants with Long arithmetic. */
    private fun reference(data: ByteArray, seed: Long = 0): Int {
        val m32 = 0xffffffffL
        fun rotl(x: Long, r: Int) = ((x shl r) or (x ushr (32 - r))) and m32
        var h = seed and m32
        var i = 0
        while (i + 4 <= data.size) {
            var k = 0L
            for (j in 0 until 4) k = k or ((data[i + j].toLong() and 0xff) shl (8 * j))
            k = (k * 0xcc9e2d51L) and m32; k = rotl(k, 15); k = (k * 0x1b873593L) and m32
            h = h xor k; h = rotl(h, 13); h = (h * 5 + 0xe6546b64L) and m32
            i += 4
        }
        var k = 0L
        for (j in data.size - i - 1 downTo 0) k = (k shl 8) or (data[i + j].toLong() and 0xff)
        if (data.size - i > 0) { k = (k * 0xcc9e2d51L) and m32; k = rotl(k, 15); k = (k * 0x1b873593L) and m32; h = h xor k }
        h = h xor data.size.toLong()
        h = h xor (h ushr 16); h = (h * 0x85ebca6bL) and m32
        h = h xor (h ushr 13); h = (h * 0xc2b2ae35L) and m32
        h = h xor (h ushr 16)
        return h.toInt()
    }

    @Test fun `hello with seed 0 is the published value`() {
        assertEquals(0x248bfa47, Murmur3.hash32("hello".toByteArray()))
        assertEquals(613153351, Murmur3.hash32("hello".toByteArray()))
    }
    @Test fun `the empty string and the seeded empty string are the published values`() {
        assertEquals(0, Murmur3.hash32(ByteArray(0)))
        assertEquals(0x514E28B7, Murmur3.hash32(ByteArray(0), 1))
    }
    @Test fun `the core agrees with the independent reference at every tail length`() {
        val text = "The quick brown fox jumps over the lazy dog".toByteArray()
        for (n in 0..text.size) assertEquals("length $n", reference(text.copyOf(n)), Murmur3.hash32(text.copyOf(n)))
        assertEquals(0x2e4ff723, Murmur3.hash32(text))      // the published vector for this sentence, seed 0
    }
    @Test fun `base64 breaks after every 76 characters and ends with a newline`() {
        val body = ByteArray(100) { it.toByte() }                    // 136 base64 characters
        val lines = Favicon.shodanText(body).split('\n')
        assertEquals(3, lines.size); assertEquals(76, lines[0].length); assertEquals(60, lines[1].length); assertEquals("", lines[2])
        assertEquals("AAEC\n", Favicon.shodanText(byteArrayOf(0, 1, 2)))
    }
    @Test fun `the favicon hash is the signed hash of the text`() {
        val body = ByteArray(300) { (it * 7).toByte() }
        assertEquals(reference(Favicon.shodanText(body).toByteArray(Charsets.US_ASCII)), Favicon.shodanHash(body))
        // A result with the top bit set prints negative, as Shodan prints it.
        val negatives = (1..40).map { n -> Favicon.shodanHash(ByteArray(n) { (it * 31 + n).toByte() }) }
        assertTrue(negatives.any { it < 0 }); assertTrue(negatives.any { it > 0 })
    }
}
