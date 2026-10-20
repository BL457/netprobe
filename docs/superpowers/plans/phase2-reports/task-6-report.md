# Task 6 report: HomeContent split and the Radar Home screen

Status: DONE
Commits: d2c38e9 "Split HomeContent and restyle Home with the radar, grouped rows and notice line"; b7d360e "Task 6 device run and Home screenshots" (screenshots only). Branch phase2, parent 023cd28.

## What was implemented
The brief's code was used verbatim, with one fix described under judgement calls:
- `ui/home/HomeContent.kt` (new): `HomeActions`, stateless `HomeContent(state, actions, progress, now, ownModel)`. The pinned header holds the network line, radar block, notice line, chips and search, all inside the Scaffold topBar. The body holds the HERO placeholder, the empty states, or a `LazyColumn` with sticky group headers, type-edge rows and the row-arrival animation (skipped under reduced motion).
- `ui/home/HomeComponents.kt` (replaced): `EdgeColorKey`/`TextAlphaKey` test hooks, `drawnAlpha`, `networkTitle`, `networkLine`, `NetworkLine`, `HomeMenu`, restyled `FilterRow`/pills, `SearchField`, `NoticeLine` (48 dp dismiss), `GroupHeader`, `TypeEdge`, `MarkChip`, `HostRowItem`, `HeroPlaceholder`. `networkDetailLine` and `HostStatus.label()` are kept. Removed: `DeviceType.icon()`, `rowSecondaryLine`, `NetworkPanel`.
- `ui/home/HomeScreen.kt` (replaced): a thin wrapper with the VM, the 250 ms sampled progress latched for the settle, a 1 s / 30 s clock and the location launcher. No Snackbar.
- `ui/Common.kt`: `EmptyState` restyled, with `pad` now defaulted. Detail's existing call still compiles.
- `RowRulesTest.kt`: the `secondary line appends the other subnet` test is deleted.
- Tests: `HomeFakes.kt`, `HomeContentTest.kt`, `RadarStateTest.kt` and a replaced `HomeScreenTest.kt`, all verbatim from the brief.

## Judgement calls
1. **IME insets (deviation from the brief, one line):** the brief's Scaffold used `contentWindowInsets = WindowInsets.navigationBars`. On the device, typing a no-match query put "No devices match "zzz"" behind the keyboard, because the empty state centres in the full body under the IME. With the keyboard open the last rows were also unreachable. Changed to `WindowInsets.navigationBars.union(WindowInsets.ime)` and verified on the device (t06-home-search-nomatch.png). All tests re-ran green afterwards. No automated test covers this because IME-in-test is unreliable, so it is device-verified only.
2. **M7, the finish announcement keyed on the final summary: left as is.** The announcement lives in `RadarBlock`'s `LaunchedEffect(mode)`. This task's wiring passes `summary` straight through and gives no cheap hook. A fix would need a pending flag plus a short debounce on `summary.headline` inside RadarBlock (a Task 4 file). Still deferred.
3. `stickyHeader` needed no opt-in, and `DeviceConfigurationOverride` needed none in the test (the compiler did not ask).
4. Detail and Settings still use Snackbars. D17 covers Home only, so they were left untouched.

## RED evidence (device compile, HomeContentTest)
```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../HomeContentTest.kt:28:78 Unresolved reference 'HomeContent'.
e: .../HomeContentTest.kt:106:50 Unresolved reference 'EdgeColorKey'.
e: .../HomeContentTest.kt:112:80 Unresolved reference 'TextAlphaKey'.
e: .../HomeFakes.kt:35:18 Unresolved reference 'HomeActions'.
e: .../RadarStateTest.kt:25:46 Unresolved reference 'HomeContent'.
BUILD FAILED in 2s
```
(Same compile failure blocks RadarStateTest, as the brief predicted.)

## GREEN evidence (Pixel 7 Pro, Android 17, every run with leaveApksInstalledAfterRun=true)
HomeContentTest: `tests="16" failures="0" errors="0" skipped="0"`, `BUILD SUCCESSFUL`
- rowAnatomy, tappingARowOpensIt, emptyStatesUseTheSpecWording, pinnedHeaderStaysWhileTheListScrolls, settledWithNoNetworkSaysSoOnTheNetworkLine, scanningAtFontScale13LeavesThreeRowsAndStopIsOutsideTheList, staleDataLineIsDrawnAtFullStrength, scanDismissesTheNoticeFirstAndTheNoticeDismisses, scanningWithAllAndBlankQueryCollapsesChipsAndSearch, heroWithoutANetworkSaysSoOnce, settledShowsTheRingAndTheSummary, searchStaysWhileScanningWithAQuery, aFinishedScanThatFoundNothingStaysSettled, chipsStayWhileScanningWithAFilter, otherSubnetGetsItsOwnStickyHeader, heroHidesChipsAndSearchAndShowsThePlaceholder: all passed.

RadarStateTest: `tests="2" failures="0" skipped="0"`. doneSummaryFollowsTheViewModelStateAndClearsWhenANewScanStarts and aFreshStateWithoutAFinishedRunShowsTheIdleText both passed.

HomeScreenTest: `tests="3" failures="0" skipped="1"`. radarIsPinnedAndChipsFollowTheHeroRule passed, scanTogglesToStopWhenOnWifi passed, scanIsDisabledWithAMessageWhenOffWifi SKIPPED by assumption (phone on Wi-Fi).

All three suites were re-run after the IME fix with identical results.

JVM + build: `./gradlew :app:testDebugUnitTest :app:assembleDebug` gave BUILD SUCCESSFUL, 275 JVM tests, 0 failed, 0 skipped.

## Screenshots (K:\claude\netprobe\docs\qa\shots\phase2\), Pixel 7 Pro 1440x3120, real LAN data
- `t06-home-settled.png`: idle Home with results. Network line "Wi-Fi (tap to show the name, needs location)" / "10.0.3.0/24  gateway 10.0.3.1" with the overflow menu, 56 dp teal ring "Scan", "15 devices / Last scan 30 seconds ago", chips (All selected, surface fill), search field, sticky "Home subnet 10.0.3.0/24" with a 1 dp rule, rows with 4 dp type edges (teal gateway, purple media, blue NAS, orange IoT, pink server, grey unknown), yellow "Interesting" mark chip, teal "N open". Matches radar-states.html tokens.
- `t06-home-scanning.png`: disc at 172 dp, arm mid-sweep, 8 blips, "8 found", Stop pill, "probing 26 of 254  3 s". Chips and search collapsed (All + blank query), list pinned below. Stale rows show a dimmed name and edge with the data line at full strength. Matches the mockup.
- `t06-home-done.png`: settled after the scan, "15 devices / Scanned in 13 s, 1 on another subnet" (done summary from the VM).
- `t06-home-notice.png`: notice line "Wi-Fi name stays hidden without location permission" with an X under the radar, above the chips. Triggered by the location prompt dismissed with Back (does not count as a denial). The dismiss target was then exercised on the device and cleared the notice.
- `t06-home-filter.png`: "Interesting" chip selected, only CHNAS listed under the Home subnet header.
- `t06-home-search-nomatch.png`: query "zzz" with the keyboard open, "No devices match "zzz"" centred above the keyboard (after the IME fix). Also shows "Last scan 3 minutes ago" after the reinstall, confirming the done summary clears on process restart (spec 3.4).
- logcat: no FATAL / AndroidRuntime crash lines for the app.

## Files changed
- app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt (new)
- app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt
- app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt
- app/src/main/java/com/northwalessolutions/netprobe/ui/Common.kt
- app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowRulesTest.kt
- app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt
- app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeFakes.kt, HomeContentTest.kt, RadarStateTest.kt (new)
- docs/qa/shots/phase2/t06-*.png (6 new)

## Self-review
- No em-dashes in any changed file (checked by script). No attribution lines in commits. No downloads and no new dependencies.
- No new compile warnings in the touched files. The only warning, HomeViewModel.kt:164 (String? vs String), predates this task.
- Scan/Stop controls are in the topBar, never in the LazyColumn (test-enforced). Tapping the disc does nothing. The ring's onScan calls dismissMessage before startScan (test-enforced).
- `scanStartedAt` is read for elapsed time only. When not scanning, the progress line is hidden, so the session-wide value is harmless. The summary uses `RadarSummaries.settled`, which needs both start and finish.

## Concerns
1. **Unexplained navigation, not reproduced:** in one capture sequence (tap the location hint, wait 2 s, KEYCODE_BACK on the permission dialog) the app was on Detail for 10.0.3.1 two seconds later. Logcat shows no adb input besides the tap and the Back, and Nav.kt has no automatic navigation. Two exact repeats did not reproduce it. Most likely a stray physical touch on the phone (physical touches do not appear in the adbd log), but it is worth Bugsy repeating the location-hint flow by hand.
2. M7 (stale count in the finish announcement) is still open in RadarBlock, see judgement call 2.
3. The IME fix is device-verified only, with no automated regression test.
4. The heads-up notification policy on the phone can cover the header in captures. The first scanning shot was retaken for this reason.
