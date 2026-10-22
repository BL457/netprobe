package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActiveProbeBudgetTest {
    private var now = 0L
    private val b = ActiveProbeBudget(maxPorts = 2, maxMs = 1_000) { now }

    @Test fun `a port enters once and the port cap holds`() {
        assertTrue(b.tryEnter(0L, 1)); assertTrue(b.tryEnter(0L, 1)); assertTrue(b.tryEnter(0L, 2)); assertFalse(b.tryEnter(0L, 3))
        assertEquals(2, b.enteredPorts)
    }
    @Test fun `a step adds its own wall time, a started step finishes, then nothing starts`() = runBlocking {
        assertEquals("a", b.step { now += 600; "a" }); assertEquals(600L, b.spentMs); assertTrue(b.allowsStep())
        assertEquals("b", b.step { now += 600; "b" }); assertEquals(1_200L, b.spentMs)
        assertFalse(b.allowsStep()); assertNull(b.step { fail("must not run"); "c" }); assertFalse(b.tryEnter(0L, 1))
    }
    @Test fun `defaults are the spec's caps`() { assertEquals(64, MAX_ACTIVE_PORTS); assertEquals(60_000L, MAX_ACTIVE_MS) }
}
