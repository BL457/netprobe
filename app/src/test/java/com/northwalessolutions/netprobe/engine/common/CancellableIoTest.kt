package com.northwalessolutions.netprobe.engine.common

import com.northwalessolutions.netprobe.engine.tools.TOOL_THREADS
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Review Focus 3: closing() inherits the caller's dispatcher, so tool reads never spill onto the whole Dispatchers.IO pool (spec 4.6, Gruff M7). */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellableIoTest {
    private val tool = Dispatchers.IO.limitedParallelism(TOOL_THREADS)

    @Test fun `a dispatchers io task still runs while sixteen tool reads are blocked`() = runBlocking {
        val parked = CountDownLatch(TOOL_THREADS); val release = CountDownLatch(1)
        val jobs = (1..TOOL_THREADS * 4).map { launch(tool) { CancellableIo.closing(Closeable { }) { parked.countDown(); release.await() } } }
        assertTrue("sixteen tool reads did not block", parked.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val done = withContext(Dispatchers.IO) { 42 }
        assertEquals(42, done)
        assertTrue("an IO task waited ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 100)
        release.countDown(); jobs.forEach { it.join() }
    }
    @Test fun `tool reads never run on more than TOOL_THREADS threads`() = runBlocking {
        val inFlight = AtomicInteger(0); val max = AtomicInteger(0); val release = CountDownLatch(1); val started = CountDownLatch(TOOL_THREADS)
        val jobs = (1..TOOL_THREADS * 4).map {
            launch(tool) { CancellableIo.closing(Closeable { }) {
                val n = inFlight.incrementAndGet(); max.updateAndGet { m -> maxOf(m, n) }; started.countDown(); release.await(); inFlight.decrementAndGet()
            } }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS)); Thread.sleep(200)
        release.countDown(); jobs.forEach { it.join() }
        assertEquals(TOOL_THREADS, max.get())
    }
    @Test fun `the block runs on the caller's dispatcher`() = runBlocking {
        val single = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "caller-thread") }.asCoroutineDispatcher()
        try {
            val name = withContext(single) { CancellableIo.closing(Closeable { }) { Thread.currentThread().name } }
            assertTrue("ran on $name", name.startsWith("caller-thread"))   // the coroutine debugger appends " @coroutine#N"
        } finally { single.close() }
    }
    @Test fun `cancellation closes the socket at once`() = runBlocking {
        val closed = CountDownLatch(1); val entered = CountDownLatch(1)
        val job = launch(tool) { CancellableIo.closing(Closeable { closed.countDown() }) { entered.countDown(); Thread.sleep(10_000) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("socket not closed on cancel", closed.await(1, TimeUnit.SECONDS))
        job.join()
    }
}
