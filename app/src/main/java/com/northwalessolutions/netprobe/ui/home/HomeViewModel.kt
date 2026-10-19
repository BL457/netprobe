package com.northwalessolutions.netprobe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryOptions
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

enum class StatusFilter(val label: String) { ALL("All"), INTERESTING("Interesting"), INVESTIGATED("Investigated"), IGNORED("Ignored"), UNMARKED("Unmarked") }
data class HostRow(val id: Long, val ip: String, val displayName: String, val identityLine: String, val type: DeviceType, val openPorts: Int, val status: HostStatus, val isGateway: Boolean, val stale: Boolean, val mac: String?,
                   val otherSubnet: String? = null) { val offSubnet: Boolean get() = otherSubnet != null }
data class HomeUiState(val env: NetworkEnv? = null, val scanning: Boolean = false, val status: DiscoveryStatus? = null, val rows: List<HostRow> = emptyList(),
                       val filter: StatusFilter = StatusFilter.ALL, val query: String = "", val message: String? = null, val networkName: String? = null, val quickScanning: Boolean = false)

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
    private const val OTHER_SUBNET_PREFIX = 24
    private const val OCTETS = 4
    private const val OCTET_MAX = 255

    /** Dotted-quad literal to an address without any name lookup; null for anything else. */
    fun parseIpv4(ip: String): Inet4Address? {
        val p = ip.split('.')
        if (p.size != OCTETS || p.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val n = p.map { it.toInt() }.takeIf { o -> o.all { it <= OCTET_MAX } } ?: return null
        return InetAddress.getByAddress(ByteArray(OCTETS) { n[it].toByte() }) as Inet4Address
    }

    /** The /24 of [ip] (for example "10.0.4.0/24") when it lies outside the scanned [scanned] subnet, else null. */
    fun otherSubnet(ip: String, scanned: Subnet): String? {
        val a = parseIpv4(ip) ?: return null
        return if (scanned.contains(a)) null else SubnetMath.of(a, OTHER_SUBNET_PREFIX).toString()
    }

    fun isOwn(ip: String, env: NetworkEnv?): Boolean = env != null && ip == env.interfaceIp.hostAddress

    fun row(h: HostEntity, env: NetworkEnv?, maxPrefix: Int, openPorts: Int, stale: Boolean): HostRow {
        val own = isOwn(h.ip, env)
        val name = if (own && h.customName.isNullOrBlank()) THIS_PHONE else DisplayName.of(h)
        val other = env?.let { otherSubnet(h.ip, SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, maxPrefix).subnet) }
        return HostRow(h.id, h.ip, name, if (own) OWN_LINE else IdentityLine.of(h), if (own) DeviceType.PHONE else h.deviceType, openPorts, h.status, h.isGateway, stale, h.mac, other)
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
    private val controller = ScanController(viewModelScope, env) { e -> discover(e) }
    /** Quick scan all is network-bound like discovery: a Wi-Fi switch or loss cancels it, and Stop cancels it. */
    private val quick = ScanController(viewModelScope, env) { quickScanAllOn() }
    private val portCounts: Flow<Map<Long, Int>> = c.ports.counts()

    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }

    val state: StateFlow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as ScanController.State; val msg = arr[8] as String?
        val maxPrefix = c.settings.state.value.maxPrefix
        val rows = hs.map { h -> RowRules.row(h, e, maxPrefix, pc[h.id] ?: 0, stale = run > 0 && h.lastDiscoveryRun < run) }
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, HomeFilter.apply(rows, f, q), f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    init {
        viewModelScope.launch { c.networkEnvMonitor.env.collect { env.value = it } }
        viewModelScope.launch {
            env.collectLatest { e ->
                networkId.value = try { e?.let { c.networks.ensure(it, GatewayMac.lookup(it.gateway)).id } } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }
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
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)); networkId.value = net.id
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
                // Stored port findings are part of the evidence, so a rediscovery never downgrades an identity a port scan established.
                val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(merged), c.ports.servicesFor(id), ports, emptyMap(), merged.mac?.let(c.ouiLookup))
                c.hosts.setIdentity(id, c.deviceIdentifier.identify(ev))
            }
        } finally { c.locks.release() }
        val st = c.discoveryEngine.status.value
        if (st.clampedFrom != null) extraMessage.value = "Large network (/${st.clampedFrom}). Scanned your /24 only. Widen in Settings."
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
