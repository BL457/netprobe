package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.common.LockHandle
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Listen between scans" (3C spec 3.2, D10, D11, amendments A1 to A4). Hears and never sends, except the writer's bounded UPnP description
 * GET (R2). One session per (plan, network identity, interface address, transport, network epoch): roaming between access points of one
 * network does not restart it (plan review G12). A failed session is restarted with a bounded backoff and says so (G4); it never ends
 * listening silently. Nothing runs in the background: foreground false ends the session.
 */
class ListenController(
    private val scope: CoroutineScope,
    private val settings: StateFlow<SettingsState>,
    private val foreground: StateFlow<Boolean>,
    private val env: StateFlow<NetworkEnv?>,
    /** AppContainer.networkEpoch: bumped by Forget, its Undo and its commit, so a session re-resolves its network row. */
    private val epoch: StateFlow<Int>,
    private val scanActive: StateFlow<Boolean>,
    private val lock: LockHandle,
    private val sockets: ListenSockets,
    /** The network row for the env (null when none or its Forget is pending): the only row this session may write to. */
    private val resolve: suspend (NetworkEnv) -> Long?,
    /** Whether the row has a completed scan (lastRunAt set), for Director ruling R3. */
    private val scanned: suspend (Long) -> Boolean,
    private val writerFor: (NetworkEnv, Long) -> HeardWriter,
    /** Called with the session's network row each time a session opens its sockets. */
    private val onSessionStart: (Long) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (Throwable) -> Unit = {},
    private val scannedOnly: Boolean = LISTEN_SCANNED_NETWORKS_ONLY,
) {
    /** What a session depends on: the plan and the network's identity, never its BSSID, SSID or DNS servers (G12). */
    private data class Key(val plan: ListenPlan, val identity: String, val interfaceIp: Inet4Address, val transport: Transport, val epoch: Int)
    private val session = MutableStateFlow<ListenStatus?>(null)
    private val started = AtomicBoolean(false)

    val status: StateFlow<ListenStatus> = combine(settings, foreground, env, session, scanActive) { s, fg, e, ss, scan ->
        ListenStatusRules.of(s.listenMulticast, s.mdns, s.ssdp, fg, e != null, ss, scan)
    }.stateIn(scope, SharingStarted.Eagerly, ListenStatus.OFF)

    /** Idempotent; AppContainer.onActivityCreated calls it once per process. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(settings, foreground, env, epoch) { s, fg, e, n ->
                val plan = ListenGate.plan(s.listenMulticast, s.mdns, s.ssdp, fg, e != null)
                if (plan == null || e == null) null else Key(plan, NetworkSignature.identityKey(e), e.interfaceIp, e.transport, n)
            }.distinctUntilChanged().collectLatest { key -> if (key == null) session.value = null else supervise(key) }
        }
    }

    /** Runs sessions for [key] until the key changes; an unexpected failure waits a bounded, doubling backoff and tries again (G4). */
    private suspend fun supervise(key: Key) {
        var backoff = LISTEN_RETRY_FIRST_MS
        while (true) {
            try {
                listen(key)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                log(e)
                session.value = ListenStatus.RETRYING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(LISTEN_RETRY_MAX_MS)
            }
        }
    }

    private suspend fun listen(key: Key) {
        val e = env.value?.takeIf { NetworkSignature.identityKey(it) == key.identity } ?: return
        var networkId: Long = resolve(e) ?: run { session.value = ListenStatus.NO_NETWORK; return }
        // Director ruling R3: a network nobody has scanned is not listened on; wait for a completed scan, then start (amendment A3).
        // A network with no row yet (NO_ROW_YET) has nothing to write to, so it waits the same way whatever R3 says; the resolve sends and writes nothing (S5).
        while (networkId == NO_ROW_YET || (scannedOnly && !scanned(networkId))) {
            session.value = ListenStatus.NOT_SCANNED
            scanActive.first { it }
            scanActive.first { !it }
            networkId = resolve(e) ?: run { session.value = ListenStatus.NO_NETWORK; return }
        }
        val opened = ArrayList<HeardSocket>(2)
        val queue = heardQueue()
        lock.acquire()
        try {
            onSessionStart(networkId)
            val mdns = if (key.plan.mdns) sockets.open(MDNS_GROUP, MDNS_PORT, e)?.also { opened += it } else null
            val ssdp = if (key.plan.ssdp) sockets.open(SSDP_GROUP, SSDP_PORT, e)?.also { opened += it } else null
            session.value = ListenStatusRules.opened(mdns != null, ssdp != null)
            if (opened.isEmpty()) { lock.release(); awaitCancellation() }   // nothing to hear: hold nothing until the key changes
            val writer = writerFor(e, networkId)
            val repeats = HeardRepeats(clock)
            coroutineScope {
                if (mdns != null) launch(io) { read(mdns, MDNS_MAX_PACKET) { bytes, from -> HeardPackets.mdns(bytes, from, key.identity).forEach { offer(queue, repeats, it) } } }
                if (ssdp != null) launch(io) { read(ssdp, SSDP_MAX_PACKET) { bytes, from -> HeardPackets.ssdp(from, bytes, key.identity)?.let { offer(queue, repeats, it) } } }
                launch {
                    for (h in queue) {
                        // One failed write (a database error) is logged and never ends the session.
                        try { writer.accept(h) } catch (ce: CancellationException) { throw ce } catch (ex: Exception) { log(ex) }
                    }
                }
            }
        } finally {
            opened.forEach { s -> runCatching { s.close() } }   // cleanup only: a failed close must not skip the release below
            queue.cancel()                                        // discards anything still queued from this network
            lock.release()
            session.value = null
        }
    }

    private fun offer(queue: Channel<Heard>, repeats: HeardRepeats, h: Heard) {
        if (repeats.admit(h.repeatKey)) queue.trySend(h)   // DROP_OLDEST: never fails, never suspends a reader
    }

    /**
     * An IOException after the session was cancelled is our own close and ends this reader quietly. One while the session is live is a real failure
     * (final review S1): it escapes, fails the session, and [supervise] shows RETRYING and reopens after its backoff. A packet the parsers choke on is logged and skipped
     * (G4). Any other failure of receive escapes, fails the session and is retried by [supervise].
     */
    private suspend fun read(socket: HeardSocket, size: Int, onPacket: (ByteArray, Inet4Address) -> Unit) {
        val buf = ByteArray(size)
        while (true) {
            val d = (try { socket.receive(buf) } catch (ce: CancellationException) { throw ce } catch (e: IOException) { if (!currentCoroutineContext().isActive) return else throw e }) ?: continue
            try { onPacket(buf.copyOf(d.length), d.from) } catch (e: Exception) { log(e) }
        }
    }

    companion object {
        /** What [resolve] returns for a network with no row yet: nothing is written for it until a scan creates the row (final review S5). */
        const val NO_ROW_YET = 0L
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        /** mDNS packets can reach the jumbo-frame size (RFC 6762 section 17); SSDP NOTIFYs are small. */
        const val MDNS_MAX_PACKET = 9000
        const val SSDP_MAX_PACKET = 4096
        /** Plan review G4: the first retry after a failed session, doubling to the cap. */
        const val LISTEN_RETRY_FIRST_MS = 1_000L
        const val LISTEN_RETRY_MAX_MS = 60_000L
        /**
         * Director ruling R3 (pending; default per the controller's recommendation, amendment A3): true listens only on a network with at least
         * one completed scan; false listens on any network.
         */
        const val LISTEN_SCANNED_NETWORKS_ONLY = true
    }
}
