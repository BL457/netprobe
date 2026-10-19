package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpTitleTest {
    @Test fun `title parsing`() {
        assertEquals("Archer AX55 & Login", HttpTitleParser.title("<html><head><TITLE>\n  Archer   AX55 &amp; Login </TITLE></head>"))
        assertNull(HttpTitleParser.title("<html><head></head><body>no title</body>"))
        assertNull(HttpTitleParser.title("<title>   </title>"))
        assertEquals(120, HttpTitleParser.title("<title>" + "x".repeat(500) + "</title>")!!.length)
        assertEquals("TP-LINK", HttpTitleParser.realm("Basic realm=\"TP-LINK\""))
        assertEquals("router", HttpTitleParser.realm("Digest realm=router, qop=auth"))
        assertNull(HttpTitleParser.realm(null))
    }

    private val servers = mutableListOf<ServerSocket>()
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }
    private fun serve(body: (String) -> String): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { while (true) s.accept().use { c ->
            val first = c.getInputStream().bufferedReader().readLine() ?: ""
            c.getOutputStream().write(body(first).toByteArray(Charsets.ISO_8859_1)); c.getOutputStream().flush() } } }
        return s.localPort
    }
    @Test fun `fetches title server and realm`() = runBlocking {
        val p = serve { "HTTP/1.1 401 Unauthorized\r\nServer: Boa/0.94\r\nWWW-Authenticate: Basic realm=\"NETGEAR R7000\"\r\nContent-Length: 42\r\n\r\n<html><title>Netgear Router</title></html>" }
        val r = HttpTitleFetcher().fetch(lo, p, tls = false)!!
        assertEquals("Netgear Router", r.title); assertEquals("Boa/0.94", r.server); assertEquals("NETGEAR R7000", r.authRealm); assertEquals(401, r.status)
    }
    @Test fun `follows one same host redirect`() = runBlocking {
        lateinit var portHolder: IntArray
        val p = serve { first -> if (first.startsWith("GET / ")) "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${portHolder[0]}/login\r\nContent-Length: 0\r\n\r\n"
                                 else "HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\n<title>Login page here</title>" }
        portHolder = intArrayOf(p)
        assertEquals("Login page here", HttpTitleFetcher().fetch(lo, p, tls = false)!!.title)
    }
    @Test fun `oversized body is capped and non utf8 bytes do not throw`() = runBlocking {
        val p = serve { "HTTP/1.1 200 OK\r\n\r\n<title>ÿþ café</title>" + "y".repeat(200_000) }
        val r = HttpTitleFetcher(maxBytes = 4096).fetch(lo, p, tls = false)!!
        assertNotNull(r.title)
    }
    @Test fun `closed port returns null`() = runBlocking {
        val closed = ServerSocket(0).also { it.close() }.localPort
        assertNull(HttpTitleFetcher(timeoutMs = 500).fetch(lo, closed, tls = false))
    }
}
