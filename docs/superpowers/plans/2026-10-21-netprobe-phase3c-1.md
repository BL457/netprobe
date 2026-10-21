# NetProbe Phase 3C-1 Implementation Plan: settings reset, Aggressive scan, manual ranges always swept, the Smart discovery walk, wordlist import from a URL

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer five of the Director's seven feedback lines without touching the database schema. A quiet "Reset to defaults" in a Settings overflow menu, with small "Default N" markers on moved sliders and an Undo. "Extended discovery" becomes "Aggressive scan", moves under Discovery, and on a mask wider than the Large networks limit sweeps the limit-sized block that holds the phone. Manual ranges are swept on every scan, bound to Wi-Fi, whatever Aggressive scan says. "Probe for gateways" becomes "Smart discovery" and gains a bounded outward walk around answering seeds outside the phone's /16. The Wordlists screen gains "Import from URL" with redirect, size, type and cancel rules. Built on `phase3b` (3A plus 3B), schema 3 unchanged.

**Architecture:** Same single module and MVVM shape. Every scan run reads its settings once into a `RunSettings` snapshot (Aggressive scan, the Large networks limit, the manual list), and the run's home range, binding (`ScanBinding.forRun`) and Home grouping read only that snapshot. The one place the swept local range is computed is the new pure `HomeRange.of`. Manual ranges reach the engine through `ExtendedOptionsOf.from`, which turns the extended phase on with `evidence = false` and no gateway probe when Aggressive scan is off. The outward walk lives inside `GatewayProbe.run` behind a `walkBudget` the engine computes from `CandidateRanges`. The URL import is a pure `UrlImportRules`, a streaming `UrlWordlistFetcher` on `HttpURLConnection` that feeds the existing `WordlistStore.import`, and a `UrlImportController` on `appScope`. Settings gains a stateless `SettingsContent` so the reset, markers and section order are Compose-testable without the Director's preferences.

**Tech Stack:** Unchanged from 3B: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00, navigation-compose 2.9.0, Room 2.7.1 with KSP (schema 3, untouched), kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, JUnit 4, Turbine, AndroidX test. No additions, no version bumps, no new Gradle plugins. `java.net.HttpURLConnection` and `java.util.zip` are platform classes, not dependencies.

**Spec:** `docs/superpowers/specs/2026-10-21-phase3c-feedback-design.md` at 33a6970 is binding: sections 1, 2, 5, 6 (plan 3C-1), 7.2 (the 3C-1 rows), 8.1 items 1 to 13, 8.2 items 4, 7 and 8, and 8.3. Its Gruff review (`docs/superpowers/plans/2026-10-21-phase3c-gruff-review.md`) explains the reasoning; the spec applied it, and where they differ the spec wins. The controller rulings in `.superpowers/sdd/2026-10-21-netprobe-phase3c/rulings.md` bind: a client /16 sweeps at most its /20 (the widest Large networks limit) with Aggressive scan on; labels "Aggressive scan" and "Smart discovery"; rigour standard. The 3B plan (`2026-10-20-netprobe-phase3b.md`) owns every name this plan consumes; the Phase 2 spec (`2026-10-19-extended-discovery-design.md`) owns every bind rule this plan keeps. Gaps and contradictions found while planning are in the Plan Self-Review Notes and go to the Director in the Task 11 record.

**Roles:** The stronger model implements and reviews Task 7 (the Smart discovery outward walk: rounds, stop conditions, the budget and the cap) and Task 9 (the URL fetch: redirects, the streaming caps, cancel and the store commit). Every other task is standard. Ada implements, Bugsy reviews each task, Gruff reviews this plan, Dot does the final review (rigour standard, controller ruling). The plan has 11 tasks: 10 built and JVM-tested now, and Task 11, the deferred on-device QA.

## Global Constraints

Carried from the 3B plan (unchanged, they still bind):

- Kotlin 2.1.21, AGP 8.10.1, Compose BOM 2025.06.00, Room 2.7.1 KSP with exported schema, targetSdk 30.
- No new dependencies, no version bumps, no new repositories, no Gradle plugins.
- No downloads or installs of anything by any agent or subagent. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director. No test fetches anything from the internet: every HTTP test talks to a `ServerSocket` on 127.0.0.1 that the test itself opens.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- No attribution lines in commits: plain messages, no `Co-Authored-By` or any other trailer.
- No disclaimers, warnings or authorisation prompts in UI copy. 3C-1 adds no confirmation dialog: Reset is undone from the snackbar (spec D4).
- Scan / Stop and every control that starts or stops work (the Settings overflow, both Wordlists import pills, the URL Cancel) live in pinned blocks, never inside a scroll container.
- Every user-visible string lives in a Copy object (`SettingsCopy`, `ExtendedCopy`, `HomeCopy`, `WordlistCopy`) and has a template in a copy table test (`CopyTableTest` for Settings and Home, `ToolCopyTableTest` for `WordlistCopy`); regex templates use doubled backslashes (`"\\d+"`). Existing Settings literals that 3C-1 does not change ("Discovery", "Speed", the slider labels) stay where they are (standing rule: do not restructure what was not asked for).
- Block bodies (`{ ... }`) for every function in which `return` appears; no `return` inside an expression body.
- Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so NetProbe is never uninstalled and the Director's data survives.
- THE PHONE IS NOT AVAILABLE during Tasks 1 to 10. Every task is built and tested on the JVM. Compose UI tests and the device fetch test are written in full and must compile (`./gradlew :app:compileDebugAndroidTestKotlin`) but are not run. No task may claim device evidence. Task 11 lists every instrumented run, screenshot and check; it runs when the phone is available.
- No instrumented test may open, create or delete a database named `DB_NAME` ("netprobe.db") or touch the "netprobe" `SharedPreferences`: the Settings Compose tests drive `SettingsContent` with in-memory state, and the device fetch test writes only under `cacheDir/url-fetch-test-*`.
- Every commit stages the files its task names and checks `git status --short` before committing; never `git add -A` or `git add .`.
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`, never a global `gradle`. Never kill python or java processes wholesale.
- Standard commands used by every task:
  - JVM, one class or package: `./gradlew :app:testDebugUnitTest --tests "<pattern>" --console=plain 2>&1 | tail -20`
  - androidTest compile only: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8`
  - Task gate, every task ends with it green: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6`, expected `BUILD SUCCESSFUL`.

New in 3C-1:

- **No schema change.** `NetProbeDb` stays at version 3, no `Migration` is added and no entity changes. A task that finds itself editing `Entities.kt`, `Migrations.kt`, `NetProbeDb.kt` or `app/schemas/` stops and asks the controller. No new `SharedPreferences` key either: reset rewrites the 14 existing covered keys.
- **Phase 2's bind rules are unchanged for scans** (Phase 2 spec 5.5): the gateway probe, the canaries and every extra-range sweep run inside `BoundNetwork`; a bind failure skips the extended phase with `SKIPPED_BIND_FAILED`; a port scan or tool run of a row outside the home range is bound to Wi-Fi when its run binds outside, and a missing binder is a `BindFailedException`, never an unbound run. The only change is when "binds outside" holds: `RunSettings.bindOutside = aggressive || manual.isNotEmpty()` instead of `extEnabled`. With Aggressive scan off and no manual ranges every path is the MVP's: unbound, the /24 on a wide mask, no extended phase, the MVP neighbour loop.
- **One settings read per run (spec D21).** Radar scans, Quick scan all, Device-screen scans and tool runs each build a `RunSettings` once at their start; nothing in a running scan reads `settings.state.value` again for the home range, binding or grouping.
- **The URL fetch is the only internet code.** `HttpURLConnection` appears only in `wordlists/UrlWordlistFetcher.kt`; it is never bound to Wi-Fi (the default network, which may be cellular), never follows redirects itself (`instanceFollowRedirects = false`), sends no `Authorization` or `Cookie`, and nothing in the app calls `CookieHandler.setDefault` or `Authenticator.setDefault` (checked by grep in Task 9).
- **Naming.** A local or parameter holding a `RunSettings` is called `runSettings`, never `run`, so it never shadows the standard library's `run` (review checklist item 1).
- 3C-1 starts only after the 3B device QA has passed (spec section 6). Task 1 Step 0 checks for the 3B QA record and records the base commit in `docs/superpowers/plans/phase3c-base.txt`, from which Task 11 builds the rollback APK.

## Review Focus

The five failure modes most likely to bite the Director. Each is pinned to a test in its owning task; the reviewer checks the test exists, fails on the bug, and was not bent to fit the code.

1. **The Phase 2 toggle-off path regresses.** With Aggressive scan off and no manual ranges, a scan must be the MVP exactly: no extended phase, nothing bound, the phone's /24 on a wide mask, the MVP neighbour loop, no new notice. Pinned: Task 4 `HomeRangeTest.aggressive off equals sweepRange for every prefix and limit` and `ScanBindingTest.bindOutside false is unbound for any target`; Task 5 `ExtendedOptionsOfTest.aggressive off with no manual ranges is ExtendedOptions()` and `DiscoveryEngineExtendedTest.aggressive off with no manual ranges is the toggle off result`, alongside Phase 2's `toggle off changes nothing`, which must stay green untouched.
2. **A manual range typed with Aggressive scan off leaks.** It must be swept bound to Wi-Fi, with no evidence ranges and no gateway probe, and a port scan of a host in it must be bound too. Pinned: Task 4 `ScanBindingTest.aggressive off with one manual range binds a target outside the home range`; Task 5 `DiscoveryEngineExtendedTest.aggressive off with one manual range sweeps only that range, bound, with no evidence and no probe`.
3. **The outward walk runs away.** It must stop at the budget `B`, after two consecutive misses, at distance 16 and at the octet boundary, never walk when more than four seeds answer, never exceed 256 attempts, and end within a second of Stop. Pinned: Task 7 `GatewayWalkTest` (all of it, notably `the spec example walks exactly the spec's units in four rounds`, `with B of one the walk stops after the first round with a hit and the extra hit is truncated`, `five answering seeds give no walk and the flag`, `walk attempts never exceed 256`, `cancellation mid walk returns within a second`).
4. **A mid-run toggle or reset changes the running scan.** Grouping, home range and binding must come from the snapshot taken at the run's start. Pinned: Task 6 `RunSnapshotTest.a run started with Aggressive off keeps its binding, home range and grouping after a mid run change`; device check Task 11 item 5.
5. **The URL import stores junk, follows a hostile redirect or ignores Cancel.** HTTPS never downgrades to HTTP, a public host never bounces the phone into a LAN address, HTML and binary bodies are refused, the 10 MB cap holds while streaming and before reading on a large `Content-Length`, Cancel stores nothing, and no credential is sent. Pinned: Task 8 `UrlImportRulesTest` (redirect, content type, NUL sniff, validate); Task 9 `UrlWordlistFetcherTest` (all of it) and the device-run `UrlWordlistFetcherDeviceTest`, run in Task 11.

### Review checklist (every task)

- Variable shadowing: no local named `run`, or named after an import; `runSettings` for every `RunSettings`.
- No progress callback in a tight loop without a step: the walk reports under one lock per unit, the fetch at most every `PROGRESS_STEP_BYTES`.
- No silent swallowing: every `catch` either maps to a named result (`FetchFailure`, `ImportResult.Unreadable`) or is a cleanup `runCatching` with a comment saying so; every Cancel path re-throws `CancellationException`.
- Every `HttpURLConnection` is disconnected in a `finally`; every test server socket is closed in `@After`.
- Never compare or hash `java.net.URL` (`URL.equals` and `hashCode` resolve host names): tests compare `url.toString()`, and no `URL` goes into a set or map key.
- No magic numbers: every limit is a named constant (`WALK_MAX_SEEDS`, `OUTWARD_MAX_UNITS`, `OUTWARD_MISS_LIMIT`, `MAX_REDIRECTS`, `MAX_URL_CHARS`, `BINARY_SNIFF_BYTES`, `PROGRESS_STEP_BYTES`, `URL_CONNECT_TIMEOUT_MS`, `URL_READ_TIMEOUT_MS`).
- Every test fails on the bug its name states; a test that would pass with the rule removed is rewritten, not kept.
- Every new string is a Copy member with a template; no new literal in a composable.

## File Structure

```
app/src/main/java/com/northwalessolutions/netprobe/
  AppContainer.kt          tool bind reads RunSettings (4); urlImport (10)
  data/Settings.kt         SettingsDefaults, SettingsReset, AppSettings(SharedPreferences) (1)
  netenv/Subnet.kt         HomeRange (4)
  engine/discovery/
    DiscoveryOptions.kt    ExtendedOptions.evidence, DiscoveryOptions.aggressive (5); ExtendedNote.GATEWAY_WALK_SKIPPED_MANY (7)
    DiscoveryEngine.kt     HomeRange for the local range, the evidence flag (5); walk budget, walk note, gatewayProbe type (7)
    CandidateRanges.kt     walkBudget (7)
    GatewayProbe.kt        GatewayProbeResult.walkSkippedManySeeds, OutwardWalk, run returns GatewayProbeResult (7)
  ui/home/
    RunSettings.kt         RunSettings (4), ExtendedOptionsOf (5)
    ScanBinding.kt         run(ip, env, home, bindOutside, ...), forRun (4)
    HomeViewModel.kt       RowRules.row aggressive, Quick scan all snapshot (4); radar snapshot, grouping, hero range, RunEnd (6)
    ExtendedNotices.kt     ExtendedNotices.of(aggressive), RunEnd.of(aggressive), RunMemory.settings (6); walk note (7)
    HomeCopy.kt            renamed and new strings (3)
    HomeContent.kt         the hero reads HomeUiState.homeRange (6)
  ui/detail/DetailViewModel.kt   the Device-screen scan reads RunSettings (4)
  ui/settings/
    SettingsCopy.kt        SettingsCopy, DefaultMarker (1)
    SettingsScreen.kt      SettingsContent, SettingsMenu, DefaultMarkerButton, StepSlider default, section order (2); HomeRange local (4)
    ExtendedSection.kt     ExtendedCopy renames, Stepper default marker, stepper and editor always enabled, MANUAL_LINE (3)
  wordlists/
    UrlImport.kt           UrlCheck, RedirectDecision, FetchFailure, UrlImportResult, UrlImportRules (8)
    UrlWordlistFetcher.kt  the HttpURLConnection fetch and CountingStream (9)
  ui/wordlists/
    WordlistCopy.kt        "Import file" and every URL string (8)
    WordlistsScreen.kt     WordlistImport.message(UrlImportResult) (8); two pills, URL dialog, progress, Cancel (10)
    UrlImportController.kt UrlImportState, UrlImportController (10)
app/src/test/java/com/northwalessolutions/netprobe/
  data/FakePrefs.kt, data/SettingsDefaultsTest.kt (1); ui/settings/DefaultMarkerTest.kt (1); ui/home/CopyTableTest.kt (1, 3)
  ui/settings/ExtendedCopyTest.kt, ui/home/ExtendedNoticesTest.kt (3)
  netenv/HomeRangeTest.kt, ui/home/ScanBindingTest.kt (rewritten), ui/home/RunSettingsTest.kt (4)
  ui/home/ExtendedOptionsOfTest.kt (5); engine/discovery/DiscoveryEngineExtendedTest.kt additions (5, 7)
  ui/home/RunSnapshotTest.kt, ui/home/RunEndTest.kt additions (6)
  engine/discovery/GatewayWalkTest.kt, engine/discovery/GatewayProbeTest.kt (one line) (7)
  wordlists/UrlImportRulesTest.kt, ui/tools/ToolCopyTableTest.kt (8)
  wordlists/LoopbackHttpServer.kt, wordlists/UrlWordlistFetcherTest.kt (9)
  ui/wordlists/UrlImportControllerTest.kt (10)
app/src/androidTest/java/com/northwalessolutions/netprobe/
  ui/settings/SettingsContentTest.kt (2), ui/settings/ExtendedSectionTest.kt (3)
  wordlists/LoopbackHttpServer.kt (a copy of the JVM one), wordlists/UrlWordlistFetcherDeviceTest.kt (9)
  ui/wordlists/WordlistsScreenTest.kt additions (10)
docs/superpowers/plans/phase3c-base.txt   the 3B commit 3C-1 builds on (Task 1 Step 0)
docs/qa/phase3c-1-on-device.md            Task 11 record (written when the phone is available)
```

---

### Task 1: Settings defaults: SettingsDefaults, SettingsReset, AppSettings over SharedPreferences, SettingsCopy and the marker text

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsCopy.kt`, `docs/superpowers/plans/phase3c-base.txt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/FakePrefs.kt`, `data/SettingsDefaultsTest.kt`, `ui/settings/DefaultMarkerTest.kt`; modify `ui/home/CopyTableTest.kt`

**Interfaces:**
- Consumes: `SettingsState`, `AppSettings`, `ManualRangeStore` (MVP and Phase 2, `data/Settings.kt`), `CopyTableTest.inTable` (3A).
- Produces:
```kotlin
object SettingsDefaults { val DEFAULTS: SettingsState; fun reset(current: SettingsState): SettingsState; fun differs(current: SettingsState): Boolean
                          fun undo(before: SettingsState, current: SettingsState): SettingsState }
object SettingsReset { suspend fun run(current: () -> SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, showUndo: suspend () -> Boolean) }
class AppSettings(prefs: SharedPreferences) { constructor(context: Context); companion object { const val PREFS_NAME = "netprobe" } }
object SettingsCopy { const val MENU; const val RESET; const val RESET_DONE; fun defaultMarker(value: Int, unit: String): String; fun resetDescription(label: String, value: Int, unit: String): String }
object DefaultMarker { fun text(value: Int, default: Int, unit: String): String? }
```

- [ ] **Step 0: Confirm the start condition and record the base (spec section 6)**

```bash
git status --short                                   # must be empty
git rev-parse --abbrev-ref HEAD                      # phase3b
test -f docs/qa/phase3b-on-device.md && echo "3B QA record present" || echo "STOP: no 3B device QA record"
```
If the 3B QA record is missing, or it records a failed item without the Director's ruling, stop and ask the controller: the spec starts 3C only after the 3B device QA passes, and only the controller may rule to start early. Otherwise branch and record the base:
```bash
git checkout -b phase3c-1
git rev-parse --short HEAD > docs/superpowers/plans/phase3c-base.txt
cat docs/superpowers/plans/phase3c-base.txt
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/FakePrefs.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.SharedPreferences

/** An in-memory SharedPreferences for JVM tests; [edits] counts edit() calls so a test can prove a reset is one edit. */
class FakePrefs : SharedPreferences {
    val stored = HashMap<String, Any?>()
    var edits = 0
        private set

    override fun getAll(): MutableMap<String, *> = HashMap(stored)
    override fun getString(key: String, defValue: String?): String? = stored[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (stored[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = stored[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = stored[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = stored[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = stored[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = stored.containsKey(key)
    override fun edit(): SharedPreferences.Editor {
        edits++
        return Editor()
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values?.toSet(); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean {
            if (clearAll) stored.clear()
            removed.forEach { stored.remove(it) }
            pending.forEach { (k, v) -> if (v == null) stored.remove(k) else stored[k] = v }
            return true
        }
        override fun apply() { commit() }
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/SettingsDefaultsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 1. */
class SettingsDefaultsTest {
    private val d = SettingsDefaults.DEFAULTS
    private val tuned = SettingsState(mdns = false, ssdp = false, netbios = false, reverseDns = false, snmp = false, httpTitle = false,
        sweepConcurrency = 256, connectTimeoutMs = 900, maxPrefix = 20, scanConcurrency = 64, scanTimeoutMs = 1200, nvdApiKey = "key-1",
        extEnabled = true, extMax = 9, extGatewayProbe = true)

    /** Each stored key and what the state looks like when only that key is missing from [tuned]'s prefs. */
    private val oneDefault: Map<String, (SettingsState) -> SettingsState> = mapOf(
        "mdns" to { s -> s.copy(mdns = d.mdns) }, "ssdp" to { s -> s.copy(ssdp = d.ssdp) }, "netbios" to { s -> s.copy(netbios = d.netbios) },
        "rdns" to { s -> s.copy(reverseDns = d.reverseDns) }, "snmp" to { s -> s.copy(snmp = d.snmp) }, "http" to { s -> s.copy(httpTitle = d.httpTitle) },
        "sweepConc" to { s -> s.copy(sweepConcurrency = d.sweepConcurrency) }, "connTimeout" to { s -> s.copy(connectTimeoutMs = d.connectTimeoutMs) },
        "maxPrefix" to { s -> s.copy(maxPrefix = d.maxPrefix) }, "scanConc" to { s -> s.copy(scanConcurrency = d.scanConcurrency) },
        "scanTimeout" to { s -> s.copy(scanTimeoutMs = d.scanTimeoutMs) }, "nvdKey" to { s -> s.copy(nvdApiKey = d.nvdApiKey) },
        "extEnabled" to { s -> s.copy(extEnabled = d.extEnabled) }, "extMax" to { s -> s.copy(extMax = d.extMax) },
        "extGatewayProbe" to { s -> s.copy(extGatewayProbe = d.extGatewayProbe) })

    @Test fun `reset returns SettingsState() with the current NVD key`() {
        assertEquals(SettingsState(), d)
        assertEquals(SettingsState(nvdApiKey = "key-1"), SettingsDefaults.reset(tuned))
    }
    @Test fun `differs is false at defaults and true after any one covered value changes`() {
        assertFalse(SettingsDefaults.differs(SettingsState()))
        assertFalse("the NVD key is not a covered value", SettingsDefaults.differs(SettingsState(nvdApiKey = "k")))
        val oneEach = listOf(d.copy(mdns = false), d.copy(ssdp = false), d.copy(netbios = false), d.copy(reverseDns = false), d.copy(snmp = false),
            d.copy(httpTitle = false), d.copy(sweepConcurrency = 160), d.copy(connectTimeoutMs = 700), d.copy(maxPrefix = 24), d.copy(scanConcurrency = 96),
            d.copy(scanTimeoutMs = 500), d.copy(extEnabled = true), d.copy(extMax = 5), d.copy(extGatewayProbe = true))
        assertEquals(14, oneEach.size)   // the 14 covered values of plan 3C-1 (spec D3)
        oneEach.forEach { assertTrue("$it", SettingsDefaults.differs(it)) }
    }
    @Test fun `fresh prefs load exactly SettingsState()`() {
        assertEquals(SettingsState(), AppSettings(FakePrefs()).state.value)
    }
    @Test fun `every key that is missing loads its SettingsState default`() {
        val prefs = FakePrefs(); AppSettings(prefs).update { tuned }
        assertEquals(oneDefault.keys, prefs.stored.keys)   // 14 covered values plus nvdKey, and nothing else
        for ((key, expected) in oneDefault) {
            val without = FakePrefs().also { p -> p.stored.putAll(prefs.stored); p.stored.remove(key) }
            assertEquals("missing $key", expected(tuned), AppSettings(without).state.value)
        }
    }
    @Test fun `a reset is one prefs edit and keeps the key`() {
        val prefs = FakePrefs(); val settings = AppSettings(prefs); settings.update { tuned }
        val before = prefs.edits
        settings.update { SettingsDefaults.reset(it) }
        assertEquals(1, prefs.edits - before)
        assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)
    }
    @Test fun `undo restores the snapshot and keeps a key typed after the reset`() = runBlocking {
        val settings = AppSettings(FakePrefs()); settings.update { tuned }
        SettingsReset.run({ settings.state.value }, settings::update) {
            assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)   // the reset is applied before Undo is offered
            settings.update { it.copy(nvdApiKey = "key-2") }                          // typed while the snackbar shows
            true
        }
        assertEquals(tuned.copy(nvdApiKey = "key-2"), settings.state.value)
    }
    @Test fun `no undo leaves the defaults`() = runBlocking {
        val settings = AppSettings(FakePrefs()); settings.update { tuned }
        SettingsReset.run({ settings.state.value }, settings::update) { false }
        assertEquals(SettingsState(nvdApiKey = "key-1"), settings.state.value)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/settings/DefaultMarkerTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3C spec 8.1 item 2. */
class DefaultMarkerTest {
    @Test fun `null at the default and the default with its unit otherwise`() {
        assertNull(DefaultMarker.text(128, 128, ""))
        assertEquals("Default 128", DefaultMarker.text(256, 128, ""))
        assertNull(DefaultMarker.text(600, 600, " ms"))
        assertEquals("Default 600 ms", DefaultMarker.text(900, 600, " ms"))
        assertEquals("Default 4", DefaultMarker.text(7, 4, ""))
    }
    @Test fun `the description names the control and the default value`() {
        assertEquals("Reset Sweep concurrency to 128", SettingsCopy.resetDescription("Sweep concurrency", 128, ""))
        assertEquals("Reset Port scan timeout to 600 ms", SettingsCopy.resetDescription("Port scan timeout", 600, " ms"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`: add two imports after `import com.northwalessolutions.netprobe.ui.detail.setByYouLine`:
```kotlin
import com.northwalessolutions.netprobe.ui.settings.DefaultMarker
import com.northwalessolutions.netprobe.ui.settings.SettingsCopy
```
In the `templates` list, replace the last entry
```kotlin
        "Open \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+ as (http|https|ssh|vnc|rdp|ftp|telnet|smb)",
    ).map { Regex(it) }
```
with
```kotlin
        "Open \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+ as (http|https|ssh|vnc|rdp|ftp|telnet|smb)",
        // 3C-1 Settings (spec 7.2)
        "Settings menu", "Reset to defaults", "Settings reset to defaults", "Default \\d+( ms)?", "Reset .+ to \\d+( ms)?",
    ).map { Regex(it) }
```
and add this test before the class's closing brace:
```kotlin
    @Test fun `settings reset strings`() {
        listOf(SettingsCopy.MENU, SettingsCopy.RESET, SettingsCopy.RESET_DONE).forEach { inTable(it) }
        for (unit in listOf("", " ms")) for (v in listOf(1, 4, 128, 600, 2000)) {
            inTable(SettingsCopy.defaultMarker(v, unit)); inTable(SettingsCopy.resetDescription("Sweep connect timeout", v, unit))
            DefaultMarker.text(v + 1, v, unit)?.let { inTable(it) }
        }
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.SettingsDefaultsTest" --tests "com.northwalessolutions.netprobe.ui.settings.DefaultMarkerTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'SettingsDefaults'`, `'SettingsReset'`, `'DefaultMarker'`, `'SettingsCopy'`, and `Argument type mismatch` for `AppSettings(FakePrefs())`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt` (whole file; `SettingsState` and `ManualRangeStore` are unchanged):
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import android.content.SharedPreferences
import com.northwalessolutions.netprobe.engine.portscan.ScanOptions
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class SettingsState(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true, val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22,
    val scanConcurrency: Int = 128, val scanTimeoutMs: Int = 600, val nvdApiKey: String = "",
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
)

/** The single source of defaults (3C spec 1.1). AppSettings.load reads every default from [DEFAULTS], so the two can never drift. */
object SettingsDefaults {
    val DEFAULTS = SettingsState()
    /** Every covered value back to its default; the NVD key is kept (D3). Manual ranges, wordlists and the brute-force options are data, not settings. */
    fun reset(current: SettingsState): SettingsState = DEFAULTS.copy(nvdApiKey = current.nvdApiKey)
    fun differs(current: SettingsState): Boolean = reset(current) != current
    /** Undo of a reset: the snapshot from before it, with any NVD key typed since kept (spec 1.2 step 4). */
    fun undo(before: SettingsState, current: SettingsState): SettingsState = before.copy(nvdApiKey = current.nvdApiKey)
}

/**
 * Reset, then Undo when [showUndo] reports the Director tapped it (spec 1.2). Each step is one AppSettings.update, so one
 * SharedPreferences edit: a kill mid-reset never leaves half the values reset. Undo restores the whole snapshot, so a slider moved
 * between the reset and the Undo tap goes back too (accepted, spec 1.2, G18).
 */
object SettingsReset {
    suspend fun run(current: () -> SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, showUndo: suspend () -> Boolean) {
        val before = current()
        update { SettingsDefaults.reset(it) }
        if (showUndo()) update { SettingsDefaults.undo(before, it) }
    }
}

class AppSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    /** The current network's manual ranges, keyed per network row so a list typed at one site is never swept at another. */
    val manual = ManualRangeStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    /** Every default comes from SettingsDefaults.DEFAULTS (3C spec 1.1); keys and coercions are unchanged. */
    private fun load(): SettingsState {
        val d = SettingsDefaults.DEFAULTS
        return SettingsState(
            mdns = prefs.getBoolean("mdns", d.mdns), ssdp = prefs.getBoolean("ssdp", d.ssdp), netbios = prefs.getBoolean("netbios", d.netbios),
            reverseDns = prefs.getBoolean("rdns", d.reverseDns), snmp = prefs.getBoolean("snmp", d.snmp), httpTitle = prefs.getBoolean("http", d.httpTitle),
            sweepConcurrency = prefs.getInt("sweepConc", d.sweepConcurrency).coerceIn(32, 512),
            connectTimeoutMs = prefs.getInt("connTimeout", d.connectTimeoutMs).coerceIn(200, 2000),
            maxPrefix = prefs.getInt("maxPrefix", d.maxPrefix).coerceIn(20, 24),
            scanConcurrency = prefs.getInt("scanConc", d.scanConcurrency).coerceIn(32, 512),
            scanTimeoutMs = prefs.getInt("scanTimeout", d.scanTimeoutMs).coerceIn(200, 2000),
            nvdApiKey = prefs.getString("nvdKey", d.nvdApiKey) ?: d.nvdApiKey,
            extEnabled = prefs.getBoolean("extEnabled", d.extEnabled),
            extMax = prefs.getInt("extMax", d.extMax).coerceIn(EXT_MAX_MIN, EXT_MAX_MAX),
            extGatewayProbe = prefs.getBoolean("extGatewayProbe", d.extGatewayProbe))
    }
    /** Port-scan options for the detail screen and "Quick scan all". */
    fun scanOptions() = ScanOptions(concurrency = state.value.scanConcurrency, connectTimeoutMs = state.value.scanTimeoutMs)
    fun update(f: (SettingsState) -> SettingsState) {
        val s = f(_state.value)
        prefs.edit().putBoolean("mdns", s.mdns).putBoolean("ssdp", s.ssdp).putBoolean("netbios", s.netbios).putBoolean("rdns", s.reverseDns).putBoolean("snmp", s.snmp).putBoolean("http", s.httpTitle)
            .putInt("sweepConc", s.sweepConcurrency).putInt("connTimeout", s.connectTimeoutMs).putInt("maxPrefix", s.maxPrefix).putInt("scanConc", s.scanConcurrency).putInt("scanTimeout", s.scanTimeoutMs)
            .putString("nvdKey", s.nvdApiKey)
            .putBoolean("extEnabled", s.extEnabled).putInt("extMax", s.extMax.coerceIn(EXT_MAX_MIN, EXT_MAX_MAX)).putBoolean("extGatewayProbe", s.extGatewayProbe).apply()
        _state.value = load()
    }

    companion object {
        const val PREFS_NAME = "netprobe"
        const val EXT_MAX_MIN = 1
        const val EXT_MAX_MAX = 16
        const val EXT_MAX_DEFAULT = 4
    }
}

/** `extManual.<networkId>`, newline-joined, in SharedPreferences (extended spec 4.4 and 8.3). No schema change. */
class ManualRangeStore(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    fun get(networkId: Long): List<Subnet> = read(key(networkId))?.lines()?.mapNotNull { CidrParser.subnetOf(it.trim()) }.orEmpty()
    fun set(networkId: Long, ranges: List<Subnet>) {
        write(key(networkId), ranges.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"))
        _revision.update { it + 1 }
    }
    fun forNetwork(networkId: Long?): List<Subnet> = networkId?.let(::get).orEmpty()
    companion object { fun key(networkId: Long) = "extManual.$networkId" }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

/** Every Settings string 3C adds (spec 7.2). Templates live in CopyTableTest. */
object SettingsCopy {
    const val MENU = "Settings menu"
    const val RESET = "Reset to defaults"
    const val RESET_DONE = "Settings reset to defaults"
    /** [unit] is written as the value shows it: "" or " ms". */
    fun defaultMarker(value: Int, unit: String) = "Default $value$unit"
    fun resetDescription(label: String, value: Int, unit: String) = "Reset $label to $value$unit"
}

/** The marker beside a moved slider or stepper (spec 1.3): null, so it takes no space, while the value is the default. */
object DefaultMarker {
    fun text(value: Int, default: Int, unit: String): String? = if (value == default) null else SettingsCopy.defaultMarker(default, unit)
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.SettingsDefaultsTest" --tests "com.northwalessolutions.netprobe.ui.settings.DefaultMarkerTest" --tests "com.northwalessolutions.netprobe.ui.home.CopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. `AppContainer.settings` still calls `AppSettings(context)`, now the secondary constructor; nothing else changes.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/phase3c-base.txt app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsCopy.kt \
  app/src/test/java/com/northwalessolutions/netprobe/data/FakePrefs.kt app/src/test/java/com/northwalessolutions/netprobe/data/SettingsDefaultsTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/settings/DefaultMarkerTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt
git status --short    # nothing else staged
git commit -m "Phase 3C-1: settings defaults, reset and undo logic, marker copy"
```

---

### Task 2: Settings UI: the stateless SettingsContent, the overflow "Reset to defaults" with Undo, slider Default markers, and the section order

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (whole file)
- Test (compile only, run in Task 11): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt`

**Interfaces:**
- Consumes: `SettingsDefaults`, `SettingsReset`, `SettingsCopy`, `DefaultMarker` (Task 1); `HomeCopy.UNDO`, `BackToHome(onBack, trailing)` (3A), `PinnedSegmented`, `npFieldColors`, `ExtendedSection`, `ToolsSection`, `rememberWordlists`, `rememberWordlistStore` (Phase 2 and 3B); `AppContainer.settings`, `currentNetwork`, `networkEnvMonitor`, `vulnCache`.
- Produces:
```kotlin
data class SettingsUi(val state: SettingsState, val networkName: String? = null, val networkId: Long? = null, val manual: List<Subnet> = emptyList(),
                      val local: Subnet? = null, val importedWordlists: Int? = null)
data class SettingsActions(val onBack: () -> Unit = {}, val update: ((SettingsState) -> SettingsState) -> Unit = {}, val current: () -> SettingsState = { SettingsState() },
                           val onManualChange: (List<Subnet>) -> Unit = {}, val clearVulnCache: suspend () -> String = { "" }, val onOpenWordlists: () -> Unit = {})
@Composable fun SettingsContent(ui: SettingsUi, actions: SettingsActions)
@Composable internal fun SettingsMenu(resetEnabled: Boolean, onReset: () -> Unit)                     // tags settingsMenu, resetDefaults
@Composable internal fun DefaultMarkerButton(text: String, description: String, tag: String, enabled: Boolean = true, onReset: () -> Unit)
@Composable internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, default: Int? = null, onCommit: (Int) -> Unit)
```
Section order after this task: Discovery, `ExtendedCopy.TITLE` (renamed "Aggressive scan" in Task 3), Speed, Large networks, Permissions, Vulnerabilities, Tools (spec 2.3).

- [ ] **Step 1: Write the failing Compose test**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 4. In-memory state only: the Director's "netprobe" preferences are never opened. */
@RunWith(AndroidJUnit4::class)
class SettingsContentTest {
    @get:Rule val rule = createComposeRule()
    private var s by mutableStateOf(SettingsState())

    private fun show(state: SettingsState = SettingsState(), networkName: String? = "Home") {
        s = state
        rule.setContent { NetProbeTheme {
            SettingsContent(SettingsUi(s, networkName, if (networkName != null) 1L else null, emptyList(), CidrParser.subnetOf("10.0.3.0/24")),
                SettingsActions(update = { f -> s = f(s) }, current = { s }))
        } }
    }
    private fun headingY(text: String) =
        rule.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().positionInRoot.y

    @Test fun resetIsDisabledAtTheDefaults() {
        show()
        rule.onNodeWithTag("settingsMenu").assert(hasContentDescription("Settings menu")).performClick()
        rule.onNodeWithTag("resetDefaults").assertIsNotEnabled()
    }
    @Test fun theOverflowIsPinnedOutsideTheScrollingColumn() {
        show()
        val menu = rule.onNodeWithTag("settingsMenu").fetchSemanticsNode()
        assertFalse(generateSequence(menu.parent) { it.parent }.any { SemanticsProperties.VerticalScrollAxisRange in it.config })
    }
    @Test fun aMovedSliderShowsItsMarkerAndTheMarkerRestoresTheDefault() {
        show(SettingsState(sweepConcurrency = 256))
        rule.onNodeWithTag("default:Sweep connect timeout").assertDoesNotExist()   // at its default: no marker, no space
        rule.onNodeWithTag("default:Sweep concurrency").assert(hasContentDescription("Reset Sweep concurrency to 128"))
            .assert(hasText("Default 128")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(128, s.sweepConcurrency)
        rule.onNodeWithTag("default:Sweep concurrency").assertDoesNotExist()
    }
    @Test fun resetToDefaultsThenUndoRestoresEveryValueAndKeepsTheKey() {
        val tuned = SettingsState(mdns = false, sweepConcurrency = 256, connectTimeoutMs = 900, maxPrefix = 20, scanTimeoutMs = 1200,
            extEnabled = true, extMax = 8, extGatewayProbe = true, nvdApiKey = "k1")
        show(tuned)
        rule.onNodeWithTag("settingsMenu").performClick()
        rule.onNodeWithTag("resetDefaults").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(SettingsState(nvdApiKey = "k1"), s)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Settings reset to defaults").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(tuned, s)
    }
    @Test fun theAggressiveSectionSitsDirectlyUnderDiscovery() {
        show()
        val discovery = headingY("Discovery"); val aggressive = headingY(ExtendedCopy.TITLE); val speed = headingY("Speed")
        assertTrue("Discovery $discovery, ${ExtendedCopy.TITLE} $aggressive, Speed $speed", discovery < aggressive && aggressive < speed)
    }
}
```

- [ ] **Step 2: Compile to see it fail**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED` with `Unresolved reference 'SettingsContent'`, `'SettingsUi'`, `'SettingsActions'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (whole file; `Section`, `npSwitchColors`, `SwitchRow` and `LocationPermissionRow` are unchanged):
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsReset
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.wordlists.rememberWordlistStore
import com.northwalessolutions.netprobe.ui.wordlists.rememberWordlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CONC_MIN = 32; private const val CONC_MAX = 512; private const val CONC_STEP = 32
private const val TIMEOUT_MIN = 200; private const val TIMEOUT_MAX = 2000; private const val TIMEOUT_STEP = 100
private val PREFIX_OPTIONS = listOf(20, 22, 24)
private val SWITCH_ROW_MIN = 56.dp
private val MARKER_PAD = 8.dp
private const val NO_UNIT = ""
private const val MS_UNIT = " ms"

/** Everything Settings shows; no AppContainer, so the Compose tests drive it with in-memory state (3C spec 8.2 item 4). */
data class SettingsUi(val state: SettingsState, val networkName: String? = null, val networkId: Long? = null, val manual: List<Subnet> = emptyList(),
                      val local: Subnet? = null, val importedWordlists: Int? = null)

/** [current] reads the live settings at the Reset tap, so the Undo snapshot is never a stale recomposition value. */
data class SettingsActions(val onBack: () -> Unit = {}, val update: ((SettingsState) -> SettingsState) -> Unit = {}, val current: () -> SettingsState = { SettingsState() },
                           val onManualChange: (List<Subnet>) -> Unit = {}, val clearVulnCache: suspend () -> String = { "" }, val onOpenWordlists: () -> Unit = {})

// The IME joins the content insets, as on Home and Detail; a separate imePadding would count the navigation bar twice.
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val network by container.currentNetwork.collectAsStateWithLifecycle()
    val revision by container.settings.manual.revision.collectAsStateWithLifecycle()
    val manual = remember(network?.id, revision) { container.settings.manual.forNetwork(network?.id) }
    val local = remember(network?.id, s.maxPrefix) {   // re-read on a network switch (Task 17 M2)
        container.networkEnvMonitor.current()?.let { SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, s.maxPrefix).subnet } }
    val wordlists by rememberWordlists(container)
    val wordlistsLoaded = rememberWordlistStore(container).value != null
    SettingsContent(
        SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null),
        SettingsActions(onBack = onBack, update = { container.settings.update(it) }, current = { container.settings.state.value },
            onManualChange = { list -> network?.id?.let { container.settings.manual.set(it, list) } },
            clearVulnCache = {
                try { container.vulnCache.clear(); "Vulnerability cache cleared" } catch (ce: CancellationException) { throw ce } catch (e: Exception) { "Could not clear cache: ${e.message}" }
            },
            onOpenWordlists = onOpenWordlists))
}

/** Settings layout (Radar spec 6, 3C spec 1 and 2.3). PINNED: the back row and the overflow menu sit in the topBar, never in the scroll. */
@Composable
fun SettingsContent(ui: SettingsUi, actions: SettingsActions) {
    val s = ui.state
    val update = actions.update
    val d = SettingsDefaults.DEFAULTS
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onReset: () -> Unit = {
        scope.launch {
            SettingsReset.run(actions.current, update) {
                snackbar.showSnackbar(SettingsCopy.RESET_DONE, actionLabel = HomeCopy.UNDO, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
            }
        }
    }
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime), snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            BackToHome(actions.onBack) { SettingsMenu(resetEnabled = SettingsDefaults.differs(s), onReset = onReset) }
        } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter)) {
            Section("Discovery")
            SwitchRow("mDNS (Bonjour)", s.mdns) { v -> update { it.copy(mdns = v) } }
            SwitchRow("SSDP / UPnP", s.ssdp) { v -> update { it.copy(ssdp = v) } }
            SwitchRow("NetBIOS names", s.netbios) { v -> update { it.copy(netbios = v) } }
            SwitchRow("Reverse DNS", s.reverseDns) { v -> update { it.copy(reverseDns = v) } }
            SwitchRow("SNMP", s.snmp) { v -> update { it.copy(snmp = v) } }
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }

            // Second, directly under Discovery (3C spec D6, 2.3): discoverable without a Home toggle.
            Section(ExtendedCopy.TITLE)
            ExtendedSection(s, update, networkName = ui.networkName, manual = ui.manual, local = ui.local, networkId = ui.networkId, onManualChange = actions.onManualChange)

            Section("Speed")
            StepSlider("Sweep concurrency", s.sweepConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, NO_UNIT, default = d.sweepConcurrency) { v -> update { it.copy(sweepConcurrency = v) } }
            StepSlider("Sweep connect timeout", s.connectTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, MS_UNIT, default = d.connectTimeoutMs) { v -> update { it.copy(connectTimeoutMs = v) } }
            StepSlider("Port scan concurrency", s.scanConcurrency, CONC_MIN, CONC_MAX, CONC_STEP, NO_UNIT, default = d.scanConcurrency) { v -> update { it.copy(scanConcurrency = v) } }
            StepSlider("Port scan timeout", s.scanTimeoutMs, TIMEOUT_MIN, TIMEOUT_MAX, TIMEOUT_STEP, MS_UNIT, default = d.scanTimeoutMs) { v -> update { it.copy(scanTimeoutMs = v) } }

            Section("Large networks")
            Text("Largest network to sweep. Bigger networks are cut down to your own /24 unless widened here.", style = NetProbeText.data, color = NetProbeColors.textMuted)
            PinnedSegmented(PREFIX_OPTIONS.map { "/$it" }, PREFIX_OPTIONS.indexOf(s.maxPrefix), { i -> update { it.copy(maxPrefix = PREFIX_OPTIONS[i]) } },
                activeFill = NetProbeColors.surface, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())

            Section("Permissions")
            LocationPermissionRow()

            Section("Vulnerabilities")
            var key by remember { mutableStateOf(s.nvdApiKey) }
            OutlinedTextField(key, { key = it; update { st -> st.copy(nvdApiKey = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("NVD API key (optional)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
            TextButton(onClick = { scope.launch { snackbar.showSnackbar(actions.clearVulnCache()) } }, modifier = Modifier.heightIn(min = Dimens.touch)) {
                Text("Clear vulnerability cache", style = NetProbeText.action, color = NetProbeColors.signal)
            }
            ToolsSection(imported = ui.importedWordlists, onOpenWordlists = actions.onOpenWordlists)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The Settings overflow (3C spec 1.2): one item, enabled only while some covered value differs from its default. */
@Composable
internal fun SettingsMenu(resetEnabled: Boolean, onReset: () -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.size(Dimens.touch).testTag("settingsMenu")) {
            Icon(Icons.Default.MoreVert, SettingsCopy.MENU, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(SettingsCopy.RESET) }, enabled = resetEnabled, onClick = { open = false; onReset() }, modifier = Modifier.testTag("resetDefaults"))
        }
    }
}

/** "Default 128" in caption / textSecondary inside a 48 dp target (3C spec 1.3); a tap restores that one control. */
@Composable
internal fun DefaultMarkerButton(text: String, description: String, tag: String, enabled: Boolean = true, onReset: () -> Unit) {
    Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(enabled = enabled, role = Role.Button, onClick = onReset)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, style = NetProbeText.caption, color = NetProbeColors.textSecondary, modifier = Modifier.padding(horizontal = MARKER_PAD))
    }
}

/** Caption in textMuted over a 1 dp surface rule, the Home group-header pattern; never teal (spec section 6). */
@Composable
internal fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(title, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
internal fun npSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = NetProbeColors.signal, checkedThumbColor = NetProbeColors.background, checkedBorderColor = NetProbeColors.signal,
    uncheckedTrackColor = NetProbeColors.surface, uncheckedThumbColor = NetProbeColors.textMuted, uncheckedBorderColor = NetProbeColors.border,
)

/** Label, optional supporting text, a switch; the whole row toggles; 56 dp minimum (spec section 6). */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SWITCH_ROW_MIN).alpha(if (enabled) 1f else DISABLED_ALPHA)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            supporting?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = npSwitchColors())
    }
}

/**
 * Moves locally while dragging and saves once on release, so preferences are not rewritten on every drag frame. With [default] set,
 * a "Default N" marker shows before the value while the saved value differs (3C spec 1.3).
 */
@Composable
internal fun StepSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean = true, default: Int? = null, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
    Column(Modifier.heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            if (default != null) DefaultMarker.text(value, default, unit)?.let { text ->
                DefaultMarkerButton(text, SettingsCopy.resetDescription(label, default, unit), "default:$label", enabled) { onCommit(default) }
            }
            Text("$snapped$unit", style = NetProbeText.data, color = NetProbeColors.signal)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1, enabled = enabled,
            onValueChangeFinished = { if (snapped != value) onCommit(snapped) },
            colors = SliderDefaults.colors(thumbColor = NetProbeColors.signal, activeTrackColor = NetProbeColors.signal, inactiveTrackColor = NetProbeColors.surface,
                activeTickColor = NetProbeColors.background, inactiveTickColor = NetProbeColors.border))
    }
}

@Composable
private fun LocationPermissionRow() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Text("Android only reveals the Wi-Fi name with location permission. It is shown for display only: NetProbe tells networks apart by their gateway.",
        style = NetProbeText.data, color = NetProbeColors.textMuted)
    if (granted) Text("Location permission granted", style = NetProbeText.secondary, color = NetProbeColors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
    else TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, modifier = Modifier.heightIn(min = Dimens.touch)) {
        Text("Allow location for Wi-Fi name", style = NetProbeText.action, color = NetProbeColors.signal)
    }
}
```

Notes for the implementer: `StepSlider` keeps its positional parameters, so `SettingsRowsTest`'s `StepSlider("Sweep concurrency", 128, 32, 512, 32, "") { }` compiles unchanged. The Undo reuses `HomeCopy.UNDO` (spec 7.2). The snackbar dies with the screen, which ends the Undo window (spec 1.2). The `local` range stays `SubnetMath.sweepRange` here; Task 4 switches it to `HomeRange`.

- [ ] **Step 4: Compile and run the JVM suite**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsContentTest.kt
git status --short
git commit -m "Phase 3C-1: Settings overflow reset with undo, default markers, section order"
```

---

### Task 3: "Aggressive scan" and "Smart discovery" copy, the stepper Default marker, stepper and manual editor always enabled, and the Home string renames

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSection.kt` (whole file), `ui/home/HomeCopy.kt`
- Test: modify `app/src/test/java/com/northwalessolutions/netprobe/ui/settings/ExtendedCopyTest.kt`, `ui/home/ExtendedNoticesTest.kt`, `ui/home/CopyTableTest.kt`; compile only (run in Task 11): modify `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSectionTest.kt`

**Interfaces:**
- Consumes: `SettingsDefaults.DEFAULTS` (Task 1), `DefaultMarker`, `SettingsCopy` (Task 1), `DefaultMarkerButton` (Task 2), `SwitchRow`, `CidrParser`, `ManualCap` (Phase 2).
- Produces (copy, spec 7.2):
```kotlin
ExtendedCopy.TITLE = "Aggressive scan"; ExtendedCopy.SUPPORT; ExtendedCopy.PROBE_LABEL = "Smart discovery"; ExtendedCopy.PROBE_SUPPORT; ExtendedCopy.MANUAL_LINE
HomeCopy.NO_EXTRA_RANGES, GATEWAY_FOUND_NONE, GATEWAY_ANSWERS_ALL, SOURCE_GATEWAY (renamed); HomeCopy.GATEWAY_WALK_SKIPPED (new);
fun HomeCopy.capDropped(n: Int) (reworded); fun HomeCopy.largeNetworkPartial(prefix: Int, swept: Int): String (new)
```
- Behaviour (spec 2.3, D8): the "Maximum extra ranges" stepper is always enabled and carries the D2 marker (default 4); "Smart discovery" stays disabled while Aggressive scan is off; the manual editor is enabled whenever a network is current and shows `MANUAL_LINE` under its title.

- [ ] **Step 1: Update the tests first**

`app/src/test/java/com/northwalessolutions/netprobe/ui/settings/ExtendedCopyTest.kt`: add this test before the closing brace:
```kotlin
    @Test fun `aggressive scan and smart discovery copy is the spec 7_2 table`() {
        assertEquals("Aggressive scan", ExtendedCopy.TITLE)
        assertEquals("Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach.", ExtendedCopy.SUPPORT)
        assertEquals("Smart discovery", ExtendedCopy.PROBE_LABEL)
        assertEquals("Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s.", ExtendedCopy.PROBE_SUPPORT)
        assertEquals("Swept first on every scan, with Aggressive scan on or off", ExtendedCopy.MANUAL_LINE)
        val removed = ExtendedCopy::class.java.declaredFields.map { it.name }
        assertFalse("MANUAL_ON" in removed); assertFalse("MANUAL_OFF" in removed)   // spec 7.2: removed from the draft
    }
```
and add `import org.junit.Assert.assertFalse` to its imports.

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt`: replace these expected strings exactly (the rest of the file is unchanged):
- line `assertEquals(listOf("Extended discovery: no extra ranges to sweep"), ...` becomes `assertEquals(listOf("Aggressive scan: no extra ranges to sweep"), ExtendedNotices.of(DiscoveryStatus(notes = listOf(ExtendedNote.NO_EXTRA_RANGES), ranges = listOf(local))))`
- `"Gateway probe found no other ranges"` becomes `"Smart discovery found no other ranges"`
- `"Gateway probe skipped: every address answered"` becomes `"Smart discovery skipped: every address answered"`
- `"2 more ranges found, raise the cap in Settings"` becomes `"2 more ranges found, raise Maximum extra ranges in Settings"`
- `"1 more range found, raise the cap in Settings"` becomes `"1 more range found, raise Maximum extra ranges in Settings"`
- `"10.0.5.0/24  gateway probe  skipped, every address answered"` becomes `"10.0.5.0/24  smart discovery  skipped, every address answered"`

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`, in the `templates` list:
- replace `"Large network \\(/\\d+\\)\\. Scanned your /24 only\\. Widen in Settings\\.",` with `"Large network \\(/\\d+\\)\\. Scanned your /\\d+ only\\. Widen in Settings\\.",`
- replace `"\\d+ more ranges? found, raise the cap in Settings", "Extended discovery: no extra ranges to sweep", "Gateway probe found no other ranges",` with `"\\d+ more ranges? found, raise Maximum extra ranges in Settings", "Aggressive scan: no extra ranges to sweep", "Smart discovery found no other ranges",`
- replace `"Gateway probe skipped: every address answered", "Other ranges skipped: could not bind to Wi-Fi", "\\S+ skipped: every address answered",` with `"Smart discovery skipped: every address answered", "Other ranges skipped: could not bind to Wi-Fi", "\\S+ skipped: every address answered", "Smart discovery: too many ranges answered, nearby ranges not checked",`
- in the range-line template (the entry that begins `"\\S+  (manual|evidence|gateway probe|local)  (swept, nothing reachable`), replace the words `gateway probe` with `smart discovery` and change nothing else on that line
- add, after the 3C-1 Settings line Task 1 added: `"Aggressive scan", "Smart discovery", "Swept first on every scan, with Aggressive scan on or off", "Your subnet is always scanned; .+", "Looks for gateways at the usual addresses .+",`

and add these tests before the class's closing brace (import `com.northwalessolutions.netprobe.ui.settings.ExtendedCopy`):
```kotlin
    @Test fun `aggressive scan and the large network notices`() {
        listOf(ExtendedCopy.TITLE, ExtendedCopy.SUPPORT, ExtendedCopy.PROBE_LABEL, ExtendedCopy.PROBE_SUPPORT, ExtendedCopy.MANUAL_LINE,
            HomeCopy.GATEWAY_WALK_SKIPPED, HomeCopy.largeNetwork(16), HomeCopy.largeNetworkPartial(16, 22), HomeCopy.largeNetworkPartial(16, 20)).forEach { inTable(it) }
        assertEquals("Large network (/16). Scanned your /22 only. Widen in Settings.", HomeCopy.largeNetworkPartial(16, 22))
        assertEquals("1 more range found, raise Maximum extra ranges in Settings", HomeCopy.capDropped(1))
    }
    @Test fun `names removed from the draft do not exist`() {   // spec 7.2
        val fields = HomeCopy::class.java.declaredFields.map { it.name }; val methods = HomeCopy::class.java.declaredMethods.map { it.name }
        assertFalse("MENU_AGGRESSIVE" in fields); assertFalse("aggressiveDescription" in methods)
    }
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSectionTest.kt`: replace the test `masterSwitchOffDisablesButShowsTheRows` with:
```kotlin
    @Test fun aggressiveOffDisablesOnlySmartDiscovery() {   // 3C spec D8, 2.3: manual ranges sweep whatever the toggle says
        show(enabled = false)
        rule.onNode(hasText("Smart discovery", substring = true) and hasClickAction()).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("At most 4 extra /24 networks (about 2 minutes)").assertIsDisplayed()
        rule.onNodeWithContentDescription("More ranges").assertIsEnabled()
        rule.onNodeWithTag("addRange").assertIsEnabled()
        rule.onNodeWithText("Swept first on every scan, with Aggressive scan on or off").assertIsDisplayed()
        rule.onNode(hasText("Aggressive scan", substring = true) and hasClickAction()).assertIsEnabled().performClick()
        rule.onNode(hasText("Smart discovery", substring = true) and hasClickAction()).assertIsEnabled()
    }
    @Test fun theStepperShowsItsDefaultMarkerWhenMovedAndRestoresFour() {
        show(cap = 7)
        rule.onNodeWithTag("default:Maximum extra ranges").assert(hasContentDescription("Reset Maximum extra ranges to 4")).assert(hasText("Default 4")).performClick()
        rule.onNodeWithText("At most 4 extra /24 networks (about 2 minutes)").assertIsDisplayed()
        rule.onNodeWithTag("default:Maximum extra ranges").assertDoesNotExist()
    }
```
(`show` already keeps the state in a `remember`ed `mutableStateOf`, so the marker tap updates it.)

- [ ] **Step 2: Run the JVM tests and compile to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.settings.ExtendedCopyTest" --tests "com.northwalessolutions.netprobe.ui.home.ExtendedNoticesTest" --tests "com.northwalessolutions.netprobe.ui.home.CopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` (`Unresolved reference 'MANUAL_LINE'`, `'GATEWAY_WALK_SKIPPED'`, `'largeNetworkPartial'`), then, once those exist, assertion failures on the old strings.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSection.kt` (whole file; `ManualCap`, `StepButton` and the editor's validation are unchanged):
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.AppSettings
import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import kotlin.math.ceil

/** Settings copy owned by the extended-discovery spec 8.3, renamed by the 3C spec 2.3 and 7.2 ("Aggressive scan", "Smart discovery"). */
object ExtendedCopy {
    const val TITLE = "Aggressive scan"
    const val SUPPORT = "Your subnet is always scanned; one wider than the Large networks limit gets your /24 only. Turn on to scan as much of it as the limit allows, plus other private ranges your phone can reach."
    const val MAX_LABEL = "Maximum extra ranges"
    const val PROBE_LABEL = "Smart discovery"
    /** Worst case: 1,572 first-pass attempts plus at most 256 for the outward walk, about 13 s plus 12.8 s (3C spec 2.5). */
    const val PROBE_SUPPORT = "Looks for gateways at the usual addresses on nearby ranges and sweeps only the ranges that answer. Up to about 1,850 connection attempts, about 30 s."
    const val MANUAL_LINE = "Swept first on every scan, with Aggressive scan on or off"
    const val NO_NETWORK = "Connect to a network to edit its ranges"
    const val BEYOND_CAP = "Beyond cap, will be skipped"
    const val ADD = "Add range"
    const val SECONDS_PER_RANGE = 16   // measured on the Pixel 7 Pro (Task 20 check 11): 13.7 to 16.2 s per /24, mean 15.5 s
    private const val SECONDS_PER_MINUTE = 60.0
    fun capLine(n: Int): String {
        val minutes = ceil(n * SECONDS_PER_RANGE / SECONDS_PER_MINUTE).toInt()
        val nets = if (n == 1) "1 extra /24 network" else "$n extra /24 networks"
        return "At most $nets (about ${if (minutes == 1) "1 minute" else "$minutes minutes"})"
    }
    fun manualTitle(networkName: String) = "Manual ranges for $networkName"
}

object ManualCap {
    /** An entry is beyond the cap when its /24 units, counted in list order, run past [cap] (manual entries come first under the cap). */
    fun beyondCap(entries: List<Subnet>, cap: Int): List<Boolean> {
        var used = 0
        return entries.map { used += CidrParser.units(it).size; used > cap }
    }
}

private const val NO_UNIT = ""

/**
 * The Aggressive scan section (3C spec 2.3). Only "Smart discovery" depends on the switch: the stepper caps manual ranges with
 * Aggressive scan off too, and the editor is enabled whenever a network is current, because manual ranges sweep on every scan (D8).
 */
@Composable
fun ExtendedSection(state: SettingsState, update: ((SettingsState) -> SettingsState) -> Unit, networkName: String?,
                    manual: List<Subnet>, local: Subnet?, networkId: Long? = null, onManualChange: (List<Subnet>) -> Unit) {
    val on = state.extEnabled
    SwitchRow(ExtendedCopy.TITLE, on, supporting = ExtendedCopy.SUPPORT) { v -> update { it.copy(extEnabled = v) } }
    Stepper(ExtendedCopy.MAX_LABEL, state.extMax, AppSettings.EXT_MAX_MIN, AppSettings.EXT_MAX_MAX, ExtendedCopy.capLine(state.extMax), enabled = true,
        default = SettingsDefaults.DEFAULTS.extMax) { v -> update { it.copy(extMax = v) } }
    SwitchRow(ExtendedCopy.PROBE_LABEL, state.extGatewayProbe, supporting = ExtendedCopy.PROBE_SUPPORT, enabled = on) { v -> update { it.copy(extGatewayProbe = v) } }
    ManualEditor(networkName, networkId, manual, local, state.extMax, enabled = networkName != null, onManualChange)
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, line: String, enabled: Boolean, default: Int? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else DISABLED_ALPHA), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        if (default != null) DefaultMarker.text(value, default, NO_UNIT)?.let { text ->
            DefaultMarkerButton(text, SettingsCopy.resetDescription(label, default, NO_UNIT), "default:$label", enabled) { onChange(default) }
        }
        StepButton(Icons.Default.Remove, "Fewer ranges", enabled && value > min) { onChange(value - 1) }
        Text("$value", style = NetProbeText.data, color = NetProbeColors.signal, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
        StepButton(Icons.Default.Add, "More ranges", enabled && value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description, tint = if (enabled) NetProbeColors.signal else NetProbeColors.border) }
}

@Composable
private fun ManualEditor(networkName: String?, networkId: Long?, manual: List<Subnet>, local: Subnet?, cap: Int, enabled: Boolean, onChange: (List<Subnet>) -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Text(networkName?.let { ExtendedCopy.manualTitle(it) } ?: ExtendedCopy.NO_NETWORK, style = NetProbeText.secondary, color = NetProbeColors.text,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        if (networkName != null) Text(ExtendedCopy.MANUAL_LINE, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        val beyond = ManualCap.beyondCap(manual, cap)
        manual.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
                Text(s.toString(), style = NetProbeText.data, color = NetProbeColors.text, modifier = Modifier.weight(1f).testTag("manualRange"))
                if (beyond[i]) Text(ExtendedCopy.BEYOND_CAP, style = NetProbeText.caption, color = NetProbeColors.mark, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = { onChange(manual - s) }, enabled = enabled) { Icon(Icons.Default.Close, "Remove $s", tint = NetProbeColors.textMuted) }
            }
        }
        var text by rememberSaveable(networkName) { mutableStateOf("") }
        var notes by remember(networkId) { mutableStateOf(emptyList<String>()) }   // one network's notes never show for the next (Task 17 M2)
        val result = if (text.isBlank()) null else CidrParser.parse(text, local)
        val error = result?.error
        val helper = (result?.entries?.mapNotNull { it.note }.orEmpty() + notes).joinToString("\n").ifEmpty { null }
        val messageInView = remember { BringIntoViewRequester() }
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // The scroll column does not follow supporting text on its own, so pull the message above the keyboard when it appears or the keyboard opens.
        LaunchedEffect(error ?: helper, imeUp) { if ((error ?: helper) != null) messageInView.bringIntoView() }
        OutlinedTextField(text, { text = it; notes = emptyList() }, Modifier.fillMaxWidth().testTag("addRange"), enabled = enabled,
            label = { Text(ExtendedCopy.ADD) }, placeholder = { Text("10.0.4.0/24") }, isError = error != null, textStyle = NetProbeText.data,
            supportingText = when {
                error != null -> { { Text(error, color = NetProbeColors.error, modifier = Modifier.bringIntoViewRequester(messageInView)) } }
                helper != null -> { { Text(helper, color = NetProbeColors.textMuted, modifier = Modifier.bringIntoViewRequester(messageInView)) } }
                else -> null
            },
            colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        val canAdd = enabled && result != null && error == null && result.entries.isNotEmpty()
        TextButton(onClick = {
            val (merged, dupNotes) = CidrParser.merge(manual, result?.subnets.orEmpty())
            onChange(merged); notes = dupNotes; text = ""
        }, enabled = canAdd, modifier = Modifier.heightIn(min = Dimens.touch).testTag("addRangeButton")) {
            Text(ExtendedCopy.ADD, style = NetProbeText.action, color = if (canAdd) NetProbeColors.signal else NetProbeColors.border)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: replace the block from `const val NO_EXTRA_RANGES` to `const val SOURCE_GATEWAY` and the `capDropped` line, and add `largeNetworkPartial` after `largeNetwork`:
```kotlin
    const val NO_EXTRA_RANGES = "Aggressive scan: no extra ranges to sweep"
    const val GATEWAY_FOUND_NONE = "Smart discovery found no other ranges"
    const val BIND_FAILED = "Other ranges skipped: could not bind to Wi-Fi"
    /** Controller ruling: the Radar copy table has no row for GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, so it takes the PHANTOM row's form. */
    const val GATEWAY_ANSWERS_ALL = "Smart discovery skipped: every address answered"
    /** ExtendedNote.GATEWAY_WALK_SKIPPED_MANY (3C spec 2.5 step 1): more than four seeds answered, so the outward walk did not run. */
    const val GATEWAY_WALK_SKIPPED = "Smart discovery: too many ranges answered, nearby ranges not checked"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_EVIDENCE = "evidence"
    const val SOURCE_GATEWAY = "smart discovery"
```
```kotlin
    fun capDropped(n: Int) = if (n == 1) "1 more range found, raise Maximum extra ranges in Settings" else "$n more ranges found, raise Maximum extra ranges in Settings"
```
```kotlin
    fun largeNetwork(prefix: Int) = "Large network (/$prefix). Scanned your /24 only. Widen in Settings."
    /** Aggressive scan on with a mask wider than the limit: the limit-sized block holding the phone was swept (3C spec 2.1). */
    fun largeNetworkPartial(prefix: Int, swept: Int) = "Large network (/$prefix). Scanned your /$swept only. Widen in Settings."
```
The progress line `gatewayProbing` ("probing 10.0.0.0/16 for gateways") is kept: it describes the action (spec 2.4).

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.settings.*" --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` twice.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
grep -rn "Extended discovery\|Probe for gateways\|raise the cap\|Gateway probe " app/src/main --include=*.kt
```
Expected: `BUILD SUCCESSFUL`; the grep finds only code comments (`DiscoveryOptions.kt` "Extended discovery inputs", `GatewayProbe.kt` doc comments), never a user-visible string.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSection.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/settings/ExtendedCopyTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/ExtendedSectionTest.kt
git status --short
git commit -m "Phase 3C-1: Aggressive scan and Smart discovery copy, stepper marker, editor always enabled"
```

---

### Task 4: HomeRange (the mask rule), the RunSettings snapshot, ScanBinding's new signature and every caller

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/netenv/Subnet.kt` (`HomeRange`), `ui/home/ScanBinding.kt` (whole file), `ui/home/HomeViewModel.kt` (`RowRules.row`, Quick scan all), `ui/detail/DetailViewModel.kt` (`scanHost`), `AppContainer.kt` (the tool runner's `bind`), `ui/settings/SettingsScreen.kt` (the editor's `local`)
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RunSettings.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/netenv/HomeRangeTest.kt`, `ui/home/RunSettingsTest.kt`; rewrite `ui/home/ScanBindingTest.kt`

**Interfaces:**
- Consumes: `SubnetMath.sweepRange`, `SubnetMath.of`, `SweepRange` (MVP), `SettingsState` (Task 1), `BoundNetwork`, `SocketBinder`, `BindFailedException`, `currentBinder` (Phase 2), `RangeRules.groupFor` (Phase 2).
- Produces:
```kotlin
object HomeRange { fun of(ip: Inet4Address, prefix: Int, maxPrefix: Int, aggressive: Boolean): SweepRange }        // netenv
data class RunSettings(val aggressive: Boolean, val maxPrefix: Int, val manual: List<Subnet>) {                      // ui.home
    val bindOutside: Boolean; fun homeRange(env: NetworkEnv): SweepRange; fun home(env: NetworkEnv): Subnet
    companion object { fun of(s: SettingsState, manual: List<Subnet>): RunSettings
                       fun effective(snapshot: RunSettings?, live: SettingsState, liveManual: List<Subnet>): RunSettings } }
object ScanBinding {
    suspend fun <T> run(ip: Inet4Address, env: NetworkEnv?, home: Subnet?, bindOutside: Boolean, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T
    suspend fun <T> forRun(ip: Inet4Address, env: NetworkEnv?, runSettings: RunSettings, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T }
fun RowRules.row(h, env, maxPrefix, openPorts, stale, extraRanges = emptyList(), manualRanges = emptyList(), aggressive: Boolean = false): HostRow
```
The old `ScanBinding.run(ip, env, maxPrefix, extEnabled, binderFor, block)` is gone; its three callers (Quick scan all, the Device-screen scan, the tool runner) move to `forRun` here. Radar scans start using `RunSettings` in Task 6.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/netenv/HomeRangeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.netenv

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 3 (D19, ruling 1, controller ruling: a /16 sweeps at most its /20). */
class HomeRangeTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun `aggressive off equals sweepRange for every prefix and limit`() {
        val phone = ip("172.20.9.40")
        for (prefix in 8..30) for (limit in 20..24)
            assertEquals("/$prefix at /$limit", SubnetMath.sweepRange(phone, prefix, limit), HomeRange.of(phone, prefix, limit, aggressive = false))
    }
    @Test fun `aggressive on with a slash 16 sweeps the limit block that holds the phone`() {
        assertEquals(SweepRange(SubnetMath.of(ip("172.20.8.0"), 22), clamped = true, originalPrefix = 16), HomeRange.of(ip("172.20.9.40"), 16, 22, aggressive = true))
        val widest = HomeRange.of(ip("172.20.9.40"), 16, 20, aggressive = true)
        assertEquals("172.20.0.0/20", widest.subnet.toString()); assertTrue(widest.clamped); assertEquals(16, widest.originalPrefix)
    }
    @Test fun `a slash 20 at limit 22 gives the slash 22 holding the phone`() {
        assertEquals("10.20.36.0/22", HomeRange.of(ip("10.20.37.5"), 20, 22, aggressive = true).subnet.toString())
    }
    @Test fun `masks at or narrower than the limit are identical on and off`() {
        for (prefix in listOf(22, 23, 24, 25)) assertEquals("/$prefix", HomeRange.of(ip("10.0.3.20"), prefix, 22, false), HomeRange.of(ip("10.0.3.20"), prefix, 22, true))
        assertEquals("10.0.3.0/24", HomeRange.of(ip("10.0.3.20"), 24, 20, aggressive = true).subnet.toString())   // the Director's home, at every limit
    }
    @Test fun `aggressive on at limit 24 is the phone's slash 24, still marked clamped`() {
        val r = HomeRange.of(ip("172.20.9.40"), 16, 24, aggressive = true)
        assertEquals("172.20.9.0/24", r.subnet.toString()); assertTrue(r.clamped)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunSettingsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class RunSettingsTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
    private val manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!)

    @Test fun `of reads Aggressive scan, the limit and the manual list once`() {
        assertEquals(RunSettings(aggressive = true, maxPrefix = 20, manual = manual), RunSettings.of(SettingsState(extEnabled = true, maxPrefix = 20), manual))
    }
    @Test fun `bindOutside holds with Aggressive scan on or a manual range, never with neither`() {
        assertTrue(RunSettings(true, 22, emptyList()).bindOutside)
        assertTrue(RunSettings(false, 22, manual).bindOutside)
        assertFalse(RunSettings(false, 22, emptyList()).bindOutside)
    }
    @Test fun `home follows HomeRange`() {
        assertEquals("172.20.9.0/24", RunSettings(false, 22, emptyList()).home(env16).toString())
        assertEquals("172.20.8.0/22", RunSettings(true, 22, emptyList()).home(env16).toString())
        assertTrue(RunSettings(true, 22, emptyList()).homeRange(env16).clamped)
    }
    @Test fun `rows group under the aggressive home range`() {
        val h = TestHosts.host(ip = "172.20.10.7")
        assertEquals("172.20.8.0/22" to false, RowRules.row(h, env16, 22, 0, false, aggressive = true).let { it.subnet to it.offSubnet })
        assertEquals("172.20.10.0/24" to true, RowRules.row(h, env16, 22, 0, false, aggressive = false).let { it.subnet to it.offSubnet })
    }
    @Test fun `effective prefers the snapshot and falls back to the live settings`() {
        val snap = RunSettings(false, 22, emptyList())
        assertSame(snap, RunSettings.effective(snap, SettingsState(extEnabled = true, maxPrefix = 20), manual))
        assertEquals(RunSettings(true, 20, manual), RunSettings.effective(null, SettingsState(extEnabled = true, maxPrefix = 20), manual))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ScanBindingTest.kt` (whole file):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** Phase 2 final review I2 and 3C spec 8.1 item 5: rows outside the home range are bound to Wi-Fi when the run binds outside; local rows never are. */
class ScanBindingTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env = TestEnvs.home   // 192.168.1.50/24
    private val home = SubnetMath.of(ip("192.168.1.50"), 24)
    private var lookups = 0
    private val binders: (NetworkEnv) -> SocketBinder? = { lookups++; FakeBinder }

    private suspend fun seen(host: String, bindOutside: Boolean, b: (NetworkEnv) -> SocketBinder? = binders): SocketBinder? =
        ScanBinding.run(ip(host), env, home, bindOutside, b) { currentBinder() }

    @Test fun `an off-subnet row's scan sees the binder`() = runBlocking {
        assertSame(FakeBinder, seen("10.0.4.7", bindOutside = true)); assertEquals(1, lookups)
    }
    @Test fun `a local row's scan does not`() = runBlocking {
        assertNull(seen("192.168.1.20", bindOutside = true)); assertEquals(0, lookups)
    }
    @Test fun `bindOutside false is unbound for any target`() = runBlocking {
        for (h in listOf("10.0.4.7", "192.168.1.20", "8.8.8.8")) assertNull(h, seen(h, bindOutside = false))
        assertEquals(0, lookups)
    }
    @Test fun `bindOutside with no Wi-Fi network to bind fails the scan instead of leaving it unbound`() = runBlocking {
        try { seen("10.0.4.7", bindOutside = true, b = { null }); fail("expected BindFailedException") } catch (_: BindFailedException) { }
    }
    @Test fun `with no env the block runs unbound`() = runBlocking {
        assertNull(ScanBinding.run(ip("10.0.4.7"), null, null, true, binders) { currentBinder() }); assertEquals(0, lookups)
    }
    @Test fun `aggressive off with one manual range binds a target outside the home range`() = runBlocking {
        val withManual = RunSettings(aggressive = false, maxPrefix = 22, manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!))
        assertSame(FakeBinder, ScanBinding.forRun(ip("10.0.4.7"), env, withManual, binders) { currentBinder() })
        assertNull(ScanBinding.forRun(ip("192.168.1.20"), env, withManual, binders) { currentBinder() })
        assertNull("no manual ranges and Aggressive off: the MVP, unbound", ScanBinding.forRun(ip("10.0.4.7"), env, RunSettings(false, 22, emptyList()), binders) { currentBinder() })
    }
    @Test fun `aggressive on with a wide mask binds outside the limit block only`() = runBlocking {
        val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
        val on = RunSettings(aggressive = true, maxPrefix = 22, manual = emptyList())
        assertNull(ScanBinding.forRun(ip("172.20.10.7"), env16, on, binders) { currentBinder() })        // inside 172.20.8.0/22
        assertSame(FakeBinder, ScanBinding.forRun(ip("172.20.30.5"), env16, on, binders) { currentBinder() })
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.netenv.HomeRangeTest" --tests "com.northwalessolutions.netprobe.ui.home.RunSettingsTest" --tests "com.northwalessolutions.netprobe.ui.home.ScanBindingTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'HomeRange'`, `'RunSettings'`, `'forRun'`, `No parameter with name 'aggressive'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/netenv/Subnet.kt`: add at the end of the file:
```kotlin
/**
 * The one place the swept local range ("home range") is computed (3C spec 2.1, D19). Aggressive scan off: exactly
 * SubnetMath.sweepRange (a wider mask sweeps the phone's /24). Aggressive scan on: a wider mask sweeps the limit-sized block that
 * holds the phone, so a client /16 at the widest limit sweeps its /20 (controller ruling), never the whole /16.
 */
object HomeRange {
    fun of(ip: Inet4Address, prefix: Int, maxPrefix: Int, aggressive: Boolean): SweepRange =
        if (!aggressive || prefix >= maxPrefix) SubnetMath.sweepRange(ip, prefix, maxPrefix)
        else SweepRange(SubnetMath.of(ip, maxPrefix), clamped = true, originalPrefix = prefix)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RunSettings.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SweepRange

/**
 * What a scan run reads once at its start (3C spec 2.2, D21): Aggressive scan, the Large networks limit and this network's manual
 * ranges. The run's home range, binding and grouping use only this, so a toggle or a reset mid-run affects the next run only.
 */
data class RunSettings(val aggressive: Boolean, val maxPrefix: Int, val manual: List<Subnet>) {
    /** Rows outside the home range are bound to Wi-Fi when this holds (spec 2.4); with neither, binding is the MVP's (unbound). */
    val bindOutside: Boolean get() = aggressive || manual.isNotEmpty()
    fun homeRange(env: NetworkEnv): SweepRange = HomeRange.of(env.interfaceIp, env.prefixLength, maxPrefix, aggressive)
    fun home(env: NetworkEnv): Subnet = homeRange(env).subnet

    companion object {
        fun of(s: SettingsState, manual: List<Subnet>): RunSettings = RunSettings(s.extEnabled, s.maxPrefix, manual)
        /** The last radar run's snapshot when this network has one in memory, else the live settings (spec 2.2). */
        fun effective(snapshot: RunSettings?, live: SettingsState, liveManual: List<Subnet>): RunSettings = snapshot ?: of(live, liveManual)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ScanBinding.kt` (whole file):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Port scans and tool runs of rows outside the home range run bound to the Wi-Fi network when the run binds outside (Aggressive scan
 * on, or manual ranges set: 3C spec 2.4), so a VPN that routes private ranges never carries them to its far end (extended spec 5.5,
 * final review I2). Local rows and the bindOutside-false path are the MVP: unbound. No network to bind is a [BindFailedException],
 * never a silent unbound scan.
 */
object ScanBinding {
    suspend fun <T> run(ip: Inet4Address, env: NetworkEnv?, home: Subnet?, bindOutside: Boolean,
                        binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T {
        if (!bindOutside || env == null || home == null) return block()
        if (home.contains(ip)) return block()
        val b = binderFor(env) ?: throw BindFailedException(IllegalStateException("no Wi-Fi or Ethernet network carries ${env.interfaceIp.hostAddress}"))
        return withContext(BoundNetwork(b)) { block() }
    }

    /** The form every caller uses: the run's snapshot decides the home range and whether to bind (D21). */
    suspend fun <T> forRun(ip: Inet4Address, env: NetworkEnv?, runSettings: RunSettings, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T =
        run(ip, env, env?.let { runSettings.home(it) }, runSettings.bindOutside, binderFor, block)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
1. Add `import com.northwalessolutions.netprobe.netenv.HomeRange` after `import com.northwalessolutions.netprobe.netenv.NetworkEnv`.
2. Replace `RowRules.row`'s signature and its `scanned` line:
```kotlin
    fun row(h: HostEntity, env: NetworkEnv?, maxPrefix: Int, openPorts: Int, stale: Boolean,
            extraRanges: List<Subnet> = emptyList(), manualRanges: List<Subnet> = emptyList(), aggressive: Boolean = false): HostRow {
        val own = isOwn(h.ip, env)
        val name = if (own && h.customName.isNullOrBlank()) THIS_PHONE else DisplayName.of(h)
        val scanned = env?.let { listOf(HomeRange.of(it.interfaceIp, it.prefixLength, maxPrefix, aggressive).subnet) + extraRanges }.orEmpty()
```
(the rest of `row` is unchanged).
3. In `quickScanAllBody`, replace
```kotlin
            val net = networkId.value ?: return
            QuickScanAllLoop.run(
```
with
```kotlin
            val net = networkId.value ?: return
            // One settings read per Quick scan all (3C spec D21): a toggle mid-run never changes which hosts are bound.
            val runSettings = RunSettings.of(c.settings.state.value, c.settings.manual.forNetwork(net))
            QuickScanAllLoop.run(
```
and replace `try { quickScanHost(h, e) }` with `try { quickScanHost(h, e, runSettings) }`.
4. Replace `quickScanHost`:
```kotlin
    private suspend fun quickScanHost(h: HostEntity, e: NetworkEnv, runSettings: RunSettings) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        ScanBinding.forRun(ip, e, runSettings, c.networkEnvMonitor::binderFor) { quickScanHostOn(h, ip) }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: add `import com.northwalessolutions.netprobe.ui.home.RunSettings` after `import com.northwalessolutions.netprobe.ui.home.RowRules`, and replace `scanHost`:
```kotlin
    private suspend fun scanHost(h: HostEntity, profile: ScanProfile, e: NetworkEnv) {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        // One settings read per scan (3C spec D21); manual ranges bind off-subnet rows even with Aggressive scan off (spec 2.4).
        val runSettings = RunSettings.of(c.settings.state.value, c.settings.manual.forNetwork(h.networkId))
        ScanBinding.forRun(ip, e, runSettings, c.networkEnvMonitor::binderFor) { scanHostOn(h, profile, ip) }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add `import com.northwalessolutions.netprobe.ui.home.RunSettings` after `import com.northwalessolutions.netprobe.ui.home.ScanBinding`, and in `toolRunner` replace
```kotlin
            bind = { ip, e, block -> ScanBinding.run(ip, e, settings.state.value.maxPrefix, settings.state.value.extEnabled, networkEnvMonitor::binderFor) { block() } },
```
with
```kotlin
            bind = { ip, e, block ->
                // Read once at the run's start (3C spec D21): manual ranges bind off-subnet targets even with Aggressive scan off (spec 2.4).
                val runSettings = RunSettings.of(settings.state.value, settings.manual.forNetwork(currentNetwork.value?.id))
                ScanBinding.forRun(ip, e, runSettings, networkEnvMonitor::binderFor) { block() }
            },
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt`: the manual editor's own-network check follows the live home range. Replace
```kotlin
    val local = remember(network?.id, s.maxPrefix) {   // re-read on a network switch (Task 17 M2)
        container.networkEnvMonitor.current()?.let { SubnetMath.sweepRange(it.interfaceIp, it.prefixLength, s.maxPrefix).subnet } }
```
with
```kotlin
    val local = remember(network?.id, s.maxPrefix, s.extEnabled) {   // re-read on a network switch (Task 17 M2) and when the home range changes (3C spec 2.1)
        container.networkEnvMonitor.current()?.let { HomeRange.of(it.interfaceIp, it.prefixLength, s.maxPrefix, s.extEnabled).subnet } }
```
and replace `import com.northwalessolutions.netprobe.netenv.SubnetMath` with `import com.northwalessolutions.netprobe.netenv.HomeRange`.

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.netenv.*" --tests "com.northwalessolutions.netprobe.ui.home.*" --tests "com.northwalessolutions.netprobe.engine.tools.*" --console=plain 2>&1 | tail -20
grep -rn "ScanBinding.run(" app/src/main --include=*.kt
```
Expected: `BUILD SUCCESSFUL`; the grep finds only `ScanBinding.kt` itself (`forRun` calls `run`). `BindingPropagationTest` (3B) still passes: it drives `BoundNetwork` directly and never called the old signature.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/netenv/Subnet.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/RunSettings.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/ScanBinding.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/test/java/com/northwalessolutions/netprobe/netenv/HomeRangeTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunSettingsTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/ScanBindingTest.kt
git status --short
git commit -m "Phase 3C-1: HomeRange mask rule, RunSettings snapshot, ScanBinding forRun"
```

---

### Task 5: The engine side: the home range in the local sweep, ExtendedOptions.evidence, and ExtendedOptionsOf (manual ranges always swept)

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt`, `engine/discovery/DiscoveryEngine.kt`, `ui/home/RunSettings.kt` (add `ExtendedOptionsOf`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedOptionsOfTest.kt`; additions to `engine/discovery/DiscoveryEngineExtendedTest.kt`

**Interfaces:**
- Consumes: `HomeRange`, `RunSettings` (Task 4), `SettingsState` (Task 1), `CandidateRanges.derive`, `Evidence.dnsServers` (Phase 2).
- Produces:
```kotlin
data class ExtendedOptions(val enabled: Boolean = false, val maxRanges: Int = 4, val gatewayProbe: Boolean = false, val manual: List<Subnet> = emptyList(),
                           val knownIps: Set<String> = emptySet(), val evidence: Boolean = true)      // evidence appended last: positional calls compile
data class DiscoveryOptions(..., val extended: ExtendedOptions = ExtendedOptions(), val aggressive: Boolean = false)   // aggressive appended last
object ExtendedOptionsOf { fun from(runSettings: RunSettings, s: SettingsState, known: Set<String>): ExtendedOptions }   // ui.home
```
- Behaviour (spec 2.4): Aggressive scan on gives today's options. Aggressive scan off with manual ranges gives `enabled = true, gatewayProbe = false, evidence = false`: the extended phase runs, bound, for the typed ranges only (no DNS-server evidence, no sighting evidence, no TEST-NET pre-check, no Smart discovery). Aggressive scan off with no manual ranges gives `ExtendedOptions()`, the MVP path. The local sweep uses `HomeRange.of(..., options.aggressive)`. With `enabled = true`, the neighbour table goes through Phase 2's `NeighbourRules` (as with Aggressive scan on); recorded as deviation 3 in the Self-Review Notes.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedOptionsOfTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions
import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3C spec 8.1 item 6. */
class ExtendedOptionsOfTest {
    private val manual = listOf(CidrParser.subnetOf("10.0.4.0/24")!!)
    private val known = setOf("10.0.3.5")

    @Test fun `aggressive on with smart discovery off carries the manual list and evidence`() {
        val o = ExtendedOptionsOf.from(RunSettings(true, 22, manual), SettingsState(extEnabled = true, extMax = 6, extGatewayProbe = false), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 6, gatewayProbe = false, manual = manual, knownIps = known, evidence = true), o)
    }
    @Test fun `aggressive on with smart discovery on probes`() {
        val o = ExtendedOptionsOf.from(RunSettings(true, 22, emptyList()), SettingsState(extEnabled = true, extGatewayProbe = true), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 4, gatewayProbe = true, manual = emptyList(), knownIps = known, evidence = true), o)
    }
    @Test fun `aggressive off with a manual list sweeps only the list`() {
        val o = ExtendedOptionsOf.from(RunSettings(false, 22, manual), SettingsState(extEnabled = false, extMax = 6, extGatewayProbe = true), known)
        assertEquals(ExtendedOptions(enabled = true, maxRanges = 6, gatewayProbe = false, manual = manual, knownIps = known, evidence = false), o)
    }
    @Test fun `aggressive off with no manual ranges is ExtendedOptions()`() {
        assertEquals(ExtendedOptions(), ExtendedOptionsOf.from(RunSettings(false, 22, emptyList()), SettingsState(extGatewayProbe = true, extMax = 9), known))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt`: add these imports after `import kotlin.random.Random`:
```kotlin
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.ui.home.ExtendedOptionsOf
import com.northwalessolutions.netprobe.ui.home.RunSettings
```
and add these members before the class's closing brace:
```kotlin
    // 3C-1 (spec 8.1 item 7): what ExtendedOptionsOf gives the engine for each Aggressive scan state.
    private fun runOpts(aggressive: Boolean, manual: List<String>, probe: Boolean = true, cap: Int = 4) = DiscoveryOptions(
        extended = ExtendedOptionsOf.from(RunSettings(aggressive, 22, manual.map(::net)), SettingsState(extEnabled = aggressive, extMax = cap, extGatewayProbe = probe), emptySet()),
        aggressive = aggressive)
    private val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), "Client", null, Transport.WIFI)

    @Test fun `aggressive off with one manual range sweeps only that range, bound, with no evidence and no probe`() = runBlocking {
        val sighting = MdnsRecord(ip("10.0.6.5"), "x", "_http._tcp", 80, emptyMap(), emptyList())
        val eng = engine(mdns = listOf(sighting), gatewayHits = listOf("10.0.10.0/24"))
        eng.hosts(runOpts(aggressive = false, manual = listOf("10.0.4.0/24")), e = env.copy(dnsServers = listOf(ip("10.0.9.53"))))
        assertEquals(listOf("10.0.3.1 bound=false", "10.0.4.1 bound=true"), log.toList())
        assertTrue("Smart discovery never runs with Aggressive scan off", gatewayCalls.isEmpty())
        assertTrue("no TEST-NET pre-check", canaryCalls.none { it.startsWith("192.0.2.1") || it.startsWith("198.51.100.1") })
        assertEquals(listOf(null, RangeSource.MANUAL), eng.status.value.ranges.map { it.source })
        assertTrue(eng.status.value.notes.isEmpty())
    }
    @Test fun `aggressive on with smart discovery off sweeps the manual range first and never probes`() = runBlocking {
        val eng = engine(gatewayHits = listOf("10.0.10.0/24"))
        eng.hosts(runOpts(aggressive = true, manual = listOf("10.0.4.0/24"), probe = false), e = env.copy(dnsServers = listOf(ip("10.0.9.53"))))
        assertEquals(listOf("10.0.3.1", "10.0.4.1", "10.0.9.1"), swept())   // manual first, then the evidence range
        assertTrue(gatewayCalls.isEmpty())
    }
    @Test fun `aggressive off with no manual ranges is the toggle off result`() = runBlocking {   // Review Focus 1, Phase 2 12.2 regression
        val eng = engine(alive = { it.hostAddress == "10.0.3.5" })
        val h = eng.hosts(runOpts(aggressive = false, manual = emptyList()))
        assertEquals(listOf("10.0.3.1"), swept()); assertTrue(gatewayCalls.isEmpty()); assertTrue(canaryCalls.isEmpty())
        assertEquals(setOf("10.0.3.1", "10.0.3.5"), h.keys)
        assertEquals(listOf(RangeState.DONE), eng.states()); assertTrue(eng.status.value.notes.isEmpty()); assertEquals(254, eng.status.value.total)
    }
    @Test fun `aggressive on sweeps the limit block of a wide mask and the probe excludes it`() = runBlocking {
        val eng = engine()
        eng.hosts(DiscoveryOptions(maxPrefix = 22, aggressive = true, extended = ExtendedOptions(enabled = true, gatewayProbe = true)), e = env16)
        assertEquals("172.20.8.1", swept().first())
        assertEquals(1022, eng.status.value.total); assertEquals(16, eng.status.value.clampedFrom)
        assertEquals("172.20.8.0/22", gatewayCalls.single().exclude.toString())
    }
    @Test fun `aggressive off keeps the phone's slash 24 on the same wide mask`() = runBlocking {
        val eng = engine()
        eng.hosts(DiscoveryOptions(maxPrefix = 22), e = env16)
        assertEquals(listOf("172.20.9.1"), swept()); assertEquals(254, eng.status.value.total); assertEquals(16, eng.status.value.clampedFrom)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.ExtendedOptionsOfTest" --tests "com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngineExtendedTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ExtendedOptionsOf'`, `No parameter with name 'evidence'`, `No parameter with name 'aggressive'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt`: replace `ExtendedOptions` and `DiscoveryOptions`:
```kotlin
/** Extended discovery inputs (extended spec 5.1). [knownIps] are the network's stored IPs, used to pick canary addresses (spec 4.7). */
data class ExtendedOptions(
    val enabled: Boolean = false,
    val maxRanges: Int = 4,                  // coerced to 1..16 by CandidateRanges.derive
    val gatewayProbe: Boolean = false,
    val manual: List<Subnet> = emptyList(),  // the current network's list (spec 4.4)
    val knownIps: Set<String> = emptySet(),
    /** False with Aggressive scan off and manual ranges set: no DNS-server or sighting evidence becomes a range (3C spec 2.4). */
    val evidence: Boolean = true,
)

data class DiscoveryOptions(
    val mdns: Boolean = true, val ssdp: Boolean = true, val netbios: Boolean = true, val reverseDns: Boolean = true,
    val snmp: Boolean = true, val httpTitle: Boolean = true,
    val sweepConcurrency: Int = 128, val connectTimeoutMs: Int = 600, val maxPrefix: Int = 22, val mdnsDurationMs: Long = 6000,
    val extended: ExtendedOptions = ExtendedOptions(),
    /** Aggressive scan: a mask wider than [maxPrefix] sweeps the limit-sized block that holds the phone (3C spec 2.1, HomeRange). */
    val aggressive: Boolean = false,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt`:
1. Add `import com.northwalessolutions.netprobe.netenv.HomeRange` after `import com.northwalessolutions.netprobe.engine.neigh.NeighbourTable`.
2. In `discover`, replace `val range = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, options.maxPrefix)` with
```kotlin
        val range = HomeRange.of(env.interfaceIp, env.prefixLength, options.maxPrefix, options.aggressive)   // the one home-range rule (3C spec 2.1)
```
3. In `extendedPhase`, replace
```kotlin
                val highPort = RandomHighPort.pick(random)   // one per run, for the TEST-NET pre-check and every canary (spec 4.3, 4.7)
                Evidence.dnsServers(env, local).forEach { noteEvidence(it) }
```
with
```kotlin
                val highPort = RandomHighPort.pick(random)   // one per run, for the TEST-NET pre-check and every canary (spec 4.3, 4.7)
                // Aggressive scan off with manual ranges: the typed ranges only, never an evidence range (3C spec 2.4).
                if (ext.evidence) Evidence.dnsServers(env, local).forEach { noteEvidence(it) }
                fun evidenceNow(): List<Inet4Address> = if (ext.evidence) synchronized(evidence) { evidence.toList() } else emptyList()
```
and replace
```kotlin
                val cand = CandidateRanges.derive(local, ext.manual, synchronized(evidence) { evidence.toList() }, hits, ext.maxRanges)
```
with
```kotlin
                val cand = CandidateRanges.derive(local, ext.manual, evidenceNow(), hits, ext.maxRanges)
```
`SubnetMath` stays imported (the neighbour rules and the canary still use it).

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RunSettings.kt`: add `import com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions` and, at the end of the file:
```kotlin
/** The engine's extended options for one run (3C spec 2.4), pure, extracted from HomeViewModel.discoverOn. */
object ExtendedOptionsOf {
    fun from(runSettings: RunSettings, s: SettingsState, known: Set<String>): ExtendedOptions = when {
        runSettings.aggressive -> ExtendedOptions(enabled = true, maxRanges = s.extMax, gatewayProbe = s.extGatewayProbe, manual = runSettings.manual,
            knownIps = known, evidence = true)
        // Aggressive scan off, manual ranges set: swept on every scan, bound to Wi-Fi, nothing else (D8, ruling 2).
        runSettings.manual.isNotEmpty() -> ExtendedOptions(enabled = true, maxRanges = s.extMax, gatewayProbe = false, manual = runSettings.manual,
            knownIps = known, evidence = false)
        else -> ExtendedOptions()   // exactly the MVP path (Phase 2 check 1)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.ExtendedOptionsOfTest" --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures; every Phase 2 test in `DiscoveryEngineExtendedTest` and `DiscoveryEngineTest` is still green (the positional `ExtendedOptions(enabled, cap, probe, manual, known)` in `opts()` compiles because `evidence` is last and defaults to true).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/RunSettings.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedOptionsOfTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt
git status --short
git commit -m "Phase 3C-1: engine home range, evidence flag, manual ranges swept with Aggressive scan off"
```

---

### Task 6: Home wiring: the radar run snapshot, grouping and the hero from it, manual-range summaries and notices, and the partial large-network notice

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt` (whole file), `ui/home/HomeViewModel.kt`, `ui/home/HomeContent.kt` (one line)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunSnapshotTest.kt`; additions to `ui/home/RunEndTest.kt`

**Interfaces:**
- Consumes: `RunSettings`, `ScanBinding.forRun`, `RowRules.row(aggressive)` (Task 4), `ExtendedOptionsOf` (Task 5), `HomeCopy.largeNetworkPartial` (Task 3), `RangeSummaryText`, `LastRun`, `ScanFinish` (Phase 2, 3A).
- Produces:
```kotlin
object ExtendedNotices { fun of(status: DiscoveryStatus, aggressive: Boolean = true): List<String> }
data class RunEnd(...) { companion object { fun of(currentNetworkId: Long?, runNetworkId: Long, extEnabled: Boolean, completed: Boolean, status: DiscoveryStatus,
                                                   aggressive: Boolean = extEnabled): RunEnd? } }
data class RunMemory(val finishedAt: Long?, val swept: List<Subnet>, val progress: List<RangeProgress>, val settings: RunSettings? = null)
HomeUiState.homeRange: String? = null       // the home range of the effective RunSettings, for the hero subtitle (spec 2.1)
```
- Behaviour: `discoverOn` builds one `RunSettings` at its start, keeps it in `runSnapshot` (memory only, cleared on a network change through `RunMemory`), builds `ExtendedOptions` with `ExtendedOptionsOf.from`, passes `aggressive` to the engine, and ends with `RunEnd.of(..., aggressive = runSettings.aggressive)`. `baseState` groups with `RunSettings.effective(runSnapshot, live settings, live manual)` and now also recomposes on `c.settings.state`. The range summary and per-range notices show whenever the run's `ExtendedOptions.enabled` was true (manual ranges with Aggressive scan off included); "Aggressive scan: no extra ranges to sweep" only when Aggressive scan was on.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunSnapshotTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** 3C spec 8.1 item 4, Review Focus 4: a run uses only the settings it started with. */
class RunSnapshotTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val env16 = NetworkEnv(ip("172.20.9.40"), 16, ip("172.20.0.1"), emptyList(), null, null, Transport.WIFI)
    private val host = TestHosts.host(ip = "172.20.9.7")

    @Test fun `a run started with Aggressive off keeps its binding, home range and grouping after a mid run change`() = runBlocking {
        val started = RunSettings.of(SettingsState(), emptyList())                         // Aggressive scan off when Scan was tapped
        val switchedOn = SettingsState(extEnabled = true, maxPrefix = 20)                   // the Director turns it on mid-run
        for (live in listOf(switchedOn, SettingsDefaults.reset(switchedOn))) {               // and then resets Settings mid-run
            val eff = RunSettings.effective(started, live, emptyList())
            assertSame(started, eff)
            assertEquals("172.20.9.0/24", eff.home(env16).toString())
            assertNull("still unbound", ScanBinding.forRun(ip("172.20.30.5"), env16, eff, { FakeBinder }) { currentBinder() })
            val row = RowRules.row(host, env16, eff.maxPrefix, 0, false, manualRanges = eff.manual, aggressive = eff.aggressive)
            assertEquals("172.20.9.0/24" to false, row.subnet to row.offSubnet)
        }
        val next = RunSettings.effective(null, switchedOn, emptyList())                     // the next run reads the new values
        assertEquals("172.20.0.0/20", next.home(env16).toString())
        assertSame(FakeBinder, ScanBinding.forRun(ip("10.9.0.5"), env16, next, { FakeBinder }) { currentBinder() })
        assertEquals("172.20.0.0/20", RowRules.row(host, env16, next.maxPrefix, 0, false, aggressive = next.aggressive).subnet)
    }
    @Test fun `a network change drops the snapshot, the same network keeps it`() {
        val mem = RunMemory(finishedAt = 500L, swept = emptyList(), progress = emptyList(), settings = RunSettings(true, 22, emptyList()))
        assertNull(mem.afterNetworkChange(1L, 2L).settings)
        assertEquals(mem, mem.afterNetworkChange(1L, 1L))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunEndTest.kt`: add these tests before the closing brace (3C spec 8.1 item 8):
```kotlin
    @Test fun `aggressive off with a manual range shows its summary but never the aggressive note`() {
        val manualOnly = DiscoveryStatus(notes = listOf(ExtendedNote.NO_EXTRA_RANGES),
            ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 3)))
        val e = RunEnd.of(1L, 1L, extEnabled = true, completed = true, status = manualOnly, aggressive = false)!!
        assertEquals(listOf("10.0.4.0/24"), e.swept!!.map { it.toString() })
        assertNull("a range with hosts needs no notice, and NO_EXTRA_RANGES is Aggressive-only", e.notice)
        assertEquals("1 other range checked", RangeSummaryText.checked(e.progress!!))
        assertEquals(listOf("10.0.4.0/24  manual  swept, 3 found"), RangeSummaryText.lines(e.progress!!))
        assertEquals("Aggressive scan: no extra ranges to sweep", RunEnd.of(1L, 1L, true, true, manualOnly, aggressive = true)!!.notice)
    }
    @Test fun `aggressive off manual notices are nothing found, phantom, stopped and bind failed`() {
        for ((state, text) in listOf(RangeState.DONE to "10.0.4.0/24: nothing found", RangeState.PHANTOM to "10.0.4.0/24 skipped: every address answered",
                RangeState.CANCELLED to "Stopped at 10.0.4.0/24")) {
            val st = DiscoveryStatus(ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, state, 254, 254, 0)))
            assertEquals(text, RunEnd.of(1L, 1L, true, true, st, aggressive = false)!!.notice)
        }
        val bind = DiscoveryStatus(notes = listOf(ExtendedNote.SKIPPED_BIND_FAILED), ranges = listOf(local))
        assertEquals("Other ranges skipped: could not bind to Wi-Fi", RunEnd.of(1L, 1L, true, true, bind, aggressive = false)!!.notice)
        val capped = DiscoveryStatus(truncatedRanges = 1, ranges = listOf(local, RangeProgress("10.0.4.0/24", RangeSource.MANUAL, RangeState.DONE, 254, 254, 2),
            RangeProgress("10.0.5.0/24", RangeSource.MANUAL, RangeState.TRUNCATED, 0, 254, 0)))
        assertEquals("1 more range found, raise Maximum extra ranges in Settings", RunEnd.of(1L, 1L, true, true, capped, aggressive = false)!!.notice)
    }
    @Test fun `the large network notice follows the run's snapshot`() {
        val clamped22 = DiscoveryStatus(clampedFrom = 16, ranges = listOf(RangeProgress("172.20.8.0/22", null, RangeState.DONE, 1022, 1022, 0)))
        assertEquals("Large network (/16). Scanned your /22 only. Widen in Settings.", RunEnd.of(1L, 1L, true, true, clamped22, aggressive = true)!!.notice)
        val clamped24 = DiscoveryStatus(clampedFrom = 16, ranges = listOf(RangeProgress("172.20.9.0/24", null, RangeState.DONE, 254, 254, 0)))
        assertEquals("Large network (/16). Scanned your /24 only. Widen in Settings.", RunEnd.of(1L, 1L, false, true, clamped24, aggressive = false)!!.notice)
        assertNull(RunEnd.of(1L, 1L, true, false, clamped22, aggressive = true)!!.notice)   // Stop, failure or network change: no notice
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.RunSnapshotTest" --tests "com.northwalessolutions.netprobe.ui.home.RunEndTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `No parameter with name 'settings'` and `No parameter with name 'aggressive'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt` (whole file; `RangeSummaryText` and `LastRun` are unchanged):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.discovery.ExtendedNote
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.engine.discovery.RangeSource
import com.northwalessolutions.netprobe.engine.discovery.RangeState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet

/** End-of-run notices for the notice line (Radar spec 4.4 and section 8). Ranges with hosts need no notice: their group says it. */
object ExtendedNotices {
    private const val MAX_RANGE_LINES = 2

    /**
     * [aggressive] false means the run swept manual ranges only (3C spec 2.4): every per-range notice and the bind failure still show,
     * the Aggressive and Smart discovery notes never do.
     */
    fun of(status: DiscoveryStatus, aggressive: Boolean = true): List<String> {
        val out = ArrayList<String>()
        status.notes.filter { aggressive || it == ExtendedNote.SKIPPED_BIND_FAILED }.forEach { n ->
            out += when (n) {
                ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL -> HomeCopy.GATEWAY_ANSWERS_ALL
                ExtendedNote.GATEWAY_PROBE_FOUND_NONE -> HomeCopy.GATEWAY_FOUND_NONE
                ExtendedNote.NO_EXTRA_RANGES -> HomeCopy.NO_EXTRA_RANGES
                ExtendedNote.SKIPPED_BIND_FAILED -> HomeCopy.BIND_FAILED
            }
        }
        val extras = status.ranges.drop(1)
        // The notice sits in the pinned header: at most MAX_RANGE_LINES per-range lines, the rest rolled up one line per kind.
        val quiet = extras.filter { it.state == RangeState.PHANTOM || (it.state == RangeState.DONE && it.found == 0) }
        quiet.take(MAX_RANGE_LINES).forEach { r -> out += if (r.state == RangeState.PHANTOM) HomeCopy.phantom(r.subnet) else HomeCopy.nothingFound(r.subnet) }
        val rest = quiet.drop(MAX_RANGE_LINES)
        rest.count { it.state == RangeState.PHANTOM }.takeIf { it > 0 }?.let { out += HomeCopy.othersAnsweredAll(it) }
        rest.count { it.state == RangeState.DONE }.takeIf { it > 0 }?.let { out += HomeCopy.othersNothingFound(it) }
        extras.firstOrNull { it.state == RangeState.CANCELLED }?.let { out += HomeCopy.cancelled(it.subnet) }   // the one that was running
        if (status.truncatedRanges > 0) out += HomeCopy.capDropped(status.truncatedRanges)
        return out
    }
}

/** The done summary's range list (extended spec 8.2): a headline count and one line per extra range with source, outcome and found count. */
object RangeSummaryText {
    fun headline(otherRanges: Int): String = HomeCopy.rangesChecked(otherRanges)

    /** The headline counts ranges actually swept; skipped, capped and stopped ranges appear only in the expanded lines. Null when none was swept (M4). */
    fun checked(ranges: List<RangeProgress>): String? = ranges.drop(1).count { it.state == RangeState.DONE }.takeIf { it > 0 }?.let(::headline)

    /** [ranges] is the status list with the local range first; the local range is not listed. */
    fun lines(ranges: List<RangeProgress>): List<String> = ranges.drop(1).map { r ->
        val source = when (r.source) {
            RangeSource.MANUAL -> HomeCopy.SOURCE_MANUAL
            RangeSource.EVIDENCE -> HomeCopy.SOURCE_EVIDENCE
            RangeSource.GATEWAY_PROBE -> HomeCopy.SOURCE_GATEWAY
            null -> HomeCopy.SOURCE_LOCAL
        }
        val outcome = when (r.state) {
            RangeState.DONE -> HomeCopy.outcomeSwept(r.found)
            RangeState.PHANTOM -> HomeCopy.OUTCOME_PHANTOM
            RangeState.TRUNCATED -> HomeCopy.OUTCOME_CAPPED
            RangeState.CANCELLED, RangeState.RUNNING -> HomeCopy.OUTCOME_STOPPED   // RUNNING never survives the engine's finally
            RangeState.PENDING -> HomeCopy.OUTCOME_NOT_STARTED
        }
        HomeCopy.rangeLine(r.subnet, source, outcome)
    }
}

/** The last run's swept extra ranges, kept in view-model memory only (extended spec 5.6), for display-time grouping. */
object LastRun {
    fun sweptRanges(status: DiscoveryStatus): List<Subnet> =
        status.ranges.drop(1).filter { it.state == RangeState.DONE }.mapNotNull { CidrParser.subnetOf(it.subnet) }
}

/** What one run publishes when it ends, or null when its network is no longer current (a slow unwind must not touch the new network's state). */
data class RunEnd(val swept: List<Subnet>?, val progress: List<RangeProgress>?, val notice: String?) {
    companion object {
        private const val FALLBACK_PREFIX = 24

        /**
         * [completed] is true only for a scan that ran to its end: the large-network notice is not shown after Stop, failure or a network
         * change. [extEnabled] is the run's ExtendedOptions.enabled (Aggressive scan on, or manual ranges set); [aggressive] is the run's
         * snapshot (3C spec 2.1, 2.4).
         */
        fun of(currentNetworkId: Long?, runNetworkId: Long, extEnabled: Boolean, completed: Boolean, status: DiscoveryStatus,
               aggressive: Boolean = extEnabled): RunEnd? {
            if (currentNetworkId != runNetworkId) return null
            val large = status.clampedFrom?.takeIf { completed }?.let { from ->
                if (aggressive) HomeCopy.largeNetworkPartial(from, sweptPrefix(status)) else HomeCopy.largeNetwork(from)
            }
            val notices = listOfNotNull(large) + if (extEnabled) ExtendedNotices.of(status, aggressive) else emptyList()
            return RunEnd(if (extEnabled) LastRun.sweptRanges(status) else null, if (extEnabled) status.ranges else null, notices.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }

        /** The local range is ranges[0] (for example "172.20.8.0/22"); its prefix is what was swept. */
        private fun sweptPrefix(status: DiscoveryStatus): Int =
            status.ranges.firstOrNull()?.subnet?.substringAfterLast('/')?.toIntOrNull() ?: FALLBACK_PREFIX
    }
}

/** The per-network memory of the last run (finish time, the extended range lists and the run's settings): all of it belongs to one network. */
data class RunMemory(val finishedAt: Long?, val swept: List<Subnet>, val progress: List<RangeProgress>, val settings: RunSettings? = null) {
    fun afterNetworkChange(previousNetworkId: Long?, networkId: Long?): RunMemory =
        if (previousNetworkId == networkId) this
        else RunMemory(ScanFinish.afterNetworkChange(previousNetworkId, networkId, finishedAt), emptyList(), emptyList(), null)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
1. `HomeUiState`: append `val homeRange: String? = null` after `val toolRunningOnNetwork: Boolean = false`:
```kotlin
                       val appMessage: String? = null, val appMessageId: Long? = null, val toolRunningOnNetwork: Boolean = false,
                       val homeRange: String? = null)
```
2. After `private val lastRunCompleted = MutableStateFlow(true)` add:
```kotlin
    /** The last radar run's settings (3C spec 2.2, D21): grouping and the hero read it, so a mid-run toggle or reset never regroups the running list. Memory only; a network change clears it. */
    private val runSnapshot = MutableStateFlow<RunSettings?>(null)
```
3. Replace the whole `baseState` declaration with:
```kotlin
    private val baseState: Flow<HomeUiState> = combine(env, controller.state, c.discoveryEngine.status, hosts, filter, query, latestRun, quick.state, extraMessage, portCounts,
        finishedAt, lastRunRanges, c.settings.manual.revision, lastRunProgress, lastRunCompleted, networkId, c.undo.pending, runSnapshot, c.settings.state) { arr ->
        @Suppress("UNCHECKED_CAST")
        val hs = arr[3] as List<HostEntity>
        @Suppress("UNCHECKED_CAST")
        val pc = arr[9] as Map<Long, Int>
        @Suppress("UNCHECKED_CAST")
        val extra = arr[11] as List<Subnet>
        @Suppress("UNCHECKED_CAST")
        val lastRun = arr[13] as List<RangeProgress>
        val e = arr[0] as NetworkEnv?; val cs = arr[1] as ScanController.State; val st = arr[2] as DiscoveryStatus
        val f = arr[4] as StatusFilter; val q = arr[5] as String; val run = arr[6] as Long; val qs = arr[7] as ScanController.State; val msg = arr[8] as String?
        val done = arr[10] as Long?
        // The last radar run's snapshot, else the live settings; manual ranges are read whatever Aggressive scan says (3C spec 2.2, D8).
        val runSettings = RunSettings.effective(arr[17] as RunSettings?, arr[18] as SettingsState, c.settings.manual.forNetwork(networkId.value))
        val all = hs.map { h -> RowRules.row(h, e, runSettings.maxPrefix, pc[h.id] ?: 0, stale = run > 0 && h.lastDiscoveryRun < run, extraRanges = extra,
            manualRanges = runSettings.manual, aggressive = runSettings.aggressive) }
        val stats = HomeStats.of(hs, all, done)
        val shown = HomeFilter.apply(all, f, q)
        HomeUiState(e, cs.scanning, if (cs.scanning) st else null, shown, f, q, cs.message ?: qs.message ?: msg, e?.ssid ?: e?.gateway?.hostAddress, qs.scanning,
            lastScanAt = stats.lastScanAt, totalHosts = stats.totalHosts, otherSubnetHosts = stats.otherSubnetHosts,
            scanStartedAt = run.takeIf { it > 0 }, scanFinishedAt = done, groups = HomeGroups.of(shown),
            lastRunRanges = lastRun, scanCompleted = arr[14] as Boolean,
            networkId = arr[15] as Long?, undoText = (arr[16] as PendingUndo?)?.text, homeRange = e?.let { runSettings.home(it).toString() })
    }
```
(`val run = arr[6] as Long` is the 3A name, left as it is; the new local is `runSettings`.)
4. In `init`, replace
```kotlin
                val kept = RunMemory(finishedAt.value, lastRunRanges.value, lastRunProgress.value).afterNetworkChange(networkId.value, net?.id)
                finishedAt.value = kept.finishedAt; lastRunRanges.value = kept.swept; lastRunProgress.value = kept.progress
```
with
```kotlin
                val kept = RunMemory(finishedAt.value, lastRunRanges.value, lastRunProgress.value, runSnapshot.value).afterNetworkChange(networkId.value, net?.id)
                finishedAt.value = kept.finishedAt; lastRunRanges.value = kept.swept; lastRunProgress.value = kept.progress; runSnapshot.value = kept.settings
```
5. In `discoverOn`, replace from `val s = c.settings.state.value` through `val binder = if (ext.enabled) c.networkEnvMonitor.binderFor(e) else null   // null skips the extended phase with its notice` with:
```kotlin
        val s = c.settings.state.value
        // One read at the start (3C spec D21): this run's home range, binding and grouping use only this snapshot.
        val runSettings = RunSettings.of(s, c.settings.manual.get(net.id))
        runSnapshot.value = runSettings
        // The stored IPs are read only when the extended phase will run (canary picks); the MVP path reads nothing extra.
        val known = if (runSettings.bindOutside) c.hosts.hostsForNetwork(net.id).first().map { it.ip }.toSet() else emptySet()
        val ext = ExtendedOptionsOf.from(runSettings, s, known)
        if (ext.enabled) extraMessage.value = null   // extended runs only: a notice stays until dismissed or the next scan starts (Radar spec 4.4)
        val opts = DiscoveryOptions(s.mdns, s.ssdp, s.netbios, s.reverseDns, s.snmp, s.httpTitle, s.sweepConcurrency, s.connectTimeoutMs, runSettings.maxPrefix,
            extended = ext, aggressive = runSettings.aggressive)
        val binder = if (ext.enabled) c.networkEnvMonitor.binderFor(e) else null   // null skips the extended phase with its notice
```
6. In the same function's `finally`, replace `RunEnd.of(networkId.value, net.id, ext.enabled, completed, st)?.let { end ->` with
```kotlin
            RunEnd.of(networkId.value, net.id, ext.enabled, completed, st, aggressive = runSettings.aggressive)?.let { end ->
```
The `ExtendedOptions` import becomes unused; remove `import com.northwalessolutions.netprobe.engine.discovery.ExtendedOptions`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`: replace
```kotlin
    val localSubnet = state.env?.let { SubnetMath.of(it.interfaceIp, it.prefixLength).toString() }
```
with
```kotlin
    // The hero names what a scan will sweep: the home range (3C spec 2.1), the mask only before the first state arrives.
    val localSubnet = state.homeRange ?: state.env?.let { SubnetMath.of(it.interfaceIp, it.prefixLength).toString() }
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` twice; `HomeContentTest` and the 3A Home tests compile unchanged (`homeRange` has a default).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
grep -n "settings.state.value" app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt
```
Expected: `BUILD SUCCESSFUL`; the grep finds exactly two lines, both at a run's start: `val s = c.settings.state.value` in `discoverOn` and `RunSettings.of(c.settings.state.value, ...)` in `quickScanAllBody`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunSnapshotTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/RunEndTest.kt
git status --short
git commit -m "Phase 3C-1: radar run snapshot, manual range summaries, partial large network notice"
```

---

### Task 7: Smart discovery's outward walk, its budget and the "too many seeds" note (STRONGER MODEL)

The stronger model implements and reviews this task. The walk is deterministic but easy to get subtly wrong: the round order, what counts as a miss, when a direction ends and when the budget stops further rounds all change which client ranges get swept and how many connection attempts a client's IDS sees.

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbe.kt` (whole file), `engine/discovery/CandidateRanges.kt` (`walkBudget`), `engine/discovery/DiscoveryOptions.kt` (`ExtendedNote`), `engine/discovery/DiscoveryEngine.kt` (the `gatewayProbe` type, its call and the production lambda), `ui/home/ExtendedNotices.kt` (one branch)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayWalkTest.kt`; modify `engine/discovery/GatewayProbeTest.kt` (one line), `engine/discovery/DiscoveryEngineExtendedTest.kt` (the fake and two tests), `ui/home/ExtendedNoticesTest.kt` (one test)

**Interfaces:**
- Consumes: `GatewayTargets`, `AnswersForEverything`, `RandomHighPort`, `Probe.Outcome` (Phase 2), `CandidateRanges.derive` (Phase 2), `HomeCopy.GATEWAY_WALK_SKIPPED` (Task 3), the engine's `evidenceNow()` (Task 5).
- Produces:
```kotlin
data class GatewayProbeResult(val hits: List<Subnet>, val answered: Set<Inet4Address> = emptySet(), val walkSkippedManySeeds: Boolean = false)
object GatewayProbe { const val WALK_MAX_SEEDS = 4; const val OUTWARD_MAX_UNITS = 16; const val OUTWARD_MISS_LIMIT = 2
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
                    onAnswer: (Inet4Address) -> Unit = {}, dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY),
                    walkBudget: (List<Subnet>) -> Int = { 0 }): GatewayProbeResult }        // was List<Subnet>
internal class OutwardWalk(seeds: List<Inet4Address>, probed: MutableSet<Subnet>, home: Subnet)
fun CandidateRanges.walkBudget(scanned: Subnet, manual: List<Subnet>, evidence: List<Inet4Address>, firstPassHits: List<Subnet>, cap: Int): Int
enum class ExtendedNote { ..., GATEWAY_WALK_SKIPPED_MANY }
DiscoveryEngine.gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?, (List<Subnet>) -> Int) -> GatewayProbeResult
```
- The walk, exactly as spec 2.5 steps 1 to 6:
  1. After the first pass, the answering seeds are the `targets.seeds` (outside the phone's enclosing /16, or all of them off RFC1918) that answered. More than `WALK_MAX_SEEDS` answering: no walk, `walkSkippedManySeeds = true` (engine note `GATEWAY_WALK_SKIPPED_MANY`). None answering: no walk.
  2. `B = walkBudget(firstPassHits)`, which the engine computes as `cap - derive(home, manual, evidence, firstPassHits, cap).ranges.size`. `B <= 0`: no walk.
  3. Directions: seeds in ascending address order, each "down" then "up" over the third octet within the seed's own /16, probing only the seed's last octet.
  4. Round `k` (1 to 16): each live direction offers its unit at distance `k`. A unit already probed (a first-pass unit, which includes every seed's unit, or one an earlier direction took) or overlapping the home range is skipped: neither a miss nor a hit, and the consecutive-miss count is unchanged. A direction ends at the octet boundary (third octet below 0 or above 255), after `OUTWARD_MISS_LIMIT` consecutive misses (a hit resets the count), or after distance 16. A round's units are probed concurrently with the same ports, timeout, semaphore, `connect` (so the same bound `Network`), answer rule and `onAnswer`.
  5. After each round its hits join `hits`. When the walk's hits reach `B`, no further round starts; extra hits of that round stay and `derive` reports them as truncated.
  6. `onProgress`'s total grows by each round's unit count before the round starts, under the same lock as `done`, so it never steps back; cancellation ends a round within one probe timeout (the round is a `coroutineScope`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayWalkTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.netenv.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.Executors

/** 3C spec 2.5 and 8.1 items 9 and 10: the first pass restated, then the outward walk's order, stop conditions, budget and bounds. */
class GatewayWalkTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private fun env(phone: String, gw: String) = NetworkEnv(ip(phone), 24, ip(gw), emptyList(), null, null, Transport.WIFI)
    private fun third(a: Inet4Address) = (SubnetMath.toInt(a) ushr 8) and 0xFF
    private val calls = Collections.synchronizedList(mutableListOf<String>())                     // "address:port", in call order
    private val progress = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())   // (done, total, attempts)
    private val executor = Executors.newSingleThreadExecutor()
    /** One thread and a connect that never suspends: units run in launch order, so the call order is the walk order. */
    private val oneThread = executor.asCoroutineDispatcher()
    @After fun stop() { executor.shutdownNow() }

    private fun answering(set: Set<String>): suspend (Inet4Address, Int) -> Probe.Outcome = { a, port ->
        calls += "${a.hostAddress}:$port"
        if (a.hostAddress in set) Probe.Outcome.Refused else Probe.Outcome.Timeout
    }
    private fun answeringWhen(rule: (Inet4Address) -> Boolean): suspend (Inet4Address, Int) -> Probe.Outcome = { a, port ->
        calls += "${a.hostAddress}:$port"
        if (rule(a)) Probe.Outcome.Refused else Probe.Outcome.Timeout
    }
    private fun firstPass(t: GatewayTargets): Set<String> = t.byUnit().values.flatten().map { it.hostAddress }.toSet()
    /** Addresses the walk probed, in first-call order (the first pass is filtered out). */
    private fun walked(t: GatewayTargets): List<String> {
        val fp = firstPass(t)
        return calls.map { it.substringBefore(':') }.filter { it !in fp }.distinct()
    }
    private suspend fun probe(t: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, budget: (List<Subnet>) -> Int): GatewayProbeResult =
        GatewayProbe.run(t, connect, onProgress = { d, total, n -> progress += Triple(d, total, n) }, dispatcher = oneThread, walkBudget = budget)

    private val phone192 = env("192.168.1.50", "192.168.1.1"); private val home192 = net("192.168.1.0/24")
    private val phone10 = env("10.0.3.20", "10.0.3.1"); private val home10 = net("10.0.3.0/24")

    @Test fun `the first pass for the Director's network is the spec's 255 units and 17 seeds`() {   // spec 8.1 item 9
        val t = GatewayTargets.of(phone10, home10)
        assertEquals(255, t.byUnit().keys.count { t.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
        val addresses = firstPass(t)
        listOf("10.0.0.1", "10.0.1.1", "10.0.2.1", "10.0.4.1", "10.0.255.254").forEach { assertTrue(it, it in addresses) }
        assertTrue(addresses.none { it.startsWith("10.0.3.") })
        assertNull("the gateway's .1 is already probed", t.gatewayOffset)
        assertEquals(listOf("10.0.4.1", "10.0.4.254"), t.byUnit()[net("10.0.4.0/24")]!!.map { it.hostAddress })
        assertEquals(listOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "172.16.0.1", "172.16.1.1", "172.16.16.1", "172.17.0.1", "172.31.0.1",
            "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1", "192.168.10.1", "192.168.88.1", "192.168.100.1", "192.168.178.1"), t.seeds.map { it.hostAddress })
        val wide = GatewayTargets.of(phone10, SubnetMath.of(ip("10.0.0.0"), 22))   // an Aggressive scan home range
        assertEquals(252, wide.byUnit().keys.count { wide.enclosing!!.contains(ip(it.toString().substringBefore('/'))) })
        assertTrue((0..3).none { k -> net("10.0.$k.0/24") in wide.byUnit() })
    }
    @Test fun `the spec example walks exactly the spec's units in four rounds`() = runBlocking {
        val t = GatewayTargets.of(phone192, home192)
        val r = probe(t, answering(setOf("10.1.1.1", "10.1.2.1", "10.1.3.1"))) { first -> CandidateRanges.walkBudget(home192, emptyList(), emptyList(), first, 4) }
        assertEquals(listOf("10.1.1.0/24", "10.1.2.0/24", "10.1.3.0/24"), r.hits.map { it.toString() })
        assertEquals(listOf("10.1.0.1", "10.1.2.1", "10.1.3.1", "10.1.4.1", "10.1.5.1"), walked(t))
        val n = t.byUnit().size
        assertEquals("rounds of 2, 1, 1 and 1 units", listOf(n, n + 2, n + 3, n + 4, n + 5), progress.map { it.second }.distinct())
        assertFalse(r.walkSkippedManySeeds)
    }
    @Test fun `each round goes seed 1 down, seed 1 up, seed 2 down, seed 2 up`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        probe(t, answering(setOf("172.16.16.1", "10.1.10.1"))) { 16 }
        assertEquals(listOf("10.1.9.1", "10.1.11.1", "172.16.15.1", "172.16.17.1", "10.1.8.1", "10.1.12.1", "172.16.14.1", "172.16.18.1"), walked(t))
    }
    @Test fun `with B of one the walk stops after the first round with a hit and the extra hit is truncated`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val r = probe(t, answering(setOf("10.1.10.1", "10.1.9.1", "10.1.11.1", "10.1.8.1", "10.1.12.1"))) { first ->
            CandidateRanges.walkBudget(home10, emptyList(), emptyList(), first, 2) }
        assertEquals("round 1 only", listOf("10.1.9.1", "10.1.11.1"), walked(t))
        assertEquals(listOf("10.1.9.0/24", "10.1.10.0/24", "10.1.11.0/24"), r.hits.map { it.toString() })
        val cand = CandidateRanges.derive(home10, emptyList(), emptyList(), r.hits, 2)
        assertEquals(listOf("10.1.9.0/24", "10.1.10.0/24"), cand.ranges.map { it.subnet.toString() })
        assertEquals(listOf("10.1.11.0/24"), cand.truncated.map { it.subnet.toString() })
    }
    @Test fun `with manual units filling the cap no walk unit is probed`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val manual = listOf(net("10.0.4.0/24"), net("10.0.5.0/24"))
        probe(t, answering(setOf("10.1.10.1", "10.1.9.1"))) { first -> CandidateRanges.walkBudget(home10, manual, emptyList(), first, 2) }
        assertTrue(walked(t).isEmpty())
    }
    @Test fun `five answering seeds give no walk and the flag`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        var asked = 0
        val r = probe(t, answering(setOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "172.16.0.1"))) { asked++; 16 }
        assertTrue(r.walkSkippedManySeeds); assertEquals("the budget is never asked", 0, asked); assertTrue(walked(t).isEmpty())
        assertEquals(5, r.hits.size)
        val four = probe(GatewayTargets.of(phone10, home10), answering(setOf("10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1"))) { 0 }
        assertFalse("four answering seeds are allowed", four.walkSkippedManySeeds)
    }
    @Test fun `a direction ends at distance 16 and at octet 0 or 255`() = runBlocking {
        val t = GatewayTargets(enclosing = SubnetMath.of(ip("10.0.0.0"), 16), exclude = home10, gatewayOffset = null, seeds = listOf(ip("10.20.3.1"), ip("10.20.250.1")))
        probe(t, answeringWhen { it.hostAddress.startsWith("10.20.") }) { 100 }
        val w = walked(t).toSet()
        assertTrue("10.20.0.1" in w); assertTrue("10.20.19.1" in w); assertFalse("10.20.20.1" in w)        // seed .3: down to 0, up 16
        assertTrue("10.20.255.1" in w); assertTrue("10.20.234.1" in w); assertFalse("10.20.233.1" in w)   // seed .250: up to 255, down 16
        assertEquals(3 + 16 + 5 + 16, w.size)
    }
    @Test fun `two consecutive misses end a direction and an already probed unit is skipped without counting`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val hitsUp = (2..8).map { "10.1.$it.1" }
        probe(t, answering(setOf("10.1.1.1") + hitsUp)) { 100 }
        val w = walked(t)
        assertTrue(w.containsAll(hitsUp))
        assertTrue("10.1.9.1 misses, 10.1.10.0/24 is a seed unit and skipped, 10.1.11.1 is the second miss", "10.1.11.1" in w)
        assertFalse("10.1.12.1" in w)
        assertEquals("the seed unit is probed only by the first pass", 2, calls.count { it.startsWith("10.1.10.1:") })
        assertTrue("down: 10.1.0.1 misses, then the octet boundary", "10.1.0.1" in w)
    }
    @Test fun `no walk for a seed inside the phone's slash 16`() = runBlocking {
        val t = GatewayTargets(SubnetMath.of(ip("10.1.0.0"), 16), SubnetMath.of(ip("10.1.3.0"), 24), null, listOf(ip("10.1.10.1")))
        val r = probe(t, answering(setOf("10.1.10.1"))) { 100 }
        assertEquals("no round was added", t.byUnit().size, progress.last().second)
        assertEquals(listOf("10.1.10.0/24"), r.hits.map { it.toString() })
    }
    @Test fun `no seed answering gives the Phase 2 attempt count exactly`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val r = probe(t, answering(emptySet())) { 16 }
        assertTrue(r.hits.isEmpty()); assertTrue(walked(t).isEmpty())
        assertEquals("527 addresses x 2 ports; the engine's 2 TEST-NET probes make the spec's 1,056", 1_054, progress.last().third)
        assertEquals(firstPass(t).size * GatewayProbe.PORTS.size, progress.last().third)
    }
    @Test fun `walk attempts never exceed 256`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val chosen = listOf("10.1.10.1", "10.10.10.1", "172.16.16.1", "192.168.10.1").map(::ip)
        // The four chosen seeds answer; around each, an even distance answers and an odd one misses, so no direction sees two misses in a row.
        val rule: (Inet4Address) -> Boolean = { a ->
            a in chosen || chosen.any { s -> SubnetMath.of(s, 16).contains(a) && a !in t.seeds && (third(a) - third(s)) % 2 == 0 }
        }
        val r = probe(t, answeringWhen(rule)) { 1_000 }
        assertFalse(r.walkSkippedManySeeds)
        val fp = firstPass(t)
        val walkAttempts = calls.count { it.substringBefore(':') !in fp }
        assertTrue("walk attempts $walkAttempts", walkAttempts in 1..256)
    }
    @Test fun `progress never steps back and the total grows by each round`() = runBlocking {
        val t = GatewayTargets.of(phone192, home192)
        probe(t, answering(setOf("10.1.1.1", "10.1.2.1", "10.1.3.1"))) { 16 }
        progress.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b.first >= a.first && b.second >= a.second && b.third >= a.third) }
        progress.forEach { assertTrue("$it", it.first <= it.second) }
        assertEquals(progress.last().first, progress.last().second)
    }
    @Test fun `cancellation mid walk returns within a second`() = runBlocking {
        val t = GatewayTargets.of(phone10, home10)
        val fp = firstPass(t)
        val walking = CompletableDeferred<Unit>()
        val connect: suspend (Inet4Address, Int) -> Probe.Outcome = { a, _ ->
            when {
                a.hostAddress == "10.1.10.1" -> Probe.Outcome.Refused
                a.hostAddress in fp -> Probe.Outcome.Timeout
                else -> { walking.complete(Unit); delay(10_000); Probe.Outcome.Timeout }   // a walk unit that never answers
            }
        }
        val job = launch(Dispatchers.Default) { GatewayProbe.run(t, connect, dispatcher = Dispatchers.Default, walkBudget = { 16 }) }
        withTimeout(10_000) { walking.await() }
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbeTest.kt`: in `a unit is a hit on connect or refused, ports are 80 then 443 only`, replace
```kotlin
        }, onAnswer = { answered += it.hostAddress }, dispatcher = Dispatchers.Default)
```
with
```kotlin
        }, onAnswer = { answered += it.hostAddress }, dispatcher = Dispatchers.Default).hits
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt`:
1. After `private val rdnsCalls = Collections.synchronizedList(mutableListOf<String>())` add:
```kotlin
    private val budgets = Collections.synchronizedList(mutableListOf<(List<com.northwalessolutions.netprobe.netenv.Subnet>) -> Int>())
```
2. In `engine(...)`, replace the parameter line
```kotlin
                       gatewayHits: List<String> = emptyList(), gatewayThrows: Boolean = false, gatewayAnswered: List<String> = emptyList(),
```
with
```kotlin
                       gatewayHits: List<String> = emptyList(), gatewayThrows: Boolean = false, gatewayAnswered: List<String> = emptyList(), walkSkipped: Boolean = false,
```
and the fake
```kotlin
            gatewayProbe = { t, onProgress, _ -> gatewayCalls += t; if (gatewayThrows) throw IllegalStateException("boom"); onProgress(1, 1, 2); GatewayProbeResult(gatewayHits.map(::net), gatewayAnswered.map(::ip).toSet()) },
```
with
```kotlin
            gatewayProbe = { t, onProgress, _, budget -> gatewayCalls += t; budgets += budget; if (gatewayThrows) throw IllegalStateException("boom"); onProgress(1, 1, 2)
                GatewayProbeResult(gatewayHits.map(::net), gatewayAnswered.map(::ip).toSet(), walkSkippedManySeeds = walkSkipped) },
```
3. Add before the class's closing brace:
```kotlin
    @Test fun `the walk budget is the cap less what the first pass already fills`() = runBlocking {   // spec 2.5 step 2
        engine().hosts(opts(manual = listOf("10.0.4.0/24"), cap = 4, probe = true))
        val budget = budgets.single()
        assertEquals("one manual unit of four", 3, budget(emptyList()))
        assertEquals(1, budget(listOf(net("10.0.10.0/24"), net("10.0.11.0/24"))))
        assertEquals(0, budget(listOf("10.0.10.0/24", "10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24").map(::net)))
    }
    @Test fun `too many answering seeds is noted`() = runBlocking {
        val eng = engine(walkSkipped = true); eng.hosts(opts(probe = true))
        assertTrue(ExtendedNote.GATEWAY_WALK_SKIPPED_MANY in eng.status.value.notes)
    }
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt`: add before the closing brace:
```kotlin
    @Test fun `the walk note says why nearby ranges were not checked, and only with Aggressive scan on`() {
        val st = DiscoveryStatus(notes = listOf(ExtendedNote.GATEWAY_WALK_SKIPPED_MANY))
        assertEquals(listOf("Smart discovery: too many ranges answered, nearby ranges not checked"), ExtendedNotices.of(st))
        assertTrue(ExtendedNotices.of(st, aggressive = false).isEmpty())
    }
```
and add `import org.junit.Assert.assertTrue` to its imports.

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --tests "com.northwalessolutions.netprobe.ui.home.ExtendedNoticesTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `No parameter with name 'walkBudget'`, `Unresolved reference 'walkBudget'` (CandidateRanges), `'walkSkippedManySeeds'`, `'GATEWAY_WALK_SKIPPED_MANY'`, and a lambda arity mismatch for the engine fake.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbe.kt` (whole file; `GatewayTargets`, `AnswersForEverything` and `RandomHighPort` are unchanged):
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.portscan.QuickPorts
import com.northwalessolutions.netprobe.netenv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The gateway probe's hit /24s, every address that answered (the canary must not reuse them, final review I3), and whether the outward
 * walk was skipped because more than [GatewayProbe.WALK_MAX_SEEDS] seeds answered (3C spec 2.5 step 1).
 */
data class GatewayProbeResult(val hits: List<Subnet>, val answered: Set<Inet4Address> = emptySet(), val walkSkippedManySeeds: Boolean = false)

/** What the gateway probe walks (extended spec 4.3 steps 1 to 4). */
data class GatewayTargets(val enclosing: Subnet?, val exclude: Subnet, val gatewayOffset: Int?, val seeds: List<Inet4Address>) {
    fun byUnit(): Map<Subnet, List<Inet4Address>> {
        val out = LinkedHashMap<Subnet, MutableList<Inet4Address>>()
        val offsets = listOfNotNull(LOW, HIGH, gatewayOffset).distinct()
        enclosing?.slash24Units()?.filterNot { it.overlaps(exclude) }?.forEach { u -> out[u] = offsets.map { SubnetMath.toAddress(u.network + it) }.toMutableList() }
        seeds.forEach { s -> out.getOrPut(SubnetMath.of(s, UNIT_PREFIX)) { mutableListOf() }.add(s) }
        return out
    }

    companion object {
        const val LOW = 1
        const val HIGH = 254
        private const val UNIT_PREFIX = 24
        private const val LAST_OCTET = 0xFF
        /** Common gateways in the other RFC1918 blocks, so a guest Wi-Fi on 192.168.x can find server ranges on 10.x or 172.16.x. */
        val SEEDS = listOf("10.0.0.1", "10.0.1.1", "10.1.1.1", "10.1.10.1", "10.10.0.1", "10.10.10.1", "10.0.0.254", "172.16.0.1", "172.16.1.1",
            "172.16.16.1", "172.17.0.1", "172.31.0.1", "192.168.0.1", "192.168.1.1", "192.168.1.254", "192.168.2.1", "192.168.10.1",
            "192.168.88.1", "192.168.100.1", "192.168.178.1")

        fun of(env: NetworkEnv, scanned: Subnet): GatewayTargets {
            val enclosing = Rfc1918.enclosing16(env.interfaceIp)
            val offset = env.gateway?.let { SubnetMath.toInt(it) and LAST_OCTET }?.takeUnless { it == LOW || it == HIGH || it == 0 }
            val seeds = SEEDS.mapNotNull { Ipv4.parse(it) }.filter { s -> (enclosing == null || !enclosing.contains(s)) && !scanned.contains(s) }
            return GatewayTargets(enclosing, scanned, offset, seeds)
        }
    }
}

/** TEST-NET pre-check (extended spec 4.3 step 0): RFC 5737 addresses are never assigned, so any answer means "answers for everything". */
object AnswersForEverything {
    val TEST_NET: List<Inet4Address> = listOf("192.0.2.1", "198.51.100.1").map { Ipv4.parse(it)!! }
    const val TEST_PORT = 80
    fun answered(o: Probe.Outcome): Boolean = o is Probe.Outcome.Connected || o == Probe.Outcome.Refused
    fun of(outcomes: List<Probe.Outcome>): Boolean = outcomes.any(::answered)
}

/** One random high port per run, never a liveness or Quick port, for the pre-check and the canaries (extended spec 4.7). */
object RandomHighPort {
    const val MIN = 49152
    const val MAX_EXCLUSIVE = 65536
    private val EXCLUDED: Set<Int> = (HostProber.DEFAULT_PORTS + QuickPorts.list).toSet()
    fun pick(random: Random): Int {
        while (true) { val p = random.nextInt(MIN, MAX_EXCLUSIVE); if (p !in EXCLUDED) return p }
    }
}

/**
 * Smart discovery's outward walk (3C spec 2.5 steps 3 and 4): two directions per answering seed, seeds in ascending address order,
 * one unit per live direction per round, only the seed's own last octet probed, within the seed's /16. [probed] starts as the first
 * pass's units and grows as directions take units, so no unit is probed twice.
 */
internal class OutwardWalk(seeds: List<Inet4Address>, private val probed: MutableSet<Subnet>, private val home: Subnet) {
    class Direction(val seed: Inet4Address, val step: Int) {
        var misses = 0
        var ended = false
    }
    val directions: List<Direction> = seeds.sortedBy { SubnetMath.toInt(it).toLong() and UNSIGNED }.flatMap { listOf(Direction(it, DOWN), Direction(it, UP)) }

    /** Round [k]'s units in direction order; ends directions that cross the octet boundary. A skipped unit is neither a miss nor a hit. */
    fun round(k: Int): List<Pair<Direction, Inet4Address>> {
        val out = ArrayList<Pair<Direction, Inet4Address>>()
        for (d in directions) {
            if (d.ended) continue
            val s = SubnetMath.toInt(d.seed)
            val c = ((s ushr THIRD_SHIFT) and OCTET_MASK) + d.step * k
            if (c < 0 || c > OCTET_MASK) { d.ended = true; continue }   // the seed's own /16 ends here
            val a = SubnetMath.toAddress((s and THIRD_CLEAR) or (c shl THIRD_SHIFT))
            val unit = SubnetMath.of(a, UNIT_PREFIX)
            if (unit.overlaps(home) || !probed.add(unit)) continue       // first pass, a seed, another direction, or the home range
            out += d to a
        }
        return out
    }
    fun record(d: Direction, hit: Boolean, k: Int) {
        if (hit) d.misses = 0 else d.misses++
        if (d.misses >= GatewayProbe.OUTWARD_MISS_LIMIT || k >= GatewayProbe.OUTWARD_MAX_UNITS) d.ended = true
    }
    fun allEnded(): Boolean = directions.all { it.ended }

    private companion object {
        const val DOWN = -1
        const val UP = 1
        const val THIRD_SHIFT = 8
        const val OCTET_MASK = 0xFF
        const val UNIT_PREFIX = 24
        const val UNSIGNED = 0xFFFFFFFFL
        val THIRD_CLEAR = 0xFFFF00FF.toInt()
    }
}

object GatewayProbe {
    const val TIMEOUT_MS = 400
    const val CONCURRENCY = 64
    val PORTS = listOf(80, 443)
    /** More answering seeds than this reads as a bogon-reject firewall answering for every private address: no walk (3C spec 2.5 step 1, G12). */
    const val WALK_MAX_SEEDS = 4
    /** Furthest a direction walks from its seed, in /24 units. */
    const val OUTWARD_MAX_UNITS = 16
    /** Consecutive misses that end a direction. */
    const val OUTWARD_MISS_LIMIT = 2
    private const val UNIT_PREFIX = 24

    /**
     * First pass: a /24 is a hit when any probed address in it connects or is refused on 80 or 443. Then the outward walk (3C spec 2.5)
     * around answering seeds outside the enclosing /16, bounded by [walkBudget] (cap slots left after the first pass; 0 means no walk).
     * Hits come back ordered by address. [onAnswer] gets each answering address, first pass and walk.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(targets: GatewayTargets, connect: suspend (Inet4Address, Int) -> Probe.Outcome, onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
                    onAnswer: (Inet4Address) -> Unit = {},
                    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY),
                    walkBudget: (List<Subnet>) -> Int = { 0 }): GatewayProbeResult {
        val units = targets.byUnit()
        val sem = Semaphore(CONCURRENCY)
        val hits = ConcurrentHashMap.newKeySet<Subnet>()
        val answered = ConcurrentHashMap.newKeySet<Inet4Address>()
        val attempts = AtomicInteger()
        val lock = Any()
        var done = 0
        var total = units.size
        onProgress(0, total, 0)
        suspend fun probeOne(a: Inet4Address): Boolean {
            val ok = sem.withPermit { answers(a, connect, attempts) }
            if (ok) { answered += a; onAnswer(a) }
            return ok
        }
        // Counter and callback under one lock: two units finishing together can never report 272 before 271, so the status never steps back.
        fun finishedOne() = synchronized(lock) { done++; onProgress(done, total, attempts.get()) }

        coroutineScope {
            for ((unit, addresses) in units) launch(dispatcher) {
                for (a in addresses) if (probeOne(a)) { hits += unit; break }
                finishedOne()
            }
        }
        val firstPass = hits.sortedBy { it.start() }
        val enclosing = targets.enclosing
        val seeds = targets.seeds.filter { it in answered && (enclosing == null || !enclosing.contains(it)) }
        if (seeds.size > WALK_MAX_SEEDS) return GatewayProbeResult(firstPass, answered.toSet(), walkSkippedManySeeds = true)
        val budget = if (seeds.isEmpty()) 0 else walkBudget(firstPass)
        if (budget <= 0) return GatewayProbeResult(firstPass, answered.toSet())

        val walk = OutwardWalk(seeds, units.keys.toHashSet(), targets.exclude)
        var walkHits = 0
        for (k in 1..OUTWARD_MAX_UNITS) {
            if (walk.allEnded()) break
            val round = walk.round(k)
            if (round.isEmpty()) continue                                  // every live direction skipped a probed unit this round
            synchronized(lock) { total += round.size; onProgress(done, total, attempts.get()) }   // the total grows before the round starts
            val results = coroutineScope {
                round.map { (d, a) -> async(dispatcher) { val ok = probeOne(a); finishedOne(); Triple(d, a, ok) } }.awaitAll()
            }
            for ((d, a, ok) in results) {
                walk.record(d, ok, k)
                if (ok) { hits += SubnetMath.of(a, UNIT_PREFIX); walkHits++ }
            }
            if (walkHits >= budget) break                                  // extra hits of this round stay; derive reports them as truncated
        }
        return GatewayProbeResult(hits.sortedBy { it.start() }, answered.toSet())
    }

    /** Ports in order, stopping at the first definitive answer: a connect or refusal is yes, unreachable is no, a timeout tries the next. Each connect is one counted attempt. */
    private suspend fun answers(a: Inet4Address, connect: suspend (Inet4Address, Int) -> Probe.Outcome, attempts: AtomicInteger): Boolean {
        for (port in PORTS) {
            attempts.incrementAndGet()
            when (connect(a, port)) {
                is Probe.Outcome.Connected, Probe.Outcome.Refused -> return true
                Probe.Outcome.Unreachable -> return false
                Probe.Outcome.Timeout -> Unit
            }
        }
        return false
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRanges.kt`: add inside `object CandidateRanges`, after `derive`:
```kotlin
    /** Smart discovery's walk budget (3C spec 2.5 step 2): cap slots left after manual, evidence and the first pass's hits; 0 or less means no walk. */
    fun walkBudget(scanned: Subnet, manual: List<Subnet>, evidence: List<Inet4Address>, firstPassHits: List<Subnet>, cap: Int): Int =
        cap.coerceIn(MIN_CAP, MAX_CAP) - derive(scanned, manual, evidence, firstPassHits, cap).ranges.size
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt`: replace the `ExtendedNote` line with
```kotlin
enum class ExtendedNote { GATEWAY_PROBE_SKIPPED_ANSWERS_ALL, GATEWAY_PROBE_FOUND_NONE, NO_EXTRA_RANGES, SKIPPED_BIND_FAILED, GATEWAY_WALK_SKIPPED_MANY }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt`:
1. Replace the constructor parameter
```kotlin
    val gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?) -> GatewayProbeResult = { _, _, _ -> GatewayProbeResult(emptyList()) },
```
with
```kotlin
    /** Smart discovery: targets, progress, the bound network, and the walk budget for the first pass's hits (3C spec 2.5 step 2). */
    val gatewayProbe: suspend (GatewayTargets, (Int, Int, Int) -> Unit, SocketBinder?, (List<Subnet>) -> Int) -> GatewayProbeResult = { _, _, _, _ -> GatewayProbeResult(emptyList()) },
```
2. In `extendedPhase`, replace
```kotlin
                        isolated {
                            val r = gatewayProbe(targets, { d, t, n -> _status.update { it.copy(gatewayProbe = GatewayProbeProgress(label, d, t, n)) } }, b)
                            hits = r.hits; gatewayAnswered = r.answered
                            ran = true
                        }
```
with
```kotlin
                        isolated {
                            // Counted against what derive would accept, so walked hits never push past the cap (3C spec 2.5 step 2).
                            val budget: (List<Subnet>) -> Int = { first -> CandidateRanges.walkBudget(local, ext.manual, evidenceNow(), first, ext.maxRanges) }
                            val r = gatewayProbe(targets, { d, t, n -> _status.update { it.copy(gatewayProbe = GatewayProbeProgress(label, d, t, n)) } }, b, budget)
                            hits = r.hits; gatewayAnswered = r.answered
                            if (r.walkSkippedManySeeds) addNote(ExtendedNote.GATEWAY_WALK_SKIPPED_MANY)
                            ran = true
                        }
```
3. In `production`, replace
```kotlin
                gatewayProbe = { targets, onProgress, b ->
                    val answered = java.util.concurrent.ConcurrentHashMap.newKeySet<Inet4Address>()
                    val hits = GatewayProbe.run(targets, connect = { a, port -> Probe.connect(a, port, GatewayProbe.TIMEOUT_MS, binder = b) },
                        onProgress = onProgress, onAnswer = { answered += it })
                    GatewayProbeResult(hits, answered.toSet())
                },
```
with
```kotlin
                // The walk's probes use the same bound connect as the first pass (Phase 2 spec 5.5).
                gatewayProbe = { targets, onProgress, b, budget ->
                    GatewayProbe.run(targets, connect = { a, port -> Probe.connect(a, port, GatewayProbe.TIMEOUT_MS, binder = b) },
                        onProgress = onProgress, walkBudget = budget)
                },
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt`: in `ExtendedNotices.of`'s `when`, add after `ExtendedNote.SKIPPED_BIND_FAILED -> HomeCopy.BIND_FAILED`:
```kotlin
                ExtendedNote.GATEWAY_WALK_SKIPPED_MANY -> HomeCopy.GATEWAY_WALK_SKIPPED
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures; `CopyTableTest.every notice and range summary state matches the table` now covers the new note (it loops `ExtendedNote.entries`).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbe.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/CandidateRanges.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryOptions.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngine.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/ExtendedNotices.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayWalkTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/GatewayProbeTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveryEngineExtendedTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/ExtendedNoticesTest.kt
git status --short
git commit -m "Phase 3C-1: Smart discovery outward walk with budget, stop rules and seed limit"
```

---

### Task 8: URL import rules and copy: UrlImportRules, the result types, WordlistCopy and the import messages

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlImport.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistCopy.kt` (whole file), `ui/wordlists/WordlistsScreen.kt` (`WordlistImport` only)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/wordlists/UrlImportRulesTest.kt`; modify `ui/tools/ToolCopyTableTest.kt`

**Interfaces:**
- Consumes: `ImportResult`, `WordlistStore.MAX_IMPORT_BYTES` (3B Task 9), `Ipv4`, `Rfc1918`, `SubnetMath` (MVP, Phase 2).
- Produces:
```kotlin
sealed interface UrlCheck { data class Ok(val url: URL); data class Invalid(val error: String) }
sealed interface RedirectDecision { data class Follow(val url: URL); object TooMany; object Insecure; object LocalTarget; object Invalid }
sealed interface FetchFailure { object UnknownHost; object NoAnswer; object Tls; object Connection; data class Status(val code: Int); data class NotText(val type: String?)
                                object Insecure; object LocalTarget; object TooManyRedirects }
sealed interface UrlImportResult { data class Stored(val result: ImportResult); data class Failed(val reason: FetchFailure); object Cancelled }
object UrlImportRules { const val MAX_REDIRECTS = 3; const val MAX_URL_CHARS = 2048; const val BINARY_SNIFF_BYTES = 8_192
    fun validate(text: String): UrlCheck; fun redirect(original: URL, from: URL, location: String?, hops: Int): RedirectDecision
    fun contentType(header: String?): Boolean; fun mediaType(header: String?): String?; fun looksBinary(prefix: ByteArray): Boolean
    fun displayName(url: URL): String?; fun failureOf(e: IOException): FetchFailure; internal fun isLocalLiteral(host: String?): Boolean }
object WordlistImport { fun message(result: ImportResult): String; fun message(result: UrlImportResult): String; fun failure(f: FetchFailure): String }
```
- Rules (spec 5.3, D16), exactly: `validate` trims; prepends `https://` when the text has no `scheme://` (a `name:` prefix followed by a non-digit with no `@` is a scheme, so `javascript:` is refused and `example.com:8080` is not); accepts `http` and `https` only; requires a host; refuses user info with `ERR_URL_USER`; refuses whitespace and control characters; caps at `MAX_URL_CHARS`; drops the fragment. `redirect` follows when `hops < MAX_REDIRECTS`, the `Location` resolves against `from` to an `http` or `https` URL with a host and no user info, the hop is not https to http, and the target is not a loopback, link-local, RFC1918 (or IPv6 loopback, link-local or unique-local) literal unless the original host was itself such a literal; a missing or unusable `Location` is `Invalid` (the fetcher reports the server's status). `contentType` accepts `text/*` except `text/html`, `application/octet-stream`, and a missing or blank header. `looksBinary` is a NUL in the first `BINARY_SNIFF_BYTES`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/UrlImportRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** 3C spec 8.1 item 11, Review Focus 5. */
class UrlImportRulesTest {
    private fun ok(text: String) = (UrlImportRules.validate(text) as UrlCheck.Ok).url.toString()
    private fun err(text: String) = (UrlImportRules.validate(text) as UrlCheck.Invalid).error
    private fun url(s: String) = URL(s)
    /** Compared as text: URL.equals resolves host names, which a unit test must never do. */
    private fun follow(d: RedirectDecision) = (d as RedirectDecision.Follow).url.toString()

    @Test fun `validate prepends https, keeps http and ports, drops the fragment`() {
        assertEquals("https://example.com/list.txt", ok("example.com/list.txt"))
        assertEquals("http://10.0.3.5:8000/w.txt", ok("  http://10.0.3.5:8000/w.txt  "))
        assertEquals("https://example.com:8080/x", ok("example.com:8080/x"))
        assertEquals("https://example.com/list.txt", ok("https://example.com/list.txt#top"))
        assertNull((UrlImportRules.validate("https://example.com/list.txt#top") as UrlCheck.Ok).url.ref)
    }
    @Test fun `validate refuses other schemes, credentials, spaces, blanks and long addresses`() {
        assertEquals(WordlistCopy.ERR_URL, err("ftp://example.com/x"))
        assertEquals(WordlistCopy.ERR_URL, err("javascript:alert(1)"))
        assertEquals(WordlistCopy.ERR_URL_USER, err("https://user:pass@example.com/l.txt"))
        assertEquals(WordlistCopy.ERR_URL_USER, err("user:pass@example.com/l.txt"))
        assertEquals(WordlistCopy.ERR_URL, err("https://example.com/a b"))
        assertEquals(WordlistCopy.ERR_URL, err(""))
        assertEquals(WordlistCopy.ERR_URL, err("https://"))
        assertEquals(WordlistCopy.ERR_URL_LONG, err("https://example.com/" + "a".repeat(UrlImportRules.MAX_URL_CHARS)))
    }
    @Test fun `redirect follows three hops to other hosts and refuses a fourth`() {
        val gh = url("https://github.com/danielmiessler/SecLists/raw/master/Discovery/Web-Content/common.txt")
        val raw = "https://raw.githubusercontent.com/danielmiessler/SecLists/master/Discovery/Web-Content/common.txt"
        assertEquals(raw, follow(UrlImportRules.redirect(gh, gh, raw, 0)))
        val box = url("https://www.dropbox.com/s/abc/list.txt?dl=1")
        assertEquals("https://dl.dropboxusercontent.com/s/abc/list.txt", follow(UrlImportRules.redirect(box, box, "https://dl.dropboxusercontent.com/s/abc/list.txt", 1)))
        assertTrue(UrlImportRules.redirect(gh, gh, raw, 2) is RedirectDecision.Follow)
        assertEquals(RedirectDecision.TooMany, UrlImportRules.redirect(gh, gh, raw, 3))
    }
    @Test fun `redirect never downgrades and allows an upgrade`() {
        val s = url("https://example.com/a"); val p = url("http://example.com/a")
        assertEquals(RedirectDecision.Insecure, UrlImportRules.redirect(s, s, "http://example.com/b", 0))
        assertEquals("https://example.com/b", follow(UrlImportRules.redirect(p, p, "https://example.com/b", 0)))
    }
    @Test fun `a public host never bounces the phone into a local address`() {
        val p = url("http://example.com/a")
        for (target in listOf("http://192.168.1.1/", "http://10.0.0.5/", "http://127.0.0.1/", "http://169.254.1.1/", "http://172.16.0.9/",
                "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[::ffff:10.0.0.5]/"))
            assertEquals(target, RedirectDecision.LocalTarget, UrlImportRules.redirect(p, p, target, 0))
        val lan = url("http://10.0.3.5/a")
        assertEquals("http://10.0.3.6/b", follow(UrlImportRules.redirect(lan, lan, "http://10.0.3.6/b", 0)))   // started local: allowed
        assertEquals("http://example.com/next", follow(UrlImportRules.redirect(p, p, "/next", 0)))           // relative
    }
    @Test fun `a missing or unusable location, another scheme or credentials are invalid`() {
        val p = url("https://example.com/a")
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, null, 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "  ", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "javascript:alert(1)", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "ftp://example.com/x", 0))
        assertEquals(RedirectDecision.Invalid, UrlImportRules.redirect(p, p, "https://u:p@example.com/x", 0))
    }
    @Test fun `content type accepts text except html, octet-stream and none`() {
        listOf("text/plain; charset=utf-8", "TEXT/PLAIN", "text/x-wordlist", "application/octet-stream", null, "", "  ").forEach { assertTrue("$it", UrlImportRules.contentType(it)) }
        listOf("text/html", "text/html; charset=utf-8", "application/zip", "application/json", "image/png").forEach { assertFalse(it, UrlImportRules.contentType(it)) }
        assertEquals("text/html", UrlImportRules.mediaType("Text/HTML; charset=utf-8")); assertNull(UrlImportRules.mediaType(null))
    }
    @Test fun `a nul in the first 8 KB is binary and one after it is not`() {
        assertTrue(UrlImportRules.looksBinary(ByteArray(UrlImportRules.BINARY_SNIFF_BYTES) { if (it == 100) 0 else 'a'.code.toByte() }))
        assertFalse(UrlImportRules.looksBinary(ByteArray(UrlImportRules.BINARY_SNIFF_BYTES + 1) { if (it == UrlImportRules.BINARY_SNIFF_BYTES) 0 else 'a'.code.toByte() }))
        assertFalse(UrlImportRules.looksBinary(ByteArray(0)))
    }
    @Test fun `the display name is the decoded last segment, else the host`() {
        assertEquals("my words.txt", UrlImportRules.displayName(url("https://x.example/lists/my%20words.txt")))
        assertEquals("common.txt", UrlImportRules.displayName(url("https://raw.githubusercontent.com/a/b/master/common.txt")))
        assertEquals("example.com", UrlImportRules.displayName(url("https://example.com/")))
        assertEquals("example.com", UrlImportRules.displayName(url("https://example.com")))
    }
    @Test fun `failures map by exception type`() {
        assertEquals(FetchFailure.UnknownHost, UrlImportRules.failureOf(UnknownHostException("x")))
        assertEquals(FetchFailure.NoAnswer, UrlImportRules.failureOf(SocketTimeoutException("x")))
        assertEquals(FetchFailure.Tls, UrlImportRules.failureOf(SSLHandshakeException("x")))
        assertEquals(FetchFailure.Connection, UrlImportRules.failureOf(IOException("x")))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt`:
1. In the `templates` list, replace `"Back", "Wordlists", "Import wordlist", "Importing",` with `"Back", "Wordlists", "Import file", "Importing",`, and add as the list's last entries (before `).map { Regex(it) }`):
```kotlin
        // 3C-1 URL import (spec 7.2)
        "Import from URL", "URL", "https://example\\.com/wordlist\\.txt", "Import", "Enter a web address starting with https:// or http://",
        "Remove the user name and password from the address", "That address is too long", "Fetching \\d+\\.\\d( of \\d+\\.\\d)? MB", "Import cancelled",
        "Could not fetch: (unknown host|no answer|secure connection failed|connection failed|redirected to an insecure address|redirected to a local address|too many redirects)",
        "Could not fetch: the server answered \\d{3}", "Could not fetch: not a text file( \\(\\S+\\))?",
```
2. In `everyString()`, after the `addAll(listOf(WordlistCopy.BACK, ...))` call, add:
```kotlin
        addAll(listOf(WordlistCopy.IMPORT_URL, WordlistCopy.URL_LABEL, WordlistCopy.URL_PLACEHOLDER, WordlistCopy.URL_IMPORT, WordlistCopy.ERR_URL,
            WordlistCopy.ERR_URL_USER, WordlistCopy.ERR_URL_LONG, WordlistCopy.CANCELLED, WordlistCopy.fetching(0, null), WordlistCopy.fetching(1_258_291, 3_565_158),
            WordlistCopy.fetching(10_485_760, null)))
        val failures = listOf(FetchFailure.UnknownHost, FetchFailure.NoAnswer, FetchFailure.Tls, FetchFailure.Connection, FetchFailure.Status(404),
            FetchFailure.Status(503), FetchFailure.NotText("text/html"), FetchFailure.NotText(null), FetchFailure.Insecure, FetchFailure.LocalTarget,
            FetchFailure.TooManyRedirects)
        failures.forEach { add(WordlistImport.message(UrlImportResult.Failed(it))) }
        listOf<ImportResult>(ImportResult.TooLarge, ImportResult.TooMany, ImportResult.Empty, ImportResult.Unreadable).forEach { add(WordlistImport.message(UrlImportResult.Stored(it))) }
        add(WordlistImport.message(UrlImportResult.Cancelled))
```
3. Add imports: `import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport`, `import com.northwalessolutions.netprobe.wordlists.FetchFailure`, `import com.northwalessolutions.netprobe.wordlists.ImportResult`, `import com.northwalessolutions.netprobe.wordlists.UrlImportResult`.
4. Add these tests before the class's closing brace:
```kotlin
    @Test fun `url import strings read as the spec table`() {
        assertEquals("Fetching 1.2 of 3.4 MB", WordlistCopy.fetching(1_258_291, 3_565_158))
        assertEquals("Fetching 1.2 MB", WordlistCopy.fetching(1_258_291, null))
        assertEquals("Could not fetch: the server answered 404", WordlistImport.message(UrlImportResult.Failed(FetchFailure.Status(404))))
        assertEquals("Could not fetch: not a text file (text/html)", WordlistImport.message(UrlImportResult.Failed(FetchFailure.NotText("text/html"))))
        assertEquals("Could not fetch: not a text file", WordlistImport.message(UrlImportResult.Failed(FetchFailure.NotText(null))))
        assertEquals("That file is too large (limit 10 MB)", WordlistImport.message(UrlImportResult.Stored(ImportResult.TooLarge)))
        assertEquals("Import file", WordlistCopy.IMPORT)
        assertFalse("FETCH_OTHER_SITE" in WordlistCopy::class.java.declaredFields.map { it.name })   // spec 7.2: removed from the draft
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.UrlImportRulesTest" --tests "com.northwalessolutions.netprobe.ui.tools.ToolCopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'UrlImportRules'`, `'UrlCheck'`, `'FetchFailure'`, `'IMPORT_URL'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlImport.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Rfc1918
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The URL dialog's check (3C spec 5.3): a URL ready to fetch, or the inline error. */
sealed interface UrlCheck {
    data class Ok(val url: URL) : UrlCheck
    data class Invalid(val error: String) : UrlCheck
}

sealed interface RedirectDecision {
    data class Follow(val url: URL) : RedirectDecision
    object TooMany : RedirectDecision
    object Insecure : RedirectDecision
    object LocalTarget : RedirectDecision
    /** No Location, an unusable one, another scheme, or credentials in it: the fetch fails with the server's status. */
    object Invalid : RedirectDecision
}

sealed interface FetchFailure {
    object UnknownHost : FetchFailure
    object NoAnswer : FetchFailure
    object Tls : FetchFailure
    object Connection : FetchFailure
    data class Status(val code: Int) : FetchFailure
    /** [type] is the refused media type, or null for the NUL-byte check. */
    data class NotText(val type: String?) : FetchFailure
    object Insecure : FetchFailure
    object LocalTarget : FetchFailure
    object TooManyRedirects : FetchFailure
}

sealed interface UrlImportResult {
    data class Stored(val result: ImportResult) : UrlImportResult
    data class Failed(val reason: FetchFailure) : UrlImportResult
    object Cancelled : UrlImportResult
}

/** Pure rules for the wordlist URL import (3C spec 5.3, D16). Nothing here opens a socket or resolves a name. */
object UrlImportRules {
    const val MAX_REDIRECTS = 3
    const val MAX_URL_CHARS = 2048
    const val BINARY_SNIFF_BYTES = 8_192
    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val SCHEME_SEPARATOR = "://"
    private const val TEXT_PREFIX = "text/"
    private const val HTML = "text/html"
    private const val OCTET_STREAM = "application/octet-stream"
    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val LOOPBACK = SubnetMath.of(Ipv4.parse("127.0.0.0")!!, 8)
    private val LINK_LOCAL = SubnetMath.of(Ipv4.parse("169.254.0.0")!!, 16)

    fun validate(text: String): UrlCheck {
        val t = text.trim()
        if (t.isEmpty()) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        if (t.length > MAX_URL_CHARS) return UrlCheck.Invalid(WordlistCopy.ERR_URL_LONG)
        if (t.any { it.isWhitespace() || it.isISOControl() }) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        val full = (if (hasScheme(t)) t else "https://$t").substringBefore('#')
        val uri = try { URI(full) } catch (_: URISyntaxException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) }
        val scheme = uri.scheme?.lowercase()
        if (scheme != HTTP && scheme != HTTPS) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        if (uri.rawUserInfo != null) return UrlCheck.Invalid(WordlistCopy.ERR_URL_USER)
        if (uri.host.isNullOrEmpty()) return UrlCheck.Invalid(WordlistCopy.ERR_URL)
        val url = try { uri.toURL() } catch (_: MalformedURLException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) } catch (_: IllegalArgumentException) { return UrlCheck.Invalid(WordlistCopy.ERR_URL) }
        if (url.toString().length > MAX_URL_CHARS) return UrlCheck.Invalid(WordlistCopy.ERR_URL_LONG)
        return UrlCheck.Ok(url)
    }

    /** "x://" is a scheme; so is an opaque "name:" (javascript:, data:) unless a port digit follows or an "@" shows user info. */
    private fun hasScheme(t: String): Boolean {
        if (SCHEME_SEPARATOR in t) return true
        val m = SCHEME.find(t) ?: return false
        val rest = t.substring(m.value.length)
        return rest.isNotEmpty() && !rest.first().isDigit() && '@' !in t
    }

    /** One hop of a 3xx (spec 5.3): [original] is the URL the Director typed, [from] the URL that answered, [hops] the redirects already followed. */
    fun redirect(original: URL, from: URL, location: String?, hops: Int): RedirectDecision {
        if (hops >= MAX_REDIRECTS) return RedirectDecision.TooMany
        val loc = location?.trim()?.takeIf { it.isNotEmpty() } ?: return RedirectDecision.Invalid
        val target = try { URL(from, loc) } catch (_: MalformedURLException) { return RedirectDecision.Invalid }
        val scheme = target.protocol.lowercase()
        if (scheme != HTTP && scheme != HTTPS) return RedirectDecision.Invalid
        if (target.host.isNullOrEmpty() || target.userInfo != null) return RedirectDecision.Invalid   // no credentials, ever
        if (from.protocol.equals(HTTPS, ignoreCase = true) && scheme == HTTP) return RedirectDecision.Insecure
        if (isLocalLiteral(target.host) && !isLocalLiteral(original.host)) return RedirectDecision.LocalTarget
        return RedirectDecision.Follow(if (target.ref == null) target else URL(target.toString().substringBefore('#')))
    }

    fun contentType(header: String?): Boolean {
        val t = mediaType(header) ?: return true                       // no header: accepted, the NUL check still runs
        return t != HTML && (t.startsWith(TEXT_PREFIX) || t == OCTET_STREAM)
    }
    fun mediaType(header: String?): String? = header?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun looksBinary(prefix: ByteArray): Boolean {
        val n = minOf(prefix.size, BINARY_SNIFF_BYTES)
        for (i in 0 until n) if (prefix[i] == 0.toByte()) return true
        return false
    }

    /** The final URL's last path segment, percent-decoded, else its host; WordlistStore.cleanName then strips the extension. */
    fun displayName(url: URL): String? {
        val path = runCatching { url.toURI().path }.getOrNull() ?: url.path.orEmpty()   // toURI throws on characters a URI refuses: keep the raw path
        val segment = path.substringAfterLast('/').trim()
        return segment.ifEmpty { url.host?.takeIf { it.isNotEmpty() } }
    }

    fun failureOf(e: IOException): FetchFailure = when (e) {
        is UnknownHostException -> FetchFailure.UnknownHost
        is SocketTimeoutException -> FetchFailure.NoAnswer
        is SSLException -> FetchFailure.Tls
        else -> FetchFailure.Connection
    }

    /** A loopback, link-local or RFC1918 IPv4 literal, or an IPv6 loopback, link-local or unique-local literal. Names are never resolved. */
    internal fun isLocalLiteral(host: String?): Boolean {
        val h = host?.removePrefix("[")?.removeSuffix("]") ?: return false
        Ipv4.parse(h)?.let { return isLocalV4(it) }
        if (':' !in h) return false
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false   // an IPv6 literal: parsed, never looked up
        if (a is Inet4Address) return isLocalV4(a)                                      // ::ffff:10.0.0.5
        return a.isLoopbackAddress || a.isLinkLocalAddress || (a.address[0].toInt() and ULA_MASK) == ULA_PREFIX
    }
    private fun isLocalV4(a: Inet4Address): Boolean = LOOPBACK.contains(a) || LINK_LOCAL.contains(a) || Rfc1918.contains(a)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistCopy.kt` (whole file):
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import java.util.Locale

/** Every Wordlists screen, import and Settings string (3B spec 9, 3C spec 7.2). Templates live in ToolCopyTableTest. */
object WordlistCopy {
    const val BACK = "Back"
    const val TITLE = "Wordlists"
    const val IMPORT = "Import file"
    const val IMPORTING = "Importing"
    const val BUILT_IN = "Built in"
    const val IMPORTED = "Imported"
    const val DELETE = "Delete"
    const val CANCEL = "Cancel"
    const val NO_IMPORTED = "No imported wordlists yet"
    const val TOO_LARGE = "That file is too large (limit 10 MB)"
    const val TOO_MANY = "That file has more than 200000 entries"
    const val EMPTY_FILE = "That file has no entries"
    const val UNREADABLE = "Could not read that file"
    const val LOADING = "Loading wordlists"
    const val DEFAULT_NAME = "Imported list"
    const val SETTINGS_SECTION = "Tools"
    const val SETTINGS_ROW = "Wordlists"
    // URL import (3C spec 5, 7.2)
    const val IMPORT_URL = "Import from URL"
    const val URL_LABEL = "URL"
    const val URL_PLACEHOLDER = "https://example.com/wordlist.txt"
    const val URL_IMPORT = "Import"
    const val ERR_URL = "Enter a web address starting with https:// or http://"
    const val ERR_URL_USER = "Remove the user name and password from the address"
    const val ERR_URL_LONG = "That address is too long"
    const val CANCELLED = "Import cancelled"
    const val FETCH_HOST = "Could not fetch: unknown host"
    const val FETCH_NO_ANSWER = "Could not fetch: no answer"
    const val FETCH_TLS = "Could not fetch: secure connection failed"
    const val FETCH_CONNECTION = "Could not fetch: connection failed"
    const val FETCH_INSECURE = "Could not fetch: redirected to an insecure address"
    const val FETCH_LOCAL = "Could not fetch: redirected to a local address"
    const val FETCH_REDIRECTS = "Could not fetch: too many redirects"
    private const val BYTES_PER_MB = 1_048_576.0
    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    /** One decimal place in MB of 1,048,576 bytes, always a full stop (spec 7.2). */
    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)
    fun entriesBuiltIn(n: Int) = "${entries(n)}, built in"
    fun entriesImported(n: Int, date: String) = "${entries(n)}, imported $date"
    fun recoveredName(n: Int) = "Recovered list $n"
    fun deletePrompt(name: String) = "Delete $name?"
    fun deleteDescription(name: String) = "Delete $name"
    fun imported(name: String, n: Int, skipped: Int) = "Imported $name, ${entries(n)}${if (skipped > 0) " ($skipped skipped)" else ""}"
    fun settingsSupport(imported: Int?) = if (imported == null) "3 built in" else "3 built in, $imported imported"
    fun fetching(read: Long, total: Long?) = if (total == null) "Fetching ${mb(read)} MB" else "Fetching ${mb(read)} of ${mb(total)} MB"
    fun fetchStatus(code: Int) = "Could not fetch: the server answered $code"
    fun fetchNotText(type: String?) = if (type == null) "Could not fetch: not a text file" else "Could not fetch: not a text file ($type)"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt`: add imports `import com.northwalessolutions.netprobe.wordlists.FetchFailure` and `import com.northwalessolutions.netprobe.wordlists.UrlImportResult`, and replace `object WordlistImport` with:
```kotlin
/** The snackbar text for an import (3B spec 9, 3C spec 5.3 and 7.2). */
object WordlistImport {
    fun message(result: ImportResult): String = when (result) {
        is ImportResult.Imported -> WordlistCopy.imported(result.meta.name, result.meta.entries, result.skipped)
        ImportResult.TooLarge -> WordlistCopy.TOO_LARGE
        ImportResult.TooMany -> WordlistCopy.TOO_MANY
        ImportResult.Empty -> WordlistCopy.EMPTY_FILE
        ImportResult.Unreadable -> WordlistCopy.UNREADABLE
    }
    fun message(result: UrlImportResult): String = when (result) {
        is UrlImportResult.Stored -> message(result.result)          // the store's own messages, reused as they are
        is UrlImportResult.Failed -> failure(result.reason)
        UrlImportResult.Cancelled -> WordlistCopy.CANCELLED
    }
    fun failure(f: FetchFailure): String = when (f) {
        FetchFailure.UnknownHost -> WordlistCopy.FETCH_HOST
        FetchFailure.NoAnswer -> WordlistCopy.FETCH_NO_ANSWER
        FetchFailure.Tls -> WordlistCopy.FETCH_TLS
        FetchFailure.Connection -> WordlistCopy.FETCH_CONNECTION
        is FetchFailure.Status -> WordlistCopy.fetchStatus(f.code)
        is FetchFailure.NotText -> WordlistCopy.fetchNotText(f.type)
        FetchFailure.Insecure -> WordlistCopy.FETCH_INSECURE
        FetchFailure.LocalTarget -> WordlistCopy.FETCH_LOCAL
        FetchFailure.TooManyRedirects -> WordlistCopy.FETCH_REDIRECTS
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.*" --tests "com.northwalessolutions.netprobe.ui.tools.ToolCopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures. `WordlistsScreenTest` (3B) still compiles: it finds the file pill by its tag `importWordlist`, not by its text.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlImport.kt app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt \
  app/src/test/java/com/northwalessolutions/netprobe/wordlists/UrlImportRulesTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt
git status --short
git commit -m "Phase 3C-1: URL import rules, result types and copy"
```

---

### Task 9: UrlWordlistFetcher: the HttpURLConnection fetch, redirects, streaming caps, cancel and the store commit (STRONGER MODEL)

The stronger model implements and reviews this task. It is the app's only internet code: a mistake follows a hostile redirect into the Director's LAN, sends something it should not, imports an HTML login page as a "wordlist", or reports "Import cancelled" after storing the list.

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcher.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt`, `wordlists/UrlWordlistFetcherTest.kt`; compile only (run in Task 11): `app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt` (a byte-for-byte copy), `wordlists/UrlWordlistFetcherDeviceTest.kt`

**Interfaces:**
- Consumes: `UrlImportRules`, `RedirectDecision`, `FetchFailure`, `UrlImportResult` (Task 8), `WordlistStore.import`, `WordlistStore.delete`, `WordlistStore.MAX_IMPORT_BYTES`, `ImportResult` (3B), `CancellableIo.closing` (3B), `HttpClient.USER_AGENT` (3B).
- Produces:
```kotlin
class UrlWordlistFetcher(open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun fetch(url: URL, store: WordlistStore, onProgress: (Long, Long?) -> Unit = { _, _ -> }): UrlImportResult
    companion object { const val URL_CONNECT_TIMEOUT_MS = 10_000; const val URL_READ_TIMEOUT_MS = 15_000; const val PROGRESS_STEP_BYTES = 65_536L } }
internal class CountingStream(inner: InputStream, total: Long?, onProgress: (Long, Long?) -> Unit) : FilterInputStream
```
- The fetch, exactly as spec 5.3 steps 1 to 7, on `Dispatchers.IO`:
  1. Every connection: `instanceFollowRedirects = false`, `useCaches = false`, the two timeouts, `User-Agent: NetProbe/0.1`, `Accept: text/plain, */*;q=0.5`; nothing else is set. No `Authorization`, no `Cookie`.
  2. The status read runs in `CancellableIo.closing(Closeable { conn.disconnect() })`; an `IOException` there is `failureOf(e)` unless the coroutine was cancelled, in which case it re-throws as a cancellation.
  3. 301, 302, 303, 307, 308 go through `UrlImportRules.redirect`; each followed hop opens a fresh connection with the same settings; any other status but 200 is `Status(code)`.
  4. `contentType` false is `NotText(media type)`; `Content-Length` above 10 MB is `Stored(TooLarge)` with no body byte read.
  5. The body: `onProgress(0, total)`, then a counting stream (progress at most every `PROGRESS_STEP_BYTES`), buffered, the first 8 KB sniffed for a NUL (`NotText(null)`), then `WordlistStore.import(displayName(finalUrl), stream)`, which enforces 10 MB while reading (after any transparent gzip), 200,000 entries, empty lists and names.
  6. Cancel: a watcher disconnects the connection the moment the job is cancelled, so a blocked read fails at once. The body step runs `NonCancellable`, so the store never sees a cancellation mid-commit: its read fails (`Unreadable`, nothing written) or, if it had just finished, the stored list is deleted again before the fetch reports the cancellation. The job then ends with `CancellationException`.
  7. Every connection is disconnected in a `finally`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

/** A scripted HTTP/1.1 server on 127.0.0.1 for the URL import tests: one request per connection, then close. Never leaves the machine. */
class LoopbackHttpServer(private val handler: (Request) -> Response) : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>)
    /** [head] is the status line and headers including the blank line; [body] writes the rest and may block (a slow body). */
    class Response(val head: String, val body: (OutputStream) -> Unit = {})

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    val port: Int = server.localPort
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    init { thread(isDaemon = true, name = "loopback-http") { acceptLoop() } }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: IOException) { return }   // closed by close(): the loop ends
            thread(isDaemon = true) { serve(s) }
        }
    }
    private fun serve(s: Socket) {
        s.use { socket ->
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val first = input.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = first.split(' ')
            val req = Request(parts[0], parts.getOrElse(1) { "/" }, headers)
            requests += req
            val r = handler(req)
            try {
                val out = BufferedOutputStream(socket.getOutputStream())
                out.write(r.head.toByteArray(Charsets.ISO_8859_1)); r.body(out); out.flush()
            } catch (_: IOException) {
                // the client hung up: expected for capped, refused and cancelled reads
            }
        }
    }
    fun url(path: String): URL = URL("http://$LOOPBACK:$port$path")
    override fun close() { server.close() }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 50
        fun ok(type: String?, body: ByteArray, contentLength: Boolean = true) = Response(
            "HTTP/1.1 200 OK\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + (if (contentLength) "Content-Length: ${body.size}\r\n" else "") +
                "Connection: close\r\n\r\n") { it.write(body) }
        fun status(code: Int, reason: String) = Response("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        fun redirect(code: Int, location: String?) = Response("HTTP/1.1 $code Moved\r\n" + (location?.let { "Location: $it\r\n" } ?: "") +
            "Content-Length: 0\r\nConnection: close\r\n\r\n")
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcherTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.CookieHandler
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections

/**
 * 3C spec 8.1 item 12: the rules and wiring against a loopback server over HTTP, on the desktop JDK. Android's OkHttp-backed
 * HttpURLConnection (gzip, disconnect, redirects) is proved by UrlWordlistFetcherDeviceTest in Task 11.
 */
class UrlWordlistFetcherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val servers = mutableListOf<LoopbackHttpServer>()
    @After fun stop() { servers.forEach { it.close() } }
    private fun serve(handler: (LoopbackHttpServer.Request) -> LoopbackHttpServer.Response) = LoopbackHttpServer(handler).also { servers += it }
    private fun store() = WordlistStore(tmp.newFolder("wl-${System.nanoTime()}"), emptyList())
    private fun noListFiles(s: WordlistStore) = s.dirForTest.listFiles { f -> f.name.endsWith(".txt") }.isNullOrEmpty()
    private val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
    private suspend fun fetch(s: WordlistStore, url: URL) = UrlWordlistFetcher().fetch(url, s) { read, total -> progress += read to total }
    private fun plainHead(extra: String = "") = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n${extra}Connection: close\r\n\r\n"

    @Test fun `a text plain list is imported and named from the url`() = runBlocking {
        val body = "admin\nlogin\n/backup\n".toByteArray()
        val srv = serve { LoopbackHttpServer.ok("text/plain; charset=utf-8", body) }
        val r = fetch(store(), srv.url("/lists/words.txt")) as UrlImportResult.Stored
        val imported = r.result as ImportResult.Imported
        assertEquals("words", imported.meta.name); assertEquals(3, imported.meta.entries)
        assertEquals("the total comes from Content-Length", 0L to body.size.toLong(), progress.first())
    }
    @Test fun `a 404, an html page and a nul byte body give their failures and store nothing`() = runBlocking {
        val srv = serve { req ->
            when (req.path) {
                "/missing" -> LoopbackHttpServer.status(404, "Not Found")
                "/page" -> LoopbackHttpServer.ok("text/html; charset=utf-8", "<html><body>Sign in</body></html>".toByteArray())
                else -> LoopbackHttpServer.ok("application/octet-stream", ByteArray(64) { if (it == 10) 0 else 'a'.code.toByte() })
            }
        }
        val s = store()
        assertEquals(UrlImportResult.Failed(FetchFailure.Status(404)), fetch(s, srv.url("/missing")))
        assertEquals(UrlImportResult.Failed(FetchFailure.NotText("text/html")), fetch(s, srv.url("/page")))
        assertEquals(UrlImportResult.Failed(FetchFailure.NotText(null)), fetch(s, srv.url("/blob.bin")))
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a content length over the cap is rejected before any body byte is read`() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 11000000\r\n")) { out -> out.write("a\n".repeat(1_000).toByteArray()); out.flush() } }
        val s = store()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), fetch(s, srv.url("/big.txt")))
        assertTrue("no body progress at all", progress.isEmpty()); assertTrue(noListFiles(s))
    }
    @Test fun `a body over 10 MB with no content length is TooLarge and nothing is kept`() = runBlocking {
        val chunk = "abcdefghij\n".repeat(6_000).toByteArray()   // 66,000 bytes; 170 of them are 11.2 MB
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out -> repeat(170) { out.write(chunk) }; out.flush() } }
        val s = store()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), fetch(s, srv.url("/huge.txt")))
        assertTrue(noListFiles(s)); assertTrue("no total without Content-Length", progress.all { it.second == null })
        assertTrue("progress is stepped, not per read", progress.size <= (WordlistStore.MAX_IMPORT_BYTES / UrlWordlistFetcher.PROGRESS_STEP_BYTES).toInt() + 2)
    }
    @Test fun `three redirects are followed and a fourth is refused`() = runBlocking {
        lateinit var srv: LoopbackHttpServer
        srv = serve { req ->
            when (req.path) {
                "/r1" -> LoopbackHttpServer.redirect(302, "/r2")
                "/r2" -> LoopbackHttpServer.redirect(301, "http://127.0.0.1:${srv.port}/r3")
                "/r3" -> LoopbackHttpServer.redirect(307, "/final/list.txt")
                "/a1" -> LoopbackHttpServer.redirect(302, "/a2")
                "/a2" -> LoopbackHttpServer.redirect(302, "/a3")
                "/a3" -> LoopbackHttpServer.redirect(302, "/a4")
                "/a4" -> LoopbackHttpServer.redirect(302, "/final/list.txt")
                else -> LoopbackHttpServer.ok("text/plain", "one\ntwo\n".toByteArray())
            }
        }
        val s = store()
        val ok = fetch(s, srv.url("/r1")) as UrlImportResult.Stored
        assertEquals("named from the final URL", "list", (ok.result as ImportResult.Imported).meta.name)
        assertEquals(UrlImportResult.Failed(FetchFailure.TooManyRedirects), fetch(s, srv.url("/a1")))
        assertEquals("the fourth hop's target is never requested", 1, srv.requests.count { it.path == "/final/list.txt" })
    }
    @Test fun `a redirect without a location is the server's status`() = runBlocking {
        val srv = serve { LoopbackHttpServer.redirect(302, null) }
        assertEquals(UrlImportResult.Failed(FetchFailure.Status(302)), fetch(store(), srv.url("/x.txt")))
    }
    @Test fun `requests carry the user agent and accept header and never a cookie or authorization`() = runBlocking {
        assertNull("the app installs no CookieHandler", CookieHandler.getDefault())
        val srv = serve { LoopbackHttpServer.ok("text/plain", "x\n".toByteArray()) }
        fetch(store(), srv.url("/l.txt"))
        val h = srv.requests.single().headers
        assertEquals("NetProbe/0.1", h["user-agent"]); assertEquals("text/plain, */*;q=0.5", h["accept"])
        assertFalse("cookie" in h); assertFalse("authorization" in h)
    }
    @Test fun `a refused connection is a connection failure`() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }   // free, then closed: nothing listens
        assertEquals(UrlImportResult.Failed(FetchFailure.Connection), fetch(store(), URL("http://127.0.0.1:$port/l.txt")))
    }
    @Test fun `cancel during a slow body ends within a second and stores nothing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out ->
            repeat(600) { n -> out.write("word$n\n".repeat(100).toByteArray()); out.flush(); Thread.sleep(SLOW_CHUNK_MS) }
        } }
        val s = store()
        val job = launch(Dispatchers.Default) { UrlWordlistFetcher().fetch(srv.url("/slow.txt"), s) { _, _ -> started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(job.isCancelled)
        assertTrue(s.lists.value.isEmpty()); assertTrue(noListFiles(s))
    }

    private companion object { const val SLOW_CHUNK_MS = 100L }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcherDeviceTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.zip.GZIPOutputStream

/**
 * 3C spec 8.2 item 7 (G10): Android's OkHttp-backed HttpURLConnection, on the phone, against a ServerSocket on 127.0.0.1. Writes only
 * under cacheDir/url-fetch-test-*: never the app's wordlists directory, its database or its preferences.
 */
@RunWith(AndroidJUnit4::class)
class UrlWordlistFetcherDeviceTest {
    private val servers = mutableListOf<LoopbackHttpServer>()
    private lateinit var dir: File
    @Before fun setUp() { dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "url-fetch-test-${System.nanoTime()}") }
    @After fun tearDown() { servers.forEach { it.close() }; dir.deleteRecursively() }
    private fun serve(handler: (LoopbackHttpServer.Request) -> LoopbackHttpServer.Response) = LoopbackHttpServer(handler).also { servers += it }
    private fun store() = WordlistStore(dir, emptyList())
    private fun listFiles() = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
    private fun plainHead(extra: String = "") = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n${extra}Connection: close\r\n\r\n"

    @Test fun aGzipBodyOverTenMegabytesStopsAtTheCapAndReportsNoTotal() = runBlocking {   // assumption A4
        val plain = "abcdefghij\n".repeat(1_000_000).toByteArray()                            // 11 MB once decoded
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(plain) } }.toByteArray()
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n")) { it.write(gz) } }
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        val r = UrlWordlistFetcher().fetch(srv.url("/big.txt"), store()) { a, b -> progress += a to b }
        assertEquals("the cap applies to the decoded text", UrlImportResult.Stored(ImportResult.TooLarge), r)
        assertTrue("Android decodes gzip itself and drops the compressed length", progress.all { it.second == null })
        assertTrue("decoded bytes were counted", progress.any { it.first > 0 })
        assertTrue(listFiles().isEmpty())
    }
    @Test fun cancelDuringASlowBodyReturnsWithinOneSecondAndLeavesNoFile() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val srv = serve { LoopbackHttpServer.Response(plainHead()) { out ->
            repeat(600) { n -> out.write("word$n\n".repeat(100).toByteArray()); out.flush(); Thread.sleep(100) }
        } }
        val s = store()
        val job = launch(Dispatchers.Default) { UrlWordlistFetcher().fetch(srv.url("/slow.txt"), s) { _, _ -> started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin(); val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
        assertTrue(s.lists.value.isEmpty()); assertTrue(listFiles().isEmpty())
    }
    @Test fun aContentLengthAboveTheCapReadsNothing() = runBlocking {
        val srv = serve { LoopbackHttpServer.Response(plainHead("Content-Length: 11000000\r\n")) { out -> out.write("a\n".repeat(1_000).toByteArray()) } }
        val progress = mutableListOf<Pair<Long, Long?>>()
        assertEquals(UrlImportResult.Stored(ImportResult.TooLarge), UrlWordlistFetcher().fetch(srv.url("/big.txt"), store()) { a, b -> progress += a to b })
        assertTrue(progress.isEmpty())
    }
    @Test fun progressHasATotalWithContentLengthAndNoneWhenChunked() = runBlocking {
        val body = "x\n".repeat(70_000).toByteArray()   // 140 KB: two progress steps
        val srv = serve { req ->
            if (req.path == "/len.txt") LoopbackHttpServer.ok("text/plain", body)
            else LoopbackHttpServer.Response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n") { out ->
                out.write("${Integer.toHexString(body.size)}\r\n".toByteArray()); out.write(body); out.write("\r\n0\r\n\r\n".toByteArray())
            }
        }
        val withLength = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        UrlWordlistFetcher().fetch(srv.url("/len.txt"), store()) { a, b -> withLength += a to b }
        assertTrue(withLength.isNotEmpty() && withLength.all { it.second == body.size.toLong() })
        val chunked = Collections.synchronizedList(mutableListOf<Pair<Long, Long?>>())
        UrlWordlistFetcher().fetch(srv.url("/chunked.txt"), store()) { a, b -> chunked += a to b }
        assertTrue(chunked.isNotEmpty() && chunked.all { it.second == null })
    }
    @Test fun redirectsAreFollowedByTheFetcherAcrossHostsAndStopAtThree() = runBlocking {
        lateinit var a: LoopbackHttpServer
        lateinit var b: LoopbackHttpServer
        b = serve { req ->
            when (req.path) {
                "/h2" -> LoopbackHttpServer.redirect(302, "http://127.0.0.1:${a.port}/h3")
                else -> LoopbackHttpServer.ok("text/plain", "one\n".toByteArray())
            }
        }
        a = serve { req ->
            when (req.path) {
                "/h1" -> LoopbackHttpServer.redirect(302, "http://localhost:${b.port}/h2")      // another host name: 127.0.0.1 to localhost
                "/h3" -> LoopbackHttpServer.redirect(302, "http://localhost:${b.port}/list.txt")
                "/c1" -> LoopbackHttpServer.redirect(302, "/c2")
                "/c2" -> LoopbackHttpServer.redirect(302, "/c3")
                "/c3" -> LoopbackHttpServer.redirect(302, "/c4")
                "/c4" -> LoopbackHttpServer.redirect(302, "/list.txt")
                else -> LoopbackHttpServer.ok("text/plain", "one\n".toByteArray())
            }
        }
        assertTrue("three hops across two host names", UrlWordlistFetcher().fetch(a.url("/h1"), store()) is UrlImportResult.Stored)
        // The platform would follow up to 20 redirects itself; the fetcher stops at the fourth, so the platform is not following.
        assertEquals(UrlImportResult.Failed(FetchFailure.TooManyRedirects), UrlWordlistFetcher().fetch(a.url("/c1"), store()))
        assertEquals(0, a.requests.count { it.path == "/list.txt" })
    }
}
```

Then copy the server into the androidTest source set (identical content, same package):
```bash
mkdir -p app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists
cp app/src/test/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.UrlWordlistFetcherTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'UrlWordlistFetcher'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcher.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.HttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wordlist import from a URL (3C spec 5.2, 5.3). HttpURLConnection, not the LAN HttpClient: platform DNS, the system trust store with
 * hostname checks, streaming, and the default network (never bound to Wi-Fi; it may be cellular). Redirects are followed here, never
 * by the platform, so every hop passes UrlImportRules.redirect. No Authorization and no Cookie are ever sent; the app installs no
 * CookieHandler. The only internet code in the app.
 */
class UrlWordlistFetcher(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    suspend fun fetch(url: URL, store: WordlistStore, onProgress: (Long, Long?) -> Unit = { _, _ -> }): UrlImportResult =
        withContext(Dispatchers.IO) { follow(url, store, onProgress) }

    private sealed interface Step {
        data class Redirect(val url: URL) : Step
        data class Done(val result: UrlImportResult) : Step
    }

    private suspend fun follow(original: URL, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        var current = original
        var hops = 0
        while (true) {
            when (val step = step(original, current, hops, store, onProgress)) {
                is Step.Redirect -> { current = step.url; hops++ }
                is Step.Done -> return step.result
            }
        }
    }

    private suspend fun step(original: URL, current: URL, hops: Int, store: WordlistStore, onProgress: (Long, Long?) -> Unit): Step {
        val conn = open(current)
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = URL_CONNECT_TIMEOUT_MS
            conn.readTimeout = URL_READ_TIMEOUT_MS
            conn.setRequestProperty(USER_AGENT_HEADER, HttpClient.USER_AGENT)
            conn.setRequestProperty(ACCEPT_HEADER, ACCEPT)
            val code = try {
                CancellableIo.closing(Closeable { conn.disconnect() }) { conn.responseCode }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()   // a disconnect from Cancel is the cancellation, not a fetch failure
                return failed(UrlImportRules.failureOf(e))
            }
            if (code in REDIRECT_CODES) return redirect(original, current, conn.getHeaderField(LOCATION_HEADER), hops, code)
            if (code != HTTP_OK) return failed(FetchFailure.Status(code))
            val type = conn.contentType
            if (!UrlImportRules.contentType(type)) return failed(FetchFailure.NotText(UrlImportRules.mediaType(type)))
            val length = conn.contentLengthLong
            if (length > WordlistStore.MAX_IMPORT_BYTES) return Step.Done(UrlImportResult.Stored(ImportResult.TooLarge))   // no body byte is read
            return Step.Done(readBody(conn, current, length.takeIf { it >= 0 }, store, onProgress))
        } finally {
            runCatching { conn.disconnect() }   // cleanup only: a failed disconnect has nothing left to release
        }
    }

    private fun failed(f: FetchFailure): Step = Step.Done(UrlImportResult.Failed(f))

    private fun redirect(original: URL, current: URL, location: String?, hops: Int, code: Int): Step =
        when (val d = UrlImportRules.redirect(original, current, location, hops)) {
            is RedirectDecision.Follow -> Step.Redirect(d.url)
            RedirectDecision.TooMany -> failed(FetchFailure.TooManyRedirects)
            RedirectDecision.Insecure -> failed(FetchFailure.Insecure)
            RedirectDecision.LocalTarget -> failed(FetchFailure.LocalTarget)
            RedirectDecision.Invalid -> failed(FetchFailure.Status(code))
        }

    /**
     * The body runs NonCancellable so a Cancel never lands inside the store's commit: the watcher disconnects at once, the store's read
     * fails (Unreadable, nothing written), and a list that finished storing in the same instant is deleted before Cancelled is reported.
     * The enclosing coroutineScope then ends the job with CancellationException.
     */
    private suspend fun readBody(conn: HttpURLConnection, finalUrl: URL, total: Long?, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        val outer = currentCoroutineContext()[Job]
        return disconnectOnCancel(conn) {
            withContext(NonCancellable) {
                val r = importBody(conn, finalUrl, total, store, onProgress)
                if (outer?.isActive == false) {
                    ((r as? UrlImportResult.Stored)?.result as? ImportResult.Imported)?.let { store.delete(it.meta.id) }
                    UrlImportResult.Cancelled
                } else r
            }
        }
    }

    private suspend fun importBody(conn: HttpURLConnection, finalUrl: URL, total: Long?, store: WordlistStore, onProgress: (Long, Long?) -> Unit): UrlImportResult {
        onProgress(0, total)
        val raw = try { conn.inputStream } catch (e: IOException) { return UrlImportResult.Failed(UrlImportRules.failureOf(e)) }
        val buffered = BufferedInputStream(CountingStream(raw, total, onProgress), SNIFF_BUFFER_BYTES)
        val prefix = try {
            buffered.mark(UrlImportRules.BINARY_SNIFF_BYTES)
            readUpTo(buffered, UrlImportRules.BINARY_SNIFF_BYTES).also { buffered.reset() }
        } catch (e: IOException) {
            runCatching { buffered.close() }   // cleanup only
            return UrlImportResult.Stored(ImportResult.Unreadable)
        }
        if (UrlImportRules.looksBinary(prefix)) {
            runCatching { buffered.close() }   // cleanup only
            return UrlImportResult.Failed(FetchFailure.NotText(null))
        }
        return UrlImportResult.Stored(store.import(UrlImportRules.displayName(finalUrl), buffered))   // the store closes the stream
    }

    /** Disconnects [conn] the moment the caller is cancelled, so a read blocked inside [block] ends at once instead of at its timeout. */
    private suspend fun <T> disconnectOnCancel(conn: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { conn.disconnect() } }
        }
        try { block() } finally { finished.set(true); watcher.cancel() }
    }

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    companion object {
        const val URL_CONNECT_TIMEOUT_MS = 10_000
        const val URL_READ_TIMEOUT_MS = 15_000
        /** Progress reaches the pill at most once per this many bytes (spec 5.1). */
        const val PROGRESS_STEP_BYTES = 65_536L
        private const val SNIFF_BUFFER_BYTES = UrlImportRules.BINARY_SNIFF_BYTES * 2
        private const val HTTP_OK = 200
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT_HEADER = "User-Agent"
        private const val ACCEPT_HEADER = "Accept"
        private const val ACCEPT = "text/plain, */*;q=0.5"
        private const val LOCATION_HEADER = "Location"
    }
}

/** Counts the bytes read from the network (after any transparent gzip) and reports at most every PROGRESS_STEP_BYTES. */
internal class CountingStream(inner: InputStream, private val total: Long?, private val onProgress: (Long, Long?) -> Unit) : FilterInputStream(inner) {
    private var count = 0L
    private var reported = 0L
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
        return n
    }
    private fun advance(n: Long) {
        count += n
        if (count - reported >= UrlWordlistFetcher.PROGRESS_STEP_BYTES) { reported = count; onProgress(count, total) }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, and check the no-credentials rule**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
grep -rn "CookieHandler.setDefault\|Authenticator.setDefault\|CookieManager(" app/src/main --include=*.kt
grep -rln "HttpURLConnection" app/src/main --include=*.kt
```
Expected: `BUILD SUCCESSFUL` twice; the first grep finds nothing; the second lists only `wordlists/UrlWordlistFetcher.kt`.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcher.kt \
  app/src/test/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt app/src/test/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcherTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists/LoopbackHttpServer.kt app/src/androidTest/java/com/northwalessolutions/netprobe/wordlists/UrlWordlistFetcherDeviceTest.kt
git status --short
git commit -m "Phase 3C-1: wordlist URL fetch with redirect rules, streaming caps and cancel"
```

---

### Task 10: The Wordlists screen: "Import file" and "Import from URL" pills, the URL dialog, progress, Cancel, and the import controller on appScope

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportController.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt` (whole file), `AppContainer.kt` (`urlImport`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportControllerTest.kt`; compile only (run in Task 11): additions to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt`

**Interfaces:**
- Consumes: `UrlWordlistFetcher` (Task 9), `UrlImportRules.validate`, `UrlCheck`, `UrlImportResult`, `FetchFailure`, `WordlistImport.message` (Task 8), `WordlistCopy` (Task 8), `AppMessages` (3B), `ActionPill`, `BackRow`, `npFieldColors` (3B, MVP).
- Produces:
```kotlin
sealed interface UrlImportState { object Idle : UrlImportState; data class Fetching(val read: Long, val total: Long?) : UrlImportState }
class UrlImportController(scope: CoroutineScope, fetch: suspend (URL, (Long, Long?) -> Unit) -> UrlImportResult, messages: AppMessages, log: (Throwable) -> Unit = {}) {
    val state: StateFlow<UrlImportState>; val message: StateFlow<String?>
    fun attach(); fun detach(); fun consumeMessage(); fun start(url: URL): Boolean; fun cancel() }
data class WordlistsActions(val onBack: () -> Unit = {}, val onImport: () -> Unit = {}, val onDelete: (WordlistMeta) -> Unit = {},
                            val onImportUrl: (URL) -> Unit = {}, val onCancelUrl: () -> Unit = {})
@Composable fun WordlistsContent(lists, importing, message, onMessageShown, actions, formatDate = ..., loading = false, urlState: UrlImportState = UrlImportState.Idle)
AppContainer.urlImport: UrlImportController
```
- UI (spec 5.1): the pinned block keeps the back row and the title; beneath the title a `FlowRow` (so Cancel wraps at font scale 1.3 instead of clipping) with "Import file" (tag `importWordlist`) and "Import from URL" (tag `importUrl`), both disabled while either import runs or the store loads. "Import from URL" opens an `AlertDialog` "Import from URL" with one field "URL" (tag `urlField`, URI keyboard, placeholder, inline error once the field is not blank) and "Import" (tag `urlImport`, enabled only when the URL validates) and "Cancel". While fetching, the URL pill reads `WordlistCopy.fetching(read, total)` and a "Cancel" text button (tag `cancelUrl`, 48 dp) appears beside it. Cancel ends the fetch and the snackbar says "Import cancelled". Leaving the screen does not cancel; the result then goes to `AppMessages`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportControllerTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

/** 3C spec 5.1: one URL import at a time, on the app scope, with its result going to the visible screen or to AppMessages. */
@OptIn(ExperimentalCoroutinesApi::class)
class UrlImportControllerTest {
    private val url = URL("https://example.com/list.txt")

    @Test fun `a fetch shows progress, refuses a second start, and gives the attached screen its message`() = runTest {
        val messages = AppMessages(); val gate = CompletableDeferred<Unit>()
        val c = UrlImportController(backgroundScope, { _, progress -> progress(65_536, 200_000); gate.await(); UrlImportResult.Failed(FetchFailure.Status(404)) }, messages)
        c.attach()
        assertTrue(c.start(url)); runCurrent()
        assertEquals(UrlImportState.Fetching(65_536, 200_000), c.state.value)
        assertFalse("one URL import at a time", c.start(URL("https://example.com/other.txt")))
        gate.complete(Unit); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value)
        assertEquals("Could not fetch: the server answered 404", c.message.value); assertNull(messages.current.value)
        c.consumeMessage(); assertNull(c.message.value)
    }
    @Test fun `with the screen gone the result goes to AppMessages`() = runTest {
        val messages = AppMessages()
        val c = UrlImportController(backgroundScope, { _, _ -> UrlImportResult.Failed(FetchFailure.UnknownHost) }, messages)
        c.attach(); c.detach()
        c.start(url); runCurrent()
        assertNull(c.message.value); assertEquals("Could not fetch: unknown host", messages.current.value?.text)
    }
    @Test fun `cancel ends the job and says Import cancelled`() = runTest {
        val c = UrlImportController(backgroundScope, { _, _ -> awaitCancellation() }, AppMessages())
        c.attach(); c.start(url); runCurrent()
        assertTrue(c.state.value is UrlImportState.Fetching)
        c.cancel(); runCurrent()
        assertEquals(UrlImportState.Idle, c.state.value); assertEquals("Import cancelled", c.message.value)
        assertTrue("a new import may start", c.start(url))
    }
    @Test fun `an unexpected failure is logged and reported as a connection failure`() = runTest {
        val logged = mutableListOf<Throwable>()
        val c = UrlImportController(backgroundScope, { _, _ -> throw IllegalStateException("boom") }, AppMessages(), log = { logged += it })
        c.attach(); c.start(url); runCurrent()
        assertEquals("Could not fetch: connection failed", c.message.value); assertEquals(1, logged.size)
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt`: add `import java.net.URL` to its imports (`WordlistMeta` is already imported), and these members before the closing brace:
```kotlin
    private val urls = mutableListOf<URL>(); private var cancels = 0
    private fun showUrl(urlState: UrlImportState = UrlImportState.Idle, message: String? = null) = rule.setContent {
        NetProbeTheme { WordlistsContent(builtIns, false, message, {}, WordlistsActions(onImport = { imports++ }, onImportUrl = { urls += it }, onCancelUrl = { cancels++ }),
            formatDate = { "3 Jan 2026" }, urlState = urlState) }
    }
    @Test fun bothImportPillsArePinnedOutsideTheList() {   // 3C spec 8.2 item 8
        showUrl()
        val list = rule.onNodeWithTag("wordlistList").fetchSemanticsNode()
        for (tag in listOf("importWordlist", "importUrl")) {
            val pill = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            assertFalse(tag, generateSequence(pill.parent) { it.parent }.any { it.id == list.id })
        }
        rule.onNodeWithText("Import file").assertIsDisplayed(); rule.onNodeWithText("Import from URL").assertIsDisplayed()
    }
    @Test fun theUrlDialogValidatesAndImports() {
        showUrl()
        rule.onNodeWithTag("importUrl").performClick()
        rule.onNodeWithTag("urlField").performTextInput("javascript:alert(1)")
        rule.onNodeWithText("Enter a web address starting with https:// or http://").assertIsDisplayed()
        rule.onNodeWithTag("urlImport").assertIsNotEnabled()
        rule.onNodeWithTag("urlField").performTextClearance()
        rule.onNodeWithTag("urlField").performTextInput("example.com/list.txt")
        rule.onNodeWithTag("urlImport").assertIsEnabled().performClick()
        assertEquals(listOf("https://example.com/list.txt"), urls.map { it.toString() })
        rule.onNodeWithTag("urlField").assertDoesNotExist()   // the dialog closed
    }
    @Test fun whileFetchingThePillShowsProgressAndCancelIsPresent() {
        showUrl(UrlImportState.Fetching(1_258_291, 3_565_158))
        rule.onNodeWithText("Fetching 1.2 of 3.4 MB").assertIsDisplayed()
        rule.onNodeWithTag("importWordlist").assertIsNotEnabled(); rule.onNodeWithTag("importUrl").assertIsNotEnabled()
        rule.onNodeWithTag("cancelUrl").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
        assertEquals(1, cancels)
    }
    @Test fun cancelShowsImportCancelled() {
        showUrl(message = "Import cancelled")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Import cancelled").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("cancelUrl").assertDoesNotExist()
    }
```
(The existing `show(...)` and the 3B tests are unchanged and still compile: `WordlistsActions` and `WordlistsContent` only gain defaulted parameters.)

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.wordlists.UrlImportControllerTest" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED` with `Unresolved reference 'UrlImportController'`, `'UrlImportState'`, `No parameter with name 'onImportUrl'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportController.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import com.northwalessolutions.netprobe.engine.tools.AppMessages
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

sealed interface UrlImportState {
    object Idle : UrlImportState
    data class Fetching(val read: Long, val total: Long?) : UrlImportState
}

/**
 * One URL import at a time on [scope] (appScope in the app), so leaving the Wordlists screen does not cancel it (3C spec 5.1). Cancel
 * cancels the job; the fetch disconnects at once and stores nothing. The result text goes to the attached screen's snackbar through
 * [message], else to [messages] (the Home notice line).
 */
class UrlImportController(private val scope: CoroutineScope, private val fetch: suspend (URL, (Long, Long?) -> Unit) -> UrlImportResult,
                          private val messages: AppMessages, private val log: (Throwable) -> Unit = {}) {
    private val _state = MutableStateFlow<UrlImportState>(UrlImportState.Idle)
    val state: StateFlow<UrlImportState> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val attached = AtomicInteger(0)
    @Volatile private var job: Job? = null

    fun attach() { attached.incrementAndGet() }
    fun detach() { attached.decrementAndGet() }
    fun consumeMessage() { _message.value = null }

    /** False while another URL import runs. */
    @Synchronized
    fun start(url: URL): Boolean {
        if (job?.isActive == true) return false
        _state.value = UrlImportState.Fetching(0, null)
        job = scope.launch {
            val result = try {
                fetch(url) { read, total -> _state.value = UrlImportState.Fetching(read, total) }
            } catch (ce: CancellationException) {
                finish(WordlistCopy.CANCELLED)
                throw ce
            } catch (e: Exception) {
                log(e)
                UrlImportResult.Failed(FetchFailure.Connection)
            }
            finish(WordlistImport.message(result))
        }
        return true
    }

    fun cancel() { job?.cancel() }

    private fun finish(text: String) {
        _state.value = UrlImportState.Idle
        if (attached.get() > 0) _message.value = text else messages.post(text)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt` (whole file; `WordlistImport` is Task 8's, the list rows and the delete dialog are unchanged):
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.BackRow
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.wordlists.FetchFailure
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.UrlCheck
import com.northwalessolutions.netprobe.wordlists.UrlImportResult
import com.northwalessolutions.netprobe.wordlists.UrlImportRules
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import com.northwalessolutions.netprobe.wordlists.WordlistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class WordlistsActions(val onBack: () -> Unit = {}, val onImport: () -> Unit = {}, val onDelete: (WordlistMeta) -> Unit = {},
                            val onImportUrl: (URL) -> Unit = {}, val onCancelUrl: () -> Unit = {})

/** The snackbar text for an import (3B spec 9, 3C spec 5.3 and 7.2). */
object WordlistImport {
    fun message(result: ImportResult): String = when (result) {
        is ImportResult.Imported -> WordlistCopy.imported(result.meta.name, result.meta.entries, result.skipped)
        ImportResult.TooLarge -> WordlistCopy.TOO_LARGE
        ImportResult.TooMany -> WordlistCopy.TOO_MANY
        ImportResult.Empty -> WordlistCopy.EMPTY_FILE
        ImportResult.Unreadable -> WordlistCopy.UNREADABLE
    }
    fun message(result: UrlImportResult): String = when (result) {
        is UrlImportResult.Stored -> message(result.result)          // the store's own messages, reused as they are
        is UrlImportResult.Failed -> failure(result.reason)
        UrlImportResult.Cancelled -> WordlistCopy.CANCELLED
    }
    fun failure(f: FetchFailure): String = when (f) {
        FetchFailure.UnknownHost -> WordlistCopy.FETCH_HOST
        FetchFailure.NoAnswer -> WordlistCopy.FETCH_NO_ANSWER
        FetchFailure.Tls -> WordlistCopy.FETCH_TLS
        FetchFailure.Connection -> WordlistCopy.FETCH_CONNECTION
        is FetchFailure.Status -> WordlistCopy.fetchStatus(f.code)
        is FetchFailure.NotText -> WordlistCopy.fetchNotText(f.type)
        FetchFailure.Insecure -> WordlistCopy.FETCH_INSECURE
        FetchFailure.LocalTarget -> WordlistCopy.FETCH_LOCAL
        FetchFailure.TooManyRedirects -> WordlistCopy.FETCH_REDIRECTS
    }
}

private val SECTION_PAD = 10.dp
private val ROW_VERTICAL = 8.dp
private val ROW_END = 6.dp
private val DELETE_PAD = 12.dp
private val PILL_ROW_PAD = 6.dp
private val PILL_GAP = 8.dp
private val NO_LISTS: StateFlow<List<WordlistMeta>> = MutableStateFlow(emptyList())
private const val PICKER_MIME = "text/plain"

/**
 * The store, resolved off the main thread: its first access scans the built-in resources, so it never happens during composition.
 * Null until it is ready.
 */
@Composable
internal fun rememberWordlistStore(container: AppContainer): State<WordlistStore?> =
    produceState<WordlistStore?>(null, container) { value = withContext(Dispatchers.IO) { container.wordlistStore } }

/** The wordlist metadata, empty until the store has loaded. */
@Composable
internal fun rememberWordlists(container: AppContainer): State<List<WordlistMeta>> {
    val store by rememberWordlistStore(container)
    return (store?.lists ?: NO_LISTS).collectAsStateWithLifecycle()
}

/**
 * The Wordlists screen (3B spec 8.6, 3C spec 5.1), stateless: a pinned top block (back row, title, the two import pills and the URL
 * Cancel) and a scrolling list of built-in then imported lists. Delete confirms in an AlertDialog. No import control is inside the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordlistsContent(lists: List<WordlistMeta>, importing: Boolean, message: String?, onMessageShown: () -> Unit, actions: WordlistsActions,
                     formatDate: (Long) -> String = RelativeTime::defaultDate, loading: Boolean = false, urlState: UrlImportState = UrlImportState.Idle) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    var confirm by remember { mutableStateOf<WordlistMeta?>(null) }
    var askUrl by rememberSaveable { mutableStateOf(false) }
    val fetching = urlState as? UrlImportState.Fetching
    val busy = importing || fetching != null || loading
    Scaffold(containerColor = NetProbeColors.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
            BackRow(WordlistCopy.BACK, actions.onBack)
            Text(WordlistCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.padding(horizontal = Dimens.gutter))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = PILL_ROW_PAD), horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
                ActionPill(if (importing) WordlistCopy.IMPORTING else WordlistCopy.IMPORT, enabled = !busy, onClick = actions.onImport, tag = "importWordlist")
                ActionPill(fetching?.let { WordlistCopy.fetching(it.read, it.total) } ?: WordlistCopy.IMPORT_URL, enabled = !busy, onClick = { askUrl = true }, tag = "importUrl")
                if (fetching != null) TextButton(onClick = actions.onCancelUrl, modifier = Modifier.heightIn(min = Dimens.touch).testTag("cancelUrl")) {
                    Text(WordlistCopy.CANCEL, style = NetProbeText.action, color = NetProbeColors.signal)
                }
            }
        }
    }) { pad ->
        val builtIn = lists.filter { it.builtIn }; val imported = lists.filter { !it.builtIn }
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("wordlistList")) {
            if (loading) item(key = "loading") {
                Text(WordlistCopy.LOADING, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = SECTION_PAD).testTag("wordlistsLoading"))
            } else {
            item(key = "builtInHeader") { SectionHeader(WordlistCopy.BUILT_IN) }
            items(builtIn, key = { it.id }) { WordlistRow(it.name, WordlistCopy.entriesBuiltIn(it.entries), null) }
            item(key = "importedHeader") { SectionHeader(WordlistCopy.IMPORTED) }
            }
            if (!loading && imported.isEmpty()) item(key = "empty") {
                Text(WordlistCopy.NO_IMPORTED, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = SECTION_PAD))
            }
            items(imported, key = { it.id }) { meta ->
                WordlistRow(meta.name, WordlistCopy.entriesImported(meta.entries, formatDate(meta.importedAt ?: 0L)), meta.id) { confirm = meta }
            }
        }
    }
    confirm?.let { meta ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = NetProbeColors.surface,
            title = { Text(WordlistCopy.deletePrompt(meta.name), style = NetProbeText.rowName, color = NetProbeColors.text) },
            confirmButton = { TextButton({ confirm = null; actions.onDelete(meta) }, modifier = Modifier.testTag("confirmDelete")) { Text(WordlistCopy.DELETE, color = NetProbeColors.signal) } },
            dismissButton = { TextButton({ confirm = null }) { Text(WordlistCopy.CANCEL, color = NetProbeColors.textSecondary) } })
    }
    if (askUrl) UrlDialog(onDismiss = { askUrl = false }) { url -> askUrl = false; actions.onImportUrl(url) }
}

/** "Import from URL" (3C spec 5.1): one field, validated as typed; the error shows once the field is not blank. */
@Composable
private fun UrlDialog(onDismiss: () -> Unit, onImport: (URL) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val check = UrlImportRules.validate(text)
    val ok = check as? UrlCheck.Ok
    val error = (check as? UrlCheck.Invalid)?.error?.takeIf { text.isNotBlank() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = NetProbeColors.surface,
        title = { Text(WordlistCopy.IMPORT_URL, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("urlField"), label = { Text(WordlistCopy.URL_LABEL) },
                placeholder = { Text(WordlistCopy.URL_PLACEHOLDER) }, singleLine = true, isError = error != null,
                supportingText = error?.let { e -> { Text(e, color = NetProbeColors.error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), textStyle = NetProbeText.data,
                colors = npFieldColors(), shape = RoundedCornerShape(8.dp))
        },
        confirmButton = {
            TextButton({ ok?.let { onImport(it.url) } }, enabled = ok != null, modifier = Modifier.testTag("urlImport")) {
                Text(WordlistCopy.URL_IMPORT, color = if (ok != null) NetProbeColors.signal else NetProbeColors.border)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(WordlistCopy.CANCEL, color = NetProbeColors.textSecondary) } })
}

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = SECTION_PAD)) {
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.surface)
        Text(title, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
            modifier = Modifier.padding(start = Dimens.gutter, top = SECTION_PAD, bottom = 4.dp).semantics { heading() })
    }
}

@Composable
private fun WordlistRow(name: String, line: String, deleteId: String?, onDelete: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.gutter, end = ROW_END, top = ROW_VERTICAL, bottom = ROW_VERTICAL),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NetProbeText.secondary, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        if (deleteId != null) {
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = WordlistCopy.deleteDescription(name) }.testTag("delete:$deleteId"), contentAlignment = Alignment.Center) {
                Text(WordlistCopy.DELETE, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = DELETE_PAD))
            }
        }
    }
}

/**
 * Route "wordlists": SAF import (ACTION_OPEN_DOCUMENT, text/plain, no permission) and URL import, both on appScope so leaving the
 * screen never cancels them; delete through the store.
 */
@Composable
fun WordlistsScreen(container: AppContainer, onBack: () -> Unit) {
    val store by rememberWordlistStore(container)
    val lists by rememberWordlists(container)
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val visible = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { visible.set(false) } }
    val urlImport = container.urlImport
    val urlState by urlImport.state.collectAsStateWithLifecycle()
    val urlMessage by urlImport.message.collectAsStateWithLifecycle()
    // While attached, the URL result goes to this screen's snackbar; once the screen is gone it goes to AppMessages (Home notice line).
    DisposableEffect(urlImport) { urlImport.attach(); onDispose { urlImport.detach() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = store
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        importing = true
        // On the app scope: leaving the screen mid-import must not cancel it; the result then goes to AppMessages (Home notice line).
        container.appScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { target.import(displayName(ctx, uri), it) } ?: ImportResult.Unreadable
                }
            } catch (ce: CancellationException) { throw ce } catch (_: Exception) { ImportResult.Unreadable }
            val text = WordlistImport.message(result)
            if (visible.get()) { importing = false; message = text } else container.appMessages.post(text)
        }
    }
    WordlistsContent(lists, importing, message ?: urlMessage, { if (message != null) message = null else urlImport.consumeMessage() },
        WordlistsActions(onBack = onBack, onImport = { launcher.launch(arrayOf(PICKER_MIME)) },
            onDelete = { meta -> scope.launch { store?.delete(meta.id) } },
            onImportUrl = { url -> urlImport.start(url) }, onCancelUrl = { urlImport.cancel() }),
        loading = store == null, urlState = urlState)
}

private fun displayName(ctx: Context, uri: Uri): String? =
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add imports `import com.northwalessolutions.netprobe.ui.wordlists.UrlImportController` and `import com.northwalessolutions.netprobe.wordlists.UrlWordlistFetcher`, and after the `wordlistStore` lazy add:
```kotlin
    /** Wordlist import from a URL (3C spec 5): one at a time on appScope. Building it touches no file and no network. */
    val urlImport: UrlImportController by lazy {
        UrlImportController(appScope, { url, progress -> UrlWordlistFetcher().fetch(url, wordlistStore, progress) }, appMessages,
            log = { Log.e(AppScopes.LOG_TAG, "url import", it) })
    }
```
(`wordlistStore` is first read inside the fetch, on appScope's `Dispatchers.Default`, never during composition.)

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.wordlists.*" --tests "com.northwalessolutions.netprobe.ui.tools.ToolCopyTableTest" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` twice.

- [ ] **Step 5: Task gate and the plan-wide checks**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
git diff --stat "$(cat docs/superpowers/plans/phase3c-base.txt)" -- app/schemas app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt
grep -rn "$(printf '\342\200\224')" app/src docs/superpowers/plans/2026-10-21-netprobe-phase3c-1.md || echo "no em-dashes"   # printf makes the U+2014 bytes, so this line holds none
```
Expected: `BUILD SUCCESSFUL`; the diff is empty (no schema change in 3C-1); "no em-dashes". Sum the `tests` attributes in `app/build/test-results/testDebugUnitTest/*.xml` and record the JVM count (883 at the base) in the commit message body.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportController.kt app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/test/java/com/northwalessolutions/netprobe/ui/wordlists/UrlImportControllerTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt
git status --short
git commit -m "Phase 3C-1: Wordlists import from URL with progress and cancel"
```

---

### Task 11: Deferred on-device QA (executed later, when the phone is available)

This task is NOT executed during the plan. Tasks 1 to 10 end with JVM tests green and every instrumented test compiled; no task has device evidence. When the Pixel 7 Pro (`28221FDH300AP9`) is on the Director's LAN, Bugsy runs every step below in order and records each result, pass or fail, in `docs/qa/phase3c-1-on-device.md`. Per the standing QA rule, Bugsy builds, installs on the device and smoke tests every core flow; a green suite is not a substitute, and code review is not QA. If any step fails, stop, record it, and tell the Director; never fix anything on his live data.

3C-1 has no schema change, so there is no migration test and no `DirectorCopyMigration` test (spec 8.3 item 0). The data-safety preamble still runs in full: the Settings reset check changes the Director's real tuning, and the manual-range and Aggressive scan checks change his preferences, so both are backed up first and restored at the end.

No 3C-1 check deletes data: Forget this network and Remove device are not exercised. Any check that turns out to need a destructive step runs only on a disposable network (the phone's own hotspot from a second device, or a guest SSID the Director does not keep), never on the home network, and is recorded with the network used.

Item 6 fetches files from the internet onto the phone. Bugsy proposes the exact URLs in the QA record first (a GitHub `/raw/` link to a small public wordlist, the same file's `raw.githubusercontent.com` link, a 404 on the same host, a `.zip`, an HTML page, and a large list for Cancel) and fetches nothing until the Director approves them; the plain HTTP case uses a file the Director serves on his LAN, also with his approval. No agent fetches anything on the PC.

**Files:**
- Create (at execution time): `docs/qa/phase3c-1-on-device.md`, `docs/qa/shots/phase3c-1/*.png`, `docs/qa/shots/phase3c-1/*.mp4`
- Backups and the rollback APK go OUTSIDE the repository (they hold the Director's network inventory): `K:/claude/netprobe_db_backup/phase3c-1/`

Common setup (as the 3B plan's Task 20):
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=28221FDH300AP9
PKG=com.northwalessolutions.netprobe
BK=/k/claude/netprobe_db_backup/phase3c-1
BASE=$(cat docs/superpowers/plans/phase3c-base.txt)    # the 3B head the phone runs (Task 1 step 0)
mkdir -p "$BK" docs/qa/shots/phase3c-1
# Binary-safe copy into the app's data directory, then an MD5 check (3B final review I3). Never stream a file with
# `adb shell ... < file`: on this PC it truncates binary stdin (3A device QA finding F5: 4220 of 323584 bytes written).
# `exec-in` is the form 3A's QA proved byte for byte. Usage: put <local file> <path under the app's data directory>
put() {
  "$ADB" -s $DEV exec-in run-as $PKG sh -c "cat > $2" < "$1"
  local want got
  want=$(md5sum < "$1" | cut -d' ' -f1)
  got=$("$ADB" -s $DEV exec-out run-as $PKG md5sum "$2" | cut -d' ' -f1)
  if [ "$want" = "$got" ]; then echo "MD5 OK $2 $got"; else echo "MD5 MISMATCH $2: PC $want, phone $got"; return 1; fi
}
# MD5 of a backup on the PC and of its source on the phone; they must match.
same() {
  local pc phone
  pc=$(md5sum < "$BK/$1.bak" | cut -d' ' -f1)
  phone=$("$ADB" -s $DEV exec-out run-as $PKG md5sum "$2" | cut -d' ' -f1)
  if [ "$pc" = "$phone" ]; then echo "MD5 OK $2 $pc"; else echo "MD5 MISMATCH $2: PC $pc, phone $phone"; return 1; fi
}
run() { ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$1" --console=plain 2>&1 | tail -12; }
```
If `leaveApksInstalledAfterRun` is rejected by this AGP version, stop and ask the Director before running any instrumented test without it.

- [ ] **Step 0: Back up the database and the preferences, before anything is installed or any instrumented test runs**

Force-stop the app so nothing writes during the copy:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do
  "$ADB" -s $DEV exec-out run-as $PKG cat databases/$f > "$BK/$f.bak"
done
"$ADB" -s $DEV exec-out run-as $PKG cat shared_prefs/netprobe.xml > "$BK/netprobe.xml.bak"
same netprobe.db databases/netprobe.db && same netprobe.db-wal databases/netprobe.db-wal && same netprobe.xml shared_prefs/netprobe.xml
ls -l "$BK"
```
Expected: four files; `netprobe.db.bak` and `netprobe.xml.bak` non-empty; `MD5 OK` three times (record each MD5 in the QA record). If either is empty or any MD5 mismatches, stop.

- [ ] **Step 1: Build the rollback APK from the recorded base, offline (no downloads)**

```bash
RB=/k/claude/netprobe_rollback_${BASE:0:7}
git worktree add "$RB" "$BASE"
cp local.properties "$RB/"
(cd "$RB" && ./gradlew :app:assembleDebug --offline --console=plain 2>&1 | tail -3)
cp "$RB/app/build/outputs/apk/debug/app-debug.apk" "$BK/netprobe-3b-${BASE:0:7}.apk"
git worktree remove --force "$RB"
ls -l "$BK"
```
Expected: `BUILD SUCCESSFUL` and the APK in `$BK`. If Gradle reports anything missing offline, stop and ask the Director; never drop `--offline`.

- [ ] **Step 2: Install over the 3B build (never clean) and check the data**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3c-1/q00-after-install.png
"$ADB" -s $DEV logcat -d | grep -E "AndroidRuntime|FATAL|IllegalStateException: Migration|Room" | tail -10
```
Check on screen: every marked host shows its mark; open two marked hosts and confirm notes, custom names, open ports and tool history. Open Settings and confirm the Director's tuning is what it was (no reset has happened). Record the JVM test count from the gate.

- [ ] **Step 3: The instrumented suite** (Compose tests use fake state; the device fetch test writes only under `cacheDir`):
```bash
for c in ui.settings.SettingsContentTest ui.settings.ExtendedSectionTest ui.settings.SettingsRowsTest ui.settings.SettingsToolsTest \
         ui.wordlists.WordlistsScreenTest wordlists.UrlWordlistFetcherDeviceTest \
         ui.home.HomeContentTest ui.home.RadarBlockTest ui.home.RadarStateTest ui.NavTransitionTest ui.ControlsMotionTest ui.ListMotionTest; do
  run com.northwalessolutions.netprobe.$c
done
run com.northwalessolutions.netprobe.ui.HomeScreenTest   # last: it launches the real activity, which opens netprobe.db
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ shared_prefs/
same netprobe.xml shared_prefs/netprobe.xml
```
Expected: every class passes; record each count. `UrlWordlistFetcherDeviceTest` reports 5 tests: its gzip case is the proof of assumption A4 and its cancel case of "Cancel ends the fetch within a second" on Android's `HttpURLConnection`. The prefs MD5 still matches the backup (no instrumented test touched the Director's preferences). A class name that does not exist on the branch is recorded and skipped, not invented. Timing tests that fail on a real frame clock are recorded with their message, not retried into a pass.

- [ ] **Step 4: Behaviour checks, each recorded pass or fail** (spec 8.3 items 1 to 7):
1. Settings: change two sliders (Sweep concurrency to 256, Port scan timeout to 1,000 ms) and two switches (SNMP off, Aggressive scan on). Each slider shows "Default 128" / "Default 600 ms"; tap one marker and confirm it restores that slider alone. Overflow, Reset to defaults: every value returns, the NVD key stays, the snackbar says "Settings reset to defaults" with Undo; tap Undo: every value returns to before the reset. Reset again and let the snackbar lapse. Force-stop and relaunch: the values persist. Then restore the Director's preferences:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
put "$BK/netprobe.xml.bak" shared_prefs/netprobe.xml
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
   Expected: `MD5 OK`; open Settings and confirm his tuning is back. Screenshot `s01-settings-markers` (markers shown) and `s01b-reset-snackbar`.
2. Aggressive scan off, manual list empty: a scan matches the Phase 2 check 1 baseline (time and host count within the Phase 2 record's range; no range summary, no extra notice).
3. Aggressive scan off, add manual `10.0.4.0/24`: the editor is enabled with "Swept first on every scan, with Aggressive scan on or off"; a scan sweeps it after the home subnet, bound to Wi-Fi (the Hue bridge at `10.0.4.20` is found); the summary reads "1 other range checked" and expands to "10.0.4.0/24  manual  swept, N found"; no evidence range is swept and no "probing ... for gateways" line appears. Then Aggressive scan on, Smart discovery off: the manual range is swept first, evidence ranges may follow, and no "probing ... for gateways" line appears.
4. Smart discovery on (manual list emptied again): `10.0.4.0/24` is found as in Phase 2 check 3, now listed as "smart discovery"; the `NetProbeExt` log line `gateway probe: ... connect attempts` is within 10 percent of 1,054 (the spec's 1,056 counts the 2 TEST-NET probes, which the log does not); no outward walk runs unless a seed answers. Record which seeds answered, and if any did, the walked units and hits.
```bash
"$ADB" -s $DEV logcat -d -s NetProbeExt | tail -5
```
5. Start a scan with Aggressive scan off and manual ranges empty, and during it turn Aggressive scan on and then Reset to defaults: the running scan's grouping, progress and range list do not change; the next scan uses the new values. Record a 15 s screen recording `r1-midrun-toggle`.
6. URL import, with the Director's approved URLs only (see the preamble): the GitHub `/raw/` link over HTTPS (redirects to `raw.githubusercontent.com`) imports; the `raw.githubusercontent.com` link imports; the plain HTTP URL on the LAN imports; the 404 says "Could not fetch: the server answered 404"; the `.zip` says "Could not fetch: not a text file (application/zip)" or "Could not fetch: not a text file"; the HTML page says "Could not fetch: not a text file (text/html)"; Cancel mid-fetch of the large list ends within a second with "Import cancelled" and no new list. Leave the screen during a fetch: the result reaches the Home notice line. Screenshots `s06-two-pills`, `s06b-url-dialog`, `s06c-fetching`. Afterwards delete every list this item imported (they are QA artefacts; record their names).
7. A wide mask cannot be produced on the Director's LAN: record "NOT RUN, covered by 8.1 item 3 (`HomeRangeTest`) and `DiscoveryEngineExtendedTest.aggressive on sweeps the limit block of a wide mask`" unless a client or guest network with a mask wider than /22 is available; if one is, run a scan with Aggressive scan off ("Scanned your /24 only") and on at limit /22 ("Scanned your /22 only") and record both.

- [ ] **Step 5: Screens for the Director at font scale 1.0 and 1.3** (`"$ADB" -s $DEV shell settings put system font_scale 1.3`, restored to 1.0 afterwards):
`s10-settings-top` (overflow menu open), `s11-aggressive-section` (directly under Discovery, Smart discovery greyed with Aggressive off), `s12-wordlists-fetching` (both pills and Cancel pinned; at 1.3 Cancel may wrap to the next line, which is the FlowRow's job, never clipped). The Director rules on the markers (G19: keep, or caption only).

- [ ] **Step 6: Restore the Director's preferences and confirm the data is untouched**

Items 3 to 6 changed his manual ranges and toggles; put his preferences back exactly:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
put "$BK/netprobe.xml.bak" shared_prefs/netprobe.xml
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
Expected: `MD5 OK`. Open Settings: his tuning, his manual ranges (if he had any) and Aggressive scan as he left it. Open Home: every mark is there.

- [ ] **Step 7: Record and hand over**

Write `docs/qa/phase3c-1-on-device.md`: the date, build commit, every step's result, the MD5s, the test counts per class, the screenshot and recording names, the attempt count and walk observations from item 4, the URLs used in item 6 and the Director's approval, and for the Director to rule on: the spec section 10 assumptions A1, A4 and A5 and open items 1, 2 and 5, the markers (G19), and every deviation and gap in the Plan Self-Review Notes below. Then commit only the QA record and its shots:
```bash
git add docs/qa/phase3c-1-on-device.md docs/qa/shots/phase3c-1
git status --short    # nothing else may be staged
git commit -m "Phase 3C-1 on-device QA record"
```
The backups and the rollback APK in `K:/claude/netprobe_db_backup/phase3c-1/` are never committed.

**Restore procedure (only on the Director's say-so).** This puts the 3B build and the step 0 backup back together. Every file is replaced with the app stopped, because a stale `-wal` or `-shm` next to a restored database can corrupt it:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
"$ADB" -s $DEV install -r -d "$BK/netprobe-3b-${BASE:0:7}.apk"   # -r keeps the data directory; -d allows going back to the older build
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do "$ADB" -s $DEV shell run-as $PKG rm -f databases/$f; done
for f in netprobe.db netprobe.db-wal; do put "$BK/$f.bak" databases/$f || break; done
put "$BK/netprobe.xml.bak" shared_prefs/netprobe.xml
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ shared_prefs/
```
Check: `MD5 OK` for all three files before going on. On any `MD5 MISMATCH`, do not start the app: run the same `put` again for that file, and if it still mismatches, stop and tell the Director (the backup on the PC is intact). Only then:
```bash
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
Expected: the app opens on the 3B build with every mark, note and setting. Record what was restored and why.

---
## Plan Self-Review Notes

**Spec coverage, by section:**
- Section 1 (settings reset): 1.1 defaults and the covered set, Task 1 (`SettingsDefaults`, `AppSettings.load` from `SettingsState()`); 1.2 overflow, one-edit reset, Undo with the newer key kept, Task 1 (`SettingsReset`) and Task 2 (`SettingsMenu`, the snackbar); 1.3 markers, Task 1 (`DefaultMarker`), Task 2 (sliders), Task 3 (the stepper). 14 covered values (D3); `listenMulticast` is 3C-2's.
- Section 2 (Aggressive scan): 2.1 `HomeRange` and both large-network notices, Tasks 4 and 6, every listed caller moved (engine Task 5, `ScanBinding` and its three callers Task 4, grouping and hero Task 6, the editor's own-network check Task 4); 2.2 `RunSettings` snapshot, Tasks 4 and 6 (radar, Quick scan all, Device-screen scans and tool runs each read once); 2.3 section order, copy and enabling, Tasks 2 and 3; 2.4 manual ranges always swept, binding, summaries and notices, Tasks 4, 5 and 6; 2.5 the first pass restated and the outward walk, Task 7.
- Section 5 (URL import): 5.1 UI, Task 10; 5.2 `HttpURLConnection`, Task 9; 5.3 rules, Task 8, and the fetch steps 1 to 7, Task 9.
- Section 6: the 3C-1 plan split and order are followed; nothing from 3C-2 (schema 4, manual tools, the listener) is in this plan.
- Section 7.2: every 3C-1 row is a Copy member with a template (Tasks 1, 3, 8); the removed names are asserted absent by reflection (`MENU_AGGRESSIVE`, `aggressiveDescription`, `MANUAL_ON`, `MANUAL_OFF`, `FETCH_OTHER_SITE`; `ManualCopy.ERR_PRIVATE` belongs to 3C-2, whose `ManualCopy` does not exist yet).
- Section 8: 8.1 items 1 to 13 are Tasks 1 (1, 2), 4 (3, 5), 6 (4, 8), 5 (6, 7), 7 (9, 10), 8 (11, 13), 9 (12), 3 and 8 (13); 8.2 items 4, 7 and 8 are compiled in Tasks 2, 3, 9 and 10 and run in Task 11; 8.3 is Task 11.

**Placeholder scan:** `grep -nE "TBD|TODO|similar to Task|implementer (completes|finishes|adds)|stub|placeholder|<[a-z ]+>"` over this file. Remaining hits are the Compose `placeholder =` parameter of the URL field and the editor, test-tag patterns written with angle brackets in prose (`default:<label>`), and the generic `<pattern>` in the standard JVM command; none is unwritten code. Every task gives its failing tests, complete production code or an exact replacement block, exact names, exact commands, the gate and an explicit staging list.

**Type consistency:**
- `RunSettings` (Task 4) is consumed by `ScanBinding.forRun` (4), `ExtendedOptionsOf` (5), `RunMemory.settings`, `baseState` and `discoverOn` (6), `DetailViewModel.scanHost` and the tool runner's `bind` (4). Every local holding one is named `runSettings`.
- `ExtendedOptions.evidence` and `DiscoveryOptions.aggressive` (Task 5) are appended last with defaults, so Phase 2's positional `ExtendedOptions(enabled, cap, probe, manual, known)` and 3A's positional `DiscoveryOptions(...)` in `discoverOn` compile.
- `GatewayProbe.run` returns `GatewayProbeResult` from Task 7 (it returned `List<Subnet>`); its only callers are the production lambda (rewritten) and `GatewayProbeTest` (one `.hits` added). The engine's `gatewayProbe` gains a fourth parameter; its only fake is `DiscoveryEngineExtendedTest.engine` (rewritten).
- `RunEnd.of(..., aggressive = extEnabled)` and `ExtendedNotices.of(status, aggressive = true)` default to the Phase 2 meaning, so `RunEndTest`, `ExtendedNoticesTest` and `CopyTableTest` call sites compile unchanged.
- `WordlistsActions` and `WordlistsContent` gain defaulted parameters only; 3B's `WordlistsScreenTest.show` compiles unchanged. `StepSlider` gains `default` after `enabled`, so `SettingsRowsTest`'s positional call compiles.
- `UrlImportResult`, `FetchFailure` and `UrlCheck` (Task 8) are consumed by Task 9 (fetcher), Task 10 (controller, dialog) and `WordlistImport` (Task 8, carried into Task 10's file unchanged).
- Checked against the code on `phase3b` at 33a6970: `ScanBinding.run(ip, env, maxPrefix, extEnabled, ...)` has exactly three callers (`HomeViewModel.quickScanHost`, `DetailViewModel.scanHost`, `AppContainer.toolRunner`); `SubnetMath.sweepRange` is called for the local range by `DiscoveryEngine.discover`, `ScanBinding`, `RowRules.row` and `SettingsScreen`; the hero reads `SubnetMath.of(env)` in `HomeContent`; `ExtendedNote` is matched exhaustively only in `ExtendedNotices.of`.

**Deviations, gaps and contradictions for the Director (the spec wins unless noted):**
1. **The task brief says "one redirect hop rule"; the spec (D16, 5.3) says up to 3 redirects to any host.** The plan follows the spec: `MAX_REDIRECTS = 3`, with the per-hop rules (no downgrade, no public-to-local). If he meant a single hop, it is one constant.
2. **A 3xx with no usable `Location` has no outcome in spec 5.3.** The plan adds `RedirectDecision.Invalid`, reported as "Could not fetch: the server answered 302". The plan also refuses a redirect whose URL carries user info, and treats IPv6 loopback, link-local and unique-local literals (and IPv4-mapped forms) as local; the spec names only IPv4 ranges. Both serve "no credentials" and "never from a public host to a local IP literal".
3. **Manual ranges with Aggressive scan off switch the neighbour table to Phase 2's `NeighbourRules`.** `ExtendedOptions.enabled` is true for them, and the engine keys the MVP neighbour loop on `!enabled`; the spec is silent. With no manual ranges the MVP loop is unchanged (Review Focus 1). The effect is that a MAC shared by many on-link IPs (proxy ARP) is not claimed, as with Aggressive scan on.
4. **Spec 2.2 snapshot grouping vs Phase 2's "display-time grouping regroups at once".** Home groups with the last radar run's snapshot until the next scan or a network change, so a manual range added or a limit changed after a scan regroups only at the next scan. The plan follows 2.2.
5. **The hero subtitle now names the home range (spec 2.1), not the mask.** On a wide mask with Aggressive scan off it reads "Tap to find what is on 172.20.9.0/24" where the MVP said "/16"; the network line keeps the mask.
6. **The "Large networks" caption is now partly wrong and is not in the copy table.** "Bigger networks are cut down to your own /24 unless widened here." is false with Aggressive scan on (the limit-sized block is swept). The plan leaves it (the copy table does not list it, and existing lines are not restructured); a one-line rewording is the Director's call.
7. **The walk's skip rule.** Spec 2.5 says a skipped unit is "skipped without counting as a miss" but not whether it resets the consecutive-miss count. The plan: neither a miss nor a hit, count unchanged (`GatewayWalkTest.two consecutive misses end a direction and an already probed unit is skipped without counting`).
8. **"Aggressive scan: no extra ranges to sweep" with Aggressive scan off.** When every manual range overlaps the home range the engine still records `NO_EXTRA_RANGES`; `ExtendedNotices.of(aggressive = false)` drops it, as spec 2.4 requires.
9. **The URL display name's host fallback loses its suffix.** `WordlistStore.cleanName` strips after the last dot, so a URL with no file segment names the list "example" for `example.com`. Left as the spec describes (5.3).
10. **Cancel within a second is proved for the body, not the connect.** On the desktop JDK a `disconnect()` during a blocking connect may wait for `URL_CONNECT_TIMEOUT_MS`; Android's OkHttp-backed connection cancels the call. The JVM and device tests cancel during the body, which is what spec 8.2 item 7 asks.
11. **Device QA item 6 fetches from the internet.** The standing "no downloads" rule covers agents; this check makes the app download third-party files to the phone, so Task 11 makes the Director approve the URLs first.
12. **Device QA item 4's attempt figure.** The `NetProbeExt` log counts 1,054 attempts for the Director's network; the spec's 1,056 adds the 2 TEST-NET pre-check probes. The check uses 1,054 within 10 percent.
13. **3C-1 starts only after the 3B device QA passes (spec section 6).** Task 1 Step 0 stops if `docs/qa/phase3b-on-device.md` is missing; only the controller can rule to start early.

**Review Focus mapping:**
1. Toggle-off regression: Task 4 `HomeRangeTest`, `ScanBindingTest.bindOutside false is unbound for any target`; Task 5 `ExtendedOptionsOfTest.aggressive off with no manual ranges is ExtendedOptions()`, `DiscoveryEngineExtendedTest.aggressive off with no manual ranges is the toggle off result` and Phase 2's `toggle off changes nothing`; device check Task 11 item 2.
2. Manual range leak: Task 4 `ScanBindingTest.aggressive off with one manual range binds a target outside the home range`; Task 5 `DiscoveryEngineExtendedTest.aggressive off with one manual range sweeps only that range, bound, with no evidence and no probe`; device check item 3.
3. Walk runaway: Task 7 `GatewayWalkTest` (13 tests) and `DiscoveryEngineExtendedTest.the walk budget is the cap less what the first pass already fills`; device check item 4.
4. Mid-run change: Task 6 `RunSnapshotTest` (2); device check item 5.
5. URL import safety: Task 8 `UrlImportRulesTest` (10); Task 9 `UrlWordlistFetcherTest` (9) and `UrlWordlistFetcherDeviceTest` (5), run in Task 11 step 3; Task 10 `UrlImportControllerTest` (4); device check item 6.
