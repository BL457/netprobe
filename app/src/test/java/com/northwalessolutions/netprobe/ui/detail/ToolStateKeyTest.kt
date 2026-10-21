package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import org.junit.Assert.*
import org.junit.Test

/** 3B QA F4: per-tick fields never change the key; structural ones always do. */
class ToolStateKeyTest {
    private val host = 7L
    private fun active(runId: Long = 1, toolId: ToolId = ToolId.DIR_BRUTEFORCE, total: Int? = 4614, done: Int = 10, found: Int = 1,
                       errors: Int = 0, ms: Long = 1_000, paused: Boolean = false, hostId: Long = host) =
        ToolRunner.Active(runId, hostId, toolId, 5000, ms, done, total, found, errors, paused)
    private fun key(vararg a: ToolRunner.Active) = ToolStateKey.of(a.associateBy { it.hostId }, host)

    @Test fun perTickFieldsLeaveTheKeyEqual() {
        val base = key(active())
        assertEquals(base, key(active(done = 1234)))
        assertEquals(base, key(active(found = 47)))
        assertEquals(base, key(active(errors = 3)))
        assertEquals(base, key(active(ms = 46_000)))
        assertEquals(base, key(active(paused = true)))
        assertEquals(base, key(active(done = 4000, found = 40, errors = 2, ms = 40_000, paused = true)))
        // Another host's progress does not touch this host's key either.
        assertEquals(key(active(), active(hostId = 8, done = 1)), key(active(), active(hostId = 8, done = 999, found = 9, ms = 9_000)))
    }
    @Test fun structuralChangesChangeTheKey() {
        val base = key(active())
        assertNotEquals(base, key(active(runId = 2)))
        assertNotEquals(base, key(active(toolId = ToolId.HTTP_FINGERPRINT)))
        assertNotEquals(key(active(total = null)), key(active(total = 294)))
        assertNotEquals(base, ToolStateKey.of(emptyMap(), host))
    }
    @Test fun crossingTheRunnerLimitChangesTheKey() {
        val others = (1..ToolRunner.MAX_ACTIVE_RUNS).map { active(runId = 100L + it, hostId = 100L + it) }
        val below = key(active(), *others.take(ToolRunner.MAX_ACTIVE_RUNS - 2).toTypedArray())
        val atLimit = key(active(), *others.take(ToolRunner.MAX_ACTIVE_RUNS - 1).toTypedArray())
        assertFalse(below.runnerFull); assertTrue(atLimit.runnerFull)
        assertNotEquals(below, atLimit)
    }
}
