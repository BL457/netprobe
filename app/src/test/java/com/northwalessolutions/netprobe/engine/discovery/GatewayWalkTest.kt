package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.netenv.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.Executors

/** 3C spec 2.5 and 8.1 items 9 and 10: the first pass restated, then the outward walk's order, stop conditions, budget and bounds. */
class GatewayWalkTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun env(phone: String, gw: String) = NetworkEnv(ip(phone), 24, ip(gw), emptyList(), null, null, Transport.WIFI)
    private fun third(a: Inet4Address) = (SubnetMath.toInt(a) ushr 8) and 0xFF
    private val calls = Collections.synchronizedList(mutableListOf<String>())                     // "address:port", in call order
    private val progress = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())   // (done, total, attempts)
    private val executor = Executors.newSingleThreadExecutor()
    /** One thread and a connect that never suspends: units run in launch order, so the call order is the walk order. */
    private val oneThread = executor.asCoroutineDispatcher()
    @After fun stop() { executor.shutdownNow() }

    private fun answering(set: Set<String>): suspend (Inet4Address, Int) -> Probe.Outcome = { a, port ->
        calls += "${a.hostAddress}:$port"
        if (a.hostAddress in set) Probe.Outcome.Refused else Probe.Outcome.Timeout
    }
    private fun answeringWhen(rule: (Inet4Address) -> Boolean): suspend (Inet4Address, Int) -> Probe.Outcome = { a, port ->
        calls += "${a.hostAddress}:$port"
        if (rule(a)) Probe.Outcome.Refused else Probe.Outcome.Timeout
    }
    private fun firstPass(t: GatewayTargets): Set<String> = t.byUnit().values.flatten().map { it.hostAddress }.toSet()
    /** Addresses the walk probed, in first-call order (the first pass is filtered out). */
    private fun walked(t: GatewayTargets): List<String> {
        val fp = firstPass(t)
        return calls.map { it.substringBefore(':') }.filter { it !in fp }.distinct()
    }
    private suspend fun probe(t: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, skip: List<Subnet> = emptyList(),
                              budget: (List<Subnet>) -> Int): GatewayProbeResult =
        GatewayProbe.run(t, connect, onProgress = { d, total, n -> progress += Triple(d, total, n) }, dispatcher = oneThread, walk = WalkPlan(budget, skip))

    private val phone192 = env("192.168.1.50", "192.168.1.1"); private val home192 = net("192.168.1.0/24")
    private val phone10 = env("10.0.3.20", "10.0.3.1"); private val home10 = net("10.0.3.0/24")

    @Test fun `the first pass for the Director's network is the spec's 255 units and 17 seeds`() {   // spec 8.1 item 9
        val t = GatewayTargets.of(phone10, home10)
        assertEquals(255, t.byUnit().keys.count { t.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
        val addresses = firstPass(t)
        listOf("10.0.0.1", "10.0.1.1", "10.0.2.1", "10.0.4.1", "10.0.255.254").forEach { assertTrue(it, it in addresses) }
        assertTrue(addresses.none { it.startsWith("10.0.3.") })
        assertNull("the gateway's .1 is already probed", t.gatewayOffset)
        assertEquals(listOf("10.0.4.1", "10.0.4.254"), t.byUnit()[net("10.0.4.0/24")]!!.map { it.hostAddress })
        assertEquals(listOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "172.16.0.1", "172.16.1.1", "172.16.16.1", "172.17.0.1", "172.31.0.1",
            "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1", "192.168.10.1", "192.168.88.1", "192.168.100.1", "192.168.178.1"), t.seeds.map { it.hostAddress })
        val wide = GatewayTargets.of(phone10, SubnetMath.of(ip("10.0.0.0"), 22))   // an Aggressive scan home range
        assertEquals(252, wide.byUnit().keys.count { wide.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
        assertTrue((0..3).none { k -> net("10.0.$k.0/24") in wide.byUnit() })
    }
    @Test fun `the spec example walks exactly the spec's units in four rounds`() = runBlocking {
        val t = GatewayTargets.of(phone192, home192)
        val r = probe(t, answering(setOf("10.1.1.1", "10.1.2.1", "10.1.3.1"))) { first -> CandidateRanges.walkBudget(home192, emptyList(), emptyList(), first, 4) }
        assertEquals(listOf("10.1.1.0/24", "10.1.2.0/24", "10.1.3.0/24"), r.hits.map { it.toString() })
        assertEquals(listOf("10.1.0.1", "10.1.2.1", "10.1.3.1", "10.1.4.1", "10.1.5.1"), walked(t))
        val n = t.byUnit().size
        assertEquals("rounds of 2, 1, 1 and 1 units", listOf(n, n + 2, n + 3, n + 4, n + 5), progress.map { it.second }.distinct())
        assertFalse(r.walkSkippedManySeeds)
    }
    @Test fun `each round goes seed 1 down, seed 1 up, seed 2 down, seed 2 up`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        probe(t, answering(setOf("172.16.16.1", "10.1.10.1"))) { 16 }
        assertEquals(listOf("10.1.9.1", "10.1.11.1", "172.16.15.1", "172.16.17.1", "10.1.8.1", "10.1.12.1", "172.16.14.1", "172.16.18.1"), walked(t))
    }
    @Test fun `with B of one the walk stops after the first round with a hit and the extra hit is truncated`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val r = probe(t, answering(setOf("10.1.10.1", "10.1.9.1", "10.1.11.1", "10.1.8.1", "10.1.12.1"))) { first ->
            CandidateRanges.walkBudget(home10, emptyList(), emptyList(), first, 2) }
        assertEquals("round 1 only", listOf("10.1.9.1", "10.1.11.1"), walked(t))
        assertEquals(listOf("10.1.9.0/24", "10.1.10.0/24", "10.1.11.0/24"), r.hits.map { it.toString() })
        val cand = CandidateRanges.derive(home10, emptyList(), emptyList(), r.hits, 2)
        assertEquals(listOf("10.1.9.0/24", "10.1.10.0/24"), cand.ranges.map { it.subnet.toString() })
        assertEquals(listOf("10.1.11.0/24"), cand.truncated.map { it.subnet.toString() })
    }
    @Test fun `manual and evidence units are never walked and a hit derive drops frees no slot`() = runBlocking {   // plan review G8
        val t = GatewayTargets.of(phone192, home192)
        val manual = listOf(net("10.1.2.0/24"))
        val r = probe(t, answering(setOf("10.1.1.1", "10.1.2.1", "10.1.3.1")), skip = manual) { hits ->
            CandidateRanges.walkBudget(home192, manual, emptyList(), hits, 3) }
        assertFalse("the manual unit is swept anyway: the walk never probes it", calls.any { it.startsWith("10.1.2.1:") })
        assertTrue("the walk went on past it and found 10.1.3.0/24", net("10.1.3.0/24") in r.hits)
        assertEquals(listOf("10.1.0.1", "10.1.3.1"), walked(t))
    }
    @Test fun `with manual units filling the cap no walk unit is probed`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val manual = listOf(net("10.0.4.0/24"), net("10.0.5.0/24"))
        probe(t, answering(setOf("10.1.10.1", "10.1.9.1"))) { first -> CandidateRanges.walkBudget(home10, manual, emptyList(), first, 2) }
        assertTrue(walked(t).isEmpty())
    }
    @Test fun `five answering seeds give no walk and the flag`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        var asked = 0
        val r = probe(t, answering(setOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "172.16.0.1"))) { asked++; 16 }
        assertTrue(r.walkSkippedManySeeds); assertEquals("the budget is never asked", 0, asked); assertTrue(walked(t).isEmpty())
        assertEquals(5, r.hits.size)
        val four = probe(GatewayTargets.of(phone10, home10), answering(setOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1"))) { 0 }
        assertFalse("four answering seeds are allowed", four.walkSkippedManySeeds)
    }
    @Test fun `a direction ends at distance 16 and at octet 0 or 255`() = runBlocking {
        val t = GatewayTargets(enclosing = SubnetMath.of(ip("10.0.0.0"), 16), exclude = home10, gatewayOffset = null, seeds = listOf(ip("10.20.3.1"), ip("10.20.250.1")))
        probe(t, answeringWhen { it.hostAddress.startsWith("10.20.") }) { 100 }
        val w = walked(t).toSet()
        assertTrue("10.20.0.1" in w); assertTrue("10.20.19.1" in w); assertFalse("10.20.20.1" in w)        // seed .3: down to 0, up 16
        assertTrue("10.20.255.1" in w); assertTrue("10.20.234.1" in w); assertFalse("10.20.233.1" in w)   // seed .250: up to 255, down 16
        assertEquals(3 + 16 + 5 + 16, w.size)
    }
    @Test fun `two consecutive misses end a direction and an already probed unit is skipped without counting`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val hitsUp = (2..8).map { "10.1.$it.1" }
        probe(t, answering(setOf("10.1.1.1") + hitsUp)) { 100 }
        val w = walked(t)
        assertTrue(w.containsAll(hitsUp))
        assertTrue("10.1.9.1 misses, 10.1.10.0/24 is a seed unit and skipped, 10.1.11.1 is the second miss", "10.1.11.1" in w)
        assertFalse("10.1.12.1" in w)
        assertEquals("the seed unit is probed only by the first pass", 2, calls.count { it.startsWith("10.1.10.1:") })
        assertTrue("down: 10.1.0.1 misses, then the octet boundary", "10.1.0.1" in w)
    }
    @Test fun `no walk for a seed inside the phone's slash 16`() = runBlocking {
        val t = GatewayTargets(SubnetMath.of(ip("10.1.0.0"), 16), SubnetMath.of(ip("10.1.3.0"), 24), null, listOf(ip("10.1.10.1")))
        val r = probe(t, answering(setOf("10.1.10.1"))) { 100 }
        assertEquals("no round was added", t.byUnit().size, progress.last().second)
        assertEquals(listOf("10.1.10.0/24"), r.hits.map { it.toString() })
    }
    @Test fun `no seed answering gives the Phase 2 attempt count exactly`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val r = probe(t, answering(emptySet())) { 16 }
        assertTrue(r.hits.isEmpty()); assertTrue(walked(t).isEmpty())
        assertEquals("527 addresses x 2 ports; the engine's 2 TEST-NET probes make the spec's 1,056", 1_054, progress.last().third)
        assertEquals(firstPass(t).size * GatewayProbe.PORTS.size, progress.last().third)
    }
    @Test fun `walk attempts never exceed 256`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val chosen = listOf("10.1.10.1", "10.10.10.1", "172.16.16.1", "192.168.10.1").map(::ip)
        // The four chosen seeds answer; around each, an even distance answers and an odd one misses, so no direction sees two misses in a row.
        val rule: (Inet4Address) -> Boolean = { a ->
            a in chosen || chosen.any { s -> SubnetMath.of(s, 16).contains(a) && a !in t.seeds && (third(a) - third(s)) % 2 == 0 }
        }
        val r = probe(t, answeringWhen(rule)) { 1_000 }
        assertFalse(r.walkSkippedManySeeds)
        val fp = firstPass(t)
        val walkAttempts = calls.count { it.substringBefore(':') !in fp }
        assertTrue("walk attempts $walkAttempts", walkAttempts in 1..256)
    }
    @Test fun `progress never steps back and the total grows by each round`() = runBlocking {
        val t = GatewayTargets.of(phone192, home192)
        probe(t, answering(setOf("10.1.1.1", "10.1.2.1", "10.1.3.1"))) { 16 }
        progress.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b.first >= a.first && b.second >= a.second && b.third >= a.third) }
        progress.forEach { assertTrue("$it", it.first <= it.second) }
        assertEquals(progress.last().first, progress.last().second)
    }
    @Test fun `cancellation mid walk returns within a second`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val fp = firstPass(t)
        val walking = CompletableDeferred<Unit>()
        val connect: suspend (Inet4Address, Int) -> Probe.Outcome = { a, _ ->
            when {
                a.hostAddress == "10.1.10.1" -> Probe.Outcome.Refused
                a.hostAddress in fp -> Probe.Outcome.Timeout
                else -> { walking.complete(Unit); delay(10_000); Probe.Outcome.Timeout }   // a walk unit that never answers
            }
        }
        val job = launch(Dispatchers.Default) { GatewayProbe.run(t, connect, dispatcher = Dispatchers.Default, walk = WalkPlan({ 16 })) }
        withTimeout(10_000) { walking.await() }
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
    }
}
