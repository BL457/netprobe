package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.*
import org.junit.Test

/** What a heard packet becomes before the queue (3C spec 3.3): the scan's shapes plus Source.HEARD, tagged with the session's key. */
class HeardPacketsTest {
    private val key = FakeSink.KEY_A

    @Test fun `an mDNS announcement becomes a heard host tagged with the session's network`() {
        val p = MdnsTestPackets.announce(MdnsTestPackets.Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+")).build()
        val h = HeardPackets.mdns(p, Ipv4.parse("10.0.3.59")!!, key).single()
        assertEquals(key, h.identityKey); assertEquals("m|10.0.3.59|_http._tcp", h.repeatKey); assertNull(h.location)
        assertEquals(setOf(Source.MDNS, Source.HEARD), h.host.sources); assertEquals("CHNAS", h.host.mdnsName)
        assertEquals(setOf("_http._tcp"), h.host.mdnsServiceTypes); assertEquals(mapOf("_http._tcp" to mapOf("model" to "DS220+")), h.host.mdnsTxt)
    }
    @Test fun `an SSDP alive becomes a heard host with its location, its USN key and the UDN MAC`() {
        val usn = "uuid:2f402f80-da50-11e1-9b23-00178817aa01::upnp:rootdevice"
        val h = HeardPackets.ssdp(Ipv4.parse("10.0.3.40")!!, HeardTestSupport.notify("10.0.3.40", usn).toByteArray(Charsets.ISO_8859_1), key)!!
        assertEquals("http://10.0.3.40:80/description.xml", h.location); assertEquals("s|10.0.3.40|$usn", h.repeatKey)
        assertEquals("00:17:88:17:aa:01", h.host.mac); assertEquals(MacSource.UPNP_UDN, h.host.macSource)
        assertEquals(setOf(Source.SSDP, Source.HEARD), h.host.sources); assertEquals("Hue/1.0 UPnP/1.0 IpBridge/1.65.0", h.host.upnpServer)
    }
    @Test fun `byebye and a malformed packet give nothing`() {
        assertNull(HeardPackets.ssdp(Ipv4.parse("10.0.3.40")!!, "NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nUSN: x\r\n\r\n".toByteArray(), key))
        assertTrue(HeardPackets.mdns(byteArrayOf(1, 2, 3), Ipv4.parse("10.0.3.59")!!, key).isEmpty())
    }
}
