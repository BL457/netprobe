# Task 9 report: Radar UI on-device QA and full instrumented suite (Bugsy)

Status: DONE. Branch phase2, started at adb82c6. Device Pixel 7 Pro 28221FDH300AP9, Android 17, on "CHi" 10.0.3.242. Date 2026-10-20.

Commits:
- ac89432 fix: keep segment labels on one line at font scale 1.3
- 081a0ca Record Radar UI on-device QA

QA record: `K:\claude\netprobe\docs\qa\phase2-radar-ui-on-device.md`. Evidence: `K:\claude\netprobe\docs\qa\shots\phase2\` a01..a16 (no a09: Task 20), plus b-* shots for the behaviour checks and extra a11 variants.

## Instrumented suite

Ran with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. The property was accepted (no warning) and both packages stayed installed afterwards.

Run 1, before any fix (tail verbatim, ANSI colour codes stripped):
```
> Task :app:connectedDebugAndroidTest

com.northwalessolutions.netprobe.ui.HomeScreenTest > scanIsDisabledWithAMessageWhenOffWifi[Pixel 7 Pro - 17] SKIPPED 

Pixel 7 Pro - 17 Tests 22/56 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 33/56 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 43/56 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 55/56 completed. (1 skipped) (0 failed)
Finished 57 tests on Pixel 7 Pro - 17

BUILD SUCCESSFUL in 1m 3s
67 actionable tasks: 3 executed, 64 up-to-date
```

Run 2, after the fix (final code):
```
Starting 57 tests on Pixel 7 Pro - 17

com.northwalessolutions.netprobe.ui.HomeScreenTest > scanIsDisabledWithAMessageWhenOffWifi[Pixel 7 Pro - 17] SKIPPED 

Pixel 7 Pro - 17 Tests 15/57 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 28/57 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 41/57 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 56/57 completed. (1 skipped) (0 failed)
Finished 58 tests on Pixel 7 Pro - 17

BUILD SUCCESSFUL in 56s
67 actionable tasks: 3 executed, 64 up-to-date
```
The "Finished" count is one higher than "Starting" each time, which is AGP's own reporting. 0 failed, 1 skipped by assumption, as the brief expected.

## Checklist rows (every pass was given after I opened and looked at the screenshot)

1. Cold start, 1.0 and 1.3: pass. Dark window and bars from the first frame. The splash centre is the system `ic_menu_search` icon on a large white disc (manifest icon). Bright, but not a window flash.
2. HERO: pass for the no-network HERO at both scales (disabled grey Scan circle, three static dots, "No network" / "Connect to Wi-Fi or Ethernet to scan" once, placeholder line, no chips or search). Connected HERO not captured: no `pm clear` per ruling and no never-scanned network in reach. Evidence for that state is t04-radar-hero.png, which is a component crop, not a full device frame (teal 84 dp Scan circle, "Nothing scanned yet", "Tap to find what is on 10.0.3.0/24").
3. Scanning early and late: pass at both. 2 s: 8 found, blips, Stop, "probing 3 of 254  0 s". 12 s: 12 found, "probing 251 of 254  10 s". Count stays in the plain centre. At 1.3 about 6 rows are visible.
4. Filter while scanning and Quick scan all: pass at both. The Interesting chip and the search stay visible under the disc during the scan. Quick scan all gives a dimmed ring, a teal arc with "Stop" inside and "Quick scanning". No growth.
5. Settled, after Detail, after restart: pass at both. "Scanned in 13 s, 1 on another subnet" (12 s at 1.3) is still there after Detail and back. After force-stop it reads "Last scan 46 seconds ago" (52 at 1.3).
6. Search none, filter none, no network: pass at both. `No devices match "zzzz"`, "No devices marked Ignored", and the no-network string shows once.
7. Notice: pass at both. Wi-Fi toggled mid-scan gives "Network changed, scan stopped" under the ring with an X, and the X dismisses it.
8. Sticky header: pass at both. "Home subnet 10.0.3.0/24" stays pinned while rows pass underneath (at 1.3 the Weather Station row is cut off by the opaque header), and "Other subnet 10.0.4.0/24" with Hue Bridge comes up from below.
10. Detail: pass at 1.0. At 1.3 it FAILED first: the "Investigated" status segment broke mid-word as "Investigate / d". Fixed in ac89432 and recaptured: now one line, six port rows with the note collapsed. The worst case (Custom "22,abc" with error "Not a port or range: 'abc'" and the note expanded) shows four full rows plus a partial one. The Scan pill is dimmed while the error shows, which happens after Scan is tapped (validation runs on Scan, same as t07). The scrolled view, note, Quick scan (115 of 115, found 9), port sheet and Why sheet pass at both scales.
11. Vulnerability sheet: pass at both. "OpenSSH 8.2" gives High 1 and Medium 1, CVSS chips 7.5 and 5.0. Offline (Wi-Fi and mobile data off) it shows the same results, "2 found, cached 20/10/2026 02:48". The "Offline, showing cached results from" line needs a cache entry past its TTL, so it was not reachable. An uncached query offline shows "Offline". The "Synology Inc DS220+" query gives "No CVEs found for ...". CVE dates show as raw ISO timestamps ("2020-06-01T16:15:14.260").
12. Settings: pass at both. Section rules, teal switches and sliders, Back to Home. No Extended discovery section yet (Part B).
13. Grow and settle: pass. Stepping through every frame, the grow is one continuous size and position change with no jump, and the centre circle stays inside the ring. The settle is one movement. The first recording ran out at 15 s before the 12 to 13 s scan settled, so I re-recorded with a 25 s limit (14.5 s of content, the file ends after the settle).

Behaviour checks:
- Stop: pass. The disc settled to the ring with "Scanned in 10 s, 1 on another subnet".
- Disc tap does nothing: pass. Three taps on the disc, scan still running, no ripple.
- Rotation lock: pass. requestedOrientation=SCREEN_ORIENTATION_PORTRAIT and the display stayed ROTATION_0 under a forced landscape.
- Background stops the arm, resume restarts it: pass. gfxinfo frames were 22 then 22 over 3 s in the background. On resume the arm moved and the scan continued (9 to 13).
- Animator scale 0: pass. The arm was at the same angle in two shots 1.5 s apart.
- TalkBack: not run, needs the Director. uiautomator descriptions match 7.3 (listed in the QA record).
- Jank: pass. 1466 frames, Janky 21 (1.43%). Legacy 180 (12.28%) uses a fixed 16 ms threshold on a 120 Hz panel. p50/p90/p95/p99 6/11/14/24 ms.
- AndroidRuntime: pass. All 50 lines are uiautomator launcher VM lines from my dumps, with no FATAL and no E. The logcat buffer started at 03:03, so I also checked the dropbox: no NetProbe crash or ANR ever recorded.

## Fix

ac89432 `fix: keep segment labels on one line at font scale 1.3`
- Cause: `PinnedSegmented` padded each segment label by 4 dp a side. In a quarter of a 411 dp screen, "Investigated" at 12 sp x 1.3 did not fit. The spec's 7.5 estimate (about 90 dp segment) assumed 412 dp with no padding, and the test used 412 x 820, so it never wrapped in tests.
- Test first: `DetailContentTest.segmentLabelsStayOnOneLineAtFontScale13OnAPixel7Pro` (411 x 820 dp, font 1.3) checks all four statuses and three profiles are one line with each status active. It failed on device: "Investigated lines with UNMARKED active expected:<1> but was:<2>".
- Fix: label padding 4 dp to 2 dp (`SEGMENT_TEXT_PAD` in `ui/Controls.kt`). DetailContentTest 13/13 passed. `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL. Reinstalled and recaptured a10-detail-top-130 (and -100, unchanged look). Full suite rerun green.
- Caveat: the only shots taken after the fix are a10-detail-top-100 and every 1.3 Detail shot. Earlier 1.0 Detail shots are pre-fix (2 dp less padding is not visible at 1.0).

## Settings touched and restored

Baseline: font_scale 1.15 (not 1.0), animator_duration_scale unset (null), accelerometer_rotation 1, user_rotation 0, mobile_data 1, Wi-Fi on "CHi" 10.0.3.242, TalkBack off.

Restore commands, all verified afterwards:
- `adb shell settings put system font_scale 1.15`. I restored his actual original value rather than 1.0, which the ruling assumed. If he wants 1.0, run `settings put system font_scale 1.0`.
- `adb shell settings delete global animator_duration_scale`, back to null (the default 1x). Same effect as putting 1, and it restores the exact original state.
- `adb shell settings put system user_rotation 0` and `adb shell settings put system accelerometer_rotation 1`
- `adb shell svc data enable` (mobile data was off briefly for the offline vuln check), now 1
- `adb shell svc wifi enable`. Reconnected to "CHi" 10.0.3.242 and ping to 10.0.3.1 succeeded.
- Removed /sdcard/radar.mp4 and /sdcard/ui.xml.

Incident: one tap aimed near the disc landed on "Wi-Fi (tap to show the name, needs location)" and opened the location permission dialog. I dismissed it with Back without granting anything. The permission is still not granted and not user-fixed, and the app showed its "Wi-Fi name stays hidden without location permission" notice, which I then dismissed.

No marks or notes were changed: CHNAS is still Interesting with "QA note NAS". No Full scans. No pm clear. Nothing downloaded or installed beyond the debug APK and its test APK.

## Open items for the Director

1. TalkBack pass (7.3) needs him. Not run.
2. Connected HERO on a real device frame: needs a never-scanned network, or his OK to clear data.
3. After a restart, stale rows lose their dimming because `latestRun` starts at 0. Needs a decision on whether to persist or derive it.
4. A stopped or network-cut scan still says "Scanned in N s" with the partial time. Spec section 8 has no stopped wording.
5. During the settle the fading-in text column overlaps the shrinking ring for about 300 ms (a13 frames).
6. Segment labels now sit 2 dp from the active fill edge at 1.3. Is it tight enough to want a smaller status label at 1.3 instead?
7. The splash shows the system magnifier icon on a big white disc. Icon choice is his.
8. The note stays expanded after Back hides the keyboard until focus moves elsewhere.
9. The vuln "Offline, showing cached results" line was not exercised (needs an expired cache entry). CVE dates are raw ISO strings.
10. The instrumented suite runs real discovery against the live app database (Home showed 16 devices, "last scan 42 seconds ago" after the run). It is harmless to marks but writes his data.
11. Spec 11.1 to 11.6 plus the stale edge alpha 0.6 and blip spiral deviations, all on the screenshots.
