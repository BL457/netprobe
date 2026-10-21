package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.*
import org.junit.Assert.*
import org.junit.Test

class RunEndTest {
    private val local = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)
    private val swept = DiscoveryStatus(ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.DONE, 254, 254, 3), RangeProgress("10.0.5.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 0)))

    @Test fun `a scan on network A that unwinds after the switch to B publishes nothing`() {
        assertNull(RunEnd.of(currentNetworkId = 2L, runNetworkId = 1L, extEnabled = true, completed = false, status = swept))
        assertNull(RunEnd.of(currentNetworkId = null, runNetworkId = 1L, extEnabled = true, completed = true, status = swept))
    }
    @Test fun `the current network gets its ranges and notices`() {
        val e = RunEnd.of(1L, 1L, extEnabled = true, completed = true, status = swept)!!
        assertEquals(listOf("10.0.4.0/24", "10.0.5.0/24"), e.swept!!.map { it.toString() }); assertEquals(swept.ranges, e.progress)
        assertEquals("10.0.5.0/24: nothing found", e.notice)
    }
    @Test fun `toggle off publishes no ranges and the large network notice only after a completed scan`() {
        val clamped = DiscoveryStatus(clampedFrom = 16)
        assertEquals("Large network (/16). Scanned your /24 only. Turn on Aggressive scan in Settings.", RunEnd.of(1L, 1L, false, true, clamped)!!.notice)
        assertNull(RunEnd.of(1L, 1L, false, false, clamped)!!.notice)   // Stop, failure or network change
        val off = RunEnd.of(1L, 1L, false, true, swept)!!
        assertNull(off.swept); assertNull(off.progress); assertNull(off.notice)
    }
    @Test fun `network change clears the finish time and both range lists, a fresh finish on the new network is kept`() {
        val mem = RunMemory(finishedAt = 500L, swept = emptyList(), progress = swept.ranges)
        assertEquals(RunMemory(null, emptyList(), emptyList()), mem.afterNetworkChange(1L, 2L))
        assertEquals(mem, mem.afterNetworkChange(1L, 1L))
        assertEquals(mem, mem.afterNetworkChange(2L, 2L))   // discover set networkId first, so the new scan's finish survives the collector
    }
    @Test fun `the controller's network changed state never yields a finish time`() {
        val changed = ScanController.State(false, ScanController.NETWORK_CHANGED)
        assertNull(ScanFinish.fromController(changed, wasScanning = true, previous = 400L, now = 900L))
        assertNull(ScanFinish.fromController(ScanController.State(false, null), wasScanning = false, previous = null, now = 900L))   // dismissal keeps null
        assertEquals(900L, ScanFinish.fromController(ScanController.State(false, null), wasScanning = true, previous = null, now = 900L))
        assertNull(ScanFinish.fromController(ScanController.State(true, null), wasScanning = false, previous = 400L, now = 900L))
    }
    @Test fun `aggressive off with a manual range shows its summary but never the aggressive note`() {
        val manualOnly = DiscoveryStatus(notes = listOf(ExtendedNote.NO_EXTRA_RANGES),
            ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 3)))
        val e = RunEnd.of(1L, 1L, extEnabled = true, completed = true, status = manualOnly, aggressive = false)!!
        assertEquals(listOf("10.0.4.0/24"), e.swept!!.map { it.toString() })
        assertNull("a range with hosts needs no notice, and NO_EXTRA_RANGES is Aggressive-only", e.notice)
        assertEquals("1 other range checked", RangeSummaryText.checked(e.progress!!))
        assertEquals(listOf("10.0.4.0/24  manual  swept, 3 found"), RangeSummaryText.lines(e.progress!!))
        assertEquals("Aggressive scan: no extra ranges to sweep", RunEnd.of(1L, 1L, true, true, manualOnly, aggressive = true)!!.notice)
    }
    @Test fun `aggressive off manual notices are nothing found, phantom, stopped and bind failed`() {
        for ((state, text) in listOf(RangeState.DONE to "10.0.4.0/24: nothing found", RangeState.PHANTOM to "10.0.4.0/24 skipped: every address answered",
                RangeState.CANCELLED to "Stopped at 10.0.4.0/24")) {
            val st = DiscoveryStatus(ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, state, 254, 254, 0)))
            assertEquals(text, RunEnd.of(1L, 1L, true, true, st, aggressive = false)!!.notice)
        }
        val bind = DiscoveryStatus(notes = listOf(ExtendedNote.SKIPPED_BIND_FAILED), ranges = listOf(local))
        assertEquals("Other ranges skipped: could not bind to Wi-Fi", RunEnd.of(1L, 1L, true, true, bind, aggressive = false)!!.notice)
        val capped = DiscoveryStatus(truncatedRanges = 1, ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 2),
            RangeProgress("10.0.5.0/24", RangeSource.MANUAL, RangeState.TRUNCATED, 0, 254, 0)))
        assertEquals("1 more range found, raise Maximum extra ranges in Settings", RunEnd.of(1L, 1L, true, true, capped, aggressive = false)!!.notice)
    }
    @Test fun `the large network notice follows the run's snapshot`() {
        val clamped22 = DiscoveryStatus(clampedFrom = 16, ranges = listOf(RangeProgress("172.20.8.0/22", null, RangeState.DONE, 1022, 1022, 0)))
        assertEquals("Large network (/16). Scanned your /22 only. Widen in Settings.", RunEnd.of(1L, 1L, true, true, clamped22, aggressive = true)!!.notice)
        val clamped24 = DiscoveryStatus(clampedFrom = 16, ranges = listOf(RangeProgress("172.20.9.0/24", null, RangeState.DONE, 254, 254, 0)))
        assertEquals("Large network (/16). Scanned your /24 only. Turn on Aggressive scan in Settings.", RunEnd.of(1L, 1L, false, true, clamped24, aggressive = false)!!.notice)
        assertNull(RunEnd.of(1L, 1L, true, false, clamped22, aggressive = true)!!.notice)   // Stop, failure or network change: no notice
    }
}
