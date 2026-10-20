# Task 16 review (ccd21d9..25b1999)

Ran `./gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL (up-to-date, whole suite green). No adb, no edits. Screenshot read.

## Spec compliance
- ✅ Settings to ExtendedOptions (enabled/maxRanges/gatewayProbe/manual/knownIps); off path passes `ExtendedOptions()`.
- ✅ `binderFor(e)` passed to `discover(e, opts, binder)`; null binder skips the phase with SKIPPED_BIND_FAILED notice (engine DiscoveryEngine.kt:160).
- ✅ Range grouping at display time, schema v1, `HostRow.subnet/offSubnet`, `RowRules.row(extraRanges, manualRanges)` via `RangeRules.groupFor`, `HomeGroups.of(rows)`; `manual.revision` is in the combine so a manual edit regroups.
- ✅ Progress line: all four extended shapes match the spec table; "N of M" counts extras only (TRUNCATED excluded); UI-owned unnamed template "probing for gateways, N of M  S s" added to CopyTableTest with doubled backslashes; three-argument gateway progress already in the engine.
- ✅ RangeSummary (hidden when < 1 other range, SETTLED only), notices, `ScanFinish.afterNetworkChange` wired, `HomeUiState.lastRunRanges`, `AppContainer.currentNetwork`.
- ✅ Strings: every new string equals the table text; GATEWAY_ANSWERS_ALL and TRUNCATED-row deletion are the ruled deviations. Plan-text conflict (informational): the Radar spec table still contains the TRUNCATED row "cut short: scan limit reached" and says SKIPPED/PHANTOM etc. are notices; the code follows the brief ruling, so the spec table should be edited to match.
- ✅ CopyTableTest enumerates phases x gateway x range states x elapsed and notes x states x sources x dropped counts; regexes are doubled-backslash.
- ✅ RangeSummary wording: 0 others hides; 1 -> "1 other range checked"; N -> "N other ranges checked"; PHANTOM "skipped, every address answered", TRUNCATED "not swept, scan limit reached", CANCELLED "stopped", PENDING/RUNNING "not swept", DONE "swept, nothing reachable" / "swept, N found". Matches the brief; tested.
- ✅ Screenshot: "Home subnet 10.0.3.0/24" group, then "Other subnet 10.0.4.0/24" with Hue Bridge 10.0.4.20, summary "Scanned in 12 s, 1 on another subnet", no RangeSummary row. Correct for toggle off.
- ✅ No em-dashes, no dependency/gradle changes in the diff.
- Ada concern (a): fine. The engine label is only ever the walked /16 (`targets.enclosing.toString()`) or the constant "other ranges", so the dotted-CIDR regex is exactly the right discriminator; `subnetOf` rejecting < /20 is irrelevant here. Not a correctness gap (slightly indirect; the cleaner long-term option is the engine emitting a null label, not worth a change now).
- Extended-off "byte-for-byte MVP": NOT strictly. See Important 2.

## Task quality: Needs fixes

### Important
1. HomeViewModel.kt (discover `finally`, about line 619) vs init env collector (about line 568): old-network summary can leak onto the new network. On a network change the controller cancels the job, but discover's `finally` runs only after the engine unwinds (it joins children, marks CANCELLED). The env collector clears `lastRunRanges`/`lastRunProgress` after `networks.ensure(B)` (one Room call). If the unwind is slower than that, the `finally` then publishes network A's `st.ranges` and swept subnets after the clear, so "3 other ranges checked" (A's ranges) shows on B and A's swept ranges regroup B's hosts. It also sets the A "Stopped at ..." notice after any dismiss. Same race when env goes null. Fix: in the `finally`, publish ranges and notices only if `networkId.value == net.id`.
2. HomeViewModel.kt discover `finally`: the unconditional notice joiner breaks the pinned-header rule. Notices are `joinToString("\n")` into NoticeLine (HomeComponents.kt:140-148), which lives in the pinned top bar with no maxLines or scroll. Every empty DONE range and every PHANTOM range adds its own line, so 16 extra ranges (a segmentation sweep, the main use case) gives up to ~19 pinned lines and pushes the device list off screen (see feedback_pin_critical_controls). Fix: cap the notice (for example a "N ranges: nothing found" roll-up, or maxLines + ellipsis; the per-range detail is already in RangeSummary).
3. Extended-off path is not identical to MVP: (a) `extraMessage.value = null` is new at the top of discover; (b) the large-network notice moved into `finally`, so it now also appears after Stop, scan failure or a network change (MVP set it only after normal completion); (c) `manual.forNetwork` (SharedPreferences read and CIDR parse) now runs in the combine even with the toggle off. (a) is harmless because the UI dismisses before scan, but (b) is a visible behaviour change for the build the Director has installed. Fix: set the clamp notice only on normal completion (outside the finally, or guarded by `!cancelled`).

### Minor
1. HomeViewModel.kt state combine: grouping and row mapping run per emission and are not keyed. Status ticks (sweep progress, gateway progress) re-run `hs.map(row)`, `HomeFilter.apply`, `HomeGroups.of` (two filters, groupBy, sort) and `manual.forNetwork` on Main for 1000 hosts. MVP already mapped rows per tick, so this adds a constant factor, not a storm (StateFlow conflation bounds it); I did not measure it. A cheap improvement: `remember`-style caching keyed on (hosts, filter, query, extra, manual, env), computed once and only `status` varying.
2. No test for the NETWORK_CHANGED wiring (concern b): yes, add one now. HomeViewModel is untestable, but the logic is two lines. Extract `ScanFinish.next(..., networkChanged: Boolean)` (or a `fromController(state, was, previous, now)`) and unit-test: NETWORK_CHANGED message clears; a following scan start/finish sets a fresh time; dismissal (message null, not scanning) keeps null. Cheap and guards the leak the ruling says is closed.
3. HomeContent range headline "N other ranges checked" counts PHANTOM, TRUNCATED, CANCELLED and PENDING ranges that were not checked ("6 other ranges checked" with 3 not swept). Spec says "ranges checked"; the lines clarify, but the headline overstates. Consider counting DONE only or rewording.
4. HomeComponents.kt RangeSummary: "Show ranges"/"Hide ranges" click labels are not in the copy table or HomeCopy.
5. RowText.kt HomeGroups.of: local header uses `local.first().subnet`. A garbage-IP row sorted first (ipKey 0) would title the home group with that string (`groupFor` returns `ip to false`). Stored IPs are valid in practice; a local-subnet argument or filtering to `subnet` of a parsed row would be safer.
6. CopyTableTest matrix never exercises source `null` ("local" label) because `localRange` is dropped by `drop(1)`; the "local" alternative in the regex is untested.
7. HomeStats.kt:25 comment still says "(Task 16 calls this)".
8. The notice at a Stop/network change is computed even though the controller message replaces it on screen (`cs.message ?: qs.message ?: msg`), so extended notices after a network change are invisible until dismissed and then cleared with it; harmless once Important 1 is fixed.

## Controller rulings check
- afterNetworkChange wired: correct. The NETWORK_CHANGED null on the controller collector is sound: the watcher sets `State(false, NETWORK_CHANGED)` synchronously after `j.cancel()` so the job's finally sees scanning already false; `start()` replaces the state (message null), so a fresh scan's finish time (`State(false, null)` -> `now`) cannot be wiped by it. A fresh scan cannot be wiped by `afterNetworkChange` either: discover sets `networkId` to the new id before the scan can finish, so the collector sees equal ids and keeps the time.
- UI strings from copy table, null-binder skip, three-argument progress, display-time grouping: applied. M7 deferred: acknowledged.

## Re-review, round 1 (25b1999..f1abb09)

JVM suite: BUILD SUCCESSFUL. Verdict: Approved. All five fixes are as described.

- I1 fixed: `RunEnd.of` (ExtendedNotices.kt) returns null unless `networkId.value == net.id`; the view model passes it in the `finally`. It cannot drop a legitimate finish: discover sets `networkId` to the run's id at its start and the env collector only rewrites it with the same id for the same network. Only exception: if `networks.ensure` throws mid-scan the collector sets `networkId` to null and that run's ranges and notices are dropped (rare, conservative).
- I2 fixed: two per-range lines at most, then one roll-up per kind with singular/plural, NoticeLine maxLines 4 with ellipsis; strings in spec section 8 and CopyTableTest (matrix widened to four ranges); the spec TRUNCATED row removed. "cut short" no longer appears in any code or copy; only the plan and Gruff review mention it as a deleted row. RangeSummaryText still maps the TRUNCATED state to "not swept, scan limit reached", which is a different string and is correct.
- I3 fixed: large-network notice only when `completed` (set after the collect returns normally), extraMessage cleared only when ext is enabled, manual ranges read only when `extEnabled`. Traced against MVP: with the toggle off, the combine and notice path now match the MVP. The only addition is the stale-network guard, which matters only after a network switch. RunEndTest covers the stopped-scan, toggle-off case.
- NETWORK_CHANGED wiring pure and tested (`ScanFinish.fromController`, `RunMemory.afterNetworkChange`); the tests assert real outcomes, not tautologies. The VM wiring itself (that `networkId.value` is what gets passed) stays unverified by tests, which is inherent.
- Headline counts DONE ranges only (tested).

Minor, new (no action required now):
1. HomeComponents.kt NOTICE_MAX_LINES = 4 against a worst case of about 8 notice lines (two probe notes, two per-range lines, two roll-ups, "Stopped at", cap-dropped): the cap-dropped line, which tells the user how to fix it, is last and can be ellipsized away. Typical runs are well under 4.
2. `RangeSummary` is drawn when at least one extra range exists, so a run whose extras were all PHANTOM, capped or stopped shows "0 other ranges checked" (still in the copy table); consider hiding the headline or rewording at zero.
3. Spec line 456 still lists TRUNCATED among the notice states "given user-facing wording here", which is now stale.
