# NetProbe Phase 3B Follow-up Tools Design (addendum to the MVP spec)

Date: 2026-10-20
Status: revised after Gruff's review (`2026-10-20-phase3b-gruff-review.md`, response in section 16) and the controller's binding rulings. The Director is away; the controller made the design decisions as his stand-in, and every call made on his behalf is listed in section 13 so he can overturn it.
Project: `K:\claude\netprobe`, built on master at b37db04
Parent spec: `2026-10-19-netprobe-mvp-design.md` (this addendum amends its sections 2.3, 5.4.1, 5.6, 5.7 and 9)
Sibling specs: `2026-10-19-extended-discovery-design.md` (binding to the Wi-Fi network, its 5.5), `2026-10-19-radar-ui-design.md` (the "Radar spec": tokens, type styles, Device screen layout, copy rules) and `2026-10-20-phase3a-polish-design.md` (the "3A spec": schema 1 to 2, `appScope`, `PortUris`, `TlsPeek.certInfo`, `setIdentityIfNotWeaker`, the Detail overflow, Remove device and Forget this network with Undo). This spec builds on 3A as written and owns every string it introduces (section 9).
Prerequisite: 3A is a hard prerequisite. Plan order is in section 15: 3A, then 3B, then 3C (SMB share enumeration).

## 1. Purpose

The Director asked: "Phase 3 should include various follow up tools like directory bruteforcing using configurable and selectable wordlists, and other similar follow up tools."

Today a port scan ends with a list of open ports, a service name and one hand-off action. On his own networks and on client assessments the next question is always "what is actually behind that port?". This addendum answers it on the phone with five follow-up tools that share one framework:

| Tool | Applies to | Turns an open port into |
|---|---|---|
| Directory brute-force | HTTP and HTTPS on any port | Hidden admin pages, APIs, backups and panels |
| HTTP fingerprint | HTTP and HTTPS on any port | Product, framework, cookies, robots and sitemap paths |
| TLS certificate | TLS ports | The names the device calls itself, issuer, expiry, key strength |
| SMB session | SMB on 445 | Dialect, whether signing is required, whether anonymous or guest sessions are allowed, the server's NetBIOS and DNS names and OS build |
| SNMP walk | Hosts that answered SNMP in discovery | System strings, interfaces and their addresses (other ranges a router serves) |

SMB share enumeration and read checks are Phase 3C (section 15), built on real captured fixtures.

Success: on the Director's LAN, a directory brute-force of the CHNAS Synology on port 5000 with "common-short" finishes in under 15 s, shows its real paths with soft-404 noise filtered, and Stop ends it within a second; the HTTP fingerprint of the Pi-hole admin page names Pi-hole and feeds the device identity; the TLS certificate of the UniFi gateway shows its subject, SANs and expiry; the SMB session probe of the CHNAS reports its dialect, signing and guest result; every result is still there after the app restarts; a run in progress survives a trip to the browser and back.

Everything stays within the platform limits of MVP spec section 4: ordinary TCP connects and UDP datagrams, no root, no raw sockets.

## 2. Scope

### 2.1 In this addendum

1. A tools framework: `Tool` interface, `ToolRunner` (one run at a time per host, pause in the background, rate ceiling), a shared `HostBusyRegistry`, an app-level message flow, Room persistence of runs and findings, a results sheet, a run history line, export as text.
2. Directory brute-force with selectable wordlists (three bundled, user-imported through the Storage Access Framework), extensions, method, concurrency, timeout, reported status codes, a calibrated not-found filter and adaptive concurrency.
3. Four companion tools: HTTP fingerprint, TLS certificate, SMB session, SNMP walk.
4. A small raw-socket `HttpClient` in `engine/common` (GET and HEAD, redirects never followed, 64 KB body cap).
5. A Wordlists screen reachable from Settings and from the brute-force options sheet.
6. Identification hints: tool results feed `DeviceIdentifier` (4.7).
7. A Room schema change (version 2 to 3) with a migration and a migration test (section 7).
8. A "running" marker on Home rows for hosts with a port scan or tool run.

This amends MVP spec 2.3 (non-goals): enumerating web content and opening anonymous or guest SMB sessions are in scope. Password guessing, exploitation and vulnerability scanning stay out.

### 2.2 Not in this addendum (YAGNI)

- Recursive brute-force (descending into found directories).
- Proxies, custom headers, a user-set Host header, cookies or authentication of any kind beyond reporting 401 and 403.
- Scheduling, repeated or background runs, notifications, a foreground service. Runs pause while NetProbe is in the background (4.8).
- User-set request delays or rate options (the fixed ceiling and adaptive concurrency in 5.3 are the throttle).
- HTTP keep-alive or HTTP/2 (every request is its own connection, `Connection: close`).
- SNMP communities other than `public`, SNMPv3, SNMP SET.
- SMB share listing, share read checks, any SMB request after SESSION_SETUP, SMB writes, SMB1, SMB 3.1.1-only servers. Share listing and read checks are Phase 3C (section 15).
- Downloaded wordlists (SecLists or any other). A SecLists subset could be bundled later if the Director approves the download (13.12).
- Editing or renaming imported wordlists; deleting single runs; clearing tool history.

### 2.3 Build constraints for every implementer task

Kotlin 2.1.21, Compose BOM 2025.06.00, Room 2.7.1 with KSP, coroutines 1.10.2, kotlinx-serialization 1.8.1, targetSdk 30. No new dependencies, no Gradle plugin or version changes, no downloads of any kind (libraries, wordlists, fonts, fixtures). `androidx.lifecycle:lifecycle-process` is not declared and is not added; the foreground signal comes from `MainActivity`'s own lifecycle (4.8). `room-testing` is already declared for `androidTest` and is in the Gradle cache; the 3A spec adds the schemas as `androidTest` assets.

The bundled wordlists are authored by the implementer (5.6.1); TLS test fixtures are generated locally with the JDK 17 `keytool` and committed (12.1); nothing is fetched. No em-dashes in code, comments, strings or docs. No attribution lines in commits. Scan, Stop and Run controls are never inside a scroll container. Every user-facing string comes from section 9 and is checked by `CopyTableTest`. No disclaimers, warnings or authorisation prompts in any UI copy.

## 3. Decisions

| Decision | Choice | Rejected alternative and why |
|---|---|---|
| Framework | One `Tool` interface, one `ToolRunner`, shared persistence and sheets | One screen per tool: five screens of near-identical plumbing, and the brief allows no new screens beyond Wordlists. |
| Where tools start | A collapsible "Tools" block at the top of the Device screen's scrolling list, plus a "Run tool" group in each port's long-press sheet | A new tools screen: one more hop for every run. Pinning tools above the list: breaks the Radar spec 7.5 row budget. |
| Concurrency of runs | One run at a time per host; at most 4 hosts running at once; all tool I/O on one `Dispatchers.IO.limitedParallelism(16)` view | Unlimited: starves discovery of IO threads (Gruff M7). |
| Host exclusivity | An app-level `HostBusyRegistry` (host id to running work: port scan or tool) consulted by Quick scan all, Detail port scans and the tool runner | Exclusivity enforced only in the Device ViewModel: Quick scan all on Home would port-scan a host mid brute-force (Gruff M1). |
| Run lifetime | Runs live on `appScope` (3A spec 5.3); leaving the Device screen does not stop them; Stop, a network change or process death does | Tie runs to the Device ViewModel: a 2 minute brute-force dies when the Director checks another device. |
| Background | The run pauses on the activity's ON_STOP and resumes on ON_START; failures that span a pause are not counted; elapsed time stops | Keep running: the cached-apps freezer turns in-flight reads into false timeouts and a false "server stopped answering". Stop on background: "Open with..." on a finding would kill the run it came from. |
| End-of-run messages | An app-level message flow read by the Home notice line and the Detail snackbar | A Detail snackbar only: lost when the run ends on Home. |
| Stop | The pinned scan bar's pill becomes Stop during a run and is the only stop | A Stop in the results or options sheet: two stops, and sheets are dismissable. |
| Cancellation | Coroutine cancellation of the run's job; every socket carries a close-on-cancel watcher that inherits the caller's dispatcher (4.6) | A separate cancel token: duplicates `Job` cancellation. `runInterruptible(Dispatchers.IO)` in the helper: funnels tool reads onto the shared 64-thread pool. |
| HTTP client | Raw sockets through `Probe.connect`, HTTP/1.1, `Connection: close`, redirects never followed, 64 KB body cap | OkHttp: a new dependency. `HttpURLConnection`: pools connections, has its own HEAD and chunking rules, and a blocked read cannot be closed from a cancelling coroutine. |
| Socket routing | All tool sockets go through `Probe.connect` or `currentBinder()`, inside `ScanBinding.run`, so `BoundNetwork` applies to off-subnet hosts exactly as for port scans | A tool-specific bind path: two rules for the same VPN problem. |
| Load on the target | Adaptive concurrency (halve on timeouts or resets, recover slowly) plus a hard ceiling of 100 requests per second per host | Concurrency as the only throttle: 8 workers with `Connection: close` can open 1,600 connections a second and stall embedded servers (Gruff M3). |
| Not-found filter | Calibration: 5 random paths per group (bare and each extension), a size band per group and status, HEAD and GET agreement check, and a runaway check | One random-path baseline with exact size: fails on pages with nonces and on HEAD-404/GET-200 servers (Gruff M4). |
| Brute-force method default | HEAD for every path, GET only for hits, GET only when calibration shows HEAD and GET disagree | GET for every path: up to 64 KB read per miss, and one more handshake per miss over HTTPS than HEAD. |
| Wordlist source | Three bundled lists authored in the repo; user imports through the Storage Access Framework | Downloading SecLists: forbidden without approval (13.12). |
| Wordlist storage | Normalised copy in `files/wordlists/`, metadata in `files/wordlists/index.json` | A Room table: wordlists are files, and a third table adds migration surface for no query benefit. |
| Persistence | Two Room tables, `tool_runs` (with a `port` column) and `tool_findings`, foreign keys with cascade | JSON blobs in `hosts`: unbounded row growth and no per-finding rows for copy and open. |
| History cap | 10 runs per host per tool, oldest pruned on insert | Unbounded: a client site with 200 hosts grows without limit. |
| Findings cap | 500 findings per run | Unbounded: a catch-all server that slips past the filter would write thousands of rows. |
| Options UI | Only directory brute-force has an options sheet; the other four run at once with fixed defaults | Options for every tool: four more sheets of single sliders. |
| Identification | Tools emit hint strings per (tool, port) (finding kind `HINT`); `DeviceIdentifier` gains a rule field `toolHint`; hints only ever add evidence and tool-triggered writes use 3A's `setIdentityIfNotWeaker` | Hints per tool only: a later run on another port drops earlier evidence (Gruff M5). Writing tool results into `hosts` or `ports` columns: schema growth and overwrites. |
| SNMP trigger | Offered per host only when discovery received an SNMP reply (`hosts.snmpSysDescr` not null) | Per port: the TCP port scan never finds UDP 161. Always offered: a guaranteed 2 s timeout on most hosts and a Tools block on every host. |
| SMB depth | Session probe only: NEGOTIATE, anonymous and guest SESSION_SETUP, CHALLENGE target info; the connection closes after SESSION_SETUP | Share listing over `srvsvc` in this phase: vetoed by Gruff (M6) until real fixtures exist; moved to 3C. |
| Name sent to servers | TLS certificate and HTTP fingerprint send the host's known name (mDNS, then reverse DNS) as SNI and Host when one exists; brute-force always sends the IP | Always the IP: reverse proxies answer with their default site and default certificate (Gruff Q1). |
| Removal and Undo | 3A's Remove device and Forget this network also snapshot the hosts' tool rows into SQLite TEMP tables and restore them on Undo | Leaving 3A's snapshot as host and ports only: the cascade drops tool history that Undo cannot bring back. Holding tool rows in memory: up to 150,000 rows on Forget (Gruff minor 2). |
| Export | Plain text through the share sheet (`ACTION_SEND`) | Files and a `FileProvider`: a new component for a few kilobytes of text. |

## 4. Tools framework

### 4.1 Interface

Package `com.northwalessolutions.netprobe.engine.tools`.

```kotlin
enum class ToolId(val key: String) {             // key is stored in tool_runs.toolId
    DIR_BRUTEFORCE("dir-bruteforce"), HTTP_FINGERPRINT("http-fingerprint"),
    TLS_CERTIFICATE("tls-certificate"), SMB_SESSION("smb-session"), SNMP_WALK("snmp-walk"),
}

data class ToolTarget(
    val hostId: Long, val ip: Inet4Address, val port: Int,
    val tls: Boolean,              // HTTPS for the web tools; always true for TLS certificate; false otherwise
    val serverName: String?,       // known name for SNI and Host (4.9); null when none or for brute-force
)

/** Run-wide controls owned by the runner: pause gate, rate ceiling, pause epoch (4.8, 5.3). */
interface RunControl {
    suspend fun gate()                       // suspends while paused, then takes one rate token for this host
    val pauseEpoch: Int                      // incremented on every pause
    fun excused(startEpoch: Int): Boolean    // true when a pause began after startEpoch, or the run is paused now
}

interface ToolProgress {
    fun update(done: Int, total: Int?, found: Int, errors: Int = 0)   // total null when unknown (SNMP walk)
    suspend fun finding(f: Finding)                                    // persisted immediately (4.6)
}

data class Finding(val kind: FindingKind, val value: String, val detail: String? = null, val url: String? = null,
                   val extra: Map<String, String> = emptyMap())

data class ToolResult(val status: ToolRunStatus, val summary: String)   // DONE or FAILED; STOPPED is set by the runner

interface Tool {
    val id: ToolId
    val name: String                                       // section 9
    fun applies(port: Int, service: ServiceInfo): Boolean
    fun appliesToHost(host: HostEntity): Boolean = false   // only SNMP walk overrides
    /** Suspends until done. Cancellation of the calling coroutine is the only stop. Calls control.gate() before every request. */
    suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult
}

sealed interface ToolOptions {
    object None : ToolOptions
    data class DirBrute(val options: DirBruteOptions) : ToolOptions   // 5.1
}
```

A tool never writes to Room itself; it reports through `ToolProgress`. Tool implementations take their I/O as constructor parameters (function types or small interfaces, as the discovery engine does) so JVM tests can fake them. No tool starts another scan or tool, and port-scan re-identification never starts a tool, so hints cannot form a feedback loop.

### 4.2 Applicability

A pure object `ToolApplicability` decides which tools a port gets, from the stored `ServiceInfo` (with 3A's evidence fields) and the port number. It reuses 3A's `PortUris.guess` so the action side and the tool side never disagree about what a port speaks.

| Tool | `applies(port, service)` is true when | `ToolTarget.tls` |
|---|---|---|
| Directory brute-force, HTTP fingerprint | `webScheme(port, service)` is not null | `webScheme == "https"` |
| TLS certificate | `webScheme(port, service) == "https"`, or port in `{465, 636, 993, 995, 5986, 8883}` | true |
| SMB session | port 445 (port 139 is excluded: it needs a NetBIOS session request first) | false |
| SNMP walk | never per port; `appliesToHost(h)` is `h.snmpSysDescr != null` | false (UDP 161) |

`webScheme(port, service)`: `PortUris.guess(port, service)` when it is `"http"` or `"https"`; otherwise `"https"` when the port is in `ServiceIdentifier.TLS`, `"http"` when the port is in `ServiceIdentifier.HTTP`; otherwise null. The fallback covers ports such as 8123, 32400 or 5001 whose banner read failed and kept their port-table name.

### 4.3 HostBusyRegistry and app messages

```kotlin
class HostBusyRegistry {                          // AppContainer singleton
    enum class Work { PORT_SCAN, TOOL }
    val busy: StateFlow<Map<Long, Work>>          // hostId -> work holding it
    fun tryClaim(hostId: Long, work: Work): Boolean   // atomic; false when the host is held
    fun release(hostId: Long, work: Work)             // no-op unless that work holds it
}

data class AppMessage(val id: Long, val text: String, val hostId: Long?)
class AppMessages {                               // AppContainer singleton
    val current: StateFlow<AppMessage?>
    fun post(text: String, hostId: Long? = null)  // replaces any unread message
    fun dismiss(id: Long)
}
```

- The Detail port scan claims `PORT_SCAN` before starting and releases in `finally`; if the host is held by a tool it does not start and shows "A tool is running on this device".
- Quick scan all claims each host for the duration of that host's scan, and skips any host held by a tool, counting it. Its existing end message gains the skipped count (section 9).
- `ToolRunner.start` claims `TOOL` and releases in the run's `finally`; a host held by a port scan returns `HostBusy`.
- Discovery does not claim hosts; tools may run during a radar scan (Gruff Q3) and the rate ceiling bounds their load.
- 3A's Remove device is disabled while its host is held by a tool, and Forget this network while any host of that network is, as 3A disables both during scans.
- Home rows: a host present in `busy` shows the word "running" in `caption` / `signal` in the row's right column in place of the open-port count; the row description appends ", port scan running" or ", tool running".
- `AppMessages.current` is shown by whichever screen is visible: the Home notice line (below 3A's Undo notice in priority) and the Detail snackbar. The visible screen dismisses it after showing it, so a message posted while Settings is open waits for Home or Detail. Tool end messages (4.4 rule 4) go here.

### 4.4 ToolRunner

```kotlin
class ToolRunner(
    private val scope: CoroutineScope,                       // AppContainer.appScope (3A spec 5.3)
    private val dispatcher: CoroutineDispatcher,             // Dispatchers.IO.limitedParallelism(TOOL_THREADS)
    private val tools: Map<ToolId, Tool>,
    private val repo: ToolRepository,
    private val busy: HostBusyRegistry,
    private val messages: AppMessages,
    private val env: StateFlow<NetworkEnv?>,
    private val foreground: StateFlow<Boolean>,              // 4.8
    private val bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit,   // ScanBinding.run with current settings
    private val onFinished: suspend (hostId: Long) -> Unit,  // re-identification (4.7)
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Active(val runId: Long, val hostId: Long, val toolId: ToolId, val port: Int, val activeMs: Long,
                      val done: Int, val total: Int?, val found: Int, val errors: Int, val paused: Boolean)
    val active: StateFlow<Map<Long, Active>>                 // hostId -> the running run
    sealed interface Start { data class Started(val runId: Long) : Start; object HostBusy : Start; object TooMany : Start; object NoNetwork : Start }
    suspend fun start(target: ToolTarget, toolId: ToolId, options: ToolOptions): Start
    fun stop(hostId: Long)
    companion object {
        const val MAX_ACTIVE_RUNS = 4; const val KEEP_RUNS_PER_TOOL = 10; const val PROGRESS_INTERVAL_MS = 200L
        const val TOOL_THREADS = 16; const val MAX_REQUESTS_PER_SECOND = 100
    }
}
```

Rules:

1. `start` first awaits `repo.ready` (completed by `markInterrupted`, rule 5), so a run started in the first moments is never marked `INTERRUPTED` under its live job. It returns `HostBusy` when `busy.tryClaim(hostId, TOOL)` fails, `TooMany` when `MAX_ACTIVE_RUNS` hosts are running, `NoNetwork` when `env.value` is null.
2. On start, in one transaction: insert a `tool_runs` row with status `RUNNING`, the target `port`, `startedAt = clock()`, `optionsJson` (7.1) and summary `""`; then prune that host and tool to the newest `KEEP_RUNS_PER_TOOL` runs (7.2).
3. The tool runs in `scope.launch(dispatcher)` inside `bind(ip, env) { ... }`, which is `ScanBinding.run(ip, env, settings.maxPrefix, settings.extEnabled, networkEnvMonitor::binderFor)`: local hosts run unbound, off-subnet hosts with extended discovery on run with `BoundNetwork`, and a host with no network to bind fails with `BindFailedException`. Every tool coroutine and every blocking call stays on `dispatcher` (4.6), so tool I/O never holds more than `TOOL_THREADS` threads in total, whatever the number of runs.
4. End states, written with `finishedAt = clock()` in `withContext(NonCancellable)`, then the host is released and `messages.post("<tool name> finished: <summary>", hostId)`:
   - Normal return: the tool's `ToolResult` (`DONE` or `FAILED` with its summary).
   - Stop: `STOPPED` with the stopped summary (section 9) built from the last progress values.
   - Network identity change (the `ScanController` rule: env becomes null or `NetworkSignature.identityKey` differs from the one at start): every active run is cancelled and written `STOPPED`, "Network changed, run stopped".
   - `BindFailedException`: `FAILED`, "Tool skipped: could not bind to Wi-Fi" (worded as 3A's D17).
   - Any other exception escaping the tool: `FAILED`, "Failed: <message or class name>". The runner never crashes the app.
5. Process death leaves a `RUNNING` row. `ToolRepository.markInterrupted()` is launched once when `AppContainer` creates the repository and completes `ready`; every `RUNNING` row becomes `INTERRUPTED`, `finishedAt` stays null, summary "Interrupted, N found", where N is that run's finding count without hints.
6. After every end state, `onFinished(hostId)` re-runs identification (4.7). It is skipped for a `FAILED` run that recorded no finding.
7. `AppContainer` exposes `toolRunActive: StateFlow<Boolean>` (`active` not empty). `NetProbeNav` keeps the screen on while `extendedRunActive || toolRunActive`, through the existing `KeepScreenOn`.
8. `active` is republished every `PROGRESS_INTERVAL_MS` while any run is active (a ticker, not only on updates), so elapsed time keeps moving while a request is slow; `activeMs` excludes paused time (4.8).

### 4.5 Sockets and binding

Every TCP socket a tool opens comes from `Probe.connect(ip, port, timeoutMs, keepOpen = true)`, which binds through `currentBinder()` before connecting. TLS is layered over that connected socket with `SSLSocketFactory.createSocket(socket, sniName, port, true)`, where `sniName` is `target.serverName` when set and the IP string otherwise, so binding and cancellation are identical for plain and TLS. The SNMP walk's `DatagramSocket` is bound with `currentBinder()?.bindOrFail(socket)` as `Snmp.query` does today. A bind failure is thrown as `BindFailedException` and is never read as a closed or silent port.

Trust-all TLS (the trust manager `TlsPeek` and `HttpTitleFetcher` already use) is used only to read. One `SSLContext` is created per run, so the platform session cache resumes TLS sessions where the server allows it. On tool sockets every entry of `supportedProtocols` and `supportedCipherSuites` is enabled, each setter wrapped so an unsupported value never throws, so old devices that only speak TLS 1.0 or 1.1 or only offer RSA key exchange or 3DES can still be read. The browser launch never uses this trust setting.

### 4.6 HttpClient, CancellableIo and progress

`engine/common/CancellableIo.kt`: `suspend fun <T> closing(socket: Closeable, block: () -> T): T` runs `block` with `runInterruptible` and no dispatcher argument, so it inherits the caller's dispatcher as `Probe.connect` does, with a watcher coroutine (the `Probe.connect` pattern) that closes the socket when the caller is cancelled. Used by `HttpClient`, the TLS tool, the SMB session probe and the SNMP walk. This is what makes "Stop ends within 1 s" hold during reads, not only during connects, without touching `Dispatchers.IO`.

`engine/common/HttpClient.kt`:

```kotlin
data class HttpResponse(
    val status: Int, val reason: String,
    val headers: List<Pair<String, String>>,    // in order, names as sent
    val body: ByteArray,                        // decoded (de-chunked), at most maxBody bytes; empty for HEAD, 1xx, 204, 304
    val bodyTruncated: Boolean,
    val contentLength: Long?,                   // from the header, null when absent
) {
    fun header(name: String): String?           // first match, case-insensitive
    fun headersAll(name: String): List<String>
}

sealed interface HttpFailure { object Refused : HttpFailure; object Timeout : HttpFailure; object Reset : HttpFailure; object Malformed : HttpFailure }
sealed interface HttpResult { data class Ok(val response: HttpResponse) : HttpResult; data class Failed(val failure: HttpFailure) : HttpResult }

class HttpClient(private val timeoutMs: Int, private val maxBody: Int = MAX_BODY_BYTES) {
    companion object { const val MAX_BODY_BYTES = 65_536; const val MAX_HEADER_BYTES = 16_384; const val USER_AGENT = "NetProbe/0.1" }
    /** Failed on any exchange failure; cancellation and BindFailedException propagate. */
    suspend fun request(ip: Inet4Address, port: Int, tls: Boolean, method: String /* "GET" or "HEAD" */, path: String,
                        hostName: String? = null, sslContext: SSLContext? = null): HttpResult
}
```

- Request bytes, exactly: `<METHOD> <path> HTTP/1.1\r\nHost: <host>\r\nUser-Agent: NetProbe/0.1\r\nAccept: */*\r\nConnection: close\r\n\r\n`. `<host>` is `hostName` when given, else the IP, plus `:<port>` unless the port is 80 for http or 443 for https.
- Redirects are never followed; `Location` is returned in `headers`.
- Line endings: CRLF or a bare LF are both accepted in the status line and headers.
- Interim `1xx` responses (such as `100 Continue`) are skipped and the next status line read.
- The status line must match `HTTP/1\.[01] \d{3}( .*)?`, else `Malformed`. Headers are read until a blank line; beyond `MAX_HEADER_BYTES` the response is `Malformed`.
- Body (GET only, never for 1xx, 204 or 304): `Transfer-Encoding: chunked` is de-chunked; otherwise `Content-Length` bytes; otherwise until close or until the overall deadline, which bounds a server that ignores `Connection: close` and sends neither framing header. Reading stops at `maxBody` with `bodyTruncated = true`.
- Timeouts: connect timeout and `soTimeout` are `timeoutMs`; an overall deadline of `2 x timeoutMs` per exchange stops a server that drips bytes.
- Every exchange runs inside `CancellableIo.closing`. Sockets are closed in `finally` on every path.

`HttpTitleFetcher` is unchanged (discovery and 3A's probe ladder keep their behaviour); the brute-force and fingerprint tools use `HttpClient` only.

Progress: tools call `ToolProgress.update` as often as they like; the runner keeps the latest values in atomics and publishes them on its ticker (4.4 rule 8). Findings are written to Room as they arrive (one insert each), so a crash or Stop keeps everything found so far. A failed insert is logged and the run continues (11.10).

### 4.7 Identification hints

Tools emit `Finding(kind = HINT, value = "<namespace>.<key>=<value>")`. Hints are stored with the run, never shown in the results sheet and never exported.

- `ToolRepository.hintsFor(hostId): List<String>`: for each (tool, port) pair on the host, the hints of one run: the newest `DONE` run if there is one, else the newest `STOPPED` or `INTERRUPTED` run. `FAILED` and `RUNNING` runs never contribute. A fingerprint of port 80 therefore never hides what a fingerprint of 5000 found, and a stopped partial run never hides a complete one.
- `HostEvidence` gains `toolHints: List<String> = emptyList()`; `HostEvidenceBuilder.build` gains a `toolHints` parameter with the same default. Every path that identifies a stored host passes `hintsFor(hostId)`: discovery's upsert (`HomeViewModel`), 3A's post-port-scan paths (`DetailViewModel`, Quick scan all) and `ToolRunner.onFinished`.
- Hints only ever add evidence, so their presence can never lower an identity. Writes triggered by a tool (`onFinished`) and by port scans go through 3A's `setIdentityIfNotWeaker`. Discovery keeps 3A's rule of always writing (a re-used IP must be able to lose an old identity), and because it passes the hints, a host whose evidence is unchanged keeps its tool-derived identity.
- `DeviceIdentifier` gains the rule field `"toolHint"`, matched against each hint string; the reason recorded is `toolHint: <hint>` (shown in "Why this identity", cut to 80 characters as today).
- Seed rules appended to `identify_rules.json` after 3A's additions and before `gateway`, each with a `sample` and a fixture test like the existing rules:

| id | pattern | Sets | Weight |
|---|---|---|---|
| hint-synology-dsm | `^http\.framework=Synology DSM$` | vendor Synology, type NAS | 90 |
| hint-pihole | `^http\.framework=Pi-hole$` | model Pi-hole, type SERVER | 90 |
| hint-unifi | `^http\.framework=UniFi OS$` | vendor Ubiquiti, type ROUTER | 70 |
| hint-hue | `^http\.framework=Philips Hue$` | vendor Philips Hue, type IOT | 90 |
| hint-home-assistant | `^http\.framework=Home Assistant$` | model Home Assistant, type SERVER | 90 |
| hint-proxmox | `^http\.framework=Proxmox VE$` | model Proxmox VE, type SERVER | 90 |
| hint-openwrt | `^http\.framework=OpenWrt LuCI$` | os OpenWrt, type ROUTER | 90 |
| hint-tls-synology | `^tls\.subject=.*Synology` | vendor Synology, type NAS | 70 |
| hint-tls-ubiquiti | `^tls\.issuer=.*(UniFi\|Ubiquiti)` | vendor Ubiquiti, type ROUTER | 70 |
| hint-snmp-synology | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.6574\.` | vendor Synology, type NAS | 90 |
| hint-snmp-mikrotik | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.14988\.` | vendor MikroTik, os RouterOS, type ROUTER | 90 |
| hint-snmp-ubiquiti | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.41112\.` | vendor Ubiquiti | 70 |
| hint-snmp-cisco | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.9\.` | vendor Cisco, type ROUTER | 70 |
| hint-snmp-netsnmp | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.(8072\|2021)\.` | os Linux | 50 |

No SMB rule is seeded in 3B: SMB hints are stored for 3C (14.6). Hint namespaces per tool are listed with each tool (5.5, 6.1 to 6.4).

### 4.8 Background pause

- `MainActivity` registers a `DefaultLifecycleObserver` on its own lifecycle (already on the classpath through `ComponentActivity`) that sets `AppContainer.foreground: MutableStateFlow<Boolean>`: false on ON_STOP, true on ON_START. NetProbe is a single-activity app, so this is the app's foreground state. No new dependency.
- When `foreground` becomes false, the runner pauses every active run: `pauseEpoch` increments, `gate()` suspends new requests, in-flight requests are left to finish, and the elapsed clock (`activeMs`) stops. When it becomes true, `gate()` releases, the rate bucket refills from empty, and the clock resumes.
- A failure is excused when `control.excused(epochAtRequestStart)` is true: the pause began after the request started, or the run is paused now. An excused failure is not counted as an error, does not count towards consecutive failures, does not shrink concurrency (5.3), and its path is requeued to run after the resume. This covers a frozen process whose in-flight reads time out on thaw, in whatever order the thaw and ON_START arrive.
- Companion tools excuse failures the same way: an excused exchange is retried once after the resume.
- If the process dies while paused, rule 5 of 4.4 records the run as `INTERRUPTED` on the next launch.
- While paused the Device screen is not visible, so no paused string is shown; the Home and Detail screens see the run as active with a stopped clock when they return.

### 4.9 Name sent to servers

`ToolTarget.serverName` is the host's `mdnsName`, else its `reverseDnsName`, else null, and is used by the TLS certificate tool (SNI) and the HTTP fingerprint (Host). If the TLS handshake with the name fails, the tool retries once with the IP and no name. Directory brute-force always uses the IP (its calibration and filter are per address). The name used is stored in `optionsJson` as `serverName`, shown in the results header (8.5) and exported (7.5).

## 5. Directory brute-force

### 5.1 Options

```kotlin
@Serializable enum class DirMethod { HEAD_THEN_GET, GET_ONLY }
@Serializable data class DirBruteOptions(
    val wordlistId: String = "builtin:common-short",   // "builtin:<name>" or "user:<uuid>"
    val extensions: List<String> = emptyList(),
    val method: DirMethod = DirMethod.HEAD_THEN_GET,
    val concurrency: Int = 8,                           // 1..16
    val timeoutS: Int = 3,                              // 1..10
    val statusCodes: List<Int> = listOf(200, 204, 301, 302, 307, 401, 403),
    val baseline: Boolean = true,                       // "Filter not-found pages": calibration and filter (5.4)
)
```

| Option | Control | Default | Validation (messages in section 9) |
|---|---|---|---|
| Wordlist | Radio list of every wordlist | `common-short` | A missing user list falls back to `common-short` with a notice |
| Extensions | Text field, comma separated | empty | Each trimmed, one leading dot dropped, must match `^[A-Za-z0-9]{1,8}$`; at most `MAX_EXTENSIONS = 8`; duplicates collapsed |
| Method | Two segments | HEAD then GET | none |
| Concurrency | Step slider, step 1 | 8 | coerced to 1..16; the starting and maximum worker count (5.3) |
| Timeout per request | Step slider, step 1 s | 3 s | coerced to 1..10 s |
| Report status codes | Text field, comma separated | `200, 204, 301, 302, 307, 401, 403` | Each an integer 100..599; at least one; duplicates collapsed |
| Filter not-found pages | Switch | on | none |

Last-used options are saved globally in SharedPreferences key `tool.dirBrute.options` (JSON of `DirBruteOptions`) when Run is tapped, and loaded when the sheet opens. Invalid stored JSON loads the defaults.

### 5.2 Request flow

Paths: for each wordlist entry `w` (already normalised, 5.6.4) in file order, the paths are `/w`, then `/w.<ext>` for each extension in the order given. Progress counts paths (`entries x (1 + extensions)`), not requests; HEAD then GET, retries and calibration send more requests than paths, and every string says "paths" (section 9).

1. Calibration (when the switch is on, 5.4). A failed calibration ends the run `FAILED`, "The server did not answer the first request". If calibration shows HEAD and GET disagree, the run is GET_ONLY from the start and `optionsJson.methodUsed` records it.
2. Workers: a producer streams paths from the wordlist (5.6.3 `withEntries`) into a `Channel(capacity = 16)`; up to `concurrency` worker coroutines take paths, each request first calling `control.gate()` and then taking a permit from the adaptive limiter (5.3).
3. Per path, HEAD_THEN_GET:
   - HEAD. If the status is 405 or 501, re-issue as GET (still one path). After `HEAD_FALLBACK_SWITCH = 3` such fallbacks the run switches to GET_ONLY for the remaining paths.
   - If the HEAD status is not in the report set, it is a miss.
   - Otherwise GET, then apply the filter (5.4) to the GET response. A finding is reported with the GET status when that status is in the report set and the filter does not hide it.
   GET_ONLY: one GET per path, then the same report-set check and filter.
4. Failures:
   - Excused failures (4.8) requeue the path and count nothing.
   - `Timeout` or `Reset`: the limiter halves (5.3); the path is retried once, after `RETRY_DELAY_MS = 250` and only once the limiter has applied the reduction (the retry takes a permit like any request). A second failure counts as an error (shown in progress and summary) and the run continues.
   - `Refused` and `Malformed` count as failures without retry.
   - After `MAX_CONSECUTIVE_FAILURES = 50` counted failures in a row the run ends `FAILED`, "The server stopped answering after N paths".
5. At `MAX_FINDINGS = 500` findings the run ends `DONE`, "Stopped at 500 findings".
6. Runaway check (5.4.3) after the first 200 completed paths.
7. Progress: `done` = paths finished, `total` = total paths, `found` = findings, `errors` = counted errors.

Path encoding: every character outside the RFC 3986 unreserved set, `/`, and an existing valid `%XX` escape is percent-encoded as UTF-8.

### 5.3 Load control

Both controls apply to every tool's requests, not only brute-force; the companion tools are sequential, so only the ceiling affects them.

- Rate ceiling: a token bucket per host, `MAX_REQUESTS_PER_SECOND = 100`, burst 10, taken in `control.gate()`. A constant, not an option. It refills from empty after a pause.
- Adaptive concurrency (AIMD), brute-force only: the active limit `L` starts at the chosen concurrency `C`. On a counted `Timeout` or `Reset`, `L = max(1, L / 2)`, at most once per `timeoutS` window, so one burst of simultaneous timeouts halves once. After `RECOVERY_SUCCESSES = 20` consecutive successes, `L = min(C, L + 1)`. Workers above `L` wait for a permit; the limiter is a resizable semaphore. Excused failures (4.8) never change `L`.
- Concurrency is further bounded by the shared tool dispatcher (`TOOL_THREADS = 16` threads across all runs).

### 5.4 Calibration and the not-found filter

#### 5.4.1 Calibration requests

- Groups: "bare", and one group per extension.
- For each group, GET 5 random paths (`/<12 random lowercase letters and digits>` for bare, the same with `.<ext>` for an extension group). At most 5 x (1 + 8) = 45 requests.
- HEAD `/` and GET `/`; HEAD the first bare random path. If either HEAD status differs from its GET status (the bare random path's GET is already in hand), the run uses GET_ONLY.
- If no calibration request gets a response, the run fails (5.2 step 1). Individual calibration failures are retried once; a group with fewer than 3 responses is calibrated from what it has.

#### 5.4.2 Baselines and the filter

For each group and each status seen in its samples, a baseline holds:

- the size band `[min - pad, max + pad]` over the normalised sizes of that status's samples, where `pad = max(32, max - min)` bytes; unknown sizes form their own "unknown" baseline;
- for 3xx, the set of normalised `Location` values (each sample's Location with its requested path replaced by a fixed token).

Normalised size = body bytes read minus (occurrences of the requested path in the body x its length in bytes); unknown when `bodyTruncated` and there is no `Content-Length`.

A GET response for a path is hidden when its group (bare, or its extension) has a baseline for the same status and its normalised size falls inside the band (or both are unknown), and, for 3xx, its normalised Location is in the baseline's set. With the switch off nothing is hidden and no calibration runs.

#### 5.4.3 Runaway check

After the first 200 completed paths: if more than 50 findings share one status and their normalised sizes lie within 64 bytes of each other, the server answers every path alike. The run ends `DONE` with the summary "Stopped: the server answers every path alike" and keeps the findings so far.

### 5.5 Findings and summary

One finding per hit, kind `PATH`:

| Field | Content |
|---|---|
| value | the path, for example `/admin` |
| detail | `<status>  <size>`, plus `  to <Location>` for 3xx (section 9 formats) |
| url | `http(s)://<ip>:<port><path>` |
| extra | `status`, `size` (bytes, empty when unknown), `location` |

Sizes: `0 B` to `1023 B`; then one decimal, `1.2 KB`; `64 KB+` when truncated with no `Content-Length`; omitted when unknown.

Hints: `http.path=<path>` for each finding whose status is 200, 204 or 401 (no rule uses them yet; panel-path rules can be added by JSON edit).

Summary: `DONE`: "7 found in 4614 paths" (", 3 errors" appended when errors > 0); `STOPPED`: "Stopped after 1234 of 4614, 7 found"; the cap: "Stopped at 500 findings"; the runaway: "Stopped: the server answers every path alike"; the two `FAILED` messages in 5.2.

### 5.6 Wordlists

#### 5.6.1 Bundled

Authored by the implementer in this repo; nothing is downloaded. Each list is composed by category from the examples below and the implementer's own knowledge of web and device paths, sorted alphabetically within each category with a blank-free layout, and must not reproduce any published list's ordering, headers or comments. The plan's reviewer checks this. Plain UTF-8 text, LF line endings, one entry per line, no leading slash, no comments, no blanks, no duplicates, ASCII only.

| Name | File | Entries | Content |
|---|---|---|---|
| common-short | `res/raw/wordlist_common_short.txt` | about 300 (250 to 350) | Generic web paths: `admin`, `login`, `backup`, `config`, `.git/HEAD`, `.env`, `server-status`, `phpinfo.php`, `wp-admin`, `uploads`, `static`, `test`, `old`, `cgi-bin` and similar |
| admin-panels | `res/raw/wordlist_admin_panels.txt` | about 150 (120 to 180) | Device and appliance panels: `webman`, `cgi-bin/luci`, `admin/index.php`, `manager/html`, `setup.cgi`, `HNAP1`, `ui`, `network`, `system`, `phpmyadmin`, `adminer.php` and similar |
| api-paths | `res/raw/wordlist_api_paths.txt` | about 150 (120 to 180) | API and discovery endpoints: `api`, `api/v1`, `v2`, `swagger.json`, `openapi.json`, `graphql`, `actuator/health`, `metrics`, `.well-known/openid-configuration`, `health`, `status`, `version` and similar |

Built-in ids: `builtin:common-short`, `builtin:admin-panels`, `builtin:api-paths`. Built-ins cannot be deleted.

#### 5.6.2 Import

- "Import wordlist" launches `ActivityResultContracts.OpenDocument()` with `arrayOf("text/plain")` (`ACTION_OPEN_DOCUMENT`). No permission is needed.
- The stream is parsed (5.6.4) and the normalised entries are written to a temporary file in `files/wordlists/`, renamed to `<uuid>.txt` (UTF-8, LF) on success. The source document is not kept or re-read.
- Name: `OpenableColumns.DISPLAY_NAME` without its last extension; if empty, "Imported list". On a clash with an existing name (built-in or imported), " (2)", " (3)" and so on are appended.
- Limits: the byte count read from the stream is enforced as it is read (`OpenableColumns.SIZE` may be null), and reading aborts at `MAX_IMPORT_BYTES = 10_485_760` (10 MB); more than `MAX_ENTRIES = 200_000` entries after normalisation is rejected; zero entries is rejected. Nothing is kept on rejection.
- Import runs on `Dispatchers.IO`; the button reads "Importing" until it finishes.

#### 5.6.3 Store

```kotlin
@Serializable data class WordlistMeta(val id: String, val name: String, val entries: Int, val importedAt: Long?, val builtIn: Boolean)
sealed interface ImportResult {
    data class Imported(val meta: WordlistMeta, val skipped: Int) : ImportResult
    object TooLarge : ImportResult; object TooMany : ImportResult; object Empty : ImportResult; object Unreadable : ImportResult
}
class WordlistStore(private val dir: File, private val builtIns: List<BuiltInList>) {
    val lists: StateFlow<List<WordlistMeta>>          // built-ins first (table order), then imported, newest first
    suspend fun import(displayName: String?, source: InputStream): ImportResult
    suspend fun delete(id: String)                    // imported only; removes the file and the index entry
    /** Opens the list, streams its entries to block, and always closes the reader (also on cancellation). Null when missing. */
    suspend fun <T> withEntries(id: String, block: suspend (Sequence<String>) -> T): T?
}
```

`index.json` holds the `WordlistMeta` of imported lists. A missing or unreadable index loads as empty, and `.txt` files in the directory without an index entry are deleted. Built-in entry counts are computed once from the raw resources. Deleting a list while a run reads it is safe: the run's open descriptor keeps reading the unlinked file until it closes.

#### 5.6.4 Parsing and normalisation

`WordlistParser.parse(input: InputStream): ParseResult(entries: List<String>, skipped: Int)`, streaming, used for imports and verified against the bundled lists:

1. A UTF-8 BOM at the start is dropped.
2. Lines split on LF, CRLF or a lone CR.
3. Each line is trimmed. Blank lines and lines starting with `#` are dropped (not counted as skipped).
4. Leading `/` characters are removed. A line that is then empty is dropped.
5. Lines that are not valid UTF-8, contain control characters, or are longer than `MAX_ENTRY_CHARS = 256` are skipped (counted).
6. Duplicates are removed, first occurrence kept, order preserved.

### 5.7 Expected timings (Wi-Fi, defaults)

| Target | Typical |
|---|---|
| common-short (about 300) against a LAN HTTP server | 4 to 8 s (the 100 per second ceiling plus calibration) |
| A 4,614-entry imported list against a LAN HTTP server | 50 to 90 s |
| The same over HTTPS with session resumption | 1 to 3 min |
| The same over HTTPS against an embedded TLS stack without resumption (full handshakes of 200 to 500 ms) | can exceed 10 min; adaptive concurrency keeps it from failing |

Targets to measure on the Pixel 7 Pro (12.3); correct this table if off by more than 50 percent.

## 6. Companion tools

All four run at once when tapped (no options sheet), with the fixed values below as named constants. Each calls `control.gate()` before every request and excuses failures across a pause (4.8).

### 6.1 HTTP fingerprint

Requests, sequential, GET via `HttpClient` with `hostName = target.serverName`, `FINGERPRINT_TIMEOUT_MS = 3000` each:

1. `/`. If it answers 3xx with a `Location` on the same IP (any scheme and any port), one GET of that location, with `tls` taken from the Location's scheme. The tool follows this one hop itself; `HttpClient` never does.
2. `/robots.txt`
3. `/sitemap.xml`
4. `/.well-known/security.txt`
5. `/<12 random lowercase letters and digits>` (the error page)

Steps 2 to 5 go to the port the redirect landed on when there was a hop, else the target port. Progress total is 5 (6 with the hop).

Findings (a file answering anything but 200 produces nothing):

| Kind | value | detail |
|---|---|---|
| `STATUS` | "200 OK" (status and reason) | "GET /", or "GET /, to <Location>" for 3xx; the hop's own status has "GET <url>" |
| `HEADER` | the header value | the header name; for each of `Server`, `X-Powered-By`, `X-AspNet-Version`, `X-AspNetMvc-Version`, `X-Generator`, `Via`, `WWW-Authenticate` |
| `COOKIE` | the cookie name only, never its value | "Cookie", plus ", Secure", ", HttpOnly", ", SameSite=<v>" when present |
| `TITLE` | `<title>` via `HttpTitleParser.title` | "Page title" |
| `GENERATOR` | `<meta name="generator">` content | "Generator meta tag" |
| `ROBOTS` | "/robots.txt" (url set) | "N rules" |
| `PATH` | each `Disallow` or `Allow` path in robots.txt, at most `MAX_ROBOTS_PATHS = 50`, wildcards kept (url set when the path has no `*` or `$`) | "Disallowed in robots.txt" or "Allowed in robots.txt" |
| `SITEMAP` | "/sitemap.xml" (url set) | "N URLs" (count of `<loc>`) |
| `SECURITY_TXT` | "/.well-known/security.txt" (url set) | "Contact: <first Contact value>", or "Present" |
| `ERROR_PAGE` | the server signature on the error page (`<address>...</address>`, `<center>nginx/x</center>`, or `Apache Tomcat/x`) | "Error page signature" |
| `FRAMEWORK` | the rule's product name | "Matched <source>: <excerpt up to 40 chars>" |

Framework rules: `res/raw/http_fingerprints.json`, hand-authored, schema `{id, source, pattern, name, sample, negativeSample}`. `source` is `header:<name>`, `cookie`, `title`, `generator`, `body` (the `/` body, or the hop's body when there was a hop) or `errorBody`; `pattern` is a case-insensitive regex; `name` may use `$1` (trimmed when the group is empty); `negativeSample` (optional) must not match. Each matching rule adds one `FRAMEWORK` finding, one per distinct name. Seed:

| id | source | pattern | name |
|---|---|---|---|
| synology-title | title | `Synology\|DiskStation` | Synology DSM |
| synology-body | body | `SYNO\.SDS\|/webman/` | Synology DSM |
| pihole | title | `Pi-hole` | Pi-hole |
| unifi-os | title | `^UniFi OS$` (negative sample "UniFi Network") | UniFi OS |
| hue | body | `hue personal wireless lighting` | Philips Hue |
| home-assistant | title | `Home Assistant` | Home Assistant |
| proxmox | title | `Proxmox Virtual Environment` | Proxmox VE |
| openwrt | title | `LuCI\|OpenWrt` | OpenWrt LuCI |
| grafana | title | `Grafana` | Grafana |
| jellyfin | title | `Jellyfin` | Jellyfin |
| plex | header:x-plex-protocol | `.+` | Plex Media Server |
| wordpress-gen | generator | `WordPress ?([\d.]*)` | WordPress $1 |
| wordpress-body | body | `/wp-content/\|/wp-includes/` | WordPress |
| drupal | header:x-generator | `Drupal` | Drupal |
| joomla | generator | `Joomla` | Joomla |
| php | header:x-powered-by | `PHP/?([\d.]*)` | PHP $1 |
| php-cookie | cookie | `^PHPSESSID$` | PHP |
| aspnet | header:x-aspnet-version | `.+` | ASP.NET |
| aspnet-cookie | cookie | `^ASP\.NET_SessionId$` | ASP.NET |
| java | cookie | `^JSESSIONID$` | Java servlet container |
| express | header:x-powered-by | `^Express$` | Express (Node.js) |
| laravel | cookie | `^laravel_session$` | Laravel |
| tomcat | errorBody | `Apache Tomcat/([\d.]+)` | Apache Tomcat $1 |
| nginx | header:server | `^nginx/?([\d.]*)` | nginx $1 |
| apache | header:server | `^Apache/?([\d.]*)` | Apache httpd $1 |
| iis | header:server | `^Microsoft-IIS/([\d.]+)` | Microsoft IIS $1 |
| lighttpd | header:server | `^lighttpd/?([\d.]*)` | lighttpd $1 |

Hints: `http.server=`, `http.poweredBy=`, `http.title=`, `http.generator=`, `http.cookie=<name>`, `http.realm=`, and `http.framework=<name>` with any trailing version number removed (so the `hint-*` rules match "Synology DSM", never "Synology DSM 7").

Summary: "<first FRAMEWORK name, else the Server header, else 'No product named'>, N findings". `FAILED` when `/` gets no response: "No HTTP response on this port".

### 6.2 TLS certificate

`TlsPeek` gains (3A's `certInfo` keeps its signature and behaviour):

```kotlin
data class TlsDetails(val protocol: String, val cipherSuite: String, val chain: List<X509Certificate>, val serverNameSent: String?)
open suspend fun details(ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int = TLS_TOOL_TIMEOUT_MS /* 3000 */): TlsDetails?
```

It connects through `Probe.connect(keepOpen = true)`, layers the trust-all `SSLSocket` with SNI set to `serverName` when given (4.5, 4.9), handshakes inside `CancellableIo.closing`, reads `session.peerCertificates` (the whole chain), and closes. A failed handshake with a name is retried once without one. A pure mapper `CertSummaries.of(details, now): List<Finding>` builds the findings, so all logic is JVM-testable from fixtures.

| Kind | value | detail |
|---|---|---|
| `CERT_SUBJECT` | RFC 2253 subject of the leaf | "Subject" |
| `CERT_ISSUER` | RFC 2253 issuer of the leaf | "Issuer" |
| `CERT_SAN` | each DNS or IP SAN | "Alternative name (DNS)" or "Alternative name (IP)" |
| `CERT_VALIDITY` | "<notBefore> to <notAfter>" (medium date) | "Valid", "Expired", "Not yet valid", or "Expires in N days" when within `EXPIRY_SOON_DAYS = 30` |
| `CERT_KEY` | "RSA N bit", "EC N bit" (field size), "DSA N bit" (`params.p.bitLength()`), "Ed25519", "Ed448", or the algorithm name | "Public key" |
| `CERT_SIGNATURE` | `sigAlgName` | "Signature algorithm" |
| `CERT_FINGERPRINT` | SHA-256 of the leaf's DER, colon-separated upper-case hex | "SHA-256 fingerprint" |
| `CERT_CHAIN` | subject of each non-leaf certificate | "Chain certificate N of M" |
| `TLS_SESSION` | "<protocol> <cipher suite>" | "Protocol and cipher" |
| `FLAG` | "Self-signed" when issuer equals subject and the leaf verifies with its own key | "Certificate" |
| `FLAG` | "RSA key under 2048 bit" when RSA and under `WEAK_RSA_BITS = 2048` | "Public key" |
| `FLAG` | "Signed with SHA-1" when `sigAlgName` contains `SHA1` | "Signature algorithm" |

Hints: `tls.subject=`, `tls.issuer=`, `tls.san=` (one per SAN).

Summary: "<subject CN, else the first SAN>, expires <date>", with ", self-signed" appended when flagged. `FAILED`: "No TLS handshake on this port".

### 6.3 SMB session

A minimal SMB2 session probe in `engine/tools/smb/`, hand-written from MS-SMB2, MS-NLMP and MS-SPNG, with no library. It sends NEGOTIATE and SESSION_SETUP only and closes the TCP connection after the last SESSION_SETUP reply; it never sends TREE_CONNECT, CREATE, READ, WRITE, IOCTL or LOGOFF. Share enumeration and read checks are Phase 3C (section 15).

Transport: TCP 445 via `Probe.connect(keepOpen = true)`, direct-TCP framing (a zero byte plus a 24-bit big-endian length), every exchange inside `CancellableIo.closing`, `SMB_TIMEOUT_MS = 3000` per exchange, responses capped at 64 KB.

Builder rules (written out because they are where hand-written clients break):

- SMB2 header: `MessageId` starts at 0 and increments by one per request on a connection; `CreditCharge` 0 for 2.0.2 and 1 otherwise; `CreditRequest` 1; `SessionId` 0 on the first SESSION_SETUP and the server's value on the second.
- NTLM NEGOTIATE flags: `NEGOTIATE_UNICODE | REQUEST_TARGET | NEGOTIATE_NTLM | NEGOTIATE_ALWAYS_SIGN | NEGOTIATE_EXTENDED_SESSIONSECURITY | NEGOTIATE_TARGET_INFO | NEGOTIATE_VERSION | NEGOTIATE_128 | NEGOTIATE_56`, plus `NEGOTIATE_ANONYMOUS` on the anonymous leg's AUTHENTICATE.
- The first SESSION_SETUP carries a SPNEGO `NegTokenInit` with mechType NTLMSSP (`1.3.6.1.4.1.311.2.2.10`) and the NTLM NEGOTIATE as `mechToken`; the second carries a SPNEGO `NegTokenResp` with the NTLM AUTHENTICATE as `responseToken`. A minimal DER writer and reader in `Spnego.kt`.

Sequence:

1. NEGOTIATE with dialects 0x0202, 0x0210, 0x0300, 0x0302; SecurityMode SIGNING_ENABLED; a random ClientGuid; no negotiate contexts. Record the dialect and whether the server requires signing (SecurityMode bit 0x02). No answer ends the run `FAILED`, "No SMB response on this port"; a refusal of every offered dialect ends it `FAILED`, "No SMB2 dialect in common".
2. Anonymous session (this connection): the NEGOTIATE leg returns `STATUS_MORE_PROCESSING_REQUIRED` with the NTLM CHALLENGE, parsed for its target info (NetBIOS computer and domain names, DNS computer and domain names) and the NTLM version (OS build). Then NTLM AUTHENTICATE with the anonymous flag, empty user and domain, an empty NT response and a single zero LM byte (MS-NLMP 3.2.5.1.2). `STATUS_SUCCESS` means allowed; any other status means refused. Close the connection.
3. Guest session (a new connection, same NEGOTIATE): user `guest`, empty domain, empty password, NTLMv2 response (NTOWFv2 from a hand-written MD4 per RFC 1320 and `Mac.getInstance("HmacMD5")`, blob timestamp from the CHALLENGE's MsvAvTimestamp when present, else now), no MIC. `STATUS_SUCCESS` with SessionFlags IS_GUEST reads "Guest mapped to guest"; `STATUS_SUCCESS` without it reads "Guest allowed"; anything else "Guest refused". Close the connection.
4. The run ends `DONE`.

Findings:

| Kind | value | detail |
|---|---|---|
| `SMB_DIALECT` | "SMB 2.0.2", "SMB 2.1", "SMB 3.0" or "SMB 3.0.2" | "Dialect" |
| `SMB_SIGNING` | "Required" or "Enabled, not required" | "Signing" |
| `SMB_SESSION` | "Anonymous allowed" or "Anonymous refused" | "Session" |
| `SMB_SESSION` | "Guest allowed", "Guest mapped to guest" or "Guest refused" | "Session" |
| `SMB_HOST` | each of NetBIOS name, NetBIOS domain, DNS name, DNS domain, OS build (`major.minor.build`) | "NetBIOS name", "NetBIOS domain", "DNS name", "DNS domain", "OS build (NTLM)" |

Hints: `smb.netbiosName=`, `smb.dnsName=`, `smb.domain=`, `smb.osBuild=`. No identification rule uses them in 3B (4.7).

Summary: "<dialect>, signing <required or not required>, anonymous <allowed or refused>, guest <allowed or refused>" ("mapped to guest" reads as allowed in the summary).

Expected results for the on-device check (12.3), to be confirmed, not asserted in tests:

| Server | Dialect | Signing | Anonymous | Guest |
|---|---|---|---|---|
| Windows 11 24H2 defaults | SMB 3.0.2 | Required | Allowed (null session) | Refused (Guest account disabled) |
| Windows 10 22H2 defaults | SMB 3.0.2 | Enabled, not required | Allowed (null session) | Refused |
| Synology DSM 7 (Samba) | SMB 3.0.2 when the maximum protocol is SMB3 | Enabled, not required | Depends on DSM settings | Allowed or mapped to guest when the DSM guest account is enabled, else refused |

### 6.4 SNMP walk

SNMPv2c, community `public`, UDP 161, using the existing `Ber` code extended with the GETNEXT PDU (0xa1), the application types IpAddress (0x40), Counter32 (0x41), Gauge32 (0x42) and TimeTicks (0x43), and the exceptions noSuchObject (0x80), noSuchInstance (0x81) and endOfMibView (0x82). Counter32, Gauge32 and TimeTicks are read as unsigned 32-bit values into `Long` by a new `Ber.Reader.unsigned(tag)` (the existing `int()` stays signed and INTEGER-only). `SNMP_TIMEOUT_MS = 1000`, one retry; request ids increment and replies carrying another id are ignored. Hard cap `SNMP_MAX_REQUESTS = 400`.

1. System group: GETNEXT from `1.3.6.1.2.1.1` until the reply leaves the subtree: sysDescr, sysObjectID, sysUpTime, sysContact, sysName, sysLocation.
2. Interfaces: GETNEXT in lockstep over ifDescr (`1.3.6.1.2.1.2.2.1.2`), ifType (`.3`), ifSpeed (`.5`), ifPhysAddress (`.6`) and ifOperStatus (`.8`), until every column has left its subtree or `SNMP_MAX_INTERFACES = 64` rows. Rows are keyed by the index suffix of each returned OID, never by position, so sparse columns assemble correctly. When ifSpeed is 4294967295, one GET of ifHighSpeed (`1.3.6.1.2.1.31.1.1.1.15.<index>`, Mbit/s) replaces it.
3. Addresses: GETNEXT in lockstep over ipAdEntAddr (`1.3.6.1.2.1.4.20.1.1`) and ipAdEntNetMask (`.3`), keyed by index suffix, up to `SNMP_MAX_ADDRESSES = 64`.

A step that times out twice ends that step only; the next step still runs. Progress total is null ("N done, K found").

| Kind | value | detail |
|---|---|---|
| `SNMP_SYSTEM` | the value (uptime as "12 days 4 h" or "3 h 12 min") | "sysDescr", "sysObjectID", "Uptime", "sysContact", "sysName", "sysLocation" |
| `SNMP_INTERFACE` | ifDescr | "<status>, <type>, <speed>, <MAC>", empty parts omitted. Status from ifOperStatus 1 to 7: up, down, testing, unknown, dormant, notPresent, lowerLayerDown. Type: 6 ethernetCsmacd, 24 softwareLoopback, 53 propVirtual, 71 ieee80211, 117 gigabitEthernet, 131 tunnel, 135 l2vlan, 161 ieee8023adLag, 209 bridge, else "type N". Speed "N Mbit/s" or "N Gbit/s", omitted when 0 |
| `SNMP_ADDRESS` | "<address>/<prefix>" | "Interface address" |

Hints: `snmp.sysObjectID=`, `snmp.sysName=`, `snmp.sysDescr=`.

Summary: "<sysName, else the first 40 chars of sysDescr>, N interfaces". `FAILED` when the system step gets nothing: "No SNMP answer to public".

## 7. Data model

### 7.1 Entities

```kotlin
enum class ToolRunStatus { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }

@Entity(tableName = "tool_runs",
    foreignKeys = [ForeignKey(entity = HostEntity::class, parentColumns = ["id"], childColumns = ["hostId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("hostId", "toolId", "port", "startedAt")])
data class ToolRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val toolId: String, val port: Int,
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String)

@Entity(tableName = "tool_findings",
    foreignKeys = [ForeignKey(entity = ToolRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")])
data class ToolFindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val kind: String,
    val value: String, val detail: String?, val extra: String?)
```

- `port` is the target port (161 for SNMP walk). The history line reads it directly.
- `status` uses Room's built-in enum-as-TEXT mapping, as `HostStatus` does.
- `kind` is TEXT, mapped by `FindingKind.parse(s)` with an `OTHER` fallback, so a kind removed in a later version never crashes a read.
- `extra` is a JSON object (`Map<String, String>` via kotlinx-serialization) carrying `url` when present plus the per-kind fields; null when empty.
- `optionsJson` is a JSON object: `{"tls": false, "serverName": null}`, plus, for brute-force runs, the `DirBruteOptions` fields, `methodUsed`, `wordlistName` and `wordlistEntries`, so history still names a wordlist after it is deleted. A malformed value decodes to defaults.

`FindingKind`: `PATH, STATUS, HEADER, COOKIE, TITLE, GENERATOR, ROBOTS, SITEMAP, SECURITY_TXT, ERROR_PAGE, FRAMEWORK, CERT_SUBJECT, CERT_ISSUER, CERT_SAN, CERT_VALIDITY, CERT_KEY, CERT_SIGNATURE, CERT_FINGERPRINT, CERT_CHAIN, TLS_SESSION, FLAG, SMB_DIALECT, SMB_SIGNING, SMB_SESSION, SMB_HOST, SNMP_SYSTEM, SNMP_INTERFACE, SNMP_ADDRESS, HINT, OTHER`.

### 7.2 DAO and repository

```kotlin
@Dao interface ToolDao {
    @Insert suspend fun insertRun(r: ToolRunEntity): Long
    @Query("UPDATE tool_runs SET finishedAt = :at, status = :status, summary = :summary WHERE id = :id")
    suspend fun finish(id: Long, at: Long?, status: ToolRunStatus, summary: String)
    @Query("DELETE FROM tool_runs WHERE hostId = :hostId AND toolId = :toolId AND id NOT IN " +
           "(SELECT id FROM tool_runs WHERE hostId = :hostId AND toolId = :toolId ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun prune(hostId: Long, toolId: String, keep: Int)
    @Query("SELECT * FROM tool_runs WHERE status = 'RUNNING'") suspend fun runningOnce(): List<ToolRunEntity>
    @Insert suspend fun insertFinding(f: ToolFindingEntity): Long
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId ORDER BY startedAt DESC, id DESC") fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>>
    @Query("SELECT * FROM tool_runs WHERE id = :id") fun run(id: Long): Flow<ToolRunEntity?>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") fun findings(runId: Long): Flow<List<ToolFindingEntity>>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") suspend fun findingsOnce(runId: Long): List<ToolFindingEntity>
    @Query("SELECT COUNT(*) FROM tool_findings WHERE runId = :runId AND kind != 'HINT'") suspend fun findingCount(runId: Long): Int
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId AND status IN ('DONE','STOPPED','INTERRUPTED') ORDER BY startedAt DESC, id DESC")
    suspend fun finishedRunsOnce(hostId: Long): List<ToolRunEntity>
    @Query("SELECT value FROM tool_findings WHERE kind = 'HINT' AND runId IN (:runIds)") suspend fun hintsOf(runIds: List<Long>): List<String>
}
```

`ToolRepository(db)` wraps these: `ready: Deferred<Unit>`, `startRun` (insert plus prune in one `withTransaction`), `finish`, `addFinding`, `markInterrupted` (each `RUNNING` row gets `INTERRUPTED` and its "Interrupted, N found" summary, in one transaction, then completes `ready`), `runsForHost`, `run`, `findings`, `hintsFor` (picks one run per (tool, port) in Kotlin from `finishedRunsOnce` by the 4.7 rule, then `hintsOf`), `snapshot(hostIds)` and `restore(token)` (7.4). `NetProbeDb` adds both entities and `abstract fun tools(): ToolDao`.

Write rule: no DAO may use `OnConflictStrategy.REPLACE` or `@Upsert` on `hosts` or `tool_runs`. A REPLACE deletes and reinserts the row, and the cascade would silently wipe the host's tool history on every discovery. A DAO test guards it (12.2).

### 7.3 Schema version and migration

- 3A takes schema 1 to 2 and is a hard prerequisite. This phase is version 3 with `MIGRATION_2_3`.
- Migration SQL (the implementer copies the `createSql` strings from the newly exported `3.json`; these are the expected values):

```sql
CREATE TABLE IF NOT EXISTS `tool_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER NOT NULL, `toolId` TEXT NOT NULL, `port` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`);
CREATE TABLE IF NOT EXISTS `tool_findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `detail` TEXT, `extra` TEXT, FOREIGN KEY(`runId`) REFERENCES `tool_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`);
```

- `@Database(version = 3, exportSchema = true)`; `NetProbeDb.open` adds `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. Never `fallbackToDestructiveMigration`: it would wipe the Director's annotations.
- `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json` is generated by KSP and committed next to `1.json` and `2.json`.
- The migration test (12.2) extends 3A's `MigrationTest`: create schema 2 with a network, a host with a note and status, and a port; run `MIGRATION_2_3` with `runMigrationsAndValidate(..., validateDroppedTables = true)`; old rows read back intact and the new tables accept a run and findings. A second case migrates 1 through 3 in one chain. A third opens the migrated database through the real `NetProbeDb` builder so Room's own validation passes.

### 7.4 Cascade, removal with Undo, growth

- Room turns on `PRAGMA foreign_keys` because these entities declare foreign keys. Deleting a host deletes its runs and their findings.
- 3A's Remove device and Forget this network delete hosts, so the cascade would remove tool history that 3A's Undo cannot restore. This phase extends both: inside 3A's delete transaction and before the delete, `ToolRepository.snapshot(hostIds)` copies the hosts' tool rows into SQLite TEMP tables named with a per-undo token (`CREATE TEMP TABLE undo_runs_<token> AS SELECT * FROM tool_runs WHERE hostId IN (...)`, and `undo_findings_<token>` for their findings, hints included). `restore(token)` runs inside 3A's restore transaction after hosts are re-inserted: `INSERT INTO tool_runs SELECT * FROM undo_runs_<token>`, then the findings, keeping the original ids. When the Undo lapses (3A's rules), the TEMP tables are dropped. TEMP tables need no schema or migration and die with the process, which matches 3A's memory-only lifetime; hosts and ports stay in memory as 3A specifies.
- TEMP tables belong to one SQLite connection. Every snapshot, restore and drop runs inside `db.withTransaction`, which Room runs on the single writer connection, so all three see the same TEMP tables. A DAO test proves it across separate transactions (12.2).
- 3A's explicit delete order (ports, then hosts, then network) is unchanged; the cascade removes tool rows when the hosts go.
- Remove device and Forget this network are disabled while an affected host is held by a tool (4.3).
- Growth bound: at most 10 runs per host per tool (5 tools, so 50 runs per host) and at most 500 findings plus hints per run.
- Wordlists are not tied to hosts and are unaffected by removal.

### 7.5 Export

The results sheet's "Share" builds plain text and sends it with `Intent.ACTION_SEND`, type `text/plain`, `EXTRA_SUBJECT` "NetProbe <tool name> <ip>:<port>", through `Intent.createChooser` (chooser title "Share"). Format:

```
NetProbe <tool name>
Target: <target>
Name sent: chnas.local
Started: 2026-10-20 14:03:11
Finished: 2026-10-20 14:04:02
Status: Done
Summary: 7 found in 4614 paths
Options: wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on

<value>\t<detail>\t<url>
```

- `<target>`: `http://<ip>:<port>/` or `https://<ip>:<port>/` for the web tools, `<ip>:<port>` for TLS certificate, `smb://<ip>/` for SMB session, `udp://<ip>:161` for SNMP.
- "Name sent" appears only when a name was sent (4.9).
- "Finished: not finished" when `finishedAt` is null. The Options line appears for brute-force runs only (extensions as "php html", or "none"; "not-found filter off" when off; the method as "GET only (HEAD and GET disagree)" when calibration switched it).
- One line per finding in stored order, tab-separated, empty fields left empty. Hints are never exported; cookie values never exist to export (6.1).
- Times use the phone's local time zone, `yyyy-MM-dd HH:mm:ss`.

## 8. UI

Tokens, type styles, radii and touch targets are the Radar spec's (its sections 2 and 7): IBM Plex, `surface` sheets with 8 dp top radius, 8 dp field and segment radii, 48 dp minimum targets, `heightIn(min)` for every text container, no shadows. Motion follows the 3A spec: finding rows that arrive during a live run use its "rows arrive as found" treatment (3A 1.10), selections cross-fade (3A 1.11), and reduced motion makes everything instant (3A 1.15). New composables live in `ui/tools/` and `ui/wordlists/`; stateless content functions take state and action lambdas, as `DetailContent` does.

### 8.1 Device screen: Tools block

The pinned scan bar heads the ports list (Radar spec 5.1 item 8). The Tools block is the first item of the scrolling `portList`, directly beneath it, followed by the port rows. It is omitted when no tool applies to the host.

Collapsed (the default; the expanded state is saved per host with `rememberSaveable(hostId)`): one row of about 58 dp, the height of a port row, with a 1 dp `rowDivider` hairline below.

- Left column: "Tools" in `secondary` / `text`, then "N available" in `data` / `textMuted` on the same line, 10 dp apart. Second line, `data` / `textMuted`, one line ellipsised: the newest run of any tool on this host ("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths"), or "No tools run yet".
- Right: a chevron, 48 dp target. The whole row toggles. Description "Tools, N available, expand" or "Tools, N available, collapse". Tag `toolsHeader`.

Expanded adds one block per applicable tool, in `ToolId` order, each with 18 dp side padding, 10 dp vertical padding and a `rowDivider` hairline below:

- Line 1: tool name in `secondary` / `text`. At the right, the action word "Results" in `action` / `signal`, 48 dp target, shown only when the tool has a run on this host; it opens the results sheet on the newest run. Description "Show <tool name> results".
- Line 2: the history line in `data` / `textMuted`: "Last run <relative time> on <port>: <summary>", or "Not run yet". Relative time uses `RelativeTime.of`.
- Line 3: one target chip per applicable port, in port order: outlined, 1 dp `border`, fully round, `data` / `textSecondary`, 32 dp visual inside a 48 dp target, 8 dp apart, wrapping with `FlowRow`. The label is the port number ("5000"); SNMP walk shows "UDP 161". Description "Run <tool name> on port <port>". Tag `toolChip:<toolId>:<port>`. Tapping runs the tool on that port: directory brute-force opens its options sheet (8.4); the others start at once.
- While the host is held in `HostBusyRegistry`, the chips are drawn at `DISABLED_ALPHA`, and a tap shows the snackbar "A tool is already running on this device" or "Wait for the port scan to finish".

### 8.2 Pinned scan bar during a run

No height is added to the pinned block. While the host has an active tool run:

- The Scan pill shows "Stop" (outlined, as during a port scan) and calls `ToolRunner.stop(hostId)`; description "Stop tool run". It is the only Stop for tool runs.
- The profile segmented control is disabled (alpha .5), so a port scan cannot start.
- The reserved progress line shows, in one row: at the left, "<tool name> on <port>" in `data` / `textMuted`, ellipsised, `weight(1f)`; at the right, the progress text (section 9) in `data` / `textMuted`, updated by the runner's ticker (4.4 rule 8). The 2 dp line shows `done / total`, or an indeterminate `LinearProgressIndicator` with the same colours when the total is null.
- When the run ends the bar returns to normal; the end message arrives through `AppMessages` (4.3) and shows as a snackbar here or on Home's notice line.
- 3A's Detail overflow: "Remove device" is disabled while this host is held (7.4).

While a port scan holds the host, tool chips and port-sheet tool rows are disabled (8.1, 8.3).

### 8.3 Port long-press sheet

3A's `PortSheet` order gains one group between "Copy IP and port" and "Look up vulnerabilities for this service": a `caption` / `textMuted` label "Run tool" and one 48 dp row per tool that applies to that port, labelled with the tool name in `signal` (tag `sheetTool:<toolId>`). Tapping closes the sheet and starts the tool on that port (directory brute-force opens its options sheet). While the host is held the rows are `textMuted` and a tap shows the same snackbars as 8.1. The group is omitted when no tool applies.

### 8.4 Directory brute-force options sheet

`ModalBottomSheet`, `surface`, 8 dp top radius, `skipPartiallyExpanded = true`. A `Column` in three parts so the Run control is never inside the scrolling part:

1. Header (fixed), 18 dp side padding: "Directory brute-force" in `rowName` / `text`; beneath it, the target URL ("http://10.0.3.10:5000/") in `data` / `textMuted`.
2. Body (`weight(1f)`, `verticalScroll`), 18 dp side padding, 12 dp between groups, each group labelled in `caption` / `textMuted`:
   - "Wordlist": one 48 dp radio row per list, the name in `secondary` / `text` at the left and "312 entries" in `data` / `textMuted` at the right; the selected radio in `signal`. Beneath, the link "Manage wordlists" (`secondary` weight 500, `signal`, 48 dp target), which closes the sheet and opens the Wordlists screen.
   - "Extensions": an outlined field (`npFieldColors`, 8 dp radius), placeholder "php, html, txt", supporting text "Each word is also tried with these endings", errors in `error`.
   - "Method": `PinnedSegmented` with "HEAD then GET" and "GET only" (40 dp visual, 48 dp target).
   - "Concurrency": the Settings `StepSlider` style, 1 to 16, step 1, value in `data` / `signal`.
   - "Timeout per request": the same, 1 to 10, value "3 s".
   - "Report status codes": an outlined field, `data` text, default "200, 204, 301, 302, 307, 401, 403", errors in `error`.
   - Switch row "Filter not-found pages" with supporting text "Checks random paths first and hides answers that match them".
3. Footer (fixed): a 1 dp `surface` rule above, 18 dp padding, respecting the navigation bar inset. At the left, the live line "<N> paths" (`data` / `textMuted`, total from 5.2); at the right, a Run pill in the `ScanPill` style labelled "Run" (`signal` fill, `scanPill` / `background`, 40 dp visual, 48 dp target, tag `toolRunPill`). The pill is disabled while any field has an error or the host is held; the busy reason shows in `error` above the footer row ("A tool is already running on this device", "Wait for the port scan to finish", "Four tools are already running, stop one first").

Run saves the options (5.1), closes the sheet and starts the run. Dismissing the sheet starts nothing.

### 8.5 Results sheet

`ModalBottomSheet`, `surface`, 8 dp top radius, `skipPartiallyExpanded = true`, opened from "Results" (8.1). The sheet holds the `runId` it shows and observes it with `ToolDao.run(id)`; if that run disappears (pruned by a new run, or its host removed) the sheet closes. Three parts:

1. Header (fixed), 18 dp side padding: "<tool name>  <ip>:<port>" in `rowName` / `text`; beneath it, in `data` / `textMuted`, "<status label>, <relative start time>", with ", as <name>" appended when a name was sent (4.9) (an active run shows "Running" and the live progress text instead of the time); beneath that the summary in `secondary` / `textSecondary`. A run switcher at the right of the title: two 48 dp chevron targets, descriptions "Older run" and "Newer run", disabled at the ends, around "Run N of M" in `data` / `textMuted` (1 is the newest), over the runs of this tool on this host.
2. Findings (`LazyColumn`, `weight(1f)`), in stored order. Row: 18 dp side padding, 10 dp vertical padding, `rowDivider` hairline below. Left column: `value` in `data` / `text` (up to 3 lines then ellipsised; a tap expands it), `detail` beneath in `data` / `textMuted`, one line. Right: "Open with..." in `action` / `signal`, only when the finding has a URL (fires `DeviceAction.OpenWith(url)`, the system chooser), and "Copy" in `action` weight 400 / `textMuted` (fires `DeviceAction.Copy`, copying the URL when there is one, else the value). Each word is its own 48 dp target. Descriptions "Open <url> with" and "Copy <value>". Opening a URL sends NetProbe to the background; the run pauses and resumes on return (4.8).
   Empty states: "Running, findings appear as they are found" (an active run with none yet), "No findings" (finished with none).
3. Footer (fixed): the text button "Share" in `signal`, 48 dp target, which exports the shown run (7.5). Disabled while that run is active.

Below API 33 a copy shows the snackbar "Copied"; from 33 the system clipboard confirmation is enough.

### 8.6 Wordlists screen

New route `wordlists` in `NetProbeNav`, reached from Settings (8.7) and from "Manage wordlists" (8.4). Laid out as Settings: a pinned top `Column` with a back row ("Back", `data` / `textMuted`, leading chevron, 48 dp; a `BackRow(label)` variant of `BackToHome`), the title "Wordlists" in `title`, and the "Import wordlist" pill (`ScanPill` style; reads "Importing" while an import runs; tag `importWordlist`). Beneath, the scrolling list:

- Section "Built in" (`caption` / `textMuted`, 1 dp `surface` rule above): one row per bundled list, the name in `secondary` / `text` and "312 entries, built in" in `data` / `textMuted`. No delete.
- Section "Imported": one row per imported list, the name and "4614 entries, imported 3 Jan 2026" (medium date). At the right, the word "Delete" in `action` / `signal`, 48 dp target, description "Delete <name>". It opens an `AlertDialog` ("Delete <name>?", buttons "Delete" in `signal` and "Cancel" in `textSecondary`) and deletes on confirm.
- An empty imported section shows "No imported wordlists yet" in `secondary` / `textMuted`.

Import results show as snackbars (section 9).

### 8.7 Settings

A new section "Tools" after "Vulnerabilities": one 56 dp row "Wordlists" (`secondary` / `text`) with supporting text "3 built in, N imported" (`data` / `textMuted`) and a trailing chevron; the row opens the Wordlists screen. No other settings: tool defaults live in the options sheet.

### 8.8 Home

- Row "running" marker and description suffixes (4.3).
- The notice line shows `AppMessages.current` when no 3A Undo notice is up, with the existing dismiss control.
- Quick scan all's end message gains the skipped count (section 9).

### 8.9 Font scale 1.3 budget

The pinned block does not grow (8.2). The collapsed Tools row (about 58 dp) takes the place of one port row, so the Radar spec 7.5 acceptance becomes: with the note and the Tools block collapsed, at least five port rows visible (was six); with the note expanded and Custom showing an error, at least two (was three). If 3A's pinned-block changes alter that budget, its numbers minus one row apply. The options sheet body scrolls; its header (about 70 dp) and footer (about 80 dp) stay visible at 1.3 on the Pixel 7 Pro. The Home "running" marker replaces the open-port count, so Home rows do not grow.

## 9. Strings

Sentence case, active voice, no disclaimers or warnings. All strings live in `ui/tools/ToolCopy.kt` and `ui/wordlists/WordlistCopy.kt`; `CopyTableTest` gains these templates and a state matrix over every helper that builds them. Numbers have no thousands separator. `<t>` is elapsed whole seconds of active time; `<relative>` comes from `RelativeTime.of`; dates are `RelativeTime.defaultDate` (medium).

| Where | String |
|---|---|
| Tool names | "Directory brute-force", "HTTP fingerprint", "TLS certificate", "SMB session", "SNMP walk" |
| Tools row | "Tools"; "N available" ("1 available") |
| Tools row history | "Last run <relative>: <tool name> on <port>, <summary>"; "No tools run yet" |
| Tools row description | "Tools, N available, expand"; "Tools, N available, collapse" |
| Tool block history | "Last run <relative> on <port>: <summary>"; "Not run yet" (SNMP: "on UDP 161") |
| Tool block action | "Results"; description "Show <tool name> results" |
| Target chip | "<port>"; "UDP 161"; description "Run <tool name> on port <port>" |
| Port sheet group | "Run tool" |
| Busy | "A tool is already running on this device"; "Wait for the port scan to finish"; "Four tools are already running, stop one first"; "A tool is running on this device" (Detail Scan refused) |
| Home row marker | "running"; description suffixes ", port scan running", ", tool running" |
| Quick scan all end | "Quick scan finished, N devices skipped: a tool is running" ("1 device"); combined with the existing partial message as "Quick scan finished, N devices could not be scanned, M skipped: a tool is running" |
| Pinned bar label | "<tool name> on <port>" ("SNMP walk on UDP 161") |
| Progress, known total | "1234 of 4614, 7 found  12 s"; with errors "1234 of 4614, 7 found, 3 errors  12 s" |
| Progress, unknown total | "12 done, 3 found  2 s" |
| Progress, before the first update | "starting" |
| Stop description | "Stop tool run" |
| Run ended message | "<tool name> finished: <summary>" |
| Run status labels | "Running", "Done", "Stopped", "Failed", "Interrupted" |
| Common summaries | "Network changed, run stopped"; "Tool skipped: could not bind to Wi-Fi"; "Failed: <reason>"; "Interrupted, N found"; "Stopped after N of M, K found"; "Stopped after N steps, K found" (unknown total) |
| Brute-force summaries | "N found in M paths"; "N found in M paths, E errors"; "Stopped at 500 findings"; "Stopped: the server answers every path alike"; "The server did not answer the first request"; "The server stopped answering after N paths" |
| HTTP fingerprint summaries | "<name>, N findings" ("1 finding"); "No product named, N findings"; "No HTTP response on this port" |
| TLS summaries | "<name>, expires <date>"; "<name>, expires <date>, self-signed"; "No TLS handshake on this port" |
| SMB session summaries | "<dialect>, signing required, anonymous allowed, guest allowed" (signing "required" or "not required"; each session "allowed" or "refused"); "No SMB2 dialect in common"; "No SMB response on this port" |
| SNMP summaries | "<name>, N interfaces" ("1 interface"); "No SNMP answer to public" |
| Options sheet | "Directory brute-force"; "Wordlist"; "N entries" ("1 entry"); "Manage wordlists"; "Extensions"; placeholder "php, html, txt"; "Each word is also tried with these endings"; "Method"; "HEAD then GET"; "GET only"; "Concurrency"; "Timeout per request"; "N s"; "Report status codes"; "Filter not-found pages"; "Checks random paths first and hides answers that match them"; "N paths" ("1 path"); "Run" |
| Options errors | "Use letters and digits only, for example php, html"; "At most 8 extensions"; "Use codes from 100 to 599, for example 200, 301, 403"; "Add at least one status code" |
| Wordlist fallback notice | "Wordlist not found, using common-short" |
| Results sheet | "<tool name>  <ip>:<port>"; "<status label>, <relative>"; "<status label>, <relative>, as <name>"; "Run N of M"; "Older run"; "Newer run"; "Open with..."; "Copy"; "Share"; "Copied"; "Running, findings appear as they are found"; "No findings" |
| Results descriptions | "Open <url> with"; "Copy <value>" |
| Export | subject "NetProbe <tool name> <ip>:<port>"; chooser title "Share"; lines "NetProbe <tool name>", "Target: ...", "Name sent: <name>", "Started: ...", "Finished: ...", "Finished: not finished", "Status: <status label>", "Summary: ...", "Options: ..." as in 7.5, including "GET only (HEAD and GET disagree)" and "not-found filter on" or "off" |
| Brute-force finding detail | "<status>  <size>"; "<status>  <size>  to <location>"; "<status>" (size unknown); sizes "N B", "N.N KB", "64 KB+" |
| HTTP fingerprint details | "GET /"; "GET /, to <location>"; "GET <url>"; header names as sent; "Cookie"; ", Secure"; ", HttpOnly"; ", SameSite=<v>"; "Page title"; "Generator meta tag"; "N rules" ("1 rule"); "Disallowed in robots.txt"; "Allowed in robots.txt"; "N URLs" ("1 URL"); "Contact: <value>"; "Present"; "Error page signature"; "Matched <source>: <excerpt>" |
| TLS details | "Subject"; "Issuer"; "Alternative name (DNS)"; "Alternative name (IP)"; "<date> to <date>"; "Valid"; "Expired"; "Not yet valid"; "Expires in N days" ("1 day"); "RSA N bit"; "EC N bit"; "DSA N bit"; "Ed25519"; "Ed448"; "Public key"; "Signature algorithm"; "SHA-256 fingerprint"; "Chain certificate N of M"; "Protocol and cipher"; "Certificate"; "Self-signed"; "RSA key under 2048 bit"; "Signed with SHA-1" |
| SMB details | "Dialect"; "SMB 2.0.2", "SMB 2.1", "SMB 3.0", "SMB 3.0.2"; "Signing"; "Required"; "Enabled, not required"; "Session"; "Anonymous allowed"; "Anonymous refused"; "Guest allowed"; "Guest mapped to guest"; "Guest refused"; "NetBIOS name"; "NetBIOS domain"; "DNS name"; "DNS domain"; "OS build (NTLM)" |
| SNMP details | "sysDescr", "sysObjectID", "Uptime", "sysContact", "sysName", "sysLocation"; "N days N h"; "N h N min"; the interface statuses and types listed in 6.4; "type N"; "N Mbit/s", "N Gbit/s"; "Interface address" |
| Wordlists screen | "Back"; "Wordlists"; "Import wordlist"; "Importing"; "Built in"; "Imported"; "N entries, built in"; "N entries, imported <date>"; "Delete"; "Delete <name>?"; "Cancel"; "No imported wordlists yet"; description "Delete <name>" |
| Import results | "Imported <name>, N entries"; "Imported <name>, N entries (K skipped)"; "That file is too large (limit 10 MB)"; "That file has more than 200000 entries"; "That file has no entries"; "Could not read that file" |
| Default import name | "Imported list" |
| Settings | section "Tools"; row "Wordlists"; "3 built in, N imported" |

## 10. Data flow

1. Open a device: `DetailViewModel` combines its existing flows with `toolRunner.active`, `hostBusy.busy`, `tools.runsForHost(hostId)` and `ToolApplicability` over the stored ports and host, producing a `ToolsUiState` (collapsed or expanded, one row per tool, history lines, busy flags).
2. Tap a chip or a port-sheet tool row: directory brute-force opens the options sheet with the saved options and `wordlists.lists`; the others call `toolRunner.start` at once.
3. `ToolRunner.start` awaits `ready`, claims the host, inserts the run and prunes (one transaction), then launches the tool on `appScope` with the tool dispatcher inside `ScanBinding.run`. The pinned bar switches to tool progress and Stop; Home shows the row's "running" marker.
4. The tool connects through `Probe.connect` (bound when off-subnet), passes every request through the gate (pause and rate ceiling), reports progress and findings. The results sheet, if open, fills live from Room.
5. If NetProbe goes to the background the run pauses and resumes on return (4.8).
6. The run ends (done, failed, Stop or network change). The runner writes the end state, releases the host, posts the end message to `AppMessages`, and re-identifies the host with `hintsFor(hostId)` through `setIdentityIfNotWeaker`; the identity line and history line update from Room.
7. Share builds the text (7.5) and opens the share sheet. "Open with..." and "Copy" go through `AndroidActionRunner`.
8. Wordlists: import normalises into `files/wordlists/`; the store's `lists` flow updates the options sheet and the Wordlists screen.
9. Remove device or Forget this network (3A): tool rows go to TEMP tables, the cascade removes them, Undo restores them (7.4).

## 11. Error handling and edge cases

- 11.1 Per-request failures never stop a run on their own (5.2 retry and error count); only `MAX_CONSECUTIVE_FAILURES`, a failed calibration or the runaway check ends a brute-force early. Companion tools end a step, not the run, on a timeout, except when the first exchange fails (each tool's `FAILED` summary).
- 11.2 Malformed bytes from any server (HTTP, TLS, SMB, SPNEGO, NTLM, BER) are caught in the parsers, which return null; parsers are fuzz-style tested with truncated and garbage input. Nothing a server sends can crash the app.
- 11.3 Off-subnet hosts with extended discovery on run bound to Wi-Fi; a bind failure fails the run with "Tool skipped: could not bind to Wi-Fi" and never runs unbound (as port scans, extended spec 5.5).
- 11.4 Network change: all runs stop with "Network changed, run stopped"; partial findings stay.
- 11.5 Background: runs pause; failures across the pause are excused (4.8). Process death: `INTERRUPTED` on the next launch; its findings stay and its hints count.
- 11.6 Catch-all servers: calibrated out (5.4); if they still produce noise, the runaway check or the 500 cap ends the run.
- 11.7 Servers that reject HEAD or answer HEAD differently from GET: calibration switches to GET only; per-path 405 and 501 fallback remains (5.2).
- 11.8 Slow or fragile servers: adaptive concurrency halves on timeouts and the 100 per second ceiling bounds the load (5.3).
- 11.9 Huge wordlists: entries stream from the file and are never loaded whole; the reader is always closed (5.6.3).
- 11.10 A deleted wordlist in the saved options: falls back to common-short with the notice; history still names it from `optionsJson`. Deleting a list mid-run is safe (5.6.3).
- 11.11 Imports in Latin-1 or another non-UTF-8 encoding: lines with non-UTF-8 bytes are skipped and the snackbar reports the skipped count; the rest import.
- 11.12 A Room write failure during a run: logged, the run continues; the end-state write is retried once, and if it still fails the row stays `RUNNING` and 11.5 repairs it on the next launch.
- 11.13 Old TLS servers: every supported protocol and cipher suite is enabled (4.5); a handshake that still fails gives "No TLS handshake on this port".
- 11.14 SMB servers that refuse both sessions or require signing: reported as findings and in the summary; the probe never sends anything after SESSION_SETUP.
- 11.15 A run shown in an open results sheet is pruned, or its host removed: the sheet closes (8.5).
- 11.16 Quick scan all meets a host held by a tool: skipped and counted (4.3). A tool start meets a host held by Quick scan all: refused with "Wait for the port scan to finish".

## 12. Testing and verification

### 12.1 Unit tests (JVM)

- `HttpClient` against in-process `ServerSocket` scripts on loopback: exact request bytes for HEAD and GET (Host as IP, as name, with and without the port), `Content-Length`, chunked (with a chunk extension and a trailer), close-delimited bodies, a body with no framing from a server that keeps the connection open (ended by the deadline), bare-LF headers, an interim `100 Continue`, no body read for 204 and 304, the 64 KB cap with `bodyTruncated`, the 16 KB header cap, a malformed status line, a server that never answers (`Timeout`), a server that drips bytes (overall deadline), a refused port (`Refused`), a redirect returned and not followed, and cancellation mid-read closing the socket within 1 s.
- `CancellableIo` and the tool dispatcher: with 64 fake reads blocked inside tool runs, a task on `Dispatchers.IO` still completes within 100 ms; tool reads never run on more than `TOOL_THREADS` threads.
- TLS through `HttpClient` and `TlsPeek.details` against a loopback `SSLServerSocket` using a committed PKCS12 test keystore, including SNI sent when a name is given and the retry without a name.
- TLS fixtures, generated locally with JDK 17 `keytool` and committed under `app/src/test/resources/tls/`: self-signed RSA 2048, RSA 1024 (weak flag), EC P-256, DSA 2048, Ed25519, a two-level chain (`-gencert`), an expired certificate (`-startdate` in the past), one signed `SHA1withRSA`, and one with DNS and IP SANs. `CertSummaries.of` is checked for every finding, every flag and the summary.
- `ToolApplicability`: every row of 4.2, including "HTTP alt" on 81, "Web / Synology" on 5000, "Synology HTTPS" on 5001 with and without a confirmed banner, an unknown port confirmed HTTP by 3A's ladder, port 139 excluded, SNMP only with `snmpSysDescr`.
- Directory brute-force with a fake exchange function and a fake clock:
  - calibration: a nonce-varying catch-all (sizes differ by a few bytes) is filtered by the band; a path-echoing 200 is filtered; a catch-all 302 to `/login` is filtered while a real `/admin` to `/admin/` is reported; an extension group with its own baseline (`.php` handled differently from bare words); a HEAD-404/GET-200 server switches to GET only and finds its paths; a group with only 3 responses; calibration failure;
  - runaway: more than 50 alike findings in the first 200 paths ends the run with its summary; 50 or fewer does not;
  - report-set filtering; HEAD 405 and 501 fallback and the switch after 3; extension expansion order and the path total; retry once then error; `Refused` not retried; abort after 50 consecutive counted failures; the 500 cap; percent-encoding of paths;
  - load control: the maximum in flight never exceeds the setting for 1, 8 and 16; a fake server that times out above 2 concurrent requests completes the run with zero counted errors once `L` has dropped; `L` halves at most once per window and recovers by one per 20 successes up to `C`; the rate ceiling holds at or under 100 requests in every 1 s window (burst 10).
- Background pause with a fake `foreground` flow: on false no new requests start and the clock stops; in-flight requests that fail during the pause, or after it with an earlier epoch, are requeued and not counted, do not change `L`, and do not count towards consecutive failures; on true the run resumes and completes with zero counted errors.
- `WordlistParser`: LF, CRLF and lone CR; BOM; comments; blanks; leading slashes; trimming; duplicates (order kept); over-long lines, invalid UTF-8 and control characters skipped and counted.
- Bundled wordlists: each within its entry range (5.6.1), parses to itself unchanged (no blanks, comments, leading slashes or duplicates), ASCII only, alphabetical within each category block.
- `WordlistStore` (temporary directory): import writes the normalised file and the index entry; name clashes get suffixes; the byte limit aborts mid-stream with no size hint; the count and empty rejections keep nothing; delete removes the file and entry; built-ins cannot be deleted; a corrupt index loads empty and orphans are removed; `withEntries` closes the reader when the block is cancelled; deleting a list during `withEntries` lets the block finish.
- `DirBruteOptions`: every validation message, coercion of concurrency and timeout, saved options round trip, invalid JSON loads defaults.
- HTTP fingerprint parsers from fixtures: headers; cookie names with flags, plus an assertion that no cookie value appears in any finding or in the export; generator meta; robots rules and the 50-path cap; sitemap count; security.txt contact; error-page signatures (Apache, nginx, Tomcat); the redirect hop to another scheme and port on the same IP (and no hop to another IP); every seed rule against its `sample` and `negativeSample` ("UniFi Network" does not give "UniFi OS"); hint strings with versions stripped.
- SMB session: MD4 against the RFC 1320 test suite; NTLMv2 against the MS-NLMP 4.2.4 test vectors (both published, so not circular); SPNEGO, NEGOTIATE and SESSION_SETUP builders against hand-built byte fixtures; header sequencing (`MessageId`, `CreditCharge` per dialect, `SessionId`); parsers for NEGOTIATE (each dialect, signing bit) and SESSION_SETUP (more-processing with CHALLENGE and target info, success with IS_NULL, success with IS_GUEST, logon failure, account disabled); an end-to-end run against a loopback `ServerSocket` replaying fixtures for: anonymous allowed, guest only, both refused, signing required; an assertion that no command other than NEGOTIATE and SESSION_SETUP is ever written. The hand-built fixtures share the implementer's reading of the specs, so the on-device check (12.3) is the real proof; 3C replaces them with captured traces.
- SNMP: the GETNEXT builder; parsing of IpAddress, Counter32, Gauge32, TimeTicks above 2^31 (no negative values) and the three exceptions; walk termination on subtree exit, endOfMibView, an error status and the 400-request cap; table assembly by index suffix with a sparse column; the ifHighSpeed substitution; MAC, speed and uptime formatting; one retry then the step ends; mismatched request ids ignored; a loopback `DatagramSocket` agent script end to end.
- `ToolRunner` with fake tools, a fake repository and a fake registry: one run per host (`HostBusy`), `TooMany` at 4, `NoNetwork`; a start issued before `markInterrupted` completes waits for it and its row is never marked `INTERRUPTED`; status transitions; Stop ends a fake tool blocked in `CancellableIo.closing` within 1 s and writes `STOPPED` with the stopped summary; a network identity change stops every run; a throwing tool gives `FAILED`, "Failed: <reason>"; the ticker republishes elapsed time while no update arrives; findings are forwarded as they arrive; the host is released and the end message posted on every end state; `onFinished` gets the host id and is skipped for a `FAILED` run with no findings.
- `HostBusyRegistry`: claim, release, double claim refused, release by the wrong work is a no-op; Quick scan all skips a host held by a tool and counts it; a tool start is refused while Quick scan all holds the host; the Detail scan is refused while a tool holds the host.
- Binding propagation: with `BoundNetwork(fakeBinder)` on the context, every socket each tool opens (HTTP, TLS, SMB, the SNMP datagram) is passed to the binder; a binder that throws makes the run `FAILED`, "Tool skipped: could not bind to Wi-Fi", and no unbound socket is opened.
- Identification: `DeviceIdentifier` with each `toolHint` seed rule (one fixture per row); `HostEvidenceBuilder` passes hints; reasons read `toolHint: ...`; per-port hints: fingerprint 5000 (Synology), then fingerprint 80 (no framework), then a discovery re-identification: the identity is still Synology NAS; a `STOPPED` run newer than a `DONE` run on the same port does not hide the `DONE` run's hints; tool-triggered writes never lower `identConfidence`.
- Copy table: `CopyTableTest` templates for section 9 with a state matrix over every `ToolCopy` and `WordlistCopy` helper (progress with and without a total and errors, every summary, plurals, history lines, sizes, durations, export lines, the Quick scan all combinations, the Home marker).
- Export text for each tool, including "Name sent", "Finished: not finished", the brute-force Options line with the GET only switch, and the absence of hints.

### 12.2 Instrumented tests

- Migration (the three cases in 7.3): 2 to 3 with validation, 1 to 3 in one chain, and the migrated database opened by the real builder.
- DAO: insert plus prune keeps exactly the newest 10 per host and tool and leaves other tools and hosts alone; deleting a run cascades to its findings; a raw `DELETE FROM hosts` cascades to runs and findings; `upsertDiscovered` on an existing host leaves its tool runs intact (the REPLACE guard); `markInterrupted`; `hintsFor` picks per (tool, port) by the 4.7 rule; `findings` excludes hints; `snapshot` in one transaction, 3A's host delete, then `restore` in a later transaction gives identical runs and findings with the same ids; dropping the snapshot removes the TEMP tables.
- Compose (`DetailContent`, Home content and the new content functions with fake state):
  1. Tools block collapsed by default with "N available" and the newest history line; it expands and collapses; chips fire the right lambda; "Results" shows only when a run exists; chips are disabled while the host is held.
  2. The pinned bar during a run shows the tool label, the progress text and Stop; Stop fires the stop lambda; the profile control is disabled.
  3. The port sheet shows "Run tool" rows only for applicable tools.
  4. Options sheet: defaults shown; invalid extensions and status codes show their messages and disable Run; the footer and Run pill stay visible with the body scrolled to the end at font scale 1.3; "Manage wordlists" fires its lambda.
  5. Results sheet: "Open with..." only when a URL exists, "Copy" always, each target at least 48 dp; run switcher bounds; both empty states; Share disabled while running; the sheet closes when its run is removed.
  6. Wordlists screen: built-in rows have no Delete; an imported row confirms before delete; the import pill is outside the scrolling list.
  7. Settings shows the Tools section and the Wordlists row.
  8. Home: a held host's row shows "running" and its description suffix; an `AppMessages` message shows on the notice line and is dismissed after showing.
  9. The font scale 1.3 acceptance of 8.9.
  10. 3A's Detail overflow: "Remove device" disabled while the host is held by a tool.

### 12.3 Deferred on-device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, for his return)

Not run while the Director is away. Results go in the QA notes with times.

1. Install the build over the 3A build. Existing hosts, ports, notes, statuses and overrides survive the migration.
2. CHNAS Synology, port 5000: directory brute-force with common-short and defaults. Known DSM paths appear (for example `/webman`), catch-all noise is filtered, and Stop mid-run ends it within about a second. A second run with admin-panels finishes. Record times against 5.7.
3. Mid-run browser round trip: during a brute-force, tap "Open with..." on a found path, read the page for 60 s, come back. The run continues and ends `DONE` with no counted errors from the gap; it never reads "The server stopped answering".
4. CHNAS: HTTP fingerprint on 5000 names Synology DSM, and the identity line shows Synology NAS with a `toolHint` reason. TLS certificate on 5001 shows subject, SANs, expiry and the self-signed flag if it applies, and "as <name>" when the CHNAS has an mDNS name.
5. CHNAS: SMB session. Compare dialect, signing, anonymous and guest against the expected-results table in 6.3, and against the DSM guest account setting.
6. The Director's Windows PC, if it has SMB on 445: SMB session, compared against the Windows rows of the table.
7. CHNAS or the UniFi gateway, if either answered SNMP in discovery: SNMP walk lists interfaces and addresses.
8. Pi-hole admin: HTTP fingerprint names Pi-hole; a brute-force with admin-panels finds `/admin`.
9. UniFi gateway: TLS certificate on 443; HTTP fingerprint names UniFi OS; a brute-force with api-paths finishes with its catch-all redirect filtered.
10. Hue bridge (off-subnet at `10.0.4.20`, extended discovery on): HTTP fingerprint names Philips Hue; a brute-force completes without stalling the bridge (adaptive concurrency); the run is bound to Wi-Fi.
11. Start a brute-force on the CHNAS, go Home: its row shows "running"; Quick scan all skips it and says so; the end message appears on Home's notice line.
12. Import a wordlist the Director already has on the phone; check the name, entry count and date; run with it; delete it and check the saved options fall back with the notice.
13. Switch Wi-Fi mid-run: it stops with "Network changed, run stopped".
14. Results: "Copy" copies the URL; "Share" produces the text of 7.5.
15. Force-stop the app mid-run and relaunch: the run shows "Interrupted" with its findings.
16. Remove a device with tool history, tap Undo: the history is back. Remove it again and let the Undo lapse: the history is gone.
17. Font scale 1.3: the Device screen budget (8.9) and the options sheet footer.

Definition of done: 12.1 and 12.2 pass, and 12.3 is recorded once the Director is back.

## 13. Assumptions for the Director

Made by the controller and Ada while the Director is away. Each can be overturned before the plan is executed.

1. Plan order: 3A first as its own plan, then 3B as one plan, then 3C for SMB share listing (section 15). 3B is schema version 3 (`MIGRATION_2_3`).
2. SMB shares became an "SMB session" probe in 3B (dialect, signing, anonymous and guest results, server names and OS build). Share listing and read checks move to 3C, to be built against real captured traffic (Gruff's partial veto, accepted by the controller).
3. Tool runs keep going when the Director leaves the Device screen, and pause while NetProbe is in the background (for example while a found page is open in the browser), resuming on return. Stop, a network change or app death ends them.
4. One app-level registry stops a port scan and a tool hitting the same host at once, Quick scan all included. Home rows show "running", and end-of-run messages reach whichever screen is open. Up to four hosts can run tools at the same time.
5. Every tool is capped at 100 requests per second per host, and brute-force halves its concurrency on timeouts and recovers slowly. Neither is an option.
6. Directory brute-force calibrates with 5 random paths per group (bare and each extension), filters by size band, switches to GET only when HEAD and GET disagree, and stops with "the server answers every path alike" when more than 50 of the first 200 paths look the same. It always sends the IP as the Host header.
7. TLS certificate and HTTP fingerprint send the host's known name (mDNS, else reverse DNS) as SNI and Host, so reverse proxies show the real site (Gruff Q1).
8. Tools may run while a radar scan sweeps the network; the rate ceiling bounds their load (Gruff Q3).
9. SNMP walk is offered only for hosts that answered SNMP during discovery, and only with community `public` (Gruff Q2: gate kept).
10. Remove device and Forget this network (3A) keep tool history in SQLite TEMP tables for the Undo window.
11. Only directory brute-force has an options sheet; the other four tools run at once with fixed defaults. The Tools block sits collapsed at the top of the Device screen's list, which costs one visible port row at font scale 1.3.
12. The bundled wordlists are written by the implementer from their own knowledge. A SecLists subset (for example `Discovery/Web-Content/common.txt` and `raft-small-directories.txt`) could be bundled later if the Director approves the download.
13. Imports accept `text/plain` documents only, up to 10 MB and 200,000 entries.
14. Tool results feed device identification through hint strings kept per tool and port, and never lower an identity. Tools never write host or port columns.
15. The brief described `HttpTitle` as a raw-socket client; it uses `HttpURLConnection`. It stays as it is, and the new `HttpClient` is raw-socket as the brief intended.

## 14. Open items

1. Virtual-host brute-forcing (a Host header field in the options sheet): left out (2.2); cheap to add if client sites need it.
2. HTTP keep-alive for brute-force speed over HTTPS: measure first (12.3 item 2) and add only if HTTPS runs are too slow (5.7).
3. Some file managers report `.lst` or extensionless wordlists as `application/octet-stream`, which the `text/plain` filter hides. Widening to `*/*` is a one-line change if the Director hits this.
4. An "Add to manual ranges" action on SNMP interface addresses, linking the walk to extended discovery.
5. A SecLists subset (13.12) needs the Director's approval of the download before any agent fetches it.
6. SMB identification rules (for example Windows from the NTLM OS build together with a DNS domain) wait for 3C's captured traces.

## 15. Plan order and Phase 3C

1. **3A**, its own plan, as specified in the 3A spec. 3B starts only after 3A is merged.
2. **3B**, one plan, in this order:
   1. framework, `HostBusyRegistry`, `AppMessages`, `CancellableIo`, the tool dispatcher, background pause, the rate ceiling, `HttpClient`, schema 2 to 3, the 3A snapshot extension;
   2. directory brute-force with calibration, adaptive concurrency, the wordlists and the Wordlists screen;
   3. HTTP fingerprint, TLS certificate and SNMP walk;
   4. SMB session last, with its own review.
3. **3C, SMB share enumeration and read checks**: a separate spec and plan. It must cover:
   - Gruff's M6 points 1 to 4: fall back to guest when anonymous listing is denied, with a "share list refused" finding; deterministic signing rules (no unsigned request on a signing-required session that is neither null nor guest); `STATUS_PENDING` interim replies and `STATUS_BUFFER_OVERFLOW` pipe reads; the NDR shape of `NetrShareEnumAll`.
   - The read-only guard, which may build CREATE only with read access bits and WRITE only to the `srvsvc` pipe.
   - A fixture-capture task: a JVM harness kept out of CI and run from the dev PC, read-only, recording real byte traces. Whether it may run against the dev PC's own SMB server without the Director is for 3C's controller to rule (Gruff Q4); captures against CHNAS wait for him.
   - An expected-results table for Windows 11 24H2, Windows 10 and Synology DSM.

## 16. Gruff review response

All seven MAJOR findings are applied, with the controller's binding rulings where they differ from Gruff's text:

| Finding | Where |
|---|---|
| M1 host exclusivity, Home presence, end messages | 4.3, 4.4, 8.8, 9, 12 |
| M2 background (controller ruling: pause and resume, excused failures, `INTERRUPTED` on process death) | 4.8, 5.2, 12.1, 12.3 item 3 |
| M3 adaptive concurrency and a 100 per second ceiling | 5.3, 12.1; 2.2 updated |
| M4 calibration (controller ruling: 5 random paths per group, size band, GET only switch, runaway check of more than 50 alike in the first 200) | 5.4, 12.1 |
| M5 `port` column, hints per (tool, port), never weaken | 7.1, 7.2, 7.3, 4.7, 12.1 |
| M6 SMB partial veto (controller ruling: session probe only in 3B, shares in 3C with captured fixtures) | 6.3, 2.2, 15 |
| M7 dispatcher inheritance and a 16-thread tool dispatcher | 4.4, 4.6, 12.1 |

MINOR findings: 1 to 9 and 11 to 16 are applied (`ready` gate in 4.4 and 7.2; TEMP-table snapshots in 7.4; the REPLACE guard in 7.2; 3A as a hard prerequisite in 7.3; HttpClient framing in 4.6; `withEntries` in 5.6.3; authoring rules in 5.6.1; byte counting and the Latin-1 note in 5.6.2 and 11.11; "paths" throughout; cipher suites and key types in 4.5 and 6.2; unsigned SNMP values, ifHighSpeed and index keying in 6.4; the redirect hop in 6.1; `^UniFi OS$` with a negative sample in 6.1; the results sheet holding its `runId` in 8.5; the progress ticker in 4.4).

Declined:

- Minor 10, in part: the HTTPS timing note is applied (5.7), but the GET only default for HTTPS is declined. HEAD then GET costs one handshake per miss, the same as GET only, and avoids reading up to 64 KB per miss; GET is added only for hits.

QUESTIONS:

1. Host header and SNI: yes for TLS certificate and HTTP fingerprint, from the known mDNS or reverse DNS name with a retry without it; brute-force stays on the IP (4.9, 13.7).
2. SNMP gate: kept. Offering the walk on every host would put a Tools block on every device for a guaranteed 2 s timeout on most of them (13.9).
3. Tools during a radar scan: allowed; the rate ceiling and adaptive concurrency bound the extra load, and discovery does not claim hosts (4.3, 13.8).
4. SMB fixture capture: moot for 3B, which ships no share code. For 3C I recommend allowing a read-only localhost capture against the dev PC without the Director, and waiting for him for CHNAS; 3C's controller rules on it (15).
