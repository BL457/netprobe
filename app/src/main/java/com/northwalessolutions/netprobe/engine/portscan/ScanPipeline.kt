package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

/** What a pipeline run found: how many ports were open, and what identify said about each. */
class PipelineResult(val opened: Int, val found: Map<Int, ServiceInfo>)

/**
 * The sweep and the identify of one Device port scan, with its collaborators passed in so a JVM test can drive it. [gate] pauses the sweep
 * before each probe and the identify before each banner connection (3D spec 3.3: no new probe starts in the background), so a found port
 * waiting in the queue does not keep connecting to the device while NetProbe is backgrounded.
 */
class ScanPipeline(
    private val engine: PortScanEngine,
    private val gate: suspend () -> Unit,
    private val options: ScanOptions,
    private val identify: suspend (port: Int) -> ServiceInfo,
    private val store: suspend (port: Int, info: ServiceInfo) -> Unit,
    private val maxParallelIdentifies: Int = ServiceIdentifier.MAX_PARALLEL_IDENTIFIES,
) {
    suspend fun run(ip: InetAddress, profile: ScanProfile, sink: ScanSink): PipelineResult {
        val found = HashMap<Int, ServiceInfo>()
        var opened = 0
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(maxParallelIdentifies)
            engine.scan(ip, profile, options, gate = gate, onProgress = sink::progress)
                .filter { it.state == PortState.OPEN }.collect { r ->
                    opened++
                    sink.identify { it.copy(pending = it.pending + 1) }
                    launch(start = CoroutineStart.ATOMIC) {
                        try {
                            sem.withPermit {
                                gate()      // checked at the moment the connection would start, so a queued identify waits in the background
                                val info = identify(r.port)
                                synchronized(found) { found[r.port] = info }
                                store(r.port, info)
                            }
                        } finally { sink.identify { IdentifyCount(it.pending - 1, it.done + 1) } }
                    }
                }
        }
        return PipelineResult(opened, found)
    }
}
