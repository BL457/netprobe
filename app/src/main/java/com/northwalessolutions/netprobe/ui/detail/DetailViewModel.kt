package com.northwalessolutions.netprobe.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.portscan.*
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.vulns.VulnQuery
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.home.RowRules
import com.northwalessolutions.netprobe.ui.home.ScanBinding
import com.northwalessolutions.netprobe.ui.home.ScanController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress

data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>)
sealed class VulnSheetState { data class Loading(val query: String) : VulnSheetState(); data class Ready(val query: String, val result: VulnResult) : VulnSheetState() }
data class DetailUiState(val host: HostEntity? = null, val displayName: String = "", val ports: List<PortRow> = emptyList(), val scanning: Boolean = false, val progress: ScanProgress? = null,
                         val profile: ScanProfile = ScanProfile.Quick, val customPortsText: String = "", val customError: String? = null, val message: String? = null, val vuln: VulnSheetState? = null)

class DetailViewModel(private val c: AppContainer, private val hostId: Long) : ViewModel() {
    private val profile = MutableStateFlow<ScanProfile>(ScanProfile.Quick); private val customText = MutableStateFlow(""); private val customError = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null); private val vuln = MutableStateFlow<VulnSheetState?>(null)
    private val env: StateFlow<NetworkEnv?> = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    /** Port scans are network-bound: losing or switching Wi-Fi cancels them, so they never probe the same IP on another network. */
    private val scan = ScanController(viewModelScope, env) { }
    private var vulnJob: Job? = null

    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), scan.state, c.portScanEngine.progress, profile, customText, customError, message, vuln, env) { arr ->
        @Suppress("UNCHECKED_CAST")
        val ps = arr[1] as List<PortEntity>
        val h = arr[0] as HostEntity?; val sc = arr[2] as ScanController.State; val pr = arr[3] as ScanProgress?; val e = arr[9] as NetworkEnv?
        val rows = ps.map { p -> val info = p.toServiceInfo()
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList()) }
        val name = h?.let { if (RowRules.isOwn(it.ip, e) && it.customName.isNullOrBlank()) RowRules.THIS_PHONE else DisplayName.of(it) } ?: ""
        DetailUiState(h, name, rows, sc.scanning, if (sc.scanning) pr else null, arr[4] as ScanProfile, arr[5] as String, arr[6] as String?, sc.message ?: arr[7] as String?, arr[8] as VulnSheetState?)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

    fun setProfile(p: ScanProfile) { profile.value = p; customError.value = null }
    fun setCustomPorts(t: String) { customText.value = t; customError.value = null }

    fun startScan() {
        if (scan.isRunning) return
        val h = state.value.host ?: return
        val prof = profile.value
        val resolved = if (prof is ScanProfile.Custom) ProfileParser.parse(customText.value).getOrElse { customError.value = it.message; return } else prof
        scan.start { e ->
            // An off-subnet row that cannot be bound to Wi-Fi is not scanned at all (final review I2): say so, never scan it unbound.
            try { scanHost(h, resolved, e) } catch (_: BindFailedException) { message.value = HomeCopy.BIND_FAILED }
        }
    }

    private suspend fun scanHost(h: HostEntity, profile: ScanProfile, e: NetworkEnv) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        val s = c.settings.state.value
        ScanBinding.run(ip, e, s.maxPrefix, s.extEnabled, c.networkEnvMonitor::binderFor) { scanHostOn(h, profile, ip) }
    }

    private suspend fun scanHostOn(h: HostEntity, profile: ScanProfile, ip: Inet4Address) {
        val types = c.hosts.mdnsTypesFor(hostId)                  // once per scan, not per port
        val found = HashMap<Int, ServiceInfo>()
        val mode = ProbeModes.forProfile(profile)   // one budget per Full or Custom scan, shared by its identifies
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
            c.portScanEngine.scan(ip, profile, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                launch { sem.withPermit {
                    val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), mode)
                    synchronized(found) { found[r.port] = info }
                    c.ports.upsertOpen(hostId, r.port, info)
                } }
            }
        }
        c.ports.markScanned(hostId)
        // Stored ports are part of the evidence: a narrow Custom scan must not forget what a Full scan found.
        c.hosts.byIdOnce(hostId)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(hostId), found.keys.toSet(), found, e.mac?.let(c.ouiLookup))
            // After a port scan the stored identity is replaced only by one at least as confident (spec 3.3).
            c.hosts.setIdentityIfNotWeaker(hostId, c.deviceIdentifier.identify(ev))
        }
    }

    fun stopScan() = scan.stop()
    fun setStatus(s: HostStatus) { viewModelScope.launch { c.hosts.setStatus(hostId, s) } }
    /** NonCancellable job: the last save fires as the screen leaves, when viewModelScope is about to be (or already is) cancelled. */
    fun setNote(n: String) { viewModelScope.launch(NonCancellable) { c.hosts.setNote(hostId, n) } }
    fun setCustomName(n: String?) { viewModelScope.launch { c.hosts.setCustomName(hostId, n) } }
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
    fun dismissMessage() { message.value = null; scan.dismissMessage() }

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
