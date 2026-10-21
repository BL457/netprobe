package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedNoticesTest {
    private val local = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)
    private fun r(s: String, state: RangeState, found: Int = 0, source: RangeSource = RangeSource.MANUAL) = RangeProgress(s, source, state, 254, 254, found)

    @Test fun `notes map to the radar copy`() {
        assertEquals(listOf("Aggressive scan: no extra ranges to sweep"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.NO_EXTRA_RANGES), ranges = listOf(local))))
        assertEquals(listOf("Other ranges skipped: could not bind to Wi-Fi"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.SKIPPED_BIND_FAILED))))
        assertEquals(listOf("Smart discovery found no other ranges"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_PROBE_FOUND_NONE))))
        assertEquals(listOf("Smart discovery skipped: every address answered"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL))))
    }
    @Test fun `range states become notices and found ranges say nothing`() {
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, found = 3), r("10.0.5.0/24", RangeState.PHANTOM), r("10.0.8.0/24", RangeState.DONE)))
        assertEquals(listOf("10.0.5.0/24 skipped: every address answered", "10.0.8.0/24: nothing found"), ExtendedNotices.of(st))
    }
    @Test fun `cancelled range names the range that was running`() {   // Review Focus 5
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, found = 2), r("10.0.6.0/24", RangeState.CANCELLED), r("10.0.7.0/24", RangeState.CANCELLED)))
        assertEquals(listOf("Stopped at 10.0.6.0/24"), ExtendedNotices.of(st))
    }
    @Test fun `cap drops are one notice`() {
        val st = DiscoveryStatus(truncatedRanges = 2, ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 1), r("10.0.5.0/24", RangeState.TRUNCATED), r("10.0.6.0/24", RangeState.TRUNCATED)))
        assertEquals(listOf("2 more ranges found, raise Maximum extra ranges in Settings"), ExtendedNotices.of(st))
        assertEquals(listOf("1 more range found, raise Maximum extra ranges in Settings"), ExtendedNotices.of(st.copy(truncatedRanges = 1)))
    }
    @Test fun `range summary lines carry source, outcome and count`() {
        val ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 3, RangeSource.EVIDENCE), r("10.0.7.0/24", RangeState.DONE), r("10.0.5.0/24", RangeState.PHANTOM, source = RangeSource.GATEWAY_PROBE),
            r("10.0.6.0/24", RangeState.TRUNCATED), r("10.0.8.0/24", RangeState.CANCELLED), r("10.0.9.0/24", RangeState.PENDING))
        assertEquals(listOf("10.0.4.0/24  evidence  swept, 3 found", "10.0.7.0/24  manual  swept, nothing reachable",
            "10.0.5.0/24  smart discovery  skipped, every address answered", "10.0.6.0/24  manual  not swept, scan limit reached",
            "10.0.8.0/24  manual  stopped", "10.0.9.0/24  manual  not started"), RangeSummaryText.lines(ranges))
        assertEquals("1 other range checked", RangeSummaryText.headline(1)); assertEquals("6 other ranges checked", RangeSummaryText.headline(6))
    }
    @Test fun `last run keeps only swept ranges`() {
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 2), r("10.0.5.0/24", RangeState.PHANTOM), r("10.0.4.16/28", RangeState.DONE)))
        assertEquals(listOf("10.0.4.0/24", "10.0.4.16/28"), LastRun.sweptRanges(st).map { it.toString() })
    }
    @Test fun `per-range notices are capped at two then rolled up per kind`() {
        val st = DiscoveryStatus(ranges = listOf(local, r("10.0.4.0/24", RangeState.PHANTOM), r("10.0.5.0/24", RangeState.DONE), r("10.0.6.0/24", RangeState.PHANTOM),
            r("10.0.7.0/24", RangeState.DONE), r("10.0.8.0/24", RangeState.DONE), r("10.0.9.0/24", RangeState.DONE, found = 2)))
        assertEquals(listOf("10.0.4.0/24 skipped: every address answered", "10.0.5.0/24: nothing found", "1 other range answered for everything", "2 other ranges: nothing found"), ExtendedNotices.of(st))
    }
    @Test fun `the done headline counts swept ranges only`() {
        val ranges = listOf(local, r("10.0.4.0/24", RangeState.DONE, 3), r("10.0.5.0/24", RangeState.PHANTOM), r("10.0.6.0/24", RangeState.TRUNCATED), r("10.0.7.0/24", RangeState.DONE))
        assertEquals("2 other ranges checked", RangeSummaryText.checked(ranges))
    }
    @Test fun `no headline when no range was swept`() {   // M4: never "0 other ranges checked" after an early Stop
        val ranges = listOf(local, r("10.0.4.0/24", RangeState.CANCELLED), r("10.0.5.0/24", RangeState.PENDING), r("10.0.6.0/24", RangeState.TRUNCATED))
        assertNull(RangeSummaryText.checked(ranges))
    }
    @Test fun `the walk note says why nearby ranges were not checked, and only with Aggressive scan on`() {
        val st = DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_WALK_SKIPPED_MANY))
        assertEquals(listOf("Smart discovery: too many ranges answered, nearby ranges not checked"), ExtendedNotices.of(st))
        assertTrue(ExtendedNotices.of(st, aggressive = false).isEmpty())
    }
}
