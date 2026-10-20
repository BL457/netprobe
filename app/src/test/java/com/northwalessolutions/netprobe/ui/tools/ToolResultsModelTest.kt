package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import org.junit.Assert.*
import org.junit.Test

/** spec 8.5: the results header, the run switcher position, rows with their url, the active state. */
class ToolResultsModelTest {
    private val now = 10_000_000L
    private fun run(id: Long, at: Long, json: String = "{\"tls\":false,\"serverName\":null}", status: ToolRunStatus = ToolRunStatus.DONE) =
        ToolRunEntity(id = id, hostId = 7, toolId = ToolId.HTTP_FINGERPRINT.key, port = 5000, startedAt = at, finishedAt = at + 1, status = status, optionsJson = json, summary = "Pi-hole, 6 findings")
    @Test fun `header, position and rows`() {
        val runs = listOf(run(3, now - 60_000), run(2, now - 7_200_000, "{\"tls\":false,\"serverName\":\"pi.hole\"}"), run(1, now - 9_000_000))
        val f = listOf(ToolFindingEntity(5, 2, "STATUS", "200 OK", "GET /", "{\"url\":\"http://10.0.3.5:5000/\"}"), ToolFindingEntity(6, 2, "TITLE", "Pi-hole", "Page title", null))
        val s = ToolResultsModel.of(runs[1], runs, "10.0.3.5", f, null, now)
        assertEquals("HTTP fingerprint  10.0.3.5:5000", s.title); assertEquals("Done, 2 hours ago, as pi.hole", s.statusLine); assertEquals("Pi-hole, 6 findings", s.summary)
        assertEquals(2, s.index); assertEquals(3, s.count)
        assertEquals(listOf(FindingRow(5, "200 OK", "GET /", "http://10.0.3.5:5000/"), FindingRow(6, "Pi-hole", "Page title", null)), s.findings)
        assertFalse(s.active)
    }
    @Test fun `an active run shows Running and its live progress`() {
        val r = run(3, now, status = ToolRunStatus.RUNNING)
        val s = ToolResultsModel.of(r, listOf(r), "10.0.3.5", emptyList(), ToolRunner.Active(3, 7, ToolId.HTTP_FINGERPRINT, 5000, 3_000, 2, 5, 1, 0, false), now)
        assertEquals("Running, 2 of 5, 1 found  3 s", s.statusLine); assertTrue(s.active)
    }
}
