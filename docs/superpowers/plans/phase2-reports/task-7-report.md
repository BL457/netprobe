# Task 7 report: DetailContent split and the Radar Detail screen

Status: DONE. Branch phase2, commits fa0e39d (code) and 0fa138e (screenshots). No attribution lines, no em-dashes, no new dependencies, no downloads.

## What was implemented
- `ui/Controls.kt`: added `ScanPill`, `PinnedSegmented`, `BackToHome` as given in the brief (imports merged into the existing block).
- `ui/detail/DetailComponents.kt` (replaced): `EditableName` (rename dialog, Save/Reset/Cancel, same rules), `DetailLinks`, `StatusSegments`, `NoteField` (same 400 ms debounce and save-on-leave rules, Done clears focus), `NoteLine` (collapsed two-line box, expands to the field, collapses on focus loss), `ScanBar` (Quick/Full/Custom, Scan/Stop pill, Custom ports field with error, reserved progress line, 2 dp progress line), `PortRowItem` (one action word, long-press sheet), `PortSheet`, `ReasonsSheet`.
- `ui/detail/DetailContent.kt` (new): `DetailActions`, `DetailContent`. Everything above the ports list is pinned in the Scaffold topBar column (`detailHeader`); only `portList` scrolls. Snackbar kept for Detail's own messages.
- `ui/detail/DetailScreen.kt`: thin wrapper wiring `DetailViewModel` to `DetailActions`.
- `ui/vulns/VulnSheet.kt` (replaced): restyled; the "Keyword matches can be noisy" disclaimer removed; failures read "<reason>, showing cached results from <time>".
- Tests: `androidTest/.../ui/detail/DetailFakes.kt`, `DetailContentTest.kt`, verbatim from the brief.

## Judgement calls (deviations from the brief's code)
1. Progress-line reserve. The brief's `heightIn(min = data.lineHeight.toDp())` failed `scanBarHeightDoesNotJumpWhenScanStarts` on the Pixel: 71.37 dp before vs 71.66 dp during the scan (1 px, pixel rounding of the laid-out text). Still `heightIn(min)` derived from the data style, but now `max(lineHeight.toDp(), measured height of one data-style line)` via `rememberTextMeasurer`, remembered per density. No fixed dp.
2. IME insets. The brief's `imePadding()` on the LazyColumn plus `contentWindowInsets = navigationBars` counted the nav bar twice: with the keyboard up the list stopped 48 dp above the keyboard (seen on device, first note-keyboard capture). Switched to Task 6's pattern: `contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime)` and no separate imePadding. Verified on the phone: list runs to the keyboard top, note field and Custom ports field are clear of the keyboard.
3. Progress line. M3 1.3 `LinearProgressIndicator` draws a gap and an end "stop" dot (visible as a dot at the right edge on device). Added `gapSize = 0.dp, drawStopIndicator = {}` so it is a plain 2 dp line as drawn.
- Port sheet repeats Copy when the primary is Copy: left as is per ruling.

## RED
```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: .../DetailContentTest.kt:27:35 Unresolved reference 'DetailActions'.
e: .../DetailContentTest.kt:27:73 Unresolved reference 'it'.
e: .../DetailContentTest.kt:33:13 Unresolved reference 'DetailContent'.
e: .../DetailContentTest.kt:91:129 Unresolved reference 'DetailContent'.
BUILD FAILED in 3s
```

## First GREEN attempt (brief code as written)
8/9 passed; `scanBarHeightDoesNotJumpWhenScanStarts` FAIL: `expected:<71.37109.dp> but was:<71.663605.dp>` (fixed by call 1).

## GREEN (final, after all three changes; leaveApksInstalledAfterRun accepted)
```
> Task :app:connectedDebugAndroidTest
Starting 9 tests on Pixel 7 Pro - 17
Pixel 7 Pro - 17 Tests 9/9 completed. (0 skipped) (0 failed)
Finished 9 tests on Pixel 7 Pro - 17
BUILD SUCCESSFUL in 22s
```
Per test (from the JUnit XML, tests=9 failures=0 errors=0): pinnedBlockStaysWhileThePortsScroll PASS, identityBlockAsDrawn PASS, portActionWordsAndTheCopyFallback PASS, longPressOpensTheSheetWithoutTheNoAppEntry PASS, whyThisIdentityOpensASheet PASS, collapsedNoteAtFontScale13ShowsSixPortRows PASS, worstCaseAtFontScale13LeavesThreePortRows PASS, scanBarHeightDoesNotJumpWhenScanStarts PASS, tappingTheListCollapsesTheExpandedNote PASS.

Full: `./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL; JVM 275 tests, 0 failures.

## Screenshots (docs/qa/shots/phase2/), all checked with Read against the mockup
- `t07-detail-top.png`: Back to Home, CHNAS title, "Synology Inc DS220+  NAS", "10.0.3.59  00:11:32:cf:4d:c5", teal links, status segments with Interesting in mark yellow, note box "QA note NAS", Quick/Full/Custom + teal Scan pill, port rows 22 Connect / 80 Open / 139 Open share / 443 Open / 445 Open share / 5000 Open. Matches the mockup's order and colours. Differences: the profile control fills the width left of the pill (mockup is content-width); the note box is 48 dp min (touch) vs the mockup's single line.
- `t07-detail-scrolled.png`: list scrolled; header and scan bar unmoved; 5357 WSDAPI shows muted "Copy" as drawn.
- `t07-detail-note-keyboard.png`: note expanded (teal focus border, 2 lines) with keyboard; scan bar and list end at the keyboard top. Done on the keyboard collapsed it (checked via uiautomator).
- `t07-detail-custom-error.png`: Custom selected, ports "22,abc", error border and "Not a port or range: 'abc'", Scan pill dimmed, keyboard up, field clear of it.
- `t07-detail-scanning.png`: Full scan running: outlined Stop pill, profile control dimmed, "4778 of 65535, found 9" right-aligned, plain teal 2 dp progress line.
- `t07-detail-port-sheet.png`: long-press on 3261 Unknown (Copy primary): Copy IP, Copy IP and port, Look up vulnerabilities for this service; no "No app installed" row.
- `t07-detail-port-sheet-http.png`: long-press on 80: Open with... (teal), Copy IP, Copy IP and port, vulnerability lookup.
- `t07-detail-why-sheet.png`: "Why this identity" sheet with the real reasons and "Found as: CHNAS (NetBIOS), CHNAS (UPnP)".
- `t07-vuln-sheet.png`: restyled vulnerability sheet, query field, Search again, "No CVEs found for Synology Inc DS220+"; no disclaimer line.
- `t07-detail-rename.png`: restyled rename dialog (Name field, Cancel, Save).
logcat: no AndroidRuntime crash for the app during the run.

## Files changed
- app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt
- app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt
- app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt (new)
- app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt
- app/src/main/java/com/northwalessolutions/netprobe/ui/vulns/VulnSheet.kt
- app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailFakes.kt (new)
- app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt (new)
- docs/qa/shots/phase2/t07-*.png (10 files)

## Self-review
- Scan/Stop lives only in the topBar column; test asserts it has no `portList` ancestor.
- Strings come from DetailText helpers (actionWord, primaryAction, actionDescription, sheetActions, versionLine, identityWithType, detailProgress); nothing re-derived.
- No `return` inside expression bodies (VulnSheet's `OkResult` early return is in a block body).
- Controls.kt carried no segmented control or chips before this task (the caller's note said Task 4 had them); `PinnedSegmented` is new, not a duplicate.

## Concerns
1. Scan to Stop changes the pill width by a few dp, so the profile control's right edge shifts slightly when a scan starts (height does not change). A `widthIn(min)` on the pill visual would fix it; not done without a ruling.
2. The Custom ports field uses the default text keyboard; a number/punctuation keyboard might suit it better (MVP behaviour unchanged).
3. The device run did a real Full scan of CHNAS (stopped about 7%), which stored two new open ports (3261, 3262 "Unknown") in the Director's data, plus one NVD query for "Synology Inc DS220+".
4. Spacing between the pinned sections uses the brief's values; on the device the gap between the note box and the scan bar is tighter than the mockup's 10 px, which reads fine but is noted.

## Fix round 1 (commit 6e1d7ba)
Source: task-7-review.md (Bugsy), items I1, I2, pill width (M1), note cursor (M2). Nothing else changed.

- I1: the action word box now uses `combinedClickable(role = Button, onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")`, matching the row body. New test `longPressOnTheActionWordOpensTheSheetAndRunsNothing`. Device: a long press on "Connect" (port 22) opened the sheet "10.0.3.59:22 SSH", and NetProbe stayed the resumed activity (no JuiceSSH).
- I2: removed `imeAction = Done` and the `onDone` handler from `NoteField`, so the IME action is Default. Device: imeOptions 0x42000000 (was 0x2000006), and Gboard shows the Enter key (`t07-fix1-note-enter.png`). Collapse still happens on focus loss. No existing test pressed Done.
- Pill width: `ScanPill` lays out both "Scan" and "Stop". The unused one gets `alpha(0f).clearAndSetSemantics {}`, so the width is max(Scan, Stop) at any font scale and the merged text is just the visible label. New test `scanPillKeepsItsWidthWhenItTurnsToStop`.
- Note cursor: `NoteField` keeps a `TextFieldValue` (`rememberSaveable(hostId, stateSaver = TextFieldValue.Saver)`) with `selection = TextRange(note.length)`. The save and debounce rules are unchanged and still keyed on the text. New test `expandedNotePutsTheCursorAtTheEnd`. Device: the cursor sits after "QA note NAS".

RED: the 3 new tests failed for the expected reasons:
- the long press did not show the sheet
- the pill left edge moved from 323.80 to 325.56 dp
- TextSelectionRange was not (11, 11)

GREEN: DetailContentTest 12/12 passed (tests=12 failures=0 errors=0), run with leaveApksInstalledAfterRun. JVM 275 tests, 0 failures, and `assembleDebug` succeeded. The build is installed on the Pixel. logcat shows no crash.
