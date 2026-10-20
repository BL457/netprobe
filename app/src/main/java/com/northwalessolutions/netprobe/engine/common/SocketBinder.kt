package com.northwalessolutions.netprobe.engine.common

import android.net.Network
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Routes a socket over one network (extended spec 5.5). Production: [NetworkBinder]; tests: a recording fake. */
interface SocketBinder {
    fun bind(socket: Socket)
    fun bind(socket: DatagramSocket)
    fun openConnection(url: URL): URLConnection
}

class NetworkBinder(private val network: Network) : SocketBinder {
    override fun bind(socket: Socket) = network.bindSocket(socket)
    override fun bind(socket: DatagramSocket) = network.bindSocket(socket)
    /** NO_PROXY as for every LAN fetch: never hand a probe to a Wi-Fi proxy or PAC. */
    override fun openConnection(url: URL): URLConnection = network.openConnection(url, Proxy.NO_PROXY)
}

/** A bind that fails (for example a VPN that disallows bypass) ends the extended phase; it is never read as a dead host. */
class BindFailedException(cause: Throwable) : IOException("Could not bind to the Wi-Fi network", cause)

/** Put on the coroutine context for the extended phase so sweeps and enrichment bind without new parameters. */
class BoundNetwork(val binder: SocketBinder) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BoundNetwork>
}

suspend fun currentBinder(): SocketBinder? = currentCoroutineContext()[BoundNetwork]?.binder

fun SocketBinder.bindOrFail(socket: Socket) = try { bind(socket) } catch (e: Exception) { throw BindFailedException(e) }
fun SocketBinder.bindOrFail(socket: DatagramSocket) = try { bind(socket) } catch (e: Exception) { throw BindFailedException(e) }
