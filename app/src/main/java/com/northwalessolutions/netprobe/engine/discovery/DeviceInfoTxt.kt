package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Apple devices publish "<Name>._device-info._tcp.local TXT model=..." but no PTR record for the type,
 * so NsdManager (PTR browse) never returns it. We ask for the TXT record of a known instance name directly.
 */
object DeviceInfoTxt {
    const val SERVICE = "_device-info._tcp"
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val MAX_LABEL_BYTES = 63
    private const val TYPE_TXT = 16
    private const val POLL_MS = 250
    private const val RECV_BUFFER = 1500

    /** One question, QTYPE TXT, QCLASS IN with the QU bit (0x8001). Null when the instance label cannot be encoded. */
    fun buildQuery(instance: String): ByteArray? {
        val label = instance.toByteArray(Charsets.UTF_8)
        if (label.isEmpty() || label.size > MAX_LABEL_BYTES) return null
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))   // id 0, flags 0, QDCOUNT 1
        for (l in listOf(label, "_device-info".toByteArray(), "_tcp".toByteArray(), "local".toByteArray())) { out.write(l.size); out.write(l) }
        out.write(0)
        out.write(byteArrayOf(0, TYPE_TXT.toByte(), 0x80.toByte(), 0x01))
        return out.toByteArray()
    }

    fun parseTxtResponse(resp: ByteArray): Map<String, String>? {
        try {
            if (resp.size < 12) return null
            if ((u16(resp, 2) and 0x8000) == 0) return null                 // not a response
            val questions = u16(resp, 4)
            val records = u16(resp, 6) + u16(resp, 8) + u16(resp, 10)
            var pos = 12
            for (i in 0 until questions) { val r = readName(resp, pos) ?: return null; pos = r.second + 4 }
            for (i in 0 until records) {
                val (owner, nameEnd) = readName(resp, pos) ?: return null
                if (nameEnd + 10 > resp.size) return null
                val type = u16(resp, nameEnd)
                val rdLen = u16(resp, nameEnd + 8)
                val rdStart = nameEnd + 10
                if (rdStart + rdLen > resp.size) return null
                if (type == TYPE_TXT && owner.endsWith("$SERVICE.local", ignoreCase = true)) {
                    return parseTxtRdata(resp, rdStart, rdStart + rdLen).takeIf { it.isNotEmpty() }
                }
                pos = rdStart + rdLen
            }
            return null
        } catch (_: Exception) { return null }
    }

    // The wire helpers moved to DnsWire (3C spec 3.3), shared with the listener; behaviour unchanged (no label limit here).
    private fun u16(b: ByteArray, at: Int) = DnsWire.u16(b, at)
    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? = DnsWire.readName(b, start)
    private fun parseTxtRdata(b: ByteArray, start: Int, end: Int): Map<String, String> = DnsWire.parseTxtRdata(b, start, end)

    /** Sends the question to the mDNS group and unicast to [host]; returns the TXT map from the first reply sent by [host]. */
    suspend fun query(instance: String, host: Inet4Address, timeoutMs: Int = 1000): Map<String, String>? {
        val q = buildQuery(instance) ?: return null
        return try {
            runInterruptible(Dispatchers.IO) {
                DatagramSocket().use { s ->
                    s.soTimeout = POLL_MS
                    s.send(DatagramPacket(q, q.size, host, MDNS_PORT))
                    runCatching { s.send(DatagramPacket(q, q.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT)) }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(RECV_BUFFER)
                    var found: Map<String, String>? = null
                    while (found == null && System.currentTimeMillis() < deadline) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                        if (p.address == host) found = parseTxtResponse(buf.copyOf(p.length))
                    }
                    found
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
