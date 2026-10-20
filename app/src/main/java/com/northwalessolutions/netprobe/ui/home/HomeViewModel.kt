package com.northwalessolutions.netprobe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.*
import android.util.Log
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryOptions
import com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.portscan.PortState
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
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
                   val subnet: String = "", val offSubnet: Boolean = false)
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false,
                       val lastScanAt: Long? = null, val totalHosts: Int = 0, val otherSubnetHosts: Int = 0, val scanStartedAt: Long? = null, val scanFinishedAt: Long? = null,
                       val groups: List<HostGroup> = emptyList(), val lastRunRanges: List<RangeProgress> = emptyList())

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
    fun of(h: HostEntity): String = of(h.identVendor, h.identModel, h.identOs, h.deviceType,
        onlyMdns = h.mdnsName != null && h.netbiosName == null && h.upnpName == null && h.mac == null)
}

/** Pure row rules: off-subnet labelling and the phone's own-address label (computed here, never stored as an identity). */
object RowRules {
    const val THIS_PHONE = "This phone"
    private const val OWN_LINE = "Android device"
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
            extraRanges: List<Subnet> = emptyList(), manualRanges: List<Subnet> = emptyList()): HostRow {
        val own = isOwn(h.ip, env)
        val name = if (own && h.customName.isNullOrBlank()) THIS_PHONE else DisplayName.of(h)
        val scanned = env?.let { listOf(SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, maxPrefix).subnet) + extraRanges }.orEmpty()
        val (subnet, off) = RangeRules.groupFor(h.ip, scanned, manualRanges)
        return HostRow(h.id, h.ip, name, if (own) OWN_LINE else IdentityLine.of(h), if (own) DeviceType.PHONE else h.deviceType, openPorts, h.status, h.isGateway, stale, h.mac, subnet, off)
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
    private val controller = ScanController(viewModelScope, env) { e -> discover(e) }
    /** Quick scan all is network-bound like discovery: a Wi-Fi switch or loss cancels it, and Stop cancels it. */
    private val quick = ScanController(viewModelScope, env) { quickScanAllOn() }
    private val portCounts: Flow<Map<Long, Int>> = c.ports.counts()

    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }

    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts,
        finishedAt, lastRunRanges, c.settings.manual.revision, lastRunProgress) { arr ->
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
        val maxPrefix = c.settings.state.value.maxPrefix
        val manual = if (c.settings.state.value.extEnabled) c.settings.manual.forNetwork(networkId.value) else emptyList()   // toggle off: the MVP path reads nothing extra
        val all = hs.map { h -> RowRules.row(h, e, maxPrefix, pc[h.id] ?: 0, stale = run > 0 && h.lastDiscoveryRun < run, extraRanges = extra, manualRanges = manual) }
        val stats = HomeStats.of(hs, all, done)
        val shown = HomeFilter.apply(all, f, q)
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, shown, f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning,
            lastScanAt = stats.lastScanAt, totalHosts = stats.totalHosts, otherSubnetHosts = stats.otherSubnetHosts,
            scanStartedAt = run.takeIf { it > 0 }, scanFinishedAt = done, groups = HomeGroups.of(shown),
            lastRunRanges = lastRun)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

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
            env.collectLatest { e ->
                val net = try { e?.let { c.networks.ensure(it, GatewayMac.lookup(it.gateway)) } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
                // "Scanned in N s" and the range list describe one network: a different network id clears both (ruling 1).
                val kept = RunMemory(finishedAt.value, lastRunRanges.value, lastRunProgress.value).afterNetworkChange(networkId.value, net?.id)
                finishedAt.value = kept.finishedAt; lastRunRanges.value = kept.swept; lastRunProgress.value = kept.progress
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

    private suspend fun discover(e: NetworkEnv) {
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)); networkId.value = net.id; c.currentNetwork.value = net
        val runId = System.currentTimeMillis(); latestRun.value = runId
        val s = c.settings.state.value
        val ext = if (!s.extEnabled) ExtendedOptions() else ExtendedOptions(enabled = true, maxRanges = s.extMax, gatewayProbe = s.extGatewayProbe,
            manual = c.settings.manual.get(net.id), knownIps = c.hosts.hostsForNetwork(net.id).first().map { it.ip }.toSet())
        if (ext.enabled) extraMessage.value = null   // extended runs only: a notice stays until dismissed or the next scan starts (Radar spec 4.4)
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, s.maxPrefix, extended = ext)
        val binder = if (ext.enabled) c.networkEnvMonitor.binderFor(e) else null   // null skips the extended phase with its notice
        if (ext.enabled) lastRunRanges.value = emptyList()
        lastRunProgress.value = emptyList()   // the range list always describes the latest run only
        val openPorts = HashMap<String, MutableSet<Int>>()   // ports seen open per ip during this run: later emissions for the host carry none
        var completed = false
        if (ext.enabled) c.extendedRunActive.value = true
        c.locks.acquire()
        try {
            c.discoveryEngine.discover(e, opts, binder).collect { h ->
                val id = c.hosts.upsertDiscovered(net.id, h, runId)
                val merged = c.hosts.byIdOnce(id) ?: return@collect
                val ports = openPorts.getOrPut(h.ip.hostAddress) { HashSet() }.also { it += h.probedOpenPorts }.toSet()
                // Stored port findings are part of the evidence, so a rediscovery never downgrades an identity a port scan established.
                val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(merged), c.ports.servicesFor(id), ports, emptyMap(), merged.mac?.let(c.ouiLookup))
                c.hosts.setIdentity(id, c.deviceIdentifier.identify(ev))
            }
            completed = true
        } finally {
            c.extendedRunActive.value = false
            c.locks.release()
            // Also on Stop or a network change: the engine has already marked unfinished ranges CANCELLED.
            val st = c.discoveryEngine.status.value
            st.gatewayProbe?.let { Log.i(EXT_LOG_TAG, "gateway probe: ${it.done} of ${it.total} units, ${it.attempts} connect attempts") }   // read by Task 20 check 3
            // A run that unwinds after a network switch publishes nothing: its ranges and notices describe the old network.
            RunEnd.of(networkId.value, net.id, ext.enabled, completed, st)?.let { end ->
                end.swept?.let { lastRunRanges.value = it }; end.progress?.let { lastRunProgress.value = it }
                end.notice?.let { extraMessage.value = it }
            }
        }
    }

    fun quickScanAll() { if (!quick.isRunning) quick.start() }
    fun stopQuickScan() = quick.stop()

    private suspend fun quickScanAllOn() {
        var failed = 0
        try {
            val net = networkId.value ?: return
            for (h in c.hosts.hostsForNetwork(net).first()) {
                // One unreachable or odd host must not end the sweep for the rest.
                try { quickScanHost(h) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { failed++ }
            }
        } finally {
            if (failed > 0) extraMessage.value = "Quick scan finished, $failed device(s) could not be scanned"
        }
    }

    private suspend fun quickScanHost(h: HostEntity) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        val types = c.hosts.mdnsTypesFor(h.id)                 // once per host, not per port
        val found = HashMap<Int, ServiceInfo>()
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
            c.portScanEngine.scan(ip, ScanProfile.Quick, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                launch { sem.withPermit {
                    val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer))
                    synchronized(found) { found[r.port] = info }
                    c.ports.upsertOpen(h.id, r.port, info)
                } }
            }
        }
        c.ports.markScanned(h.id)
        c.hosts.byIdOnce(h.id)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(h.id), found.keys.toSet(), found, e.mac?.let(c.ouiLookup))
            c.hosts.setIdentity(h.id, c.deviceIdentifier.identify(ev))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val LOCATION_OFF = "Turn on Location to show the Wi-Fi name"
        const val LOCATION_DENIED = "Wi-Fi name stays hidden without location permission"
    }
}
