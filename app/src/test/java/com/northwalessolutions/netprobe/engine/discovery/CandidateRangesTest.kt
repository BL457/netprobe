package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class CandidateRangesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val scanned = net("10.0.3.0/24")
    private fun derive(manual: List<String> = emptyList(), evidence: List<String> = emptyList(), hits: List<String> = emptyList(), cap: Int = 4) =
        CandidateRanges.derive(scanned, manual.map(::net), evidence.map(::ip), hits.map(::net), cap)
    private fun CandidateResult.names() = ranges.map { "${it.subnet}:${it.source}" }

    @Test fun `local range is excluded including an overlapping manual entry`() {
        assertEquals(emptyList<String>(), derive(manual = listOf("10.0.3.128/25"), evidence = listOf("10.0.3.9")).names())
    }
    @Test fun `non rfc1918 and link local evidence is dropped`() {
        assertEquals(emptyList<String>(), derive(evidence = listOf("8.8.8.8", "169.254.1.1", "100.64.0.5")).names())
    }
    @Test fun `evidence normalises to its slash 24 and orders by count then address`() {
        assertEquals(listOf("10.0.5.0/24:EVIDENCE", "10.0.7.0/24:EVIDENCE", "10.0.9.0/24:EVIDENCE"),
            derive(evidence = listOf("10.0.9.1", "10.0.5.1", "10.0.5.2", "10.0.7.1")).names())
        assertEquals(listOf("10.0.4.0/24:EVIDENCE"), derive(evidence = listOf("10.0.4.20")).names())
    }
    @Test fun `sources are ordered manual, evidence, probe and de-duplicated`() {
        val r = derive(manual = listOf("10.0.8.0/24", "10.0.4.0/24"), evidence = listOf("10.0.4.20", "10.0.6.1"), hits = listOf("10.0.4.0/24", "10.0.2.0/24"))
        assertEquals(listOf("10.0.8.0/24:MANUAL", "10.0.4.0/24:MANUAL", "10.0.6.0/24:EVIDENCE", "10.0.2.0/24:GATEWAY_PROBE"), r.names())
    }
    @Test fun `overlap resolves to the earlier item`() {
        assertEquals(listOf("10.0.4.16/28:MANUAL"), derive(manual = listOf("10.0.4.16/28"), evidence = listOf("10.0.4.20")).names())
    }
    @Test fun `gateway hits order by address`() {
        assertEquals(listOf("10.0.2.0/24:GATEWAY_PROBE", "10.0.10.0/24:GATEWAY_PROBE"), derive(hits = listOf("10.0.10.0/24", "10.0.2.0/24")).names())
    }
    @Test fun `cap boundaries and coercion`() {
        val many = listOf("10.0.16.0/20", "10.0.32.0/24", "10.0.33.0/24")   // 16 units plus 2
        assertEquals(1, derive(manual = many, cap = 1).ranges.size); assertEquals(4, derive(manual = many, cap = 4).ranges.size)
        assertEquals(16, derive(manual = many, cap = 16).ranges.size); assertEquals(16, derive(manual = many, cap = 40).ranges.size)
        assertEquals(1, derive(manual = many, cap = 0).ranges.size)
        assertEquals(listOf("10.0.32.0/24", "10.0.33.0/24"), derive(manual = many, cap = 16).truncated.map { it.subnet.toString() })
    }
    @Test fun `truncated lists only cap drops, never overlap drops`() {
        val r = derive(manual = listOf("10.0.4.0/24", "10.0.5.0/24"), evidence = listOf("10.0.4.9", "10.0.6.1"), cap = 1)
        assertEquals(listOf("10.0.4.0/24"), r.ranges.map { it.subnet.toString() })
        assertEquals(listOf("10.0.5.0/24", "10.0.6.0/24"), r.truncated.map { it.subnet.toString() })
    }
    @Test fun `empty inputs give empty output`() { assertEquals(CandidateResult(emptyList(), emptyList()), derive()) }
    @Test fun `clamped slash 16 leaves the other slash 24s eligible`() {
        // Phone on 10.0.0.0/16, maxPrefix 22: the swept range is 10.0.3.0/24 and exclusion is by the swept range only (spec 11.2).
        assertEquals(listOf("10.0.200.0/24:EVIDENCE"), derive(evidence = listOf("10.0.200.5")).names())
    }
    @Test fun `a manual entry that overlaps a changed local range is dropped at derive time`() {
        val r = CandidateRanges.derive(net("10.0.4.0/24"), listOf(net("10.0.4.0/24"), net("10.0.5.0/24")), emptyList(), emptyList(), 4)
        assertEquals(listOf("10.0.5.0/24"), r.ranges.map { it.subnet.toString() })
    }
    @Test fun `dns servers outside the swept range count as evidence`() {
        val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"),
            listOf(ip("10.0.9.53"), ip("10.0.3.1"), ip("1.1.1.1"), InetAddress.getByName("fd00::53")), null, null, Transport.WIFI)
        assertEquals(listOf("10.0.9.53"), Evidence.dnsServers(env, scanned).map { it.hostAddress })
    }
}
