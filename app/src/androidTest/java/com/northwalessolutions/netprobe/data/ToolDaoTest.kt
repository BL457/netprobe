package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class ToolDaoTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var ports: PortRepository
    private lateinit var tools: ToolRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); ports = PortRepository(db); tools = ToolRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private suspend fun host(ip: String) = hosts.upsertDiscovered(1, DiscoveredHost(ip(ip)), runId = 1)
    private suspend fun doneRun(hostId: Long, tool: String, port: Int, at: Long, hints: List<String> = emptyList(), shown: Int = 0): Long {
        val id = tools.startRun(hostId, tool, port, at, "{}")
        repeat(shown) { tools.addFinding(id, "PATH", "/p$it", null, null) }
        hints.forEach { tools.addFinding(id, "HINT", it, null, null) }
        tools.finish(id, at + 1, ToolRunStatus.DONE, "done")
        return id
    }

    @Test fun insertAndPruneKeepsTheNewestTenPerHostAndTool() = runBlocking {
        val h = host("10.0.3.59")
        val ids = (1..13L).map { tools.startRun(h, "dir-bruteforce", 5000, it, "{}").also { id -> tools.finish(id, it + 1, ToolRunStatus.DONE, "r$it") } }
        // Another tool and another port are untouched by pruning this (tool, port).
        tools.startRun(h, "http-fingerprint", 80, 100, "{}")
        val kept = tools.runsForHost(h).first()
        assertEquals(10, kept.count { it.toolId == "dir-bruteforce" })
        assertFalse(kept.any { it.id == ids[0] || it.id == ids[2] })   // the three oldest pruned
        assertEquals(1, kept.count { it.toolId == "http-fingerprint" })
    }
    @Test fun findingsExcludeHintsAndTheCountIgnoresHints() = runBlocking {
        val h = host("10.0.3.60"); val id = doneRun(h, "dir-bruteforce", 5000, 1, hints = listOf("http.path=/admin"), shown = 3)
        assertEquals(3, tools.findings(id).first().size); assertEquals(3, tools.findingCount(id))
        assertTrue(tools.findings(id).first().none { it.kind == "HINT" })
    }
    @Test fun deletingAHostCascadesToRunsAndFindings() = runBlocking {
        val h = host("10.0.3.61"); doneRun(h, "dir-bruteforce", 5000, 1, shown = 2)
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        db.openHelper.writableDatabase.execSQL("DELETE FROM hosts WHERE id = $h")
        assertEquals(emptyList<ToolRunEntity>(), tools.runsForHost(h).first())
    }
    @Test fun upsertDiscoveredLeavesToolRunsIntact() = runBlocking {   // the REPLACE guard (spec 7.2)
        val h = host("10.0.3.62"); val id = doneRun(h, "dir-bruteforce", 5000, 1, shown = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.62"), mdnsName = "again"), runId = 2)
        assertTrue(tools.runsForHost(h).first().any { it.id == id })
    }
    @Test fun markInterruptedStampsEveryRunningRow() = runBlocking {
        val h = host("10.0.3.63"); val id = tools.startRun(h, "dir-bruteforce", 5000, 1, "{}"); tools.addFinding(id, "PATH", "/a", null, null)
        tools.markInterrupted { n -> "Interrupted, $n found" }
        val r = tools.run(id).first()!!
        assertEquals(ToolRunStatus.INTERRUPTED, r.status); assertNull(r.finishedAt); assertEquals("Interrupted, 1 found", r.summary)
        assertTrue(tools.ready.isCompleted)
    }
    @Test fun hintsForPicksPerToolAndPort() = runBlocking {   // Review Focus 4
        val h = host("10.0.3.64")
        doneRun(h, "http-fingerprint", 5000, 10, hints = listOf("http.framework=Synology DSM"))
        doneRun(h, "http-fingerprint", 80, 20, hints = emptyList())   // newer, empty; must not hide 5000
        val stoppedId = tools.startRun(h, "tls-certificate", 443, 30, "{}"); tools.addFinding(stoppedId, "HINT", "tls.subject=x", null, null)
        tools.finish(stoppedId, 31, ToolRunStatus.STOPPED, "stopped")
        doneRun(h, "tls-certificate", 443, 15, hints = listOf("tls.subject=done"))   // older DONE wins over newer STOPPED
        val hints = tools.hintsFor(h)
        assertTrue("Synology DSM" in hints.map { it.substringAfter("=") })
        assertTrue("done" in hints.map { it.substringAfter("=") }); assertFalse("x" in hints.map { it.substringAfter("=") })
    }
    @Test fun setOptionsRewritesOneRowAndHostsWithRunsListsOnlyHostsThatHaveRuns() = runBlocking {
        val h = host("10.0.3.66"); host("10.0.3.67")
        val id = tools.startRun(h, "dir-bruteforce", 5000, 1, "{}")
        tools.setOptions(id, "{\"methodUsed\":\"GET_ONLY\"}")
        assertEquals("{\"methodUsed\":\"GET_ONLY\"}", tools.run(id).first()!!.optionsJson)
        assertEquals(setOf(h), tools.hostsWithRuns())
    }
    @Test fun snapshotSurvivesSeparateTransactionsAndRestoresIds() = runBlocking {
        val h = host("10.0.3.65"); val id = doneRun(h, "dir-bruteforce", 5000, 1, hints = listOf("http.path=/admin"), shown = 2)
        val token = tools.snapshot(listOf(h))
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        hosts.delete(hosts.snapshot(h)!!)                              // cascade drops the tool rows
        assertEquals(emptyList<ToolRunEntity>(), tools.runsForHost(h).first())
        // Re-insert the host under its id (as 3A's restore does) so the foreign key holds, then restore the tool rows.
        db.openHelper.writableDatabase.execSQL("INSERT INTO hosts (id, networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource) " +
            "VALUES ($h, 1, '10.0.3.65', 'UNKNOWN', 0, '[]', 'UNMARKED', '', 0, 0, 0, 0, 0)")
        tools.restore(token)
        val runs = tools.runsForHost(h).first()
        assertEquals(1, runs.size); assertEquals(id, runs.first().id); assertEquals(2, tools.findingCount(id))
        assertEquals(listOf("http.path=/admin"), tools.hintsFor(h))
    }
}
