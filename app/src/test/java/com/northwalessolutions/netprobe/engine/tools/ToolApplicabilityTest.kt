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
        assertTrue(ToolApplicability.appliesTo(ToolKeys.DIR_BRUTEFORCE, 81, unknown))
        assertTrue(ToolApplicability.appliesTo(ToolKeys.HTTP_FINGERPRINT, 7123, svc("HTTP")))
    }
    @Test fun `tls certificate applies to https and the bare tls ports`() {
        assertTrue(ToolApplicability.appliesTo(ToolKeys.TLS_CERTIFICATE, 5001, svc("HTTPS")))
        for (p in listOf(465, 636, 993, 995, 5986, 8883)) assertTrue("$p", ToolApplicability.appliesTo(ToolKeys.TLS_CERTIFICATE, p, unknown))
        assertFalse(ToolApplicability.appliesTo(ToolKeys.TLS_CERTIFICATE, 5000, svc("HTTP")))
    }
    @Test fun `smb session is port 445 only, 139 excluded`() {
        assertTrue(ToolApplicability.appliesTo(ToolKeys.SMB_SESSION, 445, svc("SMB")))
        assertFalse(ToolApplicability.appliesTo(ToolKeys.SMB_SESSION, 139, svc("NetBIOS")))
    }
    @Test fun `snmp walk is per host only, gated on a discovery answer`() {
        assertFalse(ToolApplicability.appliesTo(ToolKeys.SNMP_WALK, 161, unknown))
        assertFalse(ToolApplicability.snmp(host(snmp = null))); assertTrue(ToolApplicability.snmp(host(snmp = "Linux 5.10")))
    }
    @Test fun `toolsFor lists the registry's port tools in registry order and target tls matches the scheme`() {
        val reg = TestRegistry.registry()
        fun keys(port: Int, s: ServiceInfo) = ToolApplicability.toolsFor(reg, port, s).map { it.descriptor.key }
        assertEquals(listOf(ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT), keys(5000, svc("HTTP")))
        assertEquals(listOf(ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT, ToolKeys.TLS_CERTIFICATE), keys(5001, svc("HTTPS")))
        assertEquals(listOf(ToolKeys.SMB_SESSION), keys(445, svc("SMB")))
        assertTrue(ToolApplicability.appliesTo(ToolKeys.IDENTIFY, 9999, unknown)); assertTrue(keys(9999, unknown).isEmpty())   // any port, but not a Device screen chip
        assertTrue(ToolApplicability.targetTls(ToolKeys.DIR_BRUTEFORCE, 5001, svc("HTTPS"))); assertFalse(ToolApplicability.targetTls(ToolKeys.DIR_BRUTEFORCE, 5000, svc("HTTP")))
        assertTrue(ToolApplicability.targetTls(ToolKeys.TLS_CERTIFICATE, 993, unknown)); assertFalse(ToolApplicability.targetTls(ToolKeys.SMB_SESSION, 445, svc("SMB")))
    }
    @Test fun `each module answers applies and appliesToHost with the same rules as the key based table`() {
        val reg = TestRegistry.registry()
        val samples = listOf(81 to unknown, 445 to svc("SMB"), 139 to svc("NetBIOS"), 5001 to svc("HTTPS"), 993 to unknown, 22 to svc("SSH"), 7123 to svc("HTTP"))
        for (m in reg.all.value) for ((port, s) in samples) assertEquals("${m.descriptor.key} $port", ToolApplicability.appliesTo(m.descriptor.key, port, s), m.applies(port, s))
        val snmp = reg.byKey(ToolKeys.SNMP_WALK)!!
        assertFalse(snmp.appliesToHost(host(snmp = null))); assertTrue(snmp.appliesToHost(host(snmp = "Linux 5.10")))
        assertTrue(reg.byKey(ToolKeys.IDENTIFY_ALL)!!.appliesToHost(host()))
        assertFalse(reg.byKey(ToolKeys.SMB_SESSION)!!.appliesToHost(host(snmp = "Linux")))
        assertEquals(listOf(ToolKeys.SNMP_WALK, ToolKeys.IDENTIFY_ALL), ToolApplicability.hostToolsFor(reg, host(snmp = "x")).map { it.descriptor.key })
        assertEquals(listOf(ToolKeys.IDENTIFY_ALL), ToolApplicability.hostToolsFor(reg, host()).map { it.descriptor.key })
    }
    @Test fun `server name is mdns then reverse dns, trailing dot removed`() {
        assertEquals("chnas.local", ToolApplicability.serverName(host(mdns = "chnas.local.", rdns = "chnas.lan")))
        assertEquals("chnas.lan", ToolApplicability.serverName(host(rdns = "chnas.lan")))
        assertNull(ToolApplicability.serverName(host()))
    }
    @Test fun `finding kind parses leniently`() {
        assertEquals(FindingKind.PATH, FindingKind.parse("PATH")); assertEquals(FindingKind.OTHER, FindingKind.parse("WAT"))
        assertEquals(ToolKeys.SNMP_WALK, TestRegistry.registry().byKey("snmp-walk")?.descriptor?.key); assertNull(TestRegistry.registry().byKey("nope"))
    }
}
