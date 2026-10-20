# Task 6 review: HomeContent split and the Radar Home screen

Reviewer: Bugsy (QA). Range 023cd28..b7d360e (d2c38e9 code, b7d360e screenshots). Date 2026-10-20.

## Verdicts
- **Spec compliance: PASS** (one plan-text conflict with spec 3.4, stated below; behaviour matches the spec's intent on the device)
- **Task quality: Approved** (no Critical or Important findings; 5 Minor)

## Method
- Diffed every implemented file against the brief's code blocks: `HomeComponents.kt`, `HomeFakes.kt`, `HomeContentTest.kt`, `RadarStateTest.kt` and `HomeScreenTest.kt` are byte-identical to the brief. `HomeContent.kt`, `HomeScreen.kt` and `Common.kt` match except for the ruled IME change (`HomeContent.kt:50`).
- Read `RadarBlock.kt`, `Controls.kt`, `HeaderRules.kt`, `HomeCopy.kt`, `RowText.kt`, `Nav.kt` and the view model's message path to check the click and Back routes.
- Confirmed that the installed APK is this build: the on-device `base.apk` md5 `d26754a3...e0` matches `app/build/outputs/apk/debug/app-debug.apk`.
- Ran the device session below by hand with `adb shell input`. I did not run instrumented tests, install anything or edit code.
- Opened all six `t06-*.png` and compared them with the spec and the mockup strings.

## Spec compliance

| Requirement | Result | Evidence |
|---|---|---|
| `HomeActions` / `HomeContent(state, actions, progress, now, ownModel)` signature | ✅ | HomeContent.kt:22-43 |
| HomeScreen is a thin wrapper; VM surface unchanged beyond Task 5 | ✅ | HomeScreen.kt:27-49; HomeViewModel.kt has no diff in this range |
| Only Stop stops a scan; the disc has no click handler | ✅ | RadarBlock.kt:138-141: the SCANNING modifier is semantics plus tag only, with no `clickable`. Device: 3 taps on the disc mid-scan did nothing |
| Radar, pill, notice, chips and search pinned outside the LazyColumn | ✅ | HomeContent.kt:50-65 (Scaffold topBar). `scanningAtFontScale13...` asserts `radarStop` has no `deviceList` ancestor |
| `dismissMessage()` immediately before `startScan()` | ✅ | HomeContent.kt:58. Test `scanDismissesTheNoticeFirst...` checks the order. Device: a ring tap with the notice up cleared it and started the scan |
| Notice line replaces the Snackbar, with a 48 dp dismiss | ✅ | HomeComponents.kt `NoticeLine` (`Dimens.touch` box). Device dump: dismiss bounds 168x168 px = 64 dp at 2.625. No Snackbar left in `ui/home` |
| Chips and search hidden while the large disc shows with All and a blank query (D12) | ✅ | HeaderRules.kt; device scanning shot shows them collapsed. Tests cover the filter and query exceptions |
| Sticky group headers, opaque, with a 1 dp surface rule and heading semantics | ✅ | HomeComponents.kt `GroupHeader`; HomeContent.kt:86 `stickyHeader` |
| Row text from RowText / GroupHeaders / HomeGroups, no duplicated formatting | ✅ | HomeContent.kt:71,86; HostRowItem uses `RowText.dataLine`/`description`. `rowSecondaryLine`, `DeviceType.icon()` and `NetworkPanel` are gone |
| Type edge colours, gateway teal, own phone #8FA3B5, mark chip | ✅ | HostRowItem; device shows a teal gateway edge, a yellow "Interesting" chip on CHNAS and "N open" in signal |
| Stale: edge and name at 0.6 only; data line full strength | ✅ | Device rows .22/.50/.138: the name and edge are dimmed and the data line is not |
| Empty states and strings match spec 8 | ✅ | HomeCopy.kt matches the table word for word. The no-match empty state was seen in t06-home-search-nomatch.png |
| HERO placeholder drawn as a group-header line | ✅ | `HeroPlaceholder` |
| Network line: one line, no DNS suffix, menu at the right | ✅ | `networkLine`; device "10.0.3.0/24  gateway 10.0.3.1" |
| Done summary survives the Detail round trip and clears on a new scan | ✅ | Device: "Scanned in 5 s, 1 on another subnet" was still there after opening 10.0.3.1 and pressing Back |
| Test tags unique per node | ✅ | Each tag appears once in source. `radarDisc` is used in two mutually exclusive modes. List tags repeat per item, which is expected |
| No em-dashes, no new dependencies | ✅ | grep found no U+2014 in the changed files; no gradle or toml changes |
| No main-thread work added | ✅ (see Minor 2) | No I/O. Only pure list formatting during recomposition |

### Plan-text conflict (stated explicitly)
Spec 3.4 says the done summary "is held in `rememberSaveable` in `HomeContent`'s host". The brief overrides this on purpose: the summary is derived from the view model's `scanStartedAt`/`scanFinishedAt`, with no saved copy, so that it cannot survive system process death. On the device the behaviour matches the spec: it survived the Detail round trip and cleared on a new scan. One side effect differs (Minor 4). The spec text should be amended to match the brief.

## Device check log (Pixel 7 Pro 28221FDH300AP9, build b7d360e, real LAN)
Package `com.northwalessolutions.netprobe`. Location permission was denied at the start. Coordinates were taken from a uiautomator dump: hint (640,230), ring (175,529), notice X (1356,725), disc centre (720,718), Stop (720,1133).

1. Launched with `am start`. SETTLED: "15 devices / Last scan 4 minutes ago", chips and search visible, sticky "Home subnet 10.0.3.0/24".
2. **Back oddity, attempt 1:** tapped the hint. The top activity was `GrantPermissionsActivity`. After 2 s I sent KEYCODE_BACK. Result: back on Home with the notice "Wi-Fi name stays hidden without location permission" and an X. Not on Detail.
3. **Attempt 2:** dismissed the notice with the X (it disappeared), tapped the hint, and pressed Back on the dialog. Home with the notice. Not on Detail.
4. **Attempt 3:** tapped the hint with the notice still showing, then Back on the dialog. Home. Not on Detail.
5. **Attempt 4 (scan running):** tapped the ring with the notice up. The notice cleared and the scan started ("probing 3 of 254  1 s"). Then hint, then Back on the dialog. The scan had already finished: Home, "Scanned in 12 s, 1 on another subnet", notice shown. Not on Detail.
6. **Attempt 5 (Back during an active scan):** ring tap, hint after 1 s, Back 1.5 s later. Screenshot mid-scan: disc, Stop, "probing 18 of 254  2 s", notice under the progress line. Not on Detail. The scan settled to "Scanned in 13 s".
7. **Attempts 6-8 (timing):** hint, then Back after 0.2 s, 0.5 s and 1.0 s. All three ended on the identical Home frame (same md5). Not on Detail.
8. **Back on Home with the notice showing (no dialog):** the activity finished and the launcher came to the front. On relaunch the task id was new and the process was the same (pid 14502). Home showed "Last scan 1 minute ago": the done summary was gone (Minor 4). Not on Detail.
9. **Stop and the disc:** ring tap started a scan. Three taps on the disc (centre, upper-left blip area, lower-right) changed nothing: still scanning, no navigation. Tapping Stop settled the disc to the ring, "15 devices / Scanned in 5 s, 1 on another subnet", with chips and search back.
10. Detail round trip: opened 10.0.3.1 by tapping the row (Detail ports listed), pressed Back, and the summary was still there.
11. logcat: no FATAL or AndroidRuntime lines.

**Back oddity verdict: not reproduced in 8 attempts (2 with a scan running).** Code path: Home has no `BackHandler`. `onOpenHost` is reached only from `HostRowItem`'s `clickable` (HomeContent.kt:93). `NoticeLine`'s dismiss box calls only `onDismiss`. Nav.kt has no automatic navigation. The only way to reach Detail is a click on a row, by touch or by an accessibility action. The most plausible cause is a real touch landing on the list. The notice appears asynchronously when the permission result comes back and pushes every row down 64 dp. Without the notice the gateway row spans y 1185-1387 px; with it, 1353-1555 px. A finger resting on the phone or a stray tap there opens 10.0.3.1. Recorded as an observation in Minor 5.

## Findings

### Critical
None.

### Important
None.

### Minor
1. **The font-scale test threshold does not prove "three rows"** (HomeContentTest.kt:121, brief text). `h >= 186.dp` includes the sticky header. At 1.3 a row is about 63.5 dp (18 padding + 19x1.3 + 16x1.3) and the header about 44.5 dp, so three rows need about 235 dp. Failure scenario: a header change that leaves about 2.2 visible rows still passes. Fix: assert at least 3 `hostRow` nodes are displayed, or raise the threshold to about 235 dp.
2. **Grouping recomputed on every recomposition** (HomeContent.kt:46,71). `SubnetMath.of` and `HomeGroups.of` (filter, groupBy, sort) run on every recompose, which happens on the 1 s clock tick and on each 250 ms progress sample while scanning. They also produce a new `groups` list each time, so `DeviceList` never skips. Scenario: a /22 sweep with 1000+ rows means repeated O(n) allocations on the main thread several times a second. Wrap both in `remember(state.rows, state.env)`.
3. **The first rows of a scan into an empty list never animate** (HomeContent.kt:81-82, 70). `DeviceList` enters composition only when the first row arrives (`EmptyState` shows before that), and its `LaunchedEffect(state.scanning)` then captures those rows as the baseline. Scenario: the first scan after HERO, or a scan under a filter with no matches yet. The first batch appears without the rise and fade that spec 3.6 requires. Fix: hoist the baseline into `HomeContent`.
4. **The done summary is lost when the user backs out of the app while the process lives** (device step 8). The view model is scoped to the activity, Back finishes the activity, and on relaunch the summary is the idle text. Spec 3.4 says it should last "until the next scan starts or the process restarts". Arguably acceptable (the user left the app), but it is a difference from the spec text. Director's call.
5. **Notice insertion shifts the list under the finger** (HomeContent.kt:60). The notice appears asynchronously (permission result, scan end notes) and pushes every row down 64 dp. A tap aimed at one row can open the row above. This is the likely cause of Ada's unexplained Detail navigation. The placement follows spec 4.4, so this is a note for the Director, not a defect in the task.

### Notes (not counted)
- A scan stopped with Stop reports "Scanned in 5 s", the same wording as a finished scan. Spec 3.4 does not tell them apart, so this is an open question for the Director.
- `rowAnatomy`'s `TextAlphaKey == 1f` assertions on `rowData`/`hostRow` echo the constant passed to `drawnAlpha(1f)`. The pixel test `staleDataLineIsDrawnAtFullStrength` is the real check and it is valid. `RadarStateTest`'s restore step proves the "no local copy" rule. The other tests are not tautologies, and `autoAdvance = false` is used where the transition matters.
- Controller rulings checked as applied: IME union (HomeContent.kt:50, the no-match text sits above the keyboard in the shot), `scanStartedAt` used only for elapsed time, M7 left in RadarBlock.

## Screenshots compared
All six t06 shots match spec 3, 4 and 8: ring 56 dp with "Scan", disc with masked arm and blips, Stop pill, "probing N of 254  N s", sticky header with a rule, type edges, mark chip, notice with X under the radar above the chips, filter "Interesting" showing only CHNAS, and the no-match text clear of the keyboard. My device captures are in `C:\Users\blastdav\AppData\Local\Temp\claude\K--claude\91ad2c2b-1aeb-4475-884e-5bc1e72d829a\scratchpad\bugsy6\`.
