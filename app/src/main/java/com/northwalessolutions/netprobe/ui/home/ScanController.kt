package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Owns the discovery job for the Home screen. Cancels it when the network goes away or changes signature,
 * and turns a thrown discovery error into a message instead of a crash.
 */
class ScanController(private val scope: CoroutineScope, private val env: StateFlow<NetworkEnv?>, private val runDiscovery: suspend (NetworkEnv) -> Unit) {
    data class State(val scanning: Boolean = false, val message: String? = null)
    private val _state = MutableStateFlow(State()); val state: StateFlow<State> = _state
    private var job: Job? = null
    private var watcher: Job? = null
    /** Bumped on every start and stop, so a cancelled job's cleanup can never touch a newer scan's state. */
    private var generation = 0L

    fun start() {
        val e = env.value ?: run { _state.value = State(false, NO_NETWORK); return }
        if (job?.isActive == true) return
        val gen = ++generation
        val sig = NetworkSignature.of(e)
        _state.value = State(scanning = true)
        // LAZY so both handles exist before the body can run (viewModelScope dispatches immediately).
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runDiscovery(e)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                if (gen == generation) _state.value = State(false, "Scan failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                if (gen == generation && _state.value.scanning) _state.update { it.copy(scanning = false) }
            }
        }
        val w = scope.launch(start = CoroutineStart.LAZY) {
            // No drop(1): if the env changed between start() and this collector starting, the current value already differs.
            env.first { it == null || NetworkSignature.of(it) != sig }
            if (gen == generation) { j.cancel(); _state.value = State(false, NETWORK_CHANGED) }
        }
        j.invokeOnCompletion { w.cancel() }
        job = j; watcher = w
        w.start(); j.start()
    }
    fun stop() { generation++; job?.cancel(); watcher?.cancel(); _state.value = State(false, null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val NO_NETWORK = "Connect to Wi-Fi or Ethernet to scan"
        const val NETWORK_CHANGED = "Network changed, scan stopped"
    }
}
