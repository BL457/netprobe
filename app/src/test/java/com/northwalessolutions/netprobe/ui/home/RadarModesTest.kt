package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarModesTest {
    @Test fun `scanning wins over everything`() {
        assertEquals(RadarMode.SCANNING, RadarModes.of(scanning = true, totalHosts = 0, lastScanAt = null))
        assertEquals(RadarMode.SCANNING, RadarModes.of(scanning = true, totalHosts = 12, lastScanAt = 1L))
    }
    @Test fun `no stored hosts and no completed scan is hero`() { assertEquals(RadarMode.HERO, RadarModes.of(false, 0, null)) }
    @Test fun `hosts present is settled`() { assertEquals(RadarMode.SETTLED, RadarModes.of(false, 3, null)) }
    @Test fun `a finished scan that found nothing is settled not hero`() { assertEquals(RadarMode.SETTLED, RadarModes.of(false, 0, 1_000L)) }
    @Test fun `a filtered empty list is not hero because totalHosts ignores the filter`() {
        // The caller passes the unfiltered count; a filter that matches nothing still has totalHosts > 0.
        assertEquals(RadarMode.SETTLED, RadarModes.of(false, totalHosts = 13, lastScanAt = 1_000L))
    }
}
