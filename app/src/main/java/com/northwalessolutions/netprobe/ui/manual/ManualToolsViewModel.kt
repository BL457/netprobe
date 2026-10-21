package com.northwalessolutions.netprobe.ui.manual

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolOptions
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheetState
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The "Run a tool" screen (3C spec 4). Every run goes through ManualStarter and the existing ToolRunner. The host of the run this screen
 * started lives in AppContainer (plan review G13), so Stop survives the screen being recreated. Starts and the brute-force sheet's Run use
 * appScope, as the Device screen does. [state] is structural only (3B QA F4); the per-tick text is [liveProgress] and [liveResults].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualToolsViewModel(private val c: AppContainer) : ViewModel() {
    private data class PendingBrute(val request: ManualRequest, val sheet: DirBruteSheetState)
    private data class ResultsSource(val run: ToolRunEntity, val sameTool: List<ToolRunEntity>, val ip: String, val rows: List<FindingRow>)

    private val form = MutableStateFlow(ManualForm())
    private val message = MutableStateFlow<String?>(null)
    private val startedHostId: MutableStateFlow<Long?> = c.manualStartedHostId
    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val pending = MutableStateFlow<PendingBrute?>(null)
    private val env: StateFlow<NetworkEnv?> = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    private val networkId: Flow<Long?> = combine(c.currentNetwork, c.forgottenNetworkId) { n, gone -> n?.id?.takeIf { it != gone } }.distinctUntilChanged()

    private val starter = ManualStarter(
        committedBefore = { body -> c.undo.committedBefore { body() } },
        network = { e -> networkFor(e) },
        known = { id, e -> knownRanges(id, e) },
        hasBinder = { e -> c.networkEnvMonitor.binderFor(e) != null },
        routeContext = { e -> c.networkEnvMonitor.routeContext(e) },
        envNow = { c.networkEnvMonitor.current() },
        target = { id, ip -> c.hosts.manualTarget(id, ip) },
        discard = { id -> c.hosts.discardManual(id) },
        runnerStart = { t, tool, options -> c.toolRunner.start(t, tool, options) })

    /**
     * Always resolved as a scan does, MAC-aware (plan review G7, amendment A5): the gateway-and-prefix key alone cannot tell two sites with the
     * same addressing apart, so Home's current row is never taken on trust. One transaction; a Run tap is not a hot path.
     */
    private suspend fun networkFor(e: NetworkEnv): Long? {
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway))
        return net?.id?.takeIf { it != c.forgottenNetworkId.value }
    }
    /** Spec 4.5: read at the Run tap from the live settings, this network's manual ranges and its visible hosts. */
    private suspend fun knownRanges(networkId: Long, e: NetworkEnv): KnownRanges {
        val s = c.settings.state.value
        return KnownRanges.of(HomeRange.of(e.interfaceIp, e.prefixLength, s.maxPrefix, s.extEnabled).subnet, c.settings.manual.get(networkId),
            c.hosts.hostsForNetwork(networkId).first())
    }

    /** Eager, so runTool reads the same known service the switch shows (plan review G14). */
    private val known: StateFlow<KnownTarget?> = combine(networkId, form.map { it.ipText.trim() }.distinctUntilChanged()) { id, ip -> id to ip }
        .mapLatest { (id, ip) -> lookupKnown(id, ip) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private suspend fun lookupKnown(id: Long?, ip: String): KnownTarget? {
        if (id == null || Ipv4.parse(ip) == null) return null
        val h = c.hosts.findOnce(id, ip)?.takeUnless { it.manualOnly } ?: return null
        return KnownTarget(h, c.ports.servicesFor(h.id))
    }
    private val history: Flow<List<NetworkRun>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.toolRepo.recentForNetwork(id, ManualPresenter.RECENT_RUNS)
    }
    private val resultsSource: Flow<ResultsSource?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else c.toolRepo.run(id).flatMapLatest { r ->
            if (r == null) flowOf(null) else combine(c.toolRepo.findings(id), c.toolRepo.runsForHost(r.hostId), c.hosts.host(r.hostId)) { f, runs, h ->
                h?.let { ResultsSource(r, runs.filter { it.toolId == r.toolId }, it.ip, ToolResultsModel.rows(f)) }
            }
        }
    }

    val state: StateFlow<ManualUiState> = combine(listOf(form, env, known, history, c.toolRunner.active, resultsSource, pending, message,
        c.appMessages.current, startedHostId, c.settings.state)) { arr ->
        @Suppress("UNCHECKED_CAST") val historyRuns = arr[3] as List<NetworkRun>
        // Structural only (3B QA F4): every run is frozen, so a runner tick produces an equal state, which distinctUntilChanged drops.
        @Suppress("UNCHECKED_CAST") val activeMap = (arr[4] as Map<Long, ToolRunner.Active>).mapValues { ManualPresenter.frozen(it.value) }
        val e = arr[1] as NetworkEnv?; val s = arr[10] as SettingsState
        val home = e?.let { HomeRange.of(it.interfaceIp, it.prefixLength, s.maxPrefix, s.extEnabled).subnet }
        val now = System.currentTimeMillis()
        val results = (arr[5] as ResultsSource?)?.let { src -> ToolResultsModel.ofRows(src.run, src.sameTool, src.ip, src.rows, activeMap[src.run.hostId], now) }
        ManualPresenter.present(arr[0] as ManualForm, home, e != null, arr[2] as KnownTarget?, historyRuns, (arr[9] as Long?)?.let { activeMap[it] },
            results, (arr[6] as PendingBrute?)?.sheet, arr[7] as String?, arr[8] as AppMessage?, now)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ManualUiState())

    /** The pill's per-tick progress for the run this screen started, read only in LiveLine's draw (3B QA F4). */
    val liveProgress: StateFlow<String?> = combine(c.toolRunner.active, startedHostId) { m, id -> ManualPresenter.liveProgress(id?.let { m[it] }) }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    /** The open results sheet's live status line while its run is live, for ToolResultsSheet's liveStatus (3B QA F4). */
    val liveResults: StateFlow<String?> = combine(c.toolRunner.active, resultsSource) { m, src ->
        ManualPresenter.liveResultsStatus(src?.let { s -> m[s.run.hostId]?.takeIf { it.runId == s.run.id } })
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        // The results sheet closes when its run disappears (pruned by a newer run, or its network forgotten), as on the Device screen.
        viewModelScope.launch {
            resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (resultsRunId.value != null) resultsRunId.value = null }
        }
    }

    fun setIp(text: String) { form.update { it.copy(ipText = text, httpsChoice = null) } }
    fun setPort(text: String) { form.update { it.copy(portText = text.filter(Char::isDigit).take(MAX_PORT_CHARS), httpsChoice = null) } }
    fun setTool(tool: ToolId) { form.update { it.copy(tool = tool, httpsChoice = null) } }
    fun setHttps(on: Boolean) { form.update { it.copy(httpsChoice = on) } }

    /** Run (spec 4.3): validate; brute-force opens its sheet and creates nothing until the sheet's Run; the other tools start at once. */
    fun runTool() {
        val f = form.updateAndGet { it.copy(attempted = true) }
        val e = env.value ?: return
        val s = c.settings.state.value
        val checked = ManualTarget.check(f.ipText, f.portText, f.tool, HomeRange.of(e.interfaceIp, e.prefixLength, s.maxPrefix, s.extEnabled).subnet, attempted = true)
        val ip = checked.ip ?: return
        val port = checked.port ?: return
        // From the form, as the presenter computes it, never from the published state (plan review G14).
        val https = f.httpsChoice ?: ManualTarget.httpsDefault(f.tool, port, known.value?.services?.get(port))
        val request = ManualRequest(e, ip, port, f.tool, ManualTarget.tls(f.tool, https))
        when (ManualFlow.firstStep(f.tool)) {
            ManualStep.OPEN_SHEET -> openSheet(request)
            ManualStep.START -> c.appScope.launch(Dispatchers.Default) { begin(request) }
        }
    }
    private fun openSheet(r: ManualRequest) {
        // Off the main thread: the first wordlistStore access reads the built-in lists (3B).
        c.appScope.launch(Dispatchers.Default) {
            val lists = c.wordlistStore.lists.value
            val saved = c.dirBruteOptions.load()
            val initial = if (lists.any { it.id == saved.wordlistId }) saved else saved.copy(wordlistId = DirBruteOptions.DEFAULT_WORDLIST)
            if (initial != saved) message.value = ToolCopy.WORDLIST_FALLBACK
            val reason = ToolCopy.BUSY_TOO_MANY.takeIf { c.toolRunner.active.value.size >= ToolRunner.MAX_ACTIVE_RUNS }
            pending.value = PendingBrute(r, DirBruteSheetState(r.port, ToolExport.target(ToolId.DIR_BRUTEFORCE, r.ip.hostAddress, r.port, r.tls), lists, initial, reason))
        }
    }
    /** The sheet's Run: save the options, close the sheet, start (spec 4.3 step 3). */
    fun startDirBrute(o: DirBruteOptions) {
        val p = pending.value ?: return
        pending.value = null
        val options = o.coerced()
        c.appScope.launch(Dispatchers.Default) {
            c.dirBruteOptions.save(options)
            val meta = c.wordlistStore.lists.value.firstOrNull { it.id == options.wordlistId }
            begin(p.request.copy(options = ToolOptions.DirBrute(options, meta?.name ?: "", meta?.entries ?: 0)))
        }
    }
    /** Cancel creates nothing: no row was made for the open sheet (spec 4.3 step 3). */
    fun closeDirBrute() { pending.value = null }

    private suspend fun begin(r: ManualRequest) {
        val result = starter.startManual(r)
        ManualMessages.of(result, c.hostBusy.busy.value)?.let { message.value = it }
        if (result.start is ToolRunner.Start.Started) startedHostId.value = result.hostId
    }
    fun stopTool() { startedHostId.value?.let { c.toolRunner.stop(it) } }

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
    /** Share and export are ToolExport unchanged (spec 4.4). */
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message.value = ToolCopy.COPIED
    }
    fun openFinding(row: FindingRow) {
        val url = row.url ?: return
        if (!c.actionRunner.run(DeviceAction.OpenWith(url))) copyFinding(row)
    }
    fun dismissMessage() { message.value = null }
    fun dismissAppMessage(id: Long) { c.appMessages.dismiss(id) }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        /** Five digits is the widest valid port; the field filters digits and validation checks the range. */
        const val MAX_PORT_CHARS = 5
    }
}
