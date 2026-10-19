# Gruff review: Extended discovery design

Reviewer: Gruff (Devil's Advocate, partial veto)
Date: 2026-10-19
Spec under review: `2026-10-19-extended-discovery-design.md`
Code read: `DiscoveryEngine.kt`, `HostProber.kt`, `Sweep.kt`, `common/Probe.kt`, `netenv/Subnet.kt`, `data/Entities.kt`, `data/Repositories.kt`, `data/NetProbeDb.kt`, `data/Settings.kt`, `ui/home/HomeViewModel.kt`, `ui/home/ScanController.kt`, `cpp/neigh.c`, `app/build.gradle.kts`, the radar states mock-up.

## Verdict

PROCEED WITH FIXES. Veto held on findings 1 and 2 until the spec is edited: as written, a common router or firewall configuration (REJECT rules, captive portals) turns every address in every candidate range into a "live" host that is stored permanently, and the manual range list is global, so ranges typed in for one network are swept on every other network the phone joins, including client sites. Fix also before planning: 3 (drop the schema migration, derive the grouping), 4 (VPN routing), 5 (neighbour entries from other interfaces), 6 (gateway probe numbers are wrong by about 4x), 7 (align with the radar redesign). The rest are MINOR and can be folded in by the editor. Nothing in the spec needs a download: `room-testing` is already declared in `libs.versions.toml` and present in the Gradle cache, and `stickyHeader` is in the Compose foundation library already resolved.

## Findings

### 1. BLOCKER: phantom ranges from routers that answer for every address (4.3, 4.6, 5.2, 12)

**What is wrong.** The MVP liveness rule "connect or refused means alive" is safe on-link, where nothing sits between the phone and the target. Through a router it is not. The spec reuses `HostProber` unchanged for routed ranges (section 3, "the connect-or-refused rule already works through a router") and has no defence against a middlebox that answers on behalf of every destination.

**Evidence.**
- `Probe.classifyConnect` maps any `ConnectException` containing "refused" to `Outcome.Refused`, and `HostProber.probe` returns `alive = true` on `Refused`.
- Linux maps an ICMP port-unreachable reply to a TCP SYN to `ECONNREFUSED` (`icmp_err_convert` in `net/ipv4/icmp.c`). The default `iptables -j REJECT` target sends exactly that, and `--reject-with tcp-reset` sends a RST, which is also `ECONNREFUSED`. OpenWrt's zone `REJECT` policy, pfSense/OPNsense "Reject" rules and most Linux-based firewalls do one of the two for forwarded traffic. Every address behind such a rule reads as alive.
- Captive portals and many guest Wi-Fi networks DNAT TCP 80/443 (and often 53) for every destination to the portal. Connect succeeds on port 80 for any IP. That is the client guest-Wi-Fi case the spec names as the point of the exercise.
- Consequences: the gateway probe flags all 255 /24s; each extended sweep reports 254 hosts per range; enrichment runs 254 HTTP title fetches per range (all returning the portal page); up to 4,064 phantom rows are written; `Daos.kt` has no host delete, so they are permanent; "Quick scan all" (11.8) then port-scans all of them.

**Fix.** Add a canary step, specified as a pure decision plus an engine call, and test it:
1. Gateway probe canary: before probing, connect to two addresses that cannot be real hosts on the LAN, for example `192.0.2.1` and `198.51.100.1` (RFC 5737 TEST-NET, never assigned) on port 80 and on one random high port (49152 to 65535). If either connects or is refused, set the probe result to "this network answers for every address", skip the gateway probe, and show the note "Gateway probe skipped: this network answers for every address".
2. Per-range canary: before sweeping a candidate /24, probe two addresses in it that are not already known in Room for that network (the highest and lowest such host addresses) on a random high port (49152 to 65535, chosen per run, not in any liveness or Quick list). If both answer (connect or refused), mark the range `RangeState.PHANTOM` (new state), skip it, store nothing, and include it in the end-of-run summary ("10.0.9.0/24 skipped: everything answers").
3. Sweep guard: if more than 90% of the first 64 probed addresses in an extra range come back alive, cancel that range, mark it PHANTOM and discard (do not store) the hosts it produced. Hosts are emitted as found, so the ViewModel must buffer extra-range emissions until the range passes this check, or the engine must hold them; the spec must say which (simplest: the engine buffers per range and emits after the guard passes at 64 probes).
4. Tests: JVM engine test with a fake prober returning `Refused` for every address (gateway probe skipped, every range PHANTOM, zero hosts emitted), and one returning `Connected(80)` for every address (captive portal). On-device: one check on a captive-portal or REJECT-ruled network if one is available, otherwise a manual range pointed at a /24 the Director's UniFi rejects.

### 2. BLOCKER: manual ranges are global, not per network (4.4, 8.3)

**What is wrong.** `extManual` is one SharedPreferences key. The "own network" check runs only when an entry is added, against whatever network the phone is on at that moment. Ranges entered at home (`10.0.4.0/24`) are swept on the next client site the phone joins with the toggle on, and client ranges entered on site are swept at home and at the next client. RFC1918 space overlaps between sites, so this is traffic to hosts at one site that were chosen for another. The spec's own reason for defaulting the toggle off ("would send traffic to ranges the user did not choose, on any network the phone joins") applies here even with the toggle on.

**Evidence.** Section 8.3 ("new keys ... `extManual` (newline-joined)"), 4.4 ("Manual entries are persisted as a string list"), 10 ("Candidate lists exist only inside the engine call and are never carried across networks", which is true for derived candidates but not for the manual list).

**Fix.** Key the manual list by network row: `extManual.<networkId>` in the same SharedPreferences (no schema change). The Settings editor edits the list for the current network and shows its name ("Manual ranges for Home (10.0.3.1/24)"); with no current network the editor is disabled with "Connect to a network to edit its ranges". Re-run the "own network" rejection at derive time as well as at entry time (4.5 already excludes overlap with `scanned`, so state it explicitly for manual entries). Decide in the spec whether the master toggle and gateway-probe toggle stay global (the Director's delegated default is off; global is acceptable) and say so.

### 3. MAJOR: the schema migration is unnecessary and its backfill is wrong (7.1 to 7.3, 11.11, 11.12)

**What is wrong.**
- Both new columns are derivable at display time. `isOffSubnet` is `!scanned.contains(ip)`, which is exactly what `RowRules.otherSubnet` computes today. `subnet` is "the scanned range if it contains the IP, else the manual range containing it, else the /24 of the IP"; the inputs (the current `NetworkEnv`, `maxPrefix`, and the network's manual list from finding 2) are all in the ViewModel. Storing them makes them stale whenever `maxPrefix` or the manual list changes, which the spec then patches with "self-healing" that never reaches hosts not seen again.
- Storing the swept range causes split groups: a host stored as `10.0.4.0/24` (mDNS sighting, toggle off) and a later host stored as `10.0.4.16/28` (manual /28 sweep) produce two overlapping headers for one /24.
- The backfill computes the local range from the gateway's address. The local sweep covers the /24 of the phone's address (`SubnetMath.sweepRange(env.interfaceIp, ...)`), not the gateway's. On a /16 network with gateway 10.0.0.1 and the phone at 10.0.7.50, the backfill marks every host the phone actually swept as off-subnet. The migration test in 7.3 asserts this wrong behaviour (`10.0.7.3` expected as off-subnet).
- This would be the project's first migration, on the only real device, with no backup step. A failed migration throws on first open, every launch, and the only recovery is clearing app data, which loses the annotations the MVP promises to keep.
- 7.2's claim that the SQL `DEFAULT` makes the schema match is muddled: the entity has only Kotlin defaults, so `2.json` would record no default value, and Room skips the default-value comparison when the entity side has none. It validates, but by accident.

For the Director's own database (one network, gateway 10.0.3.1/24, Hue at 10.0.4.20) the backfill would produce the right values. The problem is the risk and the redundancy, not this one database.

**Fix.** Delete section 7 (keep 7's `RangeRules` as a pure display helper) and replace it with:
- `RangeRules.groupFor(ip: String, scanned: Subnet, manual: List<Subnet>): Pair<String, Boolean>` returning the group label and whether it is off-subnet; `HostRow` gets `subnet: String` computed in `RowRules.row` (which already receives `env` and `maxPrefix`).
- `upsertDiscovered` signature unchanged; no schema version bump; 11.11, 11.12, 12.1 "migration backfill" and 12.3 "migration test" and on-device check 8 removed.
- If the Director wants the columns anyway (for a future export), keep them but: compute the backfill local range from the stored phone address, which does not exist in v1, so backfill only `isOffSubnet` for networks with prefix 22 or longer and leave the rest `''` for the next scan; add `@ColumnInfo(defaultValue = "''")` and `@ColumnInfo(defaultValue = "0")`; add `sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }` to `app/build.gradle.kts` (missing today, and `MigrationTestHelper` fails without it); and make on-device check 8 start with a database backup: `adb exec-out run-as com.northwalessolutions.netprobe tar c databases > netprobe-db-backup.tar` (debug build, so `run-as` works; includes the `-wal` and `-shm` files).

### 4. MAJOR: extended probes follow the VPN, not the Wi-Fi (4.3, 5.2, 10)

**What is wrong.** The spec says "Cellular is never swept" and relies on `NetworkEnv` choosing the Wi-Fi network. That only picks which network is described. Sockets are not bound to it: there is no `bindSocket`, `bindProcessToNetwork` or `Network.socketFactory` anywhere in the code. Connects to a non-local RFC1918 address follow the default routing, and a VPN that routes RFC1918 (a corporate VPN with `10.0.0.0/8`, Tailscale subnet routes, a WireGuard tunnel home) carries the gateway probe and every extended sweep to the far end. On a client site that means probing the Director's home network or another client's network from the client's Wi-Fi. On-link local sweeps are mostly immune because the connected route is more specific; routed candidates are not.

**Evidence.** `grep bindSocket|bindProcessToNetwork|socketFactory` finds only TLS socket factories. `NetworkEnvMonitor` already detects `TRANSPORT_VPN` (line 38, 53) but only to skip VPN networks when choosing the env.

**Fix.** Bind every socket the gateway probe and extended sweeps open to the Wi-Fi/Ethernet `Network` the env came from (`network.bindSocket(socket)` before `connect`, threaded through `Probe.connect` as an optional `Network?` parameter, null for JVM tests). If binding fails (a VPN that disallows bypass), skip the extended phase with the note "Extended discovery skipped: a VPN is routing private ranges". Add an on-device check with a VPN active if the Director uses one.

### 5. MAJOR: neighbour entries from other interfaces become evidence (4.2, 6.2, 11.2)

**What is wrong.** The spec turns off-range neighbour entries into evidence. The JNI dump returns IPv4 entries from every interface: `neigh.c` reads `ndm_family` but never `ndm_ifindex`, and `NeighbourEntry` has no interface field. On a Pixel with the hotspot running alongside Wi-Fi, or after Wi-Fi Direct or Nearby Share (`p2p` interfaces on `192.168.49.x`), those clients' addresses become evidence and their range is swept and filed under the current network. Separately, proxy ARP (a router answering ARP for remote IPs, or a phone whose prefix is wider than the router's real segments) fills the table with remote IPs that all carry the router's MAC; 6.2's rule ("assign a MAC only to an IP that appears in the dump for itself") does not catch this, because those entries are for the remote IPs themselves.

**Fix.** Accept neighbour entries as evidence only when they fall inside the interface's true subnet, `SubnetMath.of(env.interfaceIp, env.prefixLength)`, but outside the swept range (this covers the clamped /16 case in 11.2 and excludes hotspot and p2p ranges, with no JNI change). Drop the "second subnet on the same layer-2 segment" case; it is rare and the manual list covers it. Never assign a MAC from an entry whose MAC equals the gateway's MAC unless the IP is the gateway, and treat any MAC shared by more than 2 IPs as proxy ARP (no MAC assigned). Add both as JVM tests.

### 6. MAJOR: the gateway probe numbers are wrong (4.3, 4.6, 8.3, 13.1)

**What is wrong.** "Normally about 510 to 600 attempts because live gateways answer on the first port" counts the live targets. Almost every target is dead, and a dead target that is silently dropped costs all 4 ports. On the Director's LAN, a /24 in 10.0.0.0/16 that the UniFi has no route for goes out the default route to the ISP, which drops it silently, so the typical run is close to the worst case: about 500 dead targets x 4 ports = about 2,000 attempts, and at 64 in flight with sequential ports per target (as `HostProber` does) about 8 waves x 1.6 s = about 13 s. The Settings text "about 510 connection attempts" understates the traffic about 4x and the 3 to 8 s figure is optimistic. Those 2,000 SYNs also create about 2,000 NAT entries on the gateway and leave the site towards the ISP.

**Fix.** Either correct the text (Settings row 3: "about 2,000 connection attempts, about 15 s"; 4.6 typical 10 to 15 s; 13.1 likewise) or cut the cost: probe 2 ports (80, 443) instead of 4. Gateways that firewall both are usually firewalled on 22 and 53 too. That halves attempts and time. Whichever is chosen, 12.4 check 9 must record the measured probe duration and attempt count.

### 7. MAJOR: conflicts with the agreed radar states (8.1, 8.2)

**What is wrong.** The radar mock-up (`radar-states.html`) is the agreed direction and disagrees with this spec in four places:
- 8.1 "The Scan button is not moved or resized by this feature": the radar replaces the Scan button with a ring that expands into the radar while scanning. The sentence will be false or will block the redesign.
- Group headers: the radar shows "Home subnet 10.0.3.0/24" and "Other subnet 10.0.4.0/24"; the spec specifies "192.168.1.0/24 (this network), 14 devices" and "10.0.4.0/24, 3 devices".
- Progress: the radar shows the count in the radar centre and "probing 171 of 254  7 s" below; the spec keeps "Found 14, probing 203/254" and adds "probing 10.0.4.0/24, 2 of 4 ranges", which drops the per-range count.
- Done state: the radar summary is "Scanned in 12 s, 1 on another subnet"; the spec's end-of-run notes go to `extraMessage`. With extended runs of 1.5 to 8 minutes, the expanded radar also stays up for minutes, pushing the list down.

**Fix.** One source of truth: state in 8.1 and 8.2 that the radar spec owns layout and wording, and this spec supplies only the data (`DiscoveryStatus.phase`, `ranges`, `truncatedRanges`). Agree the strings once: header "Home subnet 10.0.3.0/24" / "Other subnet 10.0.4.0/24" plus a count if wanted; scanning line during extended phases "10.0.4.0/24 (2 of 4), probing 171 of 254"; gateway phase "Looking for gateways, 112 of 256"; done summary "Scanned in 1 min 40 s, 4 other ranges checked, 3 devices on them". Hand both to the radar spec author before either is planned.

### 8. MINOR: the gateway probe only ever looks inside the phone's own /16 (4.3; goal F)

**What is wrong.** The enclosing /16 is computed correctly: 192.168.5.0/24 gives 192.168.0.0/16, 172.20.3.0/24 gives 172.20.0.0/16 (not the whole 172.16.0.0/12), and 10.0.3.0/24 gives 10.0.0.0/16, which contains the Hue's 10.0.4.0/24, so on-device check 3 is valid on the Director's LAN and open item 13.7 can be closed. But the client-site case in section 1 (guest Wi-Fi on 192.168.x reaching servers on 10.x or 172.16.x) is cross-block, and the probe never looks there. Also, .1 and .254 miss gateways on other offsets (.250, .253, or .126/.129 on split /25s).

**Fix.** (a) Add the phone's own gateway offset as a third probe address per /24 when it is neither .1 nor .254 (gateway 10.0.3.250 means probe .250 everywhere). (b) Add a fixed seed list of about 20 common gateway addresses in the other two blocks, probed with the same rule: `10.0.0.1, 10.0.1.1, 10.1.1.1, 10.1.10.1, 10.10.0.1, 10.10.10.1, 10.0.0.254, 172.16.0.1, 172.16.1.1, 172.16.16.1, 172.17.0.1, 172.31.0.1, 192.168.0.1, 192.168.1.1, 192.168.1.254, 192.168.2.1, 192.168.10.1, 192.168.88.1, 192.168.100.1, 192.168.178.1` (skipping any inside the phone's own /16, which the /16 walk already covers). It costs under a second. (c) Treat RFC1918 DNS servers from `NetworkEnv.dnsServers` that sit outside the swept range as evidence: on client sites they are usually domain controllers on the server range, which is the range the assessment wants.

### 9. MINOR: evidence timing and status counters do not match the current engine (4.2, 5.1, 5.2)

- 4.2 says mDNS and SSDP "have always completed first" because evidence is collected until the neighbour dump ends. In `DiscoveryEngine` the neighbour dump runs inside the sweep's `launch`, in the same `coroutineScope` as mDNS, SSDP and the `deviceInfo` launches. A fast local sweep can finish while the mDNS resolve queue is still draining. Fix: derive candidates after that `coroutineScope` returns (all strategies joined), and say so in the 5.2 flow.
- 5.1 says `probed` and `total` reset per range; the status collector uses `total = maxOf(t, it.total)`, so a manual /28 after the local /24 would show "of 254". Fix: state that the extended phase sets `total` per range without `maxOf`, and keep the engine test in 12.2.
- Mark a range `DONE` only after its enrichment jobs finish, not when the last liveness probe returns; otherwise the next range's progress starts while 32 enrichment permits are still busy and "Stop within a second" is tested against the wrong boundary.
- The gateway probe must use `Probe.connect` (which closes the socket on cancellation) on a `limitedParallelism` view, not a new connect path, so Stop works. Say so in 4.3.6.

### 10. MINOR: an empty range leaves no trace after the run (8.2, 11.9, 13.6)

**What is wrong.** For segmentation testing, "10.0.7.0/24 swept, nothing reachable" is a finding. `RangeProgress` is visible only while `scanning` is true (`HomeUiState.status` is null otherwise), and nothing is stored, so after the run the Director cannot tell which ranges were checked and found empty, nor which were PHANTOM (finding 1) or truncated.

**Fix.** No storage needed: keep the last run's `ranges` list in the ViewModel and show it in the done summary (radar state 3) as a tappable line, "4 other ranges checked", expanding to one line per range with its source, state and count. 13.6 stays "no empty headers in the list".

### 11. MINOR: manual prefix limits do not match the cap (4.4)

A /16 manual entry expands to 256 units against a cap of at most 16, so all but the first 16 (10.x.0.0 to 10.x.15.0) are always "Beyond cap". Allow /20 to /30 (a /20 is exactly 16 units), with the message "Too large, use /20 or smaller". Also correct 11.2: with a /16 Wi-Fi, `sweepRange` clamps to /24 whenever `prefix < maxPrefix`, and `maxPrefix` is coerced to 20 to 24, so widening it never sweeps "more of the /16"; the sentence "down to /20" is wrong for a /16 and only applies to /20 and /21 networks.

### 12. MINOR: long runs and the screen timeout (4.6, 11.13)

Default extended runs are 1.5 to 2 minutes and the cap maximum is up to 8, longer than a typical screen timeout. The scan job lives in `viewModelScope` with no foreground service; once the screen turns off and the device idles, network access for a backgrounded app is not guaranteed (light Doze, cached-app freezing on recent Android). Untested on Android 17 either way. Fix: set `FLAG_KEEP_SCREEN_ON` on the activity window while `scanning` is true (no permission, no new component), and add an on-device check: start a 4-range run, leave the phone untouched, confirm it completes.

### 13. MINOR: Quick scan all and the confirmation text (11.8)

11.8 refers to "the confirmation text" for Quick scan all, but there is none today (`HomeScreen.kt` runs it straight from the overflow menu). Either drop the sentence or specify the dialog ("Quick scan 37 devices, 12 on other ranges?") as new UI. With finding 1 fixed, including off-subnet hosts is reasonable; without it, Quick scan all would port-scan thousands of phantom rows.

### 14. MINOR: test plan and implementer guardrails (12, 13)

- Add the JVM tests named in findings 1, 4 (binding falls back to skip when the bind fails, with a fake), 5 and 8.
- Remove the hedge in on-device check 3 and open item 13.7: the Director's phone is in 10.0.3.0/24, so 10.0.0.0/16 contains 10.0.4.0/24.
- Open items jump from 7 to 9; renumber.
- Put in the plan header, for every implementer task: no new dependencies, no downloads, no Gradle plugin changes beyond the `androidTest` assets line (only if finding 3's fallback is taken). Everything this feature needs (`room-testing`, Compose foundation `stickyHeader`, coroutines) is already declared and cached. If the Compose BOM in use (2025.06.00) already has `stickyHeader` as stable API, the `@OptIn(ExperimentalFoundationApi::class)` in 8.1 is unnecessary; the implementer should check the compile warning rather than add an opt-in by reflex.
