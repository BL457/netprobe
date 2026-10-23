package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ActiveProbeBudget
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.LadderLimits
import com.northwalessolutions.netprobe.engine.serviceid.LadderResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolModelCopy

/**
 * Identify service (3E spec 8): the full ladder on one port with a fresh budget of one port and no time limit, whatever the Settings switch says,
 * under [LadderLimits.THOROUGH] (every hello in the file, safe then careful, no connection cap and no wall; it ends on a match, on every hello tried or on Stop),
 * one PROBE finding per probe run and one STACK finding per stack rule that hit. The result reaches the port row through [apply]
 * (PortRepository.applyIdentify), which merges it as a scan does. The ladder never consults the host claim, so the tool's own TOOL claim
 * does not stop it.
 */
class IdentifyServiceTool(
    /** Runs the ladder for the target; the last argument is told the probe count after each probe. */
    private val identify: suspend (target: ToolTarget, budget: ActiveProbeBudget, limits: LadderLimits, onProbe: (Int) -> Unit) -> Pair<ServiceInfo, LadderResult>,
    /** How many probes the ladder plans for a port under the limits, the "of m" of the progress. */
    private val plannedProbes: (port: Int, limits: LadderLimits) -> Int,
    private val apply: suspend (hostId: Long, port: Int, info: ServiceInfo) -> Unit,
    /** Settings "Include unsafe Hellos on service identification", read at the start of each run. */
    private val unsafeHellos: () -> Boolean = { false },
    private val newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget(maxPorts = 1, maxMs = NO_WALL_MS, hostWallMs = NO_WALL_MS) },
) : ToolModule {
    override val descriptor = ToolDescriptor(ToolKeys.IDENTIFY, ToolCopy.IDENTIFY, ToolModelCopy.IDENTIFY_DESCRIPTION, ToolIcon.FINGERPRINT,
        ToolCategory.IDENTITY, TargetKind.PORT, emptyList())
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(descriptor.key, port, service)

    override suspend fun run(target: ToolTarget?, inputs: ToolInputs, control: RunControl, progress: ToolProgress): ToolResult {
        if (target == null) return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolModelCopy.NO_TARGET))
        control.gate()
        val limits = LadderLimits.thorough(unsafeHellos())
        val total = plannedProbes(target.port, limits)
        progress.update(0, total, 0)
        val (info, ladder) = identify(target, newBudget(), limits) { n -> progress.update(n, maxOf(n, total), 0) }
        for (t in ladder.trace) progress.finding(Finding(FindingKind.PROBE, ToolCopy.probeLine(t.id, t.bytes, t.match)))
        for (h in ladder.stackHits) progress.finding(Finding(FindingKind.STACK, ToolCopy.stackLine(h.rule.id, h.stack, h.version)))
        // Only a port that answered something is written: a closed port must not become an open row.
        if (ladder.info != null) apply(target.hostId, target.port, info)
        val n = ladder.trace.size
        val summary = if (info.confidence == Confidence.CONFIRMED) ToolCopy.identifySummary(info.name, info.product, info.version, n) else ToolCopy.identifyNone(n)
        progress.update(n, n, n + ladder.stackHits.size)
        return ToolResult(ToolRunStatus.DONE, summary)
    }

    companion object {
        const val NO_WALL_MS = LadderLimits.NO_WALL_MS
    }
}
