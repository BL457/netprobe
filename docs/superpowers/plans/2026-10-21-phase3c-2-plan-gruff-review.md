# NetProbe Phase 3C-2 plan: Gruff review

Date: 2026-10-21. Reviewer: Gruff (Devil's Advocate, partial veto). Plan: `docs/superpowers/plans/2026-10-21-netprobe-phase3c-2.md` at 7addb1a (5225 lines, 12 tasks), read in full. I checked it against the binding spec (`2026-10-21-phase3c-feedback-design.md` at 33a6970, sections 3, 4, 6, 7.1, 8), its Gruff review, the controller rulings, the 3C-1 plan (82084d3) for every consumed name and anchor, and the 3C-1 plan's Gruff review for the standard. Code read at 7addb1a: `data/` (Entities, Daos, Repositories, NetworkSignature, Settings), `app/schemas/.../1.json` and `3.json`, `engine/tools/ToolRunner.kt`, `HostBusyRegistry.kt`, `AppMessages.kt`, `ToolId.kt`, `ToolApplicability.kt`, `engine/common/` (SocketBinder, Probe, CancellableIo), `engine/discovery/` (UpnpDescription, Ssdp, Mdns, DiscoveryEngine canary path, CandidateRanges), `netenv/` (NetworkEnvMonitor, Ipv4, Subnet), `ui/home/` (HomeViewModel, RadarCollector, ScanBinding, HomeComponents), `UndoStore.kt`, `AppContainer.kt`, `MainActivity.kt`, the test helpers the plan uses, and the uncommitted 3B QA fix F4 in the working tree.

## Verdict

2 BLOCKING, 17 non-blocking (8 SHOULD FIX, 9 MINOR). This is a strong plan. The schema change is genuinely additive: I checked both migration seeds column by column against `1.json` and `3.json`, and the SQL test, the schema-export test and the Director-copy test each fail on the bug they name. The hidden-row design holds up when traced through manual-first, scan-first, heard-first and the insert race. In every order there is one row, the tool history stays with it, and `updateScan` is the only promoter. The routing is correct: the OTHER path inherits no `BoundNetwork` (the runner launches on `appScope`), every tool socket goes through `currentBinder()`, and a Wi-Fi route with no binder fails closed. The listener's network tagging is stricter than the spec, and rightly so. Two things must change before execution. (1) Task 1's instrumented test calls `HostDao.allForNetwork`, which only exists from Task 2, so Task 1's own gate cannot go green. (2) Task 7 is knowingly written against the pre-F4 results-sheet shape and leaves "the controller rebases" as an open step, which breaks the self-contained-task rule. Task 1 Step 0 will stop on F4's files regardless. The rest is hardening: the listener's write window around the description fetch, a reader that can die silently, coexistence with the system mDNS daemon on 5353, a spec amendment for the UPnP fetch, a few QA gaps, and two client-network questions only the Director can answer.

## Findings

### G1. BLOCKING. Task 1 cannot compile its own instrumented test

- Location: Task 1 Step 6, `Migration34Test.schema3RowsSurviveWithManualOnlyZeroAndRouteNull` (plan lines 411 to 412), Step 6 compile (506 to 509), Step 7 gate (511 to 516); `allForNetwork` is introduced in Task 2 Step 3 (line 739).
- Problem: line 412 calls `room.hosts().allForNetwork(1)`, and that DAO method does not exist until Task 2. `:app:compileDebugAndroidTestKotlin` fails with an unresolved reference, so Task 1's gate fails and its commit step is never reached. Line 411 also asserts that `forNetwork` hides the manual row, which is behaviour Task 2 adds. It compiles, but in Task 1 it would describe code that does not exist yet.
- Evidence: `Daos.kt` line 26 at HEAD has only `forNetwork`. Grep shows no `allForNetwork` anywhere in `app/src`.
- Resolution: in Task 1 keep the raw-SQL half of the test (seed, migrate, the `manualOnly`, `route`, findings and ports checks, the two new-column inserts, and `NetProbeDb.open` with a `SELECT COUNT(*)`). Move the two Room-flow assertions (lines 411 to 412) into Task 2, either as one more test in `ManualHostDaoTest` against an in-memory database or as a Task 2 edit to `Migration34Test`. The `runsForHost(...).map { it.route }` line can stay, because `route` exists after Task 1.

### G2. BLOCKING. Task 7 (and the Step 0 list) depend on an unresolved F4 rebase

- Location: Task 7 interface note (line 2151), `ManualToolsViewModel.state` (2534 to 2544), the sheet call (2778 to 2780); Task 6 Step 3 (2096 to 2100); Task 1 Step 0 `comm` line (150 to 157); self-review note 18 (163).
- Problem: the 3B QA fix F4 is in the working tree now. It moves the Device screen's per-tick runner progress out of its structural state (`structuralActive`, a separate `toolBar` flow), adds `liveStatus: (() -> String)?` to `ToolResultsSheet`, and adds `ui/LiveLine.kt`. F4 exists because a screen that recomposes on every 200 ms runner tick was a QA defect. Task 7 combines `c.toolRunner.active` straight into `ManualUiState` (line 2534), so the new screen repeats the defect for both its progress text and its open results sheet. The plan says "the controller rebases this task" if F4 lands, which is a contingency inside a task, and the hard rule is self-contained tasks. The plan's names are also wrong: F4's file is `ui/LiveLine.kt` and its composable is `LiveLine`, not `LiveText` (lines 163 and 2100). Separately, Step 0's `comm` line will always print something: `engine/tools/dirbrute/Calibration.kt` (c6b88cc, already committed after 82084d3) and F4's `ui/tools/ToolResultsSheet.kt`, `ToolsUiState.kt`, `ToolsBlock.kt`, `ui/detail/Detail{Components,Content,Screen}.kt` and `ui/LiveLine.kt`. A guaranteed STOP with no pre-agreed answer stalls Task 1.
- Evidence: `git diff` of `ToolResultsSheet.kt` shows the `liveStatus` parameter and the `if (liveStatus != null) LiveLine(...) else Text(...)` pair. `DetailViewModel.kt` shows `structuralActive` and `toolBar`. `git status` shows `?? app/src/main/java/.../ui/LiveLine.kt`.
- Resolution: once F4 is committed (it must be before 3C-1 even branches), rewrite Task 7 against it. The view model emits a structural state keyed on `(runId, total == null)` for the started host, plus a separate progress flow. The pill's progress text is a `LiveLine`, and `ToolResultsSheet` gets `liveStatus` for an active run. Correct Task 6's note to `LiveLine`. In Task 1 Step 0, list the expected `comm` output (Calibration.kt and F4's files) with its disposition: Calibration.kt is irrelevant, and F4 is already absorbed by Tasks 6 and 7. Then only an unexpected file stops the task.

### G3. SHOULD FIX. The heard write is checked before a 2 s fetch and committed after it, and `hosts` has no foreign key to `networks`

- Location: `HeardWriter.accept` and `described` (plan lines 4096 to 4120), `ContainerHeardSink.store` (4238); `Entities.kt` line 22 (hosts has no `foreignKeys`).
- Problem: rules 1 and 2 (current network, Forget, scan, Undo) are read once at the top of `accept`, and then `describe` can block for up to 2 s before `store`. If a Forget is committed in that window (Home's cascade runs on `appScope`), `store` inserts a host whose `networkId` is a deleted network. No foreign key rejects it, and nothing ever deletes it: a permanent invisible row in the Director's database. A radar scan that starts in the window gets a heard write during the scan, which is what rule 2 exists to prevent. `store` also turns a missing network into `lastRunAt ?: 0` and writes anyway.
- Resolution: in `accept`, re-read `sink.context()` after `described(...)` and re-apply rules 1 and 2 before `store`. In `ContainerHeardSink.store`, `val net = c.networks.byId(networkId) ?: return false` and refuse when `net.forgotten != null`. Add one `HeardWriterTest` case: a fake `describe` that flips the context to `forgotten` or `scanActive` stores nothing.

### G4. SHOULD FIX. One unexpected exception ends the listener for the rest of the process, and the status line then misreports

- Location: `ListenController.start` (4811 to 4816), `listen` (4834 to 4843), `read` (4856 to 4862).
- Problem: `read` catches only `IOException`. Any other exception from `onPacket` (a parser path the authored tests did not reach, `withDerivedMac`, `MacExtract`) or from `writerFor` or `ContainerHeardSink`'s constructor fails the `coroutineScope`. It then propagates through `collectLatest` and kills the one `scope.launch` collector. `started` stays true, so nothing restarts it until the process dies. The `finally` sets `session = null`, so Settings reads "Not listening: connect to Wi-Fi or Ethernet" on a working Wi-Fi.
- Resolution: wrap each packet's `onPacket` in `try { } catch (ce: CancellationException) { throw ce } catch (e: Exception) { log(e) }`. Wrap `listen(key)` inside `collectLatest` the same way, so a failed session ends with a logged error and the next key change starts a new one. Add a `ListenControllerTest` case where the sink's `store` throws `IllegalStateException` once, the next packet is still written and the status stays `BOTH`.

### G5. SHOULD FIX. Listening on 5353 beside the system mDNS daemon is not checked for side effects

- Location: `MulticastListenSockets.open` (4727 to 4731), `ListenControllerTest` "the sockets stay open during a scan" (4602), Task 12 item 6 (5132); spec assumption A2.
- Problem: the scan's mDNS runs through `NsdManager` (`MdnsResolveQueue`), the system daemon on 5353. Binding a second `SO_REUSEADDR` socket to wildcard 5353 means Linux delivers each unicast datagram to that port to one socket only, normally the most recently bound. The system stack asks for unicast replies (QU) on early queries, so with listening on, our socket can take replies meant for the system daemon. That slows or thins the scan's own mDNS results, and other apps' Cast or AirPlay discovery while NetProbe is open. The plan only checks that the port opens (A2), not that nothing else degrades.
- Resolution: add to Task 12 item 6: run one radar scan with listening off and one with it on, back to back, and record the mDNS-sourced device count of each. A drop of more than one or two devices is a fail, not a note. If it fails, the fix is to close the mDNS socket while `radarScanActive` (the writer drops everything then anyway) and reopen it afterwards. Record that fallback in the plan now, so the result has a pre-agreed response.

### G6. SHOULD FIX. The binding spec still says the listener sends nothing

- Location: spec D11 (line 49, "listening sends nothing"), 3.1 (line 171, "no queries, no M-SEARCH, no description fetch"), section 6 (line 320, "UPnP description fetches from the listener" not in 3C), open item 3 (line 534); plan Global Constraints (44) and self-review gap 1 (146).
- Problem: the plan follows the controller ruling, and the ruling binds. But Dot's final review checks code against the spec, and the spec as written makes Task 10's `describe` a violation. Open item 3 was a question for the Director, and the rulings file does not say whether he answered it or the controller decided. Gap 2 (each session resolves its own row and writes only while Home's current network is that row) also goes beyond spec 3.2 and 3.4 rule 1, and gap 4 changes spec 4.3 step 4's "one transaction" wording.
- Resolution: one spec amendment commit before execution. D11 and 3.1 become "sends only one bounded UPnP description GET per heard IP inside the known ranges, bound to Wi-Fi". Section 6 drops the exclusion. Open item 3 records who decided it. 3.4 rule 1 adds the session-row rule, and 4.3 step 4 describes the two transactions plus the conditional delete. Gap 6 (all errors held until the first Run tap) and gap 10 (the process-wide removed set) go in section 9 as recorded deviations. Gaps 5 and 7 need no amendment: the resolutions are right and match spec 7.1 and 8.1 item 19.

### G7. SHOULD FIX. The manual start shortcut reintroduces the weakness gap 2 found

- Location: `ManualToolsViewModel.networkFor` (2503 to 2508); self-review gaps 2 and 3 (147 to 148).
- Problem: gap 2 explains that `identityKey` (gateway IP and prefix) cannot tell two sites with the same addressing apart. `networkFor` then accepts `c.currentNetwork` on exactly that key. At a client site that also uses 192.168.1.1/24, a Run tapped before Home re-resolves keys the hidden row and its runs on the Director's home network row. A later home scan can then promote that row, and the client device's tool history ends up on a home device.
- Resolution: always call `c.networks.ensure(e, GatewayMac.lookup(e.gateway))`. It is one transaction, it is MAC-aware, and the Run tap is not a hot path. Then check the forgotten id as now. Drop the `currentNetwork` shortcut.

### G8. SHOULD FIX. Device QA gaps

- Location: Task 12 Step 6 items 5, 6 and 8 (5124 to 5134).
- Problem and resolution, item by item:
  - Item 5: one `MAX(lastSeen)` reading cannot show that `lastSeen` moved. Read it before switching listening on and again after the 10 minutes. Every `probe` runs `am force-stop`, which kills the session (and resets its 32-row cap and removed set), so take no probe inside the 10-minute window.
  - Process death: add `am force-stop` with listening on, then `dumpsys wifi`: `netprobe-listen` must be gone. This is the only check of "released on process death".
  - Item 8: on a guest SSID with client isolation, the listener hears nothing, so "no guest device lands in the home inventory" passes without testing anything. Require at least one heard sighting on the disposable network (record it). Without one, record the item as "inconclusive, nothing heard", not as a pass.
  - Item 8 needs a second network. Without one, the cross-network rule cannot be shown on the device. Wi-Fi off and on (item 6) only proves the restart. The JVM tests named in the item are the right fallback. Say so explicitly and do not improvise.
  - Add the G5 scan-parity check to item 6.

### G9. SHOULD FIX (Director decision). Listening on a network nobody chose to scan

- Location: `AppContainer.listen` `resolve = { networks.ensure(...) }` (4919); spec 3.4 last paragraph ("on a network with no run yet a heard row has lastDiscoveryRun = 0"); Task 10 `describe` (4233 to 4236).
- Problem: the switch is global. Once the Director turns it on at home, opening NetProbe on a client's, hotel's or coworking Wi-Fi starts recording other tenants' devices (mDNS names such as "Sam's iPhone", up to 32 new rows a session). It also sends HTTP GETs to their devices' UPnP descriptions, all without a Scan tap. Sessions restart at every foreground return, so the 32-row cap is per visit, not per network. The radar scan does much the same, but only when he taps Scan. This is the part of 3C-2 most likely to look bad on a client network.
- Resolution: the Director's call (question 3). The cheap safe version: `resolve` returns null (status "Not listening", nothing held) unless the network row has `lastRunAt != null`, meaning at least one completed scan. Add a `ListenStatus` and copy line for it ("Scan this network once to start listening") and one `ListenControllerTest` case.

### G10. SHOULD FIX (Director decision). An OTHER route on a Wi-Fi with no internet goes out over mobile data

- Location: `ManualRoute.of` and `bind` (1307 to 1318); `NetworkEnvMonitor.current` (env is the Wi-Fi even when another network is the default).
- Problem: ruling 4 sends unbound so a VPN route works. When no VPN is up and the Wi-Fi is unvalidated (a client lab LAN with no internet, which is common), Android's default network is cellular. A typed private address outside the known ranges (a client VLAN the inventory has not seen yet, such as 10.20.0.5) then goes to the carrier. It fails, and the header says "via VPN or other route", which is true but unhelpful. A public target goes out over mobile data from a client site.
- Resolution: the Director's call (question 4). The minimal option: when the route is OTHER, the target is private (RFC1918, CGNAT or link-local) and the default network is not a VPN, use WIFI or ETHERNET instead. That is the only route that can reach it. Public targets stay unbound.

### G11. MINOR. A heard mDNS record's address is never compared with the packet's source

- Location: `ListenController.listen` mDNS reader (`{ bytes, _ -> HeardPackets.mdns(bytes, identityKey) ...}`, 4835); `MdnsPacket.parse` takes the address from the A record (3316).
- Problem: the source is discarded. A packet that reaches the wildcard socket from another interface (Linux `IP_MULTICAST_ALL` delivers groups any socket on the phone joined, the tun device included) is written on the strength of its A records alone. A device on a shared network can also put names on other hosts' rows. Strict source-equals-A would break the UniFi reflector the spec wants to keep.
- Resolution: drop an mDNS datagram whose source is outside the session's `KnownRanges` (the reflector's gateway source is inside). That is one `contains` per packet, plus one `HeardPacketsTest` case.

### G12. MINOR. A session is keyed on the whole NetworkEnv, so roaming restarts it

- Location: `Key(plan, env, epoch)` (4800, 4812 to 4815); `onSessionStart` clears `removedWhileListening` (4921).
- Problem: `NetworkEnv` equality includes `bssid`, `ssid` and `dnsServers`. On the Director's multi-AP UniFi, every roam restarts the session, resets the 32-row cap and clears the removed set. A device he removed can then come back after he walks between rooms or switches apps briefly. That is spec-compliant ("next listening session"), but it surprises.
- Resolution: key on `identityKey(env)`, `interfaceIp` and `transport` instead of the whole env. Clear `removedWhileListening` only when the session's network row changes, not on every restart.

### G13. MINOR. Stop disappears if the screen is recreated

- Location: `startedHostId` (2488, 2601 to 2603).
- Problem: it lives in the view model. After a configuration change or leaving and returning, the screen shows Run for a brute-force that is still running, and a second Run gets "A tool is already running on this device".
- Resolution: keep the started host id in `AppContainer` (process-wide, like `removedHostIds`), or show Stop whenever the typed IP's row is in `toolRunner.active`.

### G14. MINOR. `runTool` reads TLS from the published state, not the form

- Location: line 2567, `ManualTarget.tls(f.tool, state.value.https)`.
- Problem: `state` is a `WhileSubscribed` flow computed on `Dispatchers.Default`. A switch flip followed by a quick Run tap can read the old value.
- Resolution: compute `https` from `f` the same way the presenter does: `f.httpsChoice ?: ManualTarget.httpsDefault(...)`.

### G15. MINOR. The route is chosen on the tap's env and bound on the runner's env

- Location: `ManualStarter.begin` (1941 to 1956); `ToolRunner.start` reads `env.value` (ToolRunner.kt line 82).
- Problem: if the network changes between the tap and the runner's `startLock`, a WIFI route computed for A binds to B's Wi-Fi, and the result is stored on A's row. The window is tiny.
- Resolution: in `runnerStart`, refuse with `NoNetwork` when `identityKey(r.env) != identityKey(current env)`, or pass the env through `ToolTarget` and let the runner compare it with its own.

### G16. MINOR. The "undimmed heard row" test cannot fail

- Location: `ManualHostDaoTest.aHeardRowOnANetworkWithNoRunIsShownUndimmed` (673 to 679); self-review note 16.
- Problem: it rebuilds the dim expression inside the test (`latestRun > 0 && ...` with `latestRun = 0`), so changing `HomeViewModel`'s rule cannot fail it.
- Resolution: extract `StaleRule.of(latestRun, h)` into a pure function, use it in `HomeViewModel.baseState`, and test that function.

### G17. MINOR. The Director-copy snapshot compares a subset of columns

- Location: `DirectorCopyMigration34Test` SQL constants (497 to 501).
- Problem: it omits, for example, `ports.title/server/realm/certCn`, `hosts.identVendor/identModel/identSoftware/macSource`, `networks.forgotten`, `tool_runs.finishedAt` and `tool_findings` content. The migration cannot touch them, but the test is the proof, and today it cannot see them.
- Resolution: read `PRAGMA table_info` for each table at version 3 and compare `SELECT <those columns> ... ORDER BY rowid` row for row at version 4. That is generic, shorter, and covers everything.

### G18. MINOR. Wording

- Location: `ToolCopy.BIND_FAILED` ("could not bind to Wi-Fi", ToolCopy.kt line 69) on an ETHERNET route; `ListenCopy.NO_NETWORK` shown while the network's Forget is pending (self-review note 8).
- Resolution: record both for the Director in the Task 12 list, with no copy change now (both strings are pinned in tests). If he wants changes, it is one constant each.

### G19. MINOR. Task 7 is too large

- Location: Task 7 (2129 to 2957, about 830 lines: presenter, view model, screen, Home menu, Nav).
- Resolution: split it into 7a (presenter and view model, JVM-tested) and 7b (the screen, `HomeMenu`, `HomeContent`, `HomeScreen`, `Nav.kt`, and the two Compose tests). Tasks 4 and 6 (copy and the route header) are small and can run as one standard-model batch. Tasks 8 and 9 are independent of each other and can be batched too. Keep the stronger model on 1, 3, 5, 10 and 11.

## What the plan gets right

- `MIGRATION_3_4` is exactly two `ALTER TABLE ... ADD COLUMN`. The proxy-based `MigrationSql34Test` refuses any other call. `SchemaExportTest` diffs 3 against 4 column by column. Both migration seeds match `1.json` and `3.json` exactly (I counted all 37 host columns). The chain test runs 1 to 4, and neither test touches `netprobe.db` (file stamps are asserted).
- The QA data-safety ceremony is complete: database, `-wal`, prefs and every `files/wordlists/` file backed up with MD5 on both sides, `exec-in` only, the copy pushed under its own name, an assumption failure treated as not a pass, an offline rollback APK from the 3C-1 head, and a restore that always returns to the schema 3 backup because 3C-1 cannot open schema 4.
- The hidden row rule has one gate (`forNetwork`). Its callers are audited by grep in Task 2 Step 6. The canary's `knownIps` moves to every row (I checked: it only excludes canary picks, it never feeds candidate ranges, so a public manual target can never become a swept range). `KnownRanges` ignores `manualOnly` rows, so a typed target never widens the network.
- The conditional delete (`manualOnly = 1 AND id NOT IN tool_runs`) can never remove a promoted or started row, and the start-up sweep covers a killed process. Both are tested on the JVM and in the DAO test.
- The routing is fail-closed. The runner's default `routeBind` refuses WIFI and ETHERNET. ManualStarter refuses a known-range target with no binder before any row exists. `ToolRunnerRouteTest` proves that the binder seen inside the tool is the route's, and that the scan bind is never called for a manual run.
- The listener's gap 2 fix (a session row plus Home's row plus the identity key) is better than the spec's tag alone, and `ListenControllerTest`'s parked-store network-change test fails if either check is removed.
- The parser bounds are real: 8 pointer jumps, 128 labels, all-or-nothing on any out-of-bounds read, 16 records per packet, a test truncating at every byte, and a 1,024-key LRU with a test that proves eviction.
- The lock is a separate object with its own tag. `LocksTest` proves the scan's release never touches it. The listener starts from `MainActivity` and not the Application, so instrumented processes never open the database through it.

## Questions for the Director

1. Do you approve the one internet contact in QA item 3 (a TLS handshake to 1.1.1.1:443), or do you want to name a different public address?
2. Spec open item 3 said the listener would not fetch UPnP descriptions, and the controller ruling turned the fetch on. Do you want the fetch?
3. Should listening only run on networks you have scanned at least once? Then opening NetProbe on a client's or hotel's Wi-Fi records nothing and sends nothing until you tap Scan (G9).
4. When the Wi-Fi has no internet, a private address outside the known ranges goes out over mobile data. Should those addresses be sent over Wi-Fi instead (G10)?
