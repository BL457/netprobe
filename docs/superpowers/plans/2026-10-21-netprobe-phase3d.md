# NetProbe Phase 3D Implementation Plan: the navigation restructure (bottom bar; Devices, Scans, Tools, Settings; port scans on appScope)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Builds on top of the finished 3C-2 branch.** Phase 3D starts only after 3C-1 and 3C-2 are complete and their device QA has passed (spec section 8). Every name this plan consumes from 3C-2 is taken from the 3C-2 plan (`2026-10-21-netprobe-phase3c-2.md`): `ManualToolsScreen`, `ManualToolsContent`, `ManualActions`, `ManualToolsViewModel`, `ManualCopy`, `NetworkRun`, `ToolRepository.recentForNetwork`, `HostRepository.allHostsForNetwork`, `HostEntity.manualOnly`. Task 1 Step 0 checks that those names exist on the branch before anything is written; if one differs on the finished branch, the implementer adapts the 3D edit to the branch's actual name and records it, never the other way round.

**Goal:** Give NetProbe four top-level destinations behind a Material 3 bottom navigation bar (Devices, Scans, Tools, Settings), move Device port scans onto `appScope` (at most two at once, paused in the background, screen kept on), and list every running or recently finished scan, tool run and wordlist URL import on a new Scans destination with live progress and Stop.

**Architecture:** One `NavHost` with a nested `navigation` graph per destination (`devicesTab`, `scansTab`, `toolsTab`, `settingsTab`), each with its own back stack saved and restored by bar taps; the bar shows on the four roots only and slides away on pushed screens and while the keyboard is up. A new in-memory `LiveWork` registry publishes radar, Quick scan all, port scan and URL import progress app-wide (throttled to 250 ms); `PortScans` owns Device port scans on `appScope`; a pure `ScansRules` merges `LiveWork`, `ToolRunner.active` and `tool_runs` history into a pinned Running block and a scrolling Recent list. No schema change.

**Tech Stack:** Unchanged: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00 (Material 3 `NavigationBar`, `NavigationBarItem`, `BadgedBox`), navigation-compose 2.9.0 (ALREADY a dependency, `gradle/libs.versions.toml` line 23, so destinations are real nested `NavGraph`s and no plain state holder is needed), material-icons-extended (already a dependency; `Radar` and `MonitorHeart` are in the cached 1.7.8 jar, `Build` and `Settings` are in icons-core), Room 2.7.1 (schema 4, untouched), kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1 (JSON builders only, no `@Serializable`), JUnit 4, Turbine, AndroidX test. No additions, no version bumps.

**Spec:** `docs/superpowers/specs/2026-10-21-phase3d-navigation-design.md` (FINAL). Section 11 carries two Director rulings that override the body and are applied throughout this plan: (1) the second destination is labelled **"Scans"**, not "Activity" - the Copy object is `ScansCopy`, the route is `scans`, the graph is `scansTab`, the packages are `ui/scans` and `scans`, the preference keys are `scansClearedAt` and `scansOpenScans`; behaviour is unchanged; (2) **D16 is confirmed** - there is no Home overflow "Run a tool..." item, `HomeCopy.MENU_RUN_TOOL` is never added, the Home overflow is exactly "Quick scan all" (or "Stop quick scan") and "Forget this network", and the manual tools screen is the Tools root. Where this plan and the spec differ, the "Plan Self-Review Notes" at the end list each difference and the reason.

**Roles:** The stronger model implements and reviews Task 1 (`PortScans`: the cap, the claim, the controllers, the process-death record and the end handling) and Task 7 (the navigation shell: nested graphs, saved state, bar visibility and insets). Every other task is standard. Review rigour: standard (the spec's suggestion for open item 7; the Director has not answered, so the controller confirms before execution). The plan has 12 tasks: 11 built and JVM-tested now, and Task 12, the deferred on-device QA.

## Global Constraints

Carried from 3C-1 and 3C-2 (unchanged, they still bind):

- Kotlin 2.1.21, AGP 8.10.1, Compose BOM 2025.06.00, Room 2.7.1 KSP with exported schema, targetSdk 30 (deliberate; the manifest does NOT set `enableOnBackInvokedCallback`, so the app does not opt into predictive back and no `BackHandler` is added: Navigation handles Back).
- No new dependencies, no version bumps, no new repositories, no Gradle plugins, no Gradle file edits. Material 3 `NavigationBar` comes from the existing BOM; navigation-compose 2.9.0 is already declared.
- No downloads or installs of anything by any agent or subagent. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director. No test fetches anything from the internet or touches the real LAN.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons. Task 11 adds a source-scan test that enforces this.
- No attribution lines in commits: plain messages, no `Co-Authored-By` or any other trailer.
- No disclaimers, warnings, content-policy lines or authorisation prompts in UI copy. 3D adds no confirmation dialog: Clear history and Remove device are undone from the snackbar.
- Every user-visible string lives in a Copy object (`NavCopy`, `ScansCopy`, `ToolsCopy`, `SettingsCopy`, `DetailCopy`, `HomeCopy`) and has a template in a copy table test (`NavCopyTableTest`, `ScansCopyTableTest`, `CopyTableTest`); regex templates use doubled backslashes (`"\\d+"`). Existing literals that 3D does not change stay where they are (do not restructure what was not asked for).
- Block bodies (`{ ... }`) for every function in which `return` appears; no `return` inside an expression body.
- Action controls are pinned outside every scroll container: Scan and Stop on the Device screen, Run and Stop on the Tools screen, Stop on a Scans running row, the Clear history action, the Settings overflow and the bottom bar. The Scans Running block lives in the Scaffold `topBar`, never in the scrolling list.
- Nothing wipes or migrates user data, marks or notes. "Clear history" hides recent items before a stored cut-off; nothing is deleted. The two new `SharedPreferences` keys (`scansClearedAt`, `scansOpenScans`) are data, not settings, so "Reset to defaults" never touches them.
- The bottom bar is hidden on pushed screens (Device, Wordlists; spec D5) and while the keyboard is visible (D6). Phone only: no navigation rail, no tablet layout (D8 removed).
- No schema change: `NetProbeDb` stays at schema 4 (3C-2). A task that finds itself editing `Entities.kt`, `Migrations.kt`, `NetProbeDb.kt` or `app/schemas/` stops and asks the controller.
- Naming: a local holding a `RunSettings` is `runSettings`; no local is named `run` or after an import (review checklist item 1).
- No progress callback in a tight loop without a step: `PortScanEngine.onProgress` is called per probe but every consumer stores into a volatile and publishes at most every `Motion.PROGRESS_SAMPLE_MS` (250 ms).
- Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so NetProbe is never uninstalled and the Director's data survives.
- THE PHONE IS NOT AVAILABLE during Tasks 1 to 11. Every task is built and tested on the JVM. Compose UI tests are written in full and must compile (`./gradlew :app:compileDebugAndroidTestKotlin`) but are not run. No task may claim device evidence. Task 12 lists every instrumented run and device check. No new instrumented test may open `netprobe.db` or touch the `netprobe` `SharedPreferences` or `files/wordlists/`; the one existing exception is `HomeScreenTest`, which launches the real activity and runs last in Task 12.
- Every commit stages the files its task names and checks `git status --short` before committing; never `git add -A` or `git add .`.
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`. Never kill python or java processes wholesale.
- Standard commands used by every task:
  - JVM, one class or package: `./gradlew :app:testDebugUnitTest --tests "<pattern>" --console=plain 2>&1 | tail -20`
  - androidTest compile only: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8`
  - Task gate, every task ends with it green: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6`, expected `BUILD SUCCESSFUL`.

New in 3D (from the spec, copied verbatim where it names a value):

- Four destinations in bar order: Devices, Scans, Tools, Settings. Graph routes `devicesTab`, `scansTab`, `toolsTab`, `settingsTab`; root routes `devices`, `scans`, `tools`, `settings`; pushed routes `devices/host/{id}`, `devices/wordlists`, `scans/host/{id}`, `scans/wordlists`, `tools/wordlists`, `settings/wordlists`. Start destination `devicesTab`. Item tags `tab:devices`, `tab:scans`, `tab:tools`, `tab:settings`. Labels always shown.
- Bar tap on another destination: `nav.navigate(tab.graph) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }`. Bar tap on the current root does nothing. Back on a non-start root returns to Devices; Back on the Devices root leaves the app.
- Transitions: `SLIDE` inside a destination (3A: `NAV_MS` 240, `NAV_ENTER_OFFSET`, `NAV_EXIT_OFFSET`, `FastOutSlowInEasing`), `FADE` between destinations, `NONE` under reduced motion; the bar hides and shows with a vertical slide plus fade at `NAV_MS`. No new motion tokens.
- `MAX_PORT_SCANS = 2`; a third request is refused with no queue. Port scans pause in the background through `ForegroundGate` and keep the screen on.
- Recent: finished in the last 24 h (`RECENT_WINDOW_MS`), at most 50 items (`RECENT_LIMIT`); tool history is fetched with `RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS` = 54 rows; scan and import outcomes are memory only except a port scan cut off by process death, shown as Interrupted at the next launch. The Running block holds at most eight rows.
- The Home overflow is "Quick scan all" and "Forget this network" only. "Forget this network" is disabled while any port scan or tool run holds a host of the shown network (D19). About is the last row of Settings and opens the existing About dialog.
- Back rows name where Back goes: "Back to Devices", "Back to Scans", "Back to Tools", "Back to Settings", and "Back to device" for Wordlists opened from a Device screen.

## Review Focus

The five failure modes most likely to bite the Director that the spec's own tests do not reach. Each is pinned to a test in its owning task; the reviewer checks the test exists, fails on the bug, and was not bent to fit the code.

1. **A double tap or two pages racing for the last port-scan slot.** With one slot left, two `start` calls for different hosts at the same instant must start exactly one scan and refuse the other with `TooMany`, claiming nothing for the loser. Pinned: Task 1 `PortScansTest.two simultaneous starts with one slot left start exactly one`.
2. **A stale or repeated Stop.** Tapping Stop twice, or on a row that ended a moment ago, must call the stop once and never cancel a later scan of the same device. Pinned: Task 1 `LiveWorkTest.a second stop calls the lambda once and stop after end does nothing` and `PortScansTest.a stale stop of an ended scan does not cancel a new scan of the same host`; Task 5 `ScansTargetsTest.finished items have no stop route`.
3. **A scan body that throws at once, or a scan whose screen is gone.** Nothing may stay claimed, listed as running, in the open-scan record, or keep the screen on. Pinned: Task 1 `PortScansTest.a body that throws leaves nothing claimed, nothing running and nothing recorded` and `a scan outlives the scope that asked for it`.
4. **A device clock that goes backwards.** An ended time before its start must never print a negative duration or sort a row to the wrong end, and an item dated in the future must still be listed. Pinned: Task 5 `ScansRulesTest.a clock that went backwards never gives a negative duration and a future dated item is still recent`.
5. **Rapid tab switching and Back from a non-start tab.** Any sequence of bar taps leaves one Devices entry at the bottom of the stack and at most one other destination above it, and Back from Scans, Tools or Settings lands on Devices. Pinned: Task 7 `NavBarTest.rapidTabTapsLeaveOneDevicesEntryAndAtMostOneOtherDestination` and `backFromANonStartRootLandsOnDevices` (Compose, compile now, run in Task 12).

---

## File Structure

```
app/src/main/java/com/northwalessolutions/netprobe/
  AppContainer.kt                       liveWork, foregroundGate, portScans (1); scansHistory, scansRunning (5)
  scans/LiveWork.kt                     LiveWork registry and the tracked() helper (1)
  engine/common/ForegroundGate.kt       the background pause gate (1)
  engine/portscan/PortScanEngine.kt     gate and onProgress parameters (1)
  engine/portscan/PortScans.kt          PortScans, PortScanState, IdentifyCount, ScanSink, OpenScan, OpenScanStore (1)
  engine/portscan/PrefsOpenScanStore.kt the scansOpenScans record (1)
  engine/portscan/HostScanBody.kt       the scan body moved out of DetailViewModel (1)
  ui/home/ScanController.kt             failureMessage (1)
  ui/home/ForgetGuard.kt                workRunning, allowed (3)
  ui/home/HomeViewModel.kt              port scan aware Forget guard (3); LiveWork for radar and Quick scan all (4)
  ui/home/HomeContent.kt, HomeComponents.kt, HomeScreen.kt   Devices root: overflow, no About, bar inset (8)
  ui/detail/DetailViewModel.kt          port scans through PortScans (2)
  ui/detail/DetailBusyRules.kt, DetailCopy.kt, DetailComponents.kt, DetailContent.kt   cap refusal (2)
  ui/detail/DetailScreen.kt             back label (10)
  ui/wordlists/UrlImportController.kt   LiveWork (4)
  ui/wordlists/WordlistsScreen.kt       back label (10)
  ui/scans/ScansCopy.kt                 every Scans string (1 summaries, 5 whole file)
  ui/scans/ScansModel.kt                ScansItem, ScansSource, ScansState, ScansList (5)
  ui/scans/ScansRules.kt                build, badge, runningCount, snack (5)
  ui/scans/ScansTargets.kt              tap and stop routes (5)
  ui/scans/ScansHistoryStore.kt         scansClearedAt (5)
  ui/scans/ScansViewModel.kt            state, results sheet, Clear history (6)
  ui/scans/ScansContent.kt              the Scans screen (6)
  ui/Common.kt                          EmptyState gains an optional second line (6)
  ui/Nav.kt                             Tab, Routes, BarRules, NavMotion, TabSwitch, NavSlots, NetProbeNavHost, NetProbeShell, NetProbeNav (7)
  ui/NavCopy.kt                         NavCopy, NavLabels (7)
  ui/NavBar.kt                          NetProbeBar and TabIcons (7)
  ui/Insets.kt                          LocalBarInset, rootInsets (6)
  ui/Controls.kt                        BackToHome becomes BackBar(label, ...) (10)
  ui/manual/ManualToolsScreen.kt        Tools root: title row, no back row (9); ui/manual/ToolsCopy.kt (9)
  ui/settings/About.kt                  AboutInfo and AboutDialog moved from Home (9)
  ui/settings/SettingsScreen.kt, SettingsCopy.kt   Settings root: title row, About last (9)
app/src/test/java/com/northwalessolutions/netprobe/
  engine/common/ForegroundGateTest.kt, engine/portscan/PortScanEngineGateTest.kt, engine/portscan/PortScansTest.kt, engine/portscan/PrefsOpenScanStoreTest.kt, scans/LiveWorkTest.kt (1)
  ui/detail/DetailBusyRulesTest.kt (additions), ui/detail/DetailViewModelOwnershipTest.kt (2)
  ui/home/ForgetGuardTest.kt (3)
  scans/UrlImportLiveWorkTest.kt (4)
  ui/scans/ScansRulesTest.kt, ScansTargetsTest.kt, ScansCopyTableTest.kt, ScansHistoryStoreTest.kt (5)
  ui/RoutesTest.kt, BarRulesTest.kt, NavMotionTest.kt, NavCopyTableTest.kt (7)
  ui/home/CopyTableTest.kt (changes), ui/NoEmDashTest.kt, ui/RemovedNamesTest.kt (11)
app/src/androidTest/java/com/northwalessolutions/netprobe/
  ui/NavTransitionTest.kt (rewritten, 7), ui/NavBarTest.kt (7), ui/NavBadgeTest.kt (7), ui/HomeScreenTest.kt (additions, 7)
  ui/scans/ScansContentTest.kt (6)
  ui/home/HomeContentTest.kt (updated, 3 and 8), ui/settings/SettingsContentTest.kt (updated, 9), ui/detail/DetailContentTest.kt (additions, 2)
docs/qa/phase3d-on-device.md            Task 12 record (written when the phone is available)
```

The spec's androidTest rewrites (`NavTransitionTest`, `HomeContentTest`, `HomeScreenTest`) cannot wait for the end: the task gate compiles the androidTest source set, so each rewrite lands in the task whose code change breaks it (the table above shows which). Task 11 is the copy-table and removals task.

---

### Task 1: LiveWork, ForegroundGate, the engine hooks and PortScans on appScope (STRONGER MODEL)

The spec's Blocking 1 fix: a Device port scan stops being owned by `DetailViewModel`'s `viewModelScope`. This task builds the owner and everything it needs; Task 2 moves the Device screen onto it.

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/ForegroundGate.kt`, `scans/LiveWork.kt`, `engine/portscan/PortScans.kt`, `engine/portscan/PrefsOpenScanStore.kt`, `engine/portscan/HostScanBody.kt`, `ui/scans/ScansCopy.kt`
- Modify: `engine/portscan/PortScanEngine.kt`, `ui/home/ScanController.kt`, `AppContainer.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/common/ForegroundGateTest.kt`, `engine/portscan/PortScanEngineGateTest.kt`, `engine/portscan/PortScansTest.kt`, `engine/portscan/PrefsOpenScanStoreTest.kt`, `scans/LiveWorkTest.kt`

**Interfaces:**
- Consumes: `HostBusyRegistry` (`tryClaim`, `release`, `busy`), `AppMessages.post(text, hostId)`, `ScanController`, `ToolCopy.finished`, `DetailCopy.SCAN_BIND_FAILED`, `DisplayName.of`, `BindFailedException`, `ScanBinding.forRun`, `RunSettings.of`, `Motion.PROGRESS_SAMPLE_MS`, `TestHosts.host`, `TestEnvs` (test), `FakePrefs` (test).
- Produces:
```kotlin
class ForegroundGate(foreground: StateFlow<Boolean>) { suspend fun await() }
class LiveWork(scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { RADAR, QUICK_SCAN_ALL, PORT_SCAN, WORDLIST_IMPORT }
    enum class Outcome { DONE, STOPPED, FAILED, INTERRUPTED }
    data class Live(val id: Long, val kind: Kind, val networkId: Long?, val hostId: Long?, val label: String?, val startedAt: Long, val done: Int, val total: Int?, val found: Int)
    data class Ended(val live: Live, val outcome: Outcome, val endedAt: Long?, val summary: String)
    val live: StateFlow<List<Live>>; val ended: StateFlow<List<Ended>>
    fun begin(kind: Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit): Long
    fun progress(id: Long, done: Int, total: Int?, found: Int)
    fun end(id: Long, outcome: Outcome, summary: String); fun drop(id: Long); fun stop(id: Long); fun now(): Long
    fun addInterrupted(kind: Kind, networkId: Long?, hostId: Long?, startedAt: Long, summary: String)
    fun clearEnded(); fun restoreEnded(snapshot: List<Ended>)
    companion object { const val RECENT_LIMIT = 50; const val PUBLISH_MS = 250L }
}
suspend fun <T : Any> LiveWork.tracked(kind, networkId, hostId, label, stop: () -> Unit, summarize: (T, Long) -> Pair<LiveWork.Outcome, String>,
    stopped: (Long) -> String, failed: (Throwable) -> String, onEnd: (LiveWork.Outcome, String) -> Unit = { _, _ -> }, body: suspend (Long) -> T?): T?
data class IdentifyCount(val pending: Int = 0, val done: Int = 0)
data class PortScanState(val progress: ScanProgress?, val identify: IdentifyCount)
sealed interface PortScanStart { Started, HostBusy, TooMany, NoNetwork }
class ScanSink { fun progress(p: ScanProgress); fun identify(change: (IdentifyCount) -> IdentifyCount) }
class PortScans(scope, env: StateFlow<NetworkEnv?>, busy: HostBusyRegistry, liveWork: LiveWork, messages: AppMessages, open: OpenScanStore,
                body: suspend (HostEntity, ScanProfile, NetworkEnv, ScanSink) -> Int, clock: () -> Long = System::currentTimeMillis) {
    val state: StateFlow<Map<Long, PortScanState>>; val active: StateFlow<Boolean>
    fun start(host: HostEntity, profile: ScanProfile): PortScanStart
    fun stop(hostId: Long); suspend fun stopAndJoin(hostId: Long)
    companion object { const val MAX_PORT_SCANS = 2 }
}
PortScanEngine.scan(ip, profile, options = ScanOptions(), gate: suspend () -> Unit = {}, onProgress: (ScanProgress) -> Unit = {})
ScanController.start(onComplete: () -> Unit = {}, work: suspend (NetworkEnv) -> Unit = runDiscovery); ScanController.failureMessage(t: Throwable): String
ScansCopy.PORT_SCAN_NAME, portDone(open, seconds), stoppedAfter(seconds), duration(seconds), radarDone(found, seconds), quickDone(scanned, seconds), INTERRUPTED
AppContainer.liveWork, AppContainer.foregroundGate, AppContainer.portScans
```

- [ ] **Step 0: Confirm the start condition (spec section 8)**

```bash
git status --short
git log --oneline -3
grep -n "class ManualToolsViewModel" -r app/src/main/java
grep -n "fun recentForNetwork" app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt
grep -n "fun allHostsForNetwork" app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt
grep -n "manualOnly" app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt
grep -n "MENU_RUN_TOOL\|menuRunTool\|onRunTool\|onOpenTools" -r app/src/main/java app/src/androidTest | cut -c1-160
```
Expected: a clean tree on the finished 3C-2 branch; the four names found; the last grep is empty (D16: the overflow item was never built). If it prints lines, 3C-2 built the overflow entry before the ruling reached it: Task 8 removes them. Record any name that differs from this plan in the commit body of this task (plain text, no trailers).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/ForegroundGateTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 3.3: no new probe starts while NetProbe is in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundGateTest {
    @Test fun `await returns at once in the foreground`() = runTest {
        ForegroundGate(MutableStateFlow(true)).await()
    }
    @Test fun `await waits in the background and resumes when the app returns`() = runTest {
        val foreground = MutableStateFlow(false)
        var passed = false
        backgroundScope.launch { ForegroundGate(foreground).await(); passed = true }
        runCurrent()
        assertFalse("must wait while in the background", passed)
        foreground.value = true; runCurrent()
        assertTrue(passed)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngineGateTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 3.3 and 9.1 item 8: the gate runs before each probe and onProgress reports every step. Loopback only. */
class PortScanEngineGateTest {
    private val servers = mutableListOf<ServerSocket>()
    private fun listen(): Int { val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()); servers += s; return s.localPort }
    private fun freePort(): Int { val s = ServerSocket(0); val p = s.localPort; s.close(); return p }
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    @Test fun `no probe is issued while the gate is closed and probing resumes when it opens`() = runBlocking {
        val ports = listOf(listen(), freePort(), freePort(), freePort())
        val open = CompletableDeferred<Unit>()
        val results = CopyOnWriteArrayList<PortResult>()
        val job = launch(Dispatchers.Default) {
            PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(ports), gate = { open.await() }).collect { results += it }
        }
        delay(300)
        assertEquals("a closed gate issues no probe", 0, results.size)
        open.complete(Unit); job.join()
        assertEquals(4, results.size)
    }
    @Test fun `onProgress reports the start and every finished probe`() = runBlocking {
        val seen = CopyOnWriteArrayList<ScanProgress>()
        val ports = listOf(listen(), freePort(), freePort())
        PortScanEngine().scan(InetAddress.getLoopbackAddress(), ScanProfile.Custom(ports), onProgress = { seen += it }).collect { }
        assertEquals(ScanProgress(0, 3, 0, 0), seen.first())
        assertEquals(ScanProgress(3, 3, 1, 0), seen.last())
        assertEquals("start plus one per port", 4, seen.size)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.done >= a.done })
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/scans/LiveWorkTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.scans

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Spec 3.2 and 9.1 item 7, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveWorkTest {
    private fun TestScope.work() = LiveWork(backgroundScope, clock = { testScheduler.currentTime })
    private fun LiveWork.start(kind: LiveWork.Kind = LiveWork.Kind.RADAR, hostId: Long? = null, stop: () -> Unit = {}) = begin(kind, 1L, hostId, null, stop)

    @Test fun `begin and end publish at once`() = runTest {
        val lw = work()
        val id = lw.start()
        assertEquals(listOf(id), lw.live.value.map { it.id })
        lw.end(id, LiveWork.Outcome.DONE, "Done in 3 s, 2 devices")
        assertTrue(lw.live.value.isEmpty())
        val e = lw.ended.value.single()
        assertEquals("Done in 3 s, 2 devices", e.summary); assertEquals(LiveWork.Outcome.DONE, e.outcome); assertNotNull(e.endedAt)
    }
    @Test fun `a thousand progress calls inside one interval publish at most twice`() = runTest {
        val lw = work(); val seen = mutableListOf<List<LiveWork.Live>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lw.live.toList(seen) }
        val id = lw.start()
        val before = seen.size
        repeat(1_000) { lw.progress(id, it + 1, 5_000, 0) }
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertTrue("${seen.size - before} emissions", seen.size - before <= 2)
        assertEquals(1_000, seen.last().single().done)
    }
    @Test fun `the ticker ends with the last entry and restarts with the next begin`() = runTest {
        val lw = work()
        val a = lw.start()
        assertTrue(lw.tickerRunning)
        lw.end(a, LiveWork.Outcome.DONE, "x")
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertFalse(lw.tickerRunning)
        lw.start()
        assertTrue(lw.tickerRunning)
    }
    @Test fun `a second stop calls the lambda once and stop after end does nothing`() = runTest {
        val lw = work(); var calls = 0
        val id = lw.start { calls++ }
        lw.stop(id); lw.stop(id)
        assertEquals(1, calls)
        var late = 0
        val b = lw.start { late++ }
        lw.end(b, LiveWork.Outcome.STOPPED, "x"); lw.stop(b)
        assertEquals(0, late)
    }
    @Test fun `a second end is ignored and an unknown id does nothing`() = runTest {
        val lw = work()
        val id = lw.start()
        lw.end(id, LiveWork.Outcome.DONE, "first"); lw.end(id, LiveWork.Outcome.FAILED, "second"); lw.end(999, LiveWork.Outcome.DONE, "ghost")
        assertEquals(listOf("first"), lw.ended.value.map { it.summary })
    }
    @Test fun `after end the stop lambda is no longer held`() = runTest {
        val lw = work()
        val id = lw.start { }
        assertEquals(1, lw.heldStops)
        lw.end(id, LiveWork.Outcome.DONE, "x")
        assertEquals(0, lw.heldStops)
    }
    @Test fun `ended keeps the newest fifty first, clear empties it and restore merges back`() = runTest {
        val lw = work()
        val ids = (1..LiveWork.RECENT_LIMIT + 1).map { n -> lw.start().also { lw.end(it, LiveWork.Outcome.DONE, "n$n") } }
        assertEquals(LiveWork.RECENT_LIMIT, lw.ended.value.size)
        assertEquals("n${LiveWork.RECENT_LIMIT + 1}", lw.ended.value.first().summary)
        assertFalse("the oldest fell off", lw.ended.value.any { it.live.id == ids.first() })
        val snapshot = lw.ended.value
        lw.clearEnded(); assertTrue(lw.ended.value.isEmpty())
        val fresh = lw.start().also { lw.end(it, LiveWork.Outcome.DONE, "fresh") }
        lw.restoreEnded(snapshot)
        assertEquals(LiveWork.RECENT_LIMIT, lw.ended.value.size)
        assertTrue("an item that ended after the clear is kept", lw.ended.value.any { it.live.id == fresh })
    }
    @Test fun `an interrupted entry has no end time`() = runTest {
        val lw = work()
        lw.addInterrupted(LiveWork.Kind.PORT_SCAN, 1L, 7L, 5_000L, "Interrupted when NetProbe closed")
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.INTERRUPTED, e.outcome); assertNull(e.endedAt); assertEquals(5_000L, e.live.startedAt); assertEquals(7L, e.live.hostId)
    }
    @Test fun `tracked ends DONE with the summary and returns the body result`() = runTest {
        val lw = work(); val ends = mutableListOf<Pair<LiveWork.Outcome, String>>()
        val result = lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { n, s -> LiveWork.Outcome.DONE to "n=$n s=$s" },
            stopped = { "stopped" }, failed = { "failed" }, onEnd = { o, s -> ends += o to s }) { 7 }
        assertEquals(7, result)
        assertEquals("n=7 s=0", lw.ended.value.single().summary)
        assertEquals(listOf(LiveWork.Outcome.DONE to "n=7 s=0"), ends)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `tracked ends STOPPED when cancelled and FAILED when the body throws`() = runTest {
        val lw = work()
        val job = launch { lw.tracked<Int>(LiveWork.Kind.QUICK_SCAN_ALL, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
            stopped = { "stopped after $it" }, failed = { "failed: ${it.message}" }) { awaitCancellation() } }
        runCurrent(); assertEquals(1, lw.live.value.size)
        job.cancel(); runCurrent()
        assertEquals(LiveWork.Outcome.STOPPED, lw.ended.value.first().outcome); assertEquals("stopped after 0", lw.ended.value.first().summary)
        try {
            lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
                stopped = { "s" }, failed = { "failed: ${it.message}" }) { throw IllegalStateException("boom") }
            fail("the exception must propagate")
        } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        assertEquals(LiveWork.Outcome.FAILED, lw.ended.value.first().outcome); assertEquals("failed: boom", lw.ended.value.first().summary)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `tracked drops the entry when the body did not run`() = runTest {
        val lw = work()
        val result = lw.tracked<Int>(LiveWork.Kind.RADAR, 1L, null, null, stop = {}, summarize = { _, _ -> LiveWork.Outcome.DONE to "d" },
            stopped = { "s" }, failed = { "f" }) { null }
        assertNull(result)
        assertTrue(lw.live.value.isEmpty()); assertTrue(lw.ended.value.isEmpty())
    }
    @Test fun `stop reaches the lambda registered by tracked`() = runTest {
        val lw = work(); val release = CompletableDeferred<Unit>(); var stops = 0
        val job = launch { lw.tracked<Int>(LiveWork.Kind.WORDLIST_IMPORT, null, null, "example.com", stop = { stops++; release.complete(Unit) },
            summarize = { _, _ -> LiveWork.Outcome.DONE to "d" }, stopped = { "s" }, failed = { "f" }) { release.await(); 1 } }
        runCurrent()
        lw.stop(lw.live.value.single().id); runCurrent()
        assertEquals(1, stops); assertTrue(job.isCompleted)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PrefsOpenScanStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.FakePrefs
import org.junit.Assert.*
import org.junit.Test

/** Spec 3.3, process death: the record of scans that were running. */
class PrefsOpenScanStoreTest {
    @Test fun `add replaces a host's entry, remove deletes it and drain returns everything once`() {
        val prefs = FakePrefs(); val store = PrefsOpenScanStore(prefs)
        store.add(OpenScan(1, 10, 100)); store.add(OpenScan(2, 10, 200)); store.add(OpenScan(1, 10, 300))
        assertEquals(listOf(OpenScan(2, 10, 200), OpenScan(1, 10, 300)), PrefsOpenScanStore(prefs).drain().also { assertTrue(prefs.stored.isEmpty()) })
        assertTrue("drained once", store.drain().isEmpty())
        store.add(OpenScan(3, 10, 1)); store.remove(3)
        assertFalse("the key is removed when the list is empty", prefs.stored.containsKey(PrefsOpenScanStore.KEY))
    }
    @Test fun `a damaged record is dropped and the next add starts fresh`() {
        val prefs = FakePrefs(); prefs.stored[PrefsOpenScanStore.KEY] = "{not json"
        val store = PrefsOpenScanStore(prefs)
        assertTrue(store.drain().isEmpty())
        prefs.stored[PrefsOpenScanStore.KEY] = "[1,2]"
        store.add(OpenScan(4, 1, 2))
        assertEquals(listOf(OpenScan(4, 1, 2)), store.drain())
    }
    @Test fun `the key is the documented one`() {
        assertEquals("scansOpenScans", PrefsOpenScanStore.KEY)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScansTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.home.TestEnvs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

private typealias ScanBodyFn = suspend (HostEntity, ScanProfile, NetworkEnv, ScanSink) -> Int

private class FakeOpenStore(initial: List<OpenScan> = emptyList()) : OpenScanStore {
    val items = CopyOnWriteArrayList(initial)
    override fun add(scan: OpenScan) { items.removeAll { it.hostId == scan.hostId }; items.add(scan) }
    override fun remove(hostId: Long) { items.removeAll { it.hostId == hostId } }
    override fun drain(): List<OpenScan> { val all = items.toList(); items.clear(); return all }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(val test: TestScope, body: ScanBodyFn = { _, _, _, _ -> awaitCancellation() }, leftovers: List<OpenScan> = emptyList()) {
    val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home)
    val busy = HostBusyRegistry()
    val live = LiveWork(test.backgroundScope, clock = { test.testScheduler.currentTime })
    val messages = AppMessages()
    val store = FakeOpenStore(leftovers)
    val scans = PortScans(test.backgroundScope, env, busy, live, messages, store, body, clock = { test.testScheduler.currentTime })
}

private fun host(id: Long) = TestHosts.host("10.0.3.$id").copy(id = id)

/** Spec 3.3 and 9.1 items 8 and 9: PortScans owns Device port scans on the app scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortScansTest {
    @Test fun `two scans start, a third is refused and nothing is queued`() = runTest {
        val rig = Rig(this)
        assertSame(PortScanStart.Started, rig.scans.start(host(1), ScanProfile.Quick))
        assertSame(PortScanStart.Started, rig.scans.start(host(2), ScanProfile.Quick))
        runCurrent()
        assertSame(PortScanStart.TooMany, rig.scans.start(host(3), ScanProfile.Quick))
        assertFalse("the refused host is not claimed", rig.busy.busy.value.containsKey(3L))
        rig.scans.stop(1); runCurrent()
        assertEquals("stopping one starts nothing", setOf(2L), rig.scans.state.value.keys)
        assertFalse(rig.busy.busy.value.containsKey(3L))
    }
    @Test fun `two simultaneous starts with one slot left start exactly one`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick)
        val results = CopyOnWriteArrayList<PortScanStart>(); val gate = CountDownLatch(1)
        val threads = listOf(host(2), host(3)).map { h -> Thread { gate.await(); results += rig.scans.start(h, ScanProfile.Quick) } }
        threads.forEach { it.start() }; gate.countDown(); threads.forEach { it.join() }
        assertEquals(1, results.count { it === PortScanStart.Started }); assertEquals(1, results.count { it === PortScanStart.TooMany })
        assertEquals("exactly two hosts are claimed", 2, rig.busy.busy.value.size)
    }
    @Test fun `a host held by a tool is busy and nothing is claimed`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.busy.tryClaim(5, Work.TOOL))
        assertSame(PortScanStart.HostBusy, rig.scans.start(host(5), ScanProfile.Quick))
        assertEquals(mapOf(5L to Work.TOOL), rig.busy.busy.value)
        assertTrue(rig.store.items.isEmpty()); assertTrue(rig.scans.state.value.isEmpty())
    }
    @Test fun `no network refuses the start`() = runTest {
        val rig = Rig(this); rig.env.value = null
        assertSame(PortScanStart.NoNetwork, rig.scans.start(host(1), ScanProfile.Quick))
        assertTrue(rig.busy.busy.value.isEmpty())
    }
    @Test fun `a network identity change cancels with Network changed and releases the host`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        rig.env.value = TestEnvs.office; runCurrent()
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.scans.state.value.isEmpty())
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Network changed, scan stopped", e.summary)
        assertEquals("Port scan finished: Network changed, scan stopped", rig.messages.current.value?.text)
    }
    @Test fun `active is true while a scan runs and false after`() = runTest {
        val rig = Rig(this)
        assertFalse(rig.scans.active.value)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertTrue(rig.scans.active.value)
        rig.scans.stop(1); runCurrent()
        assertFalse(rig.scans.active.value)
    }
    @Test fun `a scan completes with no screen, ends DONE and posts its message`() = runTest {
        val rig = Rig(this, body = { _, _, _, sink -> sink.progress(ScanProgress(65_535, 65_535, 5, 0)); delay(14_000); 5 })
        rig.scans.start(host(1), ScanProfile.Full); runCurrent()
        assertEquals(65_535, rig.scans.state.value.getValue(1L).progress?.done)
        advanceTimeBy(14_001); runCurrent()
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.DONE, e.outcome); assertEquals("Done in 14 s, 5 open ports", e.summary)
        assertTrue(rig.scans.state.value.isEmpty()); assertTrue(rig.busy.busy.value.isEmpty())
        assertEquals("Port scan finished: Done in 14 s, 5 open ports", rig.messages.current.value?.text); assertEquals(1L, rig.messages.current.value?.hostId)
    }
    @Test fun `stop reports the time and stopAndJoin returns only after the body's finally`() = runTest {
        var finallyRan = false
        val rig = Rig(this, body = { _, _, _, _ -> try { awaitCancellation() } finally { finallyRan = true } })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        delay(8_000)
        rig.scans.stop(1); runCurrent()
        assertEquals("Stopped after 8 s", rig.live.ended.value.single().summary)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        finallyRan = false
        rig.scans.stopAndJoin(1)
        assertTrue("the join waited for the body", finallyRan)
        assertTrue(rig.busy.busy.value.isEmpty())
        assertEquals("a silent stop (Remove device) posts no message of its own", "Port scan finished: Stopped after 8 s", rig.messages.current.value?.text)
    }
    @Test fun `progress is per scan so a concurrent scan does not change it`() = runTest {
        val rig = Rig(this, body = { h, _, _, sink -> if (h.id == 1L) sink.progress(ScanProgress(10, 100, 1, 0)); awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); rig.scans.start(host(2), ScanProfile.Quick); runCurrent()
        assertEquals(ScanProgress(10, 100, 1, 0), rig.scans.state.value.getValue(1L).progress)
        assertNull(rig.scans.state.value.getValue(2L).progress)
    }
    @Test fun `identify counts are published`() = runTest {
        val rig = Rig(this, body = { _, _, _, sink -> sink.identify { it.copy(pending = 2) }; awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals(IdentifyCount(2, 0), rig.scans.state.value.getValue(1L).identify)
    }
    @Test fun `the open scan record holds the scan while it runs and is empty after`() = runTest {
        val rig = Rig(this)
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals(listOf(1L), rig.store.items.map { it.hostId })
        rig.scans.stop(1); runCurrent()
        assertTrue(rig.store.items.isEmpty())
    }
    @Test fun `leftover records at construction become Interrupted items and are cleared`() = runTest {
        val rig = Rig(this, leftovers = listOf(OpenScan(9, 1, 5_000)))
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.INTERRUPTED, e.outcome); assertNull(e.endedAt); assertEquals(9L, e.live.hostId)
        assertEquals("Interrupted when NetProbe closed", e.summary)
        assertTrue(rig.store.items.isEmpty())
    }
    @Test fun `a body that throws leaves nothing claimed, nothing running and nothing recorded`() = runTest {
        val rig = Rig(this, body = { _, _, _, _ -> throw IllegalStateException("boom") })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertTrue(rig.busy.busy.value.isEmpty()); assertTrue(rig.scans.state.value.isEmpty()); assertTrue(rig.store.items.isEmpty())
        assertFalse(rig.scans.active.value); assertTrue(rig.live.live.value.isEmpty())
        val e = rig.live.ended.value.single()
        assertEquals(LiveWork.Outcome.FAILED, e.outcome); assertEquals("Scan failed: boom", e.summary)
    }
    @Test fun `a bind failure ends FAILED with the bind message`() = runTest {
        val rig = Rig(this, body = { _, _, _, _ -> throw BindFailedException(IllegalStateException("no network")) })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        assertEquals("Scan skipped: could not bind to Wi-Fi", rig.live.ended.value.single().summary)
        assertTrue(rig.busy.busy.value.isEmpty())
    }
    @Test fun `a scan outlives the scope that asked for it`() = runTest {
        val rig = Rig(this)
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch { rig.scans.start(host(1), ScanProfile.Quick) }
        runCurrent()
        caller.cancel(); runCurrent()
        assertTrue("still running", rig.scans.state.value.containsKey(1L)); assertTrue("still claimed", rig.busy.busy.value.containsKey(1L))
    }
    @Test fun `a stale stop of an ended scan does not cancel a new scan of the same host`() = runTest {
        var calls = 0
        val rig = Rig(this, body = { _, _, _, _ -> if (calls++ == 0) 1 else awaitCancellation() })
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        val oldId = rig.live.ended.value.single().live.id
        rig.scans.start(host(1), ScanProfile.Quick); runCurrent()
        rig.live.stop(oldId); runCurrent()
        assertTrue("the new scan is untouched", rig.scans.state.value.containsKey(1L)); assertTrue(rig.busy.busy.value.containsKey(1L))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.scans.*" --tests "com.northwalessolutions.netprobe.engine.portscan.*" --tests "com.northwalessolutions.netprobe.engine.common.ForegroundGateTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'LiveWork'`, `'ForegroundGate'`, `'PortScans'`, `'OpenScan'`, `No parameter with name 'gate'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/ForegroundGate.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Suspends while NetProbe is in the background (3D spec 3.3). The signal is the same lifecycle flow ToolRunner reads
 * (AppContainer.foreground), so a port scan and a tool run pause and resume together.
 */
class ForegroundGate(private val foreground: StateFlow<Boolean>) {
    suspend fun await() {
        if (!foreground.value) foreground.first { it }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngine.kt`: replace
```kotlin
    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions()): Flow<PortResult> = channelFlow {
        val ports = profile.ports
        var done = 0; var open = 0; var errors = 0
        _progress.value = ScanProgress(0, ports.size, 0, 0)
```
with
```kotlin
    /**
     * [gate] runs before each probe starts (3D spec 3.3): a PortScans scan passes ForegroundGate.await, so nothing new is probed while
     * NetProbe is in the background; probes already in flight finish within one connect timeout. [onProgress] gets this scan's own
     * counts at the start and after every probe, so two scans never read each other's progress; callers must store and publish
     * sparingly, it runs once per port.
     */
    fun scan(ip: InetAddress, profile: ScanProfile, options: ScanOptions = ScanOptions(), gate: suspend () -> Unit = {},
             onProgress: (ScanProgress) -> Unit = {}): Flow<PortResult> = channelFlow {
        val ports = profile.ports
        var done = 0; var open = 0; var errors = 0
        val first = ScanProgress(0, ports.size, 0, 0)
        _progress.value = first; onProgress(first)
```
replace `            for (port in ports) {\n                sem.acquire()` with
```kotlin
            for (port in ports) {
                gate()
                sem.acquire()
```
replace
```kotlin
                        synchronized(this@PortScanEngine) { done++; if (r.state == PortState.OPEN) open++; _progress.value = ScanProgress(done, ports.size, open, errors) }
```
with
```kotlin
                        synchronized(this@PortScanEngine) {
                            done++; if (r.state == PortState.OPEN) open++
                            val p = ScanProgress(done, ports.size, open, errors)
                            _progress.value = p; onProgress(p)
                        }
```
and replace
```kotlin
                        synchronized(this@PortScanEngine) { done++; errors++; _progress.value = ScanProgress(done, ports.size, open, errors) }
```
with
```kotlin
                        synchronized(this@PortScanEngine) {
                            done++; errors++
                            val p = ScanProgress(done, ports.size, open, errors)
                            _progress.value = p; onProgress(p)
                        }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ScanController.kt`: replace
```kotlin
    fun start(work: suspend (NetworkEnv) -> Unit = runDiscovery) {
```
with
```kotlin
    fun start(onComplete: () -> Unit = {}, work: suspend (NetworkEnv) -> Unit = runDiscovery) {
```
replace the KDoc line above it `/** Starts [work] (default: the constructor's runDiscovery) unless a job is already running. */` with
```kotlin
    /**
     * Starts [work] (default: the constructor's runDiscovery) unless a job is already running. [onComplete] runs once when the job has
     * completed for any reason, including a cancel before its first dispatch, which no `finally` inside [work] can see (PortScans needs it).
     * It is not called when nothing was started (no network, or a job already running).
     */
```
replace `        j.invokeOnCompletion { w.cancel() }` with `        j.invokeOnCompletion { w.cancel(); onComplete() }`, replace
```kotlin
                if (gen == generation) _state.value = State(false, "Scan failed: ${t.message ?: t.javaClass.simpleName}")
```
with
```kotlin
                if (gen == generation) _state.value = State(false, failureMessage(t))
```
and replace
```kotlin
    companion object {
        const val NO_NETWORK = "Connect to Wi-Fi or Ethernet to scan"
```
with
```kotlin
    companion object {
        /** The failure wording, shared with the Scans summaries (3D spec 3.3). */
        fun failureMessage(t: Throwable): String = "Scan failed: ${t.message ?: t.javaClass.simpleName}"
        const val NO_NETWORK = "Connect to Wi-Fi or Ethernet to scan"
```
Existing callers compile unchanged: `start()` and `start { ... }` (the trailing lambda binds to the last parameter, `work`).

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansCopy.kt` (the summary strings PortScans and the producers need; Task 5 replaces this whole file with the complete object, a superset of this one):
```kotlin
package com.northwalessolutions.netprobe.ui.scans

/** Scans strings (3D spec 6, with the Director's "Scans" label). Task 5 adds the screen strings; templates live in ScansCopyTableTest. */
object ScansCopy {
    const val PORT_SCAN_NAME = "Port scan"
    const val INTERRUPTED = "Interrupted when NetProbe closed"
    private const val SECONDS_PER_MINUTE = 60L

    /** "8 s" or "2 min 4 s", and "2 min" on an exact minute. */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        if (s < SECONDS_PER_MINUTE) return "$s s"
        val m = s / SECONDS_PER_MINUTE; val r = s % SECONDS_PER_MINUTE
        return if (r == 0L) "$m min" else "$m min $r s"
    }
    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    fun radarDone(found: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (found == 0) "no devices" else devices(found)}"
    fun quickDone(scanned: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (scanned == 0) "no devices" else devices(scanned)} scanned"
    fun portDone(open: Int, seconds: Long) =
        "Done in ${duration(seconds)}, ${when (open) { 0 -> "no open ports"; 1 -> "1 open port"; else -> "$open open ports" }}"
    fun stoppedAfter(seconds: Long) = "Stopped after ${duration(seconds)}"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/scans/LiveWork.kt`:
```kotlin
package com.northwalessolutions.netprobe.scans

import com.northwalessolutions.netprobe.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MS_PER_S = 1000L

/**
 * What is running app-wide, and how the last few things ended (3D spec 3.2). Memory only. [progress] stores into volatile counters and
 * never emits; one ticker publishes [live] every [PUBLISH_MS] while anything is live and ends itself with the last entry, as ToolRunner's
 * ticker does. Stop lambdas are held beside the data, not in [Live], so equality sees data only, and [end] drops the lambda so a cleared
 * ViewModel is never retained.
 */
class LiveWork(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { RADAR, QUICK_SCAN_ALL, PORT_SCAN, WORDLIST_IMPORT }
    enum class Outcome { DONE, STOPPED, FAILED, INTERRUPTED }
    data class Live(val id: Long, val kind: Kind, val networkId: Long?, val hostId: Long?, val label: String?, val startedAt: Long,
                    val done: Int, val total: Int?, val found: Int)
    data class Ended(val live: Live, val outcome: Outcome, val endedAt: Long?, val summary: String)

    private class Entry(val live: Live, @Volatile var stop: (() -> Unit)?) {
        @Volatile var done = live.done
        @Volatile var total = live.total
        @Volatile var found = live.found
        fun snapshot(): Live = live.copy(done = done, total = total, found = found)
    }

    private val lock = Any()
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val seq = AtomicLong(0)
    private var ticker: Job? = null                       // guarded by lock
    private val _live = MutableStateFlow<List<Live>>(emptyList())
    private val _ended = MutableStateFlow<List<Ended>>(emptyList())
    val live: StateFlow<List<Live>> = _live
    val ended: StateFlow<List<Ended>> = _ended

    internal val heldStops: Int get() = entries.values.count { it.stop != null }
    internal val tickerRunning: Boolean get() = synchronized(lock) { ticker != null }

    fun now(): Long = clock()

    fun begin(kind: Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit): Long {
        val id = seq.incrementAndGet()
        synchronized(lock) {
            entries[id] = Entry(Live(id, kind, networkId, hostId, label, clock(), 0, null, 0), stop)
            publishLocked(); ensureTickerLocked()
        }
        return id
    }

    fun progress(id: Long, done: Int, total: Int?, found: Int) {
        val e = entries[id] ?: return
        e.done = done; e.total = total; e.found = found
    }

    /** Idempotent; an unknown id does nothing, so a finally racing a clear or a second end is harmless. */
    fun end(id: Long, outcome: Outcome, summary: String) {
        synchronized(lock) {
            val e = entries.remove(id) ?: return
            val at = if (outcome == Outcome.INTERRUPTED) null else clock()
            _ended.value = (listOf(Ended(e.snapshot(), outcome, at, summary)) + _ended.value).take(RECENT_LIMIT)
            publishLocked()
        }
    }

    /** Removes a live entry without recording it: the work did not actually run. */
    fun drop(id: Long) {
        synchronized(lock) {
            if (entries.remove(id) != null) publishLocked()
        }
    }

    /** Calls the registered lambda once; a no-op after [end] or a second call. */
    fun stop(id: Long) {
        val f = synchronized(lock) { entries[id]?.let { e -> e.stop.also { e.stop = null } } }
        f?.invoke()
    }

    /** A launch-time entry for work a dead process left open (3D spec 3.3); it has no end time. */
    fun addInterrupted(kind: Kind, networkId: Long?, hostId: Long?, startedAt: Long, summary: String) {
        val id = seq.incrementAndGet()
        val e = Ended(Live(id, kind, networkId, hostId, null, startedAt, 0, null, 0), Outcome.INTERRUPTED, null, summary)
        synchronized(lock) { _ended.value = (listOf(e) + _ended.value).take(RECENT_LIMIT) }
    }

    fun clearEnded() { synchronized(lock) { _ended.value = emptyList() } }

    /** Undo of Clear history: merges the snapshot back with anything that ended since, newest first. */
    fun restoreEnded(snapshot: List<Ended>) {
        synchronized(lock) {
            _ended.value = (_ended.value + snapshot).distinctBy { it.live.id }
                .sortedByDescending { it.endedAt ?: it.live.startedAt }.take(RECENT_LIMIT)
        }
    }

    private fun publishLocked() {
        _live.value = entries.values.map { it.snapshot() }.sortedBy { it.id }
    }

    private fun ensureTickerLocked() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                delay(PUBLISH_MS)
                // The emptiness check and clearing `ticker` share the lock with begin, so a begin racing the last end either finds this
                // ticker still live or starts a new one; a live entry is never left without a ticker.
                val last = synchronized(lock) { publishLocked(); entries.isEmpty().also { if (it) ticker = null } }
                if (last) break
            }
        }
    }

    companion object {
        const val RECENT_LIMIT = 50
        const val PUBLISH_MS = Motion.PROGRESS_SAMPLE_MS
    }
}

/**
 * Begin, run, end: the one place the outcome rules live, used by the radar scan, Quick scan all, port scans and the URL import. A body
 * that returns null did not run (the entry is dropped, nothing is recorded); a cancellation ends STOPPED with [stopped]; any other
 * throwable ends FAILED with [failed]; both are rethrown. [onEnd] runs just after the entry ends.
 */
suspend fun <T : Any> LiveWork.tracked(
    kind: LiveWork.Kind, networkId: Long?, hostId: Long?, label: String?, stop: () -> Unit,
    summarize: (T, Long) -> Pair<LiveWork.Outcome, String>, stopped: (Long) -> String, failed: (Throwable) -> String,
    onEnd: (LiveWork.Outcome, String) -> Unit = { _, _ -> },
    body: suspend (Long) -> T?,
): T? {
    val id = begin(kind, networkId, hostId, label, stop)
    val startedAt = now()
    fun seconds(): Long = ((now() - startedAt) / MS_PER_S).coerceAtLeast(0)
    try {
        val result = body(id)
        if (result == null) {
            drop(id)
            return null
        }
        val (outcome, summary) = summarize(result, seconds())
        end(id, outcome, summary); onEnd(outcome, summary)
        return result
    } catch (ce: CancellationException) {
        val summary = stopped(seconds())
        end(id, LiveWork.Outcome.STOPPED, summary); onEnd(LiveWork.Outcome.STOPPED, summary)
        throw ce
    } catch (t: Throwable) {
        val summary = failed(t)
        end(id, LiveWork.Outcome.FAILED, summary); onEnd(LiveWork.Outcome.FAILED, summary)
        throw t
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PrefsOpenScanStore.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The record of port scans that were running (3D spec 3.3): written at start, removed at end, read once at the next launch. */
data class OpenScan(val hostId: Long, val networkId: Long, val startedAt: Long)

interface OpenScanStore {
    fun add(scan: OpenScan)
    fun remove(hostId: Long)
    /** Returns every record and clears the store. */
    fun drain(): List<OpenScan>
}

/** `scansOpenScans` in the `netprobe` preferences: a JSON list. It is data, so "Reset to defaults" never touches it. */
class PrefsOpenScanStore(private val prefs: SharedPreferences) : OpenScanStore {
    @Synchronized override fun add(scan: OpenScan) { write(read().filterNot { it.hostId == scan.hostId } + scan) }
    @Synchronized override fun remove(hostId: Long) { write(read().filterNot { it.hostId == hostId }) }
    @Synchronized override fun drain(): List<OpenScan> { val all = read(); write(emptyList()); return all }

    private fun read(): List<OpenScan> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val hostId = o["hostId"]?.jsonPrimitive?.longOrNull
                val networkId = o["networkId"]?.jsonPrimitive?.longOrNull
                val startedAt = o["startedAt"]?.jsonPrimitive?.longOrNull
                if (hostId == null || networkId == null || startedAt == null) null else OpenScan(hostId, networkId, startedAt)
            }
        } catch (_: SerializationException) {
            emptyList()      // a damaged record cannot be recovered: it only costs an Interrupted line, never a crash at launch
        } catch (_: IllegalArgumentException) {
            emptyList()      // valid JSON of the wrong shape: same
        }
    }
    private fun write(list: List<OpenScan>) {
        val editor = prefs.edit()
        if (list.isEmpty()) editor.remove(KEY)
        else editor.putString(KEY, buildJsonArray {
            list.forEach { s -> add(buildJsonObject { put("hostId", s.hostId); put("networkId", s.networkId); put("startedAt", s.startedAt) }) }
        }.toString())
        editor.apply()
    }

    companion object { const val KEY = "scansOpenScans" }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScans.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.scans.ScansCopy
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Identifies started and finished during one port scan (Detail spec 1.14). */
data class IdentifyCount(val pending: Int = 0, val done: Int = 0)
data class PortScanState(val progress: ScanProgress?, val identify: IdentifyCount)

sealed interface PortScanStart {
    object Started : PortScanStart
    object HostBusy : PortScanStart
    object TooMany : PortScanStart
    object NoNetwork : PortScanStart
}

/** What a scan body reports to its owner. */
class ScanSink(private val onProgress: (ScanProgress) -> Unit, private val onIdentify: ((IdentifyCount) -> IdentifyCount) -> Unit) {
    fun progress(p: ScanProgress) { onProgress(p) }
    fun identify(change: (IdentifyCount) -> IdentifyCount) { onIdentify(change) }
}

/**
 * Device port scans on the app scope (3D spec 3.3, D10): leaving the Device screen no longer stops them. At most [MAX_PORT_SCANS] run at
 * once and a third is refused, there is no queue. One ScanController per running host, so the network watcher behaves as it always did.
 * The host is claimed in [busy] for the scan's whole life. The scan's [body] pauses itself in the background through ForegroundGate.
 * Callers touch [start], [stop] and [stopAndJoin] on the main thread, as the controllers expect; the bookkeeping is thread safe.
 */
class PortScans(
    private val scope: CoroutineScope,
    private val env: StateFlow<NetworkEnv?>,
    private val busy: HostBusyRegistry,
    private val liveWork: LiveWork,
    private val messages: AppMessages,
    private val open: OpenScanStore,
    private val body: suspend (HostEntity, ScanProfile, NetworkEnv, ScanSink) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Running(val host: HostEntity, val controller: ScanController, val startedAt: Long) {
        @Volatile var stopRequested = false
        @Volatile var silent = false
        @Volatile var progress: ScanProgress? = null
        @Volatile var identify = IdentifyCount()
        @Volatile var lastPublishAt = -PUBLISH_MS
        @Volatile var liveId = 0L
        val finished = AtomicBoolean(false)
    }

    private val lock = Any()
    private val running = HashMap<Long, Running>()      // hostId -> scan, guarded by lock
    private val _state = MutableStateFlow<Map<Long, PortScanState>>(emptyMap())
    private val _active = MutableStateFlow(false)
    val state: StateFlow<Map<Long, PortScanState>> = _state
    /** True while any port scan runs: the KeepScreenOn input, exactly as tool runs count. */
    val active: StateFlow<Boolean> = _active

    init {
        // Scans a dead process left open: shown once as Interrupted (spec 3.3), mirroring ToolRepository.markInterrupted for tool runs.
        open.drain().forEach { liveWork.addInterrupted(LiveWork.Kind.PORT_SCAN, it.networkId, it.hostId, it.startedAt, ScansCopy.INTERRUPTED) }
    }

    fun start(host: HostEntity, profile: ScanProfile): PortScanStart {
        if (env.value == null) return PortScanStart.NoNetwork
        val r: Running
        synchronized(lock) {
            if (!busy.tryClaim(host.id, HostBusyRegistry.Work.PORT_SCAN)) return PortScanStart.HostBusy
            if (running.size >= MAX_PORT_SCANS) {
                busy.release(host.id, HostBusyRegistry.Work.PORT_SCAN)
                return PortScanStart.TooMany
            }
            r = Running(host, ScanController(scope, env) { }, clock())
            running[host.id] = r
            open.add(OpenScan(host.id, host.networkId, r.startedAt))
            publishLocked()
        }
        r.controller.start(onComplete = { finish(r) }) { e -> execute(r, profile, e) }
        // The controller starts nothing when the network vanished between the check above and here: clean up what start() registered.
        if (!r.controller.isRunning && !r.finished.get()) finish(r)
        return PortScanStart.Started
    }

    fun stop(hostId: Long) {
        val r = synchronized(lock) { running[hostId] } ?: return
        stopRun(r)
    }

    /** Remove device: waits until the scan body (and so every port write) has finished, and posts no end message. */
    suspend fun stopAndJoin(hostId: Long) {
        val r = synchronized(lock) { running[hostId] } ?: return
        r.silent = true
        r.stopRequested = true
        r.controller.stopAndJoin()
        finish(r)
    }

    private fun stopRun(r: Running) {
        r.stopRequested = true
        r.controller.stop()
    }

    private suspend fun execute(r: Running, profile: ScanProfile, e: NetworkEnv) {
        val host = r.host
        val sink = ScanSink({ p -> reportProgress(r, p) }, { change -> reportIdentify(r, change) })
        liveWork.tracked<Int>(LiveWork.Kind.PORT_SCAN, host.networkId, host.id, DisplayName.of(host), stop = { stopRun(r) },
            summarize = { opened, seconds -> LiveWork.Outcome.DONE to ScansCopy.portDone(opened, seconds) },
            stopped = { seconds -> if (r.stopRequested) ScansCopy.stoppedAfter(seconds) else ScanController.NETWORK_CHANGED },
            failed = { t -> if (t is BindFailedException) DetailCopy.SCAN_BIND_FAILED else ScanController.failureMessage(t) },
            onEnd = { _, summary -> if (!r.silent) messages.post(ToolCopy.finished(ScansCopy.PORT_SCAN_NAME, summary), host.id) }) { id ->
            r.liveId = id
            body(host, profile, e, sink)
        }
    }

    /** Idempotent; the host is released last, after the record and the running entry are gone, so a new scan can never lose its own record. */
    private fun finish(r: Running) {
        if (!r.finished.compareAndSet(false, true)) return
        synchronized(lock) {
            running.remove(r.host.id, r)
            publishLocked()
        }
        open.remove(r.host.id)
        busy.release(r.host.id, HostBusyRegistry.Work.PORT_SCAN)
    }

    private fun reportProgress(r: Running, p: ScanProgress) {
        r.progress = p
        liveWork.progress(r.liveId, p.done, p.total, p.open)
        val now = clock()
        if (p.done >= p.total || now - r.lastPublishAt >= PUBLISH_MS) {
            r.lastPublishAt = now
            synchronized(lock) { publishLocked() }
        }
    }

    private fun reportIdentify(r: Running, change: (IdentifyCount) -> IdentifyCount) {
        synchronized(lock) {
            r.identify = change(r.identify)
            publishLocked()
        }
    }

    private fun publishLocked() {
        _state.value = running.mapValues { (_, r) -> PortScanState(r.progress, r.identify) }
        _active.value = running.isNotEmpty()
    }

    companion object {
        /** At most this many Device port scans at once (spec D10); the copy and the refusal follow this constant. */
        const val MAX_PORT_SCANS = 2
        private const val PUBLISH_MS = Motion.PROGRESS_SAMPLE_MS
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/HostScanBody.kt` (the scan body that lived in `DetailViewModel.scanHost` and `scanHostOn`, logic unchanged: it is wrapped by `UndoStore.committedBefore` in `AppContainer`, claims nothing itself because `PortScans.start` already holds the `PORT_SCAN` claim, and a `BindFailedException` propagates to `LiveWork.tracked`, which ends the item FAILED with the bind message):
```kotlin
package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.RunSettings
import com.northwalessolutions.netprobe.ui.home.ScanBinding
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress

/** One Device port scan: sweep, identify each open port, store, re-identify the device. Returns the number of open ports found. */
class HostScanBody(private val c: AppContainer) {
    suspend fun run(h: HostEntity, profile: ScanProfile, e: NetworkEnv, sink: ScanSink): Int {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        // One settings read per scan (3C spec D21); manual ranges bind off-subnet rows even with Aggressive scan off (spec 2.4).
        val runSettings = RunSettings.of(c.settings.state.value, c.settings.manual.forNetwork(h.networkId))
        return ScanBinding.forRun(ip, e, runSettings, c.networkEnvMonitor::binderFor) { scanOn(h, profile, ip, sink) }
    }

    private suspend fun scanOn(h: HostEntity, profile: ScanProfile, ip: Inet4Address, sink: ScanSink): Int {
        val types = c.hosts.mdnsTypesFor(h.id)                  // once per scan, not per port
        val found = HashMap<Int, ServiceInfo>()
        val mode = ProbeModes.forProfile(profile)   // one budget per Full or Custom scan, shared by its identifies
        var opened = 0
        sink.identify { IdentifyCount() }
        // Identify off the collector so a slow banner never back-pressures the scan; coroutineScope waits for every identify.
        coroutineScope {
            val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
            c.portScanEngine.scan(ip, profile, c.settings.scanOptions(), gate = c.foregroundGate::await, onProgress = sink::progress)
                .filter { it.state == PortState.OPEN }.collect { r ->
                    opened++
                    sink.identify { it.copy(pending = it.pending + 1) }
                    launch(start = CoroutineStart.ATOMIC) {
                        try {
                            sem.withPermit {
                                val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), mode)
                                synchronized(found) { found[r.port] = info }
                                c.ports.upsertOpenIfHostExists(h.id, r.port, info)
                            }
                        } finally { sink.identify { IdentifyCount(it.pending - 1, it.done + 1) } }
                    }
                }
        }
        c.ports.markScanned(h.id)
        // Stored ports are part of the evidence: a narrow Custom scan must not forget what a Full scan found.
        c.hosts.byIdOnce(h.id)?.let { stored ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(stored), c.ports.servicesFor(h.id), found.keys.toSet(), found,
                stored.mac?.let(c.ouiLookup), c.toolRepo.hintsFor(h.id))
            // After a port scan the stored identity is replaced only by one at least as confident (3A spec 3.3).
            c.hosts.setIdentityIfNotWeaker(h.id, c.deviceIdentifier.identify(ev))
        }
        return opened
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`:
1. After `import com.northwalessolutions.netprobe.engine.common.Locks` add
```kotlin
import com.northwalessolutions.netprobe.engine.common.ForegroundGate
import com.northwalessolutions.netprobe.engine.portscan.HostScanBody
import com.northwalessolutions.netprobe.engine.portscan.PortScans
import com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore
import com.northwalessolutions.netprobe.scans.LiveWork
```
2. Replace
```kotlin
    val foreground = MutableStateFlow(true)
```
with
```kotlin
    val foreground = MutableStateFlow(true)
    /** Port scans await this before each probe (3D spec 3.3); ToolRunner keeps its own gate on the same flow. */
    val foregroundGate = ForegroundGate(foreground)
    /** Radar, Quick scan all, port scans and URL imports as the Scans destination sees them (3D spec 3.2). Memory only. */
    val liveWork = LiveWork(appScope)
```
3. Replace
```kotlin
    val portScanEngine by lazy { PortScanEngine() }
```
with
```kotlin
    val portScanEngine by lazy { PortScanEngine() }
    /** Device port scans on appScope (3D spec 3.3). Building it drains the open-scan record into Interrupted Scans items. */
    val portScans: PortScans by lazy {
        val scanBody = HostScanBody(this)
        PortScans(appScope, networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()), hostBusy, liveWork, appMessages,
            PrefsOpenScanStore(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)),
            body = { h, profile, e, sink -> undo.committedBefore { scanBody.run(h, profile, e, sink) } })
    }
```

- [ ] **Step 4: Run the new tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.scans.*" --tests "com.northwalessolutions.netprobe.engine.portscan.*" --tests "com.northwalessolutions.netprobe.engine.common.ForegroundGateTest" --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures (the existing `PortScanEngineTest`, `ScanControllerJoinTest` and `HomeViewModelTest` stay green: every engine and controller change is a defaulted parameter). If `PortScansTest` hangs on `stopAndJoin`, the controller's job is not completing: read `ScanController.stopAndJoin`, never change the test.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
grep -rn "ScanController(" app/src/main --include=*.kt | cut -c1-170
```
Expected: `BUILD SUCCESSFUL`. The grep shows `ScanController(` in `HomeViewModel` (twice) and `PortScans` only; `DetailViewModel` still has its own until Task 2.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/common/ForegroundGate.kt app/src/main/java/com/northwalessolutions/netprobe/scans/LiveWork.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngine.kt app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PortScans.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/PrefsOpenScanStore.kt app/src/main/java/com/northwalessolutions/netprobe/engine/portscan/HostScanBody.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/ScanController.kt app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/common/ForegroundGateTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScanEngineGateTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PortScansTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/portscan/PrefsOpenScanStoreTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/scans/LiveWorkTest.kt
git status --short
git commit -m "Phase 3D: LiveWork, ForegroundGate and PortScans on appScope with the cap of two"
```

---

### Task 2: The Device screen scans through PortScans, with the two-scan cap and KeepScreenOn

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`, `ui/detail/DetailBusyRules.kt`, `ui/detail/DetailCopy.kt`, `ui/detail/DetailComponents.kt` (`ScanBar`), `ui/detail/DetailContent.kt`, `ui/Nav.kt` (one targeted edit; Task 7 rewrites the file)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailBusyRulesTest.kt` (additions), `ui/detail/DetailViewModelOwnershipTest.kt`; compile only (run in Task 12): additions to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`

**Interfaces:**
- Consumes: `PortScans`, `PortScanStart`, `PortScanState`, `IdentifyCount`, `PortScans.MAX_PORT_SCANS` (Task 1), `ScanController.NO_NETWORK`, `ToolCopy.SCAN_REFUSED`, `ToolsUiModel.busyMessage`.
- Produces:
```kotlin
DetailUiState(..., val identifying: Int = 0, val identified: Int = 0, val scanRefusal: String? = null)      // last, defaulted
DetailBusyRules.scanRefusal(busy, hostId, running: Int = 0): String?;  DetailBusyRules.capRefusal(running: Int): String?
DetailCopy.portScansTooMany(n: Int): String          // "Two port scans are already running, stop one in Scans"
ScanBar(..., liveToolBar = ..., refusal: String? = null)
```
Behaviour: leaving the Device screen no longer stops a port scan; the Scan pill is disabled with the refusal line while two scans run on other devices; `KeepScreenOn` counts a running port scan exactly as it counts a tool run.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailBusyRulesTest.kt`, before the closing brace of the class:
```kotlin
    @Test fun `at the cap another host's scan is refused with the stop one in Scans line`() {
        assertEquals("Two port scans are already running, stop one in Scans", DetailBusyRules.scanRefusal(registry(null), host, running = 2))
        assertNull("one slot left", DetailBusyRules.scanRefusal(registry(null), host, running = 1))
        assertNull(DetailBusyRules.scanRefusal(registry(null), host))
    }
    @Test fun `work on the host itself wins over the cap`() {
        assertEquals("Wait for the port scan to finish", DetailBusyRules.scanRefusal(registry(Work.PORT_SCAN), host, running = 2))
        assertEquals("A tool is running on this device", DetailBusyRules.scanRefusal(registry(Work.TOOL), host, running = 2))
    }
    @Test fun `the cap refusal names the constant's value as a word`() {
        val words = listOf("Zero", "One", "Two", "Three", "Four")
        val line = DetailBusyRules.capRefusal(com.northwalessolutions.netprobe.engine.portscan.PortScans.MAX_PORT_SCANS)!!
        assertTrue(line, line.startsWith(words[com.northwalessolutions.netprobe.engine.portscan.PortScans.MAX_PORT_SCANS]))
        assertNull(DetailBusyRules.capRefusal(com.northwalessolutions.netprobe.engine.portscan.PortScans.MAX_PORT_SCANS - 1))
    }
    @Test fun `one running scan reads in the singular`() {
        assertEquals("One port scan is already running, stop it in Scans", DetailCopy.portScansTooMany(1))
        assertEquals("Three port scans are already running, stop one in Scans", DetailCopy.portScansTooMany(3))
    }
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModelOwnershipTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 9.1 item 9 (design lock). DetailViewModel needs an AppContainer, which needs a Context, so the lifetime itself is proved in
 * PortScansTest `a scan outlives the scope that asked for it`; this test keeps the view model from taking ownership back.
 */
class DetailViewModelOwnershipTest {
    private val source: String = listOf("src/main/java", "app/src/main/java").map { File("$it/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt") }
        .first { it.exists() }.readText()

    @Test fun `the port scan is not owned by the view model`() {
        assertFalse("a ScanController in the view model stops the scan with the screen", source.contains("ScanController("))
        assertFalse("the engine is driven by HostScanBody now", source.contains("portScanEngine"))
        assertFalse(source.contains("viewModelScope, env"))
    }
    @Test fun `scans start, stop and are joined through PortScans`() {
        assertTrue(source.contains("c.portScans.start("))
        assertTrue(source.contains("c.portScans.stop(hostId)"))
        assertTrue("Remove device must join the scan before the delete (spec 5.3 step 1)", source.contains("c.portScans.stopAndJoin(hostId)"))
    }
}
```

Add to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`, before the closing brace of the class:
```kotlin
    @Test fun scanIsDisabledAndTheRefusalShowsWhileTwoPortScansRunElsewhere() {   // 3D spec 9.2 item 7
        show(DetailFakes.state().copy(scanRefusal = DetailCopy.portScansTooMany(2)))
        rule.onNodeWithTag("detailScanButton").assertIsNotEnabled()
        rule.onNodeWithTag("scanRefusal").assertIsDisplayed().assertTextEquals("Two port scans are already running, stop one in Scans")
    }
    @Test fun aScanRunningHereKeepsItsStopEnabledAndShowsNoRefusal() {
        show(DetailFakes.state(scanning = true).copy(progress = ScanProgress(1, 10, 0, 0)))
        rule.onNodeWithTag("detailScanButton").assertIsEnabled()
        rule.onNodeWithTag("scanRefusal").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.detail.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `No value passed for parameter`/`Unresolved reference 'capRefusal'`, `'portScansTooMany'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`: replace
```kotlin
    fun setByYou(parts: String) = "Set by you: $parts"
```
with
```kotlin
    fun setByYou(parts: String) = "Set by you: $parts"
    private val NUMBER_WORDS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    /** The refusal when [n] port scans already run (3D spec 3.3). [n] is spelled as a word, so changing MAX_PORT_SCANS cannot leave "Two" behind. */
    fun portScansTooMany(n: Int): String {
        val word = NUMBER_WORDS.getOrNull(n) ?: n.toString()
        return if (n == 1) "$word port scan is already running, stop it in Scans" else "$word port scans are already running, stop one in Scans"
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailBusyRules.kt`: add `import com.northwalessolutions.netprobe.engine.portscan.PortScans` after the `HostBusyRegistry` import, and replace
```kotlin
    /** A Detail port scan is refused by any work on the host; a tool run keeps its own wording. */
    fun scanRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long): String? {
        val work = busy[hostId] ?: return null
        return if (work == HostBusyRegistry.Work.TOOL) ToolCopy.SCAN_REFUSED else ToolsUiModel.busyMessage(work)
    }
```
with
```kotlin
    /**
     * A Detail port scan is refused by any work on the host (a tool run keeps its own wording), and, with [running] port scans already
     * at the cap, by the cap (3D spec 3.3). Work on the host itself wins, so the host's own running scan never reads as "too many".
     */
    fun scanRefusal(busy: Map<Long, HostBusyRegistry.Work>, hostId: Long, running: Int = 0): String? {
        val work = busy[hostId] ?: return capRefusal(running)
        return if (work == HostBusyRegistry.Work.TOOL) ToolCopy.SCAN_REFUSED else ToolsUiModel.busyMessage(work)
    }

    /** The refusal line while [running] port scans occupy every slot, else null. */
    fun capRefusal(running: Int): String? =
        if (running >= PortScans.MAX_PORT_SCANS) DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS) else null
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt` (targeted edits, in order):
1. Delete these two lines (the type now lives in `engine.portscan`, which this file already star-imports):
```kotlin
/** Identifies started and finished during the current Detail scan (spec 1.14). */
data class IdentifyCount(val pending: Int = 0, val done: Int = 0)
```
2. Replace
```kotlin
                         val identifying: Int = 0, val identified: Int = 0)
```
with
```kotlin
                         val identifying: Int = 0, val identified: Int = 0,
                         /** Why Scan is disabled when two scans run on other devices (3D spec 3.3); null otherwise. */
                         val scanRefusal: String? = null)
```
3. Replace
```kotlin
    /** Port scans are network-bound: losing or switching Wi-Fi cancels them, so they never probe the same IP on another network. */
    private val scan = ScanController(viewModelScope, env) { }
    private val identify = MutableStateFlow(IdentifyCount())
```
with
```kotlin
    // Port scans live in PortScans on the app scope (3D spec 3.3): leaving this screen never stops one, and the network watcher is PortScans'.
```
4. Replace the whole `val state: StateFlow<DetailUiState> = combine(...)` expression, from the line
```kotlin
    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), scan.state, c.portScanEngine.progress, profile, customText, customError, message, vuln, env, identify) { arr ->
```
through the line `    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())` with
```kotlin
    val state: StateFlow<DetailUiState> = combine(c.hosts.host(hostId), c.ports.portsForHost(hostId), c.portScans.state, profile, customText, customError, message, vuln, env) { arr ->
        @Suppress("UNCHECKED_CAST")
        val ps = arr[1] as List<PortEntity>
        @Suppress("UNCHECKED_CAST")
        val scans = arr[2] as Map<Long, PortScanState>
        val h = arr[0] as HostEntity?; val e = arr[8] as NetworkEnv?
        val mine = scans[hostId]
        val rows = ps.map { p -> val info = p.toServiceInfo()
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList(),
                h?.let { tryAsFor(it.ip, p.port) }.orEmpty()) }
        val name = h?.let { if (RowRules.isOwn(it.ip, e) && it.customName.isNullOrBlank()) RowRules.THIS_PHONE else DisplayName.of(it) } ?: ""
        DetailUiState(h, name, rows, mine != null, mine?.progress, arr[3] as ScanProfile, arr[4] as String, arr[5] as String?, arr[6] as String?, arr[7] as VulnSheetState?,
            identifying = mine?.identify?.pending ?: 0, identified = mine?.identify?.done ?: 0,
            scanRefusal = if (mine == null) DetailBusyRules.capRefusal(scans.size) else null)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())
```
5. Replace the block from `    fun startScan() {` through the line `    fun stopScan() = scan.stop()` (this removes `startScan`, `scanHost`, `scanHostOn` and `stopScan`; the scan body is `HostScanBody` now) with
```kotlin
    fun startScan() {
        val h = state.value.host ?: return
        if (c.portScans.state.value.containsKey(hostId)) return
        val prof = profile.value
        val resolved = if (prof is ScanProfile.Custom) ProfileParser.parse(customText.value).getOrElse { customError.value = it.message; return } else prof
        DetailBusyRules.scanRefusal(c.hostBusy.busy.value, hostId, c.portScans.state.value.size)?.let { message.value = it; return }
        when (c.portScans.start(h, resolved)) {
            PortScanStart.Started -> Unit
            // A start racing another screen or the cap: the same words the pill's refusal line uses.
            PortScanStart.HostBusy -> message.value = DetailBusyRules.scanRefusal(c.hostBusy.busy.value, hostId) ?: ToolCopy.SCAN_REFUSED
            PortScanStart.TooMany -> message.value = DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS)
            PortScanStart.NoNetwork -> message.value = ScanController.NO_NETWORK
        }
    }

    fun stopScan() { c.portScans.stop(hostId) }
```
6. In `removeDevice`, replace `                scan.stopAndJoin()` with `                c.portScans.stopAndJoin(hostId)`.
7. Replace `    fun dismissMessage() { message.value = null; scan.dismissMessage() }` with `    fun dismissMessage() { message.value = null }`.
8. Delete the imports that nothing uses any more: `kotlinx.coroutines.sync.Semaphore`, `kotlinx.coroutines.sync.withPermit`, `java.net.Inet4Address`, `java.net.InetAddress`, `com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder`, `com.northwalessolutions.netprobe.engine.serviceid.Evidence`, `com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier`, `com.northwalessolutions.netprobe.engine.serviceid.ProbeModes`, `com.northwalessolutions.netprobe.engine.common.BindFailedException`, `com.northwalessolutions.netprobe.ui.home.RunSettings`, `com.northwalessolutions.netprobe.ui.home.ScanBinding`. (`ServiceInfo`, `ScanController`, `NetworkEnv`, `HostBusyRegistry` stay: they are still used.) An unused import is only a warning, but leaving the engine-side imports invites the next reader to put scan code back.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt` (`ScanBar`): replace
```kotlin
            toolBar: ToolRunBar? = null, onStopTool: () -> Unit = {}, liveToolBar: () -> ToolRunBar? = { toolBar }) {
```
with
```kotlin
            toolBar: ToolRunBar? = null, onStopTool: () -> Unit = {}, liveToolBar: () -> ToolRunBar? = { toolBar },
            /** Why Scan is disabled (3D spec 3.3); shown in the progress line's reserved row, so the bar never changes height. */
            refusal: String? = null) {
```
and replace
```kotlin
                scanning -> Text(detailProgress(progress, identifying), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
```
with
```kotlin
                scanning -> Text(detailProgress(progress, identifying), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
                refusal != null -> Text(refusal, style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End, maxLines = 2,
                    modifier = Modifier.testTag("scanRefusal"))
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`: replace
```kotlin
            ScanBar(state.profile, state.customPortsText, state.customError, state.scanning, state.progress, enabled = state.host != null,
```
with
```kotlin
            ScanBar(state.profile, state.customPortsText, state.customError, state.scanning, state.progress, enabled = state.host != null && state.scanRefusal == null,
```
and replace `toolBar = toolState.bar, onStopTool = toolActions.onStopTool, liveToolBar = liveToolBar ?: { toolState.bar })` with `toolBar = toolState.bar, onStopTool = toolActions.onStopTool, liveToolBar = liveToolBar ?: { toolState.bar }, refusal = state.scanRefusal)`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` (the screen stays on while any port scan runs): replace
```kotlin
    KeepScreenOn(extended || toolRun)
```
with
```kotlin
    val portScan by container.portScans.active.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun || portScan)
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.detail.*" --tests "com.northwalessolutions.netprobe.engine.portscan.*" --console=plain 2>&1 | tail -20
grep -n "ScanController\|portScanEngine\|identify\b" app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt
```
Expected: `BUILD SUCCESSFUL`; the grep shows only `ScanController.NO_NETWORK` (one line).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. If `DetailFakes` or another androidTest helper fails to compile on `DetailUiState`, the cause is a positional call: add the new parameter by name only, it is last and defaulted.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailBusyRules.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailBusyRulesTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModelOwnershipTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt
git status --short
git commit -m "Phase 3D: Device port scans run on PortScans, capped at two, and keep the screen on"
```

---

### Task 3: "Forget this network" is blocked by port scans as well as tool runs (spec D19, Blocking 2)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/ForgetGuard.kt`
- Modify: `ui/home/HomeViewModel.kt`, `ui/home/HomeContent.kt`; rename `toolRunningOnNetwork` to `workRunningOnNetwork` everywhere it appears
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/ForgetGuardTest.kt`; update `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt`

**Interfaces:**
- Consumes: `HostBusyRegistry.busy` (`Map<Long, Work>` with `PORT_SCAN` and `TOOL`), `HostEntity`, `TestHosts.host`.
- Produces:
```kotlin
object ForgetGuard {
    fun workRunning(hosts: List<HostEntity>, busy: Map<Long, HostBusyRegistry.Work>): Boolean
    fun allowed(radarRunning: Boolean, quickRunning: Boolean, workRunning: Boolean): Boolean
}
HomeUiState.workRunningOnNetwork: Boolean      // renamed from toolRunningOnNetwork
```
Why it is a bug today: a Device port scan now outlives its screen (Task 2), so a Forget could delete the network and cascade-delete the host under a live scan that is writing ports. `HomeViewModel.state` already combines `HostBusyRegistry.busy` with every row of the shown network (3C-2: `allHosts`, manual-only rows included); the old flag looked only for `TOOL`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ForgetGuardTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 9.1 item 10. */
class ForgetGuardTest {
    private val shown = listOf(TestHosts.host("10.0.3.5").copy(id = 5), TestHosts.host("10.0.3.6").copy(id = 6))

    @Test fun `a port scan or a tool run on a host of the shown network blocks Forget`() {
        assertTrue(ForgetGuard.workRunning(shown, mapOf(5L to Work.PORT_SCAN)))
        assertTrue(ForgetGuard.workRunning(shown, mapOf(6L to Work.TOOL)))
    }
    @Test fun `work on another network's host does not`() {
        assertFalse(ForgetGuard.workRunning(shown, mapOf(99L to Work.PORT_SCAN, 98L to Work.TOOL)))
        assertFalse(ForgetGuard.workRunning(shown, emptyMap()))
        assertFalse(ForgetGuard.workRunning(emptyList(), mapOf(5L to Work.PORT_SCAN)))
    }
    @Test fun `forget is refused while anything runs and proceeds otherwise`() {
        assertTrue(ForgetGuard.allowed(radarRunning = false, quickRunning = false, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = true, quickRunning = false, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = false, quickRunning = true, workRunning = false))
        assertFalse(ForgetGuard.allowed(radarRunning = false, quickRunning = false, workRunning = true))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.ForgetGuardTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ForgetGuard'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ForgetGuard.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry

/** When "Forget this network" may run (3D spec D19): not while a scan, Quick scan all, a port scan or a tool run is live on it. */
object ForgetGuard {
    /** True when any host of the shown network holds a claim of either kind. [hosts] are every row of the network, manual targets included. */
    fun workRunning(hosts: List<HostEntity>, busy: Map<Long, HostBusyRegistry.Work>): Boolean = hosts.any { busy.containsKey(it.id) }

    fun allowed(radarRunning: Boolean, quickRunning: Boolean, workRunning: Boolean): Boolean = !radarRunning && !quickRunning && !workRunning
}
```

Rename the flag everywhere (it is in `HomeViewModel.kt`, `HomeContent.kt` and `HomeContentTest.kt`):
```bash
grep -rl "toolRunningOnNetwork" app/src | xargs sed -i 's/toolRunningOnNetwork/workRunningOnNetwork/g'
grep -rn "toolRunningOnNetwork" app/src
```
Expected: the second command prints nothing.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: replace
```kotlin
            workRunningOnNetwork = hs.any { busy[it.id] == HostBusyRegistry.Work.TOOL })
```
with
```kotlin
            workRunningOnNetwork = ForgetGuard.workRunning(hs, busy))
```
and replace
```kotlin
        if (controller.isRunning || quick.isRunning || state.value.workRunningOnNetwork) return
```
with
```kotlin
        if (!ForgetGuard.allowed(controller.isRunning, quick.isRunning, state.value.workRunningOnNetwork)) return
```
Also replace the KDoc sentence ``Forget this network stays disabled while a tool holds any of them (plan review G9)`` with ``Forget this network stays disabled while a port scan or a tool holds any of them (3D spec D19)`` (the comment sits above `val state`; if its wording differs on the branch, edit the nearest comment that mentions the flag).

`HomeContent.kt` needs no further edit: the `forgetEnabled = ... && !state.workRunningOnNetwork` line was renamed by the `sed`.

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt`: the renamed test is `forgetThisNetworkIsDisabledWhileAToolRunsOnIt`; replace its name line
```kotlin
    @Test fun forgetThisNetworkIsDisabledWhileAToolRunsOnIt() {
```
with
```kotlin
    @Test fun forgetThisNetworkIsDisabledWhileAPortScanOrAToolRunsOnIt() {   // 3D spec D19: the flag covers both kinds of claim
```
(its body already toggles `workRunningOnNetwork` after the `sed`).

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/home/ForgetGuard.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/ForgetGuardTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt
git status --short
git commit -m "Phase 3D: Forget this network is blocked by port scans as well as tool runs"
```

---

### Task 4: The radar scan, Quick scan all and the wordlist URL import report to LiveWork

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`, `ui/wordlists/UrlImportController.kt`, `AppContainer.kt` (`urlImport`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/scans/UrlImportLiveWorkTest.kt`

**Interfaces:**
- Consumes: `LiveWork`, `tracked` (Task 1), `ScansCopy.radarDone`, `quickDone`, `stoppedAfter` (Task 1), `ScanController.failureMessage` (Task 1), `WordlistImport.message`, `WordlistCopy.CANCELLED`, `UrlImportResult`, `ImportResult.Imported`, `WordlistMeta`.
- Produces:
```kotlin
UrlImportController(scope, fetch, messages, log = {}, liveWork: LiveWork? = null)      // last, defaulted: existing tests compile unchanged
HomeViewModel.discoverOn(e): Int?          // devices found, or null when the scan did not run
HomeViewModel.quickScanAllBody(e, liveId): Int?     // hosts scanned, or null when there was no network row
```
The radar scan begins a `RADAR` entry whose Stop is `stopScan()`; Quick scan all begins `QUICK_SCAN_ALL` with the host list size as total and advances `done` per host handled; the URL import begins `WORDLIST_IMPORT` labelled with the URL's host. The Scans screen reads the radar's progress text from `discoveryEngine.status` (Task 6), so the radar entry carries no counts.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/scans/UrlImportLiveWorkTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.scans

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportController
import com.northwalessolutions.netprobe.ui.wordlists.UrlImportState
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** Spec 9.1 item 11. */
@OptIn(ExperimentalCoroutinesApi::class)
class UrlImportLiveWorkTest {
    private val url = URL("https://raw.githubusercontent.com/x/list.txt")
    private fun TestScope.rig(fetch: suspend (URL, (Long, Long?) -> Unit) -> UrlImportResult): Pair<UrlImportController, LiveWork> {
        val lw = LiveWork(backgroundScope, clock = { testScheduler.currentTime })
        return UrlImportController(backgroundScope, fetch, AppMessages(), liveWork = lw) to lw
    }

    @Test fun `start begins an entry labelled with the URL host and progress bytes reach it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (c, lw) = rig { _, progress -> progress(65_536, 200_000); gate.await(); UrlImportResult.Failed(FetchFailure.UnknownHost) }
        c.attach(); c.start(url); runCurrent()
        val live = lw.live.value.single()
        assertEquals(LiveWork.Kind.WORDLIST_IMPORT, live.kind); assertEquals("raw.githubusercontent.com", live.label)
        advanceTimeBy(LiveWork.PUBLISH_MS); runCurrent()
        assertEquals(65_536, lw.live.value.single().done); assertEquals(200_000, lw.live.value.single().total)
        gate.complete(Unit); runCurrent()
    }
    @Test fun `Stored ends DONE with the store's own message`() = runTest {
        val meta = WordlistMeta("custom:1", "list", 12, 1L, false)
        val (c, lw) = rig { _, _ -> UrlImportResult.Stored(ImportResult.Imported(meta, 0)) }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.DONE, e.outcome); assertEquals("Imported list, 12 entries", e.summary)
        assertTrue(lw.live.value.isEmpty())
    }
    @Test fun `Failed ends FAILED with the fetch message`() = runTest {
        val (c, lw) = rig { _, _ -> UrlImportResult.Failed(FetchFailure.Status(404)) }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.FAILED, e.outcome); assertEquals("Could not fetch: the server answered 404", e.summary)
    }
    @Test fun `a Cancelled result ends STOPPED with Import cancelled`() = runTest {
        val (c, lw) = rig { _, _ -> UrlImportResult.Cancelled }
        c.attach(); c.start(url); runCurrent()
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Import cancelled", e.summary)
    }
    @Test fun `stopping the entry from Scans cancels the fetch`() = runTest {
        val (c, lw) = rig { _, _ -> awaitCancellation() }
        c.attach(); c.start(url); runCurrent()
        lw.stop(lw.live.value.single().id); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value); assertEquals("Import cancelled", c.message.value)
        val e = lw.ended.value.single()
        assertEquals(LiveWork.Outcome.STOPPED, e.outcome); assertEquals("Import cancelled", e.summary)
        assertEquals(0, lw.heldStops)
    }
    @Test fun `without a LiveWork the controller behaves as before`() = runTest {
        val c = UrlImportController(backgroundScope, { _, _ -> UrlImportResult.Failed(FetchFailure.UnknownHost) }, AppMessages())
        c.attach(); c.start(url); runCurrent()
        assertEquals("Could not fetch: unknown host", c.message.value)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.scans.UrlImportLiveWorkTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `No parameter with name 'liveWork'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportController.kt`:
1. After `import com.northwalessolutions.netprobe.engine.tools.AppMessages` add
```kotlin
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
```
2. Replace
```kotlin
                          private val messages: AppMessages, private val log: (Throwable) -> Unit = {}) {
```
with
```kotlin
                          private val messages: AppMessages, private val log: (Throwable) -> Unit = {}, private val liveWork: LiveWork? = null) {
```
3. Replace
```kotlin
                text = WordlistImport.message(fetch(url) { read, total -> _state.value = UrlImportState.Fetching(read, total) })
```
with
```kotlin
                text = WordlistImport.message(fetchTracked(url))
```
4. Replace
```kotlin
    fun cancel() { job?.cancel() }
```
with
```kotlin
    fun cancel() { job?.cancel() }

    /**
     * The fetch as a Scans item (3D spec 3.2): the URL's host is the label; progress is bytes read of bytes expected, at most 10 MB, so Int
     * holds both. The fetcher already reports at most once per PROGRESS_STEP_BYTES, and LiveWork publishes at most every 250 ms.
     */
    private suspend fun fetchTracked(url: URL): UrlImportResult {
        val report: (Long, Long?) -> Unit = { read, total -> _state.value = UrlImportState.Fetching(read, total) }
        val live = liveWork ?: return fetch(url, report)
        val result = live.tracked<UrlImportResult>(LiveWork.Kind.WORDLIST_IMPORT, null, null, url.host, stop = { cancel() },
            summarize = { r, _ -> outcomeOf(r) }, stopped = { WordlistCopy.CANCELLED }, failed = { WordlistImport.failure(FetchFailure.Connection) }) { id ->
            fetch(url) { read, total ->
                report(read, total)
                live.progress(id, read.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), total?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), 0)
            }
        }
        return checkNotNull(result) { "the import body never returns null" }
    }

    /** Stored is DONE whatever the store said about the file (3D spec 3.2); its words are the existing import messages. */
    private fun outcomeOf(r: UrlImportResult): Pair<LiveWork.Outcome, String> = when (r) {
        is UrlImportResult.Stored -> LiveWork.Outcome.DONE to WordlistImport.message(r)
        is UrlImportResult.Failed -> LiveWork.Outcome.FAILED to WordlistImport.message(r)
        UrlImportResult.Cancelled -> LiveWork.Outcome.STOPPED to WordlistCopy.CANCELLED
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: replace
```kotlin
            log = { Log.e(AppScopes.LOG_TAG, "url import", it) })
```
with
```kotlin
            log = { Log.e(AppScopes.LOG_TAG, "url import", it) }, liveWork = liveWork)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
1. After `import com.northwalessolutions.netprobe.ui.tools.ToolCopy` add
```kotlin
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.scans.tracked
import com.northwalessolutions.netprobe.ui.scans.ScansCopy
```
2. Replace
```kotlin
            try { discoverOn(e) } finally { c.radarScanActive.value = false; c.radarScanNetworkId.value = null }
```
with
```kotlin
            try { trackRadar { discoverOn(e) } } finally { c.radarScanActive.value = false; c.radarScanNetworkId.value = null }
```
3. Replace
```kotlin
    private suspend fun quickScanAllOn(e: NetworkEnv) { c.undo.committedBefore { quickScanAllBody(e) } }
```
with
```kotlin
    /**
     * The radar scan as a Scans item (3D spec 3.2): Stop there calls [stopScan]. [body] returns the devices found, or null when the scan
     * did not run (a Forget in flight), which removes the entry without recording it. A cleared ViewModel cancels the job, which ends
     * the entry STOPPED, so a Scans row never stays RUNNING with a Stop that does nothing.
     */
    private suspend fun trackRadar(body: suspend () -> Int?) {
        c.liveWork.tracked<Int>(LiveWork.Kind.RADAR, c.currentNetwork.value?.id, null, null, stop = { stopScan() },
            summarize = { found, seconds -> LiveWork.Outcome.DONE to ScansCopy.radarDone(found, seconds) },
            stopped = { seconds -> ScansCopy.stoppedAfter(seconds) }, failed = { t -> ScanController.failureMessage(t) }) { body() }
    }

    private suspend fun quickScanAllOn(e: NetworkEnv) {
        c.undo.committedBefore {
            c.liveWork.tracked<Int>(LiveWork.Kind.QUICK_SCAN_ALL, networkId.value, null, null, stop = { stopQuickScan() },
                summarize = { scanned, seconds -> LiveWork.Outcome.DONE to ScansCopy.quickDone(scanned, seconds) },
                stopped = { seconds -> ScansCopy.stoppedAfter(seconds) }, failed = { t -> ScanController.failureMessage(t) }) { id -> quickScanAllBody(e, id) }
        }
    }
```
4. Replace `    private suspend fun discoverOn(e: NetworkEnv) {` with `    private suspend fun discoverOn(e: NetworkEnv): Int? {`.
5. Replace `        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)) ?: return\n        if (net.id == c.forgottenNetworkId.value) return   // a Forget on this network is in flight: it is unavailable` with
```kotlin
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)) ?: return null
        if (net.id == c.forgottenNetworkId.value) return null   // a Forget on this network is in flight: it is unavailable
```
(the second line keeps its trailing comment as written in the file; if the comment differs, only the `return` changes).
6. Replace `        var completed = false\n        c.locks.acquire()` with `        var completed = false\n        var found = 0\n        c.locks.acquire()`.
7. Replace `            completed = true; lastRunCompleted.value = true` with `            completed = true; lastRunCompleted.value = true\n            found = c.discoveryEngine.status.value.found`.
8. Replace
```kotlin
                end.notice?.let { extraMessage.value = it }
            }
        }
    }
```
with
```kotlin
                end.notice?.let { extraMessage.value = it }
            }
        }
        return found
    }
```
9. Replace
```kotlin
    private suspend fun quickScanAllBody(e: NetworkEnv) {
        var failed = 0; var unbound = 0; var skipped = 0
```
with
```kotlin
    private suspend fun quickScanAllBody(e: NetworkEnv, liveId: Long): Int? {
        var failed = 0; var unbound = 0; var skipped = 0; var scanned = 0; var handled = 0
```
10. Replace `            val net = networkId.value ?: return\n` (inside `quickScanAllBody`) with `            val net = networkId.value ?: return null\n`.
11. Replace
```kotlin
            QuickScanAllLoop.run(c.hosts.hostsForNetwork(net).first(), { it.id },
```
with
```kotlin
            val toScan = c.hosts.hostsForNetwork(net).first()
            c.liveWork.progress(liveId, 0, toScan.size, 0)
            QuickScanAllLoop.run(toScan, { it.id },
```
12. Replace
```kotlin
                    try { quickScanHost(h, e, runSettings) } catch (ce: CancellationException) { throw ce } catch (_: BindFailedException) { unbound++ } catch (_: Exception) { failed++ }
                } finally { c.hostBusy.release(h.id, HostBusyRegistry.Work.PORT_SCAN) }
```
with
```kotlin
                    try { quickScanHost(h, e, runSettings); scanned++ } catch (ce: CancellationException) { throw ce } catch (_: BindFailedException) { unbound++ } catch (_: Exception) { failed++ }
                } finally {
                    c.hostBusy.release(h.id, HostBusyRegistry.Work.PORT_SCAN)
                    c.liveWork.progress(liveId, ++handled, toScan.size, 0)
                }
```
13. Replace
```kotlin
            if (parts.isNotEmpty()) extraMessage.value = parts.joinToString(". ")
        }
    }
```
with
```kotlin
            if (parts.isNotEmpty()) extraMessage.value = parts.joinToString(". ")
        }
        return scanned
    }
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.scans.*" --tests "com.northwalessolutions.netprobe.ui.wordlists.*" --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures (the 3C-1 `UrlImportControllerTest` is unchanged and green: `liveWork` is last and defaulted).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
grep -n "liveWork" app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt | cut -c1-140
```
Expected: `BUILD SUCCESSFUL`; the grep shows `trackRadar`, `quickScanAllOn` and the three `quickScanAllBody` progress lines.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportController.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/test/java/com/northwalessolutions/netprobe/scans/UrlImportLiveWorkTest.kt
git status --short
git commit -m "Phase 3D: radar scan, Quick scan all and URL imports report to LiveWork"
```

---

### Task 5: The Scans state model: ScansCopy, ScansRules, targets, badge count, snack priority and the Clear history store

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansModel.kt`, `ui/scans/ScansRules.kt`, `ui/scans/ScansTargets.kt`, `ui/scans/ScansHistoryStore.kt`
- Modify (whole file): `ui/scans/ScansCopy.kt`; `AppContainer.kt` (`scansHistory`, `scansRunning`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansRulesTest.kt`, `ScansTargetsTest.kt`, `ScansCopyTableTest.kt`, `ScansHistoryStoreTest.kt`

**Interfaces:**
- Consumes: `LiveWork.Live`, `LiveWork.Ended`, `LiveWork.RECENT_LIMIT` (Task 1), `ToolRunner.Active`, `ToolRunner.MAX_ACTIVE_RUNS`, `NetworkRun`, `ToolRunEntity`, `ToolRunStatus`, `ToolsUiModel.bar` and `summaryOf`, `ToolCopy.toolName`, `ProgressLine.of`, `detailProgress`, `WordlistCopy.fetching`, `RelativeTime.of`, `PortScanState`, `AppMessage`, `DiscoveryStatus`.
- Produces:
```kotlin
enum class ScansSource { RADAR, QUICK_SCAN_ALL, PORT_SCAN, TOOL, WORDLIST_IMPORT }
enum class ScansState { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }
data class ScansItem(key, source, liveId: Long?, networkId, hostId, host, tool: ToolId?, port, state, done, total, found, startedAt, durationMs, endedAt, summary, runId, title: String, detail: String)
data class ScansList(val running: List<ScansItem> = emptyList(), val recent: List<ScansItem> = emptyList())
data class ScansInput(live, active, ended, history, hostNames, currentNetworkId, now, clearedAt, forgottenNetworkId, removedHostIds, radarStatus = null, portStates = emptyMap(), formatDate = ...)
object ScansRules { const val RECENT_WINDOW_MS; RECENT_LIMIT; HISTORY_FETCH
    fun build(i: ScansInput): ScansList; fun badge(running: List<ScansItem>): Int; fun runningCount(live, active): Int; fun snack(undoText: String?, clearPrompt: Boolean, app: AppMessage?): ScansSnack? }
sealed interface ScansSnack { val key: String;  Undo(text), Cleared, App(id, text) }
sealed interface ScansTap { DevicesTab, Device(hostId), Results(runId), Wordlists, None }
sealed interface StopRoute { Live(id), Tool(hostId) }
object ScansTargets { fun tap(item, visibleHostIds: Set<Long>): ScansTap; fun stop(item): StopRoute? }
class ScansHistoryStore(prefs) { val clearedAt: StateFlow<Long>; fun set(at: Long); companion KEY = "scansClearedAt" }
AppContainer.scansHistory: ScansHistoryStore; AppContainer.scansRunning: StateFlow<Int>
```
Rule deviations from the spec text, each deliberate and listed in the Self-Review Notes: the item gains `title` and `detail` (computed in the pure layer so the screen stays dumb and the wording is testable), and a port scan's Stop goes through `StopRoute.Live(id)` rather than a host id, so a stale tap can never stop a newer scan of the same device (Review Focus 2).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.IdentifyCount
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 items 4 and 5 (rules, window, clear, drops, badge) plus Review Focus 4. */
class ScansRulesTest {
    private val now = 10L * ScansRules.RECENT_WINDOW_MS
    private val day = ScansRules.RECENT_WINDOW_MS

    private fun live(id: Long, kind: LiveWork.Kind = LiveWork.Kind.PORT_SCAN, hostId: Long? = 7, startedAt: Long = now - 5_000, networkId: Long? = 1, label: String? = null,
                     done: Int = 0, total: Int? = null, found: Int = 0) = LiveWork.Live(id, kind, networkId, hostId, label, startedAt, done, total, found)
    private fun ended(id: Long, endedAt: Long?, startedAt: Long = (endedAt ?: now) - 3_000, outcome: LiveWork.Outcome = LiveWork.Outcome.DONE, summary: String = "Done in 3 s, 2 open ports",
                      kind: LiveWork.Kind = LiveWork.Kind.PORT_SCAN, hostId: Long? = 7, networkId: Long? = 1) =
        LiveWork.Ended(live(id, kind, hostId, startedAt, networkId), outcome, endedAt, summary)
    private fun run(id: Long, status: ToolRunStatus, startedAt: Long, finishedAt: Long?, hostId: Long = 7, tool: ToolId = ToolId.HTTP_FINGERPRINT, port: Int = 80, summary: String = "Done") =
        NetworkRun(ToolRunEntity(id = id, hostId = hostId, toolId = tool.key, port = port, startedAt = startedAt, finishedAt = finishedAt, status = status, optionsJson = "{}", summary = summary),
            ip = "10.0.3.$hostId")
    private fun active(runId: Long, hostId: Long = 7, tool: ToolId = ToolId.DIR_BRUTEFORCE, port: Int = 443, activeMs: Long = 4_000, done: Int = 10, total: Int? = 100, found: Int = 2) =
        ToolRunner.Active(runId, hostId, tool, port, activeMs, done, total, found, 0, false)
    private fun input(live: List<LiveWork.Live> = emptyList(), active: Map<Long, ToolRunner.Active> = emptyMap(), ended: List<LiveWork.Ended> = emptyList(),
                      history: List<NetworkRun> = emptyList(), names: Map<Long, String> = mapOf(7L to "Office NAS"), currentNetworkId: Long? = 1, clearedAt: Long = 0,
                      forgotten: Long? = null, removed: Set<Long> = emptySet(), radar: DiscoveryStatus? = null, ports: Map<Long, PortScanState> = emptyMap()) =
        ScansInput(live, active, ended, history, names, currentNetworkId, now, clearedAt, forgotten, removed, radar, ports, formatDate = { "3 Jan 2026" })

    @Test fun `running is newest first and a tie breaks by key`() {
        val l = ScansRules.build(input(live = listOf(live(1, startedAt = now - 9_000), live(3, startedAt = now - 1_000), live(2, startedAt = now - 1_000)))).running
        assertEquals(listOf("live:2", "live:3", "live:1"), l.map { it.key })
    }
    @Test fun `recent is newest first and an interrupted item is windowed and sorted by its start with no duration`() {
        val e = listOf(ended(1, now - 3_000), ended(2, now - 1_000), ended(3, null, startedAt = now - 2_000, outcome = LiveWork.Outcome.INTERRUPTED, summary = ScansCopy.INTERRUPTED))
        val recent = ScansRules.build(input(ended = e)).recent
        assertEquals(listOf("live:2", "live:3", "live:1"), recent.map { it.key })
        val lost = recent.first { it.key == "live:3" }
        assertEquals(ScansState.INTERRUPTED, lost.state); assertNull(lost.durationMs); assertNull(lost.endedAt)
        assertEquals("Interrupted when NetProbe closed, just now", lost.detail)
        val old = ended(4, null, startedAt = now - day - 1, outcome = LiveWork.Outcome.INTERRUPTED)
        assertTrue("windowed by its start", ScansRules.build(input(ended = listOf(old))).recent.isEmpty())
    }
    @Test fun `a live tool run and its RUNNING history row give one running item`() {
        val l = ScansRules.build(input(active = mapOf(7L to active(5)), history = listOf(run(5, ToolRunStatus.RUNNING, now - 4_000, null))))
        assertEquals(listOf("tool:5"), l.running.map { it.key }); assertTrue(l.recent.isEmpty())
        assertEquals(now - 4_000, l.running.single().startedAt)
    }
    @Test fun `a RUNNING history row with no live run is dropped`() {
        val l = ScansRules.build(input(history = listOf(run(5, ToolRunStatus.RUNNING, now - 4_000, null))))
        assertTrue(l.running.isEmpty()); assertTrue(l.recent.isEmpty())
    }
    @Test fun `four RUNNING rows among fifty four fetched still leave fifty recent`() {
        val running = (1L..4L).map { run(100 + it, ToolRunStatus.RUNNING, now - it, null, hostId = 8) }
        val done = (1L..50L).map { run(it, ToolRunStatus.DONE, now - 10_000 - it, now - 5_000 - it) }
        val l = ScansRules.build(input(history = running + done))
        assertEquals(ScansRules.RECENT_LIMIT, l.recent.size); assertTrue(l.recent.all { it.state == ScansState.DONE })
        assertEquals(ScansRules.RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS, ScansRules.HISTORY_FETCH)
    }
    @Test fun `the window is twenty four hours inclusive of one millisecond inside`() {
        val inside = ended(1, now - day + 1); val outside = ended(2, now - day - 1)
        assertEquals(listOf("live:1"), ScansRules.build(input(ended = listOf(inside, outside))).recent.map { it.key })
    }
    @Test fun `the fifty first recent item is excluded`() {
        val e = (1L..51L).map { ended(it, now - it * 1_000) }
        val recent = ScansRules.build(input(ended = e)).recent
        assertEquals(50, recent.size); assertFalse(recent.any { it.key == "live:51" })
    }
    @Test fun `items at or before the clear time are excluded and after it included`() {
        val e = listOf(ended(1, 5_000_000), ended(2, 5_000_001), ended(3, 4_999_999))
        assertEquals(listOf("live:2"), ScansRules.build(input(ended = e, clearedAt = 5_000_000)).recent.map { it.key })
    }
    @Test fun `a forgotten network and a removed host drop their items in both sections`() {
        val l = ScansRules.build(input(
            live = listOf(live(1, networkId = 9), live(2, hostId = 8, networkId = 1), live(3, hostId = 7, networkId = 1)),
            ended = listOf(ended(4, now - 1_000, networkId = 9), ended(5, now - 1_000, hostId = 8), ended(6, now - 1_000)),
            forgotten = 9, removed = setOf(8)))
        assertEquals(listOf("live:3"), l.running.map { it.key }); assertEquals(listOf("live:6"), l.recent.map { it.key })
    }
    @Test fun `tool history is empty with no current network but memory outcomes stay`() {
        val l = ScansRules.build(input(history = listOf(run(1, ToolRunStatus.DONE, now - 9_000, now - 8_000)), ended = listOf(ended(2, now - 1_000)), currentNetworkId = null))
        assertEquals(listOf("live:2"), l.recent.map { it.key })
    }
    @Test fun `a manual only host resolves to its IP and a known host to its name`() {
        val l = ScansRules.build(input(history = listOf(run(1, ToolRunStatus.DONE, now - 9_000, now - 8_000, hostId = 12), run(2, ToolRunStatus.DONE, now - 7_000, now - 6_000, hostId = 7)),
            names = mapOf(7L to "Office NAS")))
        assertEquals("HTTP fingerprint on 10.0.3.12:80", l.recent.first { it.key == "tool:1" }.title)
        assertEquals("HTTP fingerprint on Office NAS:80", l.recent.first { it.key == "tool:2" }.title)
    }
    @Test fun `an import item carries its URL host`() {
        val l = ScansRules.build(input(live = listOf(live(1, LiveWork.Kind.WORDLIST_IMPORT, hostId = null, label = "raw.githubusercontent.com", done = 1_258_291, total = 3_565_158))))
        val item = l.running.single()
        assertEquals("Wordlist from raw.githubusercontent.com", item.title); assertEquals("raw.githubusercontent.com", item.host)
        assertEquals("Fetching 1.2 of 3.4 MB", item.detail); assertEquals(ScansSource.WORDLIST_IMPORT, item.source)
    }
    @Test fun `running rows say what each kind is doing`() {
        val ports = mapOf(7L to PortScanState(ScanProgress(312, 65_535, 4, 0), IdentifyCount(0, 0)))
        val l = ScansRules.build(input(
            live = listOf(live(1, LiveWork.Kind.RADAR, hostId = null, startedAt = now - 7_000), live(2, LiveWork.Kind.QUICK_SCAN_ALL, hostId = null, startedAt = now - 42_000, done = 3, total = 30),
                live(3, LiveWork.Kind.PORT_SCAN, hostId = 7, startedAt = now - 1_000, done = 312, total = 65_535, found = 4)),
            active = mapOf(7L to active(5, hostId = 9, activeMs = 4_000)), ports = ports, radar = DiscoveryStatus(probed = 171, total = 254, found = 9),
            names = mapOf(7L to "Office NAS", 9L to "10.0.3.9")))
        val byKey = l.running.associateBy { it.key }
        assertEquals("Radar scan", byKey.getValue("live:1").title); assertEquals("probing 171 of 254  7 s", byKey.getValue("live:1").detail)
        assertEquals("Quick scan all", byKey.getValue("live:2").title); assertEquals("3 of 30 devices, 42 s", byKey.getValue("live:2").detail)
        assertEquals("Port scan on Office NAS", byKey.getValue("live:3").title); assertEquals("312 of 65535, found 4", byKey.getValue("live:3").detail)
        assertEquals("Directory brute-force on 10.0.3.9:443", byKey.getValue("tool:5").title)
        assertEquals(4_000L, byKey.getValue("tool:5").durationMs)
    }
    @Test fun `a port scan identifying its ports says so`() {
        val ports = mapOf(7L to PortScanState(ScanProgress(65_535, 65_535, 5, 0), IdentifyCount(3, 1)))
        val item = ScansRules.build(input(live = listOf(live(1, done = 65_535, total = 65_535, found = 5)), ports = ports)).running.single()
        assertEquals("identifying 3 ports", item.detail)
    }
    @Test fun `a finished scan shows its summary and when`() {
        val item = ScansRules.build(input(ended = listOf(ended(1, now - 125_000, startedAt = now - 137_000)))).recent.single()
        assertEquals("Done in 3 s, 2 open ports, 2 minutes ago", item.detail); assertEquals(12_000L, item.durationMs)
    }
    @Test fun `history rows map their status one to one`() {
        val h = listOf(ToolRunStatus.DONE, ToolRunStatus.STOPPED, ToolRunStatus.FAILED, ToolRunStatus.INTERRUPTED).mapIndexed { i, s ->
            run(i + 1L, s, now - 9_000 - i, if (s == ToolRunStatus.INTERRUPTED) null else now - 8_000 - i, summary = if (s == ToolRunStatus.INTERRUPTED) "Interrupted, 4 found" else "x") }
        val byKey = ScansRules.build(input(history = h)).recent.associateBy { it.key }
        assertEquals(ScansState.DONE, byKey.getValue("tool:1").state); assertEquals(ScansState.STOPPED, byKey.getValue("tool:2").state)
        assertEquals(ScansState.FAILED, byKey.getValue("tool:3").state); assertEquals(ScansState.INTERRUPTED, byKey.getValue("tool:4").state)
        assertNull(byKey.getValue("tool:4").endedAt)
        assertTrue(byKey.getValue("tool:4").detail.startsWith("Interrupted, 4 found, "))
    }
    @Test fun `a clock that went backwards never gives a negative duration and a future dated item is still recent`() {
        val backwards = ended(1, endedAt = now - 10_000, startedAt = now - 2_000)          // ended before it started
        val future = ended(2, endedAt = now + 3_600_000, startedAt = now + 3_000_000)       // the clock was set back after it finished
        val l = ScansRules.build(input(ended = listOf(backwards, future)))
        assertEquals(0L, l.recent.first { it.key == "live:1" }.durationMs)
        assertTrue(l.recent.all { (it.durationMs ?: 0) >= 0 })
        assertTrue("a future dated item stays listed", l.recent.any { it.key == "live:2" })
        assertEquals("the item that sorts newest is the future one", "live:2", l.recent.first().key)
        assertEquals("just now", l.recent.first { it.key == "live:2" }.detail.substringAfterLast(", "))
    }
    @Test fun `the running count matches the running list and the badge is its size`() {
        val live = listOf(live(1), live(2, LiveWork.Kind.RADAR, hostId = null)); val act = mapOf(7L to active(5), 8L to active(6, hostId = 8))
        val list = ScansRules.build(input(live = live, active = act)).running
        assertEquals(ScansRules.runningCount(live, act), list.size); assertEquals(4, ScansRules.badge(list)); assertEquals(0, ScansRules.badge(emptyList()))
    }
    @Test fun `the snackbar order is Undo first, then the clear prompt, then an app message`() {
        val msg = AppMessage(5, "HTTP fingerprint finished: done", 7)
        assertEquals(ScansSnack.Undo("Removed DiskStation"), ScansRules.snack("Removed DiskStation", true, msg))
        assertEquals(ScansSnack.Cleared, ScansRules.snack(null, true, msg))
        assertEquals(ScansSnack.App(5, "HTTP fingerprint finished: done"), ScansRules.snack(null, false, msg))
        assertNull(ScansRules.snack(null, false, null))
        assertEquals(3, listOf(ScansSnack.Undo("a").key, ScansSnack.Cleared.key, ScansSnack.App(1, "b").key).distinct().size)
    }
}
```
`app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansTargetsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.tools.ToolId
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 6 (every row of 3.6) and Review Focus 2. */
class ScansTargetsTest {
    private fun item(source: ScansSource, state: ScansState = ScansState.RUNNING, hostId: Long? = 7, runId: Long? = null, liveId: Long? = 3) =
        ScansItem("k", source, liveId, 1, hostId, null, null, null, state, null, null, null, 0, null, null, null, runId, "t", "d")
    private val visible = setOf(7L)

    @Test fun `radar and Quick scan all open the Devices destination, running or finished`() {
        for (s in listOf(ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL)) for (st in listOf(ScansState.RUNNING, ScansState.DONE)) {
            assertEquals(ScansTap.DevicesTab, ScansTargets.tap(item(s, st, hostId = null), visible))
        }
    }
    @Test fun `a port scan opens its device when the host is visible and nothing when it is gone`() {
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.PORT_SCAN), visible))
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.PORT_SCAN, ScansState.DONE), visible))
        assertEquals(ScansTap.None, ScansTargets.tap(item(ScansSource.PORT_SCAN, ScansState.DONE, hostId = 99), visible))
    }
    @Test fun `a running tool on a visible host opens the device and on a manual only host opens the results`() {
        assertEquals(ScansTap.Device(7), ScansTargets.tap(item(ScansSource.TOOL, runId = 5), visible))
        assertEquals(ScansTap.Results(5), ScansTargets.tap(item(ScansSource.TOOL, hostId = 12, runId = 5), visible))
    }
    @Test fun `a finished tool run opens its results sheet`() {
        assertEquals(ScansTap.Results(5), ScansTargets.tap(item(ScansSource.TOOL, ScansState.DONE, runId = 5), visible))
        assertEquals(ScansTap.None, ScansTargets.tap(item(ScansSource.TOOL, ScansState.DONE, runId = null), visible))
    }
    @Test fun `an import opens Wordlists`() {
        assertEquals(ScansTap.Wordlists, ScansTargets.tap(item(ScansSource.WORDLIST_IMPORT, hostId = null), visible))
        assertEquals(ScansTap.Wordlists, ScansTargets.tap(item(ScansSource.WORDLIST_IMPORT, ScansState.DONE, hostId = null), visible))
    }
    @Test fun `running items stop through their live id and tools through their host`() {
        for (s in listOf(ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL, ScansSource.PORT_SCAN, ScansSource.WORDLIST_IMPORT)) {
            assertEquals(StopRoute.Live(3), ScansTargets.stop(item(s)))
        }
        assertEquals(StopRoute.Tool(7), ScansTargets.stop(item(ScansSource.TOOL, runId = 5)))
    }
    @Test fun `finished items have no stop route`() {
        for (s in ScansSource.entries) for (st in ScansState.entries.filter { it != ScansState.RUNNING }) assertNull("$s $st", ScansTargets.stop(item(s, st)))
        assertNull("a running item with no live id has nothing to stop", ScansTargets.stop(item(ScansSource.PORT_SCAN, liveId = null)))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansCopyTableTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.portscan.PortScans
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 12: section 6 as regex templates; every helper is driven through a matrix of inputs. */
class ScansCopyTableTest {
    private val templates: List<Regex> = listOf(
        "Scans", "Running", "Recent", "Nothing running", "Scans and tool runs show here while they run, and finished ones stay for a day",
        "Clear history", "History cleared", "Undo", "Radar scan", "Quick scan all", "Port scan", "Stop", "device",
        "Port scan on .+", ".+ on .+:\\d+", "Wordlist from .+", "\\d+ of \\d+ devices, \\d+ s",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no devices|1 device|\\d+ devices)",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no devices|1 device|\\d+ devices) scanned",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no open ports|1 open port|\\d+ open ports)",
        "Stopped after (\\d+ s|\\d+ min( \\d+ s)?)", "Interrupted when NetProbe closed", "\\d+ s|\\d+ min( \\d+ s)?",
        ".+, (just now|\\d+ seconds ago|\\d+ minutes? ago|\\d+ hours? ago|yesterday|3 Jan 2026)", "Stop (port|radar|quick|directory|http|tls|snmp|smb|wordlist).+",
        "(One port scan is|\\w+ port scans are) already running, stop (it|one) in Scans",
    ).map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `fixed strings`() {
        listOf(ScansCopy.TITLE, ScansCopy.RUNNING, ScansCopy.RECENT, ScansCopy.EMPTY, ScansCopy.EMPTY_LINE, ScansCopy.CLEAR, ScansCopy.CLEARED, ScansCopy.UNDO,
            ScansCopy.RADAR, ScansCopy.QUICK, ScansCopy.PORT_SCAN_NAME, ScansCopy.STOP, ScansCopy.UNKNOWN_HOST, ScansCopy.INTERRUPTED).forEach { inTable(it) }
        assertEquals("Undo", com.northwalessolutions.netprobe.ui.home.HomeCopy.UNDO); assertEquals(com.northwalessolutions.netprobe.ui.home.HomeCopy.UNDO, ScansCopy.UNDO)
    }
    @Test fun `duration at the boundaries`() {
        assertEquals("0 s", ScansCopy.duration(0)); assertEquals("59 s", ScansCopy.duration(59)); assertEquals("1 min", ScansCopy.duration(60))
        assertEquals("2 min 4 s", ScansCopy.duration(124)); assertEquals("0 s", ScansCopy.duration(-5))
        listOf(0L, 8L, 59L, 60L, 124L, 3_600L).forEach { inTable(ScansCopy.duration(it)) }
    }
    @Test fun `summaries in every plural and zero form`() {
        for (s in listOf(0L, 14L, 124L)) {
            for (n in listOf(0, 1, 9)) { inTable(ScansCopy.radarDone(n, s)); inTable(ScansCopy.quickDone(n, s)); inTable(ScansCopy.portDone(n, s)) }
            inTable(ScansCopy.stoppedAfter(s))
        }
        assertEquals("Done in 12 s, 9 devices", ScansCopy.radarDone(9, 12)); assertEquals("Done in 12 s, 1 device", ScansCopy.radarDone(1, 12))
        assertEquals("Done in 2 min 4 s, 30 devices scanned", ScansCopy.quickDone(30, 124)); assertEquals("Done in 9 s, 1 device scanned", ScansCopy.quickDone(1, 9))
        assertEquals("Done in 14 s, 5 open ports", ScansCopy.portDone(5, 14)); assertEquals("Done in 14 s, 1 open port", ScansCopy.portDone(1, 14))
        assertEquals("Done in 14 s, no open ports", ScansCopy.portDone(0, 14)); assertEquals("Stopped after 8 s", ScansCopy.stoppedAfter(8))
    }
    @Test fun `titles and lines`() {
        inTable(ScansCopy.portScan("Office NAS")); inTable(ScansCopy.tool("Directory brute-force", "Office NAS", 443)); inTable(ScansCopy.tool("SNMP walk", "10.0.3.9", 161))
        inTable(ScansCopy.wordlistImport("raw.githubusercontent.com")); inTable(ScansCopy.quickProgress(3, 30, 42))
        assertEquals("Port scan on Office NAS", ScansCopy.portScan("Office NAS")); assertEquals("Directory brute-force on Office NAS:443", ScansCopy.tool("Directory brute-force", "Office NAS", 443))
        assertEquals("SNMP walk on 10.0.3.9:161", ScansCopy.tool("SNMP walk", "10.0.3.9", 161)); assertEquals("3 of 30 devices, 42 s", ScansCopy.quickProgress(3, 30, 42))
        for (r in listOf("just now", "5 seconds ago", "2 minutes ago", "1 hour ago", "yesterday", "3 Jan 2026")) inTable(ScansCopy.recentLine("Done in 12 s, 9 devices", r))
        assertEquals("Done in 12 s, 9 devices, 2 minutes ago", ScansCopy.recentLine("Done in 12 s, 9 devices", "2 minutes ago"))
    }
    @Test fun `stop description lower cases the first letter of the title`() {
        assertEquals("Stop port scan on Office NAS", ScansCopy.stopDescription("Port scan on Office NAS"))
        assertEquals("Stop radar scan", ScansCopy.stopDescription("Radar scan"))
        listOf("Port scan on Office NAS", "Radar scan", "Quick scan all", "Directory brute-force on Office NAS:443", "Wordlist from example.com").forEach { inTable(ScansCopy.stopDescription(it)) }
    }
    @Test fun `the cap line names the constant's value as a word`() {
        val word = listOf("Zero", "One", "Two", "Three", "Four")[PortScans.MAX_PORT_SCANS]
        assertTrue(DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS).startsWith(word))
        for (n in 1..4) inTable(DetailCopy.portScansTooMany(n))
        assertEquals("Two port scans are already running, stop one in Scans", DetailCopy.portScansTooMany(2))
    }
    @Test fun `no Scans string carries an em dash or the old Activity word`() {
        val dash = 0x2014.toChar()
        val all = listOf(ScansCopy.TITLE, ScansCopy.EMPTY_LINE, ScansCopy.INTERRUPTED, ScansCopy.portScan("x"), ScansCopy.radarDone(2, 5), DetailCopy.portScansTooMany(2))
        all.forEach { assertFalse(it, it.contains(dash)); assertFalse(it, it.contains("Activity")) }
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansHistoryStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.FakePrefs
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 13 and section 7: the cut-off is data, written once per tap, and a settings reset never touches it. */
class ScansHistoryStoreTest {
    @Test fun `set writes the cut-off once, publishes it and survives a new store`() {
        val prefs = FakePrefs(); val store = ScansHistoryStore(prefs)
        assertEquals(0L, store.clearedAt.value)
        store.set(1_234L)
        assertEquals(1, prefs.edits); assertEquals(1_234L, store.clearedAt.value)
        assertEquals(1_234L, ScansHistoryStore(prefs).clearedAt.value)
        store.set(0L); assertEquals("undo restores the previous value", 0L, ScansHistoryStore(prefs).clearedAt.value)
    }
    @Test fun `Reset to defaults leaves the cut-off and the open scan record alone`() {
        val prefs = FakePrefs(); ScansHistoryStore(prefs).set(777L); prefs.stored[com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore.KEY] = "[]"
        val settings = AppSettings(prefs); settings.update { SettingsState(mdns = false, sweepConcurrency = 256) }
        settings.update { SettingsDefaults.reset(it) }
        assertEquals(777L, prefs.getLong("scansClearedAt", -1)); assertEquals("[]", prefs.getString("scansOpenScans", null))
        assertEquals("scansClearedAt", ScansHistoryStore.KEY)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.scans.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ScansRules'`, `'ScansItem'`, `'ScansTargets'`, `'ScansHistoryStore'`, `'UNKNOWN_HOST'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansCopy.kt` (whole file, replacing Task 1's; a superset):
```kotlin
package com.northwalessolutions.netprobe.ui.scans

/** Every Scans string (3D spec 6, with the Director's "Scans" label). Templates live in ScansCopyTableTest. */
object ScansCopy {
    const val TITLE = "Scans"
    const val RUNNING = "Running"
    const val RECENT = "Recent"
    const val EMPTY = "Nothing running"
    const val EMPTY_LINE = "Scans and tool runs show here while they run, and finished ones stay for a day"
    const val CLEAR = "Clear history"
    const val CLEARED = "History cleared"
    /** The same text as HomeCopy.UNDO. */
    const val UNDO = "Undo"
    const val RADAR = "Radar scan"
    const val QUICK = "Quick scan all"
    /** The tool name in ToolCopy.finished: "Port scan finished: Done in 14 s, 5 open ports". */
    const val PORT_SCAN_NAME = "Port scan"
    const val INTERRUPTED = "Interrupted when NetProbe closed"
    const val STOP = "Stop"
    /** A row whose device is neither named nor in the history (a removed host's old outcome never reaches the screen, so this is rare). */
    const val UNKNOWN_HOST = "device"
    private const val SECONDS_PER_MINUTE = 60L

    fun portScan(host: String) = "Port scan on $host"
    /** The port is the number the run used: 161 for the SNMP walk. */
    fun tool(tool: String, host: String, port: Int) = "$tool on $host:$port"
    fun wordlistImport(host: String) = "Wordlist from $host"
    fun quickProgress(done: Int, total: Int, seconds: Long) = "$done of $total devices, $seconds s"

    /** "8 s" or "2 min 4 s", and "2 min" on an exact minute; never negative. */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        if (s < SECONDS_PER_MINUTE) return "$s s"
        val m = s / SECONDS_PER_MINUTE; val r = s % SECONDS_PER_MINUTE
        return if (r == 0L) "$m min" else "$m min $r s"
    }
    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    fun radarDone(found: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (found == 0) "no devices" else devices(found)}"
    fun quickDone(scanned: Int, seconds: Long) = "Done in ${duration(seconds)}, ${if (scanned == 0) "no devices" else devices(scanned)} scanned"
    fun portDone(open: Int, seconds: Long) =
        "Done in ${duration(seconds)}, ${when (open) { 0 -> "no open ports"; 1 -> "1 open port"; else -> "$open open ports" }}"
    fun stoppedAfter(seconds: Long) = "Stopped after ${duration(seconds)}"
    fun recentLine(summary: String, relative: String) = "$summary, $relative"
    /** The Stop pill's accessibility description: the title with its first letter lower-cased. */
    fun stopDescription(title: String) = "Stop " + title.replaceFirstChar { it.lowercase() }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansModel.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.tools.ToolId

enum class ScansSource { RADAR, QUICK_SCAN_ALL, PORT_SCAN, TOOL, WORDLIST_IMPORT }

/** There is no paused state: every pause is a background pause, so it is never on screen (spec 3.1). */
enum class ScansState { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }

/**
 * One row of the Scans screen. [key] is unique and stable ("live:<LiveWork id>" or "tool:<runId>"). [title] and [detail] are the two text
 * lines, built by ScansRules so the wording is testable: for a running row [detail] is the live progress, for a finished row it is
 * "summary, when". [liveId] is the LiveWork id of a running item, the handle its Stop uses.
 */
data class ScansItem(
    val key: String, val source: ScansSource, val liveId: Long?, val networkId: Long?, val hostId: Long?, val host: String?,
    val tool: ToolId?, val port: Int?, val state: ScansState, val done: Int?, val total: Int?, val found: Int?,
    val startedAt: Long, val durationMs: Long?, val endedAt: Long?, val summary: String?, val runId: Long?,
    val title: String, val detail: String,
) {
    val running: Boolean get() = state == ScansState.RUNNING
    /** The time the window, the clear cut-off and the sort use: the end, or the start of an item that has no end (Interrupted). */
    val sortTime: Long get() = endedAt ?: startedAt
}

data class ScansList(val running: List<ScansItem> = emptyList(), val recent: List<ScansItem> = emptyList())
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.portscan.ScanProgress
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.detail.detailProgress
import com.northwalessolutions.netprobe.ui.home.ProgressLine
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy

/** Everything the Scans list is built from, gathered by the view model (3D spec 3.4). */
data class ScansInput(
    val live: List<LiveWork.Live>, val active: Map<Long, ToolRunner.Active>, val ended: List<LiveWork.Ended>, val history: List<NetworkRun>,
    val hostNames: Map<Long, String>, val currentNetworkId: Long?, val now: Long, val clearedAt: Long, val forgottenNetworkId: Long?,
    val removedHostIds: Set<Long>, val radarStatus: DiscoveryStatus? = null, val portStates: Map<Long, PortScanState> = emptyMap(),
    val formatDate: (Long) -> String = RelativeTime::defaultDate,
)

sealed interface ScansSnack {
    val key: String
    data class Undo(val text: String) : ScansSnack { override val key: String = "undo:$text" }
    object Cleared : ScansSnack { override val key: String = "cleared" }
    data class App(val id: Long, val text: String) : ScansSnack { override val key: String = "app:$id" }
}

object ScansRules {
    const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000
    const val RECENT_LIMIT = LiveWork.RECENT_LIMIT
    /** recentForNetwork orders by startedAt and includes RUNNING rows, which build() drops; so up to MAX_ACTIVE_RUNS extra rows are fetched. */
    const val HISTORY_FETCH = RECENT_LIMIT + ToolRunner.MAX_ACTIVE_RUNS
    private const val MS_PER_S = 1000L

    fun build(i: ScansInput): ScansList {
        // Tool history belongs to the current network (spec 3.4 rule 7); with none, only the in-memory outcomes remain.
        val history = if (i.currentNetworkId == null) emptyList() else i.history
        val ipByHost = history.associate { it.run.hostId to it.ip }
        val c = Ctx(i, history, ipByHost)
        val running = (i.live.map { runningLive(it, c) } + i.active.values.map { runningTool(it, c) })
            .filterNot { dropped(it, i) }
            .sortedWith(compareByDescending<ScansItem> { it.startedAt }.thenBy { it.key })
        val finished = i.ended.map { endedLive(it, c) } + history.mapNotNull { historyItem(it, c) }
        val recent = finished.filterNot { dropped(it, i) }.filter { inWindow(it, i) }
            .sortedWith(compareByDescending<ScansItem> { it.sortTime }.thenBy { it.key })
            .take(RECENT_LIMIT)
        return ScansList(running, recent)
    }

    /** The bar badge (spec 3.7): the number of running rows. */
    fun badge(running: List<ScansItem>): Int = running.size
    /** The same number from the raw sources, for the container-level flow that needs no history or names. */
    fun runningCount(live: List<LiveWork.Live>, active: Map<Long, ToolRunner.Active>): Int = live.size + active.size

    /** One snackbar at a time: the UndoStore Undo first (spec 3.8), then the Clear history prompt, then an end message. */
    fun snack(undoText: String?, clearPrompt: Boolean, app: AppMessage?): ScansSnack? = when {
        undoText != null -> ScansSnack.Undo(undoText)
        clearPrompt -> ScansSnack.Cleared
        app != null -> ScansSnack.App(app.id, app.text)
        else -> null
    }

    private class Ctx(val i: ScansInput, val history: List<NetworkRun>, val ipByHost: Map<Long, String>) {
        fun host(hostId: Long?, fallback: String?): String =
            (hostId?.let { i.hostNames[it] }) ?: (hostId?.let { ipByHost[it] }) ?: fallback ?: ScansCopy.UNKNOWN_HOST
    }

    private fun dropped(item: ScansItem, i: ScansInput): Boolean =
        (item.networkId != null && item.networkId == i.forgottenNetworkId) || (item.hostId != null && item.hostId in i.removedHostIds)

    private fun inWindow(item: ScansItem, i: ScansInput): Boolean = i.now - item.sortTime <= RECENT_WINDOW_MS && item.sortTime > i.clearedAt

    private fun titleOf(kind: LiveWork.Kind, host: String, label: String?): String = when (kind) {
        LiveWork.Kind.RADAR -> ScansCopy.RADAR
        LiveWork.Kind.QUICK_SCAN_ALL -> ScansCopy.QUICK
        LiveWork.Kind.PORT_SCAN -> ScansCopy.portScan(host)
        LiveWork.Kind.WORDLIST_IMPORT -> ScansCopy.wordlistImport(label ?: "")
    }

    private fun sourceOf(kind: LiveWork.Kind): ScansSource = when (kind) {
        LiveWork.Kind.RADAR -> ScansSource.RADAR
        LiveWork.Kind.QUICK_SCAN_ALL -> ScansSource.QUICK_SCAN_ALL
        LiveWork.Kind.PORT_SCAN -> ScansSource.PORT_SCAN
        LiveWork.Kind.WORDLIST_IMPORT -> ScansSource.WORDLIST_IMPORT
    }

    private fun runningLive(l: LiveWork.Live, c: Ctx): ScansItem {
        val now = c.i.now
        val elapsedS = ((now - l.startedAt) / MS_PER_S).coerceAtLeast(0)
        val host = if (l.hostId != null) c.host(l.hostId, l.label) else null
        val detail = when (l.kind) {
            LiveWork.Kind.RADAR -> ProgressLine.of(c.i.radarStatus, elapsedS)
            LiveWork.Kind.QUICK_SCAN_ALL -> ScansCopy.quickProgress(l.done, l.total ?: 0, elapsedS)
            LiveWork.Kind.PORT_SCAN -> {
                val state = l.hostId?.let { c.i.portStates[it] }
                val progress: ScanProgress? = state?.progress ?: l.total?.let { ScanProgress(l.done, it, l.found, 0) }
                detailProgress(progress, state?.identify?.pending ?: 0)
            }
            LiveWork.Kind.WORDLIST_IMPORT -> WordlistCopy.fetching(l.done.toLong(), l.total?.toLong())
        }
        return ScansItem("live:${l.id}", sourceOf(l.kind), l.id, l.networkId, l.hostId, host ?: l.label.takeIf { l.kind == LiveWork.Kind.WORDLIST_IMPORT }, null, null,
            ScansState.RUNNING, l.done, l.total, l.found, l.startedAt, (now - l.startedAt).coerceAtLeast(0), null, null, null,
            titleOf(l.kind, host ?: ScansCopy.UNKNOWN_HOST, l.label), detail)
    }

    private fun runningTool(a: ToolRunner.Active, c: Ctx): ScansItem {
        val host = c.host(a.hostId, null)
        val startedAt = c.history.firstOrNull { it.run.id == a.runId }?.run?.startedAt ?: (c.i.now - a.activeMs)
        return ScansItem("tool:${a.runId}", ScansSource.TOOL, null, c.i.currentNetworkId, a.hostId, host, a.toolId, a.port, ScansState.RUNNING,
            a.done, a.total, a.found, startedAt, a.activeMs, null, null, a.runId,
            ScansCopy.tool(ToolCopy.toolName(a.toolId), host, a.port), ToolsUiModel.bar(a).progress)
    }

    private fun endedLive(e: LiveWork.Ended, c: Ctx): ScansItem {
        val l = e.live
        val host = if (l.hostId != null) c.host(l.hostId, l.label) else null
        val state = when (e.outcome) {
            LiveWork.Outcome.DONE -> ScansState.DONE
            LiveWork.Outcome.STOPPED -> ScansState.STOPPED
            LiveWork.Outcome.FAILED -> ScansState.FAILED
            LiveWork.Outcome.INTERRUPTED -> ScansState.INTERRUPTED
        }
        val time = e.endedAt ?: l.startedAt
        return ScansItem("live:${l.id}", sourceOf(l.kind), null, l.networkId, l.hostId, host ?: l.label.takeIf { l.kind == LiveWork.Kind.WORDLIST_IMPORT }, null, null,
            state, l.done, l.total, l.found, l.startedAt, e.endedAt?.let { (it - l.startedAt).coerceAtLeast(0) }, e.endedAt, e.summary, null,
            titleOf(l.kind, host ?: ScansCopy.UNKNOWN_HOST, l.label), ScansCopy.recentLine(e.summary, RelativeTime.of(time, c.i.now, c.i.formatDate)))
    }

    private fun historyItem(nr: NetworkRun, c: Ctx): ScansItem? {
        val tool = ToolId.byKey(nr.run.toolId) ?: return null
        val state = when (nr.run.status) {
            ToolRunStatus.RUNNING -> return null        // live (and then in Running) or in the gap between insert and publish (spec 3.4 rule 2)
            ToolRunStatus.DONE -> ScansState.DONE
            ToolRunStatus.STOPPED -> ScansState.STOPPED
            ToolRunStatus.FAILED -> ScansState.FAILED
            ToolRunStatus.INTERRUPTED -> ScansState.INTERRUPTED
        }
        val run = nr.run
        val host = c.host(run.hostId, nr.ip)
        val summary = ToolsUiModel.summaryOf(run)
        val time = run.finishedAt ?: run.startedAt
        return ScansItem("tool:${run.id}", ScansSource.TOOL, null, c.i.currentNetworkId, run.hostId, host, tool, run.port, state, null, null, null,
            run.startedAt, run.finishedAt?.let { (it - run.startedAt).coerceAtLeast(0) }, run.finishedAt, summary, run.id,
            ScansCopy.tool(ToolCopy.toolName(tool), host, run.port), ScansCopy.recentLine(summary, RelativeTime.of(time, c.i.now, c.i.formatDate)))
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansTargets.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

sealed interface ScansTap {
    object DevicesTab : ScansTap
    data class Device(val hostId: Long) : ScansTap
    data class Results(val runId: Long) : ScansTap
    object Wordlists : ScansTap
    object None : ScansTap
}

/** How Stop reaches the work. Every LiveWork item stops by its own id, so a stale tap can never stop a newer run of the same device. */
sealed interface StopRoute {
    data class Live(val id: Long) : StopRoute
    data class Tool(val hostId: Long) : StopRoute
}

/** Where a tap on a row goes and what its Stop does (3D spec 3.6), pure so each row of the table is tested. */
object ScansTargets {
    fun tap(item: ScansItem, visibleHostIds: Set<Long>): ScansTap {
        val hostId = item.hostId
        return when (item.source) {
            ScansSource.RADAR, ScansSource.QUICK_SCAN_ALL -> ScansTap.DevicesTab
            ScansSource.PORT_SCAN -> if (hostId != null && hostId in visibleHostIds) ScansTap.Device(hostId) else ScansTap.None
            ScansSource.TOOL -> when {
                item.running && hostId != null && hostId in visibleHostIds -> ScansTap.Device(hostId)
                item.runId != null -> ScansTap.Results(item.runId)
                else -> ScansTap.None
            }
            ScansSource.WORDLIST_IMPORT -> ScansTap.Wordlists
        }
    }

    fun stop(item: ScansItem): StopRoute? {
        if (!item.running) return null
        return when (item.source) {
            ScansSource.TOOL -> item.hostId?.let { StopRoute.Tool(it) }
            else -> item.liveId?.let { StopRoute.Live(it) }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansHistoryStore.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Clear history cut-off (3D spec 3.8): recent items finished at or before it are hidden. Nothing is deleted. The key is data, not a
 * setting, so "Reset to defaults" (which rewrites only the covered settings keys) never touches it.
 */
class ScansHistoryStore(private val prefs: SharedPreferences) {
    private val _clearedAt = MutableStateFlow(prefs.getLong(KEY, 0L))
    val clearedAt: StateFlow<Long> = _clearedAt

    fun set(at: Long) {
        prefs.edit().putLong(KEY, at).apply()
        _clearedAt.value = at
    }

    companion object { const val KEY = "scansClearedAt" }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`:
1. After `import com.northwalessolutions.netprobe.scans.LiveWork` add
```kotlin
import com.northwalessolutions.netprobe.ui.scans.ScansHistoryStore
import com.northwalessolutions.netprobe.ui.scans.ScansRules
import kotlinx.coroutines.flow.combine
```
2. Replace
```kotlin
    val portScanEngine by lazy { PortScanEngine() }
```
with (keeping Task 1's `portScans` block that follows it exactly as it is; only this first line gains two neighbours above it)
```kotlin
    val portScanEngine by lazy { PortScanEngine() }
    /** The Clear history cut-off (3D spec 3.8), a data key in the netprobe preferences. */
    val scansHistory by lazy { ScansHistoryStore(context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)) }
    /** How many things run right now (3D spec 3.7): the Scans bar badge. Reading it creates the tool runner, as the screen-on flag always did. */
    val scansRunning: StateFlow<Int> by lazy {
        combine(liveWork.live, toolRunner.active) { live, active -> ScansRules.runningCount(live, active) }.stateIn(appScope, SharingStarted.Eagerly, 0)
    }
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.scans.*" --tests "com.northwalessolutions.netprobe.engine.portscan.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures. If a `ScansRulesTest` detail string differs by a space, fix the helper (`ScansCopy`, `ProgressLine`), never the expectation: the expectations are the Director-visible wording.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansRules.kt app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansTargets.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansHistoryStore.kt app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansRulesTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansTargetsTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansCopyTableTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/scans/ScansHistoryStoreTest.kt
git status --short
git commit -m "Phase 3D: Scans state model with the 24 hour window, targets, badge count and Clear history store"
```

---

### Task 6: The Scans screen: ScansViewModel, ScansContent (pinned Running block), Clear history with Undo, and the shared root inset

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansViewModel.kt`, `ui/scans/ScansContent.kt`, `ui/Insets.kt`
- Modify: `ui/Common.kt` (`EmptyState` gains an optional second line)
- Test (compile only, run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/scans/ScansContentTest.kt`

**Interfaces:**
- Consumes: everything from Task 5 (`ScansRules.build`, `snack`, `ScansTargets`, `ScansHistoryStore`, `ScansCopy`), `AppContainer.liveWork`, `portScans`, `scansHistory`, `toolRunner`, `toolRepo.recentForNetwork`, `hosts.allHostsForNetwork`, `undo`, `appMessages`, `forgottenNetworkId`, `removedHostIds`, `currentNetwork`, `discoveryEngine.status`; `ToolResultsSheet`, `ToolResultsModel.ofRows`, `ToolExport`, `ActionPill`, `DeviceAction` (3A, 3B).
- Produces:
```kotlin
data class ScansUiState(val list: ScansList = ScansList(), val visibleHostIds: Set<Long> = emptySet(), val results: ToolResultsState? = null,
                        val undoText: String? = null, val clearPrompt: Boolean = false, val appMessage: AppMessage? = null)
class ScansViewModel(c: AppContainer) : ViewModel() { val state: StateFlow<ScansUiState>
    fun stop(item: ScansItem); fun openRun(runId: Long); fun olderRun(); fun newerRun(); fun closeResults(); fun shareRun(); fun copyFinding(row: FindingRow); fun openFinding(row: FindingRow)
    fun undo(); fun clearHistory(); fun undoClear(); fun dismissClear(); fun dismissApp(id: Long) }
data class ScansActions(onStop, onOpen, onClear, onUndo, onUndoClear, onClearDismissed, onDismissApp, onOlderRun, onNewerRun, onCloseResults, onShare, onCopyFinding, onOpenFinding)
@Composable fun ScansContent(state: ScansUiState, actions: ScansActions)
@Composable fun ScansScreen(container: AppContainer, onOpenDevices: () -> Unit, onOpenHost: (Long) -> Unit, onOpenWordlists: () -> Unit)
val LocalBarInset: ProvidableCompositionLocal<WindowInsets?>;  @Composable fun rootInsets(): WindowInsets
EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp), line: String? = null)
```
Layout (spec 3.5): a Scaffold whose `topBar` is the pinned column - status-bar padding, a title row ("Scans" and a 48 dp "Clear history" action, tag `scansClear`, disabled with nothing to clear), and, only while something runs, the Running block (header, then one row per item: title, progress text, a 2 dp progress line, and a trailing Stop pill with tag `stop:<key>`, minimum row height 56 dp). Below it a `LazyColumn` (tag `scansRecent`) with the "Recent" header and the recent rows (48 dp minimum, no controls). With nothing running and nothing recent, the empty state shows "Nothing running" and its line. The screen shows one snackbar at a time by `ScansRules.snack`: the pending `UndoStore` Undo first (dismissal commits nothing), then "History cleared" with Undo, then an end message from `AppMessages`.

- [ ] **Step 1: Write the Compose test (compile only, run in Task 12)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/scans/ScansContentTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.LocalBarInset
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 4: fake state only, no container, no database. Reduced motion keeps the indeterminate lines still. */
@RunWith(AndroidJUnit4::class)
class ScansContentTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private val actions = ScansActions(onStop = { events += "stop ${it.key}" }, onOpen = { events += "open ${it.key}" }, onClear = { events += "clear" },
        onUndo = { events += "undo" })
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))

    private fun running(n: Int) = ScansItem("live:$n", ScansSource.PORT_SCAN, n.toLong(), 1, n.toLong(), "Office NAS", null, null, ScansState.RUNNING, 10, 100, 2, 0, 1_000, null, null, null,
        "Port scan on NAS $n", "312 of 65535, found 4")
    private fun recent(n: Int) = ScansItem("live:${100 + n}", ScansSource.PORT_SCAN, null, 1, n.toLong(), "NAS $n", null, null, ScansState.DONE, null, null, null, 0, 14_000, 1, "Done in 14 s, 5 open ports",
        null, "Port scan on NAS $n", "Done in 14 s, 5 open ports, 2 minutes ago")
    private fun show(state: ScansUiState, fontScale: Float = 1f, bar: Boolean = false) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true, LocalBarInset provides (if (bar) WindowInsets(bottom = 80.dp) else null)) {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then phone) { ScansContent(state, actions) }
        } }
    }

    @Test fun theRunningBlockIsPinnedWhileTheRecentListScrolls() {
        show(ScansUiState(ScansList(running = (1..3).map(::running), recent = (1..40).map(::recent))))
        val before = rule.onNodeWithTag("running:live:2").getBoundsInRoot()
        rule.onNodeWithTag("scansRecent").performScrollToIndex(40)
        assertEquals(before, rule.onNodeWithTag("running:live:2").getBoundsInRoot())
        val runningNode = rule.onNodeWithTag("running:live:1").fetchSemanticsNode()
        assertFalse("no running row lives inside the scrolling list",
            generateSequence(runningNode.parent) { it.parent }.any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) })
    }
    @Test fun eachRunningRowHasAStopWithItsDescriptionAndTheStopFires() {
        show(ScansUiState(ScansList(running = listOf(running(1))), visibleHostIds = setOf(1L)))
        rule.onNodeWithTag("stop:live:1").assert(hasContentDescription("Stop port scan on NAS 1")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("stop live:1"), events)
        rule.onNodeWithText("Port scan on NAS 1").performClick()
        assertEquals("stop live:1", events.first()); assertEquals("open live:1", events.last())
    }
    @Test fun clearIsDisabledWithNoRecentItemsAndEnabledWithSome() {
        show(ScansUiState(ScansList(running = listOf(running(1)))))
        rule.onNodeWithTag("scansClear").assertIsNotEnabled()
    }
    @Test fun clearFiresWhenThereIsSomethingToClear() {
        show(ScansUiState(ScansList(recent = listOf(recent(1)))))
        rule.onNodeWithTag("scansClear").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("clear"), events)
    }
    @Test fun theEmptyStateSaysNothingIsRunning() {
        show(ScansUiState())
        rule.onNodeWithText("Nothing running").assertIsDisplayed()
        rule.onNodeWithText("Scans and tool runs show here while they run, and finished ones stay for a day").assertIsDisplayed()
        rule.onNodeWithTag("scansRunning").assertDoesNotExist()
    }
    @Test fun aPendingUndoShowsInTheSnackbarWithAnUndoAction() {
        show(ScansUiState(ScansList(recent = listOf(recent(1))), undoText = "Removed DiskStation"))
        rule.onNodeWithText("Removed DiskStation").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        assertEquals(listOf("undo"), events)
    }
    @Test fun atFontScale13WithEightRunningRowsAtLeastOneRecentRowIsStillVisible() {
        show(ScansUiState(ScansList(running = (1..8).map(::running), recent = (1..5).map(::recent))), fontScale = 1.3f, bar = true)
        rule.onNodeWithTag("recent:live:101").assertIsDisplayed()
        rule.onNodeWithTag("stop:live:8").assertIsDisplayed()
    }
}
```
(`DeviceConfigurationOverride` and its `then` are already used by `DetailContentTest`; add `import androidx.compose.ui.test.DeviceConfigurationOverride` only if the wildcard import does not already cover it, which it does on the Compose BOM in use.)

- [ ] **Step 2: Run the compile to see it fail**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED` with `Unresolved reference 'ScansContent'`, `'ScansActions'`, `'LocalBarInset'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/Insets.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * The bottom inset a root screen must clear (3D spec 1.4): the bar's height plus the gesture area while the bar shows, else the gesture
 * area. NetProbeShell provides it; null (a test, or a screen outside the shell) means the navigation bars alone, which is what every
 * screen used before the bar existed.
 */
val LocalBarInset = compositionLocalOf<WindowInsets?> { null }

/** What a root screen's Scaffold uses where it used `WindowInsets.navigationBars.union(WindowInsets.ime)`: the bar inset joined with the keyboard. */
@Composable
fun rootInsets(): WindowInsets = (LocalBarInset.current ?: WindowInsets.navigationBars).union(WindowInsets.ime)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Common.kt`: replace
```kotlin
fun EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp)) {
    Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = NetProbeText.secondary, textAlign = TextAlign.Center, color = NetProbeColors.textMuted)
    }
}
```
with
```kotlin
fun EmptyState(text: String, pad: PaddingValues = PaddingValues(0.dp), line: String? = null) {
    Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = NetProbeText.secondary, textAlign = TextAlign.Center, color = NetProbeColors.textMuted)
            // The optional second line (Scans: what shows here); Home's callers pass nothing and look as before.
            line?.let { Text(it, style = NetProbeText.data, textAlign = TextAlign.Center, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansViewModel.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.PendingUndo
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.NetworkEntity
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.portscan.PortScanState
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.scans.LiveWork
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import com.northwalessolutions.netprobe.ui.tools.ToolResultsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScansUiState(
    val list: ScansList = ScansList(), val visibleHostIds: Set<Long> = emptySet(), val results: ToolResultsState? = null,
    val undoText: String? = null, val clearPrompt: Boolean = false, val appMessage: AppMessage? = null,
)

/**
 * The Scans destination (3D spec 3). State is gathered from LiveWork, the tool runner, tool history and the host list and handed to the pure
 * ScansRules; the results sheet is hosted here exactly as the Tools screen hosts it. Stop and the other click handlers run on the main thread,
 * which is where the scan controllers expect to be touched.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ScansViewModel(private val c: AppContainer) : ViewModel() {
    private data class ResultsSource(val run: ToolRunEntity, val sameTool: List<ToolRunEntity>, val ip: String, val rows: List<FindingRow>)

    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val clearPrompt = MutableStateFlow(false)
    private var clearSnapshot: Pair<Long, List<LiveWork.Ended>>? = null

    private val networkId: Flow<Long?> = c.currentNetwork.map { it?.id }.distinctUntilChanged()
    private val history: Flow<List<NetworkRun>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.toolRepo.recentForNetwork(id, ScansRules.HISTORY_FETCH)
    }
    /** Every row of the network, manual targets included, so a manual run resolves to its IP (spec 3.4 rule 9). */
    private val hosts: Flow<List<HostEntity>> = networkId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.allHostsForNetwork(id) }
    /** The radar's progress text source, sampled as Home samples it. */
    private val radar: Flow<DiscoveryStatus> = c.discoveryEngine.status.sample(Motion.PROGRESS_SAMPLE_MS).onStart { emit(c.discoveryEngine.status.value) }
    /** Keeps "2 minutes ago" honest while nothing else changes. */
    private val tick: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(IDLE_TICK_MS) } }

    private val resultsSource: Flow<ResultsSource?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else c.toolRepo.run(id).flatMapLatest { r ->
            if (r == null) flowOf(null) else combine(c.toolRepo.findings(id), c.toolRepo.runsForHost(r.hostId), c.hosts.host(r.hostId)) { f, runs, h ->
                h?.let { ResultsSource(r, runs.filter { it.toolId == r.toolId }, it.ip, ToolResultsModel.rows(f)) }
            }
        }
    }

    val state: StateFlow<ScansUiState> = combine(listOf<Flow<Any?>>(c.liveWork.live, c.liveWork.ended, c.toolRunner.active, history, hosts, c.scansHistory.clearedAt,
        c.forgottenNetworkId, c.removedHostIds, radar, c.portScans.state, c.currentNetwork, c.undo.pending, c.appMessages.current, clearPrompt, resultsSource, tick)) { arr ->
        @Suppress("UNCHECKED_CAST") val live = arr[0] as List<LiveWork.Live>
        @Suppress("UNCHECKED_CAST") val ended = arr[1] as List<LiveWork.Ended>
        @Suppress("UNCHECKED_CAST") val active = arr[2] as Map<Long, ToolRunner.Active>
        @Suppress("UNCHECKED_CAST") val historyRows = arr[3] as List<NetworkRun>
        @Suppress("UNCHECKED_CAST") val hostRows = arr[4] as List<HostEntity>
        @Suppress("UNCHECKED_CAST") val removed = arr[7] as Set<Long>
        @Suppress("UNCHECKED_CAST") val ports = arr[9] as Map<Long, PortScanState>
        val network = arr[10] as NetworkEntity?
        val now = System.currentTimeMillis()
        val list = ScansRules.build(ScansInput(live, active, ended, historyRows, hostRows.associate { it.id to DisplayName.of(it) }, network?.id, now, arr[5] as Long,
            arr[6] as Long?, removed, arr[8] as DiscoveryStatus?, ports))
        val results = (arr[14] as ResultsSource?)?.let { src -> ToolResultsModel.ofRows(src.run, src.sameTool, src.ip, src.rows, active[src.run.hostId], now) }
        ScansUiState(list, hostRows.filter { !it.manualOnly }.map { it.id }.toSet(), results, (arr[11] as PendingUndo?)?.text, arr[13] as Boolean, arr[12] as AppMessage?)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScansUiState())

    init {
        // The results sheet closes when its run disappears (pruned by a newer run, or its host removed), as on the Device screen.
        viewModelScope.launch {
            resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (resultsRunId.value != null) resultsRunId.value = null }
        }
    }

    /** Every LiveWork item stops by its own id (a stale tap is a no-op); a tool run stops by its host, as the Device screen's Stop does. */
    fun stop(item: ScansItem) {
        when (val route = ScansTargets.stop(item)) {
            is StopRoute.Live -> c.liveWork.stop(route.id)
            is StopRoute.Tool -> c.toolRunner.stop(route.hostId)
            null -> Unit
        }
    }

    fun openRun(runId: Long) { resultsRunId.value = runId }
    fun olderRun() { stepRun(+1) }
    fun newerRun() { stepRun(-1) }
    private fun stepRun(delta: Int) {
        viewModelScope.launch {
            val current = resultsRunId.value ?: return@launch
            val shown = c.toolRepo.run(current).first() ?: return@launch
            val same = c.toolRepo.runsForHost(shown.hostId).first().filter { it.toolId == shown.toolId }
            same.getOrNull(same.indexOfFirst { it.id == current } + delta)?.let { resultsRunId.value = it.id }
        }
    }
    fun closeResults() { resultsRunId.value = null }
    fun shareRun() {
        viewModelScope.launch {
            val id = resultsRunId.value ?: return@launch
            val shown = c.toolRepo.run(id).first() ?: return@launch
            val h = c.hosts.byIdOnce(shown.hostId) ?: return@launch
            val export = ToolExport.of(shown, h.ip, c.toolRepo.findingsOnce(id))
            c.actionRunner.share(ToolExport.subject(export.toolName, h.ip, shown.port), ToolExport.text(export))
        }
    }
    /** As the Device screen (3B 8.5): Copy copies the URL when there is one, else the value; below API 33 a snackbar confirms. */
    fun copyFinding(row: FindingRow) {
        c.actionRunner.run(DeviceAction.Copy(ToolCopy.COPY, row.url ?: row.value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.appMessages.post(ToolCopy.COPIED)
    }
    fun openFinding(row: FindingRow) {
        val url = row.url ?: return
        if (!c.actionRunner.run(DeviceAction.OpenWith(url))) copyFinding(row)
    }

    /** The pending UndoStore Undo (Remove device from a page opened here lands the Director on this screen, D20). A dismissal commits nothing. */
    fun undo() { c.appScope.launch { c.undo.undo() } }
    fun dismissApp(id: Long) { c.appMessages.dismiss(id) }

    /** Clear history (spec 3.8): the cut-off is written once, the in-memory outcomes are hidden, nothing is deleted; Undo restores both. */
    fun clearHistory() {
        clearSnapshot = c.scansHistory.clearedAt.value to c.liveWork.ended.value
        c.scansHistory.set(System.currentTimeMillis())
        c.liveWork.clearEnded()
        clearPrompt.value = true
    }
    fun undoClear() {
        clearSnapshot?.let { (at, ended) -> c.scansHistory.set(at); c.liveWork.restoreEnded(ended) }
        clearSnapshot = null
        clearPrompt.value = false
    }
    /** The prompt ended without Undo (timeout, or leaving the screen, which ends the chance as in 3C 1.2). */
    fun dismissClear() {
        clearSnapshot = null
        clearPrompt.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val IDLE_TICK_MS = 30_000L
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansContent.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.scans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.EmptyState
import com.northwalessolutions.netprobe.ui.rootInsets
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolResultsSheet

data class ScansActions(
    val onStop: (ScansItem) -> Unit = {}, val onOpen: (ScansItem) -> Unit = {}, val onClear: () -> Unit = {}, val onUndo: () -> Unit = {},
    val onUndoClear: () -> Unit = {}, val onClearDismissed: () -> Unit = {}, val onDismissApp: (Long) -> Unit = {},
    val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {}, val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {},
    val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {},
)

private val TITLE_ROW_MIN = 56.dp
private val RUNNING_ROW_MIN = 56.dp
private val RECENT_ROW_MIN = 48.dp
private val ROW_VERTICAL = 6.dp
private val PROGRESS_HEIGHT = 2.dp
private val STOP_GAP = 12.dp

@Composable
fun ScansScreen(container: AppContainer, onOpenDevices: () -> Unit, onOpenHost: (Long) -> Unit, onOpenWordlists: () -> Unit) {
    val vm: ScansViewModel = viewModel(factory = viewModelFactory { initializer { ScansViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    ScansContent(s, ScansActions(onStop = vm::stop, onClear = vm::clearHistory, onUndo = vm::undo, onUndoClear = vm::undoClear, onClearDismissed = vm::dismissClear,
        onDismissApp = vm::dismissApp, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseResults = vm::closeResults, onShare = vm::shareRun,
        onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding,
        onOpen = { item ->
            when (val tap = ScansTargets.tap(item, s.visibleHostIds)) {
                ScansTap.DevicesTab -> onOpenDevices()
                is ScansTap.Device -> onOpenHost(tap.hostId)
                is ScansTap.Results -> vm.openRun(tap.runId)
                ScansTap.Wordlists -> onOpenWordlists()
                ScansTap.None -> Unit
            }
        }))
}

/** Stateless, so the Compose tests drive any state. PINNED: the title row and the Running block (with every Stop) are the Scaffold topBar. */
@Composable
fun ScansContent(state: ScansUiState, actions: ScansActions) {
    val snackbar = remember { SnackbarHostState() }
    val snack = ScansRules.snack(state.undoText, state.clearPrompt, state.appMessage)
    LaunchedEffect(snack?.key) {
        when (snack) {
            // A dismissal or timeout commits nothing: the Undo stays pending under the UndoStore rules (spec 3.5 item 6).
            is ScansSnack.Undo -> if (snackbar.showSnackbar(snack.text, actionLabel = ScansCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) actions.onUndo()
            ScansSnack.Cleared ->
                if (snackbar.showSnackbar(ScansCopy.CLEARED, actionLabel = ScansCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) actions.onUndoClear()
                else actions.onClearDismissed()
            is ScansSnack.App -> { snackbar.showSnackbar(snack.text); actions.onDismissApp(snack.id) }
            null -> Unit
        }
    }
    // Leaving the screen ends the chance to undo a clear (spec 3.8, as in 3C 1.2).
    DisposableEffect(Unit) { onDispose { actions.onClearDismissed() } }
    val list = state.list
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).testTag("scansPinned")) {
            TitleRow(canClear = list.recent.isNotEmpty(), onClear = actions.onClear)
            if (list.running.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("scansRunning")) {
                Text(ScansCopy.RUNNING, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 4.dp).semantics { heading() })
                list.running.forEach { item ->
                    key(item.key) { RunningRow(item, tappable = ScansTargets.tap(item, state.visibleHostIds) != ScansTap.None, actions) }
                }
            }
        }
    }) { pad ->
        if (list.running.isEmpty() && list.recent.isEmpty()) EmptyState(ScansCopy.EMPTY, pad, line = ScansCopy.EMPTY_LINE)
        else LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("scansRecent")) {
            if (list.recent.isNotEmpty()) item(key = "recentHeader") {
                Text(ScansCopy.RECENT, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = 8.dp).semantics { heading() })
            }
            items(list.recent, key = { it.key }) { item ->
                RecentRow(item, tappable = ScansTargets.tap(item, state.visibleHostIds) != ScansTap.None) { actions.onOpen(item) }
            }
        }
    }
    state.results?.let { r ->
        ToolResultsSheet(r, actions.onOlderRun, actions.onNewerRun, actions.onCopyFinding, actions.onOpenFinding, actions.onShare, actions.onCloseResults)
    }
}

@Composable
private fun TitleRow(canClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
        Text(ScansCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text,
            modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).alpha(if (canClear) 1f else DISABLED_ALPHA)
            .clickable(enabled = canClear, role = Role.Button, onClick = onClear).testTag("scansClear"), contentAlignment = Alignment.Center) {
            Text(ScansCopy.CLEAR, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = Dimens.gutter))
        }
    }
}

/** Title, progress text, a 2 dp line, and the Stop pill, which is never inside a scroll container (the block is the topBar). */
@Composable
private fun RunningRow(item: ScansItem, tappable: Boolean, actions: ScansActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = RUNNING_ROW_MIN).padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("running:${item.key}"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = tappable, role = Role.Button) { actions.onOpen(item) }) {
            Text(item.title, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.detail, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RowProgress(item)
        }
        ActionPill(ScansCopy.STOP, enabled = true, onClick = { actions.onStop(item) }, tag = "stop:${item.key}",
            modifier = Modifier.padding(start = STOP_GAP).semantics { contentDescription = ScansCopy.stopDescription(item.title) })
    }
}

/** Determinate when the total is known; indeterminate otherwise, and absent under reduced motion (spec 3.5 item 2). */
@Composable
private fun RowProgress(item: ScansItem) {
    val total = item.total; val done = item.done
    val modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(PROGRESS_HEIGHT)
    if (total != null && total > 0 && done != null) {
        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier, color = NetProbeColors.signal, trackColor = NetProbeColors.surface)
    } else if (!LocalReducedMotion.current) {
        LinearProgressIndicator(modifier = modifier, color = NetProbeColors.signal, trackColor = NetProbeColors.surface)
    }
}

/** Recent rows carry no controls (spec 3.5 item 3). */
@Composable
private fun RecentRow(item: ScansItem, tappable: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = RECENT_ROW_MIN).clickable(enabled = tappable, role = Role.Button, onClick = onClick)
        .padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("recent:${item.key}")) {
        Text(item.title, style = NetProbeText.rowName, color = NetProbeColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.detail, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
```

- [ ] **Step 4: Compile and run the JVM suite**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
grep -n "verticalScroll\|LazyColumn" app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansContent.kt
```
Expected: `BUILD SUCCESSFUL` twice; the grep shows exactly one `LazyColumn` (the Recent list) and no `verticalScroll`: no Stop and no Clear is inside a scroll container.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/Insets.kt app/src/main/java/com/northwalessolutions/netprobe/ui/Common.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansViewModel.kt app/src/main/java/com/northwalessolutions/netprobe/ui/scans/ScansContent.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/scans/ScansContentTest.kt
git status --short
git commit -m "Phase 3D: Scans screen with a pinned Running block, Stop, Clear history and Undo"
```

---

### Task 7: The navigation shell: nested graphs, the bottom bar, saved state, bar visibility, insets and transitions (STRONGER MODEL)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/NavCopy.kt`, `ui/NavBar.kt`
- Replace (whole file): `ui/Nav.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/RoutesTest.kt`, `BarRulesTest.kt`, `NavMotionTest.kt`, `NavCopyTableTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavTransitionTest.kt` (rewritten), `NavBarTest.kt`, `NavBadgeTest.kt`, additions to `HomeScreenTest.kt`

**Interfaces:**
- Consumes: `HomeScreen(container, onOpenHost, onOpenSettings)`, `DetailScreen(container, hostId, onBack, onOpenWordlists)`, `SettingsScreen(container, onBack, onOpenWordlists)`, `WordlistsScreen(container, onBack)`, `ManualToolsScreen(container, onBack, onOpenWordlists)` (3C-2), `ScansScreen` (Task 6), `AppContainer.portScans.active`, `scansRunning`, `toolRunActive`, `extendedRunActive`, `KeepScreenOn`, `LocalBarInset` (Task 6), `Motion.NAV_MS`, `NAV_ENTER_OFFSET`, `NAV_EXIT_OFFSET`, `LocalReducedMotion`.
- Produces:
```kotlin
enum class Tab(val graph: String, val root: String) { DEVICES("devicesTab","devices"), SCANS("scansTab","scans"), TOOLS("toolsTab","tools"), SETTINGS("settingsTab","settings") }
object Routes { const val HOST_ARG = "id"; fun hostPattern(tab): String; fun host(tab, id: Long): String; fun wordlists(tab): String; fun routesOf(tab): List<String>
                fun tabOf(route: String?): Tab?; fun isRoot(route: String?): Boolean; fun isHost(route: String?): Boolean }
object BarRules { fun visible(route: String?, imeVisible: Boolean): Boolean }
enum class NavMotionKind { SLIDE, FADE, NONE };  object NavMotion { fun kind(from: String?, to: String?, reduced: Boolean): NavMotionKind }
object TabSwitch { fun select(nav: NavHostController, tab: Tab) }
class NavSlots(devices, scans, tools, settings: @Composable () -> Unit, host: @Composable (Tab, Long, String) -> Unit = ..., wordlists: @Composable (Tab, String) -> Unit = ...)
@Composable fun NetProbeNavHost(nav: NavHostController, slots: NavSlots, modifier: Modifier = Modifier)
@Composable fun NetProbeShell(nav: NavHostController, scansRunning: Int, slots: NavSlots)
@Composable fun NetProbeNav(container: AppContainer)
@Composable fun NetProbeBar(selected: Tab?, scansRunning: Int, onSelect: (Tab) -> Unit)
object NavCopy { DEVICES, SCANS, TOOLS, SETTINGS, BACK_TO_DEVICE; fun label(tab); fun scansBadge(n); fun badgeText(n); fun backTo(tab) }
object NavLabels { fun host(tab): String; fun wordlists(previousRoute: String?, tab: Tab): String }
```
Behaviour (spec 1): `devicesTab` is the start; each destination is a nested `navigation` graph; a bar tap runs `TabSwitch.select`; the bar shows on the four roots only and not while the keyboard is up; pushed screens (host, wordlists) cover it; the bar's height is exposed through `LocalBarInset`. This task keeps every root's old signature (they still have their "Back to Home" rows); Tasks 8 and 9 remove the rows and the temporary lambdas below. Navigation-compose 2.9.0 is already a dependency, so destinations are real nested graphs. `enableOnBackInvokedCallback` stays unset and no `BackHandler` is added (spec 1.3 rule 8).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/RoutesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 1. */
class RoutesTest {
    @Test fun `tabOf maps every route of the table, graph routes and route patterns included`() {
        val expected = mapOf(
            "devicesTab" to Tab.DEVICES, "devices" to Tab.DEVICES, "devices/host/{id}" to Tab.DEVICES, "devices/host/5" to Tab.DEVICES, "devices/wordlists" to Tab.DEVICES,
            "scansTab" to Tab.SCANS, "scans" to Tab.SCANS, "scans/host/{id}" to Tab.SCANS, "scans/wordlists" to Tab.SCANS,
            "toolsTab" to Tab.TOOLS, "tools" to Tab.TOOLS, "tools/wordlists" to Tab.TOOLS,
            "settingsTab" to Tab.SETTINGS, "settings" to Tab.SETTINGS, "settings/wordlists" to Tab.SETTINGS)
        expected.forEach { (route, tab) -> assertEquals(route, tab, Routes.tabOf(route)) }
        assertNull(Routes.tabOf(null)); assertNull(Routes.tabOf("")); assertNull(Routes.tabOf("home")); assertNull(Routes.tabOf("hosts/5"))
    }
    @Test fun `host and wordlists routes per tab`() {
        assertEquals("devices/host/7", Routes.host(Tab.DEVICES, 7)); assertEquals("scans/host/7", Routes.host(Tab.SCANS, 7))
        assertEquals("devices/host/{id}", Routes.hostPattern(Tab.DEVICES)); assertEquals("scans/host/{id}", Routes.hostPattern(Tab.SCANS))
        assertEquals("devices/wordlists", Routes.wordlists(Tab.DEVICES)); assertEquals("scans/wordlists", Routes.wordlists(Tab.SCANS))
        assertEquals("tools/wordlists", Routes.wordlists(Tab.TOOLS)); assertEquals("settings/wordlists", Routes.wordlists(Tab.SETTINGS))
    }
    @Test fun `every route is declared in exactly one graph and the roots are the four first routes`() {
        val all = Tab.entries.flatMap { Routes.routesOf(it) }
        assertEquals(10, all.size); assertEquals("a route may appear once in a NavHost", all.size, all.toSet().size)
        Tab.entries.forEach { tab ->
            assertEquals(tab.root, Routes.routesOf(tab).first())
            Routes.routesOf(tab).forEach { assertEquals(it, tab, Routes.tabOf(it)) }
        }
        assertEquals(listOf("devices", "scans", "tools", "settings"), Tab.entries.map { it.root })
        assertEquals(listOf("devicesTab", "scansTab", "toolsTab", "settingsTab"), Tab.entries.map { it.graph })
    }
    @Test fun `root and host predicates`() {
        Tab.entries.forEach { assertTrue(Routes.isRoot(it.root)); assertFalse(Routes.isRoot(it.graph)) }
        assertFalse(Routes.isRoot("devices/host/{id}")); assertFalse(Routes.isRoot(null))
        assertTrue(Routes.isHost("scans/host/{id}")); assertFalse(Routes.isHost("tools/wordlists")); assertFalse(Routes.isHost(null))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/BarRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 2 (D5 and D6). */
class BarRulesTest {
    @Test fun `visible on the four roots with the keyboard down`() {
        Tab.entries.forEach { assertTrue(it.root, BarRules.visible(it.root, imeVisible = false)) }
    }
    @Test fun `hidden on every pushed route`() {
        Tab.entries.flatMap { Routes.routesOf(it) }.filterNot { Routes.isRoot(it) }.forEach { assertFalse(it, BarRules.visible(it, imeVisible = false)) }
    }
    @Test fun `hidden with the keyboard up on any route`() {
        Tab.entries.flatMap { Routes.routesOf(it) }.forEach { assertFalse(it, BarRules.visible(it, imeVisible = true)) }
    }
    @Test fun `hidden before the first destination is known`() {
        assertFalse(BarRules.visible(null, imeVisible = false))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/NavMotionTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 9.1 item 3 (D7). */
class NavMotionTest {
    @Test fun `a push or pop inside a destination slides`() {
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("devices", "devices/host/{id}", reduced = false))
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("devices/host/{id}", "devices", reduced = false))
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("settings", "settings/wordlists", reduced = false))
    }
    @Test fun `a switch between destinations cross-fades`() {
        assertEquals(NavMotionKind.FADE, NavMotion.kind("devices", "scans", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("scans", "tools", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("settings", "devices", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("scans/host/{id}", "devices", reduced = false))
    }
    @Test fun `reduced motion is none for every pair`() {
        val routes = listOf("devices", "scans", "tools", "settings", "devices/host/{id}", "tools/wordlists", null)
        for (a in routes) for (b in routes) assertEquals("$a -> $b", NavMotionKind.NONE, NavMotion.kind(a, b, reduced = true))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/NavCopyTableTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 6 (Nav rows) as regex templates, plus the badge cap and the back-label rule. */
class NavCopyTableTest {
    private val templates = listOf("Devices", "Scans", "Tools", "Settings", "Scans, \\d+ running", "[1-9]|9\\+", "Back to (Devices|Scans|Tools|Settings)", "Back to device").map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `labels and back rows`() {
        Tab.entries.forEach { inTable(NavCopy.label(it)); inTable(NavCopy.backTo(it)) }
        assertEquals(listOf("Devices", "Scans", "Tools", "Settings"), Tab.entries.map { NavCopy.label(it) })
        assertEquals("Back to Devices", NavCopy.backTo(Tab.DEVICES)); assertEquals("Back to Scans", NavCopy.backTo(Tab.SCANS))
        assertEquals("Back to Tools", NavCopy.backTo(Tab.TOOLS)); assertEquals("Back to Settings", NavCopy.backTo(Tab.SETTINGS))
        inTable(NavCopy.BACK_TO_DEVICE)
    }
    @Test fun `badge text counts to nine then caps`() {
        assertEquals("1", NavCopy.badgeText(1)); assertEquals("8", NavCopy.badgeText(8)); assertEquals("9", NavCopy.badgeText(9))
        assertEquals("9+", NavCopy.badgeText(10)); assertEquals("9+", NavCopy.badgeText(40))
        (1..12).forEach { inTable(NavCopy.badgeText(it)); inTable(NavCopy.scansBadge(it)) }
        assertEquals("Scans, 1 running", NavCopy.scansBadge(1)); assertEquals("Scans, 3 running", NavCopy.scansBadge(3))
    }
    @Test fun `a wordlists page opened from a device says Back to device and any other opener names its tab`() {
        assertEquals("Back to device", NavLabels.wordlists("devices/host/{id}", Tab.DEVICES))
        assertEquals("Back to device", NavLabels.wordlists("scans/host/{id}", Tab.SCANS))
        assertEquals("Back to Tools", NavLabels.wordlists("tools", Tab.TOOLS))
        assertEquals("Back to Settings", NavLabels.wordlists("settings", Tab.SETTINGS))
        assertEquals("Back to Scans", NavLabels.wordlists("scans", Tab.SCANS))
        assertEquals("Back to Scans", NavLabels.wordlists(null, Tab.SCANS))
        assertEquals("Back to Devices", NavLabels.host(Tab.DEVICES)); assertEquals("Back to Scans", NavLabels.host(Tab.SCANS))
    }
    @Test fun `no Nav string has an em dash or the word Home or Activity`() {
        val dash = 0x2014.toChar()
        val all = Tab.entries.flatMap { listOf(NavCopy.label(it), NavCopy.backTo(it)) } + NavCopy.BACK_TO_DEVICE + NavCopy.scansBadge(3)
        all.forEach { assertFalse(it, it.contains(dash)); assertFalse(it, it.contains("Home")); assertFalse(it, it.contains("Activity")) }
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavTransitionTest.kt` (rewritten for the new `NetProbeNavHost` signature; fake slots, so no test runs a real discovery):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 1.5 and 9.2 item 3: slide inside a destination, cross-fade between destinations, nothing under reduced motion. */
@RunWith(AndroidJUnit4::class)
class NavTransitionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private fun box(tag: String): @androidx.compose.runtime.Composable () -> Unit = { Box(Modifier.fillMaxSize().testTag(tag)) }
    private fun show(reduced: Boolean) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
        nav = rememberNavController()
        NetProbeNavHost(nav, NavSlots(devices = box("fakeDevices"), scans = box("fakeScans"), tools = box("fakeTools"), settings = box("fakeSettings"),
            host = { _, _, _ -> Box(Modifier.fillMaxSize().testTag("fakeHost")) }))
    } } }

    /**
     * Steps the paused clock a frame at a time until [tag] has left the tree, failing past [budgetMs] from the navigation call. NavHost
     * removes the leaving entry only after its transition reports complete, which takes a few frames after the last animation frame
     * (QA F3), so this waits for the removal with a bound instead of asserting at a fixed instant.
     */
    private fun advanceUntilGone(tag: String, startedMs: Long, budgetMs: Long): Long {
        var elapsed = startedMs
        while (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            assertTrue("$tag still composed $elapsed ms after navigating (budget $budgetMs ms)", elapsed <= budgetMs)
            rule.mainClock.advanceTimeByFrame(); elapsed += FRAME_MS
        }
        return elapsed
    }

    @Test fun devicesToHostSlidesAndFadesBothWays() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate(Routes.host(Tab.DEVICES, 1)) }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeHost").assertExists()   // mid-transition: both
        advanceUntilGone("fakeDevices", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeDevices").assertDoesNotExist(); rule.onNodeWithTag("fakeHost").assertExists()
        rule.runOnUiThread { nav.popBackStack() }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeHost").assertExists()
        advanceUntilGone("fakeHost", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeHost").assertDoesNotExist(); rule.onNodeWithTag("fakeDevices").assertExists()
    }
    @Test fun aBarSwitchCrossFadesBetweenDestinations() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { TabSwitch.select(nav, Tab.SETTINGS) }
        rule.mainClock.advanceTimeBy(MID_MS)
        rule.onNodeWithTag("fakeDevices").assertExists(); rule.onNodeWithTag("fakeSettings").assertExists()
        advanceUntilGone("fakeDevices", MID_MS, NAV_DONE_MS)
        rule.onNodeWithTag("fakeSettings").assertExists()
    }
    @Test fun reducedMotionShowsOnlyTheDestinationForAPushAndForABarSwitch() {
        show(reduced = true); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate(Routes.host(Tab.DEVICES, 1)) }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeHost").assertExists()
        advanceUntilGone("fakeDevices", 2 * FRAME_MS, REDUCED_DONE_MS)
        rule.runOnUiThread { nav.popBackStack() }
        advanceUntilGone("fakeHost", 0, REDUCED_DONE_MS)
        rule.runOnUiThread { TabSwitch.select(nav, Tab.TOOLS) }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeTools").assertExists()
        advanceUntilGone("fakeDevices", 2 * FRAME_MS, REDUCED_DONE_MS)
    }

    private companion object {
        const val FRAME_MS = 16L                                          // the test clock's frame
        const val MID_MS = 120L                                           // half the transition
        const val NAV_DONE_MS = Motion.NAV_MS + 8 * FRAME_MS              // the animation plus completion frames
        const val REDUCED_DONE_MS = Motion.NAV_MS / 2L
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavBarTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 1 and Review Focus 5: the shell with fake destinations (no container, no database). Reduced motion keeps every switch instant. */
@RunWith(AndroidJUnit4::class)
class NavBarTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private var back: () -> Unit = {}

    @Composable private fun Root(name: String, extra: @Composable () -> Unit = {}) {
        var n by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().testTag("fake:$name")) {
            Text("count:$name=$n", Modifier.testTag("count:$name"))
            Button(onClick = { n++ }, modifier = Modifier.testTag("inc:$name")) { Text("inc") }
            extra()
        }
    }
    private fun show(scansRunning: Int = 0) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
        nav = rememberNavController()
        val owner = LocalOnBackPressedDispatcherOwner.current
        SideEffect { back = { owner!!.onBackPressedDispatcher.onBackPressed() } }
        NetProbeShell(nav, scansRunning, NavSlots(
            devices = { Root("devices") { Button(onClick = { nav.navigate(Routes.host(Tab.DEVICES, 3)) }, modifier = Modifier.testTag("openHost:devices")) { Text("open") } } },
            scans = { Root("scans") { Button(onClick = { nav.navigate(Routes.host(Tab.SCANS, 5)) }, modifier = Modifier.testTag("openHost:scans")) { Text("open") } } },
            tools = { Root("tools") }, settings = { Root("settings") },
            host = { tab, id, label -> Box(Modifier.fillMaxSize().testTag("fakeHost:${tab.root}:$id:$label")) },
            wordlists = { tab, label -> Box(Modifier.fillMaxSize().testTag("fakeWordlists:${tab.root}:$label")) }))
    } } }
    private fun tap(tab: Tab) { rule.onNodeWithTag("tab:${tab.root}").performClick(); rule.waitForIdle() }
    private fun pressBack() { rule.runOnUiThread { back() }; rule.waitForIdle() }

    @Test fun fourItemsInBarOrderWithLabelsAndDevicesSelected() {
        show()
        val lefts = Tab.entries.map { rule.onNodeWithTag("tab:${it.root}").assertIsDisplayed().getBoundsInRoot().left }
        assertEquals(lefts.sorted(), lefts)
        listOf("Devices", "Scans", "Tools", "Settings").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithTag("tab:devices").assertIsSelected(); rule.onNodeWithTag("tab:scans").assertIsNotSelected()
    }
    @Test fun tappingEachDestinationSelectsItAndRestoresThatRootsState() {
        show()
        tap(Tab.SETTINGS); rule.onNodeWithTag("tab:settings").assertIsSelected()
        rule.onNodeWithTag("inc:settings").performClick(); rule.onNodeWithTag("inc:settings").performClick()
        tap(Tab.DEVICES); rule.onNodeWithTag("fake:devices").assertExists()
        tap(Tab.SETTINGS)
        rule.onNodeWithTag("count:settings").assertTextEquals("count:settings=2")
        tap(Tab.TOOLS); rule.onNodeWithTag("count:tools").assertTextEquals("count:tools=0")
    }
    @Test fun devicesThenScansOpenAHostBackToScansThenBackToDevices() {
        show()
        tap(Tab.SCANS)
        rule.onNodeWithTag("openHost:scans").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("fakeHost:scans:5:Back to Scans").assertExists()
        rule.onNodeWithTag("navBar").assertDoesNotExist()
        pressBack()
        rule.onNodeWithTag("fake:scans").assertExists(); rule.onNodeWithTag("navBar").assertExists()
        pressBack()
        rule.onNodeWithTag("fake:devices").assertExists(); rule.onNodeWithTag("tab:devices").assertIsSelected()
    }
    @Test fun backFromANonStartRootLandsOnDevices() {   // Review Focus 5
        show()
        tap(Tab.TOOLS); pressBack()
        rule.onNodeWithTag("fake:devices").assertExists()
        tap(Tab.SETTINGS); pressBack()
        rule.onNodeWithTag("fake:devices").assertExists()
    }
    @Test fun theBarIsAbsentOnHostAndWordlistsRoutesAndWordlistsNamesWhereBackGoes() {
        show()
        rule.onNodeWithTag("openHost:devices").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("fakeHost:devices:3:Back to Devices").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
        rule.runOnUiThread { nav.navigate(Routes.wordlists(Tab.DEVICES)) }; rule.waitForIdle()
        rule.onNodeWithTag("fakeWordlists:devices:Back to device").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
        pressBack(); pressBack()
        tap(Tab.TOOLS)
        rule.runOnUiThread { nav.navigate(Routes.wordlists(Tab.TOOLS)) }; rule.waitForIdle()
        rule.onNodeWithTag("fakeWordlists:tools:Back to Tools").assertExists(); rule.onNodeWithTag("navBar").assertDoesNotExist()
    }
    @Test fun rapidTabTapsLeaveOneDevicesEntryAndAtMostOneOtherDestination() {   // Review Focus 5
        show()
        listOf(Tab.SCANS, Tab.TOOLS, Tab.DEVICES, Tab.SCANS, Tab.SETTINGS, Tab.DEVICES, Tab.TOOLS, Tab.TOOLS, Tab.SETTINGS).forEach {
            rule.onNodeWithTag("tab:${it.root}").performClick()
        }
        rule.waitForIdle()
        val routes = nav.currentBackStack.value.mapNotNull { it.destination.route }
        val roots = routes.filter { Routes.isRoot(it) }
        assertEquals("exactly one devices root: $routes", 1, roots.count { it == "devices" })
        assertTrue("at most one other destination root: $routes", roots.size <= 2)
        assertEquals("settings", roots.last())
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavBadgeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3D spec 9.2 item 2: the Scans badge on a fake running count. */
@RunWith(AndroidJUnit4::class)
class NavBadgeTest {
    @get:Rule val rule = createComposeRule()
    private fun show(running: Int) = rule.setContent { NetProbeTheme { NetProbeBar(selected = Tab.DEVICES, scansRunning = running, onSelect = {}) } }

    @Test fun noBadgeAtZero() { show(0); rule.onNodeWithTag("scansBadge").assertDoesNotExist() }
    @Test fun twoRunningShowsTwoAndItsDescription() {
        show(2)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag("tab:scans").assert(hasContentDescription("Scans, 2 running"))
    }
    @Test fun eightRunningShowsEight() {
        show(8)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("8")
        rule.onNodeWithTag("tab:scans").assert(hasContentDescription("Scans, 8 running"))
    }
    @Test fun tenRunningCapsAtNinePlus() {
        show(10)
        rule.onNodeWithTag("scansBadge", useUnmergedTree = true).assertTextEquals("9+")
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt`: add before the closing brace of the class:
```kotlin
    @Test fun theBottomBarShowsFourDestinationsWithDevicesSelected() {   // 3D spec 1.2: the real shell around the real Devices root
        listOf("devices", "scans", "tools", "settings").forEach { rule.onNodeWithTag("tab:$it").assertIsDisplayed() }
        rule.onNodeWithTag("tab:devices").assertIsSelected()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.RoutesTest" --tests "com.northwalessolutions.netprobe.ui.BarRulesTest" --tests "com.northwalessolutions.netprobe.ui.NavMotionTest" --tests "com.northwalessolutions.netprobe.ui.NavCopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'Tab'`, `'Routes'`, `'BarRules'`, `'NavMotion'`, `'NavCopy'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/NavCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

/** Navigation strings (3D spec 6, with the Director's "Scans" label). Templates live in NavCopyTableTest. */
object NavCopy {
    const val DEVICES = "Devices"
    const val SCANS = "Scans"
    const val TOOLS = "Tools"
    const val SETTINGS = "Settings"
    /** Wordlists opened from a Device screen. */
    const val BACK_TO_DEVICE = "Back to device"
    private const val BADGE_CAP = 9

    fun label(tab: Tab): String = when (tab) {
        Tab.DEVICES -> DEVICES
        Tab.SCANS -> SCANS
        Tab.TOOLS -> TOOLS
        Tab.SETTINGS -> SETTINGS
    }
    fun scansBadge(n: Int) = "Scans, $n running"
    /** The count on the Scans icon: 1 to 9, then "9+" (the Running block holds at most eight rows, so the cap is a safety net). */
    fun badgeText(n: Int): String = if (n > BADGE_CAP) "$BADGE_CAP+" else "$n"
    /** The back row names where Back goes (D18), replacing "Back to Home". */
    fun backTo(tab: Tab) = "Back to ${label(tab)}"
}

/** Which back row a pushed screen shows. */
object NavLabels {
    fun host(tab: Tab): String = NavCopy.backTo(tab)
    /** Wordlists opened from a Device screen says "Back to device"; from a root it names that destination. */
    fun wordlists(previousRoute: String?, tab: Tab): String = if (Routes.isHost(previousRoute)) NavCopy.BACK_TO_DEVICE else NavCopy.backTo(tab)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/NavBar.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build as FilledBuild
import androidx.compose.material.icons.filled.MonitorHeart as FilledMonitorHeart
import androidx.compose.material.icons.filled.Radar as FilledRadar
import androidx.compose.material.icons.filled.Settings as FilledSettings
import androidx.compose.material.icons.outlined.Build as OutlinedBuild
import androidx.compose.material.icons.outlined.MonitorHeart as OutlinedMonitorHeart
import androidx.compose.material.icons.outlined.Radar as OutlinedRadar
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/**
 * The four plain-word icons shown to the Director on the device before the merge (standing rule: icons need his approval): radar dish,
 * pulse line, wrench, gear; outlined when unselected and filled when selected. If a glyph does not resolve, take the nearest one from the
 * same package; never add a dependency.
 */
object TabIcons {
    fun of(tab: Tab, selected: Boolean): ImageVector = when (tab) {
        Tab.DEVICES -> if (selected) Icons.Filled.FilledRadar else Icons.Outlined.OutlinedRadar
        Tab.SCANS -> if (selected) Icons.Filled.FilledMonitorHeart else Icons.Outlined.OutlinedMonitorHeart
        Tab.TOOLS -> if (selected) Icons.Filled.FilledBuild else Icons.Outlined.OutlinedBuild
        Tab.SETTINGS -> if (selected) Icons.Filled.FilledSettings else Icons.Outlined.OutlinedSettings
    }
}

/** The bar itself (3D spec 1.2): labels always shown, a 1 dp surface rule along the top edge, the running count on the Scans icon. */
@Composable
fun NetProbeBar(selected: Tab?, scansRunning: Int, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = NetProbeColors.background,
        modifier = Modifier.testTag("navBar").drawBehind { drawLine(NetProbeColors.surface, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val withBadge = tab == Tab.SCANS && scansRunning > 0
            NavigationBarItem(selected = isSelected, onClick = { onSelect(tab) }, alwaysShowLabel = true,
                icon = {
                    if (withBadge) BadgedBox(badge = {
                        Badge(containerColor = NetProbeColors.signal, contentColor = NetProbeColors.background) {
                            Text(NavCopy.badgeText(scansRunning), style = NetProbeText.caption, modifier = Modifier.testTag("scansBadge"))
                        }
                    }) { Icon(TabIcons.of(tab, isSelected), contentDescription = null) }
                    else Icon(TabIcons.of(tab, isSelected), contentDescription = null)
                },
                label = { Text(NavCopy.label(tab), style = NetProbeText.chip, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = NetProbeColors.text, selectedTextColor = NetProbeColors.text,
                    indicatorColor = NetProbeColors.surface, unselectedIconColor = NetProbeColors.textMuted, unselectedTextColor = NetProbeColors.textMuted),
                modifier = Modifier.testTag("tab:${tab.root}").then(
                    if (withBadge) Modifier.semantics { contentDescription = NavCopy.scansBadge(scansRunning) } else Modifier))
        }
    }
}
```
(`Icons.Filled.FilledRadar` is how an aliased extension-property import is used: the alias replaces the property name, which lets the filled and outlined glyphs of one name coexist.)

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` (whole file). Before writing it: `git log --oneline -- app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` and `git diff` the file against the 3C-2 plan's Task 7b Nav.kt; the only differences should be Task 2's `KeepScreenOn` edit. If a QA fix touched it, read that diff and carry the fix into the new file.
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.manual.ManualToolsScreen
import com.northwalessolutions.netprobe.ui.scans.ScansScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.wordlists.WordlistsScreen

/** The four top-level destinations in bar order (3D spec 1.1). [graph] is the nested graph's route, [root] its start destination. */
enum class Tab(val graph: String, val root: String) {
    DEVICES("devicesTab", "devices"), SCANS("scansTab", "scans"), TOOLS("toolsTab", "tools"), SETTINGS("settingsTab", "settings")
}

/** Every route string in one place. A route may appear once in a NavHost, so the host and wordlists routes are per destination. */
object Routes {
    const val HOST_ARG = "id"
    private const val GRAPH_SUFFIX = "Tab"
    private const val HOST_SEGMENT = "/host/"

    fun hostPattern(tab: Tab): String = "${tab.root}/host/{$HOST_ARG}"
    fun host(tab: Tab, id: Long): String = "${tab.root}/host/$id"
    fun wordlists(tab: Tab): String = "${tab.root}/wordlists"
    /** The routes a destination's graph declares: its root first. Only Devices and Scans open a Device screen. */
    fun routesOf(tab: Tab): List<String> = when (tab) {
        Tab.DEVICES, Tab.SCANS -> listOf(tab.root, hostPattern(tab), wordlists(tab))
        Tab.TOOLS, Tab.SETTINGS -> listOf(tab.root, wordlists(tab))
    }
    /** Works for a graph route ("scansTab"), a root, a pattern ("devices/host/{id}") and a concrete route ("devices/host/5"). */
    fun tabOf(route: String?): Tab? {
        if (route == null) return null
        val head = route.substringBefore('/').removeSuffix(GRAPH_SUFFIX)
        return Tab.entries.firstOrNull { it.root == head }
    }
    fun isRoot(route: String?): Boolean = Tab.entries.any { it.root == route }
    fun isHost(route: String?): Boolean = route != null && route.contains(HOST_SEGMENT)
}

/** The bar shows on the four roots only and hides while the keyboard is up (D5, D6). */
object BarRules {
    fun visible(route: String?, imeVisible: Boolean): Boolean = Routes.isRoot(route) && !imeVisible
}

enum class NavMotionKind { SLIDE, FADE, NONE }

/** Slide inside a destination (3A 1.9), cross-fade between destinations, nothing under reduced motion (D7). */
object NavMotion {
    fun kind(from: String?, to: String?, reduced: Boolean): NavMotionKind {
        if (reduced) return NavMotionKind.NONE
        return if (Routes.tabOf(from) != Routes.tabOf(to)) NavMotionKind.FADE else NavMotionKind.SLIDE
    }
}

/** A bar tap (spec 1.3 rules 1 and 2): each destination keeps its own back stack; tapping the current root does nothing. */
object TabSwitch {
    fun select(nav: NavHostController, tab: Tab) {
        val route = nav.currentDestination?.route
        if (Routes.isRoot(route) && Routes.tabOf(route) == tab) return
        nav.navigate(tab.graph) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/**
 * What each route shows. The Compose tests pass fakes, so no test runs a real discovery. [host] and [wordlists] get the label of their
 * back row (D18), already computed from where they were opened.
 */
class NavSlots(
    val devices: @Composable () -> Unit, val scans: @Composable () -> Unit, val tools: @Composable () -> Unit, val settings: @Composable () -> Unit,
    val host: @Composable (tab: Tab, id: Long, backLabel: String) -> Unit = { _, _, _ -> },
    val wordlists: @Composable (tab: Tab, backLabel: String) -> Unit = { _, _ -> },
)

private val BAR_HEIGHT = 80.dp

@Composable
fun NetProbeNav(container: AppContainer) {
    val extended by container.extendedRunActive.collectAsStateWithLifecycle()
    val toolRun by container.toolRunActive.collectAsStateWithLifecycle()
    val portScan by container.portScans.active.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun || portScan)
    val running by container.scansRunning.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    // Tasks 8 and 9 remove the temporary onOpenSettings and onBack arguments when the roots lose their old back rows.
    NetProbeShell(nav, running, NavSlots(
        devices = { HomeScreen(container, onOpenHost = { nav.navigate(Routes.host(Tab.DEVICES, it)) }, onOpenSettings = { TabSwitch.select(nav, Tab.SETTINGS) }) },
        scans = {
            ScansScreen(container, onOpenDevices = { TabSwitch.select(nav, Tab.DEVICES) }, onOpenHost = { nav.navigate(Routes.host(Tab.SCANS, it)) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SCANS)) { launchSingleTop = true } })
        },
        tools = {
            ManualToolsScreen(container, onBack = { TabSwitch.select(nav, Tab.DEVICES) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.TOOLS)) { launchSingleTop = true } })
        },
        settings = {
            SettingsScreen(container, onBack = { TabSwitch.select(nav, Tab.DEVICES) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SETTINGS)) { launchSingleTop = true } })
        },
        host = { tab, id, _ ->
            DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate(Routes.wordlists(tab)) { launchSingleTop = true } })
        },
        wordlists = { _, _ -> WordlistsScreen(container, onBack = { nav.popBackStack() }) }))
}

/**
 * The NavHost with the bar over it (3D spec 1.2). The bar is aligned to the bottom of a Box, not a child of any screen, so it is outside
 * every scroll container by construction. Its height is published through [LocalBarInset] while it shows, so a root screen's last row
 * clears it and the inset is never counted twice; pushed screens keep their own insets and lay out at full height from the first frame.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetProbeShell(nav: NavHostController, scansRunning: Int, slots: NavSlots) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val barVisible = BarRules.visible(route, WindowInsets.isImeVisible)
    val reduced = LocalReducedMotion.current
    val inset: WindowInsets = if (barVisible) WindowInsets(bottom = BAR_HEIGHT).add(WindowInsets.navigationBars) else WindowInsets.navigationBars
    CompositionLocalProvider(LocalBarInset provides inset) {
        Box(Modifier.fillMaxSize()) {
            NetProbeNavHost(nav, slots)
            AnimatedVisibility(visible = barVisible, modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (reduced) EnterTransition.None else slideInVertically(tween(Motion.NAV_MS)) { it } + fadeIn(tween(Motion.NAV_MS)),
                exit = if (reduced) ExitTransition.None else slideOutVertically(tween(Motion.NAV_MS)) { it } + fadeOut(tween(Motion.NAV_MS))) {
                NetProbeBar(selected = Routes.tabOf(route), scansRunning = scansRunning, onSelect = { TabSwitch.select(nav, it) })
            }
        }
    }
}

/** The routes with their transitions (spec 1.5). Stateless over its destinations. */
@Composable
fun NetProbeNavHost(nav: NavHostController, slots: NavSlots, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val slide = tween<IntOffset>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    fun kindOf(from: NavBackStackEntry, to: NavBackStackEntry) = NavMotion.kind(from.destination.route, to.destination.route, reduced)
    NavHost(nav, startDestination = Tab.DEVICES.graph, modifier = modifier,
        enterTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> EnterTransition.None
                NavMotionKind.FADE -> fadeIn(fade)
                NavMotionKind.SLIDE -> slideInHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeIn(fade)
            }
        },
        exitTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> ExitTransition.None
                NavMotionKind.FADE -> fadeOut(fade)
                NavMotionKind.SLIDE -> slideOutHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeOut(fade)
            }
        },
        popEnterTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> EnterTransition.None
                NavMotionKind.FADE -> fadeIn(fade)
                NavMotionKind.SLIDE -> slideInHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeIn(fade)
            }
        },
        popExitTransition = {
            when (kindOf(initialState, targetState)) {
                NavMotionKind.NONE -> ExitTransition.None
                NavMotionKind.FADE -> fadeOut(fade)
                NavMotionKind.SLIDE -> slideOutHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeOut(fade)
            }
        },
    ) {
        Tab.entries.forEach { tabGraph(it, slots, nav) }
    }
}

private fun NavGraphBuilder.tabGraph(tab: Tab, slots: NavSlots, nav: NavHostController) {
    navigation(startDestination = tab.root, route = tab.graph) {
        composable(tab.root) {
            when (tab) {
                Tab.DEVICES -> slots.devices()
                Tab.SCANS -> slots.scans()
                Tab.TOOLS -> slots.tools()
                Tab.SETTINGS -> slots.settings()
            }
        }
        if (tab == Tab.DEVICES || tab == Tab.SCANS) {
            composable(Routes.hostPattern(tab), arguments = listOf(navArgument(Routes.HOST_ARG) { type = NavType.LongType })) { entry ->
                slots.host(tab, entry.arguments!!.getLong(Routes.HOST_ARG), NavLabels.host(tab))
            }
        }
        composable(Routes.wordlists(tab)) { entry ->
            // Frozen at first composition: while this entry pops, the previous entry changes, and the label must not flicker.
            val previous = remember(entry.id) { nav.previousBackStackEntry?.destination?.route }
            slots.wordlists(tab, NavLabels.wordlists(previous, tab))
        }
    }
}
```

- [ ] **Step 4: Run the JVM tests and compile the instrumented ones**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
grep -rn "enableOnBackInvokedCallback\|BackHandler" app/src/main
```
Expected: `BUILD SUCCESSFUL` twice; the grep prints nothing (no predictive back opt-in, no `BackHandler`: Navigation handles Back, spec 1.3 rule 8). If `NavigationBarItem` does not accept a `modifier` ordering as written, keep the `testTag` on the item; if an icon name does not resolve, substitute the nearest glyph from the same package and record it in the commit body.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt app/src/main/java/com/northwalessolutions/netprobe/ui/NavCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/NavBar.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/RoutesTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/BarRulesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/NavMotionTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/NavCopyTableTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavTransitionTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavBarTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavBadgeTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/HomeScreenTest.kt
git status --short
git commit -m "Phase 3D: bottom navigation bar with nested graphs, saved state and bar visibility rules"
```

---

### Task 8: The Devices root: Home minus the moved overflow items, About and Settings

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/About.kt`
- Modify: `ui/home/HomeContent.kt`, `ui/home/HomeComponents.kt` (`HomeMenu`), `ui/home/HomeScreen.kt`, `ui/home/HomeCopy.kt` (`ABOUT` removed), `ui/settings/SettingsCopy.kt`, `ui/Nav.kt` (one argument)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/settings/AboutInfoTest.kt`, `ui/home/CopyTableTest.kt` (two edits); update `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (compile only, run in Task 12)

**Interfaces:**
- Consumes: `HomeCopy.version`, `build`, `android`, `APP_NAME`, `CLOSE`, `COPY_VERSION`, `rootInsets()` (Task 6), `Tab`, `TabSwitch` (Task 7).
- Produces:
```kotlin
data class AboutInfo(versionName: String, versionCode: Int, gitSha: String, buildTime: String, androidRelease: String = Build.VERSION.RELEASE) { val lines: List<String> }   // moved to ui/settings/About.kt, unchanged
@Composable fun AboutDialog(about: AboutInfo, onDismiss: () -> Unit)             // tags aboutDialog, aboutCopy, aboutClose
HomeMenu(quickEnabled, quickScanning, onQuick, onStopQuick, forgetEnabled = false, onForget = {})      // onSettings and onAbout removed
HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit)                  // onOpenSettings removed
HomeActions: onOpenSettings and onAbout removed;  HomeContent(state, actions, progress, now, ownModel)         // about removed
SettingsCopy.TITLE = "Settings", ABOUT_SECTION = "About", ABOUT_ROW = "About";  HomeCopy.ABOUT removed
```
Devices is today's Home with the same layout (network line, radar, notices, chips, search, list, the running marker, the notice line). The overflow is "Quick scan all" (or "Stop quick scan") and "Forget this network" (D16 confirmed, D17): no "Settings", no "About", no "Run a tool...". The root's Scaffold uses `rootInsets()` so the last row clears the bar.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/settings/AboutInfoTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The About dialog's three lines moved with the dialog (3D spec 2 and 5); the wording is the Phase 3A table. */
class AboutInfoTest {
    @Test fun `the dialog has a version line, a build line and an Android line`() {
        val info = AboutInfo("0.3.0", 3, "c8a62e6", "2026-10-21 14:05", "14")
        assertEquals(listOf("Version 0.3.0 (3)", "Build c8a62e6, 2026-10-21 14:05", "Android 14"), info.lines)
    }
    @Test fun `the Settings strings are the documented ones`() {
        assertEquals("Settings", SettingsCopy.TITLE); assertEquals("About", SettingsCopy.ABOUT_SECTION); assertEquals("About", SettingsCopy.ABOUT_ROW)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`: replace `"Settings menu", "Reset to defaults",` with `"Settings", "Settings menu", "Reset to defaults",` (the new section and title strings), and replace
```kotlin
        listOf(HomeCopy.ABOUT, HomeCopy.APP_NAME, HomeCopy.COPY_VERSION, HomeCopy.CLOSE, HomeCopy.version("0.3.0", 3),
```
with
```kotlin
        listOf(SettingsCopy.ABOUT_ROW, SettingsCopy.ABOUT_SECTION, SettingsCopy.TITLE, HomeCopy.APP_NAME, HomeCopy.COPY_VERSION, HomeCopy.CLOSE, HomeCopy.version("0.3.0", 3),
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt`: delete the test `aboutOpensTheDialogWithTheBuildLinesAndCloseDismissesIt` in full (it moves to `SettingsContentTest` in Task 9), and add in its place:
```kotlin
    @Test fun theOverflowListsOnlyQuickScanAllAndForgetThisNetwork() {   // 3D spec 9.2 item 5; D16 is confirmed, so no "Run a tool..." either
        show(HomeFakes.settled().copy(networkId = 1))
        rule.onNodeWithTag("homeMenu").performClick()
        rule.onNodeWithText("Quick scan all").assertIsDisplayed(); rule.onNodeWithText("Forget this network").assertIsDisplayed()
        listOf("Settings", "About", "Run a tool...").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithTag("menuAbout").assertDoesNotExist(); rule.onNodeWithTag("menuRunTool").assertDoesNotExist()
    }
    @Test fun theOverflowOffersStopQuickScanWhileItRuns() {
        show(HomeFakes.settled().copy(networkId = 1, quickScanning = true))
        rule.onNodeWithTag("homeMenu").performClick()
        rule.onNodeWithText("Stop quick scan").assertIsDisplayed(); rule.onNodeWithText("Quick scan all").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.settings.AboutInfoTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'AboutInfo'` (in `ui.settings`) and `'ABOUT_ROW'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/About.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

/** What the About dialog shows; SettingsScreen fills it from BuildConfig so tests can pass fakes. Moved from Home unchanged (3D spec 2). */
data class AboutInfo(val versionName: String, val versionCode: Int, val gitSha: String, val buildTime: String,
                     val androidRelease: String = Build.VERSION.RELEASE) {
    val lines: List<String> get() = listOf(HomeCopy.version(versionName, versionCode), HomeCopy.build(gitSha, buildTime), HomeCopy.android(androidRelease))
}

/** The About dialog (title, three lines, "Copy", "Close"), moved from HomeContent unchanged. */
@Composable
fun AboutDialog(about: AboutInfo, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = NetProbeColors.surface, modifier = Modifier.testTag("aboutDialog"),
        title = { Text(HomeCopy.APP_NAME, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = { Column { about.lines.forEach { Text(it, style = NetProbeText.secondary, color = NetProbeColors.textSecondary) } } },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("aboutClose")) { Text(HomeCopy.CLOSE, color = NetProbeColors.signal) } },
        dismissButton = {
            TextButton({ clipboard.setText(AnnotatedString(about.lines.joinToString("\n"))) }, Modifier.testTag("aboutCopy")) {
                Text(HomeCopy.COPY_VERSION, color = NetProbeColors.textSecondary)
            }
        },
    )
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsCopy.kt`: replace
```kotlin
    const val RESET_DONE = "Settings reset to defaults"
```
with
```kotlin
    const val RESET_DONE = "Settings reset to defaults"
    /** The Settings root's title row and its last section (3D spec 5); About moved here from Home's overflow. */
    const val TITLE = "Settings"
    const val ABOUT_SECTION = "About"
    const val ABOUT_ROW = "About"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: delete the line `    const val ABOUT = "About"`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt` (`HomeMenu`): replace
```kotlin
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}, onAbout: () -> Unit = {}) {
```
with
```kotlin
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}) {
```
and delete the two lines
```kotlin
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
            DropdownMenuItem(text = { Text(HomeCopy.ABOUT) }, modifier = Modifier.testTag("menuAbout"), onClick = { open = false; onAbout() })
```
Then in the KDoc or comment above `HomeMenu`, if any mentions Settings or About, make it say the overflow is Quick scan all and Forget this network.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`:
1. In `HomeActions`, replace `val onStopQuickScan: () -> Unit = {}, val onOpenSettings: () -> Unit = {}, val onRequestLocation: () -> Unit = {},` with `val onStopQuickScan: () -> Unit = {}, val onRequestLocation: () -> Unit = {},` and delete the line `    val onAbout: () -> Unit = {},`.
2. Delete the `AboutInfo` data class and its KDoc (the five lines from `/** What the About dialog shows; HomeScreen fills it ...` to the closing brace of `lines`).
3. Replace
```kotlin
                now: Long = System.currentTimeMillis(), ownModel: String = Build.MODEL,
                about: AboutInfo = AboutInfo("", 0, "", "")) {
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
```
with
```kotlin
                now: Long = System.currentTimeMillis(), ownModel: String = Build.MODEL) {
```
4. Delete the whole `if (aboutOpen) { AlertDialog( ... ) }` block (from `    if (aboutOpen) {` to its closing `    }`).
5. Replace `contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), topBar = {` with `contentWindowInsets = rootInsets(), topBar = {`, and replace the comment line above it `// The IME joins the content insets so an empty state or the last rows never sit behind the keyboard while searching.` with `// The bar inset and the IME join the content insets (3D spec 1.4): the last rows clear the bar and never sit behind the keyboard while searching.`
6. Replace
```kotlin
                    quickScanning = state.quickScanning, onQuick = actions.onQuickScanAll, onStopQuick = actions.onStopQuickScan, onSettings = actions.onOpenSettings,
```
with
```kotlin
                    quickScanning = state.quickScanning, onQuick = actions.onQuickScanAll, onStopQuick = actions.onStopQuickScan,
```
and replace
```kotlin
onForget = actions.onForget,
                    onAbout = { aboutOpen = true; actions.onAbout() })
```
with
```kotlin
onForget = actions.onForget)
```
7. Imports: add `import com.northwalessolutions.netprobe.ui.rootInsets`; delete `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `androidx.compose.runtime.saveable.rememberSaveable`, `androidx.compose.ui.platform.LocalClipboardManager`, `androidx.compose.ui.text.AnnotatedString` if nothing else in the file uses them (the compiler names any it still needs).

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt`: replace `fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit) {` with `fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit) {`; replace `            onOpenSettings = onOpenSettings, onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },` with `            onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },`; replace
```kotlin
        progress = latched, now = now,
        about = AboutInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, BuildConfig.BUILD_TIME),
    )
```
with
```kotlin
        progress = latched, now = now,
    )
```
and delete `import com.northwalessolutions.netprobe.BuildConfig`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt`: replace
```kotlin
        devices = { HomeScreen(container, onOpenHost = { nav.navigate(Routes.host(Tab.DEVICES, it)) }, onOpenSettings = { TabSwitch.select(nav, Tab.SETTINGS) }) },
```
with
```kotlin
        devices = { HomeScreen(container, onOpenHost = { nav.navigate(Routes.host(Tab.DEVICES, it)) }) },
```

- [ ] **Step 4: Run the tests and check nothing of the old overflow is left**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
grep -rn "onOpenSettings\|onAbout\|menuAbout\|MENU_RUN_TOOL\|menuRunTool\|onRunTool\|onOpenTools\|HomeCopy.ABOUT" app/src | cut -c1-170
```
Expected: `BUILD SUCCESSFUL`; the grep prints only the `onOpenSettings` of `WordlistsActions`/`SettingsActions` if any (those are different parameters) and, in the two test lines this task adds, `menuAbout` and `menuRunTool` inside `assertDoesNotExist`. Any other hit is a leftover to remove (D16: if 3C-2 built `HomeMenu.onRunTool`, `HomeActions.onRunTool`, `HomeScreen.onOpenTools` or the `menuRunTool` test, delete them here and say so in the commit body).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/settings/About.kt app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt app/src/test/java/com/northwalessolutions/netprobe/ui/settings/AboutInfoTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt
git status --short
git commit -m "Phase 3D: Devices root with a two item overflow; About moves to Settings code"
```

---

### Task 9: The Tools root and the Settings root: title rows, no back rows, Wordlists from Tools, About last

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopy.kt`
- Modify: `ui/manual/ManualToolsScreen.kt`, `ui/manual/ManualCopy.kt` (`TITLE` removed), `ui/settings/SettingsScreen.kt`, `ui/Nav.kt` (two arguments)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopyTest.kt`; update (compile only, run in Task 12) `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt` and `ui/manual/ManualToolsContentTest.kt`

**Interfaces:**
- Consumes: `ManualToolsScreen`, `ManualToolsContent`, `ManualActions` (3C-2, Task 7b), `SettingsContent`, `SettingsUi`, `SettingsActions`, `SettingsMenu`, `Section`, `AboutInfo`, `AboutDialog`, `SettingsCopy.TITLE`, `ABOUT_SECTION`, `ABOUT_ROW` (Task 8), `rootInsets()`.
- Produces:
```kotlin
object ToolsCopy { const val TITLE = "Tools"; const val WORDLISTS = "Wordlists" }
@Composable fun ManualToolsScreen(container: AppContainer, onOpenWordlists: () -> Unit = {})           // onBack removed
ManualActions: onBack removed
@Composable fun SettingsScreen(container: AppContainer, onOpenWordlists: () -> Unit = {})              // onBack removed
SettingsActions: onBack removed;  SettingsUi(..., importedWordlists, about: AboutInfo = AboutInfo("", 0, "", ""))
```
Tools (spec 4): the pinned block, its Run and Stop pill, validation, route, the brute-force sheet and the "Recent runs on this network" history list are unchanged; the title row reads "Tools" with a trailing 48 dp "Wordlists" action (tag `toolsWordlists`) that opens Wordlists. Settings (spec 5): the pinned top row is "Settings" with the 3C-1 overflow at its end; everything else and its order are unchanged; a new last section "About" holds one 56 dp row (tag `aboutRow`) with the version line and a chevron that opens the About dialog.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopyTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import org.junit.Assert.*
import org.junit.Test

/** 3D spec 6: the Tools root's two strings, as templates, with no em-dash. */
class ToolsCopyTest {
    private val templates = listOf("Tools", "Wordlists").map { Regex(it) }
    @Test fun `both strings are in the table`() {
        listOf(ToolsCopy.TITLE, ToolsCopy.WORDLISTS).forEach { s -> assertTrue(s, templates.any { it.matches(s) }); assertFalse(s, s.contains(0x2014.toChar())) }
        assertEquals("Tools", ToolsCopy.TITLE); assertEquals("Wordlists", ToolsCopy.WORDLISTS)
    }
}
```

Add to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt`, before the closing brace of the class (add `import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme` only if it is not already imported, which it is):
```kotlin
    @Test fun thereIsNoBackRowAndTheTitleRowCarriesTheOverflow() {   // 3D spec 9.2 item 6
        show()
        rule.onNodeWithText("Back to Home").assertDoesNotExist()
        rule.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        rule.onNodeWithTag("settingsMenu").assertIsDisplayed()
    }
    @Test fun aboutIsTheLastRowAndOpensTheDialogWithThreeLines() {
        val info = AboutInfo("0.3.0", 3, "c8a62e6", "2026-10-21 14:05", "14")
        s = SettingsState()
        rule.setContent { NetProbeTheme {
            SettingsContent(SettingsUi(s, "Home", 1L, emptyList(), CidrParser.subnetOf("10.0.3.0/24"), about = info), SettingsActions(update = { f -> s = f(s) }, current = { s }))
        } }
        rule.onNodeWithTag("aboutRow").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Version 0.3.0 (3)").assertIsDisplayed()          // the row's own version line
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().map { it.positionInRoot.y }
        assertEquals("the About section is the last heading", headings.max(), headingY("About"))
        rule.onNodeWithTag("aboutRow").performClick()
        rule.onNodeWithTag("aboutDialog").assertIsDisplayed()
        rule.onNodeWithText("Build c8a62e6, 2026-10-21 14:05").assertIsDisplayed(); rule.onNodeWithText("Android 14").assertIsDisplayed()
        rule.onNodeWithTag("aboutCopy").assertIsDisplayed()
        rule.onNodeWithTag("aboutClose").performClick(); rule.onNodeWithTag("aboutDialog").assertDoesNotExist()
    }
```
(`headingY` is the helper already in this class.)

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt`: add before the closing brace of the class
```kotlin
    @Test fun theTitleRowIsToolsWithAWordlistsActionAndThereIsNoBackRow() {   // 3D spec 4
        show(ManualUiState())
        rule.onNodeWithText("Tools").assertIsDisplayed()
        rule.onNodeWithText("Back to Home").assertDoesNotExist(); rule.onNodeWithText("Run a tool").assertDoesNotExist()
        rule.onNodeWithTag("toolsWordlists").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("manage wordlists", events.last())
    }
```
and add `onManageWordlists = { events += "manage wordlists" }` to the test class's `actions` definition (it is a named argument of `ManualActions(...)` at the top of the class; if the finished file builds `actions` differently, add the event to whatever records the wordlists action). Delete any test in this file that taps or asserts the back row (`grep -n "Back to Home\|onBack\|backToHome" app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt`).

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.ToolsCopyTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ToolsCopy'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

/** The Tools root's strings (3D spec 4 and 6). The 3C-2 title "Run a tool" (ManualCopy.TITLE) is gone: the screen is the Tools destination now. */
object ToolsCopy {
    const val TITLE = "Tools"
    const val WORDLISTS = "Wordlists"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualCopy.kt`: delete the `TITLE` constant (`const val TITLE = "Run a tool"`), then
```bash
grep -rn "ManualCopy.TITLE\|\"Run a tool\"" app/src | cut -c1-170
```
and remove every remaining reference (the 3C-2 copy table test lists it among its templates: delete that template and that use; nothing else).

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsScreen.kt` (targeted edits against the 3C-2 file; each fails loudly if the anchor is missing, in which case adapt to the branch's text and record it):
1. In `ManualActions`, replace `    val onBack: () -> Unit = {}, val onIp: (String) -> Unit = {},` with `    val onIp: (String) -> Unit = {},`.
2. Replace `fun ManualToolsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {` with `fun ManualToolsScreen(container: AppContainer, onOpenWordlists: () -> Unit = {}) {`, and replace `    ManualToolsContent(s, ManualActions(onBack = onBack, onIp = vm::setIp,` with `    ManualToolsContent(s, ManualActions(onIp = vm::setIp,`.
3. Replace `contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),` with `contentWindowInsets = rootInsets(),` and add `import com.northwalessolutions.netprobe.ui.rootInsets`.
4. Replace
```kotlin
                BackToHome(actions.onBack)
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                    Text(ManualCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.semantics { heading() })
```
with
```kotlin
                ToolsTitleRow(actions.onManageWordlists)
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
```
5. Delete `import com.northwalessolutions.netprobe.ui.BackToHome`, and add above `ManualToolsContent`'s KDoc:
```kotlin
private val TITLE_ROW_MIN = 56.dp

/** "Tools" with a trailing 48 dp "Wordlists" action (3D spec 4): the screen is a root now, so it has a title row and no back row. */
@Composable
private fun ToolsTitleRow(onWordlists: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
        Text(ToolsCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text,
            modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onWordlists).testTag("toolsWordlists"),
            contentAlignment = Alignment.Center) {
            Text(ToolsCopy.WORDLISTS, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = Dimens.gutter))
        }
    }
}
```
(Add any import the compiler names for `Row`, `Box`, `Alignment`, `clickable`, `Role`: most are already imported for `ToolPicker`.)

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt`:
1. Imports: delete `import com.northwalessolutions.netprobe.ui.BackToHome`; add
```kotlin
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.saveable.rememberSaveable
import com.northwalessolutions.netprobe.BuildConfig
import com.northwalessolutions.netprobe.ui.rootInsets
```
(`layout.*` is already star-imported, so drop the first line if the compiler reports it redundant.)
2. Replace the `SettingsUi` declaration's last line `val local: Subnet? = null, val importedWordlists: Int? = null)` with
```kotlin
val local: Subnet? = null, val importedWordlists: Int? = null,
                      /** The About row and its dialog (3D spec 5). */
                      val about: AboutInfo = AboutInfo("", 0, "", ""))
```
3. Replace `data class SettingsActions(val onBack: () -> Unit = {}, val update:` with `data class SettingsActions(val update:` (keep the rest of that declaration).
4. Replace `fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {` with `fun SettingsScreen(container: AppContainer, onOpenWordlists: () -> Unit = {}) {`; replace `SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null),` with
```kotlin
SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null,
            AboutInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA, BuildConfig.BUILD_TIME)),
```
and replace `SettingsActions(onBack = onBack, update = { container.settings.update(it) },` with `SettingsActions(update = { container.settings.update(it) },`.
5. In `SettingsContent`, replace `Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), snackbarHost = { SnackbarHost(snackbar) },` with `Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = rootInsets(), snackbarHost = { SnackbarHost(snackbar) },`; replace the comment above `SettingsScreen` `// The IME joins the content insets, as on Home and Detail; a separate imePadding would count the navigation bar twice.` with `// The bar inset and the IME join the content insets (3D spec 1.4); a separate imePadding would count the navigation bar twice.`
6. Replace
```kotlin
        topBar = { Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            BackToHome(actions.onBack) { SettingsMenu(resetEnabled = SettingsDefaults.differs(s), onReset = onReset) }
        } }) { pad ->
```
with
```kotlin
        topBar = {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = TITLE_ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
                Text(SettingsCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text,
                    modifier = Modifier.weight(1f).padding(start = Dimens.gutter).semantics { heading() })
                SettingsMenu(resetEnabled = SettingsDefaults.differs(s), onReset = onReset)
            }
        }) { pad ->
```
and add `private val TITLE_ROW_MIN = 56.dp` next to `SWITCH_ROW_MIN`. Update the KDoc line `/** Settings layout (Radar spec 6, 3C spec 1 and 2.3). PINNED: the back row and the overflow menu sit in the topBar, never in the scroll. */` to say `PINNED: the title row and the overflow menu sit in the topBar, never in the scroll.`
7. Replace
```kotlin
            ToolsSection(imported = ui.importedWordlists, onOpenWordlists = actions.onOpenWordlists)
            Spacer(Modifier.height(24.dp))
```
with
```kotlin
            ToolsSection(imported = ui.importedWordlists, onOpenWordlists = actions.onOpenWordlists)

            Section(SettingsCopy.ABOUT_SECTION)
            AboutRow(ui.about)
            Spacer(Modifier.height(24.dp))
```
8. Add above `SettingsMenu`:
```kotlin
/** The last Settings row (3D spec 5): "About" with the version line and a chevron; it opens the existing About dialog. */
@Composable
private fun AboutRow(about: AboutInfo) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = SWITCH_ROW_MIN).clickable(role = Role.Button) { open = true }.testTag("aboutRow"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(SettingsCopy.ABOUT_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(HomeCopy.version(about.versionName, about.versionCode), style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = NetProbeColors.textMuted)
    }
    if (open) AboutDialog(about) { open = false }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt`: replace
```kotlin
            ManualToolsScreen(container, onBack = { TabSwitch.select(nav, Tab.DEVICES) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.TOOLS)) { launchSingleTop = true } })
```
with
```kotlin
            ManualToolsScreen(container, onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.TOOLS)) { launchSingleTop = true } })
```
and replace
```kotlin
            SettingsScreen(container, onBack = { TabSwitch.select(nav, Tab.DEVICES) },
                onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SETTINGS)) { launchSingleTop = true } })
```
with
```kotlin
            SettingsScreen(container, onOpenWordlists = { nav.navigate(Routes.wordlists(Tab.SETTINGS)) { launchSingleTop = true } })
```
and delete the comment line `    // Tasks 8 and 9 remove the temporary onOpenSettings and onBack arguments when the roots lose their old back rows.`.

- [ ] **Step 4: Run the tests and check the removals**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
grep -rn "BackToHome" app/src/main | cut -c1-140
grep -rn "ManualCopy.TITLE\|Back to Home" app/src | cut -c1-140
```
Expected: `BUILD SUCCESSFUL`; the first grep shows `BackToHome` only in `Controls.kt` and `DetailContent.kt` (Task 10 renames it); the second shows only the two `assertDoesNotExist` lines this task adds and the literal inside `Controls.kt` that Task 10 replaces.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ToolsCopyTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt
git status --short
git commit -m "Phase 3D: Tools and Settings roots with title rows; About is the last Settings row"
```

---

### Task 10: Pushed screens: Device and Wordlists hide the bar and name where Back goes

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt` (`BackToHome` becomes `BackBar`), `ui/detail/DetailContent.kt`, `ui/detail/DetailScreen.kt`, `ui/detail/DetailComponents.kt` (one comment), `ui/wordlists/WordlistsScreen.kt`, `ui/Nav.kt` (two slots)
- Test (compile only, run in Task 12): additions to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` and `ui/wordlists/WordlistsScreenTest.kt`

**Interfaces:**
- Consumes: `NavCopy.backTo`, `NavCopy.BACK_TO_DEVICE`, `NavLabels` and the `NavSlots.host` / `NavSlots.wordlists` back-label arguments (Task 7), `Tab`, `BackRow`.
- Produces:
```kotlin
@Composable fun BackBar(label: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {})        // replaces BackToHome
DetailContent(..., liveToolBar = ..., backLabel: String = NavCopy.backTo(Tab.DEVICES))                  // last, defaulted
DetailScreen(container, hostId, onBack, onOpenWordlists = {}, backLabel: String = NavCopy.backTo(Tab.DEVICES))
WordlistsContent(..., urlState = UrlImportState.Idle, backLabel: String = WordlistCopy.BACK)           // last, defaulted
WordlistsScreen(container, onBack, backLabel: String = WordlistCopy.BACK)
```
Behaviour (spec 1.3 rule 6, D5, D18, D20): the Device screen opened from Devices says "Back to Devices", from Scans "Back to Scans"; Wordlists opened from a Device screen says "Back to device", from Tools "Back to Tools", from Settings "Back to Settings", from Scans "Back to Scans". Both screens are pushed over the bar (the shell hides it, Task 7) and keep their own insets, so they lay out at full height from the first frame. Remove device on a Device screen opened from Scans already pops to Scans (`onBack` is a pop of that destination's own stack) and Scans shows the pending Undo (Task 6).

- [ ] **Step 1: Write the failing Compose tests (compile only, run in Task 12)**

Add to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`, before the closing brace of the class:
```kotlin
    @Test fun theBackRowNamesWhereBackGoes() {   // 3D spec 9.2 and D18
        rule.setContent { NetProbeTheme { DetailContent(DetailFakes.state(), recording, backLabel = "Back to Scans") } }
        rule.onNodeWithText("Back to Scans").assertIsDisplayed()
        rule.onNodeWithText("Back to Home").assertDoesNotExist()
    }
    @Test fun theBackRowDefaultsToDevices() {
        show(DetailFakes.state())
        rule.onNodeWithText("Back to Devices").assertIsDisplayed()
    }
```

Add to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt`, before the closing brace of the class:
```kotlin
    @Test fun theBackRowNamesWhereBackGoes() {   // 3D D18: "Back to device" from a Device screen, the tab's name from a root
        rule.setContent { NetProbeTheme { WordlistsContent(builtIns, false, null, {}, WordlistsActions(), backLabel = "Back to device") } }
        rule.onNodeWithText("Back to device").assertIsDisplayed()
    }
    @Test fun theBackRowDefaultsToBack() {
        rule.setContent { NetProbeTheme { WordlistsContent(builtIns, false, null, {}, WordlistsActions()) } }
        rule.onNodeWithText("Back").assertIsDisplayed()
    }
```

- [ ] **Step 2: Compile to see them fail**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED` with `No parameter with name 'backLabel'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt`: replace
```kotlin
/** "Back to Home" with a leading chevron, a 48 dp touch row (spec 5.1 item 1, section 6); [trailing] holds Detail's overflow (Phase 3A 4.5). */
@Composable
fun BackToHome(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
```
with
```kotlin
/** A back row naming where Back goes (3D D18: "Back to Devices"), with a leading chevron and a 48 dp touch row; [trailing] holds Detail's overflow (Phase 3A 4.5). */
@Composable
fun BackBar(label: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
```
and replace `            Text("Back to Home", style = NetProbeText.data, color = NetProbeColors.textMuted)` with `            Text(label, style = NetProbeText.data, color = NetProbeColors.textMuted)`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`: replace `import com.northwalessolutions.netprobe.ui.BackToHome` with
```kotlin
import com.northwalessolutions.netprobe.ui.BackBar
import com.northwalessolutions.netprobe.ui.NavCopy
import com.northwalessolutions.netprobe.ui.Tab
```
replace
```kotlin
                  liveToolBar: (() -> ToolRunBar?)? = null) {
```
with
```kotlin
                  liveToolBar: (() -> ToolRunBar?)? = null,
                  /** Where Back goes (3D D18): the destination this page was opened from. */
                  backLabel: String = NavCopy.backTo(Tab.DEVICES)) {
```
and replace `            BackToHome(actions.onBack) { if (state.host != null) DetailMenu(` with `            BackBar(backLabel, actions.onBack) { if (state.host != null) DetailMenu(`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt`: replace `fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {` with
```kotlin
fun DetailScreen(container: AppContainer, hostId: Long, onBack: () -> Unit, onOpenWordlists: () -> Unit = {},
                 backLabel: String = NavCopy.backTo(Tab.DEVICES)) {
```
replace `        liveToolBar = { liveBar.value })` with `        liveToolBar = { liveBar.value }, backLabel = backLabel)`, and add `import com.northwalessolutions.netprobe.ui.NavCopy` and `import com.northwalessolutions.netprobe.ui.Tab`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`: replace the comment `/** Overflow on the "Back to Home" row (spec 4.5):` with `/** Overflow on the back row (spec 4.5):`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt`:
1. Replace `                     formatDate: (Long) -> String = RelativeTime::defaultDate, loading: Boolean = false, urlState: UrlImportState = UrlImportState.Idle) {` with
```kotlin
                     formatDate: (Long) -> String = RelativeTime::defaultDate, loading: Boolean = false, urlState: UrlImportState = UrlImportState.Idle,
                     /** Where Back goes (3D D18): "Back to device" from a Device screen, else the opening destination's name. */
                     backLabel: String = WordlistCopy.BACK) {
```
2. Replace `            BackRow(WordlistCopy.BACK, actions.onBack)` with `            BackRow(backLabel, actions.onBack)`.
3. Replace `fun WordlistsScreen(container: AppContainer, onBack: () -> Unit) {` with `fun WordlistsScreen(container: AppContainer, onBack: () -> Unit, backLabel: String = WordlistCopy.BACK) {` and replace `        loading = store == null, urlState = urlState)` with `        loading = store == null, urlState = urlState, backLabel = backLabel)`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt`: replace
```kotlin
        host = { tab, id, _ ->
            DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate(Routes.wordlists(tab)) { launchSingleTop = true } })
        },
        wordlists = { _, _ -> WordlistsScreen(container, onBack = { nav.popBackStack() }) }))
```
with
```kotlin
        host = { tab, id, backLabel ->
            DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate(Routes.wordlists(tab)) { launchSingleTop = true } },
                backLabel = backLabel)
        },
        wordlists = { _, backLabel -> WordlistsScreen(container, onBack = { nav.popBackStack() }, backLabel = backLabel) }))
```

- [ ] **Step 4: Compile, run the JVM suite and check no "Back to Home" is left**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
grep -rn "BackToHome\|Back to Home" app/src/main | cut -c1-140
```
Expected: `BUILD SUCCESSFUL` twice; the grep prints nothing in `src/main`.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt
git status --short
git commit -m "Phase 3D: Device and Wordlists are pushed screens that name where Back goes"
```

---

### Task 11: Copy table closure: removed names, the source scans, and the final sweep

**Files:**
- Create: `app/src/test/java/com/northwalessolutions/netprobe/ui/RemovedNamesTest.kt`, `ui/NoEmDashTest.kt`
- Modify: whatever the sweep names (expected: nothing)
- Test: the two new classes plus the whole JVM suite and the androidTest compile

**Interfaces:**
- Consumes: every Copy object and screen from Tasks 1 to 10.
- Produces: two permanent guards. `RemovedNamesTest` pins spec section 6's "Removed" list and the Director's rulings: `HomeCopy.ABOUT` and `HomeCopy.MENU_RUN_TOOL` do not exist, `ManualCopy.TITLE` does not exist, the literal "Back to Home" and `BackToHome` do not occur in any source, and the word "Activity" appears in no 3D name (the Director's label is Scans). `NoEmDashTest` scans every Kotlin source file for the em-dash character, enforcing the house rule on code, comments and strings alike.

- [ ] **Step 1: Write the tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/RemovedNamesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3D spec 6 "Removed" and the Director's rulings of 2026-10-21 (section 11). The forbidden words are built from parts so this file never matches itself. */
class RemovedNamesTest {
    private val sourceRoot: File = listOf(File("src"), File("app/src")).first { it.isDirectory }
    private val sources: List<Pair<String, String>> = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to it.readText() }.toList()
    private fun fieldNames(className: String): List<String> = Class.forName(className).declaredFields.map { it.name }

    @Test fun `the moved and removed copy constants are gone`() {
        val home = fieldNames("com.northwalessolutions.netprobe.ui.home.HomeCopy")
        assertFalse("HomeCopy.ABOUT moved to SettingsCopy.ABOUT_ROW", "ABOUT" in home)
        assertFalse("D16: no overflow item for manual tools", "MENU_RUN_TOOL" in home)
        assertFalse("ManualCopy.TITLE is ToolsCopy.TITLE now", "TITLE" in fieldNames("com.northwalessolutions.netprobe.ui.manual.ManualCopy"))
    }
    @Test fun `the old Home back row and its component are gone from every source`() {
        val oldLabel = "Back to " + "Home"
        val oldComponent = "BackTo" + "Home"
        val hits = sources.filter { (_, text) -> text.contains(oldLabel) || text.contains(oldComponent) }.map { it.first }
        // The only allowed mentions are the assertDoesNotExist lines in the Compose tests, which spell the label out to prove it is absent.
        assertTrue("old back row still referenced in: $hits", hits.all { it.contains("androidTest") })
    }
    @Test fun `the Director's Scans label is used everywhere and no Activity name remains`() {
        val forbidden = listOf("Activity" + "Copy", "activity" + "Tab", "activity" + "ClearedAt", "activity" + "OpenScans", "\"activity/")
        val hits = sources.filter { (_, text) -> forbidden.any { text.contains(it) } }.map { it.first }
        assertTrue("Activity naming left in: $hits", hits.isEmpty())
    }
    @Test fun `the destinations and the persisted keys are the Director's names`() {
        assertEquals(listOf("devices", "scans", "tools", "settings"), Tab.entries.map { it.root })
        assertEquals("scansClearedAt", com.northwalessolutions.netprobe.ui.scans.ScansHistoryStore.KEY)
        assertEquals("scansOpenScans", com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore.KEY)
        assertEquals("Scans", NavCopy.SCANS); assertEquals("Scans", com.northwalessolutions.netprobe.ui.scans.ScansCopy.TITLE)
    }
    @Test fun `the Home overflow code offers nothing but Quick scan all and Forget`() {
        val menu = sources.first { it.first.replace(File.separatorChar, '/').endsWith("ui/home/HomeComponents.kt") }.second
        assertEquals("HomeComponents.kt holds only the overflow's two menu items", 2, Regex(Regex.escape("DropdownMenuItem(")).findAll(menu).count())
        assertTrue(menu.contains("HomeCopy.FORGET_NETWORK")); assertFalse(menu.contains("onSettings")); assertFalse(menu.contains("onAbout"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/NoEmDashTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** House rule: no em-dashes anywhere (code, comments, UI strings). */
class NoEmDashTest {
    @Test fun `no Kotlin source file contains an em dash`() {
        val dash = 0x2014.toChar()
        val root = listOf(File("src"), File("app/src")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains(dash) }.map { it.path }.toList()
        assertTrue("em dash in: $hits", hits.isEmpty())
    }
}
```

- [ ] **Step 2: Run them**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.RemovedNamesTest" --tests "com.northwalessolutions.netprobe.ui.NoEmDashTest" --console=plain 2>&1 | tail -20
```
Expected: both classes pass, because Tasks 1 to 10 already removed what they pin. A failure names the leftover (a stale `HomeCopy.MENU_RUN_TOOL`, a `BackToHome` comment, a literal "Activity" in a KDoc, an em-dash in a comment): fix the leftover in the file the message names, never the test, and keep the fix to that line.

- [ ] **Step 3: Full sweep**

```bash
./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -12
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
grep -rn "scrollable\|verticalScroll" app/src/main/java/com/northwalessolutions/netprobe/ui/scans app/src/main/java/com/northwalessolutions/netprobe/ui/NavBar.kt app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt
git diff --stat HEAD -- app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt app/schemas
```
Expected: the whole JVM suite `BUILD SUCCESSFUL`; the androidTest compile `BUILD SUCCESSFUL`; the first grep prints nothing (no Stop, Clear or bar item inside a scroll container: the only scrolling part of Scans is its `LazyColumn`, which `grep` does not match by these words); the `git diff --stat` prints nothing (no schema file changed).

- [ ] **Step 4: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/northwalessolutions/netprobe/ui/RemovedNamesTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/NoEmDashTest.kt
git status --short
git commit -m "Phase 3D: guards for removed names, the Scans label and the no em-dash rule"
```

---

### Task 12: Deferred on-device QA (executed later, when the phone is available)

This task is NOT executed with Tasks 1 to 11. It runs when the Pixel 7 Pro `28221FDH300AP9` is available and the Director has agreed to it. Nothing here may be claimed in advance; the record is `docs/qa/phase3d-on-device.md`, written as the checks run, with every item PASS, FAIL or NOT RUN (with the reason). 3D changes no schema, so the data-safety ceremony is the light form: one backup, never a clean install.

**Set-up and safety (spec 9.3, 3C-2 Task 12 preamble):**
- Backups outside the repository in `K:/claude/netprobe_db_backup/phase3d/`; reuse the `ADB` path, the `exec-out` pull helper and the MD5-both-sides check from the 3C-2 plan's Task 12 preamble (`adb shell ... < file` truncates binary stdin on this PC, finding F5; `exec-out` pulls are fine). Back up the database (`netprobe.db`, `-wal`, `-shm`), the preferences (`netprobe.xml`) and `files/wordlists/` before the first install, record sizes and MD5.
- Install the 3D debug build over the 3C-2 build (`./gradlew :app:installDebug`), never `pm clear`, never an uninstall. Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
- No download or install of anything else. Logcat is read with `adb logcat -d` into a scratch file; it is never committed.

**Instrumented runs (record the pass count of each class):**
```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.northwalessolutions.netprobe.ui.NavBarTest --console=plain 2>&1 | tail -15
```
Run, one class per command and in this order: `NavBarTest`, `NavBadgeTest`, `NavTransitionTest`, `ui.scans.ScansContentTest`, `ui.detail.DetailContentTest`, `ui.settings.SettingsContentTest`, `ui.manual.ManualToolsContentTest`, `ui.wordlists.WordlistsScreenTest`, `ui.home.HomeContentTest`, then the rest of the suite, and `ui.HomeScreenTest` last (it launches the real activity and opens the Director's database; run it after the backup). A timing test that fails on a real frame clock is recorded with its message and the frame budget, then reproduced once; it is not "fixed" by lengthening a budget without the controller's say-so.

**Device checks (spec 9.3), each with a screenshot or a logcat excerpt where it helps:**
1. The Director's scenario: open the NAS, start Directory brute-force, press Back. Devices shows the Scans badge "1"; Scans shows the run with live progress; Stop from Scans ends it as "Stopped..." and the badge goes.
2. Start a Full port scan on a device, press Back: the scan keeps running and shows in Scans; tapping it opens that device with the live bar; Back returns to Scans. When it ends with the device page closed, "Port scan finished: ..." shows on the visible screen.
3. With two port scans running, a third device's Scan is disabled with "Two port scans are already running, stop one in Scans".
4. Load check: two Full port scans, Quick scan all and a Directory brute-force together. Record each completion time, any ANR or `Skipped N frames` warning in logcat, and compare one scanned host's open ports with a solo Full scan of that host (they must match). If this fails, the controller drops `MAX_PORT_SCANS` to 1 (the copy follows the constant) and the check is repeated.
5. Radar scan running, switch to Scans: "Radar scan" with the Home progress text; Stop there stops the radar on Devices; the arm resumes where it stopped on return (3A 1.9).
6. Quick scan all: "3 of 30 devices" advances; Stop works from either place.
7. Bar switches restore each root's state (Settings scroll position, Tools fields, Devices filter and search); Back from Tools goes to Devices, Back again leaves the app.
8. Background: with a Full port scan running, press Home for 30 s; the scan's done count does not advance (record it before and after); on return it resumes; the screen stays on while it runs.
9. Process death: start a Full port scan, `adb shell am kill` NetProbe from the background, relaunch: Scans shows the scan as "Interrupted when NetProbe closed"; tool runs from before show "Interrupted, N found"; other scan outcomes are gone; the Clear history cut-off is kept.
10. "Forget this network" is disabled in the Devices overflow while a port scan runs on this network.
11. Open a device from Scans, Remove device: the page pops to Scans, its snackbar offers Undo, and Undo restores the device.
12. URL import: start an import on Wordlists, switch to Scans: the import shows with "Fetching ..." and Stop cancels it ("Import cancelled"). No import is started against any address the Director has not named; use the address the 3C-1 QA used.
13. The bar hides on device pages, on Wordlists and while typing in Devices' search; no content jumps; reduced motion (Remove animations) gives instant switches; the four labels fit at font scale 1.3 (spec assumption A1) and the last list row on every root clears the bar.
14. Clear history, then Undo, restores the list; Reset to defaults (Settings overflow) leaves the cut-off and a recent list as they were.
15. Wi-Fi off during a port scan: the item ends with "Network changed, scan stopped".
16. The Director approves the four icons (radar dish, pulse line, wrench, gear) and the four labels on the device before the merge. Nothing is merged without his sign-off.

**Definition of done (spec 9):** the JVM suite is green (Tasks 1 to 11), the Compose classes above pass on the device, items 1 to 16 are recorded with results (NOT RUN items named with the reason), and the Director has seen Devices, Scans, Tools and Settings on the device.

**Record and wrap-up:**
- [ ] Write `docs/qa/phase3d-on-device.md` (date, tester, build SHA and APK md5, device, network, font scale, the backup table, the instrumented counts, the 16 items, the load numbers of item 4, and every deviation).
- [ ] Commit only the record: `git add docs/qa/phase3d-on-device.md`, `git status --short`, `git commit -m "Phase 3D: on-device QA record"`.
- [ ] Report to the Director any FAIL or BLOCKED item and the gaps in the Plan Self-Review Notes below; the backups in `K:/claude/netprobe_db_backup/phase3d/` stay outside the repository and are never committed.

---

## Plan Self-Review Notes

**1. Spec coverage.** Section 1 (navigation): 1.1 and 1.3 Task 7 (`Tab`, `Routes`, nested graphs, `TabSwitch`; rules 5 to 8 in the Task 7 tests and the manifest grep); 1.2 Task 7 (`NetProbeBar`, tags, badge); 1.4 Tasks 6 and 7 (`BarRules`, `LocalBarInset`, `rootInsets`, used by Tasks 8 and 9); 1.5 Task 7 (`NavMotion`, transitions, bar slide). Section 2 (Devices): Task 8 (overflow, `AboutInfo` moved, `HomeScreen` signature), Task 3 (`workRunningOnNetwork`), Task 4 (radar and Quick scan all register with `LiveWork`). Section 3 (Scans): 3.1 Task 5 (item model, with `title`/`detail` added); 3.2 Tasks 1 and 4 (`LiveWork`, registration points); 3.3 Tasks 1 and 2 (`PortScans`, cap, `ForegroundGate`, KeepScreenOn, process death, end message, `DetailViewModel`); 3.4 Task 5 (every rule, 54-row fetch); 3.5 Task 6 (layout, snackbar order, Undo); 3.6 Task 5 (`ScansTargets`) and Task 6 (results sheet hosting); 3.7 Tasks 5 and 7 (`badge`, `scansRunning`, `NavCopy.badgeText`); 3.8 Tasks 5 and 6 (cut-off store, Clear and Undo). Section 4 (Tools) and 5 (Settings): Task 9. Section 6 (copy): `NavCopy` Task 7, `ScansCopy` Tasks 1 and 5, `DetailCopy.portScansTooMany` Task 2, `ToolsCopy` and `SettingsCopy` Tasks 8 and 9, removals Task 11. Section 7 (data): keys in Tasks 1 and 5, no schema file touched (Task 11 check). Section 8 (3C interaction): Tasks 1 (Step 0), 8 (D16 leftovers), 9. Section 9: JVM items 1 to 13 in Tasks 1 to 7 and 11 (item 9 as the ownership test plus `PortScansTest`, see note 3); Compose items 1 to 7 in Tasks 2, 6 to 9; device items in Task 12. D1 to D22 each have an owner; D8 and D21 are non-work.

**2. Placeholder scan.** No "TBD", "TODO", "similar to Task N", "add appropriate error handling" or code-less step. The Task 9 `ManualToolsScreen` and `ManualToolsContentTest` edits are anchored to the 3C-2 plan's exact text and tell the implementer to adapt and record on a mismatch; that is a guarded edit, not a placeholder.

**3. Differences from the spec text, each deliberate.** (a) `Activity` reads `Scans` throughout, including the preference keys (`scansClearedAt`, `scansOpenScans`) and packages (`ui/scans`, `scans`), per the Director's ruling. (b) A port scan's Stop is `StopRoute.Live(id)`, not `PortScan(hostId)`, so a stale tap can never stop a newer scan of the same device (Review Focus 2); tool runs keep `Tool(hostId)` because `ToolRunner.stop` is host-keyed. (c) `ScansItem` carries `title` and `detail` and a `liveId`, computed in the pure layer so the screen is dumb and the wording is tested; the item key for a launch-time Interrupted entry is `live:<id>` (an id from the same sequence) rather than `lost:<startedAt>:<hostId>`. (d) `ScansRules.build` takes one `ScansInput`; `radarStatus` and `portStates` are inputs so the radar row shows the Home progress text and a port scan shows "identifying N ports". (e) `LiveWork` gains `drop`, `now`, `addInterrupted` and `restoreEnded` (Undo of Clear history merges the snapshot with anything that ended since); the radar entry carries no counts. (f) `PortScanEngine.scan` calls `gate()` before `sem.acquire()` exactly as the spec says; at most one extra probe can start after a pause, which is inside "in-flight probes finish". (g) `ScanController.start` gains a leading `onComplete` parameter, because a scan cancelled before its first dispatch runs no `finally` and `PortScans` would otherwise keep the host claimed. (h) Spec test 9.1 item 9 (`DetailViewModelScanLifetimeTest`) and `RemoveDeviceTest` cannot construct a `DetailViewModel` on the JVM (it needs an `AppContainer` and a `Context`); the lifetime is proved at `PortScans` (`a scan outlives the scope that asked for it`, `stopAndJoin returns only after the body's finally`) and the view model is locked to it by `DetailViewModelOwnershipTest`, a source-text guard. (i) `UrlImportLiveWorkTest` lives in `scans/`. (j) A port scan's reported duration is wall-clock (end minus start, spec 3.1), so a scan paused in the background for minutes reports those minutes; tool runs report `activeMs`. Not changed.

**4. Type consistency.** Names defined once and reused unchanged: `LiveWork` (`begin`, `progress`, `end`, `drop`, `stop`, `now`, `addInterrupted`, `clearEnded`, `restoreEnded`, `tracked`, `RECENT_LIMIT`, `PUBLISH_MS`), `PortScans` (`start`, `stop`, `stopAndJoin`, `state`, `active`, `MAX_PORT_SCANS`), `PortScanStart`, `PortScanState`, `IdentifyCount` (moved to `engine.portscan` in Task 1, deleted from `DetailViewModel` in Task 2), `ScanSink`, `OpenScan`, `OpenScanStore`, `ScansItem` (19 fields, the same positional order in Tasks 5, 6 and the tests), `ScansList`, `ScansInput`, `ScansRules` (`build`, `badge`, `runningCount`, `snack`, `RECENT_WINDOW_MS`, `RECENT_LIMIT`, `HISTORY_FETCH`), `ScansSnack`, `ScansTap`, `StopRoute`, `ScansTargets`, `ScansHistoryStore`, `Tab`, `Routes`, `BarRules`, `NavMotion`, `TabSwitch`, `NavSlots` (host and wordlists take a back label from Task 7 on), `LocalBarInset`, `rootInsets()`, `BackBar`.

**5. Review Focus.** The five lines at the top each name their owning test in Tasks 1, 5 and 7.

**6. Open decisions for the controller.** Review rigour (spec open item 7) is assumed standard. The icons and labels are shown to the Director on the device (Task 12 item 16). If the finished 3C-2 branch differs from the 3C-2 plan in any name this plan consumes, Task 1 Step 0 and the guarded edits in Tasks 8 and 9 say how to adapt.
