# NetProbe Phase 3D: whole-branch review (plan and code)

Reviewer: Bugsy (QA). Range 3427760..c12833f, branch phase3d. Date 2026-10-21.
Scope: spec (FINAL), plan, ledger, five implementer reports, the full app/ diff. Nobody reviewed any task and the plan was never Gruff-reviewed; this is the first and only review of both.

## Verdict: READY AFTER FIXES

No data-loss or crash defect was found by reading and by the one allowed gate run, and the concurrency core (LiveWork, ForegroundGate, PortScans) holds up under close reading. But three things should be fixed before the Director spends device time (a NavHost graph that is rebuilt on every badge change, an uncapped Running block, and an untested one-line wiring that is the whole "pause in background" feature), one spec requirement is only half built (identify traffic keeps going in the background), and the entire UI half has never run. The Blocking list is empty on purpose: nothing below is a confirmed defect that corrupts data or crashes; the risk is carried by "Should fix" and by the device order.

Evidence actually produced in this review:
- Gate run once: `./gradlew :app:testDebugUnitTest` -> BUILD SUCCESSFUL. Result XMLs summed: 1236 tests, 0 skipped, 0 failures, 0 errors. Matches the ledger.
- Grep checks (all clean): no em-dash anywhere under app/src (all file types) nor in the spec or plan; no change to any Gradle file, the manifest, schemas or data/; no `BackHandler` or `enableOnBackInvokedCallback` in main; no leftover `onOpenSettings`, `onAbout`, `toolRunningOnNetwork`, "Back to Home" in main or unit tests (only the intended `assertDoesNotExist` lines in androidTest); every `ScanController(` is in PortScans or HomeViewModel (x2), none left in DetailViewModel.
- Not runnable here: the phone is off limits, so no Compose test, no navigation behaviour and no timing was observed. Everything below about navigation, insets and transitions is reading only and is labelled so.

## Blocking

None confirmed.

Candidates I examined hard and did not promote: Forget guard hole, double claim, claim leak on every path, cap race, process-death record. All hold (see "Verified"). The NavHost rebuild (S1) and the Running block cap (S2) become Blocking if the device shows flicker, lost state or an unreachable Stop.

## Should fix

S1. NavHost graph is rebuilt on every Scans badge or KeepScreenOn change (Nav.kt:123-146 and the builder in NetProbeNavHost). `NetProbeNav` reads `scansRunning`, `portScans.active`, `extendedRunActive` and `toolRunActive` in composition and builds a brand new `NavSlots(...)` each time any of them changes. `NetProbeNavHost` passes a `builder` lambda that captures `slots`; `NavHost` keys its `remember { createGraph(builder) }` on that lambda, so each change creates a new `NavGraph` and assigns `navController.graph` on a live back stack. The library probably tolerates it, but it is wasted work on the hottest path, can interact with an in-flight transition, and defeats the "do not recompose the shell per tick" intent. Fix: in `NetProbeNav` wrap the slots in `remember(container, nav) { NavSlots(...) }` (every lambda only uses `container` and `nav`) and pass `running` only to the bar. Then confirm on the device that a badge change during a tab fade causes no flicker. (Unverified: reading only.)

S2. Running block has no height cap (ScansContent.kt:89-98; known open point in the 5-6 report). Up to eight rows (radar or Quick, 2 port scans, 4 tools, 1 import) sit in the Scaffold topBar. Rows allow two-line title and two-line detail. A real title such as "Directory brute-force on Office NAS:443" wraps at font scale 1.3, so a row is nearer 100 dp than the spec's 66 dp; four such rows plus the title row and the 80 dp bar can leave no height for Recent, and past the screen height the lower Stop pills are unreachable (the topBar does not scroll). The only guard is `ScansContentTest.atFontScale13WithEightRunningRows...`, which uses short titles ("Port scan on NAS 1") and has never run. Fix: guarantee Recent a minimum (cap the topBar, and past the cap switch rows to one-line titles with the detail beside the Stop pill), and add a second test with the long titles. Do not put the rows in a scroll container (house rule).

S3. The background pause has no regression guard (HostScanBody.kt:40, AppContainer.kt:146-147). `gate = c.foregroundGate::await` and `ForegroundGate(foreground)` are the only things that make a port scan pause, and no unit test mentions `HostScanBody` or `foregroundGate`. Delete the `gate =` argument, or build `ForegroundGate` on another flow, and all 1236 tests stay green. The engine gate and `ForegroundGate` are each tested alone; the join is not. Fix: inject the gate into `HostScanBody` so a JVM test can drive it with a fake engine and a fake flow, or at minimum add a source-text guard like the ownership test.

S4. Identify keeps probing the device in the background (HostScanBody.kt:41-50). Only the sweep is gated. Each open port found launches a banner identify (`launch(start = ATOMIC)`, up to MAX_PARALLEL_IDENTIFIES at a time) that keeps connecting to the device while NetProbe is backgrounded, for as long as the queue of found ports lasts. Spec 3.3 says "no new probe starts" in the background; the plan wrote it for the sweep only. Fix: call `c.foregroundGate.await()` before `sem.withPermit` in the identify launch (cancellation-safe), and extend S3's test to cover it.

S5. "Done in N s" includes background time (Task 1 ruling R1, open). A Full scan paused for ten minutes reports "Done in 11 min 20 s" in the Recent line, while tool runs report active time only. Two kinds of row in one list disagree. This is a Director decision (below); if he wants active time the fix is small, `ToolRunner` already accumulates `activeMs` from the same `foreground` flow.

S6. A stale Stop on a tool row can stop a newer run (ScansViewModel.kt:113, `StopRoute.Tool(hostId)`). LiveWork items stop by their own id, so a stale tap is a no-op (verified in code and in tests). Tool rows stop by host: if a run ends and a new run starts on the same host between the last recomposition and the tap, the tap stops the new run. Small window, but Review Focus 2 was written for exactly this. Fix: carry `runId` in `StopRoute.Tool` and stop only if `toolRunner.active.value[hostId]?.runId == runId` at tap time.

S7. The Remove path can post a message for a device that was just deleted (DetailContent.kt:75, PortScans.kt:108-115 and :128). The Remove menu calls `actions.onStop()` (non-silent `stopRun`) and then `onRemove`, whose `stopAndJoin` sets `silent`. The stopped job completes on a Default thread and its `onEnd` can run before `silent = true` is set on Main, giving "Port scan finished: Stopped after 4 s" for a removed device, on the page the Director lands on. Fix: drop the `actions.onStop()` call from the Remove lambda (`stopAndJoin` already stops and joins), or set `silent` inside `stopRun` when removal asked for it.

S8. AppMessages is a single slot and now has more producers (AppMessages.kt:9-13, PortScans.kt:128). Two scans ending together, or a scan ending while a tool message is unread, overwrite each other without a trace; a message that ends while the Director is on Settings (which shows none) surfaces minutes later on the next screen that does. True for tool runs before, but port scans double the traffic and run much longer. Needs a decision, not necessarily code.

## Minors

1. Nav.kt:154-170 (shell) and the bar `AnimatedVisibility`: the bar probably slides in on every cold start. `currentBackStackEntryAsState()` is null for the first frame, so `barVisible` goes false then true, and the root's bottom inset jumps from navigation-bar-only to bar-inset one frame later. Fix: seed `AnimatedVisibility` with `MutableTransitionState(true)` or derive visibility from `nav.currentBackStackEntry`. Device-check first.
2. Nav.kt:118 `BAR_HEIGHT = 80.dp` is a constant while the real `NavigationBar` grows with content (`defaultMinSize`). At font scale 1.3 with the "Settings" label the bar may exceed 80 dp and cover a few dp of the last row. Fix: measure the bar (`onGloballyPositioned`) into a state and build the inset from it, 80 dp as first-frame default. Device item: last row on all four roots at 1.3.
3. Nav.kt:143 and :146 `nav.popBackStack()` on a double tap of a back row pops twice and removes the root, leaving a blank NavHost. Pre-existing pattern, but pushed screens are now more reachable. Fix: pop only when `nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED`.
4. ScanController.kt:19 `private var generation = 0L` (and `job`, `watcher`) are now read from Default threads for PortScans' controllers (appScope, no longer Main.immediate) and written from Main. Harmless for PortScans because it reads `controller.state` only for `NO_NETWORK`, but the class KDoc still promises single-thread use. Fix: `@Volatile` on the three and update the KDoc.
5. AppContainer.kt:227-229 and ScansRules.kt:59: the badge counts raw `live.size + active.size`; the Running block drops items of a forgotten network or a removed host, so the badge can read 1 over an empty block. Spec 3.7 says badge = size of the built running list. Rare (Forget is blocked while work runs, Remove joins the scan). Fix: build the badge from `ScansRules.build` or document the difference.
6. PrefsOpenScanStore.kt:52 uses `apply()`; a crash between start and the async flush loses the Interrupted line. `am kill` from background (QA item 9) is flushed by the stop hook so QA will not see it. Fix: `commit()` (two writes per scan).
7. ScansCopy.kt:21 `UNKNOWN_HOST = "device"` KDoc says a removed host's outcome "never reaches the screen"; `removedHostIds` is session-only and cleared on Undo (DetailViewModel.kt:283,300), which is right while the process lives. Correct the comment so nobody "fixes" it.
8. ScansContent.kt:107-109 `RowProgress`: an indeterminate `LinearProgressIndicator` per tool or import row animates continuously, plus 4 Hz list recomposition. Acceptable; if the device shows jank, use one shared animation or a static 2 dp line.
9. ScansRules.kt:98 `ProgressLine.of(c.i.radarStatus, ...)` reads the engine-wide `discoveryEngine.status`, correct only because radar and Quick scan all exclude each other. Add a comment.
10. DetailComponents.kt:197-205 the cap refusal line is hidden whenever a tool bar shows (`toolBar != null ->` wins the `when`), so a disabled Scan pill can sit beside a tool bar with no reason. Fix: order the refusal before the tool bar or append it to the tool label.
11. DetailViewModelOwnershipTest is a source-text test (acknowledged, plan note 3h). It would still pass if `PortScans` were constructed on `viewModelScope` in AppContainer. Accept, but say so in the QA record, or move the scope choice into a testable factory.
12. NavBar.kt:56-57 the badge text in `caption` style inside `Badge` may clip "9+". Device check.
13. ScansRules.kt:108 `startedAt = history...startedAt ?: (now - activeMs)`: with no history row (another network) the start time moves with `now`, so rows with near-equal starts can swap order between ticks. Remember the first computed value per run id.
14. Older literals remain outside Copy objects (SettingsScreen "Discovery", "Speed", DetailContent "Scanning ports", "No open ports yet, run a scan"). Out of this phase's scope; do not claim the "every string in a Copy table" rule holds repo-wide.
15. RemovedNamesTest counts the text `DropdownMenuItem(` in HomeComponents.kt; a helper refactor would break it for the wrong reason. The Compose test already added covers it; drop the count or comment it.
16. NoEmDashTest scans `.kt` only; the rule is repo-wide. Extend to `res/**/*.xml` and `docs/**/*.md`, or note the gap.
17. HomeScreenTest (new bar test) launches the real activity, which now builds `portScans` in `onActivityCreated` and drains `scansOpenScans` from the Director's real preferences. Harmless but not read-only; Task 12 already runs it last after the backup.
18. Spec hygiene: spec 3.1 says Interrupted keys are `lost:<startedAt>:<hostId>`, the build uses `live:<id>`; spec 3.3 says `activityOpenScans`, the build uses `scansOpenScans`. Add a one-line "as built" note to the spec.

## Verified (examined and found correct)

Concurrency and lifetime
- Cap of two, refusal, no queue: `start` claims the host and checks `running.size` under one lock (PortScans.kt:75-91); the loser releases its own claim. The simultaneous-start test uses real threads and checks exactly two claims. A scan that is stopping still counts until `finish`, so a rapid stop-then-start can briefly see TooMany; acceptable.
- Claim released on every path: `finish` is idempotent (CAS) and runs from the controller's `invokeOnCompletion` (normal end, exception, cancel, cancel before first dispatch), from `stopAndJoin`, and from the NO_NETWORK refusal. Order is running entry, record, then host claim, so a new scan cannot lose its own record. Cancel before first dispatch, no network at start, throw, bind failure and stale stop by id each have a test. (The NoNetwork-appears-between-the-check-and-controller-start branch has no test; it is two lines and reads correctly.)
- One lifecycle source: `ForegroundGate` wraps `AppContainer.foreground`, the same flow `ToolRunner` collects; the engine calls `gate()` before each `sem.acquire()` (at most one extra probe after a pause, as the plan notes). No second gate, no second flow. See S3 and S4 for what is untested or missed.
- KeepScreenOn: boolean OR of four flows in `NetProbeNav`; a paused scan keeps `active` true, harmless because the window is not visible.
- Sockets: probes are children of the engine's `channelFlow`; `gate()` is cancellable; cancel closes sockets (unchanged `Probe.connect`).
- Main-thread contract: every caller of `PortScans.start`, `stop`, `LiveWork.stop` (Scans click, Detail click) and `stopAndJoin` (`Dispatchers.Main.immediate`) is on Main; `finish` and `reportProgress` never touch the controller. Lock order is engine monitor then PortScans lock only; nothing takes them the other way round, so no deadlock.
- The scan survives leaving the Device page (owner scope is `appScope`; test `a scan outlives the scope that asked for it`) and does not outlive the process (no GlobalScope, no detached launches).
- Process death: record added at start, removed at finish, drained once in `init` into Interrupted entries; `onActivityCreated` builds `portScans` at launch so the Interrupted line cannot sit above newer entries. Back at the Devices root only pauses (activity stop) and does not drain, so no false Interrupted.
- LiveWork: publishes at most every 250 ms, ticker ends with the last entry and restarts safely (check and clear share the lock), stop lambda held beside the data and dropped on `end`, `end` idempotent, `ended` capped at 50, restore after Clear merges.
- Forget (Blocking 2 of the spec): `ForgetGuard.workRunning(allHosts, busy)` covers every claim kind including manual-only rows, and `forgetNetwork()` re-reads it at tap time; Quick scan all's per-host claims are covered too.
- Radar and Quick scan LiveWork entries: a cleared HomeViewModel cancels the job, `tracked` ends the entry STOPPED and drops the stop lambda, so no row is left RUNNING with a dead Stop. The radar `networkId` fix (`setNetwork`) is correct.
- URL import: begins with the URL host, progress in bytes, Stored/Failed/Cancelled map to DONE/FAILED/STOPPED with the existing messages, Stop from Scans cancels the fetch; not gated by the foreground (spec).

Scans screen
- Running block and every Stop are in the Scaffold topBar; exactly one `LazyColumn` (Recent) and no `verticalScroll` in ui/scans, NavBar.kt or Nav.kt. Wordlist imports are items. Undo from `UndoStore` outranks the Clear prompt, which outranks an app message; a timeout commits nothing; leaving the screen ends the Clear undo. Window rules, Interrupted sorted and windowed by start, the 54-row fetch, future-dated items and negative durations are tested and read correctly.
- A stale Stop on any LiveWork item is a no-op (S6 is the tool-row exception).
- Badge: an `Int` from `stateIn` collapses equal counts, so the 4 Hz ticks never recompose `NetProbeNav` by themselves (but see S1 for the other recompositions).

Navigation (reading only, nothing run)
- Bar on the four roots only (`BarRules`), hidden on pushed routes and with the IME; per-tab graphs with the canonical `popUpTo(start){saveState}`, `launchSingleTop`, `restoreState`; the Devices entry is never popped so `HomeViewModel` lives as before; Back from a device returns to the opener's tab; the Wordlists back label is frozen per entry. No dependency, manifest or Gradle change.
- Insets: roots use `rootInsets()` (bar + navigation bars joined with IME). The Home Scan/Stop pill and the Tools Run/Stop pill are both in topBars, so they are never under the bar. Bar height caveat in Minor 2.

Roots and copy
- Home overflow is exactly Quick scan all (Stop quick scan) and Forget this network; `onRunTool` and the per-port tool buttons are intact; the Tools root has the Wordlists action; Settings has the title row with the overflow and About as the last section; Back labels come from `NavCopy.backTo` and "Back to device". Removed names are gone and guarded.

## Test honesty

- Replaced tests (plan note 3h): `DetailViewModelScanLifetimeTest` and the RemoveDevice change became `PortScansTest` cases plus a source-text ownership test. The PortScans cases genuinely prove the owner's lifetime (`a scan outlives the scope that asked for it`, `stopAndJoin returns only after the body's finally`), and silence on Remove is really asserted (a second stop would change the message text). Nothing proves that DetailViewModel drives that owner correctly beyond string matching (Minor 11). Acceptable without Robolectric, but the QA record must say so.
- Behaviour versus mirroring: PortScansTest, LiveWorkTest, UrlImportLiveWorkTest, ScansRulesTest, ScansTargetsTest, ForgetGuardTest assert behaviour; Task 1's mutation of `onComplete` failed nine tests. Copy-table tests mirror strings by design. RemovedNamesTest and NoEmDashTest are sound but brittle (Minors 15, 16).
- One test was bent openly: ScansRulesTest `items at or before the clear time` (5-6 report). The rule was right, the brief's data was wrong; I read the new data and agree.
- Gaps with no JVM test: the HostScanBody-to-gate join (S3), identify traffic in the background (S4), AppContainer wiring of PortScans on `appScope`, `ScansViewModel.stop` routing.

androidTests that compile but have never run, in the order the device QA must run them
1. `ui.NavBarTest`: restoreState with `rememberSaveable`, Back from a non-start tab, rapid taps leaving one Devices entry. The only coverage of the highest-risk piece (nested graph + saveState + singleTop). If `rapidTabTaps...` or `backFromANonStartRootLandsOnDevices` fails, stop and report: it means a duplicate Devices entry or a double exit.
2. `ui.NavTransitionTest`: timing-based (the earlier phase had a frame-clock flake, QA F3); record the frame budget, do not widen it.
3. `ui.NavBadgeTest`.
4. `ui.scans.ScansContentTest`: pinned block; font scale 1.3 with eight rows (short titles only, see S2).
5. `ui.detail.DetailContentTest`: cap refusal, back labels.
6. `ui.settings.SettingsContentTest`: About last, dialog with three lines.
7. `ui.manual.ManualToolsContentTest`: Wordlists action, no back row.
8. `ui.wordlists.WordlistsScreenTest`.
9. `ui.home.HomeContentTest`: two-item overflow, Forget disabled.
10. Rest of the suite, then `ui.HomeScreenTest` last (real activity, after the backup).

## Plan quality in hindsight

Under-specified in the plan, guessed by the implementers, and my view:
- Slot and graph stability in `NetProbeNav` (S1): not mentioned. The implementer followed the plan text literally. Fix as S1.
- Running block height: the plan accepted the spec's 66 dp per row arithmetic and left "watch at Task 12". The arithmetic ignored two-line titles at 1.3. Disagree (S2).
- `ScanController` moved to appScope: the plan said "callers on Main" and that was honoured, but did not harden the class. The implementer's deviations 1 (start inside the lock) and 2 (detect the NO_NETWORK refusal exactly) are good and I agree with both. `@Volatile` was missed (Minor 4).
- "No new probe starts" was written for the sweep only; identify traffic was not considered (S4).
- Badge source: raw count at container level instead of `ScansRules.badge(running)` (Minor 5). I accept the reason (no history or names at container level).
- Radar `networkId`: the plan began the entry before the network row resolved; fixed by controller ruling. Agree with the fix; no HomeViewModel-level test pins the call (LiveWork-level tests only).
- Back at the Devices root now leaves paused scans alive and invisible (foreground false until the next launch), holding both cap slots and the Forget guard. Accepted in spec 1.3 and coherent, but the Director has not been told plainly (decision 3).
- AppMessages single slot (S8) and paused-time wording (S5) were flagged by the implementers and left; both need a Director answer rather than a silent default.

## For the Director

Decisions needed
1. Tab icons and labels (standing rule: icons need your approval before they ship). Built as: Devices = radar dish (Radar), Scans = pulse line (MonitorHeart), Tools = wrench (Build), Settings = gear (Settings); outlined when unselected, filled when selected; labels "Devices", "Scans", "Tools", "Settings", always shown. Look at all four selected and unselected, and the badge with 1, 2 and 9+ running. Say which to change.
2. "Done in N s" for a port scan that was paused in the background: wall time (today) or active time only (as tool runs do). I recommend active time so the two kinds of row agree.
3. Back at the Devices root: port scans and tool runs pause, keep their slots and still count on the badge until you reopen the app; radar and Quick scan all end. Keep this, or end port scans too when you leave by Back?
4. Messages: when two scans finish together, or one finishes while you are on Settings, keep the single "last message wins" slot, or keep the last few?
5. Cap of two port scans: confirm after the load check (device item 5). If the phone or LAN struggles the constant drops to one and all copy follows.

Ordered device checks (after S1 to S4 are in; install over the existing build, never a clean install, back up first as the plan's Task 12 preamble says)
1. Run `NavBarTest` first (list above); stop on any failure.
2. Look at the four tabs and the badge (decision 1) at font scale 1.0 and 1.3, in gesture and three-button navigation: labels fit, the last row on each root clears the bar, the bar does not slide in on cold start (Minor 1).
3. Your scenario: NAS, Directory brute-force, Back; badge "1"; Scans shows live progress; Stop from Scans; badge clears.
4. Full port scan, Back; scan keeps running and shows in Scans; tap it, Back returns to Scans; message on finish.
5. Two scans running, a third Scan is disabled with its line; then add Quick scan all and a brute-force (load check: record completion times and any "Skipped N frames").
6. Background 30 s with a Full scan running: done count frozen, resumes on return, screen stays on while running; watch for traffic from identify while backgrounded (S4).
7. Process kill from background and relaunch: Interrupted line appears; Clear history cut-off survives.
8. Forget disabled while a port scan runs; Remove from a Scans-opened device pops to Scans with Undo; Wi-Fi off mid-scan gives "Network changed, scan stopped".
9. Eight-row stress for the Running block with long titles at 1.3 (S2).
10. URL import visible and stoppable in Scans; Clear history, Undo, and Reset to defaults leaves it alone; reduced motion gives instant switches.
