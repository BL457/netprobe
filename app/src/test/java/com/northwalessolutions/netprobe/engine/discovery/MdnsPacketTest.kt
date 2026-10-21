package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.A
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.Builder
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.PTR
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.RR_FIXED
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.SRV
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.announce
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** 3C spec 8.1 item 16 (G4, Review Focus 4): authored packets, hostile ones included. */
class MdnsPacketTest {
    @Test fun `an announcement gives one record with its type, port, txt, address and link-local v6`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+", "path=/"), v6 = "fe80::211:32ff:fe12:3456").build()
        val r = MdnsPacket.parse(p).single()
        assertEquals("10.0.3.59", r.ip.hostAddress); assertEquals("CHNAS", r.instanceName); assertEquals("_http._tcp", r.type); assertEquals(5000, r.port)
        assertEquals(mapOf("model" to "DS220+", "path" to "/"), r.txt)
        assertEquals(listOf(InetAddress.getByName("fe80::211:32ff:fe12:3456").hostAddress), r.ipv6)
    }
    @Test fun `a global v6 address is not kept as link-local`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, v6 = "2001:db8::5").build()
        assertTrue(MdnsPacket.parse(p).single().ipv6.isEmpty())
    }
    @Test fun `a query gives nothing`() {
        val q = announce(Builder(response = false).question("_http._tcp.local", PTR), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000).build()
        assertTrue(MdnsPacket.parse(q).isEmpty())
    }
    @Test fun `an A record with no service gives nothing, so a name-only sighting is dropped`() {
        assertTrue(MdnsPacket.parse(Builder().record("printer.local", A, MdnsTestPackets.a("10.0.3.70")).build()).isEmpty())
    }
    @Test fun `a PTR whose SRV target has no A record gives nothing`() {
        val b = Builder().record("_ipp._tcp.local", PTR, MdnsTestPackets.name("Office._ipp._tcp.local"))
            .record("Office._ipp._tcp.local", SRV, MdnsTestPackets.srv(631, MdnsTestPackets.name("office.local")))
        assertTrue(MdnsPacket.parse(b.build()).isEmpty())
    }
    @Test fun `compression pointers are followed`() {
        val b = Builder()
        val typeAt = b.offset
        val typeName = MdnsTestPackets.name("_ipp._tcp.local")
        b.record(typeName, PTR, MdnsTestPackets.nameThenPointer("Office Printer", typeAt))
        val instanceAt = typeAt + typeName.size + RR_FIXED
        val localAt = typeAt + "_ipp".length + 1 + "_tcp".length + 1
        b.record(MdnsTestPackets.pointer(instanceAt), SRV, MdnsTestPackets.srv(631, MdnsTestPackets.nameThenPointer("office", localAt)))
        b.record(MdnsTestPackets.nameThenPointer("office", localAt), A, MdnsTestPackets.a("10.0.3.80"))
        val r = MdnsPacket.parse(b.build()).single()
        assertEquals("Office Printer", r.instanceName); assertEquals("_ipp._tcp", r.type); assertEquals(631, r.port); assertEquals("10.0.3.80", r.ip.hostAddress)
    }
    @Test fun `truncated and looping packets return empty without throwing`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+")).build()
        for (cut in 0 until p.size) assertTrue("cut at $cut", MdnsPacket.parse(p.copyOf(cut)).isEmpty())
        val loop = Builder().record(MdnsTestPackets.pointer(MdnsTestPackets.HEADER), A, MdnsTestPackets.a("10.0.3.9")).build()   // the owner points at itself
        assertTrue(MdnsPacket.parse(loop).isEmpty())
    }
    @Test fun `a packet with 40 linked instances emits 16`() {
        val b = Builder()
        for (i in 1..40) announce(b, "cam$i", "_rtsp._tcp", "cam$i", "10.0.3.${100 + i}", 554)
        val records = MdnsPacket.parse(b.build())
        assertEquals(HEARD_MAX_PER_PACKET, records.size)
        assertEquals("packet order", (1..16).map { "cam$it" }, records.map { it.instanceName })
    }
    @Test fun `the meta service list and a label flood are ignored`() {
        assertTrue(MdnsPacket.parse(Builder().record("_services._dns-sd._udp.local", PTR, MdnsTestPackets.name("_http._tcp.local")).build()).isEmpty())
        val flood = (1..MdnsPacket.MAX_NAME_LABELS + 1).joinToString(".") { "a" }
        assertTrue(MdnsPacket.parse(Builder().record(flood, A, MdnsTestPackets.a("10.0.3.9")).build()).isEmpty())
    }
    @Test fun `the same instance announced twice in one packet is emitted once`() {
        val b = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000)
        b.record("_http._tcp.local", PTR, MdnsTestPackets.name("CHNAS._http._tcp.local"))
        assertEquals(1, MdnsPacket.parse(b.build()).size)
    }
}
