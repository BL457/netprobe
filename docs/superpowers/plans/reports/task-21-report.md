# Task 21 report: ViewModels and Compose screens

Status: DONE_WITH_CONCERNS (code, JVM tests, build, install and launch are done; device UI verification is blocked, see Concerns)
Commit: 8360488 Add Compose UI: home, detail, settings, vulnerability sheet and view models

## What was implemented
- `ui/home/ScanController.kt`, `HomeViewModel.kt` (with `StatusFilter`, `HostRow`, `HomeUiState`, `IdentityLine`, `HomeFilter`), `HomeScreen.kt`, `HomeComponents.kt` (NetworkPanel, FilterRow, HostRowItem, type icons, status labels).
- `ui/detail/DetailViewModel.kt`, `DetailScreen.kt`, `DetailComponents.kt` (EditableName, IdentityBlock, StatusSelector, NoteField, ScanBar, PortRowItem).
- `ui/vulns/VulnSheet.kt`, `ui/settings/SettingsScreen.kt`, `ui/theme/Theme.kt`, `ui/Nav.kt`, `ui/Common.kt` (shared EmptyState), `MainActivity.kt`.
- Pinned layout as specified: Home Scan/Stop, progress, filters and search sit in the `Scaffold` topBar, and only the device list scrolls. On Detail, the header, status, note and scan bar sit in the topBar, and only the ports list scrolls.

## Changes from the brief's code (bugs fixed)
1. **Test harness:** in kotlinx-coroutines-test 1.10, `advanceUntilIdle()` returns once the only work left is in `backgroundScope`. A debug print showed the controller body never ran, so the brief's tests could not have passed with any implementation. `HomeViewModelTest` now uses `runCurrent()`. The assertions are unchanged.
2. **ScanController race fixes:** a generation counter stops a cancelled job's `finally` from clearing a newer scan's `scanning` flag after Stop then Scan. The watcher and job handles are captured locally, because the brief's `invokeOnCompletion { watcher?.cancel() }` would cancel the *new* scan's watcher. The watcher also no longer uses `drop(1)`, so a network change between `start()` and the watcher subscribing is still caught. Both jobs are started LAZY so their handles exist before `viewModelScope`'s immediate dispatch runs the body.
3. **Error handling:** a discovery exception becomes "Scan failed: <reason>" instead of an uncaught crash in `viewModelScope`. Quick scan all catches errors per host, so one bad host does not end the sweep, and reports a count. A Detail scan exception becomes a message. A `vulnLookup.run` exception becomes `VulnResult.Failed`, and a re-run cancels the previous lookup. `CancellationException` is always rethrown first. The network `ensure()` in `init` is also guarded.
4. **Detail scan job:** started LAZY, and its `finally` only clears `scanning` if the job is still the current one.
5. **runAction:** rewritten from the odd `"...".also {}` form into plain statements. Behaviour is the same.

## Judgement calls in the screens
- **Home search placeholder:** "Search name, IP, vendor or MAC" instead of "...service", because HomeFilter does not search services.
- **Home status line:** "Found n · probed p/t" while scanning, "Quick scanning all devices..." with an indeterminate bar during quick scan, otherwise "N shown".
- **Home empty states:** separate messages for "no search match" and "no devices with this status".
- **NetworkPanel:** shows the SSID when known, otherwise "Wi-Fi" or "Ethernet" (the brief's `ssid ?: "Wi-Fi"` would mislabel Ethernet).
- **Detail TopAppBar:** sits outside the 16dp padded column to avoid double inset. The profile segmented buttons and Scan/Stop share one row, which keeps the pinned header short.
- **Segmented buttons:** checkmark icons suppressed so "Investigated" fits.
- **NoteField:** saves 400 ms after the last keystroke, and also on dispose if an edit is still pending, so leaving the screen quickly loses nothing.
- **Rename dialog:** Save, Cancel, and Reset (Reset only when a custom name exists).
- **Port rows:** "Guessed from port number" appears when there is no product and confidence is PORT_TABLE. The disabled button reads "No app installed" (short form; the full scheme label would squash the row).
- **VulnSheet:** the chip shows score plus severity ("9.8 HIGH"). It adds a "Showing x of total" line and the cache time. The LazyColumn has no key, so a duplicate CVE id cannot crash it.
- **Settings sliders:** move locally while dragging and save once on release, so preferences are not written on every drag frame. Concurrency moves in steps of 32, timeouts in steps of 100 ms. The location row shows "granted" instead of the button once granted.
- **No disclaimer or content-policy text anywhere.** The only hint text is the brief's "Keyword matches can be noisy..." usage note. No em-dashes in any file (grep checked).

## TDD evidence
RED (before any implementation):
```
e: .../ui/home/HomeFilterTest.kt:10:9 Unresolved reference 'HostRow'.
e: .../ui/home/HomeFilterTest.kt:14:93 Unresolved reference 'HomeFilter'.
e: .../ui/home/HomeFilterTest.kt:14:116 Unresolved reference 'StatusFilter'.
FAILURE: Build failed ... ':app:compileDebugUnitTestKotlin'
```
Second RED (ScanController present, tests still using advanceUntilIdle):
```
HomeViewModelTest > network loss cancels an active scan and sets the message FAILED
HomeViewModelTest > switching networks mid scan also cancels FAILED
HomeViewModelTest > restart after stop keeps the new scan running FAILED
HomeViewModelTest > a failing discovery stops scanning with a message FAILED
HomeViewModelTest > stop clears scanning without a message and a completed scan clears scanning FAILED
10 tests completed, 5 failed
```
GREEN:
```
BUILD SUCCESSFUL
PASS "status filters"
PASS "query matches name ip and identity case-insensitively"
PASS "identity line rules"
PASS "gateway first then numeric ip order"
PASS "network loss cancels an active scan and sets the message"
PASS "switching networks mid scan also cancels"
PASS "restart after stop keeps the new scan running"
PASS "a failing discovery stops scanning with a message"
PASS "stop clears scanning without a message and a completed scan clears scanning"
PASS "start without a network sets a message and does not scan"
```
Two of these tests were added beyond the brief: the failure-to-message test, and the restart-after-stop race test (which the brief's controller would fail).
Full suite: 188 tests, 0 failed, 0 skipped (178 previous + 10 new). `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: BUILD SUCCESSFUL.

## Device
- Install: `Success`. Launch: the process stays alive (pid 5592), and logcat shows no `AndroidRuntime` or FATAL lines.
- **Screenshot: not obtained.** The phone was dozing behind a secure lock screen (`isKeyguardShowing=true`, `deviceLocked=1`). The capture came out solid black, so I deleted it and did not commit it. I did not try to get past the PIN.
- **Compose device tests: all 3 FAILED for a toolchain reason, before any UI assertion ran:**
```
HomeScreenTest > scanButtonAndFiltersArePresentAndPinned[Pixel 7 Pro - 17] FAILED
  java.lang.RuntimeException: ... java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []
  at androidx.test.espresso.Espresso.onIdle(Espresso.java:357)
Tests on Pixel 7 Pro - 17 failed: There was 3 failure(s).
```
  espresso-core 3.6.1 reaches `InputManager.getInstance()` by reflection, and Android 17 has removed it. Only 3.6.1 is in the Gradle cache, so fixing this means a version bump (espresso 3.7.x, plus a matching runner, monitor and compose ui-test). That is a download, which the constraints forbid, so I stopped. The locked screen would also stop the activity resuming, even with a fixed Espresso.

## Files changed
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\MainActivity.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\{Nav,Common}.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\theme\Theme.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\home\{ScanController,HomeViewModel,HomeScreen,HomeComponents}.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\detail\{DetailViewModel,DetailScreen,DetailComponents}.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\vulns\VulnSheet.kt
- K:\claude\netprobe\app\src\main\java\com\northwalessolutions\netprobe\ui\settings\SettingsScreen.kt
- K:\claude\netprobe\app\src\test\java\com\northwalessolutions\netprobe\ui\home\{HomeFilterTest,HomeViewModelTest,TestEnvs}.kt
- K:\claude\netprobe\app\src\androidTest\java\com\northwalessolutions\netprobe\ui\HomeScreenTest.kt

## Self-review
- **Shadowing:** none.
- **Performance:** sliders save on release, not per frame. The note field is debounced. Identification inside a host is bounded by a semaphore. No per-item allocation in hot paths beyond what the brief specifies.
- **Errors:** every launched coroutine that can throw has a catch that rethrows CancellationException first, so none of them can crash the app.
- **Resources:** locks are released in `finally`. The vuln job and scan job are cancelled on stop or close.
- **Magic numbers:** turned into constants (STOP_TIMEOUT_MS, NOTE_DEBOUNCE_MS, slider ranges and steps, STALE_ALPHA, PREFIX_OPTIONS).
- **Not verified on the device:** clipping, how tall the pinned Detail header is, and whether Scan can be reached without scrolling. The structure guarantees the pinning; the visual fit is unconfirmed.

## Concerns
1. **Device UI is not verified.** It needs the Director to unlock the phone, plus approval to bump Espresso and the androidx.test stack for Android 17 (a download).
2. **Detail pinned header height:** identity, status, note and scan bar are all pinned. That is fine on a 7 Pro, but the "Why?" expansion or the keyboard could leave little room for ports. Worth a look when the Director tests.
3. **Wi-Fi name after granting permission:** the env flow does not re-emit, so the Wi-Fi name only appears after the next link change or an app restart. Also, the network signature (BSSID/SSID) then changes, so hosts land under a new network record.
4. **`targetSdk 30`:** the app is not edge-to-edge, so status-bar insets are left to the system. This needs a visual check.

## Fix round 1

Status: DONE. Commits:
- 63c1759 Join previous discovery before starting a new scan; fix cheap UI nits
- 0382dff Bump Espresso for Android 17 and scope the filter-row test
- b38bbfc Fix Google mDNS identification and add device screenshots

JVM: 191 tests, 0 failed (`./gradlew :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL). Device: HomeScreenTest 2 passed, 1 skipped (the off-Wi-Fi test is skipped by its Assume because the phone is on Wi-Fi).

### 1. Stop-then-Scan race
`ScanController.start()` now captures `prev = job`, and the new job calls `prev?.join()` before `runDiscovery`. The generation counter is kept. New test `a new scan waits for the previous run's cleanup before starting`: its fake `runDiscovery` records `acquireN`, then on cancellation does a 100 ms NonCancellable delay (slow teardown) before recording `releaseN`. The sequence is `start(); stop(); start()`.
RED (old controller):
```
HomeViewModelTest > a new scan waits for the previous run's cleanup before starting FAILED
java.lang.AssertionError: expected:<[acquire1]> but was:<[acquire1, acquire2]>
7 tests completed, 1 failed
```
GREEN: all 7 HomeViewModelTest cases pass, and the order is `acquire1, release1, acquire2`.

### 2. Espresso
`espresso` changed from 3.6.1 to 3.7.0 in `gradle/libs.versions.toml`. It resolved first try. `androidxJunit` did not need a bump (it stays at 1.2.1), and nothing else in the catalog changed.

### 3. Device tests
The filter Row has `testTag("filterRow")`. The test uses `onNodeWithTag("filterRow").onChildren().filterToOne(hasText(...))`.
```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.HomeScreenTest
Starting 3 tests on Pixel 7 Pro - 17
com.northwalessolutions.netprobe.ui.HomeScreenTest > scanIsDisabledWithAMessageWhenOffWifi[Pixel 7 Pro - 17] SKIPPED
Finished 4 tests on Pixel 7 Pro - 17
BUILD SUCCESSFUL in 20s
<testsuite name="com.northwalessolutions.netprobe.ui.HomeScreenTest" tests="3" failures="0" errors="0" skipped="1">
```
I reinstalled the app afterwards with `adb install -r`, which returned `Success`.

### 4. Screenshots
`docs/qa/shots/task21-home-scanned.png`: 13 hosts after a 20 s scan. Gateway Ubiquiti, Synology Inc DS220+, Pi-hole, Weather Station, Google Cast Group, Google Nest Hub. The pinned panel and Scan button are visible.
`docs/qa/shots/task21-detail.png`: gateway detail. With "Why?" expanded (two reasons), the scan bar stays fully visible with plenty of room for ports, so I did **not** move ScanBar.
The device check found one real clipping bug: at the Pixel's font_scale 1.15 the status segments ellipsized ("Interesti...", "Investiga..."). They now use `BasicText` with `TextAutoSize.StepBased`, and all four labels show in full (see the detail screenshot).

### 5. Google identification: diagnosis and fix
I added a temporary `Log.d("NetProbe", "DIAG ...")` in `HomeViewModel.discover`, scanned on the device, and read logcat (excerpt, verbatim):
```
D NetProbe: DIAG ip=10.0.3.196 name=Google-Nest-Hub-fc70a11a556bacde691e9feff3d8ade6 types=[._googlecast._tcp] txt={._googlecast._tcp={bs=FA8FCA3C1A93, ... fn=Study display, ... md=Google Nest Hub, nf=1, ...}}
D NetProbe: DIAG ip=10.0.3.217 name=Google-Home-Mini-... types=[._googlecast._tcp] txt={._googlecast._tcp={... md=Google Home Mini, ...}}
D NetProbe: DIAG ip=10.0.3.175 name=Weather Station types=[._hap._tcp] txt={._hap._tcp={... md=Weather Station, ...}}
```
Cause: the TXT data is resolved and stored correctly. However, Android NSD reports the service type with a **leading dot** (`._googlecast._tcp`), and `AndroidNsdSource` only trimmed the trailing dot. So the identify strings were `._googlecast._tcp:md=...`, and the rules anchored on `^_googlecast\._tcp:` and every `mdnsType` rule (`^_hap...`, `^_sonos...`) never matched. The `ServiceIdentifier` mDNS port confirmation was also keyed on undotted types, so it missed too.
Fix:
- Added `MdnsType.normalise()` (`trim('.')`) in `Mdns.kt`.
- `MdnsResolveQueue` normalises every resolved record. That is the single JVM-testable choke point for all sources.
- `AndroidNsdSource` uses it for both found and resolved types.
- `parseTxt` in `Repositories.kt` normalises stored keys, so rows saved before the fix are judged correctly too.
- `IdentityLine` no longer repeats the vendor when the model starts with it (otherwise the row read "Google Google Nest Hub").
Tests:
- `MdnsResolveQueueTest > android style dotted service types are normalised`. RED: `expected:<[_googlecast._tcp]> but was:<[._googlecast._tcp.]>`.
- `HomeFilterTest > vendor is not repeated when the model starts with it`. RED: `expected:<Google []Nest Hub> but was:<Google [Google ]Nest Hub>`.
- Both now pass. I removed the diagnostic log (grep confirms no `DIAG` or `Log.d` remains in ui/).
Device result: the Nest Hub row now reads **"10.0.3.196 · Google Nest Hub"** with the Media icon. Google Cast Group, Weather Station (HomeKit `md=`) and the Chromecast are identified the same way.
Side observation: mDNS also reported a Hue Bridge at 10.0.4.20, which is outside the /24. Discovery passes it through, and it is not shown under this network. Worth a look later.

### 6. Minors
- (a) HostRowItem shows only the identity line when the display name is the IP.
- (b) Rename trims, and stores null when the result is blank or equals the discovered name with no custom name set, so "Reset" only appears for a real rename.
- (c) `setNote` runs in `viewModelScope.launch(NonCancellable)`, so the final save fired from `onDispose` on Back survives the ViewModel being cleared.
- (d) VulnSheet links go through the new `DetailViewModel.openLink(url)`. When no app opens the link it copies the URL and shows "No browser found. Link copied."
