package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.ui.home.StaleRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

/** 3C spec 8.2 item 3 (Review Focus 2): the hidden manual row, its promotion, its discard, the orphan sweep and recent runs. In-memory only. */
@RunWith(AndroidJUnit4::class)
class ManualHostDaoTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var tools: ToolRepository
    private lateinit var networks: NetworkRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); tools = ToolRepository(db); networks = NetworkRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun aManualRowIsHiddenFromForNetworkAndShownByAll() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val manual = hosts.manualTarget(1, "10.0.3.200")
        assertTrue(manual.inserted); assertTrue(manual.host.manualOnly); assertEquals(0L, manual.host.lastDiscoveryRun)
        assertEquals(listOf(visible), hosts.hostsForNetwork(1).first().map { it.id })
        assertEquals(setOf(visible, manual.host.id), hosts.allHostsForNetwork(1).first().map { it.id }.toSet())
    }
    @Test fun manualTargetReturnsTheExistingRowVisibleOrManual() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        assertEquals(ManualRow(hosts.byIdOnce(visible)!!, inserted = false), hosts.manualTarget(1, "10.0.3.5"))
        val first = hosts.manualTarget(1, "10.0.3.201")
        val again = hosts.manualTarget(1, "10.0.3.201")
        assertFalse(again.inserted); assertEquals(first.host.id, again.host.id)
        assertEquals("the same IP on another network is another row", true, hosts.manualTarget(2, "10.0.3.201").inserted)
    }
    @Test fun aScanFindingTheIpPromotesTheRowAndKeepsItsToolRuns() = runBlocking {
        val m = hosts.manualTarget(1, "10.0.3.200").host
        val runId = tools.startRun(m.id, "http-fingerprint", 80, 5, "{}", "WIFI")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.200"), mdnsName = "printer"), runId = 7)
        assertEquals("merged into the same row, never a second", m.id, id)
        val promoted = hosts.byIdOnce(id)!!
        assertFalse(promoted.manualOnly); assertEquals(7L, promoted.lastDiscoveryRun); assertEquals("printer", promoted.mdnsName)
        assertEquals(listOf(runId), tools.runsForHost(id).first().map { it.id })
        assertEquals("WIFI", tools.runsForHost(id).first().single().route)
        assertEquals(listOf(id), hosts.hostsForNetwork(1).first().map { it.id })
    }
    @Test fun upsertHeardKeepsAVisibleRowsRunAndSetsTheNewRunOnInsertAndPromotion() = runBlocking {
        val v = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 3)
        hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.5"), mdnsName = "nas"), runIdForNew = 9)
        assertEquals(3L, hosts.byIdOnce(v)!!.lastDiscoveryRun); assertEquals("nas", hosts.byIdOnce(v)!!.mdnsName)
        val n = hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.6")), runIdForNew = 9)
        assertEquals(9L, hosts.byIdOnce(n)!!.lastDiscoveryRun)
        val m = hosts.manualTarget(1, "10.0.3.7").host
        assertEquals(m.id, hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.7")), runIdForNew = 9))
        hosts.byIdOnce(m.id)!!.let { assertFalse(it.manualOnly); assertEquals(9L, it.lastDiscoveryRun) }
    }
    @Test fun aHeardRowOnANetworkWithNoRunIsShownUndimmed() = runBlocking {
        val id = hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.8")), runIdForNew = 0)
        val h = hosts.byIdOnce(id)!!
        assertEquals(0L, h.lastDiscoveryRun)
        // HomeViewModel's latestRun with no run on this network is 0 (StaleRun.seed of a null lastRunAt); StaleRule is the rule baseState uses.
        assertFalse(StaleRule.of(latestRun = 0, h))
    }
    @Test fun deleteOrphanManualRemovesOnlyRunlessManualRows() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val orphan = hosts.manualTarget(1, "10.0.3.201").host
        val withRun = hosts.manualTarget(1, "10.0.3.202").host
        tools.startRun(withRun.id, "tls-certificate", 443, 1, "{}", "OTHER")
        assertEquals(1, hosts.deleteOrphanManual())
        assertNull(hosts.byIdOnce(orphan.id)); assertNotNull(hosts.byIdOnce(withRun.id)); assertNotNull(hosts.byIdOnce(visible))
    }
    @Test fun discardManualKeepsAPromotedOrStartedRow() = runBlocking {
        val plain = hosts.manualTarget(1, "10.0.3.203").host
        assertEquals(1, hosts.discardManual(plain.id)); assertNull(hosts.byIdOnce(plain.id))
        val promoted = hosts.manualTarget(1, "10.0.3.204").host
        hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.204")), runId = 2)
        assertEquals(0, hosts.discardManual(promoted.id)); assertNotNull(hosts.byIdOnce(promoted.id))
        val started = hosts.manualTarget(1, "10.0.3.205").host
        tools.startRun(started.id, "smb-session", 445, 1, "{}", "WIFI")
        assertEquals(0, hosts.discardManual(started.id)); assertNotNull(hosts.byIdOnce(started.id))
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.206")), runId = 2)
        assertEquals("a visible row is never discarded", 0, hosts.discardManual(visible))
    }
    @Test fun recentForNetworkOrdersLimitsAndExcludesOtherNetworks() = runBlocking {
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val b = hosts.upsertDiscovered(2, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        (1..25).forEach { p -> tools.startRun(a, "http-fingerprint", p, p.toLong(), "{}") }   // one run per port: pruning keeps all 25
        tools.startRun(b, "http-fingerprint", 80, 100, "{}")
        val recent = tools.recentForNetwork(1, 20).first()
        assertEquals(20, recent.size)
        assertTrue(recent.all { it.ip == "10.0.3.5" && it.run.hostId == a })
        assertEquals((25 downTo 6).map { it.toLong() }, recent.map { it.run.startedAt })
    }
    @Test fun forgetsCascadeRemovesManualRowsAndTheirRuns() = runBlocking {
        val m = hosts.manualTarget(1, "10.20.0.5").host
        tools.startRun(m.id, "tls-certificate", 443, 1, "{}", "OTHER")
        networks.deleteNetworkCascade(1)
        assertTrue(hosts.allHostsForNetwork(1).first().isEmpty())
        assertTrue(tools.runsForHost(m.id).first().isEmpty())
    }
}
