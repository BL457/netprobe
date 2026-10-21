package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.detail.detailProgress
import com.northwalessolutions.netprobe.ui.home.ProgressLine
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy

/** Everything the Scans list is built from, gathered by the view model (3D spec 3.4). */
data class ScansInput(
    val live: List<LiveWork.Live>, val active: Map<Long, ToolRunner.Active>, val ended: List<LiveWork.Ended>, val history: List<NetworkRun>,
    val hostNames: Map<Long, String>, val currentNetworkId: Long?, val now: Long, val clearedAt: Long, val forgottenNetworkId: Long?,
    val removedHostIds: Set<Long>, val radarStatus: DiscoveryStatus? = null, val portStates: Map<Long, PortScanState> = emptyMap(),
    val formatDate: (Long) -> String = RelativeTime::defaultDate,
)

sealed interface ScansSnack {
    val key: String
    data class Undo(val text: String) : ScansSnack { override val key: String = "undo:$text" }
    object Cleared : ScansSnack { override val key: String = "cleared" }
    data class App(val id: Long, val text: String) : ScansSnack { override val key: String = "app:$id" }
}

object ScansRules {
    const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000
    const val RECENT_LIMIT = LiveWork.RECENT_LIMIT
    /** recentForNetwork orders by startedAt and includes RUNNING rows, which build() drops; so up to MAX_ACTIVE_RUNS extra rows are fetched. */
    const val HISTORY_FETCH = RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS
    private const val MS_PER_S = 1000L

    fun build(i: ScansInput): ScansList {
        // Tool history belongs to the current network (spec 3.4 rule 7); with none, only the in-memory outcomes remain.
        val history = if (i.currentNetworkId == null) emptyList() else i.history
        val ipByHost = history.associate { it.run.hostId to it.ip }
        val c = Ctx(i, history, ipByHost)
        val running = (i.live.map { runningLive(it, c) } + i.active.values.map { runningTool(it, c) })
            .filterNot { dropped(it, i) }
            .sortedWith(compareByDescending<ScansItem> { it.startedAt }.thenBy { it.key })
        val finished = i.ended.map { endedLive(it, c) } + history.mapNotNull { historyItem(it, c) }
        val recent = finished.filterNot { dropped(it, i) }.filter { inWindow(it, i) }
            .sortedWith(compareByDescending<ScansItem> { it.sortTime }.thenBy { it.key })
            .take(RECENT_LIMIT)
        return ScansList(running, recent)
    }

    /** The bar badge (spec 3.7): the number of running rows. */
    fun badge(running: List<ScansItem>): Int = running.size
    /** The same number from the raw sources, for the container-level flow that needs no history or names. */
    fun runningCount(live: List<LiveWork.Live>, active: Map<Long, ToolRunner.Active>): Int = live.size + active.size

    /** One snackbar at a time: the UndoStore Undo first (spec 3.8), then the Clear history prompt, then an end message. */
    fun snack(undoText: String?, clearPrompt: Boolean, app: AppMessage?): ScansSnack? = when {
        undoText != null -> ScansSnack.Undo(undoText)
        clearPrompt -> ScansSnack.Cleared
        app != null -> ScansSnack.App(app.id, app.text)
        else -> null
    }

    private class Ctx(val i: ScansInput, val history: List<NetworkRun>, val ipByHost: Map<Long, String>) {
        fun host(hostId: Long?, fallback: String?): String =
            (hostId?.let { i.hostNames[it] }) ?: (hostId?.let { ipByHost[it] }) ?: fallback ?: ScansCopy.UNKNOWN_HOST
    }

    private fun dropped(item: ScansItem, i: ScansInput): Boolean =
        (item.networkId != null && item.networkId == i.forgottenNetworkId) || (item.hostId != null && item.hostId in i.removedHostIds)

    private fun inWindow(item: ScansItem, i: ScansInput): Boolean = i.now - item.sortTime <= RECENT_WINDOW_MS && item.sortTime > i.clearedAt

    private fun titleOf(kind: LiveWork.Kind, host: String, label: String?): String = when (kind) {
        LiveWork.Kind.RADAR -> ScansCopy.RADAR
        LiveWork.Kind.QUICK_SCAN_ALL -> ScansCopy.QUICK
        LiveWork.Kind.PORT_SCAN -> ScansCopy.portScan(host)
        LiveWork.Kind.WORDLIST_IMPORT -> ScansCopy.wordlistImport(label ?: "")
    }

    private fun sourceOf(kind: LiveWork.Kind): ScansSource = when (kind) {
        LiveWork.Kind.RADAR -> ScansSource.RADAR
        LiveWork.Kind.QUICK_SCAN_ALL -> ScansSource.QUICK_SCAN_ALL
        LiveWork.Kind.PORT_SCAN -> ScansSource.PORT_SCAN
        LiveWork.Kind.WORDLIST_IMPORT -> ScansSource.WORDLIST_IMPORT
    }

    private fun runningLive(l: LiveWork.Live, c: Ctx): ScansItem {
        val now = c.i.now
        val elapsedS = ((now - l.startedAt) / MS_PER_S).coerceAtLeast(0)
        val host = if (l.hostId != null) c.host(l.hostId, l.label) else null
        val detail = when (l.kind) {
            LiveWork.Kind.RADAR -> ProgressLine.of(c.i.radarStatus, elapsedS)
            LiveWork.Kind.QUICK_SCAN_ALL -> ScansCopy.quickProgress(l.done, l.total ?: 0, elapsedS)
            LiveWork.Kind.PORT_SCAN -> {
                val state = l.hostId?.let { c.i.portStates[it] }
                val progress: ScanProgress? = state?.progress ?: l.total?.let { ScanProgress(l.done, it, l.found, 0) }
                detailProgress(progress, state?.identify?.pending ?: 0)
            }
            LiveWork.Kind.WORDLIST_IMPORT -> WordlistCopy.fetching(l.done.toLong(), l.total?.toLong())
        }
        return ScansItem("live:${l.id}", sourceOf(l.kind), l.id, l.networkId, l.hostId, host ?: l.label.takeIf { l.kind == LiveWork.Kind.WORDLIST_IMPORT }, null, null,
            ScansState.RUNNING, l.done, l.total, l.found, l.startedAt, (now - l.startedAt).coerceAtLeast(0), null, null, null,
            titleOf(l.kind, host ?: ScansCopy.UNKNOWN_HOST, l.label), detail)
    }

    private fun runningTool(a: ToolRunner.Active, c: Ctx): ScansItem {
        val host = c.host(a.hostId, null)
        val startedAt = c.history.firstOrNull { it.run.id == a.runId }?.run?.startedAt ?: (c.i.now - a.activeMs)
        return ScansItem("tool:${a.runId}", ScansSource.TOOL, null, c.i.currentNetworkId, a.hostId, host, a.toolId, a.port, ScansState.RUNNING,
            a.done, a.total, a.found, startedAt, a.activeMs, null, null, a.runId,
            ScansCopy.tool(ToolCopy.toolName(a.toolId), host, a.port), ToolsUiModel.bar(a).progress)
    }

    private fun endedLive(e: LiveWork.Ended, c: Ctx): ScansItem {
        val l = e.live
        val host = if (l.hostId != null) c.host(l.hostId, l.label) else null
        val state = when (e.outcome) {
            LiveWork.Outcome.DONE -> ScansState.DONE
            LiveWork.Outcome.STOPPED -> ScansState.STOPPED
            LiveWork.Outcome.FAILED -> ScansState.FAILED
            LiveWork.Outcome.INTERRUPTED -> ScansState.INTERRUPTED
        }
        val time = e.endedAt ?: l.startedAt
        return ScansItem("live:${l.id}", sourceOf(l.kind), null, l.networkId, l.hostId, host ?: l.label.takeIf { l.kind == LiveWork.Kind.WORDLIST_IMPORT }, null, null,
            state, l.done, l.total, l.found, l.startedAt, e.endedAt?.let { (it - l.startedAt).coerceAtLeast(0) }, e.endedAt, e.summary, null,
            titleOf(l.kind, host ?: ScansCopy.UNKNOWN_HOST, l.label), ScansCopy.recentLine(e.summary, RelativeTime.of(time, c.i.now, c.i.formatDate)))
    }

    private fun historyItem(nr: NetworkRun, c: Ctx): ScansItem? {
        val tool = ToolId.byKey(nr.run.toolId) ?: return null
        val state = when (nr.run.status) {
            ToolRunStatus.RUNNING -> return null        // live (and then in Running) or in the gap between insert and publish (spec 3.4 rule 2)
            ToolRunStatus.DONE -> ScansState.DONE
            ToolRunStatus.STOPPED -> ScansState.STOPPED
            ToolRunStatus.FAILED -> ScansState.FAILED
            ToolRunStatus.INTERRUPTED -> ScansState.INTERRUPTED
        }
        val run = nr.run
        val host = c.host(run.hostId, nr.ip)
        val summary = ToolsUiModel.summaryOf(run)
        val time = run.finishedAt ?: run.startedAt
        return ScansItem("tool:${run.id}", ScansSource.TOOL, null, c.i.currentNetworkId, run.hostId, host, tool, run.port, state, null, null, null,
            run.startedAt, run.finishedAt?.let { (it - run.startedAt).coerceAtLeast(0) }, run.finishedAt, summary, run.id,
            ScansCopy.tool(ToolCopy.toolName(tool), host, run.port), ScansCopy.recentLine(summary, RelativeTime.of(time, c.i.now, c.i.formatDate)))
    }
}
