package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolRunner.Companion.forHost
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Runs keyed by runId: a tool with no device (null target) claims no host, and a run survives its host going away. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerNoDeviceTest {
    private val ok = ToolResult(ToolRunStatus.DONE, "ok")
    private val key = "user:dns-lookup"

    @Test fun `a null target runs without a busy claim, records a null host and the tool name, and never re-identifies`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tool = FakeTool(key, TargetKind.NONE) { _, p -> p.update(1, 2, 0); gate.await(); ok }
        val rig = Rig(backgroundScope, tool)
        val started = rig.runner.start(null, key, ToolInputs.NONE)
        assertTrue(started is ToolRunner.Start.Started); runCurrent()
        assertTrue("no host was claimed", rig.busy.busy.value.isEmpty())
        val a = rig.runner.active.value.values.single()
        assertNull(a.hostId); assertEquals(key, a.toolKey); assertEquals(0, a.port); assertEquals((started as ToolRunner.Start.Started).runId, a.runId)
        assertEquals(listOf<Long?>(null), rig.store.hostIds); assertEquals(listOf(key), rig.store.toolNames)
        assertEquals("{}", rig.store.started.single())          // no target, so no tls or serverName either
        assertNull(tool.lastTarget)
        gate.complete(Unit); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.DONE, "ok"), rig.store.finished.single())
        assertTrue(rig.finishedHosts.isEmpty()); assertTrue(rig.runner.active.value.isEmpty())
        val m = rig.messages.current.value!!
        assertEquals("$key finished: ok", m.text); assertNull(m.hostId)
    }
    @Test fun `two no-device runs of the same tool run at once, and each stops by its own run id`() = runTest {
        val tool = FakeTool(key, TargetKind.NONE) { c, _ -> c.gate(); awaitCancellation() }
        val rig = Rig(backgroundScope, tool)
        val first = (rig.runner.start(null, key, ToolInputs.NONE) as ToolRunner.Start.Started).runId
        val second = (rig.runner.start(null, key, ToolInputs.NONE) as ToolRunner.Start.Started).runId
        runCurrent()
        assertEquals(setOf(first, second), rig.runner.active.value.keys)
        rig.runner.stop(first); runCurrent()
        assertEquals(setOf(second), rig.runner.active.value.keys)
        assertEquals(listOf(first), rig.store.finished.map { it.first }); assertEquals(ToolRunStatus.STOPPED, rig.store.finished.single().second)
        rig.runner.stop(second); runCurrent()
        assertTrue(rig.runner.active.value.isEmpty()); assertEquals(2, rig.store.finished.size)
        assertTrue(rig.busy.busy.value.isEmpty())
    }
    @Test fun `the four-run limit counts no-device runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(key, TargetKind.PORT) { _, _ -> gate.await(); ok })
        repeat(3) { assertTrue(rig.runner.start(null, key, ToolInputs.NONE) is ToolRunner.Start.Started) }
        assertTrue(rig.runner.start(target(7), key, ToolInputs.NONE) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.TooMany, rig.runner.start(null, key, ToolInputs.NONE))
        assertEquals(ToolRunner.Start.TooMany, rig.runner.start(target(8), key, ToolInputs.NONE))
        assertNull("a refused start never claims", rig.busy.busy.value[8])
        gate.complete(Unit); runCurrent()
        assertTrue(rig.runner.start(target(7), key, ToolInputs.NONE) is ToolRunner.Start.Started)   // released after the first host run ended
    }
    @Test fun `a host run is still refused while another run holds its host, whatever else is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(key) { _, _ -> gate.await(); ok })
        assertTrue(rig.runner.start(null, key, ToolInputs.NONE) is ToolRunner.Start.Started)
        assertTrue(rig.runner.start(target(7), key, ToolInputs.NONE) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.HostBusy, rig.runner.start(target(7), key, ToolInputs.NONE))
        rig.runner.stopHost(7); runCurrent()
        assertEquals("stopHost leaves the no-device run alone", 1, rig.runner.active.value.size)
        assertNull(rig.runner.active.value.forHost(7)); assertNull(rig.busy.busy.value[7])
        gate.complete(Unit); runCurrent()
    }
    @Test fun `stop by run id also stops a host run and releases its host`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(key) { c, _ -> c.gate(); awaitCancellation() })
        val id = (rig.runner.start(target(7), key, ToolInputs.NONE) as ToolRunner.Start.Started).runId; runCurrent()
        assertEquals(id, rig.runner.active.value.forHost(7)!!.runId)
        rig.runner.stop(id); runCurrent()
        assertEquals(ToolRunStatus.STOPPED, rig.store.finished.single().second); assertNull(rig.busy.busy.value[7])
        rig.runner.stop(id); runCurrent()                           // a stale stop is ignored
        assertEquals(1, rig.store.finished.size)
    }
    @Test fun `a no-device run pauses and resumes with the app like any other run`() = runTest {
        val reached = CompletableDeferred<Unit>(); val passed = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(key, TargetKind.NONE) { c, _ -> reached.complete(Unit); c.gate(); passed.complete(Unit); ok })
        rig.foreground.value = false
        rig.runner.start(null, key, ToolInputs.NONE)
        reached.await(); runCurrent()
        assertTrue(rig.runner.active.value.values.single().paused)
        assertFalse(passed.isCompleted)
        rig.foreground.value = true; runCurrent()
        advanceTimeBy(10); runCurrent()                                  // the bucket is emptied on resume: one token takes 10 ms
        assertTrue(passed.isCompleted)
    }
    @Test fun `a host deleted mid-run does not fail the run, and its claim is released`() = runTest {
        // Deleting a device cascades its tool_runs row away: later findings hit the foreign key and finish() updates no row.
        val hostGone = object : ToolRunnerTestSupport.FakeStore() {
            var deleted = false
            override suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long {
                if (deleted) throw IllegalStateException("FOREIGN KEY constraint failed")
                return super.addFinding(runId, kind, value, detail, extra)
            }
        }
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(key) { _, p ->
            p.finding(Finding(FindingKind.PATH, "/a")); gate.await(); p.finding(Finding(FindingKind.PATH, "/b")); ToolResult(ToolRunStatus.DONE, "2 found")
        }, store = hostGone)
        rig.runner.start(target(7), key, ToolInputs.NONE); runCurrent()
        hostGone.deleted = true
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1L to "/a"), hostGone.findings)                     // the second finding was lost, not the run
        assertEquals(Triple(1L, ToolRunStatus.DONE, "2 found"), hostGone.finished.single())
        assertNull(rig.busy.busy.value[7]); assertTrue(rig.runner.active.value.isEmpty())
    }
    @Test fun `an unregistered tool claims nothing and writes no row`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(key) { _, _ -> ok })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { rig.runner.start(target(7), "user:gone", ToolInputs.NONE) } }
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.store.hostIds.isEmpty())
    }
}
