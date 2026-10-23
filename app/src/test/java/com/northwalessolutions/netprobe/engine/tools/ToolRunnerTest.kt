package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.office
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import com.northwalessolutions.netprobe.engine.tools.ToolRunner.Companion.forHost
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerTest {
    private val ok = ToolResult(ToolRunStatus.DONE, "ok")

    @Test fun `a second run on the same host is refused`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> gate.await(); ok })
        assertTrue(rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.HostBusy, rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE))
        gate.complete(Unit); runCurrent()
        assertEquals(1, rig.store.finished.size)
    }
    @Test fun `TooMany at four hosts and NoNetwork with no env`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> gate.await(); ok })
        for (h in 1L..4L) assertTrue(rig.runner.start(target(h), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.TooMany, rig.runner.start(target(5), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE))
        assertNull("a refused start never claims the host", rig.busy.busy.value[5])
        gate.complete(Unit); runCurrent()
        rig.env.value = null
        assertEquals(ToolRunner.Start.NoNetwork, rig.runner.start(target(6), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE))
    }
    @Test fun `a start waits for markInterrupted to complete ready`() = runTest {
        val store = ToolRunnerTestSupport.FakeStore()
        val pending = CompletableDeferred<Unit>()
        val waiting = object : ToolStore by store { override val ready = pending }
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> ok })
        val runner = ToolRunner(backgroundScope, StandardTestDispatcher(testScheduler), ToolRegistry(listOf(FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> ok })),
            waiting, HostBusyRegistry(), AppMessages(), rig.env, rig.foreground, { _, _, b -> b() }, {})
        val started = async { runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE) }
        runCurrent()
        assertTrue("start must wait for ready", started.isActive); assertTrue(store.started.isEmpty())
        pending.complete(Unit); runCurrent()
        assertTrue(started.await() is ToolRunner.Start.Started)
    }
    @Test fun `a normal return writes the result, releases the host, posts the message and re-identifies`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, p ->
            p.finding(Finding(FindingKind.PATH, "/admin", "200  10 B", "http://10.0.3.9:5000/admin", mapOf("status" to "200")))
            ToolResult(ToolRunStatus.DONE, "1 found in 10 paths") })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.DONE, "1 found in 10 paths"), rig.store.finished.single())
        assertEquals(listOf(1L to "/admin"), rig.store.findings)
        assertEquals("{\"url\":\"http://10.0.3.9:5000/admin\",\"status\":\"200\"}", rig.store.extras.single())
        assertNull(rig.busy.busy.value[7]); assertEquals(listOf(7L), rig.finishedHosts)
        assertEquals("dir-bruteforce finished: 1 found in 10 paths", rig.messages.current.value!!.text)
        assertTrue(rig.runner.active.value.isEmpty())
    }
    @Test fun `an option the tool records is written into optionsJson`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, p -> p.option("methodUsed", "GET_ONLY"); ok })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(listOf("{\"tls\":false,\"serverName\":null,\"methodUsed\":\"GET_ONLY\"}"), rig.store.optionWrites)
    }
    @Test fun `Stop ends a blocked tool and writes STOPPED with the stopped summary`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { c, p -> p.update(1234, 4614, 7); entered.complete(Unit); c.gate(); awaitCancellation() })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        rig.runner.stopHost(7); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Stopped after 1234 of 4614, 7 found"), rig.store.finished.single())
        assertNull(rig.busy.busy.value[7])
    }
    @Test fun `Stop during a blocking exchange is STOPPED even when the tool then returns FAILED`() = runTest {
        // Models TlsPeek and TcpSmbTransport: the closed socket's IOException is swallowed and the tool returns FAILED without
        // suspending again. The runner must still record the cancelled run as STOPPED with the stop summary (review I1).
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, p ->
            p.update(0, null, 0); entered.complete(Unit)
            withContext(NonCancellable) { reply.await() }
            ToolResult(ToolRunStatus.FAILED, "No TLS handshake on this port")
        })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        rig.runner.stopHost(7); runCurrent()
        assertTrue("the tool is still blocked in its exchange", rig.store.finished.isEmpty())
        reply.complete(Unit); runCurrent()
        val row = rig.store.finished.single()
        assertEquals(ToolRunStatus.STOPPED, row.second)
        assertEquals(ToolCopy.stopped(0, null, 0), row.third)
        assertNull(rig.busy.busy.value[7])
    }
    @Test fun `Stop ends a tool blocked in CancellableIo closing within a second`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ToolRunnerTestSupport.FakeStore(); val entered = CompletableDeferred<Unit>()
        val tool = FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ ->
            com.northwalessolutions.netprobe.engine.common.CancellableIo.closing(java.io.Closeable { }) { entered.complete(Unit); Thread.sleep(10_000) }
            ok
        }
        val runner = ToolRunner(scope, Dispatchers.IO.limitedParallelism(16), ToolRegistry(listOf(tool)), store, HostBusyRegistry(), AppMessages(),
            kotlinx.coroutines.flow.MutableStateFlow(ToolRunnerTestSupport.home), kotlinx.coroutines.flow.MutableStateFlow(true), { _, _, b -> b() }, {})
        runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        val t0 = System.nanoTime(); runner.stopHost(7)
        withTimeout(1_000) { while (store.finished.isEmpty()) delay(10) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000); assertEquals(ToolRunStatus.STOPPED, store.finished.single().second)
        scope.cancel()
    }
    @Test fun `a network identity change stops every run`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        rig.env.value = office; runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Network changed, run stopped"), rig.store.finished.single())
    }
    @Test fun `a bind failure is FAILED with the bind wording and a throwing tool is FAILED with its reason`() = runTest {
        val bound = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> ok }, bind = { _, _, _ -> throw BindFailedException(IllegalStateException("vpn")) })
        bound.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Tool skipped: could not bind to Wi-Fi"), bound.store.finished.single())
        val boom = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> throw IllegalStateException("boom") })
        boom.runner.start(target(8), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Failed: boom"), boom.store.finished.single())
    }
    @Test fun `a tool's own timeout is FAILED, not STOPPED, and re-identifies nothing`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> withTimeout(1_000) { awaitCancellation() } })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        val (_, status, summary) = rig.store.finished.single()
        assertEquals(ToolRunStatus.FAILED, status); assertTrue(summary, summary.startsWith("Failed: Timed out"))
        assertEquals(emptyList<Long>(), rig.finishedHosts); assertNull(rig.busy.busy.value[7])
    }
    @Test fun `an Error from a tool is FAILED with its reason`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> throw AssertionError("deep") })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Failed: deep"), rig.store.finished.single())
        assertNull(rig.busy.busy.value[7])
    }
    @Test fun `onFinished is skipped for a FAILED run with no finding and runs for one with a finding`() = runTest {
        val empty = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> ToolResult(ToolRunStatus.FAILED, "No HTTP response on this port") })
        empty.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(emptyList<Long>(), empty.finishedHosts)
        val partial = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, p -> p.finding(Finding(FindingKind.HINT, "http.path=/a")); ToolResult(ToolRunStatus.FAILED, "x") })
        partial.runner.start(target(8), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); runCurrent()
        assertEquals(listOf(8L), partial.finishedHosts)
    }
    @Test fun `the ticker republishes elapsed time while no update arrives`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolKeys.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() }, now = { testScheduler.currentTime })
        rig.runner.start(target(7), ToolKeys.DIR_BRUTEFORCE, ToolInputs.NONE); entered.await()
        advanceTimeBy(1_000); runCurrent()
        val first = rig.runner.active.value.forHost(7)!!.activeMs
        advanceTimeBy(1_000); runCurrent()
        assertTrue("elapsed moved from $first", rig.runner.active.value.forHost(7)!!.activeMs >= first + 800)
        rig.runner.stopHost(7); runCurrent()
    }
}
