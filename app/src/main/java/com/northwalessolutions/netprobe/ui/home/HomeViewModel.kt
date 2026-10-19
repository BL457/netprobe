package com.northwalessolutions.netprobe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryOptions
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.portscan.PortState
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress

enum class StatusFilter(val label: String) { ALL("All"), INTERESTING("Interesting"), INVESTIGATED("Investigated"), IGNORED("Ignored"), UNMARKED("Unmarked") }
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?)
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false)

object IdentityLine {
    val DeviceType.label: String get() = when (this) { DeviceType.ROUTER -> "Router"; DeviceType.PHONE -> "Phone"; DeviceType.COMPUTER -> "Computer"; DeviceType.PRINTER -> "Printer"
        DeviceType.MEDIA -> "Media"; DeviceType.IOT -> "IoT"; DeviceType.NAS -> "NAS"; DeviceType.CAMERA -> "Camera"; DeviceType.SERVER -> "Server"; DeviceType.UNKNOWN -> "" }
    fun of(vendor: String?, model: String?, os: String?, type: DeviceType, onlyMdns: Boolean): String {
        val vm = listOfNotNull(vendor, model).joinToString(" ").trim(); if (vm.isNotEmpty()) return vm
        val ot = listOfNotNull(os, type.label.takeIf { it.isNotEmpty() }).joinToString(" ").trim(); if (ot.isNotEmpty()) return ot
        return if (onlyMdns) "Unknown device, found via mDNS" else "Unknown device"
    }
    /** Same rule from a stored host; "only mDNS" means no NetBIOS, UPnP or MAC evidence alongside the mDNS name. */
    fun of(h: HostEntity): String = of(h.identVendor, h.identModel, h.identOs, h.deviceType,
        onlyMdns = h.mdnsName != null && h.netbiosName == null && h.upnpName == null && h.mac == null)
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
    private val env = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    private val filter = MutableStateFlow(StatusFilter.ALL); private val query = MutableStateFlow("")
    private val networkId = MutableStateFlow<Long?>(null); private val latestRun = MutableStateFlow(0L)
    private val quickScanning = MutableStateFlow(false); private val extraMessage = MutableStateFlow<String?>(null)
    private val controller = ScanController(viewModelScope, env) { e -> discover(e) }
    private val portCounts: Flow<Map<Long, Int>> = c.ports.counts()

    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }

    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quickScanning, extraMessage, portCounts) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as Boolean; val msg = arr[8] as String?
        val rows = hs.map { h -> HostRow(h.id, h.ip, DisplayName.of(h), IdentityLine.of(h), h.deviceType, pc[h.id] ?: 0, h.status, h.isGateway,
            stale = run > 0 && h.lastDiscoveryRun < run, mac = h.mac) }
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, HomeFilter.apply(rows, f, q), f, q, cs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    init {
        viewModelScope.launch {
            env.collect { e ->
                networkId.value = try { e?.let { c.networks.ensure(it).id } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
            }
        }
    }

    fun startScan() = controller.start()
    fun stopScan() = controller.stop()
    fun setFilter(f: StatusFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun dismissMessage() { controller.dismissMessage(); extraMessage.value = null }

    private suspend fun discover(e: NetworkEnv) {
        val net = c.networks.ensure(e); networkId.value = net.id
        val runId = System.currentTimeMillis(); latestRun.value = runId
        val s = c.settings.state.value
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, s.maxPrefix)
        val openPorts = HashMap<String, MutableSet<Int>>()   // ports seen open per ip during this run: later emissions for the host carry none
        c.locks.acquire()
        try {
            c.discoveryEngine.discover(e, opts).collect { h ->
                val id = c.hosts.upsertDiscovered(net.id, h, runId)
                val merged = c.hosts.byIdOnce(id) ?: return@collect
                val ports = openPorts.getOrPut(h.ip.hostAddress) { HashSet() }.also { it += h.probedOpenPorts }.toSet()
                c.hosts.setIdentity(id, c.deviceIdentifier.identify(HostEvidence(c.hosts.toDiscovered(merged), ports, emptyMap(), merged.mac?.let(c.ouiLookup))))
            }
        } finally { c.locks.release() }
        val st = c.discoveryEngine.status.value
        if (st.clampedFrom != null) extraMessage.value = "Large network (/${st.clampedFrom}). Scanned your /24 only. Widen in Settings."
    }

    fun quickScanAll() {
        if (quickScanning.value) return
        viewModelScope.launch {
            quickScanning.value = true
            var failed = 0
            try {
                val net = networkId.value ?: return@launch
                for (h in c.hosts.hostsForNetwork(net).first()) {
                    // One unreachable or odd host must not end the sweep for the rest.
                    try { quickScanHost(h) } catch (ce: CancellationException) { throw ce } catch (_: Exception) { failed++ }
                }
            } finally {
                quickScanning.value = false
                if (failed > 0) extraMessage.value = "Quick scan finished, $failed device(s) could not be scanned"
            }
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
        c.hosts.byIdOnce(h.id)?.let { e -> c.hosts.setIdentity(h.id, c.deviceIdentifier.identify(HostEvidence(c.hosts.toDiscovered(e), found.keys.toSet(), found, e.mac?.let(c.ouiLookup)))) }
    }

    private companion object { const val STOP_TIMEOUT_MS = 5_000L }
}
