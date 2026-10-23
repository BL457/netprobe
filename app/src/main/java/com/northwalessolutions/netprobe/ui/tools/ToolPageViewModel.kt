package com.northwalessolutions.netprobe.ui.tools

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.PortEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.data.toServiceInfo
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.InputSpec
import com.northwalessolutions.netprobe.engine.tools.TargetKind
import com.northwalessolutions.netprobe.engine.tools.ToolDescriptor
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.manual.ManualMessages
import com.northwalessolutions.netprobe.ui.manual.ManualRequest
import com.northwalessolutions.netprobe.ui.manual.ManualResult
import com.northwalessolutions.netprobe.ui.manual.ManualStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything on the tool page that is not the form's own text: the tool, the devices and the chosen device's ports, the wordlists, the
 * run this page started (or found running), its findings, the previous runs and the messages. Structural only (3B QA F4): per-tick
 * counts come from [ToolPageViewModel.liveProgress].
 */
data class ToolPageState(
    val module: ToolModule? = null, val devices: List<DeviceChoice> = emptyList(), val ports: List<PortChoice> = emptyList(),
    val context: FormContext = FormContext(), val running: Boolean = false, val runId: Long? = null, val run: ToolResultsState? = null,
    val runStatus: ToolRunStatus? = null, val previous: List<PreviousRun> = emptyList(), val sheet: ToolResultsState? = null,
    val message: String? = null, val appMessage: AppMessage? = null,
)

/**
 * The tool page's view model (tools platform B3). The form text lives here as Compose state, so a keystroke is applied in the same frame
 * (a flow round trip drops characters, seen in the 3D QA). A run goes through the existing starter and runner; the runner owns it, so
 * leaving the page never stops it, and the Scans tab shows it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolPageViewModel(private val c: AppContainer, val key: String, private val prefillHostId: Long?, private val prefillPort: Int?) : ViewModel() {
    private data class RunSource(val run: ToolRunEntity, val sameTool: List<ToolRunEntity>, val ip: String?, val rows: List<FindingRow>)

    /** The form; null until the tool is known (a script tool may still be loading). */
    var form: ToolForm? by mutableStateOf(null)
        private set

    private val message = MutableStateFlow<String?>(null)
    private val pageRunId = MutableStateFlow<Long?>(null)
    private val sheetRunId = MutableStateFlow<Long?>(null)
    private val env: StateFlow<NetworkEnv?> = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    private val networkId: Flow<Long?> = combine(c.currentNetwork, c.forgottenNetworkId) { n, gone -> n?.id?.takeIf { it != gone } }.distinctUntilChanged()
    private val module: StateFlow<ToolModule?> = c.toolRegistry.all.map { all -> all.firstOrNull { it.descriptor.key == key } }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.toolRegistry.byKey(key))
    private val formHostId = MutableStateFlow<Long?>(null)
    private val wordlists = flow { emitAll(c.wordlistStore.lists) }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val starter = ManualStarter(
        committedBefore = { body -> c.undo.committedBefore { body() } },
        network = { e -> networkFor(e) },
        known = { id, e -> knownRanges(id, e) },
        hasBinder = { e -> c.networkEnvMonitor.binderFor(e) != null },
        routeContext = { e -> c.networkEnvMonitor.routeContext(e) },
        envNow = { c.networkEnvMonitor.current() },
        target = { id, ip -> c.hosts.manualTarget(id, ip) },
        discard = { id -> c.hosts.discardManual(id) },
        runnerStart = { t, tool, inputs -> c.toolRunner.start(t, tool, inputs) })

    /** Resolved as a scan does, MAC-aware (3C plan review G7): one transaction at a Run tap. */
    private suspend fun networkFor(e: NetworkEnv): Long? {
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway))
        return net?.id?.takeIf { it != c.forgottenNetworkId.value }
    }
    private suspend fun knownRanges(networkId: Long, e: NetworkEnv): KnownRanges {
        val s = c.settings.state.value
        return KnownRanges.of(HomeRange.of(e.interfaceIp, e.prefixLength, s.maxPrefix, s.extEnabled).subnet, c.settings.manual.get(networkId), c.hosts.hostsForNetwork(networkId).first())
    }

    private val devices: Flow<List<DeviceChoice>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id).map { DevicePick.rows(it) }
    }.flowOn(Dispatchers.Default)
    private val ports: Flow<List<PortEntity>> = formHostId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.ports.portsForHost(id) }
    private val formContext: Flow<FormContext> = combine(env, c.settings.state, formHostId.flatMapLatest { id ->
        if (id == null) flowOf(emptyMap()) else c.ports.portsForHost(id).map { list -> list.associate { it.port to it.toServiceInfo() } }
    }, wordlists) { e, s, services, lists ->
        FormContext(home = e?.let { HomeRange.of(it.interfaceIp, it.prefixLength, s.maxPrefix, s.extEnabled).subnet }, lan = e != null, services = services, wordlists = lists)
    }.distinctUntilChanged()
    private val previous: Flow<List<NetworkRun>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.toolRepo.recentForNetwork(id, PreviousRuns.SCAN)
    }
    private val runSource: Flow<RunSource?> = pageRunId.flatMapLatest { id -> runSourceOf(id) }
    private val sheetSource: Flow<RunSource?> = sheetRunId.flatMapLatest { id -> runSourceOf(id) }

    private fun runSourceOf(id: Long?): Flow<RunSource?> =
        if (id == null) flowOf(null) else c.toolRepo.run(id).flatMapLatest { r ->
            if (r == null) flowOf(null)
            else combine(c.toolRepo.findings(id), c.toolRepo.runsOfSameTool(r), r.hostId?.let { c.hosts.host(it) } ?: flowOf(null)) { f, same, h ->
                if (r.hostId != null && h == null) null else RunSource(r, same, h?.ip, ToolResultsModel.rows(f))
            }
        }

    val state: StateFlow<ToolPageState> = combine(listOf(module, devices, ports, formContext, c.toolRunner.active, pageRunId, runSource, previous, sheetSource, message,
        c.appMessages.current)) { arr ->
        @Suppress("UNCHECKED_CAST") val deviceList = arr[1] as List<DeviceChoice>
        @Suppress("UNCHECKED_CAST") val portList = arr[2] as List<PortEntity>
        @Suppress("UNCHECKED_CAST") val activeMap = (arr[4] as Map<Long, ToolRunner.Active>).mapValues { frozen(it.value) }
        @Suppress("UNCHECKED_CAST") val previousRuns = arr[7] as List<NetworkRun>
        val runId = arr[5] as Long?
        val src = arr[6] as RunSource?
        val sheet = arr[8] as RunSource?
        val now = System.currentTimeMillis()
        ToolPageState(
            module = arr[0] as ToolModule?, devices = deviceList, ports = PortChoices.of(portList), context = arr[3] as FormContext,
            running = runId != null && activeMap.containsKey(runId), runId = runId,
            run = src?.let { ToolResultsModel.ofRows(c.toolRegistry, it.run, it.sameTool, it.ip, it.rows, activeMap[it.run.id], now) },
            runStatus = src?.run?.status,
            previous = PreviousRuns.of(previousRuns, key, now),
            sheet = sheet?.let { ToolResultsModel.ofRows(c.toolRegistry, it.run, it.sameTool, it.ip, it.rows, activeMap[it.run.id], now) },
            message = arr[9] as String?, appMessage = arr[10] as AppMessage?,
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ToolPageState(module = module.value))

    /** The progress line of this page's run, read only where it is drawn (3B QA F4). */
    val liveProgress: StateFlow<String?> = combine(c.toolRunner.active, pageRunId) { m, id -> m[id]?.let { ToolsUiModel.bar(it, c.toolRegistry).progress } }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    /** The results sheet's live status line while its run is live. */
    val liveSheet: StateFlow<String?> = combine(c.toolRunner.active, sheetRunId) { m, id -> m[id]?.let { ToolCopy.resultsRunning(ToolsUiModel.bar(it, c.toolRegistry).progress) } }
        .distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            val m = module.filterNotNull().first()
            val d = m.descriptor
            val host = prefillHostId?.takeIf { d.target != TargetKind.NONE }?.let { c.hosts.byIdOnce(it) }
            form = ToolFormRules.initial(d, host?.id, host?.ip, prefillPort, remembered = rememberedFor(d))
            formHostId.value = host?.id
            // A run of this tool that is already live (the page was left and opened again) is this page's run.
            c.toolRunner.active.value.values.firstOrNull { it.toolKey == key && (host == null || it.hostId == host.id) }?.let { pageRunId.value = it.runId }
        }
        // The sheet closes when its run disappears (pruned, or its network forgotten).
        viewModelScope.launch {
            sheetRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (sheetRunId.value != null) sheetRunId.value = null }
        }
    }

    /**
     * The inputs this tool was last run with (spec section 7, G14). A remembered wordlist that has been deleted falls back to the default
     * rather than leaving Run disabled; the wordlists are waited for only when the tool has a wordlist input, and only briefly.
     */
    private suspend fun rememberedFor(d: ToolDescriptor): Map<String, String> {
        val known = if (d.inputs.any { it is InputSpec.Wordlist }) withTimeoutOrNull(WORDLIST_WAIT_MS) { wordlists.first { it.isNotEmpty() } }?.mapTo(HashSet()) { it.id } else null
        return c.lastUsedInputs.restore(d, known)
    }

    private fun frozen(a: ToolRunner.Active): ToolRunner.Active = a.copy(activeMs = 0, done = 0, found = 0, errors = 0)

    fun chooseDevice(hostId: Long?, ip: String) { form?.let { f -> form = ToolFormRules.withDevice(f, hostId, ip); formHostId.value = hostId } }
    fun setPort(text: String) { form?.let { form = ToolFormRules.withPort(it, text) } }
    fun setHttps(on: Boolean) { form?.let { form = ToolFormRules.withHttps(it, on) } }
    fun setInput(name: String, raw: String) { form?.let { form = ToolFormRules.withInput(it, name, raw) } }

    /**
     * Run: judges the form again with the view model's own context (the page's Run is only enabled when it passed), then starts through the
     * manual starter, or, for a tool with no device, straight on the runner. [confirmed] is the answer to a network "any" tool's dialog.
     */
    fun run(confirmed: Boolean) {
        val m = module.value ?: return
        val f = form ?: return
        viewModelScope.launch {
            val ctx = formContext.first()
            val evaluation = ToolFormRules.evaluate(m, f, ctx)
            if (!evaluation.canRun) return@launch
            val inputs = ToolFormRules.startInputs(m.descriptor, evaluation, confirmed) ?: return@launch
            val e = env.value
            if (e == null) { message.value = ScanController.NO_NETWORK; return@launch }
            c.lastUsedInputs.save(m.descriptor, f.values)   // when a run starts; secrets and the per-run confirmation are never kept
            c.appScope.launch(Dispatchers.Default) {
                val result = if (m.descriptor.target == TargetKind.NONE) ManualResult(c.toolRunner.start(null, key, inputs), null)
                else {
                    val ip = evaluation.ip ?: return@launch
                    starter.startManual(ManualRequest(e, ip, evaluation.port ?: 0, key, evaluation.tls, inputs))
                }
                ManualMessages.of(result, c.hostBusy.busy.value)?.let { message.value = it }
                (result.start as? ToolRunner.Start.Started)?.let { pageRunId.value = it.runId }
            }
        }
    }

    fun stop() { pageRunId.value?.let { c.toolRunner.stop(it) } }

    fun openRun(runId: Long) { sheetRunId.value = runId }
    fun olderRun() { stepRun(+1) }
    fun newerRun() { stepRun(-1) }
    private fun stepRun(delta: Int) {
        viewModelScope.launch {
            val current = sheetRunId.value ?: return@launch
            val shown = c.toolRepo.run(current).first() ?: return@launch
            val same = c.toolRepo.runsOfSameTool(shown).first()
            same.getOrNull(same.indexOfFirst { it.id == current } + delta)?.let { sheetRunId.value = it.id }
        }
    }
    fun closeSheet() { sheetRunId.value = null }
    fun shareRun() {
        viewModelScope.launch {
            val id = sheetRunId.value ?: return@launch
            val shown = c.toolRepo.run(id).first() ?: return@launch
            val ip = shown.hostId?.let { host -> c.hosts.byIdOnce(host)?.ip ?: return@launch }
            val export = ToolExport.of(shown, ip, c.toolRepo.findingsOnce(id), toolName = ToolsUiModel.nameOf(c.toolRegistry, shown))
            c.actionRunner.share(ToolExport.subject(export.toolName, ip, shown.port), ToolExport.text(export))
        }
    }
    /** As the Device screen: Copy copies the URL when there is one, else the value; below API 33 a snackbar confirms. */
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
        private const val WORDLIST_WAIT_MS = 2_000L
    }
}
