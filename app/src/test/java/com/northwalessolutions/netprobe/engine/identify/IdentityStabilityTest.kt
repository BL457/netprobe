package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.data.IdentityWrite
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** Review Focus 3: an identity a port scan established never flaps on the next radar run or a timed-out rescan (spec 3.1, 3.3). */
class IdentityStabilityTest {
    private val id = DeviceIdentifier(IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()))
    private val host = DiscoveredHost(InetAddress.getByName("10.0.3.59") as Inet4Address)   // discovery saw no title
    private val dsm = ServiceInfo("HTTP", "nginx", null, "200 OK", Confidence.CONFIRMED, title = "DiskStation - Synology DiskStation")

    @Test fun `a radar re-identification keeps what a port scan learned`() {
        val afterScan = id.identify(HostEvidenceBuilder.build(host, emptyMap(), setOf(5000), mapOf(5000 to dsm), null))
        val stored = mapOf(5000 to ServiceMerge.merge(null, dsm))
        val afterRadar = id.identify(HostEvidenceBuilder.build(host, stored, emptySet(), emptyMap(), null))   // discover(): stored ports, no new services
        assertEquals(DeviceType.NAS, afterScan.type); assertEquals("Synology", afterScan.vendor); assertEquals(afterScan, afterRadar)
    }
    @Test fun `a rescan whose HEAD timed out keeps the confirmed name, the title and the identity`() {
        val timedOut = ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE)
        assertEquals(dsm, ServiceMerge.merge(dsm, timedOut))
        val r = id.identify(HostEvidenceBuilder.build(host, mapOf(5000 to dsm), setOf(5000), mapOf(5000 to timedOut), null))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type); assertEquals(90, r.confidence)
    }
    @Test fun `a weaker port-scan identity never replaces a stronger stored one`() {
        val strong = id.identify(HostEvidenceBuilder.build(host, mapOf(5000 to dsm), emptySet(), emptyMap(), null))
        val weak = id.identify(HostEvidenceBuilder.build(host, emptyMap(), setOf(22), mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.6", null, Confidence.CONFIRMED)), null))
        assertEquals(40, weak.confidence)
        assertFalse(IdentityWrite.replaces(strong.confidence, weak.confidence)); assertTrue(IdentityWrite.replaces(strong.confidence, strong.confidence))
    }
}
