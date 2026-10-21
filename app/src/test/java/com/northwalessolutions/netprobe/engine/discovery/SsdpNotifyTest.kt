package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 17. */
class SsdpNotifyTest {
    private val from = Ipv4.parse("10.0.3.30")!!
    private fun notify(nts: String) = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
        "LOCATION: http://10.0.3.30:1400/xml/device_description.xml\r\nNT: upnp:rootdevice\r\nNTS: $nts\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/80.1\r\nUSN: uuid:RINCON_000E58A0B1C201400::upnp:rootdevice\r\n\r\n"

    @Test fun `an alive notify gives location, server, nt and usn`() {
        val r = SsdpParser.parseNotify(from, notify("ssdp:alive"))!!
        assertEquals(from, r.from); assertEquals("http://10.0.3.30:1400/xml/device_description.xml", r.location)
        assertEquals("Linux UPnP/1.0 Sonos/80.1", r.server); assertEquals("upnp:rootdevice", r.st)
        assertEquals("uuid:RINCON_000E58A0B1C201400::upnp:rootdevice", r.usn)
    }
    @Test fun `byebye is null`() { assertNull(SsdpParser.parseNotify(from, notify("ssdp:byebye"))) }
    @Test fun `an M-SEARCH request is null`() {
        assertNull(SsdpParser.parseNotify(from, "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"))
    }
    @Test fun `a search response is null here and still parsed by parse`() {
        val response = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nUSN: uuid:x\r\nLOCATION: http://10.0.3.30:1400/d.xml\r\n\r\n"
        assertNull(SsdpParser.parseNotify(from, response))
        assertNotNull(SsdpParser.parse(from, response))
    }
    @Test fun `bare newlines, lower case and a missing NTS`() {
        assertNotNull(SsdpParser.parseNotify(from, "notify * http/1.1\nnts: SSDP:ALIVE\nnt: x\n"))
        assertNull(SsdpParser.parseNotify(from, "NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(SsdpParser.parseNotify(from, ""))
    }
}
