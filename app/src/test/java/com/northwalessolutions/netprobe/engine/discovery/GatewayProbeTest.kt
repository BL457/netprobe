package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.portscan.QuickPorts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class GatewayProbeTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun env(phone: String, gw: String) = NetworkEnv(ip(phone), 24, ip(gw), emptyList(), null, null, Transport.WIFI)

    @Test fun `walk covers the enclosing slash 16 except the swept range, with low and high offsets`() {
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        assertEquals("10.0.0.0/16", t.enclosing.toString())
        val units = t.byUnit()
        assertFalse(net("10.0.3.0/24") in units)
        assertEquals(listOf("10.0.4.1", "10.0.4.254"), units[net("10.0.4.0/24")]!!.map { it.hostAddress })
        assertEquals(255, units.keys.count { t.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
    }
    @Test fun `the phone's own gateway offset is probed in every slash 24 when it is not 1 or 254`() {
        val t = GatewayTargets.of(env("192.168.1.50", "192.168.1.250"), net("192.168.1.0/24"))
        assertEquals(250, t.gatewayOffset)
        assertEquals(listOf("192.168.7.1", "192.168.7.254", "192.168.7.250"), t.byUnit()[net("192.168.7.0/24")]!!.map { it.hostAddress })
        assertNull(GatewayTargets.of(env("10.0.3.242", "10.0.3.254"), net("10.0.3.0/24")).gatewayOffset)
    }
    @Test fun `seeds skip addresses inside the phone's own slash 16`() {
        val seeds = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24")).seeds.map { it.hostAddress }
        assertFalse("10.0.0.1" in seeds); assertFalse("10.0.1.1" in seeds); assertFalse("10.0.0.254" in seeds)
        assertTrue("10.1.1.1" in seeds); assertTrue("172.16.0.1" in seeds); assertTrue("192.168.1.1" in seeds)
        assertEquals(20, GatewayTargets.SEEDS.size)
    }
    @Test fun `a non rfc1918 phone address skips the walk but keeps the seed list`() {
        val t = GatewayTargets.of(env("100.64.1.5", "100.64.1.1"), net("10.0.3.0/24"))
        assertNull(t.enclosing); assertEquals(20, t.byUnit().values.sumOf { it.size })
    }
    @Test fun `a unit is a hit on connect or refused, ports are 80 then 443 only`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>()); val answered = Collections.synchronizedList(mutableListOf<String>())
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        val hits = GatewayProbe.run(t, connect = { a, port ->
            calls += "${a.hostAddress}:$port"
            when (a.hostAddress) { "10.0.4.1" -> Probe.Outcome.Refused; "10.0.9.254" -> if (port == 443) Probe.Outcome.Connected(3, null) else Probe.Outcome.Timeout
                "10.0.5.1" -> Probe.Outcome.Unreachable; else -> Probe.Outcome.Timeout }
        }, onAnswer = { answered += it.hostAddress }, dispatcher = Dispatchers.Default)
        assertEquals(setOf("10.0.4.1", "10.0.9.254"), answered.toSet())   // the canary must not reuse these (final review I3)
        assertEquals(listOf("10.0.4.0/24", "10.0.9.0/24"), hits.map { it.toString() })
        assertTrue(calls.all { it.endsWith(":80") || it.endsWith(":443") })
        assertFalse("10.0.5.1:443" in calls)                  // unreachable is definitive: no second port
        assertFalse("10.0.4.254:80" in calls)                 // a hit ends the unit
        assertTrue("10.0.6.1:443" in calls)                   // a timeout tries the next port
    }
    @Test fun `progress reaches every unit, never steps back, counts attempts and concurrency stays at 64`() = runBlocking {
        val inFlight = AtomicInteger(); val peak = AtomicInteger()
        var last = Triple(0, 0, 0); var steppedBack = false
        val t = GatewayTargets.of(env("10.0.3.242", "10.0.3.1"), net("10.0.3.0/24"))
        GatewayProbe.run(t, connect = { _, _ ->
            val n = inFlight.incrementAndGet(); peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }; delay(2); inFlight.decrementAndGet(); Probe.Outcome.Timeout
        }, onProgress = { d, total, attempts ->
            // Reports arrive from many threads; keep the maximum so the assertion never depends on which thread called last, and flag any step back.
            synchronized(this) { if (d < last.first) steppedBack = true; last = Triple(maxOf(last.first, d), total, maxOf(last.third, attempts)) }
        }, dispatcher = Dispatchers.Default)
        val units = t.byUnit()
        assertEquals(Triple(units.size, units.size, units.values.sumOf { it.size } * GatewayProbe.PORTS.size), last)   // every address timed out on both ports
        assertFalse("progress stepped backwards", steppedBack)
        assertTrue("peak ${peak.get()}", peak.get() <= GatewayProbe.CONCURRENCY)
    }
    @Test fun `test-net pre-check reads any answer as answers for everything`() {
        assertFalse(AnswersForEverything.of(listOf(Probe.Outcome.Timeout, Probe.Outcome.Unreachable, Probe.Outcome.Timeout, Probe.Outcome.Timeout)))
        assertTrue(AnswersForEverything.of(listOf(Probe.Outcome.Timeout, Probe.Outcome.Refused)))
        assertTrue(AnswersForEverything.of(listOf(Probe.Outcome.Connected(1, null))))
        assertEquals(listOf("192.0.2.1", "198.51.100.1"), AnswersForEverything.TEST_NET.map { it.hostAddress })
    }
    @Test fun `random high ports avoid every liveness and quick port`() {
        val r = Random(42); val excluded = (HostProber.DEFAULT_PORTS + QuickPorts.list).toSet()
        repeat(2_000) { val p = RandomHighPort.pick(r); assertTrue(p in 49152..65535 && p !in excluded) }
        assertEquals(RandomHighPort.pick(Random(7)), RandomHighPort.pick(Random(7)))
    }
}
