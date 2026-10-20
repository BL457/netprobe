package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressLineTest {
    @Test fun `start of run before the total is known`() {
        assertEquals("starting", ProgressLine.of(null, 0)); assertEquals("starting", ProgressLine.of(DiscoveryStatus(), 1))
    }
    @Test fun `local sweep uses two spaces before the elapsed time`() {
        assertEquals("probing 171 of 254  7 s", ProgressLine.of(DiscoveryStatus(probed = 171, total = 254, found = 9), 7))
    }
    @Test fun `tail after the sweep`() {
        assertEquals("identifying 14 devices  9 s", ProgressLine.of(DiscoveryStatus(probed = 254, total = 254, found = 14), 9))
        assertEquals("identifying 1 device  3 s", ProgressLine.of(DiscoveryStatus(probed = 254, total = 254, found = 1), 3))
    }
}
