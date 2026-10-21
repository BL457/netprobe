package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.home.TestEnvs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

private typealias ScanBodyFn = suspend (HostEntity, ScanProfile, NetworkEnv, ScanSink) -> Int

private class FakeOpenStore(initial: List<OpenScan> = emptyList()) : OpenScanStore {
    val items = CopyOnWriteArrayList(initial)
    override fun add(scan: OpenScan) { items.removeAll { it.hostId == scan.hostId }; items.add(scan) }
    override fun remove(hostId: Long) { items.removeAll { it.hostId == hostId } }
    override fun drain(): List<OpenScan> { val all = items.toList(); items.clear(); return all }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(val test: TestScope, body: ScanBodyFn = { _, _, _, _ -> awaitCancellation() }, leftovers: List<OpenScan> = emptyList()) {
    val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
    val busy = HostBusyRegistry()
    val live = LiveWork(test.backgroundScope, clock = { test.testScheduler.currentTime })
    val messages = AppMessages()
    val store = FakeOpenStore(leftovers)
    val foreground = MutableStateFlow(true)
    val scans = PortScans(test.backgroundScope, env, busy, live, messages, store, body, clock = { test.testScheduler.currentTime }, foreground = foreground)
}

private fun host(id: Long) = TestHosts.host("10.0.3.$id").copy(id = id)

/** Spec 3.3 and 9.1 items 8 and 9: PortScans owns Device port scans on the app scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortScansTest {
    @Test fun `two scans start, a third is refused and nothing is queued`() = runTest {
        val rig = Rig(this)
        assertSame(PortScanStart.Started, rig.scans.start(host(1), ScanProfile.Quick))
        assertSame(PortScanStart.Started, rig.scans.start(host(2), ScanProfile.Quick))
        runCurrent()
        assertSame(PortScanStart.TooMany, rig.scans.start(host(3), ScanProfile.Quick))
        assertFalse("the refused host is not claimed", rig.busy.busy.value.containsKey(3L))
        rig.scans.stop(1); runCurrent()
        assertEquals("stopping one starts nothing", setOf(2L), rig.scans.state.value.keys)
        assertFalse(rig.busy.busy.value.containsKey(3L))
    }
    @Test fun `two simultaneous starts with one slot left start exactly one`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick)
        val results = CopyOnWriteArrayList<PortScanStart>(); val gate = CountDownLatch(1)
        val threads = listOf(host(2), host(3)).map { h -> Thread { gate.await(); results += rig.scans.start(h, ScanProfile.Quick) } }
        threads.forEach { it.start() }; gate.countDown(); threads.forEach { it.join() }
        assertEquals(1, results.count { it === PortScanStart.Started }); assertEquals(1, results.count { it === PortScanStart.TooMany })
        assertEquals("exactly two hosts are claimed", 2, rig.busy.busy.value.size)
    }
    @Test fun `a host held by a tool is busy and nothing is claimed`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.busy.tryClaim(5, Work.TOOL))
        assertSame(PortScanStart.HostBusy, rig.scans.start(host(5), ScanProfile.Quick))
        assertEquals(mapOf(5L to Work.TOOL), rig.busy.busy.value)
        assertTrue(rig.store.items.isEmpty()); assertTrue(rig.scans.state.value.isEmpty())
    }
    @Test fun `no network refuses the start`() = runTest {
        val rig = Rig(this); rig.env.value = null
        assertSame(PortScanStart.NoNetwork, rig.scans.start(host(1), ScanProfile.Quick))
        assertTrue(rig.busy.busy.value.isEmpty())
    }
    @Test fun `a network identity change cancels with Network changed and releases the host`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        rig.env.value = TestEnvs.office; runCurrent()
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.scans.state.value.isEmpty())
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Network changed, scan stopped", e.summary)
        assertEquals("Port scan finished: Network changed, scan stopped", rig.messages.current.value?.text)
    }
    @Test fun `active is true while a scan runs and false after`() = runTest {
        val rig = Rig(this)
        assertFalse(rig.scans.active.value)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertTrue(rig.scans.active.value)
        rig.scans.stop(1); runCurrent()
        assertFalse(rig.scans.active.value)
    }
    @Test fun `a scan completes with no screen, ends DONE and posts its message`() = runTest {
        val rig = Rig(this, body = { _, _, _, sink -> sink.progress(ScanProgress(65_535, 65_535, 5, 0)); delay(14_000); 5 })
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        assertEquals(65_535, rig.scans.state.value.getValue(1L).progress?.done)
        advanceTimeBy(14_001); runCurrent()
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.DONE, e.outcome); assertEquals("Done in 14 s, 5 open ports", e.summary)
        assertTrue(rig.scans.state.value.isEmpty()); assertTrue(rig.busy.busy.value.isEmpty())
        assertEquals("Port scan finished: Done in 14 s, 5 open ports", rig.messages.current.value?.text); assertEquals(1L, rig.messages.current.value?.hostId)
    }
    @Test fun `stop reports the time and stopAndJoin returns only after the body's finally`() = runTest {
        var finallyRan = false
        val rig = Rig(this, body = { _, _, _, _ -> try { awaitCancellation() } finally { finallyRan = true } })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        delay(8_000)
        rig.scans.stop(1); runCurrent()
        assertEquals("Stopped after 8 s", rig.live.ended.value.single().summary)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        finallyRan = false
        rig.scans.stopAndJoin(1)
        assertTrue("the join waited for the body", finallyRan)
        assertTrue(rig.busy.busy.value.isEmpty())
        assertEquals("a silent stop (Remove device) posts no message of its own", "Port scan finished: Stopped after 8 s", rig.messages.current.value?.text)
    }
    @Test fun `progress is per scan so a concurrent scan does not change it`() = runTest {
        val rig = Rig(this, body = { h, _, _, sink -> if (h.id == 1L) sink.progress(ScanProgress(10, 100, 1, 0)); awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); rig.scans.start(host(2), ScanProfile.Quick); runCurrent()
        assertEquals(ScanProgress(10, 100, 1, 0), rig.scans.state.value.getValue(1L).progress)
        assertNull(rig.scans.state.value.getValue(2L).progress)
    }
    @Test fun `identify counts are published`() = runTest {
        val rig = Rig(this, body = { _, _, _, sink -> sink.identify { it.copy(pending = 2) }; awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals(IdentifyCount(2, 0), rig.scans.state.value.getValue(1L).identify)
    }
    @Test fun `the open scan record holds the scan while it runs and is empty after`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals(listOf(1L), rig.store.items.map { it.hostId })
        rig.scans.stop(1); runCurrent()
        assertTrue(rig.store.items.isEmpty())
    }
    @Test fun `leftover records at construction become Interrupted items and are cleared`() = runTest {
        val rig = Rig(this, leftovers = listOf(OpenScan(9, 1, 5_000)))
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.INTERRUPTED, e.outcome); assertNull(e.endedAt); assertEquals(9L, e.live.hostId)
        assertEquals("Interrupted when NetProbe closed", e.summary)
        assertTrue(rig.store.items.isEmpty())
    }
    @Test fun `a body that throws leaves nothing claimed, nothing running and nothing recorded`() = runTest {
        val rig = Rig(this, body = { _, _, _, _ -> throw IllegalStateException("boom") })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.scans.state.value.isEmpty()); assertTrue(rig.store.items.isEmpty())
        assertFalse(rig.scans.active.value); assertTrue(rig.live.live.value.isEmpty())
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.FAILED, e.outcome); assertEquals("Scan failed: boom", e.summary)
    }
    @Test fun `a bind failure ends FAILED with the bind message`() = runTest {
        val rig = Rig(this, body = { _, _, _, _ -> throw BindFailedException(IllegalStateException("no network")) })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals("Scan skipped: could not bind to Wi-Fi", rig.live.ended.value.single().summary)
        assertTrue(rig.busy.busy.value.isEmpty())
    }
    @Test fun `a scan outlives the scope that asked for it`() = runTest {
        val rig = Rig(this)
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch { rig.scans.start(host(1), ScanProfile.Quick) }
        runCurrent()
        caller.cancel(); runCurrent()
        assertTrue("still running", rig.scans.state.value.containsKey(1L)); assertTrue("still claimed", rig.busy.busy.value.containsKey(1L))
    }
    @Test fun `a stop before the first dispatch releases the claim and the record and records nothing`() = runTest {
        val rig = Rig(this)
        assertSame(PortScanStart.Started, rig.scans.start(host(1), ScanProfile.Quick))
        rig.scans.stop(1); runCurrent()
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.store.items.isEmpty()); assertTrue(rig.scans.state.value.isEmpty())
        assertFalse(rig.scans.active.value); assertTrue(rig.live.live.value.isEmpty()); assertTrue(rig.live.ended.value.isEmpty())
    }
    @Test fun `stop by the LiveWork id stops that scan and only that scan`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick); rig.scans.start(host(2), ScanProfile.Quick); runCurrent()
        val id = rig.live.live.value.single { it.hostId == 2L }.id
        rig.live.stop(id); runCurrent()
        assertEquals(setOf(1L), rig.scans.state.value.keys); assertEquals(setOf(1L), rig.busy.busy.value.keys)
        val e = rig.live.ended.value.single()
        assertEquals(2L, e.live.hostId); assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Stopped after 0 s", e.summary)
    }
    @Test fun `a stale stop of an ended scan does not cancel a new scan of the same host`() = runTest {
        var calls = 0
        val rig = Rig(this, body = { _, _, _, _ -> if (calls++ == 0) 1 else awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        val oldId = rig.live.ended.value.single().live.id
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        rig.live.stop(oldId); runCurrent()
        assertTrue("the new scan is untouched", rig.scans.state.value.containsKey(1L)); assertTrue(rig.busy.busy.value.containsKey(1L))
    }
    @Test fun `Done in reports active time only, not the time spent in the background`() = runTest {
        val rig = Rig(this, body = { _, _, _, _ -> delay(20_000); 3 })
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        advanceTimeBy(5_000); rig.foreground.value = false; runCurrent()
        advanceTimeBy(10_000); rig.foreground.value = true; runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals("Done in 10 s, 3 open ports", rig.live.ended.value.single().summary)
    }
    @Test fun `Stopped after counts active time only`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        advanceTimeBy(4_000); rig.foreground.value = false; runCurrent()
        advanceTimeBy(60_000)
        rig.scans.stop(1); runCurrent()
        assertEquals("Stopped after 4 s", rig.live.ended.value.single().summary)
    }
    @Test fun `a scan that starts while in the background counts from when it comes back`() = runTest {
        val rig = Rig(this)
        rig.foreground.value = false
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        advanceTimeBy(30_000); rig.foreground.value = true; runCurrent()
        advanceTimeBy(3_000)
        rig.scans.stop(1); runCurrent()
        assertEquals("Stopped after 3 s", rig.live.ended.value.single().summary)
    }
    @Test fun `stopAndJoin posts no message at all`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        rig.scans.stopAndJoin(1); runCurrent()
        assertNull(rig.messages.current.value)
    }
}
