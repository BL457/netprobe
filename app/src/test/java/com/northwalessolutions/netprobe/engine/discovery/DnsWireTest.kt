package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** DnsWire keeps DeviceInfoTxt's behaviour; the label limit applies only when a caller passes one (the listener). */
class DnsWireTest {
    @Test fun `a plain name reads with its end position`() {
        val b = MdnsTestPackets.name("chnas.local")
        assertEquals("chnas.local" to b.size, DnsWire.readName(b, 0))
    }
    @Test fun `with no limit a long name still reads, with a limit it is refused`() {
        val long = MdnsTestPackets.name((1..200).joinToString(".") { "a" })
        assertNotNull(DnsWire.readName(long, 0))
        assertNull(DnsWire.readName(long, 0, maxLabels = 128))
    }
    @Test fun `a pointer loop and a reserved label type are refused`() {
        assertNull(DnsWire.readName(MdnsTestPackets.pointer(0), 0))
        assertNull(DnsWire.readName(byteArrayOf(0x40, 0x00), 0))
    }
    @Test fun `txt rdata keeps the first value of a key and lower-cases keys`() {
        val t = MdnsTestPackets.txt("Model=DS220+", "model=other", "noequals")
        assertEquals(mapOf("model" to "DS220+"), DnsWire.parseTxtRdata(t, 0, t.size))
    }
}
