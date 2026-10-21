package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.CompletableDeferred
import java.net.Inet4Address

/** A HeardSink that records everything and can park a store on [gate] (for the network-change test). */
class FakeSink(var ctx: HeardContext? = HOME, var ranges: KnownRanges = KnownRanges(listOf(HOME_RANGE)), private val device: UpnpDevice? = null) : HeardSink {
    val stored = mutableListOf<Pair<Long, String>>()
    val storedHosts = mutableListOf<DiscoveredHost>()
    val existing = mutableSetOf<String>()
    var removedSet: Set<Pair<Long, String>> = emptySet()
    val described = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var storeCalls = 0
        private set
    /** The next store throws once (plan review G4: one failed write never ends the session). */
    var failNextStore = false

    var gatewayIp: Inet4Address? = Ipv4.parse("10.0.3.1")
    var selfIp: Inet4Address? = Ipv4.parse("10.0.3.50")
    override fun gateway(): Inet4Address? = gatewayIp
    override fun self(): Inet4Address? = selfIp
    override suspend fun visible(networkId: Long, ip: String): Boolean = ip in existing
    override fun context(): HeardContext? = ctx
    override suspend fun known(networkId: Long): KnownRanges = ranges
    override suspend fun exists(networkId: Long, ip: String): Boolean = ip in existing
    override fun removed(): Set<Pair<Long, String>> = removedSet
    /** Runs inside describe, so a test can change the context while the fetch is "in flight" (plan review G3). */
    var duringDescribe: () -> Unit = {}
    override suspend fun describe(location: String, host: Inet4Address): UpnpDevice? { described += location; duringDescribe(); return device }
    override suspend fun store(networkId: Long, h: DiscoveredHost): Boolean {
        storeCalls++
        if (failNextStore) { failNextStore = false; throw IllegalStateException("database busy") }
        gate?.await()
        stored += networkId to h.ip.hostAddress; storedHosts += h; existing += h.ip.hostAddress
        return true
    }

    companion object {
        const val KEY_A = "10.0.3.1/24"
        val HOME_RANGE = SubnetMath.of(Ipv4.parse("10.0.3.1")!!, 24)
        val HOME = HeardContext(1, KEY_A, forgotten = false, scanActive = false, undoPending = false)
    }
}

object HeardTestSupport {
    /** An authored SSDP alive NOTIFY from [ip] (3C spec 6: nothing captured). */
    fun notify(ip: String, usn: String, location: String = "http://$ip:80/description.xml"): String =
        "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=100\r\nLOCATION: $location\r\nNT: upnp:rootdevice\r\n" +
            "NTS: ssdp:alive\r\nSERVER: Hue/1.0 UPnP/1.0 IpBridge/1.65.0\r\nUSN: $usn\r\n\r\n"
}
