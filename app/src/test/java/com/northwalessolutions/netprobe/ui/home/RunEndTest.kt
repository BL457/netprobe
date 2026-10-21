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
}
