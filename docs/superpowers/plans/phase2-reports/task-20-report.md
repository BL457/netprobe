# Task 20 report: extended discovery on the Director's LAN (Bugsy)

Status: DONE_WITH_CONCERNS (checks 7 and 10 not run, Director unavailable; his confirmation of the ranges is PENDING).

Commits on phase2 (from 2110cc3):
- 6d71fb3 fix: Settings range estimate uses the measured 16 s per /24, not 25 s
- 4f9f5e9 Record extended discovery on-device QA on the Director's LAN

Device: Pixel 7 Pro 28221FDH300AP9, Android 17, wlan1 10.0.3.242/24, network row "CHi" (id 1).

## Suite tails (verbatim)

`./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` after the fix:
```
BUILD SUCCESSFUL in 15s
```
Failing test first (before the constant change):
```
> Task :app:testDebugUnitTest FAILED
ExtendedCopyTest > live cap line uses 16 s per range, measured on the Pixel 7 Pro FAILED
3 tests completed, 1 failed
BUILD FAILED in 3s
```
`./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain` (flag accepted, no SDK install or licence step):
```
> Task :app:connectedDebugAndroidTest
Starting 67 tests on Pixel 7 Pro - 17

com.northwalessolutions.netprobe.ui.HomeScreenTest > scanIsDisabledWithAMessageWhenOffWifi[Pixel 7 Pro - 17] SKIPPED

Pixel 7 Pro - 17 Tests 14/67 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 27/67 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 40/67 completed. (1 skipped) (0 failed)
Pixel 7 Pro - 17 Tests 55/67 completed. (1 skipped) (0 failed)
Finished 68 tests on Pixel 7 Pro - 17

BUILD SUCCESSFUL in 1m 3s
67 actionable tasks: 8 executed, 59 up-to-date
```
Both APKs still installed afterwards; app data intact.

## Checks

| # | Result | Time |
|---|---|---|
| 1 Toggle off | pass: 16 devices, Hue Bridge alone under "Other subnet 10.0.4.0/24", no sweep | 12 s (Phase A 12 to 13 s) |
| 2 Evidence | pass: 10.0.4.0/24 swept, 4 hosts (10.0.4.1 UniFi, .9, .17, .20 Hue), 20 devices | 29 s total, range about 15 s (x2 runs) |
| 3 Gateway probe | pass with note: hits 10.0.0/24, 10.0.2/24, 192.168.1/24, 192.168.2/24; 10.0.4.0/24 shown as "evidence" (priority de-dup, 4.5). Log "gateway probe: 271 of 271 units, 1041 connect attempts" (x3), inside 527 to 1054 | gateway phase 9.2 to 9.6 s |
| 4 Manual | pass for this network (all 4.4 messages inline, normalise and duplicate notes, manual swept first); other-network part not run (Wi-Fi switch) | 44 s run |
| 5 Cap 1 | pass: only 10.0.4.0/24 (manual) swept, "5 more ranges found, raise the cap in Settings" | 38 s |
| 6 Stop | pass: settled under 0.2 s after the tap, "Stopped at 10.0.4.0/24", partial hosts kept, no Wi-Fi lock held | |
| 7 Wi-Fi switch | not run, Director unavailable | |
| 8 Untouched 4-range run | pass: completed, 86 s; window flag KEEP_SCREEN_ON during the run, cleared after (stay-awake on USB was on, so the flag is the evidence) | 86 s |
| 9 Phantom | EMPTY, not PHANTOM: 10.0.9.0/24 is dropped by the UniFi, "swept, nothing reachable", "10.0.9.0/24: nothing found", 0 rows stored | 15.9 s |
| 10 VPN | not run, Director unavailable | |
| 11 Timings | FAIL then pass after 6d71fb3: per /24 13.7 to 16.2 s (13 sweeps, mean 15.5 s); 25 s was 61 percent high, constant now 16; probe "about 13 s" vs 9.2 to 9.6 s is within 50 percent, unchanged | |
| S Summary | pass: "4 other ranges checked" expands to six lines incl. "10.0.9.0/24  manual  swept, nothing reachable"; gone during the next scan | |
| R9 | pass at 1.0 and 1.3 (also added as row 9 in phase2-radar-ui-on-device.md) | |
| L logcat | pass: only uiautomator VM lines; one NetProbe process alive throughout; no dropbox entries (buffer cleared before checks 3, 5, 6, 8; dump covers check 8 on) | |

Passed 12 (1, 2, 3 with note, 4 partial, 5, 6, 8, 9 as EMPTY, 11 after fix, S, R9, L); failed 0 open (11 failed then fixed); not run 2 (7, 10).

Ranges found: 10.0.4.0/24 evidence 4 hosts; 10.0.0.0/24 probe 9; 10.0.2.0/24 probe 1; 192.168.1.0/24 probe 3 (USG, "Hub Manager" .254, "Redirect" .79); 192.168.2.0/24 probe, not swept (cap).

## Fixes

- 6d71fb3 `ExtendedCopy.SECONDS_PER_RANGE` 25 -> 16; `ExtendedCopyTest` (now asserts 1, 3, 4 and 16 ranges: 1, 1, 2, 5 minutes) and `ExtendedSectionTest.stepperChangesTheCapAndMarksEntriesBeyondIt` ("3 ... about 1 minute") updated together; verified on device.

## Settings restored and read back

Yes: extEnabled=false, extGatewayProbe=false, extMax=4, no extManual key, font_scale 1.15. Marks/notes unchanged (CHNAS Interesting, "QA note NAS"). No pm clear, no Full port scan, no Wi-Fi toggle, no VPN, no downloads.

## Open items for the Director

1. Confirm the grouped list and ranges above match his network (PENDING), in particular that 192.168.1.0/24 ("Hub Manager" at .254) and 192.168.2.0/24 are reachable from his LAN.
2. Checks 7 (switch Wi-Fi mid-run) and 10 (VPN) need him.
3. A range found by several sources shows only the top one (10.0.4.0/24 never shows "gateway probe").
4. Manual editor: the "Add range" button sits under the keyboard when there is no helper text.
5. After Stop: "0 other ranges checked", and a never-started range reads "stopped".
6. "N on another subnet" counts only rows seen in the latest run.
7. Probe supporting text wraps "/16" across two lines.
8. uiautomator returns a stale tree while the radar animates; worth checking with TalkBack (Part A item still pending).
9. Location permission is not granted at present (header shows the "needs location" text).
10. Spec 8.3 still says 25 s per range.
