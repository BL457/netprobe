package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.*
import org.junit.Test

/** Review Focus 4: a removed host stays out of every scan that is running (spec 5.3 step 2, plan review G2). */
class RemovedHostsTest {
    @Test fun `a removed ip is skipped for the rest of the radar scan`() {
        val set = RemovedHosts.added(emptySet(), 1, "10.0.3.59")
        assertTrue(RemovedHosts.skip(set, 1, "10.0.3.59"))
        assertFalse("another network's host with the same ip", RemovedHosts.skip(set, 2, "10.0.3.59"))
        assertFalse(RemovedHosts.skip(set, 1, "10.0.3.60"))
    }
    @Test fun `undo takes the pair out again`() {
        val set = RemovedHosts.added(RemovedHosts.added(emptySet(), 1, "10.0.3.59"), 1, "10.0.3.70")
        assertEquals(setOf(1L to "10.0.3.70"), RemovedHosts.without(set, 1, "10.0.3.59"))
    }
    @Test fun `quick scan all skips a removed host by id whether or not a radar scan marked its ip`() {
        assertTrue(RemovedHosts.skipQuickScan(setOf(7L), emptySet(), 1, 7, "10.0.3.59"))
        assertTrue(RemovedHosts.skipQuickScan(emptySet(), setOf(1L to "10.0.3.59"), 1, 7, "10.0.3.59"))
        assertFalse(RemovedHosts.skipQuickScan(setOf(8L), setOf(2L to "10.0.3.59"), 1, 7, "10.0.3.59"))
    }
}
