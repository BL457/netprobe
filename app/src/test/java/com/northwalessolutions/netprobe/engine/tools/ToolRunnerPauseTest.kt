package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import com.northwalessolutions.netprobe.engine.tools.ToolRunner.Companion.forHost
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 1: the background pause closes the gate, stops the clock and excuses failures that span it (spec 4.8). */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerPauseTest {
    @Test fun `gate suspends while foreground is false and resumes when true`() = runTest {
        val passed = CompletableDeferred<Unit>(); val reached = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { c, _ -> reached.complete(Unit); c.gate(); passed.complete(Unit); ToolResult(ToolRunStatus.DONE, "ok") })
        rig.foreground.value = false
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE)
        reached.await(); runCurrent()
        assertFalse("the gate must stay closed in the background", passed.isCompleted)
        assertTrue(rig.runner.active.value.forHost(7)!!.paused)
        rig.foreground.value = true; runCurrent()
        // Ruling R6: the bucket is emptied on resume (spec 4.8), so the first request after it waits one token, 10 ms at 100 per second.
        assertFalse("the bucket is emptied on resume, so the first request waits one token", passed.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertTrue(passed.isCompleted)
    }
    @Test fun `a gate parked in its rate wait when the pause lands stays closed until the resume`() = runTest {
        var passed = 0; val burstTaken = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { c, _ ->
            repeat(10) { c.gate(); passed++ }; burstTaken.complete(Unit)
            c.gate(); passed++                                              // the eleventh parks in delay(10)
            awaitCancellation()
        })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); burstTaken.await(); runCurrent()
        assertEquals(10, passed)
        rig.foreground.value = false; runCurrent()
        advanceTimeBy(50); runCurrent()
        assertEquals("no request may be sent in the background", 10, passed)
        rig.foreground.value = true; runCurrent()
        assertEquals("the bucket is emptied on resume", 10, passed)
        advanceTimeBy(10); runCurrent()
        assertEquals(11, passed)
        rig.runner.stopHost(7); runCurrent()
    }
    @Test fun `a gate that wakes before the foreground collector still waits for the emptied bucket`() = runTest {
        val passed = CompletableDeferred<Unit>(); val reached = CompletableDeferred<Unit>()
        val tool = FakeTool(ToolKeys.DIR_BRUTEFORCE) { c, _ -> reached.complete(Unit); c.gate(); passed.complete(Unit); ToolResult(ToolRunStatus.DONE, "ok") }
        val fg = kotlinx.coroutines.flow.MutableStateFlow(false)
        // Runs on an unconfined dispatcher, the collectors on the standard one: the gate subscribes to foreground first, so on the
        // resume it wakes before the collector has emptied the bucket.
        val runner = ToolRunner(backgroundScope, UnconfinedTestDispatcher(testScheduler), ToolRegistry(listOf(tool)), ToolRunnerTestSupport.FakeStore(),
            HostBusyRegistry(), AppMessages(), kotlinx.coroutines.flow.MutableStateFlow(ToolRunnerTestSupport.home), fg, { _, _, b -> b() }, {},
            clock = { testScheduler.currentTime })
        runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE)
        assertTrue(reached.isCompleted); runCurrent()
        assertFalse(passed.isCompleted)
        fg.value = true; runCurrent()
        assertFalse("the full pre-pause bucket must not be spent", passed.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertTrue(passed.isCompleted)
    }
    @Test fun `a pause stops the elapsed clock`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() }, now = { testScheduler.currentTime })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        advanceTimeBy(1_000); runCurrent()
        rig.foreground.value = false; runCurrent()
        val atPause = rig.runner.active.value.forHost(7)!!.activeMs
        advanceTimeBy(60_000); runCurrent()
        assertEquals("a minute in the browser adds nothing", atPause, rig.runner.active.value.forHost(7)!!.activeMs)
        rig.foreground.value = true; advanceTimeBy(1_000); runCurrent()
        assertTrue(rig.runner.active.value.forHost(7)!!.activeMs > atPause)
        rig.runner.stopHost(7); runCurrent()
    }
    @Test fun `a failure spanning a pause is excused`() = runTest {
        var excusedAfter = false; var notExcusedFresh = true
        val inRequest = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { c, _ ->
            val epoch = c.pauseEpoch
            inRequest.complete(Unit); resume.await()            // the request is "in flight" across the pause
            excusedAfter = c.excused(epoch)                     // the pause began after this request started
            notExcusedFresh = c.excused(c.pauseEpoch)           // a request started after the resume is not excused
            ToolResult(ToolRunStatus.DONE, "ok")
        })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); inRequest.await()
        rig.foreground.value = false; runCurrent()
        rig.foreground.value = true; runCurrent()
        resume.complete(Unit); runCurrent()
        assertTrue(excusedAfter); assertFalse(notExcusedFresh)
    }
}
