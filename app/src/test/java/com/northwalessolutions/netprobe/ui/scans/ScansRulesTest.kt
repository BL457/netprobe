package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.IdentifyCount
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.engine.tools.TestRegistry
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 items 4 and 5 (rules, window, clear, drops, badge) plus Review Focus 4. */
class ScansRulesTest {
    private val now = 10L * ScansRules.RECENT_WINDOW_MS
    private val day = ScansRules.RECENT_WINDOW_MS

    private fun live(id: Long, kind: LiveWork.Kind = LiveWork.Kind.PORT_SCAN, hostId: Long? = 7, startedAt: Long = now - 5_000, networkId: Long? = 1, label: String? = null,
                     done: Int = 0, total: Int? = null, found: Int = 0) = LiveWork.Live(id, kind, networkId, hostId, label, startedAt, done, total, found)
    private fun ended(id: Long, endedAt: Long?, startedAt: Long = (endedAt ?: now) - 3_000, outcome: LiveWork.Outcome = LiveWork.Outcome.DONE, summary: String = "Done in 3 s, 2 open ports",
                      kind: LiveWork.Kind = LiveWork.Kind.PORT_SCAN, hostId: Long? = 7, networkId: Long? = 1) =
        LiveWork.Ended(live(id, kind, hostId, startedAt, networkId), outcome, endedAt, summary)
    private fun run(id: Long, status: ToolRunStatus, startedAt: Long, finishedAt: Long?, hostId: Long = 7, tool: String = ToolKeys.HTTP_FINGERPRINT, port: Int = 80, summary: String = "Done") =
        NetworkRun(ToolRunEntity(id = id, hostId = hostId, toolId = tool, port = port, startedAt = startedAt, finishedAt = finishedAt, status = status, optionsJson = "{}", summary = summary),
            ip = "10.0.3.$hostId")
    private fun active(runId: Long, hostId: Long = 7, tool: String = ToolKeys.DIR_BRUTEFORCE, port: Int = 443, activeMs: Long = 4_000, done: Int = 10, total: Int? = 100, found: Int = 2) =
        ToolRunner.Active(runId, hostId, tool, port, activeMs, done, total, found, 0, false)
    private fun input(live: List<LiveWork.Live> = emptyList(), active: Map<Long, ToolRunner.Active> = emptyMap(), ended: List<LiveWork.Ended> = emptyList(),
                      history: List<NetworkRun> = emptyList(), names: Map<Long, String> = mapOf(7L to "Office NAS"), currentNetworkId: Long? = 1, clearedAt: Long = 0,
                      forgotten: Long? = null, removed: Set<Long> = emptySet(), radar: DiscoveryStatus? = null, ports: Map<Long, PortScanState> = emptyMap()) =
        ScansInput(TestRegistry.registry(), live, active, ended, history, names, currentNetworkId, now, clearedAt, forgotten, removed, radar, ports, formatDate = { "3 Jan 2026" })

    @Test fun `running is newest first and a tie breaks by key`() {
        val l = ScansRules.build(input(live = listOf(live(1, startedAt = now - 9_000), live(3, startedAt = now - 1_000), live(2, startedAt = now - 1_000)))).running
        assertEquals(listOf("live:2", "live:3", "live:1"), l.map { it.key })
    }
    @Test fun `recent is newest first and an interrupted item is windowed and sorted by its start with no duration`() {
        val e = listOf(ended(1, now - 3_000), ended(2, now - 1_000), ended(3, null, startedAt = now - 2_000, outcome = LiveWork.Outcome.INTERRUPTED, summary = ScansCopy.INTERRUPTED))
        val recent = ScansRules.build(input(ended = e)).recent
        assertEquals(listOf("live:2", "live:3", "live:1"), recent.map { it.key })
        val lost = recent.first { it.key == "live:3" }
        assertEquals(ScansState.INTERRUPTED, lost.state); assertNull(lost.durationMs); assertNull(lost.endedAt)
        assertEquals("Interrupted when NetProbe closed, just now", lost.detail)
        val old = ended(4, null, startedAt = now - day - 1, outcome = LiveWork.Outcome.INTERRUPTED)
        assertTrue("windowed by its start", ScansRules.build(input(ended = listOf(old))).recent.isEmpty())
    }
    @Test fun `a live tool run and its RUNNING history row give one running item`() {
        val l = ScansRules.build(input(active = mapOf(5L to active(5)), history = listOf(run(5, ToolRunStatus.RUNNING, now - 4_000, null))))
        assertEquals(listOf("tool:5"), l.running.map { it.key }); assertTrue(l.recent.isEmpty())
        assertEquals(now - 4_000, l.running.single().startedAt)
    }
    @Test fun `a RUNNING history row with no live run is dropped`() {
        val l = ScansRules.build(input(history = listOf(run(5, ToolRunStatus.RUNNING, now - 4_000, null))))
        assertTrue(l.running.isEmpty()); assertTrue(l.recent.isEmpty())
    }
    @Test fun `four RUNNING rows among fifty four fetched still leave fifty recent`() {
        val running = (1L..4L).map { run(100 + it, ToolRunStatus.RUNNING, now - it, null, hostId = 8) }
        val done = (1L..50L).map { run(it, ToolRunStatus.DONE, now - 10_000 - it, now - 5_000 - it) }
        val l = ScansRules.build(input(history = running + done))
        assertEquals(ScansRules.RECENT_LIMIT, l.recent.size); assertTrue(l.recent.all { it.state == ScansState.DONE })
        assertEquals(ScansRules.RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS, ScansRules.HISTORY_FETCH)
    }
    @Test fun `the window is twenty four hours inclusive of one millisecond inside`() {
        val inside = ended(1, now - day + 1); val outside = ended(2, now - day - 1)
        assertEquals(listOf("live:1"), ScansRules.build(input(ended = listOf(inside, outside))).recent.map { it.key })
    }
    @Test fun `the fifty first recent item is excluded`() {
        val e = (1L..51L).map { ended(it, now - it * 1_000) }
        val recent = ScansRules.build(input(ended = e)).recent
        assertEquals(50, recent.size); assertFalse(recent.any { it.key == "live:51" })
    }
    @Test fun `items at or before the clear time are excluded and after it included`() {
        val cut = now - 5_000          // inside the 24 hour window, so only the cut-off decides
        val e = listOf(ended(1, cut), ended(2, cut + 1), ended(3, cut - 1))
        assertEquals(listOf("live:2"), ScansRules.build(input(ended = e, clearedAt = cut)).recent.map { it.key })
    }
    @Test fun `a forgotten network and a removed host drop their items in both sections`() {
        val l = ScansRules.build(input(
            live = listOf(live(1, networkId = 9), live(2, hostId = 8, networkId = 1), live(3, hostId = 7, networkId = 1)),
            ended = listOf(ended(4, now - 1_000, networkId = 9), ended(5, now - 1_000, hostId = 8), ended(6, now - 1_000)),
            forgotten = 9, removed = setOf(8)))
        assertEquals(listOf("live:3"), l.running.map { it.key }); assertEquals(listOf("live:6"), l.recent.map { it.key })
    }
    @Test fun `tool history is empty with no current network but memory outcomes stay`() {
        val l = ScansRules.build(input(history = listOf(run(1, ToolRunStatus.DONE, now - 9_000, now - 8_000)), ended = listOf(ended(2, now - 1_000)), currentNetworkId = null))
        assertEquals(listOf("live:2"), l.recent.map { it.key })
    }
    @Test fun `a manual only host resolves to its IP and a known host to its name`() {
        val l = ScansRules.build(input(history = listOf(run(1, ToolRunStatus.DONE, now - 9_000, now - 8_000, hostId = 12), run(2, ToolRunStatus.DONE, now - 7_000, now - 6_000, hostId = 7)),
            names = mapOf(7L to "Office NAS")))
        assertEquals("HTTP fingerprint on 10.0.3.12:80", l.recent.first { it.key == "tool:1" }.title)
        assertEquals("HTTP fingerprint on Office NAS:80", l.recent.first { it.key == "tool:2" }.title)
    }
    @Test fun `an import item carries its URL host`() {
        val l = ScansRules.build(input(live = listOf(live(1, LiveWork.Kind.WORDLIST_IMPORT, hostId = null, label = "raw.githubusercontent.com", done = 1_258_291, total = 3_565_158))))
        val item = l.running.single()
        assertEquals("Wordlist from raw.githubusercontent.com", item.title); assertEquals("raw.githubusercontent.com", item.host)
        assertEquals("Fetching 1.2 of 3.4 MB", item.detail); assertEquals(ScansSource.WORDLIST_IMPORT, item.source)
    }
    @Test fun `running rows say what each kind is doing`() {
        val ports = mapOf(7L to PortScanState(ScanProgress(312, 65_535, 4, 0), IdentifyCount(0, 0)))
        val l = ScansRules.build(input(
            live = listOf(live(1, LiveWork.Kind.RADAR, hostId = null, startedAt = now - 7_000), live(2, LiveWork.Kind.QUICK_SCAN_ALL, hostId = null, startedAt = now - 42_000, done = 3, total = 30),
                live(3, LiveWork.Kind.PORT_SCAN, hostId = 7, startedAt = now - 1_000, done = 312, total = 65_535, found = 4)),
            active = mapOf(5L to active(5, hostId = 9, activeMs = 4_000)), ports = ports, radar = DiscoveryStatus(probed = 171, total = 254, found = 9),
            names = mapOf(7L to "Office NAS", 9L to "10.0.3.9")))
        val byKey = l.running.associateBy { it.key }
        assertEquals("Radar scan", byKey.getValue("live:1").title); assertEquals("probing 171 of 254  7 s", byKey.getValue("live:1").detail)
        assertEquals("Quick scan all", byKey.getValue("live:2").title); assertEquals("3 of 30 devices, 42 s", byKey.getValue("live:2").detail)
        assertEquals("Port scan on Office NAS", byKey.getValue("live:3").title); assertEquals("312 of 65535, found 4", byKey.getValue("live:3").detail)
        assertEquals("Directory brute-force on 10.0.3.9:443", byKey.getValue("tool:5").title)
        assertEquals(4_000L, byKey.getValue("tool:5").durationMs)
    }
    @Test fun `a port scan identifying its ports says so`() {
        val ports = mapOf(7L to PortScanState(ScanProgress(65_535, 65_535, 5, 0), IdentifyCount(3, 1)))
        val item = ScansRules.build(input(live = listOf(live(1, done = 65_535, total = 65_535, found = 5)), ports = ports)).running.single()
        assertEquals("identifying 3 ports", item.detail)
    }
    @Test fun `a finished scan shows its summary and when`() {
        val item = ScansRules.build(input(ended = listOf(ended(1, now - 125_000, startedAt = now - 137_000)))).recent.single()
        assertEquals("Done in 3 s, 2 open ports, 2 minutes ago", item.detail); assertEquals(12_000L, item.durationMs)
    }
    @Test fun `history rows map their status one to one`() {
        val h = listOf(ToolRunStatus.DONE, ToolRunStatus.STOPPED, ToolRunStatus.FAILED, ToolRunStatus.INTERRUPTED).mapIndexed { i, s ->
            run(i + 1L, s, now - 9_000 - i, if (s == ToolRunStatus.INTERRUPTED) null else now - 8_000 - i, summary = if (s == ToolRunStatus.INTERRUPTED) "Interrupted, 4 found" else "x") }
        val byKey = ScansRules.build(input(history = h)).recent.associateBy { it.key }
        assertEquals(ScansState.DONE, byKey.getValue("tool:1").state); assertEquals(ScansState.STOPPED, byKey.getValue("tool:2").state)
        assertEquals(ScansState.FAILED, byKey.getValue("tool:3").state); assertEquals(ScansState.INTERRUPTED, byKey.getValue("tool:4").state)
        assertNull(byKey.getValue("tool:4").endedAt)
        assertTrue(byKey.getValue("tool:4").detail.startsWith("Interrupted, 4 found, "))
    }
    @Test fun `a clock that went backwards never gives a negative duration and a future dated item is still recent`() {
        val backwards = ended(1, endedAt = now - 10_000, startedAt = now - 2_000)          // ended before it started
        val future = ended(2, endedAt = now + 3_600_000, startedAt = now + 3_000_000)       // the clock was set back after it finished
        val l = ScansRules.build(input(ended = listOf(backwards, future)))
        assertEquals(0L, l.recent.first { it.key == "live:1" }.durationMs)
        assertTrue(l.recent.all { (it.durationMs ?: 0) >= 0 })
        assertTrue("a future dated item stays listed", l.recent.any { it.key == "live:2" })
        assertEquals("the item that sorts newest is the future one", "live:2", l.recent.first().key)
        assertEquals("just now", l.recent.first { it.key == "live:2" }.detail.substringAfterLast(", "))
    }
    @Test fun `the running count matches the running list and the badge is its size`() {
        val live = listOf(live(1), live(2, LiveWork.Kind.RADAR, hostId = null)); val act = mapOf(5L to active(5), 8L to active(6, hostId = 8))
        val list = ScansRules.build(input(live = live, active = act)).running
        assertEquals(ScansRules.runningCount(live, act), list.size); assertEquals(4, ScansRules.badge(list)); assertEquals(0, ScansRules.badge(emptyList()))
    }
    @Test fun `the snackbar order is Undo first, then the clear prompt, then an app message`() {
        val msg = AppMessage(5, "HTTP fingerprint finished: done", 7)
        assertEquals(ScansSnack.Undo("Removed DiskStation"), ScansRules.snack("Removed DiskStation", true, msg))
        assertEquals(ScansSnack.Cleared, ScansRules.snack(null, true, msg))
        assertEquals(ScansSnack.App(5, "HTTP fingerprint finished: done"), ScansRules.snack(null, false, msg))
        assertNull(ScansRules.snack(null, false, null))
        assertEquals(3, listOf(ScansSnack.Undo("a").key, ScansSnack.Cleared.key, ScansSnack.App(1, "b").key).distinct().size)
    }
}
