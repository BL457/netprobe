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

    // F1 (Phase 3A device QA): the Director's own hosts, evidence exactly as stored in his database before and after the radar scan.
    private val usgSsh = ServiceInfo("SSH", "OpenSSH", "6.6.1p1", "Debian-4~bpo70+1", Confidence.CONFIRMED)
    private val usgCert = ServiceInfo("HTTPS", null, null, "cert UbiquitiRouterUI", Confidence.CONFIRMED, certCn = "UbiquitiRouterUI", certOrg = "Ubiquiti Inc.")
    private fun usg(ip: String, gateway: Boolean = false) =
        DiscoveredHost(InetAddress.getByName(ip) as Inet4Address, httpTitle = "UniFi Security Gateway", httpServer = "Server", isGateway = gateway)

    @Test fun `F1 a UniFi Security Gateway seen by the radar with only its title reads Ubiquiti router`() {   // 10.0.4.1, 10.0.0.1, 10.0.2.1
        val r = id.identify(HostEvidenceBuilder.build(usg("10.0.4.1"), emptyMap(), emptySet(), emptyMap(), null))
        assertEquals("Ubiquiti", r.vendor); assertEquals("UniFi Security Gateway", r.model); assertEquals(DeviceType.ROUTER, r.type)
        assertTrue(r.confidence >= 70); assertFalse("a USG does not run the UniFi Network app", "UniFi Network" in r.software)
    }
    @Test fun `F1 a UniFi Security Gateway after its port scan keeps Ubiquiti router and gains the OS`() {   // 192.168.1.1
        val r = id.identify(HostEvidenceBuilder.build(usg("192.168.1.1"), mapOf(22 to usgSsh, 443 to usgCert), emptySet(), emptyMap(), null))
        assertEquals("Ubiquiti", r.vendor); assertEquals(DeviceType.ROUTER, r.type); assertEquals("Linux (Debian)", r.os); assertEquals(90, r.confidence)
    }
    @Test fun `F1 the default gateway keeps Ubiquiti at 95`() {   // 10.0.3.1
        val r = id.identify(HostEvidenceBuilder.build(usg("10.0.3.1", gateway = true), mapOf(22 to usgSsh, 443 to usgCert), emptySet(), emptyMap(), null))
        assertEquals("Ubiquiti", r.vendor); assertEquals(DeviceType.ROUTER, r.type); assertEquals(95, r.confidence)
    }
    @Test fun `F1 the UbiquitiRouterUI certificate alone reads Ubiquiti router`() {
        val r = id.identify(HostEvidenceBuilder.build(host, mapOf(443 to usgCert), emptySet(), emptyMap(), null))
        assertEquals("Ubiquiti", r.vendor); assertEquals(DeviceType.ROUTER, r.type)
    }
    @Test fun `F1 the UniFi Network app on another box still reads as software only`() {
        val r = id.identify(HostEvidenceBuilder.build(DiscoveredHost(InetAddress.getByName("10.0.3.40") as Inet4Address, httpTitle = "UniFi Network"),
            emptyMap(), emptySet(), emptyMap(), null))
        assertNull(r.vendor); assertEquals(DeviceType.UNKNOWN, r.type); assertEquals(listOf("UniFi Network"), r.software)
    }
    @Test fun `F1 a radar re-identification of the same device never lowers the stored identity`() {   // the Pi-holes, 10.0.3.170 and 10.0.3.234
        val piMac = "e4:5f:01:e4:22:dc"
        val ev = HostEvidenceBuilder.build(DiscoveredHost(InetAddress.getByName("10.0.3.170") as Inet4Address, httpTitle = "Pi-hole btast2", reverseDnsName = "pi.hole"),
            mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.2p1", "Debian-2+deb12u5", Confidence.CONFIRMED),   // the real row's full port set
                53 to ServiceInfo("DNS", confidence = Confidence.PORT_TABLE),
                80 to ServiceInfo("HTTP", null, null, "403 Forbidden", Confidence.PORT_TABLE),
                443 to ServiceInfo("HTTPS", null, null, "cert pi.hole", Confidence.PORT_TABLE, certCn = "pi.hole"),
                5900 to ServiceInfo("VNC", null, null, "RFB 003.008", Confidence.CONFIRMED)), emptySet(), emptyMap(), null)
        val radar = id.identify(ev)
        assertEquals(70, radar.confidence)                                     // the stored Phase 2 row reads model Pi-hole at 90
        assertFalse(IdentityWrite.radarReplaces(storedConfidence = 90, newConfidence = radar.confidence, previousMac = piMac, currentMac = piMac))
        assertFalse(IdentityWrite.radarReplaces(storedConfidence = 90, newConfidence = 70, previousMac = null, currentMac = piMac))   // MAC learned late
        assertTrue(IdentityWrite.radarReplaces(storedConfidence = 70, newConfidence = 70, previousMac = piMac, currentMac = piMac.uppercase()))
        assertTrue(IdentityWrite.radarReplaces(storedConfidence = 70, newConfidence = 90, previousMac = piMac, currentMac = piMac))
    }
    @Test fun `F1 a different MAC on the same IP is a different device and its identity is always written`() {   // spec 3.3, re-used IP
        assertTrue(IdentityWrite.radarReplaces(storedConfidence = 90, newConfidence = 40, previousMac = "e4:5f:01:e4:22:dc", currentMac = "aa:bb:cc:00:11:22"))
    }
}
