package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address

/** Per session: the bounded queue between the socket readers and the writer (3C spec 3.4). */
const val HEARD_QUEUE = 64
/** A (address, type or USN) key heard again within this window is dropped before the queue (spec 3.3). */
const val HEARD_REPEAT_MS = 60_000L
/** The repeat filter remembers at most this many keys (spec 3.3, G4). */
const val HEARD_REPEAT_KEYS = 1_024

/** One heard announcement, tagged with the identity key of the env its session was started on (spec 3.3, G5); [source] is the datagram's sender (G11). */
data class Heard(val identityKey: String, val host: DiscoveredHost, val repeatKey: String, val location: String? = null, val source: Inet4Address = host.ip)

/** Drops the oldest when the writer falls behind; cancelling it at the session's end discards whatever is left (spec 3.2). */
fun heardQueue(): Channel<Heard> = Channel(HEARD_QUEUE, BufferOverflow.DROP_OLDEST)

/** An LRU of last-admitted times (spec 3.3): bounded, per session, so a flood of distinct keys costs memory only up to [maxKeys]. */
class HeardRepeats(private val clock: () -> Long, private val windowMs: Long = HEARD_REPEAT_MS, private val maxKeys: Int = HEARD_REPEAT_KEYS) {
    private val last = object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxKeys
    }
    @Synchronized fun admit(key: String): Boolean {
        val now = clock()
        val previous = last[key]
        if (previous != null && now - previous < windowMs) return false
        last[key] = now
        return true
    }
    @Synchronized fun size(): Int = last.size
    private companion object { const val INITIAL_CAPACITY = 16; const val LOAD_FACTOR = 0.75f }
}

/** Heard packets in the scan's shapes plus Source.HEARD (spec 3.3); the MAC rules are the scan's (withDerivedMac, the USN MAC). */
object HeardPackets {
    private const val MDNS_KEY = "m|"
    private const val SSDP_KEY = "s|"

    /** [from] is the datagram's source; the address written is the A record's (a UniFi reflector's source is its gateway, inside the known ranges). */
    fun mdns(bytes: ByteArray, from: Inet4Address, identityKey: String): List<Heard> = MdnsPacket.parse(bytes).map { r ->
        val h = DiscoveredHost(r.ip, mdnsName = r.instanceName, mdnsServiceTypes = setOf(r.type), mdnsTxt = mapOf(r.type to r.txt),
            mdnsInstanceNames = setOf(r.instanceName), ipv6LinkLocal = r.ipv6.toSet(), sources = setOf(Source.MDNS, Source.HEARD)).withDerivedMac()
        Heard(identityKey, h, MDNS_KEY + r.ip.hostAddress + "|" + r.type, source = from)
    }

    fun ssdp(from: Inet4Address, bytes: ByteArray, identityKey: String): Heard? {
        val r = SsdpParser.parseNotify(from, String(bytes, Charsets.ISO_8859_1)) ?: return null
        val usnMac = r.usn?.let { MacExtract.fromText(it) }
        val h = DiscoveredHost(from, upnpServer = r.server, mac = usnMac, macSource = usnMac?.let { MacSource.UPNP_UDN },
            sources = setOf(Source.SSDP, Source.HEARD)).withDerivedMac()
        return Heard(identityKey, h, SSDP_KEY + from.hostAddress + "|" + (r.usn ?: r.st.orEmpty()), r.location)
    }
}
