package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SubnetTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun `slash 24 yields 254 hosts excluding network and broadcast`() {
        val s = SubnetMath.of(ip("192.168.1.37"), 24)
        assertEquals("192.168.1.0/24", s.toString())
        val hosts = s.hosts().toList()
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first().hostAddress)
        assertEquals("192.168.1.254", hosts.last().hostAddress)
    }
    @Test fun `slash 22 is swept whole`() {
        val r = SubnetMath.sweepRange(ip("10.1.2.3"), 22)
        assertFalse(r.clamped)
        assertEquals("10.1.0.0/22", r.subnet.toString())
        assertEquals(1022, r.subnet.hosts().count())
    }
    @Test fun `slash 16 is clamped to the phone's slash 24`() {
        val r = SubnetMath.sweepRange(ip("10.20.30.40"), 16)
        assertTrue(r.clamped)
        assertEquals(16, r.originalPrefix)
        assertEquals("10.20.30.0/24", r.subnet.toString())
    }
    @Test fun `slash 8 is clamped too and never iterates millions`() {
        val r = SubnetMath.sweepRange(ip("10.0.0.5"), 8)
        assertTrue(r.clamped)
        assertEquals(254, r.subnet.hosts().count())
    }
    @Test fun `maxPrefix 20 allows a slash 20 sweep`() {
        val r = SubnetMath.sweepRange(ip("172.16.5.9"), 20, maxPrefix = 20)
        assertFalse(r.clamped)
        assertEquals(4094, r.subnet.hosts().count())
    }
    @Test fun `slash 30 and slash 31 edge cases`() {
        assertEquals(2, SubnetMath.of(ip("192.168.1.1"), 30).hosts().count())
        assertEquals(2, SubnetMath.of(ip("192.168.1.1"), 31).hosts().count()) // RFC 3021: both usable
        assertEquals(1, SubnetMath.of(ip("192.168.1.1"), 32).hosts().count())
    }
    @Test fun `contains`() {
        val s = SubnetMath.of(ip("192.168.1.0"), 24)
        assertTrue(s.contains(ip("192.168.1.200")))
        assertFalse(s.contains(ip("192.168.2.1")))
    }
    @Test fun `int round trip keeps high addresses unsigned`() {
        val a = ip("224.0.0.251")
        assertEquals(a, SubnetMath.toAddress(SubnetMath.toInt(a)))
    }
}
