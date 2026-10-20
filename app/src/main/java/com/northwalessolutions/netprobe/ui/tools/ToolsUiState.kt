package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.wordlists.WordlistMeta

data class ToolChip(val toolId: ToolId, val port: Int, val label: String, val description: String)
data class ToolBlock(val toolId: ToolId, val name: String, val hasRun: Boolean, val history: String, val chips: List<ToolChip>)
data class ToolsUiState(val available: Int, val headerHistory: String, val blocks: List<ToolBlock>)
/** The pinned bar during a run (spec 8.2); [fraction] null draws an indeterminate line. */
data class ToolRunBar(val label: String, val progress: String, val fraction: Float?)
data class FindingRow(val id: Long, val value: String, val detail: String?, val url: String?)
data class ToolResultsState(val runId: Long, val title: String, val statusLine: String, val summary: String, val index: Int, val count: Int,
                            val findings: List<FindingRow>, val active: Boolean)
data class DirBruteSheetState(val port: Int, val targetUrl: String, val lists: List<WordlistMeta>, val initial: DirBruteOptions, val busyReason: String?)
data class DetailToolsState(val tools: ToolsUiState? = null, val bar: ToolRunBar? = null, val busy: HostBusyRegistry.Work? = null,
                            val results: ToolResultsState? = null, val dirBrute: DirBruteSheetState? = null, val appMessage: AppMessage? = null,
                            /** Remove device is refused only while a tool run holds the host (spec 4.3); computed by DetailBusyRules. */
                            val removeAllowed: Boolean = true)

object ToolsUiModel {
    private const val MS_PER_S = 1000L
    /** The Tools block (spec 8.1), or null when no tool applies to the host. [runs] are newest first (the DAO order). */
    fun of(host: HostEntity, ports: List<Pair<Int, ServiceInfo>>, runs: List<ToolRunEntity>, now: Long, formatDate: (Long) -> String = RelativeTime::defaultDate): ToolsUiState? {
        val blocks = ToolId.entries.mapNotNull { id ->
            val snmp = id == ToolId.SNMP_WALK
            val targets = if (snmp) listOfNotNull(ToolApplicability.SNMP_PORT.takeIf { ToolApplicability.snmp(host) })
                          else ports.filter { (p, s) -> ToolApplicability.appliesTo(id, p, s) }.map { it.first }.sorted()
            if (targets.isEmpty()) return@mapNotNull null
            val name = ToolCopy.toolName(id)
            val newest = runs.firstOrNull { it.toolId == id.key }
            ToolBlock(id, name, newest != null,
                newest?.let { ToolCopy.blockHistory(RelativeTime.of(it.startedAt, now, formatDate), ToolCopy.portLabel(it.port, snmp), summaryOf(it)) } ?: ToolCopy.NOT_RUN_YET,
                targets.map { p -> val label = ToolCopy.portLabel(p, snmp); ToolChip(id, p, label, ToolCopy.chipDescription(name, label)) })
        }
        if (blocks.isEmpty()) return null
        val newest = runs.firstOrNull { ToolId.byKey(it.toolId) != null }
        val header = newest?.let { r ->
            val id = ToolId.byKey(r.toolId)!!
            ToolCopy.rowHistory(RelativeTime.of(r.startedAt, now, formatDate), ToolCopy.toolName(id), ToolCopy.portLabel(r.port, id == ToolId.SNMP_WALK), summaryOf(r))
        } ?: ToolCopy.NO_TOOLS_RUN
        return ToolsUiState(blocks.size, header, blocks)
    }
    fun summaryOf(r: ToolRunEntity): String = r.summary.ifEmpty { ToolCopy.statusLabel(r.status) }
    fun bar(a: ToolRunner.Active): ToolRunBar {
        val snmp = a.toolId == ToolId.SNMP_WALK
        val label = ToolCopy.pinnedLabel(ToolCopy.toolName(a.toolId), ToolCopy.portLabel(a.port, snmp))
        val started = a.total != null || a.done > 0 || a.found > 0
        val progress = if (started) ToolCopy.progress(a.done, a.total, a.found, a.errors, a.activeMs / MS_PER_S) else ToolCopy.STARTING
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
    fun rows(findings: List<ToolFindingEntity>): List<FindingRow> = findings.map { FindingRow(it.id, it.value, it.detail, FindingExtra.decode(it.extra)["url"]) }

    /** The results sheet (spec 8.5) for [run], positioned in [sameTool] (newest first, this host and tool). */
    fun of(run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String, findings: List<ToolFindingEntity>, active: ToolRunner.Active?, now: Long,
           formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState = ofRows(run, sameTool, ip, rows(findings), active, now, formatDate)

    fun ofRows(run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String, rows: List<FindingRow>, active: ToolRunner.Active?, now: Long,
               formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState {
        val tool = ToolId.byKey(run.toolId) ?: ToolId.DIR_BRUTEFORCE
        val live = active?.takeIf { it.runId == run.id }
        val status = if (live != null) ToolCopy.resultsRunning(ToolsUiModel.bar(live).progress)
                     else ToolCopy.resultsStatus(ToolCopy.statusLabel(run.status), RelativeTime.of(run.startedAt, now, formatDate), ToolRunOptions.parse(run.optionsJson).serverName)
        val index = sameTool.indexOfFirst { it.id == run.id }.let { if (it < 0) 1 else it + 1 }
        return ToolResultsState(run.id, ToolCopy.resultsTitle(ToolCopy.toolName(tool), ip, run.port), status, run.summary, index, maxOf(sameTool.size, 1), rows, live != null)
    }
}
