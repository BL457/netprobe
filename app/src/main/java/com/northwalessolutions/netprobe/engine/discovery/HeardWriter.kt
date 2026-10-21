package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address

/** New rows one listening session may insert; after that heard hosts only update existing rows (3C spec 3.4 rule 4, G4). */
const val HEARD_NEW_MAX = 32
/** UPnP description GETs one session may make (controller ruling: bounded as discovery's fetch is). */
const val HEARD_FETCH_MAX = 32
/** A LOCATION longer than this is never fetched. */
const val HEARD_LOCATION_MAX_CHARS = 512
/**
 * Director ruling R2 (pending; default per the controller's ruling and recommendation, amendment A1): true fetches the bounded UPnP description of a
 * heard SSDP device inside the known ranges; false never sends anything from the listener.
 */
const val HEARD_FETCH_DESCRIPTIONS = true

/** What the writer needs to know about "now" (spec 3.4 rules 1 and 2), read at every packet. */
data class HeardContext(val networkId: Long, val identityKey: String, val forgotten: Boolean, val scanActive: Boolean, val undoPending: Boolean)

enum class HeardOutcome { WRITTEN, WRONG_NETWORK, BUSY, OUT_OF_RANGE, REMOVED, CAP_REACHED, NOT_STORED, FOREIGN_ADDRESS, SELF }

/** The writer's view of the app (production: ui.home.ContainerHeardSink; tests: a recording fake). */
interface HeardSink {
    /** The session network's gateway, the one non-host source allowed to announce for another address (final review S4a). */
    fun gateway(): Inet4Address?
    /** This phone's address on the session's network: never written as a device (final review S4b). */
    fun self(): Inet4Address?
    /** A visible (not manual-only) row for (network, ip). */
    suspend fun visible(networkId: Long, ip: String): Boolean
    /** The current network as Home resolved it, or null. */
    fun context(): HeardContext?
    suspend fun known(networkId: Long): KnownRanges
    suspend fun exists(networkId: Long, ip: String): Boolean
    /** (network, ip) removed this session or during a running scan. */
    fun removed(): Set<Pair<Long, String>>
    /** One bounded UPnP description fetch, bound to Wi-Fi; null on any failure. */
    suspend fun describe(location: String, host: Inet4Address): UpnpDevice?
    /** The guarded radar write (RadarCollector with upsertHeard); false when it was dropped there. */
    suspend fun store(networkId: Long, h: DiscoveredHost): Boolean
}

/**
 * One per listening session, fed serially by that session's queue (3C spec 3.4). [sessionNetworkId] is the network row the session resolved
 * for its env; a packet is written only while Home's current network is that same row with the packet's identity key, so nothing heard on
 * one network lands in another, including the window before Home catches up with a network change (G5). Counts are per session (G4).
 */
class HeardWriter(private val sessionNetworkId: Long, private val sink: HeardSink, private val fetchDescriptions: Boolean = HEARD_FETCH_DESCRIPTIONS) {
    private var inserted = 0
    private val fetchedIps = HashSet<String>()

    suspend fun accept(heard: Heard): HeardOutcome {
        gate(heard)?.let { return it }
        val ctx = sink.context() ?: return HeardOutcome.WRONG_NETWORK
        val ip = heard.host.ip
        val ipText: String = ip.hostAddress
        // Final review S4b: the phone's own announcements (loopback) are never a device.
        val self = sink.self()
        if (self != null && (ip == self || heard.source == self)) return HeardOutcome.SELF
        val known = sink.known(ctx.networkId)
        if (!known.contains(ip) || !known.contains(heard.source)) return HeardOutcome.OUT_OF_RANGE
        // Final review S4a: an answer for another address (a reflector's) counts only from the gateway or a host already visible.
        if (ip != heard.source && heard.source != sink.gateway() && !sink.visible(ctx.networkId, heard.source.hostAddress.orEmpty())) return HeardOutcome.FOREIGN_ADDRESS
        if ((ctx.networkId to ipText) in sink.removed()) return HeardOutcome.REMOVED
        val exists = sink.exists(ctx.networkId, ipText)
        if (!exists && inserted >= HEARD_NEW_MAX) return HeardOutcome.CAP_REACHED
        val host = described(heard, ipText)
        // Plan review G3: the fetch can take 2 s; a Forget, a scan or an Undo that landed meanwhile drops the write.
        gate(heard)?.let { return it }
        if (!sink.store(ctx.networkId, host)) return HeardOutcome.NOT_STORED
        if (!exists) inserted++
        return HeardOutcome.WRITTEN
    }

    /** Rules 1 and 2 (spec 3.4) against the context as it is now; null when the write may go ahead. */
    private fun gate(heard: Heard): HeardOutcome? {
        val ctx = sink.context()
        if (ctx == null || ctx.networkId != sessionNetworkId || ctx.forgotten || ctx.identityKey != heard.identityKey) return HeardOutcome.WRONG_NETWORK
        if (ctx.scanActive || ctx.undoPending) return HeardOutcome.BUSY
        return null
    }

    /** The UPnP description of an SSDP sighting, merged as discovery merges it (DiscoveryEngine's SSDP branch). */
    private suspend fun described(heard: Heard, ipText: String): DiscoveredHost {
        val location = heard.location?.takeIf { fetchDescriptions } ?: return heard.host
        if (location.length > HEARD_LOCATION_MAX_CHARS || fetchedIps.size >= HEARD_FETCH_MAX || !fetchedIps.add(ipText)) return heard.host
        val d = sink.describe(location, heard.host.ip) ?: return heard.host
        val h = heard.host
        val udnMac = if (h.mac == null) d.udn?.let { MacExtract.fromText(it) } else null
        return h.copy(upnpFriendlyName = d.friendlyName, upnpManufacturer = d.manufacturer, upnpModel = d.modelName, upnpDeviceType = d.deviceType,
            mac = h.mac ?: udnMac, macSource = h.macSource ?: udnMac?.let { MacSource.UPNP_UDN })
    }
}
