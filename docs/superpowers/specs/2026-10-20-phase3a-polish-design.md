# NetProbe Phase 3A Polish Design (motion, "Open with...", port-scan identification, manual device details)

Date: 2026-10-20. Author: Ada (Engineering Lead). Status: revised after Gruff's review (`2026-10-20-phase3a-gruff-review.md`, ad01848) and the controller's binding rulings on its MAJOR findings; the response is the last section. The Director is away; the controller made the design decisions below as his stand-in, and every one of them that the Director has not ruled on himself is listed in section 10 so he can overturn it on return.
Baseline: master b37db04.
Related specs: `2026-10-19-radar-ui-design.md` (the "Radar spec": tokens, radar, Home and Detail layout, copy table), `2026-10-19-extended-discovery-design.md` (binding rules for off-subnet probes, 5.5), `docs/superpowers/plans/2026-10-19-phase2-final-review.md` (open minors and copy items).

The Director's words (2026-10-20):

> The radar animation isn't great. Perhaps look at improving that and any other animations in the app (or add them in places to make it fancier!). There are a couple of times where I've seen a non-standard HTTP port but it doesn't let me open it in browser. Perhaps the non-standard ports should allow me to say 'Open with...'. Some of the devices that show up seem obvious from the data that comes out of the port scans. Perhaps there's a way to get the device info from there, or a way that I can fill it in manually.

## Purpose and decisions

Four requests and the Phase 2 copy items the Director raised, in one phase:

1. Motion: a radar that reads as a radar, and quiet motion everywhere else (section 1).
2. "Open with..." on every port, and the right primary action on HTTP served from a port the app did not expect (section 2).
3. Port-scan findings become identification evidence (section 3).
4. The Director can set a device's name, type, vendor and model himself (section 4).
5. Phase 2 follow-ups: Detail bind wording, Forget network, Remove device, "Stopped after N s", stale dimming after a restart (section 5).

The standing rules of the Radar spec carry over unchanged: Scan, Stop and the Detail scan bar are pinned outside any scroll container; only the Stop pill stops a radar scan; teal means live; IBM Plex Sans and the Phase 2 tokens; dark only; portrait only.

### Decision table

| # | Decision | Source |
|---|----------|--------|
| D1 | Radar redesign: wide trailing sweep (140 degree tail, bright head line, linear turn), per-blip afterglow keyed on the arm angle, centre ring pulse when a device lands, outline breathing while scanning, spring settle at the end | Controller |
| D2 | Arm turn slowed from 2400 ms to 3000 ms | Ada, assumption A1 |
| D3 | Elsewhere: staggered Home row arrival, Home to Detail slide and fade (240 ms), Detail port rows arrive as found, chip and segment cross-fade, Scan pill morphs to Stop, notice line slides in | Controller |
| D4 | Added for "fancier": identity line cross-fade and type-edge colour change when an identity improves, smooth Detail progress line | Ada, assumption A1 |
| D5 | Compose animation APIs only; no new dependency; `LocalReducedMotion` makes everything instant and the arm static | Controller |
| D6 | When the banner or TLS peek says HTTP or HTTPS on any port, the primary action is Open with `http(s)://ip:port/` | Controller |
| D7 | Full and Custom port scans run an active probe ladder (TLS, then HTTP, then a GET; RTSP and RDP probes on their ports) on ports that said nothing passively, capped at 64 ports and 60 s of probe time per scan; Quick stays passive; a named list of ports is never actively probed | Ada (ladder), controller ruling on Gruff M3 and M4 (gate, budget, passive-only list) |
| D8 | Every port's long-press sheet has "Open with..." (system chooser, scheme guessed) and a "Try as" chip row: http, https, ssh, vnc, rdp, ftp, telnet, smb | Controller |
| D9 | "No app installed for scheme://" rows stay out of the sheet | Phase 2 ruling, unchanged |
| D10 | Port-scan results are identification evidence: banners, Server and X-Powered-By headers, HTTP titles and auth realms on any port, TLS subject CN and organisation, confirmed service names | Controller |
| D11 | After every port scan the host is re-identified; the stored identity is replaced when the new confidence is equal or higher | Controller ("higher"), Ada (ties), assumption A3 |
| D12 | 57 new rules in `identify_rules.json` (section 3.5); application rules set a new `software` value ("Runs Home Assistant" chip on Detail) and never set model or type above weight 40; seven existing rules are converted or lowered to match | Controller (list, ruling on Gruff M6), Ada (entries and weights) |
| D13 | "Why this identity" shows port evidence as "port 5000 httpTitle: ..." | Controller |
| D14 | An "Edit device" dialog (Name, Type, Vendor, Model) replaces the rename dialog; it opens from the name, the identity line and the Detail overflow | Controller, Ada (entry points), assumption A4 |
| D15 | Manual values override detected ones everywhere they show, persist across scans, and each field has "Reset to detected" | Controller |
| D16 | One Room migration, schema 1 to 2, adds twelve nullable columns: four on `hosts` (three overrides and `identSoftware`), two on `networks` (`lastRunAt`, `forgotten`), six on `ports` | Controller (host columns, `forgotten`), Ada (the rest), assumption A5 |
| D17 | Detail bind failure reads "Scan skipped: could not bind to Wi-Fi" | Director (Phase 2 copy item) |
| D18 | The range summary row stays hidden when no range was swept | Phase 2 ruling, unchanged |
| D19 | "Forget this network" (Home overflow, a soft delete until the Undo ends) and "Remove device" (Detail overflow; stops that host's scans and is skipped for the rest of a running radar scan), each with an Undo in the notice line and no confirmation dialog | Controller, ruling on Gruff M7, assumption A6 |
| D20 | A stopped or failed scan reads "Stopped after N s" | Director (Phase 2 copy item) |
| D21 | Stale dimming survives a restart: the id of the last completed run is persisted on the network row | Director (Phase 2 note B), Ada (completed runs only, Gruff Q1) |

## 1. Motion pass

Files: `ui/home/RadarBlock.kt`, `ui/home/RadarGeometry.kt`, `ui/home/HomeContent.kt`, `ui/home/HomeComponents.kt`, `ui/detail/DetailContent.kt`, `ui/detail/DetailComponents.kt`, `ui/Controls.kt`, `ui/Nav.kt`, `ui/theme/Motion.kt`.

### 1.1 Why the radar reads wrong today

Three causes, all in `RadarBlock.kt`:

- The wedge runs backwards. `rotationZ` turns clockwise, and the sweep gradient is brightest at 0 degrees and fades towards 70 degrees, which is clockwise of the head. So the faint edge leads and the bright edge trails: the eye reads a smear being dragged, not a beam sweeping.
- It is narrow and hard-edged. A 70 degree fade then a clear stop at 110 degrees gives a pie slice, not a phosphor trail.
- Nothing reacts to it. Blips fade in once and then sit still; the arm passing over them changes nothing, so the arm looks decorative.

The fixes below address all three, and add the two cues a real scope gives: something happens when a contact appears (ring pulse) and the scope is visibly "on" (breathing outline).

### 1.2 Motion tokens

All in `ui/theme/Motion.kt`, replacing or extending the existing `Motion` object. Values marked "unchanged" keep their Radar spec value.

| Token | Value | Use |
|-------|-------|-----|
| `ARM_TURN_MS` | 3000 (was 2400) | One full turn, `LinearEasing` |
| `TRAIL_DEG` | 140f | Length of the sweep tail behind the head |
| `ARM_HEAD_ALPHA` | 0.55f (unchanged) | Wedge alpha at the head |
| `HEAD_LINE_ALPHA` | 0.85f | Leading edge line |
| `HEAD_LINE_WIDTH` | 1.5 dp | Leading edge line |
| `AFTERGLOW_DEG` | 120f | Arm travel over which a blip decays after the arm passes (1000 ms at 3000 ms a turn) |
| `BLIP_REST_ALPHA` | 0.45f | Blip core alpha when the arm is far away |
| `BLIP_STATIC_ALPHA` | 1f | Blip core alpha under reduced motion (today's full strength) |
| `GLOW_REST_ALPHA` | 0.15f | Blip glow alpha at rest |
| `GLOW_LIT_ALPHA` | 0.45f | Blip glow alpha as the arm passes |
| `GLOW_LIT_SCALE` | 1.5f | Glow scale as the arm passes (scale about the blip centre) |
| `PULSE_MS` | 700 | Centre ring pulse; animated linearly, `LinearOutSlowInEasing` applied at draw time |
| `PULSE_ALPHA` | 0.35f | Pulse ring alpha at its start |
| `PULSE_STROKE` | 1.5 dp | Pulse ring stroke |
| `PULSE_MIN_GAP_MS` | 350 | A new pulse does not restart one younger than this |
| `BREATH_MIN` / `BREATH_MAX` | 0.10f / 0.20f | Disc outline tint towards `signal`, one cycle per arm turn |
| `SIZE_MS` | 360 (unchanged) | Ring to disc expand, `FastOutSlowInEasing` |
| `SETTLE_DAMPING` | 0.7f | Disc to ring settle, `spring(dampingRatio)` |
| `SETTLE_STIFFNESS` | 400f (`Spring.StiffnessMediumLow`) | Disc to ring settle, `spring(stiffness)` |
| `ARM_FADE_MS` | 200 | Arm and blips fade during the settle |
| `LABEL_FADE_MS` | 160 (unchanged) | Count and "Scan" cross-fade |
| `BLIP_IN_MS` | 300 (unchanged) | Newest blip batch fade-in |
| `ROW_ARRIVAL_MS` | 600 (unchanged) | Home row fade and 6 dp rise, existing `rowEasing` |
| `ROW_STAGGER_MS` | 40 | Delay step between rows that arrive in the same emission |
| `ROW_STAGGER_MAX_MS` | 200 | Cap on that delay |
| `NAV_MS` | 240 | Screen transitions, `FastOutSlowInEasing` |
| `NAV_ENTER_OFFSET` | 0.25f | Entering screen slides in from a quarter of the width |
| `NAV_EXIT_OFFSET` | 0.10f | Leaving screen slides a tenth of the width |
| `PORT_ROW_MS` | 240 | Detail port row fade and 6 dp rise, and placement of the rows below it |
| `SELECT_MS` | 160 | Chip, status segment and profile segment colour cross-fade, `FastOutSlowInEasing` |
| `PILL_MORPH_MS` | 220 | Scan pill to Stop pill, `FastOutSlowInEasing` |
| `PILL_SQUASH` | 0.88f at 90 ms | Pill `scaleX` keyframe in the morph |
| `NOTICE_IN_MS` | 200 | Notice line expand and fade in |
| `NOTICE_OUT_MS` | 160 | Notice line shrink and fade out |
| `IDENTITY_FADE_MS` | 240 | Identity line cross-fade when it changes |
| `EDGE_COLOUR_MS` | 300 | Type edge colour change |
| `PROGRESS_MS` | 250 | Detail progress line, `LinearEasing` (matches the 250 ms progress sample) |
| `STATIC_ARM_DEG` | 40f (unchanged) | Arm angle under reduced motion |

### 1.3 The sweep

The arm stays its own `graphicsLayer` rotated by the angle read inside the layer block (Radar spec 3.6). Only the brush and one line change.

- Direction. In layer coordinates the head is at 360 degrees (equivalently 0, the 3 o'clock line) and the tail runs counter-clockwise back to 220 degrees, so with a clockwise `rotationZ` the bright edge leads.
- Wedge brush: `Brush.sweepGradient` with these stops (position = degrees / 360, alpha of `signal`):

| Degrees | Position | Alpha |
|---------|----------|-------|
| 0 | 0f | 0 |
| 220 | 0.6111f | 0 |
| 260 | 0.7222f | 0.03 |
| 300 | 0.8333f | 0.09 |
| 330 | 0.9167f | 0.20 |
| 350 | 0.9722f | 0.38 |
| 359 | 0.9972f | 0.55 |
| 360 | 1f | 0.55 |

  The jump from 0.55 at 360 to 0 at 0 is the sharp leading edge. The stops live in a pure `ArmTrail.stops(): List<Pair<Float, Float>>` (position, alpha) so a JVM test pins them (9.1).
- Head line: one `drawLine` in the same cached layer, from the arm mask radius (`RadarGeometry.armMask`) to the inner edge of the disc outline, along the 0 degree direction, `signal` at `HEAD_LINE_ALPHA`, `HEAD_LINE_WIDTH`, `StrokeCap.Round`.
- The annulus mask (centre 40 dp cut out, inside the outline) is unchanged, so the count and "found" still sit on plain fill (Radar spec 7.1 contrast rows still hold).
- Turn: `rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(ARM_TURN_MS, easing = LinearEasing)))`. The transition is hoisted from `Arm` to the SCANNING branch of `RadarBlock`, because three consumers read the one angle (1.4, 1.6).

### 1.4 Blip afterglow

Each blip brightens as the arm passes over it and decays over the next 120 degrees of arm travel.

- Blip angle: computed once per blip in the `drawWithCache` block from `blipOffset(i, r)`: `atan2(y, x)` in degrees, normalised to [0, 360). Screen y points down, so this is the same clockwise angle the arm uses.
- Pure helper in `RadarGeometry.kt`:

```kotlin
/** 1 when the arm head is on the blip, falling to 0 at AFTERGLOW_DEG behind it; 0 ahead of the arm. */
fun afterglow(armDeg: Float, blipDeg: Float): Float   // d = floorMod(armDeg - blipDeg, 360); if (d >= AFTERGLOW_DEG) 0 else (1 - d / AFTERGLOW_DEG)^2
```

- Draw (per blip, per frame, draw phase only): `g = afterglow(angle(), blipDeg)`; core alpha `lerp(BLIP_REST_ALPHA, 1f, g)`; glow alpha `lerp(GLOW_REST_ALPHA, GLOW_LIT_ALPHA, g)`; glow drawn inside `scale(lerp(1f, GLOW_LIT_SCALE, g), pivot = p)`. Both alphas are then multiplied by the existing newest-batch fade and the overall blips alpha.
- Cost: the positions, angles and glow brushes are still built once per `found` or size change in `drawWithCache`. The `onDrawBehind` lambda reads `angle()`, so the blips redraw every frame while scanning: at most 64 blips, two circles each, no allocation. They are drawn in the shared live layer (1.16), which has its own `graphicsLayer`, so the per-frame redraw re-records only that layer, never the disc, the count or anything above them. There is no recomposition: the angle is read only in draw.

### 1.5 Ring pulse

When a device is found during SCANNING, a thin ring expands from the centre and fades. The pulse is gated in time and driven by a counter that only ever rises, never by `found` itself (controller ruling on Gruff M2).

- Find counter: `finds` (a remembered `Int`) is incremented by one each time `found` rises during SCANNING; that is all the existing `found` effect does for the pulse. `finds` never decreases within a scan; it resets to 0 when a new scan starts, and the reset never pulses.
- Driver: one `LaunchedEffect(mode)`, active only in SCANNING, collects `snapshotFlow { finds }`. For each new value it reads the frame clock (`val now = withFrameMillis { it }`) and asks the pure gate `PulseGate.shouldStart(nowMs = now, lastStartMs: Long?)`, which is true when no pulse has started this scan or `now - lastStartMs >= PULSE_MIN_GAP_MS`. When true: `lastStartMs = now`, `pulseCount++`, and `scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(PULSE_MS, easing = LinearEasing)) }`, where `scope = rememberCoroutineScope()` and `pulse = remember { Animatable(1f) }` (1 means idle).
- Why a pulse can never freeze: a skipped find launches nothing, so it cannot cancel a running pulse. A started pulse is only replaced by a newer started pulse, which itself runs to 1. Nothing keyed on `found` touches the `Animatable`.
- Draw (in the shared live layer, 1.16, reading `pulse.value` only in `onDrawBehind`): when `p < 1`, `e = LinearOutSlowInEasing.transform(p)`; a circle stroke of radius `lerp(maskRadius, discRadius * 0.95f, e)`, `PULSE_STROKE`, `signal` at `PULSE_ALPHA * (1 - e)`.
- When the mode leaves SCANNING no new pulse starts; one already running finishes.

### 1.6 Breathing outline

While SCANNING the disc outline tint breathes once per arm turn, from the same angle, so no second infinite transition exists.

- Pure helper: `breath(armDeg: Float): Float = BREATH_MIN + (BREATH_MAX - BREATH_MIN) * (0.5f + 0.5f * cos(toRadians(armDeg)))`.
- The outline moves out of the cached `DiscBackground` into the shared live layer (1.16), whose `onDrawBehind` reads the angle: stroke colour `lerp(baseStroke, signal, breath(angle()))` where `baseStroke` is the existing fraction-driven stroke colour. In HERO and SETTLED the outline is drawn with `breath = 0` (no angle is read).

### 1.7 Expand and spring settle

- Expand (SETTLED or HERO to SCANNING): unchanged, 360 ms `FastOutSlowInEasing` on `fraction`.
- Settle (SCANNING to SETTLED): `transitionSpec` branches on the state pair. `SCANNING isTransitioningTo SETTLED` gives `spring(dampingRatio = SETTLE_DAMPING, stiffness = SETTLE_STIFFNESS)`; every other pair gives the 360 ms tween. The natural frequency is 20 rad/s, so the ring undershoots by about 4.6 percent of the 116 dp travel (about 5 dp) and rests at the 0.01 visibility threshold in about 350 to 400 ms.
- Every reader of `fraction` is sorted, so the undershoot shows only on the ring itself:

| Value | Reads |
|-------|-------|
| `diameter`, `target`, stroke width | `fraction`, unclamped (this is the visible bounce) |
| `areaHeight`, `centreX`, Stop pill and progress `layout` height, pill `graphicsLayer` alpha, text column alpha (`1 - f`), disc fill and inner-ring alpha, stroke colour lerp | `f = fraction.coerceIn(0f, 1f)` |

  A negative `layout` height would throw, and alpha must stay within 0 to 1; the clamp makes both impossible.
- The ring's click is enabled when `transition.currentState == transition.targetState` (replacing `fraction == 0f`, which a spring may cross before it rests).
- During the settle the arm and blips stay composed and fade out over `ARM_FADE_MS`. The infinite transition lives while SCANNING or settling (`pillShown`) and is disposed when the settle ends, about 400 ms after the scan stops. The pulse and breathing stop at the settle start.
- `RadarBlock` reports `onBusyChange(true)` from SCANNING until the settle ends; `HomeContent` disables "Quick scan all" while busy, so the Quick arc and the arm are never composed together.

### 1.8 Home rows: staggered arrival

Unchanged from Radar spec 3.6 except for the stagger: rows not present when the scan began fade and rise; rows that land in the same list emission start one after another.

- Pure helper: `RowStagger.delays(newIds: List<Long>): Map<Long, Int>` gives the k-th new id (in list order) `min(k * ROW_STAGGER_MS, ROW_STAGGER_MAX_MS)`.
- `DeviceList` computes the new ids of each emission against the ids already shown and keeps the delay in a `remember(r.id)`; the row's `animateItem(fadeInSpec = tween(ROW_ARRIVAL_MS, delayMillis = delay, easing = rowEasing))` and the rise `Animatable` (`delay(delay)` then `animateTo`) use it.
- Initial load, filter changes and returning to the screen still never animate.

### 1.9 Screen transitions

`ui/Nav.kt` `NavHost` gets explicit transitions (navigation-compose 2.9.0 API, already a dependency):

| Transition | Spec |
|------------|------|
| `enterTransition` (Detail or Settings opens) | `slideInHorizontally { (it * NAV_ENTER_OFFSET).toInt() } + fadeIn`, both `tween(NAV_MS, easing = FastOutSlowInEasing)` |
| `exitTransition` (Home leaves) | `slideOutHorizontally { -(it * NAV_EXIT_OFFSET).toInt() } + fadeOut`, same tween |
| `popEnterTransition` (Home returns) | `slideInHorizontally { -(it * NAV_EXIT_OFFSET).toInt() } + fadeIn`, same tween |
| `popExitTransition` (Detail or Settings leaves) | `slideOutHorizontally { (it * NAV_ENTER_OFFSET).toInt() } + fadeOut`, same tween |

Under reduced motion all four are `EnterTransition.None` / `ExitTransition.None`. The window background is already `background` (Radar spec 2.5), so a fade never shows a light flash. Home's back stack entry leaves RESUMED during the transition, so the radar's infinite transition stops as before (D11 of the Radar spec).

The arm must not jump when that happens. The last drawn angle is kept in a remembered non-state holder (`remember { FloatArray(1) }`, written inside the arm's `graphicsLayer` block, so the write causes no recomposition). While not resumed, the arm and the afterglow are drawn at that angle; on resume the infinite transition animates from that angle to that angle plus 360, so the sweep continues where it stopped.

The transitions are declared in a stateless `NetProbeNavHost(nav, home, detail, settings)` that takes the three destinations as composable slots. `NetProbeNav` passes the real screens; the Compose test passes fakes (9.2 test 7), so no test runs a real discovery.

### 1.10 Detail port rows arrive as found

- When the Detail scan starts, `DetailContent` captures the set of ports already listed (as Home does with ids).
- A port row whose port was not in that set gets `Modifier.animateItem(fadeInSpec = tween(PORT_ROW_MS), placementSpec = tween(PORT_ROW_MS, easing = FastOutSlowInEasing), fadeOutSpec = null)` plus a 6 dp rise over `PORT_ROW_MS`. The placement spec matters here: ports are sorted by number, so a new port often lands between two existing rows, and the rows below it slide down instead of jumping.
- Rows present at scan start, and every row when no scan is running, get no animation.

### 1.11 Selection cross-fades

- Filter chips (`FilterPill`): fill, outline and label colour each `animateColorAsState(tween(SELECT_MS, easing = FastOutSlowInEasing))`.
- `PinnedSegmented` (status and scan profile): the active segment fill and each label colour animate the same way. The label weight change (status: 400 to 500) is not animated; it switches at the start.

### 1.12 Scan pill morph

`ScanPill` (Detail):

- Fill alpha 1 to 0, outline alpha 0 to 1, label colour `background` to `signal` (and back for Stop to Scan), all `tween(PILL_MORPH_MS, easing = FastOutSlowInEasing)`.
- Width: a `scaleX` squash through `graphicsLayer`, `keyframes { durationMillis = PILL_MORPH_MS; 1f at 0; PILL_SQUASH at 90; 1f at PILL_MORPH_MS }`. Layout width stays max(Scan, Stop) as today, so the profile control beside it never shifts (Radar spec 5.1 item 8).
- The pill stays one node with `detailScanButton`; only the visible label changes.

### 1.13 Notice line

Home `NoticeLine` is wrapped in `AnimatedVisibility(visible = text != null, enter = expandVertically(tween(NOTICE_IN_MS)) + fadeIn(tween(NOTICE_IN_MS)), exit = shrinkVertically(tween(NOTICE_OUT_MS)) + fadeOut(tween(NOTICE_OUT_MS)))`. The last non-null text is latched so the exit animates the old words, not a blank line. The 200 ms expand also softens the "late notice shifts rows under the finger" problem (Phase 2 M8): the list slides rather than jumps.

### 1.14 Identity changes

When a port scan improves an identity (section 3), the change is shown, not snapped:

- Detail identity line: `Crossfade(targetState = identity line text, animationSpec = tween(IDENTITY_FADE_MS))`.
- Home row data line: the same cross-fade on the identity part, keyed on the row id so a scrolled-in row never animates.
- Type edge (Home row): `animateColorAsState(edgeColor, tween(EDGE_COLOUR_MS))`.
- Detail progress line: the 2 dp `LinearProgressIndicator` progress is `animateFloatAsState(fraction, tween(PROGRESS_MS, easing = LinearEasing))`, so it glides between the 250 ms samples instead of stepping.
- Detail progress counts identification too (Gruff m16): fraction = (ports probed + identifies finished) / (ports total + open ports found). While the port sweep runs the text is unchanged ("312 of 65535, found 4"); once the sweep is done and identifies are still running it reads "identifying 3 ports" ("identifying 1 port"). `DetailViewModel` keeps a pending-identify count for this. The pill reads Stop until both are done.

### 1.15 Reduced motion

`LocalReducedMotion` (Radar spec 3.7) true means:

| Animation | Reduced behaviour |
|-----------|-------------------|
| Arm | Static at `STATIC_ARM_DEG`, no infinite transition |
| Afterglow | None: blips drawn at `BLIP_STATIC_ALPHA`, glow at `GLOW_REST_ALPHA`, no scale |
| Ring pulse | Never drawn |
| Breathing | `breath = 0` |
| Expand and settle | Duration 0, no spring |
| Row and port row arrival, stagger | None |
| Screen transitions | `None` |
| Chip, segment, pill, notice, identity, edge, progress | Instant (`snap()`) |

Nothing is lost informationally; counts, text and state still update.

### 1.16 Performance rules (carried from Radar spec 3.6)

- One infinite transition on Home, in the SCANNING-or-settling branch, resumed and not reduced. It feeds the arm layer, the blip afterglow and the outline breathing by lambda; none of them reads the angle during composition.
- Rule (controller ruling on Gruff M1): every node whose draw reads a per-frame value has its own layer, so a per-frame draw never re-records the disc display list or anything above it. The disc box stacks three layers:
  1. `DiscBackground` (fill, inner rings, HERO dots) with its own `Modifier.graphicsLayer()`, recorded only when `f` or the size changes.
  2. The arm, rotated in its `graphicsLayer` block as today (a RenderThread property change, no re-record).
  3. One shared live layer: a `Spacer` with `Modifier.graphicsLayer()` then `drawWithCache`, drawing the breathing outline, the pulse ring and the blips, in that order, above the arm. Its `onDrawBehind` reads the angle and `pulse.value`, so it alone is re-recorded per frame.

  The count, "found" and the Stop pill sit outside all three and are never re-recorded by the sweep. The single `fraction` design for size is kept. The rule goes on the plan's review checklist.
- `drawWithCache` for everything static; per-frame work happens only in the live layer's `onDrawBehind` and in the arm's `graphicsLayer`.
- Recomposition: `RadarBlock` already recomposes every frame of the expand and the settle, because `fraction` is read in composition for sizes and offsets (existing, sanctioned by the Radar spec). In steady SCANNING it must not recompose per frame; the Compose test checks that window only (9.2 test 4).
- Quick scan all's arc keeps its own infinite transition. Quick scan all is disabled until the settle ends (1.7), so the arc is never composed with the arm.
- Janky frames under 5 percent during a scan on the Pixel (`dumpsys gfxinfo`), as before (deferred check, 9.4).

### 1.17 Test hooks

Compose tests cannot read a draw-phase value, so the radar exposes test-only semantics properties on `radarBlock`, all set from composition state (never from the per-frame angle):

| Key | Type | Meaning |
|-----|------|---------|
| `ArmSpinningKey` (exists) | Boolean | Infinite transition composed and turning |
| `RadarFractionKey` | Float | Current `fraction` (already read in composition, 1.16) |
| `PulseCountKey` | Int | Number of pulses started this scan |
| `PulseActiveKey` | Boolean | A pulse is animating (`pulse.value < 1`), through `derivedStateOf` |
| `BreathingKey` | Boolean | Outline breathing active |

Rows and port rows expose `ArrivalDelayKey: Int` (the stagger delay, or -1 for "does not animate"). Every motion test, not only the radar tests, sets `mainClock.autoAdvance = false` (9.2).

## 2. "Open with..." for any port

Files: `engine/serviceid/ServiceIdentifier.kt`, `engine/serviceid/TlsPeek.kt`, `engine/serviceid/BannerParsers.kt`, `engine/serviceid/ServiceInfo.kt`, `engine/discovery/HttpTitle.kt`, `actions/ActionProvider.kt`, new `actions/PortUris.kt`, `ui/detail/DetailText.kt`, `ui/detail/DetailComponents.kt`.

### 2.1 Why the Director could not open his HTTP port

`ServiceIdentifier` only speaks HTTP to a fixed set (80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400) and only speaks TLS to 443, 8443, 7443, 8006, 5001. Any other port gets a passive read; an HTTP server says nothing until asked, so the port stays "Unknown" at port-table confidence, `ActionProvider.kindFor` finds no kind, and the row's word is "Copy". The action side is right; the detection side never tells it the port is HTTP.

### 2.2 Probe ladder (ServiceIdentifier)

The ladder is profile-gated and budgeted (controller ruling on Gruff M3 and M4). All sockets inherit the scan's `BoundNetwork` as today (off-subnet scans are bound, extended spec 5.5; `BannerReader`, `TlsPeek` and `HttpTitleFetcher` already read `currentBinder()`).

`ServiceIdentifier.identify(ip, port, evidence, mode: ProbeMode)`, with `sealed interface ProbeMode { object Passive; class Active(val budget: ActiveProbeBudget) }`.

#### 2.2.1 Profile gate

| Scan | Mode | What is sent |
|------|------|--------------|
| Detail Quick, and every host of Quick scan all | `Passive` | Exactly what is sent today: a passive read on `SPEAKS_FIRST` and unknown ports, HEAD on the `HTTP` set, a TLS peek on the `TLS` set. No fallback between sets, no GET, no RTSP or RDP probe, nothing more on a silent unknown port |
| Detail Full and Custom | `Active` | Today's steps, then the active steps below, under one `ActiveProbeBudget` per scan |

Quick adds nothing to what the app sends today. Quick scan all always uses Quick, so it never runs the ladder.

#### 2.2.2 Passive-only ports

Named constants in `ServiceIdentifier`, applied in every mode:

```kotlin
/** Never sent an active probe: raw print, LPD, Cisco SCCP, X11, and the mail ports that greet first (banner only). */
val PASSIVE_ONLY: Set<Int> = setOf(25, 110, 143, 515, 2000, 2001) + (6000..6063) + (9100..9107)
/** IPP: a plain HEAD is safe and useful; never a TLS ClientHello, never a GET. */
val HEAD_ONLY: Set<Int> = setOf(631)
```

A `PASSIVE_ONLY` port gets the passive read and nothing else, in any profile; its result is whatever the read gave (or the port-table name at PORT_TABLE). Port 631 gets one HEAD in Full and Custom (counted against the budget) and the passive read in Quick. A raw print port therefore never receives a ClientHello or a `HEAD /`, so nothing is ever printed.

#### 2.2.3 Active steps (Full and Custom only)

Per open port, each step only if the previous did not confirm a service:

| Port set | Today's step (both modes) | Active steps |
|----------|---------------------------|--------------|
| `SPEAKS_FIRST` (21, 22, 25, 110, 143, 587, 2222) | Passive read | None |
| `HTTP` (unchanged list) | HEAD | If no HTTP status line: TLS peek |
| `TLS` (unchanged list) | TLS peek | If no handshake: HEAD |
| `RTSP` (554, 8554), new | Passive read | RTSP OPTIONS |
| `RDP` (3389), new | Passive read | X.224 connection request |
| `HEAD_ONLY` (631) | Passive read | HEAD only |
| Every other port not in `PASSIVE_ONLY` | Passive read (`classifyUnknown`) | Only when the read returned zero bytes: TLS peek, then HEAD if no handshake |

"Zero bytes" is exact: a port that sent any byte, printable or not, spoke first and keeps today's `classifyUnknown` result; it never goes on to TLS.

Then, for any port confirmed HTTP by a HEAD or reached by a TLS handshake (outside `HEAD_ONLY`): one GET of `/` through `HttpTitleFetcher.fetch(ip, port, tls)` (2000 ms, 64 KB, one same-host redirect, `NO_PROXY`, bound) to read the title and headers (section 3.1). A TLS-set port that completes the handshake is named "HTTPS" as today. An unknown port that completes a handshake is named "HTTPS" when its GET returns an HTTP status line and "TLS" when it does not. A plain port whose HEAD returns `HTTP/` is named "HTTP".

TLS goes before HEAD on unknown ports on purpose: a plain HEAD sent to a TLS port often comes back as an HTTP 400 ("plain HTTP request sent to HTTPS port"), which would be misread as HTTP. A TLS ClientHello sent to a plain HTTP server fails fast.

- RTSP OPTIONS: send `OPTIONS rtsp://<ip>:<port>/ RTSP/1.0\r\nCSeq: 1\r\n\r\n`; a reply starting `RTSP/1.0` confirms "RTSP"; its `Server` header fills `product`/`version` via `BannerParsers.serverHeader` and the full value goes to `server` (3.1). Pure parser `BannerParsers.rtspReply(text): ServiceInfo?`.
- RDP: send the 19-byte X.224 Connection Request `03 00 00 13 0E E0 00 00 00 00 00 01 00 08 00 03 00 00 00`; a reply whose bytes 0 and 1 are `03 00` and byte 5 is `D0` (Connection Confirm) confirms "RDP". Pure parser `BannerParsers.rdpConfirm(bytes): ServiceInfo?`.

#### 2.2.4 Budget

```kotlin
class ActiveProbeBudget(val maxPorts: Int = MAX_ACTIVE_PORTS, val maxMs: Long = MAX_ACTIVE_MS, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    fun tryEnter(port: Int): Boolean      // false once maxPorts ports have entered, or once spentMs >= maxMs
    fun allowsStep(): Boolean             // spentMs < maxMs
    suspend fun <T> step(block: suspend () -> T): T?   // null without running when !allowsStep(); else runs, adds clock() delta to spentMs
}
const val MAX_ACTIVE_PORTS = 64
const val MAX_ACTIVE_MS = 60_000L
```

Accounting:

- One budget per Detail Full or Custom scan, created when the scan starts and shared by its identifies.
- A port enters the active ladder through `tryEnter` before its first active step. Entering counts once per port, whatever steps it then runs. Ports enter in the order their identifies start, which follows the scan's port order. `PASSIVE_ONLY` ports, ports that spoke passively, and today's steps (the passive read, HEAD on the `HTTP` set, TLS on the `TLS` set) never enter and never spend.
- Every active step runs through `step`, which checks `allowsStep()` before starting and afterwards adds that step's wall time (clock after minus clock before) to `spentMs` (an `AtomicLong`). With up to 8 identifies in parallel, the 60 s is summed probe time, not wall time.
- A step that has started is allowed to finish. Each step is bounded by its own timeout (2 s at most), so `spentMs` can exceed 60 s by at most 8 x 2 s.
- A port refused entry, or whose next step is refused, keeps what it already has: its passive or today's-step result if any, otherwise `ServiceInfo(portTable.nameFor(port), confidence = PORT_TABLE)`, that is open and unknown. It is still stored and still gets "Open with..." and Try as.
- The budget is never shared between scans and never persisted.

Worst case: a Full scan of a host that answers on every port adds at most about 76 s of summed probe time to today's cost, instead of hours.

#### 2.2.5 Timeouts and Stop

Timeouts are named constants in `ServiceIdentifier`: `BANNER_TIMEOUT_MS = 1000` (the `BannerReader` default), `TLS_TIMEOUT_MS = 1500` (the `TlsPeek` default), `HTTP_FETCH_TIMEOUT_MS = 2000` (the `HttpTitleFetcher` default). Identifies still run at most `MAX_PARALLEL_IDENTIFIES = 8` at a time.

A blocking `java.net.Socket` read on Android ignores thread interrupts, so `runInterruptible` alone leaves Stop waiting for the socket timeout (Gruff m8). `BannerReader`, `TlsPeek` and `HttpTitleFetcher` therefore register the socket (or `HttpURLConnection`) and close or `disconnect()` it from `invokeOnCancellation` (through a `suspendCancellableCoroutine` wrapper around the blocking call). A JVM test against a silent local `ServerSocket` asserts cancellation completes within 300 ms (9.1).

### 2.3 Actions (ActionProvider and PortUris)

New pure `actions/PortUris.kt`:

```kotlin
object PortUris {
    val TRY_AS = listOf("http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb")
    fun uri(scheme: String, ip: String, port: Int): String
    fun guess(port: Int, service: ServiceInfo): String?   // the scheme the app believes the port speaks, or null
}
```

`uri` forms: http, https, ftp give `scheme://ip:port/`; smb gives `smb://ip/` on 139 and 445 and `smb://ip:port/` elsewhere; ssh, vnc, rdp, telnet give `scheme://ip:port`.

`guess`, first match wins:

1. Service name HTTPS: `https`. A bare "TLS" (handshake, no HTTP answer) is not a guess: 993, 995, 465, 636, 853, 8883 and Cast's 8009 must not get "Open in browser" (Gruff m6).
2. Service name HTTP: `http`.
3. Service name SMB, SSH, TELNET, VNC, RDP, FTP: that scheme in lower case.
4. The existing port fallback in `kindFor` (80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000 http; 443, 8443 https; 445, 139 smb; 22, 2222 ssh; 23 telnet; 5900 to 5910 vnc; 3389 rdp; 21 ftp).
5. Otherwise null.

`ActionProvider.actionsFor` becomes:

- When `guess` is not null: `OpenUri` with the existing label for that kind and `uri(guess, ...)` (or the "No app installed for scheme://" entry, filtered from the sheet as today). This is how D6 lands: a port confirmed HTTP or HTTPS by banner or TLS peek gets `guess` http or https, whatever its number, so its primary is Open.
- Always: `OpenWith(uri(guess ?: fallback, ip, port))`, where `fallback` is https when the service name is TLS or `certCn` is present, and http otherwise. Unknown ports default to http because a non-standard web UI is the common case on a home or office LAN.
- Then "Copy IP" and "Copy IP:port" as today.

`primary` is unchanged in shape: the first `OpenUri` with a handler. `kindFor` delegates to `PortUris.guess`, so there is one table, and "TLS" no longer maps to HTTPS.

New `ActionProvider.tryAs(ip, port): List<Pair<String, String>>` returns (scheme, uri) for each scheme in `PortUris.TRY_AS` whose uri `resolver.hasHandler` accepts, in `TRY_AS` order. `PortRow` gains `tryAs`, filled by the view model, so the sheet never offers a scheme that would open an empty chooser (Gruff m7, and D9).

The "Try as" chips fire `DeviceAction.OpenWith(uri)` for the pairs in `PortRow.tryAs`. `AndroidActionRunner` already wraps `OpenWith` in `Intent.createChooser`. The manifest `<queries>` block already lists all eight schemes, so no manifest change.

### 2.4 Port sheet

Order (long-press on a port row, `PortSheet`):

1. Title "<ip>:<port> <service>" (unchanged).
2. Remaining `OpenUri` rows with a handler (unchanged).
3. "Open with..." in `signal` (always present now).
4. "Try as" (only when `PortRow.tryAs` is not empty; the row is omitted otherwise): a `caption` / `textMuted` label, then one horizontally scrolling row of up to eight chips, one per scheme with an installed handler (the filter-chip visual: fully round, 1 dp `border`, `chip` / `textSecondary`, 32 dp visual in a 48 dp target, 8 dp gap). Tag `tryAs:<scheme>`. Description "Open <ip>:<port> as <scheme>". Tapping a chip closes the sheet and opens the chooser.
5. "Copy IP", "Copy IP and port" (unchanged wording).
6. "Look up vulnerabilities for this service" in `signal` (unchanged).

The sheet is a `ModalBottomSheet`, so it scrolls if it needs to; it contains no Scan or Stop control. Row word and description helpers (`actionWord`, `actionDescription`, `sheetLabel`, `sheetActions`) are unchanged; `sheetActions` now always yields the `OpenWith` row.

## 3. Identification from port-scan evidence

Files: `engine/serviceid/ServiceInfo.kt`, `engine/identify/IdentifyRules.kt`, `engine/identify/DeviceIdentifier.kt`, `engine/identify/DeviceIdentity.kt` (`HostEvidenceBuilder`), `res/raw/identify_rules.json`, `data/Repositories.kt`, `data/Daos.kt`, `ui/detail/DetailViewModel.kt`, `ui/home/HomeViewModel.kt`.

Today a port scan already re-runs identification (`DetailViewModel.scanHostOn`, `HomeViewModel.quickScanHostOn`), but the only port evidence the rules can see is the set of open port numbers and the SSH banner on port 22. Everything else a port scan learns (the Server header, the page title, the certificate) is either thrown away or folded into a free-text `detail`.

### 3.1 Evidence captured per port

`ServiceInfo` gains six nullable fields, all default null so every existing constructor call compiles:

```kotlin
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null, val detail: String? = null,
                       val confidence: Confidence, val title: String? = null, val server: String? = null, val realm: String? = null,
                       val poweredBy: String? = null, val certCn: String? = null, val certOrg: String? = null)
```

| Field | Source |
|-------|--------|
| `title` | `<title>` of the GET (`HttpTitleParser.title`), any HTTP or HTTPS port |
| `server` | Full `Server` header of the GET or HEAD, or of the RTSP OPTIONS reply |
| `realm` | `WWW-Authenticate` realm (`HttpTitleParser.realm`) |
| `poweredBy` | `X-Powered-By` header; `HttpPeek` gains `poweredBy` |
| `certCn` | Subject CN of the leaf certificate |
| `certOrg` | Subject O of the leaf certificate |

`TlsPeek.certNames` becomes `TlsPeek.certInfo(...): CertInfo?` with `data class CertInfo(val cn: String?, val org: String?, val names: List<String>)`; `names` (CN plus SANs) still forms the existing "cert ..." detail. The O value is read from `subjectX500Principal.name` with the same regex style as CN (`O=([^,]+)`). All values are trimmed and cut to 120 characters.

Storage: six new nullable columns on `ports` (section 6). Every path that writes or reads a port carries all six (Gruff M5), or a radar scan would re-identify without the port evidence and drop what the port scan just set:

| Path | Change |
|------|--------|
| `PortRepository.upsertOpen` and `mergePort` (write) | Merge the six fields field by field: a new non-null value wins, a null never erases a stored one. The service name and confidence never go down: a PORT_TABLE result never replaces a CONFIRMED name (a timed-out HEAD on a busy NAS leaves "HTTP" CONFIRMED); a new CONFIRMED name still replaces an old one |
| `PortRepository.servicesFor` (read, used by discovery, Detail and Quick scan all identification) | Builds `ServiceInfo` with all eleven fields |
| `DetailViewModel` port row mapping (read, `PortRow.service`) | Builds `ServiceInfo` with all eleven fields, so `PortUris.guess` and the row sub-line see them |
| `HostEvidenceBuilder.build` | Merges the six fields with the same never-erase rule |

A repository-level test pins both failure modes (9.3 test 3).

### 3.2 Rule fields

`Rule` gains `val onPorts: List<Int>? = null` and `val software: String? = null`. When `onPorts` is set, only port evidence from those ports is considered for that rule, and discovery-time evidence is not. `software` is described in 3.6.

| Field | Candidates, in order (first match wins, one apply per rule) | Reason line |
|-------|------------------------------------------------------------|-------------|
| `httpTitle` (extended) | Discovery `host.httpTitle` (unless `onPorts`), then each port's `title`, ports ascending | "httpTitle: X" or "port P httpTitle: X" |
| `httpServer` (extended) | Discovery `host.httpServer` (unless `onPorts`), then each port's `server` (HTTP, HTTPS and RTSP) | "httpServer: X" or "port P httpServer: X" |
| `httpRealm` (extended) | Discovery `host.httpAuthRealm` (unless `onPorts`), then each port's `realm` | "httpRealm: X" or "port P httpRealm: X" |
| `sshBanner` (extended) | `product` and `detail` of every port whose service name is SSH (was port 22 only) | "port P sshBanner: X" |
| `poweredBy`, new | Each port's `poweredBy` | "port P poweredBy: X" |
| `certName`, new | Each port's `certCn` | "port P certName: X" |
| `certOrg`, new | Each port's `certOrg` | "port P certOrg: X" |
| `portBanner`, new | Each port's `listOfNotNull(product, version, detail).joinToString(" ")` | "port P portBanner: X" |
| `service`, new | Name of each port whose `confidence` is CONFIRMED | "port P service: X" |

Existing fields (`mdnsType`, `mdnsTxt`, `upnp*`, `snmpSysDescr`, `hostname`, `netbiosName`, `ports`, `anyPort`, `gateway`, `oui`) are unchanged. Because the three `http*` fields now also see port evidence, the existing title, server and realm rules (title-synology, title-openwrt, title-bsd-fw, title-hass, title-proxmox, title-plex, title-pihole, title-unifi, title-cameras, server-cameras, realm-routers) fire on port-scan findings with no edit to their matching. Rule-level `$1` substitution and `MAX_VALUE_LEN` are unchanged. `CANONICAL_VENDORS` gains the router vendors so `$1` from a page title is spelled one way: tp-link to TP-Link, netgear to NETGEAR, asus to ASUS, linksys to Linksys, zyxel to Zyxel, huawei to Huawei, zte to ZTE, sagemcom to Sagemcom, technicolor to Technicolor, tenda to Tenda. Four of them (title-hass, title-plex, title-pihole, title-unifi), with port-hass and port-plex, are application rules and are converted in 3.6.

### 3.3 When the identity is written

- Discovery runs: unchanged, always write (a re-used IP must be able to lose an old identity; discovery already includes stored port evidence).
- After every port scan (Detail scan, every host of Quick scan all): re-identify from the full evidence as today, then write only when the new confidence is equal to or higher than the stored `identConfidence`. Equal is included so a scan at the same confidence can still add a model or a port reason line. New repository call `HostRepository.setIdentityIfNotWeaker(hostId, identity)`: one `UPDATE hosts SET ... WHERE id = :id AND identConfidence <= :c`, so it is atomic.
- What the guard protects: with the read paths fixed (3.1), stored evidence only grows, so a port scan's result is normally never weaker than the stored one. The guard covers the remaining window, a discovery write racing a port scan's write for the same host, and is kept because it is one `WHERE` clause. It is not the fix for identity drops; 3.1 is.
- Manual overrides (section 4) are separate columns and are never touched by either path.

### 3.4 "Why this identity"

`ReasonsSheet` lists `identReasons` as today; port reasons now carry the port, for example:

- "port 5000 httpTitle: DiskStation - Synology DiskStation"
- "port 443 certOrg: Ubiquiti Inc."
- "port 22 sshBanner: Ubuntu-3ubuntu13"

When any manual override is set, the first line of the sheet is the "Set by you" line (section 4.4). Reasons stay cut to 80 characters of matched text, as today.

### 3.5 New rules

Weights follow the existing scale: 100 direct extraction, 95 near-certain, 90 product-specific title or certificate, 80 strong vendor evidence, 70 typical, 60 and below hints. A rule that sets only `os` or `vendor` leaves the other slots to other rules. Platform and appliance rules (the box itself: DSM, SRM, QTS, TrueNAS, Unraid, OMV, ESXi, Proxmox, iDRAC, iLO, Hue Bridge, UniFi OS consoles, FRITZ!Box and other routers, printers, cameras, Tasmota and Shelly firmware) set vendor, model and type at platform weights. Application rules (software that runs on some box) set `software` and never set model or type above weight 40 (3.6). Appended to `identify_rules.json` after `ssh-dropbear` and before `gateway`, in this order (exact entries):

```json
{"id":"p-dsm-title","field":"httpTitle","onPorts":[5000,5001],"pattern":"Synology|DiskStation","vendor":"Synology","type":"NAS","weight":90,"sample":"DiskStation - Synology DiskStation"},
{"id":"p-dsm-cert","field":"certOrg","pattern":"^Synology","vendor":"Synology","type":"NAS","weight":80,"sample":"Synology Inc."},
{"id":"p-dsm-ftp","field":"portBanner","onPorts":[21],"pattern":"Synology","vendor":"Synology","type":"NAS","weight":80,"sample":"Synology 220 Synology FTP server ready."},
{"id":"p-srm-title","field":"httpTitle","pattern":"Synology Router|\\bSRM\\b","vendor":"Synology","model":"Synology Router","type":"ROUTER","weight":95,"sample":"Synology Router - SRM"},
{"id":"p-unifi-os","field":"httpTitle","pattern":"UniFi OS","vendor":"Ubiquiti","model":"UniFi OS console","weight":80,"sample":"UniFi OS"},
{"id":"p-unifi-cert","field":"certName","pattern":"^UniFi$","software":"UniFi Network","weight":40,"sample":"UniFi"},
{"id":"p-hue-title","field":"httpTitle","pattern":"hue personal wireless lighting","vendor":"Philips Hue","model":"Hue Bridge","type":"IOT","weight":90,"sample":"hue personal wireless lighting"},
{"id":"p-hue-cert","field":"certOrg","pattern":"Philips Hue","vendor":"Philips Hue","model":"Hue Bridge","type":"IOT","weight":90,"sample":"Philips Hue"},
{"id":"p-plex-http","field":"service","onPorts":[32400],"pattern":"^HTTPS?$","software":"Plex Media Server","type":"SERVER","weight":40,"sample":"HTTP"},
{"id":"p-jellyfin","field":"httpTitle","pattern":"Jellyfin","software":"Jellyfin","type":"SERVER","weight":40,"sample":"Jellyfin"},
{"id":"p-emby","field":"httpTitle","pattern":"\\bEmby\\b","software":"Emby Server","type":"SERVER","weight":40,"sample":"Emby"},
{"id":"p-proxmox-cert","field":"certOrg","pattern":"PVE Cluster","model":"Proxmox VE","type":"SERVER","weight":90,"sample":"PVE Cluster Node"},
{"id":"p-cups-server","field":"httpServer","pattern":"^CUPS","software":"CUPS","type":"PRINTER","weight":40,"sample":"CUPS/2.4 IPP/2.1"},
{"id":"p-printer-title","field":"httpTitle","pattern":"^(HP|Brother|EPSON|Canon|Lexmark|Kyocera|Xerox)\\b.*(LaserJet|OfficeJet|DeskJet|ENVY|MFC|DCP|HL-|ET-|WF-|XP-|PIXMA|imageCLASS|ECOSYS|WorkCentre|VersaLink)","vendor":"$1","type":"PRINTER","weight":90,"sample":"HP OfficeJet Pro 9010 series"},
{"id":"p-hp-server","field":"httpServer","pattern":"HP HTTP Server","vendor":"HP","type":"PRINTER","weight":80,"sample":"HP HTTP Server; HP OfficeJet Pro 9010 series"},
{"id":"p-rtsp-vendor","field":"httpServer","onPorts":[554,8554],"pattern":"(Hikvision|Dahua|Reolink|Amcrest|Axis|Hipcam)","vendor":"$1","type":"CAMERA","weight":85,"sample":"Hikvision/1.0"},
{"id":"p-rtsp-service","field":"service","pattern":"^RTSP$","type":"CAMERA","weight":60,"sample":"RTSP"},
{"id":"p-router-title","field":"httpTitle","pattern":"\\b(TP-Link|NETGEAR|ASUS(?!TOR)|Linksys|Zyxel|Huawei|ZTE|Sagemcom|Technicolor|Tenda)\\b","vendor":"$1","type":"ROUTER","weight":75,"sample":"NETGEAR Router R7000"},
{"id":"p-isp-hub","field":"httpTitle","pattern":"\\b(BT Smart Hub|EE Smart Hub|Hub Manager|Virgin Media Hub|Sky Hub|Sky Broadband|Plusnet Hub|Vodafone)\\b","model":"$1","type":"ROUTER","weight":75,"sample":"Hub Manager"},
{"id":"p-fritz","field":"httpTitle","pattern":"FRITZ!Box","vendor":"AVM","model":"FRITZ!Box","type":"ROUTER","weight":90,"sample":"FRITZ!Box"},
{"id":"p-fritz-cert","field":"certName","pattern":"fritz\\.box$","vendor":"AVM","model":"FRITZ!Box","type":"ROUTER","weight":90,"sample":"fritz.box"},
{"id":"p-draytek","field":"httpTitle","pattern":"\\bVigor","vendor":"DrayTek","type":"ROUTER","weight":80,"sample":"Vigor Login Page"},
{"id":"p-mikrotik-title","field":"httpTitle","pattern":"RouterOS","vendor":"MikroTik","os":"RouterOS","type":"ROUTER","weight":90,"sample":"RouterOS router configuration page"},
{"id":"p-mikrotik-ssh","field":"sshBanner","pattern":"^ROSSSH","vendor":"MikroTik","os":"RouterOS","type":"ROUTER","weight":90,"sample":"ROSSSH"},
{"id":"p-edgeos","field":"httpTitle","pattern":"EdgeOS|EdgeRouter","vendor":"Ubiquiti","os":"EdgeOS","type":"ROUTER","weight":90,"sample":"EdgeOS"},
{"id":"p-rdp","field":"service","pattern":"^RDP$","os":"Windows","weight":60,"sample":"RDP"},
{"id":"p-iis","field":"httpServer","pattern":"Microsoft-IIS","os":"Windows","type":"SERVER","weight":80,"sample":"Microsoft-IIS/10.0"},
{"id":"p-httpapi","field":"httpServer","pattern":"Microsoft-HTTPAPI","os":"Windows","weight":60,"sample":"Microsoft-HTTPAPI/2.0"},
{"id":"p-aspnet","field":"poweredBy","pattern":"ASP\\.NET","os":"Windows","weight":50,"sample":"ASP.NET"},
{"id":"p-openssh","field":"sshBanner","pattern":"^OpenSSH$","os":"Linux","weight":40,"sample":"OpenSSH"},
{"id":"p-ssh-freebsd","field":"sshBanner","pattern":"FreeBSD","os":"FreeBSD","weight":70,"sample":"FreeBSD-20240806"},
{"id":"p-ssh-raspbian","field":"sshBanner","pattern":"Raspbian","vendor":"Raspberry Pi","type":"COMPUTER","weight":60,"sample":"Raspbian-1+deb12u1"},
{"id":"p-esxi-title","field":"httpTitle","pattern":"VMware ESXi","vendor":"VMware","model":"ESXi","os":"ESXi","type":"SERVER","weight":90,"sample":"VMware ESXi"},
{"id":"p-esxi-cert","field":"certOrg","pattern":"VMware","vendor":"VMware","type":"SERVER","weight":70,"sample":"VMware Installer"},
{"id":"p-qnap-title","field":"httpTitle","pattern":"QNAP|\\bQTS\\b","vendor":"QNAP","type":"NAS","weight":90,"sample":"QTS"},
{"id":"p-qnap-cert","field":"certOrg","pattern":"QNAP","vendor":"QNAP","type":"NAS","weight":80,"sample":"QNAP Systems Inc."},
{"id":"p-truenas-title","field":"httpTitle","pattern":"TrueNAS|FreeNAS","model":"TrueNAS","type":"NAS","weight":90,"sample":"TrueNAS"},
{"id":"p-truenas-cert","field":"certOrg","pattern":"iXsystems","model":"TrueNAS","type":"NAS","weight":80,"sample":"iXsystems"},
{"id":"p-unraid-title","field":"httpTitle","pattern":"Unraid","model":"Unraid","type":"NAS","weight":90,"sample":"Tower/Main - Unraid"},
{"id":"p-unraid-cert","field":"certName","pattern":"unraid\\.net$","model":"Unraid","type":"NAS","weight":80,"sample":"abc123.myunraid.net"},
{"id":"p-omv","field":"httpTitle","pattern":"openmediavault","model":"OpenMediaVault","type":"NAS","weight":90,"sample":"openmediavault control panel - nas"},
{"id":"p-grafana","field":"httpTitle","pattern":"^Grafana","software":"Grafana","type":"SERVER","weight":40,"sample":"Grafana"},
{"id":"p-portainer","field":"httpTitle","pattern":"Portainer","software":"Portainer","type":"SERVER","weight":40,"sample":"Portainer"},
{"id":"p-nodered-title","field":"httpTitle","pattern":"Node-RED","software":"Node-RED","type":"SERVER","weight":40,"sample":"Node-RED"},
{"id":"p-nodered-http","field":"service","onPorts":[1880],"pattern":"^HTTP$","software":"Node-RED","weight":40,"sample":"HTTP"},
{"id":"p-nextcloud","field":"httpTitle","pattern":"Nextcloud","software":"Nextcloud","type":"SERVER","weight":40,"sample":"Nextcloud"},
{"id":"p-adguard","field":"httpTitle","pattern":"AdGuard Home","software":"AdGuard Home","type":"SERVER","weight":40,"sample":"Login | AdGuard Home"},
{"id":"p-octoprint","field":"httpTitle","pattern":"OctoPrint","software":"OctoPrint","type":"IOT","weight":40,"sample":"OctoPrint Login"},
{"id":"p-kodi","field":"httpTitle","pattern":"^Kodi","software":"Kodi","type":"MEDIA","weight":40,"sample":"Kodi"},
{"id":"p-tasmota","field":"httpTitle","pattern":"Tasmota","model":"Tasmota","type":"IOT","weight":90,"sample":"Tasmota - Main Menu"},
{"id":"p-shelly","field":"httpTitle","pattern":"Shelly","vendor":"Shelly","type":"IOT","weight":90,"sample":"Shelly Plus 1PM"},
{"id":"p-frigate","field":"httpTitle","pattern":"Frigate","software":"Frigate NVR","type":"SERVER","weight":40,"sample":"Frigate"},
{"id":"p-z2m","field":"httpTitle","pattern":"Zigbee2MQTT","software":"Zigbee2MQTT","type":"SERVER","weight":40,"sample":"Zigbee2MQTT"},
{"id":"p-kuma","field":"httpTitle","pattern":"Uptime Kuma","software":"Uptime Kuma","type":"SERVER","weight":40,"sample":"Uptime Kuma"},
{"id":"p-idrac","field":"httpTitle","pattern":"iDRAC","vendor":"Dell","model":"iDRAC","type":"SERVER","weight":90,"sample":"iDRAC9 - Login"},
{"id":"p-ilo","field":"httpTitle","pattern":"\\biLO\\b","vendor":"HPE","model":"iLO","type":"SERVER","weight":90,"sample":"iLO 5 Login"},
{"id":"p-airplay","field":"httpServer","pattern":"AirTunes","vendor":"Apple","weight":60,"sample":"AirTunes/620.8.2"},
```

That is 57 new entries (several products have both a title rule and a certificate rule). Coverage against the controller's list:

| Asked for | Rules |
|-----------|-------|
| Synology DSM on 5000/5001 | p-dsm-title, p-dsm-cert, p-dsm-ftp; existing ports-synology, title-synology |
| Pi-hole admin | existing title-pihole, converted to a software rule (3.6) and fed by port titles |
| UniFi | p-unifi-os (console, platform); p-unifi-cert and existing title-unifi as software "UniFi Network" (3.6) |
| Hue | p-hue-title, p-hue-cert; existing hue (mDNS) |
| Home Assistant 8123 | existing title-hass and port-hass, converted to software rules (3.6) |
| Plex 32400 | p-plex-http; existing title-plex and port-plex, converted to software rules (3.6) |
| Jellyfin | p-jellyfin (port 8096 is shared with Emby, so no port rule) |
| Proxmox 8006 | p-proxmox-cert; existing title-proxmox, port-proxmox |
| Printers (CUPS, IPP) | p-cups-server, p-printer-title, p-hp-server; existing port-printer, printer-mdns |
| Cameras (RTSP) | p-rtsp-vendor, p-rtsp-service; existing rtsp-camera, server-cameras (now sees RTSP Server headers) |
| Router admin pages | p-router-title, p-isp-hub, p-fritz, p-fritz-cert, p-draytek, p-mikrotik-title, p-mikrotik-ssh, p-edgeos, p-srm-title; existing title-openwrt, title-bsd-fw, realm-routers |
| Windows via SMB and RDP | p-rdp (confirmed RDP handshake, os only at 60, because xrdp on Linux answers the same handshake), p-iis, p-httpapi, p-aspnet; existing ports-windows (135 + 445), port-rdp (lowered to 60, 3.6). SMB has no banner; see section 12 row 6 |
| Linux via OpenSSH | p-openssh, p-ssh-freebsd, p-ssh-raspbian; existing ssh-distro, ssh-dropbear |
| ESXi, QNAP, TrueNAS | p-esxi-title, p-esxi-cert, p-qnap-title, p-qnap-cert, p-truenas-title, p-truenas-cert |
| Grafana, Portainer, Node-RED | p-grafana, p-portainer, p-nodered-title, p-nodered-http |
| A dozen more | Unraid (2), OpenMediaVault, Emby, Nextcloud, AdGuard Home, OctoPrint, Kodi, Tasmota, Shelly, Frigate, Zigbee2MQTT, Uptime Kuma, iDRAC, iLO, AirPlay receivers, Synology SRM: 17 |

Every new rule has a `sample` that its own pattern matches (enforced by the existing rule-sample test, extended in 9.1). The `sample` for `p-dsm-ftp` is the joined portBanner form (product, then the greeting line). The router patterns carry `\b` boundaries so "Attendance", "Pegasus" and "Aztec" never read as Tenda, ASUS or ZTE (negative tests in 9.1).

### 3.6 Software, and the existing rules it changes

Controller ruling on Gruff M6: a self-hosted application says what a box runs, not what the box is.

- `DeviceIdentity` gains `software: List<String>` (default empty). Every matching rule with `software` adds its value; the list is distinct and keeps rule-file order. It is not a weighted slot: an app never displaces another app. Software rules add their reason line as every rule does.
- Application rules set `software` and may set `type` (never `model`) only at weight 40 or below, so any platform, hostname, port-pattern or OUI evidence outranks them. A host with nothing but an app rule gets the app's type at confidence 40, which is better than "Unknown device" and loses to everything else.
- Stored in the new `hosts.identSoftware` column (section 6) as the names joined by a newline; written by `updateIdentity` and `setIdentityIfNotWeaker` with the rest of the identity. Never an override target: the Edit device dialog does not show it.
- Detail: under the data line, one chip per software name, "Runs Home Assistant", in a single horizontally scrolling row (`caption` / `textSecondary` on `surface`, 4 dp radius, 2 dp / 6 dp padding, 6 dp gap; the mark-chip shape in neutral colours, since it is not a status). The row is absent when the list is empty; when present it adds about 24 dp to the pinned block at font scale 1.3, which the Radar spec 7.5 budget absorbs (at least five port rows with the note collapsed instead of six; test 7 of the Radar spec is updated to five when a software row shows). Tag `softwareChips`; each chip's description is its text.
- Home rows, search and the device vulnerability query do not use software.

Existing rules changed (exact replacement entries, same positions in the file):

```json
{"id":"title-hass","field":"httpTitle","pattern":"Home Assistant","software":"Home Assistant","type":"SERVER","weight":40,"sample":"Home Assistant"},
{"id":"port-hass","field":"anyPort","anyPort":[8123],"software":"Home Assistant","type":"SERVER","weight":40},
{"id":"title-plex","field":"httpTitle","pattern":"\\bPlex\\b","software":"Plex Media Server","type":"SERVER","weight":40,"sample":"Plex"},
{"id":"port-plex","field":"anyPort","anyPort":[32400],"software":"Plex Media Server","type":"SERVER","weight":40},
{"id":"title-pihole","field":"httpTitle","pattern":"Pi-hole","software":"Pi-hole","type":"SERVER","weight":40,"sample":"Pi-hole - Login"},
{"id":"title-unifi","field":"httpTitle","pattern":"UniFi","software":"UniFi Network","weight":40,"sample":"UniFi Network"},
{"id":"port-rdp","field":"anyPort","anyPort":[3389],"os":"Windows","type":"COMPUTER","weight":60},
```

`port-rdp` drops from 70 to 60 so a Raspberry Pi running xrdp keeps `ssh-distro` and `host-rpi` (70); Windows PCs still get Windows from `ports-windows` (70) and `p-rdp`. The Proxmox rules stay platform rules (Proxmox VE is the host's operating system). `p-openssh` stays at 40 and may call a Mac with no other evidence "Linux"; any Apple hostname, mDNS or OUI evidence outranks it (accepted, Gruff m11).

Precedence the tests pin (9.1 test 14): a Synology with Plex, Jellyfin and Home Assistant reads Synology NAS with three software chips; an Unraid server with Jellyfin and Frigate reads Unraid NAS; a Windows PC with Plex reads Windows Computer; a Raspberry Pi with the UniFi Network app reads Raspberry Pi Computer, not a Ubiquiti router; a Pi with xrdp reads Linux.

## 4. Manual device details

Files: `data/Entities.kt`, `data/Daos.kt`, `data/Repositories.kt`, `data/NetworkSignature.kt` (`DisplayName`), `ui/home/HomeViewModel.kt` (`IdentityLine`, `RowRules`), `ui/home/HomeComponents.kt`, `ui/detail/DetailContent.kt`, `ui/detail/DetailComponents.kt`, `ui/detail/DetailViewModel.kt`, new `ui/detail/EditDevice.kt`.

### 4.1 Stored values

`HostEntity` gains `userType: DeviceType? = null`, `userVendor: String? = null`, `userModel: String? = null` (section 6). The name override already exists as `customName`. Detected values (`deviceType`, `identVendor`, `identModel`) stay where they are and keep being written by identification, so clearing an override shows the current detection at once. `HostDao.updateScan` and `updateIdentity` do not list the user columns, so no scan can overwrite them (the same guarantee `customName`, `status` and `note` have today).

New DAO call: `@Query("UPDATE hosts SET customName = :name, userType = :type, userVendor = :vendor, userModel = :model WHERE id = :id") suspend fun setOverrides(...)`. `HostRepository.setOverrides(hostId, DeviceEdits)` replaces `setCustomName`.

### 4.2 Effective values

One pure helper, used everywhere a type, vendor or model is shown or searched:

```kotlin
object Effective {
    fun type(h: HostEntity): DeviceType = h.userType ?: h.deviceType
    fun vendor(h: HostEntity): String? = h.userVendor ?: h.identVendor
    fun model(h: HostEntity): String? = h.userModel ?: h.identModel
}
```

- `IdentityLine.of(h)` uses the effective vendor, model and type (os stays detected). So the Home row data line, the Detail identity line and `identityWithType` all show the override, and Home search (which matches name, IP, identity line and MAC) finds it through the identity line.
- `RowRules.row` uses `Effective.type` for the row's type, so the edge colour follows. A gateway row stays teal whatever type is set (Radar spec 2.1 rule unchanged).
- The "This phone" row keeps its `Build.MODEL` line unless the Director sets a model, in which case the set model wins.
- `DetailViewModel.lookupVulnsForDevice` builds its query from the effective vendor and model, so a hand-entered model can be searched.
- Overrides never enter `HostEvidence`: the identifier sees only detected evidence, so "Why this identity" stays an honest account of what the network said.

### 4.3 Edit device dialog

Opens from: a tap on the Detail name (replaces the rename dialog), a tap on the identity line (new; the line gets `Role.Button`, description "Edit device, <name>"), and "Edit device" in the Detail overflow. One composable, `EditDeviceDialog(state: EditDraft, detected: Detected, onSave: (DeviceEdits) -> Unit, onCancel: () -> Unit)`.

- `AlertDialog`, `surface` container, title "Edit device" in `rowName` / `text`.
- Body is a `Column` with `verticalScroll` (it can exceed the screen at font scale 2.0). The Save and Cancel buttons are the dialog's own buttons, below the body and outside the scroll, so they stay pinned.
- Four fields, top to bottom, each followed by a supporting line in `data` / `textMuted` reading "Detected: <value>" ("Detected: none" when there is no detected value), and, on the right of that line, a "Reset to detected" text button (`signal`, 48 dp target) shown only while the field holds an override:

| Field | Control | Initial value | Detected value |
|-------|---------|---------------|----------------|
| Name | `OutlinedTextField`, single line | `customName ?: detected name` (never the displayed "This phone", so saving the own phone's dialog unchanged pins nothing) | `DisplayName.of(h.copy(customName = null))` |
| Type | Read-only field that opens a `DropdownMenu` with Router, Phone, Computer, Printer, Media, IoT, NAS, Camera, Server | Effective type label, or "Not set" when the effective type is UNKNOWN | Detected type label, or "none" |
| Vendor | `OutlinedTextField`, single line | Effective vendor | `identVendor` |
| Model | `OutlinedTextField`, single line | Effective model | `identModel` |

- "Reset to detected" puts the detected value in the field and marks it as not overridden; nothing is saved until Save.
- Overriding a detected type to "unknown" is not supported: the picker offers the nine real types only, and "Not set" is shown, never chosen. Reset to detected is the way back.
- Text fields are limited to 80 characters (the identifier's `MAX_VALUE_LEN`); input beyond that is not accepted.
- Buttons: "Save" (`signal`), "Cancel" (`textSecondary`). The old dialog's separate "Reset" button is gone; each field resets on its own.
- Pure helper `DeviceEdits.of(draft, detected): DeviceEdits` decides what is stored: values are trimmed; a blank text field stores null (no override); a value equal to the detected value stores null (the rename rule from today, so confirming a detection never pins it); a type equal to the detected type stores null.
- Fields use `npFieldColors()` and the 8 dp radius (Radar spec 4.1).

### 4.4 Where an override is visible as an override

- "Why this identity" gets a first line when any override is set, `secondary` / `text`: "Set by you: type NAS, vendor Synology, model DS220+" (only the set fields, in that order, comma separated; the name is not listed, it is visible as the title).
- Nowhere else marks it: the Director asked for his values to be shown, not flagged.

### 4.5 Detail overflow

The "Back to Home" row gets a trailing overflow icon (`MoreVert`, `textSecondary`, 48 dp target, tag `detailMenu`, description "More options") with two items: "Edit device" and "Remove device" (section 5.3). "Edit device" is always enabled. "Remove device" is always enabled too: it stops any scan of that host first (5.3).

## 5. Phase 2 follow-ups

### 5.1 Detail bind-failure wording

`DetailViewModel.startScan` shows "Scan skipped: could not bind to Wi-Fi" (new `DetailCopy.SCAN_BIND_FAILED`) instead of reusing `HomeCopy.BIND_FAILED`. Home's own uses of "Other ranges skipped: could not bind to Wi-Fi" are unchanged (open item 11.3 covers Quick scan all).

### 5.2 Range summary row

Unchanged: it stays hidden when no range was swept (Phase 2 ruling).

### 5.3 Forget this network and Remove device, with Undo

No confirmation dialog; the action happens at once and the notice line offers Undo. Controller ruling on Gruff M7 sets the mechanics below.

Entry points:

- Home overflow: "Forget this network", below "Quick scan all", above "Settings". Disabled while a radar scan or Quick scan all runs (or the radar is settling), and when there is no current network row.
- Detail overflow: "Remove device" (4.5). Always enabled. It returns to Home, where the Undo notice shows.

Shared pieces:

- `AppContainer` gains `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)`, where `handler` is a `CoroutineExceptionHandler` that logs (tag `NetProbe`) and never rethrows, so an unexpected failure in a delete or restore can never crash the app.
- `AppContainer.undo: UndoStore` holds at most one `PendingUndo(text: String, restore: suspend () -> Unit, commit: suspend () -> Unit)`. `post(new)` commits the previous one first. `undo()` runs `restore` and clears. `commit()` runs `commit` and clears; it is idempotent and suspending.
- `commit()` is called when the notice is dismissed, when a new `PendingUndo` replaces it, and at the very start of every scan body (radar `discover`, Quick scan all, a Detail scan) before anything else touches the database, so a commit always completes before the scan's first lookup. The existing `dismissMessage()` before `startScan()` still clears the notice text.

Forget this network (soft delete):

- `networks.forgotten: Long?` (section 6) holds the time of the Forget. Forget sets it and does nothing else to the database: hosts, ports and the manual ranges stay until the commit.
- `HomeViewModel` holds the forgotten id. While `forgotten` is set, Home shows that network as empty (HERO: no hosts, no completed scan), the run memory (finish time, range lists) for it is cleared, and `c.currentNetwork` is null.
- `NetworkRepository.ensure` never adopts or matches a forgotten row: both the signature lookup and the `byGateway` re-signature lookup skip rows with `forgotten` set. If the only match for the signature is a forgotten row, `ensure` returns null and inserts nothing (the unique `signature` index would refuse an insert anyway). Every env emission while the Undo is pending therefore leaves the forgotten row untouched and the id stable.
- Undo: `UPDATE networks SET forgotten = NULL WHERE id = :id`; Home's host flow re-emits and everything is back, including manual ranges, which were never removed.
- Commit (notice dismissed or replaced, or a scan starts): one transaction deletes the ports of that network's hosts, the hosts, and the network row; then the manual ranges key `extManual.<id>` is cleared. After a commit the env collector calls `ensure` for the current env, which inserts a fresh row.
- Process death with a Forget pending: the Undo is memory only, so `NetworkRepository.purgeForgotten()` runs once when `AppContainer` is created and hard-deletes every row with `forgotten` set (same cascade, same manual-range clearing).

Remove device:

1. Stop every scan of that host and wait for it: the Detail scan (`scan.stop()` then join), and the host's entry in Quick scan all, if it is the host being scanned. Quick scan all runs each host in its own child job registered in `AppContainer.hostScanJobs: ConcurrentHashMap<Long, Job>`; Remove calls `cancelAndJoin()` on it, and Quick scan all treats that one cancellation as "skip this host" (not failed, not stopping the loop).
2. If a radar scan is running on that network, add `(networkId, ip)` to `AppContainer.removedDuringScan: MutableStateFlow<Set<Pair<Long, String>>>`. The `discover` collector skips any emission whose (network, IP) is in the set, so the running scan never re-inserts the host (it merges by IP). Quick scan all also skips a host in the set. The set is cleared at the start of the next radar scan.
3. Snapshot the host row and its port rows (after step 1, so no port write is still in flight); delete both in one transaction on `appScope`; post `PendingUndo("Removed <display name>", restore, commit = {})`; navigate back.
4. Undo re-inserts the same rows with the same ids in one transaction (`OnConflictStrategy.ABORT`) and removes the pair from `removedDuringScan`. No conflict is possible: the running radar scan skips the IP, Quick scan all skips the host, and any new scan commits (drops) the Undo before it starts. The exception handler is the backstop.

Notice line: while an Undo is pending, Home shows its text with an "Undo" action word (`action` / `signal`, 48 dp target, tag `noticeUndo`, description "Undo") before the dismiss control. Undo runs `restore` and clears. The Undo notice takes the notice line while it is pending; a notice that was showing before it (an extended-discovery end note, for example) was not dismissed, so it shows again once the Undo is resolved. Dismissing the Undo notice commits it and does not dismiss the earlier notice.

New DAO calls: `setForgotten(id, at: Long?)`, `deleteHost(id)`, `deletePortsForHost(id)`, `deleteNetworkCascade(networkId)` (a `@Transaction`: ports `WHERE hostId IN (SELECT id FROM hosts WHERE networkId = :id)`, then hosts, then the network), `forgottenIds()`, and `@Insert(onConflict = ABORT)` list inserts for hosts and ports. There are no foreign keys in the schema, so the order is explicit.

### 5.4 "Stopped after N s"

- `HomeViewModel` keeps `lastRunCompleted: MutableStateFlow<Boolean>`, set from the existing `completed` flag in `discover`'s `finally` (true only when the engine flow finished normally). Exposed read-only as `HomeUiState.scanCompleted` (default true).
- `ScanSummaryText.of(totalHosts, otherSubnetHosts, durationMs, completed)`: detail "Scanned in N s" when completed, "Stopped after N s" otherwise, then ", K on another subnet" when K > 0 in both cases.
- Interrupted means Stop or a thrown error ("Scan failed: ..." also shows in the notice line). A network change still gives no summary at all (existing ruling 1).
- The finished announcement follows: "Scan finished, 13 devices" or "Scan stopped, 13 devices".

### 5.5 Stale dimming survives a restart

- `networks.lastRunAt: Long?` (section 6) holds the id of the last completed run (the run's start time, the same value as `latestRun`). `discover` writes it with `NetworkRepository.markRun(networkId, runId)` in its `finally`, only when `completed` is true (Gruff Q1): a run stopped after 2 s, failed, or killed by the system never persists, so it cannot dim nearly every host across restarts.
- The in-session rule is unchanged: `latestRun` is set when a run starts, and a stopped run still dims the hosts it did not reach until the next run, exactly as today.
- Seeding: `latestRun` is set from the row's `lastRunAt ?: 0` only when the resolved network id changes (the env collector compares the new id with the previous one). Re-emissions for the same network never touch `latestRun`, so seeding cannot revert a run in progress (Gruff m13).
- This also fixes a latent bug: today `latestRun` is not reset on a network change, so after switching networks the previous network's run id was used to judge staleness.
- The stale rule is unchanged: `run > 0 && h.lastDiscoveryRun < run`.

## 6. Data model and migration (schema 1 to 2)

One migration covers sections 3, 4 and 5. Every new column is nullable with no default, so the migration is a set of `ALTER TABLE ... ADD COLUMN` statements and no row is rewritten.

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN userType TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN userVendor TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN userModel TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN identSoftware TEXT")
        db.execSQL("ALTER TABLE networks ADD COLUMN lastRunAt INTEGER")
        db.execSQL("ALTER TABLE networks ADD COLUMN forgotten INTEGER")
        db.execSQL("ALTER TABLE ports ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN server TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN realm TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN poweredBy TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN certCn TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN certOrg TEXT")
    }
}
```

- Entities: `HostEntity` gains `userType: DeviceType? = null, userVendor: String? = null, userModel: String? = null, identSoftware: String? = null` (software names joined by a newline, 3.6); `NetworkEntity` gains `lastRunAt: Long? = null, forgotten: Long? = null`; `PortEntity` gains `title, server, realm, poweredBy, certCn, certOrg: String? = null`. All appended at the end of the constructor with defaults, so existing call sites compile unchanged.
- `DeviceType?` is stored as TEXT by Room's built-in enum handling, the same affinity as `deviceType`.
- `@Database(version = 2, exportSchema = true)`.
- `NetProbeDb.open(context: Context, name: String = DB_NAME)` with `const val DB_NAME = "netprobe.db"`; the builder adds `.addMigrations(MIGRATION_1_2)`. No `fallbackToDestructiveMigration`: the Director's marks and notes must survive.
- The exported `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/2.json` is generated by KSP and committed next to `1.json`.
- `app/build.gradle.kts`: `sourceSets { getByName("androidTest") { assets.srcDirs("$projectDir/schemas") } }` so `MigrationTestHelper` can read the schemas. `room-testing` is already an `androidTestImplementation`; no new dependency.
- Rule for every instrumented test (Gruff M8): no test may open, create or delete a database named `DB_NAME`. Migration tests use `"netprobe-migration-test.db"` (constant `MIGRATION_TEST_DB`) and delete only that file; DAO tests keep using in-memory databases.

## 7. Copy

Sentence case, active voice, no disclaimers, no warnings. Every string below is a constant in its owner object (`HomeCopy`, new `DetailCopy`, new `EditCopy`) and a template in `CopyTableTest`. Strings not listed are unchanged from Radar spec section 8.

| Where | String |
|-------|--------|
| Port sheet | "Open with..." |
| Port sheet, chip label | "Try as" |
| Port sheet, chips | "http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb" |
| Port sheet, chip description | "Open 10.0.3.59:8123 as ssh" |
| Chooser title | "Open with" (unchanged, `AndroidActionRunner`) |
| Port row sub-line with a title | "Synology DiskStation  nginx 200 OK" (title, two spaces, the existing version line; title alone when there is no version line) |
| Reasons, port evidence | "port 5000 httpTitle: DiskStation - Synology DiskStation" (form "port P field: value") |
| Reasons, overrides | "Set by you: type NAS, vendor Synology, model DS220+" |
| Detail identity description | "Edit device, DiskStation" |
| Detail name description | "Edit device, DiskStation" (replaces "Rename, ...") |
| Detail overflow | "More options"; "Edit device"; "Remove device" |
| Edit dialog title | "Edit device" |
| Edit dialog labels | "Name", "Type", "Vendor", "Model" |
| Edit dialog supporting | "Detected: Synology"; "Detected: none" |
| Edit dialog reset | "Reset to detected" |
| Edit dialog type values | "Router", "Phone", "Computer", "Printer", "Media", "IoT", "NAS", "Camera", "Server"; "Not set" |
| Edit dialog buttons | "Save", "Cancel" |
| Detail bind failure | "Scan skipped: could not bind to Wi-Fi" |
| Home overflow | "Forget this network" |
| Notice, network forgotten | "Forgot Home" (form "Forgot <network name>") |
| Notice, device removed | "Removed DiskStation" (form "Removed <display name>") |
| Notice action | "Undo" |
| Done, stopped | "Stopped after 12 s"; "Stopped after 12 s, 1 on another subnet" |
| Announcement, stopped | "Scan stopped, 13 devices" ("Scan stopped, no devices found" at zero) |
| Detail software chip | "Runs Home Assistant" (form "Runs <software>") |
| Detail progress, identify tail | "identifying 3 ports"; "identifying 1 port" |

Removed: the rename dialog strings "Rename device" and "Reset" (folded into the Edit dialog), and the description "Rename, <name>".

## 8. Scope

Changes:

- UI: `ui/home/RadarBlock.kt`, `RadarGeometry.kt` (`afterglow`, `breath`, `ArmTrail`), `HomeContent.kt`, `HomeComponents.kt` (notice with Undo, menu item, chip colours, row edge and identity animation), `HomeViewModel.kt` (`forgetNetwork`, `undo` in state, `scanCompleted`, `latestRun` from the network row, `setIdentityIfNotWeaker` in Quick scan all), `RadarModes.kt` (`ScanSummaryText`, `RadarText`), `HomeCopy.kt`; `ui/detail/DetailContent.kt`, `DetailComponents.kt`, new `EditDevice.kt`, `DetailText.kt`, `DetailViewModel.kt` (overrides, remove, bind wording, `setIdentityIfNotWeaker`), new `DetailCopy.kt`; `ui/Controls.kt` (`ScanPill`, `PinnedSegmented`); `ui/Nav.kt`; `ui/theme/Motion.kt`.
- Actions: `ActionProvider.kt`, new `PortUris.kt`.
- Engines: `engine/serviceid/*` (profile gate, `ActiveProbeBudget`, `PASSIVE_ONLY` and `HEAD_ONLY`, probe ladder, RTSP and RDP probes, `CertInfo`, new `ServiceInfo` fields, close-on-cancel sockets), `engine/discovery/HttpTitle.kt` (`HttpPeek.poweredBy`), `engine/identify/*` (rule fields, `onPorts`, `software`, port reasons, router vendor canonicalisation, `HostEvidenceBuilder` merge), `res/raw/identify_rules.json` (57 new entries, seven existing entries replaced).
- Data: `Entities.kt`, `Daos.kt`, `Repositories.kt`, `NetProbeDb.kt` (version 2, migration), `NetworkSignature.kt` (`DisplayName` unchanged in rule; detected-name helper), `AppContainer.kt` (`appScope` with its exception handler, `UndoStore`, `hostScanJobs`, `removedDuringScan`, `purgeForgotten` at start-up), `app/schemas/.../2.json`, `app/build.gradle.kts` (androidTest assets only).

Does not change:

- Discovery engine behaviour, extended discovery, binding rules, `ScanController`, the scan job lifecycle, navigation routes and arguments, permissions, the manifest, `targetSdk = 30`, Settings, the vulnerability sheet.
- Radar layout, sizes, tokens, typography, copy outside section 7.
- The rule that only the Stop pill stops a radar scan; Scan, Stop and the Detail scan bar stay pinned outside every scroll container.

Build constraints for every task: Kotlin 2.1.21, Compose BOM 2025.06.00, navigation-compose 2.9.0, Room 2.7.1 with KSP; no new dependency (no animation library, no navigation-testing, no Paparazzi or Roborazzi); no downloads or installs by any subagent; no em-dashes in code, comments, strings, docs or commits; no attribution lines in commits.

## 9. Tests and verification

### 9.1 JVM tests

1. `ArmTrail.stops()`: head alpha 0.55 at position 1; 0 at positions 0 and 0.6111; alpha non-decreasing from 0.6111 to 1; tail span 140 degrees.
2. `afterglow`: 1 at d = 0; 0.25 at d = 60; 0 at d = 120 and beyond; 0 for a blip just ahead of the arm (d = 359); wraps across 0/360 (arm 10, blip 350 gives d = 20).
3. `breath`: `BREATH_MAX` at 0 degrees, `BREATH_MIN` at 180, within bounds everywhere.
4. Blip angle: `atan2` of `blipOffset(i, r)` normalised into [0, 360) for the first 64 indices.
5. `RowStagger.delays`: 0, 40, 80, ...; capped at 200 from the sixth new row.
6. `PortUris.uri`: every scheme in `TRY_AS`, including smb on 445 (`smb://ip/`) and on 8445 (`smb://ip:8445/`).
7. `PortUris.guess`: HTTP on 8090 gives http; HTTPS on 9443 gives https; bare TLS on 993 and 8009 gives null; SSH on 2200 gives ssh; unknown 7000 gives null; port fallbacks unchanged.
8. `ActionProvider`: HTTP banner on 8123 and 8090 gives an `OpenUri` primary `http://ip:port/`; HTTPS on 9443 gives `https://ip:9443/`; bare TLS on 993 gives no primary and `OpenWith("https://ip:993/")`; an unknown port gives no primary and `OpenWith("http://ip:port/")`; every port has exactly one `OpenWith`; "No app installed" is still produced and still filtered by `sheetLabel`. `tryAs` returns only the schemes the fake resolver accepts, in `TRY_AS` order, and an empty list when it accepts none.
9. `BannerParsers.rtspReply`: a Hikvision reply gives RTSP, product and full server; non-RTSP text gives null. `rdpConfirm`: a Connection Confirm gives RDP; three wrong-byte cases give null.
10. `ServiceIdentifier` ladder in `Active` mode (fake `BannerReader`, `TlsPeek`, `HttpTitleFetcher`): a silent unknown port that answers HEAD becomes HTTP with a title; one that completes TLS and answers GET becomes HTTPS with `certCn` and `certOrg`; one that completes TLS but not GET becomes TLS; a port that sent one unprintable byte passively goes to no active step; a TLS port is never sent the plain HEAD first; an HTTP-set port that fails HEAD falls through to TLS; a silent port with nothing stays at port-table confidence.
11. Profile gate: in `Passive` mode a silent unknown port receives no TLS peek, no HEAD and no GET, an HTTP-set port receives exactly one HEAD and no GET, and 554 and 3389 receive nothing beyond the passive read (fakes record every call). Quick scan all always passes `Passive`.
12. Passive-only ports: `PASSIVE_ONLY` equals exactly {25, 110, 143, 515, 2000, 2001, 6000..6063, 9100..9107} and `HEAD_ONLY` equals {631} (the constants are pinned); in both modes a fake reader fails the test if any byte is written to 9100, 9107, 515, 2000, 6000 or 6063; 631 in `Active` mode receives one HEAD, no TLS ClientHello and no GET.
13. Budget (`ActiveProbeBudget` with a fake clock): 100 silent unknown ports in `Active` mode, each step costing 1 s of fake time: no more than 64 ports ever receive a TLS peek; no step starts once 60 s have been spent; every refused port is returned as `ServiceInfo(tableName, PORT_TABLE)`; passive reads and today's steps are not counted; with 8 parallel identifies `spentMs` never exceeds 60 s + 8 x the longest step.
14. Cancellation: against a silent local `ServerSocket`, cancelling `BannerReader.read`, `TlsPeek.certInfo` and `HttpTitleFetcher.fetch` each completes within 300 ms.
15. `TlsPeek` subject parsing: CN and O extracted from representative X.500 names (with escaped commas).
16. `HostEvidenceBuilder` and `mergePort` (pure merge function): new fields merge field by field; null never erases; a PORT_TABLE result never replaces a CONFIRMED name; a new CONFIRMED name replaces an old one.
17. `DeviceIdentifier` per new field: `httpTitle` from a port, `onPorts` excludes other ports and discovery titles, `httpServer` from an RTSP port, `sshBanner` on 2222, `poweredBy`, `certName`, `certOrg`, `portBanner`, `service` (CONFIRMED only, PORT_TABLE ignored). Reason lines carry "port P". `software` collects every matching app rule, distinct, in file order.
18. Rules file: every rule loads; ids unique; every rule's `sample` matches its own pattern (for port fields the sample is fed through the field's candidate form); no rule with `software` sets `model`, and none sets `type` above weight 40; one synthetic-evidence test per new or replaced rule asserting its vendor, model, os, type and software.
19. Precedence (3.6): Synology with Plex, Jellyfin and Home Assistant reads Synology NAS with three software names; Unraid with Jellyfin and Frigate reads Unraid NAS; a Windows PC (135 + 445) with Plex reads Windows Computer; a Raspberry Pi (`raspberrypi` hostname) with the UniFi Network app (title and cert) reads Raspberry Pi Computer, not Ubiquiti or ROUTER; a Pi with xrdp and a Raspbian SSH banner reads Linux; SRM beats DSM on type; Pi-hole on a Pi keeps COMPUTER; p-openssh loses to ports-windows on os; the gateway rule (95) keeps ROUTER over any title.
20. Router pattern negatives: titles "Attendance", "Pegasus" and "Aztec" match no router rule; "netgear router" gives vendor "NETGEAR" (canonical map).
21. `setIdentityIfNotWeaker` decision (pure part): lower confidence keeps the stored identity; equal and higher replace it.
22. `Effective`: override wins per field; null falls back to detected; gateway edge stays teal with any `userType`.
23. `IdentityLine.of` with overrides: vendor and model override; type override when no vendor or model.
24. `DeviceEdits.of`: trim; blank to null; equal to detected to null; type equal to detected to null; 80 character limit; the own phone's dialog saved unchanged stores no name.
25. "Set by you" line: field order, only set fields, absent when none set.
26. `ScanSummaryText.of`: completed and stopped, with and without other-subnet hosts, zero devices.
27. `RadarText`: finished and stopped announcements.
28. `PulseGate.shouldStart`: true with no previous start; false at 349 ms; true at 350 ms.
29. `detailProgress`: sweep text unchanged; identify tail "identifying 3 ports" and "identifying 1 port"; fraction counts identifies.
30. `UndoStore`: `post` commits the previous; `undo` runs restore once; `commit` is idempotent; dismiss commits.
31. Stale seeding (pure `StaleRun`): seeds from `lastRunAt` only on a network id change; a re-emission for the same id keeps an in-progress `latestRun`; only a completed run produces a `markRun`.
32. Copy table: `CopyTableTest` gains templates for every new string in section 7 and iterates the new helpers (summary with `completed` false, notices with names, chip descriptions, software chips, identify tail, reasons forms).

### 9.2 Compose UI tests (instrumented)

Every test that samples an animation, not only the radar tests, sets `mainClock.autoAdvance = false` and advances time explicitly (Gruff m14). Tests that need motion provide `LocalReducedMotion = false`; reduced-motion tests provide true. No test writes `Settings.Global`, and no test opens `DB_NAME` (section 6).

1. Sweep running: SCANNING, advance 16 ms: `ArmSpinningKey` true and `BreathingKey` true; SETTLED after the settle: both false.
2. Ring pulse: with `found` rising every 50 ms for 1 s (20 finds): `PulseCountKey` is 1 at 100 ms, still 1 at 300 ms, 2 at 400 ms, and never more than one start per 350 ms window overall; every started pulse finishes (`PulseActiveKey` false 700 ms after the last start); a reset of `found` to 0 by a new scan starts no pulse and resets the count. Gating is by time (frame clock), so the test advances the clock, not the eased value.
3. Spring settle: SCANNING to SETTLED, sample `RadarFractionKey` every 16 ms for 600 ms: the minimum is below 0 (undershoot); the final value is 0 by 450 ms; `radarBlock` height never drops below its settled height; no exception is thrown (the pill's `layout` height is never negative); `radarRing` is clickable only once the transition has finished; "Quick scan all" is disabled until then.
4. No per-frame recomposition in steady SCANNING: after the expand has finished, a composition counter inside `RadarBlock` changes by at most 2 across 60 advanced frames with a constant `found`. (Expand and settle recompose per frame by design, 1.16.)
5. Reduced motion radar: arm static, blips at `BLIP_STATIC_ALPHA`, no pulse after a found change, settle completes in one frame, `BreathingKey` false.
6. Home stagger: three rows added in one emission during SCANNING carry `ArrivalDelayKey` 0, 40, 80; rows present at scan start carry -1; a filter change carries -1 for all.
7. Screen transition: `NetProbeNavHost` with fake slot screens (tags `fakeHome`, `fakeDetail`); navigate; at 120 ms both exist; at 260 ms only `fakeDetail`; back reverses. With reduced motion, one frame shows only the destination. The real `NetProbeNav` is not used, so no discovery runs.
8. Detail port rows: while scanning, a new port 8123 between 80 and 443 carries `ArrivalDelayKey` 0 and the 443 row's top moves from its old to its new position over 240 ms (sampled bounds, never a single jump); rows present at scan start carry -1; with no scan running, a new row carries -1.
9. Chip cross-fade: select "Interesting", capture the chip at 0, 80 and 160 ms (`captureToImage`): the centre pixel at 80 ms lies strictly between the unselected and selected fills; at 160 ms it equals `surface`. Same for one status segment and one profile segment. Reduced motion: final colour after one frame.
10. Scan pill morph: start a scan; at 110 ms the centre pixel lies between `signal` and `background`; the profile control's bounds are identical at 0, 90 and 220 ms (no shift); `detailScanButton` is the same node throughout.
11. Notice line: message set; at 100 ms the `notice` height is between 0 and its full height; at 200 ms it is full; on dismiss the old text is still shown at 80 ms.
12. Identity cross-fade: identity text changes; at 120 ms both texts exist in the tree; at 260 ms only the new one.
13. Port sheet: long-press a port with service HTTP on 8090 whose `tryAs` is http, https and ssh: "Open with..." and exactly three `tryAs:` chips exist; no "No app installed" row; tapping `tryAs:ssh` records `DeviceAction.OpenWith("ssh://10.0.3.59:8090")` and closes the sheet; the row's action word is "Open". With an empty `tryAs`, the "Try as" label is absent.
14. Edit device: tap the identity line opens "Edit device"; set Type to NAS and Vendor to Synology, Save records `DeviceEdits(type = NAS, vendor = "Synology", ...)`; reopening shows "Reset to detected" on those two fields only; "Reset to detected" on Type then Save stores no type override; for the own phone the Name field starts with the detected name, not "This phone"; Save and Cancel stay visible with the body scrolled at font scale 2.0.
15. Detail overflow: `detailMenu` shows "Edit device" and "Remove device", both enabled while scanning; "Remove device" records the stop-scan action before the remove action, then `onBack`.
16. Home Undo: with a pending undo, the notice shows "Removed DiskStation" and `noticeUndo`; tapping it records the restore; an extended notice showing before the Undo shows again after it is resolved; "Forget this network" is disabled while a radar scan, Quick scan all or the settle runs.
17. Software chips: a host with two software names shows `softwareChips` with "Runs Home Assistant" and "Runs Grafana"; a host with none shows no row; at font scale 1.3 with chips and the note collapsed, at least five `portAction` nodes are displayed.
18. Summary: a stopped run shows "Stopped after 12 s" in SETTLED.
19. Existing tests updated: `DetailContentTest` rename tests become Edit device tests; `RadarBlockTest` arm tests read the hoisted transition; `HomeContentTest` notice tests account for the slide-in.

### 9.3 Room

All in `androidTest`. None opens, creates or deletes `DB_NAME`.

1. `MigrationTest` (`MigrationTestHelper`, `room-testing`, database name `MIGRATION_TEST_DB = "netprobe-migration-test.db"`): before anything else, record whether `context.getDatabasePath(DB_NAME)` exists and, if it does, its length and last-modified time. Create schema 1; insert a network, a host with `customName`, `status` and `note`, and a port; run `MIGRATION_1_2` with `runMigrationsAndValidate(MIGRATION_TEST_DB, 2, true, MIGRATION_1_2)`; the rows read back intact and the twelve new columns exist and are null. Then open the migrated file through `NetProbeDb.open(context, MIGRATION_TEST_DB)` so Room's own schema validation runs. Finally assert `MIGRATION_TEST_DB != DB_NAME` and that the app's database file is exactly as recorded at the start (absent, or the same length and last-modified time). The test deletes only `MIGRATION_TEST_DB` and its `-wal` and `-shm` files.
2. `DaoTest` (in-memory): `setOverrides` round trip; `updateScan` and `updateIdentity` leave the user columns alone; `setIdentityIfNotWeaker` at lower, equal and higher confidence; `markRun` then reload.
3. Port evidence survives (Gruff M5): a port scan stores port 5000 with a DSM title and the host is identified Synology NAS; a discovery-style re-identification using `servicesFor` (no title in the discovery evidence) keeps Synology NAS; a later rescan whose HEAD times out leaves port 5000 "HTTP" CONFIRMED and its title in place.
4. Forget (soft delete): after `setForgotten`, `ensure` for the same env returns null and inserts no row (row count unchanged, the forgotten row's id unchanged); `byGateway` re-signature does not adopt it; Undo clears the flag and the hosts read back under the same id; commit deletes ports, hosts, the network and nothing from another network; `purgeForgotten` at start-up removes a row left forgotten by a killed process.
5. Remove device: snapshot then delete then restore gives identical rows and ids; with a fake discovery emitting the removed IP during the same run, no host row is re-inserted (the `removedDuringScan` skip); with Quick scan all running on that host, Remove cancels that host's job, no port row is written under the deleted id afterwards (no orphans), and Quick scan all continues with the next host.
6. Restore never crashes: a restore that hits an unexpected constraint is caught by the `appScope` handler (asserted through a test handler) and the process survives.

### 9.4 Deferred on-device checklist (Pixel 7 Pro `28221FDH300AP9`, the Director's LAN)

On-device checks are deferred until the Director returns. Per the standing QA rule, Bugsy builds, installs and runs every item; a green suite is not a substitute.

Step 0, before anything is installed or any instrumented test runs: back up the live database off the device (`adb shell run-as com.northwalessolutions.netprobe cat databases/netprobe.db > netprobe.db.bak`, and the same for `netprobe.db-wal` and `netprobe.db-shm`), and confirm the three files are non-empty.

Screen recordings (`adb shell screenrecord`, 10 to 20 s each):

1. A full radar scan on the Director's LAN from SETTLED: expand, sweep with blips lighting as the arm passes, pulses as devices land, breathing outline, spring settle.
2. The same with "Remove animations" on (static arm, instant changes).
3. Home to Detail and back, Home to Settings and back.
4. A Detail Full scan on the NAS or Home Assistant host: port rows arriving and sliding into order, the pill morph, the progress line, the identity line changing.
5. Selecting chips, status segments and profile segments.

Screenshots (`adb exec-out screencap -p`, font scale 1.0 and 1.3):

6. Mid-sweep at about 12 devices (arm head, trail and lit blips visible).
7. Port sheet on a non-standard HTTP port showing "Open with..." and the Try as chips; the system chooser after a chip tap.
8. "Why this identity" for a host identified from port evidence (port lines visible).
9. Edit device dialog with two overrides set; the Home row and Detail identity line showing them.
10. The Undo notice after "Remove device" and after "Forget this network".
11. "Stopped after N s" after Stop.
12. Home after a force-stop and relaunch, showing stale rows still dimmed.

Behaviour checks, each recorded pass or fail: a non-standard HTTP port on the Director's LAN (the one he saw) shows "Open" and opens in the browser; Try as ssh on port 22 offers his SSH app; the Synology, Home Assistant, Hue, UniFi and Hub Manager hosts each gain or keep the right identity after a Full scan; an identity never drops after a Quick scan; Undo restores marks and notes exactly; janky frames under 5 percent during a radar scan (`dumpsys gfxinfo`); the database migrates in place on the installed build (install over, not clean) with marks and notes intact.

## 10. Assumptions for the Director

Each was decided by the controller or by Ada while the Director was away. Overturn any of them and the plan changes only where noted.

| # | Assumption | If overturned |
|---|------------|---------------|
| A1 | Motion choices: the arm turns once per 3 s (was 2.4 s); the trail is 140 degrees; blips dim to 0.45 between sweeps and light up as the arm passes (today they sit at full strength); extra motion beyond the list (identity cross-fade, edge colour change, smooth Detail progress line) | Tokens in `Motion.kt` (`BLIP_REST_ALPHA = 1f` restores today's blips); extras are each one modifier |
| A2 | Full and Custom scans send active probes (TLS handshake, HTTP HEAD, a GET when it is HTTP, RTSP OPTIONS on 554/8554, an RDP connection request on 3389) to ports that said nothing passively, within 64 ports and 60 s of probe time per scan; Quick, and so Quick scan all, sends nothing beyond today. There is no Settings toggle: choosing Quick is the switch | Drop the ladder: "Open with..." and Try as still work, but D6 then applies only to the fixed HTTP and TLS port lists. A toggle is open item 11.6 |
| A3 | After a port scan the identity is replaced when the new confidence is equal or higher (the controller said "higher"; equal lets a same-weight scan add a model or reason line). Discovery runs still always replace it | Change `<=` to `<` in one query |
| A4 | The Edit device dialog replaces the rename dialog and opens from the name, the identity line and the overflow; confirming a detected value stores no override, so it follows later detection; a type cannot be overridden to "unknown" | Entry points are three click handlers; the equal-to-detected rule is one line in `DeviceEdits.of` |
| A5 | The one migration adds twelve columns, not three: `identSoftware`, `lastRunAt`, `forgotten` and six port evidence columns besides the three overrides | Without the port columns a narrow scan forgets earlier titles and certificates and identities flap |
| A6 | Undo is memory only and lasts until the notice is dismissed, any scan starts, another Undo replaces it, or the process ends; then the deletion is final (a pending Forget is purged at the next start). "Remove device" works during scans by stopping that host's scans and skipping its IP for the rest of a radar scan | A timed or persisted undo is a larger change |
| A7 | Manual overrides drive every display, search and the device vulnerability query, but never feed identification evidence; a gateway stays teal whatever type is set | Feeding them into evidence would make "Why this identity" circular |
| A8 | "Open with..." defaults to http for an unknown port and https for a bare TLS port | One constant in `PortUris` |
| A9 | Home Assistant, Plex, Pi-hole and the UniFi Network app become software chips, not identities, so hosts the Director already sees as "Home Assistant" or "Pi-hole" may now read by their platform (for example "Raspberry Pi Computer") with a "Runs Home Assistant" chip | Raise the converted rules' weights back; the software chip stays |
| A10 | Stale dimming persists only for completed runs | One condition in `discover`'s `finally` |

## 11. Open items

1. Rule samples come from vendor defaults and public knowledge; titles and certificate fields vary by firmware. The deferred device check (9.4) confirms the Director's own Synology, Home Assistant, Hue, UniFi and Hub Manager hosts; anything else that misfires is a one-line rule edit.
2. SMB has no banner. Windows from SMB still rests on the 135 + 445 pair; a real SMB negotiate probe (dialect and, with NTLMSSP, the OS string) is a later engine item if the Director wants it.
3. Quick scan all still says "Other ranges skipped: could not bind to Wi-Fi" for a host it could not bind. The Director's Phase 2 copy item named Detail only; the same "Scan skipped" wording could apply there.
4. Phase 2 residual minors not in this phase stay open: M3 (the previous run's count can flash at scan start), M5, M6, M7, M9, M11, M12. M8 is softened by the notice slide (1.13) but not removed.
5. Icon approval (Phase 2) is still pending and is not part of this phase.
6. A Settings toggle for active service probes (default on, like the other discovery toggles) if the Director wants one for client-site work beyond choosing Quick (Gruff Q3).
7. Industrial control ports (102, 502, 20000, 44818, 47808), which Gruff suggested adding to the passive-only list for client sites. The controller's binding list does not include them; adding them is a one-line change to `PASSIVE_ONLY` and the Director should decide before client-site use.
8. Software names are not searchable on Home and do not feed the vulnerability query; either is a small follow-up.

## 12. Not as drawn, and deviations from earlier specs

| # | Earlier spec or mockup | This spec | Why |
|---|------------------------|-----------|-----|
| 1 | Radar spec 3.3: arm `signal` .55 at 0 degrees, .08 at 70, clear from 110; 2400 ms turn | Head at 360 with a 140 degree tail behind it, head line, 3000 ms turn | The old wedge trailed ahead of the arm (1.1); D1, D2 |
| 2 | Radar spec 3.6: blips re-recorded only when `found` or size changes | Positions and brushes are still built only then; the blips, outline and pulse are drawn every frame while scanning, in one shared live layer with its own `graphicsLayer`, and the static disc has its own layer, so nothing else is re-recorded | Afterglow, breathing and pulse need per-frame values; no recomposition is added (1.16) |
| 3 | Radar spec 3.4: settle 360 ms `FastOutSlowInEasing` | Spring, damping 0.7, stiffness 400, about 350 to 400 ms, layout values clamped | D1: a settle that lands, not a slide |
| 4 | Radar spec 5.1 item 2: name tap opens the rename dialog with Save, Reset, Cancel | Name tap opens Edit device with per-field "Reset to detected" | D14 |
| 5 | Radar spec 5.2: "`ActionProvider` and `ActionProviderTest` are untouched" | `ActionProvider` always adds `OpenWith` and delegates its port table to `PortUris` | D6, D8 |
| 6 | Controller list: "Windows via SMB and RDP banners" | RDP is confirmed by a handshake; SMB has no banner, so Windows from SMB still uses the 135 + 445 pair | Open item 11.2 |
| 7 | Controller list: "Jellyfin" with a port | Title rule only | 8096 is also Emby's default port |
| 8 | Controller: migration adds three host columns | Adds twelve columns across three tables | A5 |
| 9 | Phase 2: Detail bind failure reused "Other ranges skipped: could not bind to Wi-Fi" | "Scan skipped: could not bind to Wi-Fi" | D17 |
| 10 | Radar spec 3.4: "Scanned in N s" after any finish | "Stopped after N s" when the run did not complete | D20 |
| 11 | Existing rules title-hass, port-hass, title-plex, port-plex, title-pihole, title-unifi (weights 70 to 90, set model and type) | Software rules at weight 40 | Ruling on Gruff M6, A9 |
| 12 | Existing rule port-rdp at 70 | 60 | xrdp on Linux; 3.6 |
| 13 | Radar spec 5.1: Detail pinned block has no software row | One optional chip row under the data line, about 24 dp at 1.3 | 3.6; port-row floor at 1.3 becomes five when it shows |
| 14 | Radar spec 3.6: the Home radar may run alongside Quick scan all's arc control | Quick scan all is disabled until the settle ends | 1.7 |

## 13. Gruff review response

Review: `docs/superpowers/specs/2026-10-20-phase3a-gruff-review.md` (ad01848), verdict Proceed after fixes. All eight MAJOR findings are applied as the controller ruled; 16 of 17 MINOR findings are applied; one is declined; the four questions are answered.

### MAJOR (all applied, controller rulings binding)

| # | Applied in | How |
|---|-----------|-----|
| M1 | 1.4, 1.5, 1.6, 1.16, 12 row 2 | Static disc on its own layer; blips, breathing outline and pulse share one live layer with its own `graphicsLayer`; the rule "every node whose draw reads a per-frame value has its own layer" is stated and goes on the review checklist; single `fraction` kept |
| M2 | 1.5, 1.17, 9.1 test 28, 9.2 test 2 | Monotonic `finds` counter, frame-clock time gate (`withFrameMillis`, `PulseGate`), linear `Animatable` launched from `rememberCoroutineScope`, easing at draw; `PulseActiveKey`; test rewritten so pulses finish and gating is by time |
| M3 | 2.2.1, 2.2.3, 2.2.4, A2, 9.1 tests 11 and 13 | Quick (and Quick scan all) passive, exactly today's sends; Full and Custom run the ladder only on ports that returned zero bytes; `ActiveProbeBudget` with 64 ports and 60 s of summed probe time per scan, accounting spelled out; refused ports stored as open and unknown |
| M4 | 2.2.2, 9.1 test 12 | `PASSIVE_ONLY` = 25, 110, 143, 515, 2000, 2001, 6000 to 6063, 9100 to 9107; `HEAD_ONLY` = 631 (HEAD, never a ClientHello or GET); constants pinned by a test that fails on any byte written to them |
| M5 | 3.1, 9.1 test 16, 9.3 test 3 | `servicesFor`, `upsertOpen`/`mergePort`, the Detail row mapping and `HostEvidenceBuilder` carry all six fields; `mergePort` never lowers a CONFIRMED name to PORT_TABLE; a radar re-identification keeps port-learned evidence |
| M6 | 3.5, 3.6, 7, 9.1 tests 17 to 19, 9.2 test 17 | New `software` identity field shown as "Runs <software>" chips on Detail; app rules never set model and set type only at 40 or below; six existing app rules converted; platform rules keep model and type; Gruff's four mixed-host precedence cases tested |
| M7 | 5.3, 9.2 tests 15 and 16, 9.3 tests 4 to 6 | Forget is a soft delete (`networks.forgotten`), `ensure` never adopts or matches a forgotten row, hard delete on commit, purge at start-up; Remove stops that host's scans (Detail and Quick scan all child job) and marks its IP removed for the rest of a running radar scan; `appScope` has a `CoroutineExceptionHandler` |
| M8 | 6, 9.3 test 1, 9.4 step 0 | `NetProbeDb.open(context, name)`; migration tests use "netprobe-migration-test.db", assert it is not `DB_NAME`, and assert the app's database file is untouched; no instrumented test may open `DB_NAME`; backup step before any install or test run |

### MINOR

| # | Decision | Where |
|---|----------|-------|
| m1 | Applied: timing corrected to about 350 to 400 ms; every `fraction` reader sorted into clamped or unclamped; "no exception, never negative" in the test | 1.7, 9.2 test 3, 12 row 3 |
| m2 | Applied: last angle kept in a non-state holder and resumed from | 1.9 |
| m3 | Applied: Quick scan all disabled until the settle ends | 1.7, 1.16 |
| m4 | Applied: `BLIP_STATIC_ALPHA = 1f` token | 1.2, 1.15 |
| m5 | Applied: claim corrected; test 4 limited to steady SCANNING | 1.16, 1.17, 9.2 test 4 |
| m6 | Applied: bare TLS gets no primary; its "Open with..." defaults to https | 2.3, 9.1 tests 7 and 8 |
| m7 | Applied: chips only for schemes with an installed handler; no row when none | 2.3, 2.4, 9.2 test 13 |
| m8 | Applied: sockets closed from `invokeOnCancellation`; 300 ms cancellation test | 2.2.5, 9.1 test 14 |
| m9 | Declined: greet-first services inside TLS are rare on a LAN, the port still lands cleanly as "TLS" with its certificate, and an extra 300 ms read per TLS port spends the probe budget for little | |
| m10 | Applied: `\b` boundaries, router vendors in `CANONICAL_VENDORS`, negative tests | 3.2, 3.5, 9.1 test 20 |
| m11 | Applied: `p-rdp` is os only at 60, `port-rdp` lowered to 60, xrdp precedence test; the macOS case for `p-openssh` is accepted as Gruff allowed | 3.5, 3.6, 9.1 test 19 |
| m12 | Applied: Name starts from `customName ?: detected`; overriding a type to unknown is stated as not supported | 4.3, 9.2 test 14 |
| m13 | Applied: seed only on a network id change; `markRun` only for completed runs (with Q1) | 5.5, 9.1 test 31 |
| m14 | Applied: `autoAdvance = false` for every motion test; `NetProbeNavHost` with slots | 1.9, 9.2 preamble and test 7 |
| m15 | Applied: guard kept and described as race protection only | 3.3 |
| m16 | Applied: Detail progress counts identifies and shows "identifying N ports" | 1.14, 7, 9.1 test 29 |
| m17 | Applied: search text corrected; an earlier notice returns after the Undo resolves | 4.2, 5.3, 9.2 test 16 |

### QUESTION answers

- Q1: Yes. Only completed runs are persisted as `lastRunAt`; the in-session rule is unchanged (5.5, A10).
- Q2: Yes, blips dim to 0.45 between sweeps by design; it is in A1 and is the first thing the Director sees in recording 1 (9.4), and one token restores today's look.
- Q3: No toggle in 3A. Quick is passive by ruling, so choosing Quick is the switch; a Settings toggle is open item 11.6 for the Director.
- Q4: No export is expected or scoped in Phase 3A; the Edit device dialog stores detected and manual values side by side, which is all an export would need later.
