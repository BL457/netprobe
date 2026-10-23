package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.RateBucket
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns tool runs (spec 4.4): one per host (a run with no device has no host to claim), at most MAX_ACTIVE_RUNS runs at once, every live run keyed by its runId, on appScope so leaving the Device screen does not
 * stop them. Each run has a RunControl holding the pause gate and its own rate bucket. A foreground collector pauses and resumes every
 * run; an env collector stops the runs whose network identity changed; a ticker republishes elapsed time while any run is live.
 */
class ToolRunner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val registry: ToolRegistry,
    private val repo: ToolStore,
    private val busy: HostBusyRegistry,
    private val messages: AppMessages,
    private val env: StateFlow<NetworkEnv?>,
    private val foreground: StateFlow<Boolean>,
    private val bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit,
    private val onFinished: suspend (hostId: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (Throwable) -> Unit = {},
    /**
     * Manual runs only (ToolTarget.route set, 3C spec 4.5). The default never runs a Wi-Fi or Ethernet route unbound: with no binder it
     * throws BindFailedException, which ends the run FAILED with the bind wording; AppContainer passes the real binder.
     */
    private val routeBind: suspend (ToolRoute, NetworkEnv, suspend () -> Unit) -> Unit = { route, e, block -> ManualRoute.bind(route, e, { null }) { block() } },
) {
    data class Active(val runId: Long, val hostId: Long?, val toolKey: String, val port: Int, val activeMs: Long,
                      val done: Int, val total: Int?, val found: Int, val errors: Int, val paused: Boolean)
    sealed interface Start { data class Started(val runId: Long) : Start; object HostBusy : Start; object TooMany : Start; object NoNetwork : Start }

    private val runs = ConcurrentHashMap<Long, Run>()          // runId -> live run
    private val startLock = Mutex()
    private val _active = MutableStateFlow<Map<Long, Active>>(emptyMap())
    /** Live runs by runId; use forHost for the run of one device. */
    val active: StateFlow<Map<Long, Active>> = _active
    private var ticker: Job? = null              // guarded by tickerLock
    private val tickerLock = Any()
    /** Guards pause state against registration: a run registered while a foreground change is being applied is never left behind. */
    private val pauseLock = Any()
    private val publishLock = Any()

    /** Test seam: runs inside start() just before the run is registered, so a test can land a foreground or network change there. */
    internal var beforeRegister: suspend () -> Unit = {}
    /** Test seam: runs when the ticker has found no live run and is about to end, so a test can land a start there. */
    internal var tickerEnding: suspend () -> Unit = {}

    init {
        // Each emission applies the current value under pauseLock, and start() applies it to a new run under the same lock, so a
        // foreground flip racing a start always leaves every run matching the latest value.
        scope.launch { foreground.collect { applyForeground(); publish() } }
        scope.launch { env.collect { stopChangedNetwork() } }
    }

    private fun applyForeground() {
        synchronized(pauseLock) {
            val now = clock(); val fg = foreground.value
            runs.values.forEach { if (fg) it.resume(now) else it.pause(now) }
        }
    }

    /** Cancels every run whose start network identity differs from the current one (null env counts as a change). */
    private fun stopChangedNetwork() {
        val key = env.value?.let { NetworkSignature.identityKey(it) }
        runs.values.filter { it.startKey != key }.forEach { it.networkChanged = true; it.job?.cancel() }
    }

    @OptIn(DelicateCoroutinesApi::class)                     // CoroutineStart.ATOMIC, see below
    suspend fun start(target: ToolTarget?, toolKey: String, inputs: ToolInputs): Start {
        repo.ready.await()                                     // a run started at launch is never marked INTERRUPTED (rule 1)
        val tool = registry.byKey(toolKey) ?: throw IllegalArgumentException("no tool registered for $toolKey")   // before any claim or row
        val hostId = target?.hostId
        val run: Run = startLock.withLock {
            val e = env.value ?: return Start.NoNetwork
            if (runs.size >= MAX_ACTIVE_RUNS) return Start.TooMany
            if (hostId != null && !busy.tryClaim(hostId, HostBusyRegistry.Work.TOOL)) return Start.HostBusy   // only a run with a device has a host to claim
            val startedAt = clock()
            val optionsJson = try {
                ToolOptionsJson.encode(target, tool.descriptor, tool.recorded(inputs))
            } catch (t: Throwable) {
                if (hostId != null) busy.release(hostId, HostBusyRegistry.Work.TOOL); throw t
            }
            val runId = try {
                repo.startRun(hostId, toolKey, target?.port ?: 0, startedAt, optionsJson, target?.route?.name, tool.descriptor.name)
            } catch (t: Throwable) {
                if (hostId != null) busy.release(hostId, HostBusyRegistry.Work.TOOL); throw t
            }
            Run(runId, hostId, toolKey, tool.descriptor.name, target?.port ?: 0, startedAt, NetworkSignature.identityKey(e), e, optionsJson).also { r ->
                // Launched, assigned, then registered, all inside the lock (plan review G14), so a Stop or a network change can never
                // find the run in `runs` without its job. ATOMIC, not LAZY: a job cancelled before its first dispatch still runs its
                // body, so the end state is always written and the host released. The body waits on `registered` before the tool
                // starts and before end() runs, so end() can never remove the run ahead of its registration.
                val job = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) { execute(target, tool, inputs, r) }
                r.job = job
                try {
                    beforeRegister()
                    synchronized(pauseLock) { runs[r.runId] = r; if (!foreground.value) r.pause(startedAt) }
                } catch (t: Throwable) {
                    job.cancel(); throw t                      // never registered: the job ends as STOPPED and releases the host
                } finally {
                    r.registered.complete(Unit)
                }
                // A network change between reading env above and registering the run would have been missed by the env collector.
                stopChangedNetwork()
            }
        }
        publish(); ensureTicker()
        return Start.Started(run.runId)
    }

    /** Stops one run by its id; a run that has already ended is ignored. */
    fun stop(runId: Long) { runs[runId]?.job?.cancel() }
    /** Stops the live run on one device, if any. */
    fun stopHost(hostId: Long) { runs.values.firstOrNull { it.hostId == hostId }?.job?.cancel() }

    private suspend fun execute(target: ToolTarget?, tool: ToolModule, inputs: ToolInputs, run: Run) {
        val progress = object : ToolProgress {
            override fun update(done: Int, total: Int?, found: Int, errors: Int) { run.done = done; run.total = total; run.found = found; run.errors = errors }
            override suspend fun finding(f: Finding) {
                run.recorded.incrementAndGet()
                try { repo.addFinding(run.runId, f.kind.name, f.value, f.detail, FindingExtraJson.encode(f)) }
                catch (ce: CancellationException) { throw ce } catch (ex: Exception) { log(ex) }   // logged, the run continues (11.12)
            }
            override suspend fun option(key: String, value: String) { saveOptions(run.withOption(key, value)) }
            override suspend fun option(key: String, value: Int) { saveOptions(run.withOption(key, value)) }
            /** One UPDATE of optionsJson (spec 7.1): for example methodUsed after a calibration switch (plan review G8). */
            private suspend fun saveOptions(json: String) {
                try { repo.setOptions(run.runId, json) } catch (ce: CancellationException) { throw ce } catch (ex: Exception) { log(ex) }
            }
        }
        var status = ToolRunStatus.STOPPED; var summary = ""
        try {
            run.registered.await()
            currentCoroutineContext().ensureActive()           // a run cancelled before its first dispatch never enters the tool
            var result: ToolResult? = null
            val route = target?.route
            // A manual run follows its own route (3C spec 4.5); every other run keeps the scan binding through [bind].
            // A run with no device has no address to bind for; it runs on the system route until the script engine decides its scope.
            if (target == null) result = tool.run(null, inputs, Control(run), progress)
            else if (route == null) bind(target.ip, run.env) { result = tool.run(target, inputs, Control(run), progress) }
            else routeBind(route, run.env) { result = tool.run(target, inputs, Control(run), progress) }
            // A tool that swallows the closed socket's IOException (TlsPeek, TcpSmbTransport) returns FAILED after a Stop:
            // a cancelled run is STOPPED whatever the tool returned.
            currentCoroutineContext().ensureActive()
            val r = result ?: ToolResult(ToolRunStatus.FAILED, ToolCopy.failed("no result"))
            status = r.status; summary = r.summary
        } catch (ce: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // The run itself was not cancelled: the tool let its own timeout (or another cancellation) escape. Spec 4.4 rule 4:
                // any other exception escaping the tool is FAILED, never STOPPED.
                log(ce)
                status = ToolRunStatus.FAILED; summary = ToolCopy.failed(ce.message ?: ce.javaClass.simpleName)
            } else {
                status = ToolRunStatus.STOPPED
                summary = if (run.networkChanged) ToolCopy.NETWORK_CHANGED else ToolCopy.stopped(run.done, run.total, run.found)
            }
        } catch (b: BindFailedException) {
            status = ToolRunStatus.FAILED; summary = ToolCopy.BIND_FAILED
        } catch (t: Throwable) {
            // Exceptions and Errors alike: the row records why, never STOPPED with an empty summary.
            log(t)
            status = ToolRunStatus.FAILED; summary = ToolCopy.failed(t.message ?: t.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { run.registered.await(); end(run, status, summary) }
        }
    }

    private suspend fun end(run: Run, status: ToolRunStatus, summary: String) {
        val at = clock()
        try { repo.finish(run.runId, at, status, summary) } catch (first: Exception) {
            log(first)
            try { repo.finish(run.runId, at, status, summary) } catch (second: Exception) { log(second) }   // left RUNNING: INTERRUPTED next launch
        }
        runs.remove(run.runId, run)
        run.hostId?.let { busy.release(it, HostBusyRegistry.Work.TOOL) }
        publish()
        messages.post(ToolCopy.finished(registry.byKey(run.toolKey)?.descriptor?.name ?: run.toolName.ifEmpty { run.toolKey }, summary), run.hostId)
        if (status == ToolRunStatus.FAILED && run.recorded.get() == 0) return
        val host = run.hostId ?: return                        // a run with no device has nothing to re-identify
        try { onFinished(host) } catch (ex: Exception) { log(ex) }
    }

    /** Compute and assign under one lock, so a slow publisher can never overwrite a newer map (a stale map would pin toolRunActive). */
    private fun publish() {
        synchronized(publishLock) {
            val now = clock(); val fg = foreground.value
            _active.value = runs.values.associate { r ->
                r.runId to Active(r.runId, r.hostId, r.toolKey, r.port, r.activeMs(now), r.done, r.total, r.found, r.errors, !fg)
            }
        }
    }


    /** One ticker while any run is live; it ends itself when the last run ends (rule 8). */
    private fun ensureTicker() {
        synchronized(tickerLock) {
            if (ticker != null) return
            ticker = scope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL_MS)
                    publish()
                    // The emptiness check and clearing `ticker` share tickerLock with ensureTicker, so a start racing the last run's end
                    // either finds this ticker still live or starts a new one; a live run is never left without a ticker.
                    val last = synchronized(tickerLock) { runs.isEmpty().also { if (it) ticker = null } }
                    if (last) { tickerEnding(); break }
                }
            }
        }
    }

    private inner class Control(private val run: Run) : RunControl {
        /**
         * The pause is re-checked on every pass, so a worker parked in its rate wait when ON_STOP lands never sends in the background.
         * After the app flag it also waits for the run's own resume, which empties the bucket first, so a gate can never spend tokens
         * the pause should have discarded, whichever of the gate and the foreground collector wakes first.
         */
        override suspend fun gate() {
            while (true) {
                if (!foreground.value) foreground.first { it }
                if (run.paused.value) run.paused.first { !it }
                if (!foreground.value) continue
                val wait = run.bucket.acquireDelayMs()
                if (wait == 0L) return
                delay(wait)
            }
        }
        override val pauseEpoch: Int get() = run.pauseEpoch
        override fun excused(startEpoch: Int): Boolean = startEpoch != run.pauseEpoch || !foreground.value
    }

    private inner class Run(val runId: Long, val hostId: Long?, val toolKey: String, val toolName: String, val port: Int, startedAt: Long, val startKey: String, val env: NetworkEnv,
                            private var optionsJson: String) {
        @Synchronized fun withOption(key: String, value: String): String { optionsJson = ToolOptionsJson.withOption(optionsJson, key, value); return optionsJson }
        @Synchronized fun withOption(key: String, value: Int): String { optionsJson = ToolOptionsJson.withOption(optionsJson, key, value); return optionsJson }
        val bucket = RateBucket(MAX_REQUESTS_PER_SECOND, RateBucket.BURST, clock)
        val recorded = AtomicInteger(0)
        /** Completed by start() once the run is in `runs` (or failed to get there). */
        val registered = CompletableDeferred<Unit>()
        @Volatile var done = 0; @Volatile var total: Int? = null; @Volatile var found = 0; @Volatile var errors = 0
        @Volatile var pauseEpoch = 0; @Volatile var networkChanged = false
        @Volatile var job: Job? = null
        private var accumulatedMs = 0L
        private var segmentStart = startedAt          // -1 while paused
        private val _paused = MutableStateFlow(false)
        /** This run's own pause state; it turns false only after resume() has emptied the bucket. */
        val paused: StateFlow<Boolean> = _paused
        @Synchronized fun pause(now: Long) { if (segmentStart >= 0) { accumulatedMs += now - segmentStart; segmentStart = -1; pauseEpoch++; _paused.value = true } }
        @Synchronized fun resume(now: Long) { if (segmentStart < 0) { segmentStart = now; bucket.reset(); _paused.value = false } }
        @Synchronized fun activeMs(now: Long): Long = accumulatedMs + if (segmentStart >= 0) now - segmentStart else 0L
    }

    companion object {
        /** The live run on one device, if any. */
        fun Map<Long, Active>.forHost(hostId: Long): Active? = values.firstOrNull { it.hostId == hostId }
        const val MAX_ACTIVE_RUNS = 4; const val KEEP_RUNS_PER_TOOL = 10; const val PROGRESS_INTERVAL_MS = 200L
        const val TOOL_THREADS = 16; const val MAX_REQUESTS_PER_SECOND = 100
    }
}
