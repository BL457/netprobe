package com.northwalessolutions.netprobe.engine.script

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ScriptBytesTest {
    /** Gives out bytes forever, so a reader that does not stop at the cap would never return. */
    private class Endless : InputStream() {
        var served = 0L
        override fun read(): Int { served++; return 'x'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte()); served += len; return len }
    }

    @Test fun `a stream up to the cap is read whole`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val r = ScriptBytes.readCapped(ByteArrayInputStream(bytes), 1000) as ScriptBytes.Read.Bytes
        assertArrayEquals(bytes, r.bytes)
        assertTrue(ScriptBytes.readCapped(ByteArrayInputStream(ByteArray(0)), 10) is ScriptBytes.Read.Bytes)
    }

    @Test fun `one byte over the cap is too large and the rest is not read`() {
        assertEquals(ScriptBytes.Read.TooLarge, ScriptBytes.readCapped(ByteArrayInputStream(ByteArray(1001)), 1000))
        val endless = Endless()
        assertEquals(ScriptBytes.Read.TooLarge, ScriptBytes.readCapped(endless))
        assertTrue(endless.served < ScriptBytes.MAX_IMPORT_BYTES + 16 * 1024)
        assertEquals(1024 * 1024, ScriptBytes.MAX_IMPORT_BYTES)
    }

    @Test fun `decoding is strict utf-8 without a byte order mark`() {
        assertEquals("caf\u00e9", ScriptBytes.decode("caf\u00e9".toByteArray()))
        assertEquals("tool", ScriptBytes.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "tool".toByteArray()))
        assertNull(ScriptBytes.decode(byteArrayOf(0x61, 0xC3.toByte(), 0x28)))
        assertNull(ScriptBytes.decode(byteArrayOf(0xFF.toByte())))
        assertEquals("", ScriptBytes.decode(ByteArray(0)))
    }

    @Test fun `sha256 is lower-case hex of the utf-8 bytes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ScriptBytes.sha256("abc"))
        assertEquals(ScriptBytes.sha256("caf\u00e9"), ScriptBytes.sha256("caf\u00e9".toByteArray(Charsets.UTF_8)))
    }
}
