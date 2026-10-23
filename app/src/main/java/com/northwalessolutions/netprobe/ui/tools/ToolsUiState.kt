package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.runtime.Immutable
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.ui.home.RelativeTime

// @Immutable: built fresh by the view model and never mutated, so Compose compares them by equals() and skips unchanged blocks.
@Immutable data class ToolChip(val toolId: String, val port: Int, val label: String, val description: String)
@Immutable data class ToolBlock(val toolId: String, val name: String, val hasRun: Boolean, val history: String, val chips: List<ToolChip>)
/** One entry of a port sheet's "Run tool" list: the registry key and the name shown. */
@Immutable data class PortTool(val key: String, val name: String)
@Immutable data class ToolsUiState(val available: Int, val headerHistory: String, val blocks: List<ToolBlock>)
/** The pinned bar during a run (spec 8.2); [fraction] null draws an indeterminate line. */
@Immutable data class ToolRunBar(val label: String, val progress: String, val fraction: Float?)
/** [label] is a script's own kind for an OTHER finding (ruling c); built-in findings have none. */
@Immutable data class FindingRow(val id: Long, val value: String, val detail: String?, val url: String?, val label: String? = null)
@Immutable data class ToolResultsState(val runId: Long, val title: String, val statusLine: String, val summary: String, val index: Int, val count: Int,
                            val findings: List<FindingRow>, val active: Boolean,
                            /** "via Wi-Fi", "via Ethernet" or "via VPN or other route" for a manual run (3C spec 4.5); null shows nothing. */
                            val route: String? = null)
@Immutable data class DetailToolsState(val tools: ToolsUiState? = null, val bar: ToolRunBar? = null, val busy: HostBusyRegistry.Work? = null,
                            val results: ToolResultsState? = null, val appMessage: AppMessage? = null,
                            /** Remove device is refused only while a tool run holds the host (spec 4.3); computed by DetailBusyRules. */
                            val removeAllowed: Boolean = true,
                            /** The tool running on this host and its port (0 for a whole-device tool); the port row shows Identifying for its own port. */
                            val runTool: String? = null, val runPort: Int = 0,
                            /** The tools each port's sheet offers (the registry's applicable PORT tools), by port. */
                            val portTools: Map<Int, List<PortTool>> = emptyMap())

object ToolsUiModel {
    private const val MS_PER_S = 1000L
    /** A run's tool name: the registry's while the tool is installed, else the name stored with the run, else the built-in name or the key. */
    fun nameOf(registry: ToolRegistry, run: ToolRunEntity): String =
        registry.byKey(run.toolId)?.descriptor?.name ?: run.toolName.ifEmpty { ToolCopy.toolName(run.toolId) }
    fun nameOf(registry: ToolRegistry, toolKey: String): String = registry.byKey(toolKey)?.descriptor?.name ?: ToolCopy.toolName(toolKey)

    /**
     * The Tools block (spec 8.1), or null when no tool applies to the host. [runs] are newest first (the DAO order). Blocks follow the
     * registry order: a PORT tool gets a chip per applicable port, a HOST tool one chip (SNMP on UDP 161, others on all open ports).
     * The Identify tools are not offered here (they are manual tools and a port row action, 3E spec O1).
     */
    fun of(registry: ToolRegistry, host: HostEntity, ports: List<Pair<Int, ServiceInfo>>, runs: List<ToolRunEntity>, now: Long,
           formatDate: (Long) -> String = RelativeTime::defaultDate): ToolsUiState? {
        val blocks = registry.all.value.mapNotNull { module ->
            val d = module.descriptor
            if (!ToolApplicability.onDevicePage(d.key)) return@mapNotNull null
            val snmp = d.key == ToolKeys.SNMP_WALK
            val targets = when (d.target) {
                TargetKind.PORT -> ports.filter { (p, s) -> module.applies(p, s) }.map { it.first }.sorted()
                TargetKind.HOST -> if (module.appliesToHost(host)) listOf(if (snmp) ToolApplicability.SNMP_PORT else 0) else emptyList()
                TargetKind.NONE -> emptyList()
            }
            if (targets.isEmpty()) return@mapNotNull null
            val newest = runs.firstOrNull { it.toolId == d.key }
            ToolBlock(d.key, d.name, newest != null,
                newest?.let { ToolCopy.blockHistory(RelativeTime.of(it.startedAt, now, formatDate), ToolCopy.portLabel(it.port, snmp), summaryOf(it)) } ?: ToolCopy.NOT_RUN_YET,
                targets.map { p -> val label = ToolCopy.portLabel(p, snmp); ToolChip(d.key, p, label, ToolCopy.chipDescription(d.name, label)) })
        }
        if (blocks.isEmpty()) return null
        val newest = runs.firstOrNull { registry.byKey(it.toolId) != null }
        val header = newest?.let { r ->
            ToolCopy.rowHistory(RelativeTime.of(r.startedAt, now, formatDate), nameOf(registry, r), ToolCopy.portLabel(r.port, r.toolId == ToolKeys.SNMP_WALK), summaryOf(r))
        } ?: ToolCopy.NO_TOOLS_RUN
        return ToolsUiState(blocks.size, header, blocks)
    }
    fun summaryOf(r: ToolRunEntity): String = r.summary.ifEmpty { ToolCopy.statusLabel(r.status) }
    fun bar(a: ToolRunner.Active, registry: ToolRegistry): ToolRunBar {
        val snmp = a.toolKey == ToolKeys.SNMP_WALK
        val label = ToolCopy.pinnedLabel(nameOf(registry, a.toolKey), ToolCopy.portLabel(a.port, snmp))
        val started = a.total != null || a.done > 0 || a.found > 0
        val progress = if (!started) ToolCopy.STARTING
                       else if (a.toolKey == ToolKeys.IDENTIFY && a.total != null) ToolCopy.identifyProgress(a.done, a.total)
                       else if (a.toolKey == ToolKeys.IDENTIFY_ALL && a.total != null) ToolCopy.identifyAllProgress(a.done, a.total)
                       else ToolCopy.progress(a.done, a.total, a.found, a.errors, a.activeMs / MS_PER_S)
        val fraction = a.total?.let { t -> if (t <= 0) 0f else (a.done.toFloat() / t).coerceIn(0f, 1f) }
        return ToolRunBar(label, progress, fraction)
    }
    fun busyMessage(work: HostBusyRegistry.Work): String = when (work) {
        HostBusyRegistry.Work.TOOL -> ToolCopy.BUSY_TOOL
        HostBusyRegistry.Work.PORT_SCAN -> ToolCopy.BUSY_PORT_SCAN
    }
}

object ToolResultsModel {
    /** Rows are mapped once per findings change by the caller (Detail), not on every progress tick. */
    fun rows(findings: List<ToolFindingEntity>): List<FindingRow> = findings.map { f ->
        val extra = FindingExtra.decode(f.extra)
        FindingRow(f.id, f.value, f.detail, extra["url"], FindingLabel.of(f.kind, extra))
    }

    /** The results sheet (spec 8.5) for [run], positioned in [sameTool] (newest first, this host and tool). */
    fun of(registry: ToolRegistry, run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String?, findings: List<ToolFindingEntity>, active: ToolRunner.Active?, now: Long,
           formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState = ofRows(registry, run, sameTool, ip, rows(findings), active, now, formatDate)

    /** [ip] is null for a run with no device; the title then reads "No device". */
    fun ofRows(registry: ToolRegistry, run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String?, rows: List<FindingRow>, active: ToolRunner.Active?, now: Long,
               formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState {
        val live = active?.takeIf { it.runId == run.id }
        val status = if (live != null) ToolCopy.resultsRunning(ToolsUiModel.bar(live, registry).progress)
                     else ToolCopy.resultsStatus(ToolCopy.statusLabel(run.status), RelativeTime.of(run.startedAt, now, formatDate), ToolRunOptions.parse(run.optionsJson).serverName)
        val index = sameTool.indexOfFirst { it.id == run.id }.let { if (it < 0) 1 else it + 1 }
        return ToolResultsState(run.id, ToolCopy.resultsTitle(ToolsUiModel.nameOf(registry, run), ip, run.port), status, run.summary, index, maxOf(sameTool.size, 1), rows, live != null,
            route = ToolCopy.routeLine(ToolRoute.parse(run.route)))
    }
}
