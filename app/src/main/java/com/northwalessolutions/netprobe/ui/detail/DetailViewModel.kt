package com.northwalessolutions.netprobe.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import android.util.Log
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.AppScopes
import com.northwalessolutions.netprobe.PendingUndo
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.portscan.*
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolInputs
import com.northwalessolutions.netprobe.engine.tools.ToolKeys
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.ToolRunner.Companion.forHost
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteInputs
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.vulns.VulnQuery
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.home.RemovedHosts
import com.northwalessolutions.netprobe.ui.home.RowRules
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.tools.DetailToolsState
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheetState
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.PortTool
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import com.northwalessolutions.netprobe.ui.tools.ToolRunBar
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>, val tryAs: List<Pair<String, String>> = emptyList())
sealed class VulnSheetState { data class Loading(val query: String) : VulnSheetState(); data class Ready(val query: String, val result: VulnResult) : VulnSheetState() }
data class DetailUiState(val host: HostEntity? = null, val displayName: String = "", val ports: List<PortRow> = emptyList(), val scanning: Boolean = false, val progress: ScanProgress? = null,
                         val profile: ScanProfile = ScanProfile.Quick, val customPortsText: String = "", val customError: String? = null, val message: String? = null, val vuln: VulnSheetState? = null,
                         val identifying: Int = 0, val identified: Int = 0,
                         /** Why Scan is disabled when two scans run on other devices (3D spec 3.3); null otherwise. */
                         val scanRefusal: String? = null)

class DetailViewModel(private val c: AppContainer, private val hostId: Long) : ViewModel() {
    private val profile = MutableStateFlow<ScanProfile>(ScanProfile.Quick); private val customText = MutableStateFlow(""); private val customError = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null); private val vuln = MutableStateFlow<VulnSheetState?>(null)
    private val env: StateFlow<NetworkEnv?> = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    // Port scans live in PortScans on the app scope (3D spec 3.3): leaving this screen never stops one, and the network watcher is PortScans'.
    private var vulnJob: Job? = null
    /** Try as pairs per port, cached: the package manager is asked once per port, not on every state emission (review checklist: no per-emission work). */
    private val tryAsCache = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private fun tryAsFor(ip: String, port: Int) = tryAsCache.getOrPut("$ip:$port") { c.actions.tryAs(ip, port) }

    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), c.portScans.state, profile, customText, customError, message, vuln, env) { arr ->
        @Suppress("UNCHECKED_CAST")
        val ps = arr[1] as List<PortEntity>
        @Suppress("UNCHECKED_CAST")
        val scans = arr[2] as Map<Long, PortScanState>
        val h = arr[0] as HostEntity?; val e = arr[8] as NetworkEnv?
        val mine = scans[hostId]
        val rows = ps.map { p -> val info = p.toServiceInfo()
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList(),
                h?.let { tryAsFor(it.ip, p.port) }.orEmpty()) }
        val name = h?.let { if (RowRules.isOwn(it.ip, e) && it.customName.isNullOrBlank()) RowRules.THIS_PHONE else DisplayName.of(it) } ?: ""
        DetailUiState(h, name, rows, mine != null, mine?.progress, arr[3] as ScanProfile, arr[4] as String, arr[5] as String?, arr[6] as String?, arr[7] as VulnSheetState?,
            identifying = mine?.identify?.pending ?: 0, identified = mine?.identify?.done ?: 0,
            scanRefusal = if (mine == null) DetailBusyRules.capRefusal(scans.size) else null)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val dirBrutePort = MutableStateFlow<Int?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val resultsSource: Flow<Pair<ToolRunEntity, List<FindingRow>>?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else combine(c.toolRepo.run(id), c.toolRepo.findings(id)) { r, f -> r?.let { it to ToolResultsModel.rows(f) } }
    }
    /** The wordlist store does resource I/O on first use, so it is first touched inside this flow, which runs on Dispatchers.Default (toolState's flowOn). */
    private val wordlists: Flow<List<WordlistMeta>> = flow { emitAll(c.wordlistStore.lists) }

    /**
     * The runner republishes every PROGRESS_INTERVAL_MS while a run is live. Only these fields change what [toolState] lays out: which
     * run this host has (and whether its total is known, which picks the determinate line), and whether the runner is full. The
     * per-tick counts go to [toolBar] instead, so the Device screen does not recompose on every tick (3B QA F4).
     */
    private val structuralActive: Flow<Map<Long, ToolRunner.Active>> =
        c.toolRunner.active.distinctUntilChanged { a, b -> ToolStateKey.of(a, hostId) == ToolStateKey.of(b, hostId) }

    /** The pinned bar's per-tick fields, read only by the bar's leaf composables and the live results status line (3B QA F4). */
    val toolBar: StateFlow<ToolRunBar?> = c.toolRunner.active.map { m -> m.forHost(hostId)?.let { ToolsUiModel.bar(it, c.toolRegistry) } }.distinctUntilChanged()
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Tools block, pinned bar, busy state, both sheets and the app message for this host (spec 8.1 to 8.5, 4.3). It emits on structural
     * changes only; the bar's progress text and fraction and the live results status line come from [toolBar].
     */
    val toolState: StateFlow<DetailToolsState> = combine(listOf(c.hosts.host(hostId), c.ports.portsForHost(hostId), c.toolRepo.runsForHost(hostId),
        structuralActive, c.hostBusy.busy, resultsSource, dirBrutePort, wordlists, c.appMessages.current)) { arr ->
        @Suppress("UNCHECKED_CAST") val ports = arr[1] as List<PortEntity>
        @Suppress("UNCHECKED_CAST") val runs = arr[2] as List<ToolRunEntity>
        @Suppress("UNCHECKED_CAST") val activeMap = arr[3] as Map<Long, ToolRunner.Active>
        @Suppress("UNCHECKED_CAST") val busyMap = arr[4] as Map<Long, HostBusyRegistry.Work>
        @Suppress("UNCHECKED_CAST") val results = arr[5] as Pair<ToolRunEntity, List<FindingRow>>?
        @Suppress("UNCHECKED_CAST") val lists = arr[7] as List<WordlistMeta>
        val h = arr[0] as HostEntity?; val sheetPort = arr[6] as Int?; val appMessage = arr[8] as AppMessage?
        val active = activeMap.forHost(hostId)
        val now = System.currentTimeMillis()
        val busy = busyMap[hostId]
        DetailToolsState(
            tools = h?.let { ToolsUiModel.of(c.toolRegistry, it, ports.map { p -> p.port to p.toServiceInfo() }, runs, now) },
            bar = active?.let { ToolsUiModel.bar(it, c.toolRegistry) },
            busy = busy,
            results = if (h == null) null else results?.let { (run, findings) ->
                ToolResultsModel.ofRows(c.toolRegistry, run, runs.filter { it.toolId == run.toolId }, h.ip, findings, active, now)
            },
            dirBrute = if (h == null) null else sheetPort?.let { port -> dirBruteSheet(h, port, ports, lists, busy, activeMap.size) },
            appMessage = appMessage,
            removeAllowed = DetailBusyRules.removeAllowed(busyMap, hostId),
            runTool = active?.toolKey, runPort = active?.port ?: 0,
            portTools = ports.associate { p -> p.port to ToolApplicability.toolsFor(c.toolRegistry, p.port, p.toServiceInfo()).map { PortTool(it.descriptor.key, it.descriptor.name) } },
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailToolsState())

    init {
        // The results sheet closes when its run disappears (pruned by a new run, or its host removed) (spec 8.5, 11.15).
        viewModelScope.launch {
            resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (resultsRunId.value != null) resultsRunId.value = null }
        }
    }

    private fun dirBruteSheet(h: HostEntity, port: Int, ports: List<PortEntity>, lists: List<WordlistMeta>, busy: HostBusyRegistry.Work?, runningHosts: Int): DirBruteSheetState {
        val service = ports.firstOrNull { it.port == port }?.toServiceInfo()
        val tls = service?.let { ToolApplicability.targetTls(ToolKeys.DIR_BRUTEFORCE, port, it) } ?: false
        val saved = c.dirBruteOptions.load()
        val initial = if (lists.any { it.id == saved.wordlistId }) saved else saved.copy(wordlistId = DirBruteOptions.DEFAULT_WORDLIST)
        val reason = busy?.let(ToolsUiModel::busyMessage) ?: ToolCopy.BUSY_TOO_MANY.takeIf { runningHosts >= ToolRunner.MAX_ACTIVE_RUNS }
        return DirBruteSheetState(port, ToolExport.target(ToolKeys.DIR_BRUTEFORCE, h.ip, port, tls), lists, initial, reason)
    }

    /** A chip or port-sheet row (spec 8.1, 8.3): brute-force opens its sheet, the others start at once; a held host shows its reason. */
    fun runTool(toolKey: String, port: Int) {
        DetailBusyRules.toolRefusal(c.hostBusy.busy.value, hostId)?.let { message.value = it; return }
        if (toolKey == ToolKeys.DIR_BRUTEFORCE) {
            // Off the main thread: the first wordlistStore access reads the built-in lists.
            c.appScope.launch(Dispatchers.Default) {
                val saved = c.dirBruteOptions.load()
                if (c.wordlistStore.lists.value.none { it.id == saved.wordlistId }) message.value = ToolCopy.WORDLIST_FALLBACK
                dirBrutePort.value = port
            }
            return
        }
        start(toolKey, port, ToolInputs.NONE)
    }
    /** Run in the options sheet: save the options, close the sheet, start the run (spec 8.4). */
    fun startDirBrute(o: DirBruteOptions) {
        val port = dirBrutePort.value ?: return
        val options = o.coerced()
        dirBrutePort.value = null
        c.appScope.launch(Dispatchers.Default) {
            c.dirBruteOptions.save(options)
            val meta = c.wordlistStore.lists.value.firstOrNull { it.id == options.wordlistId }
            start(ToolKeys.DIR_BRUTEFORCE, port, DirBruteInputs.of(options, meta?.name ?: "", meta?.entries ?: 0))
        }
    }
    fun closeDirBrute() { dirBrutePort.value = null }

    private fun start(toolKey: String, port: Int, inputs: ToolInputs) {
        c.appScope.launch {
            val h = c.hosts.byIdOnce(hostId) ?: return@launch
            val ip = RowRules.parseIpv4(h.ip) ?: return@launch
            val service = c.ports.servicesFor(hostId)[port]
            val tls = service?.let { ToolApplicability.targetTls(toolKey, port, it) } ?: (toolKey == ToolKeys.TLS_CERTIFICATE)
            // Brute-force always sends the IP; the fingerprint and TLS tools send the known name (spec 4.9).
            val name = if (toolKey == ToolKeys.DIR_BRUTEFORCE) null else ToolApplicability.serverName(h)
            when (c.toolRunner.start(ToolTarget(hostId, ip, port, tls, name), toolKey, inputs)) {
                ToolRunner.Start.HostBusy -> message.value = ToolsUiModel.busyMessage(c.hostBusy.busy.value[hostId] ?: HostBusyRegistry.Work.TOOL)
                ToolRunner.Start.TooMany -> message.value = ToolCopy.BUSY_TOO_MANY
                ToolRunner.Start.NoNetwork -> message.value = ScanController.NO_NETWORK
                is ToolRunner.Start.Started -> Unit
            }
        }
    }
    fun stopTool() { c.toolRunner.stopHost(hostId) }

    /** "Results" opens the newest run of that tool (spec 8.1); the switcher moves through this tool's runs. */
    fun openResults(toolKey: String) {
        viewModelScope.launch { resultsRunId.value = c.toolRepo.runsForHost(hostId).first().firstOrNull { it.toolId == toolKey }?.id }
    }
    fun olderRun() { stepRun(+1) }
    fun newerRun() { stepRun(-1) }
    private fun stepRun(delta: Int) {
        viewModelScope.launch {
            val current = resultsRunId.value ?: return@launch
            val runs = c.toolRepo.runsForHost(hostId).first()
            val tool = runs.firstOrNull { it.id == current }?.toolId ?: return@launch
            val same = runs.filter { it.toolId == tool }
            same.getOrNull(same.indexOfFirst { it.id == current } + delta)?.let { resultsRunId.value = it.id }
        }
    }
    fun closeResults() { resultsRunId.value = null }
    fun shareRun() {
        viewModelScope.launch {
            val id = resultsRunId.value ?: return@launch
            val run = c.toolRepo.run(id).first() ?: return@launch
            val h = c.hosts.byIdOnce(hostId) ?: return@launch
            val export = ToolExport.of(run, h.ip, c.toolRepo.findingsOnce(id), toolName = ToolsUiModel.nameOf(c.toolRegistry, run))
            c.actionRunner.share(ToolExport.subject(export.toolName, h.ip, run.port), ToolExport.text(export))
        }
    }
    /** "Copy" copies the URL when there is one, else the value; below API 33 a snackbar confirms (spec 8.5). */
    fun copyFinding(row: FindingRow) {
        c.actionRunner.run(DeviceAction.Copy(ToolCopy.COPY, row.url ?: row.value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message.value = ToolCopy.COPIED
    }
    fun openFinding(row: FindingRow) { row.url?.let { runAction(DeviceAction.OpenWith(it)) } }
    fun dismissAppMessage(id: Long) { c.appMessages.dismiss(id) }

    fun setProfile(p: ScanProfile) { profile.value = p; customError.value = null }
    fun setCustomPorts(t: String) { customText.value = t; customError.value = null }

    fun startScan() {
        val h = state.value.host ?: return
        if (c.portScans.state.value.containsKey(hostId)) return
        val prof = profile.value
        val resolved = if (prof is ScanProfile.Custom) ProfileParser.parse(customText.value).getOrElse { customError.value = it.message; return } else prof
        DetailBusyRules.scanRefusal(c.hostBusy.busy.value, hostId, c.portScans.state.value.size)?.let { message.value = it; return }
        when (c.portScans.start(h, resolved)) {
            PortScanStart.Started -> Unit
            // A start racing another screen or the cap: the same words the pill's refusal line uses.
            PortScanStart.HostBusy -> message.value = DetailBusyRules.scanRefusal(c.hostBusy.busy.value, hostId) ?: ToolCopy.SCAN_REFUSED
            PortScanStart.TooMany -> message.value = DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS)
            PortScanStart.NoNetwork -> message.value = ScanController.NO_NETWORK
        }
    }

    fun stopScan() { c.portScans.stop(hostId) }

    /**
     * Detail overflow "Remove device" (spec 5.3): record the host id at once, before any suspension, so Quick scan all can never start it
     * while Remove waits (review M1); stop every scan of this host and wait for it; under the radar collector's lock, record the id again
     * (idempotent) and, when a radar scan is running, the (network, ip) pair, then delete the host and its ports in one transaction;
     * offer Undo on Home. Runs on appScope (on Main first, so the scan controller is touched on its own thread), so leaving
     * the screen never cancels it. Undo restores under the same lock, through a restore that clears anything a late scan wrote.
     */
    fun removeDevice() {
        if (!DetailBusyRules.removeAllowed(c.hostBusy.busy.value, hostId)) return   // spec 4.3: a running tool run keeps the device
        val h = state.value.host ?: return
        val name = state.value.displayName
        c.appScope.launch(Dispatchers.Main.immediate) {
            // Track only the marks this call adds, so a failed second Remove of the same host never clears the first Remove's marks while its Undo is pending.
            var addedId = hostId !in c.removedHostIds.getAndUpdate { it + hostId }
            var addedPair = false
            var removed: Pair<HostSnapshot, String?>? = null
            try {
                c.portScans.stopAndJoin(hostId)
                c.hostScanJobs[hostId]?.cancelAndJoin()
                removed = c.hostWriteLock.withLock {
                    if (hostId !in c.removedHostIds.getAndUpdate { it + hostId }) addedId = true
                    // Any running radar, even one still resolving its network (review M2): the pair is keyed by network, so another network's scan ignores it.
                    if (c.radarScanActive.value) addedPair = (h.networkId to h.ip) !in c.removedDuringScan.getAndUpdate { RemovedHosts.added(it, h.networkId, h.ip) }
                    val hostSnap = c.hosts.snapshot(hostId) ?: return@withLock null
                    // 3B 7.4: the host's tool rows into TEMP tables in the same critical section, after the host snapshot and before the cascade
                    // drops them. A failed snapshot never blocks Remove: the host is removed and Undo brings it back without its tool history.
                    val token = runCatching { c.toolRepo.snapshot(listOf(hostId)) }
                        .onFailure { Log.w(AppScopes.LOG_TAG, "tool snapshot failed", it) }.getOrNull()
                    try { c.hosts.delete(hostSnap) } catch (e: Throwable) {
                        if (token != null) runCatching { c.toolRepo.dropSnapshot(token) }   // nothing was deleted, so the TEMP tables must not leak
                        throw e
                    }
                    // 3C spec 3.4 rule 5: the listener must not re-add it before the next scan or listening session; Undo takes it out below.
                    c.removedWhileListening.update { RemovedHosts.added(it, h.networkId, h.ip) }
                    hostSnap to token
                }
            } finally {
                // Nothing was deleted (host already gone, or a failure): clear the marks this call added so the host is never skipped by every later scan.
                if (removed == null) {
                    if (addedId) c.removedHostIds.update { it - hostId }
                    if (addedPair) c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
                }
            }
            val (snap, token) = removed ?: return@launch
            // A restore or drop that fails leaves the TEMP tables until process death, which is the Undo's own lifetime (accepted, 3B fix round 0).
            c.undo.post(PendingUndo(HomeCopy.removed(name),
                // The marks are cleared in finally (the Task 13 pattern), so a failed restore never leaves the host skipped by every later scan,
                // and inside the lock (review M3), so no radar emission sees the restored row while its ip is still marked and skips its update.
                // update {} is an atomic read-modify-write that touches only this host's id and pair, so a second Remove's marks are kept.
                restore = {
                    c.hostWriteLock.withLock {
                        try {
                            c.hosts.restore(snap)
                            // Runs and findings back with their original ids, after the host row exists; a failure never undoes the host restore.
                            if (token != null) runCatching { c.toolRepo.restore(token) }.onFailure { Log.w(AppScopes.LOG_TAG, "tool restore failed", it) }
                        } finally {
                            c.removedHostIds.update { it - hostId }
                            c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
                            c.removedWhileListening.update { RemovedHosts.without(it, h.networkId, h.ip) }
                        }
                    }
                },
                // A lapsed Undo drops the TEMP tables; a failed drop must not break the next Remove's post, which runs this commit.
                commit = { if (token != null) runCatching { c.toolRepo.dropSnapshot(token) }.onFailure { Log.w(AppScopes.LOG_TAG, "tool snapshot drop failed", it) } }))
        }
    }
    fun setStatus(s: HostStatus) { viewModelScope.launch { c.hosts.setStatus(hostId, s) } }
    /** NonCancellable job: the last save fires as the screen leaves, when viewModelScope is about to be (or already is) cancelled. */
    fun setNote(n: String) { viewModelScope.launch(NonCancellable) { c.hosts.setNote(hostId, n) } }
    fun setOverrides(edits: DeviceEdits) { viewModelScope.launch { c.hosts.setOverrides(hostId, edits) } }
    fun runAction(a: DeviceAction) {
        if (c.actionRunner.run(a)) return
        c.actionRunner.run(DeviceAction.Copy("Copy IP", state.value.host?.ip ?: ""))
        message.value = "No app installed for that link. IP copied instead."
    }
    fun openLink(url: String) {
        if (c.actionRunner.run(DeviceAction.OpenUri("Open", url, true))) return
        c.actionRunner.run(DeviceAction.Copy("Copy link", url))
        message.value = "No browser found. Link copied."
    }
    fun dismissMessage() { message.value = null }

    fun lookupVulnsForService(port: Int) {
        val row = state.value.ports.firstOrNull { it.port == port } ?: return
        val q = c.vulnLookup.queryForService(row.service) ?: run { message.value = "No product or version known for port $port"; return }
        runVuln(q)
    }
    fun lookupVulnsForDevice() {
        val h = state.value.host ?: return
        if (RowRules.isOwn(h.ip, env.value)) { message.value = OWN_PHONE; return }
        val q = c.vulnLookup.queryForDevice(DeviceIdentity(Effective.vendor(h), Effective.model(h), h.identOs, Effective.type(h), h.identConfidence, h.identReasons))
            ?: run { message.value = "Device not identified well enough to search"; return }
        runVuln(q)
    }
    fun rerunVulnQuery(text: String) { if (text.isNotBlank()) runVuln(VulnQuery.ByKeyword(text.trim())) }
    fun closeVulns() { vulnJob?.cancel(); vuln.value = null }

    private fun runVuln(q: VulnQuery) {
        vulnJob?.cancel()
        vuln.value = VulnSheetState.Loading(q.text)
        vulnJob = viewModelScope.launch {
            val r = try { c.vulnLookup.run(q) } catch (ce: CancellationException) { throw ce } catch (e: Exception) {
                VulnResult.Failed(e.message ?: e.javaClass.simpleName, null)
            }
            // Show the query that actually produced the result (the keyword when a CPE query fell back).
            vuln.value = VulnSheetState.Ready((r as? VulnResult.Ok)?.queryText?.takeIf { it.isNotBlank() } ?: q.text, r)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val OWN_PHONE = "This is your phone; nothing to look up"
    }
}
