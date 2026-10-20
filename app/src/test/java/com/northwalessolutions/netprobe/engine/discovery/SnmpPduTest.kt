package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** spec 6.4: the GETNEXT request bytes, and response parsing of every application type and exception. */
class SnmpPduTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `a getnext for sysDescr is exact`() {
        // SEQUENCE { INTEGER 1 (v2c), OCTET STRING "public", GetNextRequest { id 7, error 0, index 0, varbinds { { OID 1.3.6.1.2.1.1, NULL } } } }
        val expected = "3025" + "020101" + "0406" + "7075626c6963" + "a118" + "020107" + "020100" + "020100" + "300d" + "300b" + "0607" + "2b060102010101" + "0500"
        assertEquals(expected, hexOf(SnmpPdu.build(SnmpPdu.GETNEXT, listOf("1.3.6.1.2.1.1.1"), "public", 7)))
    }
    @Test fun `a get uses pdu a0`() {
        assertEquals(0xa0.toByte(), SnmpPdu.build(SnmpPdu.GET, listOf("1.3.6.1.2.1.1.1.0"), "public", 1)[13])
    }
    @Test fun `a response with every value type parses`() {
        val vb = { oid: String, value: ByteArray -> Ber.seq(Ber.oid(SnmpPdu.oidParts(oid)), value) }
        val varbinds = Ber.seq(
            vb("1.3.6.1.2.1.1.1.0", Ber.str("Linux gw")),
            vb("1.3.6.1.2.1.1.2.0", Ber.oid(intArrayOf(1, 3, 6, 1, 4, 1, 8072, 3, 2, 10))),
            vb("1.3.6.1.2.1.1.3.0", hex("43 05 00 ff ff ff ff")),                 // TimeTicks 4294967295: unsigned, never negative
            vb("1.3.6.1.2.1.4.20.1.1.10.0.3.1", hex("40 04 0a 00 03 01")),       // IpAddress 10.0.3.1
            vb("1.3.6.1.2.1.2.2.1.5.1", hex("42 04 3b 9a ca 00")),               // Gauge32 1000000000
            vb("1.3.6.1.2.1.2.2.1.10.1", hex("41 05 00 80 00 00 00")),           // Counter32 2147483648
            vb("1.3.6.1.2.1.2.2.1.3.1", Ber.int(6)),                              // INTEGER 6
            vb("1.3.6.1.2.1.2.2.1.6.1", Ber.tlv(0x04, hex("001122334455"))),     // ifPhysAddress raw bytes
            vb("1.3.6.1.2.1.9.0", hex("82 00")))                                  // endOfMibView
        val pdu = Ber.tlv(0xa2, Ber.int(9) + Ber.int(0) + Ber.int(0) + varbinds)
        val r = SnmpPdu.parse(Ber.seq(Ber.int(1), Ber.str("public"), pdu))!!
        assertEquals(9, r.requestId); assertEquals(0, r.errorStatus)
        val v = r.varbinds
        assertEquals("Linux gw", v[0].str); assertEquals("1.3.6.1.4.1.8072.3.2.10", v[1].str)
        assertEquals(4294967295L, v[2].long); assertEquals("10.0.3.1", v[3].str)
        assertEquals(1_000_000_000L, v[4].long); assertEquals(2_147_483_648L, v[5].long); assertEquals(6L, v[6].long)
        assertEquals("001122334455", hexOf(v[7].raw!!)); assertTrue(v[8].endOfMib); assertTrue(v[8].exception)
    }
    @Test fun `negative integers sign extend and garbage is null`() {
        val pdu = Ber.tlv(0xa2, Ber.int(1) + Ber.int(0) + Ber.int(0) + Ber.seq(Ber.seq(Ber.oid(intArrayOf(1, 3, 6, 1)), Ber.int(-2))))
        assertEquals(-2L, SnmpPdu.parse(Ber.seq(Ber.int(1), Ber.str("public"), pdu))!!.varbinds.single().long)
        assertNull(SnmpPdu.parse(hex("30 05 02 01")))
        assertNull(SnmpPdu.parse(ByteArray(0)))
    }

    // Hostile replies (spec 11.2): every length is checked against the buffer, OIDs are bounded, and nothing throws.
    private fun reply(varbinds: ByteArray) = Ber.seq(Ber.int(1), Ber.str("public"), Ber.tlv(0xa2, Ber.int(1) + Ber.int(0) + Ber.int(0) + varbinds))
    @Test fun `a varbind list longer than the datagram is null`() {
        val ok = reply(Ber.seq(Ber.seq(Ber.oid(intArrayOf(1, 3, 6, 1)), Ber.int(5))))
        val i = ok.size - 11                                   // the list's length byte: one varbind of 30 08 + 8 content bytes follows it
        assertEquals("300a", hexOf(ok.copyOfRange(i - 1, i + 1))); ok[i] = 0x7f
        assertNotNull(SnmpPdu.parse(reply(Ber.seq(Ber.seq(Ber.oid(intArrayOf(1, 3, 6, 1)), Ber.int(5))))))
        assertNull(SnmpPdu.parse(ok))
    }
    @Test fun `an oid with more than 128 arcs is null`() {
        assertNull(SnmpPdu.parse(reply(Ber.seq(Ber.seq(Ber.oid(IntArray(129) { if (it == 0) 1 else 3 }), Ber.int(1))))))
        assertNotNull(SnmpPdu.parse(reply(Ber.seq(Ber.seq(Ber.oid(IntArray(128) { if (it == 0) 1 else 3 }), Ber.int(1))))))
    }
    @Test fun `an oid arc wider than an int, an empty oid and an unterminated arc are null`() {
        val vb = { oidContent: String -> reply(Ber.seq(Ber.seq(Ber.tlv(0x06, hex(oidContent)), Ber.int(1)))) }
        assertNull(SnmpPdu.parse(vb("2b 06 01 90 80 80 80 80 00")))      // 2^32 + more: overflows an Int
        assertNull(SnmpPdu.parse(vb("")))
        assertNull(SnmpPdu.parse(vb("2b 06 81")))
        assertEquals("1.3.6.1.2147483647", SnmpPdu.parse(vb("2b 06 01 87 ff ff ff 7f"))!!.varbinds.single().oid)
    }
    @Test fun `a truncated value and a truncated datagram are null`() {
        val ok = reply(Ber.seq(Ber.seq(Ber.oid(intArrayOf(1, 3, 6, 1)), Ber.str("abcdef"))))
        for (n in 1 until ok.size) assertNull("cut at $n", SnmpPdu.parse(ok.copyOf(n)))
        assertNotNull(SnmpPdu.parse(ok))
    }
    @Test fun `a non response pdu is null`() {
        assertNull(SnmpPdu.parse(SnmpPdu.build(SnmpPdu.GETNEXT, listOf("1.3.6.1"), "public", 1)))
    }
}
