package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address

/** Runs one enrichment step; a failure in a single host's lookup is swallowed (null) so it never stops the engine. Cancellation propagates. */
private suspend fun <T> softly(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

class DiscoveryEngine(
    val sweepFactory: (HostProber, Int) -> Sweep,
    val mdns: (List<String>, Long) -> Flow<MdnsRecord>,
    val ssdp: () -> Flow<SsdpResponse>,
    val upnpFetch: suspend (String, Inet4Address) -> UpnpDevice?,
    val netbios: suspend (Inet4Address) -> NetBiosInfo?,
    val snmp: suspend (Inet4Address) -> SnmpInfo?,
    val httpTitle: suspend (Inet4Address, Int, Boolean) -> HttpPeek?,
    val reverseDns: suspend (Inet4Address) -> String?,
    val neighbours: () -> Result<List<NeighbourEntry>>,
    val mdnsTypes: List<String>,
    val deviceInfo: suspend (String, Inet4Address) -> Map<String, String>?,
) {
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status

    fun discover(env: NetworkEnv, options: DiscoveryOptions): Flow<DiscoveredHost> = channelFlow {
        val range = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, options.maxPrefix)
        val found = HashSet<Inet4Address>()
        _status.value = DiscoveryStatus(total = range.subnet.size, clampedFrom = if (range.clamped) range.originalPrefix else null)
        suspend fun emit(h: DiscoveredHost) { val fresh = synchronized(found) { found.add(h.ip) }; if (fresh) _status.update { it.copy(found = found.size) }; send(h.withDerivedMac()) }

        env.gateway?.let { emit(DiscoveredHost(it, isGateway = true, sources = setOf(Source.GATEWAY))) }

        val enrich = Semaphore(ENRICHMENT_CONCURRENCY)
        val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
        val statusJob = launch { sweep.progress.collect { (p, t) -> _status.update { it.copy(probed = p, total = maxOf(t, it.total)) } } }

        coroutineScope {
            if (options.mdns) launch {
                val infoQueried = HashSet<String>()
                mdns(mdnsTypes, options.mdnsDurationMs).collect { r ->
                    emit(DiscoveredHost(r.ip, mdnsName = r.instanceName, mdnsServiceTypes = setOf(r.type), mdnsTxt = mapOf(r.type to r.txt),
                        mdnsInstanceNames = setOf(r.instanceName), ipv6LinkLocal = r.ipv6.toSet(), sources = setOf(Source.MDNS)))
                    // _device-info._tcp is not browsable, so ask for its TXT record by instance name (at most a few names per host).
                    val prefix = "${r.ip.hostAddress}|"
                    val ask = synchronized(infoQueried) { infoQueried.count { it.startsWith(prefix) } < MAX_DEVICE_INFO_QUERIES_PER_HOST && infoQueried.add(prefix + r.instanceName) }
                    if (ask) launch { enrich.withPermit {
                        softly { deviceInfo(r.instanceName, r.ip) }?.let { txt ->
                            emit(DiscoveredHost(r.ip, mdnsServiceTypes = setOf(DeviceInfoTxt.SERVICE), mdnsTxt = mapOf(DeviceInfoTxt.SERVICE to txt), sources = setOf(Source.MDNS)))
                        }
                    } }
                }
            }
            if (options.ssdp) launch {
                val fetched = HashSet<String>()
                ssdp().collect { r ->
                    val usnMac = r.usn?.let { MacExtract.fromText(it) }
                    var h = DiscoveredHost(r.from, upnpServer = r.server, mac = usnMac, macSource = usnMac?.let { MacSource.UPNP_UDN }, sources = setOf(Source.SSDP))
                    val loc = r.location
                    if (loc != null && synchronized(fetched) { fetched.add(loc) }) {
                        softly { upnpFetch(loc, r.from) }?.let { d ->
                            val udnMac = if (h.mac == null) d.udn?.let { MacExtract.fromText(it) } else null
                            h = h.copy(upnpFriendlyName = d.friendlyName, upnpManufacturer = d.manufacturer, upnpModel = d.modelName, upnpDeviceType = d.deviceType,
                                mac = h.mac ?: udnMac, macSource = h.macSource ?: udnMac?.let { MacSource.UPNP_UDN })
                        }
                    }
                    emit(h)
                }
            }
            launch {
                sweep.run(range.subnet.hosts()).collect { l ->
                    emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP), isGateway = l.ip == env.gateway))
                    launch { enrich.withPermit {
                        var h = DiscoveredHost(l.ip)
                        if (options.netbios) softly { netbios(l.ip) }?.let { h = h.copy(netbiosName = it.computerName, netbiosWorkgroup = it.workgroup, mac = it.mac, macSource = it.mac?.let { MacSource.NETBIOS }, sources = h.sources + Source.NETBIOS) }
                        if (options.reverseDns) softly { reverseDns(l.ip) }?.let { h = h.copy(reverseDnsName = it) }
                        if (options.snmp) softly { snmp(l.ip) }?.let { h = h.copy(snmpSysDescr = it.sysDescr, snmpSysName = it.sysName, snmpSysObjectId = it.sysObjectId, sources = h.sources + Source.SNMP) }
                        if (options.httpTitle) {
                            val web = l.openPorts.firstOrNull { it in WEB_PORTS }
                            if (web != null) softly { httpTitle(l.ip, web, web in TLS_PORTS) }?.let { h = h.copy(httpTitle = it.title, httpServer = it.server, httpAuthRealm = it.authRealm, sources = h.sources + Source.HTTP) }
                        }
                        if (h != DiscoveredHost(l.ip)) emit(h)
                    } }
                }
                // Sweep done: read the neighbour table once.
                val neigh = withContext(Dispatchers.IO) {   // blocking JNI netlink round trip; discover may be collected on Main
                    try { neighbours() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                }
                _status.update { it.copy(neighbourAvailable = neigh.isSuccess) }
                neigh.getOrNull()?.forEach { n ->
                    val ip = runCatching { java.net.InetAddress.getByName(n.ip) as Inet4Address }.getOrNull() ?: return@forEach
                    if (range.subnet.contains(ip)) emit(DiscoveredHost(ip, mac = n.mac, macSource = MacSource.NEIGH, sources = setOf(Source.NEIGH)))
                }
            }
        }
        statusJob.cancel()
    }

    companion object {
        fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine {
            val nsd = AndroidNsdSource(context); val upnp = UpnpFetcher(); val titles = HttpTitleFetcher(); val rdns = ReverseDns()
            return DiscoveryEngine(
                sweepFactory = { prober, c -> Sweep(prober, c) },
                mdns = { types, ms -> MdnsResolveQueue(nsd).run(types, ms) }, ssdp = { SsdpSearch().search() },
                upnpFetch = { loc, host -> upnp.fetch(loc, host) }, netbios = { NetBios.query(it) }, snmp = { Snmp.query(it) },
                httpTitle = { ip, port, tls -> titles.fetch(ip, port, tls) }, reverseDns = { rdns.lookup(it) },
                neighbours = { NeighbourTable.dump() }, mdnsTypes = mdnsTypes,
                deviceInfo = { name, host -> DeviceInfoTxt.query(name, host) },
            )
        }
        private const val MAX_DEVICE_INFO_QUERIES_PER_HOST = 3
        private const val ENRICHMENT_CONCURRENCY = 32
        private val WEB_PORTS = setOf(80, 8080, 443, 8443)
        private val TLS_PORTS = setOf(443, 8443)
    }
}
