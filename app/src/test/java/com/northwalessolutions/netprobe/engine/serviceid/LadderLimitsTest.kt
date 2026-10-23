package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The ladder limits: a scan keeps today's caps, a manual identify (thorough) plans every safe hello over the shipped probe file. */
class LadderLimitsTest {
    private val net = FakeNetwork()
    private val probes = ServiceProbeRules.load(listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()).probes
    private val ladder = ServiceProbes(probes, net::transport, clock = { net.clock.now })
    private fun budget() = ActiveProbeBudget(MAX_ACTIVE_PORTS, Long.MAX_VALUE, Long.MAX_VALUE) { net.clock.now }
    private fun ran(port: Int, limits: LadderLimits): List<String> = kotlinx.coroutines.runBlocking {
        net.connects.set(0)
        net.respond = { _, _, _ -> Reply.SILENT }
        ladder.run(java.net.InetAddress.getByName("10.0.3.59"), port, port, "Unknown", ProbeMode.Active(budget(), hellos = true, limits = limits), 1L).trace.map { it.id }
    }

    @Test fun `the scan limits are still 6 connections, 6000 ms and rarity 3, and they are the default`() {
        assertEquals(LadderLimits(6, 6_000L, 3), LadderLimits.SCAN)
        assertEquals(6, ServiceProbes.MAX_PROBES_PER_PORT); assertEquals(6_000L, ServiceProbes.PORT_WALL_MS); assertEquals(3, ServiceProbes.DEFAULT_MAX_RARITY)
        assertSame(LadderLimits.SCAN, ProbeMode.Active(budget()).limits)
    }
    @Test fun `thorough limits have no wall, every rarity, every hello and no cap beyond the plan`() {
        assertEquals(Long.MAX_VALUE, LadderLimits.THOROUGH.portWallMs); assertEquals(9, LadderLimits.THOROUGH.maxRarity); assertEquals(Int.MAX_VALUE, LadderLimits.THOROUGH.maxConnects)
        assertTrue(LadderLimits.THOROUGH.allHellos); assertFalse(LadderLimits.SCAN.allHellos)
    }
    @Test fun `a scan on an unhinted port stops at six connections and never reaches rarity 4`() {
        val ids = ran(9999, LadderLimits.SCAN)
        assertEquals(6, net.connects.get())
        assertFalse("rtsp-options" in ids)
    }
    @Test fun `a thorough plan on an unhinted port includes probes above rarity 3 and the connection cap equals the plan`() {
        val ids = ran(9999, LadderLimits.THOROUGH)
        assertTrue("rtsp-options" in ids)
        assertTrue(probes.first { it.id == "rtsp-options" }.rarity > ServiceProbes.DEFAULT_MAX_RARITY)
        val sending = probes.filter { it.sendsBytes && it.safety == ProbeSafety.SAFE }
        assertEquals("null, the TLS check and every safe hello that sends bytes", 2 + sending.size, net.connects.get())
        assertTrue("no careful probe with unsafe hellos off", ids.none { id -> probes.first { it.id == id }.safety == ProbeSafety.CAREFUL })
        assertEquals("the connections made are the plan", net.connects.get(), ladder.plannedProbeCount(9999, LadderLimits.THOROUGH))
        assertTrue(ladder.plannedProbeCount(9999, LadderLimits.THOROUGH) > ServiceProbes.MAX_PROBES_PER_PORT)
        assertEquals(6, ladder.plannedProbeCount(9999, LadderLimits.SCAN))
    }
    @Test fun `hinted probes still go first under thorough limits`() {
        val ids = ran(6379, LadderLimits.THOROUGH)
        assertEquals("null", ids[0]); assertEquals("redis-info", ids[1])
    }
    @Test fun `with unsafe hellos on the careful probes follow every safe one`() {
        val on = LadderLimits.thorough(true)
        val ids = ran(9999, on)
        val sending = probes.filter { it.sendsBytes }
        assertEquals(2 + sending.size, net.connects.get())
        val safeLast = ids.indexOfLast { id -> probes.first { it.id == id }.safety == ProbeSafety.SAFE }
        val carefulFirst = ids.indexOfFirst { id -> probes.first { it.id == id }.safety == ProbeSafety.CAREFUL }
        assertTrue(safeLast < carefulFirst)
        assertEquals(ladder.plannedProbeCount(9999, on), net.connects.get())
        assertFalse(LadderLimits.thorough(false).includeUnsafe)
        assertEquals("scan limits are untouched by the switch", 6, ladder.plannedProbeCount(9999, LadderLimits.SCAN))
    }
    @Test fun `unsafe hellos on reach industrial ports too, off they keep the careful-only rule`() {
        val on = ran(502, LadderLimits.thorough(true))
        assertEquals("modbus-device-id", on.first())
        assertTrue(on.size > 10)
    }
    @Test fun `a manual run also tries every hello on fragile and hint-only ports, a scan still does not`() {
        for (port in listOf(9100, 515, 631)) {
            assertTrue("scan on $port", ran(port, LadderLimits.SCAN).size <= 2)
            assertTrue("manual on $port", ran(port, LadderLimits.THOROUGH).size >= 7)
        }
    }
    @Test fun `industrial ports keep the careful-only rule under thorough limits`() {
        assertEquals(ran(502, LadderLimits.SCAN), ran(502, LadderLimits.THOROUGH))
        assertEquals(listOf("modbus-device-id"), ran(502, LadderLimits.THOROUGH))
    }
    @Test fun `a thorough run has no wall, so a slow silent port is still tried hello by hello`() {
        net.connectCostMs = 30_000L
        assertEquals(ladder.plannedProbeCount(9999, LadderLimits.THOROUGH), ran(9999, LadderLimits.THOROUGH).let { net.connects.get() })
    }
}
