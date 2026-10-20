# Task 7 review: DetailContent split and the Radar Detail screen

Reviewer: Bugsy. Range b7d360e..0fa138e (fa0e39d code, 0fa138e screenshots). Device: Pixel 7 Pro 28221FDH300AP9, build 0fa138e (installed 2026-10-20 02:17), system font scale 1.15.

Method: every main and test file was diffed line by line against the brief's code blocks. The only differences are the three controller-ruled deviations (progress reserve `max(lineHeight, measured line)` via `heightIn(min)`, `navigationBars.union(ime)` with no list `imePadding`, `gapSize = 0.dp, drawStopIndicator = {}`). Tests and fakes are verbatim. I read all ten t07 screenshots against `radar-layout.html`, then ran the device flow by hand.

## Verdicts

- Spec compliance: ✅ Compliant (one partial, finding I1)
- Task quality: Needs fixes (2 Important, both plan-text conflicts: the brief's code mandates the behaviour)

## Spec compliance

| Requirement | Result |
|---|---|
| `DetailContent(state, actions, startNoteExpanded)` and `DetailActions` exactly as the brief's interface | ✅ |
| `DetailScreen` is a thin wrapper, view model surface unchanged (no VM diff in range) | ✅ |
| `ScanPill`, `PinnedSegmented`, `BackToHome` added to `ui/Controls.kt`, reused by Detail, not duplicated (`PILL_STROKE` shared with `StopPill`; Settings' M3 segmented row is untouched and out of scope) | ✅ |
| Pinned block in the Scaffold topBar (`detailHeader`); `portList` is the only scrolling part; pill and profile control are never inside the LazyColumn (test asserts no `portList` ancestor; device scroll confirms) | ✅ |
| Back to Home row, 48 dp, chevron, `data` / `textMuted` | ✅ |
| Name, one line, tap opens the restyled rename dialog (Save, Reset, Cancel), "Rename, <name>" description | ✅ |
| Identity line via `identityWithType(IdentityLine.of(h), type)`; data line ip + MAC | ✅ |
| Links: "Why this identity" (always a sheet), "Look up vulnerabilities" | ✅ |
| Status: four equal segments, exactly one active in `mark` (device: Interesting > Investigated > Interesting, one active each time) | ✅ |
| Note: collapsed box, "Add a note" when empty, tap swaps to the field in place, focus loss collapses, `testTag("note")` on whichever is showing | ✅ (see M2, M3) |
| Scan bar: Quick/Full/Custom, Scan/Stop pill tagged `detailScanButton`, profile control disabled at alpha .5 while scanning | ✅ |
| Custom: ports field, invalid entry shows `error` text and disables the pill | ✅ (error appears on Scan tap, see M5) |
| Progress text from `detailProgress`, right-aligned; reserved height via `heightIn(min)`; plain 2 dp line | ✅ |
| Port row: 52 dp number column, service, `versionLine`, one word from `actionWord`, tap runs `primaryAction`, description from `actionDescription` | ✅ |
| Long-press opens the port sheet with `sheetActions` + "Look up vulnerabilities for this service" | Partial: row body yes, action word no (I1) |
| No "No app installed" row; no disclaimer line anywhere (grep: only the VM's snackbar text and a KDoc comment remain) | ✅ |
| Vulnerability sheet restyled, "<reason>, showing cached results from <time>", errors in `textMuted` | ✅ |
| Detail keeps its Snackbar for its own messages | ✅ |
| Tests: 9 meaningful tests from the plan; `autoAdvance = false` not needed (Detail has no infinite animation; the determinate indicator settles) | ✅ |
| No em-dashes in the diff, no Gradle/TOML changes, no new dependencies | ✅ |
| No main-thread work added (one `TextMeasurer.measure` of a short probe string, remembered per density) | ✅ |

## Device check log (Pixel 7 Pro, font scale 1.15)

| Step | Result |
|---|---|
| Launch, Home shows 15 devices, tap CHNAS | Pass. Detail matches t07-detail-top (CHNAS, "Synology Inc DS220+  NAS", "10.0.3.59  00:11:32:cf:4d:c5", Interesting active, "QA note NAS") |
| Status Interesting > Investigated > Interesting | Pass. One segment active each time; DB afterwards: status INTERESTING |
| Tap note | Pass with M2: field swaps in place, teal focus border, keyboard up, list ends at keyboard top. Cursor lands at index 0, before "QA". IME reports inputType 0x28001 (multi-line) with imeOptions 0x2000006 (action Done): Gboard shows a check key, no Enter (I2) |
| Back to dismiss keyboard | Keyboard hides; field stays expanded and focused (M3) |
| Re-show keyboard, tap the check (Done) key | Pass: note collapses, text unchanged ("QA note NAS" in the DB afterwards) |
| Custom, type "22,abc", tap Scan | Pass: matches t07-detail-custom-error. Error border, "Not a port or range: 'abc'", pill dimmed; a second tap on the pill does nothing (no scan). Text keyboard (inputType 0x8001) |
| Back to Quick | Pass: Custom field and error gone, pill enabled |
| Quick scan | Pass: outlined Stop pill, profile control dimmed, "112 of 115, found 9", progress line; finishes in under a second on CHNAS |
| Quick scan then immediate Stop | Pass: pill back to Scan; `lastPortScan` for CHNAS unchanged in the DB, so the run was cancelled before `markScanned` |
| Long-press port 22 row body | Pass: sheet "10.0.3.59:22 SSH", Open with..., Copy IP, Copy IP and port, Look up vulnerabilities for this service; Back dismisses it |
| Long-press the "Connect" word on the same row | FAIL (I1): no sheet; on release it launched JuiceSSH (Choose Identity dialog for 10.0.3.59). Cancelled, returned to NetProbe |
| "Why this identity" | Pass: sheet with the real reasons and "Found as: CHNAS (NetBIOS), CHNAS (UPnP)"; Back dismisses |
| Back | Pass: Home, 15 devices |
| logcat | No FATAL EXCEPTION / AndroidRuntime crash for the app |

Data touched: two Quick scans of CHNAS (one completed, one cancelled), as allowed. No Full scan, no note or status left changed, no installs. Captures in `C:\Users\blastdav\AppData\Local\Temp\claude\K--claude\91ad2c2b-1aeb-4475-884e-5bc1e72d829a\scratchpad\dev\`.

Rendering differs from the committed screenshots only in M6 (a pill ripple halo, visible in the capture right after tapping).

## Findings

### Critical
None.

### Important

**I1. Long-pressing the action word runs the primary action instead of opening the sheet.** `ui/detail/DetailComponents.kt:195`. The word box has its own plain `clickable` (48 dp wide, right edge of the row), so a long-press there is not seen by the row's `combinedClickable` (`:186`), and `clickable` fires `onClick` on release however long the press was. Failure scenario (reproduced on the device): the user long-presses "Connect" on port 22 to reach "Copy IP and port"; on release JuiceSSH opens and starts connecting to the NAS. On an "Open" row it opens the browser; on a "Copy" row it overwrites the clipboard. The right edge is where a thumb naturally lands. Fix: give the word box `combinedClickable(onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions", role = Role.Button)`, and add a test that long-presses a `portAction` node and asserts the sheet shows and `actions` is empty. Plan-text conflict: the brief's code uses plain `clickable` here.

**I2. The note field can no longer take a line break.** `ui/detail/DetailComponents.kt:117`. `KeyboardOptions(imeAction = ImeAction.Done)` on a multi-line field replaces Gboard's Enter key with Done (device: inputType 0x28001, imeOptions 0x2000006, check key shown). The MVP field had a newline key. Failure scenario: the user wants a two-line note ("admin on 5000" / "firmware 7.2"), presses the key at bottom right expecting a newline, and the note collapses instead. Notes saved earlier with line breaks still show, but no new ones can be entered on the phone. Spec 5.1 item 7 says "the existing multi-line field (2 to 4 lines)". Fix options: drop `imeAction = Done` (Enter inserts a newline; collapse happens through a list tap, the Back route in M3, or leaving the screen), or keep Done and accept single-paragraph notes. Plan-text conflict: the brief mandates Done. Needs a controller or Director ruling.

### Minor

**M1. Stop pill is narrower than Scan (Ada concern 1).** `ui/Controls.kt:76-81`. Measured on the device, the profile control's right edge moves about 3 dp when a scan starts. A fixed `widthIn(min)` is cheap but can clip or under-reserve at other font scales. A cheaper robust fix: draw both labels in the pill's inner Box and give the inactive one `alpha(0f)` with `clearAndSetSemantics {}`, so the width is max(Scan, Stop) at any scale. Worth doing; not a blocker.

**M2. Expanding a non-empty note puts the cursor at the start.** `ui/detail/DetailComponents.kt:115` with `:131` (`requestFocus`). Device: the cursor is before "QA". Failure scenario: the user taps the note to add to it and types "checked 20/10"; the result is "checked 20/10QA note NAS". Fix: hold a `TextFieldValue` with `selection = TextRange(text.length)` in `NoteField` (same `rememberSaveable(hostId)` key with `TextFieldValue.Saver`).

**M3. Dismissing the keyboard with Back leaves the note expanded and focused.** `ui/detail/DetailComponents.kt:128-129`. Focus is not lost, so the field stays open (teal border, no keyboard) until Done, a tap on the list, or leaving the screen. Taps on the header controls (segments, pill, links) also leave it open, and with no ports the empty state has no tap-to-collapse (`DetailContent.kt:64` vs `:66`). This is the device behaviour Bugsy was asked to check; it reads as a stuck field rather than a crash.

**M4. Custom ports field uses the full text keyboard (Ada concern 2).** `ui/detail/DetailComponents.kt:162`. `KeyboardType.Number` would suit digits, but Gboard's number pad may lack ',' or '-', which the field needs ("22,80,8000-8100"). Only change it after checking those keys on this phone. Advisory.

**M5. The Custom error appears only after tapping Scan.** The view model (unchanged, as required) validates in `startScan` and `setCustomPorts` clears the error, so while "22,abc" is being typed the pill stays enabled until the first tap. Spec 5.1 item 9 can be read as live validation. Acceptable under the "VM unchanged" rule; noted so it isn't mistaken for a UI bug.

**M6. Scan pill ripple is larger than the pill.** `ui/Controls.kt:74`. The ripple is clipped to a circle on the 48 dp target, not the 40 dp visual, so a dark halo shows around the pill just after a tap (seen in the post-Stop capture). Cosmetic. Move `clip(CircleShape)` and the indication onto the visual, or accept it.

**M7. The profile control fills the width beside the pill; the mockup draws it content-width with 12 dp padding** (also noted by Ada). This is not listed in spec section 12, so add a row there, or make the control content-width.

## Ada's concerns assessed
1. Stop narrower than Scan: confirmed on the device (about 3 dp). The cheap fix is the invisible-twin label (M1), not a fixed min width.
2. Text keyboard for Custom ports: confirmed (inputType 0x8001). Low value. Only switch after the ',' and '-' keys are confirmed on Gboard's number pad (M4).

## Re-review, round 1 (commit 6e1d7ba)

Scope: I1, I2, pill width (M1) and note cursor (M2) only; other minors deferred by ruling. Diff read in full; device build installed 2026-10-20 02:30.

Verdict: all four addressed, no regression found.

| Item | Code | Device | Result |
|---|---|---|---|
| I1 long press on the action word | `DetailComponents.kt` word box now `combinedClickable(role = Button, onClick = run, onLongClick = onLongPress, onLongClickLabel = "More actions")`, same as the row body | Long press on port 22 "Connect": sheet "10.0.3.59:22 SSH" opened, NetProbe stayed the resumed activity | ✅ |
| I1 regression: tap on the word | `onClick = run` unchanged | Short tap on "Connect" launched JuiceSSH as before (cancelled) | ✅ |
| I2 note line break | `imeAction = Done` and `onDone` removed, default action | imeOptions 0x42000000 (was 0x2000006), Gboard shows Enter; Enter inserted "\n" after "NAS" and the field stayed open; Backspace restored it; DB note afterwards exactly "QA note NAS" | ✅ |
| M1 pill width | Both labels stacked in the pill, unused one `alpha(0f).clearAndSetSemantics {}`; `detailScanButton` tag and clickable unchanged on the outer box | Not re-run on device (out of scope); covered by the new test | ✅ |
| M2 note cursor | `TextFieldValue` with `TextRange(note.length)`, `rememberSaveable(hostId, TextFieldValue.Saver)`; debounce and save-on-dispose still keyed on `value.text` | Cursor after "QA note NAS" | ✅ |

Regression checks: the sheet still opens from the row body (unchanged `combinedClickable` on the Row); collapse on focus loss is unchanged in `NoteLine`, and `tappingTheListCollapsesTheExpandedNote` is in the 12/12; the pill tags are unchanged; no FATAL EXCEPTION in logcat.

New tests: all three are meaningful, not tautologies. Ada reports each RED for the expected reason.
- `longPressOnTheActionWordOpensTheSheetAndRunsNothing` long-presses the `portAction` node itself, then asserts both that the sheet shows and that no action ran.
- `scanPillKeepsItsWidthWhenItTurnsToStop` compares bounds across the state flip. It also asserts the merged text is "Stop", which proves the hidden label is cleared from semantics. It only checks font scale 1.0, which is acceptable.
- `expandedNotePutsTheCursorAtTheEnd` checks `TextSelectionRange` on the editable node.

Data: the Director's CHNAS note and status are unchanged; no scans were run in this round.
