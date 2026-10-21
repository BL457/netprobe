package com.northwalessolutions.netprobe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.PendingUndo
import com.northwalessolutions.netprobe.data.*
import android.util.Log
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryOptions
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.portscan.PortState
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
import com.northwalessolutions.netprobe.ui.scans.ScansCopy
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress

private const val EXT_LOG_TAG = "NetProbeExt"

enum class StatusFilter(val label: String) { ALL("All"), INTERESTING("Interesting"), INVESTIGATED("Investigated"), IGNORED("Ignored"), UNMARKED("Unmarked") }
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?,
                   val subnet: String = "", val offSubnet: Boolean = false, val running: Boolean = false, val runningIsScan: Boolean = false)
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false,
                       val lastScanAt: Long? = null, val totalHosts: Int = 0, val otherSubnetHosts: Int = 0, val scanStartedAt: Long? = null, val scanFinishedAt: Long? = null,
                       val groups: List<HostGroup> = emptyList(), val lastRunRanges: List<RangeProgress> = emptyList(),
                       val scanCompleted: Boolean = true, val networkId: Long? = null, val undoText: String? = null,
                       val appMessage: String? = null, val appMessageId: Long? = null, val workRunningOnNetwork: Boolean = false,
                       val homeRange: String? = null)

object IdentityLine {
    val DeviceType.label: String get() = when (this) { DeviceType.ROUTER -> "Router"; DeviceType.PHONE -> "Phone"; DeviceType.COMPUTER -> "Computer"; DeviceType.PRINTER -> "Printer"
        DeviceType.MEDIA -> "Media"; DeviceType.IOT -> "IoT"; DeviceType.NAS -> "NAS"; DeviceType.CAMERA -> "Camera"; DeviceType.SERVER -> "Server"; DeviceType.UNKNOWN -> "" }
    fun of(vendor: String?, model: String?, os: String?, type: DeviceType, onlyMdns: Boolean): String {
        // Models such as "Google Nest Hub" already start with the vendor; do not print it twice.
        val v = vendor?.takeUnless { model != null && model.startsWith(it, ignoreCase = true) }
        val vm = listOfNotNull(v, model).joinToString(" ").trim(); if (vm.isNotEmpty()) return vm
        val ot = listOfNotNull(os, type.label.takeIf { it.isNotEmpty() }).joinToString(" ").trim(); if (ot.isNotEmpty()) return ot
        return if (onlyMdns) "Unknown device, found via mDNS" else "Unknown device"
    }
    /** Same rule from a stored host; "only mDNS" means no NetBIOS, UPnP or MAC evidence alongside the mDNS name. */
    fun of(h: HostEntity): String = of(Effective.vendor(h), Effective.model(h), h.identOs, Effective.type(h),
        onlyMdns = h.mdnsName != null && h.netbiosName == null && h.upnpName == null && h.mac == null)
}

/** Pure row rules: off-subnet labelling and the phone's own-address label (computed here, never stored as an identity). */
object RowRules {
    const val THIS_PHONE = "This phone"
    const val OWN_LINE = "Android device"
    private const val OCTETS = 4
    private const val OCTET_MAX = 255

    /** Dotted-quad literal to an address without any name lookup; null for anything else. */
    fun parseIpv4(ip: String): Inet4Address? {
        val p = ip.split('.')
        if (p.size != OCTETS || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val n = p.map { it.toInt() }.takeIf { o -> o.all { it <= OCTET_MAX } } ?: return null
        return InetAddress.getByAddress(ByteArray(OCTETS) { n[it].toByte() }) as Inet4Address
    }

    fun isOwn(ip: String, env: NetworkEnv?): Boolean = env != null && ip == env.interfaceIp.hostAddress

    fun row(h: HostEntity, env: NetworkEnv?, maxPrefix: Int, openPorts: Int, stale: Boolean,
            extraRanges: List<Subnet> = emptyList(), manualRanges: List<Subnet> = emptyList(), aggressive: Boolean = false): HostRow {
        val own = isOwn(h.ip, env)
        val name = if (own && h.customName.isNullOrBlank()) THIS_PHONE else DisplayName.of(h)
        val scanned = env?.let { listOf(HomeRange.of(it.interfaceIp, it.prefixLength, maxPrefix, aggressive).subnet) + extraRanges }.orEmpty()
        val (subnet, off) = RangeRules.groupFor(h.ip, scanned, manualRanges)
        // The own phone keeps "Android device" (and Build.MODEL on Home) unless the Director set a model; its type stays PHONE unless set.
        val line = if (own && h.userModel == null) OWN_LINE else IdentityLine.of(h)
        val type = if (own) h.userType ?: DeviceType.PHONE else Effective.type(h)
        return HostRow(h.id, h.ip, name, line, type, openPorts, h.status, h.isGateway, stale, h.mac, subnet, off)
    }
}

object HomeFilter {
    private const val OCTET = 256
    private fun ipKey(ip: String): Long = ip.split('.').fold(0L) { a, p -> a * OCTET + (p.toLongOrNull() ?: 0) }
    fun apply(rows: List<HostRow>, filter: StatusFilter, query: String): List<HostRow> {
        val q = query.trim().lowercase()
        return rows.filter { r -> when (filter) { StatusFilter.ALL -> true; StatusFilter.INTERESTING -> r.status == HostStatus.INTERESTING
                StatusFilter.INVESTIGATED -> r.status == HostStatus.INVESTIGATED; StatusFilter.IGNORED -> r.status == HostStatus.IGNORED; StatusFilter.UNMARKED -> r.status == HostStatus.UNMARKED } }
            .filter { r -> q.isEmpty() || r.displayName.lowercase().contains(q) || r.ip.contains(q) || r.identityLine.lowercase().contains(q) || (r.mac?.lowercase()?.contains(q) == true) }
            .sortedWith(compareByDescending<HostRow> { it.isGateway }.thenBy { ipKey(it.ip) })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    /** Fed by the monitor; [refreshEnv] re-reads it directly (the SSID appears after a permission grant without a link change). */
    private val env = MutableStateFlow(c.networkEnvMonitor.current())
    private val filter = MutableStateFlow(StatusFilter.ALL); private val query = MutableStateFlow("")
    private val networkId = MutableStateFlow<Long?>(null); private val latestRun = MutableStateFlow(0L)
    private val extraMessage = MutableStateFlow<String?>(null)
    private val finishedAt = MutableStateFlow<Long?>(null)
    /** The last extended run's swept ranges; memory only, replaced at the next extended run (extended spec 5.6). */
    private val lastRunRanges = MutableStateFlow<List<Subnet>>(emptyList())
    /** The same run's full range list for the done summary (extended spec 8.2); cleared at every scan start and on a network change. */
    private val lastRunProgress = MutableStateFlow<List<RangeProgress>>(emptyList())
    /** False from the start of a run until it completes; a stopped or failed run keeps it false so the summary reads "Stopped after N s" (spec 5.4). */
    private val lastRunCompleted = MutableStateFlow(true)
    /** The radar run's settings (3C spec 2.2, D21): grouping and the hero read it while the scan runs, so a mid-run toggle or reset never regroups the running list. Memory only; a network change clears it. */
    private val runSnapshot = MutableStateFlow<RunSettings?>(null)
    private val controller = ScanController(viewModelScope, env) { e -> discover(e) }
    /** Quick scan all is network-bound like discovery: a Wi-Fi switch or loss cancels it, and Stop cancels it. */
    private val quick = ScanController(viewModelScope, env) { e -> quickScanAllOn(e) }
    private val portCounts: Flow<Map<Long, Int>> = c.ports.counts()

    private val hosts: Flow<List<HostEntity>> = combine(networkId, c.forgottenNetworkId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }
    /** Every row of the shown network, manual targets included (3C spec 7.1): Forget stays disabled while a tool holds a hidden row. */
    private val allHosts: Flow<List<HostEntity>> = combine(networkId, c.forgottenNetworkId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.allHostsForNetwork(id) }

    private val baseState: Flow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts,
        finishedAt, lastRunRanges, c.settings.manual.revision, lastRunProgress, lastRunCompleted, networkId, c.undo.pending, runSnapshot, c.settings.state) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        @Suppress("UNCHECKED_CAST")
        val extra = arr[11] as List<Subnet>
        @Suppress("UNCHECKED_CAST")
        val lastRun = arr[13] as List<RangeProgress>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as ScanController.State; val msg = arr[8] as String?
        val done = arr[10] as Long?
        // The running scan's snapshot while it runs, the live settings otherwise (3C spec 2.2, plan review G7); manual ranges are read whatever Aggressive scan says (D8).
        val runSettings = RunSettings.forDisplay(arr[17] as RunSettings?, cs.scanning, arr[18] as SettingsState, c.settings.manual.forNetwork(networkId.value))
        val all = hs.map { h -> RowRules.row(h, e, runSettings.maxPrefix, pc[h.id] ?: 0, stale = StaleRule.of(run, h), extraRanges = extra,
            manualRanges = runSettings.manual, aggressive = runSettings.aggressive) }
        val stats = HomeStats.of(hs, all, done)
        val shown = HomeFilter.apply(all, f, q)
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, shown, f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning,
            lastScanAt = stats.lastScanAt, totalHosts = stats.totalHosts, otherSubnetHosts = stats.otherSubnetHosts,
            scanStartedAt = run.takeIf { it > 0 }, scanFinishedAt = done, groups = HomeGroups.of(shown),
            lastRunRanges = lastRun, scanCompleted = arr[14] as Boolean,
            networkId = arr[15] as Long?, undoText = (arr[16] as PendingUndo?)?.text, homeRange = e?.let { runSettings.home(it).toString() })
    }

    /** Rows held by a port scan or a tool show "running"; the newest end-of-run message is offered to the notice line (spec 4.3, 8.8).
     *  [HomeUiState.workRunningOnNetwork] reads the `allHosts` flow (every row of the shown network, manual targets included, not only the filtered rows), so
     *  Forget this network stays disabled while a port scan or a tool holds any of them (3D spec D19). */
    val state: StateFlow<HomeUiState> = combine(baseState, c.hostBusy.busy, c.appMessages.current, allHosts) { s, busy, message, hs ->
        val rows = s.rows.map { r -> busy[r.id]?.let { w -> r.copy(running = true, runningIsScan = w == HostBusyRegistry.Work.PORT_SCAN) } ?: r }
        s.copy(rows = rows, groups = HomeGroups.of(rows), appMessage = message?.text, appMessageId = message?.id,
            workRunningOnNetwork = ForgetGuard.workRunning(hs, busy))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    fun dismissAppMessage() { state.value.appMessageId?.let { c.appMessages.dismiss(it) } }

    init {
        viewModelScope.launch {
            var was = false
            controller.state.collect { st ->
                // A scan the network change cancelled never finished on any network: no "Scanned in N s" for it (ruling 1).
                finishedAt.value = ScanFinish.fromController(st, was, finishedAt.value, System.currentTimeMillis())
                was = st.scanning
            }
        }
        viewModelScope.launch { c.networkEnvMonitor.env.collect { env.value = it } }
        viewModelScope.launch {
            combine(env, c.networkEpoch) { e, _ -> e }.collectLatest { e ->
                val net = try { e?.let { c.networks.ensure(it, GatewayMac.lookup(it.gateway)) } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
                // "Scanned in N s" and the range list describe one network: a different network id clears both (ruling 1).
                val kept = RunMemory(finishedAt.value, lastRunRanges.value, lastRunProgress.value, runSnapshot.value).afterNetworkChange(networkId.value, net?.id)
                finishedAt.value = kept.finishedAt; lastRunRanges.value = kept.swept; lastRunProgress.value = kept.progress; runSnapshot.value = kept.settings
                latestRun.value = StaleRun.seed(networkId.value, net?.id, latestRun.value, net?.lastRunAt)
                networkId.value = net?.id; c.currentNetwork.value = net
            }
        }
    }

    fun startScan() = controller.start()
    fun stopScan() = controller.stop()
    fun refreshEnv(locationGranted: Boolean) {
        val e = c.networkEnvMonitor.current(); env.value = e
        // Granted but still no name: system Location is off, which the permission dialog cannot fix.
        if (locationGranted && e != null && e.transport == Transport.WIFI && e.ssid == null) extraMessage.value = LOCATION_OFF
        else if (!locationGranted) extraMessage.value = LOCATION_DENIED
    }
    fun setFilter(f: StatusFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun dismissMessage() { controller.dismissMessage(); quick.dismissMessage(); extraMessage.value = null }

    /** A pending Undo is final before the scan's first lookup (spec 5.3, Review Focus 4). */
    private suspend fun discover(e: NetworkEnv) {
        c.undo.committedBefore {
            // No suspension between the commit and these lines (review M2): from here a Remove is marked for this scan (spec 5.3 step 2).
            c.removedDuringScan.value = emptySet()          // a new radar scan starts with nothing removed
            c.removedWhileListening.value = emptySet()      // 3C spec 3.4 rule 5: after a scan the listener may add a device again
            c.radarScanActive.value = true
            try { trackRadar { discoverOn(e) } } finally { c.radarScanActive.value = false; c.radarScanNetworkId.value = null }
        }
    }
    /**
     * The radar scan as a Scans item (3D spec 3.2): Stop there calls [stopScan]. [body] returns the devices found, or null when the scan
     * did not run (a Forget in flight), which removes the entry without recording it. A cleared ViewModel cancels the job, which ends
     * the entry STOPPED, so a Scans row never stays RUNNING with a Stop that does nothing.
     */
    private suspend fun trackRadar(body: suspend () -> Int?) {
        c.liveWork.tracked<Int>(LiveWork.Kind.RADAR, c.currentNetwork.value?.id, null, null, stop = { stopScan() },
            summarize = { found, seconds -> LiveWork.Outcome.DONE to ScansCopy.radarDone(found, seconds) },
            stopped = { seconds -> ScansCopy.stoppedAfter(seconds) }, failed = { t -> ScanController.failureMessage(t) }) { body() }
    }

    private suspend fun quickScanAllOn(e: NetworkEnv) {
        c.undo.committedBefore {
            c.liveWork.tracked<Int>(LiveWork.Kind.QUICK_SCAN_ALL, networkId.value, null, null, stop = { stopQuickScan() },
                summarize = { scanned, seconds -> LiveWork.Outcome.DONE to ScansCopy.quickDone(scanned, seconds) },
                stopped = { seconds -> ScansCopy.stoppedAfter(seconds) }, failed = { t -> ScanController.failureMessage(t) }) { id -> quickScanAllBody(e, id) }
        }
    }

    private suspend fun discoverOn(e: NetworkEnv): Int? {
        runSnapshot.value = null   // the previous run's snapshot must not group the start frames of this one; live settings show until the new snapshot is taken
        // null only while this network's Forget is pending, which committedBefore has already ended; the guard stays for a Forget racing in.
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)) ?: return null
        if (net.id == c.forgottenNetworkId.value) return null   // a Forget on this network is in flight: it is unavailable
        c.radarScanNetworkId.value = net.id
        networkId.value = net.id; c.currentNetwork.value = net
        val runId = System.currentTimeMillis(); latestRun.value = runId
        lastRunCompleted.value = false
        val s = c.settings.state.value
        // One read at the start (3C spec D21): this run's home range, binding and grouping use only this snapshot.
        val runSettings = RunSettings.of(s, c.settings.manual.get(net.id))
        runSnapshot.value = runSettings
        // The stored IPs are read only when the extended phase will run (canary picks); the MVP path reads nothing extra.
        // Every stored IP, manual targets included, so a canary is never an address a manual tool is probing (3C spec 7.1).
        val known = if (runSettings.bindOutside) c.hosts.allHostsForNetwork(net.id).first().map { it.ip }.toSet() else emptySet()
        val ext = ExtendedOptionsOf.from(runSettings, s, known)
        if (ext.enabled) extraMessage.value = null   // extended runs only: a notice stays until dismissed or the next scan starts (Radar spec 4.4)
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, runSettings.maxPrefix,
            extended = ext, aggressive = runSettings.aggressive)
        val binder = if (ext.enabled) c.networkEnvMonitor.binderFor(e) else null   // null skips the extended phase with its notice
        if (ext.enabled) lastRunRanges.value = emptyList()
        lastRunProgress.value = emptyList()   // the range list always describes the latest run only
        var completed = false
        var found = 0
        c.locks.acquire()
        try {
            // Inside the try (Task 19 M1): if anything before here throws, the window is never left holding FLAG_KEEP_SCREEN_ON.
            if (ext.enabled) c.extendedRunActive.value = true
            val withRuns = c.toolRepo.hostsWithRuns()
            val collector = RadarCollector(c.hosts, c.ports, c.deviceIdentifier, c.ouiLookup, c.hostWriteLock,
                hintsFor = { id -> if (id in withRuns) c.toolRepo.hintsFor(id) else emptyList() }) { c.removedDuringScan.value }
            c.discoveryEngine.discover(e, opts, binder).collect { h -> collector.store(net.id, h, runId) }
            completed = true; lastRunCompleted.value = true
            found = c.discoveryEngine.status.value.found
        } finally {
            c.extendedRunActive.value = false
            c.locks.release()
            // runCatching: a database error here must not skip the RunEnd publication below.
            StaleRun.toPersist(completed, runId)?.let { id -> withContext(NonCancellable) { runCatching { c.networks.markRun(net.id, id) }.onFailure { Log.w(EXT_LOG_TAG, "markRun failed", it) } } }
            // Also on Stop or a network change: the engine has already marked unfinished ranges CANCELLED.
            val st = c.discoveryEngine.status.value
            st.gatewayProbe?.let { Log.i(EXT_LOG_TAG, "gateway probe: ${it.done} of ${it.total} units, ${it.attempts} connect attempts") }   // read by Task 20 check 3
            // A run that unwinds after a network switch publishes nothing: its ranges and notices describe the old network.
            RunEnd.of(networkId.value, net.id, ext.enabled, completed, st, aggressive = runSettings.aggressive)?.let { end ->
                end.swept?.let { lastRunRanges.value = it }; end.progress?.let { lastRunProgress.value = it }
                end.notice?.let { extraMessage.value = it }
            }
        }
        return found
    }

    /** Home overflow "Forget this network": a soft delete until the Undo ends; the UI disables it while a scan or the settle runs (spec 5.3). */
    fun forgetNetwork() {
        if (!ForgetGuard.allowed(controller.isRunning, quick.isRunning, state.value.workRunningOnNetwork)) return
        val net = c.currentNetwork.value ?: return
        if (c.forgottenNetworkId.value == net.id) return   // a double tap never posts twice
        // Set before launching: Home hides the rows and discoverOn refuses this network from this line on, so no Scan can start on it.
        c.forgottenNetworkId.value = net.id
        c.appScope.launch {
            try {
                c.networks.setForgotten(net.id, System.currentTimeMillis())
                c.networkEpoch.update { it + 1 }                // ensure() now returns null for this network: Home shows it empty
                // The lambdas touch only process-wide state, so an Undo still works after the activity is recreated (G4).
                // compareAndSet: a second Forget's id is never wiped by this one's end. The finally blocks never leave the stuck state.
                c.undo.post(PendingUndo(HomeCopy.forgot(net.displayName),
                    restore = {
                        try { c.networks.setForgotten(net.id, null) }
                        finally { c.forgottenNetworkId.compareAndSet(net.id, null); c.networkEpoch.update { it + 1 } }
                    },
                    commit = {
                        try {
                            c.settings.manual.set(net.id, emptyList())   // key first: a crash after the cascade then leaves no stray key
                            c.networks.deleteNetworkCascade(net.id)
                        } finally { c.forgottenNetworkId.compareAndSet(net.id, null); c.networkEpoch.update { it + 1 } }   // Home then inserts a fresh row
                    }))
            } catch (t: Throwable) {
                c.forgottenNetworkId.compareAndSet(net.id, null); c.networkEpoch.update { it + 1 }
                throw t
            }
        }
    }
    fun undo() { c.appScope.launch { c.undo.undo() } }
    /** Dismissing the Undo notice commits it; a notice showing before it is not dismissed (spec 5.3). */
    fun dismissUndo() { c.appScope.launch { c.undo.commit() } }

    fun quickScanAll() { if (!quick.isRunning) quick.start() }
    fun stopQuickScan() = quick.stop()

    private suspend fun quickScanAllBody(e: NetworkEnv, liveId: Long): Int? {
        var failed = 0; var unbound = 0; var skipped = 0; var scanned = 0; var handled = 0
        try {
            val net = networkId.value ?: return null
            // One settings read per Quick scan all (3C spec D21): a toggle mid-run never changes which hosts are bound.
            val runSettings = RunSettings.of(c.settings.state.value, c.settings.manual.forNetwork(net))
            val toScan = c.hosts.hostsForNetwork(net).first()
            c.liveWork.progress(liveId, 0, toScan.size, 0)
            QuickScanAllLoop.run(toScan, { it.id },
                { RemovedHosts.skipQuickScan(c.removedHostIds.value, c.removedDuringScan.value, net, it.id, it.ip) }, c.hostScanJobs) scanOne@{ h ->
                // A host a tool holds is skipped and counted (spec 4.3); a host another port scan holds is skipped silently.
                if (!c.hostBusy.tryClaim(h.id, HostBusyRegistry.Work.PORT_SCAN)) {
                    if (c.hostBusy.busy.value[h.id] == HostBusyRegistry.Work.TOOL) skipped++
                    return@scanOne
                }
                try {
                    // One unreachable or odd host must not end the sweep for the rest; an off-subnet host that cannot be bound is skipped and said so.
                    try { quickScanHost(h, e, runSettings); scanned++ } catch (ce: CancellationException) { throw ce } catch (_: BindFailedException) { unbound++ } catch (_: Exception) { failed++ }
                } finally {
                    c.hostBusy.release(h.id, HostBusyRegistry.Work.PORT_SCAN)
                    c.liveWork.progress(liveId, ++handled, toScan.size, 0)
                }
            }
        } finally {
            val parts = listOfNotNull(ToolCopy.quickEnd(failed, skipped), if (unbound > 0) HomeCopy.BIND_FAILED else null)
            if (parts.isNotEmpty()) extraMessage.value = parts.joinToString(". ")
        }
        return scanned
    }

    private suspend fun quickScanHost(h: HostEntity, e: NetworkEnv, runSettings: RunSettings) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        ScanBinding.forRun(ip, e, runSettings, c.networkEnvMonitor::binderFor) { quickScanHostOn(h, ip) }
    }

    private suspend fun quickScanHostOn(h: HostEntity, ip: Inet4Address) {
        val types = c.hosts.mdnsTypesFor(h.id)                 // once per host, not per port
        val found = HashMap<Int, ServiceInfo>()
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
            c.portScanEngine.scan(ip, QuickScanAllPolicy.profile, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                launch { sem.withPermit {
                    val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), QuickScanAllPolicy.mode())
                    synchronized(found) { found[r.port] = info }
                    c.ports.upsertOpenIfHostExists(h.id, r.port, info)
                } }
            }
        }
        c.ports.markScanned(h.id)
        c.hosts.byIdOnce(h.id)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(h.id), found.keys.toSet(), found, e.mac?.let(c.ouiLookup), c.toolRepo.hintsFor(h.id))
            c.hosts.setIdentityIfNotWeaker(h.id, c.deviceIdentifier.identify(ev))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOCATION_OFF = "Turn on Location to show the Wi-Fi name"
        const val LOCATION_DENIED = "Wi-Fi name stays hidden without location permission"
    }
}
