package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 19. */
class KnownRangesTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val home = SubnetMath.of(ip("10.0.3.20"), 24)

    @Test fun `the home range of any class, the manual ranges and the slash 24 of every visible private host`() {
        val cgnatHome = SubnetMath.of(ip("100.64.5.9"), 24)
        val k = KnownRanges.of(cgnatHome, listOf(CidrParser.subnetOf("10.0.4.0/24")!!), listOf(TestHosts.host(ip = "192.168.7.20"), TestHosts.host(ip = "8.8.8.8")))
        assertTrue("the home range, whatever its class", k.contains(ip("100.64.5.200")))
        assertTrue("a manual range", k.contains(ip("10.0.4.1")))
        assertTrue("a known private host's /24", k.contains(ip("192.168.7.254")))
        assertFalse("a public stored host adds no range", k.contains(ip("8.8.4.4")))
        assertFalse(k.contains(ip("192.168.8.1")))
    }
    @Test fun `manual-only hosts contribute nothing`() {
        val k = KnownRanges.of(home, emptyList(), listOf(TestHosts.host(ip = "10.20.0.5").copy(manualOnly = true)))
        assertFalse(k.contains(ip("10.20.0.9")))
        assertEquals(listOf(home), k.ranges)
    }
    @Test fun `duplicates collapse and an unparsable stored address is skipped`() {
        assertEquals(listOf(home), KnownRanges.of(home, listOf(home), listOf(TestHosts.host(ip = "10.0.3.7"), TestHosts.host(ip = "not-an-ip"))).ranges)
    }
}
