package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 3.6 and 9.1 test 19: platforms outrank the apps they run (controller ruling on Gruff M6). */
class RulesPrecedenceTest {
    private val id = DeviceIdentifier(IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()))
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private fun http(title: String) = ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title)
    private fun ident(services: Map<Int, ServiceInfo>, ports: Set<Int> = emptySet(), host: DiscoveredHost = DiscoveredHost(ip)) =
        id.identify(HostEvidence(host, ports + services.keys, services, null))
    private val pi = DiscoveredHost(ip, reverseDnsName = "raspberrypi.lan")

    @Test fun `synology running plex, jellyfin and home assistant reads synology nas with three software names`() {
        val r = ident(mapOf(5000 to http("DiskStation - Synology DiskStation"), 32400 to http("Plex"), 8096 to http("Jellyfin"), 8123 to http("Home Assistant")), setOf(5001))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type)
        assertEquals(listOf("Home Assistant", "Plex Media Server", "Jellyfin"), r.software)
    }
    @Test fun `unraid running jellyfin and frigate reads unraid nas`() {
        val r = ident(mapOf(80 to http("Tower/Main - Unraid"), 8096 to http("Jellyfin"), 8971 to http("Frigate")))
        assertEquals("Unraid", r.model); assertEquals(DeviceType.NAS, r.type); assertEquals(listOf("Jellyfin", "Frigate NVR"), r.software)
    }
    @Test fun `a windows pc running plex reads windows computer`() {
        val r = ident(mapOf(32400 to http("Plex")), setOf(135, 445))
        assertEquals("Windows", r.os); assertEquals(DeviceType.COMPUTER, r.type); assertEquals(listOf("Plex Media Server"), r.software)
    }
    @Test fun `a raspberry pi running the unifi network app reads raspberry pi computer`() {
        val r = ident(mapOf(8443 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, title = "UniFi Network", certCn = "UniFi")), host = pi)
        assertEquals("Raspberry Pi", r.vendor); assertEquals(DeviceType.COMPUTER, r.type)
        assertNotEquals("Ubiquiti", r.vendor); assertNotEquals(DeviceType.ROUTER, r.type); assertEquals(listOf("UniFi Network"), r.software)
    }
    @Test fun `a pi with xrdp and a raspbian ssh banner reads linux`() {
        val r = ident(mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.2p1", "Raspbian-1+deb12u1", Confidence.CONFIRMED), 3389 to ServiceInfo("RDP", confidence = Confidence.CONFIRMED)))
        assertTrue("os was ${r.os}", r.os!!.startsWith("Linux")); assertEquals(DeviceType.COMPUTER, r.type); assertEquals("Raspberry Pi", r.vendor)
    }
    @Test fun `srm beats dsm on type`() {
        val r = ident(mapOf(5000 to http("Synology Router - SRM")))
        assertEquals(DeviceType.ROUTER, r.type); assertEquals("Synology Router", r.model)
    }
    @Test fun `pi-hole on a pi keeps computer`() {
        val r = ident(mapOf(80 to http("Pi-hole - Login")), host = pi)
        assertEquals(DeviceType.COMPUTER, r.type); assertEquals(listOf("Pi-hole"), r.software)
    }
    @Test fun `openssh loses to the windows port pair on os`() {
        assertEquals("Windows", ident(mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.5", null, Confidence.CONFIRMED)), setOf(135, 445)).os)
    }
    @Test fun `the gateway keeps router over any title`() {
        val r = ident(mapOf(80 to http("hue personal wireless lighting"), 443 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, title = "VMware ESXi")),
            host = DiscoveredHost(ip, isGateway = true))
        assertEquals(DeviceType.ROUTER, r.type)
    }
}
