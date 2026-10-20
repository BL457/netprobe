package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighbourRulesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun n(ip: String, mac: String) = NeighbourEntry(ip, mac, "REACHABLE")
    private val gw = Ipv4.parse("10.0.3.1")
    // Phone on 10.0.0.0/16 with maxPrefix 22: the true subnet is the /16, the swept range the /24 (extended spec 11.2).
    private val trueSubnet = SubnetMath.of(Ipv4.parse("10.0.3.242")!!, 16)
    private val swept = net("10.0.3.0/24")

    @Test fun `an entry outside the true subnet is neither evidence nor a mac`() {
        val r = NeighbourRules.apply(listOf(n("192.168.49.5", "02:00:00:00:00:49")), trueSubnet, swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs); assertEquals(emptyList<Any>(), r.evidence); assertEquals(emptyList<Any>(), r.onLinkIps)
    }
    @Test fun `an entry inside the true subnet and outside the swept range is evidence and keeps its mac`() {
        val r = NeighbourRules.apply(listOf(n("10.0.7.9", "de:ad:be:ef:00:07")), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.7.9" to "de:ad:be:ef:00:07"), r.macs); assertEquals(listOf("10.0.7.9"), r.evidence.map { it.hostAddress })
    }
    @Test fun `the gateway's mac is never assigned to another ip`() {
        val r = NeighbourRules.apply(listOf(n("10.0.3.1", "aa:bb:cc:00:00:01"), n("10.0.3.50", "aa:bb:cc:00:00:01")), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.3.1" to "aa:bb:cc:00:00:01"), r.macs)
        assertEquals(listOf("10.0.3.1", "10.0.3.50"), r.onLinkIps.map { it.hostAddress })   // 10.0.3.50 keeps its row, only the MAC claim is refused
    }
    @Test fun `a mac on more than two ips is proxy arp and ignored`() {
        val shared = listOf(n("10.0.8.1", "aa:aa:aa:aa:aa:aa"), n("10.0.8.2", "aa:aa:aa:aa:aa:aa"), n("10.0.8.3", "aa:aa:aa:aa:aa:aa"))
        val r = NeighbourRules.apply(shared + n("10.0.3.9", "bb:bb:bb:bb:bb:09"), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.3.9" to "bb:bb:bb:bb:bb:09"), r.macs); assertEquals(emptyList<Any>(), r.evidence)
        val two = NeighbourRules.apply(shared.take(2), trueSubnet, swept, gw)
        assertEquals(2, two.macs.size)   // two IPs on one MAC is allowed (a dual-homed host)
    }
    @Test fun `a proxy arp ip keeps its row but loses the mac claim`() {
        val shared = listOf(n("10.0.3.20", "aa:aa:aa:aa:aa:aa"), n("10.0.3.21", "aa:aa:aa:aa:aa:aa"), n("10.0.3.22", "aa:aa:aa:aa:aa:aa"))
        val r = NeighbourRules.apply(shared, trueSubnet, swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs); assertEquals(emptyList<Any>(), r.evidence)
        assertEquals(listOf("10.0.3.20", "10.0.3.21", "10.0.3.22"), r.onLinkIps.map { it.hostAddress })
    }
    @Test fun `off-link ips on a slash 24 phone get nothing from the dump`() {
        val r = NeighbourRules.apply(listOf(n("10.0.4.20", "de:ad:be:ef:04:20")), net("10.0.3.0/24"), swept, gw)
        assertEquals(emptyMap<String, String>(), r.macs)
    }
    @Test fun `the gateway keeps its own mac when it proxy arps for other ips`() {
        val g = "aa:bb:cc:00:00:01"
        val r = NeighbourRules.apply(listOf(n("10.0.3.1", g), n("10.0.3.50", g), n("10.0.3.51", g)), trueSubnet, swept, gw)
        assertEquals(mapOf("10.0.3.1" to g), r.macs)
        assertEquals(listOf("10.0.3.1", "10.0.3.50", "10.0.3.51"), r.onLinkIps.map { it.hostAddress })
    }
}
