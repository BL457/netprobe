package com.northwalessolutions.netprobe.engine.vulns

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RateLimiterTest {
    @Test fun `blocks the sixth call inside the window`() = runBlocking {
        var now = 0L; val sleeps = mutableListOf<Long>()
        val rl = RateLimiter(5, 30_000, clock = { now }, sleeper = { ms -> sleeps += ms; now += ms })
        repeat(5) { rl.acquire() }; assertTrue(sleeps.isEmpty())
        rl.acquire(); assertEquals(1, sleeps.size); assertTrue(sleeps[0] in 29_000..30_000)
    }
}
