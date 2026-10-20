# NetProbe Radar UI Design (visual pass over the MVP screens)

Date: 2026-10-19. Author: Ada (Engineering Lead). Status: revised after Gruff's review (`2026-10-19-radar-ui-gruff-review.md`) and the controller's rulings; ready for planning once the open items in section 11 are noted.
Baseline: master 9dc24d5.
Agreed mockups: `.superpowers/brainstorm/52057-1792445317/content/` (`visual-direction.html` direction A, `radar-layout.html`, `radar-states.html`). Where this document and a mockup disagree on a number, the mockup is the source and this document is wrong, except for the deliberate deviations listed in section 12.
Related specs: `2026-10-19-netprobe-mvp-design.md` section 5.7 (current UI), `2026-10-19-extended-discovery-design.md` (data side only, see 4.2 and 8).

## 1. Purpose

NetProbe works but looks like stock Material 3 with dynamic colour. This pass gives it one memorable element, the radar, and makes everything else quiet so the radar and the data carry the screen.

- One signature element: the scan control is a radar. It is a small ring at rest, a sweeping disc while scanning, and a ring again when done. On first launch it is a large disc with Scan in the middle.
- Everything else is dark slate, thin lines and tabular figures. No icons on rows (device type is a 4 dp coloured edge), no filled cards, no shadows.
- Signal teal means "live" and nothing else: the radar, open ports, the gateway edge, the primary action word. A teal element that is not live or primary is a bug.
- The Director's standing rule is a hard requirement of this design: Scan, Stop and the Detail scan bar are pinned outside any scrolling content. The radar block is part of the pinned Home header; the scan bar is part of the pinned Detail block. Only the device list and the port list scroll.
- The app is locked to portrait (D16). Landscape is out of scope for this pass.
- UI only, apart from the read-only `HomeUiState` additions in section 9 and the one manifest change in 2.5. No engine, data, repository or scan-logic change.

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
| D10 | Font: IBM Plex Sans, two variable TTFs (upright and italic) in `res/font`, already placed by the controller; the download was approved by the Director (2026-10-19) | Director |
| D11 | Animation stops when the app is not resumed | Ada recommendation, open item 11.1 |
| D12 | Filter chips and search collapse while the large disc is showing only when the filter is All and the query is blank; otherwise they stay | Ada, follows `radar-states.html` state 2, open item 11.2 |
| D13 | Group headers use fixed wording ("Home subnet 10.0.3.0/24", "Other subnet 10.0.4.0/24"), no count, as drawn. This spec owns all Home wording; the extended-discovery spec supplies data only | Ada, controller ruling |
| D14 | Reduced motion is read from the system animator duration scale (0 means reduced) | Ada, see 3.7 |
| D15 | Router-typed hosts that are not the gateway also use the teal edge | Ada, open item 11.3 |
| D16 | The app is locked to portrait (`android:screenOrientation="portrait"` on `MainActivity`; the orientation entries removed from `configChanges`). Landscape is out of scope | Controller ruling |
| D17 | All transient messages show in one notice line under the radar block; the Snackbar is not used | Ada, Gruff finding 15 |

## 2. Design tokens

### 2.1 Palette

Dark theme only. One `NetProbeColors` object holds the tokens, and a Material `darkColorScheme` is built from it so stock components (dialogs, sheets, text fields, switches) inherit them. Read the role table below before wiring: the token called `surface` is NOT the Material role called `surface`.

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

Material colour scheme role mapping (binding; a literal token-name mapping would paint every screen #233447, because `MainActivity` wraps the app in a `Surface` that paints the Material `surface` role):

| Material role | Value |
|---------------|-------|
| `background`, `surface` | #16202B (token `background`) |
| `surfaceVariant`, `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest` | #233447 (token `surface`) |
| `onBackground`, `onSurface` | `text` |
| `onSurfaceVariant` | `textSecondary` |
| `primary` | `signal` |
| `onPrimary` | `background` |
| `outline` | `border` |
| `outlineVariant` | `borderSubtle` |
| `error` | #FF8A8A |
| `surfaceTint` | Transparent (no tonal elevation tint) |

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

The four chosen values are from the same pastel family as the drawn ones (similar lightness, so none outshouts another) and are distinct from teal, blue and violet. A non-gateway ROUTER (an access point, for example) also gets teal (D15). If the Director wants teal on edges for the gateway alone, use #5FB8B0 for non-gateway routers: a one-line change in `edgeColor`. The four chosen colours are reviewed by the Director on device screenshots (open item 11.4).

Stale hosts (not seen in the latest run): the type edge is drawn at 0.6 alpha (0.5 fails the 3:1 floor for the NAS, media, phone and camera edges) and the device name at 0.6 alpha. The data line, mark chip and open-port count stay at full strength, so a stale host's IP stays readable. The name alpha is the highest value that keeps the contrast test (7.1) at or above 4.5:1; if a token change breaks it the test fails.

### 2.2 Typography

IBM Plex Sans as two variable TTFs (axes `wdth` and `wght`), already downloaded by the controller from IBM's official source and placed at `app/src/main/res/font/ibm_plex_sans.ttf` (upright) and `app/src/main/res/font/ibm_plex_sans_italic.ttf` (italic, used by the note line and "Add a note", so Android never synthesises a slant). Weights 400, 500 and 600 come from `FontVariation.Settings(FontVariation.weight(n))` (the `wght` axis) on API 26+, one `Font(R.font..., variationSettings = ...)` entry per weight. The SIL Open Font License text is at `app/src/main/res/raw/ibm_plex_sans_ofl.txt`.

The Director approved this download on 2026-10-19 and the controller has done it; no plan step or subagent downloads or installs anything. There is no runtime font download and no `ui-text-google-fonts`.

IPs, MACs, ports, counts and times use tabular figures: `fontFeatureSettings = "tnum"` on the `data`, `port`, `count` and `radarCount` styles. An instrumented check (10.2 test 9) measures "1111" and "0000" in each tnum style and asserts equal width, so a font file without the feature is caught. The Material `Typography` is fully overridden so no stock style leaks through.

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

### 2.5 Theme replacement, window and system bars

`ui/theme/Theme.kt` drops the `dynamicDarkColorScheme`, `dynamicLightColorScheme` and `lightColorScheme` branches and the `dark` parameter. `NetProbeTheme` always supplies the Radar dark scheme (role table in 2.1), `NetProbeTypography`, the shapes, and `CompositionLocal`s for the extra tokens. Light mode is explicitly out of scope: a device set to the light theme still shows the dark Radar look.

The app is NOT edge-to-edge today (no `enableEdgeToEdge()`, `targetSdk = 30`, and `res/values/themes.xml` has parent `android:Theme.Material.Light.NoActionBar`, which gives a white window flash on cold start and on navigation). This pass fixes it:

- `res/values/themes.xml`: parent `android:Theme.Material.NoActionBar`, with `windowBackground`, `statusBarColor` and `navigationBarColor` all #16202B (API 26 safe, no new dependency).
- `MainActivity`: call `enableEdgeToEdge()` with `SystemBarStyle.dark(#16202B)` for the status bar and the navigation bar, so the bars use `background` with light icons. Scaffolds apply the insets.
- `MainActivity` is locked to portrait: `android:screenOrientation="portrait"` in the manifest, and the `orientation` entries removed from `configChanges` (other entries are left as they are). This is the only manifest change in this pass (D16).

## 3. The radar component

`ui/home/RadarBlock.kt` is one composable that owns all three states and the first-launch variant, so the size change is one continuous animation, not a screen swap.

```kotlin
enum class RadarMode { HERO, SCANNING, SETTLED }   // HERO = first launch: no hosts, no completed scan, not scanning
data class RadarSummary(val headline: String, val detail: String)
@Composable fun RadarBlock(mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary,
                           canScan: Boolean, onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier)
```

### 3.1 Mode derivation (pure, JVM-testable)

`RadarModes.of(scanning: Boolean, totalHosts: Int, lastScanAt: Long?)`:

- SCANNING when a scan is running.
- HERO when not scanning, the current network has no stored hosts (`totalHosts`, which is unfiltered), and there is no completed scan.
- SETTLED otherwise, including a finished scan that found nothing (ring with "No devices found", so the Director is not thrown back to a first-launch screen).

`lastScanAt` is the `max(lastDiscoveryRun)` of the network's hosts, which is already stored; no schema change. It is the start time of that run, not its end, so "Last scan 2 minutes ago" is early by the scan duration; that is accepted. A search or filter with no matches is not a first launch, because `totalHosts` ignores the filter and query.

### 3.2 Idle (SETTLED)

- Row: 56 dp ring, 14 dp gap, text column. Ring: 3 dp `signal` stroke, "Scan" in `ringLabel` / `signal` centred. This is a full teal ring because tapping it does something (`radar-states.html` state 1).
- Text: headline `summary` / `text` ("13 devices"), detail `secondary` / `textSecondary` ("Last scan 2 minutes ago"). The headline is `totalHosts`: every stored host for this network, stale ones included, whatever the filter or query. The content description is one string: "Scan, 13 devices, last scan 2 minutes ago".
- Tapping the ring starts a scan. The touch target is padded to 64 dp square.
- Disabled (no network): ring stroke and label in `border`, no ripple, description "Scan, unavailable, not connected".

### 3.3 Scanning

- Disc 172 dp, centred in the pinned header, 2 dp `borderSubtle` outline, radial background (`#1B2836` to 30 percent, `background` from 70 percent). Inner rings at 28 dp and 58 dp inset, 1 dp `borderSubtle`.
- Arm: a sweep gradient, `signal` at alpha .55 at 0 deg, alpha .08 at 70 deg, transparent from 110 deg to 360 deg, clipped to the disc and masked out of the centre 40 dp radius, so the "found" label and the count always sit on the plain `#1B2836` fill (contrast in 7.1). One turn per 2400 ms, linear.
- Blips: one per newly found host. A 6 dp `signal` dot with an 8 dp glow (drawn radial alpha, not a shadow). Position is pseudo-random but stable: `blipOffset(index: Int, radiusPx: Float): Offset` hashes the index (a fixed 32-bit integer hash) to an angle in [0, 360) and a radius fraction in [0.5, 0.88] of the disc radius (0.22 was contradicted by the centre count, which the blips must never cover). Blips fade in over 300 ms and stay until the scan ends. At most 64 are drawn; the centre count is exact regardless.
- Centre: found count in `radarCount` / `text`, `maxLines = 1`, auto-sized down (Compose `BasicText` `autoSize`, or a measure-and-shrink fallback if the BOM's API is not stable) so the text never exceeds 96 dp width at any font scale (a /22 sweep can reach 1022 hosts and 39 sp digits would otherwise cross the inner ring). "found" in `radarLabel` / `textMuted` below it.
- Below the disc, 8 dp gap: Stop pill, "Stop" in `action` / `signal`, 1.5 dp `signal` outline, fully round, 4 dp / 14 dp visual padding, 48 dp minimum touch height. Then, 6 dp gap: the progress line in `data` / `textMuted`, centred. The progress strings are in section 8; the drawn form uses two spaces before the elapsed time: "probing 171 of 254  7 s".
- With extended discovery on the progress line shows the active phase instead (strings in section 8). The feature being absent is not an error: the range text is simply never produced.
- Tapping the disc does nothing: the disc has no click handler and no ripple. Only Stop is actionable and calls `vm.stopScan()` exactly as the current Stop button does.
- Quick scan all (overflow menu) is not a radar scan. While it runs the block stays SETTLED, the ring stroke dims to `border` and an indeterminate `signal` arc sweeps over it with "Stop" inside (a teal arc on the idle teal ring would be invisible), and the detail text reads "Quick scanning" (no count: the view model exposes no progress and none is added). The same control starts and stops; quick scan never grows the disc. The overflow item "Quick scan all" is disabled while a radar scan runs, so the two controls never both show Stop.
- The UI latches the last non-null `DiscoveryStatus`: the view model sets `status` to null in the same emission in which scanning stops, so the final found count and progress would otherwise be gone when the settle begins.

### 3.4 Done (settling)

When `scanning` goes from true to false: the disc shrinks to the ring over 360 ms, the count cross-fades to "Scan" over 160 ms, blips fade out over 200 ms, and the text column fades in with the headline "13 devices" and the detail "Scanned in 12 s, 1 on another subnet".

Summary lifetime: the finished-scan summary persists across navigation (leaving to Detail and coming back) until the next scan starts or the process restarts. It is held in `rememberSaveable` in `HomeContent`'s host so the Navigation back stack restores it, keyed on `scanStartedAt` so a stale summary never shows against a newer run; starting a scan clears it. After a process restart, or when no summary is held, the detail reads the idle text ("Last scan 2 minutes ago"), because the duration is not persisted. (There is no rotation case: the app is portrait-locked.)

Duration: `scanFinishedAt - scanStartedAt`, both read from `HomeUiState` (section 9), so opening a device mid-scan and coming back still gives the true duration. The headline is `totalHosts`; "on another subnet" is `otherSubnetHosts`.

`ScanSummaryText.of` (pure): "Scanned in N s", then ", K on another subnet" only when K > 0. Zero devices: headline "No devices found", detail "Scanned in 12 s". Error counts are not shown: `DiscoveryStatus` has no error count and adding one is engine work, deferred (open item 11.7).

### 3.5 First launch (HERO)

- The header shows the network line, then a 172 dp disc with a filled `signal` circle of 84 dp in the middle, "Scan" in `heroButton` / `background`. Three static 6 dp `textMuted` dots sit at fixed offsets to suggest a radar at rest. The arm does not turn.
- Under the disc, centred: "Nothing scanned yet" (`summary`, `text`) and "Tap to find what is on 10.0.3.0/24" (`secondary`, `textMuted`). With no network: "No network" and "Connect to Wi-Fi or Ethernet to scan", the circle disabled (`border` fill, `textMuted` label). That no-network string lives only here; the list area shows no second copy of it.
- No filter chips or search in HERO (D12; HERO always has filter All and a blank query). The list area shows the placeholder "Devices appear here as they are found", drawn as in `radar-layout.html`: a group-header-style line (`caption` / `textMuted`, left aligned, 1 dp `surface` rule above).
- HERO and SCANNING share the same 14 dp top padding, so the transition does not jump.
- On tap the disc keeps its size, the button fades to the count, the arm starts and the Stop pill appears. When the scan finishes it settles to the 56 dp ring and HERO is not shown again for that network.
- Only the 84 dp Scan circle is actionable. The disc around it does nothing (D6).

### 3.6 Animation approach and timing

One `updateTransition(targetState = mode)` drives everything, so the states cannot drift apart. It animates a single `fraction` (0 = ring, 1 = disc, 360 ms, `FastOutSlowInEasing` both ways). Everything geometric is derived from that one value:

- diameter: lerp 56 dp to 172 dp;
- horizontal offset: lerp from the start gutter (ring at rest) to centred (disc), so the disc grows from where the ring is and does not snap to centre;
- stroke width: lerp 3 dp to 2 dp;
- stroke colour: lerp `signal` to `borderSubtle`;
- block height: lerp from the ring row to disc plus Stop pill plus progress line, so the list below slides with it.

| Value | Spec |
|-------|------|
| Scan label to count | cross-fade 160 ms |
| Ring text column (headline and detail) | fade out on expand, fade in on settle; composed only while `fraction < 1`, so hidden text is never laid out and there is no interpolating layout |
| Stop pill and progress line | alpha, 160 ms, delayed 120 ms on expand; composed only when the mode is not SETTLED |
| Blip fade in / out | 300 ms / 200 ms |
| Arm | `rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)))` |
| Row arrival | 600 ms, `CubicBezierEasing(0f, 0f, 0.58f, 1f)`, alpha 0 to 1 and 6 dp rise |

- `animateDpAsState` is an acceptable substitute for the transition if the plan prefers it; the output is the same. A shared-element transition (`SharedTransitionLayout`) is not used: it is experimental at the project's Compose BOM 2025.06.00 and nothing here moves between destinations.
- Drawing: the static disc (fill, outline, inner rings) and the blips are drawn with `drawWithCache`, so they are re-recorded only when `found` or the size changes. The arm is its own layer, rotated with `graphicsLayer { rotationZ = angle }` with the angle read inside the layer block, so each frame is a RenderThread composite and never a recomposition or a re-record of the disc and glows. The infinite transition exists only in the composition branch where the mode is SCANNING and the lifecycle is RESUMED, so it is disposed when scanning ends or the app is backgrounded (D11).
- Resumed check: `LocalLifecycleOwner.current.lifecycle.currentStateAsState()` at least RESUMED.
- Progress numbers feed the UI through a flow sampled every 250 ms in the UI layer (no engine change), so a burst of discoveries cannot recompose the header on every host.
- Row arrival: while SCANNING, rows whose id was not present when the scan began fade and rise (`Modifier.animateItem(fadeInSpec = ...)` plus a one-shot `graphicsLayer` translation keyed on the id). Initial load, filter changes and returning to the screen do not animate. The set of already-present ids is captured when `scanning` flips to true.

### 3.7 Reduced motion

Compose has no first-class reduced-motion flag, and `LocalAccessibilityManager` only exposes timeout recommendations, so it cannot answer this question. The rule is explicit:

```kotlin
val LocalReducedMotion = compositionLocalOf { false }          // provided by NetProbeTheme
@Composable fun rememberReducedMotion(): Boolean   // Settings.Global.ANIMATOR_DURATION_SCALE == 0f
```

`NetProbeTheme` provides `LocalReducedMotion` from `rememberReducedMotion()`; every animated component reads the local, so tests can override it without writing system settings. `rememberReducedMotion` re-reads the setting each time the lifecycle resumes (not once per composition).

Android's "Remove animations" setting and a developer animator scale of "off" both set the scale to 0. When true: the arm is drawn static at 40 degrees, the size change and row arrival are instant (duration 0), blips appear without fading, and the count still updates. Nothing is lost informationally. Other animator scales (for example 0.5x) are honoured automatically by Compose tweens and need no special case.

## 4. Home screen

Maps to `HomeScreen.kt` and `HomeComponents.kt`. The `Scaffold` topBar remains the pinned region. Test tags: `homeMenu`, `search`, `filterRow` and `deviceList` are kept. `scanButton` is kept on the HERO disc's Scan circle (the live control on first launch); the settled ring carries `radarRing` and the Stop pill carries `radarStop`. One node carries one tag.

Stateless split (UI only): `HomeScreen(container, ...)` becomes a thin wrapper that builds the view model, collects its state and calls `HomeContent(state: HomeUiState, actions: HomeActions)`, where `HomeActions` bundles the callbacks (`onScan`, `onStop`, `onOpenHost`, `onDismissMessage`, filter and query setters, menu actions). All layout lives in `HomeContent`, so tests can drive any state with a fake and record which action fired.

### 4.1 Pinned header, top to bottom

1. Network line (`NetworkPanel` restyled): "Home" in `rowName` / `text`, then "10.0.3.0/24  gateway 10.0.3.1" in `data` / `textMuted`, one line, ellipsised. The name rule is unchanged (SSID, else "Wi-Fi" or "Ethernet"; the "tap to show the name, needs location" hint stays and is the one tappable-looking text here, in `signal`). The DNS suffix of `networkDetailLine` is dropped from the Home line to keep it to one line; the function itself and its test are unchanged. The overflow menu icon (`homeMenu`) sits at the right end, 48 dp target, `textSecondary`. This line is the only place the network name appears.
2. `RadarBlock` (section 3).
3. Notice line (4.4), when there is a message.
4. Filter chips: All, Interesting, Investigated, Ignored, Unmarked; horizontally scrollable; 8 dp gap. Chip: fully round, 1 dp `border` outline, `chip` text in `textSecondary`, 32 dp visual height inside a 48 dp touch target. Selected: `surface` fill and outline, `text`.
5. Search field: `OutlinedTextField`, 8 dp radius, 1 dp `border`, focused outline and cursor `signal`, placeholder in `textMuted`, search icon `textMuted`. The query lives in the view model.

Chips and search collapse (D12) only while the large disc is showing (HERO or SCANNING) AND the filter is All AND the query is blank. If a filter other than All is selected, the chip row stays visible during SCANNING; if the query is non-blank, the search field stays too. A hidden filter must never keep applying invisibly. Keeping them costs about 58 dp, which portrait absorbs (7.5).

None of this scrolls. The device list fills the rest of the screen.

### 4.2 Grouped list

- Grouping data comes from the view model, the UI only formats it. Once extended discovery lands, `HomeViewModel` supplies the group key (`HostRow.subnet`), the group order (local group first, then other groups by network address ascending) and the within-group order (gateway first, then by IP, unchanged `HomeFilter`). Until then the UI splits the existing rows by `HostRow.otherSubnet` (null means local) as today and formats the same headers; the plan must not block on the extended feature. This spec adds no grouping logic beyond formatting the header text.
- Sticky headers (`stickyHeader`; it is stable in foundation 1.8.2 at Compose BOM 2025.06.00, so no `@OptIn` is needed). Header: `caption` / `textMuted`, opaque `background` so rows scroll cleanly beneath it, 1 dp `surface` rule above. Text is fixed wording, never the network name: "Home subnet 10.0.3.0/24" for the local group and "Other subnet 10.0.4.0/24" for others. No count is shown, as drawn (D13). Exposed as a heading. A single group still shows its header, because it carries the subnet.

### 4.3 Row anatomy

Columns: 4 dp type edge, content, right column; 12 dp gap. The whole row is the click target (`onOpenHost`).

- Edge: `edgeColor(type, isGateway)`. Gateway row: teal. "This phone" row: #8FA3B5. Edge height 34 dp, vertically centred.
- Line 1: display name in `rowName` / `text`, one line, ellipsised; if status is not Unmarked, a mark chip: `caption`, `background` text on `mark`, 4 dp radius, 1 dp / 6 dp padding, 6 dp left margin; text is the status label.
- Line 2: `data` / `textMuted`: "10.0.3.59  Synology DS220+" (IP, two spaces, identity). Rule: identity when known (not blank and not the "Unknown device" fallback), else the MAC, else the IP alone. When the name equals the IP, line 2 is the identity or MAC only. `rowDataLine` replaces `rowSecondaryLine`: same logic, two spaces instead of the middle dot, and the "other subnet" suffix is gone (the group header carries it). For the "This phone" row the identity is `Build.MODEL` (for example "Pixel 7 Pro"), substituted in the UI; `RowRules` is untouched and its "Android device" line is not shown.
- Right column: open-port count in `data` / `signal`, tabular, right-aligned: "9 open". Nothing when zero. The old "Gateway" text label and the status chip are removed: the gateway is the teal edge and status is the mark chip.
- Stale: edge at 0.6 alpha and name at 0.6 alpha only (2.1), plus a semantics suffix "not seen in the last scan". The data line, chip and open count are not dimmed. This is the one intentional reduction.
- No divider between rows; rhythm comes from the 9 dp padding. Group headers carry the only rule.

### 4.4 Empty states and messages

Empty states: `secondary` / `textMuted`, centred in the list area under the header (strings in section 8). There is no empty-state copy for "no network" (HERO owns it, 3.5).

Messages: every `HomeUiState.message` is shown in one notice line, `data` / `textMuted`, directly under the radar block, with a 48 dp dismiss target, so the explanation sits beside the thing it explains. The Snackbar is not used (D17); `HomeScreen` no longer consumes messages into a snackbar. A notice stays until dismissed or the next scan starts: the UI calls `dismissMessage()` immediately before `startScan()`. Messages include network changed, the large-network clamp, the cap note, location prompts, quick-scan failures, "Scan failed", and the two extended-discovery end notes (section 8).

## 5. Device screen

Maps to `DetailScreen.kt` and `DetailComponents.kt`, as drawn in `radar-layout.html`. Structure is unchanged: a pinned topBar `Column` with identity, status, note and scan bar; the ports `LazyColumn` (`portList`) is the only scrolling part. Stateless split (UI only): `DetailScreen(container, ...)` is a thin wrapper over `DetailContent(state: DetailUiState, actions: DetailActions)`, as for Home.

### 5.1 Pinned block, top to bottom

1. "Back to Home": `data` / `textMuted` with a leading chevron, replacing the `TopAppBar` navigation icon. 48 dp tall touch row. Gesture back is unchanged.
2. Name: `title` / `text`, one line, ellipsised. Tap opens the existing rename dialog (`EditableName`, restyled; Save, Reset, Cancel). Description "Rename, <name>".
3. Identity line: `secondary` / `textSecondary`, "Synology DS220+  NAS" (identity text, two spaces, type label). Rule (pure helper `identityWithType`): the type label is appended only when the identity text from `IdentityLine.of` does not already end with it (case-insensitive) and the type is not UNKNOWN, so "Android Phone  Phone" never appears; with a blank identity the type label stands alone; with neither, the line is omitted. The "Found as" names list moves behind "Why this identity".
4. Data line: `data` / `textMuted`, "10.0.3.59  00:11:32:9a:1f:e4". MAC omitted when unknown.
5. Links row: "Why this identity" and "Look up vulnerabilities", `secondary` weight 500 in `signal`, 18 dp apart, each a 48 dp-high target. "Why this identity" always opens a `ModalBottomSheet` (`surface`) with the reasons and the "Found as" names; it is never inline, so the pinned block has no variable-height part.
6. Status control: four equal segments (Unmarked, Interesting, Investigated, Ignored), 8 dp radius, 1 dp `border`, `chip` text. Inactive: `textSecondary` on transparent. Active: `mark` fill, `background` text, weight 500. 40 dp visual height inside a 48 dp touch target (the drawn control is about 30 dp; see section 12), 18 dp side margin. Exactly one is active.
7. Note line: a box, 8 dp radius, 1 dp `border`, 8 dp / 10 dp padding, `secondary` italic / `textSecondary`, max 2 lines ellipsised. Empty: "Add a note" in `textMuted` italic. Tap swaps in the existing multi-line field in place (2 to 4 lines, saved on change as today); losing focus collapses it. `testTag("note")` stays on whichever is showing.
8. Scan bar, with a 1 dp `surface` rule beneath: Quick / Full / Custom segmented control at the left (8 dp radius, 1 dp `border`, 12 dp horizontal padding, active segment `surface` fill with `text`, inactive `textSecondary`; 40 dp visual inside a 48 dp target), Scan pill at the right (fully round, `signal` fill, "Scan" in `scanPill` / `background`; 40 dp visual inside a 48 dp target). While scanning the pill becomes an outlined "Stop" pill (1.5 dp `signal`, `signal` text) and the profile control is disabled (alpha .5).
9. Scan feedback inside the pinned bar: progress text `data` / `textMuted`, right-aligned under the pill ("312 of 65535, found 4"), and a 2 dp `signal` linear progress line across the bar (track `surface`). The reserved height is `heightIn(min = ...)` computed from the `data` style's line height in sp (about 21 dp at font scale 1.3), not a fixed 18 dp, so the bar does not jump when a scan starts. The Custom profile shows its ports field under the segmented control as today, restyled; an invalid entry shows an `error` message and disables the pill.

`detailScanButton` stays on the Scan/Stop pill.

### 5.2 Ports list (scrolling)

Row: columns `52 dp / 1fr / auto`, 10 dp gap, 10 dp vertical padding (about 58 dp tall), 18 dp side padding, 1 dp `rowDivider` hairline below. The whole row triggers the primary action.

- Number: `port` / `text` in the fixed 52 dp column (65535 fits at font scale 1.3).
- Service: `secondary` / `text`; beneath it product and version in `data` / `textMuted`, one line ("OpenSSH 8.2p1", "cert chnas.local"; "HTTP" when no version).
- Right: one action word, `action` / `signal`, right-aligned, 48 dp high and wide touch area. The word comes from a pure UI helper, not from `actions`: `actionWord(uriScheme: String?): String` keyed on the scheme of the primary action's `OpenUri.uri`: http, https, ftp give "Open"; ssh, telnet, vnc, rdp give "Connect"; smb gives "Open share". When no app handles the service (the primary action is the "No app installed for ..." entry, or there is no `OpenUri`) the word is "Copy" in `textMuted` weight 400 and copies `ip:port` (this replaces the disabled "No app installed" button).
- Long-press opens a `ModalBottomSheet` (`surface`, 8 dp top radius) titled "<ip>:<port> <service>" listing the remaining actions as 48 dp `secondary` rows. A second pure helper, `sheetLabel(action)`, maps the existing `ActionProvider` labels to the sheet wording: "Copy IP:port" to "Copy IP and port", other `OpenUri` actions keep their provider label, plus "Open with..." and "Look up vulnerabilities for this service" in `signal`. The sheet filters out the "No app installed" entry exactly as `PortRowItem` does today. The row overflow icon is removed.
- `ActionProvider` and `ActionProviderTest` are untouched. The helpers live in `ui/detail` and have their own JVM tests (10.1).
- Empty: "No open ports yet, run a scan"; while scanning with none yet, "Scanning ports".

### 5.3 Vulnerability sheet

`VulnSheet.kt` stays a `ModalBottomSheet`, same tokens. Background `surface`; title `rowName`; the editable query field restyled as in 4.1; four severity labels with counts (Critical in `error`, High in #FFB86B, Medium in `mark`, Low in `textMuted`; text plus count, never colour alone); CVE rows: id in `port`, a CVSS score chip (`caption`, `background` text on the severity colour, 4 dp radius), one-line summary in `secondary` / `textSecondary`, published date `data` / `textMuted`; `rowDivider` hairlines; tap opens the NVD page. Messages in `textMuted` (section 8).

## 6. Settings screen

Maps to `SettingsScreen.kt` (`Section`, `SwitchRow`, `StepSlider`, `LocationPermissionRow`). Restyle only; order and content are unchanged. Settings strings belong to each feature's spec (see section 8).

- Section title: `caption` / `textMuted` with a 1 dp `surface` rule above, 18 dp gutter, the same pattern as Home group headers. Not teal: teal is for live things.
- Switch row: label `secondary` / `text`, optional supporting text `data` / `textMuted`, 56 dp minimum height. Switch on: `signal` track, `background` thumb. Off: `surface` track with a `border` outline, `textMuted` thumb. Teal on a switch is the one deliberate stretch of "live" (on means active); open item 11.5 if the Director disagrees.
- Step slider: label `secondary` left, value `data` / `signal` tabular right, track `surface`, active track and thumb `signal`, stepping per existing config, 48 dp minimum row height.
- API key and manual-range fields: the restyled outlined field of 4.1. "Clear cache" is a `signal` text button.
- Extended discovery section (extended-discovery spec 8.3), between "Large networks" and "Permissions": "Extended discovery" switch with "Sweep other ranges your phone can reach."; "Maximum extra ranges" stepper (1 to 16) with the live line "At most N extra /24 networks (about M minutes)"; "Probe for gateways" switch with its supporting warning in `textMuted`; "Manual ranges": CIDRs in `data` tabular with a 48 dp delete target each, an "Add range" field with inline errors in `error` and normalisation notes in `textMuted`, and "Beyond cap, will be skipped" in `mark`. When the master switch is off the other rows are drawn at alpha .5, never hidden. The wording of these rows is owned by the extended-discovery spec; this spec only styles them.
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
| PRINTER edge | #D6C7A1 | 9.83 | 7.59 | Non-text 3:1 |
| border | #3B5368 | 2.06 | 1.59 | Decorative only, see below |
| borderSubtle | #2A3E54 | 1.50 | n/a | Decorative only (radar rings) |
| surface | #233447 | 1.30 | n/a | Fill only |
| stale name (text at 0.6 alpha) | blended over #16202B | at least 4.5 | n/a | Row name of a stale host |
| stale edge (type edge at 0.6 alpha) | blended over #16202B | at least 3.0 | n/a | Non-text; checked for every edge colour except UNKNOWN |
| radar count on disc centre | #E6EDF3 on #1B2836 | at least 4.5 | n/a | The arm is masked out of the centre 40 dp radius, so the count never sits under it |
| radar "found" label on disc centre | #8FA3B5 on #1B2836 | at least 4.5 (about 5.75) | n/a | Same mask |

`border` and `borderSubtle` fall short of 3:1 for component boundaries. They are used only where state is also carried by fill and label: the active segment is filled, the selected chip changes fill and text colour, the note box is labelled by its text or "Add a note". The unknown-type edge (#3B5368) is therefore also decorative, because type is always given in words in the identity line. If the on-device review shows the outlines too faint, raise `border` to #5B7792 (about 3.4:1); the tokens are in one file. Muted text is never used below 11 sp. The stale data line is not dimmed, so it keeps the `textMuted` ratios above.

### 7.2 Targets and sizes

- The accessibility floor is a 48 dp x 48 dp touch target for everything tappable: ring (padded to 64 dp), Stop pills, Scan pill, chips, status and profile segments, Detail links, "Back to Home", port action word (48 dp high and wide), Settings delete controls, the note line, the notice dismiss. Use `minimumInteractiveComponentSize()` or `heightIn(min = 48.dp)`. The visual may be smaller: chips are 32 dp visual height; the status and profile segments and the Scan pill are 40 dp visual inside their 48 dp target; the Stop pill is as drawn with a 48 dp minimum height.
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

All screens must work at font scale 1.0 and 1.3 (2.0 is a graceful-degradation check only: no overlap, scroll where scrolling is intended). Portrait only.

- Home: the disc stays 172 dp (it is a graphic); the progress line wraps to two lines rather than clipping; the network line stays one line with ellipsis; the data line may take two lines; segment and chip labels never truncate. SCANNING at 1.3 on a Pixel 7 Pro is about 343 dp of header (18 top + 48 network line + 12 + 172 disc + 56 Stop + 27 progress + 10), leaving about 435 dp of list after the sticky header, about 7 rows; with chips kept visible for an active filter, about 6. Acceptance: at least three rows visible while scanning at 1.3 (10.2 test 1).
- Detail on a Pixel 7 Pro (about 892 x 412 dp, roughly 820 dp usable between system bars): pinned block at 1.3 with the note collapsed is about 403 dp (back 48, title 34, identity 22, data 20, links 48, status 48, note 48, scan bar 60, progress 21, gutters about 40), leaving about 417 dp: six port rows of about 62 dp. With the note field expanded to its 4-line maximum the block grows by about 90 dp, and the Custom ports field with an error adds about 82 dp more: about 575 dp worst case, leaving about 245 dp, so at least three rows. "Why this identity" opens a sheet (5.1), so no inline reasons enter this budget. Acceptance: collapsed note, at least six rows; note expanded plus Custom with an error, at least three.
- The 52 dp port column holds "65535" (about 40 dp at 1.3). "Open share" never wraps (about 62 dp at 1.3); the service column wraps instead.
- Status segments: "Investigated" at 12 sp x 1.3 needs about 70 dp inside a segment of about 90 dp, so it fits; if a device is narrower the label wraps to two lines and the segment grows.

## 8. Copy

Sentence case, active voice, no disclaimers. Plurals: "1 device", "13 devices". This spec owns all Home wording (group headers, progress, done summary, notices). The extended-discovery spec supplies data only: its strings in its 8.1 ("(this network), 14 devices") and 8.2 ("Found 14, probing 203/254", "Found 19") are void. Settings strings (including the extended-discovery section's validation and normalisation messages) belong to each feature's spec and are not repeated here. Group headers say "Home subnet" as a fixed word and never show the network name; the network name appears only in the network line (4.1). Group headers show no count.

| Where | String |
|-------|--------|
| Ring label (idle, hero) | "Scan" |
| Idle | "13 devices" / "Last scan 2 minutes ago" (relative time: "just now", "N seconds ago", "N minutes ago", "N hours ago", "yesterday", then the date) |
| Idle after a scan that found nothing | "No devices found" / "Last scan 2 minutes ago" |
| Done | "13 devices" / "Scanned in 12 s, 1 on another subnet" |
| Done, single subnet | "13 devices" / "Scanned in 12 s" |
| Done, nothing found | "No devices found" / "Scanned in 12 s" |
| Hero | "Nothing scanned yet" / "Tap to find what is on 10.0.3.0/24" |
| Hero, no network | "No network" / "Connect to Wi-Fi or Ethernet to scan" |
| Disc centre | count, "found" beneath |
| Stop pill | "Stop" |
| Progress, start of run (total not yet known) | "starting" |
| Progress, local sweep | "probing 171 of 254  7 s" |
| Progress, tail after the sweep (enrichment, neighbour dump) | "identifying 14 devices  9 s" |
| Progress, gateway probe | "probing 10.0.0.0/16 for gateways, 112 of 256  9 s" |
| Progress, extended range | "10.0.4.0/24 (2 of 4), probing 171 of 254" |
| Progress, between phases | "preparing next range  31 s" |
| Quick scan all | "Quick scanning" |
| Group header, local | "Home subnet 10.0.3.0/24" |
| Group header, other | "Other subnet 10.0.4.0/24" |
| Mark chip | "Interesting", "Investigated", "Ignored" |
| Row open ports | "9 open" |
| Search placeholder | "Search name, IP, vendor or MAC" |
| Filter chips | "All", "Interesting", "Investigated", "Ignored", "Unmarked" |
| Empty, hero | "Devices appear here as they are found" |
| Empty, scanning | "Looking for devices" |
| Empty, scanned none | "No devices found. Check the network and scan again." |
| Empty, search | "No devices match \"<query>\"" |
| Empty, filter | "No devices marked <label>" |
| Notice, network changed | "Network changed, scan stopped" |
| Notice, large network | "Large network (/16). Scanned your /24 only. Widen in Settings." |
| Notice, cap dropped ranges | "2 more ranges found, raise the cap in Settings" |
| Notice, extended discovery found nothing to sweep | "Extended discovery: no extra ranges to sweep" |
| Notice, gateway probe found nothing | "Gateway probe found no other ranges" |
| Notice, extended note SKIPPED_BIND_FAILED | "Other ranges skipped: could not bind to Wi-Fi" |
| Notice, extended note PHANTOM | "10.0.5.0/24 skipped: every address answered" |
| Notice, extended note TRUNCATED | "10.0.7.0/24 cut short: scan limit reached" |
| Notice, extended note CANCELLED | "Stopped at 10.0.6.0/24" |
| Notice, extended note EMPTY | "10.0.4.0/24: nothing found" |
| Notice, scan failed | "Scan failed: <reason>" |
| Notice, quick scan partial | The view model's existing string, unchanged ("Quick scan finished, N device(s) could not be scanned") |
| Notice, location | "Turn on Location to show the Wi-Fi name"; "Wi-Fi name stays hidden without location permission" |
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

The extended-discovery end notes (the two above plus the `ExtendedNote` states SKIPPED_BIND_FAILED, PHANTOM, TRUNCATED, CANCELLED and EMPTY, which are given user-facing wording here) are notices (4.4), not part of the done summary: they appear in the notice line after the run ends and stay until dismissed or the next scan starts. The progress and summary strings are produced by pure helpers (`progressLine`, `ScanSummaryText`) whose every state maps to a row of this table (10.1).

## 9. Scope

Changes (UI layer, `app/src/main/java/com/northwalessolutions/netprobe/ui/**` plus resources):

- Theme: `theme/Theme.kt` (tokens, role mapping, `NetProbeTypography`, shapes, composition locals), new `theme/Tokens.kt` and `theme/Motion.kt`. `res/values/themes.xml`, `MainActivity.kt` (`enableEdgeToEdge`) and the manifest attributes in 2.5. The two variable font files in `res/font` and the OFL text in `res/raw` are already placed by the controller (2.2).
- New components: `home/RadarBlock.kt`; pure helpers `RadarModes`, `ScanSummaryText`, `RelativeTime`, `GroupHeaders` (header text only), `blipOffset`, `actionWord`, `sheetLabel`, `identityWithType`; `TypeEdge`, `MarkChip`, `PinnedSegmented`, `ScanPill`, `StopPill`, `rememberReducedMotion`, `LocalReducedMotion`.
- Screens: `HomeScreen.kt` and `DetailScreen.kt` (thin wrappers) with new `HomeContent` and `DetailContent`; `HomeComponents.kt` (row, chips, network line, group headers, notice line), `DetailComponents.kt` (identity block, status segments, note line, scan bar, port rows, port sheet, reasons sheet), `VulnSheet.kt`, `SettingsScreen.kt`, `Common.kt` (`EmptyState` colours).
- `DeviceType.icon()` and the icon imports go when their last caller does.
- Existing tests updated to match: `HomeScreenTest.scanButtonAndFiltersArePresentAndPinned` (HERO hides chips and search at launch on a fresh install) and `scanIsDisabledWithAMessageWhenOffWifi` (the no-network string appears once, in HERO).

The only view-model change is a set of read-only additions to `HomeUiState`, exposing data the view model already holds or computes, with no new collection, coroutine or setting:

- `lastScanAt: Long?`: `max(lastDiscoveryRun)` over the network's hosts.
- `totalHosts: Int`: stored hosts for this network, unfiltered (stale included).
- `otherSubnetHosts: Int`: unfiltered, not stale, on another subnet.
- `scanStartedAt: Long?`: exposes the existing `latestRun` (set to the run id when a scan starts).
- `scanFinishedAt: Long?`: the completion time, recorded in view-model state when scanning goes false; with `scanStartedAt` it gives the scan duration, so a Detail round trip mid-scan still measures correctly.

`HomeUiState.rows` stays filtered and searched, and `status` keeps its existing null-when-idle behaviour; the UI latches the last non-null status (3.3).

Does not change:

- Behaviour of `HomeViewModel`, `DetailViewModel`, `ScanController`, `StatusFilter`, `HomeFilter`, `RowRules`, apart from the read-only fields above.
- Engines (`discovery`, `portscan`, `serviceid`, `identify`, `actions`, `vulns`), `netenv`, Room entities, DAOs, migrations, repositories, `Settings.kt` keys. `ActionProvider` and `ActionProviderTest` are untouched.
- Navigation routes and arguments, the scan job lifecycle (cancel on Stop or network change), permissions, and the manifest apart from the portrait lock in 2.5.
- Extended discovery behaviour: this spec only styles its progress line, headers and Settings section. If that feature has not merged when this ships, those pieces are absent, not stubbed. `HostRow.subnet` is a dependency of the grouping; until it lands the group key derives from `otherSubnet`, and the plan must not block on it.

Forbidden dependencies and actions (verbatim from the review): no new dependencies, specifically not `ui-text-google-fonts` (downloadable fonts reach Google at runtime), `material3-adaptive` or window-size-class, Paparazzi or Roborazzi (screenshots are `adb exec-out screencap` only), and no animation libraries. No subagent downloads or installs anything. The Plex files arrive per section 2.2. Engines, `ActionProvider`, `RowRules`, `HomeFilter`, DAOs and `Settings.kt` stay untouched, with the read-only field list above as the only VM change.

## 10. Tests and verification

### 10.1 JVM tests (pure helpers)

- `RadarModes.of`: scanning wins; no stored hosts and no last scan gives HERO; hosts present gives SETTLED; a finished scan with zero hosts gives SETTLED; a filtered-empty list is not HERO (`totalHosts` ignores the filter).
- `ScanSummaryText.of`: "Scanned in 12 s"; with other-subnet count; zero devices; singular "1 device".
- `RelativeTime.of`: boundaries at 59 s, 60 s, 59 min, 60 min, 23 h, 24 h, 2 days.
- `GroupHeaders`: exact header strings for local and other groups (fixed wording, no count, never the network name).
- `rowDataLine`: IP with identity; IP with MAC; name equals IP; unknown identity falls back to MAC; no "other subnet" suffix; the own-phone row uses the supplied `Build.MODEL` string.
- `edgeColor`: every `DeviceType` has a colour; the ten values are pairwise distinct; gateway overrides the type colour.
- `blipOffset`: deterministic per index; radius within [0.5, 0.88] of the disc radius; inside the disc; the first 64 points are at least 4 dp apart (guards the hash).
- `progressLine`: start, local, tail, gateway-probe, extended and between-phase states, with elapsed seconds in the two-space form.
- String coverage: every state that `progressLine` and `ScanSummaryText` can produce equals a string in the section 8 table (the table's strings are held as constants the test iterates), so a state without a string fails the build.
- `actionWord`: http, https, ftp give "Open"; ssh, telnet, vnc, rdp give "Connect"; smb gives "Open share"; null or unknown scheme gives "Copy". `sheetLabel`: "Copy IP:port" maps to "Copy IP and port"; the "No app installed" entry is filtered out.
- `identityWithType`: type label appended; not appended when the identity already ends with it; blank identity gives the type alone; UNKNOWN gives no label.
- Contrast: a test-only helper asserts every ratio in 7.1 meets its stated minimum, including the stale name and edge rows and the radar centre rows.

### 10.2 Compose UI tests (instrumented)

Tags: `radarBlock`, `radarRing`, `radarDisc`, `radarStop`, `radarCount`, `radarProgress`, `scanButton` (HERO circle), `filterRow`, `search`, `deviceList`, `groupHeader`, `detailHeader`, `detailScanBar`, `detailScanButton`, `statusSegments`, `note`, `portList`, `portAction`. One tag per node. Tests drive `HomeContent` and `DetailContent` with fake state and recording actions, and provide `LocalReducedMotion` where needed. Any test that waits in SCANNING sets `mainClock.autoAdvance = false` and advances time explicitly, because the infinite arm transition would otherwise keep the clock busy.

1. Pinned structure, Home: scroll `deviceList` to the end; `radarBlock`, `radarRing` or `radarStop`, and the network line keep identical `boundsInRoot`. The sticky group header stays at the top of the list. In SCANNING at `fontScale = 1.3f` with `DeviceConfigurationOverride.ForcedSize(412 x 820 dp)`, `deviceList` is tall enough for at least 3 rows (height at least 3 x 62 dp) and `radarStop` is not a descendant of `deviceList`.
2. Pinned structure, Detail: scroll `portList`; `detailHeader` and `detailScanBar` bounds are unchanged and `detailScanButton` remains clickable.
3. Three radar states from a fake state: HERO (disc and `scanButton` circle present, no chips, "Nothing scanned yet", the no-network string appears once); SCANNING (`radarDisc` 172 dp, `radarStop` present, `radarCount` shows the count, ring absent); SETTLED (`radarRing` 56 dp, summary text matches, no Stop). Chips and search: hidden in SCANNING with All and a blank query; chip row visible in SCANNING with the Interesting filter; search visible in SCANNING with a non-blank query.
4. Disc does nothing: click `radarDisc`, assert `stopScan` was not called; click `radarStop`, assert it was.
5. Reduced motion: with `LocalReducedMotion` set to true, the arm is static and the size change completes in one frame (test clock reports no pending animation). No test writes `Settings.Global`.
6. Row anatomy: edge colour exposed through a custom semantics property; the gateway row has the teal edge and "gateway" in its description; "This phone" has the muted edge and its data line shows the device model; a marked row shows its chip text; a stale row's data line is not dimmed.
7. Font scale (`DeviceConfigurationOverride.FontScale(1.3f)`, phone-sized configuration): on Detail, at least six `portAction` nodes displayed with the note collapsed; at least three with the note expanded and Custom selected with an invalid ports entry showing its error; the scan bar height is the same before and after a scan starts (no jump). On Home, Scan or Stop and every chip are reachable and not clipped.
8. Summary persistence: with `StateRestorationTester`, finish a scan, navigate away and back (or recreate the host); the done summary ("Scanned in 12 s") is still shown; start a new scan and it is cleared; a fresh state with no saved summary shows the idle text.
9. Typography: in each `tnum` style (`data`, `port`, `count`, `radarCount`), "1111" and "0000" measure the same width.

### 10.3 On-device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN, mandatory before handoff)

For each of font scale 1.0 and 1.3, capture a screenshot (`adb exec-out screencap -p`) of:

1. Cold start: the first frame after launch, showing no light window flash and dark status and navigation bars.
2. Home first launch (app data cleared): HERO with Scan in the middle.
3. Home scanning, early (few devices) and late (about 12 devices, blips visible, rows arriving).
4. Home scanning with a filter active (chips still visible), and with quick scan all running (indeterminate ring with "Stop").
5. Home settled with the summary text, and again after leaving and re-entering (the summary is still shown), and after a process restart (idle text).
6. Home with a search that matches nothing, a filter that matches nothing, and with no network.
7. Home with a notice line showing.
8. Home with the other-subnet group, sticky header mid-scroll.
9. Home with extended discovery on (range progress line), once that feature is merged.
10. Detail: top state, scrolled ports, note expanded, scanning (progress line visible), long-press port sheet, "Why this identity" sheet open, and Custom with an error and the note expanded at 1.3.
11. Vulnerability sheet with results and offline.
12. Settings: top, middle, bottom, including the extended discovery section.

Also record a short `adb shell screenrecord` of the disc growing and settling (and HERO to SCANNING), because a still cannot show a jump.

Behaviour checks, each recorded pass or fail: Stop pill stops a scan and the disc settles; tapping the disc does nothing; rotating the device does not rotate the app (portrait lock); backgrounding mid-scan stops the arm and resuming restarts it (if the Director confirms 11.1); animator scale 0 freezes the arm; TalkBack reads the ring, Stop pill and rows as in 7.3; janky-frame percentage from `adb shell dumpsys gfxinfo` under 5 percent during a scan.

Per the standing QA rule, Bugsy builds, installs on the device and smoke tests every flow; static review alone is not accepted, and a green test suite is not a substitute for looking at the screenshots.

## 11. Open items for the Director

(The font bundling item is closed: approved 2026-10-19, see 2.2. The group-header wording item is closed: fixed wording, no count, D13.)

1. Background behaviour of the arm. Recommendation: the arm and all radar animation stop while the app is not resumed (battery, nothing to look at); the scan itself is unaffected. Confirm, or say you want it to keep turning.
2. D12: chips and search collapse while the large disc is showing, but only with the filter All and a blank query. Confirm, or say they should always stay and the disc should be smaller.
3. D15: non-gateway routers keep the teal edge, or use a dimmer teal (#5FB8B0) so teal on an edge means the gateway alone.
4. Edge colours for COMPUTER, SERVER, CAMERA and PRINTER, and the outline strength of `border`: reviewed by the Director on the device screenshots.
5. Teal on "on" switches in Settings: keep, or use `mark` yellow. Reviewed on the device screenshots.
6. The items in section 12 marked for the Director's eye (group header without a count, HERO chips hidden, quick scan arc, own-phone model line) are confirmed on the device screenshots.
7. Error counting ("M errors" in the done summary) is deferred to engine work: `DiscoveryStatus` has no error count and this pass changes no engine. Raise it with the extended-discovery or next engine spec.

## 12. Not as drawn

Every deliberate difference from the agreed mockups, so the Director is never surprised.

| # | Mockup | This spec | Why |
|---|--------|-----------|-----|
| 1 | Progress copy "probing 171 of 254  7 s" | Same, two spaces | Reverted to the drawn form |
| 2 | Group headers drawn without a count | Same, no count | As drawn |
| 3 | `radar-layout.html` option B shows chips under the HERO disc | Chips and search hidden while the large disc shows, only with filter All and a blank query | Follows `radar-states.html` state 2; D12, open item 11.2 |
| 4 | HERO disc drawn 170 dp, #233447 outline and rings, 60 dp inner inset | 172 dp, #2A3E54, 58 dp, as in the states mockup | One disc for HERO and SCANNING so there is no jump |
| 5 | Filter chips about 24 dp visual | 32 dp visual in a 48 dp target | 48 dp is the accessibility floor; 32 dp is the Material default |
| 6 | Status segments, profile segments and Scan pill about 29 to 31 dp visual | 40 dp visual inside a 48 dp target | Same floor; the target is 48 dp, the visual is bigger than drawn but smaller than a full 48 dp block |
| 7 | "Back to Home" without a chevron; links at regular weight | Leading chevron; links weight 500 | Affordance |
| 8 | "This phone" line drawn "10.0.3.242  Pixel 7 Pro" | Shows `Build.MODEL` (for example "Pixel 7 Pro") | `RowRules.OWN_LINE` gives "Android device"; the UI substitutes the model, `RowRules` untouched |
| 9 | Quick scan all not drawn | Ring stroke dimmed to `border`, indeterminate `signal` arc over it, "Stop" inside, detail "Quick scanning" | Reuses the scanning-ring look; reviewed on device |
| 10 | COMPUTER, SERVER, CAMERA, PRINTER edges and teal "on" switches not drawn | Chosen values in 2.1, teal switches in section 6 | Reviewed on device screenshots, open items 11.4 and 11.5 |
| 11 | Stale rows not drawn | Edge at 0.6 alpha and name at 0.6 alpha only | Keeps stale IPs readable (7.1) |
| 12 | Arm drawn across the whole disc | Arm masked out of the centre 40 dp radius | Keeps the count and "found" readable (7.1) |
| 13 | HERO placeholder drawn as a group-header line | Same | As drawn; included here so it is not read as a deviation |
