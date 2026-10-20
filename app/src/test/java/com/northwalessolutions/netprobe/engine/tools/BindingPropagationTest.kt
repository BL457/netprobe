package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.smb.TcpSmbTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Every tool socket is bound through the BoundNetwork on the coroutine context, which is how ScanBinding.run routes a run over
 * Wi-Fi past a VPN on a client's network. The four calls below are exactly the socket seams AppContainer's tool factories use:
 * HttpClient.request (dir-bruteforce, fingerprint), TlsPeek.details (tls-certificate), UdpSnmpExchange (snmp-walk) and
 * Probe.connect into TcpSmbTransport (smb-session). A refactor that drops the context (a stray withContext(EmptyCoroutineContext
 * + Dispatchers.IO), a socket opened without Probe.connect) fails the first test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BindingPropagationTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val closers = mutableListOf<Closeable>()
    @After fun closeAll() { closers.forEach { runCatching { it.close() } } }

    private class RecordingBinder : SocketBinder {
        val tcp = AtomicInteger(0); val udp = AtomicInteger(0)
        override fun bind(socket: Socket) { tcp.incrementAndGet() }
        override fun bind(socket: DatagramSocket) { udp.incrementAndGet() }
        override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException("tools never open a URLConnection")
    }
    /** A VPN in lockdown: every bind is refused. */
    private object RefusingBinder : SocketBinder {
        override fun bind(socket: Socket): Unit = throw SecurityException("vpn lockdown")
        override fun bind(socket: DatagramSocket): Unit = throw SecurityException("vpn lockdown")
        override fun openConnection(url: URL): URLConnection = throw SecurityException("vpn lockdown")
    }
    /**
     * A loopback server that answers every connection with an empty 404 and counts the connections it accepted. It reads the request
     * (or ClientHello) first: closing with it unread sends an RST, which TlsPeek rightly reads as a dead host and never retries on the IP.
     */
    private fun http404(accepted: AtomicInteger = AtomicInteger(0)): ServerSocket {
        val s = ServerSocket(0, 50, lo); closers += s
        thread(isDaemon = true) {
            while (!s.isClosed) runCatching {
                s.accept().use { c -> accepted.incrementAndGet(); c.soTimeout = READ_MS; runCatching { c.getInputStream().read(ByteArray(REQUEST_BYTES)) }; c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
        }
        return s
    }

    @Test fun `http, tls, snmp and smb sockets all reach the context binder`() = runBlocking {
        val binder = RecordingBinder(); val tcp = http404()
        val udp = DatagramSocket(0, lo); closers += udp                               // never answers: the exchange times out after binding
        withContext(BoundNetwork(binder)) {
            HttpClient(500).request(lo, tcp.localPort, false, "GET", "/")
            TlsPeek().details(lo, tcp.localPort, "nas.lan", 500)                    // the named handshake fails on plain HTTP, then the IP retry
            UdpSnmpExchange(lo, udp.localPort, 200).getNext(listOf("1.3.6.1.2.1.1"))
            (Probe.connect(lo, tcp.localPort, 500, keepOpen = true) as? Probe.Outcome.Connected)?.socket?.let { TcpSmbTransport(it, 500).close() }
        }
        assertEquals("http 1, tls 2 (named, then IP), smb 1", 4, binder.tcp.get())
        assertEquals("snmp 1", 1, binder.udp.get())
    }
    @Test fun `a refusing binder fails the run with the bind wording and opens no unbound socket`() = runTest {
        val accepted = AtomicInteger(0); val tcp = http404(accepted)
        val tool = HttpFingerprintTool({ t -> HttpGet { tls, port, path -> HttpClient(500).request(t.ip, port, tls, "GET", path) } }, emptyList())
        val rig = Rig(backgroundScope, tool, bind = { _, _, block -> withContext(BoundNetwork(RefusingBinder)) { block() } })
        rig.runner.start(ToolTarget(7, lo, tcp.localPort, tls = false, serverName = null), ToolId.HTTP_FINGERPRINT, ToolOptions.None); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Tool skipped: could not bind to Wi-Fi"), rig.store.finished.single())
        Thread.sleep(SETTLE_MS)
        assertEquals("no connection reached the server", 0, accepted.get())
    }
    private companion object { const val SETTLE_MS = 200L; const val READ_MS = 1000; const val REQUEST_BYTES = 4096 }
}
