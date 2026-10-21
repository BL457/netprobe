# Gruff review: Phase 3D navigation spec

Verdict: APPROVE WITH CHANGES. The two Blocking items must be closed in the spec before the plan is written.

Reviewed: `2026-10-21-phase3d-navigation-design.md` against branch `phase3c-1` (0949b59), `ToolRunner.kt`, `DetailViewModel.kt`, `HomeViewModel.kt`, `HomeContent.kt`, `ScanController.kt`, `PortScanEngine.kt`, `Nav.kt`, `MainActivity.kt`, `AppContainer.kt`, the 3C spec and the 3C-2 plan.

Overall: a bottom bar with four destinations is the right call. Material 3 says a navigation bar for three to five top-level destinations on compact width; tabs are for peers inside one destination; a drawer is for six or more or rarely used ones. The Director's actual complaint (a brute-force he can no longer see) is solved: tool runs are already on `appScope`, so Activity only has to show them. The weak part is D10. Moving port scans to `appScope` changes a liveness rule, and the spec does not follow that change through the code that relied on the old rule.

## Blocking

**1. Port scans on `appScope` have no background or app-exit rule (D10, 3.3).**
Today each kind of work has a clear liveness rule. The radar and Quick scan all run on `HomeViewModel.viewModelScope`, so they end when the activity finishes (Back on Home). Tool runs pause while `container.foreground` is false (`ToolRunner.applyForeground`, `Control.gate`), and `KeepScreenOn(extended || toolRun)` covers them. `PortScans` as drawn has neither rule. Press Back on Devices during a Full scan and it keeps probing 65,535 ports with no UI and no pause. It also does not count towards KeepScreenOn, and doze can stall it mid-way. Change: add a 3.3 rule for `foreground == false`. My recommendation is to match the tools: `PortScanEngine.scan` takes a `gate: suspend () -> Unit = {}` called before each `sem.acquire()`, and `PortScans` passes the same foreground wait `ToolRunner` uses. `toolRunActive`'s KeepScreenOn input also counts live port scans. State in one line what Back on the Devices root does to each kind of work: radar and Quick end; port scans and tool runs pause and resume on return. Add a `PortScansTest` case: no probe is issued while foreground is false, and it resumes when foreground is true.

**2. "Forget this network" can now run under a live port scan (section 2, D10).**
`HomeViewModel.forgetNetwork` (line 250) and `HomeContent` `forgetEnabled` (line 86) block only on the radar, Quick, and `toolRunningOnNetwork`. That flag is computed as `busy[it.id] == HostBusyRegistry.Work.TOOL` (HomeViewModel.kt:156). Today a Device port scan cannot be running while Home is visible. After D10 it can, so Forget would soft-delete and then cascade the network under a running scan. Change: rename the flag to `workRunningOnNetwork`, make it true for any `HostBusyRegistry` claim (TOOL or PORT_SCAN) on a host of the network, and add JVM tests: Forget is refused with a port scan running on the network, and allowed with one running on another network.

## Should fix

**3. MAX_PORT_SCANS = 2: say what happens at the cap and prove the load (D10, 3.3, A4, 9.3).**
Today at most one Device scan exists, because two Device screens cannot be open at once. Two is a reasonable cap, but the worst case is now two scans at `scanConcurrency` (default 128, each on its own `Dispatchers.IO.limitedParallelism`), plus Quick scan all, plus four tool runs at 100 req/s. A4 asserts this is fine and no test checks it. Changes:
- (a) State that there is no queue. The third request is refused and nothing waits, so Stop never meets a queued item.
- (b) The Device screen's Scan shows as unavailable when the cap is reached (as the brute-force sheet already reads `activeMap.size`), not only as a snackbar after the tap.
- (c) A copy test pins `PORT_SCANS_TOO_MANY` to `MAX_PORT_SCANS`, so changing the constant cannot leave "Two" behind.
- (d) Add a 9.3 step: two Full scans, Quick scan all and a brute-force together. Record the completion times and any ANR. Check that the open ports on one host match a solo scan of that host.

**4. Port scan end state and messages are undefined (3.3).**
`ToolRunner.end` posts to `AppMessages`, so a run that ends off screen is announced. Port scans that end off screen announce nothing, and `PortScanState.message` has no rule for when it is cleared. A `NETWORK_CHANGED` from yesterday could greet the Director on his next visit to that device. Change: on end, `PortScans` removes the host's entry from `state` and posts the summary to `AppMessages` with the host id, in the `ToolCopy.finished` form ("Port scan: Done in 14 s, 5 open ports"). The Device screen shows it through the path it already uses for tool messages. Add the copy row.

**5. D5 contradicts 1.3 rule 1 and test 9.2.1.**
The bar is hidden on every pushed route, so no one can switch destinations while a Device screen is open. "Leaving Activity with a Device screen open over it and coming back" and "Devices to host to Activity to Devices shows the host screen again" cannot be reached in the UI. That test would pin a path users cannot take. Change: keep D5 (see item 16). Rewrite rule 1's example to what is reachable: root state is restored (scroll, fields, filters). Replace the 9.2.1 case with this sequence: Devices > Activity > open host > Back (Activity root) > Back (Devices root). If the Director answers open item 5 with "shown", restore the original test then.

**6. Activity misses a long-running job that already outlives its screen (3.1, D9).**
3C spec section 5 puts wordlist URL import (and file import) on `appScope`: "Leaving the screen does not cancel." That is exactly the Director's pattern of leaving the page and then having no way to see the job. Change: add `LiveWork.Kind.WORDLIST_IMPORT`. Its progress is the 3C "Fetching 1.2 of 3.4 MB" text, Stop calls the import's Cancel, and a tap pushes `<tab>/wordlists`. Otherwise record it as an open item for the Director to exclude. Also say in one line that "Listen between scans" (3C D10) is a mode, not a job, and does not appear.

**7. Map the Director's four named pages explicitly (Purpose, D2, D1, section 11).**
He asked for discovery, scans, settings and about. The spec maps "scans" to Activity without saying so. It also turns "about" into a Settings row that opens a dialog, and labels that "Controller baseline". Change: add a three-column table: his word, the destination, why. Add "Scans" to open item 1 as a label option for Activity, since it is his own word. Make "About is a Settings row, not a page" its own open item.

**8. Superseding 3C D12 overrides a Director ruling, and the order wastes 3C-2 work (D15, section 8, 12 row 2).**
3C D12's source is "Director (entry point, ruling 3)", not a controller baseline. The 3C-2 plan (Task 7b and the Home overflow Compose test) builds and tests "Run a tool..." in the overflow, and 3D then deletes it. Change: list the supersession as an open item. If the Director accepts the Tools destination before 3C-2 builds, drop the overflow wiring from 3C-2 rather than build it and then remove it; otherwise keep the order as drawn. Renaming SMB share listing to 3E (open item 6) is fine as an open item.

**9. Drop the tablet rail (D8, 1.6, open item 3).**
The app is portrait-locked and its only device is the Pixel 7 Pro. The mandatory device QA can never exercise the rail, so it would be a second inset model (`LocalBarInset` branch, rail layout) shipped untested on real hardware. Change: remove D8, `BarLayout` and the rail tests, and note it as future work. If the Director wants it kept, 9.3 needs a NOT RUN entry for it.

**10. Tighten the history window rules (3.4 rule 3, 3.5 item 4, D12).**
- (a) Rule 3 windows by "ended", but an INTERRUPTED row has no `finishedAt`. Say it is windowed by `startedAt`.
- (b) 3C-2's `recentForNetwork` orders by `startedAt DESC LIMIT :limit` and includes RUNNING rows, which rule 2 then drops. Fetch `RECENT_LIMIT + MAX_ACTIVE_RUNS`.
- (c) The empty-state line "and for a day after" is untrue for scan outcomes after a restart. Use "Scans and tool runs show here while they run, and finished ones stay for a day", or say in the line that scan outcomes last until NetProbe closes.
- (d) State that `KEEP_RUNS_PER_TOOL = 10` pruning can remove a run from Recent inside the 24 h.

On the in-memory question itself: memory is enough for this phase. The Director's complaint is about work in progress. The radar outcome already survives on Devices and a port scan leaves the host's last-scanned mark. Activity only reads `tool_runs` and never writes it, so there is no duplication. Clear history deletes nothing, which honours the no-wipe rule. Keep open item 4 as drawn.

**11. Remove device from a Device screen opened from Activity (1.3 rule 6, 3A 5.3).**
`DetailViewModel.removeDevice` "offers Undo on Home". With rule 6, the pop lands on Activity, not Devices, so the Undo snackbar has no defined place to appear and the chance to undo can be missed. Change: say where it shows. Either Activity also shows `c.undo` (as 3.5 item 5 does for `AppMessages`), or Remove always pops to the Devices root. Add a 9.3 step.

**12. Tests for the D10 move (9.1, 9.2).**
No test in `test/` or `androidTest/` pins "a Device scan stops when the screen is left", so D10 breaks no test. Nothing pins the new behaviour from the ViewModel side either. Change: add a JVM test in which clearing the `DetailViewModel`'s store during a `PortScans` run leaves the scan running and the host claimed. Update `RemoveDeviceTest` so Remove still joins the scan through `portScans.stopAndJoin` before the delete. `NavTransitionTest`'s `NetProbeNavHost` signature changes, so list it in 9.2.3 as rewritten, not extended.

## Consider

**13. Back and predictive back (1.3).**
The rules match Material's per-destination stacks and Android's "back to the start destination". Add two lines. First: with targetSdk 30, predictive back is not opted into (`enableOnBackInvokedCallback` is not set) and no `BackHandler` is added. Second: Back on the Activity, Tools or Settings root discards that destination's saved stack, which is the standard behaviour.

**14. `ActivityState.PAUSED` is dead while Activity is visible (3.1).**
`ToolRunner.Active.paused` is `!foreground` for every run, so it is never true while the screen can be seen. Drop PAUSED, or give it copy and a test. Do not leave an unrendered state.

**15. Copy details (section 6).**
`stopDescription` gives "Stop Port scan on Office NAS"; lower-case the title inside the description. `PORT_SCANS_TOO_MANY` could point at the fix: "Two port scans are already running, stop one in Activity".

**16. D5 keep, with a cheap hint.**
Hiding the bar on pushed screens is consistent with Material, where full-screen detail screens may cover it, and the five-port-row budget is a real cost. The gap is that while he is viewing device B there is no sign that a brute-force is running on A. A trailing "N running" text in the existing back row costs no height, and a tap on it goes to Activity. That would let the bar stay hidden. Offer it with open item 5.
