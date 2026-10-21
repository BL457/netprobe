# NetProbe Phase 3D Navigation Design (bottom bar, Devices, Activity, Tools, Settings)

Date: 2026-10-21. Author: Ada (Engineering Lead). Status: draft for Gruff's review and the Director's open items (section 11).
Baseline: branch `phase3c-1` at efe4628 (3A and 3B complete, both 3C plans committed, 3C-1 about to build). This plan builds after the 3C-2 device QA (section 8).
Related specs: `2026-10-19-radar-ui-design.md` (the "Radar spec": Home, Detail, Settings, tokens, Phase 2 navigation), `2026-10-20-phase3a-polish-design.md` (the "3A spec": motion tokens, transitions 1.9, Home overflow, Undo), `2026-10-20-phase3b-followup-tools-design.md` (the "3B spec": `ToolRunner`, `HostBusyRegistry`, Home running marker, `AppMessages`), `2026-10-21-phase3c-feedback-design.md` (the "3C spec": Settings overflow and reset, manual tools, wordlist URL import).

The Director's request, verbatim:

> we need a few extra things. We need a page set up I think. One for discovery. One for scans. One settings. One for about. and any more you think should be there. Work out what the current best practice is for how this should be arranged (tabs, or side bar, or something else etc). This way, any scans going on can be seen in the relevant page since currently, If I have a bruteforce going and I go back a page, there's no easy way to see what's currently in progress.

## Purpose and decisions

NetProbe today is one stack: Home, with Device, Settings and Wordlists pushed over it and everything else in Home's overflow. Work that outlives its screen (tool runs on `appScope`) has no home of its own once the Device screen is left. This phase gives the app four top-level destinations behind a Material 3 bottom navigation bar, and one place, Activity, that lists every scan and tool run that is running or ran recently, with live progress and Stop.

Best practice, as applied: Material 3 recommends a navigation bar for three to five top-level destinations of equal importance on compact widths, a navigation rail on medium and wider widths, and a modal drawer only for six or more destinations or rarely used ones. Tabs are for peer views inside one destination, not app-level sections. NetProbe has four destinations used constantly, so a bar. About is not a destination anyone visits twice; it is the last row of Settings. Android's navigation principles add: a fixed start destination (Devices), each bar destination keeps its own back stack (`saveState` / `restoreState`), and Back from any other destination's root returns to the start destination before leaving the app.

Standing rules carry over: Scan, Stop and Run controls and the navigation bar are never inside a scroll container; no em-dashes; no disclaimers or warnings in copy; no new dependencies (navigation-compose 2.9.0, material3 from BOM 2025.06.00 and material-icons-extended are already in); no downloads; every user-facing string is in the copy table (section 6) and pinned by `CopyTableTest`.

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | A Material 3 `NavigationBar` with four destinations: Devices, Activity, Tools, Settings; labels always shown | Controller baseline, Director's brief |
| D2 | About is the last row of Settings and opens the existing About dialog; it is not a destination and leaves the Home overflow | Controller baseline |
| D3 | Devices is the start destination and is today's Home (radar, list, filters, search); the radar Scan stays on it | Controller baseline |
| D4 | Each destination is a nested graph with its own back stack; bar taps use `popUpTo(start) { saveState = true }`, `launchSingleTop`, `restoreState`; reselecting the current destination pops to its root | Material and Android guidance |
| D5 | The bar shows on the four destination roots only; Device detail and Wordlists are pushed full screen over it and hide it | Ada (departure from "visible everywhere", 1.4 justifies) |
| D6 | The bar also hides while the keyboard is up | Ada (1.4) |
| D7 | Destination switches cross-fade at `Motion.NAV_MS`; pushes and pops inside a destination keep 3A's slide (1.9); the bar hides and shows with a vertical slide at `NAV_MS`; everything is `None` under reduced motion | Ada, 3A tokens |
| D8 | A `NavigationRail` replaces the bar when the window is at least 600 dp wide | Controller baseline; open item 3 |
| D9 | Activity lists radar scans, Quick scan all, Device port scans and tool runs in one model; running items in a pinned block, recent items in the list below | Controller baseline, Ada (pinned block, 3.5) |
| D10 | Device port scans move from `DetailViewModel`'s `viewModelScope` to an app-level `PortScans` owner on `appScope`, so leaving the Device screen no longer stops them; at most `MAX_PORT_SCANS = 2` at once | Ada (the Director's complaint applies to port scans too; 3.3) |
| D11 | A new in-memory `LiveWork` registry publishes radar, Quick scan all and port scan progress app-wide, throttled to `Motion.PROGRESS_SAMPLE_MS`; tool runs keep `ToolRunner.active` | Ada (3.2) |
| D12 | Recent: finished in the last 24 h, at most 50 items; tool runs from `tool_runs` survive a restart, scan outcomes are memory only | Controller baseline; open item 4 |
| D13 | "Clear history" hides recent items finished before a stored cut-off; nothing is deleted; Undo from the snackbar | Ada (cheap and reversible, 3.8) |
| D14 | The Activity bar item carries a badge with the running count; no badge at zero | Controller baseline |
| D15 | Tools hosts the 3C-2 "Run a tool" screen as its root, plus a Wordlists entry; Wordlists stays reachable from Settings and the brute-force sheet | Controller baseline; supersedes 3C spec D12's entry point |
| D16 | The Home overflow keeps "Quick scan all" and "Forget this network" only | Controller baseline |
| D17 | The back row names where Back goes ("Back to Devices", "Back to Activity", ...), replacing "Back to Home" | Ada (Home no longer exists by name) |
| D18 | No schema change; one new `SharedPreferences` key (`activityClearedAt`) | Ada (section 7) |

## 1. Navigation structure

### 1.1 Destinations

| Destination | Graph route | Root route | Pushed routes | Label | Icon (plain words, for the Director's approval) |
|---|---|---|---|---|---|
| Devices | `devicesTab` | `devices` | `devices/host/{id}`, `devices/wordlists` | Devices | radar dish |
| Activity | `activityTab` | `activity` | `activity/host/{id}`, `activity/wordlists` | Activity | pulse line |
| Tools | `toolsTab` | `tools` | `tools/wordlists` | Tools | wrench |
| Settings | `settingsTab` | `settings` | `settings/wordlists` | Settings | gear |

The root `NavHost` has `startDestination = "devicesTab"`. Each destination is a `navigation(route = <graph>, startDestination = <root>)` block. The same screen composable serves every route it appears under (`DetailScreen` under `devices/host/{id}` and `activity/host/{id}`); route names differ per graph because a route may appear once in a `NavHost`. Pure object `Routes` (in `ui/Nav.kt`) holds the strings and `tabOf(route: String?): Tab?` and `host(tab, id)`; `Tab` is an enum `DEVICES, ACTIVITY, TOOLS, SETTINGS` in bar order. Icons come from material-icons-extended (the implementer picks the nearest glyph for each word, outlined when unselected and filled when selected) and are shown to the Director on the device before the merge (standing rule: icons need his approval).

Results sheets, the brute-force options sheet, the vulnerability sheet, the Edit device dialog and the About dialog are not routes; they stay `ModalBottomSheet`s and dialogs owned by their screens, drawn over everything including the bar.

### 1.2 The bar

`NetProbeNav` lays out a `Box`: the `NavHost` fills it, and the bar is aligned to the bottom over it inside `AnimatedVisibility` (1.4). Bar: `NavigationBar(containerColor = background)` with a 1 dp `surface` rule along its top edge, `NavigationBarItem` per `Tab` with `alwaysShowLabel = true`, label in `NetProbeText.chip`, colours: indicator `surface`, selected icon and label `text`, unselected `textMuted`. Item tags `tab:devices`, `tab:activity`, `tab:tools`, `tab:settings`. The bar's own `windowInsets` take the navigation-bar inset, so its 80 dp sit above the gesture area. The bar is outside every scroll container by construction (it is not a child of any screen).

Selection: an item is selected when `Routes.tabOf(currentDestination.route)` is its tab (equivalently, its graph route is in `currentDestination.hierarchy`). The badge (3.7) sits on the Activity icon through `BadgedBox`.

### 1.3 Back stack rules

1. Bar tap on another destination: `nav.navigate(tab.graph) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }`. Leaving Activity with a Device screen open over it and coming back shows that Device screen again (restored), as Material specifies.
2. Bar tap on the current destination: `nav.popBackStack(tab.root, inclusive = false)`; on the root itself, nothing happens.
3. System Back or the back row on a pushed screen pops one entry within the destination.
4. System Back on the Activity, Tools or Settings root returns to Devices (the start destination stays under them, rule 1). System Back on the Devices root leaves the app, as Home does today.
5. Devices' entry is never popped while the app runs, so `HomeViewModel` (and with it the radar scan and Quick scan all) lives as long as today; the other roots' `ViewModel`s survive bar switches through `saveState`.
6. Opening a device from Activity pushes `activity/host/{id}` onto Activity's stack, so Back returns to Activity; opening one from Devices pushes `devices/host/{id}`, so Back returns to Devices.
7. Process recreation restores the stacks through `rememberNavController`'s saved state; no deep links are declared (none are needed: nothing outside the app opens a NetProbe screen).

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

### 1.6 Wide windows (D8)

When `LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP` (600), a `NavigationRail` (same items, badge, colours, tags) sits at the start edge outside the `NavHost`, the bottom bar is not composed, and `LocalBarInset` is `WindowInsets.navigationBars`. The app is portrait-locked on the Pixel 7 Pro, so this path is reached only on tablets and foldables; pure `BarRules.layout(widthDp): BarLayout` (`BAR`, `RAIL`) decides.

## 2. Devices

Devices is today's `HomeScreen` unchanged in layout: network line, radar, notices, chips, search, list, the Home running marker (3B 4.3), the notice line for `AppMessages`.

- The overflow (`homeMenu`) becomes two items, in order: "Quick scan all" (or "Stop quick scan" while it runs) and "Forget this network", with their current enabled rules. "Settings" and "About" leave it (they are a bar destination and a Settings row). The 3C-2 item "Run a tool..." leaves it (the Tools destination replaces it).
- `HomeScreen(container, onOpenHost)`: the `onOpenSettings` parameter, the `onAbout` and `onRunTool` actions and the About dialog in `HomeContent` are removed. `AboutInfo` moves to `ui/settings/About.kt` unchanged.
- `onOpenHost` pushes `devices/host/{id}`.
- `HomeViewModel` registers its radar scan and Quick scan all with `LiveWork` (3.2); nothing else changes in it.

## 3. Activity

### 3.1 The item model

```kotlin
enum class ActivitySource { RADAR, QUICK_SCAN_ALL, PORT_SCAN, TOOL }
enum class ActivityState { RUNNING, PAUSED, DONE, STOPPED, FAILED, INTERRUPTED }
data class ActivityItem(
    val key: String,              // "live:<LiveWork id>" or "tool:<runId>"; unique, stable for LazyColumn keys
    val source: ActivitySource,
    val networkId: Long?,
    val hostId: Long?,            // null for RADAR and QUICK_SCAN_ALL
    val host: String?,            // display name, or the IP for a manual-only row; null for RADAR and QUICK_SCAN_ALL
    val tool: ToolId?, val port: Int?,
    val state: ActivityState,
    val done: Int?, val total: Int?, val found: Int?,
    val startedAt: Long,
    val durationMs: Long?,        // running: elapsed (tool runs: activeMs, excluding pauses); finished: end minus start; INTERRUPTED: null
    val endedAt: Long?, val summary: String?, val runId: Long?,
)
```

Pure `ActivityRules` in `ui/activity/` builds the list from four sources, all already app-level after this phase:

| Source | Running from | Finished from | Persists across restart |
|---|---|---|---|
| Radar scan | `LiveWork.live` (kind `RADAR`), progress text from `ProgressLine.of(discoveryEngine.status)` | `LiveWork.ended` | No |
| Quick scan all | `LiveWork.live` (kind `QUICK_SCAN_ALL`), done and total in devices | `LiveWork.ended` | No |
| Device port scan | `LiveWork.live` (kind `PORT_SCAN`), fed by `PortScans` | `LiveWork.ended` | No |
| Tool run | `ToolRunner.active` (`Active.paused` gives `PAUSED`) | `ToolRepository.recentForNetwork(currentNetworkId, RECENT_LIMIT)` (3C-2), status mapped one to one | Yes |

`HostBusyRegistry` is not a source: it says which host is held, not the work's progress. It stays the busy check for starts.

### 3.2 `LiveWork` (D11)

New `activity/LiveWork.kt`, an `AppContainer` singleton:

```kotlin
class LiveWork(scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { RADAR, QUICK_SCAN_ALL, PORT_SCAN }
    enum class Outcome { DONE, STOPPED, FAILED }
    data class Live(val id: Long, val kind: Kind, val networkId: Long?, val hostId: Long?, val startedAt: Long,
                    val done: Int, val total: Int?, val found: Int)
    data class Ended(val live: Live, val outcome: Outcome, val endedAt: Long, val summary: String)
    val live: StateFlow<List<Live>>               // in start order
    val ended: StateFlow<List<Ended>>             // newest first, at most RECENT_LIMIT
    fun begin(kind: Kind, networkId: Long?, hostId: Long?, stop: () -> Unit): Long
    fun progress(id: Long, done: Int, total: Int?, found: Int)   // stores only; published by the ticker
    fun end(id: Long, outcome: Outcome, summary: String)
    fun stop(id: Long)                            // calls the registered lambda once; no-op after end
    fun clearEnded()
}
```

- `progress` writes volatile counters and never emits; one ticker publishes `live` every `Motion.PROGRESS_SAMPLE_MS` (250 ms) while any entry is live and ends itself with the last one, as `ToolRunner`'s ticker does. `begin` and `end` publish at once.
- The `stop` lambdas are held in a map beside the data, not in `Live`, so equality and `distinctUntilChanged` see data only; `end` removes the lambda, so a cleared `HomeViewModel` is never retained.
- Registration points: `HomeViewModel.discoverOn` begins `RADAR` after the network row resolves (stop: `controller.stop()`), feeds `progress(probed, total, found)` from the status it already collects, and ends in its `finally` with `DONE` when `completed`, `STOPPED` on cancellation, `FAILED` on an exception (summary `ScanController`'s message). `quickScanAllBody` begins `QUICK_SCAN_ALL` with the host list size as total, advances `done` per host, and ends in its `finally`. `PortScans` begins and ends `PORT_SCAN` (3.3).
- `end` is idempotent, and `end` for an unknown id does nothing, so a `finally` racing a `clearEnded` or a second end is harmless.

### 3.3 `PortScans` (D10)

Today `DetailViewModel` owns a `ScanController(viewModelScope, ...)`, so popping the Device screen cancels its port scan; the scan's progress is the engine-wide `PortScanEngine.progress`, which Quick scan all overwrites when both run. Both block Activity, so:

- New `engine/portscan/PortScans.kt` in `AppContainer`, on `appScope`: `start(host: HostEntity, profile: ScanProfile): Start` (`Started`, `HostBusy`, `TooMany`, `NoNetwork`), `stop(hostId)`, `suspend stopAndJoin(hostId)`, and `state: StateFlow<Map<Long, PortScanState>>` with `data class PortScanState(val progress: ScanProgress?, val identify: IdentifyCount, val message: String?)`.
- One `ScanController(appScope, env)` per running host, so the network watcher, `NO_NETWORK` and `NETWORK_CHANGED` behave as today; the scan body (`scanHost` and `scanHostOn`, unchanged in logic: claim `PORT_SCAN`, `committedBefore`, `ScanBinding.run`, identify, `markScanned`, re-identify) moves out of `DetailViewModel`.
- `TooMany` when `MAX_PORT_SCANS = 2` are running; Quick scan all's per-host scans are not counted (they are one Quick scan all item).
- `PortScanEngine.scan` gains `onProgress: (ScanProgress) -> Unit = {}`, called where `_progress` is set today; `PortScans` passes `liveWork.progress(...)` and its own state. The engine-wide `progress` flow stays for compatibility; nothing reads it after this phase.
- `DetailViewModel` reads `portScans.state[hostId]` for its bar, calls `start` and `stop`, and its Remove device path calls `portScans.stopAndJoin(hostId)` where it calls `scan.stopAndJoin()` today (3A 5.3 step 1 unchanged). Refusals show in the Device snackbar: `HostBusy` keeps the 3B messages, `TooMany` is `DetailCopy.PORT_SCANS_TOO_MANY`.
- End summaries: `ActivityCopy.portDone(open, seconds)`, `ActivityCopy.stoppedAfter(seconds)`, the `ScanController` failure message, `ScanController.NETWORK_CHANGED`, `DetailCopy.SCAN_BIND_FAILED`.

### 3.4 List rules

`ActivityRules.build(live, active, ended, history, hostNames, now, clearedAt, forgottenNetworkId, removedHostIds): ActivityList` with `data class ActivityList(val running: List<ActivityItem>, val recent: List<ActivityItem>)`:

1. Running: every `LiveWork.live` entry and every `ToolRunner.active` entry, newest `startedAt` first, ties by `key`.
2. Recent: `LiveWork.ended` plus history rows whose status is not `RUNNING`, newest `endedAt` first (an `INTERRUPTED` row, whose `finishedAt` is null, sorts by `startedAt`). A history row with status `RUNNING` is dropped: either it is live (and in Running) or it is in the gap between insert and publish.
3. Window: an item is recent when it ended within `RECENT_WINDOW_MS` (24 h) of `now`, ended after `clearedAt`, and is among the first `RECENT_LIMIT` (50) after sorting.
4. Dropped everywhere: items whose `networkId` equals `forgottenNetworkId` and items whose `hostId` is in `removedHostIds` (their rows are gone or going; a tap could only fail).
5. Tool history is for the current network row (`c.currentNetwork`); with no current network it is empty. Scan outcomes in memory show whatever network they ran on.
6. No grouping beyond the two sections: Quick scan all's per-host scans are folded into its one item, and each tool run is its own item.
7. Host names: `hostNames[hostId]` from `HostRepository.allHostsForNetwork` (3C-2, so manual-only rows resolve), display name as Home shows it, else the IP.

### 3.5 Layout

The Activity root is a Scaffold with `LocalBarInset.current.union(WindowInsets.ime)`:

1. Pinned top `Column` (status-bar padding, `background`, never scrolls): a title row with "Activity" in `title` and, at the end, a 48 dp "Clear history" text action in `action` / `signal` (tag `activityClear`), enabled only when `recent` is not empty.
2. Pinned Running block (inside the same pinned `Column`, never scrolls), shown only when `running` is not empty: header "Running" in `groupHeader` / `textMuted`, then one row per item: title in `rowName` / `text` (3.6), progress text in `data` / `textMuted`, a 2 dp `LinearProgressIndicator` in `signal` on `surface` (determinate when `total` is known, indeterminate otherwise, and absent under reduced motion when indeterminate), and a trailing "Stop" pill (`ActionPill`, 48 dp target, description "Stop <title>", tag `stop:<key>`). Row minimum 56 dp. The block is bounded: one of radar or Quick scan all (they exclude each other, Radar spec 3.7), at most two port scans and at most four tool runs, seven rows in all.
3. Below, a `LazyColumn` (tag `activityRecent`): header "Recent", then the recent rows: title as in 3.6, line two "<summary>, <relative time>" in `data` / `textMuted` (`RelativeTime.of`), 48 dp minimum, keyed by `key`. Recent rows carry no controls.
4. Empty state (no running and no recent items): the shared `EmptyState` with "Nothing running" and the line "Scans and tool runs show here while they run and for a day after".
5. The screen shows and dismisses `appMessages.current` in its snackbar, as the Device and Tools screens do, so an end message is not left to surface later on Devices.

Height on the Pixel 7 Pro at font scale 1.3 with all seven running rows: title row about 56 dp, Running block about 7 x 66 + 24 = 486 dp, bar 80 dp, leaving about 200 dp: at least two recent rows. Acceptance in 9.2 test 4.

### 3.6 Titles, tap targets and Stop

| Source | Title | Progress text (running) | Tap | Stop calls |
|---|---|---|---|---|
| RADAR | "Radar scan" | `ProgressLine.of(status, elapsedS)` | Devices destination (bar rule 1) | `liveWork.stop(id)`, which calls `HomeViewModel.stopScan` |
| QUICK_SCAN_ALL | "Quick scan all" | `ActivityCopy.quickProgress(done, total, elapsedS)` | Devices destination | `liveWork.stop(id)`, which calls `stopQuickScan` |
| PORT_SCAN | "Port scan on <host>" | `detailProgress(progress, identifying)` | push `activity/host/{id}` | `portScans.stop(hostId)` |
| TOOL | "<tool> on <host>:<port label>" | `ToolsUiModel.bar(active)` text | running on a visible host: push `activity/host/{id}`; running on a manual-only host, or finished: the `ToolResultsSheet` on that run, over Activity | `toolRunner.stop(hostId)` |

Pure `ActivityTargets.tap(item, visibleHostIds): ActivityTap` (`DevicesTab`, `Device(hostId)`, `Results(runId)`, `None`) and `ActivityTargets.stop(item): StopRoute?` (`Live(id)`, `PortScan(hostId)`, `Tool(hostId)`, null for finished items). A finished scan item taps to `Device(hostId)` for a port scan whose host is visible and `DevicesTab` for radar and Quick scan all. The results sheet is the 3B sheet with the 3C-2 route header, hosted by `ActivityViewModel` exactly as the Tools screen hosts it. Stop is posted on `Dispatchers.Main.immediate`, so the controllers are touched on their own thread.

### 3.7 Badge (D14)

`ActivityRules.badge(running: List<ActivityItem>): Int` = `running.size`. At 0 no badge is composed. At 1 or more, a `Badge` in `signal` with the count in `background`, `caption` style; the item's content description becomes "Activity, N running" (`NavCopy.activityBadge`). The count is at most seven (3.5), so no "9+" form is needed; `badgeText` caps at "9+" anyway.

### 3.8 Clear history (D13)

"Clear history" stores `activityClearedAt = now` in the `netprobe` `SharedPreferences` and calls `liveWork.clearEnded()`. Recent items ending at or before it are hidden (rule 3.4.3); running items are untouched; tool runs stay in `tool_runs` and on their device pages. The snackbar shows "History cleared" with "Undo" (`SnackbarDuration.Long`); Undo restores the previous `activityClearedAt` and `LiveWork.ended` from a snapshot taken at the tap. Leaving Activity dismisses the snackbar and ends the chance to undo, as in 3C 1.2.

## 4. Tools

- The Tools root is the 3C-2 `ManualToolsScreen` (section 4 of the 3C spec) with two changes: the `BackToHome` row is removed (it is a root), and the title row reads "Tools" with a trailing 48 dp "Wordlists" text action (`action` / `signal`, tag `toolsWordlists`) that pushes `tools/wordlists`. The pinned block, its Run and Stop pill, validation, route, the brute-force sheet and the "Recent runs on this network" history list are unchanged, and Run and Stop stay in the pinned block.
- `ManualToolsScreen(container, onOpenWordlists)`: `onBack` goes.
- Wordlists is reachable from Tools (`tools/wordlists`), Settings (`settings/wordlists`, the 3B Tools section row) and the Device brute-force sheet (`devices/wordlists`, or `activity/wordlists` when the Device screen was opened from Activity). `Routes.wordlists(tab)` gives the route for the current destination.

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
| `ActivityCopy.EMPTY_LINE` | Scans and tool runs show here while they run and for a day after |
| `ActivityCopy.CLEAR` | Clear history |
| `ActivityCopy.CLEARED` | History cleared |
| `ActivityCopy.UNDO` | Undo (same text as `HomeCopy.UNDO`) |
| `ActivityCopy.RADAR` | Radar scan |
| `ActivityCopy.QUICK` | Quick scan all |
| `ActivityCopy.portScan(host)` | Port scan on Office NAS |
| `ActivityCopy.tool(tool, host, portLabel)` | Directory brute-force on Office NAS:443 / SNMP walk on 10.0.3.9:161 |
| `ActivityCopy.quickProgress(done, total, s)` | 3 of 30 devices, 42 s |
| `ActivityCopy.radarDone(found, s)` | Done in 12 s, 9 devices / Done in 12 s, 1 device |
| `ActivityCopy.quickDone(scanned, s)` | Done in 2 min 4 s, 30 devices scanned / Done in 9 s, 1 device scanned |
| `ActivityCopy.portDone(open, s)` | Done in 14 s, 5 open ports / Done in 14 s, 1 open port / Done in 14 s, no open ports |
| `ActivityCopy.stoppedAfter(s)` | Stopped after 8 s |
| `ActivityCopy.duration(s)` | 8 s / 2 min 4 s (used by the four lines above) |
| `ActivityCopy.recentLine(summary, relative)` | Done in 12 s, 9 devices, 2 minutes ago |
| `ActivityCopy.STOP` | Stop |
| `ActivityCopy.stopDescription(title)` | Stop Port scan on Office NAS |
| `DetailCopy.PORT_SCANS_TOO_MANY` | Two port scans are already running, stop one first |
| `ToolsCopy.TITLE` | Tools (replaces the 3C-2 title "Run a tool" on this screen) |
| `ToolsCopy.WORDLISTS` | Wordlists |
| `SettingsCopy.TITLE` | Settings |
| `SettingsCopy.ABOUT_SECTION` | About |
| `SettingsCopy.ABOUT_ROW` | About (moved from `HomeCopy.ABOUT`) |

Unchanged and reused: `HomeCopy.version`, `build`, `android`, `APP_NAME`, `CLOSE`, `COPY_VERSION`, `ProgressLine` texts, `ToolCopy` progress and summaries, `ScanController.NO_NETWORK` and `NETWORK_CHANGED`, `RelativeTime.of`. Removed: `HomeCopy.ABOUT` (moved), the "Settings" overflow item text, `HomeCopy.MENU_RUN_TOOL` and `ManualCopy.TITLE` (3C-2), the literal "Back to Home"; `CopyTableTest` asserts none of them exists. If an existing duration formatter is found in the plan's code read, `ActivityCopy.duration` delegates to it; the texts above are the contract.

## 7. Data model

No schema change; the database stays at schema 4 (3C-2). Activity reads `tool_runs` through 3C-2's `ToolRepository.recentForNetwork` and `HostRepository.allHostsForNetwork`. One new `SharedPreferences` key in the existing `netprobe` file: `activityClearedAt` (Long, default 0); it is data, not a setting, so the 3C-1 "Reset to defaults" does not touch it. `LiveWork` and `PortScans` are memory only.

## 8. Interaction with 3C-1 and 3C-2

Order: 3C-1, then 3C-2, then this phase, each after the previous device QA. Neither 3C plan changes; 3D adapts their output:

| Plan | What 3D changes in its output |
|---|---|
| 3C-1 | Settings' top row loses `BackToHome` and gains the title; the overflow and Reset with Undo stay. 3C-1's Settings Compose test is updated for the title row. Wordlists' URL import is untouched; its back row gets `NavCopy.backTo`. |
| 3C-2 | The Home overflow loses "Run a tool..." (`menuRunTool`); its Compose test (3C spec 8.2 item 5) becomes "Quick scan all", "Forget this network" and nothing else. The `tools` route becomes the Tools root inside `toolsTab`; `ManualToolsScreen` loses `onBack` and gains the title row (section 4). `recentForNetwork` and `allHostsForNetwork` are reused by Activity. The Settings Tools section (the Wordlists row) remains. |
| 3C spec D12 | Superseded: manual tools are reached from the Tools destination, not the Home overflow. |
| 3B 4.3 | `AppMessages` is also shown and dismissed by Activity (3.5 item 5) and, as 3C-2 already does, Tools. |

## 9. Tests

### 9.1 JVM

1. `RoutesTest`: `tabOf` for every route in 1.1 (and null for null); `host(tab, id)` and `wordlists(tab)` per tab; every route is declared in exactly one graph.
2. `BarRulesTest`: visible on the four roots with the keyboard down; hidden on every pushed route and on any route with the keyboard up; `layout(599) == BAR`, `layout(600) == RAIL`.
3. `NavMotionTest`: devices to devices/host is `SLIDE`; devices to activity, activity/host to tools and settings/wordlists to devices are `FADE`; any pair under reduced motion is `NONE`.
4. `ActivityRulesTest`: running newest first with the tie broken by key; recent newest first, an `INTERRUPTED` row sorted by `startedAt` with null duration; a live tool run and its `RUNNING` history row give one running item; a `RUNNING` history row with no live run is dropped; items ended 24 h plus 1 ms ago are excluded and 24 h minus 1 ms included; the 51st recent item is excluded; items ending at or before `clearedAt` are excluded and after it included; forgotten-network and removed-host items are dropped in both sections; tool history is empty with no current network; manual-only hosts resolve to the IP.
5. `ActivityBadgeTest`: 0 gives no badge; 1, 3 and 7 give the count and the description; 10 gives "9+".
6. `ActivityTargetsTest`: every row of 3.6's tap and Stop columns, including a running tool on a manual-only host going to `Results`, a finished port scan on a removed host giving `None`, and finished items giving no Stop route.
7. `LiveWorkTest` (virtual time): `begin` and `end` publish at once; 1,000 `progress` calls inside 250 ms produce at most two emissions; the ticker stops with the last entry and restarts with the next `begin`; `stop` calls the lambda once and not after `end`; a second `end` is ignored; `ended` holds at most 50, newest first; `clearEnded` empties it; after `end` the stop-lambda map no longer holds the entry.
8. `PortScansTest` (fake engine and env): two scans start, a third returns `TooMany`; a host held by a tool returns `HostBusy` and claims nothing; with no env, `NoNetwork`; a network identity change cancels with `NETWORK_CHANGED` and releases the host; no screen or `ViewModel` is involved, and the scan completes and ends its `LiveWork` entry `DONE` with `portDone`; `stopAndJoin` returns only after the body's `finally`; per-scan progress comes from `onProgress`, so a concurrent Quick scan all does not change it.
9. `ActivityCopyTableTest` (or `CopyTableTest` rows): every row of section 6, the plural and zero forms, `duration` at 0, 59, 60 and 124 s; removed names absent; no string contains an em-dash.
10. Clear history: `activityClearedAt` written once per tap; Undo restores the previous value and the `ended` snapshot; Reset to defaults leaves the key alone.

### 9.2 Compose (compiled in the plan's tasks, run on the device in the QA task)

1. `NavBarTest` on `NetProbeNavHost` with fake destinations: four items in bar order with labels; tapping each selects it; Devices to host to Activity to Devices shows the host screen again (restored); reselecting Devices from its host screen pops to the root; Back on the Activity root shows Devices; the bar is absent on the host and wordlists routes.
2. `NavBadgeTest`: with a fake running list of 0, 2 and 7, no badge, "2" and "7", and the descriptions.
3. `NavTransitionTest` (3A's, extended): transitions are `None` under reduced motion for push, pop and bar switches.
4. `ActivityContentTest`: Running block pinned (it does not move when the recent list is scrolled), each running row has a Stop with its description, Clear disabled with no recent items, empty state text, and at font scale 1.3 with seven running rows at least two recent rows visible.
5. `HomeContentTest` (updated): the overflow lists "Quick scan all" and "Forget this network" and nothing else.
6. `SettingsContentTest` (updated): no back row; the About row is last and opens the dialog with three lines.

### 9.3 Device QA (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, by Bugsy, mandatory)

1. The Director's scenario: open the NAS, start Directory brute-force, Back. Devices shows the Activity badge "1"; Activity shows the run with live progress; Stop from Activity ends it as "Stopped..." and the badge goes.
2. Start a Full port scan on a device, Back: the scan keeps running and shows in Activity; tapping it opens that device with the live bar; Back returns to Activity.
3. A third port scan is refused with "Two port scans are already running, stop one first".
4. Radar scan running, switch to Activity: "Radar scan" with the Home progress text; Stop there stops the radar on Devices; the arm resumes where it stopped on return (3A 1.9).
5. Quick scan all: "3 of 30 devices" advances; Stop works from either place.
6. Bar switches restore each destination's state (Settings scroll position, Tools fields, Devices filter and search); Back from Tools goes to Devices, Back again leaves the app.
7. The bar hides on device pages, on Wordlists and while typing in Home's search; no content jumps; reduced motion (Remove animations) gives instant switches.
8. Kill and relaunch: tool runs from before are in Recent (an interrupted one reads "Interrupted, N found"), scan outcomes are gone, Clear history state is kept.
9. Clear history, then Undo, restores the list; Reset to defaults leaves it cleared.
10. Wi-Fi off during a port scan: the item ends with "Network changed, scan stopped".
11. The Director approves the four icons and labels on the device before merge.

Definition of done: 9.1 green, 9.2 passing on the device, 9.3 recorded with results (NOT RUN items named with the reason), and the Director has seen Devices, Activity, Tools and Settings on the device.

## 10. Assumptions

- A1. The four `NavigationBarItem` labels fit at font scale 1.3 on a 412 dp wide phone without truncation (the longest is "Settings"); checked in 9.3 item 7.
- A2. `WindowInsets.isImeVisible` (foundation-layout, experimental API, already on the classpath) reports the keyboard on the Pixel 7 Pro with the app's edge-to-edge setup.
- A3. Running radar and Quick scan all together cannot happen (Radar spec 3.7 disables each while the other runs), which bounds the pinned block at seven rows.
- A4. Two concurrent Device port scans at the default concurrency are within what the phone and the LAN handle; Quick scan all already runs one host after another beside a Device scan today.
- A5. Showing scan outcomes from earlier networks in Recent during the same session is wanted (they are this session's work); tool history follows the current network as the Tools screen does.

## 11. Open items for the Director

1. Names: "Devices", "Activity", "Tools", "Settings". Alternatives: "Network" for Devices, "Running" or "Jobs" for Activity.
2. Icons: radar dish, pulse line, wrench, gear (shown on the device before merge).
3. Tablet rail (D8): keep it (small, tested by a pure rule), or drop it while the app is portrait-locked on a phone.
4. History window: 24 h and 50 items, or a different window; and whether Recent should keep scan outcomes across a restart (it would need a table, which this spec avoids).
5. The bar on device pages: hidden (this spec, to keep the port-row budget) or shown (one tap to Activity from a device, four port rows instead of five).
6. Phase number: the 3C spec (D18) put SMB share listing in "Phase 3D". This spec takes 3D for navigation; SMB share listing becomes Phase 3E unless you say otherwise.
7. Review rigour for the 3D plan: light, standard or heavy (standard suggested: the port scan move touches Remove device and the busy registry).

## 12. Not as drawn, and deviations from earlier specs

| # | Earlier spec or baseline | This spec | Why |
|---|---|---|---|
| 1 | Radar spec 4.x and 3A: Home overflow "Quick scan all", "Forget this network", "Settings" (plus "About", 5abf1d2) | Two items; Settings is a destination, About a Settings row | D2, D16 |
| 2 | 3C spec D12 and 4.1: "Run a tool..." in the Home overflow, screen with a back row | The Tools destination root, no back row, title "Tools" | D15 |
| 3 | Radar spec 4.x: "Back to Home" back row | "Back to <destination>" | D17 |
| 4 | 3A 1.9: one slide for every navigation | Slide inside a destination, fade between destinations | D7 |
| 5 | Controller baseline: Detail, sheets and wordlists pushed "above the bar", bar pinned | Pushed screens cover the bar (hidden), bar pinned on roots and hidden with the keyboard | 1.4, open item 5 |
| 6 | Controller baseline: "back from a device returns to Devices" | Back returns to the destination it was opened from (Devices or Activity) | 1.3 rule 6, Material per-destination stacks |
| 7 | MVP and Radar spec: a Device port scan stops when the Device screen is left; progress from the engine-wide flow | Port scans run on `appScope` (at most two), per-scan progress | D10; without it Activity could not show them |
| 8 | Radar spec 3.7: Quick scan all exposes no progress | Devices done of total, shown in Activity only; the radar block's arc is unchanged | 3.6 |
| 9 | Controller baseline: one Activity list with Stop per item | Running items in a pinned block, recent items in the scrolling list | Stop never inside a scroll container |
| 10 | 3B 4.3: `AppMessages` shown by Home and Detail | Also by Activity and Tools | 3.5 item 5 |
| 11 | 3C spec D18: SMB share listing is Phase 3D | Proposed 3E | Open item 6 |
