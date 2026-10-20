package com.northwalessolutions.netprobe.ui.home

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** spec 5.3 step 1, Review Focus 4: Remove cancels one host's child job, or keeps an unreached host out; the loop carries on. */
class QuickScanAllLoopTest {
    @Test fun `cancelling one host skips it and the loop carries on`() = runTest {
        val jobs = ConcurrentHashMap<Long, Job>(); val done = mutableListOf<Long>()
        val inHostTwo = CompletableDeferred<Unit>()
        val loop = launch {
            QuickScanAllLoop.run(listOf(1L, 2L, 3L), { it }, { false }, jobs) { id ->
                if (id == 2L) { inHostTwo.complete(Unit); awaitCancellation() }
                done += id
            }
        }
        inHostTwo.await()
        jobs.getValue(2L).cancelAndJoin()                 // Remove device on host 2
        loop.join()
        assertEquals(listOf(1L, 3L), done); assertTrue(jobs.isEmpty()); assertFalse(loop.isCancelled)
    }
    @Test fun `a host removed after the loop started but before it is reached is never scanned`() = runTest {   // G2 path (a)
        val removedIds = mutableSetOf<Long>(); val done = mutableListOf<Long>()
        QuickScanAllLoop.run(listOf(1L, 2L, 3L), { it }, { RemovedHosts.skipQuickScan(removedIds, emptySet(), 1, it, "10.0.3.$it") }, ConcurrentHashMap()) { id ->
            if (id == 1L) removedIds += 3L                // the Director removes host 3 from Detail while host 1 is scanned
            done += id
        }
        assertEquals(listOf(1L, 2L), done)
    }
    @Test fun `a host's job is registered before its body runs`() = runTest {   // G13: Remove can always find the job to cancel
        val jobs = ConcurrentHashMap<Long, Job>(); val seen = mutableListOf<Boolean>()
        withContext(Dispatchers.Unconfined) {
            QuickScanAllLoop.run(listOf(1L, 2L), { it }, { false }, jobs) { id -> seen += jobs.containsKey(id) }
        }
        assertEquals(listOf(true, true), seen)
    }
    @Test fun `stopping quick scan all stops the loop`() = runTest {
        val jobs = ConcurrentHashMap<Long, Job>(); val started = CompletableDeferred<Unit>()
        val loop = launch { QuickScanAllLoop.run(listOf(1L, 2L), { it }, { false }, jobs) { started.complete(Unit); awaitCancellation() } }
        started.await(); loop.cancelAndJoin()
        assertTrue(loop.isCancelled); assertTrue(jobs.isEmpty())
    }
}
