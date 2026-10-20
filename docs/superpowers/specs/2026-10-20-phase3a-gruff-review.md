# Gruff review: NetProbe Phase 3A polish design

Reviewer: Gruff (Devil's Advocate, partial veto). Date: 2026-10-20.
Spec: `docs/superpowers/specs/2026-10-20-phase3a-polish-design.md` at 491b1a9.
Read against: the Radar spec, the extended discovery spec, the Phase 2 final review, and the code at master 491b1a9 (`RadarBlock.kt`, `Motion.kt`, `Nav.kt`, `ui/detail/*`, `ActionProvider.kt`, `engine/identify/*`, `engine/serviceid/*`, `engine/portscan/*`, `HttpTitle.kt`, `Entities.kt`, `Daos.kt`, `Repositories.kt`, `NetProbeDb.kt`, `HomeViewModel.kt`, `identify_rules.json`, `app/schemas/.../1.json`).

## Verdict: Proceed after fixes

The direction is right and most of the spec is careful. The sweep diagnosis is correct, the migration is additive, the copy has no em-dashes, and no new dependency is needed. Eight findings must be fixed in the spec before planning starts. Two of them can lose the Director's data or crash the app (M7, M8). One prints junk on his printer (M4). Three would make identification worse, not better (M5, M6, M3). Two would ship motion that breaks the Phase 2 performance rule or fails its own test (M1, M2). None of them needs a redesign, so no veto.

## Answers to the brief, in short

1. Motion. The sweep root cause is right: the current stops (`RadarBlock.kt:245`, alpha .55 at 0, .08 at 70 degrees, clear from 110) put the faint tail clockwise of the head, and `rotationZ` turns clockwise, so the tail leads. The new stops fix it. Afterglow can be computed in the draw phase without rebuilding the `drawWithCache` cache, but as specified it invalidates the nearest ancestor layer every frame (M1). The spring fits the single `fraction` if `transitionSpec` branches on the state pair, but the timing claim is wrong and not every consumer is listed for clamping (m1). The screen transitions need no new dependency: navigation-compose 2.9.0 `NavHost` takes all four transition lambdas. The ring pulse gate does not work as written (M2).
2. Active probes. They are not gated by profile, have no cap, cost up to 3.5 s per silent port, and send bytes to raw print ports (M3, M4). Stop latency is bounded by socket timeouts, not by cancellation (m8).
3. Identification. All 57 rules have a pattern, a weight and a sample. There is no flapping from ties, because `DeviceIdentifier.set` uses `w > slot.weight` in file order. The real flapping path is that the read paths drop the new fields (M5). App rules compete with platform rules at the same weights (M6). Two patterns over-match (m10, m11).
4. Migration. All ten columns are named, nullable, with no default, which matches Room's expectation for Kotlin `= null` fields. `1.json` has been committed once (278df9e) and matches the entities, so the Director's database should migrate in place. The tests as written can wipe it (M8).
5. Overrides. Precedence (`Effective`), search (through `IdentityLine`), the vulnerability query and reset per field are all specified. The app has no export feature, so "export of detected values" does not apply (Q4). One edge case pins "This phone" as a custom name (m12).
6. Phase 2 follow-ups. The strings and tests are there. Forget and Remove with Undo are not safe as designed (M7).
7. Standing rules. No new dependency, no downloads, no em-dashes (none in the spec), no disclaimers or prompts, targetSdk 30 unchanged. Scan and Stop stay pinned, and only the Stop pill stops. No violation found.
8. Testability. Most behaviours have a named test. Gaps: `autoAdvance = false` is only required for radar tests, the navigation test has no seam, and the pulse test contradicts the mechanism (m14, M2).

## MAJOR (must fix before planning)

### M1. Per-frame draws with no layer of their own re-record the parent display list every frame (1.4, 1.5, 1.6, 1.16, 12 row 2)

The arm is cheap because only a `graphicsLayer` property changes each frame. The spec adds three nodes whose `onDrawBehind` reads the angle or the pulse every frame: the blips (afterglow), the new outline `Spacer` (breathing) and the pulse `Spacer`. None of them has its own `graphicsLayer`. In Compose, a draw invalidation on a node without a layer goes up to the nearest ancestor layer (`NodeCoordinator.invalidateLayer` walks `wrappedBy`). That ancestor's whole display list is then re-recorded, which includes `DiscBackground`, the count text and possibly much more of the Home screen. The `drawWithCache` cache survives, but its `onDrawBehind` runs again every frame. That is exactly what Radar spec 3.6 set out to avoid. Test 4 (the recomposition counter) cannot see it, and the only check is the deferred `gfxinfo` run.

Scenario: a 60-device scan on the Pixel. The blips, outline and pulse invalidate every frame. Every frame re-records the disc, its rings, the count, and every sibling up to the first layer. Janky frames creep past 5 percent, and nobody finds out until the Director is back.

Fix: give each per-frame node its own `Modifier.graphicsLayer()`, or put the three in one shared layer that is separate from a static `DiscBackground` layer. State it in 1.16 as a rule: "every node whose draw reads a per-frame value has its own layer." Add the rule to the plan's review checklist. Correct section 12 row 2 to match.

### M2. The ring pulse gate measures eased progress, not time, and the effect that drives it can freeze a ring on screen (1.5, 9.2 test 2)

Two defects:

- The gate `pulse.value >= PULSE_MIN_GAP_MS / PULSE_MS` compares the eased value with 0.5. With `LinearOutSlowInEasing` (cubic 0, 0, 0.2, 1) the value reaches 0.5 at a time fraction of 0.2, which is 140 ms, not 350 ms. Test 2 says "found 4 to 5 at 200 ms: still 1", but by then the value is past 0.5, so a second pulse starts. Either the test fails, or the implementer quietly changes the test to fit.
- The spec does not say where `snapTo` / `animateTo` run. The natural home is the existing `LaunchedEffect(found, mode)` (`RadarBlock.kt:94`). A new `found` restarts that effect and cancels the running `animateTo` partway through. With the gate saying "otherwise do nothing", the `Animatable` stays at, say, 0.3. Since `p < 1` it is drawn, so a pulse ring is left frozen on the disc until the next pulse happens to be allowed.

Fix: keep the gate in time. Record the pulse start time from the frame clock (`withFrameMillis`) and compare elapsed milliseconds with `PULSE_MIN_GAP_MS`. Animate a linear 0 to 1 and apply the easing at draw time. Launch the animation from a `rememberCoroutineScope`, never from an effect keyed on `found`. Add a `PulseActiveKey` and extend test 2: with `found` changing every 50 ms for 1 s, every pulse finishes (`PulseActiveKey` false 700 ms after the last start), and the counts at 100, 200 and 500 ms match.

### M3. The unknown-port probe ladder is not gated, not capped, and multiplies the identify cost by 3.5 (2.2, A2)

Today a silent unknown port costs one passive read (1 s). The ladder adds a TLS peek (1.5 s) and a HEAD (1 s), so 3.5 s, plus a 2 s GET when the port turns out to speak HTTP. Nothing limits it by profile, so Quick and Quick scan all get it too. The Quick list (`ScanProfile.kt`) includes ports that are open and silent on ordinary LAN hosts but are in no fixed set: 135, 139, 445, 1025 to 1029 and 49152 to 49157 on Windows, plus 7, 9, 13, 37, 111, 2049 and 5357. `MAX_PARALLEL_IDENTIFIES` is 8, and Quick scan all is serial host by host (`HomeViewModel.kt:216-222`).

Scenarios:
- A Windows PC with 135, 139, 445 and six dynamic RPC ports open has nine silent unknown ports. That is about 3.5 s of extra wall time per host per Quick scan. Over 30 hosts, Quick scan all goes from about a minute to several.
- Full scan of a device that answers SYN on every port (some firewalls, ISP hubs in DMZ mode, tarpits). That is 65535 x 3.5 s / 8, about 8 hours of identify tail, against about 2.3 hours today. The progress line has said 100 percent the whole time (m16).

Fix: specify the gate and the budget.
- The ladder runs only on Detail scans (Quick, Custom, Full). Quick scan all keeps the passive read.
- Cap active probing at a fixed number of unknown ports per host per scan (suggested 32, lowest port first), as a named constant.
- When more than a threshold of ports are open (suggested 512), skip active probing for that host entirely.
- Define "nothing was read" as zero bytes. Today `classifyUnknown` returns PORT_TABLE for non-printable bytes, and that case must not go on to TLS.
- Add these JVM tests: Quick scan all sends no TLS or HEAD to an unknown port; the cap is honoured; the threshold is honoured.
- Update A2 with the real cost and the gate.

### M4. TLS ClientHello and HEAD sent to raw print ports print junk pages (2.2)

Port 9100 is in the Quick list and in no fixed set, so it falls into "every other port". JetDirect raw printing prints whatever bytes arrive. The passive read is harmless, but the TLS ClientHello and the `HEAD / HTTP/1.0` that follow are printed. nmap excludes 9100 to 9107 from version detection by default for exactly this reason. 515 (LPD) is also in the Quick list.

Scenario: the Director runs Quick on his HP printer, or Quick scan all. The printer prints one or two pages of binary junk and "HEAD / HTTP/1.0 Host: 10.0.3.x" every time.

Fix: add `NEVER_ACTIVE = 9100..9107 + 515` (and, given client sites, 102, 502, 20000, 44818, 47808 for industrial controllers) to `ServiceIdentifier`. These ports get the passive read only. Add a JVM test with a fake reader that fails the test if any byte is sent to those ports.

### M5. The read paths drop the six new port fields, so identity flaps between port scans and radar scans (3.1, 3.3)

`PortRepository.servicesFor` (`Repositories.kt:141`) and the Detail row mapping (`DetailViewModel.kt:46`) build `ServiceInfo` from five columns only. The spec changes `mergePort` and `HostEvidenceBuilder` but never names these two read paths. Discovery always rewrites the identity from `servicesFor` (`HomeViewModel.kt:194-195`). If `servicesFor` leaves out `title`, `server`, `certCn` and the rest, the next radar scan re-identifies without the port evidence and drops the identity that the port scan just set. The next port scan raises it again.

A second path makes this worse. `mergePort` (`Repositories.kt:138`) always overwrites `serviceName` and `confidence`. If one HEAD times out on a busy NAS, a port that was CONFIRMED "HTTP" goes back to the table name at PORT_TABLE. The `service` rules stop firing, `PortUris.guess` loses http, and the row's word flips from "Open" to "Copy".

On the brief's flapping question: ties do not flap. `set` uses `w > slot.weight` in file order, ports are taken in ascending order, and stored evidence otherwise only grows (`applyScan` keeps old values on null). The `setIdentityIfNotWeaker` guard is close to a no-op for the same reason. The 9.4 check "an identity never drops after a Quick scan" proves little, because the drop happens on discovery.

Fix:
- Name `servicesFor` and the Detail mapping in 3.1 and require all six fields on both.
- `mergePort` never replaces a CONFIRMED name with a PORT_TABLE result. A new CONFIRMED name still wins.
- Add a repository-level test: a port scan sets an identity from a port title; a discovery run without that title follows; the identity is unchanged. Add a second test: a flaky rescan keeps "HTTP" CONFIRMED.

### M6. App rules take the device's model and type at platform weights (3.5)

Many of the new rules describe software that runs on a box, not the box itself: Jellyfin, Emby, Plex (`p-plex-http` at 85), Pi-hole, AdGuard, Frigate, Home Assistant, Grafana, Portainer, Node-RED, Nextcloud, Z2M and Kuma. They set `model` and often `type` at 70 to 90. The platform rules (`p-dsm-title`, `p-unraid-title`, `p-truenas-title`, `p-omv`, `p-esxi-title`) are also 90. They come later in the file, so app rules win ties. Now that `httpTitle` reads every port's title, every self-hosted app becomes a candidate device identity.

Scenarios:
- An Unraid server running Jellyfin and Frigate. `p-jellyfin` (90, earlier) takes the model, so the row reads "Jellyfin", not "Unraid".
- A Synology running Jellyfin reads "Synology Jellyfin". The device vulnerability search then queries NVD for "Synology Jellyfin".
- The Director's Windows PC running Plex has `p-plex-http` (85, SERVER) beating `ports-windows` (70, COMPUTER), so it reads as a server.
- A Raspberry Pi running the UniFi Network app has the existing `title-unifi` (ROUTER 70, earlier than `host-rpi`) now fed by the port 8443 title, so it becomes a Ubiquiti router.

Fix: separate platform from app. Platform and appliance rules (DSM, SRM, QTS, TrueNAS, Unraid, OMV, ESXi, Proxmox, iDRAC, iLO, FRITZ!Box, routers, printers, cameras) keep their weights and set type and model. App rules set neither type nor model above 60, or set nothing and only add a reason line. Review the existing `title-unifi`, `title-plex`, `port-plex`, `title-hass` and `title-pihole` under the same rule, because 3.2 widens them. Add precedence tests to 9.1 test 14 for named mixed hosts:
- Synology with Plex, Jellyfin and Home Assistant stays Synology NAS.
- Unraid with Jellyfin and Frigate stays Unraid NAS.
- A Windows PC with Plex stays a Windows computer.
- A Pi with the UniFi app is not a router.

### M7. Undo restore can collide with recreated rows and crash the app (5.3, A6)

- Forget deletes the network row, but the env collector calls `NetworkRepository.ensure` on every env emission (`HomeViewModel.kt:150-155`). The next emission inserts a new row with a new id and the same signature. Emissions come from a LinkProperties change, a DHCP renew, or `refreshEnv` on resume when the value differs. So the claim "`networkId` is not changed by Forget" is false. Undo then inserts the old row and hits the unique `signature` index with `OnConflictStrategy.ABORT`. That throws on `appScope`, which has no `CoroutineExceptionHandler`, so the process crashes.
- Remove device is disabled only while a Detail scan runs. A radar scan or Quick scan all started on Home keeps running in `HomeViewModel`'s scope while the Director is on Detail. Discovery can re-insert the host with a new id (unique `networkId, ip`). Quick scan all, iterating its snapshot of hosts, upserts ports under the deleted `hostId`, leaving orphans since there are no foreign keys. Undo then ABORTs on either unique index and crashes.

Scenario: the Director starts a radar scan, opens a device, taps Remove device, and is back on Home within a second. Discovery finds the IP again. He taps Undo and the app dies.

Fix:
- Forget keeps the network row. It deletes the hosts, ports and manual ranges and clears `lastRunAt`, so `networkId` really is stable.
- Disable Remove device and Forget while any scan runs (radar, Quick scan all, Detail), and say so in 5.3 and test 15.
- Run restore in one transaction that treats a conflict as "already back": skip it and show "Could not undo, the device was found again" (a new string for section 7).
- Give `appScope` a `CoroutineExceptionHandler`.
- Add these tests to 9.3: restore after the network row was recreated; restore after the host was rediscovered with a new id; no orphan ports after Remove during Quick scan all.

### M8. The migration tests can wipe the Director's database on the Pixel (9.3, 9.4)

The standing QA rule says Bugsy runs the instrumented suite on the Director's Pixel, inside the app's own data directory. The Phase 2 final review already noted that `HomeScreenTest` writes into the live database. `NetProbeDb.open` hard-codes `"netprobe.db"`. 9.3 test 2 says to open the migrated database "through `NetProbeDb` (the real builder)". `MigrationTestHelper.createDatabase(name, 1)` deletes any existing file of that name before it creates schema 1. If the test passes the real name, or calls the real `open(context)`, his marks, notes and port history are gone.

Fix:
- `NetProbeDb.open(context, name: String = DB_NAME)`. The migration tests use `"migration-test.db"` and delete only that file. Add a one-line test asserting the test name is not `DB_NAME`.
- 9.4 gains a first step before install-over: copy the live database off the device (`adb shell run-as com.northwalessolutions.netprobe cat databases/netprobe.db > backup`, plus the `-wal` and `-shm` files).
- State in 9.3 that no instrumented test may open `DB_NAME`.

## MINOR

- **m1 (1.7, 9.2 test 3). The spring timing is wrong and the clamp list is incomplete.** With damping 0.7 and stiffness 400, the natural frequency is 20 rad/s. The overshoot is 4.6 percent (fine), but the spring settles to the 0.01 threshold in about 330 to 400 ms, not about 600 ms. Every place that reads `fraction` must be sorted into clamped or unclamped. A negative value in the pill's `layout` height (`RadarBlock.kt:174`) throws. `graphicsLayer { alpha = fraction }` (line 175) and `Modifier.alpha(1f - fraction)` (line 159) leave 0..1. Fix: list them. Clamp `areaHeight`, pill height, pill alpha, text alpha and `centreX`. Leave `diameter`, `target` and `stroke` unclamped. Correct the duration, and add "no exception, pill height never negative" to test 3.
- **m2 (1.9, 1.16). The arm jumps during screen transitions.** When Home stops being RESUMED, the transition is disposed and the arm snaps to `STATIC_ARM_DEG` (40). On return it restarts from 0. The afterglow snaps with it. During the 240 ms slide this is a visible jump. Fix: keep the last angle in a remembered float, show it while not resumed, and restart the infinite transition from that angle to that angle plus 360.
- **m3 (1.16). "QuickArc is never composed with the arm" is no longer guaranteed.** The arm now lives through the settle, while Home's "Quick scan all" is enabled as soon as the mode is SETTLED. Fix: disable Quick scan all until the settle ends, or drop the claim.
- **m4 (1.15). Reduced-motion blips do not match normal blips.** Under reduced motion blips sit at core alpha 0.8, a value that is not a token. Normal rest is 0.45 and lit is 1.0. Fix: pick one value and make it a token.
- **m5 (1.17, 9.2 test 4). `RadarFractionKey` "only in semantics" is moot.** `fraction` is already read in composition (`diameter`, offsets), so `RadarBlock` recomposes every frame of the expand and the settle. That is existing and sanctioned. Fix: say so, and keep test 4's 60 frames inside steady SCANNING.
- **m6 (2.3). TLS-only services get "Open in browser" as the primary action.** The ladder makes 993, 995, 465, 636, 853, 8883 and 8009 (Cast) "TLS", and `guess` rule 1 maps TLS to https. Fix: only "HTTPS", or a successful GET, gives https. A bare "TLS" gives no primary, and its "Open with..." still defaults to https.
- **m7 (2.4). Try as chips open an empty chooser when no app handles the scheme.** `AndroidActionRunner` wraps `OpenWith` in `createChooser`, which always starts, so tapping "rdp" with no RDP app shows "No apps can perform this action". The "IP copied instead" fallback never fires. This goes against D9. Fix: build chips only for schemes where `resolver.hasHandler` is true. The manifest `<queries>` already lists them.
- **m8 (2.2, 9.1 test 10). Stop latency is set by socket timeouts.** `runInterruptible` interrupts the thread, but a blocking `java.net.Socket` read on Android ignores interrupts, and the coroutine waits for the block to return. That is up to 2 s for a GET. `ScanController.start` joins the previous job, so "Scan" after "Stop" waits too. Fix: close the socket (or `disconnect()`) from `invokeOnCancellation`. Add one JVM test against a silent `ServerSocket` asserting cancellation within 300 ms.
- **m9 (2.2). The ladder skips an IMAP greeting.** After a TLS handshake the ladder sends a GET, but IMAPS and POP3S speak first inside TLS, so their greeting is never read. Fix (optional): read first for 300 ms after the handshake before sending GET.
- **m10 (3.5). Router patterns match inside words.** `p-router-title` and `p-isp-hub` match substrings with IGNORE_CASE. "Attendance" contains "tenda", "Pegasus" contains "asus", and "Aztec" contains "zte", each giving ROUTER 75. `$1` keeps the page's case ("Netgear" vs "NETGEAR"), and `CANONICAL_VENDORS` covers only printers. Fix: add `\b` boundaries, add the router vendors to the canonical map, and add negative tests for these three titles.
- **m11 (3.5). Two rules give the wrong OS.** xrdp on Linux and Raspberry Pi answers the X.224 Connection Confirm, so `p-rdp` (80, Windows, COMPUTER) labels a Pi as Windows over `ssh-distro` and `host-rpi` (70). `p-openssh` (40) labels a Mac with no hostname evidence as Linux. Fix: `p-rdp` sets os only, at 60 or less, and add an xrdp precedence test. Accept the macOS case or drop `p-openssh`.
- **m12 (4.3). Two Edit device edge cases.** For the own phone, the Name field starts as "This phone" (the displayed name), which is never equal to the detected name. Save with no edits therefore pins "This phone" as `customName`. Separately, nothing lets the Director override a wrong detected type to "unknown". Fix: start the Name field from `customName ?: detected`. Either add "Not set" as a choosable type override, or state that it is not supported.
- **m13 (5.5). Stale seeding has an ordering race.** The env collector runs on every env emission. If it seeds `latestRun` from the row before `discover` has written `markRun`, it reverts `latestRun` mid-run and nothing dims. Fix: `markRun` before `latestRun.value = runId`, and seed only when the network id changes. See Q1.
- **m14 (9.2). Two test gaps.** `autoAdvance = false` is required only for "radar tests", but tests 6 to 12 sample mid-animation too. Test 7 has no seam: `NetProbeNav` takes the `AppContainer` and builds the real screens, which run a real discovery into the live database (Phase 2 note). Fix: require `autoAdvance = false` for every motion test, and add `NetProbeNavHost(nav, home, detail, settings)` with composable slots for the test.
- **m15 (3.3, A3). The not-weaker guard rarely matters.** Once M5 is fixed, the guard only matters in the window where a concurrent discovery write races a port scan. Keep it, because it is cheap, but say what it protects. Do not present it as the fix for identity drops.
- **m16 (1.14, 2.2). The progress line reads full while identification is still running.** It follows the port-scan engine only. With the ladder, the identify tail runs on after the line reads 100 percent while the pill still says Stop. Fix: count completed identifies in the progress (done = ports probed + identifies finished), or show a second phase in the progress text.
- **m17 (4.2, 5.3). Two notice and search details.** 4.2 says Home search matches "the identity line and vendor", but it matches name, IP, identity line and MAC (`HomeViewModel.kt:91`). Overrides are still searchable through `IdentityLine`. Correct the text. In 5.3, an Undo notice "takes the notice line over any other message", but extended notices stay until dismissed (Radar spec 4.4). Say whether the earlier notice comes back once the Undo is resolved.

## QUESTION

- **Q1 (5.5). Does a stopped or killed run count?** `markRun` is written at run start. A run stopped after 2 s, or killed by the system, then dims nearly every host across restarts until the next full run. Should the stored run id be written only when a run completes? The in-session rule could stay as it is.
- **Q2 (1.4). Should blips dim between sweeps?** `BLIP_REST_ALPHA` 0.45 dims every blip for two thirds of each turn. Today they are full strength. This is A1 territory, but it is a visible change to what the Director already liked, so he should see it on the first recording.
- **Q3 (A2). Does he want a setting for the active probes?** They send TLS, HTTP, RTSP and RDP requests to every open port, including on client sites during segmentation work. The aim is not to add a restriction. The question is whether he wants a Settings toggle for active service probes, default on, like the other discovery toggles.
- **Q4 (brief item 5). Is any export expected?** The app has no export, so "export of detected values" does not apply. If export is expected in this phase, it is unscoped.

## What holds up

- The sweep stops, the head line and the direction fix are right and pinned by a pure test.
- The migration is additive with no destructive fallback. The `room-testing` and androidTest assets wiring needs no new dependency.
- `Effective` keeps overrides out of `HostEvidence`, so "Why this identity" stays honest.
- RTSP and RDP probes are correct byte for byte (TPKT 19 bytes, Connection Confirm code `D0` at offset 5).
- TLS goes before HEAD on unknown ports, and the reason given is the right one.
- The copy table, decision table and assumptions list are complete and free of em-dashes.
