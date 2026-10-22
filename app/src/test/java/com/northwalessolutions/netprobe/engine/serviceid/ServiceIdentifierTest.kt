package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** identify(): the port-table floor, the probe ladder, and the mDNS and UPnP arbiters, over the shipped probe file and a scripted network. */
class ServiceIdentifierTest {
    private val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":80,"name":"HTTP"},{"port":5900,"name":"VNC"},{"port":21,"name":"FTP"},{"port":8006,"name":"Proxmox"},{"port":443,"name":"HTTPS"}]""")
    private val ip = InetAddress.getByName("10.0.3.59")
    private val net = FakeNetwork()
    private val probes = ServiceProbeRules.load(listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()).probes
    private val ladder = ServiceProbes(probes, net::transport, clock = { net.clock.now })
    private val identifier = ServiceIdentifier(table, ladder)
    private fun ident(port: Int, ev: Evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive) = runBlocking { identifier.identify(ip, port, ev, mode) }

    @Test fun `ssh server speaks first`() {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n") else Reply.SILENT }
        val s = ident(22)
        assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("SSH", s.name)
    }
    @Test fun `ftp greeting names the product and version`() {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("220 (vsFTPd 3.0.5)\r\n") else Reply.SILENT }
        val s = ident(21)
        assertEquals("vsFTPd", s.product); assertEquals("3.0.5", s.version); assertEquals("FTP", s.name)
    }
    @Test fun `silent port falls back to the port table name`() {
        val s = ident(5900)
        assertEquals("VNC", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `mdns type confirms the port table name`() {
        val s = ident(5900, Evidence(mdnsTypes = setOf("_rfb._tcp")))
        assertEquals("VNC", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("mDNS _rfb._tcp", s.detail)
    }
    @Test fun `a confirmed probe result beats the mdns guess and upnp only lifts a web port with nothing else`() {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("RFB 003.008\n") else Reply.SILENT }
        assertEquals("VNC", ident(5900, Evidence(mdnsTypes = setOf("_ssh._tcp"))).name)
        net.respond = { _, _, _ -> Reply.SILENT }
        val u = ident(80, Evidence(upnpServer = "Linux UPnP/1.0 Sonos"))
        assertEquals(Confidence.CONFIRMED, u.confidence); assertEquals("Linux UPnP/1.0 Sonos", u.detail)
    }
    @Test fun `tls failure keeps the port table name`() {
        val s = ident(8006)
        assertEquals("Proxmox", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `tls cert names confirm https and carry cn and o`() {
        net.cert = { CertInfo("router.lan", "Ubiquiti Inc.", listOf("router.lan")) }
        val s = ident(443)
        assertEquals("HTTPS", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertTrue(s.detail!!.contains("router.lan"))
        assertEquals("router.lan", s.certCn); assertEquals("Ubiquiti Inc.", s.certOrg)
    }
    @Test fun `passive http port gets exactly one head and no get`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("HEAD") == true) Reply.text("HTTP/1.1 200 OK\r\nServer: lighttpd/1.4.59\r\n\r\n") else Reply.SILENT }
        val s = ident(80)
        assertEquals("HTTP", s.name); assertEquals("lighttpd", s.product); assertEquals("1.4.59", s.version)
        assertEquals(1, net.sent().size)
    }
}
