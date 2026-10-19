# Radar UI spec: Gruff review

Date: 2026-10-19. Reviewer: Gruff (Devil's Advocate, partial veto). Spec under review: `2026-10-19-radar-ui-design.md`. Baseline read: master code under `app/src/main/java/com/northwalessolutions/netprobe/ui/**`, `MainActivity.kt`, `res/values/themes.xml`, `AndroidManifest.xml`, `engine/discovery/DiscoveryOptions.kt`, `actions/*`, the three agreed mockups, MVP spec 5.7 and extended-discovery spec 8.

## Verdict

**PROCEED WITH FIXES.** The direction is faithful to what the Director picked and the contrast numbers are correct, but the spec cannot go to planning as written. Fix before the plan: finding 1 (BLOCKER: landscape breaks the pinned rule the spec calls a hard requirement), and the MAJORs 2 to 10 (one claimed field does not exist and the "two read-only additions" scope is understated; port action words live in the out-of-scope actions package; the Material colour mapping has a name collision that will paint the app the wrong slate and the "already edge-to-edge" claim is false; hiding chips while scanning hides an active filter; the Detail worst case at 1.3 leaves two port rows; the spec owns all Home wording but leaves several strings unstated; the tests cannot be written against the current screen structure; the radar animation omits the horizontal move). The MINORs can be folded in during the same edit.

## Findings

### 1. BLOCKER: landscape violates the pinned rule (spec 7.5, 1, 10.3)

What is wrong: the spec only budgets portrait, yet rotation is supported (`AndroidManifest.xml` line 32 `configChanges="orientation|screenSize"`, no `screenOrientation`) and the spec's own checklist rotates mid-scan.

Evidence (Pixel 7 Pro landscape, about 891 x 411 dp, roughly 360 dp usable between bars):
- Detail pinned block at font scale 1.0: 14 top + 48 back + 26 title + 17 identity + 16 data + 48 links + 12 + 48 status + 10 + 36 note + 78 scan bar + 18 progress = about 371 dp. At 1.3 about 403 dp. The block is a `Scaffold` topBar `Column` and the scan bar is its last child, so it is clipped off the bottom: Scan/Stop unreachable. Today's Detail header is about 336 dp and only just fits; this design adds the back row, the 48 dp links row and 48 dp segments.
- Home SCANNING at 1.0: about 330 dp header, leaving about 30 dp of list: zero rows while devices "arrive below".

Fix: add a section "Compact height (available height under 480 dp)", decided by the Director:
- Option A (recommended, UI only): Home keeps the 56 dp ring while scanning (no disc growth), showing the found count and progress text beside it; Detail pins only back, name and the scan bar, and moves identity, links, status and note into the first item of `portList` (the standing rule pins Scan/Stop and the scan bar, not status or note).
- Option B: lock portrait (`android:screenOrientation="portrait"`), which is a manifest change outside the spec's stated scope and needs the Director's yes.
Add a Compose test with `DeviceConfigurationOverride.ForcedSize(DpSize(891.dp, 360.dp))` asserting `detailScanButton` and `scanButton` are displayed and fully inside the root bounds.

### 2. MAJOR: `errorCount` does not exist (spec 3.4, 8, 9, 10.1)

What is wrong: section 9 says `errorCount` is "already in `DiscoveryStatus`". It is not: `data class DiscoveryStatus(val probed, val total, val found, val clampedFrom, val neighbourAvailable)` (`engine/discovery/DiscoveryOptions.kt` line 8). Producing ", 3 errors" needs an engine change, which section 9 forbids. (Extended-discovery spec 8.2 "the error count is appended as today" is wrong for the same reason.)

Fix: delete the ", M errors" variant from 3.4, the copy table, and the `ScanSummaryText` test, or explicitly move it to the engine work of a later spec. Do not leave it for the implementer to discover.

### 3. MAJOR: the "two read-only additions" scope is understated (spec 3.1, 3.2, 3.3, 3.4, 9)

What is wrong and evidence, from `HomeViewModel.kt`:
- `HomeUiState.rows` is already filtered and searched (`HomeFilter.apply(rows, f, q)` inside the combine). The UI cannot know the unfiltered host count, so HERO ("hosts counted before filter and query"), the headline "13 devices" and "1 on another subnet" are all wrong whenever a filter or query is active. A third field is needed.
- `status` is set to `null` the moment scanning stops (`if (cs.scanning) st else null`), so the final found count is gone in the very emission that triggers the settle. The UI must latch the last non-null status; the spec does not say so.
- Scan duration "measured in the UI" breaks when the Director opens a device mid-scan and comes back: Home recomposes with `scanning` already true, so the start is never observed. The VM already holds the start time (`latestRun = runId = System.currentTimeMillis()` at start).
- "Quick scanning 4 of 13": `quickScanAllOn` exposes no progress. The fallback is mentioned in passing, but the copy table lists the count as the string.
- Quick-scan failure copy "2 devices could not be scanned" lives in the VM (`"Quick scan finished, $failed device(s) could not be scanned"`), so the copy table cannot be met without touching the VM.
- `lastScanAt = max(lastDiscoveryRun)` is the run's start time, not its end, so "Last scan 2 minutes ago" is early by the scan duration (minor, but state it).
- The headline is undefined: stored hosts including stale ones (list count) or `found` this run? The mockups show both as 13 because nothing is stale.

Fix: replace the section 9 allowance with an exact list of read-only `HomeUiState` fields: `lastScanAt: Long?`, `totalHosts: Int` (unfiltered), `otherSubnetHosts: Int` (unfiltered, not stale), `scanStartedAt: Long?` (exposes the existing `latestRun`). State that the UI latches the last non-null `DiscoveryStatus`. Define the headline as `totalHosts`. Drop the quick-scan count (copy is "Quick scanning") and keep the VM's existing failure string, or list the one-line VM string change explicitly.

### 4. MAJOR: port action words come from the `actions` package (spec 5.2, 8, 9)

What is wrong: the spec wants "Open", "Connect", "Open share" and a sheet entry "Copy IP and port". The labels are produced by `ActionProvider` (`"Open in browser"`, `"Connect with SSH app"`, `"Open with FTP app"`, `"Copy IP:port"`, `"Copy IP"`, and `"No app installed for ssh://"` for unhandled schemes), which section 9 puts out of scope and which `ActionProviderTest` pins. An implementer meeting this will edit `ActionProvider`.

Fix: specify a pure UI helper `actionWord(a: DeviceAction): String` keyed on the `OpenUri.uri` scheme (http/https/ftp to "Open", ssh/telnet/vnc/rdp to "Connect", smb to "Open share"), plus a sheet-label mapping, with a JVM test; state that the sheet filters out the "No app installed" entry exactly as `PortRowItem` does today; state that `ActionProvider` and its test are untouched. Same pattern for the Detail identity line ("Synology DS220+  NAS"): spell out that the type label is appended only when `IdentityLine.of` did not already end with it (otherwise "Android Phone  Phone").

### 5. MAJOR: Material colour mapping and system bars (spec 2.1, 2.5)

What is wrong:
- Token `surface` is #233447, and the spec says a Material `darkColorScheme` is "built from" the tokens. `MainActivity` wraps everything in `Surface(Modifier.fillMaxSize())`, which paints `colorScheme.surface`. A literal mapping paints every screen #233447 instead of #16202B. Sheets, menus, dialogs and text fields also pull `surfaceContainer*` roles, and M3 tonal elevation tints via `surfaceTint`.
- "The app is already edge-to-edge; verify only" is false. `MainActivity` has no `enableEdgeToEdge()`, `targetSdk = 30`, and `themes.xml` is `parent="android:Theme.Material.Light.NoActionBar"`: a light window background (white flash on cold start and on navigation) and default bar colours.

Fix: add an explicit role table: `background` and `surface` = #16202B; `surfaceVariant`, `surfaceContainerLowest` to `surfaceContainerHighest` = #233447; `onSurface` = text; `onSurfaceVariant` = textSecondary; `primary` = signal; `onPrimary` = background; `outline` = border; `outlineVariant` = borderSubtle; `error` = #FF8A8A; `surfaceTint` = Transparent. Add to scope: `res/values/themes.xml` parent `android:Theme.Material.NoActionBar` with `windowBackground`, `statusBarColor` and `navigationBarColor` = #16202B (API 26 safe, no new dependency). Add a screenshot of the cold-start frame.

### 6. MAJOR: hiding chips and search while scanning hides an active filter (spec D12, 3.5, 4.1)

What is wrong: if the Director has "Interesting" selected or a query typed and taps Scan, the chips and search vanish but the filter still applies. Newly found hosts are Unmarked, so nothing "arrives below" and there is no visible reason why. The mockup's state 2 only showed the case with All selected.

Fix: collapse chips and search only when `filter == ALL` and the query is blank. Otherwise keep the chip row (and the field, if the query is non-blank) visible during SCANNING. This is UI only and costs about 58 dp, which portrait absorbs: Home SCANNING at 1.3 is about 343 dp of header (18 top + 48 network line + 12 + 172 disc + 56 Stop + 27 progress + 10), leaving about 435 dp of list after the sticky header, about 7 rows; with chips kept, about 6.

### 7. MAJOR: Detail worst case at font scale 1.3 is two rows, not three (spec 5.1, 7.5)

What is wrong: the 7.5 budget leaves out the Custom ports field and the inline reasons. Portrait 1.3: base block about 403 dp, note expanded +90, Custom field with error +82, reasons inline (3 lines plus "and N more") +88 = about 663 dp of the roughly 820 dp usable, leaving about 157 dp: two port rows of about 63 dp. Also the scan feedback reserves 18 dp, but the 12 sp line at 1.3 is about 21 dp, so the bar jumps when a scan starts.

Fix: "Why this identity" always opens the reasons (and "Found as") in the sheet, never inline. That removes the variable and keeps the worst case at about 575 dp, so three rows remain. Reserve the progress height with `heightIn(min)` computed from the `data` style's line height (sp), not a fixed 18 dp. Add the Custom-plus-note-expanded case to Compose test 7.

### 8. MAJOR: the Radar spec owns Home wording but does not yet state all of it (spec D13, 3.3, 4.2, 8, 11.3)

Settled input: the extended-discovery review ruled that this spec owns Home layout and all wording (group headers, progress and done-summary strings), and the extended-discovery spec supplies data only. So this spec must be complete on its own. Checked against that ruling:

What is wrong:
- D13 and open item 11.3 still frame the header wording as superseding extended 8.1 "pending the Director". Under the ruling there is nothing to supersede; the open item should go, and the strings here are final.
- Group-header count format is unstated: "the post-filter device count right-aligned" could be "14" or "14 devices". The mockups the Director saw show no count at all (see finding 11).
- "Home subnet" is ambiguous: in every mockup the SSID is "Home". Fixed word, or the network name ("Office subnet")?
- Progress strings missing for real states the extended data will produce: the start of a run before `total` is known ("probing 0 of 0, 0 s"), the tail after the local sweep finishes while enrichment and the neighbour dump run (stuck at "probing 254 of 254"), and the between-phase gap before an extended range starts.
- Extended-run end notes from extended-discovery 11.5 and 11.6 ("Extended discovery: no extra ranges to sweep", "Gateway probe found no other ranges") are not in the copy table, and nothing says whether they are notices (4.4) or part of the done summary.
- Error-count wording appears in the copy table but its data does not exist (finding 2).
- Where grouping is computed: extended 9.4 puts it in the `HomeViewModel` combine; this spec has `GroupHeaders.of` in the UI. Under the ruling the data (group key, order, post-filter counts) comes from the extended side and only the header text is formatted here. Say so, or the two implementations will duplicate it.
- Settings strings for the extended section (manual-range validation and normalisation messages, "per 4.4" of the extended spec) are quoted only by reference. If wording ownership covers Settings, they must be copied into section 8 here; if it covers Home only, say that.

Fix: delete open item 11.3 and the "superseding" text in D13. In section 8 add: the count format (recommend a bare number, or no count, to match the mockups; the Director picks); "Home subnet" defined as a fixed word or the network name; progress strings for start ("starting", no numbers), post-sweep tail ("identifying 14 devices, 9 s" or similar), and between phases; the two extended end notes with their placement (notice line); and a line stating that the extended-discovery spec's strings in 8.1 and 8.2 ("(this network), 14 devices", "Found 14, probing 203/254", "Found 19") are void. State that grouping data comes from the VM once extended discovery lands and the UI only formats it. Add a JVM test that every progress and summary state in `progressLine` and `ScanSummaryText` produces a string from the table.

### 9. MAJOR: the tests cannot be written against the current screen structure (spec 10.2)

What is wrong:
- Tests 1, 3, 4 and 7 need "a fake state" and must check that `stopScan` was not called, but `HomeScreen(container, ...)` and `DetailScreen(container, ...)` build their view models internally. There is no seam.
- Test 5 needs the animator scale at 0. An instrumented test cannot write `Settings.Global` without shell permission.
- An infinite transition running in SCANNING keeps the test clock busy. Tests that wait for idle in that state need `mainClock.autoAdvance = false` and explicit `advanceTimeBy`.
- `scanButton` "tags whichever is the live control" while 10.2 also tags the Stop pill `radarStop`. One node carries one `testTag`.
- Existing `HomeScreenTest.scanButtonAndFiltersArePresentAndPinned` asserts that `filterRow` and `search` are displayed at launch. With a fresh install (HERO) they are hidden, so the test fails. `scanIsDisabledWithAMessageWhenOffWifi` uses `onNodeWithText("Connect to Wi-Fi or Ethernet to scan")`, and the spec now shows that string both as HERO no-network copy and as the empty state, which is two nodes and a failure.
- Nothing asserts the list's remaining height with the disc showing, which is the actual risk of the pinned rule here.

Fix: add to scope a stateless split: `HomeContent(state: HomeUiState, actions: HomeActions)` and `DetailContent(state, actions)`, with the screens as thin VM wrappers (UI only). Make reduced motion a `CompositionLocal` (`LocalReducedMotion`) defaulting to `rememberReducedMotion()`. Use `radarStop` only on the pill and keep `scanButton` on the ring and hero circle, updating the existing test. Pick one owner for the no-network string (HERO copy only; the list shows nothing). Add to test 1: in SCANNING at font scale 1.3 with `ForcedSize(412 x 820 dp)`, `deviceList` height is at least 3 x 62 dp, and `radarStop` is not a descendant of `deviceList`. Use `DeviceConfigurationOverride.FontScale` for test 7.

### 10. MAJOR: radar animation details that will cause a visible jump or waste (spec 3.3, 3.6)

What is wrong:
- At rest the ring sits at the left gutter. Scanning centres the disc. The spec animates only the diameter, so the disc would grow from the left and then snap to centre. Stroke colour (signal at 56 dp, borderSubtle at 172 dp) and width are listed but colour is not animated.
- The custom `Layout` that moves the text from "right of the ring" to "below the disc" moves something that is not shown while scanning (state 2 has no text column, only Stop and progress). It is complexity with no visible output.
- "Arm angle read only inside the `Canvas` draw lambda" avoids recomposition but still re-records the draw (sweep brush, rings, up to 64 radial-glow blips) every frame at 120 Hz for the length of the scan.
- HERO mockup padding is 26/10 dp and the scanning mockup 14/6 dp, but HERO to SCANNING "keeps its size". Mismatched padding would jump.
- The 30 sp count at 1.3 (39 sp) with 3 to 4 digits (a /22 sweep is up to 1022 hosts) is about 70 to 95 dp wide, so it crosses the 56 dp inner ring.

Fix: animate one `fraction` (0 = ring, 1 = disc) with `updateTransition`; derive diameter, horizontal offset (lerp from start gutter to centred), stroke width and stroke colour from it. Fade the ring's text column out on expand and in on settle instead of the interpolating `Layout`. Draw the static disc and the blips with `drawWithCache` (redrawn only when `found` changes), and the arm as its own layer rotated via `graphicsLayer { rotationZ = angle }`, so each frame is a RenderThread composite, not a re-record. Use one top padding for HERO and SCANNING (take the scanning mockup's 14 dp). Give the count `maxLines = 1` and `TextAutoSize` capped to 96 dp width.

### 11. MINOR: deviations from the agreed mockups (spec 2, 3, 4, 5)

Every change from what the Director saw:
1. Progress copy: mockup "probing 171 of 254  7 s" (two spaces); spec "probing 171 of 254, 7 s".
2. Group header count: not drawn in any mockup (D13 adds it).
3. HERO: `radar-layout.html` option B shows the filter chips under the disc; the spec hides them (D12). The placeholder "Devices appear here as they are found" is drawn as a group-header line (11 sp, rule above, left aligned); the spec centres it as a 13 sp empty state.
4. HERO disc: drawn 170 dp with #233447 outline and rings and a 60 dp inner inset; the spec uses the states mockup's 172 dp, #2A3E54 and 58 dp. A sensible unification, but say so.
5. Filter chips: drawn about 24 dp visual (3/10 dp padding); the spec says 32 dp visual (the Material default).
6. Status segments, profile segments and Scan pill: drawn about 29 to 31 dp visual; the spec says "48 dp tall" for the status control. Keep the visual as drawn and the 48 dp touch target, as already done for chips.
7. "Back to Home" gains a leading chevron; the links gain weight 500. Neither is drawn.
8. "This phone" data line: drawn "10.0.3.242  Pixel 7 Pro"; the code (`RowRules.OWN_LINE`) gives "Android device", and the spec's `rowDataLine` would print that.
9. Quick scan all borrows card A's partial teal arc with "Stop" inside the ring. The Director never saw this state.
10. Undrawn colours: COMPUTER, SERVER, CAMERA, PRINTER edges and teal "on" switches.
Fix: list these as a short "Not as drawn" table in the spec. Revert 1, 5 and 6 to the drawn values. Get the Director's eye on 2, 3, 8, 9 and 10 in the screenshot pass (8 can be met in the UI by substituting `Build.MODEL` for the own row's identity).

### 12. MINOR: contrast claims verified, two gaps (spec 7.1, 4.3, 3.3)

All 17 stated ratios recomputed with the WCAG formula and they match to two decimals (for example #8FA3B5: 6.33 on #16202B and 4.88 on #233447; #B9C7D4: 9.55 and 7.37; #35D4C8: 8.94 and 6.90; #FFD56B: 11.75 and 9.06; PRINTER on #233447 is 7.59, which the table leaves as "n/a"). Gaps:
- Stale rows at 0.5 alpha: the 12 sp `textMuted` data line becomes about #526270, which is 2.62:1, and the name becomes 4.46:1. The IP of a stale host is effectively unreadable.
- The disc centre: when the arm (signal at .55 over #1B2836) passes under the centre, the 11 sp "found" label drops to about 1.65:1 and the count to 3.6:1.
Fix: for stale rows, dim the edge and name only and keep the data line at full `textMuted` (or raise the alpha so that the contrast test passes 4.5:1). Draw the arm only outside a 40 dp radius (a centre mask), so the label always sits on #1B2836 (5.75:1). Add both to the contrast test. The 48 dp target list is otherwise complete. The action word's 48 dp should be stated as height and width, though the whole row already carries the same action.

### 13. MINOR: font wording and what has to be fetched (spec D10, 2.2, 9, 11.1)

What is wrong: the spec still says "Roboto until you decide" and "font files only after approval". Bundling is approved. The note line and "Add a note" are italic, so without an italic file Android will synthesise a slanted Regular.
Fix: state IBM Plex Sans as the font. List exactly four static TTFs: Regular, Italic, Medium, SemiBold, taken from IBM's official `IBM/plex` release, with the OFL text shipped as `res/raw`. Make it explicit that the lead or the Director places the files and that no subagent downloads anything. Add a JVM or instrumented check that "1111" and "0000" measure the same width with `tnum`, so a font without the feature is caught.

### 14. MINOR: summary persistence statement is self-contradictory (spec 3.4)

What is wrong: "after leaving and returning it reads the idle text", but the summary is in `rememberSaveable`, and Navigation restores saveable state when popping back from Detail, so it will still read "Scanned in 12 s". Rotation does not recreate the activity anyway (`configChanges`).
Fix: decide the behaviour (keeping the summary across a Detail round trip is the more useful one) and make the text and a test match it.

### 15. MINOR: smaller behaviour gaps (spec 3.3, 4.4)

- While a radar scan runs, the overflow "Quick scan all" stays enabled today (`enabled = s.quickScanning || (s.rows.isNotEmpty() && s.env != null)`). Disable it while `scanning` so the two controls never both show Stop.
- Notices: `HomeScreen` currently consumes every message into the snackbar at once (`LaunchedEffect(s.message) { ...; vm.dismissMessage() }`). Splitting "Scan failed" (snackbar) from the rest means prefix-matching strings. Simpler: show all messages in the notice line. "Until the next scan starts" is met by calling `dismissMessage()` before `startScan()` (UI only).
- `rememberReducedMotion` must re-read the setting on resume (or observe it), not once per composition.

### 16. MINOR: nothing stops scope or dependency creep (spec 9, G)

Fix: add to section 9: no new dependencies, specifically not `ui-text-google-fonts` (downloadable fonts reach Google at runtime), `material3-adaptive` or window-size-class (use `LocalConfiguration` or `BoxWithConstraints` for finding 1), Paparazzi or Roborazzi (screenshots are `adb exec-out screencap` only), and no animation libraries. No subagent downloads or installs anything. The Plex files arrive per finding 13. Engines, `ActionProvider`, `RowRules`, `HomeFilter`, DAOs and `Settings.kt` stay untouched, with the read-only field list from finding 3 as the only VM change.

### 17. MINOR: screenshot checklist gaps (spec 10.3)

Add: landscape Home scanning and Detail (finding 1); a filter active during a scan (finding 6); quick scan all running; a notice line showing; Detail Custom with an error and the note expanded at 1.3; the cold-start frame (finding 5); and a short `adb shell screenrecord` of grow and settle, because a still cannot show a jump. Open items 11.2 and 11.4 to 11.6 still need the Director's answers before the plan. 11.1 is closed (approved).
