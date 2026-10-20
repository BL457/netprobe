# Task 4 review: Radar component (Bugsy)

Commit reviewed: `ddf1ad4` (range c090c5b..ddf1ad4). Sources read at HEAD: `ui/home/RadarBlock.kt`, `ui/home/RadarGeometry.kt`, `ui/Controls.kt`, `BlipOffsetTest.kt`, `RadarBlockTest.kt`, spec diff.

## Runtime evidence

- `./gradlew :app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, 270 tests, 0 failures, 0 errors, 0 skipped. All 4 `BlipOffsetTest` cases are present and pass.
- I recomputed the blip geometry independently in Python (murmur3 finaliser, golden angle, jitter, band [0.5, 0.88], r = 86). Closest pair of the first 64 = **5.46 dp**, which passes the 4 dp guard and matches the report. Minimum blip centre radius 45.9 dp. Outermost dot + glow edge 86.55 dp (0.55 dp past the disc edge at alpha about 0, so not visible).
- Hash constants checked: -0x61c88647 = 0x9E3779B9, -0x7a143595 = 0x85EBCA6B, -0x3d4d51cb = 0xC2B2AE35. All correct.
- `RadarBlockTest.kt` differs from the brief only by the accepted `import androidx.compose.ui.unit.width`.
- No changes to Gradle or the TOML file (no new dependencies). There are no em-dashes in the commit. There is no `return` inside an expression body: only `return@LaunchedEffect` in lambdas, and block-bodied `mix` and `blipOffset`.
- Device rerun and radar screenshots are still pending (known gap, per the controller ruling). The baseline `t04-home-before-radar.png` shows the old Home (Scan pill, chips, list), which is fine as the Task 6 baseline.

## Spec compliance: ✅ against the brief and the rulings (one spec 3.6 conflict that comes from the brief itself, see I1)

| Requirement | Result |
|---|---|
| One `updateTransition` with a single `fraction` drives diameter, centre X, area height, stroke width and stroke colour (RadarBlock.kt:76-79, 115-122) | ✅ `target` and `areaHeight` both come to 64 + 108f, so the ring/disc box and its row stay in step |
| `drawWithCache` for the disc (176-198) and the blips (205-218) | ✅ (blips re-record during the alpha fade, see M5) |
| Arm on its own `graphicsLayer`, angle read inside the layer block (232) | ✅ `rotationZ = angle()` inside the block. The annulus is cached per size |
| Arm infinite transition only while SCANNING + RESUMED + motion allowed (75, 138, 222-228) | ✅ It lives inside `if (spinning)`, so it is disposed on pause, on reduce, and when the scan ends. The quick-scan arc does not follow this rule (M1) |
| Reduced motion: arm static at 40 degrees, transition duration 0, blips snap in, pill not delayed (78-84, 96, 163, 227) | ✅ `Motion.STATIC_ARM_DEG = 40f` |
| Ruling: blip band [0.5, 0.88], 4 dp guard still holds | ✅ 5.46 dp (computed and tested). The spec is updated in 3.3 and 10.1 |
| Ruling: arm centre is a cut-out (even-odd annulus), blips above the arm | ✅ 238-245. Draw order is disc, arm, blips, quick arc, labels |
| Ruling: quick scan ring base stroke dims to `border`, `signal` arc sweeps, "Stop" inside in `signal` | ✅ 121-122, 140-142, 249-269. The arc is centred on the ring stroke (inset w/2) |
| Tags `radarRing`, `radarStop`, `scanButton`: one node each | ✅ `radarRing` only in SETTLED. `radarStop` only in SCANNING. `scanButton` only while `heroAlpha > 0`. Each tag is on a single layout node, so its semantics merge into one node |
| Only Stop stops; the disc has no click handler (128-130) | ✅ The SCANNING modifier is semantics only. HeroButton is disabled once the mode leaves HERO (145) |
| Count is exact and autosizes (271-279) | ✅ `BasicText` StepBased 12 sp to 30 sp, `widthIn(max = 96.dp)`, `maxLines = 1` |
| HERO: 172 dp disc, 84 dp signal circle, three 6 dp `textMuted` dots, no arm, titles, disabled state with `border` fill and muted label, single no-network string (145, 154-159, 186-195, 281-290) | ✅ |
| blipOffset is deterministic and bounded | ✅ It is a pure function of the index, and the JVM tests cover it |
| Compose tests with `autoAdvance = false` where SCANNING or the transition is involved | ✅ Matches the brief. The device passed 8/8 before the final blip move |
| Semantics: ring description, `Role.Button`, scanning polite live region stepped by 5, single finish announcement, decorative layers cleared | ✅ (see M7, M9) |
| Settled row: 14 dp visual gap (64 dp target + 10 dp = 4 + 56 + 14) | ✅ |

## Task quality: Needs fixes (1 Important, which needs a controller ruling because the brief code carries it, plus 10 Minor)

### Important

**I1. Block height jumps by about 82 dp in one frame at scan start, and again at settle (RadarBlock.kt:133-172). Conflicts with spec 3.6.**
Spec 3.6 says "block height: lerp from the ring row to disc plus Stop pill plus progress line, so the list below slides with it". In the code, only `areaHeight` (64 to 172 dp) is lerped. The pill column (8 + 48 + 6 + about 20 dp) is composed in full on the first SCANNING frame, with only its alpha animated, and removed at once on SETTLED.
Failure scenario: tap the ring. On frame 1 the header goes from 64 dp to 146 dp and the device list jumps down about 82 dp, then slides the remaining 108 dp. On Stop, the list jumps up 82 dp, then slides. That is a visible hitch on every scan of the Director's chosen centrepiece. Neither JVM nor device tests check block height.
Plan conflict: the brief's code has this structure verbatim, and spec 3.6 contradicts itself ("composed only when the mode is not SETTLED" for the pill, against "block height lerps ... plus Stop pill"). Suggested fix: keep the pill column composed while `fraction > 0` and its mode was SCANNING, and scale its measured height by `fraction` with `Modifier.layout`, keeping the 120 ms alpha delay. Alternatively, rule that the jump is accepted and amend spec 3.6.

### Minor

- **M1. Quick-scan arc infinite transition is not gated on RESUMED (RadarBlock.kt:140, 251-257).** Spec 3.6 / D11 says infinite transitions are disposed when the app is backgrounded. The arc's `rememberInfiniteTransition` runs whenever `quickScanning && !reduced`. Scenario: a translucent activity, permission dialog or share sheet over Home (PAUSED, not STOPPED) keeps the arc driving frames for the whole quick scan. STOPPED is covered by the Recomposer's paused frame clock. Fix: pass `spinning`-style gating (`lifecycle >= RESUMED`) into `QuickArc`.
- **M2. The ring is clickable at disc size during the 360 ms settle (124-127).** At the moment of SETTLED, `modeModifier` switches to `clickable(onScan)` on a target box that is still about 172 dp and shrinking. Scenario: the user taps Stop and then taps the disc again (expecting it to stop, or by double-tap habit). A new scan starts immediately, with a full-disc ripple. Fix: `enabled = live && fraction < SOME_SMALL` or `transition.isRunning == false`.
- **M3. `shownBlips` is not reset when the mode enters SCANNING (90-98).** Blips from the previous scan are kept. Scenario: Task 5/6 latch the last non-null `DiscoveryStatus` (spec 3.3), so if `found` is still the previous run's value for the first emissions of a new scan, the previous scan's blips (and count) fade back in, then vanish when `found` drops to 0. Fix: in the effect, when the mode becomes SCANNING, set `shownBlips = 0; newestFrom = 0` before comparing.
- **M4. The Stop pill can be clicked while invisible (161-166).** The pill is at alpha 0 for the 120 ms delay plus the start of the fade, but it is laid out and clickable, so a tap there stops the scan on an invisible control. That is low risk because the user has just tapped the ring above it. Fix: `enabled = pillAlpha.value > 0.5f`, or accept it.
- **M5. The blip cache re-records every frame during the blip fade (139, 205-218).** `blipsAlpha` is passed as a value captured by the `drawWithCache` lambda, so each frame of the 300 ms / 200 ms fade rebuilds up to 64 radial-gradient brushes. Spec 3.6 says re-record only when `found` or the size changes. Fix: pass `{ blipsAlpha }` as a lambda read in `onDrawBehind`, as already done for `newestAlpha`.
- **M6. The count can extend past the 40 dp arm cut-out and the 43 dp blip band (spec self-conflict 3.3 / 7.1; RadarGeometry.kt:15, RadarBlock.kt:276).** `countMaxWidth` 96 dp is wider than the mask diameter (80 dp) and the blip band's inner diameter (86 dp). My geometry run, with a 4-digit count at font scale 1.3 (autosized to about 96 x 66 dp with "found"), shows blip dots 0, 5, 8 and 12 sitting under the count box, and the arm passing under the outer digits. The count draws on top, so it is never hidden, but the "always on plain fill" contrast promise in 7.1 is not met for that case. Plan conflict: spec 3.3 numbers (40 dp mask, 96 dp max count) are inconsistent with each other. Needs a ruling (for example, a cap of about 80 dp) or acceptance, and a device check in the pending 1022 screenshot.
- **M7. The finish announcement may use a stale headline (106-110).** `summary.headline` is read when the mode flips to SETTLED. If `totalHosts` from the Room flow lands one emission later, TalkBack says the previous count ("Scan finished, 12 devices" while the ring shows 13). Depends on Task 5/6 wiring. Consider keying on the summary or delaying one frame.
- **M8. TalkBack reads the count twice while scanning (128-130, 143).** The disc node has the description "Scanning, 9 devices found, ..." but does not merge or clear its children, so "9" and "found" are separate focusable nodes. TalkBack users hear the count twice. Fix: `clearAndSetSemantics` on `CountColumn` in SCANNING, keeping the `radarCount` tag via the unmerged tree, or accept.
- **M9. `blipOffset` returns NaN for a negative index (RadarGeometry.kt:37).** `i % MAX_BLIPS` is negative, so `sqrt` returns NaN. No caller passes negatives today. Use `Math.floorMod` or `require(index >= 0)`.
- **M10. Report inaccuracy.** The report says `t04-home-before-radar.png` is "Uncommitted", but it is in `ddf1ad4` (stat line, 304407 bytes). Documentation only.

## Controls.kt and Tasks 6 / 7

- Task 7 appends `ScanPill`, `PinnedSegmented` and `BackToHome` with a wildcard `layout.*` import next to the existing explicit `Box/heightIn/padding` imports. Kotlin allows that, so it compiles. `ScanPill` reuses the private `PILL_STROKE` in the same file, which is fine. `DISABLED_ALPHA`, `NetProbeText.scanPill` and `NetProbeText.chip` exist. `material-icons-extended` is already a dependency, so `KeyboardArrowLeft` resolves. No collisions found.
- `npFieldColors()` does not set the error text, error label, disabled label or disabled placeholder colours. These fall back to `RadarColorScheme` (`error = NetProbeColors.error`, `onSurface = text`), so Task 7's Custom ports field (`isError`, `enabled = !scanning`) gets sensible colours. Nothing to change.
- `StopPill` hardcodes "Stop" rather than `HomeCopy.STOP`, which is a nit: same string, and Task 7's `ScanPill` does the same.

## Conflicts with the plan text (explicit)

1. Spec 3.6 block-height lerp against the brief's instant pill composition (I1). The spec also contradicts itself on this point.
2. Spec 3.3: 96 dp count maximum against the 40 dp cut-out and the [0.5, 0.88] band (M6).
3. Spec 3.3 still says blipOffset "hashes the index to an angle". The golden-angle spiral is the brief's recorded deviation, and the spec text was not updated to match. That is wording only.
4. Ruling note carried from the report: the controller described `border` as "#2A3E54, the disc rim colour", but `border` is #3B5368 (#2A3E54 is `borderSubtle`). The implementer used `border`, which matches the spec wording. The controller should confirm this was the intended token.

## Re-review, round 1 (fix commit babaca7)

Scope: I1, M1, M2, M3, M5, M6 and M9 only. M4, M7, M8 and M10, and the HERO to SCANNING step of about 30 dp, are deferred by ruling and not re-raised here.
Runtime: `./gradlew :app:testDebugUnitTest --rerun`: BUILD SUCCESSFUL, 271 tests, 0 failures, 0 errors. Device tests were not run (no adb, as instructed).

### (a) Are the findings fixed?

| Finding | Status | Evidence |
|---|---|---|
| I1 settle/expand jump | ✅ Fixed in the code | RadarBlock.kt pill column: `layout { height * fraction }` + `clipToBounds` + `graphicsLayer { alpha = fraction }`. `pillShown` covers SCANNING and the SCANNING to SETTLED settle (`transition.currentState == SCANNING && targetState == SETTLED`), so the column shrinks with the disc and is removed only once `fraction == 0`. During the settle, Stop is a no-op. Spec 3.6 table row updated |
| M1 quick arc lifecycle | ✅ Fixed | `QuickArc(animate = resumed && !reduced)`. `rememberInfiniteTransition` is only in the `animate` branch |
| M2 tap during settle | ✅ Fixed | `clickable(enabled = live && fraction == 0f)`. The tween ends exactly on target 0f, and reduced motion snaps to 0f, so the ring is enabled the moment it settles |
| M3 previous blips | ✅ Fixed in effect | `blipMode` tracks entry into SCANNING and resets `shownBlips`/`newestFrom`. Residual: Task 6 clears the latch in a `LaunchedEffect(s.scanning)` (task-6-brief.md:703), so the first SCANNING frame still passes the stale `found`. The reset then sets `shownBlips = n` with `newest` at 0 and `blipsAlpha` at 0, and the next frame (found = 0) clears it. That is at most one frame at alpha of about 0, so invisible. Not a finding |
| M5 blip cache re-record | ✅ Fixed | `blipsAlpha: () -> Float`, read in `onDrawBehind`. The cache now captures only the count, `newestFrom` and the size |
| M6 count width | ✅ Fixed | `Dimens.countMaxWidth = 80.dp`, spec 3.3 updated. My geometry rerun with an 80 dp cap: no blip dot under the count box at font scale 1.0 or 1.3 (it was 4 dots at 1.3 before) |
| M9 negative index | ✅ Fixed | `Math.floorMod`. `blipOffset(-1)` lands in the band |

### (b) Regressions

None found in the surrounding code.
- The pill column's `layout` only changes the reported height, and `clipToBounds` sits outside it, so the clip and the hit test follow the reduced height.
- Existing tests that click or check `radarStop` do so after 1000 ms (fraction 1, full height).
- Task 6 assertions that `radarStop` is gone after settling run after idle, so after the transition ends and `pillShown` is false.
- The ring is reported disabled during the 360 ms settle; Task 6 tests click only after idle.
- `Motion.PILL_FADE_MS` and `PILL_DELAY_MS` are now unused in RadarBlock. That is dead constants only, not a regression.

### (c) Test meaningfulness

- JVM `negative index stays finite and in the band`: meaningful. On the old `%` code, `blipOffset(-1)` gives `sqrt(-0.5/64) = NaN`, so the assertion fails. With `floorMod` it passes.
- **Device `blockHeightSlidesWithoutAStepBothWays`: NOT meaningful as an I1 guard (Important, RadarBlockTest.kt, the `start < half` and `settleStart > half` assertions).** Working through the pre-fix code at font scale 1.0:
  - ring = 64 dp;
  - full = 172 + pill column (8 + 48 + 6 + progress line, about 80 dp), about 254 dp, so half is about 159 dp;
  - old expand, frame 2: `areaHeight` is about 67 dp plus the pill column already laid out at full height, about 147 dp. That is below 159, so it **passes**;
  - old settle, frame 2: pill removed, `areaHeight` still about 170 dp. That is above 159, so it **passes**;
  - the 180 ms midpoint and end checks also pass on the old code.

  So the test is green with or without the fix: the halfway bound tolerates the roughly 80 dp step it was written to catch.

  Fix: bound the first-frames step tightly. For example, `assertTrue(start - ring < 12.dp)` on expand and `assertTrue(full - settleStart < 12.dp)` on settle. The fixed code moves a few dp in two frames; the old code moves about 80 dp.

  The `radarStop.assertDoesNotExist()` at the end is meaningful, because it covers `pillShown` clearing.

Verdict: all seven findings are addressed in production code, and there are no regressions. One new Important: the I1 regression test does not detect I1 and needs tighter step bounds before it counts as evidence (it is still pending its device run in any case).

## Re-review, round 2 (commit 5764db3)

Scope: the round 1 Important (the I1 regression test), plus a regression check of the 5764db3 diff. Screenshots were not reviewed (per the coordinator).

- **Tightened test catches I1 now.** In `RadarBlockTest.blockHeightSlidesWithoutAStepBothWays` the two-frame checks are now `start - ring < 12.dp` and `full - settleStart < 12.dp`. Against the pre-fix code, using the round 1 numbers (ring 64 dp, full about 254 dp):
  - expand: about 147 - 64 = about 83 dp, which is not under 12, so it **fails**;
  - settle: about 254 - 170 = about 84 dp, which is not under 12, so it **fails**.

  Against the fixed code: the whole 190 dp travel is driven by `fraction`. Two 16 ms frames of a 360 ms `FastOutSlowInEasing` give a fraction of about 0.03 to 0.04, so about 6 to 7 dp each way, which passes. That fits the reported 9/9 on the Pixel. The margin is about 5 dp. That is enough at the test's fixed settings (font scale is not overridden), but a future change to the size duration or easing would need the bound revisited. This is a note, not a finding.

  The midpoint (180 ms) and end-state checks are unchanged, and the unused `half` is removed.
- **Removed `Motion.PILL_FADE_MS` / `PILL_DELAY_MS`.** There are no remaining references in any `.kt` file. `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL.
- **Regressions:** none. The diff touches only the test assertions, the two dead constants and the screenshot binaries.

Verdict: the round 1 Important is resolved. Task 4 has no open Critical or Important findings.
