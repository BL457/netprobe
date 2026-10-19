package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class UpnpDescriptionTest {
    private fun fixture(n: String) = javaClass.getResource("/fixtures/$n")!!.readText()
    @Test fun `router description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!
        assertEquals("TP-Link", d.manufacturer); assertEquals("Archer AX55", d.modelName); assertEquals("Archer AX55", d.friendlyName)
        assertTrue(d.deviceType!!.contains("InternetGatewayDevice")); assertEquals("uuid:12345678-1234-1234-1234-aabbccddeeff", d.udn)
    }
    @Test fun `takes the root device not the embedded one`() {
        assertEquals("Archer AX55", UpnpDescriptionParser.parse(fixture("upnp_desc_router.xml"))!!.friendlyName)
    }
    @Test fun `tv description`() {
        val d = UpnpDescriptionParser.parse(fixture("upnp_desc_tv.xml"))!!
        assertEquals("Samsung Electronics", d.manufacturer); assertTrue(d.deviceType!!.contains("MediaRenderer"))
    }
    @Test fun `garbage yields null not an exception`() {
        assertNull(UpnpDescriptionParser.parse(fixture("upnp_desc_garbage.xml"))); assertNull(UpnpDescriptionParser.parse(""))
    }
}
