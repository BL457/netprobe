package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.*
import kotlin.concurrent.thread

/** spec 12.1: TLS through HttpClient and TlsPeek.details against a loopback SSLServerSocket, SNI sent, and the retry without a name. */
class TlsLoopbackTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val servers = mutableListOf<SSLServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private fun serverContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/rsa2048.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, "changeit".toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
    /** Accepts up to [connections] handshakes, recording each SNI name sent (null when none); only [onlyName] is accepted when set. */
    private fun serve(onlyName: String?, connections: Int, reply: String?): Pair<Int, MutableList<String?>> {
        val ss = serverContext().serverSocketFactory.createServerSocket(0, 50, lo) as SSLServerSocket; servers += ss
        if (onlyName != null) ss.sslParameters = ss.sslParameters.apply { serverNames = null; sniMatchers = listOf(SNIHostName.createSNIMatcher(Regex.escape(onlyName))) }
        val names = Collections.synchronizedList(mutableListOf<String?>())
        thread(isDaemon = true) {
            repeat(connections) {
                runCatching {
                    val s = ss.accept() as SSLSocket
                    s.use {
                        it.startHandshake()
                        names += ((it.session as ExtendedSSLSession).requestedServerNames.firstOrNull() as? SNIHostName)?.asciiName
                        if (reply != null) {
                            val head = StringBuilder(); val input = it.inputStream
                            while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()) }
                            it.outputStream.write(reply.toByteArray()); it.outputStream.flush()
                        }
                    }
                }
            }
        }
        return ss.localPort to names
    }

    /** The server thread records a name after its own handshake returns, which can be just after the client's: wait for it (bounded). */
    private fun seen(names: MutableList<String?>, count: Int): List<String?> {
        val until = System.currentTimeMillis() + 2000
        while (names.size < count && System.currentTimeMillis() < until) Thread.sleep(10)
        return names.toList()
    }

    @Test fun `http client over tls sends the name as sni and as host`() = runBlocking {
        val (port, names) = serve("chnas.local", 1, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val r = HttpClient(2000).request(lo, port, true, "GET", "/", "chnas.local", null) as HttpResult.Ok
        assertEquals(200, r.response.status); assertEquals("ok", String(r.response.body))
        assertEquals(listOf<String?>("chnas.local"), seen(names, 1))
    }
    @Test fun `details sends the name and reads the chain`() = runBlocking {
        val (port, names) = serve("chnas.local", 1, null)
        val d = TlsPeek().details(lo, port, "chnas.local", 2000)!!
        assertEquals("chnas.local", d.serverNameSent); assertTrue(d.chain.first().subjectX500Principal.name.contains("rsa.lan"))
        assertEquals(listOf<String?>("chnas.local"), seen(names, 1))
    }
    @Test fun `a refused name is retried once without a name`() = runBlocking {
        val (port, names) = serve("chnas.local", 2, null)
        val d = TlsPeek().details(lo, port, "wrong.name", 2000)!!
        assertNull(d.serverNameSent)
        assertEquals(listOf<String?>(null), seen(names, 1))        // the named attempt failed its handshake and recorded nothing
    }
    @Test fun `a refused connection is not retried`() = runBlocking {
        val port = java.net.ServerSocket(0, 1, lo).use { it.localPort }   // closed again: nothing listens
        assertNull(TlsPeek().details(lo, port, "chnas.local", 2000))
    }
}
