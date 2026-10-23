package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import com.northwalessolutions.netprobe.engine.discovery.SnmpResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * One SNMP request with one retry (spec 6.4): request ids increment, a reply carrying another id, from another address or that does
 * not parse is ignored, each attempt waits at most [timeoutMs] in total. The socket is bound through currentBinder() as Snmp.query
 * does; the blocking send and receive run inside CancellableIo.closing, so a stop closes the socket at once. Read-only: GET and
 * GETNEXT only, never SET.
 */
class UdpSnmpExchange(private val ip: InetAddress, private val port: Int = SNMP_PORT, private val timeoutMs: Int = SnmpWalkTool.SNMP_TIMEOUT_MS,
                      private val community: String = "public", private val version: Int = SnmpPdu.VERSION_2C) : SnmpExchange {
    private var nextId = Random.nextInt(1, FIRST_ID_LIMIT)          // a random start, so a stale or forged reply rarely matches

    override suspend fun getNext(oids: List<String>): SnmpStep = send(SnmpPdu.GETNEXT, oids)
    override suspend fun get(oids: List<String>): SnmpStep = send(SnmpPdu.GET, oids)

    private suspend fun send(pduType: Int, oids: List<String>): SnmpStep {
        val binder = currentBinder()
        try {
            DatagramSocket().use { socket ->
                binder?.bindOrFail(socket)
                repeat(ATTEMPTS) {
                    val id = nextId++
                    val request = SnmpPdu.build(pduType, oids, community, id, version)
                    val reply = CancellableIo.closing(socket) {
                        socket.send(DatagramPacket(request, request.size, ip, port))
                        receiveMatching(socket, id)
                    }
                    if (reply != null) return if (reply.errorStatus != 0) SnmpStep.Error else SnmpStep.Ok(reply.varbinds)
                }
            }
        } catch (e: BindFailedException) {
            throw e                                                    // the runner reports a lost Wi-Fi binding (spec 4.6)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()                   // a socket closed by a stop is a stop, not an error
            return SnmpStep.Error
        }
        return SnmpStep.Timeout
    }

    /** Waits up to [timeoutMs] in total for a reply with [id] from the target; anything else is skipped. */
    private fun receiveMatching(socket: DatagramSocket, id: Int): SnmpResponse? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(MAX_DATAGRAM)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            socket.soTimeout = left.toInt()
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { return null }
            if (packet.address != ip || packet.port != port) continue
            val r = SnmpPdu.parse(buf.copyOf(packet.length)) ?: continue
            if (r.requestId == id) return r
        }
    }

    private companion object { const val SNMP_PORT = 161; const val ATTEMPTS = 2; const val MAX_DATAGRAM = 65_535; const val FIRST_ID_LIMIT = 1 shl 30 }
}
