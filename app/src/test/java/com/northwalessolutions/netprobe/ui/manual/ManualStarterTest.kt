package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ManualRow
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.engine.tools.RouteContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private val RIG_ENV = NetworkEnv(Ipv4.parse("10.0.3.20")!!, 24, Ipv4.parse("10.0.3.1")!!, emptyList(), "Home", null, Transport.WIFI)

/** 3C spec 8.1 item 22 (4.3, G8, Review Focus 2 and 3). */
class ManualStarterTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val env = RIG_ENV

    private class Rig(var networkId: Long? = 1, var binder: Boolean = true, var existing: HostEntity? = null,
                      var result: ToolRunner.Start = ToolRunner.Start.Started(5), var throwOnStart: Boolean = false,
                      var envNow: NetworkEnv? = RIG_ENV, var ctx: RouteContext = RouteContext.CONNECTED) {
        val calls = mutableListOf<String>()
        val targets = mutableListOf<ToolTarget>()
        val discarded = mutableListOf<Long>()
        var inserted = 0
        val starter = ManualStarter(
            committedBefore = { body -> calls += "commit"; body() },
            network = { calls += "network"; networkId },
            known = { _, _ -> KnownRanges.of(SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24), emptyList(), emptyList()) },
            hasBinder = { binder },
            routeContext = { ctx },
            envNow = { envNow },
            target = { id, ipText ->
                calls += "target"
                existing?.let { ManualRow(it, inserted = false) }
                    ?: ManualRow(TestHosts.host(ip = ipText).copy(id = NEW_ROW, networkId = id, manualOnly = true), inserted = true).also { inserted++ }
            },
            discard = { id -> discarded += id },
            runnerStart = { t, _, _ ->
                calls += "start"; targets += t
                if (throwOnStart) throw IllegalStateException("runner failed")
                result
            })
        companion object { const val NEW_ROW = 40L }
    }
    private fun req(ipText: String, tool: String = ToolKeys.HTTP_FINGERPRINT, port: Int = 443, tls: Boolean = true) = ManualRequest(env, ip(ipText), port, tool, tls)

    @Test fun `the pending Undo is committed before the row is looked up or made`() = runBlocking {
        val rig = Rig(); rig.starter.startManual(req("10.0.3.5"))
        assertEquals(listOf("commit", "network", "target", "start"), rig.calls)
    }
    @Test fun `a refused start deletes the row this call inserted and keeps a row that existed`() = runBlocking {
        for (refusal in listOf(ToolRunner.Start.TooMany, ToolRunner.Start.HostBusy, ToolRunner.Start.NoNetwork)) {
            val fresh = Rig(result = refusal)
            assertEquals(refusal, fresh.starter.startManual(req("10.0.3.200")).start)
            assertEquals("$refusal", listOf(Rig.NEW_ROW), fresh.discarded)
            val known = Rig(result = refusal, existing = TestHosts.host(ip = "10.0.3.5").copy(id = 9))
            assertEquals(ManualResult(refusal, 9), known.starter.startManual(req("10.0.3.5")))
            assertTrue("$refusal: a row that existed is never deleted", known.discarded.isEmpty())
        }
    }
    @Test fun `a started run keeps its row`() = runBlocking {
        val rig = Rig()
        assertEquals(ManualResult(ToolRunner.Start.Started(5), Rig.NEW_ROW), rig.starter.startManual(req("10.0.3.200")))
        assertTrue(rig.discarded.isEmpty())
    }
    @Test fun `a start that throws deletes the inserted row and rethrows`() = runBlocking {
        val rig = Rig(throwOnStart = true)
        try { rig.starter.startManual(req("10.0.3.200")); fail("expected the runner's exception") } catch (_: IllegalStateException) { }
        assertEquals(listOf(Rig.NEW_ROW), rig.discarded)
    }
    @Test fun `no network ends with NoNetwork and creates no row`() = runBlocking {
        val rig = Rig(networkId = null)
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, null), rig.starter.startManual(req("10.0.3.5")))
        assertEquals(0, rig.inserted); assertFalse("target" in rig.calls); assertFalse("start" in rig.calls)
    }
    @Test fun `a known-range target with no Wi-Fi binder is NoNetwork before any row exists`() = runBlocking {
        val rig = Rig(binder = false)
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, null), rig.starter.startManual(req("10.0.3.5")))
        assertEquals(0, rig.inserted); assertTrue(rig.targets.isEmpty())
    }
    @Test fun `a target outside the known ranges needs no binder and is routed OTHER`() = runBlocking {
        val rig = Rig(binder = false)
        assertTrue(rig.starter.startManual(req("10.20.0.5")).start is ToolRunner.Start.Started)
        assertEquals(ToolRoute.OTHER, rig.targets.single().route)
        val public = Rig(); public.starter.startManual(req("1.1.1.1", ToolKeys.TLS_CERTIFICATE))
        assertEquals(ToolRoute.OTHER, public.targets.single().route)
    }
    @Test fun `the target carries the port, tls, route and the name rule`() = runBlocking {
        val nas = TestHosts.host(ip = "10.0.3.59", mdns = "chnas.local").copy(id = 9)
        val fingerprint = Rig(existing = nas); fingerprint.starter.startManual(req("10.0.3.59", ToolKeys.HTTP_FINGERPRINT, port = 5000, tls = false))
        assertEquals(ToolTarget(9, ip("10.0.3.59"), 5000, tls = false, serverName = "chnas.local", route = ToolRoute.WIFI), fingerprint.targets.single())
        val brute = Rig(existing = nas); brute.starter.startManual(req("10.0.3.59", ToolKeys.DIR_BRUTEFORCE, port = 5001, tls = true))
        assertEquals("brute-force always sends the IP (3B 4.9)", ToolTarget(9, ip("10.0.3.59"), 5001, tls = true, serverName = null, route = ToolRoute.WIFI), brute.targets.single())
        val fresh = Rig(); fresh.starter.startManual(req("10.0.3.200", ToolKeys.TLS_CERTIFICATE))
        assertNull("a fresh manual row has no name to send", fresh.targets.single().serverName)
    }
    @Test fun `a network change between the tap and the start refuses and discards the new row`() = runBlocking {
        val rig = Rig(envNow = RIG_ENV.copy(gateway = Ipv4.parse("10.0.9.1")!!))
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, Rig.NEW_ROW), rig.starter.startManual(req("10.0.3.200")))
        assertEquals(listOf(Rig.NEW_ROW), rig.discarded); assertFalse("start" in rig.calls)
        assertEquals("no LAN at all refuses too", ToolRunner.Start.NoNetwork, Rig(envNow = null).starter.startManual(req("10.0.3.200")).start)
    }
    @Test fun `R4 sends an offline LAN's private outside target over Wi-Fi, so it needs the binder`() = runBlocking {
        val offline = RouteContext(lanHasInternet = false, defaultIsVpn = false)
        val rig = Rig(ctx = offline); rig.starter.startManual(req("10.20.0.5"))
        assertEquals(ToolRoute.WIFI, rig.targets.single().route)
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, null), Rig(ctx = offline, binder = false).starter.startManual(req("10.20.0.5")))
    }
    @Test fun `each refusal maps to the 3B message`() {
        assertNull(ManualMessages.of(ManualResult(ToolRunner.Start.Started(1), 9), emptyMap()))
        assertEquals(ToolCopy.BUSY_TOOL, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), mapOf(9L to HostBusyRegistry.Work.TOOL)))
        assertEquals(ToolCopy.BUSY_PORT_SCAN, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), mapOf(9L to HostBusyRegistry.Work.PORT_SCAN)))
        assertEquals("a claim released in between still reads as a tool", ToolCopy.BUSY_TOOL, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), emptyMap()))
        assertEquals(ToolCopy.BUSY_TOO_MANY, ManualMessages.of(ManualResult(ToolRunner.Start.TooMany, 9), emptyMap()))
        assertEquals(ScanController.NO_NETWORK, ManualMessages.of(ManualResult(ToolRunner.Start.NoNetwork, null), emptyMap()))
    }
    @Test fun `brute-force opens its sheet first, so the starter is reached only from the sheet's Run`() {
        assertEquals(ManualStep.OPEN_SHEET, ManualFlow.firstStep(ToolKeys.DIR_BRUTEFORCE))
        for (t in ToolKeys.BUILT_IN - ToolKeys.DIR_BRUTEFORCE) assertEquals(t, ManualStep.START, ManualFlow.firstStep(t))
    }
}
