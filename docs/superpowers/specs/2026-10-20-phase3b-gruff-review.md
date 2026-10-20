# Gruff review: Phase 3B follow-up tools design

Date: 2026-10-20
Reviewer: Gruff (Devil's Advocate, partial veto)
Spec under review: `2026-10-20-phase3b-followup-tools-design.md` (commit c5fedb3)
Read against: the 3A polish spec (migration 1 to 2, `appScope`, Remove device and Forget this network with Undo, `setIdentityIfNotWeaker`), the MVP spec, the extended discovery spec (binding rules), and the code on master: `engine/common/Probe.kt`, `SocketBinder.kt`, `engine/serviceid/TlsPeek.kt`, `engine/discovery/Snmp.kt` and `HttpTitle.kt`, `data/Daos.kt`, `data/Repositories.kt`, `ui/home/ScanBinding.kt`, `ui/home/HomeViewModel.kt`, `ui/detail/DetailViewModel.kt`.

## Verdict

**Proceed after fixes, with one partial veto.**

The framework is sound: `Tool` plus `ToolRunner` on `appScope`, sockets through `Probe.connect` and `ScanBinding.run`, close-on-cancel for reads, findings persisted as they arrive, a pure mapper for certificates, and hints routed through 3A's not-weaker write. The test list is unusually concrete. But seven things must change in the spec before a plan is written, and I veto SMB share enumeration (6.3 steps 5 and 6) in this phase: it moves to its own plan, and 3B ships the SMB session probe only (M6).

Tags: MAJOR (must fix before planning), MINOR (fix in the spec or the plan), QUESTION (the Director or controller decides).

## MAJOR findings (priority order)

### M1. Runs outlive the Device screen, but nothing outside it knows (3, 4.3, 8.2, 10)

The spec makes runs survive leaving the Device screen and allows four at once, then gives them no presence anywhere else.

Scenarios:
1. The Director starts brute-forces on two hosts and goes back to Home. Home shows nothing running. The only way to stop them is to find and open each device. The keep-screen-on flag is the only sign.
2. On Home he taps Quick scan all. `HomeViewModel.quickScanAllOn` port-scans every host with its own `ScanController` and knows nothing of `ToolRunner`. A Quick port scan and a brute-force now hit the same host at once. That breaks the decision in section 3 that port scans and tools are mutually exclusive, which is enforced only by the Device ViewModel (4.3 rule 1). A slow device under both loads gives timeouts in both.
3. The run ends while he is on Home or Settings. "<tool name> finished: <summary>" is a Device screen snackbar, so it is lost.
4. 7.4 disables Forget this network "while any host of that network has" a run, so Home needs the active set anyway. The spec gives Home no state for it.

Fix:
- Add one app-level `HostBusy` registry in `AppContainer`. The Detail port scan, Quick scan all and `ToolRunner` all claim a host there and release it in `finally`. `ToolRunner.start` returns `HostBusy` when a port scan holds the host. Quick scan all skips hosts with an active tool run and counts them in its existing end message, for example "Quick scan finished, 1 device skipped: a tool is running". Add that string to section 9.
- Home: each host row with an active run shows a running marker using the existing scanning row treatment. The notice line shows "N tools running" when no other notice is up.
- End-of-run messages go to an app-level `MutableSharedFlow<String>` that whichever screen is visible collects (Home's notice line, the Device snackbar).
- Tests: Quick scan all skips a busy host; a tool start is refused while Quick scan all holds the host; the end message appears on Home.

### M2. Background behaviour is undefined, and the freezer turns it into false failures (2.2, 4.3, 11.11, 8.5)

2.2 rules out a foreground service, and nothing says what happens when NetProbe leaves the foreground. The results sheet itself sends him away: "Open with..." on a found path opens the browser in the middle of a run. On the Pixel 7 Pro (Android 14), the cached-apps freezer freezes a backgrounded process within about 10 s, whatever the targetSdk.

Scenario: a 4,614-entry HTTPS brute-force is running. He taps "Open with..." on `/admin`, reads the page for a minute and comes back. While frozen, every in-flight read passed its `soTimeout`. On thaw the workers see a burst of `Timeout`s, retry, and after 50 in a row the run ends `FAILED`, "The server stopped answering after N requests". That is untrue. The progress line's elapsed time also includes the frozen minute.

Fix: define the background rule and test it with a fake lifecycle. My recommendation is pause and resume:
- `ToolRunner` observes `ProcessLifecycleOwner` (lifecycle-process is already in the AndroidX set; check it is on the classpath without a new dependency, otherwise use an `ActivityLifecycleCallbacks` counter).
- On `ON_STOP` it suspends the producer and lets in-flight requests finish.
- Failures that span a background period are not counted towards `MAX_CONSECUTIVE_FAILURES`.
- On `ON_START` it resumes.

The simpler alternative is to stop every run on `ON_STOP` with the summary "Stopped when NetProbe went to the background". Either way, write it in 4.3 rule 4 and in section 9. Add a 12.3 item: open a found path in the browser mid-run, return after 60 s, and the run continues or reads as stopped, never `FAILED`.

### M3. No ceiling on request rate per host, and concurrency does not bound it (2.2, 3, 5.1, 5.2)

2.2 says concurrency is the only throttle. But with `Connection: close`, every request is a new TCP connection, plus a TLS handshake for HTTPS. Against a fast LAN server answering 404 in 5 ms, 8 workers make about 1,600 new connections a second.

Scenarios:
- The Hue bridge, a printer's embedded server or an lwIP device has a tiny connection table and a slow CPU. It stalls at 8 parallel connects. Timeouts are retried after 250 ms, which adds load at exactly the wrong moment. The run aborts at 50 consecutive failures, or the device stops answering its real clients.
- On a client assessment, freezing a client's printer or a PLC's web panel is real harm.
- Single-threaded servers queue the 8 connections, so a 3 s timeout fails requests that would have succeeded one at a time.

Fix: add two run-wide controls, both testable with a fake clock:
- Adaptive concurrency (AIMD). Start at the chosen setting. Halve the active worker count (minimum 1) on any `Timeout` or `Reset`. Add one worker back per 20 consecutive successes, never above the setting.
- A hard per-host token bucket: `MAX_REQUESTS_PER_SECOND = 100`, as a constant and not an option.

Then strike "Request rate limiting" from 2.2 and record the change in section 13. Retries should wait until the worker count has dropped, not fire straight back. Test: a fake server that times out above 2 concurrent requests completes the run with zero errors once the workers have backed off.

### M4. One baseline sample is too thin for the not-found filter (5.2, 5.3)

The filter hides a response only when its status matches the single random-path baseline and its normalised size is equal to it exactly.

Scenarios:
1. Many catch-all pages embed a CSRF token, a nonce, a timestamp or a request id. This is common for SPA shells like Home Assistant, Grafana and UniFi OS, and for router UIs. Sizes then differ by a few bytes on every request, nothing is hidden, and every word is a "hit" until "Stopped at 500 findings" with status `DONE`. The success criterion "soft-404 noise filtered" fails, and the history line looks like a successful run.
2. HEAD then GET only falls back on 405 and 501. Plenty of embedded and framework servers answer HEAD with 404, 400 or a blanket 200 while GET differs. HEAD 404 is never in the report set, so the default method silently reports "0 found in 312 requests": a false clean result.
3. With extensions set, `.php` paths often go to a different handler than bare words, so the one bare-path baseline does not describe them. 3 rejects a per-extension baseline as "a rare case". It is not rare on PHP stacks.

Fix: replace the single baseline with a calibration step of at most five requests:
- GET two random bare paths and, when extensions are set, one random path with the first extension.
- HEAD and GET `/`.

Then:
- If the two bare baselines differ in size, accept matches within a band of `max(2 x |difference|, 32 bytes)`.
- Keep a separate baseline for extension paths.
- If HEAD and GET of `/` or of a baseline path disagree on status, switch the run to GET_ONLY. Record that in `optionsJson` and the Options export line.

Add a runaway check: if more than 90 percent of the first 100 completed paths are reported, end the run `DONE` with "Most paths answer alike, results not filtered" (factual, not a warning). Tests: a nonce-varying catch-all is filtered; a HEAD-404/GET-200 server is found under the default method; the runaway rule fires.

### M5. Hints are keyed by tool, not by tool and port, so identity evidence is lost (4.7, 7.2)

`hints(hostId)` takes the newest finished run per `toolId`. The CHNAS has web ports 5000 and 5001, and the UniFi gateway has 80 and 443.

Scenario: a fingerprint of 5000 emits `http.framework=Synology DSM`. Later a fingerprint of 80 (a redirect stub, no framework) becomes the newest HTTP fingerprint run. `hintsFor` now returns no Synology hint. The next discovery calls `HomeViewModel` line 195, which still calls `setIdentity`, not not-weaker. That re-identifies without the hint, and the identity drops back.

The same happens when a `STOPPED` partial run supersedes a complete `DONE` run of the same tool, or when TLS on 8443 replaces TLS on 443.

Fix:
- Add `port INTEGER NOT NULL` to `tool_runs`. The schema is still unwritten, so this is free. Select hints from the newest qualifying run per (`toolId`, `port`), and prefer `DONE` over `STOPPED` and `INTERRUPTED` when the newest is partial. The column also replaces parsing `optionsJson` for the history line.
- State in 4.7 that every identity write that passes hints, discovery's included, goes through `setIdentityIfNotWeaker`. Otherwise say why discovery may lower an identity.
- Test: fingerprint 5000 (Synology), then fingerprint 80 (none), then a discovery: the identity is still Synology NAS.

There is no feedback loop: `onFinished` re-identifies but never starts a scan, and port-scan re-identification reads hints but never starts a tool. The spec should say so in one sentence.

### M6. SMB: share enumeration is not bounded enough to plan. Partial veto on 6.3 steps 5 and 6 in this phase

The spec admits this is the least certain item, but it leaves out the decisions that decide interop.

Windows 11 behaviour today:
- 24H2 requires signing.
- Insecure guest logons are blocked and the Guest account is disabled.
- `RestrictNullSessAccess` lets an anonymous session set up and connect to `IPC$`, then denies the `srvsvc` open or `NetrShareEnumAll` with `ACCESS_DENIED`.

Gaps:
1. **Anonymous allowed, listing denied.** Step 5 uses "the first allowed session (anonymous preferred)" and never falls back to guest. Samba with `restrict anonymous = 2` and `map to guest = bad user` is a common NAS setup: anonymous sets up, listing is denied, guest would have listed everything. The spec reports nothing for this, and has no finding or summary for "share list refused".
2. **Signing detection is vague.** "A request refused after session setup because the server requires signing" does not say which status. `ACCESS_DENIED` is ambiguous. Make it deterministic: if NEGOTIATE says signing is required and SessionFlags has neither IS_NULL nor IS_GUEST, send no unsigned request and report "Signing required, shares not listed". Anonymous and guest sessions are not signed under MS-SMB2, so they may proceed.
3. **Async and partial responses.**
   - Named-pipe READs can return an interim `STATUS_PENDING` (ASYNC flag) before the real reply.
   - A pipe READ shorter than the RPC response returns `STATUS_BUFFER_OVERFLOW` (0x80000005), which means more data, not failure.

   As written, the client misreads both as errors.
4. **Builder rules left implicit.** These are where hand-written clients break against Samba versus Windows:
   - The NTLM NEGOTIATE flags (UNICODE, NTLM, EXTENDED_SESSIONSECURITY, ANONYMOUS on the anonymous leg).
   - The SPNEGO `NegTokenResp` wrapping of AUTHENTICATE.
   - CreditRequest and MessageId sequencing, with reads at most 65,536 bytes so CreditCharge stays 1.
   - The NDR shape of `NetrShareEnumAll`: unique-pointer server name, level 1 with a null buffer, `PreferedMaximumLength` 0xFFFFFFFF, resume handle.
5. **Circular tests.** The "hand-built byte fixtures" are written by the same implementer from the same reading of the specs. Client and fixture will share every misunderstanding, and the suite goes green while nothing works (the Director's standing "green is not working" rule).

Fix: split SMB in two.
- **3B ships "SMB session"**: steps 1 to 4 only. That covers NEGOTIATE, anonymous and guest SESSION_SETUP, and the CHALLENGE target info: dialect, signing, session results, NetBIOS and DNS names, domain and OS build. That is most of the identification value for about a third of the protocol. The NDR and DCERPC code and `SmbReadOnlyGuard`'s write paths are not needed yet, and nothing past SESSION_SETUP is sent.
- **Share enumeration and read checks become their own plan (3C)**. It includes points 1 to 4 written into the spec. It also needs a fixture-capture task: a JVM harness, kept out of CI, run from the dev PC against the PC's own Windows 10 SMB server on `localhost:445` (read-only, no download), recording real byte traces as fixtures. Add a table of expected results for Windows 11 24H2, Windows 10 and Synology DSM Samba, so the 12.3 check has something to compare against.
- If the controller rejects the split, points 1 to 5 become MAJOR blockers on 6.3 as written.

### M7. `CancellableIo` funnels every tool read onto the shared 64-thread IO pool (4.6, 5.2)

4.6 runs every blocking read with `runInterruptible(Dispatchers.IO)`. Four brute-forces at concurrency 16 hold 64 blocked threads, which is the whole default `Dispatchers.IO` pool. `Probe.connect`'s own comment warns against exactly this.

Scenario: during those runs the Director starts a radar scan. Discovery enrichment queues behind the tools and times out, so those steps silently miss and give wrong identities, not slow ones. Those steps are `Snmp.query`, `NetBios`, `HttpTitle`, `DeviceInfoTxt` and `TlsPeek.certNames`, all on `Dispatchers.IO`. Wordlist import and Room's coroutine callers wait too.

Fix: `CancellableIo.closing` calls `runInterruptible` without a dispatcher argument, so it inherits the caller's dispatcher as `Probe.connect` does. Each tool runs on its own `Dispatchers.IO.limitedParallelism(n)` view (elastic views are not capped by the 64 limit). Test: with 64 blocked fake reads in tools, a `Dispatchers.IO` task still completes promptly.

## MINOR findings

1. **4.3 rule 5, `markInterrupted` ordering.** "Runs once when `AppContainer` creates the repository" is suspend work. If it runs on `appScope` asynchronously, a run started in the first moments is written `RUNNING` and then marked `INTERRUPTED` under its live job. Fix: `ToolRunner.start` awaits a `ready: Deferred<Unit>` completed by `markInterrupted`, or `markInterrupted` runs blocking during container setup. Test the race.

2. **7.4, the Forget this network snapshot can be huge.** The bound is 50 runs per host times 500 findings. A client site with 30 web hosts each brute-forced ten times holds about 150,000 finding rows in memory for the Undo, tens of MB on the heap: an OOM risk on Forget. Fix: snapshot the tool rows into SQLite `TEMP` tables (`CREATE TEMP TABLE ... AS SELECT`) on the same connection, and restore with `INSERT ... SELECT`. Temp tables need no schema or migration and die with the process, which matches 3A's memory-only lifetime. Keep hosts and ports in memory as 3A specifies.

3. **7.4, guard the cascade.** These are the schema's first foreign keys. A future `@Insert(onConflict = REPLACE)` or `@Upsert` on `hosts` would delete and reinsert the row, and the cascade would wipe that host's tool history on every discovery. No host write uses REPLACE today (checked `Daos.kt`). Add the rule to 7.4 and a DAO test: `upsertDiscovered` on an existing host leaves its tool runs intact.

4. **7.3, remove the "3B before 3A" contingency.** 3B depends on 3A's `appScope`, `PortUris`, `setIdentityIfNotWeaker`, the undo snapshots, the Detail overflow and `PortSheet`, so it cannot be built first. The renumbering note invites a schema mix-up for no benefit. State that 3A is a hard prerequisite.

5. **4.5, HttpClient framing rules.**
   - 1xx, 204 and 304 responses have no body. Skip interim `100` responses.
   - Accept bare LF line endings in the status line and headers. Many embedded servers send them.
   - Servers that ignore `Connection: close` are handled by `Content-Length` and chunked framing, but a body with neither must be bounded by the overall deadline. Say so.

   Add each to the 12.1 list.

6. **5.5.3, `open(id): Sequence<String>?` leaks the reader** when a run is stopped mid-list, because a lazily read file sequence cannot close itself. Fix: `suspend fun <T> withEntries(id, block: (Sequence<String>) -> T): T?` with `use`. Also state that deleting a list during a run is safe, because the open descriptor keeps reading.

7. **5.5.1, the bundled lists are authored from memory.** Originality cannot be tested. Instruct the implementer to compose by category from the spec's examples and their own knowledge of device paths, sort alphabetically within each category, and never reproduce any published list's ordering or headers. The plan's reviewer checks this. Common single words such as `admin` are not anyone's content, and SecLists is MIT-licensed, but the no-copy and no-download rule stands as written (13.10).

8. **5.5.2, import size.** `OpenableColumns.SIZE` can be null, so enforce the 10 MB limit by counting bytes read and abort at the limit. Latin-1 lists lose their non-UTF-8 lines as "skipped". That is acceptable, but the snackbar already reports the skipped count, so say so in 11.

9. **5.2 and 5.4, "requests" counts paths, not requests.** HEAD then GET, the retries and the calibration all send more than `entries x (1 + extensions)`. Either call the unit "paths" in the progress, summary and export strings, or count real requests. Pick one so "7 found in 4614 requests" is true.

10. **5.6, HTTPS timings assume session resumption.** Many embedded TLS stacks do not resume. With full RSA handshakes at 200 to 500 ms on a bridge or printer, and HEAD then GET doubling the handshakes, a 4,614-entry HTTPS run can take more than 10 minutes. Note this in 5.6. Consider GET_ONLY as the HTTPS default, since one GET with a 64 KB cap costs less than two handshakes.

11. **4.4 and 6.2, old TLS needs cipher suites, not only protocols.** Enable `supportedCipherSuites` as well (RSA key exchange, 3DES), wrapped so an unsupported protocol or suite never throws. `CERT_KEY` should name Ed25519, Ed448 and DSA (`DSAPublicKey.params.p.bitLength()`) as well as RSA and EC. All are in `java.security`, so no dependency.

12. **6.4, unsigned SNMP values.**
    - `Ber.Reader.int()` is a signed `Int` and requires tag 0x02. Counter32, Gauge32 and TimeTicks must read as unsigned 32-bit into `Long`, or uptime and speeds go negative.
    - `ifSpeed` saturates at 4294967295 on 10G links: then GET `ifHighSpeed.<index>` (`1.3.6.1.2.1.31.1.1.1.15`).
    - Lockstep table assembly must key rows by the index suffix of each OID, not by position, because columns can be sparse.

13. **6.1, the redirect hop is under-specified.** UniFi on 80 and Synology on 5000 redirect to a different scheme and port on the same IP. Define "same host" as the same IP with any scheme and port, set `tls` from the Location scheme, and name the hop in the `STATUS` detail.

14. **4.7, `hint-unifi` misfires.** The `unifi` fingerprint matches any title containing "UniFi", so a self-hosted UniFi Network Application on a Linux server becomes "UniFi OS" and then a ROUTER at weight 70. Fix: title pattern `^UniFi OS$`, or a body marker specific to the console, and a negative fixture for "UniFi Network".

15. **8.5, the results sheet versus pruning.** A new run prunes the eleventh, which can be the run an open sheet shows, and "Run N of M" shifts under the reader. The sheet should hold its `runId` and close if that run is pruned, as 11.14 does for a removed host.

16. **8.2, the elapsed time needs a ticker.** "12 s" in the progress text changes only when `active` is published, which happens only on updates. A run blocked on a slow request freezes the clock. Add a 1 s ticker in the UI state, or publish on a timer.

## QUESTIONS

1. **Host header and SNI (5.2, 6.1, 6.2, 13.7).** Homelab reverse proxies (Caddy, Traefik, Nginx Proxy Manager) answer an IP Host with their default site, and present a default certificate without SNI. The TLS tool would then show "TRAEFIK DEFAULT CERT". The host row already knows `mdnsName` and `reverseDnsName`. Should the TLS and fingerprint tools send the known name as SNI and Host when one exists (no new UI), with the brute-force staying on the IP?

2. **SNMP gate (4.2, 6.4).** The gate depends on discovery's SNMP toggle and a single 1 s, unretried UDP probe. With SNMP off in Settings, or one lost datagram, the walk is never offered. The rejected alternative costs 2 s ("No SNMP answer to public"). Offer the walk on every host, or keep the gate?

3. **Tools during a radar scan.** Should tool starts be allowed while Home's discovery is sweeping the same network? They add load that can make discovery miss slow hosts. My lean is to allow it and rely on M3's ceiling, but the Director should choose.

4. **Fixture capture for SMB (M6).** Is running a read-only capture harness against the dev PC's own SMB server acceptable without the Director? Against CHNAS, it should wait for him.

## Standing rules check

- No new dependencies: holds (raw sockets, `java.security`, hand-written MD4 and BER). Confirm lifecycle-process for M2 is already on the classpath, or use an `ActivityLifecycleCallbacks` counter.
- No downloads: holds. Wordlists are authored, fixtures come from local `keytool`, and SecLists is deferred to the Director (13.10).
- No em-dashes: none in the spec.
- No disclaimers or prompts in UI copy: holds. M2, M4 and M1 add factual strings only.
- Stop pill only, and Scan, Stop and Run outside scroll containers: holds (8.2, 8.4 footer, 8.6 pinned import).
- targetSdk 30: holds. SAF `OpenDocument` needs no permission.

## Testability

Strong overall: loopback `ServerSocket`, `SSLServerSocket` and `DatagramSocket` fakes for every tool, a binder recording fake, a migration chain test, and Compose tests for both sheets and the Wordlists screen. Gaps to add:
- the lifecycle pause or stop (M2);
- the Quick scan all exclusion (M1);
- the rate ceiling under a fake clock (M3);
- the calibration cases (M4);
- the per-port hints (M5);
- IO pool starvation (M7);
- the `markInterrupted` race (minor 1);
- the REPLACE guard (minor 3).

The deferred checklist in 12.3 should gain the M2 browser round trip and the SMB expected-results comparison.

## Scope and plan ordering

1. **3A first, as its own plan.** 3B depends on its `appScope`, schema 2, `PortUris`, `setIdentityIfNotWeaker`, Remove device and Forget this network snapshots, the Detail overflow and the `PortSheet` order. Do not merge: 3A is already large, and a merged plan puts two migrations and two undo changes in one review.
2. **3B as one plan.** It covers the framework, `CancellableIo`, `HttpClient`, directory brute-force with the wordlists and Wordlists screen, HTTP fingerprint, TLS certificate, SNMP walk, the SMB session probe (M6), schema 2 to 3 (with the `port` column from M5) and the 3A snapshot extension. Land the framework and `HttpClient` first, then brute-force, then the three companions, then SMB session last with its own review.
3. **3C: SMB share enumeration and read checks**, with the fixture capture task and the fixes from M6 points 1 to 4.
