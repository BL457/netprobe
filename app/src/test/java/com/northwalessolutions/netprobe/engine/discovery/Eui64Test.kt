package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

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
}

class ReverseDnsTest {
    @Test fun `loopback resolves or returns null without throwing and respects timeout`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = ReverseDns(timeoutMs = 1500).lookup(InetAddress.getByName("192.0.2.123") as Inet4Address)
        assertTrue(System.currentTimeMillis() - t0 < 2500)
        if (r != null) assertNotEquals("192.0.2.123", r)
    }
}
