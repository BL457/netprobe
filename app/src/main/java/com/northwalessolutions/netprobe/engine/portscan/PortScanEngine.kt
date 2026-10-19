package com.northwalessolutions.netprobe.engine.portscan

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

    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions()): Flow<PortResult> = channelFlow {
        val ports = profile.ports
        var done = 0; var open = 0; var errors = 0
        _progress.value = ScanProgress(0, ports.size, 0, 0)
        val dispatcher = dispatcherFactory(options.concurrency)
        val sem = Semaphore(options.concurrency)
        coroutineScope {
            for (port in ports) {
                sem.acquire()
                launch(dispatcher) {
                    try {
                        val r = when (val o = Probe.connect(ip, port, options.connectTimeoutMs)) {
                            is Probe.Outcome.Connected -> PortResult(port, PortState.OPEN, o.rttMs)
                            Probe.Outcome.Refused -> PortResult(port, PortState.CLOSED, null)
                            Probe.Outcome.Timeout, Probe.Outcome.Unreachable -> PortResult(port, PortState.FILTERED, null)
                        }
                        send(r)
                        synchronized(this@PortScanEngine) { done++; if (r.state == PortState.OPEN) open++; _progress.value = ScanProgress(done, ports.size, open, errors) }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        synchronized(this@PortScanEngine) { done++; errors++; _progress.value = ScanProgress(done, ports.size, open, errors) }
                    } finally { sem.release() }
                }
            }
        }
    }
}
