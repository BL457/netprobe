package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import org.junit.Assert.*
import org.junit.Test

/** Runs with no device (schema 6): Scans rows, the stop route, the results header and the export all read "No device" and never crash. */
class NoDeviceRowsTest {
    private val reg = TestRegistry.registry()
    private val now = 10L * ScansRules.RECENT_WINDOW_MS
    private fun run(id: Long, hostId: Long?, status: ToolRunStatus = ToolRunStatus.DONE, toolId: String = "user:dns-lookup", name: String = "DNS lookup") =
        ToolRunEntity(id = id, hostId = hostId, toolId = toolId, port = 0, startedAt = now - 5_000, finishedAt = if (status == ToolRunStatus.RUNNING) null else now - 4_000,
            status = status, optionsJson = "{}", summary = "3 records", toolName = name)
    private fun input(history: List<NetworkRun> = emptyList(), active: Map<Long, ToolRunner.Active> = emptyMap()) =
        ScansInput(reg, emptyList(), active, emptyList(), history, mapOf(7L to "Office NAS"), 1, now, 0, null, emptySet(), null, emptyMap(), formatDate = { "3 Jan 2026" })

    @Test fun `a finished run with no device names the stored tool and says No device, with no port`() {
        val item = ScansRules.build(input(history = listOf(NetworkRun(run(1, null), null)))).recent.single()
        assertEquals("DNS lookup on No device", item.title); assertEquals("No device", item.host); assertNull(item.hostId)
        assertEquals("user:dns-lookup", item.tool); assertEquals(ScansSource.TOOL, item.source)
    }
    @Test fun `a removed tool with a device keeps its stored name`() {
        val item = ScansRules.build(input(history = listOf(NetworkRun(run(2, 7, toolId = "user:gone", name = "Old tool"), "10.0.3.59")))).recent.single()
        assertEquals("Old tool on Office NAS:0", item.title)
    }
    @Test fun `a live run with no device shows in Running and stops by its run id`() {
        val a = ToolRunner.Active(5, null, "user:dns-lookup", 0, 4_000, 1, 2, 0, 0, false)
        val item = ScansRules.build(input(active = mapOf(5L to a))).running.single()
        assertEquals("user:dns-lookup on No device", item.title); assertNull(item.hostId)
        assertEquals(StopRoute.Tool(null, 5), ScansTargets.stop(item))
        assertTrue(ScansTargets.toolStopApplies(StopRoute.Tool(null, 5), 5)); assertFalse(ScansTargets.toolStopApplies(StopRoute.Tool(null, 5), 6))
    }
    @Test fun `the running count includes runs with no device`() {
        val a = ToolRunner.Active(5, null, "user:dns-lookup", 0, 0, 0, null, 0, 0, false)
        assertEquals(1, ScansRules.runningCount(emptyList(), mapOf(5L to a)))
    }
    @Test fun `the results header and the export read No device`() {
        val r = run(3, null)
        val state = ToolResultsModel.ofRows(reg, r, listOf(r), null, emptyList(), null, now)
        assertEquals("DNS lookup  No device", state.title)
        val e = ToolExport.of(r, null, emptyList())
        assertEquals("DNS lookup", e.toolName); assertEquals(ToolCopy.NO_DEVICE, e.target)
        assertEquals("NetProbe DNS lookup", ToolExport.subject(e.toolName, null, 0))
        assertTrue(ToolExport.text(e).contains("Target: No device"))
    }
}
