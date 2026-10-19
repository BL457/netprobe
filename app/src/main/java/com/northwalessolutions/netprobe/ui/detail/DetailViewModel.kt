package com.northwalessolutions.netprobe.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.portscan.*
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.vulns.VulnQuery
import com.northwalessolutions.netprobe.engine.vulns.VulnResult
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
    private val scanning = MutableStateFlow(false); private val message = MutableStateFlow<String?>(null); private val vuln = MutableStateFlow<VulnSheetState?>(null)
    private var scanJob: Job? = null
    private var vulnJob: Job? = null

    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), scanning, c.portScanEngine.progress, profile, customText, customError, message, vuln) { arr ->
        @Suppress("UNCHECKED_CAST")
        val ps = arr[1] as List<PortEntity>
        val h = arr[0] as HostEntity?; val sc = arr[2] as Boolean; val pr = arr[3] as ScanProgress?
        val rows = ps.map { p -> val info = ServiceInfo(p.serviceName, p.product, p.version, p.detail, p.confidence)
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList()) }
        DetailUiState(h, h?.let { DisplayName.of(it) } ?: "", rows, sc, if (sc) pr else null, arr[4] as ScanProfile, arr[5] as String, arr[6] as String?, arr[7] as String?, arr[8] as VulnSheetState?)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

    fun setProfile(p: ScanProfile) { profile.value = p; customError.value = null }
    fun setCustomPorts(t: String) { customText.value = t; customError.value = null }

    fun startScan() {
        if (scanJob?.isActive == true) return
        val h = state.value.host ?: return
        val prof = profile.value
        val resolved = if (prof is ScanProfile.Custom) ProfileParser.parse(customText.value).getOrElse { customError.value = it.message; return } else prof
        scanning.value = true
        // LAZY so scanJob is assigned before the body runs (viewModelScope dispatches immediately); the finally only clears its own scan.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                scanHost(h, resolved)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                message.value = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (scanJob === coroutineContext[Job]) scanning.value = false
            }
        }
        scanJob = job
        job.start()
    }

    private suspend fun scanHost(h: HostEntity, profile: ScanProfile) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        val types = c.hosts.mdnsTypesFor(hostId)                  // once per scan, not per port
        val found = HashMap<Int, ServiceInfo>()
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
            c.portScanEngine.scan(ip, profile, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                launch { sem.withPermit {
                    val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer))
                    synchronized(found) { found[r.port] = info }
                    c.ports.upsertOpen(hostId, r.port, info)
                } }
            }
        }
        c.ports.markScanned(hostId)
        c.hosts.byIdOnce(hostId)?.let { e -> c.hosts.setIdentity(hostId, c.deviceIdentifier.identify(HostEvidence(c.hosts.toDiscovered(e), found.keys.toSet(), found, e.mac?.let(c.ouiLookup)))) }
    }

    fun stopScan() { scanJob?.cancel(); scanJob = null; scanning.value = false }
    fun setStatus(s: HostStatus) { viewModelScope.launch { c.hosts.setStatus(hostId, s) } }
    fun setNote(n: String) { viewModelScope.launch { c.hosts.setNote(hostId, n) } }
    fun setCustomName(n: String?) { viewModelScope.launch { c.hosts.setCustomName(hostId, n) } }
    fun runAction(a: DeviceAction) {
        if (c.actionRunner.run(a)) return
        c.actionRunner.run(DeviceAction.Copy("Copy IP", state.value.host?.ip ?: ""))
        message.value = "No app installed for that link. IP copied instead."
    }
    fun dismissMessage() { message.value = null }

    fun lookupVulnsForService(port: Int) {
        val row = state.value.ports.firstOrNull { it.port == port } ?: return
        val q = c.vulnLookup.queryForService(row.service) ?: run { message.value = "No product or version known for port $port"; return }
        runVuln(q)
    }
    fun lookupVulnsForDevice() {
        val h = state.value.host ?: return
        val q = c.vulnLookup.queryForDevice(DeviceIdentity(h.identVendor, h.identModel, h.identOs, h.deviceType, h.identConfidence, h.identReasons))
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

    private companion object { const val STOP_TIMEOUT_MS = 5_000L }
}
