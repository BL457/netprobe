# NetProbe Phase 2 final fix round (Ada)

Branch `phase2`, BASE 885d08b, HEAD 0a02460. Six commits, one per item. Review: `docs/superpowers/plans/2026-10-19-phase2-final-review.md` (left untracked, as found).

## 1. I1, neighbour rules only with the toggle on (0dfdc01)
- `DiscoveryEngine.kt` neighbour block: with `options.extended.enabled == false` it runs the MVP loop from 569cd8f verbatim (every in-range entry emitted with its raw MAC, `MacSource.NEIGH`). `NeighbourRules.apply` (proxy-ARP filter, gateway-MAC rule, evidence, `neighMacs`) runs only when the toggle is on.
- Test flipped: the old toggle-off test (`DiscoveryEngineExtendedTest`, formerly :239) is now `toggle on - ... three ips share its mac` (rules apply). New `toggle off - the neighbour block is the MVP, every on-link entry keeps its mac`: three IPs sharing one MAC keep it, and 10.0.3.30 carrying the gateway's MAC (range extender) keeps it.
- RED: new toggle-off test failed (AssertionError at :253, MACs null). GREEN: Extended 24/24, Engine 20/20, NeighbourRules 7/7.

## 2. I2, off-subnet port scans bound to Wi-Fi (8555b00)
- New `ui/home/ScanBinding.kt`: `ScanBinding.run(ip, env, maxPrefix, extEnabled, binderFor) { ... }`. Toggle off, or the ip inside the swept local range: runs the block unchanged (MVP). Otherwise `binderFor(env)` and `withContext(BoundNetwork(b))`; no binder throws `BindFailedException`, never an unbound scan.
- `HomeViewModel.quickScanHost` and `DetailViewModel.scanHost` wrap the scan and identify steps in it, using `c.networkEnvMonitor::binderFor` and the env the ScanController passes in.
- Quick scan all: a `BindFailedException` is counted separately, the loop carries on with the other hosts, and the end message includes `HomeCopy.BIND_FAILED` ("Other ranges skipped: could not bind to Wi-Fi"), joined with the existing partial-failure string when both apply.
- Detail: `BindFailedException` is caught in the scan job and shows `HomeCopy.BIND_FAILED` in the Detail message line; ports are not marked scanned.
- Also `TlsPeek.certNames` now binds its SSLSocket to `currentBinder()` (it built a raw socket, so the TLS cert read of an off-subnet port would still have followed a VPN). A failed bind fails closed (null), as before.
- Test `ScanBindingTest` (4): off-subnet row sees the fake binder (one lookup); local row does not (no lookup); toggle off binds nothing; off-subnet with no binder throws `BindFailedException`.
- RED: compile failure (`Unresolved reference 'ScanBinding'`). GREEN: 4/4; HomeViewModelTest 10/10, ServiceIdentifierTest 7/7.

## 3. I3, canary never probes gateway-probe answers or .1/.254 (3984d1f)
- `GatewayProbe.run` gains `onAnswer` (each answering address); new `GatewayProbeResult(hits, answered)`; the engine's `gatewayProbe` parameter returns it; production collects answers in a concurrent set.
- `sweepExtra`: after the existing "fewer than four unknown addresses skips the canary" gate, the canary pool drops gateway-probe answers and last-octet 1 / 254. Two or more left: lowest and highest. Fewer: probes what remains (one or none; none leaves the sweep guard) and records `RangeProgress.canaryFallback` (null when two clean canaries were used). Extended spec 4.7 paragraph updated.
- Tests: `canaries skip the gateway probe's answering addresses and the 1 and 254 offsets` (.1 and .254 answered the probe and refuse the canary: canaries at .2 and .253, range DONE, no fallback); `with fewer than two clean canary addresses the canary uses what remains and says so` (/29: one canary at .251, `canaryFallback == 1`); GatewayProbeTest asserts `onAnswer` gets 10.0.4.1 and 10.0.9.254.
- RED: `expected [10.0.4.2, 10.0.4.253] but was [10.0.4.1, 10.0.4.254]`; `expected [10.0.4.251] but was [10.0.4.251, 10.0.4.254]`. GREEN: Extended 26/26, Engine 20/20, GatewayProbe 8/8, PhantomGuard 4/4.

## 4. Task 19 M1, keep-screen-on flag inside the try (9e22098)
- `HomeViewModel.discover`: `c.locks.acquire()` stays before the `try`; `extendedRunActive = true` is now the first statement inside it, so a throw constructing or acquiring Locks never sets the flag, and the `finally` always clears it.
- No test: there is no view-model test harness (no Robolectric or mocking library, and no new dependencies allowed). This is the same gap Dot noted (MVP M19). Compile and the full suites are green.

## 5. Task 17 M2, Settings keyed on the network (dd7748c)
- `SettingsScreen`: `local = remember(network?.id, s.maxPrefix)`. `ExtendedSection` takes `networkId: Long? = null` and passes it to `ManualEditor`, where `notes` is `remember(networkId)`.
- Device test `ExtendedSectionTest.editorNotesResetWhenTheNetworkChanges`: add 10.0.4.0/24 twice ("Duplicate removed" shown), switch network id 1 to 2, and the note is gone.
- RED on the Pixel: `assertDoesNotExist` failed, 1 node "Duplicate removed: 10.0.4.0/24" found. GREEN: ExtendedSectionTest 7/7 on the Pixel. The `local` keying has no test (SettingsScreen needs the AppContainer).

## 6. Tasks 15/16 M4, "not started" and no zero headline (0a02460)
- Engine `finally`: only RUNNING becomes CANCELLED; a never-started range stays PENDING.
- `RangeSummaryText.lines`: PENDING reads "not started" (`HomeCopy.OUTCOME_NOT_STARTED`, replacing the unreachable `OUTCOME_NOT_SWEPT`); CANCELLED (and the unreachable RUNNING) read "stopped".
- `RangeSummaryText.checked` returns null when no range was swept, and `RangeSummary` then renders nothing (the notice line already says "Stopped at ...", phantom or cap).
- Radar spec section 8: two new rows (range summary headline with the omission rule; range summary line with every outcome, including "stopped" vs "not started").
- Tests: engine stop and bind-failure tests now expect `[DONE, CANCELLED, PENDING]`; ExtendedNoticesTest expects "not started" plus a new `no headline when no range was swept`; CopyTableTest template `...|stopped|not started)` (doubled backslashes kept) and a check that `checked()` is in the table and never starts with "0 "; device `HomeContentTest.rangeSummaryIsAbsentWhenNoRangeWasSwept`.
- RED: 5 JVM failures (two engine state lists, copy table `'10.0.1.0/24  manual  not swept'`, summary lines, `expected null, but was:<0 other ranges checked>`). GREEN: all JVM tests. The device test was not run RED on its own; its JVM equivalent was.

## Suites
- JVM: `./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug` gave BUILD SUCCESSFUL in 43s; 390 tests, 0 failures, 0 errors, 0 skipped (382 before plus 8 new).
- Device: `./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` on the Pixel 7 Pro (28221FDH300AP9, unlocked) gave "Finished 70 tests on Pixel 7 Pro - 17", BUILD SUCCESSFUL in 1m 6s; XML: tests=69, failures=0, errors=0, skipped=1 (the existing assumption skip). The flag was accepted.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` gave Success; `lastUpdateTime=2026-10-20 05:13:13`, targetSdk=30, versionName 0.1.0. I changed no stored setting, mark or note. As before, `HomeScreenTest` runs a real discovery into the live database, which adds or refreshes rows (Part A QA note H).

## Concerns
- I2 wording: Detail reuses "Other ranges skipped: could not bind to Wi-Fi" as instructed. For a single-host scan, "Scan skipped: could not bind to Wi-Fi" would read better. That needs the Director's wording.
- I2 scope: "local" is the swept range (the same rule as row grouping and discovery binding), so on a clamped /16 an on-link host outside the swept /24 is scanned bound. That is harmless (the binder is the same Wi-Fi network). I also bound `TlsPeek`. It is outside the letter of the item but closes the same leak.
- I3: one canary (or none) is used when fewer than two clean addresses remain, as instructed; one answering canary then marks the range PHANTOM. This is only reachable on tiny manual ranges.
- M4: with nothing swept, the whole range summary row is omitted, not just its text, so the per-range lines ("stopped", "not started") are visible only when at least one range was swept. The notice line covers the early-Stop case.
- M1 and the SettingsScreen `local` keying have no automated test (no VM or container harness).
- Not re-reviewed: no reviewer has looked at this round's fixes yet.
