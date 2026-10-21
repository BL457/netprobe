package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/** One received packet: the IPv4 sender and how many bytes of the caller's buffer it filled. */
data class Datagram(val from: Inet4Address, val length: Int)

/** A listening socket (3C spec 3.3). */
interface HeardSocket {
    /** Suspends until a packet arrives; null for a non-IPv4 sender; throws an IOException once the socket is closed. */
    suspend fun receive(into: ByteArray): Datagram?
    fun close()
}

/** Opens a joined multicast socket for one group and port on the env's network, or null when it cannot (spec 3.3). */
fun interface ListenSockets {
    fun open(group: String, port: Int, env: NetworkEnv): HeardSocket?
}

/**
 * Production sockets (spec 3.3): MulticastSocket(null), SO_REUSEADDR (the system mDNS service holds 5353 too), bound to the group port,
 * bound to the Wi-Fi or Ethernet Network, then joined on that network's interface. A null interface or no binder opens nothing (G11): never
 * joinGroup(addr, null), which could join on a VPN's tun device. Reads close the socket at once on cancellation (CancellableIo).
 */
class MulticastListenSockets(
    private val binderFor: (NetworkEnv) -> SocketBinder?,
    private val interfaceFor: (Inet4Address) -> NetworkInterface? = { NetworkInterface.getByInetAddress(it) },
    private val newSocket: () -> MulticastSocket = { MulticastSocket(null as SocketAddress?) },
    private val log: (Throwable) -> Unit = {},
) : ListenSockets {
    override fun open(group: String, port: Int, env: NetworkEnv): HeardSocket? {
        val iface = (try { interfaceFor(env.interfaceIp) } catch (e: Exception) { log(e); null }) ?: return null
        val binder = binderFor(env) ?: return null
        val socket = newSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            binder.bind(socket)
            socket.joinGroup(InetSocketAddress(InetAddress.getByName(group), port), iface)
            Opened(socket)
        } catch (e: Exception) {
            log(e)
            runCatching { socket.close() }   // cleanup only: the socket never opened, so a failed close changes nothing
            null
        }
    }

    private class Opened(private val socket: MulticastSocket) : HeardSocket {
        override suspend fun receive(into: ByteArray): Datagram? = withContext(Dispatchers.IO) {
            val p = DatagramPacket(into, into.size)
            CancellableIo.closing(socket) { socket.receive(p) }
            (p.address as? Inet4Address)?.let { Datagram(it, p.length) }
        }
        override fun close() { socket.close() }
    }
}
