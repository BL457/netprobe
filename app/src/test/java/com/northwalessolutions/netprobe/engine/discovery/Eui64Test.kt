package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

class Eui64Test {
    @Test fun `derives mac from eui64 link local`() {
        assertEquals("00:11:22:33:44:55", Eui64.macFromLinkLocal("fe80::211:22ff:fe33:4455"))
        assertEquals("b8:27:eb:12:34:56", Eui64.macFromLinkLocal("fe80::ba27:ebff:fe12:3456%wlan0"))
    }
    @Test fun `privacy addresses and non link local return null`() {
        assertNull(Eui64.macFromLinkLocal("fe80::1c2d:3e4f:5a6b:7c8d"))
        assertNull(Eui64.macFromLinkLocal("2a00:1450:4009:81f::200e"))
        assertNull(Eui64.macFromLinkLocal("not an address")); assertNull(Eui64.macFromLinkLocal(""))
    }
    @Test fun `uppercase hex works and malformed literals return null`() {
        assertEquals("00:11:22:33:44:55", Eui64.macFromLinkLocal("FE80::211:22FF:FE33:4455"))
        assertNull(Eui64.macFromLinkLocal(".:"))
        assertNull(Eui64.macFromLinkLocal(".a:"))
    }
}
