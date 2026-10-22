package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.LadderResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbes
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

/**
 * Identify service (3E spec 8): the full ladder on one port with a fresh budget of one port and 20 s, whatever the Settings switch says,
 * one PROBE finding per probe run and one STACK finding per stack rule that hit. The result reaches the port row through [apply]
 * (PortRepository.applyIdentify), which merges it as a scan does. The ladder never consults the host claim, so the tool's own TOOL claim
 * does not stop it.
 */
class IdentifyServiceTool(
    /** Runs the ladder for the target; the last argument is told the probe count after each probe. */
    private val identify: suspend (target: ToolTarget, budget: ActiveProbeBudget, onProbe: (Int) -> Unit) -> Pair<ServiceInfo, LadderResult>,
    private val apply: suspend (hostId: Long, port: Int, info: ServiceInfo) -> Unit,
    private val newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget(maxPorts = 1, hostWallMs = HOST_WALL_MS) },
) : Tool {
    override val id = ToolId.IDENTIFY
    override val name = ToolCopy.IDENTIFY
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.IDENTIFY, port, service)

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        control.gate()
        val total = ServiceProbes.MAX_PROBES_PER_PORT
        progress.update(0, total, 0)
        val (info, ladder) = identify(target, newBudget()) { n -> progress.update(n, maxOf(n, total), 0) }
        for (t in ladder.trace) progress.finding(Finding(FindingKind.PROBE, ToolCopy.probeLine(t.id, t.bytes, t.match)))
        for (h in ladder.stackHits) progress.finding(Finding(FindingKind.STACK, ToolCopy.stackLine(h.rule.id, h.stack, h.version)))
        // Only a port that answered something is written: a closed port must not become an open row.
        if (ladder.info != null) apply(target.hostId, target.port, info)
        val n = ladder.trace.size
        val summary = if (info.confidence == Confidence.CONFIRMED) ToolCopy.identifySummary(info.name, info.product, info.version, n) else ToolCopy.identifyNone(n)
        progress.update(n, maxOf(n, total), n + ladder.stackHits.size)
        return ToolResult(ToolRunStatus.DONE, summary)
    }

    companion object { const val HOST_WALL_MS = 20_000L }
}
