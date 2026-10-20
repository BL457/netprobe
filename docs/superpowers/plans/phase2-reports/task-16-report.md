# Task 16 report

Status: DONE. Commit: "Group Home by range and show extended progress and end-of-run notices" (on phase2, after ccd21d9; no attribution lines).

## Implemented
Brief followed verbatim: HomeCopy extended strings, ProgressLine extended phases, ExtendedNotices.kt (ExtendedNotices, RangeSummaryText, LastRun), HomeGroups.of(rows), HostRow.subnet/offSubnet, RowRules.row(extraRanges, manualRanges) via RangeRules.groupFor, HomeUiState.groups and lastRunRanges, RangeSummary composable above the list when SETTLED, AppContainer.currentNetwork, view model settings to ExtendedOptions, binderFor passed to discover(env, options, binder), notices computed in discover's finally, EXT_LOG_TAG gateway-probe log line.

## Rulings
1. ScanFinish.afterNetworkChange is wired in the env collector (clears finishedAt, lastRunRanges and lastRunProgress when the resolved network id changes). Extra hardening: the controller collector sets finishedAt to null when ScanController.state.message == NETWORK_CHANGED, because the cancelled scan's scanning-to-false transition could otherwise land after the clear (race) and leak "Scanned in N s" for the old network.
2. ProgressLine no longer prints the engine's gateway label unless it is a dotted CIDR (regex). Otherwise it uses the new HomeCopy.gatewayProbingUnnamed template "probing for gateways, N of M  S s" (added to CopyTableTest templates, doubled backslashes; covered by ProgressLineTest and the matrix test with label "other ranges"). Engine constant left untouched.
3. GatewayProbe 3-arg onProgress: no change needed, engine already uses it.
4. Null binder passed straight to the engine (it skips the phase with SKIPPED_BIND_FAILED); notice shown via ExtendedNotices.
5. Grouping derived at display time; no Room change.
6. Not done: keying the radar finish announcement on the final summary was not cheap (RadarBlock mode-transition effect), left as is.

## Deviation from the brief
CidrParser.subnetOf rejects /16 (min /20), so the brief-style check was replaced by the CIDR regex above (a /16 label is the normal case).

## RED (compileDebugUnitTestKotlin FAILED, 33 errors)
CopyTableTest/ExtendedNoticesTest: Unresolved reference 'ExtendedNotices', 'RangeSummaryText', 'LastRun', 'rangesChecked', 'capDropped'; RowRulesTest: Unresolved 'subnet', No parameter named 'extraRanges'/'manualRanges'; RowTextTest/HomeStatsTest: Too many arguments for HostRow constructor, No value passed for parameter 'localSubnet'. BUILD FAILED.
(An intermediate GREEN-phase failure: ProgressLineTest "gateway probe phase" failed at the /16 CidrParser check, fixed with the regex.)

## GREEN
- `:app:testDebugUnitTest --tests ...ui.home.*`: BUILD SUCCESSFUL (all 86 ui.home tests, including ExtendedNoticesTest 6, existing HomeViewModelTest unchanged).
- `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL.
- `connectedDebugAndroidTest` HomeContentTest on Pixel 7 Pro (leaveApksInstalledAfterRun=true accepted): 19 tests, 0 failed, BUILD SUCCESSFUL.
- Device: installed, scanned once (extended discovery left off, no settings touched): "Home subnet 10.0.3.0/24" group then "Other subnet 10.0.4.0/24" header with Hue Bridge, "Scanned in 12 s, 1 on another subnet". Screenshot: docs/qa/shots/phase2/t16-home-grouped-toggle-off.png.

## Files changed
Main: ui/home/{ExtendedNotices.kt (new), HomeViewModel.kt, RowText.kt, HomeContent.kt, HomeComponents.kt, HomeCopy.kt, ProgressLine.kt}, AppContainer.kt.
Tests: ExtendedNoticesTest (new), RowRulesTest, ProgressLineTest, CopyTableTest, RowTextTest, HomeStatsTest; androidTest HomeFakes, HomeContentTest. Plus the screenshot.

## Self-review / concerns
- No VM-level test for the NETWORK_CHANGED finishedAt clearing or env-collector wiring (HomeViewModel is hard to unit test with AppContainer; verified by reading only). ScanFinish.afterNetworkChange itself is already unit-tested.
- Extended path itself (RangeSummary with real data, notices, ranges grouping on a swept extra range) not exercised on device: the toggle arrives in Task 17.
- The notice at end of a Stop is shown even though Stop resets controller state; extraMessage is separate so it persists until dismissed or next scan.
- Edited files kept their CRLF endings.

## Fix round 1
1. I1: discover's finally publishes through RunEnd.of(currentNetworkId, runNetworkId, ...), which returns null when the run's network is no longer current; RunEndTest covers A unwinding after B.
2. I2: ExtendedNotices shows at most two per-range lines, then "N other ranges: nothing found" / "N other ranges answered for everything" (singular for 1); NoticeLine maxLines 4 with ellipsis; strings added to spec section 8 (TRUNCATED row removed) and CopyTableTest (doubled backslashes; matrix now uses 4 extra ranges).
3. I3: large-network notice only when the scan completed (RunEnd, `completed` flag); extraMessage cleared in discover only when the toggle is on; manual ranges read in the combine only when the toggle is on; test: toggle off, stopped scan gives no notice.
4. RunMemory.afterNetworkChange and ScanFinish.fromController are pure and tested (RunEndTest); VM uses them.
5. RangeSummaryText.checked counts DONE ranges only (test in ExtendedNoticesTest).
Tests: `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; HomeContentTest on device re-run (result in coordinator reply).
