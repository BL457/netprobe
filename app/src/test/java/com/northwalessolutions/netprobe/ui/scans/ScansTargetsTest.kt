package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.tools.ToolId
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 6 (every row of 3.6) and Review Focus 2. */
class ScansTargetsTest {
    private fun item(source: ScansSource, state: ScansState = ScansState.RUNNING, hostId: Long? = 7, runId: Long? = null, liveId: Long? = 3) =
        ScansItem("k", source, liveId, 1, hostId, null, null, null, state, null, null, null, 0, null, null, null, runId, "t", "d")
    private val visible = setOf(7L)

    @Test fun `radar and Quick scan all open the Devices destination, running or finished`() {
        for (s in listOf(ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL)) for (st in listOf(ScansState.RUNNING, ScansState.DONE)) {
            assertEquals(ScansTap.DevicesTab, ScansTargets.tap(item(s, st, hostId = null), visible))
        }
    }
    @Test fun `a port scan opens its device when the host is visible and nothing when it is gone`() {
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.PORT_SCAN), visible))
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.PORT_SCAN, ScansState.DONE), visible))
        assertEquals(ScansTap.None, ScansTargets.tap(item(ScansSource.PORT_SCAN, ScansState.DONE, hostId = 99), visible))
    }
    @Test fun `a running tool on a visible host opens the device and on a manual only host opens the results`() {
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.TOOL, runId = 5), visible))
        assertEquals(ScansTap.Results(5), ScansTargets.tap(item(ScansSource.TOOL, hostId = 12, runId = 5), visible))
    }
    @Test fun `a finished tool run opens its results sheet`() {
        assertEquals(ScansTap.Results(5), ScansTargets.tap(item(ScansSource.TOOL, ScansState.DONE, runId = 5), visible))
        assertEquals(ScansTap.None, ScansTargets.tap(item(ScansSource.TOOL, ScansState.DONE, runId = null), visible))
    }
    @Test fun `an import opens Wordlists`() {
        assertEquals(ScansTap.Wordlists, ScansTargets.tap(item(ScansSource.WORDLIST_IMPORT, hostId = null), visible))
        assertEquals(ScansTap.Wordlists, ScansTargets.tap(item(ScansSource.WORDLIST_IMPORT, ScansState.DONE, hostId = null), visible))
    }
    @Test fun `running items stop through their live id and tools through their host`() {
        for (s in listOf(ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL, ScansSource.PORT_SCAN, ScansSource.WORDLIST_IMPORT)) {
            assertEquals(StopRoute.Live(3), ScansTargets.stop(item(s)))
        }
        assertEquals(StopRoute.Tool(7, 5), ScansTargets.stop(item(ScansSource.TOOL, runId = 5)))
    }
    @Test fun `finished items have no stop route`() {
        for (s in ScansSource.entries) for (st in ScansState.entries.filter { it != ScansState.RUNNING }) assertNull("$s $st", ScansTargets.stop(item(s, st)))
        assertNull("a running item with no live id has nothing to stop", ScansTargets.stop(item(ScansSource.PORT_SCAN, liveId = null)))
    }
    @Test fun `a tool stop applies only while that run is still the one active on the host`() {
        val route = StopRoute.Tool(7, 5)
        assertTrue(ScansTargets.toolStopApplies(route, activeRunId = 5))
        assertFalse("a newer run on the same host", ScansTargets.toolStopApplies(route, activeRunId = 6))
        assertFalse("the run already ended", ScansTargets.toolStopApplies(route, activeRunId = null))
    }
    @Test fun `a tool row with no run id has no stop route`() {
        assertNull(ScansTargets.stop(item(ScansSource.TOOL, runId = null)))
    }
}
