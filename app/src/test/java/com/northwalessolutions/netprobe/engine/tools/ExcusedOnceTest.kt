package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 4.8: companion tools retry once a failure that spans a pause; nothing else is retried. */
class ExcusedOnceTest {
    private class Control : RunControl { var epoch = 0; var gates = 0
        override suspend fun gate() { gates++ }; override val pauseEpoch get() = epoch; override fun excused(startEpoch: Int) = startEpoch != epoch }

    @Test fun `a failure that spans a pause is retried once after the gate`() = runTest {
        val c = Control(); var calls = 0
        val r = c.excusedOnce({ it == null }) { calls++; if (calls == 1) { c.epoch++; null } else "ok" }
        assertEquals("ok", r); assertEquals(2, calls); assertEquals(1, c.gates)
    }
    @Test fun `a plain failure and a success are not retried`() = runTest {
        val c = Control(); var calls = 0
        assertNull(c.excusedOnce({ it == null }) { calls++; null as String? }); assertEquals(1, calls)
        assertEquals("ok", c.excusedOnce({ it == null }) { calls++; c.epoch++; "ok" }); assertEquals(2, calls)
    }
}
