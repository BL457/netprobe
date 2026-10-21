package com.northwalessolutions.netprobe.engine.common

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 3.2 (Review Focus 5): the listener's lock is its own; the scan's acquire and release never touch it. */
class LocksTest {
    private class FakeLock : LockHandle {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }
    private val scanMulticast = FakeLock(); private val perf = FakeLock(); private val listen = FakeLock()
    private val locks = Locks(scanMulticast, perf, listen)

    @Test fun `the scan's release never releases the listener's lock`() {
        locks.listen.acquire(); locks.acquire(); locks.release()
        assertFalse(scanMulticast.held); assertFalse(perf.held)
        assertTrue("the listen lock survives a scan's end", listen.held)
    }
    @Test fun `releasing the listener's lock leaves a running scan's locks held`() {
        locks.acquire(); locks.listen.acquire(); locks.listen.release()
        assertTrue(scanMulticast.held); assertTrue(perf.held); assertFalse(listen.held)
    }
    @Test fun `the three tags are distinct`() {
        assertEquals(3, setOf(Locks.SCAN_TAG, Locks.PERF_TAG, Locks.LISTEN_TAG).size)
        assertEquals("netprobe-listen", Locks.LISTEN_TAG); assertEquals("netprobe-mdns", Locks.SCAN_TAG)
    }
}
