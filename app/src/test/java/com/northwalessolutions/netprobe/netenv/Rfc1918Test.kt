package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

class Rfc1918Test {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!

    @Test fun `literal parser never resolves names`() {
        for (bad in listOf("nas.local", "10.0.3", "10.0.3.256", "1.2.3.4.5", "", "a.b.c.d", "10.0.3.1 ")) assertNull(bad, Ipv4.parse(bad))
        assertEquals("10.0.3.7", Ipv4.parse("10.0.3.7")!!.hostAddress)
    }
    @Test fun `private blocks only`() {
        for (s in listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.0.1")) assertTrue(s, Rfc1918.contains(ip(s)))
        for (s in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "169.254.1.1", "192.169.0.1")) assertFalse(s, Rfc1918.contains(ip(s)))
    }
    @Test fun `enclosing slash 16 for each block`() {
        assertEquals("10.0.0.0/16", Rfc1918.enclosing16(ip("10.0.3.242")).toString())
        assertEquals("172.20.0.0/16", Rfc1918.enclosing16(ip("172.20.9.9")).toString())
        assertEquals("192.168.0.0/16", Rfc1918.enclosing16(ip("192.168.1.50")).toString())
        assertNull(Rfc1918.enclosing16(ip("172.15.1.1"))); assertNull(Rfc1918.enclosing16(ip("172.32.1.1"))); assertNull(Rfc1918.enclosing16(ip("100.64.1.1")))
    }
    @Test fun `overlap, containment and slash 24 units`() {
        assertTrue(net("10.0.4.0/24").overlaps(net("10.0.4.16/28"))); assertFalse(net("10.0.4.0/24").overlaps(net("10.0.5.0/24")))
        assertTrue(net("10.0.4.0/22").containsSubnet(net("10.0.7.0/24"))); assertFalse(net("10.0.4.0/24").containsSubnet(net("10.0.4.0/22")))
        assertTrue(net("192.168.0.0/20").overlaps(net("192.168.15.0/24")))   // high-bit networks compare unsigned
        assertEquals(listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24", "10.0.7.0/24"), net("10.0.4.0/22").slash24Units().map { it.toString() })
        assertEquals(16, net("10.0.0.0/20").slash24Units().size)
        assertEquals(listOf("10.0.4.16/28"), net("10.0.4.16/28").slash24Units().map { it.toString() })
    }
}
