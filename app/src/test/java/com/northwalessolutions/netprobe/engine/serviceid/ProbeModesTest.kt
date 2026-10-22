package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import org.junit.Assert.*
import org.junit.Test

/** Mode selection per profile and the Settings switch (3E spec 3). */
class ProbeModesTest {
    private val custom = ScanProfile.Custom(listOf(22, 80))
    @Test fun `quick stays passive whatever the switch says`() {
        assertSame(ProbeMode.Passive, ProbeModes.forProfile(ScanProfile.Quick, hellos = true))
        assertSame(ProbeMode.Passive, ProbeModes.forProfile(ScanProfile.Quick, hellos = false))
    }
    @Test fun `full and custom are active with the ladder when the switch is on`() {
        listOf(ScanProfile.Full, custom).forEach { assertTrue((ProbeModes.forProfile(it, hellos = true) as ProbeMode.Active).hellos) }
    }
    @Test fun `full and custom drop to the 3A steps when the switch is off`() {
        listOf(ScanProfile.Full, custom).forEach { assertFalse((ProbeModes.forProfile(it, hellos = false) as ProbeMode.Active).hellos) }
    }
    @Test fun `the switch defaults to on`() {
        assertTrue((ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active).hellos)
    }
}
