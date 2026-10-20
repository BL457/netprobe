package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Runs one enrichment step; a failure in a single host's lookup is swallowed (null). Cancellation and bind failures propagate. */
private suspend fun <T> softly(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: BindFailedException) {
    throw e
} catch (_: Exception) {
    null
}

/** Runs one whole strategy; any failure ends that strategy only. Cancellation and bind failures propagate. */
private suspend fun isolated(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BindFailedException) {
        throw e
    } catch (_: Exception) {
        // strategy died; the rest of discovery carries on
    }
}

/** Stops one range's sweep the moment the guard says PHANTOM; caught by that range only. */
private class PhantomStop : Exception()

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
    val gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?) -> List<Subnet> = { _, _, _ -> emptyList() },
    val canaryProbe: suspend (Inet4Address, Int, SocketBinder?) -> Probe.Outcome = { _, _, _ -> Probe.Outcome.Timeout },
    val random: Random = Random.Default,
) {
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status

    private fun updateRange(i: Int, f: (RangeProgress) -> RangeProgress) =
        _status.update { s -> s.copy(ranges = s.ranges.mapIndexed { j, r -> if (j == i) f(r) else r }) }
    private fun addNote(n: ExtendedNote) = _status.update { it.copy(notes = it.notes + n) }

    /** Step 7 of extended spec 5.2: one throwaway socket proves the Wi-Fi network can be bound. */
    private suspend fun bindWorks(b: SocketBinder): Boolean = withContext(Dispatchers.IO) {
        try { Socket().use { b.bind(it) }; true } catch (e: Exception) { false }
    }

    fun discover(env: NetworkEnv, options: DiscoveryOptions, binder: SocketBinder? = null): Flow<DiscoveredHost> = channelFlow {
        val range = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, options.maxPrefix)
        val local = range.subnet
        val found = HashSet<Inet4Address>()
        val evidence = LinkedHashSet<Inet4Address>()
        fun noteEvidence(ip: Inet4Address) { if (!local.contains(ip)) synchronized(evidence) { evidence.add(ip) } }
        _status.value = DiscoveryStatus(total = local.size, clampedFrom = if (range.clamped) range.originalPrefix else null,
            ranges = listOf(RangeProgress(local.toString(), null, RangeState.RUNNING, 0, local.size, 0)))
        suspend fun emit(h: DiscoveredHost) {
            val fresh = synchronized(found) { found.add(h.ip) }
            if (fresh) _status.update { it.copy(found = synchronized(found) { found.size }) }
            send(h.withDerivedMac())
        }

        val enrich = Semaphore(ENRICHMENT_CONCURRENCY)
        suspend fun enrichHost(l: Liveness) = enrich.withPermit {
            var h = DiscoveredHost(l.ip)
            if (options.netbios) softly { netbios(l.ip) }?.let { h = h.copy(netbiosName = it.computerName, netbiosWorkgroup = it.workgroup, mac = it.mac, macSource = it.mac?.let { MacSource.NETBIOS }, sources = h.sources + Source.NETBIOS) }
            // The system PTR resolver cannot be bound to the Network, so extra-range hosts (bound context) get no reverse DNS (spec 5.3).
            if (options.reverseDns && currentBinder() == null) softly { reverseDns(l.ip) }?.let { h = h.copy(reverseDnsName = it) }
            if (options.snmp) softly { snmp(l.ip) }?.let { h = h.copy(snmpSysDescr = it.sysDescr, snmpSysName = it.sysName, snmpSysObjectId = it.sysObjectId, sources = h.sources + Source.SNMP) }
            if (options.httpTitle) {
                val web = WEB_PORTS.firstOrNull { it in l.openPorts }
                if (web != null) softly { httpTitle(l.ip, web, web in TLS_PORTS) }?.let { h = h.copy(httpTitle = it.title, httpServer = it.server, httpAuthRealm = it.authRealm, sources = h.sources + Source.HTTP) }
            }
            if (h != DiscoveredHost(l.ip)) emit(h)
        }

        /** One extra range: canary, guarded sweep, enrichment; DONE only after enrichment (extended spec 4.7 and 5.2). */
        suspend fun sweepExtra(i: Int, c: CandidateRange, canaryPort: Int, b: SocketBinder) {
            updateRange(i) { it.copy(state = RangeState.RUNNING) }
            val known = options.extended.knownIps + synchronized(found) { found.map { it.hostAddress } }
            val fresh = c.subnet.hosts().filter { it != env.interfaceIp && it.hostAddress !in known }.toList()
            if (fresh.size >= PhantomGuard.CANARY_MIN_UNKNOWN) {
                val outcomes = listOf(fresh.first(), fresh.last()).map { softly { canaryProbe(it, canaryPort, b) } ?: Probe.Outcome.Timeout }
                if (PhantomGuard.canaryPhantom(outcomes)) { updateRange(i) { it.copy(state = RangeState.PHANTOM, found = 0) }; return }
            }
            val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
            val released = AtomicInteger()
            val phantom = try {
                coroutineScope {
                    val held = ArrayList<Liveness>()
                    var verdict = PhantomGuard.Verdict.HOLD
                    // The guard judges a fixed sample, the first SAMPLE addresses in address order, never "whichever 64 answered first":
                    // live hosts answer in milliseconds and dead routed ones time out, so completion order would flag any busy real range.
                    val addresses = c.subnet.hosts().filter { it != env.interfaceIp }.toList()
                    val sampleSize = minOf(PhantomGuard.SAMPLE, addresses.size)
                    val index = HashMap<Inet4Address, Int>(addresses.size).also { m -> addresses.forEachIndexed { n, a -> m[a] = n } }
                    var probed = 0; var sampleDone = 0; var sampleAlive = 0
                    suspend fun release(l: Liveness) {
                        emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP)))   // never isGateway (spec 5.3)
                        released.incrementAndGet()
                        launch { enrichHost(l) }
                    }
                    sweep.runAll(addresses.asSequence()).collect { l ->
                        probed++
                        if ((index[l.ip] ?: Int.MAX_VALUE) < sampleSize) { sampleDone++; if (l.alive) sampleAlive++ }
                        if (l.alive) { if (verdict == PhantomGuard.Verdict.RELEASE) release(l) else held += l }   // every live host is held until the verdict, sample or not
                        if (verdict == PhantomGuard.Verdict.HOLD) {
                            verdict = PhantomGuard.decide(sampleDone, sampleAlive, finished = sampleDone == sampleSize)
                            when (verdict) {
                                PhantomGuard.Verdict.RELEASE -> { held.forEach { release(it) }; held.clear() }
                                PhantomGuard.Verdict.PHANTOM -> throw PhantomStop()
                                PhantomGuard.Verdict.HOLD -> Unit
                            }
                        }
                        val p = probed
                        updateRange(i) { it.copy(probed = p) }
                    }
                    if (verdict == PhantomGuard.Verdict.HOLD) {   // a sample that never completed (a probe that threw): judge what there is
                        verdict = PhantomGuard.decide(sampleDone, sampleAlive, finished = true)
                        if (verdict == PhantomGuard.Verdict.RELEASE) { held.forEach { release(it) }; held.clear() }
                    }
                    verdict == PhantomGuard.Verdict.PHANTOM
                }
            } catch (_: PhantomStop) { true }
            updateRange(i) { it.copy(state = if (phantom) RangeState.PHANTOM else RangeState.DONE, found = if (phantom) 0 else released.get()) }
        }

        suspend fun extendedPhase() {
            val ext = options.extended
            val b = binder
            if (b == null || !bindWorks(b)) { addNote(ExtendedNote.SKIPPED_BIND_FAILED); return }
            try {
                val highPort = RandomHighPort.pick(random)   // one per run, for the TEST-NET pre-check and every canary (spec 4.3, 4.7)
                Evidence.dnsServers(env, local).forEach { noteEvidence(it) }
                var hits = emptyList<Subnet>()
                if (ext.gatewayProbe) {
                    _status.update { it.copy(phase = DiscoveryPhase.GATEWAY_PROBE) }
                    val pre = AnswersForEverything.TEST_NET.flatMap { a ->
                        listOf(AnswersForEverything.TEST_PORT, highPort).map { p -> softly { canaryProbe(a, p, b) } ?: Probe.Outcome.Timeout }
                    }
                    if (AnswersForEverything.of(pre)) addNote(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL)
                    else {
                        val targets = GatewayTargets.of(env, local)
                        val label = targets.enclosing?.toString() ?: OTHER_RANGES_LABEL
                        var ran = false
                        isolated {
                            hits = gatewayProbe(targets, { d, t, n -> _status.update { it.copy(gatewayProbe = GatewayProbeProgress(label, d, t, n)) } }, b)
                            ran = true
                        }
                        if (ran && hits.isEmpty()) addNote(ExtendedNote.GATEWAY_PROBE_FOUND_NONE)
                    }
                }
                // Only now: every local strategy has joined, so the evidence is complete (spec 4.2). New sightings below add no ranges.
                val cand = CandidateRanges.derive(local, ext.manual, synchronized(evidence) { evidence.toList() }, hits, ext.maxRanges)
                if (cand.ranges.isEmpty()) { addNote(ExtendedNote.NO_EXTRA_RANGES); return }
                _status.update { s -> s.copy(phase = DiscoveryPhase.EXTENDED, truncatedRanges = cand.truncated.size,
                    ranges = s.ranges + cand.ranges.map { RangeProgress(it.subnet.toString(), it.source, RangeState.PENDING, 0, it.subnet.size, 0) } +
                        cand.truncated.map { RangeProgress(it.subnet.toString(), it.source, RangeState.TRUNCATED, 0, it.subnet.size, 0) }) }
                withContext(BoundNetwork(b)) { cand.ranges.forEachIndexed { k, c -> sweepExtra(1 + k, c, highPort, b) } }   // one range at a time
            } catch (e: BindFailedException) {
                addNote(ExtendedNote.SKIPPED_BIND_FAILED)
            }
        }

        try {
            env.gateway?.let { emit(DiscoveredHost(it, isGateway = true, sources = setOf(Source.GATEWAY))) }
            val sweep = sweepFactory(HostProber(timeoutMs = options.connectTimeoutMs), options.sweepConcurrency)
            val statusJob = launch { sweep.progress.collect { (p, t) ->
                _status.update { it.copy(probed = p, total = maxOf(t, it.total)) }
                updateRange(0) { r -> r.copy(probed = p, total = maxOf(t, r.total)) }
            } }

            coroutineScope {
                if (options.mdns) launch { isolated {
                    val infoQueried = HashSet<String>()
                    mdns(mdnsTypes, options.mdnsDurationMs).collect { r ->
                        noteEvidence(r.ip)
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
                } }
                if (options.ssdp) launch { isolated {
                    val fetched = HashSet<String>()
                    ssdp().collect { r ->
                        noteEvidence(r.from)
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
                } }
                launch {
                    isolated { sweep.run(local.hosts()).collect { l ->
                        emit(DiscoveredHost(l.ip, probedOpenPorts = l.openPorts, sources = setOf(Source.SWEEP), isGateway = l.ip == env.gateway))
                        launch { enrichHost(l) }
                    } }
                    // Sweep done: read the neighbour table once.
                    val neigh = withContext(Dispatchers.IO) {   // blocking JNI netlink round trip; discover may be collected on Main
                        try { neighbours() } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
                    }
                    _status.update { it.copy(neighbourAvailable = neigh.isSuccess) }
                    neigh.getOrNull()?.forEach { n ->
                        val ip = runCatching { java.net.InetAddress.getByName(n.ip) as Inet4Address }.getOrNull() ?: return@forEach
                        if (local.contains(ip)) emit(DiscoveredHost(ip, mac = n.mac, macSource = MacSource.NEIGH, sources = setOf(Source.NEIGH)))
                    }
                }
            }
            // The collector sees a conflated StateFlow and may miss the last update, so flush it before cancelling.
            val (probed, total) = sweep.progress.value
            _status.update { it.copy(probed = probed, total = maxOf(total, it.total)) }
            statusJob.cancel()
            val localFound = synchronized(found) { found.count { local.contains(it) } }
            updateRange(0) { it.copy(state = RangeState.DONE, probed = probed, total = maxOf(total, it.total), found = localFound) }

            if (options.extended.enabled) extendedPhase()
        } finally {
            // Stop, a network change or a failure: whatever had not finished is CANCELLED; partial results stay (spec 5.4).
            _status.update { s -> s.copy(phase = DiscoveryPhase.DONE, ranges = s.ranges.map { r ->
                if (r.state == RangeState.RUNNING || r.state == RangeState.PENDING) r.copy(state = RangeState.CANCELLED) else r }) }
        }
    }

    companion object {
        const val CANARY_TIMEOUT_MS = 600
        private const val OTHER_RANGES_LABEL = "other ranges"
        fun production(context: Context, mdnsTypes: List<String>): DiscoveryEngine {
            val nsd = AndroidNsdSource(context); val upnp = UpnpFetcher(); val titles = HttpTitleFetcher(); val rdns = ReverseDns()
            return DiscoveryEngine(
                sweepFactory = { prober, c -> Sweep(prober, c) },
                mdns = { types, ms -> MdnsResolveQueue(nsd).run(types, ms) }, ssdp = { SsdpSearch().search() },
                upnpFetch = { loc, host -> upnp.fetch(loc, host) }, netbios = { NetBios.query(it) }, snmp = { Snmp.query(it) },
                httpTitle = { ip, port, tls -> titles.fetch(ip, port, tls) }, reverseDns = { rdns.lookup(it) },
                neighbours = { NeighbourTable.dump() }, mdnsTypes = mdnsTypes,
                deviceInfo = { name, host -> DeviceInfoTxt.query(name, host) },
                gatewayProbe = { targets, onProgress, b ->
                    GatewayProbe.run(targets, connect = { a, port -> Probe.connect(a, port, GatewayProbe.TIMEOUT_MS, binder = b) }, onProgress = onProgress)
                },
                // Probe.connect blocks: never on the collector's (possibly Main) dispatcher.
                canaryProbe = { a, port, b -> withContext(Dispatchers.IO) { Probe.connect(a, port, CANARY_TIMEOUT_MS, binder = b) } },
            )
        }
        private const val MAX_DEVICE_INFO_QUERIES_PER_HOST = 3
        private const val ENRICHMENT_CONCURRENCY = 32
        private val WEB_PORTS = listOf(80, 8080, 443, 8443)
        private val TLS_PORTS = setOf(443, 8443)
    }
}
