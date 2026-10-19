package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SsdpTest {
    private fun fixture(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    private val from = InetAddress.getByName("192.168.1.1") as Inet4Address
    @Test fun `parses headers case-insensitively`() {
        val r = SsdpParser.parse(from, fixture("ssdp_response_router.txt"))!!
        assertEquals("http://192.168.1.1:1900/igd.xml", r.location)
        assertEquals("Linux/3.10 UPnP/1.1 MiniUPnPd/2.1", r.server)
        assertTrue(r.st!!.contains("InternetGatewayDevice")); assertTrue(r.usn!!.startsWith("uuid:"))
    }
    @Test fun `non 200 and garbage return null`() {
        assertNull(SsdpParser.parse(from, "NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(SsdpParser.parse(from, "")); assertNull(SsdpParser.parse(from, "\u0000\u0001garbage"))
    }
    @Test fun `mac extraction from usn and names`() {
        assertEquals("aa:bb:cc:dd:ee:ff", MacExtract.fromText("uuid:5c6f-1234-aa:bb:cc:dd:ee:ff::urn"))
        assertEquals("aa:bb:cc:dd:ee:ff", MacExtract.fromText("uuid:12345678-1234-1234-1234-aabbccddeeff"))
        assertEquals("00:11:22:33:44:55", MacExtract.fromText("raspberrypi [00:11:22:33:44:55]"))
        assertNull(MacExtract.fromText("Living Room TV")); assertNull(MacExtract.fromText("1234567890ab12")) // 14 hex: not a MAC
        assertNull(MacExtract.fromText("uuid:12345678-1234-4234-1234-aabbccddeeff"))   // version 4 UUID: random node, not a MAC
    }
}
