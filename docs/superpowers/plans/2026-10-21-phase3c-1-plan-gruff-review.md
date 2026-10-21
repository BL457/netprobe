# NetProbe Phase 3C-1 plan: Gruff review

Date: 2026-10-21. Reviewer: Gruff (Devil's Advocate, partial veto). Plan: `docs/superpowers/plans/2026-10-21-netprobe-phase3c-1.md` at 290850e (4247 lines, 11 tasks). Checked against the binding spec (`2026-10-21-phase3c-feedback-design.md` at 33a6970), its Gruff review, the controller rulings, the Phase 2 spec and QA record (`docs/qa/phase2-extended-discovery-on-device.md`), and the code at 290850e: `data/Settings.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/ExtendedSection.kt`, `ui/home/HomeViewModel.kt`, `ui/home/ExtendedNotices.kt`, `ui/home/ScanBinding.kt`, `ui/home/HomeContent.kt`, `engine/discovery/DiscoveryEngine.kt`, `GatewayProbe.kt`, `CandidateRanges.kt`, `HostProber.kt`, `netenv/Subnet.kt`, `wordlists/WordlistStore.kt`, `ui/wordlists/WordlistsScreen.kt`, `WordlistCopy.kt`, `engine/common/CancellableIo.kt`, `HttpClient.kt`, `AppContainer.kt`, and the test helpers the plan's tests call (`DiscoveryEngineExtendedTest`, `ExtendedSectionTest`, `TestHosts`, `TestEnvs`). Every whole-file block in the plan was diffed against the file it replaces.

## Verdict

3 BLOCKING, 8 SHOULD FIX, 8 MINOR. The plan is careful, and the engine work is right. The toggle-off path is the MVP's: no extra host read, `ExtendedOptions()`, `HomeRange` equal to `sweepRange`, binding unbound. The settings keys are untouched, so an existing extended toggle carries over as Aggressive scan. I traced the outward walk by hand through the spec example and through every `GatewayWalkTest` case: the tests are real, they use fakes at the connect boundary, and each one fails on the bug it names. The URL fetch commits under `NonCancellable` and deletes on a late Cancel, which is the subtle part, and it is done properly. Every whole-file rewrite matches the current code except for the intended changes. Three things must change before execution. (1) The whole-file blocks were written against 290850e while the 3B device QA is still running and may commit fixes to the same files, so a block applied over a fixed file would silently revert the fix. (2) The device QA backup leaves out the one store this plan's QA writes to: the Director's wordlists. (3) Device QA item 4 expects the wrong source for 10.0.4.0/24. It also inherits the spec's false claim that no seed answers on the Director's LAN, so the outward walk, a Review Focus item, would ship with no device evidence. All three are cheap to fix. Fix them, take the SHOULD FIX items or record why not, then execute.

## Findings

### G1. BLOCKING. Whole-file blocks can silently revert 3B QA fixes

- Location: Task 1 Step 0 (lines 138 to 150), and the whole-file replacements: `Settings.kt` (line 339), `SettingsScreen.kt` (591), `ExtendedSection.kt` (942), `ScanBinding.kt` (1354), `ExtendedNotices.kt` (1801), `GatewayProbe.kt` (2286), `WordlistCopy.kt` (2887), `WordlistsScreen.kt` (3759).
- Problem: the blocks reproduce the files exactly as they stand at 290850e. I diffed all eight and they match apart from the intended changes. But the 3B device QA is running now on the phone and commits under `docs/qa/`. Any fix it lands goes into the 3B surfaces, and two of those surfaces are replaced wholesale here: `WordlistsScreen.kt` and `WordlistCopy.kt`, and `SettingsScreen.kt` hosts `ToolsSection`. A subagent pasting a whole-file block over a fixed file deletes the fix with no compile error and no failing test. Targeted "replace X with Y" edits fail loudly when the text has moved, but whole-file blocks never do. Step 0 checks only that the 3B QA record exists.
- Evidence: `git log 290850e..HEAD` is empty today and `docs/qa/shots/phase3b/` is untracked, so QA is in flight.
- Resolution: add to Task 1 Step 0, `git diff --name-only 290850e HEAD -- <the eight files above> app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt` must print nothing; otherwise stop and the controller re-bases the affected blocks on the new head. Add to each whole-file step: "before writing, `git diff --quiet 290850e -- <file>` or stop". Record 290850e in `phase3c-base.txt` beside the build base so the check has a fixed reference.

### G2. BLOCKING. The device QA backup misses the Director's wordlists

- Location: Task 11 Step 0 (lines 4098 to 4110), item 6 (line 4164), the restore procedure (lines 4190 to 4203).
- Problem: Step 0 backs up `databases/netprobe.db*` and `shared_prefs/netprobe.xml`. Item 6 then imports several lists into the real store at `files/wordlists/`, where `index.json` and `<uuid>.txt` hold the Director's own imported wordlists (`AppContainer.kt` line 158), and deletes them again afterwards. That store is the only data this plan's new code writes. A defect in the new import or delete path that corrupts `index.json` costs him his imports. `recover()` would adopt the files under "Recovered list N" names and lose his names. The restore procedure cannot put them back. Hard rule: the Director's data is safe.
- Resolution: in Step 0, back up `files/wordlists/index.json` and every `files/wordlists/*.txt` with `exec-out run-as ... cat` and MD5 each one on the PC and on the phone (list them first with `run-as $PKG ls files/wordlists`). After item 6, compare `index.json` against the backup, and expect it identical once the QA lists are deleted. Add the wordlists to the restore procedure with `put`.

### G3. BLOCKING. Device QA item 4 expects the wrong result and cannot exercise the walk

- Location: Task 11 item 4 (line 4159); spec 2.5 last sentence (spec line 161); Review Focus 3 (line 53).
- Problem: (a) The expectation "10.0.4.0/24 is found as in Phase 2 check 3, now listed as smart discovery" is wrong. With Aggressive scan on, evidence stays on, the Hue Bridge's mDNS evidence outranks probe hits, and the range is listed as "evidence" (Phase 2 QA check 3 and note A). Bugsy would record a false fail or chase a non-bug. (b) The spec says "No seed answers on the Director's LAN". The Phase 2 record says otherwise: the probe hit 192.168.1.0/24 and 192.168.2.0/24, which are seed units (seeds 192.168.1.1 and 192.168.2.1, both outside 10.0.0.0/16). So two seeds answer there. At his cap of 4 the budget is `B = 4 - |{10.0.4 evidence, 10.0.0, 10.0.2, 192.168.1}| = 0`, so no walk runs. That is correct behaviour, but it means the walk, the one piece the plan gives to the stronger model, gets no device run at all.
- Resolution: rewrite item 4. Expected ranges: 10.0.4.0/24 "evidence"; 10.0.0.0/24, 10.0.2.0/24 and 192.168.1.0/24 "smart discovery"; 192.168.2.0/24 capped, with the notice "1 more range found, raise Maximum extra ranges in Settings". Expect seeds 192.168.1.1 and 192.168.2.1 to answer and no walk at cap 4 (B = 0). Then run once more with Maximum extra ranges at 8. The walk must run: record the walked units from the `NetProbeExt` log, confirm attempts above the first pass are at most 256, and confirm every direction ends after two misses or at its limit. Then put the cap back. That run also re-checks the bound walk connect on real hardware. Record for the Director that spec 2.5's last sentence is wrong.

### G4. SHOULD FIX. A redirect to a local name is followed

- Location: Task 8 rules (line 2598), `redirect` (lines 2836 to 2846), `isLocalLiteral` (lines 2875 to 2883); Review Focus 5 (line 55).
- Problem: only IP literals count as local. A public server answering `302 Location: http://localhost:8080/`, `http://router.lan/`, `http://nas.local/` or `http://10.0.0.5.nip.io/` sends the phone into the LAN or into itself. Review Focus 5 says "a public host never bounces the phone into a LAN address". The spec's literal-only wording (D16) is honoured, but the threat it names is not closed. Impact is low: it is a blind GET with no credentials, and the body only lands in a wordlist. But it is the kind of thing a client's pentester would write up against us.
- Resolution: when the original host is not local, `isLocalLiteral` also treats `localhost`, `*.localhost`, `*.local`, `*.lan`, `*.home.arpa` and single-label names as local. The fetcher resolves each followed hop's host (`InetAddress.getAllByName`, on IO, before `open`) and refuses with `LocalTarget` when any address is loopback, link-local, RFC1918 or ULA. Add JVM cases for `localhost` and a single-label name. The device test's `127.0.0.1` to `localhost` hop still passes, because its original is a local literal. If the controller wants the spec's wording kept, record it as a deviation for the Director.

### G5. SHOULD FIX. Manual ranges saved under Phase 2 now sweep with Aggressive scan off

- Location: Task 5 `ExtendedOptionsOf` (lines 1650 to 1659), Task 6 `discoverOn` (lines 1960 to 1972), Task 11 Steps 0 and 2 and item 2 (line 4157).
- Problem: before 3C-1, an `extManual.<id>` list was swept only with the toggle on. After the upgrade it sweeps, bound, on every scan of that network. The Phase 2 QA left no key, but 3A, 3B or the Director may have added one since. Item 2 ("manual list empty: matches the Phase 2 check 1 baseline") silently assumes there is none.
- Resolution: in Task 11 Step 0, grep the prefs backup for `extManual.` and record each key and value. If any exist, tell the Director before Step 2 that they now sweep on every scan, and empty the list for item 2. Step 6 restores it from the backup. Add one line to the QA record's Director list.

### G6. SHOULD FIX. Large networks copy is false, and "Widen in Settings" can be advice that cannot work

- Location: `SettingsScreen.kt` line 715 in the Task 2 block; `HomeCopy.largeNetwork` and `largeNetworkPartial` (lines 1101 to 1104); self-review gap 6 (line 4233).
- Problem: (a) "Bigger networks are cut down to your own /24 unless widened here." is false with Aggressive scan on, and false for a mask wider than /20 with it off, where widening changes nothing (`sweepRange` gives the /24 whenever `prefix < maxPrefix`). (b) With Aggressive scan off on a /16, "Scanned your /24 only. Widen in Settings." points at a control that cannot help. The control that helps is Aggressive scan. With Aggressive scan on at the /20 limit, "Widen in Settings" points past the widest stop. (c) The cost is never stated. A /20 home range is 4,094 addresses: 32 waves of 128 at up to 12 ports x 600 ms, which matches the measured 16 s per /24. That is about 4 minutes for the local sweep alone, against about 1 minute at /22. The progress line ("probing N of 4094") is honest, but Settings never tells him. Leaving known-false copy in Settings is not "not restructuring": it is a defect the plan already found.
- Resolution: before Task 3, put the reworded caption to the Director as a copy-table row with a template, for example "Largest block of a big network to sweep. With Aggressive scan off, a network wider than this gets your /24 only. A /22 takes about 1 minute, a /20 about 4." Make `largeNetwork` say "Turn on Aggressive scan in Settings." when the mask is wider than /20 (or wider than the limit, his call). Drop "Widen in Settings." from `largeNetworkPartial` when the swept prefix is already 20. Pin all three in `CopyTableTest` and `RunEndTest`.

### G7. SHOULD FIX. The snapshot outlives the run: the hero and the grouping go stale

- Location: Task 6 `baseState` (lines 1921 to 1946), `HomeContent` (lines 1986 to 1987), self-review gaps 4 and 5 (lines 4231 to 4232).
- Problem: `runSnapshot` is kept until the next radar scan or a network change. After a run, the Director turns Aggressive scan on, or adds a manual range. The hero keeps naming the last run's range, although the code comment says it "names what a scan will sweep". Grouping also stays on the old rules, which loses Phase 2's "a changed maxPrefix or manual list regroups immediately" (Phase 2 spec lines 278 and 339). D21's purpose is that a change mid-run affects the next run only. That needs the snapshot only while the run is live.
- Resolution (controller ruling, since spec 2.2 reads the other way): use the snapshot only while `controller.state.scanning` is true, and the live settings otherwise. This keeps `RunSnapshotTest` valid, restores Phase 2's regroup-at-once, and makes the hero honest. Add one test: after the run ends, a live change regroups and the hero follows. If the controller keeps the spec's reading, fix the `HomeContent` comment to "the last run's home range" and record it.

### G8. SHOULD FIX. The walk budget counts hits that `derive` will drop

- Location: Task 7 `GatewayProbe.run` (lines 2447 to 2465): `OutwardWalk(seeds, units.keys.toHashSet(), targets.exclude)` and `walkHits >= budget`.
- Problem: the walk's `probed` set holds only first-pass units. A walked unit that is already a manual or evidence /24 is probed again, which is a wasted attempt on a range that will be swept anyway. If it answers, it counts toward `walkHits` while `derive` discards it as an overlap. So the walk can stop before it finds a real new range. Example: phone on 192.168.1.0/24, manual 10.1.2.0/24, seed 10.1.1.1 answers, cap 3, B = 1. Round 1 hits 10.1.2.1 and the walk ends, having added nothing.
- Resolution: after each round, stop when `walkBudget(firstPass + walk hits so far) <= 0`, so the same `derive` that sweeps decides. Drop the `walkHits` counter. Also pass the manual and evidence units into the walk's `probed` set so they are never probed. Add a `GatewayWalkTest` case for the example above.

### G9. SHOULD FIX. Device QA does not re-run the Phase 2 checks this plan touches

- Location: Task 11 Step 4 (lines 4149 to 4165).
- Problem: Task 6 rewrites `RunEnd.of`, `ExtendedNotices.of` and `discoverOn`. Task 7 changes `GatewayProbe.run`'s return type and the engine call. Items 2 to 4 re-run Phase 2 checks 1 and 3, and a manual-range variant. Phase 2 check 2 is never re-run as such (Aggressive on, Smart discovery off, no manual ranges: 10.0.4.0/24 by evidence, 4 hosts, about 29 s), because item 3's manual 10.0.4.0/24 masks the evidence path. Check 5 (cap 1, now with the reworded `capDropped`) and check 6 (Stop mid extended sweep, "Stopped at", lock released) are not re-run either.
- Resolution: add three items. Phase 2 check 2 as written. Check 5 with the new text "N more ranges found, raise Maximum extra ranges in Settings". Check 6 with `dumpsys wifi` showing no NetProbe lock held. Each takes under a minute on his LAN.

### G10. SHOULD FIX. `UrlImportController` lets a new import overlap a cancelled one

- Location: Task 10 `start` and `cancel` (lines 3731 to 3750).
- Problem: `job?.isActive` turns false the moment `cancel()` is called, while the old job may still be inside its `NonCancellable` body (finishing the store read, then deleting). A second `start` is accepted. The old job's `finish(CANCELLED)` then sets `Idle` while the new fetch runs, so both pills re-enable mid-fetch and a third start is possible.
- Resolution: guard on `job?.isCompleted == false`. Pass the job's identity to `finish` and reset `state` only when that job is still the current one. Add a controller test: cancel, then start at once while the first fetch is held in a non-cancellable gate, and the state must stay `Fetching`.

### G11. SHOULD FIX. Name the item 6 URLs in the plan, so the approval happens once and up front

- Location: Task 11 preamble (line 4062) and item 6 (line 4164).
- Problem: the approval gate is present and right: the app downloading third-party files on the phone at an agent's direction is an agent download. But the URLs are left for Bugsy to propose mid-QA, which stalls QA on the Director's reply. "Cancel mid-fetch of a large list" may also be impossible to hit on fast Wi-Fi, since a few MB lands in under a second. A list over 10 MB never reaches the body at all, because the `Content-Length` pre-check refuses it.
- Resolution: list the candidate URLs in Task 11 now, so the Director approves them with the plan: the exact SecLists `common.txt` `github.com/.../raw/...` link and its `raw.githubusercontent.com` twin, a 404 path on the same host, a small `.zip`, an HTML page, and one large list under 10 MB. Add: if the large list completes before Cancel can be tapped, record "NOT RUN, covered by `UrlWordlistFetcherDeviceTest.cancelDuringASlowBodyReturnsWithinOneSecondAndLeavesNoFile`".

### G12. MINOR. The tool runner reads the current network's manual list, not the target's

- Location: Task 4 `AppContainer` bind (lines 1435 to 1439). `RunSettings.of(settings.state.value, settings.manual.forNetwork(currentNetwork.value?.id))`. In practice the target is always on the current network, because Home lists only it. Use the host's `networkId` if `ToolRunner` has it to hand, or add a comment saying why current is safe.

### G13. MINOR. The walk's progress label names the wrong /16

- Location: Task 7 engine call (lines 2516 to 2523). During the walk, the progress line still reads "probing 10.0.0.0/16 for gateways" while it probes 10.1.x or 192.168.x. Either keep it and note it for the Director, or switch the label to `OTHER_RANGES_LABEL` for walk rounds.

### G14. MINOR. Two seeds in the same /16 leapfrog under the skip rule

- Location: `OutwardWalk.round` (lines 2367 to 2380), rule 4 (line 2044). With 192.168.1.1 and 192.168.2.1 both answering, "192.168.1 up" skips every unit "192.168.2 up" has just taken, never counting a miss. Once the leading direction ends, the trailing one carries on past it for two more misses. The attempt bound still holds, but it is wasted noise, and it makes the G3 run's walked units harder to predict. Option: end a direction when its next unit was already probed by the walk as a miss. Otherwise just state the behaviour in the QA expectation.

### G15. MINOR. The URL fetch has no overall deadline

- Location: Task 9 timeouts (lines 3403 to 3404). A server dripping one byte every 14 s keeps the pill on "Fetching" for hours, up to 10 MB. Cancel covers it, so this is acceptable. Note it, or add a total deadline as a named constant.

### G16. MINOR. `HomeScreenTest` opens `netprobe.db`, against the plan's own constraint

- Location: Task 11 Step 3 (line 4143) vs Global Constraints (line 30). It is the existing pre-3C test and the Phase 2 and 3B suites ran it. Say so in the constraint ("no new instrumented test"), and keep it after the Step 0 backup, as the plan already does.

### G17. MINOR. Task 1 Step 0's clean-tree check will fail on the QA agent's files

- Location: line 141. Today `docs/qa/shots/phase3b/` is untracked. Add: "if the only entries are under `docs/qa/`, wait for the QA agent's commit; never stage, move or delete them".

### G18. MINOR. No test pins the neighbour-rules switch for manual-only runs

- Location: Task 5 behaviour (line 1499), self-review gap 3 (line 4230). The switch is right: `NeighbourRules` passed Phase 2 QA with the toggle on, and extra-range hosts need its `neighMacs`. Add one `DiscoveryEngineExtendedTest` case: Aggressive off, one manual range, a neighbour entry inside the home range. The local host keeps its MAC, and a gateway-MAC entry on another IP is not claimed.

### G19. MINOR. The host fallback name

- Location: self-review gap 9 (line 4236). `example.com` becomes "example". Acceptable, and the Director can rename. Leave it.

## The writer's gaps (self-review notes 1 to 13)

1. Three redirect hops: right. Spec D16 is binding and came from the spec Gruff review's G9. The brief's "one hop" is superseded.
2. A 3xx with no usable `Location` reported as "the server answered 302": right. Adding IPv6 loopback, link-local and ULA literals and refusing user info in `Location`: right, and in the spec's spirit. See G4 for names.
3. Manual-only runs switching to `NeighbourRules`: right. Pin it (G18).
4. Snapshot grouping: it follows spec 2.2, but the better reading is the snapshot only while the run is live (G7).
5. The hero shows the home range: right, but stale after a run (G7).
6. The Large networks caption: leaving false copy is not acceptable (G6).
7. A skip is neither a miss nor a hit: right, and pinned. See G14 for the leapfrog side effect.
8. Dropping `NO_EXTRA_RANGES` with Aggressive scan off: right.
9. The display-name fallback: fine (G19).
10. Cancel proved for the body, not the connect: acceptable. Android cancels the OkHttp call. The JDK limit is a test-harness limit.
11. The URL approval gate: right, but name the URLs now (G11).
12. 1,054 vs 1,056: right. The Phase 2 run logged 1,041, inside 10 percent.
13. Step 0 stop: right, but it needs the G1 base check and the G17 note.

## Answers to the review brief

- B. Phase 2 regression: with Aggressive scan off and no manual ranges, `discoverOn` reads no extra hosts (`bindOutside` false), builds `ExtendedOptions()`, passes `aggressive = false` (so `HomeRange` is `sweepRange`), and binds nothing. `RunEnd` defaults to the old meaning, and Phase 2's engine-level `toggle off changes nothing` stays green untouched. Settings keys are unchanged: `extEnabled`, `extMax` and `extGatewayProbe` carry over. Bind rules for scans are unchanged except that "binds outside" now also holds for manual ranges, which is ruling 2. The remaining regression risk is the QA coverage (G3, G9).
- C. Walk: candidate order, the 2-miss stop, the 16-unit and octet limits, the more-than-4-seeds skip, the 256-attempt bound and Stop within one timeout are right and tested. The budget counting is G8. Re-probing is excluded for the home range and first-pass units only (G8). Client-network noise is bounded at 256 extra attempts. Tests use fakes at the connect boundary, a single-thread dispatcher for deterministic order and real time only for the cancel case. No virtual time is needed.
- D. Wide mask: the block is `SubnetMath.of(phone, limit)`, the phone's own /22 or /20, swept in address order, and the gateway probe excludes it (pinned). Cost and copy are G6.
- E. Reset: covers 14 values and keeps the NVD key, manual ranges, wordlists and brute-force options. One prefs edit. Undo comes from the snackbar with no confirmation. The markers are conditional, and the editor and stepper are enabled with Aggressive scan off. All as spec, and the Compose tests drive in-memory state only.
- F. URL fetch: redirects are not followed by the platform, `useCaches` is off, timeouts are 10 s connect and 15 s read, and only User-Agent and Accept are sent: no Authorization, no Cookie, and a grep confirms no CookieHandler or Authenticator. Each hop goes through the rule (3 hops, no downgrade, no public-to-local literal: G4). The 10 MB cap is enforced by the store at `MAX_IMPORT_BYTES + 1` decoded bytes, plus the `Content-Length` pre-check. Content types `text/*` except HTML, `octet-stream` or none, plus the NUL sniff. Cancel disconnects, commits under `NonCancellable` and deletes on a late cancel. The tests use the plan's own `ServerSocket` loopback server, not the JDK's `com.sun.net.httpserver`. That one would have been fine on the JVM side, but it does not exist on Android, so a shared plain server that also runs in the device test is the right call.
- G. Ordering and intermediate builds: every task leaves a compiling, green tree. Task 4 adds `aggressive = false` defaults before Task 6 uses them. Task 5 appends `evidence` and `aggressive` last, so positional callers compile. Task 7 adds the enum value and its `when` branch together. Names match the real code: the three `ScanBinding.run` callers, the `ExtendedSection` parameter names, the `DiscoveryEngineExtendedTest` helpers (`log`, `swept`, `gatewayCalls`, `canaryCalls`, `states`, `hosts`), `MdnsRecord`'s six-argument constructor, and `TestHosts.host(ip =)`. The only ordering hazard is G1.
- H. Size: 4,247 lines, inflated mostly by the eight whole-file blocks. No task is too big to hand to one subagent. Tasks 7 and 9 are large but cohesive, and correctly given to the stronger model. Do not batch further. The whole-file blocks are the risk, not the length (G1).
- I. Device QA: backup-first with `exec-out` and MD5 on both sides, `exec-in` with MD5 for every restore, an offline rollback APK from the recorded base, install over the old build, and marks and notes checked. A migration test on a copy does not apply (Room version unchanged, and the Task 10 gate diffs the schema and entity files against the base). What does apply is the prefs and wordlists backup (G2) and the leftover manual list check (G5). Phase 2 re-runs: G3 and G9. The URL approval gate is present (G11). No destructive step runs, and the disposable-network rule is stated.
- J. Client-network embarrassment: false Large networks copy (G6), a redirect into a client's LAN by name (G4), a stale hero after a toggle (G7). The walk and the /20 sweep are bounded and the Director ruled them in, but the /20's 4-minute cost should be visible (G6).

## What the plan gets right

- The toggle-off path is truly the MVP's, verified line by line against `HomeViewModel.discoverOn` at 290850e, and pinned at three levels.
- `HomeRange` is the one home-range rule, used by the engine, binding, grouping, the hero and the editor. `RunSettings` is read once per run for radar, Quick scan all, Device-screen scans and tool runs, and the plan names every caller.
- Settings defaults come from one `SettingsState()`, the reset is a single prefs edit, and Undo keeps a key typed during the snackbar. The Compose tests never open the Director's prefs.
- The walk is deterministic, bounded and correctly placed inside the bound connect, with a budget computed by the same `derive` that sweeps (G8 tightens it). `GatewayWalkTest` reproduces the spec example exactly and checks the 256 bound with a rule that would blow it without the 16-unit limit.
- The URL fetch is the app's only internet code (grep-checked), is never bound to Wi-Fi, follows redirects itself, and has an Android-side device test for the OkHttp-specific behaviour (gzip, disconnect, redirects).
- No schema change, enforced by a diff gate. No new dependencies. Every string is a Copy member with a template, and the removed names are asserted absent by reflection. Controls are pinned (overflow in the top bar, both pills and Cancel in the Wordlists top bar), no em-dashes (grep gate, and the plan itself has none), and no disclaimers.
- The QA preamble carries 3A finding F5 (`exec-in`, never `shell <`) and the 3B MD5 discipline, never commits backups, and makes the Director approve every downloaded URL.
