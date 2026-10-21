package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.scans.ScansCopy
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Identifies started and finished during one port scan (Detail spec 1.14). */
data class IdentifyCount(val pending: Int = 0, val done: Int = 0)
data class PortScanState(val progress: ScanProgress?, val identify: IdentifyCount)

sealed interface PortScanStart {
    object Started : PortScanStart
    object HostBusy : PortScanStart
    object TooMany : PortScanStart
    object NoNetwork : PortScanStart
}

/** What a scan body reports to its owner. */
class ScanSink(private val onProgress: (ScanProgress) -> Unit, private val onIdentify: ((IdentifyCount) -> IdentifyCount) -> Unit) {
    fun progress(p: ScanProgress) { onProgress(p) }
    fun identify(change: (IdentifyCount) -> IdentifyCount) { onIdentify(change) }
}

/**
 * Device port scans on the app scope (3D spec 3.3, D10): leaving the Device screen no longer stops them. At most [MAX_PORT_SCANS] run at
 * once and a third is refused, there is no queue. One ScanController per running host, so the network watcher behaves as it always did.
 * The host is claimed in [busy] for the scan's whole life. The scan's [body] pauses itself in the background through ForegroundGate.
 * Callers touch [start], [stop] and [stopAndJoin] on the main thread, as the controllers expect; the bookkeeping is thread safe.
 */
class PortScans(
    private val scope: CoroutineScope,
    private val env: StateFlow<NetworkEnv?>,
    private val busy: HostBusyRegistry,
    private val liveWork: LiveWork,
    private val messages: AppMessages,
    private val open: OpenScanStore,
    private val body: suspend (HostEntity, ScanProfile, NetworkEnv, ScanSink) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
) {
    private class Running(val host: HostEntity, val controller: ScanController, val startedAt: Long) {
        @Volatile var stopRequested = false
        @Volatile var silent = false
        @Volatile var progress: ScanProgress? = null
        @Volatile var identify = IdentifyCount()
        @Volatile var lastPublishAt = -PUBLISH_MS
        @Volatile var liveId = 0L
        /** Foreground time so far: milliseconds banked in earlier foreground stretches, plus the open stretch (-1 when in the background). */
        private var bankedMs = 0L
        private var segmentStart = -1L
        @Synchronized fun setForeground(fg: Boolean, now: Long) {
            if (fg && segmentStart < 0) segmentStart = now
            else if (!fg && segmentStart >= 0) { bankedMs += now - segmentStart; segmentStart = -1 }
        }
        @Synchronized fun activeMs(now: Long): Long = bankedMs + if (segmentStart >= 0) now - segmentStart else 0L
        val finished = AtomicBoolean(false)
    }

    private val lock = Any()
    private val running = HashMap<Long, Running>()      // hostId -> scan, guarded by lock
    private val _state = MutableStateFlow<Map<Long, PortScanState>>(emptyMap())
    private val _active = MutableStateFlow(false)
    val state: StateFlow<Map<Long, PortScanState>> = _state
    /** True while any port scan runs: the KeepScreenOn input, exactly as tool runs count. */
    val active: StateFlow<Boolean> = _active

    init {
        // Scans a dead process left open: shown once as Interrupted (spec 3.3), mirroring ToolRepository.markInterrupted for tool runs.
        open.drain().forEach { liveWork.addInterrupted(LiveWork.Kind.PORT_SCAN, it.networkId, it.hostId, it.startedAt, ScansCopy.INTERRUPTED) }
    }

    fun start(host: HostEntity, profile: ScanProfile): PortScanStart {
        if (env.value == null) return PortScanStart.NoNetwork
        synchronized(lock) {
            if (!busy.tryClaim(host.id, HostBusyRegistry.Work.PORT_SCAN)) return PortScanStart.HostBusy
            if (running.size >= MAX_PORT_SCANS) {
                busy.release(host.id, HostBusyRegistry.Work.PORT_SCAN)
                return PortScanStart.TooMany
            }
            val r = Running(host, ScanController(scope, env) { }, clock())
            running[host.id] = r
            open.add(OpenScan(host.id, host.networkId, r.startedAt))
            publishLocked()
            // Started under the lock, so a stop() for this host can never land between registering the scan and starting its job (a
            // stop that found no job would leave the scan running). start() only launches; the body runs later on the scope.
            r.controller.start(onComplete = { finish(r) }) { e -> execute(r, profile, e) }
            // The controller refuses only when the network vanished since the check above, and then no job exists and onComplete never
            // runs: release the claim and the record here. NO_NETWORK is set by that refusal alone, and stop() needs this lock.
            if (r.controller.state.value.message == ScanController.NO_NETWORK) {
                finish(r)
                return PortScanStart.NoNetwork
            }
        }
        return PortScanStart.Started
    }

    fun stop(hostId: Long) {
        val r = synchronized(lock) { running[hostId] } ?: return
        stopRun(r)
    }

    /** Remove device: waits until the scan body (and so every port write) has finished, and posts no end message. */
    suspend fun stopAndJoin(hostId: Long) {
        val r = synchronized(lock) { running[hostId] } ?: return
        r.silent = true
        r.stopRequested = true
        r.controller.stopAndJoin()
        finish(r)
    }

    private fun stopRun(r: Running) {
        r.stopRequested = true
        r.controller.stop()
    }

    private suspend fun execute(r: Running, profile: ScanProfile, e: NetworkEnv) {
        val host = r.host
        val sink = ScanSink({ p -> reportProgress(r, p) }, { change -> reportIdentify(r, change) })
        // Active time only (final review S5): the same foreground flow the gate and ToolRunner read, so "Done in N s" agrees with tool rows.
        r.setForeground(foreground.value, clock())
        val watch = scope.launch { foreground.collect { r.setForeground(it, clock()) } }
        try { liveWork.tracked<Int>(LiveWork.Kind.PORT_SCAN, host.networkId, host.id, DisplayName.of(host), stop = { stopRun(r) },
            summarize = { opened, seconds -> LiveWork.Outcome.DONE to ScansCopy.portDone(opened, seconds) },
            stopped = { seconds -> if (r.stopRequested) ScansCopy.stoppedAfter(seconds) else ScanController.NETWORK_CHANGED },
            failed = { t -> if (t is BindFailedException) DetailCopy.SCAN_BIND_FAILED else ScanController.failureMessage(t) },
            onEnd = { _, summary -> if (!r.silent) messages.post(ToolCopy.finished(ScansCopy.PORT_SCAN_NAME, summary), host.id) },
            elapsedMs = { r.activeMs(clock()) }) { id ->
            r.liveId = id
            body(host, profile, e, sink)
        } } finally { watch.cancel() }
    }

    /** Idempotent; the host is released last, after the record and the running entry are gone, so a new scan can never lose its own record. */
    private fun finish(r: Running) {
        if (!r.finished.compareAndSet(false, true)) return
        synchronized(lock) {
            running.remove(r.host.id, r)
            publishLocked()
        }
        open.remove(r.host.id)
        busy.release(r.host.id, HostBusyRegistry.Work.PORT_SCAN)
    }

    private fun reportProgress(r: Running, p: ScanProgress) {
        r.progress = p
        liveWork.progress(r.liveId, p.done, p.total, p.open)
        val now = clock()
        if (p.done >= p.total || now - r.lastPublishAt >= PUBLISH_MS) {
            r.lastPublishAt = now
            synchronized(lock) { publishLocked() }
        }
    }

    private fun reportIdentify(r: Running, change: (IdentifyCount) -> IdentifyCount) {
        synchronized(lock) {
            r.identify = change(r.identify)
            publishLocked()
        }
    }

    private fun publishLocked() {
        _state.value = running.mapValues { (_, r) -> PortScanState(r.progress, r.identify) }
        _active.value = running.isNotEmpty()
    }

    companion object {
        /** At most this many Device port scans at once (spec D10); the copy and the refusal follow this constant. */
        const val MAX_PORT_SCANS = 2
        private const val PUBLISH_MS = Motion.PROGRESS_SAMPLE_MS
    }
}
