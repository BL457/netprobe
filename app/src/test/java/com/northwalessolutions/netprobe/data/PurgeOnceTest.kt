package com.northwalessolutions.netprobe.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Gruff plan review G1: the leftover-Forget purge runs once, before the first lookup that needs it, and never at process start. */
class PurgeOnceTest {
    @Test fun `runs once however many callers arrive together, and every caller waits for it`() = runTest {
        val once = PurgeOnce(); val log = mutableListOf<String>()
        val callers = (1..5).map { i -> async { once.run { delay(10); log += "purge" }; log += "lookup $i" } }
        callers.awaitAll()
        assertEquals("purge", log.first()); assertEquals(1, log.count { it == "purge" }); assertEquals(6, log.size)
    }
    @Test fun `a failed purge is tried again by the next caller`() = runTest {
        val once = PurgeOnce(); var attempts = 0
        val failed = runCatching { once.run { attempts++; throw IllegalStateException("disk") } }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        once.run { attempts++ }; once.run { attempts++ }
        assertEquals(2, attempts)
    }
}
