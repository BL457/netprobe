package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class NetBiosTest {
    private fun hex(s: String) = s.replace(Regex("[ ]+"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun name16(n: String, suffix: Int) = n.padEnd(15).toByteArray(Charsets.US_ASCII).map { "%02x".format(it) }.joinToString("") + "%02x".format(suffix)

    // Header: txid 1234, flags 8400 (response, AA), qd 0, an 1, ns 0, ar 0. Then RR for "*".
    private val response = hex(
        "1234 8400 0000 0001 0000 0000" +
        "20" + "43 4b" + "41".repeat(30) + "00" +   // name: CK + 30 A's
        "0021 0001 00000000" +
        "0065" +                                      // RDLENGTH: 1 + 3*18 + 46 = 101 = 0x65
        "03" +
        name16("DESKTOP-ABC", 0x00) + "0400" +        // unique, active
        name16("WORKGROUP", 0x00) + "8400" +          // group
        name16("DESKTOP-ABC", 0x20) + "0400" +        // file server service
        "001122334455" + "00".repeat(40))             // unit id (MAC) + stats

    @Test fun `request has the right length and query type`() {
        val r = NetBios.buildNodeStatusRequest(0x1234)
        assertEquals(50, r.size)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x20, r[12].toInt()); assertEquals(0x43, r[13].toInt()); assertEquals(0x4b, r[14].toInt())
        assertEquals(0x00, r[46].toInt()); assertEquals(0x21, r[47].toInt()); assertEquals(0x01, r[49].toInt())
    }
    @Test fun `parses names and mac`() {
        val i = NetBios.parseNodeStatus(response)!!
        assertEquals("DESKTOP-ABC", i.computerName); assertEquals("WORKGROUP", i.workgroup); assertEquals("00:11:22:33:44:55", i.mac)
    }
    @Test fun `all zero mac is reported as null`() {
        val r = response.copyOf(); for (k in 0 until 6) r[r.size - 46 + k] = 0
        assertNull(NetBios.parseNodeStatus(r)!!.mac)
    }
    @Test fun `truncated and garbage input return null`() {
        assertNull(NetBios.parseNodeStatus(response.copyOf(60)))
        assertNull(NetBios.parseNodeStatus(ByteArray(0)))
        assertNull(NetBios.parseNodeStatus(ByteArray(200) { 0x7f }))
    }
}
