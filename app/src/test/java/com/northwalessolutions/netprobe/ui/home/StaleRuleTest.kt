package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.TestHosts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's dim rule (3A spec 5.5), extracted so the heard-row case of 3C spec 3.4 is pinned on the rule baseState uses (plan review G16). */
class StaleRuleTest {
    @Test fun `with no run on the network nothing is dimmed, a heard row with run 0 included`() {
        assertFalse(StaleRule.of(0, TestHosts.host().copy(lastDiscoveryRun = 0)))
    }
    @Test fun `a row the latest run did not reach is dimmed and one it reached is not`() {
        assertTrue(StaleRule.of(9, TestHosts.host().copy(lastDiscoveryRun = 3)))
        assertFalse(StaleRule.of(9, TestHosts.host().copy(lastDiscoveryRun = 9)))
    }
}
