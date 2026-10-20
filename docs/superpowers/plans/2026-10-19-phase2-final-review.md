# NetProbe Phase 2 final whole-branch review (Dot)

Branch `phase2`, range `569cd8f..885d08b` (38 commits), reviewed 2026-10-20 against the plan, both specs, the SDD ledger, the 20 task reviews, both QA records and the MVP final review.

## Verdict: Ready after fixes

The branch is in good shape. The engine contract between Tasks 15, 16 and 18 holds. Cancellation goes through structured concurrency, the producer's `finally` runs before the view model reads the status, and the Task 16 I1 race is closed because the env collector's compare-and-set does not suspend. The radar's infinite transitions exist only while the screen is resumed, and `KeepScreenOn` leaks no Activity or Window. The bind-failure path is rethrown at every layer that could swallow it, and no new code path sends traffic off the LAN beyond the spec-sanctioned TEST-NET pre-check. I ran `./gradlew :app:testDebugUnitTest --rerun` myself: 382 tests executed, 0 failures (the first, non-forced run was UP-TO-DATE from cache, so I forced it). Three things stop me passing it as is. First, the extended-off path is not the MVP byte for byte: the neighbour-table MAC rules now apply with the toggle off. Second, port scans of off-subnet rows (Detail and Quick scan all) are never bound to Wi-Fi, so the VPN leak the spec closes for discovery is still open for the follow-up scans, and Phase 2 is what fills the list with those rows. Third, the per-range canary can wrongly mark a real range as PHANTOM on topologies the Director's own LAN already has. All three are small fixes, or for I1 and I2 a ruling from the Director, and two trivial deferred minors should go in with them.

## Findings

### Important

**I1. The toggle-off path changes MAC assignment (standing rule: extended-off must be the MVP byte for byte).**
`engine/discovery/DiscoveryEngine.kt:249-256` runs `NeighbourRules.apply` (`engine/discovery/NeighbourRules.kt:15-21`) on every run, toggle on or off. The MVP (569cd8f, `DiscoveryEngine.kt` neighbour block) gave every in-range neighbour entry its MAC. Now two cases change: a MAC seen on 3 or more IPs gets no MAC (proxy-ARP filter), and the gateway's MAC on any other IP is dropped. Extended spec 5.2 step 4 and 6.2 sanction the rule, but the same section says "With the toggle off nothing changes", and Bugsy's Task 18 trace lists these as "the only MVP-visible changes" without a ruling on the toggle. `DiscoveryEngineExtendedTest.kt:239-245` then pins the drift with `enabled = false`.
Failure scenario: on a client site, phones behind a MAC-NAT range extender (three or more clients sharing the extender's MAC) are now listed with no MAC, no OUI vendor and weaker identification. The Director's installed build is toggle-off. Rows already stored keep their MAC because `updateScan` never overwrites with null, so his current data is safe. New networks are affected.
Fix size: small. Either gate on `options.extended.enabled`, so toggle-off uses the MVP loop and the test is flipped, or the Director rules that the rule is a deliberate correctness fix for both paths and the standing rule is amended. Needs a decision before merge.

**I2. Port scans of off-subnet rows are not bound to the Wi-Fi network.**
`ui/home/HomeViewModel.kt:226-233` (`quickScanHost`) and `ui/detail/DetailViewModel.kt:60-67` (`scanHost`) call `PortScanEngine.scan` with no `BoundNetwork` in context. `binderFor` is used only for discovery (`HomeViewModel.kt:179`). Extended spec 5.5 explains why routed probes must be bound: a VPN that routes RFC1918 "would carry routed probes to the far end, so on a client site the app could probe the Director's home or another client's network". Spec 11.8 keeps off-subnet rows in Quick scan all but says nothing about binding them. This is MVP final-review M8 made worse: the Director's LAN now holds 17 off-subnet rows after the QA runs (QA record note D).
Failure scenario: Tailscale with subnet routes, or a client VPN, is up. The Director taps a 192.168.1.79 row found by an earlier extended run and runs Full, or taps Quick scan all. Every connect follows the VPN to whatever 192.168.1.79 is at the far end.
Fix size: small to medium. For a host outside the local swept range, run the scan inside `withContext(BoundNetwork(binder))` with `binderFor(env)`. If no binder is available, skip that host and count it in the existing "could not be scanned" message. Add one fake-binder test. If the Director prefers to defer it, record a ruling.

**I3. The canary can mark a real range PHANTOM, most likely on ranges the gateway probe has just proven real.**
`DiscoveryEngine.kt:110-114` picks the lowest and highest addresses not yet known and calls the range PHANTOM if both connect or refuse on a random high port. `GatewayProbe.run` (`GatewayProbe.kt:113-130`) returns only the hit subnets, so the `.1` and `.254` that just answered on 80 or 443 are still "unknown". They are exactly the pair the canary probes.
Failure scenario: a range whose `.1` and `.254` are both live hosts that send TCP RST for closed ports (common for Linux-based routers and Windows hosts without a firewall). The Director's own 192.168.1.0/24 has a live `.1` (UniFi) and `.254` (Hub Manager). It survived QA only because the UniFi drops closed ports. On a client site the range is reported "skipped: every address answered", a false finding in segmentation work, and nothing in it is stored.
Fix size: small to medium. Have the gateway probe also return the addresses that answered, and add them (plus evidence IPs) to the canary's `known` set before `fresh` is built. Add one engine test where `.1` and `.254` refuse the canary but are gateway hits.

### Minor

- **M1.** `HomeViewModel.kt:184-185`: `extendedRunActive = true` is set before `c.locks.acquire()`, outside the `try`. If `acquire` throws, the window keeps `FLAG_KEEP_SCREEN_ON` until a later extended run ends. Fix: trivial (move it inside the `try`). Deferred from Task 19.
- **M2.** `ui/settings/SettingsScreen.kt:74`: `local` is `remember(s.maxPrefix)`, not keyed on the network. `ExtendedSection.kt:100`: `notes` is not keyed on the network either. Scenario: Settings is open during a Wi-Fi switch, the editor shows the new network's list, but "This is your own network" is checked against the old subnet, so it refuses the old network's /24 and accepts the new one's own range (harmless at derive time, but a wrong inline message). Fix: trivial (`remember(network?.id, s.maxPrefix)`). Deferred from Task 17.
- **M3.** `DiscoveryEngine.kt:85` resets `status` only once `discover` runs, while `HomeViewModel.kt:132` exposes `status` as soon as the controller says scanning, and `ui/home/HomeScreen.kt:34` latches it. Scenario: tap Scan after a 33-host extended run. For the `ensure` plus gateway nudge (up to 300 ms), the disc can show "33 found" and "identifying 33 devices" from the previous run, then drop to 1. Likely, not seen on device. Fix: small (null `status` until the run's own reset, or reset the engine status at scan start).
- **M4.** `DiscoveryEngine.kt:270-271` turns PENDING ranges into CANCELLED, so a range that never started reads "stopped" (`ExtendedNotices.kt:57`), and the `OUTCOME_NOT_SWEPT` branch for PENDING is unreachable. After Stop the headline reads "0 other ranges checked" (QA note C). This is a Task 15 and 16 contract mismatch. Fix: small, once the Director picks the wording.
- **M5.** `ExtendedSection.kt:52-55`: `ManualCap.beyondCap` counts /24 units that overlap the local range, but `CandidateRanges.kt:33` skips them. A manual /20 that contains the local /24 marks one entry too many "Beyond cap, will be skipped". Fix: small (pass `local` and skip overlapping units).
- **M6.** `HomeViewModel.kt:127-128` reads `settings.state.value` (`extEnabled`, `maxPrefix`) inside `combine` without it being an input. Turning the toggle on or off does not regroup manual-range rows until some other flow emits. Fix: small (add `c.settings.state` to the combine).
- **M7.** `DiscoveryEngine.kt:170-172`: the TEST-NET pre-check runs its 4 probes one after another (about 2.4 s at 600 ms, measured 3.5 s in QA check 3), and `ProgressLine.kt:15` shows "preparing next range" before any range exists. Fix: small (probe in parallel, and the Radar spec owner picks a pre-check string).
- **M8.** `ui/home/HomeContent.kt:109,117`: at the end of a long extended run, the notice (up to 4 lines) and the 48 dp range summary appear above the list in the same frame. This combines the Task 6 deferred "late notice shifts rows under the finger" with Task 16's multi-line notices. Scenario: the Director is scrolling at second 86 and his tap lands on the wrong host (the Task 6 stray-Detail oddity). Fix: medium. Director's eye.
- **M9.** `HomeComponents.kt:143` `NOTICE_MAX_LINES = 4`: in the worst case (large network, probe found none, two quiet ranges, roll-ups, stopped, cap) the last line, "raise the cap in Settings", is the one that gets cut off. Fix: small (put the cap line first or raise the limit). Deferred from Task 16.
- **M10.** `HttpTitle.kt:66`: a bind failure inside `Network.openConnection` comes back as "no title" instead of `BindFailedException`. It fails closed, so nothing leaks, but the phase does not end with SKIPPED_BIND_FAILED for that cause. Information only.
- **M11.** `HomeViewModel.kt:201` leaves a production `Log.i` for Task 20 (no personal data). Keep it as diagnostics (MVP M2 asked for logging) or remove it. Trivial.
- **M12.** Spec-sanctioned, but compounded by Part B: the headline `totalHosts` and Quick scan all both include every off-subnet row from earlier extended runs, even with the toggle off ("33 devices" after a 16-host toggle-off scan). This is extended open item 13.4 (local-only option). Director's call.

### Test honesty

- Unit suite: executed by me with `--rerun`, 382 passed. The instrumented counts (57 and 67 started, 1 skipped by assumption) come from the QA records; I did not run them, by instruction.
- `DiscoveryEngineExtendedTest.kt:239-245` asserts the toggle-off MAC drop. It is green because it pins the I1 drift, not because it guards the MVP.
- The view-model wiring (`discover` finally, env collector ordering, `extendedRunActive` set and clear) is covered only through the pure helpers `RunEnd`, `RunMemory` and `ScanFinish`. No test would catch a reordering of `HomeViewModel.kt:152-154` or `:203-206`. This is MVP M19 carried over.
- `TypographyTest` cannot detect Plex failing to load (Task 1 deferred). `HomeScreenTest` runs a real discovery into the Director's live database (Part A QA note H); marks and notes were untouched, but the run does add rows.
- No test feeds the canary a pair of real refusing hosts (I3), and there is no gateway-probe bind-failure test (Task 15 deferred).

### Security and privacy

- Off-LAN traffic: NVD (user-tapped, unchanged), plus the spec-sanctioned TEST-NET pre-check (192.0.2.1 and 198.51.100.1, bound to Wi-Fi, which leaves towards the ISP) and gateway-probe misses that take the default route (spec 4.3 says so). Reverse DNS is skipped off-subnet by ruling. HTTP title follows only one same-host redirect, with NO_PROXY on both paths. Nothing else new.
- Stored data: manual ranges live in `extManual.<networkId>` in the private SharedPreferences, one key per network, user-bounded, and keyed by Room AUTOINCREMENT ids that are never reused (no network delete exists). Off-subnet rows grow by at most cap x 254 per run, phantom-guarded. Last-run range lists are memory only.
- The open hole is I2 (unbound off-subnet port scans).

## Deferred minors to fix before merge

1. Task 19: set the keep-screen-on flag inside the `try` (M1).
2. Task 17: key the Settings own-network subnet and editor notes on the current network (M2).
3. Task 16 and 15: never-started ranges read "stopped" and "0 other ranges checked" after Stop (M4). Fix together with the Director's wording choice.

The other deferred minors in the ledger stay deferred. None of them compound into a defect beyond M8 and M9 above, and those need the Director's eye rather than a fix now.

## Needs the Director

- Extended QA check 7 (switch Wi-Fi mid extended run) and check 10 (VPN routing private ranges): not run.
- Confirm that the grouped list and the ranges in the Part B QA table match his network (10.0.4, 10.0.0, 10.0.2, 192.168.1 and 192.168.2).
- Radar spec section 11 items 11.1 to 11.6 on the screenshots (arm stops in background, chips collapse rule, router teal, edge colours and `border`, teal switches, section 12 items), plus the deviations: stale alpha 0.6 and the blip spiral.
- The icon: the splash shows `@android:drawable/ic_menu_search` on a white disc (Part A QA note A). Standing rule: he approves icons.
- Rulings on I1 and I2. Wording for M4, M7 and "Scanned in N s" after Stop (Part A note C). Stale dimming lost after restart (Part A note B). Add range under the keyboard (Part B note B). "/16" wrapping in the probe text (note E). TalkBack is still not run.

## What the Director should test first

1. With the toggle off (his installed path), run a scan on his LAN. Compare the device count and each row's MAC and vendor against the MVP and his UniFi client list, and check that any repeater or mesh clients still show a MAC (I1).
2. Turn extended on with Tailscale or another VPN up and scan (check 10). Then open an off-subnet row, run Quick, and watch where the traffic goes (I2).
3. Switch Wi-Fi in the middle of an extended run (check 7). Expect "Network changed, scan stopped", no range summary, and the screen no longer held on.
4. Stop in the middle of a range and read the summary and notice wording (M4).

## Re-review after fixes (885d08b..0a02460, six commits)

### Verdict: Ready to merge

I re-ran the JVM suite at 0a02460 with `--rerun`: 390 tests executed, 0 failures. The device figure (69 run, 0 failed, 1 skipped by assumption) comes from Ada's report. The diff has no em-dashes and no attribution lines. Each finding is fixed as described, and I found no regression.

- **I1, fixed.** With the toggle off, `DiscoveryEngine.kt:255-259` runs the 569cd8f neighbour loop line for line. It uses the same `InetAddress.getByName` cast, the same range check (`local` is `range.subnet`), and the raw MAC with `MacSource.NEIGH`. `NeighbourRules`, the evidence step and `neighMacs` now run only with the toggle on. The flipped test is honest. The old assertion now runs with the toggle on, which is where the rule belongs. The new toggle-off test asserts the MVP outcome: three IPs sharing one MAC keep it, and an extender IP carrying the gateway's MAC keeps it. Ada reports this test went RED against the pre-fix code.
- **I2, fixed.** `ui/home/ScanBinding.kt:19-23` returns the plain block when the toggle is off, when there is no env, or when the IP is inside the swept local range. So a local row is never bound, and with the toggle off nothing changes from the MVP. Otherwise it binds or throws `BindFailedException`, and never falls back to an unbound scan. `PortScanEngine`'s flow inherits `BoundNetwork` from the collector, and `TlsPeek` now binds too. Both Quick scan all (`HomeViewModel.kt:327`, which skips the host and carries on) and Detail (`DetailViewModel.kt:208`) catch the failure and say so. All four tests check the binder the block actually sees and how many lookups were made. One residual, accepted by the toggle-off rule: with the toggle off, rows left from an earlier extended run are still scanned unbound, exactly as in the MVP. This goes to the Director as information, not as a blocker.
- **I3, fixed.** The canary pool now excludes every address that answered the gateway probe, plus the .1 and .254 offsets (`DiscoveryEngine.kt:111-114`). The fallback cannot leave a range unguarded. With one canary left, a single answer marks the range PHANTOM, which is stricter than before. With none left, `canaryPhantom` returns false and the sweep guard (layer 3) still judges the first 64 addresses. A /24 always has at least two clean addresses, so the fallback only applies to small manual ranges. Spec 4.7 has been updated to match.
- **M1, M2 and M4, fixed.** The keep-screen-on flag is now set inside the `try` (`HomeViewModel.kt:309`). The Settings `local` subnet and the editor notes are now keyed on the network id. A range that never started now stays PENDING and reads "not started". The headline is omitted when no range was swept. Notices, `LastRun` and `ProgressLine` all still behave correctly with PENDING left after DONE.

### Ada's two copy concerns, ruled

1. Detail reuses "Other ranges skipped: could not bind to Wi-Fi" for a bind failure. This can merge. The message is accurate about the cause, and it only appears with the toggle on and a VPN that blocks bypass. "Scan skipped: could not bind to Wi-Fi" would read better on Detail. That goes to the Director as a copy item, not a merge blocker.
2. The range summary row is hidden entirely when no range was swept. This can merge. In every case where nothing was swept, the notice line already says why: "Stopped at", phantom lines or roll-ups, the cap notice, or the bind notice. Per-range sources ("not started", "manual") are lost only for a run that swept nothing. The Director can ask for the row back if he wants the source list after an early Stop.

### Residual minors (none block merge)

M3 (the previous run's count can flash when a new scan starts), M5, M6, M7, M8 (notice and summary shift the list), M9, M11 and M12 stand as written above, for the Director or a later round. One doc nit: extended spec 5.4 still says "Unfinished ranges are marked CANCELLED", which the M4 fix makes inaccurate. A one-line edit for the spec owner.

The items needing the Director are unchanged: checks 7 and 10, the grouped-list confirmation, radar spec items 11.1 to 11.6, the icon, and the copy items above.
