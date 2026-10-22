package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.LadderResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Identify all services (Director ruling R2): the full ladder over every open port of one host as ONE tool run. One shared budget of
 * [MAX_PORTS] ports and [WALL_MS] of wall time, the same as a scan's. Each port is applied as it finishes, so a Stop keeps the ports
 * already named. The run is a normal tool run, so the host claim, the Scans tab row, the pinned Stop and the badge all come with it.
 */
class IdentifyAllServicesTool(
    /** The open ports stored for the host. */
    private val portsFor: suspend (hostId: Long) -> List<Int>,
    private val identify: suspend (target: ToolTarget, budget: ActiveProbeBudget, onProbe: (Int) -> Unit) -> Pair<ServiceInfo, LadderResult>,
    private val apply: suspend (hostId: Long, port: Int, info: ServiceInfo) -> Unit,
    private val newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget(maxPorts = MAX_PORTS, maxMs = WALL_MS, hostWallMs = WALL_MS) },
) : Tool {
    override val id = ToolId.IDENTIFY_ALL
    override val name = ToolCopy.IDENTIFY_ALL
    override fun applies(port: Int, service: ServiceInfo) = false
    override fun appliesToHost(host: HostEntity) = true

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        control.gate()
        val ports = portsFor(target.hostId).sorted().take(MAX_PORTS)
        val total = ports.size
        val budget = newBudget()
        var named = 0
        progress.update(0, total, 0)
        for ((i, port) in ports.withIndex()) {
            control.gate()
            currentCoroutineContext().ensureActive()
            val (info, ladder) = identify(target.copy(port = port), budget) { }
            if (ladder.info != null) apply(target.hostId, port, info)
            val recognised = info.confidence == Confidence.CONFIRMED
            if (recognised) named++
            progress.finding(Finding(FindingKind.PROBE, ToolCopy.identifyAllLine(port, recognised, info.name, info.product, info.version)))
            progress.update(i + 1, total, named)
        }
        val summary = if (named == 0) ToolCopy.IDENTIFY_ALL_NONE else ToolCopy.identifyAllSummary(named, total)
        return ToolResult(ToolRunStatus.DONE, summary)
    }

    companion object { const val MAX_PORTS = 64; const val WALL_MS = 60_000L }
}
