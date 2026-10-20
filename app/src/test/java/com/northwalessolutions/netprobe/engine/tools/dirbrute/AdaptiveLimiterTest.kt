package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 5.3: AIMD, halve at most once per window, recover one per 20 successes up to C, never more than the limit in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveLimiterTest {
    @Test fun `halves at most once per window and recovers by one per twenty successes up to C`() = runTest {
        var now = 0L; val l = AdaptiveLimiter(c = 8, now = { now }, windowMs = 1000)
        assertEquals(8, l.limitNow)
        l.onTimeoutOrReset(); assertEquals(4, l.limitNow)
        l.onTimeoutOrReset(); assertEquals("same window, one halving only", 4, l.limitNow)
        now = 1001; l.onTimeoutOrReset(); assertEquals(2, l.limitNow)
        repeat(19) { l.onSuccess() }; assertEquals(2, l.limitNow)
        l.onSuccess(); assertEquals(3, l.limitNow)
        repeat(200) { l.onSuccess() }; assertEquals("never above C", 8, l.limitNow)
    }
    @Test fun `a waiter is admitted only within the limit and a cancelled waiter leaks no permit`() = runTest {
        val l = AdaptiveLimiter(c = 2, now = { 0L })
        l.acquire(); l.acquire()
        val third = launch { l.acquire() }
        advanceUntilIdle(); assertTrue("the third acquire must wait", third.isActive)
        third.cancelAndJoin()
        l.release(); l.release()
        val a = launch { l.acquire() }; val b = launch { l.acquire() }; advanceUntilIdle()
        assertFalse(a.isActive); assertFalse(b.isActive)                // two permits are free again: nothing leaked
    }
}
