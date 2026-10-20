# NetProbe Phase 3B Follow-up Tools Design (addendum to the MVP spec)

Date: 2026-10-20
Status: draft by Ada for Gruff's review. The Director is away; the controller made the design decisions as his stand-in, and every call made on his behalf is listed in section 13 so he can overturn it.
Project: `K:\claude\netprobe`, built on master at b37db04
Parent spec: `2026-10-19-netprobe-mvp-design.md` (this addendum amends its sections 2.3, 5.4.1, 5.6, 5.7 and 9)
Sibling specs: `2026-10-19-extended-discovery-design.md` (binding to the Wi-Fi network, its 5.5), `2026-10-19-radar-ui-design.md` (the "Radar spec": tokens, type styles, Device screen layout, copy rules) and `2026-10-20-phase3a-polish-design.md` (the "3A spec": schema 1 to 2, `PortUris`, `TlsPeek.certInfo`, `setIdentityIfNotWeaker`, the Detail overflow, Remove device and Forget this network with Undo). This spec builds on 3A as written and owns every string it introduces (section 9).

## 1. Purpose

The Director asked: "Phase 3 should include various follow up tools like directory bruteforcing using configurable and selectable wordlists, and other similar follow up tools."

Today a port scan ends with a list of open ports, a service name and one hand-off action. On his own networks and on client assessments the next question is always "what is actually behind that port?". This addendum answers it on the phone with five follow-up tools that share one framework:

| Tool | Applies to | Turns an open port into |
|---|---|---|
| Directory brute-force | HTTP and HTTPS on any port | Hidden admin pages, APIs, backups and panels |
| HTTP fingerprint | HTTP and HTTPS on any port | Product, framework, cookies, robots and sitemap paths |
| TLS certificate | TLS ports | The names the device calls itself, issuer, expiry, key strength |
| SMB shares | SMB on 445 | Share names, whether anonymous or guest sessions work, which shares can be read |
| SNMP walk | Hosts that answered SNMP in discovery | System strings, interfaces and their addresses (other ranges a router serves) |

Success: on the Director's LAN, a directory brute-force of the CHNAS Synology on port 5000 with "common-short" finishes in under 10 s, shows its real paths with soft-404 noise filtered, and Stop ends it within a second; the HTTP fingerprint of the Pi-hole admin page names Pi-hole and feeds the device identity; the TLS certificate of the UniFi gateway shows its subject, SANs and expiry; the SMB tool lists the CHNAS shares and says which are readable as guest; every result is still there after the app restarts.

Everything stays within the platform limits of MVP spec section 4: ordinary TCP connects and UDP datagrams, no root, no raw sockets.

## 2. Scope

### 2.1 In this addendum

1. A tools framework: `Tool` interface, `ToolRunner` (one run at a time per host), Room persistence of runs and findings, a results sheet, a run history line, export as text.
2. Directory brute-force with selectable wordlists (three bundled, user-imported through the Storage Access Framework), extensions, method, concurrency, timeout, reported status codes and a not-found baseline filter.
3. Four companion tools: HTTP fingerprint, TLS certificate, SMB shares, SNMP walk.
4. A small raw-socket `HttpClient` in `engine/common` (GET and HEAD, redirects never followed, 64 KB body cap).
5. A Wordlists screen reachable from Settings and from the brute-force options sheet.
6. Identification hints: tool results feed `DeviceIdentifier` (4.7).
7. A Room schema change (version 2 to 3) with a migration and a migration test (section 7).

This amends MVP spec 2.3 (non-goals): enumerating web content and opening anonymous or guest SMB sessions are in scope. Password guessing, exploitation and vulnerability scanning stay out.

### 2.2 Not in this addendum (YAGNI)

- Recursive brute-force (descending into found directories).
- Proxies, custom headers, virtual-host (Host header) override, cookies or authentication of any kind beyond reporting 401 and 403.
- Scheduling, repeated or background runs, notifications, a foreground service.
- Request rate limiting or delays (concurrency is the only throttle).
- HTTP keep-alive or HTTP/2 (every request is its own connection, `Connection: close`).
- SNMP communities other than `public`, SNMPv3, SNMP SET.
- SMB writes of any kind, SMB1, SMB 3.1.1-only servers (6.3), listing directory contents.
- Downloaded wordlists (SecLists or any other). A SecLists subset could be bundled later if the Director approves the download (13.9).
- Editing or renaming imported wordlists; deleting single runs; clearing tool history.

### 2.3 Build constraints for every implementer task

Kotlin 2.1.21, Compose BOM 2025.06.00, Room 2.7.1 with KSP, coroutines 1.10.2, kotlinx-serialization 1.8.1, targetSdk 30. No new dependencies, no Gradle plugin or version changes, no downloads of any kind (libraries, wordlists, fonts, fixtures). `room-testing` is already declared for `androidTest` and is in the Gradle cache; the 3A spec adds the schemas as `androidTest` assets (`sourceSets { getByName("androidTest") { assets.srcDirs("$projectDir/schemas") } }`), and this phase adds that line only if 3A has not.

The bundled wordlists are authored by the implementer from memory of common web paths. TLS test fixtures are generated locally with the JDK 17 `keytool` and committed (12.1); nothing is fetched. No em-dashes in code, comments, strings or docs. No attribution lines in commits. Scan, Stop and Run controls are never inside a scroll container. Every user-facing string comes from section 9 and is checked by `CopyTableTest`. No disclaimers, warnings or authorisation prompts in any UI copy.

## 3. Decisions

| Decision | Choice | Rejected alternative and why |
|---|---|---|
| Framework | One `Tool` interface, one `ToolRunner`, shared persistence and sheets | One screen per tool: five screens of near-identical plumbing, and the brief allows no new screens beyond Wordlists. |
| Where tools start | A collapsible "Tools" block at the top of the Device screen's scrolling list, plus a "Run tool" group in each port's long-press sheet | A new tools screen: one more hop for every run. Pinning tools above the list: breaks the Radar spec 7.5 row budget. |
| Concurrency of runs | One run at a time per host; at most 4 hosts running at once | Unlimited: four brute-forces at 16 each is already 64 sockets. A queue: UI for something nobody asked for. |
| Port scan and tool on the same host | Mutually exclusive: the Device screen has one pinned bar and one Stop | Both at once: two progress lines and two Stops in the pinned block. |
| Run lifetime | Runs live on the app-level `appScope` (3A spec 5.3); leaving the Device screen does not stop them; Stop, a network change or process death does | Tie runs to the Device ViewModel like port scans: a 2 minute brute-force dies when the Director checks another device. |
| Stop | The pinned scan bar's pill becomes Stop during a run and is the only stop | A Stop in the results or options sheet: two stops, and sheets are dismissable. |
| Cancellation | Coroutine cancellation of the run's job; every socket carries a close-on-cancel watcher (4.6) | A separate cancel token in `run(...)`: duplicates `Job` cancellation and lets a tool ignore it. |
| HTTP client | Raw sockets through `Probe.connect`, HTTP/1.1, `Connection: close`, redirects never followed, 64 KB body cap | OkHttp: a new dependency. `HttpURLConnection` (as `HttpTitleFetcher` uses): pools connections, has its own HEAD and chunking rules, and a blocked read cannot be closed from a cancelling coroutine, so Stop could take a full timeout. |
| Socket routing | All tool sockets go through `Probe.connect` or `currentBinder()`, inside `ScanBinding.run`, so `BoundNetwork` applies to off-subnet hosts exactly as for port scans | A tool-specific bind path: two rules for the same VPN problem. |
| Brute-force method default | HEAD for every word, GET only for hits | GET for every word: up to 64 KB read per word for nothing. HEAD only: no body size, so the not-found filter cannot work. |
| Not-found filter | One GET to a random path first; hits matching its status, normalised size and normalised redirect target are hidden | No filter: catch-all servers (Synology, UniFi) report every word. A baseline per extension: more requests for a rare case. |
| Wordlist source | Three bundled lists authored in the repo; user imports through the Storage Access Framework | Downloading SecLists: forbidden without approval (13.9). |
| Wordlist storage | Normalised copy in `files/wordlists/`, metadata in `files/wordlists/index.json` | A Room table: wordlists are files, and a third table adds migration surface for no query benefit. |
| Persistence | Two Room tables, `tool_runs` and `tool_findings`, foreign keys with cascade | JSON blobs in `hosts`: unbounded row growth and no per-finding rows for copy and open. |
| History cap | 10 runs per host per tool, oldest pruned on insert | Unbounded: a client site with 200 hosts grows without limit. |
| Findings cap | 500 findings per run | Unbounded: a catch-all server that slips past the filter would write thousands of rows. |
| Options UI | Only directory brute-force has an options sheet; the other four run at once with fixed defaults | Options for every tool: four more sheets of single sliders. |
| Identification | Tools emit hint strings (finding kind `HINT`); `DeviceIdentifier` gains a rule field `toolHint`; the identity is written through 3A's `setIdentityIfNotWeaker` | Writing tool results into `hosts` or `ports` columns: schema growth, and tool data and scan data overwriting each other. |
| SNMP trigger | Offered per host only when discovery received an SNMP reply (`hosts.snmpSysDescr` not null) | Per port: the TCP port scan never finds UDP 161. Always offered: a walk of a host that ignored discovery's GET is a guaranteed timeout. |
| SMB depth | Negotiate, anonymous and guest sessions, share list over `srvsvc`, open of each share root for read | Tree connect only: says nothing about share names. Directory listing: more protocol for little more knowledge. |
| Removal and Undo | 3A's Remove device and Forget this network snapshots include the host's tool runs and findings, and their restore re-inserts them | Leaving 3A's snapshot as host and ports only: the foreign-key cascade would silently drop tool history that Undo then cannot bring back. |
| Export | Plain text through the share sheet (`ACTION_SEND`) | Files and a `FileProvider`: a new component for a few kilobytes of text. |

## 4. Tools framework

### 4.1 Interface

Package `com.northwalessolutions.netprobe.engine.tools`.

```kotlin
enum class ToolId(val key: String) {             // key is stored in tool_runs.toolId
    DIR_BRUTEFORCE("dir-bruteforce"), HTTP_FINGERPRINT("http-fingerprint"),
    TLS_CERTIFICATE("tls-certificate"), SMB_SHARES("smb-shares"), SNMP_WALK("snmp-walk"),
}

data class ToolTarget(
    val hostId: Long, val ip: Inet4Address, val port: Int,
    val tls: Boolean,              // HTTPS for the web tools; always true for TLS certificate; false otherwise
)

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
    /** Suspends until done. Cancellation of the calling coroutine is the only stop. */
    suspend fun run(target: ToolTarget, options: ToolOptions, progress: ToolProgress): ToolResult
}

sealed interface ToolOptions {
    object None : ToolOptions
    data class DirBrute(val options: DirBruteOptions) : ToolOptions   // 5.1
}
```

A tool never writes to Room itself; it reports through `ToolProgress`. Tool implementations take their I/O as constructor parameters (function types or small interfaces, as the discovery engine does) so JVM tests can fake them.

### 4.2 Applicability

A pure object `ToolApplicability` decides which tools a port gets, from the stored `ServiceInfo` (with 3A's evidence fields) and the port number. It reuses 3A's `PortUris.guess` so the action side and the tool side never disagree about what a port speaks.

| Tool | `applies(port, service)` is true when | `ToolTarget.tls` |
|---|---|---|
| Directory brute-force, HTTP fingerprint | `webScheme(port, service)` is not null | `webScheme == "https"` |
| TLS certificate | `webScheme(port, service) == "https"`, or port in `{465, 636, 993, 995, 5986, 8883}` | true |
| SMB shares | port 445 (port 139 is excluded: it needs a NetBIOS session request first) | false |
| SNMP walk | never per port; `appliesToHost(h)` is `h.snmpSysDescr != null` | false (UDP 161) |

`webScheme(port, service)`: `PortUris.guess(port, service)` when it is `"http"` or `"https"`; otherwise `"https"` when the port is in `ServiceIdentifier.TLS`, `"http"` when the port is in `ServiceIdentifier.HTTP`; otherwise null. The fallback covers ports such as 8123, 32400 or 5001 whose banner read failed and kept their port-table name.

### 4.3 ToolRunner

```kotlin
class ToolRunner(
    private val scope: CoroutineScope,                       // AppContainer.appScope (3A spec 5.3)
    private val tools: Map<ToolId, Tool>,
    private val repo: ToolRepository,
    private val env: StateFlow<NetworkEnv?>,
    private val bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit,   // ScanBinding.run with current settings
    private val onFinished: suspend (hostId: Long) -> Unit,  // re-runs identification (4.7)
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Active(val runId: Long, val hostId: Long, val toolId: ToolId, val port: Int, val startedAt: Long,
                      val done: Int, val total: Int?, val found: Int, val errors: Int)
    val active: StateFlow<Map<Long, Active>>                 // hostId -> the running run
    sealed interface Start { data class Started(val runId: Long) : Start; object HostBusy : Start; object TooMany : Start; object NoNetwork : Start }
    fun start(target: ToolTarget, toolId: ToolId, options: ToolOptions): Start
    fun stop(hostId: Long)
    companion object { const val MAX_ACTIVE_RUNS = 4; const val KEEP_RUNS_PER_TOOL = 10; const val PROGRESS_INTERVAL_MS = 200L }
}
```

Rules:

1. `start` returns `HostBusy` when the host already has an active run, `TooMany` when `MAX_ACTIVE_RUNS` hosts are running, `NoNetwork` when `env.value` is null. The Device ViewModel also refuses while its own port scan runs (8.2).
2. On start, in one transaction: insert a `tool_runs` row with status `RUNNING`, `startedAt = clock()`, `optionsJson` (7.1) and summary `""`; then prune that host and tool to the newest `KEEP_RUNS_PER_TOOL` runs (7.2).
3. The tool runs in `scope.launch` inside `bind(ip, env) { ... }`, which is `ScanBinding.run(ip, env, settings.maxPrefix, settings.extEnabled, networkEnvMonitor::binderFor)`: local hosts run unbound, off-subnet hosts with extended discovery on run with `BoundNetwork`, and a host with no network to bind fails with `BindFailedException`.
4. End states, written with `finishedAt = clock()` in `withContext(NonCancellable)`:
   - Normal return: the tool's `ToolResult` (`DONE` or `FAILED` with its summary).
   - Stop: `STOPPED` with the stopped summary (section 9) built from the last progress values.
   - Network identity change (the `ScanController` rule: env becomes null or `NetworkSignature.identityKey` differs from the one at start): every active run is cancelled and written `STOPPED`, "Network changed, run stopped".
   - `BindFailedException`: `FAILED`, "Tool skipped: could not bind to Wi-Fi" (worded as 3A's D17).
   - Any other exception escaping the tool: `FAILED`, "Failed: <message or class name>". The runner never crashes the app.
5. Process death leaves a `RUNNING` row. `ToolRepository.markInterrupted()` runs once when `AppContainer` creates the repository, before any read: every `RUNNING` row becomes `INTERRUPTED`, `finishedAt` stays null, summary "Interrupted, N found", where N is that run's finding count without hints.
6. After every end state, `onFinished(hostId)` re-runs identification (4.7). It is skipped for a `FAILED` run that recorded no finding.
7. `AppContainer` exposes `toolRunActive: StateFlow<Boolean>` (`active` not empty). `NetProbeNav` keeps the screen on while `extendedRunActive || toolRunActive`, through the existing `KeepScreenOn`.

### 4.4 Sockets and binding

Every TCP socket a tool opens comes from `Probe.connect(ip, port, timeoutMs, keepOpen = true)`, which binds through `currentBinder()` before connecting. TLS is layered over that connected socket with `SSLSocketFactory.createSocket(socket, ip.hostAddress, port, true)`, so binding and cancellation are identical for plain and TLS. The SNMP walk's `DatagramSocket` is bound with `currentBinder()?.bindOrFail(socket)` as `Snmp.query` does today. A bind failure is thrown as `BindFailedException` and is never read as a closed or silent port.

Trust-all TLS (the trust manager `TlsPeek` and `HttpTitleFetcher` already use) is used only to read. One `SSLContext` is created per run, so the platform session cache resumes TLS sessions across a brute-force run's connections. Every protocol in `socket.supportedProtocols` is enabled on tool sockets so old devices that only speak TLS 1.0 or 1.1 can still be read. The browser launch never uses this trust setting.

### 4.5 HttpClient (`engine/common/HttpClient.kt`)

```kotlin
data class HttpResponse(
    val status: Int, val reason: String,
    val headers: List<Pair<String, String>>,    // in order, names as sent
    val body: ByteArray,                        // decoded (de-chunked), at most maxBody bytes; empty for HEAD
    val bodyTruncated: Boolean,
    val contentLength: Long?,                   // from the header, null when absent
) {
    fun header(name: String): String?           // first match, case-insensitive
    fun headersAll(name: String): List<String>
}

sealed interface HttpFailure { object Refused : HttpFailure; object Timeout : HttpFailure; object Reset : HttpFailure; object Malformed : HttpFailure }

class HttpClient(private val timeoutMs: Int, private val maxBody: Int = MAX_BODY_BYTES) {
    companion object { const val MAX_BODY_BYTES = 65_536; const val MAX_HEADER_BYTES = 16_384; const val USER_AGENT = "NetProbe/0.1" }
    /** Failed on any exchange failure; cancellation and BindFailedException propagate. */
    suspend fun request(ip: Inet4Address, port: Int, tls: Boolean, method: String /* "GET" or "HEAD" */, path: String,
                        sslContext: SSLContext? = null): HttpResult
}
sealed interface HttpResult { data class Ok(val response: HttpResponse) : HttpResult; data class Failed(val failure: HttpFailure) : HttpResult }
```

- Request bytes, exactly: `<METHOD> <path> HTTP/1.1\r\nHost: <host>\r\nUser-Agent: NetProbe/0.1\r\nAccept: */*\r\nConnection: close\r\n\r\n`. `<host>` is the IP, plus `:<port>` unless the port is 80 for http or 443 for https.
- Redirects are never followed; `Location` is returned in `headers`.
- The status line must match `HTTP/1\.[01] \d{3}( .*)?`, else `Malformed`. Headers are read until a blank line; beyond `MAX_HEADER_BYTES` the response is `Malformed`.
- Body (GET only): `Transfer-Encoding: chunked` is de-chunked; otherwise `Content-Length` bytes; otherwise until close. Reading stops at `maxBody` with `bodyTruncated = true`.
- Timeouts: connect timeout and `soTimeout` are `timeoutMs`; an overall deadline of `2 x timeoutMs` per exchange stops a server that drips bytes.
- Every exchange runs inside `CancellableIo.closing(socket) { ... }` (4.6), so Stop unblocks a blocked read at once.
- Sockets are closed in `finally` on every path.

`HttpTitleFetcher` is unchanged (discovery and 3A's probe ladder keep their behaviour); the brute-force and fingerprint tools use `HttpClient` only.

### 4.6 Cancellation, progress and persistence

- `engine/common/CancellableIo.kt`: `suspend fun <T> closing(socket: Closeable, block: () -> T): T` runs `block` with `runInterruptible(Dispatchers.IO)` and a watcher coroutine (the `Probe.connect` pattern) that closes the socket when the caller is cancelled. Used by `HttpClient`, the TLS tool, the SMB client and the SNMP walk. This is what makes "Stop ends within 1 s" hold during reads, not only during connects.
- Tools call `ToolProgress.update` as often as they like; the runner keeps the latest values in atomics and publishes `active` at most every `PROGRESS_INTERVAL_MS`, plus once at the end. No StateFlow emission per request.
- Findings are written to Room as they arrive (one insert each; findings are rare compared with requests), so a crash or Stop keeps everything found so far. A failed insert is logged and the run continues (11.10).

### 4.7 Identification hints

Tools emit `Finding(kind = HINT, value = "<namespace>.<key>=<value>")`. Hints are stored with the run, never shown in the results sheet and never exported.

- `ToolRepository.hintsFor(hostId): List<String>`: the hints of the newest run per tool whose status is `DONE`, `STOPPED` or `INTERRUPTED`.
- `HostEvidence` gains `toolHints: List<String> = emptyList()`; `HostEvidenceBuilder.build` gains a `toolHints` parameter with the same default. Every path that re-identifies a stored host (discovery's upsert, 3A's post-port-scan path in `DetailViewModel` and `HomeViewModel`, and `ToolRunner.onFinished`) passes `hintsFor(hostId)`, so no later scan drops what a tool learned.
- `ToolRunner.onFinished` writes the identity through 3A's `HostRepository.setIdentityIfNotWeaker`, the same rule as after a port scan. Manual overrides (3A section 4) are untouched.
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
| hint-smb-admin-share | `^smb\.share=ADMIN\$$` | os Windows | 70 |
| hint-snmp-synology | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.6574\.` | vendor Synology, type NAS | 90 |
| hint-snmp-mikrotik | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.14988\.` | vendor MikroTik, os RouterOS, type ROUTER | 90 |
| hint-snmp-ubiquiti | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.41112\.` | vendor Ubiquiti | 70 |
| hint-snmp-cisco | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.9\.` | vendor Cisco, type ROUTER | 70 |
| hint-snmp-netsnmp | `^snmp\.sysObjectID=1\.3\.6\.1\.4\.1\.(8072\|2021)\.` | os Linux | 50 |

Hint namespaces per tool are listed with each tool (5.4, 6.1 to 6.4).

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
    val baseline: Boolean = true,
)
```

| Option | Control | Default | Validation (messages in section 9) |
|---|---|---|---|
| Wordlist | Radio list of every wordlist | `common-short` | A missing user list falls back to `common-short` with a notice |
| Extensions | Text field, comma separated | empty | Each trimmed, one leading dot dropped, must match `^[A-Za-z0-9]{1,8}$`; at most `MAX_EXTENSIONS = 8`; duplicates collapsed |
| Method | Two segments | HEAD then GET | none |
| Concurrency | Step slider, step 1 | 8 | coerced to 1..16 |
| Timeout per request | Step slider, step 1 s | 3 s | coerced to 1..10 s |
| Report status codes | Text field, comma separated | `200, 204, 301, 302, 307, 401, 403` | Each an integer 100..599; at least one; duplicates collapsed |
| Filter not-found pages | Switch | on | none |

Last-used options are saved globally in SharedPreferences key `tool.dirBrute.options` (JSON of `DirBruteOptions`) when Run is tapped, and loaded when the sheet opens. Invalid stored JSON loads the defaults.

### 5.2 Request flow

Paths: for each wordlist entry `w` (already normalised, 5.5.4) in file order, the paths are `/w`, then `/w.<ext>` for each extension in the order given. Total requests shown in progress: `entries x (1 + extensions)`.

1. Baseline (when the switch is on): one GET of `/<12 random lowercase letters and digits>`. If it fails, the run ends `FAILED`, "The server did not answer the first request". Its status, normalised size (5.3) and normalised `Location` are kept.
2. Workers: a producer streams paths from the wordlist file into a `Channel(capacity = concurrency)`; `concurrency` worker coroutines on `Dispatchers.IO.limitedParallelism(concurrency)` take paths. At most `concurrency` requests are ever in flight.
3. Per path, HEAD_THEN_GET:
   - HEAD. If the status is 405 or 501, re-issue it as GET (still one path). After `HEAD_FALLBACK_SWITCH = 3` such fallbacks the run switches to GET_ONLY for the remaining paths.
   - If the HEAD status is not in the report set, it is a miss.
   - Otherwise GET, then apply the filter (5.3) to the GET response. A finding is reported with the GET status when that status is in the report set and the filter does not hide it.
   GET_ONLY: one GET per path, then the same report-set check and filter.
4. Failures: a request that fails with `Timeout` or `Reset` is retried once after `RETRY_DELAY_MS = 250`; a second failure counts as an error (shown in progress and summary) and the run continues. After `MAX_CONSECUTIVE_FAILURES = 50` failed paths in a row the run ends `FAILED`, "The server stopped answering after N requests". `Refused` counts as a failure without retry (the server has gone away).
5. At `MAX_FINDINGS = 500` findings the run ends `DONE`, "Stopped at 500 findings".
6. Progress: `done` = paths finished, `total` = total requests, `found` = findings, `errors` = errors.

Path encoding: every character outside the RFC 3986 unreserved set, `/`, and an existing valid `%XX` escape is percent-encoded as UTF-8.

### 5.3 Not-found filter

A GET response `r` is hidden when the baseline exists and all of:

1. `r.status == baseline.status`;
2. normalised sizes are equal, or both are unknown. Normalised size = body bytes read minus (occurrences of the requested path in the body x its length in bytes); unknown when `bodyTruncated` and there is no `Content-Length`. This absorbs pages that echo the requested path back;
3. for a 3xx, the `Location` values are equal after replacing the requested path with the baseline's path in each (a catch-all redirect to `/login` matches; a `/admin` to `/admin/` redirect does not).

With the switch off nothing is hidden.

### 5.4 Findings and summary

One finding per hit, kind `PATH`:

| Field | Content |
|---|---|
| value | the path, for example `/admin` |
| detail | `<status>  <size>`, plus `  to <Location>` for 3xx (section 9 formats) |
| url | `http(s)://<ip>:<port><path>` |
| extra | `status`, `size` (bytes, empty when unknown), `location` |

Sizes: `0 B` to `1023 B`; then one decimal, `1.2 KB`; `64 KB+` when truncated with no `Content-Length`; omitted when unknown.

Hints: `http.path=<path>` for each finding whose status is 200, 204 or 401 (no rule uses them yet; they cost nothing and let panel-path rules be added by JSON edit).

Summary: `DONE`: "7 found in 4614 requests" (", 3 errors" appended when errors > 0); `STOPPED`: "Stopped after 1234 of 4614, 7 found"; the cap: "Stopped at 500 findings"; the two `FAILED` messages in 5.2.

### 5.5 Wordlists

#### 5.5.1 Bundled

Authored by the implementer in this repo from memory of common web paths; nothing is downloaded. Plain UTF-8 text, LF line endings, one entry per line, no leading slash, no comments, no blanks, no duplicates, ASCII only.

| Name | File | Entries | Content |
|---|---|---|---|
| common-short | `res/raw/wordlist_common_short.txt` | about 300 (250 to 350) | Generic web paths: `admin`, `login`, `backup`, `config`, `.git/HEAD`, `.env`, `server-status`, `phpinfo.php`, `wp-admin`, `uploads`, `static`, `test`, `old`, `cgi-bin` and similar |
| admin-panels | `res/raw/wordlist_admin_panels.txt` | about 150 (120 to 180) | Device and appliance panels: `webman`, `cgi-bin/luci`, `admin/index.php`, `manager/html`, `setup.cgi`, `HNAP1`, `ui`, `network`, `system`, `phpmyadmin`, `adminer.php` and similar |
| api-paths | `res/raw/wordlist_api_paths.txt` | about 150 (120 to 180) | API and discovery endpoints: `api`, `api/v1`, `v2`, `swagger.json`, `openapi.json`, `graphql`, `actuator/health`, `metrics`, `.well-known/openid-configuration`, `health`, `status`, `version` and similar |

Built-in ids: `builtin:common-short`, `builtin:admin-panels`, `builtin:api-paths`. Built-ins cannot be deleted.

#### 5.5.2 Import

- "Import wordlist" launches `ActivityResultContracts.OpenDocument()` with `arrayOf("text/plain")` (`ACTION_OPEN_DOCUMENT`). No permission is needed.
- The stream is parsed (5.5.4) and the normalised entries are written to `files/wordlists/<uuid>.txt` (UTF-8, LF), first to a temporary file that is renamed on success. The source document is not kept or re-read.
- Name: `OpenableColumns.DISPLAY_NAME` without its last extension; if empty, "Imported list". On a clash with an existing name (built-in or imported), " (2)", " (3)" and so on are appended.
- Limits: a source over `MAX_IMPORT_BYTES = 10_485_760` (10 MB) is rejected; more than `MAX_ENTRIES = 200_000` entries after normalisation is rejected; zero entries is rejected. Nothing is written on rejection.
- Import runs on `Dispatchers.IO`; the button reads "Importing" until it finishes.

#### 5.5.3 Store

`WordlistStore` in `data`:

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
    fun open(id: String): Sequence<String>?           // streams entries; null when missing
}
```

`index.json` holds the `WordlistMeta` of imported lists. A missing or unreadable index loads as empty, and `.txt` files in the directory without an index entry are deleted. Built-in entry counts are computed once from the raw resources.

#### 5.5.4 Parsing and normalisation

`WordlistParser.parse(input: InputStream): ParseResult(entries: List<String>, skipped: Int)`, streaming, used for imports and verified against the bundled lists:

1. A UTF-8 BOM at the start is dropped.
2. Lines split on LF, CRLF or a lone CR.
3. Each line is trimmed. Blank lines and lines starting with `#` are dropped (not counted as skipped).
4. Leading `/` characters are removed. A line that is then empty is dropped.
5. Lines that are not valid UTF-8, contain control characters, or are longer than `MAX_ENTRY_CHARS = 256` are skipped (counted).
6. Duplicates are removed, first occurrence kept, order preserved.

### 5.6 Expected timings (Wi-Fi, defaults)

| Target | Typical |
|---|---|
| common-short (about 300) against a LAN HTTP server | 2 to 5 s |
| A 4,614-entry imported list against a LAN HTTP server | 20 to 45 s |
| The same over HTTPS (one TLS handshake per request, sessions resumed) | 40 to 90 s |

Targets to measure on the Pixel 7 Pro (12.3); correct this table if off by more than 50 percent.

## 6. Companion tools

All four run at once when tapped (no options sheet), with the fixed values below as named constants.

### 6.1 HTTP fingerprint

Requests, sequential, GET via `HttpClient`, `FINGERPRINT_TIMEOUT_MS = 3000` each:

1. `/`; if it answers 3xx with a same-host `Location`, one GET of that location (the tool follows one hop itself; `HttpClient` never does).
2. `/robots.txt`
3. `/sitemap.xml`
4. `/.well-known/security.txt`
5. `/<12 random lowercase letters and digits>` (the error page)

Progress total is 5 (6 with the redirect hop).

Findings (a file answering anything but 200 produces nothing):

| Kind | value | detail |
|---|---|---|
| `STATUS` | "200 OK" (status and reason) | "GET /", or "GET /, to <Location>" for 3xx |
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

Framework rules: `res/raw/http_fingerprints.json`, hand-authored, schema `{id, source, pattern, name, sample}`. `source` is `header:<name>`, `cookie`, `title`, `generator`, `body` (the `/` body) or `errorBody`; `pattern` is a case-insensitive regex; `name` may use `$1` (trimmed when the group is empty). Each matching rule adds one `FRAMEWORK` finding, one per distinct name. Seed:

| id | source | pattern | name |
|---|---|---|---|
| synology-title | title | `Synology\|DiskStation` | Synology DSM |
| synology-body | body | `SYNO\.SDS\|/webman/` | Synology DSM |
| pihole | title | `Pi-hole` | Pi-hole |
| unifi | title | `UniFi` | UniFi OS |
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
data class TlsDetails(val protocol: String, val cipherSuite: String, val chain: List<X509Certificate>)
open suspend fun details(ip: InetAddress, port: Int, timeoutMs: Int = TLS_TOOL_TIMEOUT_MS /* 3000 */): TlsDetails?
```

It connects through `Probe.connect(keepOpen = true)`, layers the trust-all `SSLSocket` (4.4), handshakes inside `CancellableIo.closing`, reads `session.peerCertificates` (the whole chain), and closes. A pure mapper `CertSummaries.of(details, now): List<Finding>` builds the findings, so all logic is JVM-testable from fixtures.

| Kind | value | detail |
|---|---|---|
| `CERT_SUBJECT` | RFC 2253 subject of the leaf | "Subject" |
| `CERT_ISSUER` | RFC 2253 issuer of the leaf | "Issuer" |
| `CERT_SAN` | each DNS or IP SAN | "Alternative name (DNS)" or "Alternative name (IP)" |
| `CERT_VALIDITY` | "<notBefore> to <notAfter>" (medium date) | "Valid", "Expired", "Not yet valid", or "Expires in N days" when within `EXPIRY_SOON_DAYS = 30` |
| `CERT_KEY` | "RSA 2048 bit", "EC 256 bit" (field size), or the algorithm name | "Public key" |
| `CERT_SIGNATURE` | `sigAlgName` | "Signature algorithm" |
| `CERT_FINGERPRINT` | SHA-256 of the leaf's DER, colon-separated upper-case hex | "SHA-256 fingerprint" |
| `CERT_CHAIN` | subject of each non-leaf certificate | "Chain certificate N of M" |
| `TLS_SESSION` | "<protocol> <cipher suite>" | "Protocol and cipher" |
| `FLAG` | "Self-signed" when issuer equals subject and the leaf verifies with its own key | "Certificate" |
| `FLAG` | "RSA key under 2048 bit" when RSA and under `WEAK_RSA_BITS = 2048` | "Public key" |
| `FLAG` | "Signed with SHA-1" when `sigAlgName` contains `SHA1` | "Signature algorithm" |

Hints: `tls.subject=`, `tls.issuer=`, `tls.san=` (one per SAN).

Summary: "<subject CN, else the first SAN>, expires <date>", with ", self-signed" appended when flagged. `FAILED`: "No TLS handshake on this port".

### 6.3 SMB shares (the riskiest implementation item)

A minimal, read-only SMB2 client in `engine/tools/smb/`, hand-written from MS-SMB2, MS-NLMP, MS-SPNG and MS-SRVS, with no library. This is the largest and least certain part of the phase: Windows and Samba differ in what they accept for anonymous and guest sessions, SPNEGO and NDR are easy to get subtly wrong, and only the on-device check against real servers (12.3) proves it. The plan gives it its own tasks and its own review, and lands it after the other four tools.

Transport: TCP 445 via `Probe.connect(keepOpen = true)`, direct-TCP framing (a zero byte plus a 24-bit big-endian length), every exchange inside `CancellableIo.closing`, `SMB_TIMEOUT_MS = 3000` per exchange, responses capped at 64 KB.

Sequence:

1. NEGOTIATE with dialects 0x0202, 0x0210, 0x0300, 0x0302; SecurityMode SIGNING_ENABLED; a random ClientGuid; no negotiate contexts. Record the dialect and whether the server requires signing (SecurityMode bit 0x02). No answer ends the run `FAILED`, "No SMB response on this port"; a refusal of every offered dialect ends it `FAILED`, "No SMB2 dialect in common".
2. Anonymous session (this connection): SESSION_SETUP carrying a SPNEGO NegTokenInit with mechType NTLMSSP (`1.3.6.1.4.1.311.2.2.10`) wrapping NTLM NEGOTIATE. The CHALLENGE in the reply is parsed for its target info (NetBIOS computer and domain names, DNS computer and domain names, timestamp) and the NTLM version (OS build). Then NTLM AUTHENTICATE with the anonymous flag, empty user and domain, an empty NT response and a single zero LM byte (MS-NLMP 3.2.5.1.2). `STATUS_SUCCESS` means allowed (SessionFlags IS_NULL or IS_GUEST recorded); any other status means refused.
3. Guest session (a new connection, same NEGOTIATE): user `guest`, empty domain, empty password, NTLMv2 response (NTOWFv2 from a hand-written MD4 per RFC 1320 and `Mac.getInstance("HmacMD5")`), no MIC. `STATUS_SUCCESS` means allowed; IS_GUEST in SessionFlags reads "mapped to guest".
4. If neither session is allowed, the run ends `DONE` with the session and host findings only.
5. On the first allowed session (anonymous preferred): if SessionFlags has ENCRYPT_DATA the run ends `DONE`, "Encryption required, shares not listed". Otherwise TREE_CONNECT `\\<ip>\IPC$`; CREATE the pipe `srvsvc`; DCERPC BIND to SRVSVC (`4b324fc8-1670-01d3-1278-5a47bf6ee188` v3.0, transfer syntax NDR `8a885d04-1ceb-11c9-9fe8-08002b104860` v2) by WRITE then READ; `NetrShareEnumAll` (opnum 15) at level 1 by WRITE then READ, reassembling fragments until the last-fragment flag, up to 64 KB; parse `SHARE_INFO_1` (name, type, remark) for at most `MAX_SHARES = 100` shares. A request refused after session setup because the server requires signing ends the listing, "Signing required, shares not listed".
6. For each disk share (type & 0x0FFFFFFF == 0): TREE_CONNECT; refused means "listed only"; ShareFlags ENCRYPT_DATA means "encrypted, not read"; otherwise CREATE the share root with DesiredAccess `FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES | SYNCHRONIZE` (0x00100081), disposition FILE_OPEN, option FILE_DIRECTORY_FILE: success is "read" (then CLOSE), refusal is "listed only". TREE_DISCONNECT after each. Non-disk shares are "not checked".
7. LOGOFF, close.

Read-only guard: `SmbReadOnlyGuard` is the only path that builds CREATE and WRITE requests. CREATE is allowed only with access bits within `FILE_READ_DATA | FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES | FILE_READ_EA | READ_CONTROL | SYNCHRONIZE` and disposition FILE_OPEN, except the `srvsvc` pipe, which may add `FILE_WRITE_DATA`. WRITE is allowed only to the `srvsvc` pipe's file id. Anything else throws before a byte is sent. The client implements no SET_INFO, IOCTL or QUERY_DIRECTORY command at all.

Findings:

| Kind | value | detail |
|---|---|---|
| `SMB_DIALECT` | "SMB 2.0.2", "SMB 2.1", "SMB 3.0" or "SMB 3.0.2" | "Dialect" |
| `SMB_SIGNING` | "Required" or "Enabled, not required" | "Signing" |
| `SMB_SESSION` | "Anonymous allowed" or "Anonymous refused" | "Session" |
| `SMB_SESSION` | "Guest allowed", "Guest mapped to guest" or "Guest refused" | "Session" |
| `SMB_HOST` | each of NetBIOS name, NetBIOS domain, DNS name, DNS domain, OS build (`major.minor.build`) | "NetBIOS name", "NetBIOS domain", "DNS name", "DNS domain", "OS build (NTLM)" |
| `SMB_SHARE` | share name (url `smb://<ip>/<share>` for disk shares) | "<type>, <access>", plus ", <remark>" when not blank. Type: "Disk", "Printer", "Device", "IPC", with " (hidden)" when the special bit is set. Access: "read", "listed only", "encrypted, not read", "not checked" |

Hints: `smb.share=<name>`, `smb.netbiosName=`, `smb.dnsName=`, `smb.domain=`.

Summary: "N shares, anonymous <allowed or refused>, guest <allowed or refused>"; "Anonymous and guest refused"; or the encryption and signing messages above.

### 6.4 SNMP walk

SNMPv2c, community `public`, UDP 161, using the existing `Ber` code extended with the GETNEXT PDU (0xa1), the application types IpAddress (0x40), Counter32 (0x41), Gauge32 (0x42) and TimeTicks (0x43), and the exceptions noSuchObject (0x80), noSuchInstance (0x81) and endOfMibView (0x82). `SNMP_TIMEOUT_MS = 1000`, one retry; request ids increment and replies carrying another id are ignored. Hard cap `SNMP_MAX_REQUESTS = 400`.

1. System group: GETNEXT from `1.3.6.1.2.1.1` until the reply leaves the subtree: sysDescr, sysObjectID, sysUpTime, sysContact, sysName, sysLocation.
2. Interfaces: GETNEXT in lockstep over ifDescr (`1.3.6.1.2.1.2.2.1.2`), ifType (`.3`), ifSpeed (`.5`), ifPhysAddress (`.6`) and ifOperStatus (`.8`), one request per row, until the first column leaves its subtree or `SNMP_MAX_INTERFACES = 64` rows.
3. Addresses: GETNEXT in lockstep over ipAdEntAddr (`1.3.6.1.2.1.4.20.1.1`) and ipAdEntNetMask (`.3`), up to `SNMP_MAX_ADDRESSES = 64`.

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
    indices = [Index("hostId", "toolId", "startedAt")])
data class ToolRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val toolId: String,
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String)

@Entity(tableName = "tool_findings",
    foreignKeys = [ForeignKey(entity = ToolRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")])
data class ToolFindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val kind: String,
    val value: String, val detail: String?, val extra: String?)
```

- `status` uses Room's built-in enum-as-TEXT mapping, as `HostStatus` does.
- `kind` is TEXT, mapped by `FindingKind.parse(s)` with an `OTHER` fallback, so a kind removed in a later version never crashes a read.
- `extra` is a JSON object (`Map<String, String>` via kotlinx-serialization) carrying `url` when present plus the per-kind fields; null when empty.
- `optionsJson` is a JSON object, `{"target": {"port": 5000, "tls": false}}` plus, for brute-force runs, the `DirBruteOptions` fields and `wordlistName` and `wordlistEntries`, so history still names a wordlist after it is deleted. A malformed value decodes to "port unknown" and the history line omits the port.

`FindingKind`: `PATH, STATUS, HEADER, COOKIE, TITLE, GENERATOR, ROBOTS, SITEMAP, SECURITY_TXT, ERROR_PAGE, FRAMEWORK, CERT_SUBJECT, CERT_ISSUER, CERT_SAN, CERT_VALIDITY, CERT_KEY, CERT_SIGNATURE, CERT_FINGERPRINT, CERT_CHAIN, TLS_SESSION, FLAG, SMB_DIALECT, SMB_SIGNING, SMB_SESSION, SMB_HOST, SMB_SHARE, SNMP_SYSTEM, SNMP_INTERFACE, SNMP_ADDRESS, HINT, OTHER`.

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
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") fun findings(runId: Long): Flow<List<ToolFindingEntity>>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") suspend fun findingsOnce(runId: Long): List<ToolFindingEntity>
    @Query("SELECT COUNT(*) FROM tool_findings WHERE runId = :runId AND kind != 'HINT'") suspend fun findingCount(runId: Long): Int
    @Query("SELECT value FROM tool_findings WHERE kind = 'HINT' AND runId IN (SELECT MAX(id) FROM tool_runs " +
           "WHERE hostId = :hostId AND status IN ('DONE','STOPPED','INTERRUPTED') GROUP BY toolId)")
    suspend fun hints(hostId: Long): List<String>
    // Undo support for the 3A snapshots (7.4)
    @Query("SELECT * FROM tool_runs WHERE hostId IN (:hostIds)") suspend fun runsForHostsOnce(hostIds: List<Long>): List<ToolRunEntity>
    @Query("SELECT * FROM tool_findings WHERE runId IN (:runIds)") suspend fun allFindingsOnce(runIds: List<Long>): List<ToolFindingEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRuns(rs: List<ToolRunEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFindings(fs: List<ToolFindingEntity>)
}
```

`ToolRepository(db)` wraps these: `startRun` (insert plus prune in one `withTransaction`), `finish`, `addFinding`, `markInterrupted` (each `RUNNING` row gets `INTERRUPTED` and its "Interrupted, N found" summary, in one transaction), `runsForHost`, `findings`, `hintsFor`, `snapshot(hostIds)` and `restore(snapshot)`. `NetProbeDb` adds both entities and `abstract fun tools(): ToolDao`. SQLite queries with `IN (:list)` are chunked at 900 ids to stay under the bound-variable limit.

### 7.3 Schema version and migration

- The 3A spec takes schema 1 to 2. This phase is version 3 with `MIGRATION_2_3`. If 3B were ever built before 3A, the same body becomes `MIGRATION_1_2` and 3A takes the next number; the plan states which applies at execution time.
- Migration SQL (the implementer copies the `createSql` strings from the newly exported `3.json`; these are the expected values):

```sql
CREATE TABLE IF NOT EXISTS `tool_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER NOT NULL, `toolId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_startedAt` ON `tool_runs` (`hostId`, `toolId`, `startedAt`);
CREATE TABLE IF NOT EXISTS `tool_findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `detail` TEXT, `extra` TEXT, FOREIGN KEY(`runId`) REFERENCES `tool_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`);
```

- `@Database(version = 3, exportSchema = true)`; `NetProbeDb.open` adds `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. Never `fallbackToDestructiveMigration`: it would wipe the Director's annotations.
- `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json` is generated by KSP and committed next to `1.json` and `2.json`.
- The migration test (12.2) extends 3A's `MigrationTest`: create schema 2 with a network, a host with a note and status, and a port; run `MIGRATION_2_3` with `runMigrationsAndValidate(..., validateDroppedTables = true)`; old rows read back intact and the new tables accept a run and findings. A second case migrates 1 through 3 in one chain. A third opens the migrated database through the real `NetProbeDb` builder so Room's own validation passes.

### 7.4 Cascade, removal with Undo, growth

- Room turns on `PRAGMA foreign_keys` because these entities declare foreign keys. Deleting a host deletes its runs and their findings.
- 3A's Remove device and Forget this network delete hosts, so the cascade would remove tool history that 3A's Undo then cannot restore. This phase extends both snapshots: before the delete, `ToolRepository.snapshot(hostIds)` captures the hosts' runs and their findings (hints included); `restore` re-inserts them with their original ids after 3A has re-inserted the hosts, in the same transaction (order: network, hosts, ports, tool runs, tool findings). When the Undo lapses the snapshot is dropped as 3A specifies.
- 3A's explicit delete order (ports, then hosts, then network) is unchanged; the cascade removes tool rows when the hosts go.
- Remove device is disabled while the host has an active tool run, and Forget this network is disabled while any host of that network has one, in the same way 3A disables both during scans.
- Growth bound: at most 10 runs per host per tool (5 tools, so 50 runs per host) and at most 500 findings plus hints per run.
- Wordlists are not tied to hosts and are unaffected by removal.

### 7.5 Export

The results sheet's "Share" builds plain text and sends it with `Intent.ACTION_SEND`, type `text/plain`, `EXTRA_SUBJECT` "NetProbe <tool name> <ip>:<port>", through `Intent.createChooser` (chooser title "Share"). Format:

```
NetProbe <tool name>
Target: <target>
Started: 2026-10-20 14:03:11
Finished: 2026-10-20 14:04:02
Status: Done
Summary: 7 found in 4614 requests
Options: wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on

<value>\t<detail>\t<url>
```

- `<target>`: `http://<ip>:<port>/` or `https://<ip>:<port>/` for the web tools, `<ip>:<port>` for TLS certificate, `smb://<ip>/` for SMB, `udp://<ip>:161` for SNMP.
- "Finished: not finished" when `finishedAt` is null. The Options line appears for brute-force runs only (extensions as "php html", or "none"; "not-found filter off" when off).
- One line per finding in stored order, tab-separated, empty fields left empty. Hints are never exported; cookie values never exist to export (6.1).
- Times use the phone's local time zone, `yyyy-MM-dd HH:mm:ss`.

## 8. UI

Tokens, type styles, radii and touch targets are the Radar spec's (its sections 2 and 7): IBM Plex, `surface` sheets with 8 dp top radius, 8 dp field and segment radii, 48 dp minimum targets, `heightIn(min)` for every text container, no shadows. Motion follows the 3A spec: finding rows that arrive during a live run use its "rows arrive as found" treatment (3A 1.10), selections cross-fade (3A 1.11), and reduced motion makes everything instant (3A 1.15). New composables live in `ui/tools/` and `ui/wordlists/`; stateless content functions take state and action lambdas, as `DetailContent` does.

### 8.1 Device screen: Tools block

The pinned scan bar heads the ports list (Radar spec 5.1 item 8). The Tools block is the first item of the scrolling `portList`, directly beneath it, followed by the port rows. It is omitted when no tool applies to the host.

Collapsed (the default; the expanded state is saved per host with `rememberSaveable(hostId)`): one row of about 58 dp, the height of a port row, with a 1 dp `rowDivider` hairline below.

- Left column: "Tools" in `secondary` / `text`, then "N available" in `data` / `textMuted` on the same line, 10 dp apart. Second line, `data` / `textMuted`, one line ellipsised: the newest run of any tool on this host ("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 requests"), or "No tools run yet".
- Right: a chevron, 48 dp target. The whole row toggles. Description "Tools, N available, expand" or "Tools, N available, collapse". Tag `toolsHeader`.

Expanded adds one block per applicable tool, in `ToolId` order, each with 18 dp side padding, 10 dp vertical padding and a `rowDivider` hairline below:

- Line 1: tool name in `secondary` / `text`. At the right, the action word "Results" in `action` / `signal`, 48 dp target, shown only when the tool has a run on this host; it opens the results sheet on the newest run. Description "Show <tool name> results".
- Line 2: the history line in `data` / `textMuted`: "Last run <relative time> on <port>: <summary>", or "Not run yet". Relative time uses `RelativeTime.of`.
- Line 3: one target chip per applicable port, in port order: outlined, 1 dp `border`, fully round, `data` / `textSecondary`, 32 dp visual inside a 48 dp target, 8 dp apart, wrapping with `FlowRow`. The label is the port number ("5000"); SNMP walk shows "UDP 161". Description "Run <tool name> on port <port>". Tag `toolChip:<toolId>:<port>`. Tapping runs the tool on that port: directory brute-force opens its options sheet (8.4); the others start at once.
- While the host has an active tool run or port scan, the chips are drawn at `DISABLED_ALPHA`, and a tap shows the snackbar "A tool is already running on this device" or "Wait for the port scan to finish".

### 8.2 Pinned scan bar during a run

No height is added to the pinned block. While the host has an active tool run:

- The Scan pill shows "Stop" (outlined, as during a port scan) and calls `ToolRunner.stop(hostId)`; description "Stop tool run". It is the only Stop for tool runs.
- The profile segmented control is disabled (alpha .5), so a port scan cannot start.
- The reserved progress line shows, in one row: at the left, "<tool name> on <port>" in `data` / `textMuted`, ellipsised, `weight(1f)`; at the right, the progress text (section 9) in `data` / `textMuted`. The 2 dp line shows `done / total`, or an indeterminate `LinearProgressIndicator` with the same colours when the total is null.
- When the run ends the bar returns to normal and a snackbar shows "<tool name> finished: <summary>" (the summary of whatever end state was written).
- 3A's Detail overflow items ("Edit device", "Remove device") follow 7.4: "Remove device" is disabled during a tool run on this host.

While a port scan runs on the host, tool chips and port-sheet tool rows are disabled (8.1, 8.3).

### 8.3 Port long-press sheet

3A's `PortSheet` order gains one group between "Copy IP and port" and "Look up vulnerabilities for this service": a `caption` / `textMuted` label "Run tool" and one 48 dp row per tool that applies to that port, labelled with the tool name in `signal` (tag `sheetTool:<toolId>`). Tapping closes the sheet and starts the tool on that port (directory brute-force opens its options sheet). While the host is busy the rows are `textMuted` and a tap shows the same snackbars as 8.1. The group is omitted when no tool applies.

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
   - Switch row "Filter not-found pages" with supporting text "Checks a random path first and hides answers that match it".
3. Footer (fixed): a 1 dp `surface` rule above, 18 dp padding, respecting the navigation bar inset. At the left, the live line "<N> requests" (`data` / `textMuted`, total from 5.2); at the right, a Run pill in the `ScanPill` style labelled "Run" (`signal` fill, `scanPill` / `background`, 40 dp visual, 48 dp target, tag `toolRunPill`). The pill is disabled while any field has an error or the host is busy; the busy reason shows in `error` above the footer row ("A tool is already running on this device", "Wait for the port scan to finish", "Four tools are already running, stop one first").

Run saves the options (5.1), closes the sheet and starts the run. Dismissing the sheet starts nothing.

### 8.5 Results sheet

`ModalBottomSheet`, `surface`, 8 dp top radius, `skipPartiallyExpanded = true`, opened from "Results" (8.1). Three parts:

1. Header (fixed), 18 dp side padding: "<tool name>  <ip>:<port>" in `rowName` / `text`; beneath it, in `data` / `textMuted`, "<status label>, <relative start time>" (an active run shows "Running" and the live progress text instead of the time); beneath that the summary in `secondary` / `textSecondary`. A run switcher at the right of the title: two 48 dp chevron targets, descriptions "Older run" and "Newer run", disabled at the ends, around "Run N of M" in `data` / `textMuted` (1 is the newest).
2. Findings (`LazyColumn`, `weight(1f)`), in stored order. Row: 18 dp side padding, 10 dp vertical padding, `rowDivider` hairline below. Left column: `value` in `data` / `text` (up to 3 lines then ellipsised; a tap expands it), `detail` beneath in `data` / `textMuted`, one line. Right: "Open with..." in `action` / `signal`, only when the finding has a URL (fires `DeviceAction.OpenWith(url)`, the system chooser), and "Copy" in `action` weight 400 / `textMuted` (fires `DeviceAction.Copy`, copying the URL when there is one, else the value). Each word is its own 48 dp target. Descriptions "Open <url> with" and "Copy <value>".
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

### 8.8 Font scale 1.3 budget

The pinned block does not grow (8.2). The collapsed Tools row (about 58 dp) takes the place of one port row, so the Radar spec 7.5 acceptance becomes: with the note and the Tools block collapsed, at least five port rows visible (was six); with the note expanded and Custom showing an error, at least two (was three). If 3A's pinned-block changes alter that budget, its numbers minus one row apply. The options sheet body scrolls; its header (about 70 dp) and footer (about 80 dp) stay visible at 1.3 on the Pixel 7 Pro.

## 9. Strings

Sentence case, active voice, no disclaimers or warnings. All strings live in `ui/tools/ToolCopy.kt` and `ui/wordlists/WordlistCopy.kt`; `CopyTableTest` gains these templates and a state matrix over every helper that builds them. Numbers have no thousands separator. `<t>` is elapsed whole seconds; `<relative>` comes from `RelativeTime.of`; dates are `RelativeTime.defaultDate` (medium).

| Where | String |
|---|---|
| Tool names | "Directory brute-force", "HTTP fingerprint", "TLS certificate", "SMB shares", "SNMP walk" |
| Tools row | "Tools"; "N available" ("1 available") |
| Tools row history | "Last run <relative>: <tool name> on <port>, <summary>"; "No tools run yet" |
| Tools row description | "Tools, N available, expand"; "Tools, N available, collapse" |
| Tool block history | "Last run <relative> on <port>: <summary>"; "Not run yet" (SNMP: "on UDP 161") |
| Tool block action | "Results"; description "Show <tool name> results" |
| Target chip | "<port>"; "UDP 161"; description "Run <tool name> on port <port>" |
| Port sheet group | "Run tool" |
| Busy | "A tool is already running on this device"; "Wait for the port scan to finish"; "Four tools are already running, stop one first" |
| Pinned bar label | "<tool name> on <port>" ("SNMP walk on UDP 161") |
| Progress, known total | "1234 of 4614, 7 found  12 s"; with errors "1234 of 4614, 7 found, 3 errors  12 s" |
| Progress, unknown total | "12 done, 3 found  2 s" |
| Progress, before the first update | "starting" |
| Stop description | "Stop tool run" |
| Run ended snackbar | "<tool name> finished: <summary>" |
| Run status labels | "Running", "Done", "Stopped", "Failed", "Interrupted" |
| Common summaries | "Network changed, run stopped"; "Tool skipped: could not bind to Wi-Fi"; "Failed: <reason>"; "Interrupted, N found"; "Stopped after N of M, K found"; "Stopped after N steps, K found" (unknown total) |
| Brute-force summaries | "N found in M requests"; "N found in M requests, E errors"; "Stopped at 500 findings"; "The server did not answer the first request"; "The server stopped answering after N requests" |
| HTTP fingerprint summaries | "<name>, N findings" ("1 finding"); "No product named, N findings"; "No HTTP response on this port" |
| TLS summaries | "<name>, expires <date>"; "<name>, expires <date>, self-signed"; "No TLS handshake on this port" |
| SMB summaries | "N shares, anonymous allowed, guest allowed" (each part allowed or refused; "1 share"); "Anonymous and guest refused"; "Encryption required, shares not listed"; "Signing required, shares not listed"; "No SMB2 dialect in common"; "No SMB response on this port" |
| SNMP summaries | "<name>, N interfaces" ("1 interface"); "No SNMP answer to public" |
| Options sheet | "Directory brute-force"; "Wordlist"; "N entries" ("1 entry"); "Manage wordlists"; "Extensions"; placeholder "php, html, txt"; "Each word is also tried with these endings"; "Method"; "HEAD then GET"; "GET only"; "Concurrency"; "Timeout per request"; "N s"; "Report status codes"; "Filter not-found pages"; "Checks a random path first and hides answers that match it"; "N requests" ("1 request"); "Run" |
| Options errors | "Use letters and digits only, for example php, html"; "At most 8 extensions"; "Use codes from 100 to 599, for example 200, 301, 403"; "Add at least one status code" |
| Wordlist fallback notice | "Wordlist not found, using common-short" |
| Results sheet | "<tool name>  <ip>:<port>"; "<status label>, <relative>"; "Run N of M"; "Older run"; "Newer run"; "Open with..."; "Copy"; "Share"; "Copied"; "Running, findings appear as they are found"; "No findings" |
| Results descriptions | "Open <url> with"; "Copy <value>" |
| Export | subject "NetProbe <tool name> <ip>:<port>"; chooser title "Share"; lines "NetProbe <tool name>", "Target: ...", "Started: ...", "Finished: ...", "Finished: not finished", "Status: <status label>", "Summary: ...", "Options: ..." as in 7.5 |
| Brute-force finding detail | "<status>  <size>"; "<status>  <size>  to <location>"; "<status>" (size unknown); sizes "N B", "N.N KB", "64 KB+" |
| HTTP fingerprint details | "GET /"; "GET /, to <location>"; header names as sent; "Cookie"; ", Secure"; ", HttpOnly"; ", SameSite=<v>"; "Page title"; "Generator meta tag"; "N rules" ("1 rule"); "Disallowed in robots.txt"; "Allowed in robots.txt"; "N URLs" ("1 URL"); "Contact: <value>"; "Present"; "Error page signature"; "Matched <source>: <excerpt>" |
| TLS details | "Subject"; "Issuer"; "Alternative name (DNS)"; "Alternative name (IP)"; "<date> to <date>"; "Valid"; "Expired"; "Not yet valid"; "Expires in N days" ("1 day"); "RSA N bit"; "EC N bit"; "Public key"; "Signature algorithm"; "SHA-256 fingerprint"; "Chain certificate N of M"; "Protocol and cipher"; "Certificate"; "Self-signed"; "RSA key under 2048 bit"; "Signed with SHA-1" |
| SMB details | "Dialect"; "SMB 2.0.2", "SMB 2.1", "SMB 3.0", "SMB 3.0.2"; "Signing"; "Required"; "Enabled, not required"; "Session"; "Anonymous allowed"; "Anonymous refused"; "Guest allowed"; "Guest mapped to guest"; "Guest refused"; "NetBIOS name"; "NetBIOS domain"; "DNS name"; "DNS domain"; "OS build (NTLM)"; "Disk", "Printer", "Device", "IPC", " (hidden)"; "read", "listed only", "encrypted, not read", "not checked" |
| SNMP details | "sysDescr", "sysObjectID", "Uptime", "sysContact", "sysName", "sysLocation"; "N days N h"; "N h N min"; the interface statuses and types listed in 6.4; "type N"; "N Mbit/s", "N Gbit/s"; "Interface address" |
| Wordlists screen | "Back"; "Wordlists"; "Import wordlist"; "Importing"; "Built in"; "Imported"; "N entries, built in"; "N entries, imported <date>"; "Delete"; "Delete <name>?"; "Cancel"; "No imported wordlists yet"; description "Delete <name>" |
| Import results | "Imported <name>, N entries"; "Imported <name>, N entries (K skipped)"; "That file is too large (limit 10 MB)"; "That file has more than 200000 entries"; "That file has no entries"; "Could not read that file" |
| Default import name | "Imported list" |
| Settings | section "Tools"; row "Wordlists"; "3 built in, N imported" |

## 10. Data flow

1. Open a device: `DetailViewModel` combines its existing flows with `toolRunner.active`, `tools.runsForHost(hostId)` and `ToolApplicability` over the stored ports and host, producing a `ToolsUiState` (collapsed or expanded, one row per tool, history lines, busy flags).
2. Tap a chip or a port-sheet tool row: directory brute-force opens the options sheet with the saved options and `wordlists.lists`; the others call `toolRunner.start` at once.
3. `ToolRunner.start` inserts the run and prunes (one transaction), then launches the tool on `appScope` inside `ScanBinding.run`. The pinned bar switches to tool progress and Stop.
4. The tool connects through `Probe.connect` (bound when off-subnet), reports progress (published every 200 ms) and findings (inserted at once). The results sheet, if open, fills live from Room.
5. The run ends (done, failed, Stop or network change). The runner writes the end state and re-identifies the host with `hintsFor(hostId)` through `setIdentityIfNotWeaker`; the identity line and history line update from Room, and a snackbar reports the summary.
6. Share builds the text (7.5) and opens the share sheet. "Open with..." and "Copy" go through `AndroidActionRunner`.
7. Wordlists: import normalises into `files/wordlists/`; the store's `lists` flow updates the options sheet and the Wordlists screen.
8. Remove device or Forget this network (3A): the snapshot includes tool runs and findings; the cascade removes them; Undo restores them (7.4).

## 11. Error handling and edge cases

- 11.1 Per-request failures never stop a run on their own (5.2 retry and error count); only `MAX_CONSECUTIVE_FAILURES` or a failed first request ends a brute-force early. Companion tools end a step, not the run, on a timeout, except when the first exchange fails (each tool's `FAILED` summary).
- 11.2 Malformed bytes from any server (HTTP, TLS, SMB, SPNEGO, NDR, BER) are caught in the parsers, which return null; parsers are fuzz-style tested with truncated and garbage input. Nothing a server sends can crash the app.
- 11.3 Off-subnet hosts with extended discovery on run bound to Wi-Fi; a bind failure fails the run with "Tool skipped: could not bind to Wi-Fi" and never runs unbound (as port scans, extended spec 5.5).
- 11.4 Network change: all runs stop with "Network changed, run stopped"; partial findings stay.
- 11.5 Process death: `INTERRUPTED` on the next launch (4.3 item 5); its findings stay and its hints count.
- 11.6 Catch-all servers: filtered by the baseline (5.3); if one slips past, the 500 cap ends the run.
- 11.7 Servers that reject HEAD: per-path fallback, then GET only after 3 (5.2).
- 11.8 Huge wordlists: entries stream from the file and are never loaded whole; progress and Stop stay responsive.
- 11.9 A deleted wordlist in the saved options: falls back to common-short with the notice; history still names it from `optionsJson`.
- 11.10 A Room write failure during a run: logged, the run continues; the end-state write is retried once, and if it still fails the row stays `RUNNING` and 11.5 repairs it on the next launch.
- 11.11 Leaving the Device screen during a run: the run continues and returning shows it. The screen stays on while any run is active.
- 11.12 Old TLS servers: every supported protocol is enabled (4.4); a handshake that still fails gives "No TLS handshake on this port".
- 11.13 SMB servers that require signing or encryption: reported as findings and summaries (6.3), never retried with other credentials.
- 11.14 A host removed while its results sheet is open: the run flow emits nothing and the sheet closes.

## 12. Testing and verification

### 12.1 Unit tests (JVM)

- `HttpClient` against in-process `ServerSocket` scripts on loopback: exact request bytes for HEAD and GET (Host with and without the port), `Content-Length`, chunked (with a chunk extension and a trailer), close-delimited bodies, the 64 KB cap with `bodyTruncated`, the 16 KB header cap, a malformed status line, a server that never answers (`Timeout`), a server that drips bytes (overall deadline), a refused port (`Refused`), a redirect returned and not followed, and cancellation mid-read closing the socket within 1 s.
- TLS through `HttpClient` and `TlsPeek.details` against a loopback `SSLServerSocket` using a committed PKCS12 test keystore.
- TLS fixtures, generated locally with JDK 17 `keytool` and committed under `app/src/test/resources/tls/`: self-signed RSA 2048, RSA 1024 (weak flag), EC P-256, a two-level chain (`-gencert`), an expired certificate (`-startdate` in the past), one signed `SHA1withRSA`, and one with DNS and IP SANs. `CertSummaries.of` is checked for every finding, every flag and the summary.
- `ToolApplicability`: every row of 4.2, including "HTTP alt" on 81, "Web / Synology" on 5000, "Synology HTTPS" on 5001 with and without a confirmed banner, an unknown port confirmed HTTP by 3A's ladder, port 139 excluded, SNMP only with `snmpSysDescr`.
- Directory brute-force with a fake exchange function: the baseline filter for a soft-404 200, a path-echoing 200 (normalised size), a catch-all 302 to `/login` versus a real `/admin` to `/admin/`, and unknown sizes; report-set filtering; HEAD 405 and 501 fallback and the switch after 3; extension expansion order and the request total; retry once then error; `Refused` not retried; abort after 50 consecutive failures; the 500 cap; a failed baseline; percent-encoding of paths; the concurrency bound (the fake records the maximum in flight, which never exceeds the setting for 1, 8 and 16).
- `WordlistParser`: LF, CRLF and lone CR; BOM; comments; blanks; leading slashes; trimming; duplicates (order kept); over-long lines, invalid UTF-8 and control characters skipped and counted.
- Bundled wordlists: each within its entry range (5.5.1), parses to itself unchanged (no blanks, comments, leading slashes or duplicates), ASCII only.
- `WordlistStore` (temporary directory): import writes the normalised file and the index entry; name clashes get suffixes; the size, count and empty rejections write nothing; delete removes the file and entry; built-ins cannot be deleted; a corrupt index loads empty and orphans are removed.
- `DirBruteOptions`: every validation message, coercion of concurrency and timeout, saved options round trip, invalid JSON loads defaults.
- HTTP fingerprint parsers from fixtures: headers; cookie names with flags, plus an assertion that no cookie value appears in any finding or in the export; generator meta; robots rules and the 50-path cap; sitemap count; security.txt contact; error-page signatures (Apache, nginx, Tomcat); every seed rule against its `sample`; hint strings with versions stripped.
- SMB: MD4 against the RFC 1320 test suite; NTLMv2 against the MS-NLMP 4.2.4 test vectors; SPNEGO and NTLM builders against hand-built byte fixtures; parsers for NEGOTIATE, SESSION_SETUP (more-processing with CHALLENGE and target info; success with IS_NULL and with IS_GUEST; logon failure), TREE_CONNECT (with and without ENCRYPT_DATA), CREATE and READ; DCERPC bind_ack and a `NetrShareEnumAll` level 1 response, including a fragmented one and a truncated one; `SmbReadOnlyGuard` rejects every write-capable access mask, every disposition but FILE_OPEN, and WRITE to any file id but the pipe's; an end-to-end run against a loopback `ServerSocket` replaying fixtures for anonymous allowed, guest only, both refused, signing required and encryption required.
- SNMP: the GETNEXT builder; parsing of IpAddress, Counter32, Gauge32, TimeTicks and the three exceptions; walk termination on subtree exit, endOfMibView, an error status and the 400-request cap; lockstep table assembly; MAC, speed and uptime formatting; one retry then the step ends; mismatched request ids ignored; a loopback `DatagramSocket` agent script end to end.
- `ToolRunner` with fake tools and a fake repository: one run per host (`HostBusy`), `TooMany` at 4, `NoNetwork`; status transitions; Stop ends a fake tool blocked in `CancellableIo.closing` within 1 s and writes `STOPPED` with the stopped summary; a network identity change stops every run; a throwing tool gives `FAILED`, "Failed: <reason>"; progress is published at most every 200 ms under virtual time; findings are forwarded as they arrive; `onFinished` gets the host id and is skipped for a `FAILED` run with no findings.
- Binding propagation: with `BoundNetwork(fakeBinder)` on the context, every socket each tool opens (HTTP, TLS, SMB, the SNMP datagram) is passed to the binder; a binder that throws makes the run `FAILED`, "Tool skipped: could not bind to Wi-Fi", and no unbound socket is opened.
- Identification: `DeviceIdentifier` with each `toolHint` seed rule (one fixture per row); `HostEvidenceBuilder` passes hints; reasons read `toolHint: ...`; the discovery and port-scan paths pass `hintsFor` (a hint survives a later port scan).
- Copy table: `CopyTableTest` templates for section 9 with a state matrix over every `ToolCopy` and `WordlistCopy` helper (progress with and without a total and errors, every summary, plurals, history lines, sizes, durations, export lines).
- Export text for each tool, including "Finished: not finished", the brute-force Options line and the absence of hints.

### 12.2 Instrumented tests

- Migration (the three cases in 7.3): 2 to 3 with validation, 1 to 3 in one chain, and the migrated database opened by the real builder.
- DAO: insert plus prune keeps exactly the newest 10 per host and tool and leaves other tools and hosts alone; deleting a run cascades to its findings; a raw `DELETE FROM hosts` cascades to runs and findings; `markInterrupted`; `hints` returns only the newest finished run per tool; `findings` excludes hints; `snapshot` then a 3A host delete then `restore` gives identical runs and findings with the same ids.
- Compose (`DetailContent` and the new content functions with fake state):
  1. Tools block collapsed by default with "N available" and the newest history line; it expands and collapses; chips fire the right lambda; "Results" shows only when a run exists; chips are disabled while busy.
  2. The pinned bar during a run shows the tool label, the progress text and Stop; Stop fires the stop lambda; the profile control is disabled.
  3. The port sheet shows "Run tool" rows only for applicable tools.
  4. Options sheet: defaults shown; invalid extensions and status codes show their messages and disable Run; the footer and Run pill stay visible with the body scrolled to the end at font scale 1.3; "Manage wordlists" fires its lambda.
  5. Results sheet: "Open with..." only when a URL exists, "Copy" always, each target at least 48 dp; run switcher bounds; both empty states; Share disabled while running.
  6. Wordlists screen: built-in rows have no Delete; an imported row confirms before delete; the import pill is outside the scrolling list.
  7. Settings shows the Tools section and the Wordlists row.
  8. The font scale 1.3 acceptance of 8.8.
  9. 3A's Detail overflow: "Remove device" disabled during a tool run.

### 12.3 Deferred on-device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, for his return)

Not run while the Director is away. Results go in the QA notes with times.

1. Install the build over the 3A build. Existing hosts, ports, notes, statuses and overrides survive the migration.
2. CHNAS Synology, port 5000: directory brute-force with common-short and defaults. Known DSM paths appear (for example `/webman`), catch-all noise is filtered, and Stop mid-run ends it within about a second. A second run with admin-panels finishes. Record times against 5.6.
3. CHNAS: HTTP fingerprint on 5000 names Synology DSM, and the identity line shows Synology NAS with a `toolHint` reason. TLS certificate on 5001 shows subject, SANs, expiry and the self-signed flag if it applies.
4. CHNAS: SMB shares. Shares are listed and the anonymous and guest results recorded; shares marked "read" match what the Director expects; nothing was written (share modification times unchanged).
5. CHNAS or the UniFi gateway, if either answered SNMP in discovery: SNMP walk lists interfaces and addresses.
6. Pi-hole admin: HTTP fingerprint names Pi-hole; a brute-force with admin-panels finds `/admin`.
7. UniFi gateway: TLS certificate on 443; HTTP fingerprint names UniFi OS; a brute-force with api-paths finishes with its catch-all redirect filtered.
8. Hue bridge (off-subnet at `10.0.4.20`, extended discovery on): HTTP fingerprint names Philips Hue; the run is bound to Wi-Fi (it works with a VPN running, if the Director uses one).
9. Import a wordlist the Director already has on the phone; check the name, entry count and date; run with it; delete it and check the saved options fall back with the notice.
10. Leave the Device screen during a brute-force and come back: the run continued. Switch Wi-Fi mid-run: it stops with "Network changed, run stopped".
11. Results: "Open with..." on a found path opens the browser chooser; "Copy" copies the URL; "Share" produces the text of 7.5.
12. Force-stop the app mid-run and relaunch: the run shows "Interrupted" with its findings.
13. Remove a device with tool history, tap Undo: the history is back. Remove it again and let the Undo lapse: the history is gone.
14. Font scale 1.3: the Device screen budget (8.8) and the options sheet footer.

Definition of done: 12.1 and 12.2 pass, and 12.3 is recorded once the Director is back.

## 13. Assumptions for the Director

Made by the controller and Ada while the Director is away. Each can be overturned before the plan is executed.

1. Schema: this phase is version 3 (`MIGRATION_2_3`), after 3A's 1 to 2 (7.3).
2. Remove device and Forget this network (3A) also snapshot and restore tool history, so Undo brings it back; both are disabled while a tool runs on an affected host (7.4).
3. Tool runs keep going when the Director leaves the Device screen, and stop on Stop, a network change or app death. The screen stays on while a run is active. Port scans keep their current behaviour (4.3).
4. A port scan and a tool run cannot run on the same host at once: one pinned bar, one Stop. Up to four hosts can run tools at the same time (8.2).
5. SNMP walk is offered only for hosts that answered SNMP during discovery, and only with community `public` (6.4).
6. SMB shares tries an anonymous session and a `guest` session with an empty password, checks read access by opening each share root only, and never writes. SMB 3.1.1-only servers are not supported (6.3).
7. Directory brute-force falls back from HEAD to GET on 405 or 501, hides responses matching a random-path baseline (with echoed paths and catch-all redirects accounted for), stops at 500 findings, and always sends the IP as the Host header (5.2, 5.3).
8. Only directory brute-force has an options sheet; the other four tools run at once with fixed defaults (section 6).
9. The Tools block sits collapsed at the top of the Device screen's list, which costs one visible port row at font scale 1.3 (8.1, 8.8).
10. The bundled wordlists are written from memory by the implementer. A SecLists subset (for example `Discovery/Web-Content/common.txt` and `raft-small-directories.txt`) could be bundled later if the Director approves the download.
11. Imports accept `text/plain` documents only, up to 10 MB and 200,000 entries (5.5.2).
12. Tool results feed device identification through hint strings and new `toolHint` rules, written with 3A's not-weaker rule; tools never write host or port columns (4.7).
13. The brief described `HttpTitle` as a raw-socket client; it uses `HttpURLConnection`. It stays as it is, and the new `HttpClient` is raw-socket as the brief intended (4.5).

## 14. Open items

1. Virtual-host brute-forcing (a Host header field in the options sheet): left out (2.2); cheap to add if client sites need it.
2. HTTP keep-alive for brute-force speed over HTTPS: measure first (12.3 item 2) and add only if HTTPS runs are too slow.
3. Some file managers report `.lst` or extensionless wordlists as `application/octet-stream`, which the `text/plain` filter hides. Widening to `*/*` is a one-line change if the Director hits this.
4. SMB: listing a readable share's top-level directory, and SMB1 detection (an informational exposure flag on the MVP roadmap).
5. An "Add to manual ranges" action on SNMP interface addresses, linking the walk to extended discovery.
6. A SecLists subset (13.10) needs the Director's approval of the download before any agent fetches it.
