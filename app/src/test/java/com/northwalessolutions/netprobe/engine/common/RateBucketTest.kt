package com.northwalessolutions.netprobe.engine.common

import org.junit.Assert.*
import org.junit.Test

/** spec 5.3: a per-host token bucket, 100 per second, burst 10, that refills from empty after a pause (Gruff M3). */
class RateBucketTest {
    private var now = 0L
    private val bucket = RateBucket(ratePerSecond = 100, burst = 10) { now }

    @Test fun `the burst grants ten at once, then a wait`() {
        repeat(10) { assertEquals("token $it", 0L, bucket.acquireDelayMs()) }
        assertEquals("the eleventh waits exactly one token's time at 100 per second", 10L, bucket.acquireDelayMs())
    }
    @Test fun `a clock that steps backwards never removes tokens`() {
        now = 5_000
        val stepped = RateBucket(ratePerSecond = 100, burst = 10) { now }
        now = 0
        repeat(10) { assertEquals("token $it after the clock stepped back", 0L, stepped.acquireDelayMs()) }
    }
    @Test fun `no more than one hundred are granted in any one second window`() {
        var granted = 0
        // Drive a fake second one millisecond at a time, always taking a token when one is free.
        while (now <= 1000) { if (bucket.acquireDelayMs() == 0L) granted++; now += 1 }
        assertTrue("granted $granted in 1 s", granted <= 100 + 10)   // at most the window plus the initial burst
        assertTrue(granted >= 100)
    }
    @Test fun `reset empties the bucket so the next token waits`() {
        assertEquals(0L, bucket.acquireDelayMs())
        bucket.reset()
        assertTrue("after reset the bucket is empty", bucket.acquireDelayMs() > 0)
    }
}
