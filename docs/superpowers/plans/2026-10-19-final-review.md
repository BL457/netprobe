# NetProbe MVP: final whole-branch review

Reviewer: Dot (QA). Date: 2026-10-19. Range: dd10ecf..e9e6641 (40 commits, branch `mvp`, working tree clean at e9e6641).
Method: read-only. I read the spec, the plan (Global Constraints, Review Focus, self-review), Gruff's review, the full SDD ledger, the on-device QA record and the Task 22 fix report. I then read every main-source file at HEAD in passes by area: build/manifest; engine/neigh and JNI; engine/common and portscan; engine/discovery; engine/serviceid; engine/identify and rules JSON; engine/vulns; data; wiring and actions; ui (home, detail, settings, vuln sheet); tests (JVM and androidTest); docs/qa. I read `git show e9e6641` in full. No Gradle, no adb, no downloads. One web lookup of AOSP `NsdService.java` (read only).

Constraint checks run:
- `git log --format=%B dd10ecf..e9e6641 | grep -i -E "co-authored|generated with"`: no matches (exit 1). Clean.
- Em-dash (U+2014) in tracked files and commit messages: none. Two en-dashes exist only inside a deliberate non-ASCII test fixture (`HttpTitleTest.kt:65-66`); acceptable.
- `targetSdk = 30`, `compileSdk = 35`, `minSdk = 26`: held. Repositories: `google()`, `mavenCentral()`, plus `gradlePluginPortal()` for plugins, which the plan itself specifies (plan line 93). No jvmToolchain. Espresso 3.7.0 bump was ruled in the ledger.
- Scan/Stop pinned: Home header and Detail scan bar are in `Scaffold.topBar`, outside the lists. Held.
- LAN-only traffic: holds for probes, with two caveats noted under Minor (system proxy, reverse DNS resolver).

## Strengths

- **Cancellation is real, not nominal.** `Probe.connect` closes the socket from an `Unconfined`/`UNDISPATCHED` watcher, then re-checks `isActive` after the watcher is disabled so a kept-open socket cannot leak (`engine/common/Probe.kt:39-62`). The ledger shows the brief's version failed its own test at 1716 ms and this one passes at about 20 ms; device QA saw Stop return within 1 s at 4064/65535.
- **Annotations are structurally safe.** `HostDao.updateScan` lists scan columns only, so `customName`, `status` and `note` cannot be overwritten by any scan path, and upserts are transactional with insert-race fallbacks (`data/Daos.kt:56-62`, `data/Repositories.kt:70-106`). The race test was shown RED against the whole-row update before going green. That is the right way to prove a concurrency fix.
- **MAC provenance is explicit.** `MacSource` ranks (netlink > NetBIOS > UPnP UDN > names/EUI-64) are applied both in the in-flight merge and in the stored row, and version-4 UUIDs are correctly refused as MAC sources (`engine/discovery/Ssdp.kt:21-27`).
- **Parsers are uniformly defensive.** Every LAN parser returns null on anything unexpected, reads are bounded by byte cap and an overall deadline (not just per-read timeouts), UPnP fetch is pinned to the responder's own IP with redirects off, and the HTTP title fetch follows at most one same-host redirect. These close SSRF-to-internet paths from hostile LAN devices.
- **NVD privacy is tested, not asserted.** API key only in a header, `instanceFollowRedirects = false` so the key never follows a redirect, a test that only the NVD host is contacted, a `cpe:`/`kw:` cache-key discriminator, CPE version sanitising, and OpenSSH `p` updates mapped correctly (device QA: 39 CVEs for OpenSSH 8.2).
- **ScanController** (generation counter, LAZY start so handles exist before the body runs, join-before-start so a cancelled run releases non-refcounted locks first) is small, correct and tested for exactly the races it guards (`ui/home/ScanController.kt`).
- **JNI helper is tight**: unbound `SOCK_CLOEXEC` netlink socket, `@Synchronized` around the static buffer, `DeleteLocalRef` per string, errno surfaced as a `Result` failure, and a device gate with before/after MAC counts (6 to 11) and zero `avc: denied`.
- **Process discipline**: every deviation in the ledger has a ruling and a cost-if-wrong; 40 small, well-titled commits; no attribution lines; no em-dashes.

## Verification of e9e6641 (D1 to D5)

| Defect | Verdict | Notes |
|---|---|---|
| D1 off-subnet host | **Addressed** | `RowRules.otherSubnet` against the same `sweepRange` the engine used; row reads "..., other subnet 10.0.4.0/24". Pure function, unit tested, device screenshot. The fix report's analysis that the Hue MAC came from the UPnP UDN (rank 2), not NEIGH, matches the code: NEIGH entries are filtered by `range.subnet.contains(ip)` (`DiscoveryEngine.kt:116`). Residuals (Minor): the "Found n" counter still counts off-subnet hosts, and Quick scan all will port-scan them (see M8). |
| D2 adoption on location grant | **Addressed for the reported repro, but the underlying identity is still unstable** | `ensure` adopts exactly one id-less row with the same gateway and prefix, inside the transaction; DaoTest 7/7 includes the case. However the adopted signature is BSSID-based, so the very next AP or band roam creates a fresh empty network row, and turning Location off in quick settings (or revoking the permission, which QA did) does the same in reverse. See C1. The fix also attaches a pre-grant shared row to whichever same-gateway network grants first; that ambiguity is inherent to the no-location mode and is acceptable once C1 is fixed. |
| D3 own phone | **Addressed, with a data-model wrinkle** | Row shows "This phone" / "Android device". But `ownIdentity()` is persisted as `identVendor = "This phone"`, so (a) the device "Look up vulnerabilities" button sends the keyword "This phone" to the NVD, and (b) if DHCP moves the phone, the old IP's row keeps "This phone" as its identity until that IP is re-identified. Prefer a display-time label and skip own-address rows in vuln lookup (Minor M10). |
| D4 Wi-Fi name hint | **Addressed as text** | "Wi-Fi (name needs location, see Settings)". Spec 7 also wants a tap on the placeholder to request the permission; not wired. When the permission is granted but system Location is off, the hint sends the user to Settings, which then says "Location permission granted" with no guidance (Minor M4). Note D4 now steers the Director into the BSSID mode that C1 describes. |
| D5 NAS icon/type | **Addressed** | `upnpManufacturer` field handled in `DeviceIdentifier`, `upnp-synology`/`upnp-qnap` at 95, loader field list and `SyntheticEvidence` updated, differential test and a DS220+ test added. |

No breakage found in e9e6641: schema unchanged (DAO queries only; `git status` clean after the device build, so the exported 1.json still matches), `HostRow` gained a defaulted field, all call sites updated. One test smell: `RowRulesTest` "garbage ip" calls `InetAddress.getByName("not-an-ip")`, which performs a real DNS lookup in a JVM unit test (network-dependent, slow offline).

## Issues

### Critical (Must Fix)

**C1. Network signature is unstable once location is granted, so marks and notes "vanish" on routine roaming (spec defect, spec 5.6).**
`NetworkSignature.of` hashes `bssid ?: ssid ?: ""` plus gateway/prefix (`data/NetworkSignature.kt:4-6`), exactly as the spec says. A BSSID identifies one radio, not one network: dual-band APs expose a different BSSID per band and multi-AP systems (the Director's gateway is a UniFi Security Gateway, so UniFi APs are likely) expose one per AP per band. With location granted, a roam:
1. changes `env` (bssid is in the data class), so `ScanController`'s watcher sees a new signature and aborts any running scan with "Network changed, scan stopped";
2. makes `ensure` miss by signature, find no id-less row (D2 already adopted it), and insert a new empty network row; the Home list empties and new scan results land in the new row, splitting the inventory.
Turning system Location off (common) or revoking the permission does the same in the other direction. This defeats the spec's success criterion "remembers investigated/interesting marks when the phone rejoins the network", and D4 now actively prompts the user into this mode. QA step 7 (switch networks) was not run, so nothing on device has exercised it.
Fix (needs a Director decision on the identity rule): key the network on values that do not change with roaming or the Location toggle, for example gateway IP + prefix + gateway MAC (already available from the neighbour table without any permission; it is in the table before a sweep, as the spike log shows `10.0.3.1 e0:63:da:26:f1:37` at "before sweep"), with SSID used only as the display name. If the Director prefers to keep SSID in the key, at minimum drop BSSID. Either way, exclude BSSID from the `ScanController` change test and re-run QA step 7.

### Important (Should Fix)

**I1. Client network inventories leave the phone via Android Auto Backup (spec 9).**
The manifest has no `android:allowBackup`/`fullBackupContent`/`dataExtractionRules`, so for targetSdk 30 Auto Backup is on: `netprobe.db` (hosts, MACs, notes, per-network inventories of client sites) and the `netprobe` SharedPreferences (including the NVD API key) are uploaded to the user's Google account backup and included in device-to-device transfer. Spec 9: "All data is stored on the device ... Nothing is exported in the MVP." For assessment work this is a confidentiality problem that the user will not know about. Fix: `android:allowBackup="false"` (one line). **Fix before merge.**

**I2. Port scans ignore network changes, and Quick scan all has no Stop (spec 5.3, 5.7; Review Focus 2).**
Only the Home discovery is watched by `ScanController`. `DetailViewModel.startScan` (a Full scan runs 2 to 6 minutes) and `HomeViewModel.quickScanAll` hold no reference to `env`, so after a Wi-Fi switch they keep probing the same IP addresses on whatever network the phone has joined. For the authorised-assessment use case that is traffic against a network the Director may not be authorised to scan. Quick scan all also has no Stop control at all (the ledger deferred "no cancel for quick-scan-all"), against spec 5.3 "Progress and Stop are always visible". Fix: give both jobs the same signature watcher (reuse `ScanController` or extract its watcher), and turn the Home Scan button into Stop while quick-scanning. **Fix before merge.**

**I3. Device identity regresses on every rediscovery and on narrow rescans.**
Spec 5.4.1: identification runs "after discovery and again after any port scan", on the host evidence "plus its open ports with ServiceInfo". In code, the discovery path passes only this run's liveness ports and `services = emptyMap()` (`HomeViewModel.kt:189-191`), and both port-scan paths pass only the ports found by that scan (`DetailViewModel.kt:84`, `HomeViewModel.kt:232`). Stored `PortEntity` rows are never fed back. So: port-scan a host, get "Linux (Ubuntu)" from the SSH banner or "Windows Computer" from 135+445; tap Scan on Home; every emission for that host overwrites the identity without those facts. A Custom scan of one port does the same. Fix: build `HostEvidence` from the union of stored ports/services and the current findings in all three paths (one helper in `HostRepository`). **Fix before merge.**

**I4. The `ssh-windows` rule can never fire on a real banner (green test, dead rule).**
`BannerParsers.ssh("SSH-2.0-OpenSSH_for_Windows_8.1")` yields product `OpenSSH`, version `for_Windows_8.1`, no comment. The rule matches `OpenSSH_for_Windows` against product and detail only (`DeviceIdentifier.kt:47`), so it never matches. The test passes because `SyntheticEvidence` puts the sample string into `detail` (`SyntheticEvidence.kt:28`), a shape the parser never produces. Side effect: the version `for_Windows_8.1` passes `SAFE_VERSION`, giving the CPE `...openssh:for_windows_8.1...`, which returns nothing and falls back to a noisy keyword search. Windows PC identification was also untestable on device (no Windows PC on the LAN). Fix: parse `OpenSSH_for_Windows_X` as product `OpenSSH for Windows` (or keep product `OpenSSH`, version `X`, detail `for_Windows`), and make the sshBanner synthetic evidence go through `BannerParsers.ssh` so the test exercises the real shape. **Fix before merge** (small, and Windows is one of the four classes QA step 8 names).

**I5. DNS servers are not shown (spec 2.1 item 7, 5.1).**
`NetworkEnv.dnsServers` is populated and never displayed anywhere. MVP item 7 lists "interface IP and prefix, gateway, DNS, SSID when permitted". For assessment work the resolver is useful (and it tells the user where reverse-DNS queries go). Fix: one more line in `NetworkPanel`. **Fix before merge** (trivial; otherwise record it as a spec deviation).

### Minor (Nice to Have)

- **M1. Per-probe errors are invisible (spec 8).** `Probe.connect` maps every unexpected exception, including `EMFILE`, to `Timeout`, so the port scan reports such ports as FILTERED and `ScanProgress.errors` is always 0; the UI never shows "errors: n". Low risk on the Pixel (high fd limit), real on older devices at concurrency 512. Add an `Outcome.Error` and surface the count.
- **M2. No logging at all.** There are zero `Log` calls in main code. Spec 8 says NsdManager failure codes and lock failures are logged. `onStartDiscoveryFailed` is an empty body, `isolated`/`softly` swallow silently. For a field tool this leaves no diagnostics when a strategy dies on a client network. Add `Log.w` in those catch sites.
- **M3. Neighbour-table unavailability is never shown.** `DiscoveryStatus.neighbourAvailable` is set but not read by any UI (spec 5.2 item 6 "so the UI can say so").
- **M4. Location UX.** Tapping the "name needs location" placeholder does not request the permission (spec 7); after granting in Settings, the SSID does not appear until the next link change (ledger Task 21 minor); permission granted with Location services off shows a misleading hint.
- **M5. TLS ports get no HEAD after the handshake** (spec 5.4 item 4). HTTPS services therefore never carry a `Server` product/version, so "Look up vulnerabilities" says "No product or version known" for every HTTPS port.
- **M6. NVD paging is oldest-first (spec defect).** `resultsPerPage=50` with no `startIndex` returns NVD's first page (oldest published first); the app then sorts that page by CVSS. When `total > 50` ("Showing 50 of 120") the severity counts and the top of the list describe the oldest 50 CVEs, not the worst or newest. Fetch the last page (`startIndex = total - 50`) or page through up to a cap.
- **M7. LAN fetches honour the system proxy.** `UpnpFetcher` and `HttpTitleFetcher` use `url.openConnection()`, which follows a Wi-Fi proxy/PAC if one is configured (common on corporate client networks), so UPnP/HTTP title requests for LAN IPs could be sent to an external proxy. Use `openConnection(Proxy.NO_PROXY)`. Related, spec-sanctioned: reverse-DNS PTR queries go to the network's resolver, which may be a public one.
- **M8. Quick scan all includes off-subnet hosts** (for example 10.0.4.20, reached via the router). For scoped client work, skip rows with `otherSubnet != null` or ask first.
- **M9. Old-Android XML hardening.** Android's Expat SAX does not recognise `disallow-doctype-decl` (the `runCatching` hides that), so DOCTYPE and internal entities are still parsed. External entities are blocked by `resolveEntity`, good. But on older Android builds whose Expat predates billion-laughs protection, an entity-expansion document inside the 64 KB cap can raise `OutOfMemoryError`, which `catch (_: Exception)` does not catch, crashing the app from a LAN device. The ledger's "CHECK ON DEVICE IN TASK 22" for this was not recorded. Cheap fix: return null if the body contains `<!DOCTYPE` or `<!ENTITY` before parsing.
- **M10. "This phone" persisted as vendor** (see D3): display-time label instead, and no NVD device lookup for the own address.
- **M11. `AndroidActionRunner.run` catches only `ActivityNotFoundException`.** A `SecurityException` from a non-exported or permission-guarded handler would crash; catch it too and fall back to copy.
- **M12. Ports never age.** Only OPEN results are stored and `PortEntity.lastSeen` is never read, so a port that has since closed is still listed as open after a rescan. Dim or flag ports not seen in the latest scan of that profile.
- **M13. Shared `PortScanEngine.progress`.** Detail shows Quick-scan-all's progress if both run at once (single singleton StateFlow).
- **M14. Search does not cover service names** (spec 5.7 "search field matching name, IP, vendor, service names").
- **M15. Identification work on the main thread.** Each discovery emission recompiles about 60 regexes on `viewModelScope` (Main). Fine at /24 today; precompile rules once in `DeviceIdentifier`.
- **M16. Unexplained "first Scan tap did nothing"** (fix report). Most likely the button is disabled until the combined Home state first emits (`enabled = s.env != null`, initial state has `env = null`), which is benign; confirm, since it touches the core flow.
- **M17. Detail pinned header can crowd out the list.** The expanded "Why?" reasons, status row, note field and scan bar all sit in the non-scrolling top bar; on a small screen or with the keyboard up the port list can shrink to nothing.
- **M18. No README** (build, sideload, why targetSdk 30, permissions, known limitations such as no OUI database). Useful before handing the APK to anyone else.
- **M19. Test gaps that only the device covers or nothing covers:** no JVM test drives `HomeViewModel.discover` or `DetailViewModel.scanHost` (I3 and D3 paths are untested); spec 10's "Detail status change persists" Compose test does not exist (device QA step 6 covers it); `AndroidNsdSource`, `NetworkEnvMonitor` callbacks, `Locks`, the SSDP socket, `AndroidActionRunner` and the Android SAX parser are device-only; QA steps 5 (no-handler fallback), 7 (network switch) and 8 (Windows PC) are unexercised.

## Deferred-minor triage

| Ledger group | Decision |
|---|---|
| T1 gradlew CRLF | DEFER (moot: committed blob has no CRLF) |
| T1 lint `abortOnError=false` | DEFER |
| T2 static C buffer not re-entrant | DONE (resolved by `@Synchronized`) |
| T2 unchecked JNI allocations, 2048 cap, NLM_F_DUMP_INTR, ipv4Re > 255, weak tests, spike baseline | DEFER (spike read before/after counts correctly) |
| T2 FindClass local ref, no NULL checks after NewStringUTF/NewObjectArray | DEFER (one call per run; OOM-only path) |
| T3 Subnet /0 size, ctor unvalidated, test gaps | DEFER (unreachable via `sweepRange`; prefix coerced 20..24) |
| T4 quote stripping/loopback/blank tests, publish() unguarded, candidate order, 169.254 accepted, onLost race | DEFER |
| T5 profile parser messages, Full allocates per access, table test gaps, loose names | DEFER |
| T6 errors counter dead | DEFER, but record as a spec 8 deviation (M1) |
| T6 keepOpen leak | DONE (3cbeebf) |
| T6 timing tests on 192.0.2.1, weak progressive test, unused imports, KDoc | DEFER |
| T7 shared progress per Sweep, coverage gaps, hosts.toList() | DEFER |
| T8 banner cancellation 1 s, copyOf per loop, parser edge cases, IPv6 Host, 5001 named HTTPS | DEFER (see I4 for the one parser case that matters now) |
| T9 resolve not stopped on timeout, early-cancel untested, DeviceInfoTxt untested, multicast unbound, discover failures silent, partial handle leak | DEFER (device is Android 17; silent failures folded into M2) |
| T10 root-device/TV tests, empty fields "", UUID regex, scheme check, broadcast flag | DEFER |
| T10 disallow-doctype on Android SAX (CHECK ON DEVICE) | DEFER with the one-line pre-check in M9; not checked on device |
| T10 HttpURLConnection not disconnected, deadline about 2x timeout | DEFER |
| T11 1024-byte buffer vs 64 names, unconnected socket/txId, test gaps | DEFER |
| T12 SNMP BER minors | DEFER |
| T13 charset not honoured, numeric entities, NBSP class, `<titlefoo>`, realm comma | DEFER |
| T14 detached-job cancel unobserved, network-dependent TEST-NET test | DEFER |
| T15 mdnsTxt per-type overwrite, SSDP fetch sequential, softly silent, neighbour-only hosts, untested caps | DEFER |
| T15 weak progress-flush test, sweep-collect isolation untested, strategy failures invisible | DEFER (M2) |
| T16 differential test proves firing not change, regex recompiled per host, vendor/model length, load() silent, Hikvision sample | DEFER (M15) |
| T16 gateway 95 beats SERVER 90, netbios-only Windows/UNKNOWN, canonicalVendor not on UPnP, Buds match host-android, ASUS test | DEFER |
| T17 toDiscovered lossy, no tests for ensure/setIdentity/TTL/Settings, stale ssid, Settings.update non-atomic | DEFER, except that the identity rebuild must include stored ports (I3) |
| T17 race-test assertion dropped, weak floor | DEFER |
| T18 port fallback breadth, thin tests, primary builds whole list, IPv6 unbracketed | DEFER |
| T19 non-IO labelled Offline, cache unguarded, wall clock limiter, 403 text with key, no body cap, missing totalResults | DEFER |
| T19 TTL proven on fake only, cpe/kw collision test | DEFER |
| T20 Locks construction, partial lock acquisition silent, Kotlin assert(), no wiring JVM test | DEFER (M2) |
| T21 row repeats IP | DONE (b38bbfc) |
| T21 Detail header with keyboard, tight fits, message masking | DEFER (M17) |
| T21 note save on Back, blank rename, vuln URL copy | DONE (63c1759) |
| T21 no cancel for quick-scan-all | **FIX BEFORE MERGE** (I2) |
| T21 mixed font sizes, vendor dedupe prefix, join delay, NAS icon | DEFER (NAS icon DONE in D5) |
| T21 Hue outside /24 | DONE (D1) |
| T21 Wi-Fi name not refreshed after grant | DEFER (M4), but re-test with C1 |
| QA D6 router model text, Pi-hole two rows | DEFER (two IPs sharing one MAC is a real dual-address host, not a merge bug) |
| QA D7 extra Synology ports | DEFER (Director to confirm) |

## Declined to judge

- targetSdk 30 and sideload-only distribution: a spec decision (spec 3), not a defect.
- arm64-v8a only ABI: intended (ledger Task 2).
- Release signing, minification, R8 rules: not in plan for a sideloaded MVP.
- App name, icon and applicationId: spec open item 11.2, Director's call.
- No OUI vendor database: spec open item 11.1, needs Director approval to download.
- EMFILE concurrency halving: deferred by the spec itself (section 8).
- NsdManager per-client request limit on Android 13 and lower with 30 concurrent discoveries: AOSP still applies a `MAX_LIMIT` check before choosing a backend, but I could not confirm the value, and the Director's Android 17 device demonstrably resolved types far down the list. Unverifiable without other hardware.
- Whether the seven extra Full-scan Synology ports (D7) are real: needs the Director.
- Discovered set versus the router's client list: needs the Director (spec definition of done).
- Scan performance on /22 and /20 networks: not measured, and I may not run the device.
- Test flakiness of the TEST-NET (192.0.2.1) timing tests on networks that route it: environment-dependent, I may not run Gradle.
- Visual polish and Material theming beyond the pinned-controls rule.
- An in-app authorisation or scope confirmation for client networks: not in the spec; raised only as M8 where existing behaviour reaches beyond the scanned subnet.
- IPv6 discovery and non-SNMP UDP probes: roadmap items.
- The HomeScreen Compose test that is skipped by design: ruled in the ledger.

## Recommendations

1. Decide the network identity rule (C1) with the Director; I recommend gateway IP + prefix + gateway MAC, SSID as display name only. Then re-run QA steps 1b and 7, including a band/AP roam and a Location toggle.
2. Land the small fix set in one round: I1 (`allowBackup="false"`), I2 (watcher on both port-scan paths, Stop for Quick scan all), I3 (one evidence helper used by all three identify call sites), I4 (Windows OpenSSH banner shape plus a real-shape test), I5 (DNS line). None needs a schema change.
3. Add a JVM test for the HomeViewModel discovery-to-identity path with fakes, so I3 and the own-phone rule are covered by more than device screenshots.
4. Before handing the APK to anyone else: M2 logging, M7 `Proxy.NO_PROXY`, M9 DOCTYPE pre-check, M11 catch, M18 README.
5. Get the Director's router client list comparison and the QA step 7 result recorded; both are part of the spec's definition of done.

## Assessment

**Ready to merge?** With fixes

**Reasoning:** The engines, persistence and cancellation work are well built and device-proven, and D1 to D5 are addressed without breakage. But the BSSID-based network identity (C1) breaks the "marks return when you rejoin" promise as soon as location is granted, client inventories go to cloud backup (I1), port scans outlive a network change (I2), and identities regress on rescan (I3); these must be fixed, with I4 and I5 small enough to land in the same round.
