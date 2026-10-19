package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class DeviceIdentifierTest {
    private val rules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText())
    private val id = DeviceIdentifier(rules)
    private val ip = InetAddress.getByName("192.168.1.2") as Inet4Address
    private fun ev(h: DiscoveredHost = DiscoveredHost(ip), ports: Set<Int> = emptySet(), services: Map<Int, ServiceInfo> = emptyMap(), oui: String? = null) = HostEvidence(h, ports, services, oui)

    @Test fun `rules file loads with unique ids and valid fields`() {
        assertTrue(rules.size >= 45); assertEquals(rules.size, rules.map { it.id }.toSet().size)
        val fields = setOf("mdnsType", "mdnsTxt", "upnpDeviceType", "snmpSysDescr", "httpTitle", "httpRealm", "httpServer", "hostname", "netbiosName", "sshBanner", "ports", "anyPort", "gateway", "oui")
        rules.forEach { assertTrue("bad field in ${it.id}", it.field in fields); assertTrue(it.weight in 1..100) }
    }
    @Test fun `every rule is well formed`() {
        for (r in rules) {
            assertTrue("rule ${r.id} has unknown type ${r.type}", r.type == null || DeviceType.values().any { it.name == r.type })
            assertTrue("rule ${r.id} has a pattern but no sample", r.pattern == null || r.sample != null)
            val re = r.pattern?.let { Regex(it, RegexOption.IGNORE_CASE) }   // must compile
            if (listOf(r.vendor, r.model, r.os).any { it?.contains("\$1") == true }) {
                assertNotNull("rule ${r.id} uses \$1 but its sample yields no group 1", re!!.find(r.sample!!)?.groups?.get(1))
            }
        }
    }
    @Test fun `apple device-info model`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_device-info._tcp"), mdnsTxt = mapOf("_device-info._tcp" to mapOf("model" to "MacBookPro18,3")))))
        assertEquals("Apple", r.vendor); assertEquals("MacBookPro18,3", r.model); assertEquals(DeviceType.COMPUTER, r.type); assertTrue(r.reasons.any { it.contains("MacBookPro18,3") })
    }
    @Test fun `chromecast md`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_googlecast._tcp"), mdnsTxt = mapOf("_googlecast._tcp" to mapOf("md" to "Chromecast Ultra", "fn" to "Living Room")))))
        assertEquals("Google", r.vendor); assertEquals("Chromecast Ultra", r.model); assertEquals(DeviceType.MEDIA, r.type)
    }
    @Test fun `upnp direct extraction wins over port hints`() {
        val r = id.identify(ev(DiscoveredHost(ip, upnpManufacturer = "TP-Link", upnpModel = "Archer AX55", upnpDeviceType = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"), ports = setOf(80, 445)))
        assertEquals("TP-Link", r.vendor); assertEquals("Archer AX55", r.model); assertEquals(DeviceType.ROUTER, r.type); assertEquals(100, r.confidence)
    }
    @Test fun `snmp routeros`() {
        val r = id.identify(ev(DiscoveredHost(ip, snmpSysDescr = "RouterOS RB750Gr3")))
        assertEquals("MikroTik", r.vendor); assertEquals("RouterOS", r.os); assertEquals(DeviceType.ROUTER, r.type); assertEquals("RB750Gr3", r.model)
    }
    @Test fun `synology snmp and title`() {
        assertEquals("Synology", id.identify(ev(DiscoveredHost(ip, snmpSysDescr = "Linux DiskStation 4.4.302+ #69057 SMP"))).vendor)
        val t = id.identify(ev(DiscoveredHost(ip, httpTitle = "DiskStation - Synology DiskStation"))); assertEquals(DeviceType.NAS, t.type)
    }
    @Test fun `windows by ports and netbios`() {
        val r = id.identify(ev(DiscoveredHost(ip, netbiosName = "DESKTOP-ABC"), ports = setOf(135, 445)))
        assertEquals("Windows", r.os); assertEquals(DeviceType.COMPUTER, r.type)
    }
    @Test fun `iphone by lockdown port and hostname`() {
        assertEquals(DeviceType.PHONE, id.identify(ev(ports = setOf(62078))).type)
        val r = id.identify(ev(DiscoveredHost(ip, reverseDnsName = "Davids-iPhone.lan"))); assertEquals("iOS", r.os); assertEquals("Apple", r.vendor)
    }
    @Test fun `android phone by hostname`() {
        val r = id.identify(ev(DiscoveredHost(ip, reverseDnsName = "Pixel-7-Pro.lan"))); assertEquals("Android", r.os); assertEquals(DeviceType.PHONE, r.type)
    }
    @Test fun `printer by ipp and ty txt`() {
        val r = id.identify(ev(DiscoveredHost(ip, mdnsServiceTypes = setOf("_ipp._tcp"), mdnsTxt = mapOf("_ipp._tcp" to mapOf("ty" to "HP OfficeJet Pro 9010")))))
        assertEquals(DeviceType.PRINTER, r.type); assertEquals("HP OfficeJet Pro 9010", r.model)
    }
    @Test fun `home assistant and proxmox by title or port`() {
        assertEquals("Home Assistant", id.identify(ev(ports = setOf(8123))).model)
        assertEquals("Proxmox VE", id.identify(ev(DiscoveredHost(ip, httpTitle = "pve - Proxmox Virtual Environment"))).model)
    }
    @Test fun `ssh banner os`() {
        val r = id.identify(ev(services = mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED))))
        assertEquals("Linux (Ubuntu)", r.os)
    }
    @Test fun `camera by rtsp`() { assertEquals(DeviceType.CAMERA, id.identify(ev(ports = setOf(554, 80))).type) }
    @Test fun `gateway flag`() { assertEquals(DeviceType.ROUTER, id.identify(ev(DiscoveredHost(ip, isGateway = true))).type) }
    @Test fun `oui vendor alone is a weak vendor`() {
        val r = id.identify(ev(oui = "Espressif Inc.")); assertEquals("Espressif Inc.", r.vendor); assertEquals(50, r.confidence)
    }
    @Test fun `no evidence is unknown`() { assertEquals(DeviceIdentity.UNKNOWN, id.identify(ev())) }
    @Test fun `every rule in the file contributes for its own synthetic evidence`() {
        // Differential check: removing the rule under test must change the outcome, so a typo in a pattern, field or ports list fails here.
        for (r in rules.filter { it.field != "oui" }) {
            val e = SyntheticEvidence.forRule(r, ip)
            assertNotEquals("rule ${r.id} contributed nothing", DeviceIdentifier(rules - r).identify(e), DeviceIdentifier(rules).identify(e))
        }
    }
}
