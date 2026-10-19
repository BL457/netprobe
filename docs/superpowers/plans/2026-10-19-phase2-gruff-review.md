# Gruff review: NetProbe Phase 2 implementation plan

Reviewed: `docs/superpowers/plans/2026-10-19-netprobe-phase2.md` (20 tasks) against both binding specs and the current code at `app/src/main/java/com/northwalessolutions/netprobe/`. Date: 2026-10-20.

## Verdict

**PROCEED WITH FIXES (findings 1 and 2 before Tasks 1 and 15 are built; the rest can be fixed by the editor in place).** I found no blockers. Every Modify line I checked points at real code and real signatures: `Probe.connect` and its four callers, `Sweep`, `HostProber`, `NetBios.query`, `Snmp.query`, `HttpTitleFetcher.fetch`, `NetworkEnvMonitor`, `HostRow`, `HomeUiState`, `RowRules`, `ActionProvider.primary` (null when there is no handler, which the Task 3 fixtures model correctly), `DeviceAction`, `ScanProgress`, `VulnResult` ("Offline" reason), `NetworkEntity.displayName`, `NeighbourEntry`. The technical bets I could check all hold:
- `TextAutoSize` is already compiled in `DetailComponents.kt`.
- Variable-TTF `Font(resId, weight, style, variationSettings)` is the documented API 26+ path.
- `enableEdgeToEdge` works at targetSdk 30.
- `Network.bindSocket(Socket)` forces the fd to exist on an unconnected `Socket()`.
- The contrast and blip figures are right: I recomputed stale edges at 0.5 and 0.6 alpha and got the plan's numbers (PHONE 2.62 and 3.18). The closest pair of the first 64 blips is 8.09 dp.
- The engine fakes do exercise the canary and the sweep guard.

Two defects need fixing before build:
- The sweep guard judges the fastest 64 responders, not the first 64 addresses. On a busy routed /24 it will throw away real hosts.
- Task 1's edge-to-edge switch removes keyboard resizing, and no task adds IME insets. The Director could not see what he types into the Settings fields, including the manual ranges editor.

## Findings

### 1. MAJOR - Tasks 14 and 15 - the sweep guard samples by completion order, so busy real ranges are discarded as phantoms

**What is wrong.** `sweepExtra` counts `probed` and `alive` in the order `Sweep.runAll` delivers results, and the real `Sweep` delivers them in completion order. Alive hosts answer in milliseconds. A dead routed address takes up to 12 ports x 600 ms = 7.2 s, or about 3 s for a router's host-unreachable. So the first 64 completions are mostly the live hosts. Any extra /24 with about 58 or more live hosts trips `alive > 0.9 * probed` at probe 64. It is marked PHANTOM, its hosts are discarded and nothing is stored. Server, camera and IoT VLANs on client sites are exactly that dense, and so may the Director's 10.0.4.0/24 be. Extended spec 4.7 says "the first 64 probed addresses", which means a fixed address sample, not the fastest 64.

**Evidence.**
- Plan Task 15: `sweep.runAll(c.subnet.hosts().filter { it != env.interfaceIp }).collect { l -> probed++; if (l.alive) { alive++ ...`
- Plan Task 14: `Sweep.runAll` sends `prober.probe(ip)` from 128 concurrent launches.
- The test fake hides the problem. `FakeSweep.runAll` emits in address order with no latency difference (`list.forEachIndexed { i, a -> ... emit(...) }`), so no test can see the bias.

**Fix.**
- In `sweepExtra`, index the host list (`val list = c.subnet.hosts().filter { ... }.toList()`, with a map from ip to index).
- Count only results whose index is below `PhantomGuard.SAMPLE`: `sampleDone` and `sampleAlive`.
- Call `PhantomGuard.decide(sampleDone, sampleAlive, finished = sampleDone == minOf(SAMPLE, list.size))`, keeping the 16-probe floor.
- Keep holding every alive host, sample or not, until the verdict.
- Add a `DiscoveryEngineExtendedTest` case: a FakeSweep that emits 60 alive hosts (.100 to .159) first with zero delay, then the dead rest with a delay. Expect `DONE` with 60 hosts, not `PHANTOM`.
- Keep `accept-everything` as it is; it must still be `PHANTOM`.
- Note for device check 2: on a first extended run the canary's "lowest unknown address" is usually the range's own .1 router, which refuses a high port. The canary then rests on .254 alone. That is spec behaviour, but record which addresses the canary hit.

### 2. MAJOR - Tasks 1, 7, 8 and 17 - edge-to-edge without IME insets hides the Settings fields behind the keyboard

**What is wrong.** `enableEdgeToEdge()` turns off `decorFitsSystemWindows`, so the window no longer resizes for the keyboard. No screen in the plan handles `WindowInsets.ime`:
- Every Scaffold uses `contentWindowInsets = WindowInsets.navigationBars`.
- The Settings `verticalScroll` column has no `imePadding`.

The NVD API key field is the last field in Settings, and its parent cannot scroll it above the keyboard, so the Director types blind. The extended section's "Add range" field (Task 17) sits in the same column, and its inline errors and normalisation notes appear under the field, behind the keyboard. Detail's note and Custom ports fields are in the pinned header, so they are safe, but the port list loses its bottom rows behind the keyboard.

**Evidence.** Plan Task 1: `enableEdgeToEdge(statusBarStyle = ..., navigationBarStyle = ...)`. A grep of the plan for `imePadding` or `WindowInsets.ime` finds nothing. The manifest has no `windowSoftInputMode`.

**Fix.**
- Task 8: add `.imePadding()` to the Settings scroll column, after `padding(pad)` and before `verticalScroll`. Do the same in Task 17's Settings change.
- Task 7: add `.imePadding()` to the Detail `portList`.
- Add one assertion to `ExtendedSectionTest` or `SettingsRowsTest`, or a device screenshot in Task 17 Step 5: with the keyboard open on "Add range", the field and its supporting text are visible.

This adds no dependency; `imePadding` is in foundation.

### 3. MINOR - Task 13 - the gateway-probe progress test is flaky

**What is wrong.** `onProgress(done.incrementAndGet(), units.size)` runs on many threads. Two units can finish together and deliver 272 before 271, so `last` ends at 271 and `assertEquals(t.byUnit().size to t.byUnit().size, last)` fails at random. The engine's status would also step backwards for a moment.

**Fix.** In the test, keep the max (`last = maxOf(last.first, d) to total`). In `GatewayProbe.run`, report through the max the same way, or call `onProgress` inside a `synchronized` block that holds the counter.

### 4. MINOR - Task 17 - `persistsAGoodRangeForTheCurrentNetworkOnly` will likely match two nodes

**What is wrong.** After Add, the field is still focused and empty, so Material 3 shows the placeholder `"10.0.4.0/24"`. The placeholder is merged into the text field's semantics. `rule.onNodeWithText("10.0.4.0/24")` then finds both the list row and the field, and fails with "expected exactly 1 node".

**Fix.** Either drop the placeholder (the label "Add range" is enough), or assert `rule.onNode(hasText("10.0.4.0/24") and !hasSetTextAction()).assertIsDisplayed()`.

### 5. MINOR - Task 18 - the neighbour rules drop local host rows, not just MACs

**What is wrong.** Extended spec 6.2 says proxy-ARP entries and entries carrying the gateway's MAC get no MAC assigned. The plan instead emits NEIGH rows only from `r.macs`, so those IPs inside the local swept range vanish as hosts. That changes MVP behaviour. Hosts found only through the neighbour table (phones that firewall all 12 sweep ports) disappear when three or more clients share a MAC, which is common behind a MAC-NAT Wi-Fi repeater.

**Evidence.** Plan Task 18: `r.macs.forEach { (text, mac) -> ... if (local.contains(ip)) emit(DiscoveredHost(ip, mac = mac, ...)) }`

**Fix.** Return `onLinkIps` (every parsed entry inside the true subnet) from `NeighbourRules.Outcome` as well. Emit a `Source.NEIGH` row for every local IP, and attach `mac` only when the IP is in `r.macs`. Add a test: three local IPs on one MAC give three rows with no MAC.

### 6. MINOR - Tasks 5 and 6 - a finished scan that found nothing drops back to HERO

**What is wrong.** `lastScanAt` is `max(lastDiscoveryRun)` over stored hosts, so it is null when no host was stored. `RadarModes.of(false, 0, null)` is then HERO, against spec 3.1 ("SETTLED otherwise, including a finished scan that found nothing"). The "No devices found / Scanned in 12 s" summary can never show. It is rare, because the gateway or the phone itself usually answers, but the `RadarModesTest` case for it describes a state the view model cannot produce.

**Fix.** In `HomeContent`, call `RadarModes.of(state.scanning, state.totalHosts, state.lastScanAt ?: state.scanFinishedAt)`.

### 7. MINOR - Task 6 - settled with no network leaves the network line blank

**What is wrong.** `NetworkLine` renders nothing when `env == null`; the MVP `NetworkPanel` said "No network". With stored hosts and no Wi-Fi, the screen shows a grey ring with "13 devices / Last scan ..." and nothing saying it is offline. The ring's reason is only in its content description.

**Fix.** When `env == null` and the mode is not HERO, show `HomeCopy.HERO_NO_NETWORK` ("No network", already in the copy table) in `rowName` / `textMuted` on the network line. HERO keeps its own single copy of the no-network wording, so test 3 still passes.

### 8. MINOR - Tasks 2 and 16 - `CopyTableTest` checks hand-picked examples, not every state

**What is wrong.** The test feeds one chosen input per branch and checks the output against literal example strings. It cannot fail when a reachable state yields text outside the table. Two cases it misses:
- `RadarSummaries.settled` returns detail `""` when hosts exist and `lastScanAt` is null.
- GATEWAY_PROBE with `gatewayProbe == null` (the pre-check) reads "preparing next range".

That is a spec 10.1 coverage gap.

**Fix.**
- Hold the table as regex templates (for example `"probing \\d+ of \\d+  \\d+ s"`).
- Iterate a generated matrix: every `DiscoveryPhase` x {gatewayProbe null or set} x every `RangeState` for the active range, plus summaries for 0, 1 and 13 hosts x other-subnet 0 and 1 x with or without a finish time x with or without `lastScanAt`.
- Assert each output matches a template, with a separate allow-list for `""`.

### 9. MINOR - Task 6 - two Compose assertions test the hook, not the drawing

**What is wrong.**
- In `rowAnatomy`, the data line sets `Modifier.semantics { textAlpha = 1f }` as a constant, so the "data line never dimmed" check passes even if someone puts the MVP's whole-row `.alpha(STALE_ALPHA)` back. That regression is the one this check exists to catch.
- `RadarStateTest`'s `StateRestorationTester` step restores nothing that matters, because the summary is a pure function of the passed state. That is acceptable under the plan's deviation 4, but it is not evidence of persistence.

**Fix.**
- Add one helper, `Modifier.drawnAlpha(a) = alpha(a).semantics { textAlpha = a }`, and use it for both the name and the data line.
- Add an assertion that the `hostRow` node and its ancestors carry no alpha below 1. Simplest: put `TextAlphaKey` on the row too, from the same helper, and assert 1f.
- Rename the restoration test to say it checks the view-model-derived summary, and cover persistence in device check a05.

### 10. MINOR - Task 7 - the note never collapses on a tap elsewhere

**What is wrong.** Compose does not clear text-field focus when the user taps a non-focusable area or hides the keyboard. "Losing focus collapses it" (spec 5.1 item 7) therefore almost never happens, and the pinned block stays at its expanded worst case, about 90 dp taller.

**Fix.**
- Add `Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }` on the Detail Scaffold content.
- Add an IME Done action (`keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })`) with `imeAction = ImeAction.Done` on the note field.
- Add one `DetailContentTest` case: expand the note, click `portList`, and the `note` node is the collapsed box.

### 11. MINOR - Task 4 - the arm's centre mask overspills the disc while it grows

**What is wrong.** `ArmLayer` always draws `drawCircle(NetProbeColors.discCentre, mask, center)` with a 40 dp radius, an 80 dp dark circle. The arm is composed from the first SCANNING frame, while the disc is still near 56 dp, and the layer does not clip. The dark circle shows outside the ring for the first part of the grow. Device check a13 records this transition.

**Fix.** Use `drawCircle(NetProbeColors.discCentre, minOf(mask, r * 0.47f), center)`, which is 40 dp at the full 86 dp radius, or set `graphicsLayer { clip = true; shape = CircleShape }` on the arm layer.

### 12. MINOR - Tasks 6 and 7 - check the `DeviceConfigurationOverride` opt-in

**What is wrong.** `HomeContentTest` and `DetailContentTest` use `DeviceConfigurationOverride.FontScale` and `ForcedSize` with no opt-in. I could not confirm from the public reference whether ui-test at BOM 2025.06.00 marks them experimental.

**Fix.** Add a line to Task 6 Step 4: "if the compiler asks for `@OptIn(ExperimentalTestApi::class)`, add it to the test class only". The same rule the plan already uses for `stickyHeader`.

### 13. MINOR - Tasks 1 and 20 - two device checks cannot prove what they claim

**What is wrong.**
- Task 1 Step 5 writes `user_rotation` while auto-rotate may be on. Android ignores `user_rotation` when `accelerometer_rotation` is 1, so `t01-rotation-locked.png` proves nothing.
- Task 20 check 3 counts attempts with `cat /proc/net/tcp | wc -l`. That is a snapshot of open sockets, not an attempt count, and it may be unreadable from shell on Android 13+.

**Fix.**
- Task 1: set `accelerometer_rotation` to 0, then `user_rotation` to 1, capture, and restore both. Or rotate the phone by hand and say so.
- Task 20: compute attempts from `GatewayTargets.byUnit()` (the sum of addresses x up to 2 ports). Add an `attempts` field to `GatewayProbeProgress`, counted in `GatewayProbe.answers`, and read it off the status. This is engine-internal and already within extended spec scope.

### 14. MINOR - Tasks 15 and 18 - the expected test counts are off by one

**What is wrong.** `DiscoveryEngineExtendedTest` as written has 18 tests, but Task 15 says "19 passed". Task 18 adds one more and says "20". An implementer chasing the missing test wastes time, or adds a filler.

**Fix.** Change the counts to 18 and 19.

### 15. MINOR - Task 16 - "Scanned in N s" survives a network switch

**What is wrong.** `latestRun` and `finishedAt` are never reset when `env` changes. After a move to another network, the radar shows the old network's duration against the new network's `totalHosts`.

**Fix.** In the `env.collectLatest` collector, set `finishedAt.value = null` when the resolved network id differs from the previous one. `latestRun` can stay, because `RadarSummaries.settled` needs both values.

## Coverage notes (no action unless the Director wants it)

- Every Radar spec section 8 string that the plan claims to produce is present verbatim in `HomeCopy`, `DetailText`, `DetailComponents`, `VulnSheet` and `SettingsScreen`. "<range> cut short: scan limit reached" (TRUNCATED) is deliberately unused, and "Gateway probe skipped: every address answered" is added. Both are already flagged in the plan's self-review.
- Extended spec 8.2 asks for a per-range list with source and found count in the done summary. The plan reaches it only through notices, which carry no source and say nothing for a range swept with hosts. This is flagged as deviation 8. It is a Director call, not a defect.
- The UI tasks stay UI-only, apart from the allowed `HomeUiState` fields and the flagged `groups`. The plan has no schema change, no targetSdk change, no new dependency and no download step. The forbidden-library list is copied into Global Constraints.
