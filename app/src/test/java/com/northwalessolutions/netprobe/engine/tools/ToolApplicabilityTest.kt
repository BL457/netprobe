package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** spec 4.2 and 4.9: which tools a port gets, the target tls flag, the SNMP gate and the name sent. */
class ToolApplicabilityTest {
    private fun svc(name: String, c: Confidence = Confidence.CONFIRMED) = ServiceInfo(name, confidence = c)
    private val unknown = svc("Unknown", Confidence.PORT_TABLE)
    private fun host(snmp: String? = null, mdns: String? = null, rdns: String? = null) = HostEntity(networkId = 1, ip = "10.0.3.1", mac = null,
        reverseDnsName = rdns, mdnsName = mdns, netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0,
        identReasons = emptyList(), snmpSysDescr = snmp, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "",
        isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)

    @Test fun `web tools apply to http and https on any port`() {
        assertEquals("http", ToolApplicability.webScheme(81, unknown))                       // "HTTP alt" on 81, in ServiceIdentifier.HTTP
        assertEquals("http", ToolApplicability.webScheme(5000, unknown))                     // "Web / Synology", a PortUris port
        assertEquals("https", ToolApplicability.webScheme(5001, svc("HTTPS")))               // confirmed banner
        assertEquals("https", ToolApplicability.webScheme(5001, unknown))                    // no banner: ServiceIdentifier.TLS fallback
        assertEquals("http", ToolApplicability.webScheme(7123, svc("HTTP")))                 // an unknown port confirmed HTTP by 3A's ladder
        assertNull(ToolApplicability.webScheme(22, svc("SSH")))
        assertTrue(ToolApplicability.appliesTo(ToolId.DIR_BRUTEFORCE, 81, unknown))
        assertTrue(ToolApplicability.appliesTo(ToolId.HTTP_FINGERPRINT, 7123, svc("HTTP")))
    }
    @Test fun `tls certificate applies to https and the bare tls ports`() {
        assertTrue(ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, 5001, svc("HTTPS")))
        for (p in listOf(465, 636, 993, 995, 5986, 8883)) assertTrue("$p", ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, p, unknown))
        assertFalse(ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, 5000, svc("HTTP")))
    }
    @Test fun `smb session is port 445 only, 139 excluded`() {
        assertTrue(ToolApplicability.appliesTo(ToolId.SMB_SESSION, 445, svc("SMB")))
        assertFalse(ToolApplicability.appliesTo(ToolId.SMB_SESSION, 139, svc("NetBIOS")))
    }
    @Test fun `snmp walk is per host only, gated on a discovery answer`() {
        assertFalse(ToolApplicability.appliesTo(ToolId.SNMP_WALK, 161, unknown))
        assertFalse(ToolApplicability.snmp(host(snmp = null))); assertTrue(ToolApplicability.snmp(host(snmp = "Linux 5.10")))
    }
    @Test fun `toolsFor lists in ToolId order and target tls matches the scheme`() {
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT), ToolApplicability.toolsFor(5000, svc("HTTP")))
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE), ToolApplicability.toolsFor(5001, svc("HTTPS")))
        assertEquals(listOf(ToolId.SMB_SESSION), ToolApplicability.toolsFor(445, svc("SMB")))
        assertTrue(ToolApplicability.targetTls(ToolId.DIR_BRUTEFORCE, 5001, svc("HTTPS"))); assertFalse(ToolApplicability.targetTls(ToolId.DIR_BRUTEFORCE, 5000, svc("HTTP")))
        assertTrue(ToolApplicability.targetTls(ToolId.TLS_CERTIFICATE, 993, unknown)); assertFalse(ToolApplicability.targetTls(ToolId.SMB_SESSION, 445, svc("SMB")))
    }
    @Test fun `server name is mdns then reverse dns, trailing dot removed`() {
        assertEquals("chnas.local", ToolApplicability.serverName(host(mdns = "chnas.local.", rdns = "chnas.lan")))
        assertEquals("chnas.lan", ToolApplicability.serverName(host(rdns = "chnas.lan")))
        assertNull(ToolApplicability.serverName(host()))
    }
    @Test fun `finding kind parses leniently`() {
        assertEquals(FindingKind.PATH, FindingKind.parse("PATH")); assertEquals(FindingKind.OTHER, FindingKind.parse("WAT"))
        assertEquals(ToolId.SNMP_WALK, ToolId.byKey("snmp-walk")); assertNull(ToolId.byKey("nope"))
    }
}
