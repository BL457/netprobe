package com.northwalessolutions.netprobe.ui.scans

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.PendingUndo
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.NetworkEntity
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import com.northwalessolutions.netprobe.ui.tools.ToolResultsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScansUiState(
    val list: ScansList = ScansList(), val visibleHostIds: Set<Long> = emptySet(), val results: ToolResultsState? = null,
    val undoText: String? = null, val clearPrompt: Boolean = false, val appMessage: AppMessage? = null,
)

/**
 * The Scans destination (3D spec 3). State is gathered from LiveWork, the tool runner, tool history and the host list and handed to the pure
 * ScansRules; the results sheet is hosted here exactly as the Tools screen hosts it. Stop and the other click handlers run on the main thread,
 * which is where the scan controllers expect to be touched.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ScansViewModel(private val c: AppContainer) : ViewModel() {
    private data class ResultsSource(val run: ToolRunEntity, val sameTool: List<ToolRunEntity>, val ip: String, val rows: List<FindingRow>)

    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val clearPrompt = MutableStateFlow(false)
    private var clearSnapshot: Pair<Long, List<LiveWork.Ended>>? = null

    private val networkId: Flow<Long?> = c.currentNetwork.map { it?.id }.distinctUntilChanged()
    private val history: Flow<List<NetworkRun>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.toolRepo.recentForNetwork(id, ScansRules.HISTORY_FETCH)
    }
    /** Every row of the network, manual targets included, so a manual run resolves to its IP (spec 3.4 rule 9). */
    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.allHostsForNetwork(id) }
    /** The radar's progress text source, sampled as Home samples it. */
    private val radar: Flow<DiscoveryStatus> = c.discoveryEngine.status.sample(Motion.PROGRESS_SAMPLE_MS).onStart { emit(c.discoveryEngine.status.value) }
    /** Keeps "2 minutes ago" honest while nothing else changes. */
    private val tick: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(IDLE_TICK_MS) } }

    private val resultsSource: Flow<ResultsSource?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else c.toolRepo.run(id).flatMapLatest { r ->
            if (r == null) flowOf(null) else combine(c.toolRepo.findings(id), c.toolRepo.runsForHost(r.hostId), c.hosts.host(r.hostId)) { f, runs, h ->
                h?.let { ResultsSource(r, runs.filter { it.toolId == r.toolId }, it.ip, ToolResultsModel.rows(f)) }
            }
        }
    }

    val state: StateFlow<ScansUiState> = combine(listOf<Flow<Any?>>(c.liveWork.live, c.liveWork.ended, c.toolRunner.active, history, hosts, c.scansHistory.clearedAt,
        c.forgottenNetworkId, c.removedHostIds, radar, c.portScans.state, c.currentNetwork, c.undo.pending, c.appMessages.current, clearPrompt, resultsSource, tick)) { arr ->
        @Suppress("UNCHECKED_CAST") val live = arr[0] as List<LiveWork.Live>
        @Suppress("UNCHECKED_CAST") val ended = arr[1] as List<LiveWork.Ended>
        @Suppress("UNCHECKED_CAST") val active = arr[2] as Map<Long, ToolRunner.Active>
        @Suppress("UNCHECKED_CAST") val historyRows = arr[3] as List<NetworkRun>
        @Suppress("UNCHECKED_CAST") val hostRows = arr[4] as List<HostEntity>
        @Suppress("UNCHECKED_CAST") val removed = arr[7] as Set<Long>
        @Suppress("UNCHECKED_CAST") val ports = arr[9] as Map<Long, PortScanState>
        val network = arr[10] as NetworkEntity?
        val now = System.currentTimeMillis()
        val list = ScansRules.build(ScansInput(live, active, ended, historyRows, hostRows.associate { it.id to DisplayName.of(it) }, network?.id, now, arr[5] as Long,
            arr[6] as Long?, removed, arr[8] as DiscoveryStatus?, ports))
        val results = (arr[14] as ResultsSource?)?.let { src -> ToolResultsModel.ofRows(src.run, src.sameTool, src.ip, src.rows, active[src.run.hostId], now) }
        ScansUiState(list, hostRows.filter { !it.manualOnly }.map { it.id }.toSet(), results, (arr[11] as PendingUndo?)?.text, arr[13] as Boolean, arr[12] as AppMessage?)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScansUiState())

    init {
        // The results sheet closes when its run disappears (pruned by a newer run, or its host removed), as on the Device screen.
        viewModelScope.launch {
            resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (resultsRunId.value != null) resultsRunId.value = null }
        }
    }

    /** Every LiveWork item stops by its own id (a stale tap is a no-op); a tool row stops by its run id (checked against the runner at tap time), never by host alone. */
    fun stop(item: ScansItem) {
        when (val route = ScansTargets.stop(item)) {
            is StopRoute.Live -> c.liveWork.stop(route.id)
            is StopRoute.Tool -> if (ScansTargets.toolStopApplies(route, c.toolRunner.active.value[route.hostId]?.runId)) c.toolRunner.stop(route.hostId)
            null -> Unit
        }
    }

    fun openRun(runId: Long) { resultsRunId.value = runId }
    fun olderRun() { stepRun(+1) }
    fun newerRun() { stepRun(-1) }
    private fun stepRun(delta: Int) {
        viewModelScope.launch {
            val current = resultsRunId.value ?: return@launch
            val shown = c.toolRepo.run(current).first() ?: return@launch
            val same = c.toolRepo.runsForHost(shown.hostId).first().filter { it.toolId == shown.toolId }
            same.getOrNull(same.indexOfFirst { it.id == current } + delta)?.let { resultsRunId.value = it.id }
        }
    }
    fun closeResults() { resultsRunId.value = null }
    fun shareRun() {
        viewModelScope.launch {
            val id = resultsRunId.value ?: return@launch
            val shown = c.toolRepo.run(id).first() ?: return@launch
            val h = c.hosts.byIdOnce(shown.hostId) ?: return@launch
            val export = ToolExport.of(shown, h.ip, c.toolRepo.findingsOnce(id))
            c.actionRunner.share(ToolExport.subject(export.toolName, h.ip, shown.port), ToolExport.text(export))
        }
    }
    /** As the Device screen (3B 8.5): Copy copies the URL when there is one, else the value; below API 33 a snackbar confirms. */
    fun copyFinding(row: FindingRow) {
        c.actionRunner.run(DeviceAction.Copy(ToolCopy.COPY, row.url ?: row.value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.appMessages.post(ToolCopy.COPIED)
    }
    fun openFinding(row: FindingRow) {
        val url = row.url ?: return
        if (!c.actionRunner.run(DeviceAction.OpenWith(url))) copyFinding(row)
    }

    /** The pending UndoStore Undo (Remove device from a page opened here lands the Director on this screen, D20). A dismissal commits nothing. */
    fun undo() { c.appScope.launch { c.undo.undo() } }
    fun dismissApp(id: Long) { c.appMessages.dismiss(id) }

    /** Clear history (spec 3.8): the cut-off is written once, the in-memory outcomes are hidden, nothing is deleted; Undo restores both. */
    fun clearHistory() {
        clearSnapshot = c.scansHistory.clearedAt.value to c.liveWork.ended.value
        c.scansHistory.set(System.currentTimeMillis())
        c.liveWork.clearEnded()
        clearPrompt.value = true
    }
    fun undoClear() {
        clearSnapshot?.let { (at, ended) -> c.scansHistory.set(at); c.liveWork.restoreEnded(ended) }
        clearSnapshot = null
        clearPrompt.value = false
    }
    /** The prompt ended without Undo (timeout, or leaving the screen, which ends the chance as in 3C 1.2). */
    fun dismissClear() {
        clearSnapshot = null
        clearPrompt.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val IDLE_TICK_MS = 30_000L
    }
}
