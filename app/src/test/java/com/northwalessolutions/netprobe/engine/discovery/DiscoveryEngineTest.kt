package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

class DiscoveryEngineTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env = NetworkEnv(ip("192.168.1.50"), 24, ip("192.168.1.1"), emptyList(), "Home", null, Transport.WIFI)

    /** Fake prober: hosts in [alive] answer; everything else is dead. */
    private fun engine(alive: Map<String, Set<Int>>, mdns: List<MdnsRecord> = emptyList(), ssdp: List<SsdpResponse> = emptyList(),
                       neigh: Result<List<NeighbourEntry>> = Result.success(emptyList()), netbios: Map<String, NetBiosInfo> = emptyMap(),
                       deviceInfo: Map<String, Map<String, String>> = emptyMap()) = DiscoveryEngine(
        sweepFactory = { _, _ -> object : Sweep(HostProber(), 8) {
            override fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = hosts.toList().filter { it.hostAddress in alive }
                .map { Liveness(it, true, alive[it.hostAddress]!!, 3) }.asFlow() } },
        mdns = { _, _ -> mdns.asFlow() }, ssdp = { ssdp.asFlow() },
        upnpFetch = { loc, _ -> if (loc.contains("igd")) UpnpDevice("Archer AX55", "TP-Link", "Archer AX55", null, "urn:schemas-upnp-org:device:InternetGatewayDevice:1", "uuid:1-2-3-4-aabbccddeeff") else null },
        netbios = { netbios[it.hostAddress] }, snmp = { if (it.hostAddress == "192.168.1.1") SnmpInfo("RouterOS", "gw", null) else null },
        httpTitle = { ip, port, _ -> if (port == 80 && ip.hostAddress == "192.168.1.1") HttpPeek("Router Login", "Boa", null, 200) else null },
        reverseDns = { if (it.hostAddress == "192.168.1.20") "desktop.lan" else null }, neighbours = { neigh }, mdnsTypes = listOf("_http._tcp"),
        deviceInfo = { name, _ -> deviceInfo[name] },
    )

    @Test fun `gateway is emitted first even if it drops probes`() = runBlocking {
        val first = engine(alive = emptyMap()).discover(env, DiscoveryOptions()).first()
        assertEquals("192.168.1.1", first.ip.hostAddress); assertTrue(first.isGateway); assertTrue(Source.GATEWAY in first.sources)
    }
    @Test fun `hosts from different sources merge by ip`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.20"), "Desktop", "_http._tcp", 80, mapOf("model" to "MacBookPro18,3"), listOf("fe80::211:22ff:fe33:4499")))   // EUI-64 of 00:11:22:33:44:99: must lose to the NetBIOS MAC
        val ssdp = listOf(SsdpResponse(ip("192.168.1.1"), "http://192.168.1.1:1900/igd.xml", "MiniUPnPd/2.1", "urn:x", "uuid:1-2-3-4-aabbccddeeff::urn:x"))
        val all = engine(alive = mapOf("192.168.1.1" to setOf(80), "192.168.1.20" to setOf(445)), mdns = mdns, ssdp = ssdp,
            netbios = mapOf("192.168.1.20" to NetBiosInfo("DESKTOP-ABC", "WORKGROUP", "00:11:22:33:44:55"))).discover(env, DiscoveryOptions()).toList()
        val byIp = all.groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        val gw = byIp["192.168.1.1"]!!; val pc = byIp["192.168.1.20"]!!
        assertEquals("TP-Link", gw.upnpManufacturer); assertEquals("Archer AX55", gw.upnpModel); assertEquals("RouterOS", gw.snmpSysDescr)
        assertEquals("Router Login", gw.httpTitle); assertEquals("aa:bb:cc:dd:ee:ff", gw.mac)              // from UDN
        assertEquals(setOf(Source.GATEWAY, Source.SWEEP, Source.SSDP, Source.SNMP, Source.HTTP), gw.sources)
        assertEquals("Desktop", pc.mdnsName); assertEquals("MacBookPro18,3", pc.mdnsTxt["_http._tcp"]!!["model"])
        assertEquals("DESKTOP-ABC", pc.netbiosName); assertEquals("00:11:22:33:44:55", pc.mac)             // NetBIOS (rank 3) beats the EUI-64 MAC (rank 1), whatever the arrival order
        assertEquals("desktop.lan", pc.reverseDnsName); assertEquals(setOf(445), pc.probedOpenPorts)
        assertEquals(2, byIp.size)
    }
    @Test fun `neighbour table supplies macs after the sweep and status records availability`() = runBlocking {
        val e = engine(alive = mapOf("192.168.1.30" to emptySet()), neigh = Result.success(listOf(NeighbourEntry("192.168.1.30", "de:ad:be:ef:00:01", "REACHABLE"))))
        val merged = e.discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        assertEquals("de:ad:be:ef:00:01", merged["192.168.1.30"]!!.mac); assertTrue(Source.NEIGH in merged["192.168.1.30"]!!.sources)
        assertEquals(true, e.status.value.neighbourAvailable)
    }
    @Test fun `neighbour table beats an earlier eui64 mac`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.41"), "esp", "_http._tcp", 80, emptyMap(), listOf("fe80::211:22ff:fe33:4455")))
        val e = engine(alive = emptyMap(), mdns = mdns, neigh = Result.success(listOf(NeighbourEntry("192.168.1.41", "de:ad:be:ef:00:02", "REACHABLE"))))
        val merged = e.discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        assertEquals("de:ad:be:ef:00:02", merged["192.168.1.41"]!!.mac); assertEquals(MacSource.NEIGH, merged["192.168.1.41"]!!.macSource)
    }
    @Test fun `device-info txt is queried per instance and merged into mdnsTxt`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.42"), "Davids-MacBook", "_smb._tcp", 445, emptyMap(), emptyList()))
        val merged = engine(alive = emptyMap(), mdns = mdns, deviceInfo = mapOf("Davids-MacBook" to mapOf("model" to "MacBookPro18,3")))
            .discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        val mac = merged["192.168.1.42"]!!
        assertEquals("MacBookPro18,3", mac.mdnsTxt["_device-info._tcp"]!!["model"]); assertTrue("_device-info._tcp" in mac.mdnsServiceTypes)
        assertTrue("_smb._tcp" in mac.mdnsServiceTypes)
    }
    @Test fun `neighbour denial is recorded not thrown`() = runBlocking {
        val e = engine(alive = emptyMap(), neigh = Result.failure(Exception("errno 13")))
        e.discover(env, DiscoveryOptions()).toList()
        assertEquals(false, e.status.value.neighbourAvailable)
    }
    @Test fun `eui64 is used when nothing else gives a mac`() = runBlocking {
        val mdns = listOf(MdnsRecord(ip("192.168.1.40"), "esp-sensor", "_http._tcp", 80, emptyMap(), listOf("fe80::211:22ff:fe33:4455")))
        val merged = engine(alive = emptyMap(), mdns = mdns).discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
        assertEquals("00:11:22:33:44:55", merged["192.168.1.40"]!!.mac)
    }
    @Test fun `large network is clamped and status says so`() = runBlocking {
        val e = engine(alive = emptyMap())
        e.discover(env.copy(prefixLength = 16), DiscoveryOptions()).toList()
        assertEquals(16, e.status.value.clampedFrom); assertEquals(254, e.status.value.total)
    }
    @Test fun `disabled strategies are not called`() = runBlocking {
        var snmpCalls = 0
        val e = DiscoveryEngine(sweepFactory = { _, _ -> object : Sweep(HostProber(), 8) { override fun run(hosts: Sequence<Inet4Address>) = flowOf(Liveness(ip("192.168.1.9"), true, emptySet(), 1)) } },
            mdns = { _, _ -> emptyFlow() }, ssdp = { emptyFlow() }, upnpFetch = { _, _ -> null }, netbios = { null }, snmp = { snmpCalls++; null },
            httpTitle = { _, _, _ -> null }, reverseDns = { null }, neighbours = { Result.success(emptyList()) }, mdnsTypes = emptyList(),
            deviceInfo = { _, _ -> null })
        e.discover(env, DiscoveryOptions(snmp = false)).toList()
        assertEquals(0, snmpCalls)
    }

    // ---- fix round 1: error isolation, progress flush, cancellation, disabled strategies ----

    private fun sweepOf(vararg found: Pair<String, Set<Int>>) = object : Sweep(HostProber(), 8) {
        override fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = found.map { (a, p) -> Liveness(ip(a), true, p, 1) }.asFlow()
    }
    private fun fake(
        sweep: Sweep = sweepOf(), mdns: (List<String>, Long) -> Flow<MdnsRecord> = { _, _ -> emptyFlow() }, ssdp: () -> Flow<SsdpResponse> = { emptyFlow() },
        upnpFetch: suspend (String, Inet4Address) -> UpnpDevice? = { _, _ -> null }, netbios: suspend (Inet4Address) -> NetBiosInfo? = { null },
        snmp: suspend (Inet4Address) -> SnmpInfo? = { null }, httpTitle: suspend (Inet4Address, Int, Boolean) -> HttpPeek? = { _, _, _ -> null },
        reverseDns: suspend (Inet4Address) -> String? = { null }, neighbours: () -> Result<List<NeighbourEntry>> = { Result.success(emptyList()) },
        deviceInfo: suspend (String, Inet4Address) -> Map<String, String>? = { _, _ -> null },
    ) = DiscoveryEngine({ _, _ -> sweep }, mdns, ssdp, upnpFetch, netbios, snmp, httpTitle, reverseDns, neighbours, listOf("_http._tcp"), deviceInfo)

    private fun merged(e: DiscoveryEngine) = runBlocking {
        e.discover(env, DiscoveryOptions()).toList().groupBy { it.ip.hostAddress }.mapValues { (_, v) -> v.reduce { a, b -> a.merge(b) } }
    }

    @Test fun `final sweep progress is flushed into status`() {
        val prog = MutableStateFlow(0 to 0)
        val sweep = object : Sweep(HostProber(), 8) {
            override val progress: StateFlow<Pair<Int, Int>> get() = prog
            override fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = flow { prog.value = 254 to 254 }
        }
        val e = fake(sweep = sweep)
        runBlocking { e.discover(env, DiscoveryOptions()).toList() }
        assertEquals(254, e.status.value.probed); assertEquals(e.status.value.total, e.status.value.probed)
    }
    @Test fun `a throwing mdns flow does not stop the sweep`() {
        val m = merged(fake(sweep = sweepOf("192.168.1.20" to setOf(445)), mdns = { _, _ -> flow { throw IOException("mdns dead") } },
            neighbours = { Result.success(listOf(NeighbourEntry("192.168.1.20", "de:ad:be:ef:00:03", "REACHABLE"))) }))
        assertEquals(setOf(445), m["192.168.1.20"]!!.probedOpenPorts); assertEquals("de:ad:be:ef:00:03", m["192.168.1.20"]!!.mac)
    }
    @Test fun `a throwing ssdp flow does not stop the sweep`() {
        val m = merged(fake(sweep = sweepOf("192.168.1.20" to setOf(445)), ssdp = { flow { throw IOException("ssdp dead") } }))
        assertEquals(setOf(445), m["192.168.1.20"]!!.probedOpenPorts)
    }
    @Test fun `a throwing netbios leaves other hosts and lookups intact`() {
        val m = merged(fake(sweep = sweepOf("192.168.1.20" to emptySet(), "192.168.1.21" to emptySet()),
            netbios = { if (it.hostAddress == "192.168.1.20") throw IOException("boom") else NetBiosInfo("OTHER", null, null) }, reverseDns = { "h-" + it.hostAddress }))
        assertNull(m["192.168.1.20"]!!.netbiosName); assertEquals("h-192.168.1.20", m["192.168.1.20"]!!.reverseDnsName); assertEquals("OTHER", m["192.168.1.21"]!!.netbiosName)
    }
    @Test fun `a throwing snmp leaves other hosts and lookups intact`() {
        val m = merged(fake(sweep = sweepOf("192.168.1.20" to emptySet(), "192.168.1.21" to emptySet()),
            snmp = { if (it.hostAddress == "192.168.1.20") throw IOException("boom") else SnmpInfo("Linux", null, null) }, reverseDns = { "h-" + it.hostAddress }))
        assertNull(m["192.168.1.20"]!!.snmpSysDescr); assertEquals("h-192.168.1.20", m["192.168.1.20"]!!.reverseDnsName); assertEquals("Linux", m["192.168.1.21"]!!.snmpSysDescr)
    }
    @Test fun `a throwing deviceInfo keeps the mdns record`() {
        val mdns = listOf(MdnsRecord(ip("192.168.1.42"), "A", "_smb._tcp", 445, emptyMap(), emptyList()), MdnsRecord(ip("192.168.1.43"), "B", "_smb._tcp", 445, emptyMap(), emptyList()))
        val m = merged(fake(mdns = { _, _ -> mdns.asFlow() }, deviceInfo = { n, _ -> if (n == "A") throw IOException("boom") else mapOf("model" to "Mac1,1") }))
        assertEquals("A", m["192.168.1.42"]!!.mdnsName); assertNull(m["192.168.1.42"]!!.mdnsTxt["_device-info._tcp"]); assertEquals("Mac1,1", m["192.168.1.43"]!!.mdnsTxt["_device-info._tcp"]!!["model"])
    }
    @Test fun `a throwing upnpFetch keeps the ssdp response`() {
        val ssdp = listOf(SsdpResponse(ip("192.168.1.1"), "http://192.168.1.1/igd.xml", "Srv/1", "urn:x", null), SsdpResponse(ip("192.168.1.7"), "http://192.168.1.7/d.xml", "Srv/2", "urn:x", null))
        val m = merged(fake(ssdp = { ssdp.asFlow() }, upnpFetch = { loc, _ -> if (loc.contains("192.168.1.1/")) throw IOException("boom") else UpnpDevice("Dev", "Acme", null, null, null, null) }))
        assertEquals("Srv/1", m["192.168.1.1"]!!.upnpServer); assertNull(m["192.168.1.1"]!!.upnpManufacturer); assertEquals("Acme", m["192.168.1.7"]!!.upnpManufacturer)
    }
    @Test fun `a throwing neighbours call is recorded as unavailable`() {
        val e = fake(neighbours = { throw IOException("jni") })
        runBlocking { e.discover(env, DiscoveryOptions()).toList() }
        assertEquals(false, e.status.value.neighbourAvailable)
    }
    @Test fun `cancelling the collector mid-discovery does not hang`() = runBlocking {
        val hang = object : Sweep(HostProber(), 8) { override fun run(hosts: Sequence<Inet4Address>): Flow<Liveness> = flow { awaitCancellation() } }
        val job = async(Dispatchers.Default) { fake(sweep = hang).discover(env, DiscoveryOptions()).toList() }
        delay(200); job.cancel()
        withTimeout(1000) { job.join() }
        assertTrue(job.isCancelled)
        try { job.await(); fail("expected cancellation") } catch (_: CancellationException) { }
    }
    @Test fun `every disabled strategy is never called`() {
        val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun hit(k: String) { calls.merge(k, 1, Int::plus) }
        val mdns = listOf(MdnsRecord(ip("192.168.1.9"), "x", "_http._tcp", 80, emptyMap(), emptyList()))
        fun build() = fake(sweep = sweepOf("192.168.1.9" to setOf(80)), mdns = { _, _ -> hit("mdns"); mdns.asFlow() }, ssdp = { hit("ssdp"); emptyFlow() },
            netbios = { hit("netbios"); null }, reverseDns = { hit("rdns"); null }, snmp = { hit("snmp"); null }, httpTitle = { _, _, _ -> hit("http"); null })
        runBlocking { build().discover(env, DiscoveryOptions(mdns = false, ssdp = false, netbios = false, reverseDns = false, snmp = false, httpTitle = false)).toList() }
        assertTrue("calls with all disabled: $calls", calls.isEmpty())
        runBlocking { build().discover(env, DiscoveryOptions()).toList() }
        assertEquals(setOf("mdns", "ssdp", "netbios", "rdns", "snmp", "http"), calls.keys)   // control: all fire when enabled
    }
    @Test fun `http title port choice is deterministic`() {
        var port = -1
        merged(fake(sweep = sweepOf("192.168.1.9" to setOf(8443, 443, 8080, 80)), httpTitle = { _, p, _ -> port = p; null }))
        assertEquals(80, port)
    }
}
