package com.northwalessolutions.netprobe.engine.common

import com.northwalessolutions.netprobe.engine.discovery.HostProber
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.discovery.NetBios
import com.northwalessolutions.netprobe.engine.discovery.Snmp
import com.northwalessolutions.netprobe.engine.discovery.Sweep
import com.northwalessolutions.netprobe.engine.portscan.PortScanEngine
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.net.*

class ProbeBindTest {
    private class RecordingBinder(private val fail: Boolean = false) : SocketBinder {
        val bound = mutableListOf<String>()
        override fun bind(socket: Socket) { if (fail) throw SocketException("EPERM"); bound += "tcp connected=${socket.isConnected}" }
        override fun bind(socket: DatagramSocket) { if (fail) throw SocketException("EPERM"); bound += "udp" }
        override fun openConnection(url: URL): URLConnection { bound += "url ${url.host}"; return url.openConnection(Proxy.NO_PROXY) }
    }
    private val lo = InetAddress.getLoopbackAddress() as Inet4Address

    @Test fun `binder is applied before connect`() = runBlocking {
        ServerSocket(0).use { server ->
            val b = RecordingBinder()
            assertTrue(Probe.connect(lo, server.localPort, 500, binder = b) is Probe.Outcome.Connected)
            assertEquals(listOf("tcp connected=false"), b.bound)
        }
    }
    @Test fun `the bound network in the coroutine context is used when no binder is passed`() = runBlocking {
        ServerSocket(0).use { server ->
            val b = RecordingBinder()
            withContext(BoundNetwork(b)) { Probe.connect(lo, server.localPort, 500) }
            assertEquals(1, b.bound.size)
        }
    }
    @Test fun `no binder leaves connect as it was`() = runBlocking {
        ServerSocket(0).use { server -> assertTrue(Probe.connect(lo, server.localPort, 500) is Probe.Outcome.Connected) }
    }
    @Test fun `binder failure surfaces as BindFailedException and never connects`() = runBlocking {   // Review Focus 4
        ServerSocket(0).use { server ->
            server.soTimeout = 300
            try { Probe.connect(lo, server.localPort, 500, binder = RecordingBinder(fail = true)); fail("expected BindFailedException") }
            catch (_: BindFailedException) { }
            assertThrows(SocketTimeoutException::class.java) { server.accept() }; Unit   // nothing reached the server on default routing
        }
    }
    @Test fun `sweep propagates a bind failure instead of counting it as a dead host`() = runBlocking {
        val sweep = Sweep(HostProber(ports = listOf(80), timeoutMs = 200), concurrency = 4)
        try { withContext(BoundNetwork(RecordingBinder(fail = true))) { sweep.run(sequenceOf(lo)).toList() }; fail("expected BindFailedException") }
        catch (_: BindFailedException) { }
    }
    @Test fun `udp and http enrichment honour the bound network`() = runBlocking {
        val b = RecordingBinder()
        withContext(BoundNetwork(b)) { NetBios.query(lo, 100); Snmp.query(lo, 100); HttpTitleFetcher().fetch(lo, ServerSocket(0).use { it.localPort }, false) }
        assertEquals(listOf("udp", "udp", "url 127.0.0.1"), b.bound)
    }
    @Test fun `udp enrichment rethrows a bind failure`() = runBlocking {
        try { withContext(BoundNetwork(RecordingBinder(fail = true))) { NetBios.query(lo, 100) }; fail("expected BindFailedException") }
        catch (_: BindFailedException) { }
    }
    @Test fun `port scan under a failing bound network throws instead of reporting closed ports`() = runBlocking {
        val scan = PortScanEngine().scan(lo, ScanProfile.Custom(listOf(80, 443)))
        try { withContext(BoundNetwork(RecordingBinder(fail = true))) { scan.toList() }; fail("expected BindFailedException") }
        catch (_: BindFailedException) { }
    }
    @Test fun `bindOrFail rethrows cancellation untouched`() {
        val cancelling = object : SocketBinder {
            override fun bind(socket: Socket) { throw kotlinx.coroutines.CancellationException("c") }
            override fun bind(socket: DatagramSocket) { throw kotlinx.coroutines.CancellationException("c") }
            override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException()
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { cancelling.bindOrFail(Socket()) }
    }
}
