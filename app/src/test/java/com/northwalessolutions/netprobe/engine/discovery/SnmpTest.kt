package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class SnmpTest {
    private fun hex(s: String) = s.replace(Regex("\\s"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `get request encodes version community and three oids`() {
        val r = Snmp.buildGet("public", 0x5a5a)
        assertEquals(0x30, r[0].toInt())
        val text = String(r, Charsets.ISO_8859_1)
        assertTrue(text.contains("public"))
        // OID 1.3.6.1.2.1.1.1.0 encodes as 06 08 2b 06 01 02 01 01 01 00
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 1, 0) })
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 5, 0) })
        assertTrue(r.toList().windowed(10).any { it.map { b -> b.toInt() and 0xff } == listOf(6, 8, 0x2b, 6, 1, 2, 1, 1, 2, 0) })
    }

    // A hand-written (independent of the encoder) GetResponse: sysDescr "RouterOS RB750Gr3", sysName "gw",
    // sysObjectID 1.3.6.1.4.1.14988.1, request-id 0x5a5a. Lengths: varbinds 0x1d, 0x0e, 0x14; list 0x45; PDU 0x51; message 0x5e.
    // 14988 = 117 * 128 + 12, so its base-128 encoding is f5 0c.
    private val response = hex(
        "30 5e 02 01 01 04 06 70 75 62 6c 69 63 a2 51 02 02 5a 5a 02 01 00 02 01 00 30 45" +
        "30 1d 06 08 2b 06 01 02 01 01 01 00 04 11 52 6f 75 74 65 72 4f 53 20 52 42 37 35 30 47 72 33" +
        "30 0e 06 08 2b 06 01 02 01 01 05 00 04 02 67 77" +
        "30 14 06 08 2b 06 01 02 01 01 02 00 06 08 2b 06 01 04 01 f5 0c 01")

    @Test fun `parses a hand-written get response`() {
        val i = Snmp.parseResponse(response)!!
        assertEquals("RouterOS RB750Gr3", i.sysDescr); assertEquals("gw", i.sysName); assertEquals("1.3.6.1.4.1.14988.1", i.sysObjectId)
    }
    @Test fun `oid encodes multi-byte subidentifiers`() {
        assertArrayEquals(hex("06 08 2b 06 01 04 01 f5 0c 01"), Ber.oid(intArrayOf(1, 3, 6, 1, 4, 1, 14988, 1)))
    }

    @Test fun `parses a get response`() {
        val built = SnmpTestSupport.encodeResponse(requestId = 0x5a5a, descr = "RouterOS RB750Gr3", name = "gw", oid = intArrayOf(1,3,6,1,4,1,14988,1))
        val i = Snmp.parseResponse(built)!!
        assertEquals("RouterOS RB750Gr3", i.sysDescr); assertEquals("gw", i.sysName); assertEquals("1.3.6.1.4.1.14988.1", i.sysObjectId)
    }
    @Test fun `long form lengths are handled`() {
        val long = "x".repeat(300)
        val i = Snmp.parseResponse(SnmpTestSupport.encodeResponse(1, long, "n", intArrayOf(1,3,6,1)))!!
        assertEquals(300, i.sysDescr!!.length)
    }
    @Test fun `error status yields null`() {
        val b = SnmpTestSupport.encodeResponse(1, "d", "n", intArrayOf(1,3,6,1), errorStatus = 2)
        assertNull(Snmp.parseResponse(b))
    }
    @Test fun `garbage and truncation yield null`() {
        val good = SnmpTestSupport.encodeResponse(1, "RouterOS", "gw", intArrayOf(1,3,6,1))
        assertNull(Snmp.parseResponse(good.copyOf(good.size / 2)))
        assertNull(Snmp.parseResponse(ByteArray(0))); assertNull(Snmp.parseResponse(ByteArray(64) { -1 }))
        assertNull(Snmp.parseResponse(response.copyOf(20)))
    }
}
