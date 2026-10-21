package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Owns one network-bound scan job (Home discovery, Quick scan all, a Detail port scan). Cancels it when the network goes
 * away or its identity (gateway IP + prefix) changes, and turns a thrown error into a message instead of a crash.
 * A BSSID change (roaming between access points) is the same network and does not stop the scan.
 */
class ScanController(private val scope: CoroutineScope, private val env: StateFlow<NetworkEnv?>, private val runDiscovery: suspend (NetworkEnv) -> Unit) {
    data class State(val scanning: Boolean = false, val message: String? = null)
    private val _state = MutableStateFlow(State()); val state: StateFlow<State> = _state
    private var job: Job? = null
    private var watcher: Job? = null
    /** Bumped on every start and stop, so a cancelled job's cleanup can never touch a newer scan's state. */
    private var generation = 0L

    /**
     * Starts [work] (default: the constructor's runDiscovery) unless a job is already running. [onComplete] runs once when the job has
     * completed for any reason, including a cancel before its first dispatch, which no `finally` inside [work] can see (PortScans needs it).
     * It is not called when nothing was started (no network, or a job already running).
     */
    fun start(onComplete: () -> Unit = {}, work: suspend (NetworkEnv) -> Unit = runDiscovery) {
        val e = env.value ?: run { _state.value = State(false, NO_NETWORK); return }
        if (job?.isActive == true) return
        val gen = ++generation
        val prev = job   // may still be cancelling: its finally (lock release, status writes) must finish before this run starts
        val key = NetworkSignature.identityKey(e)
        _state.value = State(scanning = true)
        // LAZY so both handles exist before the body can run (viewModelScope dispatches immediately).
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                prev?.join()
                work(e)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                if (gen == generation) _state.value = State(false, failureMessage(t))
            } finally {
                if (gen == generation && _state.value.scanning) _state.update { it.copy(scanning = false) }
            }
        }
        val w = scope.launch(start = CoroutineStart.LAZY) {
            // No drop(1): if the env changed between start() and this collector starting, the current value already differs.
            env.first { it == null || NetworkSignature.identityKey(it) != key }
            if (gen == generation) { j.cancel(); _state.value = State(false, NETWORK_CHANGED) }
        }
        j.invokeOnCompletion { w.cancel(); onComplete() }
        job = j; watcher = w
        w.start(); j.start()
    }
    /** Stop, then wait until the cancelled job (and its finally) has finished: Remove device must not race a port write (spec 5.3 step 1). */
    suspend fun stopAndJoin() {
        val running = job
        stop()
        running?.join()
    }
    val isRunning: Boolean get() = job?.isActive == true
    fun stop() { generation++; job?.cancel(); watcher?.cancel(); _state.value = State(false, null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** The failure wording, shared with the Scans summaries (3D spec 3.3). */
        fun failureMessage(t: Throwable): String = "Scan failed: ${t.message ?: t.javaClass.simpleName}"
        const val NO_NETWORK = "Connect to Wi-Fi or Ethernet to scan"
        const val NETWORK_CHANGED = "Network changed, scan stopped"
    }
}
