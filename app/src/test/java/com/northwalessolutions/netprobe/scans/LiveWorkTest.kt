package com.northwalessolutions.netprobe.scans

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Spec 3.2 and 9.1 item 7, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveWorkTest {
    private fun TestScope.work() = LiveWork(backgroundScope, clock = { testScheduler.currentTime })
    private fun LiveWork.start(kind: LiveWork.Kind = LiveWork.Kind.RADAR, hostId: Long? = null, stop: () -> Unit = {}) = begin(kind, 1L, hostId, null, stop)

    @Test fun `begin and end publish at once`() = runTest {
        val lw = work()
        val id = lw.start()
        assertEquals(listOf(id), lw.live.value.map { it.id })
        lw.end(id, LiveWork.Outcome.DONE, "Done in 3 s, 2 devices")
        assertTrue(lw.live.value.isEmpty())
        val e = lw.ended.value.single()
        assertEquals("Done in 3 s, 2 devices", e.summary); assertEquals(LiveWork.Outcome.DONE, e.outcome); assertNotNull(e.endedAt)
    }
    @Test fun `a thousand progress calls inside one interval publish at most twice`() = runTest {
        val lw = work(); val seen = mutableListOf<List<LiveWork.Live>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lw.live.toList(seen) }
        val id = lw.start()
        val before = seen.size
        repeat(1_000) { lw.progress(id, it + 1, 5_000, 0) }
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertTrue("${seen.size - before} emissions", seen.size - before <= 2)
        assertEquals(1_000, seen.last().single().done)
    }
    @Test fun `the ticker ends with the last entry and restarts with the next begin`() = runTest {
        val lw = work()
        val a = lw.start()
        assertTrue(lw.tickerRunning)
        lw.end(a, LiveWork.Outcome.DONE, "x")
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertFalse(lw.tickerRunning)
        lw.start()
        assertTrue(lw.tickerRunning)
    }
    @Test fun `a second stop calls the lambda once and stop after end does nothing`() = runTest {
        val lw = work(); var calls = 0
        val id = lw.start { calls++ }
        lw.stop(id); lw.stop(id)
        assertEquals(1, calls)
        var late = 0
        val b = lw.start { late++ }
        lw.end(b, LiveWork.Outcome.STOPPED, "x"); lw.stop(b)
        assertEquals(0, late)
    }
    @Test fun `a second end is ignored and an unknown id does nothing`() = runTest {
        val lw = work()
        val id = lw.start()
        lw.end(id, LiveWork.Outcome.DONE, "first"); lw.end(id, LiveWork.Outcome.FAILED, "second"); lw.end(999, LiveWork.Outcome.DONE, "ghost")
        assertEquals(listOf("first"), lw.ended.value.map { it.summary })
    }
    @Test fun `after end the stop lambda is no longer held`() = runTest {
        val lw = work()
        val id = lw.start { }
        assertEquals(1, lw.heldStops)
        lw.end(id, LiveWork.Outcome.DONE, "x")
        assertEquals(0, lw.heldStops)
    }
    @Test fun `ended keeps the newest fifty first, clear empties it and restore merges back`() = runTest {
        val lw = work()
        val ids = (1..LiveWork.RECENT_LIMIT + 1).map { n -> lw.start().also { lw.end(it, LiveWork.Outcome.DONE, "n$n") } }
        assertEquals(LiveWork.RECENT_LIMIT, lw.ended.value.size)
        assertEquals("n${LiveWork.RECENT_LIMIT + 1}", lw.ended.value.first().summary)
        assertFalse("the oldest fell off", lw.ended.value.any { it.live.id == ids.first() })
        val snapshot = lw.ended.value
        lw.clearEnded(); assertTrue(lw.ended.value.isEmpty())
        val fresh = lw.start().also { lw.end(it, LiveWork.Outcome.DONE, "fresh") }
        lw.restoreEnded(snapshot)
        assertEquals(LiveWork.RECENT_LIMIT, lw.ended.value.size)
        assertTrue("an item that ended after the clear is kept", lw.ended.value.any { it.live.id == fresh })
    }
    @Test fun `an interrupted entry has no end time`() = runTest {
        val lw = work()
        lw.addInterrupted(LiveWork.Kind.PORT_SCAN, 1L, 7L, 5_000L, "Interrupted when NetProbe closed")
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.INTERRUPTED, e.outcome); assertNull(e.endedAt); assertEquals(5_000L, e.live.startedAt); assertEquals(7L, e.live.hostId)
    }
    @Test fun `tracked ends DONE with the summary and returns the body result`() = runTest {
        val lw = work(); val ends = mutableListOf<Pair<LiveWork.Outcome, String>>()
        val result = lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { n, s -> LiveWork.Outcome.DONE to "n=$n s=$s" },
            stopped = { "stopped" }, failed = { "failed" }, onEnd = { o, s -> ends += o to s }) { 7 }
        assertEquals(7, result)
        assertEquals("n=7 s=0", lw.ended.value.single().summary)
        assertEquals(listOf(LiveWork.Outcome.DONE to "n=7 s=0"), ends)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `tracked ends STOPPED when cancelled and FAILED when the body throws`() = runTest {
        val lw = work()
        val job = launch { lw.tracked<Int>(LiveWork.Kind.QUICK_SCAN_ALL, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
            stopped = { "stopped after $it" }, failed = { "failed: ${it.message}" }) { awaitCancellation() } }
        runCurrent(); assertEquals(1, lw.live.value.size)
        job.cancel(); runCurrent()
        assertEquals(LiveWork.Outcome.STOPPED, lw.ended.value.first().outcome); assertEquals("stopped after 0", lw.ended.value.first().summary)
        try {
            lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
                stopped = { "s" }, failed = { "failed: ${it.message}" }) { throw IllegalStateException("boom") }
            fail("the exception must propagate")
        } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        assertEquals(LiveWork.Outcome.FAILED, lw.ended.value.first().outcome); assertEquals("failed: boom", lw.ended.value.first().summary)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `tracked drops the entry when the body did not run`() = runTest {
        val lw = work()
        val result = lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
            stopped = { "s" }, failed = { "f" }) { null }
        assertNull(result)
        assertTrue(lw.live.value.isEmpty()); assertTrue(lw.ended.value.isEmpty())
    }
    @Test fun `stop reaches the lambda registered by tracked`() = runTest {
        val lw = work(); val release = CompletableDeferred<Unit>(); var stops = 0
        val job = launch { lw.tracked<Int>(LiveWork.Kind.WORDLIST_IMPORT, null, null, "example.com", stop = { stops++; release.complete(Unit) },
            summarize = { _, _ -> LiveWork.Outcome.DONE to "d" }, stopped = { "s" }, failed = { "f" }) { release.await(); 1 } }
        runCurrent()
        lw.stop(lw.live.value.single().id); runCurrent()
        assertEquals(1, stops); assertTrue(job.isCompleted)
    }
    @Test fun `a radar entry that began on the previous network carries the new one once it is resolved`() = runTest {
        val lw = work()
        val id = lw.begin(LiveWork.Kind.RADAR, 1L, null, null) {}          // began while the previous network (1) was still current
        lw.setNetwork(id, 2L)                                              // discoverOn resolved the switched-to network
        assertEquals(2L, lw.live.value.single().networkId)
        lw.end(id, LiveWork.Outcome.DONE, "Done in 3 s, 2 devices")
        assertEquals(2L, lw.ended.value.single().live.networkId)
    }
    @Test fun `a radar entry that began with no network gets one and an unknown id does nothing`() = runTest {
        val lw = work()
        val id = lw.begin(LiveWork.Kind.RADAR, null, null, null) {}
        lw.setNetwork(id + 99, 5L); assertNull(lw.live.value.single().networkId)
        lw.setNetwork(id, 5L); assertEquals(5L, lw.live.value.single().networkId)
        lw.end(id, LiveWork.Outcome.STOPPED, "x"); lw.setNetwork(id, 6L)
        assertEquals(5L, lw.ended.value.single().live.networkId)
    }
}
