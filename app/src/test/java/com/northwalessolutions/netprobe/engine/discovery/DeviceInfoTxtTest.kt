package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DeviceInfoTxtTest {
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun name(vararg labels: String): ByteArray {
        val o = ByteArrayOutputStream()
        for (l in labels) { val b = l.toByteArray(); o.write(b.size); o.write(b) }
        o.write(0); return o.toByteArray()
    }
    private fun txt(kv: List<String>): ByteArray {
        val o = ByteArrayOutputStream()
        for (s in kv) { val b = s.toByteArray(); o.write(b.size); o.write(b) }
        return o.toByteArray()
    }
    /** A legacy-unicast style reply: header, echoed question, one TXT answer (owner name plain or a pointer to the question). */
    private fun response(kv: List<String>, owner: Array<String> = arrayOf("Davids-MacBook", "_device-info", "_tcp", "local"),
                         compressed: Boolean = false, type: Int = 16): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(u16(0)); o.write(u16(0x8400)); o.write(u16(1)); o.write(u16(1)); o.write(u16(0)); o.write(u16(0))
        val q = name(*owner)
        o.write(q); o.write(u16(16)); o.write(u16(0x8001))
        if (compressed) o.write(byteArrayOf(0xC0.toByte(), 12)) else o.write(q)
        o.write(u16(type)); o.write(u16(0x8001)); o.write(byteArrayOf(0, 0, 0x11, 0x94.toByte()))
        val rdata = txt(kv); o.write(u16(rdata.size)); o.write(rdata)
        return o.toByteArray()
    }

    @Test fun `query packet is a TXT question with the QU bit`() {
        val q = DeviceInfoTxt.buildQuery("Davids-MacBook")!!
        assertEquals(14 + 42, q.size)
        assertEquals(1, q[5].toInt())                               // QDCOUNT 1
        assertEquals(14, q[12].toInt())                             // first label length
        assertEquals("Davids-MacBook", String(q, 13, 14))
        assertEquals(12, q[27].toInt()); assertEquals("_device-info", String(q, 28, 12))
        assertEquals(0x00, q[q.size - 4].toInt()); assertEquals(16, q[q.size - 3].toInt())   // QTYPE TXT
        assertEquals(0x80, q[q.size - 2].toInt() and 0xff); assertEquals(1, q[q.size - 1].toInt())  // QCLASS IN + QU
    }
    @Test fun `query refuses an empty or over-long instance label`() {
        assertNull(DeviceInfoTxt.buildQuery("")); assertNull(DeviceInfoTxt.buildQuery("x".repeat(64)))
    }
    @Test fun `parses model and osxvers from a plain answer`() {
        val m = DeviceInfoTxt.parseTxtResponse(response(listOf("model=MacBookPro18,3", "osxvers=23")))!!
        assertEquals("MacBookPro18,3", m["model"]); assertEquals("23", m["osxvers"])
    }
    @Test fun `parses an answer whose owner name is a compression pointer`() {
        assertEquals("iMac21,1", DeviceInfoTxt.parseTxtResponse(response(listOf("model=iMac21,1"), compressed = true))!!["model"])
    }
    @Test fun `ignores other owner names and other record types`() {
        assertNull(DeviceInfoTxt.parseTxtResponse(response(listOf("model=X"), owner = arrayOf("Mac", "_airplay", "_tcp", "local"))))
        assertNull(DeviceInfoTxt.parseTxtResponse(response(listOf("model=X"), type = 1)))
    }
    @Test fun `truncated garbage and pointer loops return null`() {
        val ok = response(listOf("model=MacBookPro18,3"))
        assertNull(DeviceInfoTxt.parseTxtResponse(ok.copyOf(30)))
        assertNull(DeviceInfoTxt.parseTxtResponse(ByteArray(0)))
        assertNull(DeviceInfoTxt.parseTxtResponse(ByteArray(100) { 0x7f }))
        val loop = ByteArrayOutputStream().apply {
            write(u16(0)); write(u16(0x8400)); write(u16(0)); write(u16(1)); write(u16(0)); write(u16(0))
            write(byteArrayOf(0xC0.toByte(), 12)); write(ByteArray(10))
        }.toByteArray()
        assertNull(DeviceInfoTxt.parseTxtResponse(loop))
    }
}
