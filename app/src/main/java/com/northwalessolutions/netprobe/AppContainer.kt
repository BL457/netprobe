package com.northwalessolutions.netprobe

import android.content.Context
import com.northwalessolutions.netprobe.actions.ActionProvider
import com.northwalessolutions.netprobe.actions.AndroidActionRunner
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek
import com.northwalessolutions.netprobe.engine.tools.FingerprintRules
import com.northwalessolutions.netprobe.engine.tools.HttpFingerprintTool
import com.northwalessolutions.netprobe.engine.tools.HttpGet
import com.northwalessolutions.netprobe.engine.tools.SnmpWalkTool
import com.northwalessolutions.netprobe.engine.tools.TlsCertificateTool
import com.northwalessolutions.netprobe.engine.tools.UdpSnmpExchange
import com.northwalessolutions.netprobe.engine.tools.namedThenIp
import com.northwalessolutions.netprobe.engine.common.Locks
import com.northwalessolutions.netprobe.engine.common.ForegroundGate
import com.northwalessolutions.netprobe.engine.portscan.HostScanBody
import com.northwalessolutions.netprobe.engine.portscan.PortScans
import com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.scans.ScansHistoryStore
import com.northwalessolutions.netprobe.ui.scans.ScansRules
import kotlinx.coroutines.flow.combine
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngine
import com.northwalessolutions.netprobe.engine.discovery.HeardWriter
import com.northwalessolutions.netprobe.engine.discovery.ListenController
import com.northwalessolutions.netprobe.engine.discovery.MulticastListenSockets
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.portscan.PortScanEngine
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.vulns.*
import com.northwalessolutions.netprobe.netenv.NetworkEnvMonitor
import android.util.Log
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ManualRoute
import com.northwalessolutions.netprobe.engine.tools.TOOL_THREADS
import com.northwalessolutions.netprobe.engine.tools.Tool
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteforceTool
import com.northwalessolutions.netprobe.engine.tools.dirbrute.Exchange
import com.northwalessolutions.netprobe.engine.tools.dirbrute.LoadedWordlist
import com.northwalessolutions.netprobe.engine.tools.smb.SmbSessionTool
import com.northwalessolutions.netprobe.ui.home.RunSettings
import com.northwalessolutions.netprobe.ui.home.ContainerHeardSink
import com.northwalessolutions.netprobe.ui.home.ScanBinding
import com.northwalessolutions.netprobe.ui.tools.DirBruteOptionsStore
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportController
import com.northwalessolutions.netprobe.wordlists.BuiltInList
import com.northwalessolutions.netprobe.wordlists.UrlWordlistFetcher
import com.northwalessolutions.netprobe.wordlists.WordlistStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual dependency container of lazily-created singletons. */
class AppContainer(val context: Context) {
    /** The network row Home resolved for the current env; Settings edits that network's manual ranges (extended spec 8.3 item 4). */
    val currentNetwork = MutableStateFlow<NetworkEntity?>(null)
    /** True from the start of an extended run until its discover() returns, whatever screen is showing. */
    val extendedRunActive = MutableStateFlow(false)
    /** Deletes and restores run here, so leaving a screen never cancels them; failures are logged, never thrown (spec 5.3). */
    val appScope: CoroutineScope = AppScopes.create(AppScopes.handler { Log.e(AppScopes.LOG_TAG, "background task failed", it) })
    val undo = UndoStore()
    /** The network whose Forget is pending; Home shows it empty until Undo or the commit (spec 5.3). Process-wide, not per ViewModel (G4). */
    val forgottenNetworkId = MutableStateFlow<Long?>(null)
    /** Bumped after Forget, Undo or the commit so Home resolves the network row again, whichever HomeViewModel is alive (G4). */
    val networkEpoch = MutableStateFlow(0)
    /** Each host's Quick scan all child job, so Remove device cancels exactly that host's scan (spec 5.3 step 1). */
    val hostScanJobs = ConcurrentHashMap<Long, Job>()
    /** (network, ip) removed while a radar scan runs; that scan and Quick scan all skip them; cleared at the next radar scan start. */
    val removedDuringScan = MutableStateFlow<Set<Pair<Long, String>>>(emptySet())
    /** Host ids removed in this process; Quick scan all skips them radar or not (plan review G2). Ids are AUTOINCREMENT, never reused. */
    val removedHostIds = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * (network, ip) removed with Remove device since the last radar scan or listening session started (3C spec 3.4 rule 5, G22): the
     * listener does not re-add them. Cleared by both starts; Undo takes its pair out.
     */
    val removedWhileListening = MutableStateFlow<Set<Pair<Long, String>>>(emptySet())
    /** The host of the run the "Run a tool" screen last started (plan review G13): process-wide, so Stop survives the screen being recreated. */
    val manualStartedHostId = MutableStateFlow<Long?>(null)
    /** The network a radar scan is running on, or null; set once the scan has resolved its network row. */
    val radarScanNetworkId = MutableStateFlow<Long?>(null)
    /**
     * True from the very start of a radar scan body (straight after its Undo commit, before any suspension) until it ends, so a Remove
     * during the scan's start-up window, before [radarScanNetworkId] is known, is still kept out of that scan (review M2).
     */
    val radarScanActive = MutableStateFlow(false)
    /** Held by each radar emission's check and write and by Remove's mark and delete, so the two never interleave (G2). */
    val hostWriteLock = Mutex()
    private val activityStarted = AtomicBoolean(false)
    /**
     * MainActivity.onCreate calls this; the first call per process does the work. Never the Application: an instrumented test process runs
     * NetProbeApp but no activity, so it never opens netprobe.db through here. Removes manual targets a killed process left without a run
     * (3C spec 4.3, G8), under the write lock so no radar emission interleaves; a failure is logged by appScope's handler.
     */
    fun onActivityCreated() {
        if (!activityStarted.compareAndSet(false, true)) return
        appScope.launch { hostWriteLock.withLock { hosts.deleteOrphanManual() } }
        listen.start()
        portScans       // built at launch, so port scans a dead process left open show as Interrupted before anything new ends (3D spec 3.3)
    }

    /**
     * "Listen between scans" (3C spec 3.2): sessions on appScope, gated by the switch, the foreground and the LAN, with their own lock.
     * Started only by onActivityCreated. Each session resolves its env's network row and writes only while Home's current network is that row.
     */
    /** The network row of the last listening session; Long.MIN_VALUE before the first (row ids are positive). */
    private val listenRow = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    val listen: ListenController by lazy {
        ListenController(appScope, settings.state, foreground,
            networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()), networkEpoch, radarScanActive,
            locks.listen, MulticastListenSockets(networkEnvMonitor::binderFor, log = { Log.w(AppScopes.LOG_TAG, "listen socket", it) }),
            // Final review S5: no connect to the router, no row created. Home's scan makes the row; until then the session waits as NOT_SCANNED.
            resolve = { e ->
                val row = networks.find(e, GatewayMac.peek(e.gateway))
                if (row == null) ListenController.NO_ROW_YET else row.id.takeIf { row.forgotten == null && it != forgottenNetworkId.value }
            },
            scanned = { id -> networks.byId(id)?.lastRunAt != null },
            writerFor = { e, id -> HeardWriter(id, ContainerHeardSink(this@AppContainer, e)) },
            // G12: the removed set is cleared only when listening moves to another network row, not on every restart.
            onSessionStart = { id -> if (listenRow.getAndSet(id) != id) removedWhileListening.value = emptySet() },
            log = { Log.e(AppScopes.LOG_TAG, "listen", it) })
    }
    /** One run at a time per host, across Quick scan all, Detail scans and the tool runner (spec 4.3). */
    val hostBusy = HostBusyRegistry()
    /** End-of-run messages for whichever screen is visible (spec 4.3). */
    val appMessages = AppMessages()
    /** NetProbe's foreground state; MainActivity's lifecycle sets it. Runs pause while false (spec 4.8). */
    val foreground = MutableStateFlow(true)
    /** Port scans await this before each probe (3D spec 3.3); ToolRunner keeps its own gate on the same flow. */
    val foregroundGate = ForegroundGate(foreground)
    /** Radar, Quick scan all, port scans and URL imports as the Scans destination sees them (3D spec 3.2). Memory only. */
    val liveWork = LiveWork(appScope)
    /** Every tool coroutine and blocking call runs here, so tool I/O never holds more than TOOL_THREADS threads total (spec 4.4, Gruff M7). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val toolDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(TOOL_THREADS)
    val settings by lazy { AppSettings(context) }
    val db by lazy { NetProbeDb.open(context) }
    val networks by lazy { NetworkRepository(db) { settings.manual.set(it, emptyList()) } }
    val hosts by lazy { HostRepository(db) }
    val ports by lazy { PortRepository(db) }
    val vulnCache by lazy { VulnCacheRepository(db) }
    val toolRepo by lazy { ToolRepository(db) }
    /** The registered tools; each tool task (10 to 14) adds its line to toolMap(). A ToolId absent here never gets a chip. */
    val tools: Map<ToolId, Tool> by lazy { toolMap() }
    private fun toolMap(): Map<ToolId, Tool> = buildMap {
        // Task 10: DIR_BRUTEFORCE. Task 11: HTTP_FINGERPRINT. Task 12: TLS_CERTIFICATE. Task 13: SNMP_WALK. Task 15: SMB_SESSION.
        put(ToolId.DIR_BRUTEFORCE, DirBruteforceTool(
            exchangeFor = { t, timeoutMs ->
                val client = HttpClient(timeoutMs)
                val ctx = if (t.tls) HttpClient.trustAllContext() else null          // one SSLContext per run: TLS sessions resume
                Exchange { method, path -> client.request(t.ip, t.port, t.tls, method, path, null, ctx) }   // Host is always the IP
            },
            resolve = { id -> resolveWordlist(id) }))
        put(ToolId.HTTP_FINGERPRINT, HttpFingerprintTool(
            getFor = { t ->
                val client = HttpClient(HttpFingerprintTool.FINGERPRINT_TIMEOUT_MS)
                val ctx = HttpClient.trustAllContext()      // one client and one SSLContext per run (spec 4.5)
                HttpGet { tls, port, path ->
                    // spec 4.9: the name first; only a failed TLS handshake is retried, once, on the IP.
                    namedThenIp(tls, t.serverName) { name -> client.request(t.ip, port, tls, "GET", path, name, if (tls) ctx else null) }
                }
            },
            rules = FingerprintRules.load(RawResources.read(context, R.raw.http_fingerprints))))
        put(ToolId.TLS_CERTIFICATE, TlsCertificateTool(peek = { t, name -> TlsPeek().details(t.ip, t.port, name) }))
        put(ToolId.SNMP_WALK, SnmpWalkTool(exchangeFor = { t -> UdpSnmpExchange(t.ip) }))
        // tcpConnect goes through Probe.connect, so off-subnet hosts are reached over Wi-Fi exactly as port scans are.
        put(ToolId.SMB_SESSION, SmbSessionTool(connect = { t -> SmbSessionTool.tcpConnect(t) }))
    }
    /** The chosen list, else common-short (spec 5.1, 11.10); streams through withEntries, so a list is never loaded whole. */
    private fun resolveWordlist(id: String): LoadedWordlist? {
        val lists = wordlistStore.lists.value
        val meta = lists.firstOrNull { it.id == id } ?: lists.firstOrNull { it.id == DirBruteOptions.DEFAULT_WORDLIST } ?: return null
        return LoadedWordlist(meta.id, meta.name, meta.entries) { collect -> wordlistStore.withEntries(meta.id) { seq -> for (w in seq) collect(w) } }
    }
    /** Reading this lazy is the only place markInterrupted starts; no instrumented test reads it (Global Constraints). */
    val toolRunner: ToolRunner by lazy {
        appScope.launch { toolRepo.markInterrupted(ToolCopy::interrupted) }
        ToolRunner(appScope, toolDispatcher, tools, toolRepo, hostBusy, appMessages,
            networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()),
            foreground,
            bind = { ip, e, block ->
                // Read once at the run's start (3C spec D21): manual ranges bind off-subnet targets even with Aggressive scan off (spec 2.4).
                // The current network's list is the target's: Home and the Device screen list only the current network's hosts (plan review G12).
                val runSettings = RunSettings.of(settings.state.value, settings.manual.forNetwork(currentNetwork.value?.id))
                ScanBinding.forRun(ip, e, runSettings, networkEnvMonitor::binderFor) { block() }
            },
            onFinished = { hostId -> reidentifyFromTools(hostId) },
            log = { Log.e(AppScopes.LOG_TAG, "tool run", it) },
            // Manual runs (3C spec 4.5, ruling 4): known-range targets over the Wi-Fi or Ethernet Network, never unbound; others on the system route.
            routeBind = { route, e, block -> ManualRoute.bind(route, e, networkEnvMonitor::binderFor) { block() } })
    }
    /** True while any tool run is live; Nav keeps the screen on for it (spec 4.4). */
    val toolRunActive: StateFlow<Boolean> by lazy { toolRunner.active.map { it.isNotEmpty() }.stateIn(appScope, SharingStarted.Eagerly, false) }
    /** ToolRunner.onFinished: re-identify from stored ports plus the host's tool hints, never lowering the identity (spec 4.7). */
    private suspend fun reidentifyFromTools(hostId: Long) {
        val h = hosts.byIdOnce(hostId) ?: return
        val ev = HostEvidenceBuilder.build(hosts.toDiscovered(h), ports.servicesFor(hostId), emptySet(), emptyMap(), h.mac?.let(ouiLookup), toolRepo.hintsFor(hostId))
        hosts.setIdentityIfNotWeaker(hostId, deviceIdentifier.identify(ev))
    }
    val networkEnvMonitor by lazy { NetworkEnvMonitor(context) }
    val portTable by lazy { PortTable.fromJson(RawResources.read(context, R.raw.ports)) }
    val mdnsTypes by lazy { RawResources.lines(RawResources.read(context, R.raw.mdns_types)) }
    val identifyRules by lazy { IdentifyRules.load(RawResources.read(context, R.raw.identify_rules)) }
    val cpeMap by lazy { Cpe.load(RawResources.read(context, R.raw.cpe_map)) }
    val discoveryEngine by lazy { DiscoveryEngine.production(context, mdnsTypes) }
    val portScanEngine by lazy { PortScanEngine() }
    /** The Clear history cut-off (3D spec 3.8), a data key in the netprobe preferences. */
    val scansHistory by lazy { ScansHistoryStore(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)) }
    /** How many things run right now (3D spec 3.7): the Scans bar badge. Reading it creates the tool runner, as the screen-on flag always did. */
    val scansRunning: StateFlow<Int> by lazy {
        combine(liveWork.live, toolRunner.active) { live, active -> ScansRules.runningCount(live, active) }.stateIn(appScope, SharingStarted.Eagerly, 0)
    }
    /** Device port scans on appScope (3D spec 3.3). Building it drains the open-scan record into Interrupted Scans items. */
    val portScans: PortScans by lazy {
        val scanBody = HostScanBody(this)
        PortScans(appScope, networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()), hostBusy, liveWork, appMessages,
            PrefsOpenScanStore(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)),
            body = { h, profile, e, sink -> undo.committedBefore { scanBody.run(h, profile, e, sink) } },
            foreground = foreground)
    }
    /** Built-in lists from raw resources and user imports under filesDir/wordlists; lazy, so no file work until first use. */
    val wordlistStore by lazy {
        WordlistStore(java.io.File(context.filesDir, "wordlists"), listOf(
            BuiltInList("builtin:common-short", "common-short") { context.resources.openRawResource(R.raw.wordlist_common_short) },
            BuiltInList("builtin:admin-panels", "admin-panels") { context.resources.openRawResource(R.raw.wordlist_admin_panels) },
            BuiltInList("builtin:api-paths", "api-paths") { context.resources.openRawResource(R.raw.wordlist_api_paths) },
        ), log = { Log.e(AppScopes.LOG_TAG, "wordlist recovery", it) })
    }
    /** Wordlist import from a URL (3C spec 5): one at a time on appScope. Building it touches no file and no network. */
    val urlImport: UrlImportController by lazy {
        UrlImportController(appScope, { url, progress -> UrlWordlistFetcher().fetch(url, wordlistStore, progress) }, appMessages,
            log = { Log.e(AppScopes.LOG_TAG, "url import", it) }, liveWork = liveWork)
    }
    /** Last-used brute-force options, saved globally (spec 5.1). */
    val dirBruteOptions by lazy {
        val prefs = context.getSharedPreferences("netprobe", Context.MODE_PRIVATE)
        DirBruteOptionsStore({ prefs.getString(DirBruteOptionsStore.KEY, null) }, { prefs.edit().putString(DirBruteOptionsStore.KEY, it).apply() })
    }
    val serviceIdentifier by lazy { ServiceIdentifier(portTable) }
    val deviceIdentifier by lazy { DeviceIdentifier(identifyRules) }
    val actionRunner by lazy { AndroidActionRunner(context) }
    val actions by lazy { ActionProvider(actionRunner) }
    val locks by lazy { Locks(context) }
    val ouiLookup: (String) -> String? = { null }
    val vulnLookup by lazy {
        VulnLookup(UrlHttpGet(), object : VulnCache {
            override suspend fun get(query: String, maxAgeMs: Long) = vulnCache.get(query, maxAgeMs)?.let { it.json to it.fetchedAt }
            override suspend fun getAny(query: String) = vulnCache.getAny(query)?.let { it.json to it.fetchedAt }
            override suspend fun put(query: String, json: String) = vulnCache.put(query, json)
        }, { settings.state.value.nvdApiKey }, cpeMap)
    }
}
