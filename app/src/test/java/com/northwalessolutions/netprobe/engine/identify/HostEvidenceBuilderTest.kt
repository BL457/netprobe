package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class HostEvidenceBuilderTest {
    private val host = DiscoveredHost(InetAddress.getByName("10.0.3.20") as Inet4Address)
    private val ssh = ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED)
    private val smb = ServiceInfo("SMB", confidence = Confidence.PORT_TABLE)
    private val rules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText())

    @Test fun `rediscovery keeps stored ports and services`() {
        val e = HostEvidenceBuilder.build(host, mapOf(22 to ssh, 445 to smb), newPorts = setOf(80), newServices = emptyMap(), ouiVendor = null)
        assertEquals(setOf(22, 80, 445), e.openPorts); assertEquals(ssh, e.services[22]); assertEquals(smb, e.services[445])
    }
    @Test fun `new service facts win but nulls never erase stored ones`() {
        val e = HostEvidenceBuilder.build(host, mapOf(22 to ssh), emptySet(), mapOf(22 to ServiceInfo("SSH", null, "9.7p1", null, Confidence.CONFIRMED), 8080 to smb), null)
        assertEquals(ServiceInfo("SSH", "OpenSSH", "9.7p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED), e.services[22])
        assertEquals(setOf(22, 8080), e.openPorts)
    }
    @Test fun `a narrow rescan does not downgrade the identity`() {
        val id = DeviceIdentifier(rules)
        val full = id.identify(HostEvidenceBuilder.build(host, mapOf(22 to ssh), emptySet(), emptyMap(), null))
        val narrow = id.identify(HostEvidenceBuilder.build(host, mapOf(22 to ssh), setOf(80), mapOf(80 to ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE)), null))
        assertEquals("Linux (Ubuntu)", full.os); assertEquals(full.os, narrow.os)
    }
}
