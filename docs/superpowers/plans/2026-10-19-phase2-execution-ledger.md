# SDD ledger - plan: docs/superpowers/plans/2026-10-19-netprobe-phase2.md

Specs: docs/superpowers/specs/2026-10-19-radar-ui-design.md (Tasks 1-9), docs/superpowers/specs/2026-10-19-extended-discovery-design.md (Tasks 10-20). Both Gruff-reviewed and fixed; plan Gruff-reviewed (15 findings applied).
Branch: phase2 (from master at 66580a4). BASE before Task 1: 569cd8f
Roles: implementer = Ada (sonnet; opus for Tasks 4, 6, 7, 15), task reviewer = Bugsy (sonnet; opus for 4, 6, 7, 15), device steps by the implementer with the phone attached, final review = Dot (opus). Controller installs nothing extra: every UI task installs its own build.

Ruling: work on branch `phase2` in place, same reasoning as the MVP - cost if wrong: none.
Ruling: the landscape question from Gruff's UI review resolved as portrait lock - cost if wrong: no landscape use.
Ruling: font files fetched by the controller under the Director's explicit approval (variable TTFs, ~1.1 MB; static files no longer hosted) - cost if wrong: ~700 KB over the estimate.
Ruling: no Room migration; grouping derived at display time (Gruff ED finding 3) - cost if wrong: none.
Ruling: plan deviations resolved: "every address answered" wording kept; unused "cut short" row deleted; per-range done summary built in Task 16 - cost if wrong: copy polish.
Ruling: "No network" shown on the network line when there is no live network (Gruff fix) rather than the stored network name, which would need a view-model change - cost if wrong: a less informative line while offline.

## Pre-flight conflict scan (plan after Gruff review, commit 569cd8f)

| Producer task | Consumer task(s) | Interface | Finding |
|---|---|---|---|
| 1 theme tokens, Plex FontFamily, LocalReducedMotion, portrait lock | 4, 6, 7, 8, 17 | token names, CompositionLocal | clean (Gruff checked A) |
| 2 RadarModes, summaries, progress strings, HomeCopy | 4, 5, 6, 16 | pure helpers | clean; copy-table test enumerates every state (Gruff finding 8 fix) |
| 3 row/group-header/Detail text helpers, actionWord, identityWithType | 6, 7 | pure helpers | clean |
| 4 Radar composable (HERO/SCANNING/SETTLED, quick ring, RadarGeometry.armMask) | 6 | single animated fraction, tags radarRing/radarStop/scanButton | clean |
| 5 HomeUiState read-only fields (lastScanAt, totalHosts, otherSubnetHosts, scanStartedAt, scanFinishedAt), ScanFinish.afterNetworkChange | 6, 16 | fields | clean |
| 6 HomeContent(state, actions) + screen | 9, 16 | content split | clean |
| 7 DetailContent + sheets, imePadding on portList | 9 | content split | clean |
| 8 Settings restyle with imePadding | 17 | column kept | clean |
| 10 Probe.connect(network: Network?) | 13, 14, 15 | signature change; existing callers updated in 10 | clean (Gruff checked callers) |
| 11 Cidr/Subnet ops, per-network manual settings | 12, 13, 17 | pure | clean |
| 12 CandidateRanges, RangeRules.groupFor, DNS evidence | 15, 16 | pure | clean |
| 13 GatewayProbe (targets, TEST-NET, attempts counter) | 15, 20 | progress under lock (Gruff fix) | clean |
| 14 ExtendedOptions/status, PhantomGuard (address-ordered sample), full-result sweep | 15 | types | clean (Gruff MAJOR 1 fixed) |
| 15 DiscoveryEngine extended phase | 16, 18, 20 | callbacks (Int, Int, Int) | clean |
| 16 VM plumbing, range grouping, notices, RangeSummary | 17, 19, 20 | HomeUiState.lastRunRanges | clean |
| 18 NeighbourRules (on-link rows kept, MAC filtered) | 20 | Outcome.onLinkIps | clean |
| Self-consistency per task | 1..20 | tests vs code vs files | Gruff review D checked; Ada applied fixes 1-15 | clean |

Global-constraint check: no downloads (fonts already committed), no new dependencies, schema v1, targetSdk 30, portrait lock in Task 1, leaveApksInstalledAfterRun on every instrumented run.

## Progress
Task 1: dispatched to Ada (sonnet), BASE 569cd8f. Phone attached but keyguard showing at dispatch; Director asked to unlock.
Task 1: implementer NEEDS_CONTEXT - code green (225 unit tests, TypographyTest 1/1 on device) but phone locked and dozing, screenshots black and discarded
Ruling: commit the code now; screenshots become a follow-up commit once the Director unlocks the phone; task review proceeds on the code diff and completion waits for the screenshots - cost if wrong: one extra commit
Task 1: code committed (35e8b8b), screenshots pending the phone unlock; review dispatched to Bugsy (sonnet)
Task 1: review clean (Bugsy). Important plan-mandated: spec says stale edge alpha 0.5 but 0.5 fails the 3:1 floor; code uses 0.6
Ruling: correct the spec to 0.6 (controller edit) - cost if wrong: none
Task 1: minor (deferred): TypographyTest cannot detect Plex not loading; duplicate bar hex in MainActivity; redundant ExperimentalTextApi opt-in
Task 1: code complete and reviewed (commits 569cd8f..35e8b8b); SCREENSHOTS PENDING phone unlock before marking complete
Task 2: dispatched to Ada (sonnet), BASE 35e8b8b
Task 2: implementer DONE_WITH_CONCERNS (commit 86b9232): CopyTableTest regex escapes doubled (brief had illegal single backslashes; Task 16 must use the same form); spec-file concern was a false alarm; review dispatched to Bugsy (sonnet)
Task 2: review clean (Bugsy). Important plan/spec-level: lastScanAt = max(lastDiscoveryRun) is null for a zero-host network, so a finished empty scan falls back to HERO after a process restart
Ruling: within a session Task 5 folds scanFinishedAt (lastScanAt ?: scanFinishedAt); after a restart HERO for a zero-host network is accepted (nothing to show) - carry to Task 5 and 6 dispatches - cost if wrong: "Nothing scanned yet" wording on a network that was scanned and empty
Task 2: minor (deferred): HomeCopy doc comment overclaims completeness; ringDescription capitalisation; copy-table regexes accept 0 counts; HeaderRules test gaps; STOP_QUICK string not in the table; Task 16 must use doubled regex backslashes
Task 2: complete (commits 2dfb315..86b9232, review clean)
Task 3: dispatched to Ada (sonnet), BASE 86b9232
Task 1: screenshots committed (7c6212f): dark window and bars, Plex rendering, portrait held
Task 1: complete (commits 569cd8f..35e8b8b code, 7c6212f screenshots, review clean)
Task 3: implementer DONE (commit c090c5b); review dispatched to Bugsy (sonnet)
Task 3: review clean (Bugsy). Minor (deferred): identityWithType endsWith has no word boundary; blank ownModel not guarded; empty local subnet renders "Home subnet "; sheet repeats Copy when primary is Copy (plan-mandated)
Task 3: complete (commits 7c6212f..c090c5b, review clean)
Task 4: dispatched to Ada (opus), BASE c090c5b
Task 4: implementer BLOCKED at the device step - code green (JVM 270/270, RadarBlockTest 8/8 on device before the final blip move), phone relocked itself mid-task; nothing committed
Ruling: commit the code now (as Task 1); device RadarBlockTest rerun and committed screenshots follow the next unlock; review proceeds on the diff - cost if wrong: one extra commit
Ruling: Ada deviations accepted: brief compile fixes; arm centre mask is a cut-out not a solid disc (solid hid the inner ring and blips); blips drawn above the arm (matches mockup) - cost if wrong: none
Ruling: blip radius fraction [0.5, 0.88] not [0.22, 0.88]; spec contradicted itself (blips must not cover the count); spec corrected (controller edit); 4 dp spacing still holds (5.5 dp) - cost if wrong: blips cluster nearer the rim
Ruling: quick-scan ring: base stroke dims to `border` while the indeterminate `signal` arc sweeps, since teal on teal is invisible (Ada concern); spec 3.3 and 12.9 corrected; applied in Task 4 before commit - cost if wrong: a dimmer ring during quick scan
Ruling: quick-scan dimmed stroke uses `border` (#3B5368) as the spec names it; my hex in the ruling above was borderSubtle - keep `border` - cost if wrong: none
Task 4: implementer DONE (commit ddf1ad4); device rerun + screenshots PENDING UNLOCK; review dispatched to Bugsy (opus)
Task 4: review (Bugsy, opus): spec compliance OK; quality Needs fixes - I1 Stop pill + progress line pop in/out so the list jumps ~82 dp (brief code and spec 3.6 row 227 contradict the "list slides" rule); 10 minors
Ruling: I1 fix - pill and progress area height and alpha driven by the same animated fraction as the disc; spec row 227 corrected (controller edit) - cost if wrong: a slightly busier settle
Ruling: M6 fix - count max width 80 dp (matches the 80 dp arm cut-out); spec 3.3 corrected (controller edit) - cost if wrong: smaller digits for 4-digit counts at 1.3 scale
Ruling: fix now in round 1: I1, M1 (gate quick arc on RESUMED), M2 (no Scan click until the settle ends), M3 (reset blips on SCANNING), M5 (blip alpha read as a lambda), M6, M9 (floorMod). Deferred: M4 (invisible pill clickable during the 120 ms delay, low risk), M7 (stale headline announcement, depends on Task 5/6 wiring - carry to Task 6), M8 (TalkBack double count), M10 (report wording) - cost if wrong: minor a11y polish
Task 4: fix round 1 - resume Ada (opus), BASE ddf1ad4
Task 4: fix round 1 DONE (babaca7, 271 JVM green; device run of new height test pending unlock). Leftover: HERO to SCANNING steps ~30 dp once (HERO text swapped for the pill area)
Ruling: HERO step deferred - happens once per network on the first scan and the Director sees it on the device screenshots; fix only if he notices - cost if wrong: one 30 dp hop on first scan
Task 4: scoped re-review dispatched to Bugsy (sonnet), diff ddf1ad4..babaca7
Task 4: re-review round 1 (Bugsy): all seven fixed, no regressions; NEW Important - blockHeightSlidesWithoutAStepBothWays would pass on the pre-fix code (half-height bounds too loose); PILL_FADE_MS/PILL_DELAY_MS now unused
Task 4: fix round 2 - folded into Ada's running device step: tighten the test to <12 dp over the first two frames, drop the dead constants
Task 4: fix round 2 + device steps DONE (5764db3): RadarBlockTest 9/9 on the Pixel, JVM 271/271, 8 radar screenshots committed, build installed on the phone. Ada concern: disabled idle ring label faint (spec colour) - for the Director's eye
Task 4: scoped re-review round 2 dispatched to Bugsy (test tightening only), diff babaca7..5764db3
Task 4: re-review round 2 clean (Bugsy). Deferred minors: M4, M7 (carry to Task 6), M8, M10; HERO to SCANNING 30 dp hop
Task 4: complete (commits c090c5b..5764db3, review clean, 9/9 device, screenshots committed, build installed)
Task 5: dispatched to Ada (sonnet), BASE 5764db3
Task 5: implementer DONE (023cd28, HomeStatsTest 5/5). Notes: afterNetworkChange wired in Task 16 per brief; scanStartedAt = latestRun stays set after the scan (Task 6 uses it only while scanning); review dispatched to Bugsy (sonnet)
Task 5: review clean (Bugsy). Minor (deferred): scanFinishedAt/lastScanAt leak across network switch and cancelled scan until Task 16 wires afterNetworkChange; otherSubnet test uses hand-built rows; scanStartedAt is session-wide (Task 6 gates on scanning)
Task 5: complete (commits 5764db3..023cd28, review clean)
Task 6: dispatched to Ada (opus), BASE 023cd28
Task 6: implementer DONE (d2c38e9 code, b7d360e screenshots; device HomeContentTest 16/16, RadarStateTest 2/2, HomeScreenTest 2 pass 1 skipped on Wi-Fi; JVM 275). Deviation: Scaffold insets navigationBars.union(ime) so the no-match text and last rows clear the keyboard. Unreproduced oddity: once landed on Detail for 10.0.3.1 after dismissing the location notice with Back. M7 still open (RadarBlock-side fix). Review dispatched to Bugsy (opus)
Ruling: ime inset deviation accepted (spec requires imePadding behaviour; a hidden empty-state is a defect) - cost if wrong: none
Task 6: review clean (Bugsy, opus); device check: Back oddity not reproduced in 8 tries, Home has no Back handler, likely a stray touch after the late notice shifted rows 64 dp; scan/stop/disc-tap/summary all behave. Plan vs spec: done summary derived from the view model, not rememberSaveable
Ruling: view-model derivation satisfies spec 3.4 (summary survives Detail and back); spec wording stands as intent - cost if wrong: none
Task 6: minor (deferred, candidates for the final fix round): 1.3 font-scale row test threshold 186 dp vs ~235 dp needed; list re-grouped every recomposition (wrap in remember(hosts, subnet)); first rows into an empty list skip the arrival animation; backing out of the app loses the done summary; late notice shifts rows under the finger
Task 6: complete (commits 023cd28..b7d360e, review clean, device-checked, build installed)
Task 7: dispatched to Ada (opus), BASE b7d360e
Task 7: implementer DONE (fa0e39d code, 0fa138e screenshots; device DetailContentTest 9/9, JVM 275). Deviations: progress reserve max(line height, measured line); navigationBars.union(ime) as Task 6; linear indicator gap and stop-dot disabled. Concerns: Stop pill a few dp wider than Scan so the profile control nudges; Custom field opens the text keyboard; device run did a real Full scan of CHNAS (stopped ~7%, saved ports 3261/3262, one NVD search). Review dispatched to Bugsy (opus)
Ruling: three deviations accepted (all device-found rendering fixes within spec intent) - cost if wrong: none
Task 7: review (Bugsy, opus): compliant bar long-press; quality Needs fixes. I1 long-press on the action word runs the primary action (launched JuiceSSH) instead of the sheet - brief used plain clickable; I2 ImeAction.Done on the multi-line note removes Enter so notes cannot have line breaks (MVP regression) - brief mandated Done; 7 minors. Device check otherwise clean
Ruling: I1 fix - action word uses combinedClickable, long press opens the sheet like the row body - cost if wrong: none
Ruling: I2 fix - ImeAction.Default on the note (spec never says Done; the brief did); collapse still on focus loss - cost if wrong: Done key gone, Enter inserts a line break as in the MVP
Ruling: fix now: pill width (draw both labels, unused one invisible), note cursor at the end on expand. Deferred: Back hides the keyboard but leaves the note open; text keyboard on the Custom field stays (number pad may lack comma and hyphen)
Task 7: fix round 1 - resume Ada (opus), BASE 0fa138e
Task 7: fix round 1 DONE (6e1d7ba; device DetailContentTest 12/12, JVM 275, installed). Scoped re-review dispatched to Bugsy (sonnet), diff 0fa138e..6e1d7ba
Task 7: re-review round 1 clean (Bugsy). Deferred minors: Back hides keyboard but leaves the note open; text keyboard on Custom field; remaining Bugsy minors in task-7-review.md
Task 7: complete (commits b7d360e..6e1d7ba, review clean, device-checked, build installed)
Task 8: dispatched to Ada (sonnet), BASE 6e1d7ba
Task 8: implementer DONE (adb82c6; device 2/2, JVM green, installed). Deviation: contentWindowInsets navigationBars.union(ime) as Home/Detail (brief double-counted the nav bar); middle screenshot dropped as identical. Concerns: M3 slider geometry gappy; Clear cache under keyboard until scrolled. Review dispatched to Bugsy (sonnet)
Ruling: inset deviation accepted (consistent with Tasks 6 and 7) - cost if wrong: none
Task 8: review clean (Bugsy). Minor (deferred): TextButton padding indents two rows ~12 dp; disabled sliders double-dim; sliders lack TalkBack label; no gap above segmented control/permission button; thin tests; M3 slider thumb look
Task 8: complete (commits 6e1d7ba..adb82c6, review clean, device-checked, installed)
Task 9: dispatched to Bugsy (opus) as QA executor, BASE adb82c6. Rulings: NO pm clear (deletes the Director's marks) - HERO via Wi-Fi off / never-scanned network only; every device setting touched (font_scale, animator scale, Wi-Fi) restored; TalkBack row recorded as "not run" unless the Director enables it; Detail scanning evidence via Quick profile, no Full scans - cost if wrong: thinner a02/TalkBack evidence
Task 9: QA DONE (fix ac89432 segment label padding at 1.3; record 081a0ca). Instrumented suite 57/0/1 skipped. Not run: TalkBack; connected HERO not captured. Settings restored (font_scale back to the Director's 1.15). Open items for the Director: stale dimming lost after restart; stopped/network-cut scan still says "Scanned in N s"; ~300 ms text/ring overlap during settle; splash icon; note stays expanded until focus moves; vuln offline line unreachable; raw ISO CVE dates; instrumented suite scans into live data; spec 11.1-11.6
Task 9: scoped review of fix ac89432 dispatched to Bugsy (sonnet, fresh); Task 10 dispatched to Ada (sonnet) in parallel (reviewer is read-only), BASE 081a0ca
Task 9: review clean (Bugsy, sonnet). Minor (deferred): 2 dp segment padding tight at 1.3; Settings /22 control not asserted at 1.3
Task 9: complete (commits adb82c6..081a0ca). PART A (Radar UI) COMPLETE
Task 10: implementer DONE (51f8a99; ProbeBindTest 7/7). Bind via BoundNetwork coroutine-context element + SocketBinder; callers unchanged. Notes: GatewayMac must not run inside a BoundNetwork context unless intended (carry to Task 15); binderFor needs device QA (Task 20). Review dispatched to Bugsy (sonnet)
Task 10: review (Bugsy): compliant; quality Needs fixes. I1 PortScanEngine catch(Exception) swallows BindFailedException so an extended phase could never end SKIPPED_BIND_FAILED; I2 TlsPeek, DeviceInfoTxt (UDP), UpnpDescription build sockets outside Probe and ignore BoundNetwork; 6 minors (incl. bindOrFail catching CancellationException)
Ruling: I1 fix now (rethrow BindFailedException in PortScanEngine) plus the bindOrFail CancellationException rethrow - cost if wrong: none
Ruling: I2 carried to Task 15: every enricher the extended phase calls for extra-range hosts must go through Probe or bind its own socket to BoundNetwork; NvdClient is internet traffic and exempt - cost if wrong: an enricher leaks onto default routing for extra-range hosts
Task 10: fix round 1 - resume Ada (sonnet), BASE 51f8a99
Task 10: fix round 1 DONE (a01c236; ProbeBindTest 9/9). Scoped re-review dispatched to Bugsy (sonnet)
Task 10: re-review round 1 clean. Minor (deferred): cancellation test covers the Socket overload only; 6 earlier minors in task-10-review.md
Task 10: complete (commits 081a0ca..a01c236, review clean)
Task 11: dispatched to Ada (sonnet), BASE a01c236
Task 11: implementer DONE (679338f; Rfc1918Test 4, CidrParserTest 6, ManualRangeStoreTest 4). Review dispatched to Bugsy (sonnet)
Task 11: review clean (Bugsy). Minor (deferred): Ipv4 accepts leading zeros and Unicode digits (brief-verbatim); whitespace stripped inside octets; edge-test gaps; AppSettings.manual adapter untested
Task 11: complete (commits a01c236..679338f, review clean)
Task 12: dispatched to Ada (sonnet), BASE 679338f
Task 12: implementer DONE (365c836; CandidateRangesTest 12/12, RangeRulesTest 6/6). Note: groupFor is first-match-wins in list order. Review dispatched to Bugsy (sonnet)
Task 12: review (Bugsy): compliant; Approved with one Important test gap - RangeRulesTest puts the manual /28 in both lists so dropping "+ manualRanges" stays green; 5 minors. First-match grouping confirmed as spec (scanned before manual)
Ruling: fix the test gap now (manual-only case + swept /24 plus manual /28 precedence case); minors deferred - cost if wrong: none
Task 12: fix round 1 - resume Ada (sonnet), BASE 365c836
Task 12: fix round 1 DONE (6f4d06a, tests only, mutation check went red). Scoped re-review dispatched to Bugsy (sonnet); Task 13 dispatched to Ada (sonnet) in parallel, BASE 6f4d06a
Task 12: re-review round 1 clean. Minor (deferred): overlap-with-truncated untested; no 172.x/192.168.x ordering tests; cap test sizes only; sub-/24 swept range excludes the rest of its /24 evidence; dnsServers not de-duplicated
Task 12: complete (commits 679338f..6f4d06a, review clean)
Task 13: implementer DONE (ea934c6; GatewayProbeTest 8/8). Ruling: onProgress is the 3-arg (done, units, attempts) form as the interface, call sites and test use (brief listing had 2) - carry to Tasks 15 and 20 - cost if wrong: none. BindFailedException propagates from run (intended). Review dispatched to Bugsy (sonnet)
Task 13: review clean (Bugsy). Minor (deferred): no cancellation/BindFailed propagation test; progress test weak on peak>1 and 80-then-443 order; onProgress exception inside the lock aborts the probe; seeds filtered by scanned (no effect for RFC1918)
Task 13: complete (commits 6f4d06a..ea934c6, review clean)
Task 14: dispatched to Ada (sonnet), BASE ea934c6
Task 14: implementer DONE (25de21d; PhantomGuardTest 4, SweepTest 2). Review dispatched to Bugsy (sonnet)
Task 14: review clean (Bugsy). Minor (deferred): runAll KDoc says ordered but completion-ordered; thin boundary tests; runAll BindFailed/concurrency untested; progress counts a BindFailed host; Double 0.9 compare fragile. Address-ordered sample + phantom discard are in Task 15 sweepExtra (check there)
Task 14: complete (commits ea934c6..25de21d, review clean)
Task 15: dispatched to Ada (opus), BASE 25de21d
Task 15: implementer DONE (1de0df7; DiscoveryEngineExtendedTest 20/20 incl. a mutation-checked bind-failure test; DiscoveryEngineTest 20/20 unchanged; 351 JVM). Ruling 4 applied: extra-range hosts get reverse DNS, NetBIOS, SNMP, HTTP title only (last three bound); TlsPeek/DeviceInfoTxt/UpnpDescription never called off-subnet. Concerns resolved by later briefs: HomeViewModel binder is Task 16 (binderFor(e)); neighbour evidence is Task 18. Open: reverse DNS for extra-range hosts uses the system resolver, unbound - to the reviewer. Review dispatched to Bugsy (opus)
Task 15: review (Bugsy, opus): compliant, MVP sweep unchanged, 9/13 mutants killed; Needs fixes for I1 only - reverse DNS for extra-range hosts uses the unbound default-network resolver (VPN leak, foreign names saved to Room); 5 minors; plan conflict: Task 18 expects 21 extended tests, now 22
Ruling: I1 fix - skip reverse DNS when a BoundNetwork is in context (one line + test); spec 5.3 corrected (controller edit); bound DnsResolver PTR is a follow-up - cost if wrong: no hostnames for other-subnet hosts
Ruling: fix now also: one random high port per run (spec), the 6.9 s wall-clock test moved to virtual time. Deferred: no gateway-probe bind-failure test; Stop lag up to ~4 s on a slow HTTP title (MVP behaviour); "other ranges" label copy belongs to Task 16; Task 18 test count 21 -> 22 (carry)
Task 15: fix round 1 - resume Ada (opus), BASE 1de0df7
Task 15: fix round 1 DONE (ccd21d9; 352 JVM; extended tests now 21 so Task 18 count is +2). Scoped re-review dispatched to Bugsy (opus, same reviewer)
Task 15: re-review round 1 clean. Deferred minors: no gateway-probe bind-failure test; Stop lag on slow HTTP title; follow-up: bound DnsResolver PTR for off-subnet hosts
Task 15: complete (commits 25de21d..ccd21d9, review clean)
Task 16: dispatched to Ada (sonnet), BASE ccd21d9
Task 16: implementer DONE (25b1999; ui.home 86 tests, HomeContentTest 19/19 on device, grouping seen on the phone with the Hue Bridge under "Other subnet 10.0.4.0/24"). Ruling 1 applied with an extra NETWORK_CHANGED null of finishedAt (race). Ruling 2: new template "probing for gateways, N of M  S s". Concerns: CidrParser.subnetOf min /20 so a CIDR regex used; no unit test for the VM network-change wiring; M7 not cheap, left. Review dispatched to Bugsy (sonnet)
Task 16: review (Bugsy): compliant; Needs fixes. I1 discover finally can publish the old network's ranges/notices after the env collector cleared them; I2 per-range notices joined with newlines into the pinned NoticeLine (16 ranges = ~19 pinned lines); I3 extended-off path not MVP byte-for-byte (large-network notice moved into finally, extraMessage cleared, manual ranges read with the toggle off); 8 minors
Ruling: fix all three now. I2: at most two per-range notice lines, then one roll-up per kind ("N other ranges: nothing found" / "N other ranges answered for everything"), strings added to the spec table by Ada; I3: restore the MVP ordering and gate the manual-range read and extraMessage clear on the toggle. Also now: a pure-function test for the NETWORK_CHANGED wiring; headline counts swept ranges only - cost if wrong: copy polish
Task 16: fix round 1 - resume Ada (sonnet), BASE 25b1999
Task 16: fix round 1 DONE (f1abb09; HomeContentTest 19/19 on device; RunEnd/RunMemory/ScanFinish.fromController pure helpers + tests). Scoped re-review dispatched to Bugsy (sonnet, same reviewer)
Task 16: re-review round 1 clean. Minor (deferred): NOTICE_MAX_LINES 4 can ellipsize the cap-dropped line in an ~8-line worst case; "0 other ranges checked" when all extras phantom/capped/stopped; spec line 456 TRUNCATED mention (controller edit now)
Task 16: complete (commits ccd21d9..f1abb09, review clean)
Task 17: dispatched to Ada (sonnet), BASE f1abb09
Task 17: implementer DONE_WITH_CONCERNS (af71329 code, 112360f screenshots; ExtendedSectionTest 6/6 on device; settings restored and read back). Concerns: error line under the manual field hidden by the keyboard; Add range button text stays teal when disabled
Ruling: fix both before review (bringIntoView / scroll the error into view when it appears; disabled Add range uses the disabled text colour) - cost if wrong: none
Task 17: pre-review fix - resume Ada (sonnet)
Task 17: pre-review fix DONE (8693b56; ExtendedSectionTest 6/6; settings restored). Review dispatched to Bugsy (sonnet), diff f1abb09..8693b56
Task 17: review clean (Bugsy); device check passed, settings read back unchanged. Minor (deferred): local not keyed on network; notes not reset on network change; no stepper-bounds or stored-key tests; error text in a dimmed editor
Task 17: complete (commits f1abb09..8693b56, review clean, device-checked, installed)
Task 18: dispatched to Ada (sonnet), BASE 8693b56. Carry: DiscoveryEngineExtendedTest count is 21 (brief expects 21 pre-task; Ada's brief counts need +2 overall vs the plan)
Task 18: implementer DONE (d39f93e; NeighbourRulesTest 6/6, extended 23/23, engine 20/20). Note: onLinkIps added to Outcome (brief snippet omitted it); device neighbour dump not re-checked (Task 20). Controller committed the spec line-456 edit separately. Review dispatched to Bugsy (sonnet)
Task 18: review clean (Bugsy); on-link MAC trace PASS. Minor needing ruling: a proxy-ARP router loses its own MAC (gateway entry counted in the proxy filter, NeighbourRules.kt:121)
Ruling: the gateway's own IP always keeps its MAC, exempt from the proxy-ARP filter (the Ubiquiti row showed its MAC in the MVP and must not regress) - fix now with a test - cost if wrong: none. Other minors deferred: MAC-less proxy rows kept; same IP two MACs last-wins; test gaps; weak hotspot assertion
Task 18: fix round 1 - resume Ada (sonnet), BASE d39f93e
Task 18: fix round 1 DONE (7e3511a; NeighbourRulesTest 7/7). Scoped re-review dispatched to Bugsy (sonnet); Task 19 dispatched to Ada (sonnet) in parallel (different files), BASE 7e3511a
Task 18: re-review round 1 clean
Task 18: complete (commits 8693b56..7e3511a, review clean)
Task 19: implementer DONE_WITH_CONCERNS (2110cc3; KeepScreenOnTest 1/1 on device). Flag scoped to extended runs per spec; live dumpsys evidence needs the toggle on - carried to Task 20. Concern: flag set outside the try before locks.acquire(). Review dispatched to Bugsy (sonnet)
Ruling: live keep-screen-on dumpsys evidence captured in Task 20 (toggle on there) - cost if wrong: none
Task 19: review clean (Bugsy). Minor (deferred): set the flag inside the try (free hardening); no VM-level set/clear test; stale flag while STOPPED; test mutates Compose state off the UI thread
Task 19: complete (commits 7e3511a..2110cc3, review clean)
Task 20: dispatched to Bugsy (opus) as QA executor, BASE 2110cc3. Rulings: checks 7 (Wi-Fi switch) and 10 (VPN) recorded "not run, Director unavailable" and the Director's confirmation of the grouped list recorded as pending (controller asks him in the final message); settings toggled for the checks are restored to defaults afterwards (toggles off, cap 4, manual ranges cleared) and font_scale back to the Director's 1.15, not the brief's 1.0; no pm clear; no marks or notes changed - cost if wrong: two rows without evidence
Task 20: QA DONE_WITH_CONCERNS (fix 6d71fb3 SECONDS_PER_RANGE 25 -> 16; record 4f9f5e9; spec 8.3 corrected 885d08b). Instrumented 67/0/1 skipped. 12 passed, 2 not run (7 Wi-Fi switch, 10 VPN: Director). Ranges: 10.0.4.0/24 evidence 4 hosts; 10.0.0.0/24 probe 9; 10.0.2.0/24 probe 1; 192.168.1.0/24 probe 3; 192.168.2.0/24 capped. Per /24 13.7-16.2 s, gateway phase ~9.4 s, 1041 attempts. Check 9 EMPTY not PHANTOM (UniFi drops 10.0.9.0/24). Settings restored incl. font_scale 1.15. Open items for the Director in the QA record
Task 20: scoped review of fix 6d71fb3 dispatched to Bugsy (sonnet, fresh); FINAL whole-branch review dispatched to Dot (opus), range 569cd8f..885d08b, in parallel
Task 20: scoped review clean (Bugsy). Minor (deferred): QA record does not label which run b-range-summary.png belongs to (cap 4 run)
Task 20: complete (commits 2110cc3..885d08b). ALL 20 TASKS COMPLETE. Awaiting Dot final review
FINAL REVIEW (Dot, opus): Ready after fixes; 382 JVM tests forced rerun green. I1 neighbour rules run with the toggle off (range-extender clients lose MACs, not MVP byte-for-byte); I2 port scans of off-subnet rows (Quick scan all, Detail) not bound to Wi-Fi (VPN leak, spec 5.5); I3 canary probes the .1/.254 the gateway probe just proved real, so a real range that resets closed ports reads PHANTOM; 12 minors
Ruling: single final fix dispatch covers I1 (neighbour rules only when the toggle is on; toggle-off path identical to the MVP, test flipped), I2 (off-subnet port scans run inside BoundNetwork via binderFor when extended is on; bind failure surfaces the existing notice; local rows unchanged), I3 (canary picks the lowest and highest unknown addresses excluding gateway-probe hits and .1/.254), plus deferred minors: Task 19 M1 flag inside the try; Task 17 M2 Settings subnet/notes keyed on the network; Tasks 15/16 M4 wording: never-started ranges read "not started" and the headline is omitted when no range was swept - cost if wrong: copy the Director may re-word
FINAL FIX ROUND DONE (0dfdc01 I1, 8555b00 I2 incl. ScanBinding + TlsPeek bind, 3984d1f I3 canaryFallback, 9e22098 M1, dd7748c M2, 0a02460 M4; HEAD 0a02460). JVM 390/0 forced rerun; device 69/0/1 skipped; final build installed 05:13. Concerns: Detail reuses "Other ranges skipped" wording for a bind failure (Director copy); the range summary row hidden when nothing swept. Scoped re-review dispatched to Dot (opus), diff 885d08b..0a02460
FINAL RE-REVIEW (Dot): READY TO MERGE. Spec line 234 aligned (never-started ranges stay PENDING); final review doc committed. Copy items for the Director: Detail bind-failure wording ("Scan skipped: could not bind to Wi-Fi" suggested); range summary row hidden when nothing swept. Open minors M3, M5-M9, M11, M12 in the final review doc. Awaiting the Director's merge decision
MERGED: phase2 fast-forwarded into master at 2f255e0 (Director: "Merge it to master"); branch phase2 deleted; unit suite + assembleDebug rerun green on master; merged build installed on the Pixel for field testing
