# NetProbe Phase 3C spec: Gruff review

Date: 2026-10-21. Reviewer: Gruff (Devil's Advocate, partial veto). Spec: `docs/superpowers/specs/2026-10-21-phase3c-feedback-design.md` at c64f89a (433 lines). Checked against the Phase 2, Radar, 3A and 3B specs, the Phase 2 and 3A device QA records, and the code on `phase3b` (`GatewayProbe.kt`, `CandidateRanges.kt`, `Subnet.kt`, `Settings.kt`, `ExtendedSection.kt`, `HomeViewModel.kt`, `RadarCollector.kt`, `Repositories.kt`, `Daos.kt`, `Entities.kt`, `ScanBinding.kt`, `Locks.kt`, `ToolRunner.kt`, `HttpClient.kt`, `WordlistStore.kt`, `AndroidManifest.xml`).

## Verdict

6 BLOCKING, 11 SHOULD FIX, 8 MINOR. The spec is careful and mostly right: the schema change is additive, the flagged-row model for manual targets is the right call, the URL fetch reasoning is sound, and the claim that Phase 2 already covers the Director's 10.0.0.1 / 10.0.1.1 / 10.0.2.1 example is true (verified in `GatewayTargets.byUnit`). But it puts an Aggressive toggle on Home after the Director said Settings; it leaves the literal "trust the subnet mask" request undelivered on the networks where it matters (masks wider than the Large networks limit); it lets manual tools reach a non-local private address through a VPN depending on an unrelated toggle, which is the exact failure Phase 2 5.5 was written to prevent; the listener can create unbounded inventory rows from unsolicited packets and can write one network's packets into another network's rows across a network change; and the device checklist drops the backup, rollback and Director-copy migration steps that 3A and 3B both ran. All six are cheap to fix in the spec. Fix them, take the Director's answers below, then plan as two plans, not one.

## Findings

### G1. BLOCKING. Home overflow "Aggressive scan" contradicts the Director's answer

- Location: D6 (line 33), 2.2 (lines 92 to 93), 4.1 (line 181), 7.2 `HomeCopy.MENU_AGGRESSIVE` and `aggressiveDescription` (lines 306 to 307), 8.2 item 4, 8.3 item 4.
- Problem: the Director was asked where the toggle lives and answered "Settings (not Home)". D6 cites "make it discoverable" as Ada's reason to add it to Home anyway. That is not a defensible extra; it overrides an explicit answer. It also adds a scan-time hazard the spec then has to manage (disabled during scans) and a sync test.
- Evidence: brief answer; D6 source column reads "Ada".
- Resolution: drop the Home menu item, its two copy rows, 8.2 item 4's toggle part and 8.3 item 4. Home overflow becomes "Quick scan all", "Run a tool...", "Forget this network", "Settings". Discoverability is served by moving the section under Discovery (keep that). If Ada still thinks Home needs it, it goes to the Director as a question, not into the spec.

### G2. BLOCKING. "Trust the subnet mask" is not delivered when the mask is wider than the Large networks limit

- Location: 2.1 (line 86) "The baseline is unchanged".
- Problem: `SubnetMath.sweepRange` returns the phone's /24 whenever `prefix < maxPrefix` (Subnet.kt lines 35 to 37). On a client /20 or /16 flat network with the default limit /22, the baseline sweeps one /24, and Aggressive scan does not sweep the rest of the masked subnet either: `CandidateRanges.derive` only takes manual, evidence and gateway-probe /24s, capped at 4. On a flat /16 the gateway probe mostly misses (one gateway), so most of the subnet the mask declares is never swept even with Aggressive on. The Director's request was "trust the subnet mask where appropriate as the baseline and scan further with a toggle". The spec restates current behaviour and calls it done.
- Evidence: `Subnet.kt` 35 to 37; `CandidateRanges.derive`; `ExtendedOptions` has no "rest of the mask" source.
- Resolution: the Director decides (question 1). Recommended: baseline clamps to the limit-sized block containing the phone (a /22 at default) instead of falling to /24, and Aggressive scan adds the remaining /24s of the masked subnet, nearest first, as a new `RangeSource.MASK` after manual and before evidence, under the same cap. The Director's home /24 is unaffected (prefix >= limit), so Phase 2 check 1 stands. Add JVM pins for /20 and /16 masks.

### G3. BLOCKING. Manual tools route through a VPN or not depending on the Aggressive toggle

- Location: 4.3 last paragraph (line 213).
- Problem: the runner binds through `ScanBinding.run(ip, env, maxPrefix, extEnabled, ...)`, which returns unbound for any target when `extEnabled` is false (ScanBinding.kt). Today that path is only reachable for rows an extended scan found, so it was safe. Manual tools make it the common path: the Director types `10.20.0.5` with Aggressive off on a client site while a work VPN or Tailscale subnet route covers 10/8, and the brute-force goes down the tunnel to someone else's 10.20.0.5. Phase 2 5.5 (line 238) names this exact failure: "on a client site the app could probe the Director's home or another client's network". The screen also requires Wi-Fi or Ethernet (`ManualCopy.NO_LAN`), so the user reasonably expects LAN routing.
- Resolution: manual runs pass `extEnabled = true` semantics to the binding (bind to the Wi-Fi or Ethernet `Network` for any target outside the swept local range, whatever the toggle), with a missing binder giving `NoNetwork` as today. Add a JVM pin: Aggressive off, non-local manual target, binder invoked. If the Director wants "through the VPN" as a use case, that is a later explicit option (question 4).

### G4. BLOCKING. The listener can create unbounded rows in the Director's inventory from unsolicited packets

- Location: 3.3 (lines 156 to 165), 3.4.
- Problem: any device on the LAN (or one hostile client on a client network) can send mDNS responses with A records for arbitrary RFC1918 addresses, or SSDP NOTIFYs. Each new `(ip, type)` is a new key, so the 60 s repeat filter does not slow it, and every unseen IP becomes a permanent host row on that network with no scan run. The 64-entry queue bounds memory, not writes. The repeat map itself has no size bound. Phase 2 built a three-layer phantom guard precisely because "write permanent rows" for things that are not there is the worst outcome (Phase 2 line 158). Active-scan mDNS has the same spoof surface but is bounded by the scan's duration; the listener runs as long as Home is open.
- Resolution: (a) `HEARD_NEW_MAX = 32` new rows per listening session (per collector instance); after that, heard hosts only update existing rows; (b) a new row from a heard packet only when the address is in the swept local range or in a /24 that already holds a stored, visible host on this network (UniFi mDNS reflection across VLANs still works for known VLANs; a scan adds new ones); (c) at most 16 records emitted per packet; (d) the repeat map is an LRU of 1,024 keys. JVM pins for each.

### G5. BLOCKING. Heard packets from one network can be written into another network's rows

- Location: 3.2 (lines 143 to 148) and 3.4 (line 169).
- Problem: on a network change the listener collector restarts on the new env at once, but `c.currentNetwork` is only updated after `networks.ensure(...)` finishes in `HomeViewModel` (lines 171 to 179), which is asynchronous and only runs while Home's ViewModel is alive. Packets from network B arriving in that window, and anything already in the 64-deep queue from network A, are written against whichever network `currentNetwork` names at write time. That breaks the Phase 2 network identity rule (rows belong to the network keyed by gateway and gateway MAC) and could put a client's devices into the Director's home inventory.
- Resolution: each emission carries the `NetworkEnv` (or its `NetworkSignature.identityKey`) it was captured on; the writer drops it unless `currentNetwork` matches that env; the queue is created per collector instance so cancelling the collector discards it. JVM pin: env A, enqueue, switch to env B before `currentNetwork` changes, nothing written to either.

### G6. BLOCKING. The Director's-data safety steps are missing from the device checklist

- Location: 8.2 item 1 (line 380), 8.3 (lines 387 to 398), definition of done (line 400).
- Problem: 3A (spec 9.4 step 0, QA steps 0 to 4) and 3B (plan Task 20, `DirectorCopyMigration23Test`, rollback APK from the recorded base, MD5-checked copies) both backed up the live database and prefs, built a rollback APK, ran the migration on a pushed copy of the Director's database, then migrated in place and checked marks, notes, ports and tool runs. 3C's 8.2 item 1 uses `MIGRATION_TEST_DB` only and 8.3 has no backup or rollback step. Hard rule: the Director's data survives every migration.
- Resolution: add 8.3 item 0: backup of the schema 3 database and prefs with MD5; rollback APK from the 3B head (record the base in `phase3c-base.txt`); `DirectorCopyMigration34Test` on a pushed copy (skipped when absent); in-place migration with every mark, note, override, port and tool run checked; schema 4 backup after. Also back up `SharedPreferences` before the Settings reset test (8.3 item 1 resets the Director's real tuning).

### G7. SHOULD FIX. Manual tools restricted to RFC1918 blocks real targets on client networks

- Location: D12, 4.2 (line 199), open item 10.5.
- Problem: the MVP sweeps whatever subnet the phone is on, RFC1918 or not (`sweepRange` has no RFC1918 check), so a Device-screen tool can already run on a host in a client's 100.64.0.0/10 (CGNAT, Tailscale), a publicly numbered internal LAN (universities, older enterprises) or 169.254.0.0/16 (an unconfigured camera or printer that fell back to link-local, a classic on-site find). Manual tools would refuse the same address. That is inconsistent and blocks a core pentest use.
- Resolution: allow any unicast IPv4 that is (a) inside the current swept local range, whatever its class, (b) RFC1918, (c) 100.64.0.0/10, or (d) 169.254.0.0/16. Reject 0/8, 127/8, multicast, 255.255.255.255 and the local range's network and broadcast addresses. Public internet targets stay out until the Director answers question 3. Error copy becomes "Use an address on this network or a private range". Note the same RFC1918 limit applies to manual ranges via `CandidateRanges.derive`; say so in 2.3.

### G8. SHOULD FIX. Orphan hidden rows accumulate

- Location: 4.3 step 2 (line 208), 7.1.
- Problem: `manualTarget` inserts the row before `toolRunner.start`; a `TooMany` result (4 runs active) or a brute-force sheet the Director cancels leaves a `manualOnly` row with no runs. Typos ("10.0.3.200" for "10.0.3.20") leave rows forever. They are invisible, never pruned and counted nowhere, so nobody will notice until a query misbehaves.
- Resolution: create the row inside the start path and delete it in the same transaction when start does not return `Started` (or open the sheet before creating the row); plus a startup sweep `DELETE FROM hosts WHERE manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs)`. DAO test for both.

### G9. SHOULD FIX. Redirect rule rejects GitHub `/raw/` and Dropbox links; content-type rule rejects real wordlists

- Location: D16, 5.3 `redirect` and `contentType` (lines 236 to 237), open item 10.3.
- Problem: the Director's likely sources are GitHub (SecLists, Assetnote) and gists. `github.com/<u>/<r>/raw/...` and `gist.github.com/.../raw` redirect cross-host to `*.githubusercontent.com`; Dropbox `?dl=1` redirects to `dl.dropboxusercontent.com`. All fail with "redirected to another site", and he will not know to rewrite the URL. Separately, many servers send `.lst`, `.dic` and extensionless files as `application/octet-stream`; `text/plain` only rejects them. The same-host rule buys little: the fetch is a GET of a URL he typed.
- Resolution: up to `MAX_REDIRECTS = 3`, any host, never https to http, and refuse a redirect whose `Location` host is an IP literal in loopback, link-local or RFC1918 space when the original host was not (stops a public server bouncing the phone into a LAN GET). Content type: accept `text/*` except `text/html`, plus `application/octet-stream` and a missing header, then reject the body if the first 8 KB holds a NUL byte (`Could not fetch: not a text file`). Keep rejecting `text/html` (a login or error page would import as junk). Update tests 14 and 15.

### G10. SHOULD FIX. JVM tests for the URL fetch exercise the wrong HttpURLConnection

- Location: 8.1 item 15, assumption A4.
- Problem: JVM tests run the desktop JDK `HttpURLConnection`; Android's is OkHttp-backed and differs in exactly the behaviours the spec relies on: transparent gzip (and with it, `getContentLength()` returning -1 and the progress total disappearing), `disconnect()` from another thread during a read, and redirect handling. A green JVM run proves nothing about A4 or cancel-within-1-s on the phone.
- Resolution: add an instrumented `UrlWordlistFetcherDeviceTest` against a `ServerSocket` on 127.0.0.1 on the device: gzip-encoded body over 10 MB decoded stops at the cap; cancel during a slow body within 1 s; `Content-Length` pre-check; progress with and without a total. Keep the JVM tests for the pure rules.

### G11. SHOULD FIX. Listener joins a group on a possibly null interface

- Location: 3.3 (line 154).
- Problem: `NetworkInterface.getByInetAddress(env.interfaceIp)` can return null (address change race, interface renamed). `joinGroup(addr, null)` joins on the default interface, which with a VPN up can be the tun device.
- Resolution: a null interface means that socket does not open and the status line shows `ListenCopy.NO_PORTS`. JVM pin with a fake interface lookup.

### G12. SHOULD FIX. Outward walk on networks that reject every RFC1918 destination

- Location: D9, 2.4 (lines 113 to 118).
- Problem: firewalls with bogon-reject rules answer RST for unrouted RFC1918 but often drop TEST-NET, so the pre-check passes and many or all 17 seeds "answer". Each then walks 16 units each way: up to 1,088 extra attempts and dozens of phantom hits crowding real ones out of the cap of 4. Bounded, but it is exactly the noisy pattern a client's IDS flags, for zero value.
- Resolution: skip the outward walk when more than 4 of the seeds answered (record a note); and stop a direction when a unit answers with Refused on both ports from the same latency band as the seed (optional). Add the "many seeds answer" JVM case. Also recompute the copy: "Up to about 1,600 connection attempts" is wrong once the walk exists (worst case about 2,650). Either state the real upper figure or drop the walk (question 5).

### G13. SHOULD FIX. Device checklist item 2 cannot pass on the Director's LAN as written

- Location: 8.3 item 2 (line 390).
- Problem: "only that range is swept after the home subnet" ignores evidence ranges: `CandidateRanges.derive` adds /24s from off-subnet DNS servers and sightings whatever `gatewayProbe` says, and the 3A QA recorded 17 hosts on other subnets on this LAN.
- Resolution: "the manual range is swept first; evidence ranges may follow; no gateway probe runs (no 'probing ... for gateways' line)".

### G14. SHOULD FIX. Listener expectations in QA item 5 are unrealistic

- Location: 8.3 item 5 (line 393), 3.3.
- Problem: mDNS responders announce at boot or on change, not periodically; between scans the listener mostly hears multicast answers to other devices' queries. Requiring a PTR, SRV and A for the same instance in one packet drops many of those. Hue and Sonos NOTIFY intervals vary (often minutes). "Within two minutes" will fail on a quiet network and Bugsy will chase a non-bug.
- Resolution: pass criterion "at least one device not yet in the inventory, or a known device's `lastSeen` updated, within 10 minutes; record what was heard and from which protocol". Consider (Director's call, not a block) also accepting an A record whose name is a `.local` host name as a name-only sighting under the G4 limits.

### G15. SHOULD FIX. Toggling or resetting during a scan: the spec contradicts itself

- Location: 1.2 last paragraph (line 76) vs 2.2 (line 93).
- Problem: 2.2 disables the Home toggle during scans because "`ScanBinding` and the range grouping read `extEnabled` mid-run"; 1.2 says a reset during a scan is harmless because "the running scan keeps the options it started with". Both cannot be true. The Settings switch has never been disabled during scans (ExtendedSection.kt line 62), so the hazard, if real, exists today.
- Resolution: with G1 the Home toggle goes; then either snapshot `extEnabled` and `maxPrefix` at run start for `baseState` grouping and `ScanBinding` (preferred, one place) or disable the Aggressive switch and Reset during a radar scan. Pin whichever with a test.

### G16. SHOULD FIX. Two plans, not one

- Location: section 6 (lines 252 to 265).
- Problem: six tasks spanning a schema change, a new screen, a long-lived multicast listener with its own lock, an engine change and an internet fetch. One plan means one device QA at the end and one rollback point for all of it. The listener (3.x) and manual tools (4.x plus schema 4) carry nearly all the risk; reset, copy and URL import carry almost none.
- Resolution: Plan 3C-1: settings reset and markers, Aggressive copy and section move, manual editor while off, G2's mask work, URL import; its own device QA (no schema change, so no migration ceremony). Plan 3C-2: schema 4 with G6's full migration ceremony, manual tools, then the listener last; its own device QA. Both start only after the 3B device QA passes (3C-2 touches `ToolRunner`, the results sheet and `HostBusyRegistry`, which 3B QA is still exercising). If the Director wants it faster, cut in this order: the outward walk (Phase 2 already covers his example), the default markers (the overflow reset alone answers his brief), the listener's seven-state status line (two states: listening, not listening).

### G17. SHOULD FIX. Manual ranges gated on Aggressive scan may not match "Add manual ranges to scan"

- Location: D8, 2.3 item 4, open item 10.7.
- Problem: a range the Director types is an explicit instruction; saving it and then silently not sweeping it with Aggressive off is the kind of thing he will report as a bug, "Saved now" line notwithstanding. The counter-argument (toggle off is exactly the MVP path, Phase 2 check 1) is real.
- Resolution: Director decides (question 2). If he says sweep always, manual ranges run bound to Wi-Fi as extended ranges do, the Phase 2 toggle-off path is unchanged when the list is empty, and the JVM regression case pins "no manual ranges, toggle off, identical to MVP".

### G18. MINOR. Settings reset scope is right; two edge cases

- Location: D3, 1.1, 1.2.
- Keeping the NVD key, manual ranges, wordlists and brute-force options is right; resetting Aggressive scan, Smart discovery and "Listen between scans" to off is right (they are settings, and off is the safe default). Two edges: (a) Undo restores the whole `before` snapshot, so a slider the Director changes in the ~10 s after a reset is silently reverted by Undo; acceptable, but state it. (b) `maxPrefix` reset changes how big a home subnet is swept on his next client visit; that is correct, just make sure it is in the 15.

### G19. MINOR. "Default N" markers are borderline against "not loads of buttons"

- Location: D2, 1.3.
- With five sliders and a stepper moved, six tappable markers appear. They are small and conditional, so probably fine, but the brief was explicitly about not adding buttons. Show the Director on device (already in the definition of done) and drop the tap target, keeping the caption, if he calls it clutter.

### G20. MINOR. Dropping "Noisy on networks you do not own." is correct

- Location: section 9 row 4, 2.3 item 3.
- The standing no-disclaimer rule wins; the honest part is the cost, and the new subtitle keeps a concrete attempt count and time. That is a description, not a warning. Keep it, but the number must be true (G12).

### G21. MINOR. Cap of 4 extra ranges vs "then keep going"

- Location: 2.4, 2.3 item 2.
- On a client /16 with a dozen VLANs, Smart discovery finds them and sweeps only the lowest 4. Phase 2 already reports truncation (`CandidateResult.truncated`); confirm the Home notice for truncated gateway hits says how many were left and points at "Maximum extra ranges", so "keep going" is one setting away.

### G22. MINOR. Heard re-adds removed devices within seconds

- Location: 3.4.
- After a Remove device commits, the listener can re-insert the host as soon as it announces, where a scan would only do so on the next run. Consistent with Remove's meaning, but it will look broken. Either note it in the QA record or keep removed `(network, ip)` pairs in an in-memory skip set for the listening session.

### G23. MINOR. `upsertHeard` with no run yet

- Location: 3.4 (line 171).
- `runIdForNew = lastRunAt ?: 0`: confirm in a test that a row with `lastDiscoveryRun = 0` on a network with no run is shown undimmed (the dim rule compares to the latest run, which is null). One line in 8.2 item 2.

### G24. MINOR. Copy nits

- `HomeCopy.MENU_RUN_TOOL` "Run a tool..." uses three full stops; the Material convention is the single ellipsis character. Pick one and put it in `CopyTableTest`.
- `ManualCopy.ERR_PRIVATE` changes under G7.
- With G1, delete `HomeCopy.MENU_AGGRESSIVE` and `aggressiveDescription`.

### G25. MINOR. Tests that need a second network or a VPN

- Location: 8.1, 8.3.
- G3 (VPN binding), G5 (network change mid-listen) and the listener's env-change release cannot be proved on the Director's single LAN. Pin them with JVM fakes and add device checks that use what Bugsy has: Wi-Fi off and on, the guest SSID if the UniFi has one, and a VPN app if one is already installed (no installs). Mark any that cannot run as NOT RUN in the record, as 3A did, rather than dropping them.

## What the spec gets right

- Schema 3 to 4 is one additive column with a default; no row is rewritten; `4.json` exported.
- The flagged `manualOnly` host row is the right model: `tool_runs.hostId`, `HostBusyRegistry`, pruning and the results sheet all keep working, promotion on discovery keeps history, and the unique `(networkId, ip)` index means a later scan merges into one row instead of two (`upsertDiscovered` finds by `(networkId, ip)` and `updateScan` clears the flag).
- `forNetwork` filtering covers Home, `HomeStats`, groups, search and Quick scan all in one place; `allForNetwork` for `toolRunningOnNetwork` and canary `knownIps` is correctly spotted.
- Phase 2 does cover the Director's 10.0.0.1 / 10.0.1.1 / 10.0.2.1 example: `GatewayTargets.byUnit` probes .1 and .254 of every /24 in the enclosing /16. The spec checked the code instead of trusting the brief, and recorded the deviation (section 9 row 2).
- The heard write goes through `RadarCollector` with `hostWriteLock`, `RemovedHosts.skip` and `IdentityWrite.sameDevice`, not a raw DAO call; it is dropped during scans, pending Undo and Forget; it never moves "Last scan".
- A separate `netprobe-listen` lock so the scan's `Locks.release()` cannot drop it; foreground only; no service, no wake lock; default off; listen-only, no M-SEARCH or queries.
- `HttpURLConnection` for the internet fetch is right: the in-repo `HttpClient` is IP-only, trust-all and capped at 64 KB; cleartext is already allowed in the manifest, so plain HTTP on the LAN works.
- The store's existing 10 MB and 200,000-entry caps are enforced while reading, after decoding.
- Every string is in the copy table, Run/Stop pinned outside `manualHistory`, no new dependencies, test packets authored in source.

## Questions for the Director

1. On a network whose mask is wider than the Large networks limit (a /16 at a client), should Aggressive scan sweep the rest of the masked subnet, nearest /24s first?
2. Should manual ranges you type always be swept, even with Aggressive scan off?
3. Should "Run a tool" accept public internet addresses, or only addresses on the current network plus private, CGNAT and link-local ranges?
4. Should manual tools always stay on Wi-Fi or Ethernet, or do you want to reach hosts through a VPN from the phone?
5. Keep the Smart discovery outward walk for hits outside your /16, or drop it (your example is already covered)?
