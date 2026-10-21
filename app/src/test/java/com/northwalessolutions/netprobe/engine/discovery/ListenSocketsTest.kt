package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.IOException
import java.net.*

/** 3C spec 8.1 item 15, G11 (Review Focus 5). Port 0 everywhere: no test binds 5353 or 1900 on the PC. */
class ListenSocketsTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private val env = NetworkEnv(Ipv4.parse("10.0.3.50")!!, 24, Ipv4.parse("10.0.3.1")!!, emptyList(), "Home", null, Transport.WIFI)
    private fun loopback(): NetworkInterface? = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())

    @Test fun `a null interface opens no socket, so nothing joins on a default or VPN interface`() {
        var created = 0
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { null }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `an interface lookup that throws opens nothing`() {
        var created = 0
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { throw SocketException("interface gone") }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.SSDP_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `no Wi-Fi or Ethernet binder opens no socket`() {
        val lo = loopback(); assumeNotNull(lo)
        var created = 0
        val s = MulticastListenSockets({ null }, interfaceFor = { lo }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `a socket that cannot join is closed and reported as not open`() {
        val lo = loopback(); assumeNotNull(lo)
        var closed = false
        val failing = object : MulticastSocket(null as SocketAddress?) {
            override fun joinGroup(mcastaddr: SocketAddress?, netIf: NetworkInterface?) { throw IOException("no multicast route") }
            override fun close() { closed = true; super.close() }
        }
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { lo }, newSocket = { failing })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertTrue(closed)
    }
    /**
     * Plan review G5: the system mDNS daemon holds 5353 with SO_REUSEADDR; ours must bind the same port beside it. The "daemon" here binds an
     * ephemeral port (no test binds the PC's real 5353) and the group join is stubbed, so only the shared bind is under test.
     */
    @Test fun `a listener socket binds the mDNS port beside a daemon that holds it with SO_REUSEADDR`() {
        val lo = loopback(); assumeNotNull(lo)
        val daemon = MulticastSocket(null as SocketAddress?).apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        try {
            var ours: MulticastSocket? = null
            val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { lo }, newSocket = {
                object : MulticastSocket(null as SocketAddress?) { override fun joinGroup(mcastaddr: SocketAddress?, netIf: NetworkInterface?) {} }.also { ours = it }
            })
            val opened = s.open(ListenController.MDNS_GROUP, daemon.localPort, env)
            assertNotNull("bound beside the daemon", opened)
            assertTrue(ours!!.reuseAddress); assertEquals(daemon.localPort, ours!!.localPort)
            opened!!.close()
        } finally { daemon.close() }
        assertEquals(5353, ListenController.MDNS_PORT)
    }
}
