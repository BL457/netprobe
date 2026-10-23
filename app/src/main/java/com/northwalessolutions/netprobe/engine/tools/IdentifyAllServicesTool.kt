package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.LadderLimits
import com.northwalessolutions.netprobe.engine.serviceid.LadderResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Identify all services (Director ruling R2): the full ladder over every open port of one host as ONE tool run, under
 * [LadderLimits.THOROUGH] per port. One shared budget of [MAX_PORTS] ports and no wall time: the run ends when every port is done or on Stop. Each port is applied as it finishes, so a Stop keeps the ports
 * already named. The run is a normal tool run, so the host claim, the Scans tab row, the pinned Stop and the badge all come with it.
 */
class IdentifyAllServicesTool(
    /** The open ports stored for the host. */
    private val portsFor: suspend (hostId: Long) -> List<Int>,
    private val identify: suspend (target: ToolTarget, budget: ActiveProbeBudget, limits: LadderLimits, onProbe: (Int) -> Unit) -> Pair<ServiceInfo, LadderResult>,
    private val apply: suspend (hostId: Long, port: Int, info: ServiceInfo) -> Unit,
    /** Settings "Include unsafe Hellos on service identification", read at the start of each run. */
    private val unsafeHellos: () -> Boolean = { false },
    private val newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget(maxPorts = MAX_PORTS, maxMs = NO_WALL_MS, hostWallMs = NO_WALL_MS) },
) : ToolModule {
    override val descriptor = ToolDescriptor(ToolKeys.IDENTIFY_ALL, ToolCopy.IDENTIFY_ALL, ToolModelCopy.IDENTIFY_ALL_DESCRIPTION, ToolIcon.SEARCH,
        ToolCategory.IDENTITY, TargetKind.HOST, emptyList())
    override fun applies(port: Int, service: ServiceInfo) = false
    override fun appliesToHost(host: HostEntity) = true

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (target == null) return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolModelCopy.NO_TARGET))
        control.gate()
        val ports = portsFor(target.hostId).sorted().take(MAX_PORTS)
        val total = ports.size
        val budget = newBudget()
        val limits = LadderLimits.thorough(unsafeHellos())
        var named = 0
        var done = 0
        progress.update(0, total, 0)
        // Up to PARALLEL_PORTS ports at once. A port is applied and reported the moment its ladder ends, one line per port, under a lock
        // and shielded from cancellation, so a Stop keeps (and reports) every port that finished before it.
        val report = Mutex()
        coroutineScope {
            val permits = Semaphore(PARALLEL_PORTS)
            for (port in ports) {
                launch {
                    permits.withPermit {
                        control.gate()
                        currentCoroutineContext().ensureActive()
                        val (info, ladder) = identify(target.copy(port = port), budget, limits) { }
                        currentCoroutineContext().ensureActive()   // a Stop seen while the ladder ran: this port is not applied
                        withContext(NonCancellable) {
                            if (ladder.info != null) apply(target.hostId, port, info)
                            val recognised = info.confidence == Confidence.CONFIRMED
                            report.withLock {
                                if (recognised) named++
                                done++
                                progress.finding(Finding(FindingKind.PROBE, ToolCopy.identifyAllLine(port, recognised, info.name, info.product, info.version)))
                                progress.update(done, total, named)
                            }
                        }
                    }
                }
            }
        }
        val summary = if (named == 0) ToolCopy.IDENTIFY_ALL_NONE else ToolCopy.identifyAllSummary(named, total)
        return ToolResult(ToolRunStatus.DONE, summary)
    }

    companion object {
        const val MAX_PORTS = 64
        /** Ports of the one host identified at the same time. */
        const val PARALLEL_PORTS = 4
        const val NO_WALL_MS = LadderLimits.NO_WALL_MS
    }
}
