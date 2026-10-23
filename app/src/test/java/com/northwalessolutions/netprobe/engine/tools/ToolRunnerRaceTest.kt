package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.office
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import com.northwalessolutions.netprobe.engine.tools.ToolRunner.Companion.forHost
import org.junit.Assert.*
import org.junit.Test

/** The four hardenings on top of the plan's runner: each test lands the race in the window it closes and fails if the window reopens. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerRaceTest {
    @Test fun `a foreground flip during start leaves the new run paused`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> awaitCancellation() })
        runCurrent()                                                      // the collectors subscribe
        // The flip lands and the foreground collector runs before the run is in `runs`, so only start() can pause it.
        rig.runner.beforeRegister = { rig.foreground.value = false; yield() }
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE)
        rig.runner.beforeRegister = {}
        advanceTimeBy(60_000); runCurrent()
        assertEquals("a run registered in the background must not count time", 0L, rig.runner.active.value.forHost(7)!!.activeMs)
        rig.runner.stopHost(7); runCurrent()
    }
    @Test fun `a network change during start stops the new run`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> awaitCancellation() })
        runCurrent()
        // The change lands and the env collector runs before the run is in `runs`, so only start() can catch it.
        rig.runner.beforeRegister = { rig.env.value = office; yield() }
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE)
        runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Network changed, run stopped"), rig.store.finished.single())
    }
    @Test fun `a run started as the ticker ends still gets a ticker`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> awaitCancellation() })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        rig.runner.stopHost(7); runCurrent()
        // The ticker's next tick finds no run and is about to end; a start lands right there.
        rig.runner.tickerEnding = { rig.runner.tickerEnding = {}; rig.runner.start(target(8), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE) }
        advanceTimeBy(ToolRunner.PROGRESS_INTERVAL_MS); runCurrent()
        assertTrue(rig.runner.active.value.forHost(8) != null)
        val first = rig.runner.active.value.forHost(8)!!.activeMs
        advanceTimeBy(1_000); runCurrent()
        assertTrue("elapsed moved from $first", rig.runner.active.value.forHost(8)!!.activeMs >= first + 800)
        rig.runner.stopHost(8); runCurrent()
    }
    @Test fun `a Stop before the run's first dispatch still writes STOPPED and releases the host`() = runTest {
        var entered = false
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> entered = true; awaitCancellation() })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE)
        rig.runner.stopHost(7)                                                // no runCurrent between: the job has not been dispatched yet
        runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Stopped after 0 steps, 0 found"), rig.store.finished.single())
        assertFalse("a cancelled job never enters the tool body", entered)
        assertNull(rig.busy.busy.value[7]); assertTrue(rig.runner.active.value.isEmpty())
    }
    @Test fun `an unregistered tool is refused before any claim or row`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> ToolResult(ToolRunStatus.DONE, "ok") })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { rig.runner.start(target(7), ToolKeys.SNMP_WALK, ToolInputs.NONE) } }
        runCurrent()
        assertNull("the host is never claimed", rig.busy.busy.value[7])
        assertTrue("no row is inserted", rig.store.started.isEmpty())
        assertTrue(rig.store.finished.isEmpty()); assertTrue(rig.runner.active.value.isEmpty())
    }
}
