package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.*
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

    private val local = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)
    @Test fun `gateway probe phase`() {
        val st = DiscoveryStatus(probed = 254, total = 254, phase = DiscoveryPhase.GATEWAY_PROBE, gatewayProbe = GatewayProbeProgress("10.0.0.0/16", 112, 256))
        assertEquals("probing 10.0.0.0/16 for gateways, 112 of 256  9 s", ProgressLine.of(st, 9))
        assertEquals("preparing next range  31 s", ProgressLine.of(st.copy(gatewayProbe = null), 31))
        assertEquals("probing for gateways, 112 of 256  9 s", ProgressLine.of(st.copy(gatewayProbe = GatewayProbeProgress("other ranges", 112, 256)), 9))   // ruling 2: no engine label in the UI
    }
    @Test fun `extended range counts extra ranges only and uses the range's own numbers`() {
        val st = DiscoveryStatus(probed = 254, total = 254, phase = DiscoveryPhase.EXTENDED, ranges = listOf(local,
            RangeProgress("10.0.5.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 1),
            RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, RangeState.RUNNING, 171, 254, 0),
            RangeProgress("10.0.6.0/24", RangeSource.EVIDENCE, RangeState.PENDING, 0, 254, 0),
            RangeProgress("10.0.9.0/24", RangeSource.GATEWAY_PROBE, RangeState.PENDING, 0, 254, 0),
            RangeProgress("10.0.10.0/24", RangeSource.GATEWAY_PROBE, RangeState.TRUNCATED, 0, 254, 0)))
        assertEquals("10.0.4.0/24 (2 of 4), probing 171 of 254", ProgressLine.of(st, 40))
        assertEquals("preparing next range  41 s", ProgressLine.of(st.copy(ranges = st.ranges.map { if (it.state == RangeState.RUNNING) it.copy(state = RangeState.DONE) else it }), 41))
    }
}
