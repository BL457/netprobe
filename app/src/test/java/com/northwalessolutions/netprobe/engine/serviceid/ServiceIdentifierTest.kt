package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ServiceIdentifierTest {
    private val servers = mutableListOf<ServerSocket>()
    private val table = PortTable.fromJson("""[{"port":22,"name":"SSH"},{"port":80,"name":"HTTP"},{"port":5900,"name":"VNC"},{"port":21,"name":"FTP"},{"port":8006,"name":"Proxmox"},{"port":443,"name":"HTTPS"}]""")
    private val lo = InetAddress.getLoopbackAddress()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    /** A one-shot server: optionally sends greeting, optionally reads a line then replies. */
    private fun serve(greeting: String? = null, reply: ((String) -> String)? = null): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) {
            runCatching {
                while (true) s.accept().use { c ->
                    greeting?.let { c.getOutputStream().write(it.toByteArray()); c.getOutputStream().flush() }
                    if (reply != null) {
                        val req = c.getInputStream().bufferedReader().readLine() ?: ""
                        c.getOutputStream().write(reply(req).toByteArray()); c.getOutputStream().flush()
                    }
                    Thread.sleep(50)
                }
            }
        }
        return s.localPort
    }
    private fun ident(port: Int, asPort: Int, ev: Evidence = Evidence()) = runBlocking {
        // identify() decides behaviour from the port number; tests remap by passing a reader bound to the real loopback port.
        ServiceIdentifier(table, BannerReader(timeoutMs = 800), portOverride = mapOf(asPort to port)).identify(lo, asPort, ev)
    }

    @Test fun `ssh server speaks first`() {
        val s = ident(serve(greeting = "SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n"), asPort = 22)
        assertEquals("OpenSSH", s.product); assertEquals("9.6p1", s.version); assertEquals(Confidence.CONFIRMED, s.confidence)
    }
    @Test fun `http head returns server header`() {
        val p = serve(reply = { "HTTP/1.1 200 OK\r\nServer: lighttpd/1.4.59\r\nContent-Length: 0\r\n\r\n" })
        val s = ident(p, asPort = 80)
        assertEquals("HTTP", s.name); assertEquals("lighttpd", s.product); assertEquals("1.4.59", s.version)
    }
    @Test fun `silent port falls back to port table name`() {
        val s = ident(serve(), asPort = 5900)
        assertEquals("VNC", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `mdns type confirms the port table name`() {
        val s = ident(serve(), asPort = 5900, ev = Evidence(mdnsTypes = setOf("_rfb._tcp")))
        assertEquals("VNC", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("mDNS _rfb._tcp", s.detail)
    }
    @Test fun `ftp greeting parsed`() {
        val s = ident(serve(greeting = "220 (vsFTPd 3.0.5)\r\n"), asPort = 21)
        assertEquals("vsFTPd", s.product); assertEquals("3.0.5", s.version)
    }

    private fun tlsIdent(port: Int, names: List<String>?) = runBlocking {
        val peek = object : TlsPeek() { override suspend fun certNames(ip: InetAddress, port: Int, timeoutMs: Int) = names }
        ServiceIdentifier(table, BannerReader(timeoutMs = 800), peek).identify(lo, port)
    }
    @Test fun `tls failure keeps port table name`() {
        val s = tlsIdent(8006, null)
        assertEquals("Proxmox", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `tls cert names confirm https`() {
        val s = tlsIdent(443, listOf("router.lan"))
        assertEquals("HTTPS", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertTrue(s.detail!!.contains("router.lan"))
    }
}
