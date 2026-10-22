package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import org.junit.Assert.*
import org.junit.Test

/** Mode selection per profile (3E spec 3, Director ruling R1): every scan is passive. */
class ProbeModesTest {
    private val custom = ScanProfile.Custom(listOf(22, 80))
    @Test fun `quick stays passive`() {
        assertSame(ProbeMode.Passive, ProbeModes.forProfile(ScanProfile.Quick))
    }
    @Test fun `full and custom get the 3A steps only, never the hello ladder`() {
        listOf(ScanProfile.Full, custom).forEach {
            val mode = ProbeModes.forProfile(it) as ProbeMode.Active
            assertFalse("$it", mode.hellos)
        }
    }
    @Test fun `each full or custom scan gets a fresh budget`() {
        val a = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active
        val b = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active
        assertNotSame(a.budget, b.budget)
    }
    @Test fun `an on-demand mode keeps the hellos on by default`() {
        assertTrue(ProbeMode.Active(ActiveProbeBudget()).hellos)
    }
}
