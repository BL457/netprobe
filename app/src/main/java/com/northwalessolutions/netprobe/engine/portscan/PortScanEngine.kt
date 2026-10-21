package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.Probe
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import java.net.InetAddress

enum class PortState { OPEN, CLOSED, FILTERED }
data class PortResult(val port: Int, val state: PortState, val rttMs: Int?)
data class ScanOptions(val concurrency: Int = 128, val connectTimeoutMs: Int = 600)
data class ScanProgress(val done: Int, val total: Int, val open: Int, val errors: Int)

class PortScanEngine(
    private val dispatcherFactory: (Int) -> CoroutineDispatcher = { Dispatchers.IO.limitedParallelism(it) },
) {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    /**
     * [gate] runs before each probe starts (3D spec 3.3): a PortScans scan passes ForegroundGate.await, so nothing new is probed while
     * NetProbe is in the background; probes already in flight finish within one connect timeout. [onProgress] gets this scan's own
     * counts at the start and after every probe, so two scans never read each other's progress; callers must store and publish
     * sparingly, it runs once per port.
     */
    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions(), gate: suspend () -> Unit = {},
             onProgress: (ScanProgress) -> Unit = {}): Flow<PortResult> = channelFlow {
        val ports = profile.ports
        var done = 0; var open = 0; var errors = 0
        val first = ScanProgress(0, ports.size, 0, 0)
        _progress.value = first; onProgress(first)
        val dispatcher = dispatcherFactory(options.concurrency)
        val sem = Semaphore(options.concurrency)
        coroutineScope {
            for (port in ports) {
                gate()
                sem.acquire()
                launch(dispatcher) {
                    try {
                        val r = when (val o = Probe.connect(ip, port, options.connectTimeoutMs)) {
                            is Probe.Outcome.Connected -> PortResult(port, PortState.OPEN, o.rttMs)
                            Probe.Outcome.Refused -> PortResult(port, PortState.CLOSED, null)
                            Probe.Outcome.Timeout, Probe.Outcome.Unreachable -> PortResult(port, PortState.FILTERED, null)
                        }
                        send(r)
                        synchronized(this@PortScanEngine) {
                            done++; if (r.state == PortState.OPEN) open++
                            val p = ScanProgress(done, ports.size, open, errors)
                            _progress.value = p; onProgress(p)
                        }
                    } catch (e: CancellationException) { throw e
                    } catch (e: BindFailedException) { throw e      // the network cannot be used: fail the scan, do not report ports as closed
                    } catch (e: Exception) {
                        synchronized(this@PortScanEngine) {
                            done++; errors++
                            val p = ScanProgress(done, ports.size, open, errors)
                            _progress.value = p; onProgress(p)
                        }
                    } finally { sem.release() }
                }
            }
        }
    }
}
