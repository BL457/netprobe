# NetProbe Radar UI Design (visual pass over the MVP screens)

Date: 2026-10-19. Author: Ada (Engineering Lead). Status: draft for the Director's review.
Baseline: master 9dc24d5.
Agreed mockups: `.superpowers/brainstorm/52057-1792445317/content/` (`visual-direction.html` direction A, `radar-layout.html`, `radar-states.html`). Where this document and a mockup disagree on a number, the mockup is the source and this document is wrong.
Related specs: `2026-10-19-netprobe-mvp-design.md` section 5.7 (current UI), `2026-10-19-extended-discovery-design.md` section 8 (Home grouping, progress text, Settings section).

## 1. Purpose

NetProbe works but looks like stock Material 3 with dynamic colour. This pass gives it one memorable element, the radar, and makes everything else quiet so the radar and the data carry the screen.

- One signature element: the scan control is a radar. It is a small ring at rest, a sweeping disc while scanning, and a ring again when done. On first launch it is a large disc with Scan in the middle.
- Everything else is dark slate, thin lines and tabular figures. No icons on rows (device type is a 4 dp coloured edge), no filled cards, no shadows.
- Signal teal means "live" and nothing else: the radar, open ports, the gateway edge, the primary action word. A teal element that is not live or primary is a bug.
- The Director's standing rule is a hard requirement of this design: Scan, Stop and the Detail scan bar are pinned outside any scrolling content. The radar block is part of the pinned Home header; the scan bar is part of the pinned Detail block. Only the device list and the port list scroll.
- UI only. No engine, data, repository or scan-logic change (section 9).

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | Direction A "Radar", dark theme only for this pass | Director |
| D2 | Idle: 56 dp ring with "Scan" inside, last result text beside it | Director |
| D3 | Scanning: ring grows to a 172 dp radar disc, Stop pill below, progress line below that | Director |
| D4 | Done: disc settles back to the ring with a summary text | Director |
| D5 | First launch, no results: open on the large disc with Scan in the middle; becomes the ring after the first scan | Director |
| D6 | Only the Stop pill stops a scan; tapping the disc does nothing | Director |
| D7 | Device screen carried as drawn in `radar-layout.html` | Director |
| D8 | Device type shown as a coloured edge, not a row icon | Director (direction A) |
| D9 | Material dynamic colour replaced by fixed tokens; light mode out of scope | Director (dark only), Ada (tokens) |
| D10 | Font: IBM Plex Sans (Regular, Medium, SemiBold), bundled in res/font; bundling approved by the Director 2026-10-19 | Roboto would need no download but loses the tabular-figure character of the mockups |
| D11 | Animation stops when the app is not resumed | Ada recommendation, open item 11.2 |
| D12 | Filter chips and search collapse while the large disc is showing (scanning, first launch) and return when it settles | Ada, follows `radar-states.html` state 2, open item 11.4 |
| D13 | Group headers use the mockup wording ("Home subnet 10.0.3.0/24") with a right-aligned count, superseding the header wording in extended-discovery spec 8.1 | Ada, open item 11.3 |
| D14 | Reduced motion is read from the system animator duration scale (0 means reduced) | Ada, see 3.7 |
| D15 | Router-typed hosts that are not the gateway also use the teal edge | Ada, open item 11.5 |

## 2. Design tokens

### 2.1 Palette

Dark theme only. One `NetProbeColors` object holds the tokens, and a Material `darkColorScheme` is built from it so stock components (dialogs, sheets, text fields, switches) inherit them.

| Token | Hex | Use |
|-------|-----|-----|
| `background` | #16202B | Screen background; text colour on teal and yellow fills |
| `surface` | #233447 | Selected chip fill, ring track, group-header rules, active scan-profile segment, sheet background |
| `rowDivider` | #1D2A38 | Port-row hairlines (as drawn) |
| `borderSubtle` | #2A3E54 | Radar disc outline and inner rings |
| `border` | #3B5368 | Chip, segmented-control and note outlines; unknown-type edge |
| `text` | #E6EDF3 | Titles, names, active labels |
| `textSecondary` | #B9C7D4 | Inactive segment labels, chip labels, identity line, progress prose |
| `textMuted` | #8FA3B5 | Data lines (IP, MAC), network line, group headers, "Back to Home", hints |
| `signal` | #35D4C8 | Live only: arm, blips, ring, open-port count, gateway edge, primary action word, links, Scan pill fill |
| `mark` | #FFD56B | Status marks: list chip and active status segment |
| `error` | #FF8A8A | Error text and failed states (text only, never an edge) |

Type edge colours (4 dp wide, 34 dp tall, 2 dp radius). A row whose type is unknown uses `border`.

| Device type | Hex | Note |
|-------------|-----|------|
| ROUTER | #35D4C8 | Same as `signal`. A row with `isGateway` always uses this, whatever its type |
| NAS | #7FA7FF | As drawn |
| MEDIA | #C98BFF | As drawn |
| IOT | #FFB86B | As drawn |
| PHONE | #8FA3B5 | As drawn; the "This phone" row also uses it |
| COMPUTER | #9BE07A | Chosen: green |
| SERVER | #F2A0D6 | Chosen: pink |
| CAMERA | #FF8A8A | Chosen: coral |
| PRINTER | #D6C7A1 | Chosen: sand |
| UNKNOWN | #3B5368 | As drawn |

The four chosen values are from the same pastel family as the drawn ones (similar lightness, so none outshouts another) and are distinct from teal, blue and violet. A non-gateway ROUTER (an access point, for example) also gets teal (D15). If the Director wants teal on edges for the gateway alone, use #5FB8B0 for non-gateway routers: a one-line change in `edgeColor`.

Stale hosts (not seen in the latest run) keep the existing 0.5 alpha on the whole row, edge included.

### 2.2 Typography

IBM Plex Sans (400, 500, 600) bundled into `res/font` (SIL Open Font License). The Director approved this download on 2026-10-19; it is the only file download permitted for this work. Roboto remains the fallback if a font fails to load.

IPs, MACs, ports, counts and times use tabular figures: `fontFeatureSettings = "tnum"` on the `data`, `port`, `count` and `radarCount` styles. The Material `Typography` is fully overridden so no stock style leaks through.

| Style | Size / weight | Used for |
|-------|---------------|----------|
| `title` | 20 sp / 600 | Detail device name |
| `rowName` | 14 sp / 500 | Row device name, network name on Home |
| `summary` | 15 sp / 500 | Radar headline ("13 devices"), as drawn |
| `secondary` | 13 sp / 400 | Identity line, summary detail, progress prose, note, port service name, links |
| `data` | 12 sp / 400, tnum | IP, MAC, row data line, network line, port version, progress line, open-port count |
| `chip` | 12 sp / 400 | Filter chips, segmented-control labels |
| `action` | 12 sp / 500 | Port action word, Stop pill |
| `scanPill` | 13 sp / 600 | Detail Scan pill |
| `port` | 15 sp / 600, tnum | Port number column, CVE id |
| `caption` | 11 sp / 400 | Group header, section title, mark chip |
| `radarCount` | 30 sp / 600, tnum | Found count in the disc centre |
| `radarLabel` | 11 sp / 400 | "found" under the count |
| `ringLabel` | 12 sp / 600 | "Scan" in the ring |
| `heroButton` | 16 sp / 600 | "Scan" in the first-launch disc |

Letter spacing is 0 except group headers at 0.02 em (as drawn).

### 2.3 Spacing and radii

Spacing tokens in dp (the values the mockups use; no others): 2, 4, 6, 8, 10, 12, 14, 18, 26.

- Screen gutter 18 dp. A row has 14 dp left padding so the 4 dp edge sits at 14 dp and text starts at 30 dp.
- Row: 4 dp edge, 12 dp gap, content, auto-width right column; vertical padding 9 dp (row height about 52 dp, above the 48 dp target).
- Header: top 18 dp, bottom 10 dp. Radar block top margin 12 dp. Group header padding 10 / 18 / 4 dp with a 1 dp `surface` rule above it and 10 dp margin above the rule.

Corner radii: ring, disc, Stop pill, Scan pill, filter chips are fully round. Surfaces (note box, segmented controls, sheets, text fields) are 8 dp. Mark chips are 4 dp. The type edge is 2 dp.

Strokes: ring 3 dp; disc outline 2 dp; inner disc rings 1 dp; chip and control outlines 1 dp; Stop pill outline 1.5 dp.

No shadows and no tonal elevation: separation is by hairline and surface tone only.

### 2.4 Tokens in code

```kotlin
object NetProbeColors { val background = Color(0xFF16202B) /* ...all tokens above */ }
fun DeviceType.edgeColor(isGateway: Boolean = false): Color
@Composable fun NetProbeTheme(content: @Composable () -> Unit)   // `dark` parameter removed
```

### 2.5 Theme replacement and scope of theming

`ui/theme/Theme.kt` drops the `dynamicDarkColorScheme`, `dynamicLightColorScheme` and `lightColorScheme` branches and the `dark` parameter. `NetProbeTheme` always supplies the Radar dark scheme, `NetProbeTypography`, the shapes, and `CompositionLocal`s for the extra tokens. Material dynamic colour is replaced by these tokens. Light mode is explicitly out of scope: a device set to the light theme still shows the dark Radar look. System bars use `background` with light icons (the app is already edge-to-edge; verify only).

## 3. The radar component

`ui/home/RadarBlock.kt` is one composable that owns all three states and the first-launch variant, so the size change is one continuous animation, not a screen swap.

```kotlin
enum class RadarMode { HERO, SCANNING, SETTLED }   // HERO = first launch: no hosts, no completed scan, not scanning
data class RadarSummary(val headline: String, val detail: String)
@Composable fun RadarBlock(mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary,
                           canScan: Boolean, onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier)
```

### 3.1 Mode derivation (pure, JVM-testable)

`RadarModes.of(scanning: Boolean, hostCount: Int, lastScanAt: Long?)`:

- SCANNING when a scan is running.
- HERO when not scanning, the current network has no hosts (counted before filter and query), and there is no completed scan.
- SETTLED otherwise, including a finished scan that found nothing (ring with "No devices found", so the Director is not thrown back to a first-launch screen).

`lastScanAt` is `max(lastDiscoveryRun)` over the network's hosts, which is already stored; no schema change. A search or filter with no matches is not a first launch.

### 3.2 Idle (SETTLED)

- Row: 56 dp ring, 14 dp gap, text column. Ring: 3 dp `signal` stroke, "Scan" in `ringLabel` / `signal` centred. This is a full teal ring because tapping it does something (`radar-states.html` state 1).
- Text: headline `summary` / `text` ("13 devices"), detail `secondary` / `textSecondary` ("Last scan 2 minutes ago"). The content description is one string: "Scan, 13 devices, last scan 2 minutes ago".
- Tapping the ring starts a scan. The touch target is padded to 64 dp square.
- Disabled (no network): ring stroke and label in `border`, no ripple, description "Scan, unavailable, not connected".

### 3.3 Scanning

- Disc 172 dp, centred in the pinned header, 2 dp `borderSubtle` outline, radial background (`#1B2836` to 30 percent, `background` from 70 percent). Inner rings at 28 dp and 58 dp inset, 1 dp `borderSubtle`.
- Arm: a sweep gradient, `signal` at alpha .55 at 0 deg, alpha .08 at 70 deg, transparent from 110 deg to 360 deg, clipped to the disc, one turn per 2400 ms, linear.
- Blips: one per newly found host. A 6 dp `signal` dot with an 8 dp glow (drawn radial alpha, not a shadow). Position is pseudo-random but stable: `blipOffset(index: Int, radiusPx: Float): Offset` hashes the index (a fixed 32-bit integer hash) to an angle in [0, 360) and a radius fraction in [0.22, 0.88] of the disc radius. Blips fade in over 300 ms and stay until the scan ends. At most 64 are drawn; the centre count is exact regardless.
- Centre: found count in `radarCount` / `text`, "found" in `radarLabel` / `textMuted` below it.
- Below the disc, 8 dp gap: Stop pill, "Stop" in `action` / `signal`, 1.5 dp `signal` outline, fully round, 4 dp / 14 dp visual padding, 48 dp minimum touch height. Then, 6 dp gap: the progress line in `data` / `textMuted`, centred: "probing 171 of 254, 7 s".
- With extended discovery on (extended-discovery spec 8.2) the progress line shows the active phase instead, with elapsed seconds appended to every variant: "probing 10.0.4.0/24, 2 of 4 ranges, 31 s" or "probing 10.0.0.0/16 for gateways, 112 of 256, 9 s". The feature being absent is not an error: the range text is simply never produced.
- Tapping the disc does nothing: the disc has no click handler and no ripple. Only Stop is actionable and calls `vm.stopScan()` exactly as the current Stop button does.
- Quick scan all (overflow menu) is not a radar scan. While it runs the block stays SETTLED, the ring shows an indeterminate teal arc with "Stop" inside, and the detail text reads "Quick scanning 4 of 13". The same control starts and stops; quick scan never grows the disc. (Its progress count is read from existing state; if no count is available the detail reads "Quick scanning".)

### 3.4 Done (settling)

When `scanning` goes from true to false: the disc shrinks to the ring over 360 ms, the count cross-fades to "Scan" over 160 ms, blips fade out over 200 ms, and the text column fades in with the headline "13 devices" and the detail "Scanned in 12 s, 1 on another subnet". The summary detail stays while the Home screen remains composed; after leaving and returning, or after process death, it reads the idle text ("Last scan 2 minutes ago") because the duration is not persisted. The summary is held in `rememberSaveable` so rotation keeps it.

`ScanSummaryText.of` (pure): "Scanned in N s", then ", K on another subnet" only when K > 0, then ", M errors" only when the existing error count is above 0. Zero devices: headline "No devices found", detail "Scanned in 12 s".

### 3.5 First launch (HERO)

- The header shows the network line, then a 172 dp disc with a filled `signal` circle of 84 dp in the middle, "Scan" in `heroButton` / `background`. Three static 6 dp `textMuted` dots sit at fixed offsets to suggest a radar at rest. The arm does not turn.
- Under the disc, centred: "Nothing scanned yet" (`summary`, `text`) and "Tap to find what is on 10.0.3.0/24" (`secondary`, `textMuted`). With no network: "No network" and "Connect to Wi-Fi or Ethernet to scan", the circle disabled (`border` fill, `textMuted` label).
- No filter chips or search in HERO (D12). The list area shows the muted placeholder "Devices appear here as they are found".
- On tap the disc keeps its size, the button fades to the count, the arm starts and the Stop pill appears. When the scan finishes it settles to the 56 dp ring and HERO is not shown again for that network.
- Only the 84 dp Scan circle is actionable. The disc around it does nothing (D6).

### 3.6 Animation approach and timing

One `updateTransition(targetState = mode)` drives everything, so the states cannot drift apart.

| Value | Spec |
|-------|------|
| Disc diameter | `animateDp`: SETTLED 56 dp, SCANNING and HERO 172 dp |
| Expand and settle | 360 ms each, `FastOutSlowInEasing` |
| Ring stroke | 3 dp at 56 dp, 2 dp at 172 dp |
| Scan label to count | cross-fade 160 ms |
| Stop pill and progress line | alpha, 160 ms, delayed 120 ms on expand |
| Blip fade in / out | 300 ms / 200 ms |
| Arm | `rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)))` |
| Row arrival | 600 ms, `CubicBezierEasing(0f, 0f, 0.58f, 1f)`, alpha 0 to 1 and 6 dp rise |

- `animateDpAsState` is an acceptable substitute for the transition if the plan prefers it; the output is the same. A shared-element transition (`SharedTransitionLayout`) is not used: it is experimental at the project's Compose BOM 2025.06.00 and nothing here moves between destinations.
- Layout: a custom `Layout` interpolates the text position between "right of the ring" and "below the disc" by `fraction = (diameter - 56) / (172 - 56)`, so the text never jumps. The header height follows the measured block, so the list below slides with it.
- The arm angle is read only inside the `Canvas` draw lambda, so rotation never triggers recomposition. The infinite transition exists only in the composition branch where the mode is SCANNING and the lifecycle is RESUMED, so it is disposed when scanning ends or the app is backgrounded (D11).
- Resumed check: `LocalLifecycleOwner.current.lifecycle.currentStateAsState()` at least RESUMED.
- Progress numbers feed the UI through a flow sampled every 250 ms in the UI layer (no engine change), so a burst of discoveries cannot recompose the header on every host.
- Row arrival: while SCANNING, rows whose id was not present when the scan began fade and rise (`Modifier.animateItem(fadeInSpec = ...)` plus a one-shot `graphicsLayer` translation keyed on the id). Initial load, filter changes and returning to the screen do not animate. The set of already-present ids is captured when `scanning` flips to true.

### 3.7 Reduced motion

Compose has no first-class reduced-motion flag, and `LocalAccessibilityManager` only exposes timeout recommendations, so it cannot answer this question. The rule is explicit:

```kotlin
@Composable fun rememberReducedMotion(): Boolean   // Settings.Global.ANIMATOR_DURATION_SCALE == 0f
```

Android's "Remove animations" setting and a developer animator scale of "off" both set the scale to 0. When true: the arm is drawn static at 40 degrees, the size change and row arrival are instant (duration 0), blips appear without fading, and the count still updates. Nothing is lost informationally. Other animator scales (for example 0.5x) are honoured automatically by Compose tweens and need no special case.

## 4. Home screen

Maps to `HomeScreen.kt` and `HomeComponents.kt`. The `Scaffold` topBar remains the pinned region. The existing test tags `scanButton`, `homeMenu`, `search`, `filterRow` and `deviceList` are kept; `scanButton` now tags the ring, the hero circle and the Stop pill (one tag, whichever is the live control).

### 4.1 Pinned header, top to bottom

1. Network line (`NetworkPanel` restyled): "Home" in `rowName` / `text`, then "10.0.3.0/24  gateway 10.0.3.1" in `data` / `textMuted`, one line, ellipsised. The name rule is unchanged (SSID, else "Wi-Fi" or "Ethernet"; the "tap to show the name, needs location" hint stays and is the one tappable-looking text here, in `signal`). The DNS suffix of `networkDetailLine` is dropped from the Home line to keep it to one line; the function itself and its test are unchanged. The overflow menu icon (`homeMenu`) sits at the right end, 48 dp target, `textSecondary`.
2. `RadarBlock` (section 3).
3. Filter chips, hidden while the large disc is showing (D12): All, Interesting, Investigated, Ignored, Unmarked; horizontally scrollable; 8 dp gap. Chip: fully round, 1 dp `border` outline, `chip` text in `textSecondary`, 32 dp visual height with a 48 dp touch target. Selected: `surface` fill and outline, `text`.
4. Search field, hidden with the chips: `OutlinedTextField`, 8 dp radius, 1 dp `border`, focused outline and cursor `signal`, placeholder in `textMuted`, search icon `textMuted`. The query lives in the view model, so hiding the field loses nothing.

None of this scrolls. The device list fills the rest of the screen.

### 4.2 Grouped list

- Groups by subnet, local group first, then other groups by network address ascending, as in extended-discovery spec 8.1; this spec only restyles them. The group key is `HostRow.subnet` when that field lands; until then it is derived from `HostRow.otherSubnet` (null means local) as today.
- Sticky headers (`stickyHeader`, with the local `@OptIn(ExperimentalFoundationApi::class)`). Header: `caption` / `textMuted`, opaque `background` so rows scroll cleanly beneath it, 1 dp `surface` rule above. Text "Home subnet 10.0.3.0/24" for the local group and "Other subnet 10.0.4.0/24" for others, with the post-filter device count right-aligned in the same style (D13). Exposed as a heading. A single group still shows its header, because it carries the subnet.
- Within a group: gateway first, then by IP (unchanged `HomeFilter`).

### 4.3 Row anatomy

Columns: 4 dp type edge, content, right column; 12 dp gap. The whole row is the click target (`onOpenHost`).

- Edge: `edgeColor(type, isGateway)`. Gateway row: teal. "This phone" row: #8FA3B5. Edge height 34 dp, vertically centred.
- Line 1: display name in `rowName` / `text`, one line, ellipsised; if status is not Unmarked, a mark chip: `caption`, `background` text on `mark`, 4 dp radius, 1 dp / 6 dp padding, 6 dp left margin; text is the status label.
- Line 2: `data` / `textMuted`: "10.0.3.59  Synology DS220+" (IP, two spaces, identity). Rule: identity when known (not blank and not the "Unknown device" fallback), else the MAC, else the IP alone. When the name equals the IP, line 2 is the identity or MAC only. `rowDataLine` replaces `rowSecondaryLine`: same logic, two spaces instead of the middle dot, and the "other subnet" suffix is gone (the group header carries it).
- Right column: open-port count in `data` / `signal`, tabular, right-aligned: "9 open". Nothing when zero. The old "Gateway" text label and the status chip are removed: the gateway is the teal edge and status is the mark chip.
- Stale: whole row at alpha 0.5 (unchanged `STALE_ALPHA`) plus a semantics suffix "not seen in the last scan". This is the one intentional contrast reduction.
- No divider between rows; rhythm comes from the 9 dp padding. Group headers carry the only rule.

### 4.4 Empty states and messages

Empty states: `secondary` / `textMuted`, centred in the list area under the header (strings in section 8). Transient messages (network changed, large-network clamp, location prompts, quick-scan failures) keep their source (`HomeUiState.message`) and strings, and are shown as a one-line `data` / `textMuted` notice directly under the radar block with a 48 dp dismiss target, so the explanation sits beside the thing it explains. The clamp and cap notes stay until dismissed or the next scan starts. The Snackbar host stays for "Scan failed" messages.

## 5. Device screen

Maps to `DetailScreen.kt` and `DetailComponents.kt`, as drawn in `radar-layout.html`. Structure is unchanged: a pinned topBar `Column` with identity, status, note and scan bar; the ports `LazyColumn` (`portList`) is the only scrolling part.

### 5.1 Pinned block, top to bottom

1. "Back to Home": `data` / `textMuted` with a leading chevron, replacing the `TopAppBar` navigation icon. 48 dp tall touch row. Gesture back is unchanged.
2. Name: `title` / `text`, one line, ellipsised. Tap opens the existing rename dialog (`EditableName`, restyled; Save, Reset, Cancel). Description "Rename, <name>".
3. Identity line: `secondary` / `textSecondary`, "Synology DS220+  NAS" (vendor and model, two spaces, type label). The "Found as" names list moves behind "Why this identity".
4. Data line: `data` / `textMuted`, "10.0.3.59  00:11:32:9a:1f:e4". MAC omitted when unknown.
5. Links row: "Why this identity" and "Look up vulnerabilities", `secondary` weight 500 in `signal`, 18 dp apart, each a 48 dp-high target. "Why this identity" expands the reasons inline below the row, capped at 3 reasons then "and N more" which opens the full list in a sheet, so the pinned block stays bounded.
6. Status control: four equal segments (Unmarked, Interesting, Investigated, Ignored), 8 dp radius, 1 dp `border`, `chip` text. Inactive: `textSecondary` on transparent. Active: `mark` fill, `background` text, weight 500. 48 dp tall, 18 dp side margin. Exactly one is active.
7. Note line: a box, 8 dp radius, 1 dp `border`, 8 dp / 10 dp padding, `secondary` italic / `textSecondary`, max 2 lines ellipsised. Empty: "Add a note" in `textMuted` italic. Tap swaps in the existing multi-line field in place (2 to 4 lines, saved on change as today); losing focus collapses it. `testTag("note")` stays on whichever is showing.
8. Scan bar, with a 1 dp `surface` rule beneath: Quick / Full / Custom segmented control at the left (8 dp radius, 1 dp `border`, 12 dp horizontal padding, active segment `surface` fill with `text`, inactive `textSecondary`), Scan pill at the right (fully round, `signal` fill, "Scan" in `scanPill` / `background`, 48 dp minimum height). While scanning the pill becomes an outlined "Stop" pill (1.5 dp `signal`, `signal` text) and the profile control is disabled (alpha .5).
9. Scan feedback inside the pinned bar: progress text `data` / `textMuted`, right-aligned under the pill ("312 of 65535, found 4"), and a 2 dp `signal` linear progress line across the bar (track `surface`). Reserved height 18 dp when idle so nothing jumps. The Custom profile shows its ports field under the segmented control as today, restyled; an invalid entry shows an `error` message and disables the pill.

`detailScanButton` stays on the Scan/Stop pill.

### 5.2 Ports list (scrolling)

Row: columns `52 dp / 1fr / auto`, 10 dp gap, 10 dp vertical padding (about 58 dp tall), 18 dp side padding, 1 dp `rowDivider` hairline below. The whole row triggers the primary action.

- Number: `port` / `text` in the fixed 52 dp column (65535 fits at font scale 1.3).
- Service: `secondary` / `text`; beneath it product and version in `data` / `textMuted`, one line ("OpenSSH 8.2p1", "cert chnas.local"; "HTTP" when no version).
- Right: one action word, `action` / `signal`, right-aligned: "Open", "Connect", "Open share". When no app handles the service the word is "Copy" in `textMuted` weight 400 and copies `ip:port` (this replaces the disabled "No app installed" button). The word has a 48 dp-high touch area.
- Long-press opens a `ModalBottomSheet` (`surface`, 8 dp top radius) titled "<ip>:<port> <service>" listing the remaining actions as 48 dp `secondary` rows: "Copy IP and port", "Open with...", other actions for the service, and "Look up vulnerabilities for this service" in `signal`. The row overflow icon is removed.
- Empty: "No open ports yet, run a scan"; while scanning with none yet, "Scanning ports".

### 5.3 Vulnerability sheet

`VulnSheet.kt` stays a `ModalBottomSheet`, same tokens. Background `surface`; title `rowName`; the editable query field restyled as in 4.1; four severity labels with counts (Critical in `error`, High in #FFB86B, Medium in `mark`, Low in `textMuted`; text plus count, never colour alone); CVE rows: id in `port`, a CVSS score chip (`caption`, `background` text on the severity colour, 4 dp radius), one-line summary in `secondary` / `textSecondary`, published date `data` / `textMuted`; `rowDivider` hairlines; tap opens the NVD page. Messages in `textMuted` (section 8).

## 6. Settings screen

Maps to `SettingsScreen.kt` (`Section`, `SwitchRow`, `StepSlider`, `LocationPermissionRow`). Restyle only; order and content are unchanged.

- Section title: `caption` / `textMuted` with a 1 dp `surface` rule above, 18 dp gutter, the same pattern as Home group headers. Not teal: teal is for live things.
- Switch row: label `secondary` / `text`, optional supporting text `data` / `textMuted`, 56 dp minimum height. Switch on: `signal` track, `background` thumb. Off: `surface` track with a `border` outline, `textMuted` thumb. Teal on a switch is the one deliberate stretch of "live" (on means active); open item 11.6 if the Director disagrees.
- Step slider: label `secondary` left, value `data` / `signal` tabular right, track `surface`, active track and thumb `signal`, stepping per existing config, 48 dp minimum row height.
- API key and manual-range fields: the restyled outlined field of 4.1. "Clear cache" is a `signal` text button.
- Extended discovery section (extended-discovery spec 8.3), between "Large networks" and "Permissions": "Extended discovery" switch with "Sweep other ranges your phone can reach."; "Maximum extra ranges" stepper (1 to 16) with the live line "At most N extra /24 networks (about M minutes)"; "Probe for gateways" switch with its supporting warning in `textMuted`; "Manual ranges": CIDRs in `data` tabular with a 48 dp delete target each, an "Add range" field with inline errors in `error` and normalisation notes in `textMuted`, and "Beyond cap, will be skipped" in `mark`. When the master switch is off the other rows are drawn at alpha .5, never hidden.
- Back control: the same "Back to Home" pattern as Detail.

## 7. Accessibility and quality floor

### 7.1 Contrast

Computed with the WCAG relative-luminance formula. A contrast test utility (section 10.1) asserts every row, so a token edit that breaks the floor fails the build.

| Colour | Hex | On #16202B | On #233447 | Rule |
|--------|-----|-----------|-----------|------|
| text | #E6EDF3 | 13.93 | 10.75 | Any size |
| textSecondary | #B9C7D4 | 9.55 | 7.37 | Any size |
| textMuted | #8FA3B5 | 6.33 | 4.88 | Passes 4.5:1 on both, so 12 sp `data` is allowed, as are 11 sp captions on `background` or `surface` |
| signal | #35D4C8 | 8.94 | 6.90 | Any size |
| mark | #FFD56B | 11.75 | 9.06 | Any size |
| background on signal fill | #16202B on #35D4C8 | 8.94 | n/a | Scan pill, hero circle |
| background on mark fill | #16202B on #FFD56B | 11.75 | n/a | Mark chip, active status segment |
| NAS edge | #7FA7FF | 6.96 | 5.37 | Non-text 3:1 |
| MEDIA edge | #C98BFF | 6.76 | 5.21 | Non-text 3:1 |
| IOT edge | #FFB86B | 9.66 | 7.45 | Non-text 3:1 |
| COMPUTER edge | #9BE07A | 10.46 | 8.07 | Non-text 3:1 |
| SERVER edge | #F2A0D6 | 8.45 | 6.52 | Non-text 3:1 |
| CAMERA edge, error text | #FF8A8A | 7.25 | 5.60 | Non-text 3:1; error text 4.5:1 |
| PRINTER edge | #D6C7A1 | 9.83 | n/a | Non-text 3:1 |
| border | #3B5368 | 2.06 | 1.59 | Decorative only, see below |
| borderSubtle | #2A3E54 | 1.50 | n/a | Decorative only (radar rings) |
| surface | #233447 | 1.30 | n/a | Fill only |

`border` and `borderSubtle` fall short of 3:1 for component boundaries. They are used only where state is also carried by fill and label: the active segment is filled, the selected chip changes fill and text colour, the note box is labelled by its text or "Add a note". The unknown-type edge (#3B5368) is therefore also decorative, because type is always given in words in the identity line. If the on-device review shows the outlines too faint, raise `border` to #5B7792 (about 3.4:1); the tokens are in one file. Muted text is never used below 11 sp.

### 7.2 Targets and sizes

- Minimum 48 dp x 48 dp touch targets: ring (padded to 64 dp), Stop pills, Scan pill, chips (32 dp visual), status and profile segments, Detail links, "Back to Home", port action word, Settings delete controls, the note line. Use `minimumInteractiveComponentSize()` or `heightIn(min = 48.dp)`; the visual may be smaller.
- Every text size is `sp`. Any container holding text uses `heightIn(min)`, not a fixed height.

### 7.3 Semantics

- Radar idle: `Role.Button`, "Scan, 13 devices, last scan 2 minutes ago". Scanning: the disc has the description "Scanning, 9 devices found, probing 171 of 254" as a polite live region, announced when `found` changes by 5 or more and at the end (so TalkBack is not flooded); it has no click action. Stop pill: `Role.Button`, "Stop scan". HERO: the circle is a button, "Scan the network 10.0.3.0/24".
- Scan finished: one polite announcement, "Scan finished, 13 devices", sent through the accessibility manager.
- Arm, blips, inner rings: `clearAndSetSemantics {}` (decorative).
- Chips: `selected` semantics, description "Filter: Interesting, selected".
- Rows: merged, "Ubiquiti gateway, gateway, 10.0.3.1, 3 open ports", with "marked Interesting" when a chip shows and "not seen in the last scan" when stale. The edge colour is never the only carrier of type or gateway status.
- Group headers: `heading()`. Status segments: `Role.RadioButton` in a `selectableGroup`.
- Port action: "Open port 80 in browser"; "Copy" reads "Copy 10.0.3.59:5357".

### 7.4 Motion

Reduced-motion behaviour is in 3.7. The sweep is a slow soft rotation (0.42 Hz), far from any flash threshold.

### 7.5 Font scale 1.3

All screens must work at font scale 1.0 and 1.3 (2.0 is a graceful-degradation check only: no overlap, scroll where scrolling is intended).

- Home: the disc stays 172 dp (it is a graphic); the progress line wraps to two lines rather than clipping; the network line stays one line with ellipsis; the data line may take two lines; segment and chip labels never truncate.
- Detail on a Pixel 7 Pro (about 892 x 412 dp, roughly 820 dp usable between system bars): estimated pinned block at 1.3 with the note collapsed is about 390 dp (back 48, title 34, identity 22, data 20, links 48, status 48, note 48, scan bar 60, progress 22, gutters about 40), leaving about 430 dp: at least six port rows of about 62 dp. With the note field expanded to its 4-line maximum the block grows by about 90 dp, leaving at least three rows. Acceptance on the device: collapsed note, at least six rows; expanded note, at least three. The requirement of at least three rows is therefore met in the worst case with margin.
- On a shorter device the reasons expansion (5.1 item 5) is capped at 3 and is the only element allowed to be dropped from the pinned block; copy is never shortened.
- The 52 dp port column holds "65535" (about 40 dp at 1.3). "Open share" never wraps (about 62 dp at 1.3); the service column wraps instead.
- Status segments: "Investigated" at 12 sp x 1.3 needs about 70 dp inside a segment of about 90 dp, so it fits; if a device is narrower the label wraps to two lines and the segment grows.

## 8. Copy

Sentence case, active voice, no disclaimers. Plurals: "1 device", "13 devices".

| Where | String |
|-------|--------|
| Ring label (idle, hero) | "Scan" |
| Idle | "13 devices" / "Last scan 2 minutes ago" (relative time: "just now", "N seconds ago", "N minutes ago", "N hours ago", "yesterday", then the date) |
| Idle after a scan that found nothing | "No devices found" / "Last scan 2 minutes ago" |
| Done | "13 devices" / "Scanned in 12 s, 1 on another subnet" |
| Done, single subnet | "13 devices" / "Scanned in 12 s" |
| Done with errors | "13 devices" / "Scanned in 12 s, 3 errors" |
| Done, nothing found | "No devices found" / "Scanned in 12 s" |
| Hero | "Nothing scanned yet" / "Tap to find what is on 10.0.3.0/24" |
| Hero, no network | "No network" / "Connect to Wi-Fi or Ethernet to scan" |
| Disc centre | count, "found" beneath |
| Stop pill | "Stop" |
| Progress, local sweep | "probing 171 of 254, 7 s" |
| Progress, gateway probe | "probing 10.0.0.0/16 for gateways, 112 of 256, 9 s" |
| Progress, extended sweep | "probing 10.0.4.0/24, 2 of 4 ranges, 31 s" |
| Quick scan all | "Quick scanning 4 of 13" |
| Group header, local | "Home subnet 10.0.3.0/24" |
| Group header, other | "Other subnet 10.0.4.0/24" |
| Mark chip | "Interesting", "Investigated", "Ignored" |
| Row open ports | "9 open" |
| Search placeholder | "Search name, IP, vendor or MAC" |
| Filter chips | "All", "Interesting", "Investigated", "Ignored", "Unmarked" |
| Empty, no network | "Connect to Wi-Fi or Ethernet to scan" |
| Empty, hero | "Devices appear here as they are found" |
| Empty, scanning | "Looking for devices" |
| Empty, scanned none | "No devices found. Check the network and scan again." |
| Empty, search | "No devices match \"<query>\"" |
| Empty, filter | "No devices marked <label>" |
| Message, network changed | "Network changed, scan stopped" |
| Message, large network | "Large network (/16). Scanned your /24 only. Widen in Settings." |
| Message, cap dropped ranges | "2 more ranges found, raise the cap in Settings" |
| Message, scan failed | "Scan failed: <reason>" |
| Message, quick scan partial | "Quick scan finished, 2 devices could not be scanned" |
| Message, location | "Turn on Location to show the Wi-Fi name"; "Wi-Fi name stays hidden without location permission" |
| Detail back | "Back to Home" |
| Detail links | "Why this identity"; "Look up vulnerabilities" |
| Status segments | "Unmarked", "Interesting", "Investigated", "Ignored" |
| Note, empty | "Add a note" |
| Scan profile | "Quick", "Full", "Custom" |
| Scan pill | "Scan" / "Stop" |
| Detail progress | "312 of 65535, found 4" |
| Port actions | "Open", "Connect", "Open share", "Copy" |
| Port sheet | "Copy IP and port"; "Open with..."; "Look up vulnerabilities for this service" |
| Ports empty | "No open ports yet, run a scan"; "Scanning ports" |
| Rename dialog | "Rename device", "Name", "Save", "Reset", "Cancel" |
| Settings sections | "Discovery", "Speed", "Large networks", "Extended discovery", "Permissions", "Vulnerabilities" |
| Vulnerability sheet | "No CVEs found for <query>"; "Offline, showing cached results from <time>"; "Offline" |

## 9. Scope

Changes (UI layer, `app/src/main/java/com/northwalessolutions/netprobe/ui/**` plus resources):

- Theme: `theme/Theme.kt` (tokens, `NetProbeTypography`, shapes, composition locals), new `theme/Tokens.kt` and `theme/Motion.kt`. Font files only after approval (`res/font`).
- New components: `home/RadarBlock.kt`; pure helpers `RadarModes`, `ScanSummaryText`, `RelativeTime`, `GroupHeaders`, `blipOffset`; `TypeEdge`, `MarkChip`, `PinnedSegmented`, `ScanPill`, `StopPill`, `rememberReducedMotion`.
- Screens: `HomeScreen.kt`, `HomeComponents.kt` (row, chips, network line, group headers), `DetailScreen.kt`, `DetailComponents.kt` (identity block, status segments, note line, scan bar, port rows, port sheet), `VulnSheet.kt`, `SettingsScreen.kt`, `Common.kt` (`EmptyState` colours).
- `DeviceType.icon()` and the icon imports go when their last caller does.

Does not change:

- View models and their behaviour: `HomeViewModel`, `DetailViewModel`, `ScanController`, `StatusFilter`, `HomeFilter`, `RowRules`. Two read-only additions to `HomeUiState` are allowed if the UI cannot derive them: `lastScanAt: Long?` (max `lastDiscoveryRun`, already stored) and `errorCount` (already in `DiscoveryStatus`). Scan duration is measured in the UI. No new collection, coroutine or setting.
- Engines (`discovery`, `portscan`, `serviceid`, `identify`, `actions`, `vulns`), `netenv`, Room entities, DAOs, migrations, repositories, `Settings.kt` keys.
- Navigation routes and arguments, the scan job lifecycle (cancel on Stop or network change, survive rotation), permissions and manifest.
- Extended discovery behaviour: this spec only styles its progress line, headers and Settings section. If that feature has not merged when this ships, those pieces are absent, not stubbed. `HostRow.subnet` is a dependency of the grouping; until it lands the group key derives from `otherSubnet`, and the plan must not block on it.

## 10. Tests and verification

### 10.1 JVM tests (pure helpers)

- `RadarModes.of`: scanning wins; no hosts and no last scan gives HERO; hosts present gives SETTLED; a finished scan with zero hosts gives SETTLED; a filtered-empty list is not HERO.
- `ScanSummaryText.of`: "Scanned in 12 s"; with other-subnet count; with errors; zero devices; singular "1 device".
- `RelativeTime.of`: boundaries at 59 s, 60 s, 59 min, 60 min, 23 h, 24 h, 2 days.
- `GroupHeaders.of`: local first, others by network address ascending, post-filter counts, empty groups omitted, exact header strings.
- `rowDataLine`: IP with identity; IP with MAC; name equals IP; unknown identity falls back to MAC; no "other subnet" suffix.
- `edgeColor`: every `DeviceType` has a colour; the ten values are pairwise distinct; gateway overrides the type colour.
- `blipOffset`: deterministic per index; radius within [0.22, 0.88] of the disc radius; inside the disc; the first 64 points are at least 4 dp apart (guards the hash).
- `progressLine`: local, gateway-probe and extended phases, with elapsed seconds.
- Contrast: a test-only helper asserts every ratio in 7.1 meets its stated minimum.

### 10.2 Compose UI tests (instrumented)

Tags: `radarBlock`, `radarRing`, `radarDisc`, `radarStop`, `radarCount`, `radarProgress`, `scanButton`, `filterRow`, `search`, `deviceList`, `groupHeader`, `detailHeader`, `detailScanBar`, `detailScanButton`, `statusSegments`, `note`, `portList`, `portAction`.

1. Pinned structure, Home: scroll `deviceList` to the end; `radarBlock`, `scanButton` and the network line keep identical `boundsInRoot`. The sticky group header stays at the top of the list.
2. Pinned structure, Detail: scroll `portList`; `detailHeader` and `detailScanBar` bounds are unchanged and `detailScanButton` remains clickable.
3. Three radar states from a fake state: HERO (disc and Scan circle present, no chips, "Nothing scanned yet"); SCANNING (`radarDisc` 172 dp, `radarStop` present, `radarCount` shows the count, ring absent); SETTLED (ring 56 dp, summary text matches, no Stop).
4. Disc does nothing: click `radarDisc`, assert `stopScan` was not called; click `radarStop`, assert it was.
5. Reduced motion: with the animator scale at 0, the arm is static and the size change completes in one frame (test clock reports no pending animation).
6. Row anatomy: edge colour exposed through a custom semantics property; the gateway row has the teal edge and "gateway" in its description; "This phone" has the muted edge; a marked row shows its chip text.
7. Font scale: at `fontScale = 1.3f` on the Detail screen, at least six `portAction` nodes displayed with the note collapsed and at least three with it expanded (phone-sized configuration); on Home, Scan or Stop and every chip are reachable and not clipped.

### 10.3 On-device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, mandatory before handoff)

For each of font scale 1.0 and 1.3, capture a screenshot (`adb exec-out screencap -p`) of:

1. Home first launch (app data cleared): HERO with Scan in the middle.
2. Home scanning, early (few devices) and late (about 12 devices, blips visible, rows arriving).
3. Home settled with the summary text, and again after leaving and re-entering (idle text).
4. Home with a search that matches nothing, a filter that matches nothing, and with no network.
5. Home with the other-subnet group, sticky header mid-scroll.
6. Home with extended discovery on (range progress line), once that feature is merged.
7. Detail: top state, scrolled ports, note expanded, scanning (progress line visible), long-press port sheet, "Why this identity" expanded.
8. Vulnerability sheet with results and offline.
9. Settings: top, middle, bottom, including the extended discovery section.

Behaviour checks, each recorded pass or fail: Stop pill stops a scan and the disc settles; tapping the disc does nothing; rotating mid-scan keeps the scan and the disc; backgrounding mid-scan stops the arm and resuming restarts it (if the Director confirms 11.2); animator scale 0 freezes the arm; TalkBack reads the ring, Stop pill and rows as in 7.3; janky-frame percentage from `adb shell dumpsys gfxinfo` under 5 percent during a scan.

Per the standing QA rule, Bugsy builds, installs on the device and smoke tests every flow; static review alone is not accepted, and a green test suite is not a substitute for looking at the screenshots.

## 11. Open items for the Director

1. Font bundling: approved by the Director (IBM Plex Sans Regular, Medium, SemiBold from Google Fonts, SIL OFL). The plan fetches exactly those files once.
2. Background behaviour of the arm. Recommendation: the arm and all radar animation stop while the app is not resumed (battery, nothing to look at); the scan itself is unaffected. Confirm, or say you want it to keep turning.
3. D13: group headers read "Home subnet 10.0.3.0/24" with a right-aligned count, replacing "10.0.3.0/24 (this network), 14 devices" from the extended-discovery spec.
4. D12: chips and search collapse while the large disc is showing. Confirm, or say they should stay and the disc should be smaller.
5. D15: non-gateway routers keep the teal edge, or use a dimmer teal (#5FB8B0) so teal on an edge means the gateway alone.
6. Teal on "on" switches in Settings (6): keep, or use `mark` yellow.
