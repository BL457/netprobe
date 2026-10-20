package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.Collections
import kotlin.random.Random

class DiscoveryEngineExtendedTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)

    private object OkBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private object FailingBinder : SocketBinder {
        override fun bind(socket: Socket) = throw SocketException("EPERM: VPN does not allow bypass")
        override fun bind(socket: DatagramSocket) = throw SocketException("EPERM")
        override fun openConnection(url: URL): URLConnection = throw SocketException("EPERM")
    }

    /**
     * Answers in [completionOrder] (default: address order); logs "<first address> bound=<BoundNetwork present>" for every range it sweeps.
     * The real Sweep delivers results in completion order, so a test can put live hosts first the way a busy range does.
     */
    private class FakeSweep(private val alive: (Inet4Address) -> Boolean, private val log: MutableList<String>, private val delayFor: (Inet4Address) -> Long,
                            private val completionOrder: (List<Inet4Address>) -> List<Inet4Address> = { it }) :
        Sweep(HostProber(), 8) {
        private val p = MutableStateFlow(0 to 0)
        override val progress: StateFlow<Pair<Int, Int>> get() = p
        override fun runAll(hosts: Sequence<Inet4Address>): Flow<Liveness> = flow {
            val list = hosts.toList(); p.value = 0 to list.size
            log += "${list.first().hostAddress} bound=${currentCoroutineContext()[BoundNetwork] != null}"
            completionOrder(list).forEachIndexed { i, a ->
                delayFor(a).takeIf { it > 0 }?.let { delay(it) }
                p.value = i + 1 to list.size
                emit(if (alive(a)) Liveness(a, true, setOf(80), 2) else Liveness(a, false, emptySet(), null))
            }
        }
    }

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val netbiosCalls = Collections.synchronizedList(mutableListOf<String>())
    private val gatewayCalls = Collections.synchronizedList(mutableListOf<GatewayTargets>())
    private val canaryCalls = Collections.synchronizedList(mutableListOf<String>())
    private val rdnsCalls = Collections.synchronizedList(mutableListOf<String>())

    private fun engine(alive: (Inet4Address) -> Boolean = { false }, mdns: List<MdnsRecord> = emptyList(), mdnsDelayMs: Long = 0,
                       gatewayHits: List<String> = emptyList(), gatewayThrows: Boolean = false, gatewayAnswered: List<String> = emptyList(),
                       canary: (Inet4Address, Int) -> Probe.Outcome = { _, _ -> Probe.Outcome.Timeout },
                       delayFor: (Inet4Address) -> Long = { 0 }, onNetbios: suspend (Inet4Address) -> Unit = {},
                       completionOrder: (List<Inet4Address>) -> List<Inet4Address> = { it },
                       neigh: List<com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry> = emptyList()): DiscoveryEngine =
        DiscoveryEngine(
            sweepFactory = { _, _ -> FakeSweep(alive, log, delayFor, completionOrder) },
            mdns = { _, _ -> flow { if (mdnsDelayMs > 0) delay(mdnsDelayMs); mdns.forEach { emit(it) } } }, ssdp = { emptyFlow() },
            upnpFetch = { _, _ -> null }, netbios = { a -> netbiosCalls += a.hostAddress; onNetbios(a); null }, snmp = { null },
            httpTitle = { _, _, _ -> null }, reverseDns = { a -> rdnsCalls += a.hostAddress; null }, neighbours = { Result.success(neigh) }, mdnsTypes = listOf("_http._tcp"),
            deviceInfo = { _, _ -> null },
            gatewayProbe = { t, onProgress, _ -> gatewayCalls += t; if (gatewayThrows) throw IllegalStateException("boom"); onProgress(1, 1, 2); GatewayProbeResult(gatewayHits.map(::net), gatewayAnswered.map(::ip).toSet()) },
            canaryProbe = { a, port, _ -> canaryCalls += "${a.hostAddress}:$port"; canary(a, port) },
            random = Random(1),
        )
    private fun opts(enabled: Boolean = true, manual: List<String> = emptyList(), cap: Int = 4, probe: Boolean = false, known: Set<String> = emptySet()) =
        DiscoveryOptions(extended = ExtendedOptions(enabled, cap, probe, manual.map(::net), known))
    private suspend fun DiscoveryEngine.hosts(o: DiscoveryOptions, b: SocketBinder? = OkBinder, e: NetworkEnv = env) =
        discover(e, o, b).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { x, y -> x.merge(y) } }
    private fun swept() = log.map { it.substringBefore(' ') }
    private fun DiscoveryEngine.states() = status.value.ranges.map { it.state }

    @Test fun `toggle off changes nothing`() = runBlocking {
        val e = engine(alive = { it.hostAddress == "10.0.3.5" })
        val h = e.hosts(opts(enabled = false, manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1"), swept()); assertTrue(gatewayCalls.isEmpty()); assertTrue(canaryCalls.isEmpty())
        assertEquals(setOf("10.0.3.1", "10.0.3.5"), h.keys)
        assertEquals(listOf(RangeState.DONE), e.states()); assertTrue(e.status.value.notes.isEmpty()); assertEquals(254, e.status.value.total)
    }
    @Test fun `manual range only is swept bound, enriched, never a gateway and without a mac`() = runBlocking {
        val e = engine(alive = { it.hostAddress in setOf("10.0.4.7", "10.0.4.9") })
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(listOf("10.0.3.1 bound=false", "10.0.4.1 bound=true"), log.toList())
        val host = h["10.0.4.7"]!!
        assertFalse(host.isGateway); assertNull(host.mac); assertTrue(Source.SWEEP in host.sources)
        assertTrue("10.0.4.7" in netbiosCalls && "10.0.4.9" in netbiosCalls)
        assertEquals(RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 2), e.status.value.ranges[1])
        assertEquals(DiscoveryPhase.DONE, e.status.value.phase)
    }
    @Test fun `an mdns sighting leads to its slash 24 and merges with the sweep result`() = runBlocking {
        val hue = MdnsRecord(ip("10.0.4.20"), "Hue Bridge - 317AA9", "_hue._tcp", 443, emptyMap(), emptyList())
        val h = engine(alive = { it.hostAddress == "10.0.4.20" }, mdns = listOf(hue)).hosts(opts())
        assertEquals(listOf("10.0.3.1", "10.0.4.1"), swept())
        assertEquals(setOf(Source.MDNS, Source.SWEEP), h["10.0.4.20"]!!.sources)
    }
    @Test fun `a dns server outside the range is evidence`() = runBlocking {
        engine().hosts(opts(), e = env.copy(dnsServers = listOf(ip("10.0.9.53"))))
        assertEquals(listOf("10.0.3.1", "10.0.9.1"), swept())
    }
    @Test fun `candidates are derived only after a slow mdns has finished`() = runBlocking {
        engine(mdns = listOf(MdnsRecord(ip("10.0.6.5"), "slow", "_http._tcp", 80, emptyMap(), emptyList())), mdnsDelayMs = 300).hosts(opts())
        assertEquals(listOf("10.0.3.1", "10.0.6.1"), swept())
    }
    @Test fun `gateway probe gets the right targets and its hits come after manual and evidence`() = runBlocking {
        val e = engine(mdns = listOf(MdnsRecord(ip("10.0.6.5"), "x", "_http._tcp", 80, emptyMap(), emptyList())), gatewayHits = listOf("10.0.10.0/24"))
        e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1", "10.0.4.1", "10.0.6.1", "10.0.10.1"), swept())
        assertEquals("10.0.0.0/16", gatewayCalls.single().enclosing.toString()); assertEquals("10.0.3.0/24", gatewayCalls.single().exclude.toString())
        assertEquals(GatewayProbeProgress("10.0.0.0/16", 1, 1, 2), e.status.value.gatewayProbe)
        canaryCalls.filter { it.startsWith("192.0.2.1") || it.startsWith("198.51.100.1") }.let { assertEquals(4, it.size) }   // pre-check: 2 addresses x (80 + high port)
    }
    @Test fun `gateway probe off is never called and an empty probe is noted`() = runBlocking {
        engine(gatewayHits = listOf("10.0.10.0/24")).hosts(opts(manual = listOf("10.0.4.0/24"), probe = false)); assertTrue(gatewayCalls.isEmpty())
        val e = engine(); e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertTrue(ExtendedNote.GATEWAY_PROBE_FOUND_NONE in e.status.value.notes)
    }
    @Test fun `refuse-everything network yields phantom ranges and no extra hosts`() = runBlocking {   // Review Focus 3
        val e = engine(alive = { true }, canary = { _, _ -> Probe.Outcome.Refused }, gatewayHits = listOf("10.0.10.0/24"))
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24"), probe = true))
        assertTrue(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL in e.status.value.notes); assertTrue(gatewayCalls.isEmpty())
        assertEquals(listOf(RangeState.DONE, RangeState.PHANTOM, RangeState.PHANTOM), e.states())
        assertTrue(h.keys.none { it.startsWith("10.0.4.") || it.startsWith("10.0.5.") }); assertEquals(listOf("10.0.3.1"), swept())
    }
    @Test fun `accept-everything range is discarded by the sweep guard without enrichment`() = runBlocking {   // Review Focus 3
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") })   // canary times out: the portal only answers port 80
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(RangeState.PHANTOM, e.status.value.ranges[1].state); assertEquals(0, e.status.value.ranges[1].found)
        assertTrue(h.keys.none { it.startsWith("10.0.4.") }); assertTrue(netbiosCalls.none { it.startsWith("10.0.4.") })
    }
    @Test fun `a busy real range whose live hosts answer first is not mistaken for a phantom`() = runTest {   // virtual time: the dead hosts' delays cost no wall clock   // the guard judges the first 64 ADDRESSES, not the fastest 64 answers
        val busy = (100..159).map { "10.0.4.$it" }.toSet()   // 60 live hosts, none among the first 64 addresses
        val e = engine(alive = { it.hostAddress in busy }, delayFor = { if (it.hostAddress in busy) 0L else 1L },
            completionOrder = { all -> all.filter { it.hostAddress in busy } + all.filter { it.hostAddress !in busy } })   // live hosts finish first, dead ones time out later
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(RangeState.DONE, e.status.value.ranges[1].state)
        assertEquals(60, h.keys.count { it.startsWith("10.0.4.") }); assertEquals(60, e.status.value.ranges[1].found)
    }
    @Test fun `the phantom guard never discards an mdns sighting`() = runBlocking {
        val hue = MdnsRecord(ip("10.0.4.20"), "Hue Bridge", "_hue._tcp", 443, emptyMap(), emptyList())
        val h = engine(alive = { it.hostAddress.startsWith("10.0.4.") }, mdns = listOf(hue)).hosts(opts())
        assertEquals(setOf(Source.MDNS), h["10.0.4.20"]!!.sources)
    }
    @Test fun `a small all-alive manual range is believed`() = runBlocking {
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") })
        val h = e.hosts(opts(manual = listOf("10.0.4.16/28")))
        assertEquals(14, h.keys.count { it.startsWith("10.0.4.") }); assertEquals(RangeState.DONE, e.status.value.ranges[1].state)
        assertEquals(14, e.status.value.ranges[1].total); assertEquals(254, e.status.value.total)   // per-range total; top-level total stays the local one
    }
    @Test fun `cap sweeps exactly cap ranges in order and reports the rest as truncated`() = runBlocking {
        val e = engine()
        e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24", "10.0.7.0/24"), cap = 2))
        assertEquals(listOf("10.0.3.1", "10.0.4.1", "10.0.5.1"), swept())
        assertEquals(2, e.status.value.truncatedRanges)
        assertEquals(listOf(RangeState.DONE, RangeState.DONE, RangeState.DONE, RangeState.TRUNCATED, RangeState.TRUNCATED), e.states())
    }
    @Test fun `bind failure skips the extended phase and keeps the local result`() = runBlocking {   // Review Focus 4
        for (b in listOf<SocketBinder?>(FailingBinder, null)) {
            log.clear()
            val e = engine(alive = { it.hostAddress == "10.0.3.5" })
            val h = e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true), b = b)
            assertEquals(listOf(ExtendedNote.SKIPPED_BIND_FAILED), e.status.value.notes); assertEquals(listOf("10.0.3.1"), swept())
            assertTrue("10.0.3.5" in h.keys); assertTrue(gatewayCalls.isEmpty()); assertTrue(canaryCalls.isEmpty())
        }
    }
    @Test fun `ranges move pending, running, done with done only after enrichment`() = runBlocking {
        lateinit var e: DiscoveryEngine
        val seen = Collections.synchronizedList(mutableListOf<RangeState>())
        e = engine(alive = { it.hostAddress == "10.0.4.7" }, onNetbios = { a -> if (a.hostAddress == "10.0.4.7") { delay(100); seen += e.status.value.ranges[1].state } })
        e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24")))
        assertEquals(listOf(RangeState.RUNNING), seen.toList())   // still running while its enrichment ran
        assertEquals(listOf(RangeState.DONE, RangeState.DONE, RangeState.DONE), e.states())
    }
    @Test fun `stop mid range cancels within a second and drops held hosts`() = runBlocking {   // Review Focus 5
        val e = engine(alive = { it.hostAddress.startsWith("10.0.4.") }, delayFor = { if (it.hostAddress.startsWith("10.0.4.")) 20L else 0L })
        val got = Collections.synchronizedList(mutableListOf<DiscoveredHost>())
        val job = launch(Dispatchers.Default) { e.discover(env, opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24")), OkBinder).collect { got += it } }
        withTimeout(5_000) { while (e.status.value.ranges.getOrNull(1)?.let { it.state == RangeState.RUNNING && it.probed >= 5 } != true) delay(10) }
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertEquals(listOf(RangeState.DONE, RangeState.CANCELLED, RangeState.CANCELLED), e.states())
        assertTrue(got.none { it.ip.hostAddress.startsWith("10.0.4.") })   // held by the undecided guard, never emitted
        assertEquals(DiscoveryPhase.DONE, e.status.value.phase)
    }
    @Test fun `a throwing gateway probe does not stop later ranges`() = runBlocking {
        val e = engine(gatewayThrows = true)
        e.hosts(opts(manual = listOf("10.0.4.0/24"), probe = true))
        assertEquals(listOf("10.0.3.1", "10.0.4.1"), swept()); assertFalse(ExtendedNote.GATEWAY_PROBE_FOUND_NONE in e.status.value.notes)
    }
    @Test fun `toggle on with nothing to sweep is noted`() = runBlocking {
        val e = engine(); e.hosts(opts())
        assertEquals(listOf(ExtendedNote.NO_EXTRA_RANGES), e.status.value.notes)
    }
    @Test fun `canaries use the lowest and highest addresses not already known`() = runBlocking {
        engine().hosts(opts(manual = listOf("10.0.4.0/24"), known = setOf("10.0.4.1", "10.0.4.254")))
        val probed = canaryCalls.map { it.substringBefore(':') }
        assertEquals(listOf("10.0.4.2", "10.0.4.253"), probed)
        assertTrue(canaryCalls.all { it.substringAfter(':').toInt() in 49152..65535 })
    }
    @Test fun `canaries skip the gateway probe's answering addresses and the 1 and 254 offsets`() = runBlocking {   // final review I3
        val real = setOf("10.0.4.1", "10.0.4.254")   // live hosts that reset closed ports, just proved real by the gateway probe
        val e = engine(gatewayHits = listOf("10.0.4.0/24"), gatewayAnswered = real.toList(),
            canary = { a, _ -> if (a.hostAddress in real) Probe.Outcome.Refused else Probe.Outcome.Timeout })
        e.hosts(opts(probe = true))
        val probed = canaryCalls.map { it.substringBefore(':') }.filter { it.startsWith("10.0.4.") }
        assertEquals(listOf("10.0.4.2", "10.0.4.253"), probed)
        assertEquals(RangeState.DONE, e.status.value.ranges[1].state); assertNull(e.status.value.ranges[1].canaryFallback)
    }
    @Test fun `with fewer than two clean canary addresses the canary uses what remains and says so`() = runBlocking {
        val e = engine(gatewayAnswered = listOf("10.0.4.252", "10.0.4.253"))
        e.hosts(opts(manual = listOf("10.0.4.248/29"), probe = true, known = setOf("10.0.4.249", "10.0.4.250")))
        assertEquals(listOf("10.0.4.251"), canaryCalls.map { it.substringBefore(':') }.filter { it.startsWith("10.0.4.") })
        assertEquals(1, e.status.value.ranges[1].canaryFallback)
    }
    @Test fun `a bind failure mid phase ends the extended phase as skipped and keeps what was found`() = runBlocking {   // controller ruling 2
        val e = engine(alive = { it.hostAddress == "10.0.4.7" },
            onNetbios = { a -> if (a.hostAddress == "10.0.4.7") throw BindFailedException(SocketException("EPERM")) })
        val h = e.hosts(opts(manual = listOf("10.0.4.0/24", "10.0.5.0/24")))
        assertEquals(listOf(ExtendedNote.SKIPPED_BIND_FAILED), e.status.value.notes); assertEquals(listOf("10.0.3.1", "10.0.4.1"), swept())
        assertEquals(listOf(RangeState.DONE, RangeState.CANCELLED, RangeState.CANCELLED), e.states()); assertEquals(DiscoveryPhase.DONE, e.status.value.phase)
        assertTrue("10.0.4.7" in h.keys)   // released before its enrichment failed: partial results stay
        log.clear()
        val c = engine(canary = { _, _ -> throw BindFailedException(SocketException("EPERM")) })
        c.hosts(opts(manual = listOf("10.0.4.0/24")))
        assertEquals(listOf(ExtendedNote.SKIPPED_BIND_FAILED), c.status.value.notes); assertEquals(listOf("10.0.3.1"), swept())
        assertEquals(listOf(RangeState.DONE, RangeState.CANCELLED), c.states())
    }
    @Test fun `reverse dns is skipped for extra-range hosts and still runs for local ones`() = runBlocking {   // the system PTR resolver cannot be bound to the Network (spec 5.3)
        engine(alive = { it.hostAddress in setOf("10.0.3.5", "10.0.4.7") }).hosts(opts(manual = listOf("10.0.4.0/24")))
        assertTrue("10.0.3.5" in rdnsCalls); assertFalse("10.0.4.7" in rdnsCalls)
        assertTrue("10.0.4.7" in netbiosCalls)   // control: the extra-range host was released and the bound enrichment ran
    }

    @Test fun `neighbour dump gives evidence and macs only inside the true subnet`() = runBlocking {
        val env16 = env.copy(prefixLength = 16)   // swept range clamps to 10.0.3.0/24 at maxPrefix 22
        val neigh = listOf(com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.7.9", "de:ad:be:ef:00:07", "REACHABLE"),
            com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("192.168.49.5", "02:00:00:00:00:49", "REACHABLE"))
        val h = engine(alive = { it.hostAddress == "10.0.7.9" }, neigh = neigh).hosts(opts(), e = env16)
        assertEquals(listOf("10.0.3.1", "10.0.7.1"), swept())                      // the hotspot range is never a candidate
        assertEquals("de:ad:be:ef:00:07", h["10.0.7.9"]!!.mac); assertEquals(MacSource.NEIGH, h["10.0.7.9"]!!.macSource)
        assertFalse("192.168.49.5" in h.keys)
    }
    @Test fun `toggle on - a host known only from the neighbour table keeps its row when three ips share its mac`() = runBlocking {
        val shared = "aa:bb:cc:dd:ee:ff"
        val neigh = listOf(20, 21, 22).map { com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.3.$it", shared, "REACHABLE") }
        val h = engine(neigh = neigh).hosts(opts(enabled = true))
        assertTrue(listOf("10.0.3.20", "10.0.3.21", "10.0.3.22").all { it in h.keys })
        assertTrue(listOf(20, 21, 22).all { h["10.0.3.$it"]!!.mac == null })   // no MAC claim, but the hosts are still listed
    }
    @Test fun `toggle off - the neighbour block is the MVP, every on-link entry keeps its mac`() = runBlocking {   // final review I1
        val shared = "aa:bb:cc:dd:ee:ff"; val gwMac = "11:22:33:44:55:66"
        val neigh = listOf(20, 21, 22).map { com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.3.$it", shared, "REACHABLE") } +
            listOf(com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.3.1", gwMac, "REACHABLE"),
                com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry("10.0.3.30", gwMac, "REACHABLE"))   // a range extender answering for 10.0.3.30
        val h = engine(neigh = neigh).hosts(opts(enabled = false))
        assertEquals(setOf("10.0.3.1", "10.0.3.20", "10.0.3.21", "10.0.3.22", "10.0.3.30"), h.keys)
        assertTrue(listOf(20, 21, 22).all { h["10.0.3.$it"]!!.mac == shared && h["10.0.3.$it"]!!.macSource == MacSource.NEIGH })
        assertEquals(gwMac, h["10.0.3.30"]!!.mac); assertEquals(gwMac, h["10.0.3.1"]!!.mac)
    }
}
