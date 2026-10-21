package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.net.*

data class SsdpResponse(val from: Inet4Address, val location: String?, val server: String?, val st: String?, val usn: String?)

object SsdpParser {
    fun parse(from: Inet4Address, text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().startsWith("HTTP/1.1 200")) return null
        val h = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        return SsdpResponse(from, h["location"], h["server"], h["st"], h["usn"])
    }

    /**
     * The listener's NOTIFY (3C spec 3.3): "NOTIFY * HTTP/1.1" with NTS ssdp:alive only; byebye, M-SEARCH requests and search responses are
     * null. [SsdpResponse.st] carries the NT header; the address is the packet source, never a header.
     */
    fun parseNotify(from: Inet4Address, text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().trim().equals(NOTIFY_LINE, ignoreCase = true)) return null
        val h = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        if (!h["nts"].orEmpty().equals(ALIVE, ignoreCase = true)) return null
        return SsdpResponse(from, h["location"], h["server"], h["nt"], h["usn"])
    }
    private const val NOTIFY_LINE = "NOTIFY * HTTP/1.1"
    private const val ALIVE = "ssdp:alive"
}

object MacExtract {
    private val colon = Regex("(?<![0-9a-fA-F])(?<![0-9a-fA-F]{2}:)([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})(?![0-9a-fA-F])(?!:[0-9a-fA-F]{2})")
    // Only a version-1 (time-based) UUID carries a real MAC in its node field; version 4 UDNs are random.
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-([0-9a-fA-F])[0-9a-fA-F]{3}-[0-9a-fA-F]{4}-([0-9a-fA-F]{12})")
    private val bare = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{12})(?![0-9a-fA-F])")
    fun fromText(s: String): String? {
        colon.find(s)?.let { return it.groupValues[1].lowercase() }
        uuid.find(s)?.let { m -> return if (m.groupValues[1] == "1") m.groupValues[2].lowercase().chunked(2).joinToString(":") else null }
        bare.find(s)?.let { m -> return m.groupValues[1].lowercase().chunked(2).joinToString(":") }
        return null
    }
}

class SsdpSearch(private val sendRepeats: Int = 3, private val listenMs: Long = 4000) {
    fun search(): Flow<SsdpResponse> = channelFlow {
        val group = InetAddress.getByName("239.255.255.250")
        val msearch = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
        val socket = MulticastSocket().apply { soTimeout = 500; broadcast = true }
        try {
            launch(Dispatchers.IO) {
                repeat(sendRepeats) { runCatching { socket.send(DatagramPacket(msearch, msearch.size, group, 1900)) }; delay(1000) }
            }
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + listenMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                val got = runInterruptible(Dispatchers.IO) { try { socket.receive(p); true } catch (_: SocketTimeoutException) { false } }
                if (!got) continue
                val from = p.address as? Inet4Address ?: continue
                val r = SsdpParser.parse(from, String(p.data, 0, p.length, Charsets.ISO_8859_1)) ?: continue
                if (seen.add("${from.hostAddress}|${r.usn}")) send(r)
            }
        } finally { runCatching { socket.close() } }
    }
}
