# Task 4 report: Radar component (Ada)

Status: DONE, committed as `ddf1ad4` ("Add the radar component with HERO, scanning, settled and quick-scan states"), following the coordinator's rulings.
- Device rerun of `RadarBlockTest` on the final code: DONE, 9/9 green (see "Device run and screenshots" below).
- On-device screenshots of the radar states: DONE (see below). The baseline `t04-home-before-radar.png` is committed.

## What was implemented
- `ui/home/RadarGeometry.kt`: `RadarGeometry` (MAX_BLIPS, radius band, `mix`, `armMask`, jitter, radius fraction) and `blipOffset`: the golden-angle spiral with a hashed jitter, as briefed.
- `ui/Controls.kt`: `StopPill`, `npFieldColors`, as briefed.
- `ui/home/RadarBlock.kt`: `RadarBlock` and `ArmSpinningKey`. One `updateTransition` drives a single `fraction` (diameter, centre offset, stroke width, stroke colour, area height). The disc is drawn with `drawWithCache`. The arm sits on its own `graphicsLayer` with the rotation read in the layer block. The infinite transition only exists while SCANNING, RESUMED and not reduced. The count autosizes to a 96 dp maximum. The tags are the ones the brief lists.
- Tests: `BlipOffsetTest.kt` (JVM) and `RadarBlockTest.kt` (device), verbatim from the brief, plus one import fix (below).

## Coordinator rulings (applied)
- Deviations 1 to 4 accepted. The spec now gives the blip radius band as [0.5, 0.88]. That spec edit (the coordinator's) is included in `ddf1ad4`.
- **Ring change:** while quick scan runs, the ring's base stroke dims to the `border` token and the indeterminate `signal` arc sweeps over it, with "Stop" (still `signal`) inside, as spec 3.3 and 12.9 say. In `RadarBlock.kt`, `ringColor = if (live && !quickScanning) signal else border`, and that is the colour `strokeColor` lerps from. No existing device test asserts the ring colour, so no test was added.
- Note: the ruling gave `border` as "#2A3E54, the disc rim colour", but in `Tokens.kt` `border` is #3B5368 and #2A3E54 is `borderSubtle`. I followed the spec's token name (`border`, #3B5368). If the rim colour was what was meant, change it to `NetProbeColors.borderSubtle` (one word).
- Full suite after the ring change: `./gradlew :app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, 270 unit tests, 0 failures.

## Deviations and judgement calls (as originally raised)
1. **Test compile fix:** the brief's `RadarBlockTest` calls `getBoundsInRoot().width` without `import androidx.compose.ui.unit.width`, so it did not compile. I added the import and changed nothing else.
2. **Compile fix:** `CacheDrawScope` has no `center`, so I used `size.center` (import `androidx.compose.ui.geometry.center`).
3. **Arm mask is now a cut-out, not a painted plate.** The brief drew an opaque `discCentre` circle of radius 40 dp on the arm layer. On device this:
   - hid the 28 dp inner ring;
   - hid every blip closer than 40 dp (about 18% of them);
   - cut blips in half at the plate edge;
   - left a visible lighter plate that is not in the mockup.

   Spec 3.3 only says the *arm* is "masked out of the centre 40 dp radius". The arm is now an even-odd annulus path: outer edge at r minus the 2 dp disc stroke (so it sits inside the outline, as the mockup's `overflow:hidden` does), inner edge at `RadarGeometry.armMask(40 dp, r)`. The count still sits on the disc's own fill, which is `#1B2836` to 30% and only darker beyond that, so contrast cannot drop.
4. **Blips are on their own `drawWithCache` overlay above the arm**, matching the mockup's DOM order. Static disc, arm, blips, labels.
5. **`MIN_RADIUS_FRACTION` raised from 0.22 to 0.5 (deviation from spec 3.3, for the Director).** With items 3 and 4 alone, blips landed on the count and on "found" (very bad at 1022). The spec conflicts with itself: a blip band starting at 19 dp against a count that must sit on plain fill within 40 dp. At 0.5 the nearest dot centre is 43 dp out, so it clears the mask. The closest pair of the first 64 is 5.46 dp apart, so the 4 dp guard still holds (I computed this, then confirmed it with `BlipOffsetTest`). To revert, set the constant back to 0.22. That brings back hidden blips under a plate, or blips over the count with item 3.

## TDD evidence
RED (JVM):
```
> Task :app:compileDebugUnitTestKotlin FAILED
e: .../BlipOffsetTest.kt:10:80 Unresolved reference 'blipOffset'.
e: .../BlipOffsetTest.kt:14:46 Unresolved reference 'RadarGeometry'.
```
RED (device compile):
```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../RadarBlockTest.kt:28:13 Unresolved reference 'RadarBlock'.
e: .../RadarBlockTest.kt:59:50 Unresolved reference 'ArmSpinningKey'.
```
GREEN (JVM, final code, MIN_RADIUS_FRACTION 0.5):
```
BUILD SUCCESSFUL in 5s
<testsuite name="com.northwalessolutions.netprobe.ui.home.BlipOffsetTest" tests="4" skipped="0" failures="0" errors="0"
  deterministic per index / centre mask never outgrows the disc while it grows /
  radius stays within the band and inside the disc / first 64 blips are at least 4 dp apart : all passed
```
GREEN (device, Pixel 7 Pro 28221FDH300AP9, run with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, accepted). This run was on the brief's code; it was rerun green after changes 3 and 4 (16/16 together with a temporary capture class). It has **not** been rerun after change 5, which only moves blip positions, and no device assertion checks those.
```
Starting 8 tests on Pixel 7 Pro - 17
Finished 8 tests on Pixel 7 Pro - 17
BUILD SUCCESSFUL in 21s
<testsuite name="...RadarBlockTest" tests="8" failures="0" errors="0" skipped="0">
  quickScanRingShowsStopAndStopsTheQuickScan, discDoesNothingAndOnlyStopStops, settledRingStartsAScanAndCarriesTheSummary,
  normalMotionGrowsOverTheTransition, scanningShowsTheDiscTheCountAndStop, heroWithANetworkOffersTheSubnet,
  reducedMotionGrowsInOneFrameWithAStaticArm, heroWithoutANetworkDisablesTheCircleAndSaysSoOnce : all passed
```
The final capture attempt failed with "No compose hierarchies found". `dumpsys` showed `mWakefulness=Dozing` and `isKeyguardShowing=true` with a secure lock, so this was the environment, not the code. I did not try to bypass the lock. The screen is back to sleep.

Full suite (final code): `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL, 270 unit tests, 0 failures (266 + 4).

## Screenshots
- `K:\claude\netprobe\docs\qa\shots\phase2\t04-home-before-radar.png` (brief Step 5): the current Home with the old Scan button and list, as the Task 6 baseline. Uncommitted.
- For self-review only (not committed), I used a temporary capture class, now moved out of the repo to `C:\Users\blastdav\AppData\Local\Temp\claude\K--claude\91ad2c2b-1aeb-4475-884e-5bc1e72d829a\scratchpad\TmpRadarShots.kt`. Its images (changes 3 and 4, before change 5) are in `C:\Users\blastdav\AppData\Local\Temp\claude\K--claude\91ad2c2b-1aeb-4475-884e-5bc1e72d829a\scratchpad\shots\`:
  - settled: teal 3 dp ring with "Scan", "13 devices" / "Last scan 2 minutes ago". Matches mockup state 1.
  - scanning: 172 dp disc, outline, both inner rings. The arm is clipped inside the disc and cut out of the centre. "9" / "found" are centred and readable. Stop pill, progress line.
  - scanning1022: the count autosizes and stays inside the inner area (in that capture blips still overlapped it, which is why I made change 5).
  - scanningReduced: static arm at 40 degrees.
  - hero / heroNoNet: 84 dp teal circle with three muted dots; the disabled version has a `border` fill and muted label.
  - quick: ring with "Stop", "Quick scanning".
  The device files were deleted.

## Files changed (committed in ddf1ad4)
- `K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\home\RadarGeometry.kt` (new)
- `K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\home\RadarBlock.kt` (new)
- `K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\Controls.kt` (new)
- `K:\claude\netprobe\app\src\test\java\com\northwalessolutions\netprobe\ui\home\BlipOffsetTest.kt` (new)
- `K:\claude\netprobe\app\src\androidTest\java\com\northwalessolutions\netprobe\ui\home\RadarBlockTest.kt` (new)
- `K:\claude\netprobe\docs\qa\shots\phase2\t04-home-before-radar.png` (new)

## Self-review findings
- No em-dashes; no new dependencies; no downloads.
- The arm's per-frame cost is only the layer rotation. The blip overlay re-records only during the 200 to 300 ms fades and when the count changes; the disc re-records during the 360 ms size change, when the size changes every frame anyway.
- Small layout paddings (12, 8, 6, 4, 14 dp) are inline literals, as in the brief. They are candidates for `Dimens` in a later tidy.

## Concerns
- (Resolved by ruling, see above.) **The quick-scan arc was invisible.** It is a teal arc drawn on a ring that is already a full teal 3 dp stroke, so the only visible difference from idle is the "Stop" label and "Quick scanning". A suggested fix for Scout or the Director: draw the ring track in `borderSubtle` while quick scanning so the teal arc reads. Not changed, because it is a design call.
- Change 5 is a spec deviation and needs the Director's sign-off.

## Remaining after the phone is unlocked (coordinator)
1. Rerun `RadarBlockTest` on the device with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
2. Capture the radar-state screenshots, including the quick-scan ring with its dimmed `border` stroke and the teal arc.

## Fix round 1 (commit `babaca7`)
These fixes respond to Bugsy's review (`task-4-review.md`), following the coordinator's rulings. JVM and build: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL, 271 unit tests, 0 failures. The device tests compile (`compileDebugAndroidTestKotlin`), but running them is **PENDING UNLOCK**, including the new height test. The commit also includes the coordinator's own spec edits (row 227, section 3.3), picked up by `git add -A` as before.

- **I1, settle jump: fixed.**
  - The Stop pill and progress-line column is measured at its natural height and laid out at `height x fraction` (`Modifier.layout` plus `clipToBounds`). Its alpha is `graphicsLayer { alpha = fraction }`, the same animated fraction that drives the disc. The separate 120 ms delayed pill fade is gone.
  - The column is composed while the mode is SCANNING and during the settle that follows (`transition.currentState == SCANNING && targetState == SETTLED`). This is derived from the transition, with no state writes during composition. During the settle its Stop is a no-op.
  - New device test `blockHeightSlidesWithoutAStepBothWays` (autoAdvance = false) checks both directions:
    - two frames into a change, the height is still on the starting side of halfway between ring and full;
    - at 180 ms it is strictly between the ring row (64 dp) and the full height;
    - it ends exactly at full height after expanding, and at the ring row after settling;
    - after settling, `radarStop` no longer exists.
- **M1, quick arc lifecycle: fixed.** `QuickArc(animate = resumed && !reduced)`. The infinite transition exists only while the screen is resumed and motion is allowed; otherwise the arc is drawn still at 40 degrees, as the arm is.
- **M2, tap during settle: fixed.** The ring's `clickable` is `enabled = live && fraction == 0f`, so neither Scan nor stop-quick-scan works until the settle has finished.
- **M3, old blips fading back in: fixed.** The blip effect tracks the last mode it saw. When the mode enters SCANNING it resets `shownBlips` and `newestFrom` to 0 before comparing against `found`.
- **M5, blip cache re-recording every frame: fixed.** `Blips` takes `blipsAlpha: () -> Float` and reads it inside `onDrawBehind`, so the fade only redraws. The cache is rebuilt only when the blip count or the size changes.
- **M6, count width: fixed.** `Dimens.countMaxWidth` is now 80 dp, the arm cut-out diameter. No test pinned 96.
- **M9, negative index: fixed.** `radiusFraction` uses `Math.floorMod(i, MAX_BLIPS)`. New JVM test `negative index stays finite and in the band` (BlipOffsetTest now has 5 tests, all passing). It was written with the fix, so there is no separate RED run.

Not in scope, noted only: going from HERO to SCANNING leaves the disc fraction at 1. The HERO title and subtitle are swapped for the pill area in one frame, a step of roughly 30 dp. M10 (the report called the baseline screenshot uncommitted) is a documentation point only: `t04-home-before-radar.png` is in `ddf1ad4`.

## Fix round 2 (folded into the device-step commit)
- `blockHeightSlidesWithoutAStepBothWays` is tightened. Two frames into a change, it now asserts `start - ring < 12.dp` on expand and `full - settleStart < 12.dp` on settle; the midpoint checks are unchanged. Bugsy measured the pre-fix code at about 147 dp on expand and 170 dp on settle, roughly 83 dp from the start height each time, so the old step would now fail. The halfway checks it replaces were too loose to catch that.
- `Motion.PILL_FADE_MS` and `Motion.PILL_DELAY_MS` are deleted; nothing references them now. The plan doc still lists them as historical text; I left it alone.

## Device run and screenshots (commit `5764db3`)
`RadarBlockTest` on the Pixel 7 Pro (28221FDH300AP9), unlocked, with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (the property was accepted), including the tightened test:
```
Starting 9 tests on Pixel 7 Pro - 17
Finished 9 tests on Pixel 7 Pro - 17
BUILD SUCCESSFUL in 27s
<testsuite name="com.northwalessolutions.netprobe.ui.home.RadarBlockTest" tests="9" failures="0" errors="0" skipped="0" time="9.672">
  <testcase name="blockHeightSlidesWithoutAStepBothWays" time="2.21" />
  <testcase name="quickScanRingShowsStopAndStopsTheQuickScan" time="0.84" />
  <testcase name="discDoesNothingAndOnlyStopStops" time="0.924" />
  <testcase name="settledRingStartsAScanAndCarriesTheSummary" time="0.938" />
  <testcase name="normalMotionGrowsOverTheTransition" time="1.02" />
  <testcase name="scanningShowsTheDiscTheCountAndStop" time="0.886" />
  <testcase name="heroWithANetworkOffersTheSubnet" time="0.846" />
  <testcase name="reducedMotionGrowsInOneFrameWithAStaticArm" time="0.865" />
  <testcase name="heroWithoutANetworkDisablesTheCircleAndSaysSoOnce" time="0.783" />
```
- JVM and build after the change: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL, 271 unit tests, 0 failures.
- The debug build is installed on the phone with `adb install -r`: Success.

Screenshots, in `docs/qa/shots/phase2/`. These are the component rendered on the device by a temporary androidTest capture class (`captureToImage`); that class was removed before the commit. The brief names only the Home baseline, so these names are mine:
- `t04-radar-idle.png`: teal 3 dp ring with "Scan", "13 devices" / "Last scan 2 minutes ago". Matches mockup state 1.
- `t04-radar-idle-disabled.png`: no network; ring and label in `border`.
- `t04-radar-scanning.png`: 172 dp disc with its outline and both inner rings. The arm stays inside the rim and is cut out of the centre. 9 blips sit in the [0.5, 0.88] band. "9" / "found" are centred on plain fill, then the Stop pill and progress line. Matches mockup state 2, except that blips stay out of the centre (the accepted deviation).
- `t04-radar-scanning-1022.png`: 4-digit count, autosized, within 80 dp and clear of the blips; 64 blips drawn.
- `t04-radar-reduced-motion.png`: static arm at 40 degrees; otherwise the same as scanning.
- `t04-radar-hero.png`: 84 dp teal Scan circle, three muted dots, "Nothing scanned yet" / "Tap to find what is on 10.0.3.0/24".
- `t04-radar-hero-no-network.png`: circle in `border` fill with a muted label, "No network" / "Connect to Wi-Fi or Ethernet to scan".
- `t04-radar-quick-scan.png`: ring dimmed to `border` with a teal 90 degree arc over it and "Stop" in teal, "Quick scanning". The arc is now clearly visible.

Remaining note: in `t04-radar-idle-disabled.png` the "Scan" label in `border` (#3B5368 on #16202B) is faint. That is per spec 3.2 for a disabled control and is exempt from contrast minimums, but the Director may want to look at it.
