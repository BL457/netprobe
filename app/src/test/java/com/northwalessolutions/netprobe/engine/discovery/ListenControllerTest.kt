package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.common.LockHandle
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.ContinuationInterceptor

/** 3C spec 8.1 items 15 and 18 (the network change), Review Focus 4 and 5: fake lock, fake sockets, test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenControllerTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val envA = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    private val envB = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.9.1"), emptyList(), "Guest", null, Transport.WIFI)

    private class FakeLock : LockHandle {
        var held = false; var acquires = 0; var releases = 0
        override fun acquire() { acquires++; held = true }
        override fun release() { if (held) releases++; held = false }
    }
    private class FakeSocket : HeardSocket {
        val inbox = Channel<Pair<ByteArray, Inet4Address>>(Channel.UNLIMITED)
        var closed = false
        override suspend fun receive(into: ByteArray): Datagram? {
            val (bytes, from) = inbox.receiveCatching().getOrNull() ?: throw IOException("closed")
            bytes.copyInto(into)
            return Datagram(from, bytes.size)
        }
        override fun close() { closed = true; inbox.close() }
    }
    private class FakeSockets : ListenSockets {
        var fail = false
        var throwTimes = 0
        val opened = mutableListOf<Pair<String, FakeSocket>>()
        override fun open(group: String, port: Int, env: NetworkEnv): HeardSocket? {
            if (throwTimes > 0) { throwTimes--; throw IllegalStateException("socket layer failed") }
            return if (fail) null else FakeSocket().also { opened += group to it }
        }
        fun latest(group: String): FakeSocket = opened.last { it.first == group }.second
    }
    private inner class Rig(scope: CoroutineScope, initial: SettingsState = SettingsState(listenMulticast = true)) {
        val settings = MutableStateFlow(initial)
        val foreground = MutableStateFlow(true)
        val env = MutableStateFlow<NetworkEnv?>(envA)
        val epoch = MutableStateFlow(0)
        val scan = MutableStateFlow(false)
        val lock = FakeLock(); val sockets = FakeSockets(); val sink = FakeSink()
        val writers = mutableListOf<Long>()
        var resolveTo: (NetworkEnv) -> Long? = { e -> if (e == envA) 1L else 2L }
        var scannedFlag = true
        val sessionRows = mutableListOf<Long>()
        val controller = ListenController(scope, settings, foreground, env, epoch, scan, lock, sockets,
            resolve = { e -> resolveTo(e) }, scanned = { scannedFlag }, writerFor = { _, id -> writers += id; HeardWriter(id, sink) },
            onSessionStart = { sessionRows += it }, io = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, clock = { 0L })
        fun hear(ipText: String, usn: String) {
            sockets.latest(ListenController.SSDP_GROUP).inbox.trySend(HeardTestSupport.notify(ipText, usn).toByteArray(Charsets.ISO_8859_1) to ip(ipText))
        }
    }

    @Test fun `with the switch off nothing is held`() = runTest {
        val rig = Rig(backgroundScope, SettingsState())
        rig.controller.start(); runCurrent()
        assertEquals(0, rig.lock.acquires); assertTrue(rig.sockets.opened.isEmpty())
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
    }
    @Test fun `switching on acquires once and opens both ports`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        assertEquals(1, rig.lock.acquires); assertTrue(rig.lock.held)
        assertEquals(listOf(ListenController.MDNS_GROUP, ListenController.SSDP_GROUP), rig.sockets.opened.map { it.first })
        assertEquals(ListenStatus.BOTH, rig.controller.status.value)
        rig.controller.start(); runCurrent()
        assertEquals("start is idempotent", 1, rig.lock.acquires)
    }
    @Test fun `a heard announcement on the session's network is written once within the repeat window`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertEquals(listOf(1L to "10.0.3.7"), rig.sink.stored)
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertEquals("a repeat is dropped before the queue", 1, rig.sink.storeCalls)
    }
    @Test fun `going to the background releases the lock and closes the sockets`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.foreground.value = false; runCurrent()
        assertFalse(rig.lock.held); assertEquals(1, rig.lock.releases)
        assertTrue(rig.sockets.opened.all { it.second.closed })
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
        rig.foreground.value = true; runCurrent()
        assertEquals(2, rig.lock.acquires); assertTrue(rig.lock.held)
    }
    @Test fun `turning the switch off and a reset both release`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.settings.value = rig.settings.value.copy(listenMulticast = false); runCurrent()
        assertFalse(rig.lock.held)
        rig.settings.value = rig.settings.value.copy(listenMulticast = true); runCurrent()
        assertTrue(rig.lock.held); assertEquals(2, rig.lock.acquires)
        rig.settings.value = SettingsDefaults.reset(rig.settings.value); runCurrent()
        assertFalse(rig.lock.held); assertEquals(2, rig.lock.releases)
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
    }
    @Test fun `losing the network releases`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.env.value = null; runCurrent()
        assertFalse(rig.lock.held); assertTrue(rig.sockets.opened.all { it.second.closed })
        assertEquals(ListenStatus.NO_NETWORK, rig.controller.status.value)
    }
    @Test fun `a network change releases, then re-acquires with a new writer`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.env.value = envB; runCurrent()
        assertEquals(2, rig.lock.acquires); assertEquals(1, rig.lock.releases); assertTrue(rig.lock.held)
        assertEquals(listOf(1L, 2L), rig.writers)
        assertTrue("the old sockets are closed", rig.sockets.opened.take(2).all { it.second.closed })
        assertEquals(4, rig.sockets.opened.size)
    }
    @Test fun `a Forget or its Undo restarts the session on the network row`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.epoch.value = 1; runCurrent()
        assertEquals(2, rig.lock.acquires); assertEquals(listOf(1L, 1L), rig.writers)
    }
    @Test fun `no network row means nothing is held`() = runTest {
        val rig = Rig(backgroundScope); rig.resolveTo = { null }
        rig.controller.start(); runCurrent()
        assertEquals(0, rig.lock.acquires); assertTrue(rig.sockets.opened.isEmpty())
        assertEquals(ListenStatus.NO_NETWORK, rig.controller.status.value)
    }
    @Test fun `ports that cannot open release the lock and say so`() = runTest {
        val rig = Rig(backgroundScope); rig.sockets.fail = true
        rig.controller.start(); runCurrent()
        assertEquals(1, rig.lock.acquires); assertFalse(rig.lock.held)
        assertEquals(ListenStatus.NO_PORTS, rig.controller.status.value)
    }
    @Test fun `only the enabled protocol is opened`() = runTest {
        val rig = Rig(backgroundScope, SettingsState(listenMulticast = true, ssdp = false))
        rig.controller.start(); runCurrent()
        assertEquals(listOf(ListenController.MDNS_GROUP), rig.sockets.opened.map { it.first })
        assertEquals(ListenStatus.MDNS_ONLY, rig.controller.status.value)
    }
    @Test fun `a running scan shows as paused and what is heard meanwhile is dropped`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.scan.value = true; rig.sink.ctx = FakeSink.HOME.copy(scanActive = true)
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertTrue(rig.sink.stored.isEmpty())
        assertEquals(ListenStatus.PAUSED_SCAN, rig.controller.status.value)
        assertTrue("the sockets stay open during a scan", rig.lock.held)
    }
    @Test fun `roaming between access points of one network keeps the session`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.env.value = envA.copy(bssid = "aa:bb:cc:dd:ee:02", dnsServers = listOf(ip("10.0.3.1"))); runCurrent()
        assertEquals("no restart, so the 32-row count and the removed set survive (G12)", 1, rig.lock.acquires)
        assertEquals(listOf(1L), rig.sessionRows)
    }
    @Test fun `a failed write is logged and the next packet is still written`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.sink.failNextStore = true
        rig.hear("10.0.3.7", "uuid:a"); rig.hear("10.0.3.8", "uuid:b"); runCurrent()
        assertEquals(listOf(1L to "10.0.3.8"), rig.sink.stored)
        assertEquals(ListenStatus.BOTH, rig.controller.status.value)
    }
    @Test fun `a failed session says so and restarts after its backoff, never silently`() = runTest {
        val rig = Rig(backgroundScope); rig.sockets.throwTimes = 1
        rig.controller.start(); runCurrent()
        assertEquals(ListenStatus.RETRYING, rig.controller.status.value); assertFalse("released while waiting", rig.lock.held)
        advanceTimeBy(ListenController.LISTEN_RETRY_FIRST_MS); runCurrent()
        assertEquals(ListenStatus.BOTH, rig.controller.status.value); assertTrue(rig.lock.held); assertEquals(2, rig.lock.acquires)
    }
    @Test fun `R3 on, an unscanned network holds nothing until a scan completes`() = runTest {
        val rig = Rig(backgroundScope); rig.scannedFlag = false
        rig.controller.start(); runCurrent()
        assertEquals(0, rig.lock.acquires); assertEquals(ListenStatus.NOT_SCANNED, rig.controller.status.value)
        rig.scan.value = true; runCurrent(); rig.scannedFlag = true; rig.scan.value = false; runCurrent()
        assertEquals(1, rig.lock.acquires); assertEquals(ListenStatus.BOTH, rig.controller.status.value)
        assertTrue("the shipped default is the controller's recommendation", ListenController.LISTEN_SCANNED_NETWORKS_ONLY)
    }
    @Test fun `R3 off listens on an unscanned network at once`() = runTest {
        val sink = FakeSink(); val lock = FakeLock()
        val controller = ListenController(backgroundScope, MutableStateFlow(SettingsState(listenMulticast = true)), MutableStateFlow(true), MutableStateFlow<NetworkEnv?>(envA),
            MutableStateFlow(0), MutableStateFlow(false), lock, FakeSockets(), resolve = { 1L }, scanned = { false }, writerFor = { _, id -> HeardWriter(id, sink) },
            io = backgroundScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, clock = { 0L }, scannedOnly = false)
        controller.start(); runCurrent()
        assertEquals(1, lock.acquires); assertEquals(ListenStatus.BOTH, controller.status.value)
    }
    @Test fun `packets from network A queued at a change are discarded and B's are dropped until Home catches up`() = runTest {
        val rig = Rig(backgroundScope)
        val gate = CompletableDeferred<Unit>(); rig.sink.gate = gate
        rig.controller.start(); runCurrent()
        for (i in 1..3) rig.hear("10.0.3.${10 + i}", "uuid:$i")
        runCurrent()                                    // the writer is parked in A's first store, two more wait in A's queue
        rig.env.value = envB; runCurrent()              // Home has not caught up: the current network is still A (FakeSink.HOME)
        rig.hear("10.0.3.20", "uuid:b"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue("nothing written to A or B", rig.sink.stored.isEmpty())
        assertEquals("only A's parked first store ever started, and it was cancelled", 1, rig.sink.storeCalls)
        assertEquals(listOf(1L, 2L), rig.writers)
    }
}
