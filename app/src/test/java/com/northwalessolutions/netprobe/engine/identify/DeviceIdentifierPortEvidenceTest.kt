package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 17, with inline rules so each field is tested apart from the rules file. */
class DeviceIdentifierPortEvidenceTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val host = DiscoveredHost(ip)
    private fun rule(id: String, field: String, pattern: String? = null, onPorts: List<Int>? = null, vendor: String? = null, model: String? = null,
                     os: String? = null, type: String? = null, software: String? = null, weight: Int = 70, anyPort: List<Int>? = null) =
        Rule(id = id, field = field, pattern = pattern, anyPort = anyPort, vendor = vendor, model = model, os = os, type = type, weight = weight,
            onPorts = onPorts, software = software)
    private fun ev(services: Map<Int, ServiceInfo>, h: DiscoveredHost = host, extraPorts: Set<Int> = emptySet()) = HostEvidence(h, services.keys + extraPorts, services, null)
    private fun http(title: String? = null, server: String? = null, realm: String? = null, poweredBy: String? = null) =
        ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title, server = server, realm = realm, poweredBy = poweredBy)
    private fun one(r: Rule, e: HostEvidence) = DeviceIdentifier(listOf(r)).identify(e)

    @Test fun `httpTitle matches a port title and the reason names the port`() {
        val r = one(rule("t", "httpTitle", "Synology", vendor = "Synology"), ev(mapOf(5000 to http(title = "DiskStation - Synology DiskStation"))))
        assertEquals("Synology", r.vendor); assertEquals(listOf("port 5000 httpTitle: DiskStation - Synology DiskStation"), r.reasons)
    }
    @Test fun `discovery evidence is tried first, then ports ascending`() {
        val rr = rule("t", "httpTitle", "Router", model = "x")
        assertEquals(listOf("httpTitle: Router A"), one(rr, ev(mapOf(8080 to http(title = "Router B")), host.copy(httpTitle = "Router A"))).reasons)
        assertEquals(listOf("port 80 httpTitle: Router C"), one(rr, ev(mapOf(8080 to http(title = "Router B"), 80 to http(title = "Router C")))).reasons)
    }
    @Test fun `onPorts excludes other ports and discovery titles`() {
        val r = rule("t", "httpTitle", "DiskStation", onPorts = listOf(5000, 5001), vendor = "Synology")
        assertEquals(DeviceIdentity.UNKNOWN, one(r, ev(mapOf(8080 to http(title = "DiskStation")), host.copy(httpTitle = "DiskStation"))))
        assertEquals("Synology", one(r, ev(mapOf(5001 to http(title = "DiskStation")))).vendor)
    }
    @Test fun `httpServer reads an rtsp port's server header`() {
        val rtsp = ServiceInfo("RTSP", "Hikvision", "1.0", null, Confidence.CONFIRMED, server = "Hikvision/1.0")
        val r = one(rule("s", "httpServer", "(Hikvision)", vendor = "\$1", type = "CAMERA"), ev(mapOf(554 to rtsp)))
        assertEquals("Hikvision", r.vendor); assertEquals(DeviceType.CAMERA, r.type); assertEquals(listOf("port 554 httpServer: Hikvision/1.0"), r.reasons)
    }
    @Test fun `httpRealm reads a port realm`() {
        val r = one(rule("r", "httpRealm", "NETGEAR", vendor = "NETGEAR"), ev(mapOf(80 to http(realm = "NETGEAR R7000"))))
        assertEquals(listOf("port 80 httpRealm: NETGEAR R7000"), r.reasons)
    }
    @Test fun `sshBanner reads every ssh port, not only 22`() {
        val ssh = ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED)
        val r = one(rule("s", "sshBanner", "Ubuntu", os = "Linux"), ev(mapOf(2222 to ssh)))
        assertEquals("Linux", r.os); assertEquals(listOf("port 2222 sshBanner: Ubuntu-3ubuntu13"), r.reasons)
        assertEquals(DeviceIdentity.UNKNOWN, one(rule("s", "sshBanner", "Ubuntu", os = "Linux"), ev(mapOf(2222 to ssh.copy(name = "HTTP")))))
    }
    @Test fun `poweredBy certName and certOrg`() {
        assertEquals(listOf("port 80 poweredBy: ASP.NET"), one(rule("p", "poweredBy", "ASP\\.NET", os = "Windows"), ev(mapOf(80 to http(poweredBy = "ASP.NET")))).reasons)
        val tls = ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = "fritz.box", certOrg = "Ubiquiti Inc.")
        assertEquals(listOf("port 443 certName: fritz.box"), one(rule("c", "certName", "fritz\\.box$", vendor = "AVM"), ev(mapOf(443 to tls))).reasons)
        assertEquals(listOf("port 443 certOrg: Ubiquiti Inc."), one(rule("o", "certOrg", "Ubiquiti", vendor = "Ubiquiti"), ev(mapOf(443 to tls))).reasons)
    }
    @Test fun `portBanner joins product, version and detail`() {
        val ftp = ServiceInfo("FTP", "Synology", null, "220 Synology FTP server ready.", Confidence.CONFIRMED)
        assertEquals(listOf("port 21 portBanner: Synology 220 Synology FTP server ready."),
            one(rule("b", "portBanner", "Synology", vendor = "Synology"), ev(mapOf(21 to ftp))).reasons)
    }
    @Test fun `service matches confirmed names only`() {
        val r = rule("r", "service", "^RDP$", os = "Windows", weight = 60)
        assertEquals(listOf("port 3389 service: RDP"), one(r, ev(mapOf(3389 to ServiceInfo("RDP", confidence = Confidence.CONFIRMED)))).reasons)
        assertEquals(DeviceIdentity.UNKNOWN, one(r, ev(mapOf(3389 to ServiceInfo("RDP", confidence = Confidence.PORT_TABLE)))))
    }
    @Test fun `software collects every matching app rule, distinct, in file order`() {
        val rules = listOf(rule("a", "httpTitle", "Grafana", software = "Grafana", type = "SERVER", weight = 40),
            rule("b", "anyPort", software = "Plex Media Server", weight = 40, anyPort = listOf(32400)),
            rule("c", "httpTitle", "Grafana", software = "Grafana", weight = 40))
        val r = DeviceIdentifier(rules).identify(ev(mapOf(3000 to http(title = "Grafana")), extraPorts = setOf(32400)))
        assertEquals(listOf("Grafana", "Plex Media Server"), r.software); assertEquals(DeviceType.SERVER, r.type); assertNull(r.model)
        assertEquals(listOf("port 3000 httpTitle: Grafana", "anyPort: 32400"), r.reasons)
    }
    @Test fun `router vendors are spelled one way`() {
        val rr = rule("v", "httpTitle", "\\b(netgear|tp-link|asus)\\b", vendor = "\$1", type = "ROUTER")
        assertEquals("NETGEAR", one(rr, ev(mapOf(80 to http(title = "netgear router")))).vendor)
        assertEquals("TP-Link", one(rr, ev(mapOf(80 to http(title = "TP-LINK Archer AX55")))).vendor)
        assertEquals("ASUS", one(rr, ev(mapOf(80 to http(title = "Asus RT-AX86U")))).vendor)
    }
    @Test fun `discovery evidence keeps the old reason form`() {
        assertEquals(listOf("httpTitle: pfSense - Login"),
            one(rule("t", "httpTitle", "pfSense", os = "pfSense"), ev(emptyMap(), host.copy(httpTitle = "pfSense - Login"))).reasons)
    }

    @Test fun `a software-only app rule beside a platform rule leaves the platform type and vendor and still lists the software`() {
        val rules = listOf(rule("app", "httpTitle", "Plex", software = "Plex Media Server", weight = 40),
            rule("platform", "httpTitle", "DiskStation", vendor = "Synology", type = "NAS", weight = 80))
        val r = DeviceIdentifier(rules).identify(ev(mapOf(5000 to http(title = "DiskStation"), 32400 to http(title = "Plex"))))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type); assertEquals(listOf("Plex Media Server"), r.software); assertEquals(80, r.confidence)
    }
    @Test fun `port evidence never lowers a higher host confidence`() {
        val rules = listOf(rule("upnp", "upnpManufacturer", "Acme", vendor = "Acme", weight = 100), rule("port", "httpTitle", "Widget", model = "Widget", weight = 60))
        val r = DeviceIdentifier(rules).identify(ev(mapOf(80 to http(title = "Widget")), host.copy(upnpManufacturer = "Acme")))
        assertEquals("Acme", r.vendor); assertEquals("Widget", r.model); assertEquals(100, r.confidence)
    }
}
