package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import org.junit.Assert.*
import org.junit.Test

class PhantomGuardTest {
    @Test fun `holds until 64 probes, then judges more than 90 percent alive as phantom`() {
        assertEquals(PhantomGuard.Verdict.HOLD, PhantomGuard.decide(probed = 63, alive = 63, finished = false))
        assertEquals(PhantomGuard.Verdict.PHANTOM, PhantomGuard.decide(64, 58, false))     // 90.6 percent
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(64, 57, false))     // 89.1 percent
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(64, 3, false))
    }
    @Test fun `small ranges are judged at the end and need 16 probes to judge at all`() {
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(14, 14, finished = true))   // a /28 that is all alive is believed
        assertEquals(PhantomGuard.Verdict.PHANTOM, PhantomGuard.decide(30, 30, finished = true))
        assertEquals(PhantomGuard.Verdict.RELEASE, PhantomGuard.decide(30, 2, finished = true))
        assertEquals(PhantomGuard.Verdict.HOLD, PhantomGuard.decide(30, 30, finished = false))
    }
    @Test fun `canary is phantom only when both addresses answer`() {
        assertTrue(PhantomGuard.canaryPhantom(listOf(Probe.Outcome.Refused, Probe.Outcome.Connected(1, null))))
        assertFalse(PhantomGuard.canaryPhantom(listOf(Probe.Outcome.Refused, Probe.Outcome.Timeout)))
        assertFalse(PhantomGuard.canaryPhantom(emptyList()))
    }
    @Test fun `status defaults keep the MVP meaning`() {
        val s = DiscoveryStatus()
        assertEquals(DiscoveryPhase.LOCAL, s.phase); assertTrue(s.ranges.isEmpty()); assertEquals(0, s.truncatedRanges)
        assertTrue(s.notes.isEmpty()); assertNull(s.gatewayProbe)
        assertFalse(DiscoveryOptions().extended.enabled); assertEquals(4, ExtendedOptions().maxRanges)
    }
}
