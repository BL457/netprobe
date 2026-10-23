package com.northwalessolutions.netprobe

import android.content.Context
import com.northwalessolutions.netprobe.actions.ActionProvider
import com.northwalessolutions.netprobe.actions.AndroidActionRunner
import com.northwalessolutions.netprobe.data.*
import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek
import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRules
import com.northwalessolutions.netprobe.engine.identify.StackIdentity
import com.northwalessolutions.netprobe.engine.tools.HttpFingerprintTool
import com.northwalessolutions.netprobe.engine.tools.HttpGet
import com.northwalessolutions.netprobe.engine.tools.SnmpWalkTool
import com.northwalessolutions.netprobe.engine.tools.IdentifyAllServicesTool
import com.northwalessolutions.netprobe.engine.tools.IdentifyServiceTool
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
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
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbeRules
import com.northwalessolutions.netprobe.engine.serviceid.ServiceProbes
import com.northwalessolutions.netprobe.engine.vulns.*
import com.northwalessolutions.netprobe.netenv.NetworkEnvMonitor
import android.util.Log
import com.northwalessolutions.netprobe.engine.script.RhinoScriptEngine
import com.northwalessolutions.netprobe.engine.script.ScriptEngine
import com.northwalessolutions.netprobe.engine.script.CatalogUpdater
import com.northwalessolutions.netprobe.engine.script.ScriptImporter
import com.northwalessolutions.netprobe.engine.script.ScriptToolModule
import com.northwalessolutions.netprobe.engine.script.ScriptToolStore
import com.northwalessolutions.netprobe.engine.tools.WordlistRef
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.LastUsedInputs
import com.northwalessolutions.netprobe.engine.tools.LastUsedStorage
import com.northwalessolutions.netprobe.engine.tools.ManualRoute
import com.northwalessolutions.netprobe.engine.tools.TOOL_THREADS
import com.northwalessolutions.netprobe.engine.tools.ToolModule
import com.northwalessolutions.netprobe.engine.tools.ToolRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteforceTool
import com.northwalessolutions.netprobe.engine.tools.dirbrute.Exchange
import com.northwalessolutions.netprobe.engine.tools.dirbrute.LoadedWordlist
import com.northwalessolutions.netprobe.engine.tools.smb.SmbSessionTool
import com.northwalessolutions.netprobe.ui.home.RunSettings
import com.northwalessolutions.netprobe.ui.home.ContainerHeardSink
import com.northwalessolutions.netprobe.ui.home.ScanBinding
import com.northwalessolutions.netprobe.ui.tools.CatalogController
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolImportController
import com.northwalessolutions.netprobe.ui.tools.ToolRemoval
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportController
import com.northwalessolutions.netprobe.oui.OuiInfo
import com.northwalessolutions.netprobe.oui.OuiMeta
import com.northwalessolutions.netprobe.oui.OuiStore
import com.northwalessolutions.netprobe.oui.OuiUpdater
import com.northwalessolutions.netprobe.update.AndroidApkVerifier
import com.northwalessolutions.netprobe.update.HttpsUpdateFetcher
import com.northwalessolutions.netprobe.update.SharedUpdatePrefs
import com.northwalessolutions.netprobe.update.WhatsNewState
import com.northwalessolutions.netprobe.update.UpdateChecker
import com.northwalessolutions.netprobe.update.UpdateController
import com.northwalessolutions.netprobe.update.UpdateInstaller
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
        appScope.launch(Dispatchers.IO) { updates.cleanUp() }   // in-app updater spec 4: stale APKs and .part files
        appScope.launch(Dispatchers.IO) { lastUsedInputs }   // moves the old brute-force options key once (tools platform spec section 7)
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
    /** Every tool the app can run: the built-ins now, script tools later (tools platform spec section 3). */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(builtInTools()).also { registry ->
            // Script tools join the registry once their descriptions are read (the bundled examples and the imported tools), and the
            // registry follows every later save and remove.
            appScope.launch(Dispatchers.IO) {
                scriptToolStore.load()
                scriptToolStore.modules.collect { registry.setScriptTools(it) }
            }
        }
    }
    /** The JavaScript engine for script tools (tools platform D1); one instance, each run gets its own thread and sandbox. */
    val scriptEngine: ScriptEngine by lazy { RhinoScriptEngine() }
    val scriptToolStore: ScriptToolStore by lazy {
        ScriptToolStore(
            assets = { name -> runCatching { context.assets.open("tools/$name").bufferedReader().use { it.readText() } }.getOrNull() },
            engine = scriptEngine,
            moduleFor = { d, source ->
                ScriptToolModule(d, source, scriptEngine, env = networkEnvMonitor::current, lanBinder = networkEnvMonitor::binderFor, wordlistLines = ::scriptWordlistLines)
            },
            log = { Log.w(AppScopes.LOG_TAG, it) },
            dir = java.io.File(context.filesDir, "tools"),
        )
    }
    /** Remove for an imported tool (tile menu and Settings): the script, the index line and the remembered inputs; history keeps its tool name. */
    val toolRemoval: ToolRemoval by lazy { ToolRemoval(scriptToolStore) { key -> lastUsedInputs.forget(key) } }
    /**
     * The last inputs of every tool, in the netprobe preferences (spec section 7). The first read moves the old brute-force options key into
     * the dir-brute tool's remembered inputs and deletes it.
     */
    val lastUsedInputs: LastUsedInputs by lazy {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        LastUsedInputs(object : LastUsedStorage {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
            override fun remove(key: String) { prefs.edit().remove(key).apply() }
        }).also { it.migrateDirBrute() }
    }
    /** Runs a removal on appScope, so leaving the screen cannot stop it half way, and hands the message to [done]. */
    fun removeTool(key: String, done: (String) -> Unit) {
        appScope.launch { done(toolRemoval.remove(key)) }
    }
    /** The import flow shared by the Tools tab and Settings (tools platform D10): reading, fetching and saving run on appScope. */
    val toolImport: ToolImportController by lazy {
        ToolImportController(appScope, ScriptImporter(scriptEngine, scriptToolStore, HttpsUpdateFetcher()), log = { Log.e(AppScopes.LOG_TAG, "tool import", it) })
    }
    /** "Update tools from GitHub" (spec section 11), shared by the Tools tab and Settings: fetches only when the user taps the action. */
    val catalog: CatalogController by lazy {
        val fetcher = HttpsUpdateFetcher()
        CatalogController(appScope, CatalogUpdater(ScriptImporter(scriptEngine, scriptToolStore, fetcher), scriptToolStore, fetcher), log = { Log.e(AppScopes.LOG_TAG, "tool catalog", it) })
    }
    /** Lines [offset] until [offset] + [limit] of a wordlist, for ctx.wordlist; empty when the list is gone. */
    private suspend fun scriptWordlistLines(ref: WordlistRef, offset: Int, limit: Int): List<String> {
        val out = ArrayList<String>()
        var index = 0
        wordlistStore.withEntries(ref.id) { entries ->
            for (line in entries) {
                if (index >= offset + limit) break
                if (index >= offset) out += line
                index++
            }
        }
        return out
    }
    private fun builtInTools(): List<ToolModule> = buildList {
        add(DirBruteforceTool(
            exchangeFor = { t, timeoutMs ->
                val client = HttpClient(timeoutMs)
                val ctx = if (t.tls) HttpClient.trustAllContext() else null          // one SSLContext per run: TLS sessions resume
                Exchange { method, path -> client.request(t.ip, t.port, t.tls, method, path, null, ctx) }   // Host is always the IP
            },
            resolve = { id -> resolveWordlist(id) }))
        add(HttpFingerprintTool(
            getFor = { t ->
                val client = HttpClient(HttpFingerprintTool.FINGERPRINT_TIMEOUT_MS)
                val ctx = HttpClient.trustAllContext()      // one client and one SSLContext per run (spec 4.5)
                HttpGet { tls, port, path ->
                    // spec 4.9: the name first; only a failed TLS handshake is retried, once, on the IP.
                    namedThenIp(tls, t.serverName) { name -> client.request(t.ip, port, tls, "GET", path, name, if (tls) ctx else null) }
                }
            },
            rules = stackRules))
        add(TlsCertificateTool(peek = { t, name -> TlsPeek().details(t.ip, t.port, name) }))
        // tcpConnect goes through Probe.connect, so off-subnet hosts are reached over Wi-Fi exactly as port scans are.
        add(SmbSessionTool(connect = { t -> SmbSessionTool.tcpConnect(t) }))
        add(SnmpWalkTool(exchangeFor = { t, snmp -> UdpSnmpExchange(t.ip, community = snmp.community, version = snmp.version) }))
        add(IdentifyServiceTool(
            identify = { t, budget, limits, onProbe -> serviceIdentifier.identifyTraced(t.ip, t.port, mode = ProbeMode.Active(budget, hellos = true, limits = limits), hostId = t.hostId, onProbe = onProbe) },
            plannedProbes = { port, limits -> serviceIdentifier.plannedProbes(port, limits) },
            unsafeHellos = { settings.state.value.unsafeHellos },
            apply = { hostId, port, info -> ports.applyIdentify(hostId, port, info) }))
        add(IdentifyAllServicesTool(
            portsFor = { hostId -> ports.servicesFor(hostId).keys.toList() },
            unsafeHellos = { settings.state.value.unsafeHellos },
            identify = { t, budget, limits, onProbe -> serviceIdentifier.identifyTraced(t.ip, t.port, mode = ProbeMode.Active(budget, hellos = true, limits = limits), hostId = t.hostId, onProbe = onProbe) },
            apply = { hostId, port, info -> ports.applyIdentify(hostId, port, info) }))
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
        ToolRunner(appScope, toolDispatcher, toolRegistry, toolRepo, hostBusy, appMessages,
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
    /** The HTTP stack rules (Phase 3E spec 5), loaded once: the service ladder, the Fingerprint tool and the identity rules all read this list. */
    val stackRules by lazy { HttpStackRules.load(RawResources.read(context, R.raw.http_stacks), cpeMap.keys).rules }
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
    /** The offline vendor table (Director ruling 2026-10-22 b): filesDir/oui/oui.bin, read on the first lookup; nothing ships built in. */
    val ouiStore: OuiStore by lazy {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        OuiStore(java.io.File(context.filesDir, "oui/" + OuiStore.FILE_NAME), object : OuiMeta {
            override fun read(): OuiInfo? = if (prefs.contains(OUI_COUNT_KEY)) OuiInfo(prefs.getLong(OUI_UPDATED_KEY, 0L), prefs.getInt(OUI_COUNT_KEY, 0)) else null
            override fun write(info: OuiInfo) { prefs.edit().putLong(OUI_UPDATED_KEY, info.updatedAt).putInt(OUI_COUNT_KEY, info.count).apply() }
        }, log = { Log.e(AppScopes.LOG_TAG, "vendor table", it) })
    }
    /** "Update vendor database" on appScope; the same fetcher as the wordlist URL import. Building it touches no file and no network. */
    val ouiUpdater: OuiUpdater by lazy {
        OuiUpdater(appScope, ouiStore, appMessages, { url, max, progress -> UrlWordlistFetcher().fetchBytes(url, max, progress) },
            log = { Log.e(AppScopes.LOG_TAG, "vendor update", it) }, liveWork = liveWork)
    }
    /** The post-update "What's new" card state; decided on first use, which is when Home first shows. */
    val whatsNew: WhatsNewState by lazy {
        WhatsNewState(SharedUpdatePrefs(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
    }
    /** The in-app updater (spec 1-4) on appScope. Building it touches no file and no network; the first check happens when MainActivity starts. */
    val updates: UpdateController by lazy {
        val fetcher = HttpsUpdateFetcher()
        val prefs = SharedUpdatePrefs(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE))
        val log: (Throwable) -> Unit = { t -> Log.e(AppScopes.LOG_TAG, "updater", t); Unit }
        UpdateController(appScope,
            UpdateChecker(fetcher, System::currentTimeMillis, prefs, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT, log = { Log.w(AppScopes.LOG_TAG, it); Unit }),
            UpdateInstaller(context.cacheDir, fetcher, AndroidApkVerifier(context), BuildConfig.VERSION_CODE, log, prefs),
            appMessages, autoEnabled = { settings.state.value.updateAuto }, networkAvailable = ::hasInternet, log = log)
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    val serviceProbes by lazy { ServiceProbes(ServiceProbeRules.load(RawResources.read(context, R.raw.service_probes)).probes, stackRules = stackRules) }
    val serviceIdentifier by lazy { ServiceIdentifier(portTable, serviceProbes) }
    val deviceIdentifier by lazy { DeviceIdentifier(identifyRules + StackIdentity.rules(stackRules)) }
    val actionRunner by lazy { AndroidActionRunner(context) }
    val actions by lazy { ActionProvider(actionRunner) }
    val locks by lazy { Locks(context) }
    val ouiLookup: (String) -> String? = { mac -> ouiStore.lookup(mac) }
    val vulnLookup by lazy {
        VulnLookup(UrlHttpGet(), object : VulnCache {
            override suspend fun get(query: String, maxAgeMs: Long) = vulnCache.get(query, maxAgeMs)?.let { it.json to it.fetchedAt }
            override suspend fun getAny(query: String) = vulnCache.getAny(query)?.let { it.json to it.fetchedAt }
            override suspend fun put(query: String, json: String) = vulnCache.put(query, json)
        }, { settings.state.value.nvdApiKey }, cpeMap)
    }
}

private const val OUI_UPDATED_KEY = "ouiUpdatedAt"
private const val OUI_COUNT_KEY = "ouiCount"
