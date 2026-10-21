# NetProbe Phase 3D Navigation Design (bottom bar, Devices, Activity, Tools, Settings)

Date: 2026-10-21. Author: Ada (Engineering Lead). Status: revised after Gruff's review (`2026-10-21-phase3d-navigation-gruff-review.md`) and the controller's rulings on it; the Director's open items are in section 11.
Baseline: branch `phase3c-1` (3A and 3B complete, both 3C plans committed, 3C-1 building). This plan builds after the 3C-2 device QA (section 8).
Related specs: `2026-10-19-radar-ui-design.md` (the "Radar spec": Home, Detail, Settings, tokens, Phase 2 navigation), `2026-10-20-phase3a-polish-design.md` (the "3A spec": motion tokens, transitions 1.9, Home overflow, Undo), `2026-10-20-phase3b-followup-tools-design.md` (the "3B spec": `ToolRunner`, `HostBusyRegistry`, Home running marker, `AppMessages`), `2026-10-21-phase3c-feedback-design.md` (the "3C spec": Settings overflow and reset, manual tools, wordlist URL import).

The Director's request, verbatim:

> we need a few extra things. We need a page set up I think. One for discovery. One for scans. One settings. One for about. and any more you think should be there. Work out what the current best practice is for how this should be arranged (tabs, or side bar, or something else etc). This way, any scans going on can be seen in the relevant page since currently, If I have a bruteforce going and I go back a page, there's no easy way to see what's currently in progress.

## Purpose and decisions

NetProbe today is one stack: Home, with Device, Settings and Wordlists pushed over it and everything else in Home's overflow. Work that outlives its screen (tool runs and the wordlist URL import on `appScope`) has nowhere to be seen once its screen is left. This phase gives the app four top-level destinations behind a Material 3 bottom navigation bar, and one place, Activity, that lists every scan, tool run and wordlist URL import that is running or ran recently, with live progress and Stop.

Best practice, as applied: Material 3 recommends a navigation bar for three to five top-level destinations of equal importance on compact widths, and a modal drawer only for six or more destinations or rarely used ones. Tabs are for peer views inside one destination, not app-level sections. NetProbe has four destinations used constantly, so a bar. Android's navigation principles add: a fixed start destination (Devices), each bar destination keeps its own back stack (`saveState` / `restoreState`), and Back from any other destination's root returns to the start destination before leaving the app.

The Director's four pages, mapped:

| His word | Destination | Why |
|---|---|---|
| discovery | Devices | The radar and the device list are the discovery page; "Devices" names what it shows. |
| scans | Activity | Every scan and tool run, running or recent, in one list; this is the page his brute-force complaint asks for. "Scans" is offered as its label (open item 1). |
| settings | Settings | As now, with About last. |
| about | A row at the end of Settings opening the About dialog | Version and build lines are read once, not visited; a bar slot for them would push a used destination out. Open item 2. |
| (added) | Tools | The 3C-2 "Run a tool" screen and Wordlists, so manual tools have a home of their own. |

Standing rules carry over: Scan, Stop and Run controls and the navigation bar are never inside a scroll container; no em-dashes; no disclaimers or warnings in copy; no new dependencies (navigation-compose 2.9.0, material3 from BOM 2025.06.00 and material-icons-extended are already in); no downloads; every user-facing string is in the copy table (section 6) and pinned by `CopyTableTest`.

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | A Material 3 `NavigationBar` with four destinations: Devices, Activity, Tools, Settings; labels always shown; phone only, no navigation rail | Controller baseline, Director's brief; rail dropped (Gruff 9) |
| D2 | About is the last row of Settings and opens the existing About dialog; it is not a destination and leaves the Home overflow | Controller baseline; Director to confirm (open item 2) |
| D3 | Devices is the start destination and is today's Home (radar, list, filters, search); the radar Scan stays on it | Controller baseline |
| D4 | Each destination is a nested graph with its own back stack; bar taps use `popUpTo(start) { saveState = true }`, `launchSingleTop`, `restoreState`; reselecting the current destination pops to its root | Material and Android guidance |
| D5 | The bar shows on the four destination roots only; Device detail and Wordlists are pushed full screen over it and hide it | Ada, kept by the controller (1.4) |
| D6 | The bar also hides while the keyboard is up | Ada (1.4) |
| D7 | Destination switches cross-fade at `Motion.NAV_MS`; pushes and pops inside a destination keep 3A's slide (1.9); the bar hides and shows with a vertical slide at `NAV_MS`; everything is `None` under reduced motion | Ada, 3A tokens |
| D8 | (Removed: the tablet rail. Future work if a tablet is ever a target.) | Controller ruling on Gruff 9 |
| D9 | Activity lists radar scans, Quick scan all, Device port scans, tool runs and wordlist URL imports in one model; running items in a pinned block, recent items in the list below | Controller baseline and ruling on Gruff 6 |
| D10 | Device port scans move from `DetailViewModel`'s `viewModelScope` to an app-level `PortScans` owner on `appScope`, so leaving the Device screen no longer stops them; at most `MAX_PORT_SCANS = 2` at once, a third is refused with no queue; they pause in the background through `ForegroundGate` and keep the screen on, as tool runs do | Ada; controller rulings on Gruff 1 and 3 |
| D11 | A new in-memory `LiveWork` registry publishes radar, Quick scan all, port scan and URL import progress app-wide, throttled to `Motion.PROGRESS_SAMPLE_MS`; tool runs keep `ToolRunner.active` | Ada (3.2) |
| D12 | Recent: finished in the last 24 h, at most 50 items; tool runs from `tool_runs` survive a restart; scan and import outcomes are memory only, except that a port scan cut off by process death is shown as Interrupted at the next launch | Controller baseline and ruling on Gruff 1; open item 5 |
| D13 | "Clear history" hides recent items finished before a stored cut-off; nothing is deleted; Undo from the snackbar | Ada (3.8) |
| D14 | The Activity bar item carries a badge with the running count; no badge at zero | Controller baseline |
| D15 | Tools hosts the 3C-2 "Run a tool" screen as its root, plus a Wordlists entry; Wordlists stays reachable from Settings and the brute-force sheet | Controller baseline |
| D16 | The 3C spec D12 entry point (Home overflow "Run a tool...", a Director ruling) is built by 3C-2 as ruled; 3D moves it into the Tools destination (section 2). **Director to confirm:** if he agrees before 3C-2 starts, 3C-2 skips building the overflow entry and its test; if he does not confirm by 3D, the overflow item stays and opens the Tools destination | Controller ruling on Gruff 8 |
| D17 | The Home overflow keeps "Quick scan all" and "Forget this network" (plus "Run a tool..." if D16 is not confirmed) | Controller baseline |
| D18 | The back row names where Back goes ("Back to Devices", "Back to Activity", ...), replacing "Back to Home" | Ada (Home no longer exists by name) |
| D19 | "Forget this network" is disabled while any port scan or tool run holds a host of that network | Controller ruling on Gruff 2 |
| D20 | Remove device from a Device screen opened from Activity pops to Activity, and Activity shows the pending Undo from `UndoStore` | Controller ruling on Gruff 11 |
| D21 | SMB share listing, placed in "Phase 3D" by 3C spec D18, becomes Phase 3E | Controller ruling |
| D22 | No schema change; three new `SharedPreferences` keys (section 7) | Ada |

## 1. Navigation structure

### 1.1 Destinations

| Destination | Graph route | Root route | Pushed routes | Label | Icon (plain words, for the Director's approval) |
|---|---|---|---|---|---|
| Devices | `devicesTab` | `devices` | `devices/host/{id}`, `devices/wordlists` | Devices | radar dish |
| Activity | `activityTab` | `activity` | `activity/host/{id}`, `activity/wordlists` | Activity | pulse line |
| Tools | `toolsTab` | `tools` | `tools/wordlists` | Tools | wrench |
| Settings | `settingsTab` | `settings` | `settings/wordlists` | Settings | gear |

The root `NavHost` has `startDestination = "devicesTab"`. Each destination is a `navigation(route = <graph>, startDestination = <root>)` block. The same screen composable serves every route it appears under (`DetailScreen` under `devices/host/{id}` and `activity/host/{id}`); route names differ per graph because a route may appear once in a `NavHost`. Pure object `Routes` (in `ui/Nav.kt`) holds the strings and `tabOf(route: String?): Tab?`, `host(tab, id)` and `wordlists(tab)`; `Tab` is an enum `DEVICES, ACTIVITY, TOOLS, SETTINGS` in bar order. Icons come from material-icons-extended (the implementer picks the nearest glyph for each word, outlined when unselected and filled when selected) and are shown to the Director on the device before the merge (standing rule: icons need his approval).

Results sheets, the brute-force options sheet, the vulnerability sheet, the Edit device dialog and the About dialog are not routes; they stay `ModalBottomSheet`s and dialogs owned by their screens, drawn over everything including the bar.

### 1.2 The bar

`NetProbeNav` lays out a `Box`: the `NavHost` fills it, and the bar is aligned to the bottom over it inside `AnimatedVisibility` (1.4). Bar: `NavigationBar(containerColor = background)` with a 1 dp `surface` rule along its top edge, a `NavigationBarItem` per `Tab` with `alwaysShowLabel = true`, label in `NetProbeText.chip`, colours: indicator `surface`, selected icon and label `text`, unselected `textMuted`. Item tags `tab:devices`, `tab:activity`, `tab:tools`, `tab:settings`. The bar's own `windowInsets` take the navigation-bar inset, so its 80 dp sit above the gesture area. The bar is outside every scroll container by construction (it is not a child of any screen).

Selection: an item is selected when `Routes.tabOf(currentDestination.route)` is its tab. The badge (3.7) sits on the Activity icon through `BadgedBox`.

### 1.3 Back stack rules

1. Bar tap on another destination: `nav.navigate(tab.graph) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }`. Bar taps happen only on roots (the bar is hidden on pushed screens, D5), so what is restored is each root's own state: the Devices filter, search and list scroll, the Tools fields, the Settings scroll position, the Activity list scroll.
2. Bar tap on the current destination: nothing happens (it is already its root).
3. System Back or the back row on a pushed screen pops one entry within the destination.
4. System Back on the Activity, Tools or Settings root returns to Devices (the start destination stays under them, rule 1) and discards that destination's saved state, the standard behaviour. System Back on the Devices root leaves the app, as Home does today.
5. Devices' entry is never popped while the activity lives, so `HomeViewModel` (and with it the radar scan and Quick scan all) lives as long as today; the other roots' `ViewModel`s survive bar switches through `saveState`.
6. Opening a device from Activity pushes `activity/host/{id}` onto Activity's stack, so Back returns to Activity; opening one from Devices pushes `devices/host/{id}`, so Back returns to Devices.
7. Process recreation restores the stacks through `rememberNavController`'s saved state; no deep links are declared (nothing outside the app opens a NetProbe screen).
8. With targetSdk 30 the app does not opt into predictive back (`enableOnBackInvokedCallback` is not set) and no `BackHandler` is added; Navigation handles Back.

What Back on the Devices root (leaving the app) does to running work: the radar scan and Quick scan all end, as today (`HomeViewModel` is cleared); port scans and tool runs pause and resume when NetProbe returns to the foreground (3.3, 3B 4.8); a wordlist URL import continues to its end (3C 5.1, it is an internet fetch, not a probe).

### 1.4 Bar visibility (D5, D6)

Pure `BarRules.visible(route: String?, imeVisible: Boolean): Boolean` is true exactly when the route is one of the four roots and the keyboard is down (`WindowInsets.isImeVisible`).

Why pushed screens hide the bar: the Device screen's pinned block already spends most of the height (Radar spec 7.5, 3B section 8.2: at least five port rows collapsed, two in the worst case). An 80 dp bar would cut that to four and one. The Device screen shows its own port scan and tool progress, Back is one gesture to Devices or Activity, and the badge is on every root. Wordlists is a task screen with its own pinned imports. The keyboard rule keeps the bar from riding above the IME over Home's search results, the NVD key field and the Tools fields.

Insets: the bar's height is exposed through `LocalBarInset: WindowInsets`: `WindowInsets(bottom = 80.dp).add(WindowInsets.navigationBars)` while the bar is visible, else `WindowInsets.navigationBars`. Each root screen's Scaffold uses `LocalBarInset.current.union(WindowInsets.ime)` where it uses `WindowInsets.navigationBars.union(WindowInsets.ime)` today, so the last list row clears the bar and the inset is never counted twice. Pushed screens keep their current insets and lay out at full height from their first frame; the bar slides out over them, so no content jumps.

### 1.5 Transitions (D7)

Pure `NavMotion.kind(from: String?, to: String?, reduced: Boolean): NavMotionKind` with `SLIDE, FADE, NONE`: `NONE` when reduced; `FADE` when `Routes.tabOf(from) != Routes.tabOf(to)`; else `SLIDE`. The four `NavHost` transition lambdas read it from `initialState.destination.route` and `targetState.destination.route`:

- `SLIDE`: 3A section 1.9 exactly (`NAV_MS` 240, `NAV_ENTER_OFFSET`, `NAV_EXIT_OFFSET`, `FastOutSlowInEasing`).
- `FADE`: `fadeIn(tween(NAV_MS, easing = FastOutSlowInEasing))` and `fadeOut` of the same, no slide (a horizontal slide between peers implies a hierarchy that is not there).
- Bar: `slideInVertically { it } + fadeIn` and `slideOutVertically { it } + fadeOut`, `tween(NAV_MS)`; `EnterTransition.None` and `ExitTransition.None` under reduced motion.

No new motion tokens. The radar arm's pause and resume when Devices leaves RESUMED (3A 1.9) is unchanged; a bar switch is one more way to leave.

## 2. Devices

Devices is today's `HomeScreen` unchanged in layout: network line, radar, notices, chips, search, list, the Home running marker (3B 4.3), the notice line for `AppMessages` and `UndoStore`.

- The overflow (`homeMenu`) becomes, in order: "Quick scan all" (or "Stop quick scan" while it runs) and "Forget this network". "Settings" and "About" leave it (a bar destination and a Settings row). "Run a tool..." (built by 3C-2 as ruled) moves: its target, the manual tools screen, becomes the Tools root, and the item leaves the overflow only when D16 is confirmed; unconfirmed, it stays third and selects the Tools destination (bar rule 1).
- Forget guard (D19): `HomeUiState.toolRunningOnNetwork` is renamed `workRunningOnNetwork` and is true when any host of the shown network has any `HostBusyRegistry` claim (`TOOL` or `PORT_SCAN`). `forgetEnabled` and `HomeViewModel.forgetNetwork` read it exactly where they read the old flag, so a live port scan disables Forget the same way a live tool run does (a disabled menu item, as today; no new copy).
- `HomeScreen(container, onOpenHost)`: the `onOpenSettings` parameter, the `onAbout` action and the About dialog in `HomeContent` are removed. `AboutInfo` moves to `ui/settings/About.kt` unchanged.
- `onOpenHost` pushes `devices/host/{id}`.
- `HomeViewModel` registers its radar scan and Quick scan all with `LiveWork` (3.2); nothing else changes in it.

## 3. Activity

### 3.1 The item model

```kotlin
enum class ActivitySource { RADAR, QUICK_SCAN_ALL, PORT_SCAN, TOOL, WORDLIST_IMPORT }
enum class ActivityState { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }
data class ActivityItem(
    val key: String,              // "live:<LiveWork id>", "tool:<runId>" or "lost:<startedAt>:<hostId>"; unique, stable for LazyColumn keys
    val source: ActivitySource,
    val networkId: Long?,
    val hostId: Long?,            // null for RADAR, QUICK_SCAN_ALL and WORDLIST_IMPORT
    val host: String?,            // display name, the IP for a manual-only row, the URL host for an import; else null
    val tool: ToolId?, val port: Int?,
    val state: ActivityState,
    val done: Int?, val total: Int?, val found: Int?,
    val startedAt: Long,
    val durationMs: Long?,        // running: elapsed (tool runs: activeMs); finished: end minus start; INTERRUPTED: null
    val endedAt: Long?, val summary: String?, val runId: Long?,
)
```

There is no paused state: every pause is a background pause, so it is never on screen (Gruff 14).

Pure `ActivityRules` in `ui/activity/` builds the list from these sources, all app-level after this phase:

| Source | Running from | Finished from | Persists across restart |
|---|---|---|---|
| Radar scan | `LiveWork.live` (kind `RADAR`), progress text from `ProgressLine.of(discoveryEngine.status)` | `LiveWork.ended` | No |
| Quick scan all | `LiveWork.live` (kind `QUICK_SCAN_ALL`), done and total in devices | `LiveWork.ended` | No |
| Device port scan | `LiveWork.live` (kind `PORT_SCAN`), fed by `PortScans` | `LiveWork.ended`; scans cut off by process death from `activityOpenScans` (3.3) | Only as Interrupted |
| Tool run | `ToolRunner.active` | `ToolRepository.recentForNetwork(currentNetworkId, RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS)` (3C-2), status mapped one to one | Yes |
| Wordlist URL import | `LiveWork.live` (kind `WORDLIST_IMPORT`), fed by 3C-1's `UrlImportController` | `LiveWork.ended` | No |

`HostBusyRegistry` is not a source: it says which host is held, not the work's progress. It stays the busy check for starts. "Listen between scans" (3C D10) is a mode, not a job, and does not appear in Activity. File import (3B) reads a local file in a moment and does not appear either.

### 3.2 `LiveWork` (D11)

New `activity/LiveWork.kt`, an `AppContainer` singleton:

```kotlin
class LiveWork(scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { RADAR, QUICK_SCAN_ALL, PORT_SCAN, WORDLIST_IMPORT }
    enum class Outcome { DONE, STOPPED, FAILED, INTERRUPTED }
    data class Live(val id: Long, val kind: Kind, val networkId: Long?, val hostId: Long?, val label: String?, val startedAt: Long,
                    val done: Int, val total: Int?, val found: Int)
    data class Ended(val live: Live, val outcome: Outcome, val endedAt: Long?, val summary: String)
    val live: StateFlow<List<Live>>               // in start order
    val ended: StateFlow<List<Ended>>             // newest first, at most RECENT_LIMIT
    fun begin(kind: Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit): Long
    fun progress(id: Long, done: Int, total: Int?, found: Int)   // stores only; published by the ticker
    fun end(id: Long, outcome: Outcome, summary: String)
    fun stop(id: Long)                            // calls the registered lambda once; no-op after end
    fun addEnded(e: Ended)                        // the launch-time Interrupted entries (3.3)
    fun clearEnded()
}
```

- `progress` writes volatile counters and never emits; one ticker publishes `live` every `Motion.PROGRESS_SAMPLE_MS` (250 ms) while any entry is live and ends itself with the last one, as `ToolRunner`'s ticker does. `begin` and `end` publish at once.
- The `stop` lambdas are held in a map beside the data, not in `Live`, so equality and `distinctUntilChanged` see data only; `end` removes the lambda, so a cleared `HomeViewModel` is never retained.
- Registration points: `HomeViewModel.discoverOn` begins `RADAR` after the network row resolves (stop: `controller.stop()`), feeds `progress(probed, total, found)` from the status it already collects, and ends in its `finally` with `DONE` when `completed`, `STOPPED` on cancellation, `FAILED` on an exception (summary `ScanController`'s message). `quickScanAllBody` begins `QUICK_SCAN_ALL` with the host list size as total, advances `done` per host, and ends in its `finally`. `PortScans` begins and ends `PORT_SCAN` (3.3). `UrlImportController.start` begins `WORDLIST_IMPORT` with the URL's host as `label` (stop: `cancel()`), passes the bytes read and total it already reports (`done` and `total` in bytes, at most 10 MB, so `Int` holds them), and ends with `DONE` and `WordlistImport.message(result)` for `Stored`, `FAILED` and the same message for `Failed`, `STOPPED` and "Import cancelled" for `Cancelled`. The Wordlists page's pills, progress, Cancel and messages are unchanged.
- `end` is idempotent, and `end` for an unknown id does nothing, so a `finally` racing a `clearEnded` or a second end is harmless.

### 3.3 `PortScans` (D10)

Today `DetailViewModel` owns a `ScanController(viewModelScope, ...)`, so popping the Device screen cancels its port scan; the scan's progress is the engine-wide `PortScanEngine.progress`, which Quick scan all overwrites when both run. Both block Activity, so:

- New `engine/portscan/PortScans.kt` in `AppContainer`, on `appScope`: `start(host: HostEntity, profile: ScanProfile): Start` (`Started`, `HostBusy`, `TooMany`, `NoNetwork`), `stop(hostId)`, `suspend stopAndJoin(hostId)`, `state: StateFlow<Map<Long, PortScanState>>` with `data class PortScanState(val progress: ScanProgress?, val identify: IdentifyCount)`, and `active: StateFlow<Boolean>`.
- One `ScanController(appScope, env)` per running host, so the network watcher, `NO_NETWORK` and `NETWORK_CHANGED` behave as today; the scan body (`scanHost` and `scanHostOn`, unchanged in logic: claim `PORT_SCAN`, `committedBefore`, `ScanBinding.run`, identify, `markScanned`, re-identify) moves out of `DetailViewModel`.
- Cap: `TooMany` when `MAX_PORT_SCANS = 2` are running. There is no queue: the third request is refused and nothing waits, so Stop never meets a queued item. Quick scan all's per-host scans are not counted (they are one Quick scan all item). The Device screen's Scan pill is disabled while the cap is reached and the host is not one of the two, with `DetailCopy.portScansTooMany(MAX_PORT_SCANS)` as its refusal line, through `DetailBusyRules.scanRefusal` (which gains the running count), the way the brute-force sheet reads `active.size`; the snackbar refusal stays for a tap that races the cap.
- Background (Gruff 1): new `engine/common/ForegroundGate.kt`, `class ForegroundGate(private val foreground: StateFlow<Boolean>) { suspend fun await() { if (!foreground.value) foreground.first { it } } }`, built in `AppContainer` on `container.foreground`, the lifecycle signal `ToolRunner` already uses. `PortScanEngine.scan` gains `gate: suspend () -> Unit = {}`, called before each `sem.acquire()`; `PortScans` passes `foregroundGate::await`. While NetProbe is in the background no new probe starts; probes already in flight (at most the scan concurrency) finish within one connect timeout. The scan resumes when NetProbe returns. Quick scan all and radar are unchanged (they end with `HomeViewModel`). `ToolRunner` keeps its own `Control.gate` (it also resets the rate bucket); both read the same `foreground` flow.
- Screen on: `NetProbeNav` calls `KeepScreenOn(extended || toolRun || portScan)` with `portScan` from `portScans.active`, exactly as tool runs count.
- Process death: nothing is cancelled (the process is gone). `PortScans` writes each running scan to the `activityOpenScans` preference (a JSON list of `hostId`, `networkId`, `startedAt`) at start and removes it at end. At the next launch, `AppContainer` turns every leftover entry into an `INTERRUPTED` `LiveWork.Ended` (summary `ActivityCopy.INTERRUPTED`, `endedAt` null) and clears the key, mirroring `ToolRepository.markInterrupted` for tool runs.
- `PortScanEngine.scan` gains `onProgress: (ScanProgress) -> Unit = {}`, called where `_progress` is set today; `PortScans` passes `liveWork.progress(...)` and its own state. The engine-wide `progress` flow stays for compatibility; nothing reads it after this phase.
- End (Gruff 4): `PortScans` removes the host's entry from `state`, ends the `LiveWork` entry and posts the summary to `AppMessages` with the host id: `ToolCopy.finished(ActivityCopy.PORT_SCAN_NAME, summary)`, "Port scan finished: Done in 14 s, 5 open ports". The Device screen shows it through the path it already uses for tool messages; nothing per-host is kept for a later visit. Summaries: `ActivityCopy.portDone(open, seconds)`, `ActivityCopy.stoppedAfter(seconds)`, the `ScanController` failure message, `ScanController.NETWORK_CHANGED`, `DetailCopy.SCAN_BIND_FAILED`.
- `DetailViewModel` reads `portScans.state[hostId]` for its bar and calls `start` and `stop`; its Remove device path calls `portScans.stopAndJoin(hostId)` where it calls `scan.stopAndJoin()` today (3A 5.3 step 1 unchanged). `HostBusy` refusals keep the 3B messages.

### 3.4 List rules

`ActivityRules.build(live, active, ended, history, hostNames, now, clearedAt, forgottenNetworkId, removedHostIds): ActivityList` with `data class ActivityList(val running: List<ActivityItem>, val recent: List<ActivityItem>)`:

1. Running: every `LiveWork.live` entry and every `ToolRunner.active` entry, newest `startedAt` first, ties by `key`.
2. Recent: `LiveWork.ended` plus history rows whose status is not `RUNNING`, newest first by `endedAt`. A history row with status `RUNNING` is dropped: either it is live (and in Running) or it is in the gap between insert and publish.
3. Window: an item is recent when its time (its `endedAt`; for an `INTERRUPTED` item, whose `endedAt` is null, its `startedAt`) is within `RECENT_WINDOW_MS` (24 h) of `now` and after `clearedAt`, and it is among the first `RECENT_LIMIT` (50) after sorting. Sorting uses the same time.
4. History is fetched with `RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS` rows, because `recentForNetwork` orders by `startedAt DESC` and includes `RUNNING` rows that rule 2 drops.
5. Tool runs pruned by `KEEP_RUNS_PER_TOOL = 10` (3B) leave Recent too, even inside the 24 h; Activity never writes `tool_runs`.
6. Dropped everywhere: items whose `networkId` equals `forgottenNetworkId` and items whose `hostId` is in `removedHostIds`.
7. Tool history is for the current network row (`c.currentNetwork`); with no current network it is empty. Scan and import outcomes in memory show whatever network they ran on.
8. No grouping beyond the two sections: Quick scan all's per-host scans are folded into its one item, and each tool run is its own item.
9. Host names: `hostNames[hostId]` from `HostRepository.allHostsForNetwork` (3C-2, so manual-only rows resolve), the display name as Home shows it, else the IP.

### 3.5 Layout

The Activity root is a Scaffold with `LocalBarInset.current.union(WindowInsets.ime)`:

1. Pinned top `Column` (status-bar padding, `background`, never scrolls): a title row with "Activity" in `title` and, at the end, a 48 dp "Clear history" text action in `action` / `signal` (tag `activityClear`), enabled only when `recent` is not empty.
2. Pinned Running block (inside the same pinned `Column`, never scrolls), shown only when `running` is not empty: header "Running" in `groupHeader` / `textMuted`, then one row per item: title in `rowName` / `text` (3.6), progress text in `data` / `textMuted`, a 2 dp `LinearProgressIndicator` in `signal` on `surface` (determinate when `total` is known, indeterminate otherwise, and absent under reduced motion when indeterminate), and a trailing "Stop" pill (`ActionPill`, 48 dp target, description `ActivityCopy.stopDescription(title)`, tag `stop:<key>`). Row minimum 56 dp. The block is bounded: one of radar or Quick scan all (they exclude each other, Radar spec 3.7), at most two port scans, at most four tool runs and at most one URL import (3C 5.1 disables both import pills while one runs), eight rows in all.
3. Below, a `LazyColumn` (tag `activityRecent`): header "Recent", then the recent rows: title as in 3.6, line two `ActivityCopy.recentLine(summary, relative)` in `data` / `textMuted` (`RelativeTime.of`; an `INTERRUPTED` item uses its `startedAt`), 48 dp minimum, keyed by `key`. Recent rows carry no controls.
4. Empty state (no running and no recent items): the shared `EmptyState` with "Nothing running" and the line `ActivityCopy.EMPTY_LINE`.
5. The screen shows and dismisses `appMessages.current` in its snackbar, as the Device and Tools screens do, so an end message is not left to surface later on Devices.
6. Undo (D20): when `c.undo.pending` is not null, Activity's snackbar shows its text with "Undo" (`SnackbarDuration.Long`); Undo calls `c.undo.undo()` on `appScope`. A dismissal or timeout commits nothing: the Undo stays pending under the existing `UndoStore` rules and Devices' notice line still offers it. So Remove device on a Device screen opened from Activity, which pops back to Activity, shows its Undo where the Director lands.

Height on the Pixel 7 Pro at font scale 1.3 with all eight running rows: title row about 56 dp, Running block about 8 x 66 + 24 = 552 dp, bar 80 dp, leaving about 130 dp: at least one recent row (at least two at font scale 1.0). Acceptance in 9.2 test 4.

### 3.6 Titles, tap targets and Stop

| Source | Title | Progress text (running) | Tap | Stop calls |
|---|---|---|---|---|
| RADAR | "Radar scan" | `ProgressLine.of(status, elapsedS)` | Devices destination (bar rule 1) | `liveWork.stop(id)`, which calls `HomeViewModel.stopScan` |
| QUICK_SCAN_ALL | "Quick scan all" | `ActivityCopy.quickProgress(done, total, elapsedS)` | Devices destination | `liveWork.stop(id)`, which calls `stopQuickScan` |
| PORT_SCAN | `ActivityCopy.portScan(host)` | `detailProgress(progress, identifying)` | push `activity/host/{id}` | `portScans.stop(hostId)` |
| TOOL | `ActivityCopy.tool(tool, host, portLabel)` | `ToolsUiModel.bar(active)` text | running on a visible host: push `activity/host/{id}`; running on a manual-only host, or finished: the `ToolResultsSheet` on that run, over Activity | `toolRunner.stop(hostId)` |
| WORDLIST_IMPORT | `ActivityCopy.wordlistImport(host)` | `WordlistCopy.fetching(read, total)` | push `activity/wordlists` | `liveWork.stop(id)`, which calls `urlImport.cancel()` |

Pure `ActivityTargets.tap(item, visibleHostIds): ActivityTap` (`DevicesTab`, `Device(hostId)`, `Results(runId)`, `Wordlists`, `None`) and `ActivityTargets.stop(item): StopRoute?` (`Live(id)`, `PortScan(hostId)`, `Tool(hostId)`, null for finished items). A finished scan item taps to `Device(hostId)` for a port scan whose host is visible and `DevicesTab` for radar and Quick scan all; a finished import taps to `Wordlists`. The results sheet is the 3B sheet with the 3C-2 route header, hosted by `ActivityViewModel` exactly as the Tools screen hosts it. Stop is posted on `Dispatchers.Main.immediate`, so the controllers are touched on their own thread.

### 3.7 Badge (D14)

`ActivityRules.badge(running: List<ActivityItem>): Int` = `running.size`. At 0 no badge is composed. At 1 or more, a `Badge` in `signal` with `NavCopy.badgeText(n)` in `background`, `caption` style; the item's content description becomes `NavCopy.activityBadge(n)`. The count is at most eight (3.5); `badgeText` caps at "9+" anyway.

### 3.8 Clear history (D13)

"Clear history" stores `activityClearedAt = now` in the `netprobe` `SharedPreferences` and calls `liveWork.clearEnded()`. Recent items whose time (3.4 rule 3) is at or before it are hidden; running items are untouched; tool runs stay in `tool_runs` and on their device pages. The snackbar shows "History cleared" with "Undo" (`SnackbarDuration.Long`); Undo restores the previous `activityClearedAt` and `LiveWork.ended` from a snapshot taken at the tap. Leaving Activity dismisses the snackbar and ends the chance to undo, as in 3C 1.2. This snackbar is separate from the `UndoStore` one (3.5 item 6); when both are due, the `UndoStore` one shows first.

## 4. Tools

- The Tools root is the 3C-2 `ManualToolsScreen` (section 4 of the 3C spec) with two changes: the `BackToHome` row is removed (it is a root), and the title row reads "Tools" with a trailing 48 dp "Wordlists" text action (`action` / `signal`, tag `toolsWordlists`) that pushes `tools/wordlists`. The pinned block, its Run and Stop pill, validation, route, the brute-force sheet and the "Recent runs on this network" history list are unchanged, and Run and Stop stay in the pinned block.
- `ManualToolsScreen(container, onOpenWordlists)`: `onBack` goes.
- Wordlists is reachable from Tools (`tools/wordlists`), Settings (`settings/wordlists`, the 3B Tools section row), Activity (an import item) and the Device brute-force sheet (`devices/wordlists`, or `activity/wordlists` when the Device screen was opened from Activity). `Routes.wordlists(tab)` gives the route for the current destination.

## 5. Settings

- The Settings root drops the `BackToHome` row. Its pinned top row is "Settings" in `title` with the 3C-1 overflow (`settingsMenu`, "Reset to defaults") at the end. Everything else, its order (3C spec 2.3) and the Tools section with the Wordlists row, is unchanged.
- A new last section "About" holds one 56 dp row (tag `aboutRow`): "About" in `secondary` / `text`, the version line `HomeCopy.version(versionName, versionCode)` in `data` / `textMuted`, a chevron, opening the existing About dialog (title, three lines, "Copy version", "Close", tags `aboutDialog`, `aboutCopy`, `aboutClose`) moved from `HomeContent` to `ui/settings/About.kt`.
- `SettingsScreen(container, onOpenWordlists)`: `onBack` goes.

## 6. Copy table (every new or changed string)

| Name | Text |
|---|---|
| `NavCopy.DEVICES` | Devices |
| `NavCopy.ACTIVITY` | Activity |
| `NavCopy.TOOLS` | Tools |
| `NavCopy.SETTINGS` | Settings |
| `NavCopy.activityBadge(n)` | Activity, 1 running / Activity, 3 running |
| `NavCopy.badgeText(n)` | 1 ... 9 / 9+ |
| `NavCopy.backTo(tab)` | Back to Devices / Back to Activity / Back to Tools / Back to Settings (replaces "Back to Home") |
| `NavCopy.BACK_TO_DEVICE` | Back to device (Wordlists opened from a Device screen) |
| `ActivityCopy.TITLE` | Activity |
| `ActivityCopy.RUNNING` | Running |
| `ActivityCopy.RECENT` | Recent |
| `ActivityCopy.EMPTY` | Nothing running |
| `ActivityCopy.EMPTY_LINE` | Scans and tool runs show here while they run, and finished ones stay for a day |
| `ActivityCopy.CLEAR` | Clear history |
| `ActivityCopy.CLEARED` | History cleared |
| `ActivityCopy.UNDO` | Undo (same text as `HomeCopy.UNDO`) |
| `ActivityCopy.RADAR` | Radar scan |
| `ActivityCopy.QUICK` | Quick scan all |
| `ActivityCopy.PORT_SCAN_NAME` | Port scan (the tool name in `ToolCopy.finished`: "Port scan finished: Done in 14 s, 5 open ports") |
| `ActivityCopy.portScan(host)` | Port scan on Office NAS |
| `ActivityCopy.tool(tool, host, portLabel)` | Directory brute-force on Office NAS:443 / SNMP walk on 10.0.3.9:161 |
| `ActivityCopy.wordlistImport(host)` | Wordlist from raw.githubusercontent.com |
| `ActivityCopy.quickProgress(done, total, s)` | 3 of 30 devices, 42 s |
| `ActivityCopy.radarDone(found, s)` | Done in 12 s, 9 devices / Done in 12 s, 1 device |
| `ActivityCopy.quickDone(scanned, s)` | Done in 2 min 4 s, 30 devices scanned / Done in 9 s, 1 device scanned |
| `ActivityCopy.portDone(open, s)` | Done in 14 s, 5 open ports / Done in 14 s, 1 open port / Done in 14 s, no open ports |
| `ActivityCopy.stoppedAfter(s)` | Stopped after 8 s |
| `ActivityCopy.INTERRUPTED` | Interrupted when NetProbe closed |
| `ActivityCopy.duration(s)` | 8 s / 2 min 4 s (used by the lines above) |
| `ActivityCopy.recentLine(summary, relative)` | Done in 12 s, 9 devices, 2 minutes ago |
| `ActivityCopy.STOP` | Stop |
| `ActivityCopy.stopDescription(title)` | Stop port scan on Office NAS (the title's first letter lower-cased) |
| `DetailCopy.portScansTooMany(n)` | Two port scans are already running, stop one in Activity (n as a word: Two, Three, Four) |
| `ToolsCopy.TITLE` | Tools (replaces the 3C-2 title "Run a tool" on this screen) |
| `ToolsCopy.WORDLISTS` | Wordlists |
| `SettingsCopy.TITLE` | Settings |
| `SettingsCopy.ABOUT_SECTION` | About |
| `SettingsCopy.ABOUT_ROW` | About (moved from `HomeCopy.ABOUT`) |

Unchanged and reused: `HomeCopy.version`, `build`, `android`, `APP_NAME`, `CLOSE`, `COPY_VERSION`, `ProgressLine` texts, `ToolCopy.finished`, progress and summaries, `ScanController.NO_NETWORK` and `NETWORK_CHANGED`, `WordlistCopy.fetching`, `CANCELLED` and the `WordlistImport` messages, `RelativeTime.of`, the `UndoStore` pending texts. Removed: `HomeCopy.ABOUT` (moved), the "Settings" overflow item text, `ManualCopy.TITLE` (3C-2), the literal "Back to Home"; `HomeCopy.MENU_RUN_TOOL` is removed only when D16 is confirmed. `CopyTableTest` asserts the removed names are absent. If an existing duration formatter is found in the plan's code read, `ActivityCopy.duration` delegates to it; the texts above are the contract.

## 7. Data model

No schema change; the database stays at schema 4 (3C-2). Activity reads `tool_runs` through 3C-2's `ToolRepository.recentForNetwork` and `HostRepository.allHostsForNetwork`. New keys in the existing `netprobe` `SharedPreferences` file, all data rather than settings, so the 3C-1 "Reset to defaults" does not touch them: `activityClearedAt` (Long, default 0), `activityOpenScans` (String, JSON list, default empty, 3.3). `LiveWork` and `PortScans` are otherwise memory only.

## 8. Interaction with 3C-1 and 3C-2

Order: 3C-1, then 3C-2, then this phase, each after the previous device QA. Neither 3C plan changes unless the Director confirms D16; 3D adapts their output:

| Plan | What 3D changes in its output |
|---|---|
| 3C-1 | Settings' top row loses `BackToHome` and gains the title; the overflow and Reset with Undo stay. 3C-1's Settings Compose test is updated for the title row. `UrlImportController` gains the `LiveWork` calls (3.2); the Wordlists page is unchanged apart from its back row (`NavCopy.backTo`). |
| 3C-2 | Builds the Home overflow "Run a tool..." as ruled (3C D12). 3D makes the manual tools screen the Tools root (`ManualToolsScreen` loses `onBack`, gains the title row, section 4) and moves the entry point into the Tools destination: with D16 confirmed the overflow item and its Compose test (3C spec 8.2 item 5) go; without it the item stays and selects the Tools destination. If the Director confirms D16 before 3C-2 starts, 3C-2 skips the overflow wiring and its test instead. `recentForNetwork` and `allHostsForNetwork` are reused by Activity. The Settings Tools section (the Wordlists row) remains. |
| 3C spec D18 | SMB share listing is Phase 3E (D21). |
| 3B 4.3 | `AppMessages` is also shown and dismissed by Activity (3.5 item 5) and, as 3C-2 already does, Tools. |

## 9. Tests

### 9.1 JVM

1. `RoutesTest`: `tabOf` for every route in 1.1 (and null for null); `host(tab, id)` and `wordlists(tab)` per tab; every route is declared in exactly one graph.
2. `BarRulesTest`: visible on the four roots with the keyboard down; hidden on every pushed route and on any route with the keyboard up.
3. `NavMotionTest`: devices to devices/host is `SLIDE`; devices to activity, activity to tools and settings to devices are `FADE`; any pair under reduced motion is `NONE`.
4. `ActivityRulesTest`: running newest first with the tie broken by key; recent newest first, an `INTERRUPTED` item windowed and sorted by `startedAt` with null duration; a live tool run and its `RUNNING` history row give one running item; a `RUNNING` history row with no live run is dropped; with four `RUNNING` rows among 54 fetched, 50 recent remain; items 24 h plus 1 ms old are excluded and 24 h minus 1 ms included; the 51st recent item is excluded; items at or before `clearedAt` are excluded and after it included; forgotten-network and removed-host items are dropped in both sections; tool history is empty with no current network; manual-only hosts resolve to the IP; an import item carries its URL host.
5. `ActivityBadgeTest`: 0 gives no badge; 1, 3 and 8 give the count and the description; 10 gives "9+".
6. `ActivityTargetsTest`: every row of 3.6's tap and Stop columns, including a running tool on a manual-only host going to `Results`, a finished port scan on a removed host giving `None`, an import going to `Wordlists`, and finished items giving no Stop route.
7. `LiveWorkTest` (virtual time): `begin` and `end` publish at once; 1,000 `progress` calls inside 250 ms produce at most two emissions; the ticker stops with the last entry and restarts with the next `begin`; `stop` calls the lambda once and not after `end`; a second `end` is ignored; `ended` holds at most 50, newest first; `clearEnded` empties it; after `end` the stop-lambda map no longer holds the entry.
8. `PortScansTest` (fake engine, env and foreground): two scans start, a third returns `TooMany` and nothing is queued (stopping one later starts nothing); a host held by a tool returns `HostBusy` and claims nothing; with no env, `NoNetwork`; a network identity change cancels with `NETWORK_CHANGED` and releases the host; with foreground false no probe is issued after the in-flight ones, and probing resumes when it turns true; `active` is true while a scan runs (the KeepScreenOn input); the scan completes with no screen involved, ends its `LiveWork` entry `DONE` with `portDone`, removes its `state` entry and posts "Port scan finished: ..." with the host id; `stopAndJoin` returns only after the body's `finally`; per-scan progress comes from `onProgress`, so a concurrent Quick scan all does not change it; `activityOpenScans` holds the scan while it runs and is empty after; leftover entries at construction become `INTERRUPTED` ended items and the key is cleared.
9. `DetailViewModelScanLifetimeTest`: clearing the `DetailViewModel`'s `ViewModelStore` while its `PortScans` run is live leaves the scan running and the host claimed. `RemoveDeviceTest` (updated): Remove joins the scan through `portScans.stopAndJoin` before the delete.
10. `ForgetGuardTest`: `workRunningOnNetwork` is true with a `PORT_SCAN` or `TOOL` claim on a host of the shown network and false with one on another network's host; `forgetNetwork` is refused in the first case and proceeds in the second.
11. `UrlImportLiveWorkTest`: `UrlImportController.start` begins a `WORDLIST_IMPORT` entry with the URL host; progress bytes reach it; `Stored`, `Failed` and `Cancelled` end it `DONE`, `FAILED` and `STOPPED` with the existing messages; `liveWork.stop` cancels the fetch.
12. `ActivityCopyTableTest` (or `CopyTableTest` rows): every row of section 6, the plural and zero forms, `duration` at 0, 59, 60 and 124 s; `stopDescription` lower-cases the title's first letter; `portScansTooMany(PortScans.MAX_PORT_SCANS)` names the constant's value as a word, so changing the constant cannot leave "Two" behind; removed names absent; no string contains an em-dash.
13. Clear history: `activityClearedAt` written once per tap; Undo restores the previous value and the `ended` snapshot; Reset to defaults leaves both new keys alone.

### 9.2 Compose (compiled in the plan's tasks, run on the device in the QA task)

1. `NavBarTest` on `NetProbeNavHost` with fake destinations: four items in bar order with labels; tapping each selects it and restores that root's state (a fake root's `rememberSaveable` counter survives a switch away and back); Devices, Activity, open a host from Activity, Back (Activity root, bar shown), Back (Devices root); the bar is absent on host and wordlists routes. If the Director answers open item 6 with "shown", the original restored-host case returns.
2. `NavBadgeTest`: with a fake running list of 0, 2 and 8, no badge, "2" and "8", and the descriptions.
3. `NavTransitionTest` (3A's, rewritten for the new `NetProbeNavHost` signature): transitions are `None` under reduced motion for push, pop and bar switches.
4. `ActivityContentTest`: the Running block is pinned (it does not move when the recent list is scrolled), each running row has a Stop with its description, Clear is disabled with no recent items, the empty state text, a pending Undo shows in the snackbar, and at font scale 1.3 with eight running rows at least one recent row is visible.
5. `HomeContentTest` (updated): the overflow lists "Quick scan all" and "Forget this network" (and "Run a tool..." third while D16 is unconfirmed) and nothing else; Forget is disabled with `workRunningOnNetwork`.
6. `SettingsContentTest` (updated): no back row; the About row is last and opens the dialog with three lines.
7. `DetailContentTest` (updated): with two port scans running elsewhere, Scan is disabled and shows the refusal line.

### 9.3 Device QA (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, by Bugsy, mandatory)

1. The Director's scenario: open the NAS, start Directory brute-force, Back. Devices shows the Activity badge "1"; Activity shows the run with live progress; Stop from Activity ends it as "Stopped..." and the badge goes.
2. Start a Full port scan on a device, Back: the scan keeps running and shows in Activity; tapping it opens that device with the live bar; Back returns to Activity. When it ends with the device page closed, "Port scan finished: ..." shows on the visible screen.
3. With two port scans running, a third device's Scan is disabled with "Two port scans are already running, stop one in Activity".
4. Load: two Full port scans, Quick scan all and a Directory brute-force together. Record each completion time, any ANR or dropped frame warning in logcat, and check that one scanned host's open ports match a solo Full scan of that host.
5. Radar scan running, switch to Activity: "Radar scan" with the Home progress text; Stop there stops the radar on Devices; the arm resumes where it stopped on return (3A 1.9).
6. Quick scan all: "3 of 30 devices" advances; Stop works from either place.
7. Bar switches restore each root's state (Settings scroll position, Tools fields, Devices filter and search); Back from Tools goes to Devices, Back again leaves the app.
8. Background: with a Full port scan running, press Home for 30 s; the scan's done count does not advance (recorded before and after); on return it resumes; the screen stays on while it runs.
9. Process death: start a Full port scan, `adb shell am kill` NetProbe from the background, relaunch: Activity shows the scan as "Interrupted when NetProbe closed"; tool runs from before show "Interrupted, N found"; other scan outcomes are gone; Clear history state is kept.
10. Forget this network is disabled in the overflow while a port scan runs on this network.
11. Open a device from Activity, Remove device: the page pops to Activity, its snackbar offers Undo, and Undo restores the device.
12. URL import: start an import on Wordlists, switch to Activity: the import shows with "Fetching ..." and Stop cancels it ("Import cancelled").
13. The bar hides on device pages, on Wordlists and while typing in Home's search; no content jumps; reduced motion (Remove animations) gives instant switches.
14. Clear history, then Undo, restores the list; Reset to defaults leaves it cleared.
15. Wi-Fi off during a port scan: the item ends with "Network changed, scan stopped".
16. The Director approves the four icons and labels on the device before merge.

Definition of done: 9.1 green, 9.2 passing on the device, 9.3 recorded with results (NOT RUN items named with the reason), and the Director has seen Devices, Activity, Tools and Settings on the device.

## 10. Assumptions

- A1. The four `NavigationBarItem` labels fit at font scale 1.3 on a 412 dp wide phone without truncation (the longest is "Settings"); checked in 9.3 item 13.
- A2. `WindowInsets.isImeVisible` (foundation-layout, experimental API, already on the classpath) reports the keyboard on the Pixel 7 Pro with the app's edge-to-edge setup.
- A3. Radar and Quick scan all cannot run together (Radar spec 3.7), which bounds the pinned block at eight rows.
- A4. Two concurrent Full port scans beside Quick scan all and four tool runs are within what the phone and the LAN handle; 9.3 item 4 measures it, and if it fails the cap drops to one (the copy follows the constant).
- A5. Showing scan outcomes from earlier networks in Recent during the same session is wanted (they are this session's work); tool history follows the current network as the Tools screen does.

## 11. Open items for the Director

**Director rulings (2026-10-21):**
- Open item 1: the second destination is labelled **"Scans"**, not "Activity". Every "Activity" label, route name and `ActivityCopy` reference in this spec reads "Scans" / `ScansCopy` when the plan is written; the behaviour is unchanged.
- D16 confirmed: 3C-2 skips building the Home overflow "Run a tool..." item and its Compose test. "Run a tool" lives in the Tools destination only. `HomeCopy.MENU_RUN_TOOL` is not added; the overflow is "Quick scan all" and "Forget this network" (D17 without the conditional).

1. Names: "Devices", "Activity", "Tools", "Settings". Alternatives: "Scans" (your word) or "Running" for Activity, "Network" for Devices.
2. About as a Settings row opening a dialog, not a page of its own (D2).
3. Icons: radar dish, pulse line, wrench, gear (shown on the device before merge).
4. D16: may 3C-2 skip building "Run a tool..." in the Home overflow, since 3D gives manual tools the Tools destination? Answer before 3C-2 starts to save the work; otherwise the item stays and opens Tools.
5. History window: 24 h and 50 items, scan outcomes in memory only (port scans cut off by a crash show as Interrupted), or a different window.
6. The bar on device pages: hidden (this spec, to keep the port-row budget) or shown (one tap to Activity from a device, four port rows instead of five). A cheaper middle course: keep it hidden and add a trailing "N running" text to the device page's back row (no extra height) that opens Activity; not built unless you ask.
7. Review rigour for the 3D plan: light, standard or heavy (standard suggested: the port scan move touches Remove device, Forget and the busy registry).

## 12. Not as drawn, and deviations from earlier specs

| # | Earlier spec or baseline | This spec | Why |
|---|---|---|---|
| 1 | Radar spec 4.x and 3A: Home overflow "Quick scan all", "Forget this network", "Settings" (plus "About", 5abf1d2) | Two items (three while D16 is unconfirmed); Settings is a destination, About a Settings row | D2, D17 |
| 2 | 3C spec D12 (Director ruling): "Run a tool..." in the Home overflow, screen with a back row | Built by 3C-2 as ruled; 3D makes the screen the Tools root and moves the entry there, the overflow item going only on the Director's confirmation | D16, open item 4 |
| 3 | Radar spec 4.x: "Back to Home" back row | "Back to <destination>" | D18 |
| 4 | 3A 1.9: one slide for every navigation | Slide inside a destination, fade between destinations | D7 |
| 5 | Controller baseline: Detail, sheets and wordlists pushed "above the bar", bar pinned | Pushed screens cover the bar (hidden), bar pinned on roots and hidden with the keyboard | 1.4, open item 6 |
| 6 | Controller baseline: "back from a device returns to Devices" | Back returns to the destination it was opened from (Devices or Activity) | 1.3 rule 6, Material per-destination stacks |
| 7 | MVP and Radar spec: a Device port scan stops when the Device screen is left; progress from the engine-wide flow | Port scans run on `appScope` (at most two, no queue), pause in the background, keep the screen on, per-scan progress | D10 |
| 8 | Radar spec 3.7: Quick scan all exposes no progress | Devices done of total, shown in Activity only; the radar block's arc is unchanged | 3.6 |
| 9 | Controller baseline: one Activity list with Stop per item | Running items in a pinned block, recent items in the scrolling list | Stop never inside a scroll container |
| 10 | 3B 4.3: `AppMessages` shown by Home and Detail; 3A 5.3: Undo offered on Home | Also shown by Activity and Tools; Activity also offers the pending Undo | 3.5 items 5 and 6 |
| 11 | 3C spec D18: SMB share listing is Phase 3D | Phase 3E | D21 |
| 12 | Controller baseline: a navigation rail on tablet widths | Phone-only bar | D1, D8 (controller ruling on Gruff 9) |
| 13 | 3C spec 5.1: URL import results reach `AppMessages` when the Wordlists page is gone | Unchanged, and the import is also an Activity item | D9 |
