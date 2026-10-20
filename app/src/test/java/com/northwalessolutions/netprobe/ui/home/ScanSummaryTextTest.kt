package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSummaryTextTest {
    @Test fun `duration only`() { assertEquals(RadarSummary("13 devices", "Scanned in 12 s"), ScanSummaryText.of(13, 0, 12_000)) }
    @Test fun `with other subnet count`() { assertEquals(RadarSummary("13 devices", "Scanned in 12 s, 1 on another subnet"), ScanSummaryText.of(13, 1, 12_000)) }
    @Test fun `zero devices`() { assertEquals(RadarSummary("No devices found", "Scanned in 12 s"), ScanSummaryText.of(0, 0, 12_000)) }
    @Test fun `singular device and rounding`() {
        assertEquals(RadarSummary("1 device", "Scanned in 13 s"), ScanSummaryText.of(1, 0, 12_600))
        assertEquals("Scanned in 1 s", ScanSummaryText.of(1, 0, 100).detail)
    }
    @Test fun `settled uses the done summary only for a finished run`() {
        val now = 10_000_000L
        assertEquals("Scanned in 12 s", RadarSummaries.settled(13, 0, 5_000L, 1_000_000L, 1_012_000L, now) { "d" }.detail)
        assertEquals("Last scan 2 minutes ago", RadarSummaries.settled(13, 0, now - 120_000, null, null, now) { "d" }.detail)
        assertEquals(RadarSummary("No devices found", "Last scan just now"), RadarSummaries.settled(0, 0, now, null, null, now) { "d" })
        assertEquals("", RadarSummaries.settled(3, 0, null, null, null, now) { "d" }.detail)
    }
    @Test fun `semantics strings`() {
        assertEquals("Scan, 13 devices, last scan 2 minutes ago", RadarText.ringDescription(RadarSummary("13 devices", "Last scan 2 minutes ago")))
        assertEquals("Scanning, 9 devices found, probing 171 of 254", RadarText.scanningDescription(9, "probing 171 of 254  7 s"))
        assertEquals("Scanning, 1 device found", RadarText.scanningDescription(1, null))
        assertEquals("Scan the network 10.0.3.0/24", RadarText.heroDescription("10.0.3.0/24"))
        assertEquals(RadarText.RING_DISABLED, RadarText.heroDescription(null))
        assertEquals("Scan finished, 13 devices", RadarText.finished("13 devices"))
        assertEquals("Scan finished, no devices found", RadarText.finished("No devices found"))
    }
    @Test fun `a stopped run reads stopped after`() {
        assertEquals(RadarSummary("13 devices", "Stopped after 12 s"), ScanSummaryText.of(13, 0, 12_000, completed = false))
        assertEquals(RadarSummary("13 devices", "Stopped after 12 s, 1 on another subnet"), ScanSummaryText.of(13, 1, 12_000, completed = false))
        assertEquals(RadarSummary("No devices found", "Stopped after 12 s"), ScanSummaryText.of(0, 0, 12_000, completed = false))
        assertEquals("Stopped after 12 s", RadarSummaries.settled(13, 0, 5_000L, 1_000_000L, 1_012_000L, 10_000_000L, completed = false) { "d" }.detail)
        assertEquals("Scanned in 12 s", ScanSummaryText.of(13, 0, 12_000).detail)
    }
    @Test fun `finished and stopped announcements`() {
        assertEquals("Scan stopped, 13 devices", RadarText.stopped("13 devices")); assertEquals("Scan stopped, no devices found", RadarText.stopped("No devices found"))
        assertEquals("Scan finished, 13 devices", RadarText.finished("13 devices"))
    }
}
