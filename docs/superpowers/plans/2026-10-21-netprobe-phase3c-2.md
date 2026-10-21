# NetProbe Phase 3C-2 Implementation Plan: schema 4, manual tools from the Home overflow, and multicast listening between scans

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the Director's last two feedback lines on top of 3C-1. "Manual tools page for manually firing off tools without needing to use a scan": a "Run a tool..." item in the Home overflow opens a screen that takes any IPv4 address and an optional port, runs any of the five 3B tools through the existing `ToolRunner`, and shows the results, history and export in the existing sheet. A target inside the network's known ranges is bound to Wi-Fi or Ethernet; any other target goes out unbound so a VPN route works, and the results header says which ("via Wi-Fi", "via Ethernet", "via VPN or other route"). "Can you listen for multicast on wifi on android?": a Settings switch "Listen between scans", default off, holds its own `MulticastLock` only while on, in the foreground and on a LAN, hears mDNS answers and SSDP NOTIFYs, and adds heard devices through the guarded radar write, bounded to the network's known ranges and 32 new rows per session. Schema 3 to 4 is additive: `hosts.manualOnly` and `tool_runs.route`.

**Architecture:** Same single module and MVVM shape. A manual target is a `hosts` row: the existing row for (network, IP), or a new row flagged `manualOnly` that `HostDao.forNetwork` hides from Home, its counts and Quick scan all, that a scan or a heard packet promotes by clearing the flag, and that is deleted when its run never starts. `ToolTarget` gains `route: ToolRoute?`; when set, `ToolRunner` binds through a new `routeBind` (`ManualRoute.bind`) instead of `ScanBinding`, and stores the route in `tool_runs.route`. The pure `KnownRanges` (home range, manual ranges, the /24 of every visible private host) decides both the manual route and where the listener may insert. The listener is `ListenController` on `appScope`: one session per (plan, env, network epoch), keyed with `collectLatest`, each session with its own lock hold, sockets, bounded queue, repeat filter and `HeardWriter`, which drops anything not for the session's network row and enters the inventory only through `RadarCollector` with `upsertHeard`.

**Tech Stack:** Unchanged from 3C-1: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00, navigation-compose 2.9.0, Room 2.7.1 with KSP (schema 3 to 4 here), kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, JUnit 4, Turbine, AndroidX test, `room-testing` (already declared). No additions, no version bumps, no new Gradle plugins. `java.net.MulticastSocket` and `android.net.wifi.WifiManager.MulticastLock` are platform classes.

**Spec:** `docs/superpowers/specs/2026-10-21-phase3c-feedback-design.md` at 33a6970 is binding: sections 3, 4, 6 (plan 3C-2), 7.1, 7.2 (the 3C-2 rows), 8.1 items 14 to 24, 8.2 items 1 to 3, 5 and 6, and 8.4. Its Gruff review (`docs/superpowers/plans/2026-10-21-phase3c-gruff-review.md`) explains the reasoning; the spec applied it, and where they differ the spec wins. The controller rulings in `.superpowers/sdd/2026-10-21-netprobe-phase3c/rulings.md` bind and override the spec where they say more: "via Ethernet" is allowed; the listener fetches the UPnP description of a heard SSDP device only when the device is inside the network's known ranges and the switch is on, bounded as discovery's fetch is; a name-only mDNS sighting is dropped; rigour standard. The 3C-1 plan (`2026-10-21-netprobe-phase3c-1.md` at 82084d3) owns every name this plan consumes from 3C-1 (`RunSettings`, `HomeRange`, `ScanBinding.forRun`, `SettingsDefaults`, `SettingsContent`, `SettingsUi`, the renamed Aggressive scan copy, manual ranges always swept); the 3B plan owns the tools framework. Gaps and contradictions found while planning are in the Plan Self-Review Notes and go to the Director in the Task 12 record.

**Roles:** The stronger model implements and reviews Task 1 (the schema 4 migration and the Director-copy test), Tasks 3 and 5 (the manual tools routing: `ManualRoute`, the runner's `routeBind`, and the start path that creates and discards hidden rows), and Tasks 10 and 11 (the listener's writer and controller: network tagging, the drop rules, the lock lifecycle). Every other task is standard. Ada implements, Bugsy reviews each task, Gruff reviews this plan, Dot does the final review (rigour standard, controller ruling). The plan has 12 tasks: 11 built and JVM-tested now, and Task 12, the deferred on-device QA.

## Global Constraints

Carried from the 3C-1 plan (unchanged, they still bind):

- Kotlin 2.1.21, AGP 8.10.1, Compose BOM 2025.06.00, Room 2.7.1 KSP with exported schema, targetSdk 30.
- No new dependencies, no version bumps, no new repositories, no Gradle plugins, no Gradle file edits.
- No downloads or installs of anything by any agent or subagent. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director. No JVM or instrumented test touches the internet or the real LAN: mDNS and SSDP packets are authored byte arrays in the test sources (spec section 6), listener sockets are fakes, and the one real socket test binds an ephemeral port.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- No attribution lines in commits: plain messages, no `Co-Authored-By` or any other trailer.
- No disclaimers, warnings or authorisation prompts in UI copy. The manual tools screen accepts any address (ruling 3) without a confirmation dialog.
- Scan / Stop and every control that starts or stops work (the manual tools Run / Stop pill, its fields and tool row) live in pinned blocks, never inside a scroll container. The manual history list is the only scrolling part of that screen.
- Every user-visible string lives in a Copy object (`ManualCopy`, `ListenCopy`, `HomeCopy`, `ToolCopy`) and has a regex template in `CopyTableTest` (Home, manual tools, listening) or `ToolCopyTableTest` (`ToolCopy`); regex templates use doubled backslashes. Strings 3C-2 does not change stay where they are.
- Block bodies (`{ ... }`) for every function in which `return` appears; no `return` inside an expression body.
- Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so NetProbe is never uninstalled and the Director's data survives.
- THE PHONE IS NOT AVAILABLE during Tasks 1 to 11. Every task is built and tested on the JVM. Compose UI tests, DAO tests and migration tests are written in full and must compile (`./gradlew :app:compileDebugAndroidTestKotlin`) but are not run. No task may claim device evidence. Task 12 lists every instrumented run, screenshot and check; it runs when the phone is available.
- No new instrumented test may open, create or delete a database named `DB_NAME` ("netprobe.db") or touch the "netprobe" `SharedPreferences` or `files/wordlists/`: migration tests use `MIGRATION_TEST_DB` or the pushed copy `netprobe-migration-copy.db`, DAO tests use in-memory databases, Compose tests drive stateless content with in-memory state. The existing exception is 3A's `HomeScreenTest`; Task 12 runs it last, after the backup.
- Every commit stages the files its task names and checks `git status --short` before committing; never `git add -A` or `git add .`.
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`, never a global `gradle`. Never kill python or java processes wholesale.
- Standard commands used by every task:
  - JVM, one class or package: `./gradlew :app:testDebugUnitTest --tests "<pattern>" --console=plain 2>&1 | tail -20`
  - androidTest compile only: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8`
  - Task gate, every task ends with it green: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6`, expected `BUILD SUCCESSFUL`.
- Naming (review checklist item 1): a local or parameter holding a `RunSettings` is `runSettings`; no local, parameter or member function is named `run`; a `ToolRunner.Start` result is `started` or `result`.

New in 3C-2:

- **Schema 4 is the only schema change, and it is additive.** `MIGRATION_3_4` is exactly two `ALTER TABLE ... ADD COLUMN` statements with no `UPDATE`, `DELETE`, `DROP` or `CREATE`; `NetProbeDb` is version 4 with `MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4` and never `fallbackToDestructiveMigration`; `4.json` is generated by KSP and committed, never hand-written; `1.json` to `3.json` are never edited.
- **The hidden row rule.** `HostDao.forNetwork` (Home, `HomeStats`, groups, search, Quick scan all, and the known /24s of the listener and the manual route) never returns a `manualOnly` row; `HostDao.allForNetwork` (Forget's tool check, the canary `knownIps`) returns every row. A manual row is promoted only by `HostDao.updateScan` (a scan or a heard packet finding the IP). It is deleted only by `HostDao.deleteManualIfUnused` (its own refused start) or `HostDao.deleteOrphanManual` (the start-up sweep), both of which keep any row that has a tool run or has been promoted, and by Forget's cascade.
- **Scans keep Phase 2's bind rules.** Radar scans, Quick scan all, Device-screen scans and Device-screen tool runs bind exactly as after 3C-1 (`ScanBinding.forRun`). Only a `ToolTarget` with `route` set (built only by `ManualStarter`) uses `ManualRoute.bind`: `WIFI` and `ETHERNET` bind to the Wi-Fi or Ethernet `Network` or fail with `BindFailedException`, never unbound; `OTHER` is unbound. The runner's default `routeBind` refuses a `WIFI` or `ETHERNET` route.
- **The listener sends nothing to the LAN except one bounded UPnP description GET** per new heard IP inside the known ranges (controller ruling), bound to Wi-Fi: no mDNS query, no M-SEARCH. It never writes except through `RadarCollector.store` with `HostRepository.upsertHeard` (the write lock, `RemovedHosts.skip`, `IdentityWrite.sameDevice`), never while a radar scan runs or an Undo is pending, and never for a network row other than the one its session resolved.
- **Lock lifecycle.** `Locks.listen` (`netprobe-listen`) is a separate `MulticastLock` from the scan's `netprobe-mdns`; nothing but `ListenController` acquires or releases it, and the controller holds it only inside a session (switch on, foreground, LAN up, network row resolved, at least one socket open). No service, no wake lock, nothing in the background.
- **Starts from the activity, not the Application.** The orphan sweep and `ListenController.start()` run from `MainActivity.onCreate` through `AppContainer.onActivityCreated()`, so an instrumented test process (which runs `NetProbeApp.onCreate` but no activity) never opens `netprobe.db` through them.
- **Whole-file blocks are guarded (3C-1 plan review G1).** This plan's blocks were written against 82084d3 plus the 3C-1 plan's code. Files 3C-1 edits are changed here only by targeted "replace X with Y" edits, which fail loudly if the text moved. The three existing files rewritten whole (`data/NetProbeDb.kt`, `engine/common/Locks.kt`, `ui/Nav.kt`) are untouched by 3C-1; before pasting each, run the `git diff --quiet 82084d3 -- <file>` line printed above it, and on STOP ask the controller to rebase the block.
- 3C-2 starts only after the 3C-1 device QA has passed (spec section 6). Task 1 Step 0 checks for the 3C-1 QA record and that `app/src/main` changed since 82084d3 only in the files the 3C-1 plan names, and appends the build base (line 3, from which Task 12's rollback APK is built) and the plan base 82084d3 (line 4) to `docs/superpowers/plans/phase3c-base.txt`.

## Review Focus

The five failure modes most likely to bite the Director. Each is pinned to a test in its owning task; the reviewer checks the test exists, fails on the bug, and was not bent to fit the code.

1. **The Director's data does not survive schema 4.** The migration must be two additive columns, every mark, note, override, port, network and tool run of his own database must come through unchanged, and Room must open the result. Pinned: Task 1 `MigrationSql34Test.exactly the two additive columns and nothing else`, `SchemaExportTest.version 4 keeps every version 3 table and column unchanged and adds only manualOnly and route`, `Migration34Test` (both tests) and `DirectorCopyMigration34Test.theDirectorsSchema3CopyMigratesWithEveryMarkNotePortAndToolRun`, run on his pushed copy in Task 12 Step 3 before the in-place install.
2. **A hidden manual row leaks or is left behind.** It must never appear on Home, in the counts or in Quick scan all; a later scan must merge into the same row (keeping its tool history), not create a second; a start the runner refuses must delete the row it created and keep one that existed. Pinned: Task 2 `ManualHostDaoTest` (all of it, notably `aManualRowIsHiddenFromForNetworkAndShownByAll`, `aScanFindingTheIpPromotesTheRowAndKeepsItsToolRuns`, `discardManualKeepsAPromotedOrStartedRow`); Task 5 `ManualStarterTest.a refused start deletes the row this call inserted and keeps a row that existed`; device check Task 12 item 2.
3. **A manual run takes the wrong route.** A target inside the known ranges must be bound to Wi-Fi or Ethernet, and with no binder must be refused or fail, never run unbound; a target outside them must run unbound whatever Aggressive scan says; Device-screen runs keep the scan binding. Pinned: Task 3 `ManualRouteTest` (all of it), `ToolRunnerRouteTest.a WIFI manual run with no binder fails with the bind wording and the tool never runs` and `the default route binder refuses a Wi-Fi route rather than run it unbound`; Task 5 `ManualStarterTest.a known-range target with no Wi-Fi binder is NoNetwork before any row exists`; device check Task 12 item 3.
4. **The listener writes where it must not.** Nothing outside the known ranges, nothing past 32 new rows a session, nothing while a radar scan runs or an Undo is pending, nothing for a removed device, nothing for a network other than the session's (including the window before Home catches up with a network change), and nothing from a malformed or flooding packet. Pinned: Task 8 `MdnsPacketTest` (all of it); Task 10 `HeardWriterTest` (all of it, notably `the 33rd new row of a session is dropped while an update to an existing row still applies` and `a packet for another network or another row is dropped`); Task 11 `ListenControllerTest.packets from network A queued at a change are discarded and B's are dropped until Home catches up`; device checks Task 12 items 4, 5 and 7.
5. **The listener's lock outlives its reason, or the scan releases it.** It must be released on ON_STOP, on the switch turning off or a reset, on network loss or change, and when no port opens; a scan ending must never release it. Pinned: Task 11 `ListenControllerTest` (the lifecycle tests), `LocksTest.the scan's release never releases the listener's lock`, `ListenSocketsTest.a null interface opens no socket, so nothing joins on a default or VPN interface`; device checks Task 12 items 4 and 5 (`dumpsys wifi`).

### Review checklist (every task)

- Variable shadowing: no local, parameter or member function named `run`, or named after an import; `runSettings` for every `RunSettings`.
- No progress or status work in a tight loop: the listener's readers offer to a bounded queue, the repeat filter runs before the queue, and the writer does at most one known-range read, one existence read and one store per accepted packet.
- No silent swallowing: every `catch` maps to a named result (`HeardOutcome`, `ToolRunStatus.FAILED`, `NoNetwork`) or is logged, or is a cleanup `runCatching` with a comment saying so; every coroutine path re-throws `CancellationException`.
- Every socket and lock is closed or released in a `finally`; every test socket is closed.
- No magic numbers: every limit is a named constant (`HEARD_QUEUE`, `HEARD_NEW_MAX`, `HEARD_REPEAT_MS`, `HEARD_REPEAT_KEYS`, `HEARD_MAX_PER_PACKET`, `HEARD_FETCH_MAX`, `HEARD_LOCATION_MAX_CHARS`, `MDNS_MAX_PACKET`, `SSDP_MAX_PACKET`, `MAX_NAME_LABELS`, `RECENT_RUNS`, `PORT_MIN`, `PORT_MAX`).
- Every test fails on the bug its name states; a test that would pass with the rule removed is rewritten, not kept.
- Every new string is a Copy member with a template; no new literal in a composable.

## File Structure

```
app/src/main/java/com/northwalessolutions/netprobe/
  AppContainer.kt          onActivityCreated and the orphan sweep (2); routeBind (3); removedWhileListening (10); listen (11)
  MainActivity.kt          container.onActivityCreated() (2)
  data/
    Entities.kt            HostEntity.manualOnly, ToolRunEntity.route (1)
    Migrations.kt          MIGRATION_3_4 (1)
    NetProbeDb.kt          version 4 (1, whole file)
    Daos.kt                forNetwork hides manual rows, allForNetwork, updateScan promotes, the two manual deletes, NetworkRun, recentForNetwork (2)
    Repositories.kt        ManualRow, HeardRun, allHostsForNetwork, upsertHeard, manualTarget, discardManual, deleteOrphanManual, recentForNetwork, startRun with route (2)
    NetworkSignature.kt    identityKey(NetworkEntity) (2)
    Settings.kt            SettingsState.listenMulticast and its key (9)
  engine/common/Locks.kt   LockHandle, Locks.listen (11, whole file)
  engine/discovery/
    KnownRanges.kt         (3)
    DnsWire.kt, MdnsPacket.kt (8); DeviceInfoTxt.kt delegates to DnsWire (8); Ssdp.kt SsdpParser.parseNotify (8)
    ListenGate.kt          ListenPlan, ListenGate, ListenStatus, ListenStatusRules (9)
    DiscoveredHost.kt      Source.HEARD (10); Heard.kt, HeardWriter.kt (10); UpnpDescription.kt UpnpFetcher opener (10)
    ListenSockets.kt, ListenController.kt (11)
  engine/tools/
    ToolId.kt              ToolTarget.route (3); ToolStore.kt startRun with route (2); ToolRunner.kt routeBind (3); ManualRoute.kt with ToolRoute (3)
    ToolApplicability.kt   SMB_PORT public (4)
  ui/Nav.kt                the "tools" route (7, whole file)
  ui/detail/DetailViewModel.kt   Remove device marks removedWhileListening (10)
  ui/home/
    HomeViewModel.kt       allHosts, canary knownIps (2); removedWhileListening cleared at scan start (10)
    HomeCopy.kt            MENU_RUN_TOOL (4)
    HomeComponents.kt, HomeContent.kt, HomeScreen.kt   the "Run a tool..." item (7)
    RadarCollector.kt      upsert parameter (10); ContainerHeardSink.kt (10)
  ui/manual/
    ManualCopy.kt, ManualTarget.kt (4); ManualStarter.kt (5); ManualPresenter.kt, ManualToolsViewModel.kt, ManualToolsScreen.kt (7)
  ui/settings/
    ListenCopy.kt (9); SettingsScreen.kt the switch and status line (9), the status wiring (11)
  ui/tools/
    ToolCopy.kt            ROUTE_* and routeLine (4); ToolsUiState.kt ToolResultsState.route (6); ToolResultsSheet.kt the route line (6)
app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/4.json   generated by KSP (1)
app/src/test/java/com/northwalessolutions/netprobe/
  data/MigrationSql34Test.kt, data/SchemaExportTest.kt additions (1); data/HeardRunTest.kt, data/NetworkKeyTest.kt (2); data/SettingsDefaultsTest.kt edits (9)
  engine/discovery/KnownRangesTest.kt (3); MdnsTestPackets.kt, MdnsPacketTest.kt, SsdpNotifyTest.kt, DnsWireTest.kt (8); ListenGateTest.kt (9)
  engine/discovery/HeardTestSupport.kt, HeardWriterTest.kt, HeardRepeatsTest.kt, HeardPacketsTest.kt (10); ListenSocketsTest.kt, ListenControllerTest.kt (11)
  engine/common/LocksTest.kt (11)
  engine/tools/ManualRouteTest.kt, ToolRunnerRouteTest.kt (3)
  ui/manual/ManualTargetTest.kt (4); ManualStarterTest.kt (5); ManualPresenterTest.kt (7)
  ui/home/CopyTableTest.kt additions (4, 9); ui/tools/ToolCopyTableTest.kt addition (4); ui/tools/ToolResultsRouteTest.kt (6)
app/src/androidTest/java/com/northwalessolutions/netprobe/
  data/Migration34Test.kt, data/DirectorCopyMigration34Test.kt (1); data/ManualHostDaoTest.kt (2)
  ui/tools/RouteLineTest.kt (6); ui/home/HomeMenuTest.kt, ui/manual/ManualToolsContentTest.kt (7); ui/settings/SettingsListenTest.kt (9)
docs/superpowers/plans/phase3c-base.txt   lines 3 and 4 appended (Task 1 Step 0)
docs/qa/phase3c-2-on-device.md            Task 12 record (written when the phone is available)
```

---

### Task 1: Schema 4: `manualOnly` and `route`, MIGRATION_3_4, the exported 4.json, and the migration tests including the Director's copy (STRONGER MODEL)

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`, `data/Migrations.kt`, `data/NetProbeDb.kt` (whole file)
- Create: `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/4.json` (generated by KSP, never hand-written)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/MigrationSql34Test.kt`; additions to `data/SchemaExportTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/data/Migration34Test.kt`, `data/DirectorCopyMigration34Test.kt`

**Interfaces:**
- Consumes: `HostEntity`, `ToolRunEntity`, `MIGRATION_1_2`, `MIGRATION_2_3`, `DB_NAME`, `MIGRATION_TEST_DB` (3A, 3B); `MigrationTestHelper` (`room-testing`).
- Produces:
```kotlin
data class HostEntity(..., @ColumnInfo(defaultValue = "0") val manualOnly: Boolean = false)     // last field
data class ToolRunEntity(..., val route: String? = null)                                        // last field: "WIFI", "ETHERNET", "OTHER" or null
val MIGRATION_3_4: Migration                                                                     // two ALTER TABLE ADD COLUMN, nothing else
@Database(..., version = 4) abstract class NetProbeDb   // open(): addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```
Both new fields are last with defaults, so every positional and named `HostEntity(...)` and `ToolRunEntity(...)` in main and test code compiles unchanged, and the column order of a freshly created schema 4 database matches a migrated one (the 3B Undo snapshot's `INSERT INTO tool_runs SELECT *` copies within one database either way).

- [ ] **Step 0: Confirm the start condition, check what changed since 82084d3, and record both bases (spec section 6, 3C-1 plan review G1)**

```bash
git status --short                                   # must be empty; see below for entries under docs/qa/
git rev-parse --abbrev-ref HEAD                      # the 3C-1 branch head (phase3c-1, or wherever the controller merged it)
test -f docs/qa/phase3c-1-on-device.md && echo "3C-1 QA record present" || echo "STOP: no 3C-1 device QA record"
P=app/src/main/java/com/northwalessolutions/netprobe/
comm -23 <(git diff --name-only 82084d3 HEAD -- app/src/main | sed "s|^$P||" | sort) <(printf '%s\n' \
  AppContainer.kt data/Settings.kt engine/discovery/CandidateRanges.kt engine/discovery/DiscoveryEngine.kt \
  engine/discovery/DiscoveryOptions.kt engine/discovery/GatewayProbe.kt netenv/Subnet.kt ui/detail/DetailViewModel.kt \
  ui/home/ExtendedNotices.kt ui/home/HomeContent.kt ui/home/HomeCopy.kt ui/home/HomeViewModel.kt ui/home/RunSettings.kt \
  ui/home/ScanBinding.kt ui/settings/ExtendedSection.kt ui/settings/SettingsCopy.kt ui/settings/SettingsScreen.kt \
  ui/wordlists/UrlImportController.kt ui/wordlists/WordlistCopy.kt ui/wordlists/WordlistsScreen.kt \
  wordlists/UrlImport.kt wordlists/UrlWordlistFetcher.kt | sort)                  # must print nothing
for f in data/NetProbeDb.kt engine/common/Locks.kt ui/Nav.kt; do git diff --quiet 82084d3 HEAD -- "$P$f" || echo "STOP: $f changed since 82084d3"; done
```
- If `git status` lists entries only under `docs/qa/`, the 3C-1 QA agent has not committed yet: wait for its commit, and never stage, move or delete those files.
- If the 3C-1 QA record is missing, or it records a failed item without the Director's ruling, stop and ask the controller: the spec starts 3C-2 only after the 3C-1 device QA passes.
- The `comm` line lists production files changed since 82084d3 that the 3C-1 plan does not name (for example a 3C-1 QA fix). If it prints any file, stop and give the controller the list; it decides whether a block below must be rebased. A STOP from the `for` line means a file this plan rewrites whole has changed: stop and ask.

Otherwise branch and append both bases (line 3 is the build base Task 12's rollback APK uses, line 4 the plan base the guards use; lines 1 and 2 are 3C-1's):
```bash
git checkout -b phase3c-2
printf '%s\nplan-3c2 82084d3\n' "$(git rev-parse --short HEAD)" >> docs/superpowers/plans/phase3c-base.txt
cat docs/superpowers/plans/phase3c-base.txt          # four lines
```

- [ ] **Step 1: Write the failing JVM tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/MigrationSql34Test.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** MIGRATION_3_4 against a recording database: two additive ALTERs and nothing else (3C spec 7.1, Review Focus 1). */
class MigrationSql34Test {
    private fun recorded(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String else fail("migration called ${method.name}, expected execSQL only")
            null
        } as SupportSQLiteDatabase
        MIGRATION_3_4.migrate(db)
        return sql
    }

    @Test fun `versions are 3 to 4`() {
        assertEquals(3, MIGRATION_3_4.startVersion); assertEquals(4, MIGRATION_3_4.endVersion)
    }
    @Test fun `exactly the two additive columns and nothing else`() {
        assertEquals(listOf("ALTER TABLE hosts ADD COLUMN manualOnly INTEGER NOT NULL DEFAULT 0", "ALTER TABLE tool_runs ADD COLUMN route TEXT"), recorded())
    }
    @Test fun `no statement rewrites, drops or recreates anything`() {
        for (s in recorded()) for (word in listOf("UPDATE", "DELETE", "DROP", "INSERT", "CREATE", "RENAME"))
            assertFalse("$word in an additive migration: $s", s.contains(word, ignoreCase = true))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/SchemaExportTest.kt`: add these two tests before the class's closing brace (the file's imports already cover them):
```kotlin
    @Test fun `version 4 is exported`() {
        assertEquals(4, root(4)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }
    @Test fun `version 4 keeps every version 3 table and column unchanged and adds only manualOnly and route`() {
        val v3 = columns(3); val v4 = columns(4)
        assertEquals("no table added or dropped", v3.keys, v4.keys)
        for ((table, cols) in v3) for ((name, field) in cols) {
            val col = v4.getValue(table)[name]
            assertNotNull("$table.$name was dropped", col)
            assertEquals("$table.$name affinity", field["affinity"], col!!["affinity"])
            assertEquals("$table.$name notNull", field["notNull"], col["notNull"])
            assertEquals("$table.$name default", field["defaultValue"], col["defaultValue"])
        }
        val added = v4.mapValues { (t, cols) -> cols.keys - v3.getValue(t).keys }.filterValues { it.isNotEmpty() }
        assertEquals(mapOf("hosts" to setOf("manualOnly"), "tool_runs" to setOf("route")), added)
        val manualOnly = v4.getValue("hosts").getValue("manualOnly")
        assertEquals("INTEGER", manualOnly["affinity"]!!.jsonPrimitive.content)
        assertTrue("manualOnly is NOT NULL", manualOnly["notNull"]!!.jsonPrimitive.boolean)
        assertEquals("0", manualOnly["defaultValue"]!!.jsonPrimitive.content)
        val route = v4.getValue("tool_runs").getValue("route")
        assertEquals("TEXT", route["affinity"]!!.jsonPrimitive.content)
        assertNotEquals("route must be nullable", true, route["notNull"]?.jsonPrimitive?.boolean)
        assertNull("route has no default", route["defaultValue"])
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.MigrationSql34Test" --tests "com.northwalessolutions.netprobe.data.SchemaExportTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'MIGRATION_3_4'`.

- [ ] **Step 3: Implement the entities, the migration and the database version**

`app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`:
1. Replace `import androidx.room.Entity` with
```kotlin
import androidx.room.ColumnInfo
import androidx.room.Entity
```
2. In `HostEntity`, replace
```kotlin
    /** Software names joined by a newline, written with the identity (spec 3.6). */
    val identSoftware: String? = null,
)
```
with
```kotlin
    /** Software names joined by a newline, written with the identity (spec 3.6). */
    val identSoftware: String? = null,
    /**
     * 3C spec 7.1: a manual tool target nothing has discovered. Hidden from every device list and count (HostDao.forNetwork); a scan or a
     * heard packet finding the IP clears it (HostDao.updateScan), keeping the row's tool history. SQL default 0, so MIGRATION_3_4 rewrites no row.
     */
    @ColumnInfo(defaultValue = "0") val manualOnly: Boolean = false,
)
```
3. In `ToolRunEntity`, replace
```kotlin
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String,
)
```
with
```kotlin
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String,
    /** 3C spec 4.5: how a manual run left the phone, a ToolRoute name ("WIFI", "ETHERNET", "OTHER"); null for Device-screen and pre-4 runs. */
    val route: String? = null,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt`: append at the end of the file:
```kotlin

/**
 * Schema 3 to 4 (3C spec 7.1): two additive columns. manualOnly has a SQL default of 0, so every existing host stays visible; route is
 * nullable with no default, so every existing run reads "no route". No row is rewritten, nothing is dropped or recreated.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN manualOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tool_runs ADD COLUMN route TEXT")
    }
}
```

Before writing it (3C-1 plan review G1): `git diff --quiet 82084d3 -- app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt || echo "STOP: app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt changed since 82084d3"`; if it prints STOP, stop and ask the controller.

`app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt` (whole file):
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class, ToolRunEntity::class, ToolFindingEntity::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    abstract fun tools(): ToolDao
    companion object {
        /**
         * No fallbackToDestructiveMigration: the Director's marks and notes must survive (spec 7.3, 3C spec 7.1). Every version step is
         * registered, so a schema 1, 2 or 3 database reaches 4. [name] exists for the migration tests.
         */
        fun open(context: Context, name: String = DB_NAME): NetProbeDb =
            Room.databaseBuilder(context, NetProbeDb::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
```

- [ ] **Step 4: Generate and inspect 4.json**

```bash
./gradlew :app:kspDebugKotlin --console=plain 2>&1 | tail -5
ls app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/
git status --short app/schemas
git diff --quiet -- app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json && echo "3.json unchanged"
grep -n '"manualOnly"\|"route"' -A4 app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/4.json
```
Expected: `BUILD SUCCESSFUL`; the directory lists `1.json` to `4.json`; `git status` shows only `?? .../4.json`; "3.json unchanged"; the grep shows `manualOnly` with `"affinity": "INTEGER"`, `"notNull": true`, `"defaultValue": "0"`, and `route` with `"affinity": "TEXT"` and no `notNull`. If KSP rewrote `1.json`, `2.json` or `3.json`, stop and ask the controller: never commit a change to an older schema.

- [ ] **Step 5: Run the JVM tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures (`ToolMigrationSqlTest` and the existing `SchemaExportTest` cases still pass).

- [ ] **Step 6: Write the instrumented migration tests (compile only, run in Task 12)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/Migration34Test.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 3 to 4 on a throwaway file (3C spec 8.2 item 1, Review Focus 1). Never opens, creates or deletes DB_NAME. */
@RunWith(AndroidJUnit4::class)
class Migration34Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema3(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen, lastRunAt, forgotten) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5, 4, NULL)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
            "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
            "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm, " +
            "userType, userVendor, userModel, identSoftware) VALUES " +
            "(7, 1, '10.0.3.59', NULL, 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
            "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL, 'NAS', 'Synology', 'DS220+', NULL)")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary) " +
            "VALUES (1, 7, 'http-fingerprint', 5000, 100, 200, 'DONE', '{\"tls\":false,\"serverName\":\"chnas.local\"}', 'Synology DSM, 6 findings')")
        db.execSQL("INSERT INTO tool_findings (id, runId, kind, value, detail, extra) VALUES (1, 1, 'FRAMEWORK', 'Synology DSM', 'Matched title', NULL)")
    }

    @Test fun schema3RowsSurviveWithManualOnlyZeroAndRouteNull() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 3).use { seedSchema3(it) }
        val v4 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 4, true, MIGRATION_3_4)
        v4.query("SELECT customName, status, note, identVendor, userType, userVendor, userModel, manualOnly FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1))
            assertEquals("QA note NAS", c.getString(2)); assertEquals("Synology", c.getString(3)); assertEquals("NAS", c.getString(4))
            assertEquals("Synology", c.getString(5)); assertEquals("DS220+", c.getString(6)); assertEquals(0, c.getInt(7))
        }
        v4.query("SELECT summary, route FROM tool_runs WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("Synology DSM, 6 findings", c.getString(0)); assertTrue("an old run has no route", c.isNull(1))
        }
        v4.query("SELECT COUNT(*) FROM tool_findings WHERE runId = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        v4.query("SELECT COUNT(*) FROM ports WHERE hostId = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        // The new columns work: a hidden manual row and a routed run.
        v4.execSQL("INSERT INTO hosts (networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource, manualOnly) " +
            "VALUES (1, '10.20.0.5', 'UNKNOWN', 0, '[]', 'UNMARKED', '', 0, 1, 1, 0, 0, 1)")
        v4.execSQL("INSERT INTO tool_runs (hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) VALUES (7, 'tls-certificate', 443, 300, 301, 'DONE', '{}', 'x', 'OTHER')")
        v4.close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)     // Room's own validation through the production builder
        try {
            runBlocking {
                assertEquals("the manual row is hidden", listOf("10.0.3.59"), room.hosts().forNetwork(1).first().map { it.ip })
                assertEquals(2, room.hosts().allForNetwork(1).first().size)
                assertEquals(listOf("OTHER", null), room.tools().runsForHost(7).first().map { it.route })   // newest first
            }
        } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    @Test fun oneToFourInOneChain() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', NULL, NULL, 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', NULL, NULL, NULL, NULL, 0, '[]', NULL, NULL, NULL, 'INTERESTING', 'keep', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
        }
        val v4 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        v4.query("SELECT note, status, manualOnly FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)); assertEquals("INTERESTING", c.getString(1)); assertEquals(0, c.getInt(2))
        }
        v4.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/DirectorCopyMigration34Test.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Director's own data, schema 3 to 4, on a pushed copy (3C spec 8.2 item 2, G6). Never opens, creates or deletes DB_NAME; deletes
 * only the copy, in @After, even when an assertion fails. Skipped (an assumption failure, not a pass) when no copy was pushed.
 */
@RunWith(AndroidJUnit4::class)
class DirectorCopyMigration34Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private data class Snapshot(val networks: List<String>, val hosts: List<String>, val ports: List<String>, val runs: List<String>, val findings: Int)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun rows(query: (String) -> Cursor, sql: String): List<String> = query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    private fun count(query: (String) -> Cursor, sql: String): Int = query(sql).use { it.moveToFirst(); it.getInt(0) }
    private fun snapshot(query: (String) -> Cursor) =
        Snapshot(rows(query, NETWORKS_SQL), rows(query, HOSTS_SQL), rows(query, PORTS_SQL), rows(query, RUNS_SQL), count(query, "SELECT COUNT(*) FROM tool_findings"))
    @After fun tidy() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_COPY_DB + it).delete() } }

    @Test fun theDirectorsSchema3CopyMigratesWithEveryMarkNotePortAndToolRun() {
        val copy = context.getDatabasePath(MIGRATION_COPY_DB)
        assumeTrue("no pushed copy of the Director's database", copy.exists())
        assertNotEquals(DB_NAME, MIGRATION_COPY_DB)
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        val v3 = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertEquals("the copy must be schema 3 (the 3C-1 build)", 3, v3.version)
        val was = snapshot { v3.rawQuery(it, null) }
        v3.close()
        val v4 = helper.runMigrationsAndValidate(MIGRATION_COPY_DB, 4, true, MIGRATION_3_4)
        assertEquals("every network, host, mark, note, override, port and tool run unchanged", was, snapshot { v4.query(it) })
        assertEquals("no existing host is hidden", 0, count({ v4.query(it) }, "SELECT COUNT(*) FROM hosts WHERE manualOnly != 0"))
        assertEquals("no existing run gains a route", 0, count({ v4.query(it) }, "SELECT COUNT(*) FROM tool_runs WHERE route IS NOT NULL"))
        v4.close()
        val room = NetProbeDb.open(context, MIGRATION_COPY_DB)     // Room's own validation through the production builder
        try { assertEquals(was.hosts.size, room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM hosts").use { it.moveToFirst(); it.getInt(0) }) }
        finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    private companion object {
        const val MIGRATION_COPY_DB = "netprobe-migration-copy.db"
        const val NETWORKS_SQL = "SELECT id || '|' || signature || '|' || displayName || '|' || IFNULL(gatewayIp, '') || '|' || prefixLength || '|' || IFNULL(lastRunAt, '') FROM networks ORDER BY id"
        const val HOSTS_SQL = "SELECT id || '|' || networkId || '|' || ip || '|' || status || '|' || note || '|' || IFNULL(customName, '') || '|' || IFNULL(userType, '') || '|' || " +
            "IFNULL(userVendor, '') || '|' || IFNULL(userModel, '') || '|' || IFNULL(mac, '') || '|' || lastDiscoveryRun FROM hosts ORDER BY id"
        const val PORTS_SQL = "SELECT id || '|' || hostId || '|' || port || '|' || serviceName || '|' || IFNULL(product, '') || '|' || IFNULL(version, '') FROM ports ORDER BY id"
        const val RUNS_SQL = "SELECT id || '|' || hostId || '|' || toolId || '|' || port || '|' || status || '|' || summary || '|' || optionsJson FROM tool_runs ORDER BY id"
    }
}
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/plans/phase3c-base.txt app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt \
  app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/4.json \
  app/src/test/java/com/northwalessolutions/netprobe/data/MigrationSql34Test.kt app/src/test/java/com/northwalessolutions/netprobe/data/SchemaExportTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/Migration34Test.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/DirectorCopyMigration34Test.kt
git status --short    # nothing else staged
git commit -m "Phase 3C-2: schema 4 with manualOnly and route, additive migration and tests"
```

---

### Task 2: The data layer for hidden rows: forNetwork hides them, allForNetwork, promotion, the manual target and its discard, upsertHeard, the start-up orphan sweep, recent runs per network, the stored route, and Home's Forget and canary reads

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt`, `data/Repositories.kt`, `data/NetworkSignature.kt`, `engine/tools/ToolStore.kt`, `ui/home/HomeViewModel.kt`, `AppContainer.kt`, `MainActivity.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/HeardRunTest.kt`, `data/NetworkKeyTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/data/ManualHostDaoTest.kt`

**Interfaces:**
- Consumes: `HostEntity.manualOnly`, `ToolRunEntity.route` (Task 1); `HostRepository.upsertDiscovered`, `applyScan`, `ToolRepository`, `NetworkRepository.deleteNetworkCascade` (3A, 3B); `HomeViewModel.hosts`, `discoverOn`'s `known` line (3C-1 Task 6).
- Produces:
```kotlin
@Dao interface HostDao { fun forNetwork(networkId: Long): Flow<List<HostEntity>>      // now AND manualOnly = 0
                         fun allForNetwork(networkId: Long): Flow<List<HostEntity>>
                         suspend fun deleteOrphanManual(): Int; suspend fun deleteManualIfUnused(id: Long): Int }   // updateScan also sets manualOnly = 0
data class NetworkRun(@Embedded val run: ToolRunEntity, val ip: String)
@Dao interface ToolDao { fun recentForNetwork(networkId: Long, limit: Int): Flow<List<NetworkRun>> }
data class ManualRow(val host: HostEntity, val inserted: Boolean)
object HeardRun { fun runId(existing: HostEntity?, runIdForNew: Long): Long }
class HostRepository { fun allHostsForNetwork(networkId: Long): Flow<List<HostEntity>>
                       suspend fun upsertHeard(networkId: Long, h: DiscoveredHost, runIdForNew: Long): Long
                       suspend fun manualTarget(networkId: Long, ip: String): ManualRow
                       suspend fun discardManual(hostId: Long): Int; suspend fun deleteOrphanManual(): Int }
class ToolRepository { fun recentForNetwork(networkId: Long, limit: Int): Flow<List<NetworkRun>>
                       override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?): Long }
interface ToolStore { suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?): Long }   // default: the 5-argument form
object NetworkSignature { fun identityKey(n: NetworkEntity): String }        // the same "gateway/prefix" as identityKey(env)
class AppContainer { fun onActivityCreated() }                               // once per process, from MainActivity.onCreate
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/HeardRunTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 3C spec 3.4: which lastDiscoveryRun a heard write leaves on the row. */
class HeardRunTest {
    @Test fun `a new row takes the run for new rows`() {
        assertEquals(9L, HeardRun.runId(null, 9))
    }
    @Test fun `an existing visible row keeps its own run, so a heard packet never undims a device the latest scan missed`() {
        assertEquals(3L, HeardRun.runId(TestHosts.host().copy(lastDiscoveryRun = 3), 9))
    }
    @Test fun `a manual row being promoted takes the run for new rows`() {
        assertEquals(9L, HeardRun.runId(TestHosts.host().copy(lastDiscoveryRun = 0, manualOnly = true), 9))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/NetworkKeyTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.ui.home.TestEnvs
import org.junit.Assert.assertEquals
import org.junit.Test

/** A network row and the env it was made from share one identity key (3C spec 3.4 rule 1). */
class NetworkKeyTest {
    private val row = NetworkEntity(signature = "s", displayName = "Home", gatewayIp = "192.168.1.1", prefixLength = 24, ssid = null, lastSeen = 0)
    @Test fun `a network row's key equals its env's identity key`() {
        assertEquals(NetworkSignature.identityKey(TestEnvs.home), NetworkSignature.identityKey(row))
    }
    @Test fun `a row with no gateway keys on the prefix alone, as an env with no gateway does`() {
        assertEquals(NetworkSignature.identityKey(TestEnvs.home.copy(gateway = null)), NetworkSignature.identityKey(row.copy(gatewayIp = null)))
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/ManualHostDaoTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.ui.home.RowRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

/** 3C spec 8.2 item 3 (Review Focus 2): the hidden manual row, its promotion, its discard, the orphan sweep and recent runs. In-memory only. */
@RunWith(AndroidJUnit4::class)
class ManualHostDaoTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var tools: ToolRepository
    private lateinit var networks: NetworkRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); tools = ToolRepository(db); networks = NetworkRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun aManualRowIsHiddenFromForNetworkAndShownByAll() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val manual = hosts.manualTarget(1, "10.0.3.200")
        assertTrue(manual.inserted); assertTrue(manual.host.manualOnly); assertEquals(0L, manual.host.lastDiscoveryRun)
        assertEquals(listOf(visible), hosts.hostsForNetwork(1).first().map { it.id })
        assertEquals(setOf(visible, manual.host.id), hosts.allHostsForNetwork(1).first().map { it.id }.toSet())
    }
    @Test fun manualTargetReturnsTheExistingRowVisibleOrManual() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        assertEquals(ManualRow(hosts.byIdOnce(visible)!!, inserted = false), hosts.manualTarget(1, "10.0.3.5"))
        val first = hosts.manualTarget(1, "10.0.3.201")
        val again = hosts.manualTarget(1, "10.0.3.201")
        assertFalse(again.inserted); assertEquals(first.host.id, again.host.id)
        assertEquals("the same IP on another network is another row", true, hosts.manualTarget(2, "10.0.3.201").inserted)
    }
    @Test fun aScanFindingTheIpPromotesTheRowAndKeepsItsToolRuns() = runBlocking {
        val m = hosts.manualTarget(1, "10.0.3.200").host
        val runId = tools.startRun(m.id, "http-fingerprint", 80, 5, "{}", "WIFI")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.200"), mdnsName = "printer"), runId = 7)
        assertEquals("merged into the same row, never a second", m.id, id)
        val promoted = hosts.byIdOnce(id)!!
        assertFalse(promoted.manualOnly); assertEquals(7L, promoted.lastDiscoveryRun); assertEquals("printer", promoted.mdnsName)
        assertEquals(listOf(runId), tools.runsForHost(id).first().map { it.id })
        assertEquals("WIFI", tools.runsForHost(id).first().single().route)
        assertEquals(listOf(id), hosts.hostsForNetwork(1).first().map { it.id })
    }
    @Test fun upsertHeardKeepsAVisibleRowsRunAndSetsTheNewRunOnInsertAndPromotion() = runBlocking {
        val v = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 3)
        hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.5"), mdnsName = "nas"), runIdForNew = 9)
        assertEquals(3L, hosts.byIdOnce(v)!!.lastDiscoveryRun); assertEquals("nas", hosts.byIdOnce(v)!!.mdnsName)
        val n = hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.6")), runIdForNew = 9)
        assertEquals(9L, hosts.byIdOnce(n)!!.lastDiscoveryRun)
        val m = hosts.manualTarget(1, "10.0.3.7").host
        assertEquals(m.id, hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.7")), runIdForNew = 9))
        hosts.byIdOnce(m.id)!!.let { assertFalse(it.manualOnly); assertEquals(9L, it.lastDiscoveryRun) }
    }
    @Test fun aHeardRowOnANetworkWithNoRunIsShownUndimmed() = runBlocking {
        val id = hosts.upsertHeard(1, DiscoveredHost(ip("10.0.3.8")), runIdForNew = 0)
        val h = hosts.byIdOnce(id)!!
        assertEquals(0L, h.lastDiscoveryRun)
        val latestRun = 0L   // HomeViewModel's latestRun with no run on this network (StaleRun.seed of a null lastRunAt)
        assertFalse(RowRules.row(h, null, 22, 0, stale = latestRun > 0 && h.lastDiscoveryRun < latestRun).stale)
    }
    @Test fun deleteOrphanManualRemovesOnlyRunlessManualRows() = runBlocking {
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val orphan = hosts.manualTarget(1, "10.0.3.201").host
        val withRun = hosts.manualTarget(1, "10.0.3.202").host
        tools.startRun(withRun.id, "tls-certificate", 443, 1, "{}", "OTHER")
        assertEquals(1, hosts.deleteOrphanManual())
        assertNull(hosts.byIdOnce(orphan.id)); assertNotNull(hosts.byIdOnce(withRun.id)); assertNotNull(hosts.byIdOnce(visible))
    }
    @Test fun discardManualKeepsAPromotedOrStartedRow() = runBlocking {
        val plain = hosts.manualTarget(1, "10.0.3.203").host
        assertEquals(1, hosts.discardManual(plain.id)); assertNull(hosts.byIdOnce(plain.id))
        val promoted = hosts.manualTarget(1, "10.0.3.204").host
        hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.204")), runId = 2)
        assertEquals(0, hosts.discardManual(promoted.id)); assertNotNull(hosts.byIdOnce(promoted.id))
        val started = hosts.manualTarget(1, "10.0.3.205").host
        tools.startRun(started.id, "smb-session", 445, 1, "{}", "WIFI")
        assertEquals(0, hosts.discardManual(started.id)); assertNotNull(hosts.byIdOnce(started.id))
        val visible = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.206")), runId = 2)
        assertEquals("a visible row is never discarded", 0, hosts.discardManual(visible))
    }
    @Test fun recentForNetworkOrdersLimitsAndExcludesOtherNetworks() = runBlocking {
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        val b = hosts.upsertDiscovered(2, DiscoveredHost(ip("10.0.3.5")), runId = 1)
        (1..25).forEach { p -> tools.startRun(a, "http-fingerprint", p, p.toLong(), "{}") }   // one run per port: pruning keeps all 25
        tools.startRun(b, "http-fingerprint", 80, 100, "{}")
        val recent = tools.recentForNetwork(1, 20).first()
        assertEquals(20, recent.size)
        assertTrue(recent.all { it.ip == "10.0.3.5" && it.run.hostId == a })
        assertEquals((25 downTo 6).map { it.toLong() }, recent.map { it.run.startedAt })
    }
    @Test fun forgetsCascadeRemovesManualRowsAndTheirRuns() = runBlocking {
        val m = hosts.manualTarget(1, "10.20.0.5").host
        tools.startRun(m.id, "tls-certificate", 443, 1, "{}", "OTHER")
        networks.deleteNetworkCascade(1)
        assertTrue(hosts.allHostsForNetwork(1).first().isEmpty())
        assertTrue(tools.runsForHost(m.id).first().isEmpty())
    }
}
```

- [ ] **Step 2: Run the JVM tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.HeardRunTest" --tests "com.northwalessolutions.netprobe.data.NetworkKeyTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'HeardRun'` and `None of the following candidates is applicable` for `identityKey(row)`.

- [ ] **Step 3: Implement the DAO changes**

`app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt`:
1. Replace
```kotlin
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
```
with
```kotlin
    /** Every visible row: manual tool targets are hidden from Home, its counts, groups, search and Quick scan all (3C spec 7.1). */
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND manualOnly = 0 ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    /** Every row, manual targets included: Forget's tool check and the canary picks read this (3C spec 7.1). */
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun allForNetwork(networkId: Long): Flow<List<HostEntity>>
```
2. In `updateScan`'s query, replace `"lastDiscoveryRun = :runId, upnpDeviceType = :upnpDeviceType, ` with `"lastDiscoveryRun = :runId, manualOnly = 0, upnpDeviceType = :upnpDeviceType, `, and replace its KDoc line
```kotlin
    /** Scan-derived columns only; customName, the user* overrides, status and note are deliberately absent so they are never overwritten. */
```
with
```kotlin
    /**
     * Scan-derived columns only; customName, the user* overrides, status and note are deliberately absent so they are never overwritten.
     * A scan or a heard packet finding a manual tool target promotes it (manualOnly = 0), keeping its tool history (3C spec 7.1).
     */
```
3. Replace
```kotlin
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun deleteHost(id: Long)
}
```
with
```kotlin
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun deleteHost(id: Long)
    /** Start-up sweep (3C spec 4.3, G8): manual targets a killed process left with no run. A promoted row or one with a run is kept. */
    @Query("DELETE FROM hosts WHERE manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs)") suspend fun deleteOrphanManual(): Int
    /** A refused manual start (spec 4.3 step 4): only while the row is still manualOnly and has no run, so a promotion or a started run keeps it. */
    @Query("DELETE FROM hosts WHERE id = :id AND manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs)") suspend fun deleteManualIfUnused(id: Long): Int
}
```
4. Replace `data class PortCount(val hostId: Long, val n: Int)` with
```kotlin
data class PortCount(val hostId: Long, val n: Int)
/** One run with its host's IP, for the manual tools history (3C spec 4.1, 7.1). */
data class NetworkRun(@Embedded val run: ToolRunEntity, val ip: String)
```
5. In `ToolDao`, replace
```kotlin
    @Query("SELECT DISTINCT hostId FROM tool_runs") suspend fun hostsWithRuns(): List<Long>
```
with
```kotlin
    @Query("SELECT DISTINCT hostId FROM tool_runs") suspend fun hostsWithRuns(): List<Long>
    /** Newest first, every host of the network, manual targets included (3C spec 7.1). */
    @Query("SELECT tool_runs.*, hosts.ip AS ip FROM tool_runs JOIN hosts ON hosts.id = tool_runs.hostId WHERE hosts.networkId = :networkId " +
        "ORDER BY tool_runs.startedAt DESC, tool_runs.id DESC LIMIT :limit")
    fun recentForNetwork(networkId: Long, limit: Int): Flow<List<NetworkRun>>
```

- [ ] **Step 4: Implement the repository and signature changes**

`app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt`:
1. Replace
```kotlin
/** A host row and its port rows, read together, for Remove device and its Undo (spec 5.3). */
data class HostSnapshot(val host: HostEntity, val ports: List<PortEntity>)
```
with
```kotlin
/** A host row and its port rows, read together, for Remove device and its Undo (spec 5.3). */
data class HostSnapshot(val host: HostEntity, val ports: List<PortEntity>)

/** The row a manual tool run is keyed on (3C spec 4.3 step 4); [inserted] is true only when this call created it. */
data class ManualRow(val host: HostEntity, val inserted: Boolean)

/** 3C spec 3.4: a heard write leaves an existing visible row's lastDiscoveryRun alone; a new row or a promoted manual row takes [runIdForNew]. */
object HeardRun {
    fun runId(existing: HostEntity?, runIdForNew: Long): Long = if (existing == null || existing.manualOnly) runIdForNew else existing.lastDiscoveryRun
}
```
2. In `HostRepository`, replace
```kotlin
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().forNetwork(networkId)
```
with
```kotlin
    /** Visible rows only (3C spec 7.1): every device list, count and Quick scan all read this. */
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().forNetwork(networkId)
    /** Every row, manual targets included: Forget's tool check and the canary picks (3C spec 7.1). */
    fun allHostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().allForNetwork(networkId)
```
3. Replace the whole `upsertDiscovered` function (from its KDoc line `/** Read and write are one transaction, and the write lists scan columns only, so annotations written concurrently are never lost. */` to its closing brace, the line before `private suspend fun applyScan`) with:
```kotlin
    /** Read and write are one transaction, and the write lists scan columns only, so annotations written concurrently are never lost. */
    suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long): Long = upsert(networkId, h) { runId }

    /**
     * The listener's write (3C spec 3.4): the same merge as a scan, except an existing visible row keeps its lastDiscoveryRun, so a heard
     * packet never undims a device the latest scan missed and never moves "Last scan". A new row or a promoted manual row gets [runIdForNew].
     */
    suspend fun upsertHeard(networkId: Long, h: DiscoveredHost, runIdForNew: Long): Long = upsert(networkId, h) { existing -> HeardRun.runId(existing, runIdForNew) }

    private suspend fun upsert(networkId: Long, h: DiscoveredHost, runFor: (HostEntity?) -> Long): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val ip = h.ip.hostAddress
        val existing = db.hosts().find(networkId, ip)
        val newRank = h.macSource?.rank ?: 0
        if (existing == null) {
            val txt = h.mdnsTxt.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            try {
                return@withTransaction db.hosts().insert(HostEntity(networkId = networkId, ip = ip, mac = h.mac, reverseDnsName = h.reverseDnsName, mdnsName = h.mdnsName,
                    netbiosName = h.netbiosName, upnpName = h.upnpFriendlyName, upnpManufacturer = h.upnpManufacturer, upnpModel = h.upnpModel, vendor = h.upnpManufacturer,
                    deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
                    snmpSysDescr = h.snmpSysDescr, httpTitle = h.httpTitle, mdnsTxtJson = txt, status = HostStatus.UNMARKED, note = "", isGateway = h.isGateway,
                    firstSeen = now, lastSeen = now, lastPortScan = null, lastDiscoveryRun = runFor(null),
                    macSource = newRank, upnpDeviceType = h.upnpDeviceType, upnpServer = h.upnpServer, httpServer = h.httpServer, httpAuthRealm = h.httpAuthRealm))
            } catch (e: SQLiteConstraintException) {
                // Lost an insert race on (networkId, ip): fall through to the update path on the winner's row.
                val raced = db.hosts().find(networkId, ip) ?: throw e
                return@withTransaction applyScan(raced, h, newRank, now, runFor(raced))
            }
        }
        applyScan(existing, h, newRank, now, runFor(existing))
    }
```
4. Replace
```kotlin
    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
```
with
```kotlin
    /**
     * 3C spec 4.3 step 4: the row a manual run is keyed on, in one transaction: the existing row for (network, ip), visible or manual, or a
     * new hidden row with every scan column empty. A lost insert race returns the winner's row as not inserted, so it is never discarded.
     */
    suspend fun manualTarget(networkId: Long, ip: String): ManualRow = db.withTransaction {
        db.hosts().find(networkId, ip)?.let { return@withTransaction ManualRow(it, inserted = false) }
        val now = System.currentTimeMillis()
        val row = HostEntity(networkId = networkId, ip = ip, mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null, upnpName = null,
            upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null,
            identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED,
            note = "", isGateway = false, firstSeen = now, lastSeen = now, lastPortScan = null, lastDiscoveryRun = 0, manualOnly = true)
        try {
            ManualRow(row.copy(id = db.hosts().insert(row)), inserted = true)
        } catch (e: SQLiteConstraintException) {
            ManualRow(db.hosts().find(networkId, ip) ?: throw e, inserted = false)
        }
    }
    /** A refused manual start (3C spec 4.3 step 4, G8); a promoted row, a row with a run and a visible row are kept. Returns rows deleted. */
    suspend fun discardManual(hostId: Long): Int = db.hosts().deleteManualIfUnused(hostId)
    /** The start-up sweep (3C spec 4.3, G8). Returns rows deleted. */
    suspend fun deleteOrphanManual(): Int = db.hosts().deleteOrphanManual()

    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
```
5. In `ToolRepository`, replace
```kotlin
    override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long = db.withTransaction {
        val id = dao.insertRun(ToolRunEntity(hostId = hostId, toolId = toolId, port = port, startedAt = startedAt,
            finishedAt = null, status = ToolRunStatus.RUNNING, optionsJson = optionsJson, summary = ""))
        dao.prune(hostId, toolId, port, KEEP_RUNS_PER_TOOL)   // per (host, tool, port), ruling R5: port 80's runs never prune port 5000's
        id
    }
```
with
```kotlin
    override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long =
        startRun(hostId, toolId, port, startedAt, optionsJson, null)
    /** 3C spec 4.5: [route] is a ToolRoute name for a manual run, null for a Device-screen run; written in the same insert. */
    override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?): Long = db.withTransaction {
        val id = dao.insertRun(ToolRunEntity(hostId = hostId, toolId = toolId, port = port, startedAt = startedAt,
            finishedAt = null, status = ToolRunStatus.RUNNING, optionsJson = optionsJson, summary = "", route = route))
        dao.prune(hostId, toolId, port, KEEP_RUNS_PER_TOOL)   // per (host, tool, port), ruling R5: port 80's runs never prune port 5000's
        id
    }
```
6. In `ToolRepository`, replace
```kotlin
    fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>> = dao.runsForHost(hostId)
```
with
```kotlin
    fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>> = dao.runsForHost(hostId)
    /** The manual tools history (3C spec 4.1): newest first, every host of the network. */
    fun recentForNetwork(networkId: Long, limit: Int): Flow<List<NetworkRun>> = dao.recentForNetwork(networkId, limit)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolStore.kt`: replace
```kotlin
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
```
with
```kotlin
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
    /**
     * 3C spec 4.5: the run with its route stored ("WIFI", "ETHERNET", "OTHER", null). The default drops the route, so a store that keeps none
     * (the JVM fakes) needs no change; ToolRepository overrides it and writes the route in the same insert.
     */
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?): Long =
        startRun(hostId, toolId, port, startedAt, optionsJson)
```

`app/src/main/java/com/northwalessolutions/netprobe/data/NetworkSignature.kt`: replace
```kotlin
    fun identityKey(env: NetworkEnv): String = (env.gateway?.hostAddress ?: "") + "/" + env.prefixLength
```
with
```kotlin
    fun identityKey(env: NetworkEnv): String = (env.gateway?.hostAddress ?: "") + "/" + env.prefixLength
    /** The same key from a stored row (its gatewayIp is the env's gateway address at insert), so a listener tag compares with the current network (3C spec 3.4 rule 1). */
    fun identityKey(n: NetworkEntity): String = (n.gatewayIp ?: "") + "/" + n.prefixLength
```

- [ ] **Step 5: Home's Forget check and canary read every row; the orphan sweep runs from the activity**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
1. Replace
```kotlin
    private val hosts: Flow<List<HostEntity>> = combine(networkId, c.forgottenNetworkId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }
```
with
```kotlin
    private val hosts: Flow<List<HostEntity>> = combine(networkId, c.forgottenNetworkId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }
    /** Every row of the shown network, manual targets included (3C spec 7.1): Forget stays disabled while a tool holds a hidden row. */
    private val allHosts: Flow<List<HostEntity>> = combine(networkId, c.forgottenNetworkId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.allHostsForNetwork(id) }
```
2. Replace `val state: StateFlow<HomeUiState> = combine(baseState, c.hostBusy.busy, c.appMessages.current, hosts) { s, busy, message, hs ->` with `val state: StateFlow<HomeUiState> = combine(baseState, c.hostBusy.busy, c.appMessages.current, allHosts) { s, busy, message, hs ->`, and in the KDoc above it replace ``reads the `hosts` flow (every host of the shown network, not only the filtered rows)`` with ``reads the `allHosts` flow (every row of the shown network, manual targets included, not only the filtered rows)``.
3. In `discoverOn` (the 3C-1 Task 6 line), replace
```kotlin
        val known = if (runSettings.bindOutside) c.hosts.hostsForNetwork(net.id).first().map { it.ip }.toSet() else emptySet()
```
with
```kotlin
        // Every stored IP, manual targets included, so a canary is never an address a manual tool is probing (3C spec 7.1).
        val known = if (runSettings.bindOutside) c.hosts.allHostsForNetwork(net.id).first().map { it.ip }.toSet() else emptySet()
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`:
1. Replace `import kotlinx.coroutines.sync.Mutex` with
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
```
2. Replace `    val locks by lazy { Locks(context) }` with
```kotlin
    val locks by lazy { Locks(context) }
    private val activityStarted = AtomicBoolean(false)
    /**
     * MainActivity.onCreate calls this; the first call per process does the work. Never the Application: an instrumented test process runs
     * NetProbeApp but no activity, so it never opens netprobe.db through here. Removes manual targets a killed process left without a run
     * (3C spec 4.3, G8), under the write lock so no radar emission interleaves; a failure is logged by appScope's handler.
     */
    fun onActivityCreated() {
        if (!activityStarted.compareAndSet(false, true)) return
        appScope.launch { hostWriteLock.withLock { hosts.deleteOrphanManual() } }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/MainActivity.kt`: replace
```kotlin
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
```
with
```kotlin
        container.onActivityCreated()   // the orphan sweep (3C spec 4.3); idempotent across activity recreation
        setContent { NetProbeTheme { Surface(Modifier.fillMaxSize()) { NetProbeNav(container) } } }
```

- [ ] **Step 6: Run the tests to see them pass, and check every forNetwork caller**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
grep -rn "hostsForNetwork\|allHostsForNetwork" app/src/main --include=*.kt
```
Expected: `BUILD SUCCESSFUL` twice. The grep shows `hostsForNetwork` only in `Repositories.kt`, `HomeViewModel.hosts` and `quickScanAllBody` (both visible-only, as intended), and `allHostsForNetwork` only in `Repositories.kt`, `HomeViewModel.allHosts` and `discoverOn`'s `known`. Any other caller is reported to the controller before the commit.

- [ ] **Step 7: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/NetworkSignature.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/main/java/com/northwalessolutions/netprobe/MainActivity.kt \
  app/src/test/java/com/northwalessolutions/netprobe/data/HeardRunTest.kt app/src/test/java/com/northwalessolutions/netprobe/data/NetworkKeyTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/ManualHostDaoTest.kt
git status --short
git commit -m "Phase 3C-2: hidden manual rows, promotion, discard, orphan sweep, upsertHeard, recent runs"
```

---

### Task 3: KnownRanges, ToolRoute and ManualRoute, ToolTarget.route, and the runner's routeBind (STRONGER MODEL)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/KnownRanges.kt`, `engine/tools/ManualRoute.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt` (`ToolTarget`), `engine/tools/ToolRunner.kt`, `AppContainer.kt` (the tool runner's `routeBind`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/KnownRangesTest.kt`, `engine/tools/ManualRouteTest.kt`, `engine/tools/ToolRunnerRouteTest.kt`

**Interfaces:**
- Consumes: `HostEntity.manualOnly` (Task 1); `ToolStore.startRun(..., route)` (Task 2); `HomeRange.of` (3C-1 Task 4); `BindFailedException`, `BoundNetwork`, `SocketBinder`, `currentBinder` (Phase 2); `ToolRunner`, `ToolTarget`, `ToolRunnerTestSupport` (3B).
- Produces:
```kotlin
class KnownRanges(val ranges: List<Subnet>) { fun contains(ip: Inet4Address): Boolean
    companion object { fun of(home: Subnet, manual: List<Subnet>, hosts: List<HostEntity>): KnownRanges } }   // engine.discovery
enum class ToolRoute { WIFI, ETHERNET, OTHER; companion object { fun parse(s: String?): ToolRoute? } }     // engine.tools
object ManualRoute { fun of(ip: Inet4Address, known: KnownRanges, env: NetworkEnv): ToolRoute; fun needsBinder(route: ToolRoute): Boolean
    suspend fun <T> bind(route: ToolRoute, env: NetworkEnv, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T }
data class ToolTarget(hostId, ip, port, tls, serverName, val route: ToolRoute? = null)                     // last, defaulted
class ToolRunner(..., log, routeBind: suspend (ToolRoute, NetworkEnv, suspend () -> Unit) -> Unit = <refuses WIFI and ETHERNET>)   // last, defaulted
```
Every existing `ToolTarget(...)` and `ToolRunner(...)` call (3B tools, tests, `DetailViewModel`) compiles unchanged and keeps `route = null`, which means the scan binding.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/KnownRangesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 19. */
class KnownRangesTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val home = SubnetMath.of(ip("10.0.3.20"), 24)

    @Test fun `the home range of any class, the manual ranges and the slash 24 of every visible private host`() {
        val cgnatHome = SubnetMath.of(ip("100.64.5.9"), 24)
        val k = KnownRanges.of(cgnatHome, listOf(CidrParser.subnetOf("10.0.4.0/24")!!), listOf(TestHosts.host(ip = "192.168.7.20"), TestHosts.host(ip = "8.8.8.8")))
        assertTrue("the home range, whatever its class", k.contains(ip("100.64.5.200")))
        assertTrue("a manual range", k.contains(ip("10.0.4.1")))
        assertTrue("a known private host's /24", k.contains(ip("192.168.7.254")))
        assertFalse("a public stored host adds no range", k.contains(ip("8.8.4.4")))
        assertFalse(k.contains(ip("192.168.8.1")))
    }
    @Test fun `manual-only hosts contribute nothing`() {
        val k = KnownRanges.of(home, emptyList(), listOf(TestHosts.host(ip = "10.20.0.5").copy(manualOnly = true)))
        assertFalse(k.contains(ip("10.20.0.9")))
        assertEquals(listOf(home), k.ranges)
    }
    @Test fun `duplicates collapse and an unparsable stored address is skipped`() {
        assertEquals(listOf(home), KnownRanges.of(home, listOf(home), listOf(TestHosts.host(ip = "10.0.3.7"), TestHosts.host(ip = "not-an-ip"))).ranges)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ManualRouteTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** 3C spec 8.1 item 21, D20, ruling 4 (Review Focus 3). */
class ManualRouteTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val wifi = NetworkEnv(ip("10.0.3.20"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    private val ethernet = wifi.copy(transport = Transport.ETHERNET)
    private val known = KnownRanges.of(SubnetMath.of(ip("10.0.3.20"), 24), emptyList(), listOf(TestHosts.host(ip = "10.0.4.20")))

    @Test fun `a home range target and a known slash 24 target go over Wi-Fi or Ethernet`() {
        for (t in listOf("10.0.3.5", "10.0.4.99")) {
            assertEquals(t, ToolRoute.WIFI, ManualRoute.of(ip(t), known, wifi))
            assertEquals(t, ToolRoute.ETHERNET, ManualRoute.of(ip(t), known, ethernet))
        }
    }
    @Test fun `targets outside every known range take the system route`() {
        for (t in listOf("10.20.0.5", "8.8.8.8", "100.64.1.1", "169.254.10.10")) assertEquals(t, ToolRoute.OTHER, ManualRoute.of(ip(t), known, wifi))
    }
    @Test fun `Aggressive scan makes no difference for a target outside every known range`() {
        for (aggressive in listOf(false, true)) {
            val home = HomeRange.of(ip("10.0.3.20"), 24, 22, aggressive).subnet
            assertEquals("aggressive $aggressive", ToolRoute.OTHER, ManualRoute.of(ip("10.20.0.5"), KnownRanges.of(home, emptyList(), emptyList()), wifi))
        }
    }
    @Test fun `bind uses the binder for Wi-Fi and Ethernet and never asks for one for OTHER`() = runBlocking {
        var lookups = 0
        val binderFor: (NetworkEnv) -> SocketBinder? = { lookups++; FakeBinder }
        assertSame(FakeBinder, ManualRoute.bind(ToolRoute.WIFI, wifi, binderFor) { currentBinder() })
        assertSame(FakeBinder, ManualRoute.bind(ToolRoute.ETHERNET, ethernet, binderFor) { currentBinder() })
        assertNull(ManualRoute.bind(ToolRoute.OTHER, wifi, binderFor) { currentBinder() })
        assertEquals(2, lookups)
    }
    @Test fun `no binder for a Wi-Fi route is a bind failure, never an unbound run`() = runBlocking {
        var ran = false
        try { ManualRoute.bind(ToolRoute.WIFI, wifi, { null }) { ran = true }; fail("expected BindFailedException") } catch (_: BindFailedException) { }
        assertFalse("the block never ran", ran)
    }
    @Test fun `route names round trip and anything else parses to null`() {
        ToolRoute.entries.forEach { assertEquals(it, ToolRoute.parse(it.name)) }
        assertNull(ToolRoute.parse(null)); assertNull(ToolRoute.parse("VPN")); assertNull(ToolRoute.parse("wifi"))
        assertTrue(ManualRoute.needsBinder(ToolRoute.WIFI)); assertTrue(ManualRoute.needsBinder(ToolRoute.ETHERNET)); assertFalse(ManualRoute.needsBinder(ToolRoute.OTHER))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerRouteTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import kotlin.coroutines.ContinuationInterceptor

/** 3C spec 4.5: a manual run follows its own route; a Device-screen run keeps the scan binding (Review Focus 3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerRouteTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    /** Records the route each run is stored with; everything else is the 3B fake. */
    private class RouteStore(private val inner: ToolRunnerTestSupport.FakeStore = ToolRunnerTestSupport.FakeStore()) : ToolStore by inner {
        val routes = mutableListOf<String?>()
        val finished: List<Triple<Long, ToolRunStatus, String>> get() = inner.finished
        override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String, route: String?): Long {
            routes += route
            return inner.startRun(hostId, toolId, port, startedAt, optionsJson)
        }
    }
    private class Rig(scope: CoroutineScope, routeBind: (suspend (ToolRoute, NetworkEnv, suspend () -> Unit) -> Unit)?) {
        val store = RouteStore()
        val seen = mutableListOf<SocketBinder?>()
        val scanBinds = mutableListOf<Inet4Address>()
        private val tool = ToolRunnerTestSupport.FakeTool(ToolId.TLS_CERTIFICATE) { _, _ -> seen += currentBinder(); ToolResult(ToolRunStatus.DONE, "ok") }
        private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        private val scanBind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit = { ip, _, block -> scanBinds += ip; block() }
        private val env = MutableStateFlow<NetworkEnv?>(ToolRunnerTestSupport.home)
        val runner = if (routeBind == null)
            ToolRunner(scope, dispatcher, mapOf(tool.id to tool), store, HostBusyRegistry(), AppMessages(), env, MutableStateFlow(true), scanBind, onFinished = {})
        else
            ToolRunner(scope, dispatcher, mapOf(tool.id to tool), store, HostBusyRegistry(), AppMessages(), env, MutableStateFlow(true), scanBind, onFinished = {}, routeBind = routeBind)
    }
    private fun target(route: ToolRoute?) = ToolTarget(7, ToolRunnerTestSupport.ip("10.20.0.5"), 443, tls = true, serverName = null, route = route)

    @Test fun `a WIFI manual run is bound through the route binder and never through the scan bind`() = runTest {
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { FakeBinder }) { block() } }
        rig.runner.start(target(ToolRoute.WIFI), ToolId.TLS_CERTIFICATE, ToolOptions.None); runCurrent()
        assertEquals(listOf<SocketBinder?>(FakeBinder), rig.seen)
        assertTrue("the scan bind is for Device-screen runs only", rig.scanBinds.isEmpty())
        assertEquals(listOf<String?>("WIFI"), rig.store.routes)
    }
    @Test fun `an OTHER manual run goes out unbound and never asks for a binder`() = runTest {
        var lookups = 0
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { lookups++; FakeBinder }) { block() } }
        rig.runner.start(target(ToolRoute.OTHER), ToolId.TLS_CERTIFICATE, ToolOptions.None); runCurrent()
        assertEquals(listOf<SocketBinder?>(null), rig.seen); assertEquals(0, lookups)
        assertEquals(listOf<String?>("OTHER"), rig.store.routes)
        assertEquals(ToolRunStatus.DONE, rig.store.finished.single().second)
    }
    @Test fun `a WIFI manual run with no binder fails with the bind wording and the tool never runs`() = runTest {
        val rig = Rig(backgroundScope) { route, e, block -> ManualRoute.bind(route, e, { null }) { block() } }
        rig.runner.start(target(ToolRoute.WIFI), ToolId.TLS_CERTIFICATE, ToolOptions.None); runCurrent()
        assertTrue("no socket was opened", rig.seen.isEmpty())
        assertEquals(Triple(1L, ToolRunStatus.FAILED, ToolCopy.BIND_FAILED), rig.store.finished.single())
    }
    @Test fun `the default route binder refuses a Wi-Fi route rather than run it unbound`() = runTest {
        val rig = Rig(backgroundScope, routeBind = null)
        rig.runner.start(target(ToolRoute.WIFI), ToolId.TLS_CERTIFICATE, ToolOptions.None); runCurrent()
        assertTrue(rig.seen.isEmpty())
        assertEquals(Triple(1L, ToolRunStatus.FAILED, ToolCopy.BIND_FAILED), rig.store.finished.single())
    }
    @Test fun `a Device-screen run keeps the scan bind and stores no route`() = runTest {
        var routed = 0
        val rig = Rig(backgroundScope) { _, _, block -> routed++; block() }
        rig.runner.start(target(null), ToolId.TLS_CERTIFICATE, ToolOptions.None); runCurrent()
        assertEquals(listOf(ToolRunnerTestSupport.ip("10.20.0.5")), rig.scanBinds); assertEquals(0, routed)
        assertEquals(listOf<String?>(null), rig.store.routes)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.KnownRangesTest" --tests "com.northwalessolutions.netprobe.engine.tools.ManualRouteTest" --tests "com.northwalessolutions.netprobe.engine.tools.ToolRunnerRouteTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'KnownRanges'`, `'ToolRoute'`, `'ManualRoute'`, `No parameter with name 'route'` and `No parameter with name 'routeBind'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/KnownRanges.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Rfc1918
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import java.net.Inet4Address

/**
 * The ranges a network is known to hold (3C spec 3.4, 4.5): its home range (any class), its manual ranges, and the /24 of every stored
 * visible private host. The listener inserts heard hosts only inside these; a manual tool target inside them is bound to Wi-Fi or Ethernet.
 * Manual-only rows contribute nothing: a typed target must never widen what counts as this network.
 */
class KnownRanges(val ranges: List<Subnet>) {
    fun contains(ip: Inet4Address): Boolean = ranges.any { it.contains(ip) }

    companion object {
        private const val UNIT_PREFIX = 24
        fun of(home: Subnet, manual: List<Subnet>, hosts: List<HostEntity>): KnownRanges {
            val units = hosts.asSequence().filter { !it.manualOnly }.mapNotNull { Ipv4.parse(it.ip) }.filter { Rfc1918.contains(it) }
                .map { SubnetMath.of(it, UNIT_PREFIX) }.toList()
            return KnownRanges((listOf(home) + manual + units).distinct())
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ManualRoute.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** How a manual tool run leaves the phone (3C spec 4.5, D20); stored by name in tool_runs.route and shown in the results header. */
enum class ToolRoute {
    WIFI, ETHERNET, OTHER;
    companion object { fun parse(s: String?): ToolRoute? = entries.firstOrNull { it.name == s } }
}

/**
 * The manual tools route rule (3C spec 4.5, ruling 4). Only manual targets use it; scans, Quick scan all, Device-screen runs and the
 * listener keep their own bindings. A target inside the network's known ranges goes over the Wi-Fi or Ethernet Network, and with no
 * binder fails rather than run unbound; any other target goes out on the system's routing, so a VPN or Tailscale route can carry it.
 */
object ManualRoute {
    fun of(ip: Inet4Address, known: KnownRanges, env: NetworkEnv): ToolRoute = when {
        !known.contains(ip) -> ToolRoute.OTHER
        env.transport == Transport.ETHERNET -> ToolRoute.ETHERNET
        else -> ToolRoute.WIFI
    }
    fun needsBinder(route: ToolRoute): Boolean = route != ToolRoute.OTHER

    suspend fun <T> bind(route: ToolRoute, env: NetworkEnv, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T {
        if (!needsBinder(route)) return block()
        val b = binderFor(env) ?: throw BindFailedException(IllegalStateException("no Wi-Fi or Ethernet network carries ${env.interfaceIp.hostAddress}"))
        return withContext(BoundNetwork(b)) { block() }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt`: replace
```kotlin
    val serverName: String?,         // known name for SNI and Host (4.9); null when none or for brute-force
)
```
with
```kotlin
    val serverName: String?,         // known name for SNI and Host (4.9); null when none or for brute-force
    val route: ToolRoute? = null,    // manual runs only (3C spec 4.5): the runner's routeBind; null keeps the scan binding
)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt`:
1. Replace
```kotlin
    private val log: (Throwable) -> Unit = {},
) {
```
with
```kotlin
    private val log: (Throwable) -> Unit = {},
    /**
     * Manual runs only (ToolTarget.route set, 3C spec 4.5). The default never runs a Wi-Fi or Ethernet route unbound: with no binder it
     * throws BindFailedException, which ends the run FAILED with the bind wording; AppContainer passes the real binder.
     */
    private val routeBind: suspend (ToolRoute, NetworkEnv, suspend () -> Unit) -> Unit = { route, e, block -> ManualRoute.bind(route, e, { null }) { block() } },
) {
```
2. Replace
```kotlin
                repo.startRun(target.hostId, toolId.key, target.port, startedAt, optionsJson)
```
with
```kotlin
                repo.startRun(target.hostId, toolId.key, target.port, startedAt, optionsJson, target.route?.name)
```
3. Replace
```kotlin
            bind(target.ip, run.env) { result = tool.run(target, options, Control(run), progress) }
```
with
```kotlin
            val route = target.route
            // A manual run follows its own route (3C spec 4.5); every other run keeps the scan binding through [bind].
            if (route == null) bind(target.ip, run.env) { result = tool.run(target, options, Control(run), progress) }
            else routeBind(route, run.env) { result = tool.run(target, options, Control(run), progress) }
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`:
1. Replace `import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry` with
```kotlin
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ManualRoute
```
2. In `toolRunner`, replace
```kotlin
            log = { Log.e(AppScopes.LOG_TAG, "tool run", it) })
```
with
```kotlin
            log = { Log.e(AppScopes.LOG_TAG, "tool run", it) },
            // Manual runs (3C spec 4.5, ruling 4): known-range targets over the Wi-Fi or Ethernet Network, never unbound; others on the system route.
            routeBind = { route, e, block -> ManualRoute.bind(route, e, networkEnvMonitor::binderFor) { block() } })
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.*" --tests "com.northwalessolutions.netprobe.engine.discovery.KnownRangesTest" --console=plain 2>&1 | tail -20
grep -n "routeBind\|route" app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt
```
Expected: `BUILD SUCCESSFUL`; every 3B runner test (`ToolRunnerTest`, `ToolRunnerRaceTest`, `ToolRunnerPauseTest`, `BindingPropagationTest`) still passes untouched; the grep shows the parameter, the stored route and the one `if (route == null)` branch.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/KnownRanges.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ManualRoute.kt app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/KnownRangesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ManualRouteTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerRouteTest.kt
git status --short
git commit -m "Phase 3C-2: known ranges, manual tool route and the runner's route binding"
```

---

### Task 4: Manual tools copy and validation: ManualCopy, ManualTarget, HomeCopy.MENU_RUN_TOOL, the route strings, and their copy-table rows

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualCopy.kt`, `ui/manual/ManualTarget.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`, `ui/tools/ToolCopy.kt`, `engine/tools/ToolApplicability.kt` (`SMB_PORT` public)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualTargetTest.kt`; additions to `ui/home/CopyTableTest.kt` and `ui/tools/ToolCopyTableTest.kt`

**Interfaces:**
- Consumes: `ToolRoute` (Task 3); `Ipv4.parse`, `Subnet`, `start()`/`end()` (MVP, Phase 2); `ToolApplicability.SNMP_PORT`, `targetTls`, `ServiceIdentifier.TLS` (3B); `CopyTableTest.inTable`, `ToolCopyTableTest.inTable` (3A, 3B).
- Produces:
```kotlin
object ManualCopy { const val TITLE; IP; IP_PLACEHOLDER; PORT; PORT_PLACEHOLDER; USE_HTTPS; FIXED_SMB; FIXED_SNMP; NO_LAN; ERR_IP; ERR_ADDRESS; ERR_PORT_NEEDED; ERR_PORT_RANGE
                    HISTORY; HISTORY_EMPTY; fun toolRow(name: String); fun knownDevice(name: String); fun historyRow(tool: String, ip: String, portLabel: String)
                    fun historyLine(summary: String, relative: String); fun historyDescription(tool: String, ip: String, portLabel: String) }
data class ManualCheck(val ip: Inet4Address?, val port: Int?, val ipError: String?, val portError: String?) { val canRun: Boolean }
object ManualTarget { const val PORT_MIN = 1; const val PORT_MAX = 65_535
    fun check(ipText: String, portText: String, tool: ToolId, home: Subnet?, attempted: Boolean): ManualCheck
    fun fixedPort(tool: ToolId): Int?; fun tls(tool: ToolId, https: Boolean): Boolean; fun httpsDefault(tool: ToolId, port: Int?, service: ServiceInfo?): Boolean }
HomeCopy.MENU_RUN_TOOL = "Run a tool..."
ToolCopy.ROUTE_WIFI, ROUTE_ETHERNET, ROUTE_OTHER; fun ToolCopy.routeLine(route: ToolRoute?): String?
ToolApplicability.SMB_PORT = 445   // was private
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualTargetTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 20 (4.2, ruling 3). */
class ManualTargetTest {
    private val home = SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24)
    private fun check(ip: String, port: String = "443", tool: ToolId = ToolId.HTTP_FINGERPRINT, attempted: Boolean = true) =
        ManualTarget.check(ip, port, tool, home, attempted)

    @Test fun `every error string`() {
        assertEquals(ManualCopy.ERR_IP, check("").ipError)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3").ipError)
        assertEquals("no name lookup", ManualCopy.ERR_IP, check("chnas.local").ipError)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3.256").ipError)
        assertEquals(ManualCopy.ERR_ADDRESS, check("127.0.0.1").ipError)
        assertEquals(ManualCopy.ERR_PORT_NEEDED, check("10.0.3.5", "").portError)
        for (p in listOf("0", "65536", "99999", "123456", "44a")) assertEquals(p, ManualCopy.ERR_PORT_RANGE, check("10.0.3.5", p).portError)
    }
    @Test fun `reserved addresses and the home range's network and broadcast are refused`() {
        for (ip in listOf("0.1.2.3", "127.0.0.1", "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255", "10.0.3.0", "10.0.3.255"))
            assertEquals(ip, ManualCopy.ERR_ADDRESS, check(ip).ipError)
    }
    @Test fun `any other address is accepted, public included`() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "100.64.1.1", "169.254.10.10", "10.20.0.5", "10.0.3.5", "10.0.3.1")) {
            val c = check(ip)
            assertTrue(ip, c.canRun); assertEquals(Ipv4.parse(ip), c.ip); assertEquals(443, c.port)
        }
    }
    @Test fun `SMB and SNMP fix their ports and ignore the port field`() {
        check("10.0.3.59", "", ToolId.SMB_SESSION).let { assertNull(it.portError); assertEquals(445, it.port); assertTrue(it.canRun) }
        check("10.0.3.1", "abc", ToolId.SNMP_WALK).let { assertNull(it.portError); assertEquals(161, it.port) }
        assertEquals(445, ManualTarget.fixedPort(ToolId.SMB_SESSION)); assertEquals(161, ManualTarget.fixedPort(ToolId.SNMP_WALK))
        assertNull(ManualTarget.fixedPort(ToolId.TLS_CERTIFICATE))
    }
    @Test fun `nothing shows until the first Run tap, then errors follow the fields`() {
        ManualTarget.check("", "", ToolId.HTTP_FINGERPRINT, home, attempted = false).let { assertNull(it.ipError); assertNull(it.portError); assertFalse(it.canRun) }
        assertNull("a half-typed address is not an error before Run", check("10.0.3", attempted = false).ipError)
        assertFalse(check("10.0.3", attempted = false).canRun)
        assertEquals(ManualCopy.ERR_IP, check("10.0.3", attempted = true).ipError)
    }
    @Test fun `with no home range only the reserved blocks are refused`() {
        assertTrue(ManualTarget.check("10.0.3.0", "443", ToolId.HTTP_FINGERPRINT, null, true).canRun)
        assertFalse(ManualTarget.check("127.0.0.1", "443", ToolId.HTTP_FINGERPRINT, null, true).canRun)
    }
    @Test fun `tls follows the tool and the switch`() {
        assertTrue(ManualTarget.tls(ToolId.TLS_CERTIFICATE, https = false))
        assertFalse(ManualTarget.tls(ToolId.SMB_SESSION, https = true)); assertFalse(ManualTarget.tls(ToolId.SNMP_WALK, https = true))
        assertTrue(ManualTarget.tls(ToolId.HTTP_FINGERPRINT, https = true)); assertFalse(ManualTarget.tls(ToolId.DIR_BRUTEFORCE, https = false))
    }
    @Test fun `https defaults from the port, or from a known host's stored service on it`() {
        assertTrue(ManualTarget.httpsDefault(ToolId.HTTP_FINGERPRINT, 443, null)); assertTrue(ManualTarget.httpsDefault(ToolId.DIR_BRUTEFORCE, 5001, null))
        assertFalse(ManualTarget.httpsDefault(ToolId.HTTP_FINGERPRINT, 8080, null)); assertFalse(ManualTarget.httpsDefault(ToolId.HTTP_FINGERPRINT, null, null))
        assertTrue(ManualTarget.httpsDefault(ToolId.HTTP_FINGERPRINT, 5000, ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED)))
        assertFalse(ManualTarget.httpsDefault(ToolId.HTTP_FINGERPRINT, 443, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`: add these imports after the file's last `import`:
```kotlin
import com.northwalessolutions.netprobe.ui.manual.ManualCopy
```
and add this test before the class's closing brace (its own template list, so the 3A and 3C-1 lists above are not touched):
```kotlin
    @Test fun `manual tools and Home menu strings`() {
        val manualTemplates = listOf("Run a tool\\.\\.\\.", "Run a tool", "IP address", "10\\.0\\.3\\.20", "Port", "443", "Tool: .+", "Use HTTPS",
            "Uses port 445", "Uses UDP 161", "Known device: .+, results also show on its page", "Connect to Wi-Fi or Ethernet to run a tool",
            "Enter an address like 10\\.0\\.3\\.20", "That address cannot be used", "This tool needs a port", "Use a port from 1 to 65535",
            "Recent runs on this network", "No tools run on this network yet", ".+ on \\d+\\.\\d+\\.\\d+\\.\\d+:(\\d+|UDP 161)", ".+, .+",
            "Show .+ results for \\d+\\.\\d+\\.\\d+\\.\\d+:(\\d+|UDP 161)").map { Regex(it) }
        val strings = listOf(HomeCopy.MENU_RUN_TOOL, ManualCopy.TITLE, ManualCopy.IP, ManualCopy.IP_PLACEHOLDER, ManualCopy.PORT, ManualCopy.PORT_PLACEHOLDER,
            ManualCopy.toolRow("HTTP fingerprint"), ManualCopy.USE_HTTPS, ManualCopy.FIXED_SMB, ManualCopy.FIXED_SNMP, ManualCopy.knownDevice("CHNAS"),
            ManualCopy.NO_LAN, ManualCopy.ERR_IP, ManualCopy.ERR_ADDRESS, ManualCopy.ERR_PORT_NEEDED, ManualCopy.ERR_PORT_RANGE, ManualCopy.HISTORY,
            ManualCopy.HISTORY_EMPTY, ManualCopy.historyRow("HTTP fingerprint", "10.0.3.5", "5000"), ManualCopy.historyRow("SNMP walk", "10.0.3.1", "UDP 161"),
            ManualCopy.historyLine("Synology DSM, 6 findings", "2 min ago"), ManualCopy.historyDescription("HTTP fingerprint", "10.0.3.5", "5000"))
        strings.forEach { s -> assertTrue("not in the manual tools copy table: '$s'", manualTemplates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains('\u2014')) }
        assertEquals("three full stops, as 3B's Open with...", "Run a tool...", HomeCopy.MENU_RUN_TOOL)
        assertEquals("HTTP fingerprint on 10.0.3.5:5000", ManualCopy.historyRow("HTTP fingerprint", "10.0.3.5", "5000"))
        assertEquals("Synology DSM, 6 findings, 2 min ago", ManualCopy.historyLine("Synology DSM, 6 findings", "2 min ago"))
        val removed = HomeCopy::class.java.declaredFields.map { it.name } + ManualCopy::class.java.declaredFields.map { it.name }
        for (gone in listOf("MENU_AGGRESSIVE", "ERR_PRIVATE")) assertFalse("$gone was removed from the spec", gone in removed)   // spec 7.2
    }
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt`: add `import com.northwalessolutions.netprobe.engine.tools.ToolRoute` after `import com.northwalessolutions.netprobe.engine.tools.ToolId`, and add this test before the class's closing brace:
```kotlin
    @Test fun `route header strings`() {
        val routeTemplates = listOf("via Wi-Fi", "via Ethernet", "via VPN or other route").map { Regex(it) }
        val shown = ToolRoute.entries.map { ToolCopy.routeLine(it)!! }
        shown.forEach { s -> assertTrue("not in the route table: '$s'", routeTemplates.any { it.matches(s) }); assertFalse(s.contains('\u2014')) }
        assertEquals(listOf("via Wi-Fi", "via Ethernet", "via VPN or other route"), shown)
        assertNull("a Device-screen run shows no route", ToolCopy.routeLine(null))
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.*" --tests "com.northwalessolutions.netprobe.ui.home.CopyTableTest" --tests "com.northwalessolutions.netprobe.ui.tools.ToolCopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ManualCopy'`, `'ManualTarget'`, `'MENU_RUN_TOOL'`, `'routeLine'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

/** Every manual tools string (3C spec 7.2). Templates live in CopyTableTest; Run, Stop and the results sheet reuse ToolCopy and HomeCopy. */
object ManualCopy {
    const val TITLE = "Run a tool"
    const val IP = "IP address"
    const val IP_PLACEHOLDER = "10.0.3.20"
    const val PORT = "Port"
    const val PORT_PLACEHOLDER = "443"
    fun toolRow(name: String) = "Tool: $name"
    const val USE_HTTPS = "Use HTTPS"
    const val FIXED_SMB = "Uses port 445"
    const val FIXED_SNMP = "Uses UDP 161"
    fun knownDevice(name: String) = "Known device: $name, results also show on its page"
    const val NO_LAN = "Connect to Wi-Fi or Ethernet to run a tool"
    const val ERR_IP = "Enter an address like 10.0.3.20"
    const val ERR_ADDRESS = "That address cannot be used"
    const val ERR_PORT_NEEDED = "This tool needs a port"
    const val ERR_PORT_RANGE = "Use a port from 1 to 65535"
    const val HISTORY = "Recent runs on this network"
    const val HISTORY_EMPTY = "No tools run on this network yet"
    fun historyRow(tool: String, ip: String, portLabel: String) = "$tool on $ip:$portLabel"
    fun historyLine(summary: String, relative: String) = "$summary, $relative"
    fun historyDescription(tool: String, ip: String, portLabel: String) = "Show $tool results for $ip:$portLabel"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualTarget.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.end
import com.northwalessolutions.netprobe.netenv.start
import java.net.Inet4Address

/** The manual tools form checked (3C spec 4.2); [ip] and [port] are null whenever their field is not usable. */
data class ManualCheck(val ip: Inet4Address?, val port: Int?, val ipError: String?, val portError: String?) {
    val canRun: Boolean get() = ip != null && port != null
}

/**
 * Pure validation for "Run a tool" (3C spec 4.2, ruling 3): any unicast IPv4 address, public included, except the reserved blocks and the
 * home range's own network and broadcast addresses. No name lookup. Errors stay hidden until the first Run tap ([attempted]).
 */
object ManualTarget {
    const val PORT_MIN = 1
    const val PORT_MAX = 65_535
    private const val MAX_PORT_DIGITS = 5
    private const val UNSIGNED_MASK = 0xFFFFFFFFL
    /** Below /31 a subnet has a network and a broadcast address (RFC 3021 point-to-point links have neither). */
    private const val LAST_PREFIX_WITH_BROADCAST = 30
    /** 0.0.0.0/8 "this network", 127.0.0.0/8 loopback, 224.0.0.0/4 multicast, 240.0.0.0/4 reserved (255.255.255.255 included). */
    private val RESERVED = listOf(Subnet(0x00000000, 8), Subnet(0x7F000000, 8), Subnet(0xE0000000.toInt(), 4), Subnet(0xF0000000.toInt(), 4))

    fun fixedPort(tool: ToolId): Int? = when (tool) {
        ToolId.SNMP_WALK -> ToolApplicability.SNMP_PORT
        ToolId.SMB_SESSION -> ToolApplicability.SMB_PORT
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE -> null
    }

    fun check(ipText: String, portText: String, tool: ToolId, home: Subnet?, attempted: Boolean): ManualCheck {
        val ipTrim = ipText.trim(); val portTrim = portText.trim()
        val parsed = Ipv4.parse(ipTrim)
        val ipProblem = when {
            parsed == null -> ManualCopy.ERR_IP
            reserved(parsed, home) -> ManualCopy.ERR_ADDRESS
            else -> null
        }
        val fixed = fixedPort(tool)
        val port = fixed ?: portTrim.takeIf { it.length in 1..MAX_PORT_DIGITS && it.all(Char::isDigit) }?.toInt()?.takeIf { it in PORT_MIN..PORT_MAX }
        val portProblem = when {
            fixed != null -> null
            portTrim.isEmpty() -> ManualCopy.ERR_PORT_NEEDED
            port == null -> ManualCopy.ERR_PORT_RANGE
            else -> null
        }
        return ManualCheck(parsed.takeIf { ipProblem == null }, port.takeIf { portProblem == null },
            ipProblem.takeIf { attempted }, portProblem.takeIf { attempted })
    }

    /** TLS certificate always speaks TLS; SMB and SNMP never do; the web tools follow the "Use HTTPS" switch (spec 4.1 item 5). */
    fun tls(tool: ToolId, https: Boolean): Boolean = when (tool) {
        ToolId.TLS_CERTIFICATE -> true
        ToolId.SMB_SESSION, ToolId.SNMP_WALK -> false
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> https
    }

    /** The switch's starting value (spec 4.1 item 5): a known host's stored service on the port decides, else a TLS port number. */
    fun httpsDefault(tool: ToolId, port: Int?, service: ServiceInfo?): Boolean {
        if (port == null) return false
        if (service != null) return ToolApplicability.targetTls(tool, port, service)
        return port in ServiceIdentifier.TLS
    }

    private fun reserved(ip: Inet4Address, home: Subnet?): Boolean {
        if (RESERVED.any { it.contains(ip) }) return true
        if (home == null || home.prefix > LAST_PREFIX_WITH_BROADCAST || !home.contains(ip)) return false
        val v = SubnetMath.toInt(ip).toLong() and UNSIGNED_MASK
        return v == home.start() || v == home.end()
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: replace
```kotlin
    const val FORGET_NETWORK = "Forget this network"
```
with
```kotlin
    const val FORGET_NETWORK = "Forget this network"
    /** 3C spec 4.1: three full stops, matching 3B's "Open with..." (pinned in CopyTableTest). */
    const val MENU_RUN_TOOL = "Run a tool..."
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolCopy.kt`:
1. Replace `import com.northwalessolutions.netprobe.engine.tools.ToolId` with
```kotlin
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
```
2. Replace
```kotlin
    const val BIND_FAILED = "Tool skipped: could not bind to Wi-Fi"
```
with
```kotlin
    const val BIND_FAILED = "Tool skipped: could not bind to Wi-Fi"
    // The results header for a manual run (3C spec 4.5, controller ruling: "via Ethernet" allowed)
    const val ROUTE_WIFI = "via Wi-Fi"
    const val ROUTE_ETHERNET = "via Ethernet"
    const val ROUTE_OTHER = "via VPN or other route"
    fun routeLine(route: ToolRoute?): String? = when (route) {
        ToolRoute.WIFI -> ROUTE_WIFI
        ToolRoute.ETHERNET -> ROUTE_ETHERNET
        ToolRoute.OTHER -> ROUTE_OTHER
        null -> null
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicability.kt`: replace `    private const val SMB_PORT = 445` with `    const val SMB_PORT = 445`.

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --tests "com.northwalessolutions.netprobe.engine.tools.ToolApplicabilityTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualTarget.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicability.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualTargetTest.kt app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt
git status --short
git commit -m "Phase 3C-2: manual tools copy and validation, route strings"
```

---

### Task 5: ManualStarter: commit the pending Undo, resolve the network, choose the route, key the run on the hidden row, and discard it when the start is refused (STRONGER MODEL)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualStarter.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualStarterTest.kt`

**Interfaces:**
- Consumes: `ManualRow` (Task 2), `KnownRanges`, `ManualRoute`, `ToolRoute`, `ToolTarget.route` (Task 3), `ToolRunner.Start`, `ToolApplicability.serverName`, `ToolsUiModel.busyMessage`, `ToolCopy.BUSY_TOO_MANY`, `ScanController.NO_NETWORK` (3B, MVP).
- Produces:
```kotlin
data class ManualRequest(val env: NetworkEnv, val ip: Inet4Address, val port: Int, val tool: ToolId, val tls: Boolean, val options: ToolOptions = ToolOptions.None)
data class ManualResult(val start: ToolRunner.Start, val hostId: Long?)
enum class ManualStep { OPEN_SHEET, START }
object ManualFlow { fun firstStep(tool: ToolId): ManualStep }
object ManualMessages { fun of(result: ManualResult, busy: Map<Long, HostBusyRegistry.Work>): String? }
class ManualStarter(committedBefore, network, known, hasBinder, target, discard, runnerStart) { suspend fun startManual(r: ManualRequest): ManualResult }
```
The start order is spec 4.3: inside `committedBefore` (a pending Remove or Forget is final first), resolve the network (null or forgotten: `NoNetwork`, no row), choose the route from the live known ranges, refuse a Wi-Fi or Ethernet route with no binder (`NoNetwork`, no row), take or create the row, start the runner, and delete the row when this call created it and the start was not `Started` (or threw). The row transaction and the runner start are deliberately separate transactions (self-review gap 4): the runner takes its own locks and writes `tool_runs` in its own transaction, and the conditional delete (`manualOnly = 1` and no run) can never remove a promoted or started row.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualStarterTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ManualRow
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.netenv.Transport
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 22 (4.3, G8, Review Focus 2 and 3). */
class ManualStarterTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val env = NetworkEnv(ip("10.0.3.20"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)

    private class Rig(var networkId: Long? = 1, var binder: Boolean = true, var existing: HostEntity? = null,
                      var result: ToolRunner.Start = ToolRunner.Start.Started(5), var throwOnStart: Boolean = false) {
        val calls = mutableListOf<String>()
        val targets = mutableListOf<ToolTarget>()
        val discarded = mutableListOf<Long>()
        var inserted = 0
        val starter = ManualStarter(
            committedBefore = { body -> calls += "commit"; body() },
            network = { calls += "network"; networkId },
            known = { _, _ -> KnownRanges.of(SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24), emptyList(), emptyList()) },
            hasBinder = { binder },
            target = { id, ipText ->
                calls += "target"
                existing?.let { ManualRow(it, inserted = false) }
                    ?: ManualRow(TestHosts.host(ip = ipText).copy(id = NEW_ROW, networkId = id, manualOnly = true), inserted = true).also { inserted++ }
            },
            discard = { id -> discarded += id },
            runnerStart = { t, _, _ ->
                calls += "start"; targets += t
                if (throwOnStart) throw IllegalStateException("runner failed")
                result
            })
        companion object { const val NEW_ROW = 40L }
    }
    private fun req(ipText: String, tool: ToolId = ToolId.HTTP_FINGERPRINT, port: Int = 443, tls: Boolean = true) = ManualRequest(env, ip(ipText), port, tool, tls)

    @Test fun `the pending Undo is committed before the row is looked up or made`() = runBlocking {
        val rig = Rig(); rig.starter.startManual(req("10.0.3.5"))
        assertEquals(listOf("commit", "network", "target", "start"), rig.calls)
    }
    @Test fun `a refused start deletes the row this call inserted and keeps a row that existed`() = runBlocking {
        for (refusal in listOf(ToolRunner.Start.TooMany, ToolRunner.Start.HostBusy, ToolRunner.Start.NoNetwork)) {
            val fresh = Rig(result = refusal)
            assertEquals(refusal, fresh.starter.startManual(req("10.0.3.200")).start)
            assertEquals("$refusal", listOf(Rig.NEW_ROW), fresh.discarded)
            val known = Rig(result = refusal, existing = TestHosts.host(ip = "10.0.3.5").copy(id = 9))
            assertEquals(ManualResult(refusal, 9), known.starter.startManual(req("10.0.3.5")))
            assertTrue("$refusal: a row that existed is never deleted", known.discarded.isEmpty())
        }
    }
    @Test fun `a started run keeps its row`() = runBlocking {
        val rig = Rig()
        assertEquals(ManualResult(ToolRunner.Start.Started(5), Rig.NEW_ROW), rig.starter.startManual(req("10.0.3.200")))
        assertTrue(rig.discarded.isEmpty())
    }
    @Test fun `a start that throws deletes the inserted row and rethrows`() = runBlocking {
        val rig = Rig(throwOnStart = true)
        try { rig.starter.startManual(req("10.0.3.200")); fail("expected the runner's exception") } catch (_: IllegalStateException) { }
        assertEquals(listOf(Rig.NEW_ROW), rig.discarded)
    }
    @Test fun `no network ends with NoNetwork and creates no row`() = runBlocking {
        val rig = Rig(networkId = null)
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, null), rig.starter.startManual(req("10.0.3.5")))
        assertEquals(0, rig.inserted); assertFalse("target" in rig.calls); assertFalse("start" in rig.calls)
    }
    @Test fun `a known-range target with no Wi-Fi binder is NoNetwork before any row exists`() = runBlocking {
        val rig = Rig(binder = false)
        assertEquals(ManualResult(ToolRunner.Start.NoNetwork, null), rig.starter.startManual(req("10.0.3.5")))
        assertEquals(0, rig.inserted); assertTrue(rig.targets.isEmpty())
    }
    @Test fun `a target outside the known ranges needs no binder and is routed OTHER`() = runBlocking {
        val rig = Rig(binder = false)
        assertTrue(rig.starter.startManual(req("10.20.0.5")).start is ToolRunner.Start.Started)
        assertEquals(ToolRoute.OTHER, rig.targets.single().route)
        val public = Rig(); public.starter.startManual(req("1.1.1.1", ToolId.TLS_CERTIFICATE))
        assertEquals(ToolRoute.OTHER, public.targets.single().route)
    }
    @Test fun `the target carries the port, tls, route and the name rule`() = runBlocking {
        val nas = TestHosts.host(ip = "10.0.3.59", mdns = "chnas.local").copy(id = 9)
        val fingerprint = Rig(existing = nas); fingerprint.starter.startManual(req("10.0.3.59", ToolId.HTTP_FINGERPRINT, port = 5000, tls = false))
        assertEquals(ToolTarget(9, ip("10.0.3.59"), 5000, tls = false, serverName = "chnas.local", route = ToolRoute.WIFI), fingerprint.targets.single())
        val brute = Rig(existing = nas); brute.starter.startManual(req("10.0.3.59", ToolId.DIR_BRUTEFORCE, port = 5001, tls = true))
        assertEquals("brute-force always sends the IP (3B 4.9)", ToolTarget(9, ip("10.0.3.59"), 5001, tls = true, serverName = null, route = ToolRoute.WIFI), brute.targets.single())
        val fresh = Rig(); fresh.starter.startManual(req("10.0.3.200", ToolId.TLS_CERTIFICATE))
        assertNull("a fresh manual row has no name to send", fresh.targets.single().serverName)
    }
    @Test fun `each refusal maps to the 3B message`() {
        assertNull(ManualMessages.of(ManualResult(ToolRunner.Start.Started(1), 9), emptyMap()))
        assertEquals(ToolCopy.BUSY_TOOL, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), mapOf(9L to HostBusyRegistry.Work.TOOL)))
        assertEquals(ToolCopy.BUSY_PORT_SCAN, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), mapOf(9L to HostBusyRegistry.Work.PORT_SCAN)))
        assertEquals("a claim released in between still reads as a tool", ToolCopy.BUSY_TOOL, ManualMessages.of(ManualResult(ToolRunner.Start.HostBusy, 9), emptyMap()))
        assertEquals(ToolCopy.BUSY_TOO_MANY, ManualMessages.of(ManualResult(ToolRunner.Start.TooMany, 9), emptyMap()))
        assertEquals(ScanController.NO_NETWORK, ManualMessages.of(ManualResult(ToolRunner.Start.NoNetwork, null), emptyMap()))
    }
    @Test fun `brute-force opens its sheet first, so the starter is reached only from the sheet's Run`() {
        assertEquals(ManualStep.OPEN_SHEET, ManualFlow.firstStep(ToolId.DIR_BRUTEFORCE))
        for (t in ToolId.entries - ToolId.DIR_BRUTEFORCE) assertEquals(t.key, ManualStep.START, ManualFlow.firstStep(t))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.ManualStarterTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ManualStarter'`, `'ManualRequest'`, `'ManualResult'`, `'ManualFlow'`, `'ManualMessages'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualStarter.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.ManualRow
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ManualRoute
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolOptions
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.ScanController
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** One Run tap after validation (3C spec 4.3): [tls] already follows ManualTarget.tls, [options] the brute-force sheet when there was one. */
data class ManualRequest(val env: NetworkEnv, val ip: Inet4Address, val port: Int, val tool: ToolId, val tls: Boolean, val options: ToolOptions = ToolOptions.None)
/** [hostId] is the row the run was keyed on, or null when the start ended before any row (no network, no binder). */
data class ManualResult(val start: ToolRunner.Start, val hostId: Long?)

enum class ManualStep { OPEN_SHEET, START }

object ManualFlow {
    /** Spec 4.3 step 3: brute-force opens its options sheet first and creates nothing until the sheet's Run; the other tools start at once. */
    fun firstStep(tool: ToolId): ManualStep = if (tool == ToolId.DIR_BRUTEFORCE) ManualStep.OPEN_SHEET else ManualStep.START
}

/** Spec 4.3 step 6: the 3B messages in the screen's snackbar; null when the run started. */
object ManualMessages {
    fun of(result: ManualResult, busy: Map<Long, HostBusyRegistry.Work>): String? = when (result.start) {
        is ToolRunner.Start.Started -> null
        ToolRunner.Start.HostBusy -> ToolsUiModel.busyMessage(result.hostId?.let { busy[it] } ?: HostBusyRegistry.Work.TOOL)
        ToolRunner.Start.TooMany -> ToolCopy.BUSY_TOO_MANY
        ToolRunner.Start.NoNetwork -> ScanController.NO_NETWORK
    }
}

/**
 * The manual start path (3C spec 4.3, 4.5, G8). Every dependency is a function so the JVM tests drive it with fakes; ManualToolsViewModel
 * passes the container's undo store, network repository, settings, binder lookup, host repository and tool runner.
 */
class ManualStarter(
    private val committedBefore: suspend (suspend () -> ManualResult) -> ManualResult,
    /** The network row for [NetworkEnv], or null when there is none or its Forget is pending. */
    private val network: suspend (NetworkEnv) -> Long?,
    private val known: suspend (Long, NetworkEnv) -> KnownRanges,
    private val hasBinder: (NetworkEnv) -> Boolean,
    private val target: suspend (Long, String) -> ManualRow,
    /** Deletes the row only while it is still manualOnly with no run (HostRepository.discardManual). */
    private val discard: suspend (Long) -> Unit,
    private val runnerStart: suspend (ToolTarget, ToolId, ToolOptions) -> ToolRunner.Start,
) {
    suspend fun startManual(r: ManualRequest): ManualResult = committedBefore { begin(r) }

    private suspend fun begin(r: ManualRequest): ManualResult {
        val networkId = network(r.env) ?: return ManualResult(ToolRunner.Start.NoNetwork, null)
        val route = ManualRoute.of(r.ip, known(networkId, r.env), r.env)
        // Spec 4.5: a known-range target with no Wi-Fi or Ethernet Network to bind is refused here, before any row, never run unbound.
        if (ManualRoute.needsBinder(route) && !hasBinder(r.env)) return ManualResult(ToolRunner.Start.NoNetwork, null)
        val row = target(networkId, r.ip.hostAddress)
        // Brute-force always sends the IP (3B 4.9); the other tools send the row's known name, which a fresh manual row does not have.
        val name = if (r.tool == ToolId.DIR_BRUTEFORCE) null else ToolApplicability.serverName(row.host)
        val started = try {
            runnerStart(ToolTarget(row.host.id, r.ip, r.port, r.tls, name, route), r.tool, r.options)
        } catch (t: Throwable) {
            if (row.inserted) withContext(NonCancellable) { discard(row.host.id) }
            throw t
        }
        if (started !is ToolRunner.Start.Started && row.inserted) discard(row.host.id)
        return ManualResult(started, row.host.id)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualStarter.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualStarterTest.kt
git status --short
git commit -m "Phase 3C-2: manual tool start path with the hidden row and its discard"
```

---

### Task 6: The results sheet header names a manual run's route

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiState.kt` (`ToolResultsState.route`, `ToolResultsModel.ofRows`), `ui/tools/ToolResultsSheet.kt` (the route line)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsRouteTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/RouteLineTest.kt`

**Interfaces:**
- Consumes: `ToolRunEntity.route` (Task 1), `ToolRoute.parse` (Task 3), `ToolCopy.routeLine` (Task 4), `ToolResultsSheet`, `ToolsFakes.results` (3B).
- Produces:
```kotlin
data class ToolResultsState(..., val active: Boolean, val route: String? = null)   // last, defaulted: 3B's ToolsFakes and every caller compile unchanged
```
Behaviour (spec 4.5): under the title row the sheet shows "via Wi-Fi", "via Ethernet" or "via VPN or other route" in `data` / `textMuted` (tag `routeLine`), and nothing for a Device-screen or pre-4 run (route null) or an unknown stored name. The Device screen gets this for free: a manual target later promoted to a device shows the route of its manual runs on its own page.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsRouteTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 3C spec 8.1 item 23. */
class ToolResultsRouteTest {
    private fun runWith(route: String?) = ToolRunEntity(id = 3, hostId = 7, toolId = "tls-certificate", port = 443, startedAt = 0, finishedAt = 1,
        status = ToolRunStatus.DONE, optionsJson = "{}", summary = "unifi.local, expires 3 Jan 2027", route = route)

    @Test fun `the header names the stored route and shows nothing for a Device-screen run`() {
        val cases = listOf("WIFI" to "via Wi-Fi", "ETHERNET" to "via Ethernet", "OTHER" to "via VPN or other route", null to null, "SOMETHING" to null)
        for ((stored, expected) in cases) {
            val r = runWith(stored)
            assertEquals("stored $stored", expected, ToolResultsModel.ofRows(r, listOf(r), "1.1.1.1", emptyList(), null, 10).route)
        }
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/RouteLineTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 6 (the route line) on the 3B results sheet, fake state only. */
@RunWith(AndroidJUnit4::class)
class RouteLineTest {
    @get:Rule val rule = createComposeRule()
    private fun show(state: ToolResultsState) = rule.setContent {
        NetProbeTheme { ToolResultsSheet(state, onOlder = {}, onNewer = {}, onCopy = {}, onOpen = {}, onShare = {}, onDismiss = {}) }
    }

    @Test fun aManualRunsRouteSitsUnderTheTitleAboveTheStatus() {
        show(ToolsFakes.results().copy(route = ToolCopy.ROUTE_OTHER))
        val route = rule.onNodeWithTag("routeLine").assertTextEquals("via VPN or other route").fetchSemanticsNode().positionInRoot.y
        val title = rule.onNodeWithText("Directory brute-force  10.0.3.10:5000").fetchSemanticsNode().positionInRoot.y
        val status = rule.onNodeWithText("Done, 2 hours ago").fetchSemanticsNode().positionInRoot.y
        assertTrue("title $title, route $route, status $status", title < route && route < status)
    }
    @Test fun aDeviceScreenRunShowsNoRouteLine() {
        show(ToolsFakes.results())
        rule.onNodeWithTag("routeLine").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the JVM test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.tools.ToolResultsRouteTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'route'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiState.kt`:
1. Replace `import com.northwalessolutions.netprobe.engine.tools.ToolRunner` with
```kotlin
import com.northwalessolutions.netprobe.engine.tools.ToolRoute
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
```
2. Replace
```kotlin
                            val findings: List<FindingRow>, val active: Boolean)
```
with
```kotlin
                            val findings: List<FindingRow>, val active: Boolean,
                            /** "via Wi-Fi", "via Ethernet" or "via VPN or other route" for a manual run (3C spec 4.5); null shows nothing. */
                            val route: String? = null)
```
3. In `ToolResultsModel.ofRows`, replace
```kotlin
        return ToolResultsState(run.id, ToolCopy.resultsTitle(ToolCopy.toolName(tool), ip, run.port), status, run.summary, index, maxOf(sameTool.size, 1), rows, live != null)
```
with
```kotlin
        return ToolResultsState(run.id, ToolCopy.resultsTitle(ToolCopy.toolName(tool), ip, run.port), status, run.summary, index, maxOf(sameTool.size, 1), rows, live != null,
            route = ToolCopy.routeLine(ToolRoute.parse(run.route)))
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsSheet.kt`: insert this line as the statement directly before the status-line statement, inside the header `Column`, after the title `Row`:
```kotlin
                state.route?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.testTag("routeLine")) }   // 3C spec 4.5
```
At 82084d3 the status-line statement is the single line `Text(state.statusLine, style = NetProbeText.data, color = NetProbeColors.textMuted)`. The 3B device QA's fix F4 (uncommitted in the working tree when this plan was written) turns it into the pair `if (liveStatus != null) LiveText(...)` / `else Text(state.statusLine, ...)`; if that has landed, the line goes before the `if`, never between the `if` and its `else`.

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.tools.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` twice; 3B's `ToolResultsSheetTest` and `ToolsFakes` compile unchanged.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiState.kt app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsSheet.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsRouteTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/RouteLineTest.kt
git status --short
git commit -m "Phase 3C-2: results sheet names a manual run's route"
```

---

### Task 7: The "Run a tool" screen: ManualPresenter, ManualToolsViewModel, ManualToolsScreen, the Home overflow item and the "tools" route

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenter.kt`, `ui/manual/ManualToolsViewModel.kt`, `ui/manual/ManualToolsScreen.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt` (`HomeMenu`), `ui/home/HomeContent.kt` (`HomeActions`, the `HomeMenu` call), `ui/home/HomeScreen.kt`, `ui/Nav.kt` (whole file)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenterTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt`, `ui/home/HomeMenuTest.kt`

**Interfaces:**
- Consumes: `ManualCopy`, `ManualTarget` (Task 4), `ManualStarter`, `ManualRequest`, `ManualFlow`, `ManualMessages` (Task 5), `KnownRanges` (Task 3), `NetworkRun`, `HostRepository.manualTarget`, `discardManual`, `ToolRepository.recentForNetwork`, `NetworkSignature.identityKey(NetworkEntity)` (Task 2), `ToolResultsState.route` (Task 6), `HomeRange` (3C-1); `ToolResultsSheet`, `DirBruteSheet`, `DirBruteSheetState`, `ToolResultsModel`, `ToolsUiModel`, `ToolExport`, `ActionPill`, `SwitchRow`, `BackToHome`, `npFieldColors`, `RelativeTime`, `DisplayName` (3A, 3B).
- Produces:
```kotlin
data class ManualForm(val ipText: String = "", val portText: String = "", val tool: ToolId = ToolId.HTTP_FINGERPRINT, val httpsChoice: Boolean? = null, val attempted: Boolean = false)
data class KnownTarget(val host: HostEntity, val services: Map<Int, ServiceInfo>)
data class ManualHistoryRow(val runId: Long, val title: String, val line: String, val description: String)
data class ManualUiState(form, ipError, portError, portEnabled, showHttps, https, fixedLine, infoLine, lan, running, progress, history, results, dirBrute, message, appMessage)
object ManualPresenter { const val RECENT_RUNS = 20; fun present(...): ManualUiState }
class ManualToolsViewModel(c: AppContainer) : ViewModel() { val state: StateFlow<ManualUiState>; fun setIp/setPort/setTool/setHttps/runTool/stopTool/openRun/olderRun/newerRun/closeResults/shareRun/copyFinding/openFinding/startDirBrute/closeDirBrute/dismissMessage/dismissAppMessage }
data class ManualActions(...); @Composable fun ManualToolsContent(state: ManualUiState, actions: ManualActions); @Composable fun ManualToolsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {})
fun HomeMenu(..., onForget: () -> Unit = {}, onRunTool: () -> Unit = {})      // item tag menuRunTool, always enabled
data class HomeActions(..., val onRunTool: () -> Unit = {}); fun HomeScreen(container, onOpenHost, onOpenSettings, onOpenTools: () -> Unit = {})
fun NetProbeNavHost(nav, home, detail, settings, wordlists = {}, tools: @Composable () -> Unit = {})   // route "tools"
```
Per-tick updates (3B device QA F4, uncommitted in the working tree when this plan was written): F4 moves the Device screen's per-tick progress out of its tool state so the screen does not recompose on every runner tick. The manual screen below combines `c.toolRunner.active` like 3B's Device screen did, and its `state` ends with `distinctUntilChanged()`, so it re-emits only when the shown values change (the progress text changes at most once per tick of the running run). If F4 lands as a reusable pattern (a separate bar flow and a live text composable), the controller rebases this task to use it; Task 1 Step 0 stops on F4's files.

Screen layout (spec 4.1): a pinned top block (status-bar padding, background colour, never scrolls) with the back row, the title, the IP (weight 0.65) and Port (0.35) fields, the tool row, the option row (the "Use HTTPS" switch for the two web tools, "Uses port 445" or "Uses UDP 161", nothing for TLS certificate), the known-device or no-LAN line, and the Run / Stop pill with the 3B progress text; below it the only scrolling part, the `LazyColumn` `manualHistory`. Errors show as each field's supporting text from the first Run tap on (self-review gap 6).

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenterTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 4.1 and 4.2 as screen state (8.2 item 6 drives the composable with what this produces). */
class ManualPresenterTest {
    private val home = SubnetMath.of(Ipv4.parse("10.0.3.20")!!, 24)
    private val now = 10 * 60_000L
    private fun present(form: ManualForm, lan: Boolean = true, known: KnownTarget? = null, history: List<NetworkRun> = emptyList(), active: ToolRunner.Active? = null) =
        ManualPresenter.present(form, home, lan, known, history, active, null, null, null, null, now) { "3 Jan 2026" }
    private fun runOn(id: Long, tool: String, port: Int, summary: String) = ToolRunEntity(id = id, hostId = 9, toolId = tool, port = port,
        startedAt = now - 2 * 60_000L, finishedAt = now, status = ToolRunStatus.DONE, optionsJson = "{}", summary = summary)

    @Test fun `errors wait for the first Run tap`() {
        val typed = ManualForm(ipText = "10.0.3", portText = "")
        present(typed).let { assertNull(it.ipError); assertNull(it.portError) }
        present(typed.copy(attempted = true)).let { assertEquals(ManualCopy.ERR_IP, it.ipError); assertEquals(ManualCopy.ERR_PORT_NEEDED, it.portError) }
    }
    @Test fun `each tool's option row and port field`() {
        present(ManualForm(tool = ToolId.SMB_SESSION)).let { assertFalse(it.portEnabled); assertFalse(it.showHttps); assertEquals(ManualCopy.FIXED_SMB, it.fixedLine) }
        present(ManualForm(tool = ToolId.SNMP_WALK)).let { assertFalse(it.portEnabled); assertEquals(ManualCopy.FIXED_SNMP, it.fixedLine) }
        present(ManualForm(tool = ToolId.TLS_CERTIFICATE)).let { assertTrue(it.portEnabled); assertFalse(it.showHttps); assertNull(it.fixedLine) }
        for (t in listOf(ToolId.HTTP_FINGERPRINT, ToolId.DIR_BRUTEFORCE)) present(ManualForm(tool = t)).let { assertTrue(it.showHttps); assertNull(it.fixedLine) }
    }
    @Test fun `Use HTTPS starts from the port or the known service and keeps the Director's choice`() {
        assertTrue(present(ManualForm(portText = "443")).https)
        assertFalse(present(ManualForm(portText = "8080")).https)
        val nas = KnownTarget(TestHosts.host(ip = "10.0.3.59", mdns = "CHNAS"), mapOf(5000 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED)))
        assertTrue(present(ManualForm(ipText = "10.0.3.59", portText = "5000"), known = nas).https)
        assertFalse(present(ManualForm(portText = "443", httpsChoice = false)).https)
    }
    @Test fun `a known device names itself and no LAN replaces it`() {
        val nas = KnownTarget(TestHosts.host(ip = "10.0.3.59", mdns = "CHNAS"), emptyMap())
        assertEquals(ManualCopy.knownDevice("CHNAS"), present(ManualForm(ipText = "10.0.3.59"), known = nas).infoLine)
        present(ManualForm(ipText = "10.0.3.59"), lan = false, known = nas).let { assertEquals(ManualCopy.NO_LAN, it.infoLine); assertFalse(it.lan) }
        assertNull(present(ManualForm(ipText = "10.20.0.5")).infoLine)
    }
    @Test fun `the run this screen started shows Stop with the 3B progress`() {
        val a = ToolRunner.Active(1, 9, ToolId.DIR_BRUTEFORCE, 5000, 3_000, 12, 300, 2, 0, false)
        present(ManualForm(), active = a).let { assertTrue(it.running); assertEquals("12 of 300, 2 found  3 s", it.progress) }
        present(ManualForm()).let { assertFalse(it.running); assertNull(it.progress) }
    }
    @Test fun `history rows read tool on ip and port, then summary and age`() {
        val rows = present(ManualForm(), history = listOf(NetworkRun(runOn(2, "http-fingerprint", 5000, "Synology DSM, 6 findings"), "10.0.3.5"),
            NetworkRun(runOn(3, "snmp-walk", 161, "gw, 3 interfaces"), "10.0.3.1"), NetworkRun(runOn(4, "no-such-tool", 1, "x"), "10.0.3.9"))).history
        assertEquals(listOf(2L, 3L), rows.map { it.runId })   // an unknown tool key is skipped
        assertEquals("HTTP fingerprint on 10.0.3.5:5000", rows[0].title)
        assertEquals("Synology DSM, 6 findings, 2 minutes ago", rows[0].line)
        assertEquals("Show HTTP fingerprint results for 10.0.3.5:5000", rows[0].description)
        assertEquals("SNMP walk on 10.0.3.1:UDP 161", rows[1].title)
    }
}
```

`"2 minutes ago"` is `RelativeTime.of`'s wording for 120 s (checked against the 3A code); `RelativeTime` is not changed.

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.ManualPresenterTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ManualPresenter'`, `'ManualForm'`, `'KnownTarget'`.

- [ ] **Step 3: Implement the presenter**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenter.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheetState
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolResultsState
import com.northwalessolutions.netprobe.ui.tools.ToolsUiModel

/** What the Director has typed and chosen; [httpsChoice] is null until he touches the switch, [attempted] true from the first Run tap. */
data class ManualForm(val ipText: String = "", val portText: String = "", val tool: ToolId = ToolId.HTTP_FINGERPRINT,
                      val httpsChoice: Boolean? = null, val attempted: Boolean = false)
/** A visible host of this network at the typed IP, with its stored services for the HTTPS default (spec 4.1 items 5 and 6). */
data class KnownTarget(val host: HostEntity, val services: Map<Int, ServiceInfo>)
data class ManualHistoryRow(val runId: Long, val title: String, val line: String, val description: String)
data class ManualUiState(
    val form: ManualForm = ManualForm(), val ipError: String? = null, val portError: String? = null,
    val portEnabled: Boolean = true, val showHttps: Boolean = true, val https: Boolean = false, val fixedLine: String? = null,
    val infoLine: String? = null, val lan: Boolean = true, val running: Boolean = false, val progress: String? = null,
    val history: List<ManualHistoryRow> = emptyList(), val results: ToolResultsState? = null, val dirBrute: DirBruteSheetState? = null,
    val message: String? = null, val appMessage: AppMessage? = null,
)

/** Pure state for the "Run a tool" screen (3C spec 4.1, 4.2); the view model only gathers its inputs. */
object ManualPresenter {
    const val RECENT_RUNS = 20

    fun present(form: ManualForm, home: Subnet?, lan: Boolean, known: KnownTarget?, history: List<NetworkRun>, active: ToolRunner.Active?,
                results: ToolResultsState?, dirBrute: DirBruteSheetState?, message: String?, appMessage: AppMessage?, now: Long,
                formatDate: (Long) -> String = RelativeTime::defaultDate): ManualUiState {
        val checked = ManualTarget.check(form.ipText, form.portText, form.tool, home, form.attempted)
        val fixed = ManualTarget.fixedPort(form.tool)
        val port = fixed ?: form.portText.trim().toIntOrNull()
        val webTool = form.tool == ToolId.DIR_BRUTEFORCE || form.tool == ToolId.HTTP_FINGERPRINT
        val https = form.httpsChoice ?: ManualTarget.httpsDefault(form.tool, port, port?.let { known?.services?.get(it) })
        val fixedLine = when (form.tool) {
            ToolId.SMB_SESSION -> ManualCopy.FIXED_SMB
            ToolId.SNMP_WALK -> ManualCopy.FIXED_SNMP
            ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE -> null
        }
        val info = if (!lan) ManualCopy.NO_LAN else known?.let { ManualCopy.knownDevice(DisplayName.of(it.host)) }
        val rows = history.mapNotNull { nr -> ToolId.byKey(nr.run.toolId)?.let { id -> historyRow(nr, id, now, formatDate) } }
        return ManualUiState(form, checked.ipError, checked.portError, portEnabled = fixed == null, showHttps = webTool, https = https, fixedLine = fixedLine,
            infoLine = info, lan = lan, running = active != null, progress = active?.let { ToolsUiModel.bar(it).progress }, history = rows,
            results = results, dirBrute = dirBrute, message = message, appMessage = appMessage)
    }

    private fun historyRow(nr: NetworkRun, id: ToolId, now: Long, formatDate: (Long) -> String): ManualHistoryRow {
        val name = ToolCopy.toolName(id)
        val label = ToolCopy.portLabel(nr.run.port, id == ToolId.SNMP_WALK)
        return ManualHistoryRow(nr.run.id, ManualCopy.historyRow(name, nr.ip, label),
            ManualCopy.historyLine(ToolsUiModel.summaryOf(nr.run), RelativeTime.of(nr.run.startedAt, now, formatDate)),
            ManualCopy.historyDescription(name, nr.ip, label))
    }
}
```

- [ ] **Step 4: Run the JVM test to see it pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.manual.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Write the Compose tests (compile only, run in Task 12)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolsFakes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 6: fake state only, no container, no database. */
@RunWith(AndroidJUnit4::class)
class ManualToolsContentTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private var s by mutableStateOf(ManualUiState())
    private val actions = ManualActions(onRun = { events += "run" }, onStop = { events += "stop" }, onOpenRun = { events += "open $it" },
        onTool = { events += "tool ${it.key}" })
    private fun show(state: ManualUiState) { s = state; rule.setContent { NetProbeTheme { ManualToolsContent(s, actions) } } }
    private fun history(n: Int) = (1..n).map {
        ManualHistoryRow(it.toLong(), ManualCopy.historyRow(ToolCopy.HTTP_FINGERPRINT, "10.0.3.$it", "80"), ManualCopy.historyLine("Done", "2 minutes ago"),
            ManualCopy.historyDescription(ToolCopy.HTTP_FINGERPRINT, "10.0.3.$it", "80"))
    }

    @Test fun theRunPillIsPinnedOutsideTheHistoryAndScrollingNeverMovesIt() {
        show(ManualUiState(history = history(30)))
        val pill = rule.onNodeWithTag("manualRun")
        assertFalse(generateSequence(pill.fetchSemanticsNode().parent) { it.parent }.any { SemanticsProperties.VerticalScrollAxisRange in it.config })
        val y = pill.fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag("manualHistory").performScrollToIndex(30)
        rule.waitForIdle()
        assertEquals(y, rule.onNodeWithTag("manualRun").fetchSemanticsNode().positionInRoot.y)
        rule.onNodeWithTag("manualRun").performClick()
        assertEquals(listOf("run"), events)
    }
    @Test fun errorsShowUnderTheirFields() {
        show(ManualUiState(form = ManualForm(ipText = "10.0.3", attempted = true), ipError = ManualCopy.ERR_IP, portError = ManualCopy.ERR_PORT_NEEDED))
        rule.onNodeWithText(ManualCopy.ERR_IP).assertExists()
        rule.onNodeWithText(ManualCopy.ERR_PORT_NEEDED).assertExists()
    }
    @Test fun smbDisablesThePortFieldAndSaysWhichPort() {
        show(ManualUiState(form = ManualForm(tool = ToolId.SMB_SESSION), portEnabled = false, showHttps = false, fixedLine = ManualCopy.FIXED_SMB))
        rule.onNodeWithTag("manualPort").assertIsNotEnabled()
        rule.onNodeWithText(ManualCopy.FIXED_SMB).assertExists()
        rule.onNodeWithText(ManualCopy.USE_HTTPS).assertDoesNotExist()
    }
    @Test fun theToolRowListsTheFiveToolsInOrder() {
        show(ManualUiState())
        rule.onNodeWithTag("manualTool").assert(hasText(ManualCopy.toolRow(ToolCopy.HTTP_FINGERPRINT))).performClick()
        val ys = ToolId.entries.map { rule.onNodeWithTag("manualTool:${it.key}").fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        rule.onNodeWithTag("manualTool:${ToolId.SNMP_WALK.key}").performClick()
        assertEquals(listOf("tool snmp-walk"), events)
    }
    @Test fun aRunningRunShowsStopAndItsProgressAndLocksTheFields() {
        show(ManualUiState(running = true, progress = "12 of 300, 2 found  3 s"))
        rule.onNodeWithTag("manualRun").assert(hasText(HomeCopy.STOP)).assert(hasContentDescription(ToolCopy.STOP_DESCRIPTION)).performClick()
        rule.onNodeWithTag("manualProgress").assertTextEquals("12 of 300, 2 found  3 s")
        rule.onNodeWithTag("manualIp").assertIsNotEnabled()
        assertEquals(listOf("stop"), events)
    }
    @Test fun aHistoryRowOpensItsRunAndTheSheetShowsItsRoute() {
        show(ManualUiState(history = history(2)))
        rule.onNodeWithTag("historyRow:2").assert(hasContentDescription(ManualCopy.historyDescription(ToolCopy.HTTP_FINGERPRINT, "10.0.3.2", "80"))).performClick()
        assertEquals(listOf("open 2"), events)
        s = s.copy(results = ToolsFakes.results().copy(route = ToolCopy.ROUTE_WIFI))
        rule.waitForIdle()
        rule.onNodeWithTag("routeLine").assertTextEquals("via Wi-Fi")
    }
    @Test fun withNoLanTheFieldsAndRunAreDisabledAndTheLineSaysWhy() {
        show(ManualUiState(lan = false, infoLine = ManualCopy.NO_LAN))
        rule.onNodeWithTag("manualIp").assertIsNotEnabled()
        rule.onNodeWithTag("manualRun").assertIsNotEnabled()
        rule.onNodeWithText(ManualCopy.NO_LAN).assertExists()
    }
    @Test fun anEmptyHistorySaysSo() {
        show(ManualUiState())
        rule.onNodeWithText(ManualCopy.HISTORY_EMPTY).assertExists()
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeMenuTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 8.2 item 5: the Home overflow is exactly these four items, in this order (G1: no Aggressive item). */
@RunWith(AndroidJUnit4::class)
class HomeMenuTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theOverflowListsQuickScanAllRunAToolForgetAndSettingsInThatOrderAndNothingElse() {
        val events = mutableListOf<String>()
        rule.setContent { NetProbeTheme { HomeMenu(quickEnabled = true, quickScanning = false, onQuick = {}, onStopQuick = {}, onSettings = {},
            forgetEnabled = true, onForget = {}, onRunTool = { events += "tool" }) } }
        rule.onNodeWithTag("homeMenu").performClick()
        val labels = listOf("Quick scan all", HomeCopy.MENU_RUN_TOOL, HomeCopy.FORGET_NETWORK, "Settings")
        val ys = labels.map { rule.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(4)
        rule.onNodeWithTag("menuRunTool").assert(hasText(HomeCopy.MENU_RUN_TOOL)).assertIsEnabled().performClick()
        assertEquals(listOf("tool"), events)
    }
    @Test fun runAToolIsEnabledWithNothingScannedAndNothingToForget() {
        rule.setContent { NetProbeTheme { HomeMenu(quickEnabled = false, quickScanning = false, onQuick = {}, onStopQuick = {}, onSettings = {}) } }
        rule.onNodeWithTag("homeMenu").performClick()
        rule.onNodeWithTag("menuRunTool").assertIsEnabled()
    }
}
```

- [ ] **Step 6: Implement the view model**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsViewModel.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.data.NetworkRun
import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolOptions
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheetState
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolExport
import com.northwalessolutions.netprobe.ui.tools.ToolResultsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The "Run a tool" screen (3C spec 4). Every run goes through ManualStarter and the existing ToolRunner; the screen remembers only the host
 * of the run it started, so Stop and the progress follow that run. Starts and the brute-force sheet's Run use appScope, as the Device
 * screen does, so leaving the screen never cancels a start half-way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualToolsViewModel(private val c: AppContainer) : ViewModel() {
    private data class PendingBrute(val request: ManualRequest, val sheet: DirBruteSheetState)
    private data class ResultsSource(val run: ToolRunEntity, val sameTool: List<ToolRunEntity>, val ip: String, val rows: List<FindingRow>)

    private val form = MutableStateFlow(ManualForm())
    private val message = MutableStateFlow<String?>(null)
    private val startedHostId = MutableStateFlow<Long?>(null)
    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val pending = MutableStateFlow<PendingBrute?>(null)
    private val env: StateFlow<NetworkEnv?> = c.networkEnvMonitor.env.stateIn(viewModelScope, SharingStarted.Eagerly, c.networkEnvMonitor.current())
    private val networkId: Flow<Long?> = combine(c.currentNetwork, c.forgottenNetworkId) { n, gone -> n?.id?.takeIf { it != gone } }.distinctUntilChanged()

    private val starter = ManualStarter(
        committedBefore = { body -> c.undo.committedBefore { body() } },
        network = { e -> networkFor(e) },
        known = { id, e -> knownRanges(id, e) },
        hasBinder = { e -> c.networkEnvMonitor.binderFor(e) != null },
        target = { id, ip -> c.hosts.manualTarget(id, ip) },
        discard = { id -> c.hosts.discardManual(id) },
        runnerStart = { t, tool, options -> c.toolRunner.start(t, tool, options) })

    /** The current network row only when it is this env's network (same gateway and prefix), else resolved as a scan does (spec 4.3 step 1). */
    private suspend fun networkFor(e: NetworkEnv): Long? {
        val current = c.currentNetwork.value?.takeIf { NetworkSignature.identityKey(it) == NetworkSignature.identityKey(e) }
        val net = current ?: c.networks.ensure(e, GatewayMac.lookup(e.gateway))
        return net?.id?.takeIf { it != c.forgottenNetworkId.value }
    }
    /** Spec 4.5: read at the Run tap from the live settings, this network's manual ranges and its visible hosts. */
    private suspend fun knownRanges(networkId: Long, e: NetworkEnv): KnownRanges {
        val s = c.settings.state.value
        return KnownRanges.of(HomeRange.of(e.interfaceIp, e.prefixLength, s.maxPrefix, s.extEnabled).subnet, c.settings.manual.get(networkId),
            c.hosts.hostsForNetwork(networkId).first())
    }

    private val known: Flow<KnownTarget?> = combine(networkId, form.map { it.ipText.trim() }.distinctUntilChanged()) { id, ip -> id to ip }
        .mapLatest { (id, ip) -> lookupKnown(id, ip) }
    private suspend fun lookupKnown(id: Long?, ip: String): KnownTarget? {
        if (id == null || Ipv4.parse(ip) == null) return null
        val h = c.hosts.findOnce(id, ip)?.takeUnless { it.manualOnly } ?: return null
        return KnownTarget(h, c.ports.servicesFor(h.id))
    }
    private val history: Flow<List<NetworkRun>> = networkId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else c.toolRepo.recentForNetwork(id, ManualPresenter.RECENT_RUNS)
    }
    private val resultsSource: Flow<ResultsSource?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else c.toolRepo.run(id).flatMapLatest { r ->
            if (r == null) flowOf(null) else combine(c.toolRepo.findings(id), c.toolRepo.runsForHost(r.hostId), c.hosts.host(r.hostId)) { f, runs, h ->
                h?.let { ResultsSource(r, runs.filter { it.toolId == r.toolId }, it.ip, ToolResultsModel.rows(f)) }
            }
        }
    }

    val state: StateFlow<ManualUiState> = combine(listOf(form, env, known, history, c.toolRunner.active, resultsSource, pending, message,
        c.appMessages.current, startedHostId, c.settings.state)) { arr ->
        @Suppress("UNCHECKED_CAST") val historyRuns = arr[3] as List<NetworkRun>
        @Suppress("UNCHECKED_CAST") val activeMap = arr[4] as Map<Long, ToolRunner.Active>
        val e = arr[1] as NetworkEnv?; val s = arr[10] as SettingsState
        val home = e?.let { HomeRange.of(it.interfaceIp, it.prefixLength, s.maxPrefix, s.extEnabled).subnet }
        val now = System.currentTimeMillis()
        val results = (arr[5] as ResultsSource?)?.let { src -> ToolResultsModel.ofRows(src.run, src.sameTool, src.ip, src.rows, activeMap[src.run.hostId], now) }
        ManualPresenter.present(arr[0] as ManualForm, home, e != null, arr[2] as KnownTarget?, historyRuns, (arr[9] as Long?)?.let { activeMap[it] },
            results, (arr[6] as PendingBrute?)?.sheet, arr[7] as String?, arr[8] as AppMessage?, now)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ManualUiState())

    init {
        // The results sheet closes when its run disappears (pruned by a newer run, or its network forgotten), as on the Device screen.
        viewModelScope.launch {
            resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
                .collect { if (resultsRunId.value != null) resultsRunId.value = null }
        }
    }

    fun setIp(text: String) { form.update { it.copy(ipText = text, httpsChoice = null) } }
    fun setPort(text: String) { form.update { it.copy(portText = text.filter(Char::isDigit).take(MAX_PORT_CHARS), httpsChoice = null) } }
    fun setTool(tool: ToolId) { form.update { it.copy(tool = tool, httpsChoice = null) } }
    fun setHttps(on: Boolean) { form.update { it.copy(httpsChoice = on) } }

    /** Run (spec 4.3): validate; brute-force opens its sheet and creates nothing until the sheet's Run; the other tools start at once. */
    fun runTool() {
        val f = form.updateAndGet { it.copy(attempted = true) }
        val e = env.value ?: return
        val s = c.settings.state.value
        val checked = ManualTarget.check(f.ipText, f.portText, f.tool, HomeRange.of(e.interfaceIp, e.prefixLength, s.maxPrefix, s.extEnabled).subnet, attempted = true)
        val ip = checked.ip ?: return
        val port = checked.port ?: return
        val request = ManualRequest(e, ip, port, f.tool, ManualTarget.tls(f.tool, state.value.https))
        when (ManualFlow.firstStep(f.tool)) {
            ManualStep.OPEN_SHEET -> openSheet(request)
            ManualStep.START -> c.appScope.launch(Dispatchers.Default) { begin(request) }
        }
    }
    private fun openSheet(r: ManualRequest) {
        // Off the main thread: the first wordlistStore access reads the built-in lists (3B).
        c.appScope.launch(Dispatchers.Default) {
            val lists = c.wordlistStore.lists.value
            val saved = c.dirBruteOptions.load()
            val initial = if (lists.any { it.id == saved.wordlistId }) saved else saved.copy(wordlistId = DirBruteOptions.DEFAULT_WORDLIST)
            if (initial != saved) message.value = ToolCopy.WORDLIST_FALLBACK
            val reason = ToolCopy.BUSY_TOO_MANY.takeIf { c.toolRunner.active.value.size >= ToolRunner.MAX_ACTIVE_RUNS }
            pending.value = PendingBrute(r, DirBruteSheetState(r.port, ToolExport.target(ToolId.DIR_BRUTEFORCE, r.ip.hostAddress, r.port, r.tls), lists, initial, reason))
        }
    }
    /** The sheet's Run: save the options, close the sheet, start (spec 4.3 step 3). */
    fun startDirBrute(o: DirBruteOptions) {
        val p = pending.value ?: return
        pending.value = null
        val options = o.coerced()
        c.appScope.launch(Dispatchers.Default) {
            c.dirBruteOptions.save(options)
            val meta = c.wordlistStore.lists.value.firstOrNull { it.id == options.wordlistId }
            begin(p.request.copy(options = ToolOptions.DirBrute(options, meta?.name ?: "", meta?.entries ?: 0)))
        }
    }
    /** Cancel creates nothing: no row was made for the open sheet (spec 4.3 step 3). */
    fun closeDirBrute() { pending.value = null }

    private suspend fun begin(r: ManualRequest) {
        val result = starter.startManual(r)
        ManualMessages.of(result, c.hostBusy.busy.value)?.let { message.value = it }
        if (result.start is ToolRunner.Start.Started) startedHostId.value = result.hostId
    }
    fun stopTool() { startedHostId.value?.let { c.toolRunner.stop(it) } }

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
    /** Share and export are ToolExport unchanged (spec 4.4). */
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message.value = ToolCopy.COPIED
    }
    fun openFinding(row: FindingRow) {
        val url = row.url ?: return
        if (!c.actionRunner.run(DeviceAction.OpenWith(url))) copyFinding(row)
    }
    fun dismissMessage() { message.value = null }
    fun dismissAppMessage(id: Long) { c.appMessages.dismiss(id) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        /** Five digits is the widest valid port; the field filters digits and validation checks the range. */
        const val MAX_PORT_CHARS = 5
    }
}
```

- [ ] **Step 7: Implement the screen, the Home item and the route**

`app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsScreen.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.manual

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.BackToHome
import com.northwalessolutions.netprobe.ui.home.HomeCopy
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.settings.SwitchRow
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.ui.tools.DirBruteSheet
import com.northwalessolutions.netprobe.ui.tools.FindingRow
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import com.northwalessolutions.netprobe.ui.tools.ToolResultsSheet

/** Every action the screen fires; ManualToolsScreen wires them to ManualToolsViewModel, the Compose tests record them. */
data class ManualActions(
    val onBack: () -> Unit = {}, val onIp: (String) -> Unit = {}, val onPort: (String) -> Unit = {}, val onTool: (ToolId) -> Unit = {},
    val onHttps: (Boolean) -> Unit = {}, val onRun: () -> Unit = {}, val onStop: () -> Unit = {}, val onOpenRun: (Long) -> Unit = {},
    val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {}, val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {},
    val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {}, val onStartDirBrute: (DirBruteOptions) -> Unit = {},
    val onCloseDirBrute: () -> Unit = {}, val onManageWordlists: () -> Unit = {}, val onDismissMessage: () -> Unit = {}, val onDismissApp: (Long) -> Unit = {},
)

private const val IP_WEIGHT = 0.65f
private const val PORT_WEIGHT = 0.35f
private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val FIELD_GAP = 8.dp
private val LINE_VERTICAL = 6.dp
private val ROW_VERTICAL = 8.dp
private val PROGRESS_START = 12.dp

@Composable
fun ManualToolsScreen(container: AppContainer, onBack: () -> Unit, onOpenWordlists: () -> Unit = {}) {
    val vm: ManualToolsViewModel = viewModel(factory = viewModelFactory { initializer { ManualToolsViewModel(container) } })
    val s by vm.state.collectAsStateWithLifecycle()
    ManualToolsContent(s, ManualActions(onBack = onBack, onIp = vm::setIp, onPort = vm::setPort, onTool = vm::setTool, onHttps = vm::setHttps,
        onRun = vm::runTool, onStop = vm::stopTool, onOpenRun = vm::openRun, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun,
        onCloseResults = vm::closeResults, onShare = vm::shareRun, onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding,
        onStartDirBrute = vm::startDirBrute, onCloseDirBrute = vm::closeDirBrute, onManageWordlists = onOpenWordlists,
        onDismissMessage = vm::dismissMessage, onDismissApp = vm::dismissAppMessage))
}

/**
 * "Run a tool" (3C spec 4.1). Stateless. PINNED: the fields, the tool row and the Run / Stop pill sit in the topBar and never scroll; the
 * history list below is the only scrolling part.
 */
@Composable
fun ManualToolsContent(state: ManualUiState, actions: ManualActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.onDismissMessage() } }
    LaunchedEffect(state.appMessage?.id) { state.appMessage?.let { m -> snackbar.showSnackbar(m.text); actions.onDismissApp(m.id) } }
    val editable = state.lan && !state.running
    Scaffold(containerColor = NetProbeColors.background, contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 10.dp)) {
                BackToHome(actions.onBack)
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                    Text(ManualCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.semantics { heading() })
                    Row(Modifier.fillMaxWidth().padding(top = ROW_VERTICAL), horizontalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
                        OutlinedTextField(state.form.ipText, actions.onIp, Modifier.weight(IP_WEIGHT).testTag("manualIp"), enabled = editable,
                            label = { Text(ManualCopy.IP) }, placeholder = { Text(ManualCopy.IP_PLACEHOLDER) }, singleLine = true,
                            isError = state.ipError != null, supportingText = state.ipError?.let { e -> { Text(e) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = npFieldColors(), shape = FIELD_SHAPE)
                        OutlinedTextField(state.form.portText, actions.onPort, Modifier.weight(PORT_WEIGHT).testTag("manualPort"),
                            enabled = editable && state.portEnabled, label = { Text(ManualCopy.PORT) }, placeholder = { Text(ManualCopy.PORT_PLACEHOLDER) },
                            singleLine = true, isError = state.portError != null, supportingText = state.portError?.let { e -> { Text(e) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = npFieldColors(), shape = FIELD_SHAPE)
                    }
                    ToolPicker(state.form.tool, editable, actions.onTool)
                    if (state.showHttps) SwitchRow(ManualCopy.USE_HTTPS, state.https, enabled = editable) { actions.onHttps(it) }
                    state.fixedLine?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = LINE_VERTICAL)) }
                    state.infoLine?.let {
                        Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(vertical = LINE_VERTICAL).testTag("manualInfo"))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = ROW_VERTICAL), verticalAlignment = Alignment.CenterVertically) {
                        ActionPill(if (state.running) HomeCopy.STOP else ToolCopy.RUN, enabled = state.lan || state.running,
                            onClick = if (state.running) actions.onStop else actions.onRun, tag = "manualRun",
                            modifier = if (state.running) Modifier.semantics { contentDescription = ToolCopy.STOP_DESCRIPTION } else Modifier)
                        state.progress?.let {
                            Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(start = PROGRESS_START).testTag("manualProgress"))
                        }
                    }
                }
            }
        }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("manualHistory")) {
            item(key = "historyHeader") {
                Text(ManualCopy.HISTORY, style = NetProbeText.groupHeader, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).semantics { heading() })
            }
            if (state.history.isEmpty()) item(key = "historyEmpty") {
                Text(ManualCopy.HISTORY_EMPTY, style = NetProbeText.secondary, color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = Dimens.gutter))
            }
            items(state.history, key = { it.runId }) { r -> HistoryRow(r) { actions.onOpenRun(r.runId) } }
        }
    }
    state.dirBrute?.let {
        DirBruteSheet(it, onRun = actions.onStartDirBrute, onManageWordlists = { actions.onCloseDirBrute(); actions.onManageWordlists() }, onDismiss = actions.onCloseDirBrute)
    }
    state.results?.let {
        ToolResultsSheet(it, actions.onOlderRun, actions.onNewerRun, actions.onCopyFinding, actions.onOpenFinding, actions.onShare, actions.onCloseResults)
    }
}

/** "Tool: HTTP fingerprint" with a chevron; the menu lists the five tools in ToolId order (spec 4.1 item 4). */
@Composable
private fun ToolPicker(tool: ToolId, enabled: Boolean, onPick: (ToolId) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.Button) { open = true }.testTag("manualTool"), verticalAlignment = Alignment.CenterVertically) {
            Text(ManualCopy.toolRow(ToolCopy.toolName(tool)), style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            ToolId.entries.forEach { t ->
                DropdownMenuItem(text = { Text(ToolCopy.toolName(t)) }, onClick = { open = false; onPick(t) }, modifier = Modifier.testTag("manualTool:${t.key}"))
            }
        }
    }
}

/** One history row, 48 dp minimum, its description naming the tool and target (spec 4.1). */
@Composable
private fun HistoryRow(r: ManualHistoryRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = r.description }
        .padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("historyRow:${r.runId}")) {
        Text(r.title, style = NetProbeText.secondary, color = NetProbeColors.text)
        Text(r.line, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt`: replace
```kotlin
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}) {
```
with
```kotlin
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}, onRunTool: () -> Unit = {}) {
```
and replace
```kotlin
            DropdownMenuItem(text = { Text(HomeCopy.FORGET_NETWORK) }, enabled = forgetEnabled, onClick = { open = false; onForget() })
```
with
```kotlin
            // 3C spec 4.1: always enabled; the screen itself says when there is no LAN.
            DropdownMenuItem(text = { Text(HomeCopy.MENU_RUN_TOOL) }, onClick = { open = false; onRunTool() }, modifier = Modifier.testTag("menuRunTool"))
            DropdownMenuItem(text = { Text(HomeCopy.FORGET_NETWORK) }, enabled = forgetEnabled, onClick = { open = false; onForget() })
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`:
1. Replace
```kotlin
    val onForget: () -> Unit = {}, val onUndo: () -> Unit = {}, val onDismissUndo: () -> Unit = {}, val onDismissApp: () -> Unit = {},
)
```
with
```kotlin
    val onForget: () -> Unit = {}, val onUndo: () -> Unit = {}, val onDismissUndo: () -> Unit = {}, val onDismissApp: () -> Unit = {},
    val onRunTool: () -> Unit = {},
)
```
2. Replace
```kotlin
                    forgetEnabled = state.networkId != null && !state.scanning && !state.quickScanning && !radarBusy && !state.toolRunningOnNetwork, onForget = actions.onForget)
```
with
```kotlin
                    forgetEnabled = state.networkId != null && !state.scanning && !state.quickScanning && !radarBusy && !state.toolRunningOnNetwork, onForget = actions.onForget,
                    onRunTool = actions.onRunTool)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt`:
1. Replace `fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit) {` with `fun HomeScreen(container: AppContainer, onOpenHost: (Long) -> Unit, onOpenSettings: () -> Unit, onOpenTools: () -> Unit = {}) {`.
2. Replace `onForget = vm::forgetNetwork, onUndo = vm::undo, onDismissUndo = vm::dismissUndo, onDismissApp = vm::dismissAppMessage),` with `onForget = vm::forgetNetwork, onUndo = vm::undo, onDismissUndo = vm::dismissUndo, onDismissApp = vm::dismissAppMessage, onRunTool = onOpenTools),`.

Before writing it (3C-1 plan review G1): `git diff --quiet 82084d3 -- app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt || echo "STOP: app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt changed since 82084d3"`; if it prints STOP, stop and ask the controller.

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` (whole file):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.detail.DetailScreen
import com.northwalessolutions.netprobe.ui.home.HomeScreen
import com.northwalessolutions.netprobe.ui.manual.ManualToolsScreen
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion
import com.northwalessolutions.netprobe.ui.wordlists.WordlistsScreen

@Composable
fun NetProbeNav(container: AppContainer) {
    val extended by container.extendedRunActive.collectAsStateWithLifecycle()
    val toolRun by container.toolRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun)
    val nav = rememberNavController()
    NetProbeNavHost(nav,
        home = { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") },
            onOpenTools = { nav.navigate("tools") { launchSingleTop = true } }) },
        detail = { id -> DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) },
        settings = { SettingsScreen(container, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) },
        wordlists = { WordlistsScreen(container, onBack = { nav.popBackStack() }) },
        tools = { ManualToolsScreen(container, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") { launchSingleTop = true } }) })
}

/**
 * The routes with their transitions (spec 1.9): the entering screen slides in from a quarter of the width and fades in, the leaving
 * one slides a tenth and fades out, 240 ms FastOutSlowIn; none under reduced motion. Stateless over its destinations, so the
 * Compose test passes fakes and no test runs a real discovery. "tools" is the 3C manual tools screen (spec 4.1).
 */
@Composable
fun NetProbeNavHost(nav: NavHostController, home: @Composable () -> Unit, detail: @Composable (Long) -> Unit, settings: @Composable () -> Unit,
                   wordlists: @Composable () -> Unit = {}, tools: @Composable () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val slide = tween<IntOffset>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(Motion.NAV_MS, easing = FastOutSlowInEasing)
    NavHost(nav, startDestination = "home",
        enterTransition = { if (reduced) EnterTransition.None else slideInHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeIn(fade) },
        exitTransition = { if (reduced) ExitTransition.None else slideOutHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeOut(fade) },
        popEnterTransition = { if (reduced) EnterTransition.None else slideInHorizontally(slide) { -(it * Motion.NAV_EXIT_OFFSET).toInt() } + fadeIn(fade) },
        popExitTransition = { if (reduced) ExitTransition.None else slideOutHorizontally(slide) { (it * Motion.NAV_ENTER_OFFSET).toInt() } + fadeOut(fade) },
    ) {
        composable("home") { home() }
        composable("host/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { detail(it.arguments!!.getLong("id")) }
        composable("settings") { settings() }
        composable("wordlists") { wordlists() }
        composable("tools") { tools() }
    }
}
```

- [ ] **Step 8: Compile and run the JVM suite**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
grep -n "verticalScroll\|LazyColumn" app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsScreen.kt
```
Expected: `BUILD SUCCESSFUL` twice; `NavTransitionTest`, `HomeContentTest` and `HomeScreenTest` compile unchanged (every new parameter is defaulted); the grep shows exactly one `LazyColumn` (the history) and no `verticalScroll`.

- [ ] **Step 9: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenter.kt app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsScreen.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/manual/ManualPresenterTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/manual/ManualToolsContentTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeMenuTest.kt
git status --short
git commit -m "Phase 3C-2: Run a tool screen, Home overflow item and route"
```

---

### Task 8: The listener's parsers: DnsWire shared with DeviceInfoTxt, MdnsPacket for unsolicited answers, and SsdpParser.parseNotify, with hostile-packet bounds

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DnsWire.kt`, `engine/discovery/MdnsPacket.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DeviceInfoTxt.kt` (its three wire helpers delegate to `DnsWire`, behaviour unchanged), `engine/discovery/Ssdp.kt` (`SsdpParser.parseNotify`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsTestPackets.kt` (authored packets), `MdnsPacketTest.kt`, `DnsWireTest.kt`, `SsdpNotifyTest.kt`; 3A's `DeviceInfoTxtTest` and the existing `SsdpTest` must pass unchanged

**Interfaces:**
- Consumes: `MdnsRecord`, `MdnsType.normalise`, `SsdpResponse`, `SsdpParser.parse` (MVP).
- Produces:
```kotlin
internal object DnsWire { const val NO_LABEL_LIMIT: Int; fun u16(b: ByteArray, at: Int): Int
    fun readName(b: ByteArray, start: Int, maxLabels: Int = NO_LABEL_LIMIT): Pair<String, Int>?; fun parseTxtRdata(b: ByteArray, start: Int, end: Int): Map<String, String> }
const val HEARD_MAX_PER_PACKET = 16
object MdnsPacket { const val MAX_NAME_LABELS = 128; fun parse(b: ByteArray): List<MdnsRecord> }
object SsdpParser { fun parseNotify(from: Inet4Address, text: String): SsdpResponse? }   // st carries the NT header
```
Rules (spec 3.3, G4, controller ruling): responses only (QR set); a record is emitted only for a PTR naming an instance whose SRV target has an A record in the same packet, so an A record alone (a name-only sighting) gives nothing; the `_services._dns-sd._udp` meta list is ignored; at most `HEARD_MAX_PER_PACKET` records per packet; a name longer than `MAX_NAME_LABELS` labels, a pointer loop (DnsWire's 8-jump limit) or any out-of-bounds read makes the whole packet yield nothing, never a partial list and never an exception. SSDP: `NOTIFY * HTTP/1.1` with `NTS: ssdp:alive` only; byebye, M-SEARCH requests and search responses are null.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsTestPackets.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Authored mDNS packets for the listener tests (3C spec 6: nothing captured, nothing downloaded). */
object MdnsTestPackets {
    const val A = 1; const val PTR = 12; const val TXT = 16; const val AAAA = 28; const val SRV = 33
    const val HEADER = 12
    /** Type, class, TTL and RDLENGTH after an owner name. */
    const val RR_FIXED = 10
    private const val CLASS_IN_FLUSH = 0x8001
    private const val CLASS_IN = 1
    private const val TTL = 120
    private const val FLAGS_RESPONSE = 0x8400
    private const val POINTER_HIGH = 0xC0

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun labels(out: ByteArrayOutputStream, dotted: String) {
        dotted.split('.').filter { it.isNotEmpty() }.forEach { l -> val b = l.toByteArray(); out.write(b.size); out.write(b) }
    }
    fun name(dotted: String): ByteArray = ByteArrayOutputStream().apply { labels(this, dotted); write(0) }.toByteArray()
    fun pointer(at: Int) = byteArrayOf((POINTER_HIGH or (at shr 8)).toByte(), (at and 0xFF).toByte())
    /** [dotted] labels, then a compression pointer to [at] instead of the terminating zero. */
    fun nameThenPointer(dotted: String, at: Int): ByteArray = ByteArrayOutputStream().apply { labels(this, dotted); write(pointer(at)) }.toByteArray()
    fun srv(port: Int, target: ByteArray) = u16(0) + u16(0) + u16(port) + target
    fun txt(vararg pairs: String): ByteArray = ByteArrayOutputStream().apply { pairs.forEach { val b = it.toByteArray(); write(b.size); write(b) } }.toByteArray()
    fun a(ip: String): ByteArray = InetAddress.getByName(ip).address

    class Builder(private val response: Boolean = true) {
        private val body = ByteArrayOutputStream()
        private var questions = 0
        private var answers = 0
        /** Absolute offset the next written byte will have, for compression pointers. */
        val offset: Int get() = HEADER + body.size()
        fun question(dotted: String, type: Int) = apply { body.write(name(dotted)); body.write(u16(type)); body.write(u16(CLASS_IN)); questions++ }
        fun record(owner: ByteArray, type: Int, rdata: ByteArray) = apply {
            body.write(owner); body.write(u16(type)); body.write(u16(CLASS_IN_FLUSH)); body.write(u32(TTL)); body.write(u16(rdata.size)); body.write(rdata); answers++
        }
        fun record(owner: String, type: Int, rdata: ByteArray) = record(name(owner), type, rdata)
        fun build(): ByteArray = u16(0) + u16(if (response) FLAGS_RESPONSE else 0) + u16(questions) + u16(answers) + u16(0) + u16(0) + body.toByteArray()
    }

    /** PTR, SRV, TXT and A (and AAAA) for one instance: the shape a responder announces. */
    fun announce(b: Builder, instance: String, type: String, host: String, ip: String, port: Int, txtPairs: Array<String> = emptyArray(), v6: String? = null): Builder {
        val full = "$instance.$type.local"
        b.record("$type.local", PTR, name(full))
        b.record(full, SRV, srv(port, name("$host.local")))
        b.record(full, TXT, txt(*txtPairs))
        b.record("$host.local", A, a(ip))
        v6?.let { b.record("$host.local", AAAA, InetAddress.getByName(it).address) }
        return b
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsPacketTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.A
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.Builder
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.PTR
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.RR_FIXED
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.SRV
import com.northwalessolutions.netprobe.engine.discovery.MdnsTestPackets.announce
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** 3C spec 8.1 item 16 (G4, Review Focus 4): authored packets, hostile ones included. */
class MdnsPacketTest {
    @Test fun `an announcement gives one record with its type, port, txt, address and link-local v6`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+", "path=/"), v6 = "fe80::211:32ff:fe12:3456").build()
        val r = MdnsPacket.parse(p).single()
        assertEquals("10.0.3.59", r.ip.hostAddress); assertEquals("CHNAS", r.instanceName); assertEquals("_http._tcp", r.type); assertEquals(5000, r.port)
        assertEquals(mapOf("model" to "DS220+", "path" to "/"), r.txt)
        assertEquals(listOf(InetAddress.getByName("fe80::211:32ff:fe12:3456").hostAddress), r.ipv6)
    }
    @Test fun `a global v6 address is not kept as link-local`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, v6 = "2001:db8::5").build()
        assertTrue(MdnsPacket.parse(p).single().ipv6.isEmpty())
    }
    @Test fun `a query gives nothing`() {
        val q = announce(Builder(response = false).question("_http._tcp.local", PTR), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000).build()
        assertTrue(MdnsPacket.parse(q).isEmpty())
    }
    @Test fun `an A record with no service gives nothing, so a name-only sighting is dropped`() {
        assertTrue(MdnsPacket.parse(Builder().record("printer.local", A, MdnsTestPackets.a("10.0.3.70")).build()).isEmpty())
    }
    @Test fun `a PTR whose SRV target has no A record gives nothing`() {
        val b = Builder().record("_ipp._tcp.local", PTR, MdnsTestPackets.name("Office._ipp._tcp.local"))
            .record("Office._ipp._tcp.local", SRV, MdnsTestPackets.srv(631, MdnsTestPackets.name("office.local")))
        assertTrue(MdnsPacket.parse(b.build()).isEmpty())
    }
    @Test fun `compression pointers are followed`() {
        val b = Builder()
        val typeAt = b.offset
        val typeName = MdnsTestPackets.name("_ipp._tcp.local")
        b.record(typeName, PTR, MdnsTestPackets.nameThenPointer("Office Printer", typeAt))
        val instanceAt = typeAt + typeName.size + RR_FIXED
        val localAt = typeAt + "_ipp".length + 1 + "_tcp".length + 1
        b.record(MdnsTestPackets.pointer(instanceAt), SRV, MdnsTestPackets.srv(631, MdnsTestPackets.nameThenPointer("office", localAt)))
        b.record(MdnsTestPackets.nameThenPointer("office", localAt), A, MdnsTestPackets.a("10.0.3.80"))
        val r = MdnsPacket.parse(b.build()).single()
        assertEquals("Office Printer", r.instanceName); assertEquals("_ipp._tcp", r.type); assertEquals(631, r.port); assertEquals("10.0.3.80", r.ip.hostAddress)
    }
    @Test fun `truncated and looping packets return empty without throwing`() {
        val p = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+")).build()
        for (cut in 0 until p.size) assertTrue("cut at $cut", MdnsPacket.parse(p.copyOf(cut)).isEmpty())
        val loop = Builder().record(MdnsTestPackets.pointer(MdnsTestPackets.HEADER), A, MdnsTestPackets.a("10.0.3.9")).build()   // the owner points at itself
        assertTrue(MdnsPacket.parse(loop).isEmpty())
    }
    @Test fun `a packet with 40 linked instances emits 16`() {
        val b = Builder()
        for (i in 1..40) announce(b, "cam$i", "_rtsp._tcp", "cam$i", "10.0.3.${100 + i}", 554)
        val records = MdnsPacket.parse(b.build())
        assertEquals(HEARD_MAX_PER_PACKET, records.size)
        assertEquals("packet order", (1..16).map { "cam$it" }, records.map { it.instanceName })
    }
    @Test fun `the meta service list and a label flood are ignored`() {
        assertTrue(MdnsPacket.parse(Builder().record("_services._dns-sd._udp.local", PTR, MdnsTestPackets.name("_http._tcp.local")).build()).isEmpty())
        val flood = (1..MdnsPacket.MAX_NAME_LABELS + 1).joinToString(".") { "a" }
        assertTrue(MdnsPacket.parse(Builder().record(flood, A, MdnsTestPackets.a("10.0.3.9")).build()).isEmpty())
    }
    @Test fun `the same instance announced twice in one packet is emitted once`() {
        val b = announce(Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000)
        b.record("_http._tcp.local", PTR, MdnsTestPackets.name("CHNAS._http._tcp.local"))
        assertEquals(1, MdnsPacket.parse(b.build()).size)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DnsWireTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** DnsWire keeps DeviceInfoTxt's behaviour; the label limit applies only when a caller passes one (the listener). */
class DnsWireTest {
    @Test fun `a plain name reads with its end position`() {
        val b = MdnsTestPackets.name("chnas.local")
        assertEquals("chnas.local" to b.size, DnsWire.readName(b, 0))
    }
    @Test fun `with no limit a long name still reads, with a limit it is refused`() {
        val long = MdnsTestPackets.name((1..200).joinToString(".") { "a" })
        assertNotNull(DnsWire.readName(long, 0))
        assertNull(DnsWire.readName(long, 0, maxLabels = 128))
    }
    @Test fun `a pointer loop and a reserved label type are refused`() {
        assertNull(DnsWire.readName(MdnsTestPackets.pointer(0), 0))
        assertNull(DnsWire.readName(byteArrayOf(0x40, 0x00), 0))
    }
    @Test fun `txt rdata keeps the first value of a key and lower-cases keys`() {
        val t = MdnsTestPackets.txt("Model=DS220+", "model=other", "noequals")
        assertEquals(mapOf("model" to "DS220+"), DnsWire.parseTxtRdata(t, 0, t.size))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SsdpNotifyTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 17. */
class SsdpNotifyTest {
    private val from = Ipv4.parse("10.0.3.30")!!
    private fun notify(nts: String) = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n" +
        "LOCATION: http://10.0.3.30:1400/xml/device_description.xml\r\nNT: upnp:rootdevice\r\nNTS: $nts\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/80.1\r\nUSN: uuid:RINCON_000E58A0B1C201400::upnp:rootdevice\r\n\r\n"

    @Test fun `an alive notify gives location, server, nt and usn`() {
        val r = SsdpParser.parseNotify(from, notify("ssdp:alive"))!!
        assertEquals(from, r.from); assertEquals("http://10.0.3.30:1400/xml/device_description.xml", r.location)
        assertEquals("Linux UPnP/1.0 Sonos/80.1", r.server); assertEquals("upnp:rootdevice", r.st)
        assertEquals("uuid:RINCON_000E58A0B1C201400::upnp:rootdevice", r.usn)
    }
    @Test fun `byebye is null`() { assertNull(SsdpParser.parseNotify(from, notify("ssdp:byebye"))) }
    @Test fun `an M-SEARCH request is null`() {
        assertNull(SsdpParser.parseNotify(from, "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"))
    }
    @Test fun `a search response is null here and still parsed by parse`() {
        val response = "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nUSN: uuid:x\r\nLOCATION: http://10.0.3.30:1400/d.xml\r\n\r\n"
        assertNull(SsdpParser.parseNotify(from, response))
        assertNotNull(SsdpParser.parse(from, response))
    }
    @Test fun `bare newlines, lower case and a missing NTS`() {
        assertNotNull(SsdpParser.parseNotify(from, "notify * http/1.1\nnts: SSDP:ALIVE\nnt: x\n"))
        assertNull(SsdpParser.parseNotify(from, "NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n"))
        assertNull(SsdpParser.parseNotify(from, ""))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.MdnsPacketTest" --tests "com.northwalessolutions.netprobe.engine.discovery.DnsWireTest" --tests "com.northwalessolutions.netprobe.engine.discovery.SsdpNotifyTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'MdnsPacket'`, `'DnsWire'`, `'HEARD_MAX_PER_PACKET'`, `'parseNotify'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DnsWire.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

/**
 * DNS wire helpers shared by DeviceInfoTxt (moved from it, behaviour unchanged) and the listener's MdnsPacket (3C spec 3.3). Every read is
 * bounds-checked and returns null rather than throw; compressed names follow at most MAX_POINTER_JUMPS pointers.
 */
internal object DnsWire {
    private const val POINTER_MASK = 0xC0
    private const val OFFSET_MASK = 0x3F
    private const val MAX_POINTER_JUMPS = 8
    private const val BYTE_MASK = 0xff
    private const val BITS_PER_BYTE = 8
    /** DeviceInfoTxt's behaviour: no limit on the number of labels. */
    const val NO_LABEL_LIMIT = Int.MAX_VALUE

    fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b[at + 1].toInt() and BYTE_MASK)

    /** Reads a possibly compressed name. Returns the dotted name and the position after it in the original stream, or null. */
    fun readName(b: ByteArray, start: Int, maxLabels: Int = NO_LABEL_LIMIT): Pair<String, Int>? {
        val parts = ArrayList<String>(); var pos = start; var end = -1; var jumps = 0
        while (true) {
            if (pos < 0 || pos >= b.size) return null
            val len = b[pos].toInt() and BYTE_MASK
            when {
                len == 0 -> { if (end < 0) end = pos + 1; return parts.joinToString(".") to end }
                (len and POINTER_MASK) == POINTER_MASK -> {
                    if (pos + 1 >= b.size || ++jumps > MAX_POINTER_JUMPS) return null
                    if (end < 0) end = pos + 2
                    pos = ((len and OFFSET_MASK) shl BITS_PER_BYTE) or (b[pos + 1].toInt() and BYTE_MASK)
                }
                (len and POINTER_MASK) != 0 -> return null
                else -> {
                    if (pos + 1 + len > b.size || parts.size >= maxLabels) return null
                    parts += String(b, pos + 1, len, Charsets.UTF_8); pos += 1 + len
                }
            }
        }
    }

    /** TXT rdata as key to value: keys lower-cased, the first value of a key kept, entries without '=' skipped. */
    fun parseTxtRdata(b: ByteArray, start: Int, end: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>(); var p = start
        while (p < end) {
            val l = b[p].toInt() and BYTE_MASK
            if (l == 0 || p + 1 + l > end) break
            val s = String(b, p + 1, l, Charsets.UTF_8)
            val eq = s.indexOf('=')
            if (eq > 0) out.putIfAbsent(s.substring(0, eq).lowercase(), s.substring(eq + 1))
            p += 1 + l
        }
        return out
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/MdnsPacket.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** At most this many records are emitted from one heard packet; the rest are dropped (3C spec 3.3, G4). */
const val HEARD_MAX_PER_PACKET = 16

/**
 * Unsolicited mDNS responses heard by the listener (3C spec 3.3). The bytes come from any device on the LAN, so every read is bounds-checked,
 * names stop at MAX_NAME_LABELS labels and DnsWire's jump limit, and a malformed packet yields nothing rather than a partial list. A record
 * is emitted only for a PTR whose instance has an SRV whose target has an A record in the same packet (controller ruling: a name-only
 * sighting is dropped).
 */
object MdnsPacket {
    /** DNS names have at most 127 labels; anything longer is hostile. */
    const val MAX_NAME_LABELS = 128
    private const val HEADER = 12
    private const val RR_FIXED = 10
    private const val QUESTION_FIXED = 4
    private const val FLAGS_AT = 2; private const val QD_AT = 4; private const val AN_AT = 6; private const val NS_AT = 8; private const val AR_AT = 10
    private const val TYPE_AT = 0; private const val RDLEN_AT = 8
    private const val QR_BIT = 0x8000
    private const val TYPE_A = 1; private const val TYPE_PTR = 12; private const val TYPE_TXT = 16; private const val TYPE_AAAA = 28; private const val TYPE_SRV = 33
    private const val A_LEN = 4; private const val AAAA_LEN = 16
    private const val SRV_FIXED = 6; private const val SRV_PORT_AT = 4
    private const val LOCAL_SUFFIX = ".local"
    private const val META_TYPE = "_services._dns-sd._udp"

    private class Srv(val target: String, val port: Int)

    fun parse(b: ByteArray): List<MdnsRecord> {
        if (b.size < HEADER) return emptyList()
        if ((DnsWire.u16(b, FLAGS_AT) and QR_BIT) == 0) return emptyList()   // a query, not an answer
        val questions = DnsWire.u16(b, QD_AT)
        val records = DnsWire.u16(b, AN_AT) + DnsWire.u16(b, NS_AT) + DnsWire.u16(b, AR_AT)
        var pos = HEADER
        repeat(questions) {
            val (_, end) = DnsWire.readName(b, pos, MAX_NAME_LABELS) ?: return emptyList()
            pos = end + QUESTION_FIXED
            if (pos > b.size) return emptyList()
        }
        val ptrs = ArrayList<Pair<String, String>>()            // (service type owner, lower case; instance full name), in packet order
        val srv = HashMap<String, Srv>()
        val txt = HashMap<String, Map<String, String>>()
        val v4 = HashMap<String, Inet4Address>()
        val v6 = HashMap<String, MutableList<String>>()
        repeat(records) {
            val (owner, nameEnd) = DnsWire.readName(b, pos, MAX_NAME_LABELS) ?: return emptyList()
            if (nameEnd + RR_FIXED > b.size) return emptyList()
            val type = DnsWire.u16(b, nameEnd + TYPE_AT)
            val rdLen = DnsWire.u16(b, nameEnd + RDLEN_AT)
            val rd = nameEnd + RR_FIXED
            if (rd + rdLen > b.size) return emptyList()
            val key = owner.lowercase()
            when (type) {
                TYPE_PTR -> { val target = DnsWire.readName(b, rd, MAX_NAME_LABELS) ?: return emptyList(); ptrs += key to target.first }
                TYPE_SRV -> if (rdLen > SRV_FIXED) {
                    val target = DnsWire.readName(b, rd + SRV_FIXED, MAX_NAME_LABELS) ?: return emptyList()
                    srv.putIfAbsent(key, Srv(target.first.lowercase(), DnsWire.u16(b, rd + SRV_PORT_AT)))
                }
                TYPE_TXT -> txt.putIfAbsent(key, DnsWire.parseTxtRdata(b, rd, rd + rdLen))
                TYPE_A -> if (rdLen == A_LEN) v4.putIfAbsent(key, InetAddress.getByAddress(b.copyOfRange(rd, rd + A_LEN)) as Inet4Address)
                TYPE_AAAA -> if (rdLen == AAAA_LEN) {
                    val addr = InetAddress.getByAddress(b.copyOfRange(rd, rd + AAAA_LEN))
                    if (addr is Inet6Address && addr.isLinkLocalAddress) v6.getOrPut(key) { ArrayList() } += addr.hostAddress.substringBefore('%')
                }
            }
            pos = rd + rdLen
        }
        return linked(ptrs, srv, txt, v4, v6)
    }

    private fun linked(ptrs: List<Pair<String, String>>, srv: Map<String, Srv>, txt: Map<String, Map<String, String>>,
                       v4: Map<String, Inet4Address>, v6: Map<String, List<String>>): List<MdnsRecord> {
        val out = ArrayList<MdnsRecord>()
        val seen = HashSet<String>()
        for ((typeOwner, instanceFull) in ptrs) {
            if (out.size >= HEARD_MAX_PER_PACKET) break
            val typeName = typeOwner.removeSuffix(LOCAL_SUFFIX)
            if (typeName == META_TYPE) continue
            val suffix = ".$typeOwner"
            val instKey = instanceFull.lowercase()
            if (!instKey.endsWith(suffix)) continue
            val instance = instanceFull.dropLast(suffix.length)
            if (instance.isEmpty() || !seen.add(instKey)) continue
            val s = srv[instKey] ?: continue
            val ip = v4[s.target] ?: continue
            out += MdnsRecord(ip, instance, MdnsType.normalise(typeName), s.port, txt[instKey].orEmpty(), v6[s.target].orEmpty())
        }
        return out
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DeviceInfoTxt.kt`:
1. Replace
```kotlin
    private const val TYPE_TXT = 16
    private const val POINTER_MASK = 0xC0
    private const val MAX_POINTER_JUMPS = 8
    private const val POLL_MS = 250
```
with
```kotlin
    private const val TYPE_TXT = 16
    private const val POLL_MS = 250
```
2. Replace everything from `    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)` through the closing brace of `parseTxtRdata` (the line before the KDoc `/** Sends the question to the mDNS group and unicast to [host]; ...`) with:
```kotlin
    // The wire helpers moved to DnsWire (3C spec 3.3), shared with the listener; behaviour unchanged (no label limit here).
    private fun u16(b: ByteArray, at: Int) = DnsWire.u16(b, at)
    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? = DnsWire.readName(b, start)
    private fun parseTxtRdata(b: ByteArray, start: Int, end: Int): Map<String, String> = DnsWire.parseTxtRdata(b, start, end)

```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Ssdp.kt`: replace
```kotlin
        return SsdpResponse(from, h["location"], h["server"], h["st"], h["usn"])
    }
}
```
with
```kotlin
        return SsdpResponse(from, h["location"], h["server"], h["st"], h["usn"])
    }

    /**
     * The listener's NOTIFY (3C spec 3.3): "NOTIFY * HTTP/1.1" with NTS ssdp:alive only; byebye, M-SEARCH requests and search responses are
     * null. [SsdpResponse.st] carries the NT header; the address is the packet source, never a header.
     */
    fun parseNotify(from: Inet4Address, text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().trim().equals(NOTIFY_LINE, ignoreCase = true)) return null
        val h = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        if (!h["nts"].orEmpty().equals(ALIVE, ignoreCase = true)) return null
        return SsdpResponse(from, h["location"], h["server"], h["nt"], h["usn"])
    }
    private const val NOTIFY_LINE = "NOTIFY * HTTP/1.1"
    private const val ALIVE = "ssdp:alive"
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures; `DeviceInfoTxtTest` and `SsdpTest` pass unchanged.

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DnsWire.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/MdnsPacket.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DeviceInfoTxt.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Ssdp.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsTestPackets.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/MdnsPacketTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/DnsWireTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SsdpNotifyTest.kt
git status --short
git commit -m "Phase 3C-2: mDNS answer and SSDP NOTIFY parsers with hostile-packet bounds"
```

---

### Task 9: "Listen between scans": the listenMulticast setting (the 15th reset value), ListenGate and the status rules, ListenCopy, and the Settings switch with its status line

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenGate.kt`, `ui/settings/ListenCopy.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt` (3C-1's file: targeted edits), `ui/settings/SettingsScreen.kt` (3C-1's file: targeted edits)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenGateTest.kt`; edits to `data/SettingsDefaultsTest.kt` (3C-1 Task 1) and an addition to `ui/home/CopyTableTest.kt`; compile only (run in Task 12): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsListenTest.kt`

**Interfaces:**
- Consumes: `SettingsState`, `SettingsDefaults`, `AppSettings` (3C-1 Task 1); `SettingsUi`, `SettingsActions`, `SettingsContent`, `SwitchRow` (3C-1 Task 2).
- Produces:
```kotlin
data class SettingsState(..., val listenMulticast: Boolean = false)          // key "listenMulticast"; covered by SettingsDefaults.reset (15 values)
data class ListenPlan(val mdns: Boolean, val ssdp: Boolean)
object ListenGate { fun plan(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean): ListenPlan? }
enum class ListenStatus { OFF, BOTH, MDNS_ONLY, SSDP_ONLY, NO_PORTS, NO_NETWORK, PAUSED_SCAN, BOTH_OFF }
object ListenStatusRules { fun opened(mdns: Boolean, ssdp: Boolean): ListenStatus
                           fun of(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean, session: ListenStatus?, scanActive: Boolean): ListenStatus }
object ListenCopy { const val LABEL; SUPPORT; BOTH; MDNS_ONLY; SSDP_ONLY; NO_PORTS; NO_NETWORK; PAUSED_SCAN; BOTH_OFF; fun status(s: ListenStatus): String? }
data class SettingsUi(..., val listenStatus: String? = null)                // last, defaulted; Task 11 wires it from ListenController.status
```
Settings "Discovery" gains, directly after "HTTP page titles", the switch "Listen between scans" with its subtitle; while it is on, the status line (tag `listenStatus`) follows in `data` / `textMuted`. "Reset to defaults" turns it off (D3: 15 covered values).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenGateTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 14 and the 3.1 status line. */
class ListenGateTest {
    @Test fun `null for each of switch off, background, no LAN and both protocols off`() {
        assertNull(ListenGate.plan(listen = false, mdns = true, ssdp = true, foreground = true, hasLan = true))
        assertNull(ListenGate.plan(listen = true, mdns = true, ssdp = true, foreground = false, hasLan = true))
        assertNull(ListenGate.plan(listen = true, mdns = true, ssdp = true, foreground = true, hasLan = false))
        assertNull(ListenGate.plan(listen = true, mdns = false, ssdp = false, foreground = true, hasLan = true))
    }
    @Test fun `the plan carries the mDNS and SSDP switches`() {
        assertEquals(ListenPlan(mdns = true, ssdp = true), ListenGate.plan(true, true, true, true, true))
        assertEquals(ListenPlan(mdns = true, ssdp = false), ListenGate.plan(true, true, false, true, true))
        assertEquals(ListenPlan(mdns = false, ssdp = true), ListenGate.plan(true, false, true, true, true))
    }
    @Test fun `what was opened names the status`() {
        assertEquals(ListenStatus.BOTH, ListenStatusRules.opened(true, true)); assertEquals(ListenStatus.MDNS_ONLY, ListenStatusRules.opened(true, false))
        assertEquals(ListenStatus.SSDP_ONLY, ListenStatusRules.opened(false, true)); assertEquals(ListenStatus.NO_PORTS, ListenStatusRules.opened(false, false))
    }
    @Test fun `the status line follows the switch, the protocols, the LAN, the session and a running scan`() {
        fun of(listen: Boolean = true, mdns: Boolean = true, ssdp: Boolean = true, fg: Boolean = true, lan: Boolean = true,
               session: ListenStatus? = ListenStatus.BOTH, scan: Boolean = false) = ListenStatusRules.of(listen, mdns, ssdp, fg, lan, session, scan)
        assertEquals(ListenStatus.OFF, of(listen = false)); assertEquals(ListenStatus.OFF, of(fg = false))
        assertEquals(ListenStatus.BOTH_OFF, of(mdns = false, ssdp = false, session = null))
        assertEquals(ListenStatus.NO_NETWORK, of(lan = false, session = null)); assertEquals(ListenStatus.NO_NETWORK, of(session = null))
        assertEquals(ListenStatus.BOTH, of()); assertEquals(ListenStatus.MDNS_ONLY, of(session = ListenStatus.MDNS_ONLY))
        assertEquals(ListenStatus.PAUSED_SCAN, of(scan = true)); assertEquals(ListenStatus.PAUSED_SCAN, of(session = ListenStatus.SSDP_ONLY, scan = true))
        assertEquals("nothing to pause when no port opened", ListenStatus.NO_PORTS, of(session = ListenStatus.NO_PORTS, scan = true))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/SettingsDefaultsTest.kt` (3C-1 Task 1's file), targeted edits:
1. Replace `extEnabled = true, extMax = 9, extGatewayProbe = true)` (the end of `tuned`) with `extEnabled = true, extMax = 9, extGatewayProbe = true, listenMulticast = true)`.
2. Replace
```kotlin
        "extGatewayProbe" to { s -> s.copy(extGatewayProbe = d.extGatewayProbe) })
```
with
```kotlin
        "extGatewayProbe" to { s -> s.copy(extGatewayProbe = d.extGatewayProbe) },
        "listenMulticast" to { s -> s.copy(listenMulticast = d.listenMulticast) })
```
3. Replace
```kotlin
            d.copy(scanTimeoutMs = 500), d.copy(extEnabled = true), d.copy(extMax = 5), d.copy(extGatewayProbe = true))
        assertEquals(14, oneEach.size)   // the 14 covered values of plan 3C-1 (spec D3)
```
with
```kotlin
            d.copy(scanTimeoutMs = 500), d.copy(extEnabled = true), d.copy(extMax = 5), d.copy(extGatewayProbe = true), d.copy(listenMulticast = true))
        assertEquals(15, oneEach.size)   // the 15 covered values once plan 3C-2 adds listenMulticast (spec D3)
```
4. Replace `assertEquals(oneDefault.keys, prefs.stored.keys)   // 14 covered values plus nvdKey, and nothing else` with `assertEquals(oneDefault.keys, prefs.stored.keys)   // 15 covered values plus nvdKey, and nothing else`.
5. Add this test before the class's closing brace:
```kotlin
    @Test fun `listening is off by default and a reset turns it off`() {
        assertFalse(SettingsState().listenMulticast)
        assertFalse(SettingsDefaults.reset(SettingsState(listenMulticast = true)).listenMulticast)
        val prefs = FakePrefs(); AppSettings(prefs).update { it.copy(listenMulticast = true) }
        assertEquals(true, prefs.stored["listenMulticast"])
        assertTrue(AppSettings(prefs).state.value.listenMulticast)
    }
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`: add `import com.northwalessolutions.netprobe.ui.settings.ListenCopy` after the file's last `import`, and add this test before the class's closing brace:
```kotlin
    @Test fun `listening strings`() {
        val listenTemplates = listOf("Listen between scans", "While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP\\.",
            "Listening for (mDNS and SSDP|mDNS only|SSDP only)", "Could not open the multicast ports", "Not listening: connect to Wi-Fi or Ethernet",
            "Paused while a scan runs", "mDNS and SSDP are off above").map { Regex(it) }
        val strings = listOf(ListenCopy.LABEL, ListenCopy.SUPPORT) + ListenStatus.entries.mapNotNull { ListenCopy.status(it) }
        strings.forEach { s -> assertTrue("not in the listening copy table: '$s'", listenTemplates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains('\u2014')) }
        assertEquals(9, strings.size)   // label, subtitle and the seven status lines (spec 3.1); OFF shows nothing
        assertEquals(null, ListenCopy.status(ListenStatus.OFF))
    }
```
(`ListenStatus` comes in through the file's existing `import com.northwalessolutions.netprobe.engine.discovery.*`.)

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsListenTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 3C spec 3.1 on the stateless Settings content: in-memory state only, the Director's preferences are never opened. */
@RunWith(AndroidJUnit4::class)
class SettingsListenTest {
    @get:Rule val rule = createComposeRule()
    private var s by mutableStateOf(SettingsState())

    private fun show(state: SettingsState, status: String?) {
        s = state
        rule.setContent { NetProbeTheme { SettingsContent(SettingsUi(s, "Home", 1L, listenStatus = status), SettingsActions(update = { f -> s = f(s) }, current = { s })) } }
    }

    @Test fun theSwitchSitsAfterHttpPageTitlesIsOffByDefaultAndShowsNoStatus() {
        show(SettingsState(), ListenCopy.BOTH)
        val http = rule.onNodeWithText("HTTP page titles").fetchSemanticsNode().positionInRoot.y
        val listen = rule.onNodeWithText(ListenCopy.LABEL).fetchSemanticsNode().positionInRoot.y
        assertTrue("HTTP page titles $http, listen $listen", http < listen)
        rule.onNodeWithText(ListenCopy.SUPPORT, substring = true).assertExists()
        rule.onNodeWithTag("listenStatus").assertDoesNotExist()
    }
    @Test fun turningItOnSavesItAndShowsTheStatusLine() {
        show(SettingsState(), ListenCopy.PAUSED_SCAN)
        rule.onNodeWithText(ListenCopy.LABEL).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(s.listenMulticast)
        rule.onNodeWithTag("listenStatus").assertTextEquals(ListenCopy.PAUSED_SCAN)
    }
    @Test fun resetToDefaultsTurnsListeningOff() {
        show(SettingsState(listenMulticast = true), ListenCopy.BOTH)
        rule.onNodeWithTag("settingsMenu").performClick()
        rule.onNodeWithTag("resetDefaults").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertFalse(s.listenMulticast)
        rule.onNodeWithTag("listenStatus").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.ListenGateTest" --tests "com.northwalessolutions.netprobe.data.SettingsDefaultsTest" --tests "com.northwalessolutions.netprobe.ui.home.CopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'ListenGate'`, `'ListenStatus'`, `'ListenCopy'` and `No parameter with name 'listenMulticast'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenGate.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

/** What a listening session opens (3C spec 3.2). */
data class ListenPlan(val mdns: Boolean, val ssdp: Boolean)

object ListenGate {
    /** Null means: hold no lock, open no socket (spec 3.2). The mDNS and SSDP switches are the scan's own (Settings "Discovery"). */
    fun plan(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean): ListenPlan? =
        if (!listen || !foreground || !hasLan || (!mdns && !ssdp)) null else ListenPlan(mdns, ssdp)
}

/** The Settings status line under "Listen between scans" (spec 3.1); OFF shows nothing. */
enum class ListenStatus { OFF, BOTH, MDNS_ONLY, SSDP_ONLY, NO_PORTS, NO_NETWORK, PAUSED_SCAN, BOTH_OFF }

object ListenStatusRules {
    private val LISTENING = setOf(ListenStatus.BOTH, ListenStatus.MDNS_ONLY, ListenStatus.SSDP_ONLY)

    fun opened(mdns: Boolean, ssdp: Boolean): ListenStatus = when {
        mdns && ssdp -> ListenStatus.BOTH
        mdns -> ListenStatus.MDNS_ONLY
        ssdp -> ListenStatus.SSDP_ONLY
        else -> ListenStatus.NO_PORTS
    }

    /** [session] is what the running session opened, or NO_NETWORK when it found no network row, or null when no session runs. */
    fun of(listen: Boolean, mdns: Boolean, ssdp: Boolean, foreground: Boolean, hasLan: Boolean, session: ListenStatus?, scanActive: Boolean): ListenStatus = when {
        !listen || !foreground -> ListenStatus.OFF
        !mdns && !ssdp -> ListenStatus.BOTH_OFF
        !hasLan || session == null -> ListenStatus.NO_NETWORK
        scanActive && session in LISTENING -> ListenStatus.PAUSED_SCAN
        else -> session
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ListenCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import com.northwalessolutions.netprobe.engine.discovery.ListenStatus

/** Every "Listen between scans" string (3C spec 7.2). Templates live in CopyTableTest. */
object ListenCopy {
    const val LABEL = "Listen between scans"
    const val SUPPORT = "While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP."
    const val BOTH = "Listening for mDNS and SSDP"
    const val MDNS_ONLY = "Listening for mDNS only"
    const val SSDP_ONLY = "Listening for SSDP only"
    const val NO_PORTS = "Could not open the multicast ports"
    const val NO_NETWORK = "Not listening: connect to Wi-Fi or Ethernet"
    const val PAUSED_SCAN = "Paused while a scan runs"
    const val BOTH_OFF = "mDNS and SSDP are off above"

    fun status(s: ListenStatus): String? = when (s) {
        ListenStatus.OFF -> null
        ListenStatus.BOTH -> BOTH
        ListenStatus.MDNS_ONLY -> MDNS_ONLY
        ListenStatus.SSDP_ONLY -> SSDP_ONLY
        ListenStatus.NO_PORTS -> NO_PORTS
        ListenStatus.NO_NETWORK -> NO_NETWORK
        ListenStatus.PAUSED_SCAN -> PAUSED_SCAN
        ListenStatus.BOTH_OFF -> BOTH_OFF
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt` (3C-1's file), targeted edits:
1. Replace
```kotlin
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
)
```
with
```kotlin
    val extEnabled: Boolean = false, val extMax: Int = AppSettings.EXT_MAX_DEFAULT, val extGatewayProbe: Boolean = false,
    /** "Listen between scans" (3C spec 3.1): off by default; covered by SettingsDefaults.reset, so a reset turns it off (D3). */
    val listenMulticast: Boolean = false,
)
```
2. Replace
```kotlin
            extGatewayProbe = prefs.getBoolean("extGatewayProbe", d.extGatewayProbe))
```
with
```kotlin
            extGatewayProbe = prefs.getBoolean("extGatewayProbe", d.extGatewayProbe),
            listenMulticast = prefs.getBoolean("listenMulticast", d.listenMulticast))
```
3. Replace `.putBoolean("extGatewayProbe", s.extGatewayProbe).apply()` with `.putBoolean("extGatewayProbe", s.extGatewayProbe).putBoolean("listenMulticast", s.listenMulticast).apply()`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (3C-1's file), targeted edits:
1. Replace
```kotlin
                      val local: Subnet? = null, val importedWordlists: Int? = null)
```
with
```kotlin
                      val local: Subnet? = null, val importedWordlists: Int? = null,
                      /** The listener's status line (3C spec 3.1), already worded; null shows nothing. */
                      val listenStatus: String? = null)
```
2. Replace
```kotlin
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }
```
with
```kotlin
            SwitchRow("HTTP page titles", s.httpTitle) { v -> update { it.copy(httpTitle = v) } }
            // 3C spec 3.1: directly after HTTP page titles; the status line only while the switch is on.
            SwitchRow(ListenCopy.LABEL, s.listenMulticast, ListenCopy.SUPPORT) { v -> update { it.copy(listenMulticast = v) } }
            if (s.listenMulticast) ui.listenStatus?.let {
                Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.padding(bottom = 6.dp).testTag("listenStatus"))
            }
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.ListenGateTest" --tests "com.northwalessolutions.netprobe.data.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` twice; 3C-1's `SettingsContentTest` compiles unchanged (`listenStatus` is defaulted).

- [ ] **Step 5: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenGate.kt app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ListenCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Settings.kt app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenGateTest.kt app/src/test/java/com/northwalessolutions/netprobe/data/SettingsDefaultsTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsListenTest.kt
git status --short
git commit -m "Phase 3C-2: Listen between scans setting, gate, status rules and Settings switch"
```

---

### Task 10: The heard writer: Heard, the repeat filter and queue, HeardWriter's drop rules, the bounded UPnP fetch, and the guarded write through RadarCollector with upsertHeard (STRONGER MODEL)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Heard.kt`, `engine/discovery/HeardWriter.kt`, `ui/home/ContainerHeardSink.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveredHost.kt` (`Source.HEARD`), `engine/discovery/UpnpDescription.kt` (`UpnpFetcher.fetch` opener), `ui/home/RadarCollector.kt` (`upsert`), `AppContainer.kt` (`removedWhileListening`), `ui/detail/DetailViewModel.kt` (Remove device marks it), `ui/home/HomeViewModel.kt` (a radar scan clears it)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardTestSupport.kt`, `HeardWriterTest.kt`, `HeardRepeatsTest.kt`, `HeardPacketsTest.kt`

**Interfaces:**
- Consumes: `MdnsPacket`, `HEARD_MAX_PER_PACKET`, `SsdpParser.parseNotify` (Task 8); `KnownRanges` (Task 3); `HostRepository.upsertHeard`, `NetworkSignature.identityKey(NetworkEntity)` (Task 2); `RadarCollector`, `RemovedHosts`, `MacExtract`, `UpnpFetcher`, `UpnpDevice` (MVP, 3A); `HomeRange` (3C-1).
- Produces:
```kotlin
const val HEARD_QUEUE = 64; const val HEARD_REPEAT_MS = 60_000L; const val HEARD_REPEAT_KEYS = 1_024
data class Heard(val identityKey: String, val host: DiscoveredHost, val repeatKey: String, val location: String? = null)
fun heardQueue(): Channel<Heard>                                   // HEARD_QUEUE, DROP_OLDEST
class HeardRepeats(clock: () -> Long, windowMs: Long = HEARD_REPEAT_MS, maxKeys: Int = HEARD_REPEAT_KEYS) { fun admit(key: String): Boolean; fun size(): Int }
object HeardPackets { fun mdns(bytes: ByteArray, identityKey: String): List<Heard>; fun ssdp(from: Inet4Address, bytes: ByteArray, identityKey: String): Heard? }
const val HEARD_NEW_MAX = 32; const val HEARD_FETCH_MAX = 32; const val HEARD_LOCATION_MAX_CHARS = 512
data class HeardContext(val networkId: Long, val identityKey: String, val forgotten: Boolean, val scanActive: Boolean, val undoPending: Boolean)
enum class HeardOutcome { WRITTEN, WRONG_NETWORK, BUSY, OUT_OF_RANGE, REMOVED, CAP_REACHED, NOT_STORED }
interface HeardSink { fun context(): HeardContext?; suspend fun known(networkId: Long): KnownRanges; suspend fun exists(networkId: Long, ip: String): Boolean
                      fun removed(): Set<Pair<Long, String>>; suspend fun describe(location: String, host: Inet4Address): UpnpDevice?
                      suspend fun store(networkId: Long, h: DiscoveredHost): Boolean }
class HeardWriter(sessionNetworkId: Long, sink: HeardSink) { suspend fun accept(heard: Heard): HeardOutcome }
enum class Source { ..., HEARD }                                   // in memory only, never stored
class UpnpFetcher { suspend fun fetch(location: String, allowedHost: Inet4Address, opener: (URL) -> URLConnection = NO_PROXY_OPENER): UpnpDevice? }
class RadarCollector(..., hintsFor, upsert: suspend (Long, DiscoveredHost, Long) -> Long = hosts::upsertDiscovered, removed)
class ContainerHeardSink(c: AppContainer, env: NetworkEnv) : HeardSink
AppContainer.removedWhileListening: MutableStateFlow<Set<Pair<Long, String>>>
```
The drop rules, in order (spec 3.4, G4, G5, G22): (1) no current network, its Forget pending, its id not the session's network row, or its identity key not the packet's tag: `WRONG_NETWORK`; (2) a radar scan running or an Undo pending: `BUSY`; (3) the address outside the network's `KnownRanges`: `OUT_OF_RANGE`; (4) the (network, ip) removed this session: `REMOVED`; (5) no row for (network, ip) and `HEARD_NEW_MAX` new rows already inserted by this writer: `CAP_REACHED`. Then, for an SSDP sighting with a `LOCATION`, the description is fetched at most once per IP, at most `HEARD_FETCH_MAX` times per session, never for a `LOCATION` over `HEARD_LOCATION_MAX_CHARS`, bound to Wi-Fi, by the existing `UpnpFetcher` (2 s, 64 KB, host must equal the packet source, no redirects) (controller ruling). Then the write goes through `RadarCollector.store` with `upsertHeard`, which takes `hostWriteLock`, checks `RemovedHosts.skip` again and applies `IdentityWrite.sameDevice`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardTestSupport.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.CompletableDeferred
import java.net.Inet4Address

/** A HeardSink that records everything and can park a store on [gate] (for the network-change test). */
class FakeSink(var ctx: HeardContext? = HOME, var ranges: KnownRanges = KnownRanges(listOf(HOME_RANGE)), private val device: UpnpDevice? = null) : HeardSink {
    val stored = mutableListOf<Pair<Long, String>>()
    val storedHosts = mutableListOf<DiscoveredHost>()
    val existing = mutableSetOf<String>()
    var removedSet: Set<Pair<Long, String>> = emptySet()
    val described = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var storeCalls = 0
        private set

    override fun context(): HeardContext? = ctx
    override suspend fun known(networkId: Long): KnownRanges = ranges
    override suspend fun exists(networkId: Long, ip: String): Boolean = ip in existing
    override fun removed(): Set<Pair<Long, String>> = removedSet
    override suspend fun describe(location: String, host: Inet4Address): UpnpDevice? { described += location; return device }
    override suspend fun store(networkId: Long, h: DiscoveredHost): Boolean {
        storeCalls++
        gate?.await()
        stored += networkId to h.ip.hostAddress; storedHosts += h; existing += h.ip.hostAddress
        return true
    }

    companion object {
        const val KEY_A = "10.0.3.1/24"
        val HOME_RANGE = SubnetMath.of(Ipv4.parse("10.0.3.1")!!, 24)
        val HOME = HeardContext(1, KEY_A, forgotten = false, scanActive = false, undoPending = false)
    }
}

object HeardTestSupport {
    /** An authored SSDP alive NOTIFY from [ip] (3C spec 6: nothing captured). */
    fun notify(ip: String, usn: String, location: String = "http://$ip:80/description.xml"): String =
        "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=100\r\nLOCATION: $location\r\nNT: upnp:rootdevice\r\n" +
            "NTS: ssdp:alive\r\nSERVER: Hue/1.0 UPnP/1.0 IpBridge/1.65.0\r\nUSN: $usn\r\n\r\n"
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardWriterTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.HOME
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.HOME_RANGE
import com.northwalessolutions.netprobe.engine.discovery.FakeSink.Companion.KEY_A
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Ipv4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 8.1 item 18 (G4, G5, G22, Review Focus 4). */
class HeardWriterTest {
    private fun heard(ipText: String, key: String = KEY_A, location: String? = null) =
        Heard(key, DiscoveredHost(Ipv4.parse(ipText)!!, sources = setOf(Source.SSDP, Source.HEARD)), "s|$ipText", location)
    private fun writer(sink: FakeSink) = HeardWriter(1, sink)

    @Test fun `an address outside the known ranges is dropped`() = runBlocking {
        val sink = FakeSink()
        assertEquals(HeardOutcome.OUT_OF_RANGE, writer(sink).accept(heard("10.20.0.5")))
        assertTrue(sink.stored.isEmpty())
    }
    @Test fun `new rows inside the home range, a manual range and a known host's slash 24 are kept`() = runBlocking {
        val sink = FakeSink(ranges = KnownRanges.of(HOME_RANGE, listOf(CidrParser.subnetOf("10.0.4.0/24")!!), listOf(TestHosts.host(ip = "10.0.5.9"))))
        val w = writer(sink)
        for (t in listOf("10.0.3.7", "10.0.4.8", "10.0.5.20")) assertEquals(t, HeardOutcome.WRITTEN, w.accept(heard(t)))
        assertEquals(listOf(1L to "10.0.3.7", 1L to "10.0.4.8", 1L to "10.0.5.20"), sink.stored)
    }
    @Test fun `the 33rd new row of a session is dropped while an update to an existing row still applies`() = runBlocking {
        val sink = FakeSink(); sink.existing += "10.0.3.250"
        val w = writer(sink)
        for (i in 1..HEARD_NEW_MAX) assertEquals("row $i", HeardOutcome.WRITTEN, w.accept(heard("10.0.3.$i")))
        assertEquals(HeardOutcome.CAP_REACHED, w.accept(heard("10.0.3.${HEARD_NEW_MAX + 1}")))
        assertEquals("an existing row still updates", HeardOutcome.WRITTEN, w.accept(heard("10.0.3.250")))
        assertEquals(HEARD_NEW_MAX + 1, sink.stored.size)
        assertEquals("a new session starts its own count", HeardOutcome.WRITTEN, writer(sink).accept(heard("10.0.3.200")))
    }
    @Test fun `nothing is written while a radar scan runs or an Undo is pending`() = runBlocking {
        for (ctx in listOf(HOME.copy(scanActive = true), HOME.copy(undoPending = true))) {
            val sink = FakeSink(ctx = ctx)
            assertEquals("$ctx", HeardOutcome.BUSY, writer(sink).accept(heard("10.0.3.7")))
            assertTrue(sink.stored.isEmpty())
        }
    }
    @Test fun `a packet for another network or another row is dropped`() = runBlocking {
        for (ctx in listOf(null, HOME.copy(forgotten = true), HOME.copy(networkId = 2), HOME.copy(identityKey = "10.0.9.1/24"))) {
            val sink = FakeSink(ctx = ctx)
            assertEquals("$ctx", HeardOutcome.WRONG_NETWORK, writer(sink).accept(heard("10.0.3.7")))
            assertTrue(sink.stored.isEmpty())
        }
        val sink = FakeSink()
        assertEquals("heard on another env", HeardOutcome.WRONG_NETWORK, writer(sink).accept(heard("10.0.3.7", key = "10.0.9.1/24")))
        assertTrue(sink.stored.isEmpty())
    }
    @Test fun `a device removed this session is not re-added`() = runBlocking {
        val sink = FakeSink(); sink.removedSet = setOf(1L to "10.0.3.7")
        assertEquals(HeardOutcome.REMOVED, writer(sink).accept(heard("10.0.3.7")))
        assertEquals(HeardOutcome.WRITTEN, writer(sink).accept(heard("10.0.3.8")))
        assertEquals(listOf(1L to "10.0.3.8"), sink.stored)
    }
    @Test fun `the description is fetched once per IP inside the known ranges and merged into the write`() = runBlocking {
        val sink = FakeSink(device = UpnpDevice("Living Room", "Sonos", "One", null, "urn:schemas-upnp-org:device:ZonePlayer:1", null))
        val w = writer(sink)
        assertEquals(HeardOutcome.WRITTEN, w.accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/d.xml")))
        w.accept(heard("10.0.3.30", location = "http://10.0.3.30:1400/other.xml"))
        w.accept(heard("10.20.0.5", location = "http://10.20.0.5/d.xml"))
        assertEquals("one GET, never for an address outside the known ranges", listOf("http://10.0.3.30:1400/d.xml"), sink.described)
        val h = sink.storedHosts.first()
        assertEquals("Living Room", h.upnpFriendlyName); assertEquals("Sonos", h.upnpManufacturer); assertEquals("One", h.upnpModel)
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", h.upnpDeviceType)
    }
    @Test fun `the description budget is capped per session and a long location is never fetched`() = runBlocking {
        val sink = FakeSink()
        (1..HEARD_FETCH_MAX + 5).forEach { sink.existing += "10.0.3.$it" }      // updates only, so the new-row cap is not what stops it
        val w = writer(sink)
        for (i in 1..HEARD_FETCH_MAX + 5) w.accept(heard("10.0.3.$i", location = "http://10.0.3.$i/d.xml"))
        assertEquals(HEARD_FETCH_MAX, sink.described.size)
        val fresh = FakeSink()
        writer(fresh).accept(heard("10.0.3.9", location = "http://10.0.3.9/" + "a".repeat(HEARD_LOCATION_MAX_CHARS)))
        assertTrue(fresh.described.isEmpty())
        assertEquals("the sighting itself is still written", 1, fresh.stored.size)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardRepeatsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 3C spec 3.3 and 8.1 item 18: the repeat filter's window and bound, and the queue's overflow. */
class HeardRepeatsTest {
    @Test fun `a repeat within 60 s is dropped and one after it is kept`() {
        var now = 0L
        val r = HeardRepeats({ now })
        assertTrue(r.admit("k"))
        now = HEARD_REPEAT_MS - 1; assertFalse(r.admit("k"))
        now = HEARD_REPEAT_MS; assertTrue(r.admit("k"))
        assertTrue("another key is not a repeat", r.admit("other"))
    }
    @Test fun `the map never holds more than 1,024 keys and forgets the eldest`() {
        val r = HeardRepeats({ 0L })
        for (i in 0 until HEARD_REPEAT_KEYS + 50) assertTrue(r.admit("k$i"))
        assertEquals(HEARD_REPEAT_KEYS, r.size())
        assertFalse("a recent key is still a repeat", r.admit("k${HEARD_REPEAT_KEYS + 49}"))
        assertTrue("the eldest was evicted, so it is new again", r.admit("k0"))
        assertEquals(HEARD_REPEAT_KEYS, r.size())
    }
    @Test fun `the queue drops the oldest when the writer falls behind`() = runBlocking {
        val q = heardQueue()
        for (i in 0..HEARD_QUEUE) assertTrue(q.trySend(Heard(FakeSink.KEY_A, DiscoveredHost(Ipv4.parse("10.0.3.$i")!!), "s|10.0.3.$i")).isSuccess)
        assertEquals("the first of 65 was dropped", "s|10.0.3.1", q.receive().repeatKey)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardPacketsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.*
import org.junit.Test

/** What a heard packet becomes before the queue (3C spec 3.3): the scan's shapes plus Source.HEARD, tagged with the session's key. */
class HeardPacketsTest {
    private val key = FakeSink.KEY_A

    @Test fun `an mDNS announcement becomes a heard host tagged with the session's network`() {
        val p = MdnsTestPackets.announce(MdnsTestPackets.Builder(), "CHNAS", "_http._tcp", "chnas", "10.0.3.59", 5000, arrayOf("model=DS220+")).build()
        val h = HeardPackets.mdns(p, key).single()
        assertEquals(key, h.identityKey); assertEquals("m|10.0.3.59|_http._tcp", h.repeatKey); assertNull(h.location)
        assertEquals(setOf(Source.MDNS, Source.HEARD), h.host.sources); assertEquals("CHNAS", h.host.mdnsName)
        assertEquals(setOf("_http._tcp"), h.host.mdnsServiceTypes); assertEquals(mapOf("_http._tcp" to mapOf("model" to "DS220+")), h.host.mdnsTxt)
    }
    @Test fun `an SSDP alive becomes a heard host with its location, its USN key and the UDN MAC`() {
        val usn = "uuid:2f402f80-da50-11e1-9b23-00178817aa01::upnp:rootdevice"
        val h = HeardPackets.ssdp(Ipv4.parse("10.0.3.40")!!, HeardTestSupport.notify("10.0.3.40", usn).toByteArray(Charsets.ISO_8859_1), key)!!
        assertEquals("http://10.0.3.40:80/description.xml", h.location); assertEquals("s|10.0.3.40|$usn", h.repeatKey)
        assertEquals("00:17:88:17:aa:01", h.host.mac); assertEquals(MacSource.UPNP_UDN, h.host.macSource)
        assertEquals(setOf(Source.SSDP, Source.HEARD), h.host.sources); assertEquals("Hue/1.0 UPnP/1.0 IpBridge/1.65.0", h.host.upnpServer)
    }
    @Test fun `byebye and a malformed packet give nothing`() {
        assertNull(HeardPackets.ssdp(Ipv4.parse("10.0.3.40")!!, "NOTIFY * HTTP/1.1\r\nNTS: ssdp:byebye\r\nUSN: x\r\n\r\n".toByteArray(), key))
        assertTrue(HeardPackets.mdns(byteArrayOf(1, 2, 3), key).isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.Heard*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'HeardSink'`, `'HeardContext'`, `'HeardWriter'`, `'Heard'`, `'HeardRepeats'`, `'HeardPackets'`, `'HEARD'`.

- [ ] **Step 3: Implement the heard shapes and the writer**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveredHost.kt`: replace
```kotlin
enum class Source { SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP, GATEWAY }
```
with
```kotlin
/** HEARD marks a listener sighting (3C spec 3.3); like every Source it lives in memory only and is never stored. */
enum class Source { SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP, GATEWAY, HEARD }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Heard.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.Inet4Address

/** Per session: the bounded queue between the socket readers and the writer (3C spec 3.4). */
const val HEARD_QUEUE = 64
/** A (address, type or USN) key heard again within this window is dropped before the queue (spec 3.3). */
const val HEARD_REPEAT_MS = 60_000L
/** The repeat filter remembers at most this many keys (spec 3.3, G4). */
const val HEARD_REPEAT_KEYS = 1_024

/** One heard announcement, tagged with the identity key of the env its session was started on (spec 3.3, G5). */
data class Heard(val identityKey: String, val host: DiscoveredHost, val repeatKey: String, val location: String? = null)

/** Drops the oldest when the writer falls behind; cancelling it at the session's end discards whatever is left (spec 3.2). */
fun heardQueue(): Channel<Heard> = Channel(HEARD_QUEUE, BufferOverflow.DROP_OLDEST)

/** An LRU of last-admitted times (spec 3.3): bounded, per session, so a flood of distinct keys costs memory only up to [maxKeys]. */
class HeardRepeats(private val clock: () -> Long, private val windowMs: Long = HEARD_REPEAT_MS, private val maxKeys: Int = HEARD_REPEAT_KEYS) {
    private val last = object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxKeys
    }
    @Synchronized fun admit(key: String): Boolean {
        val now = clock()
        val previous = last[key]
        if (previous != null && now - previous < windowMs) return false
        last[key] = now
        return true
    }
    @Synchronized fun size(): Int = last.size
    private companion object { const val INITIAL_CAPACITY = 16; const val LOAD_FACTOR = 0.75f }
}

/** Heard packets in the scan's shapes plus Source.HEARD (spec 3.3); the MAC rules are the scan's (withDerivedMac, the USN MAC). */
object HeardPackets {
    private const val MDNS_KEY = "m|"
    private const val SSDP_KEY = "s|"

    fun mdns(bytes: ByteArray, identityKey: String): List<Heard> = MdnsPacket.parse(bytes).map { r ->
        val h = DiscoveredHost(r.ip, mdnsName = r.instanceName, mdnsServiceTypes = setOf(r.type), mdnsTxt = mapOf(r.type to r.txt),
            mdnsInstanceNames = setOf(r.instanceName), ipv6LinkLocal = r.ipv6.toSet(), sources = setOf(Source.MDNS, Source.HEARD)).withDerivedMac()
        Heard(identityKey, h, MDNS_KEY + r.ip.hostAddress + "|" + r.type)
    }

    fun ssdp(from: Inet4Address, bytes: ByteArray, identityKey: String): Heard? {
        val r = SsdpParser.parseNotify(from, String(bytes, Charsets.ISO_8859_1)) ?: return null
        val usnMac = r.usn?.let { MacExtract.fromText(it) }
        val h = DiscoveredHost(from, upnpServer = r.server, mac = usnMac, macSource = usnMac?.let { MacSource.UPNP_UDN },
            sources = setOf(Source.SSDP, Source.HEARD)).withDerivedMac()
        return Heard(identityKey, h, SSDP_KEY + from.hostAddress + "|" + (r.usn ?: r.st.orEmpty()), r.location)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HeardWriter.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address

/** New rows one listening session may insert; after that heard hosts only update existing rows (3C spec 3.4 rule 4, G4). */
const val HEARD_NEW_MAX = 32
/** UPnP description GETs one session may make (controller ruling: bounded as discovery's fetch is). */
const val HEARD_FETCH_MAX = 32
/** A LOCATION longer than this is never fetched. */
const val HEARD_LOCATION_MAX_CHARS = 512

/** What the writer needs to know about "now" (spec 3.4 rules 1 and 2), read at every packet. */
data class HeardContext(val networkId: Long, val identityKey: String, val forgotten: Boolean, val scanActive: Boolean, val undoPending: Boolean)

enum class HeardOutcome { WRITTEN, WRONG_NETWORK, BUSY, OUT_OF_RANGE, REMOVED, CAP_REACHED, NOT_STORED }

/** The writer's view of the app (production: ui.home.ContainerHeardSink; tests: a recording fake). */
interface HeardSink {
    /** The current network as Home resolved it, or null. */
    fun context(): HeardContext?
    suspend fun known(networkId: Long): KnownRanges
    suspend fun exists(networkId: Long, ip: String): Boolean
    /** (network, ip) removed this session or during a running scan. */
    fun removed(): Set<Pair<Long, String>>
    /** One bounded UPnP description fetch, bound to Wi-Fi; null on any failure. */
    suspend fun describe(location: String, host: Inet4Address): UpnpDevice?
    /** The guarded radar write (RadarCollector with upsertHeard); false when it was dropped there. */
    suspend fun store(networkId: Long, h: DiscoveredHost): Boolean
}

/**
 * One per listening session, fed serially by that session's queue (3C spec 3.4). [sessionNetworkId] is the network row the session resolved
 * for its env; a packet is written only while Home's current network is that same row with the packet's identity key, so nothing heard on
 * one network lands in another, including the window before Home catches up with a network change (G5). Counts are per session (G4).
 */
class HeardWriter(private val sessionNetworkId: Long, private val sink: HeardSink) {
    private var inserted = 0
    private val fetchedIps = HashSet<String>()

    suspend fun accept(heard: Heard): HeardOutcome {
        val ctx = sink.context()
        if (ctx == null || ctx.networkId != sessionNetworkId || ctx.forgotten || ctx.identityKey != heard.identityKey) return HeardOutcome.WRONG_NETWORK
        if (ctx.scanActive || ctx.undoPending) return HeardOutcome.BUSY
        val ip = heard.host.ip
        val ipText: String = ip.hostAddress
        if (!sink.known(ctx.networkId).contains(ip)) return HeardOutcome.OUT_OF_RANGE
        if ((ctx.networkId to ipText) in sink.removed()) return HeardOutcome.REMOVED
        val exists = sink.exists(ctx.networkId, ipText)
        if (!exists && inserted >= HEARD_NEW_MAX) return HeardOutcome.CAP_REACHED
        if (!sink.store(ctx.networkId, described(heard, ipText))) return HeardOutcome.NOT_STORED
        if (!exists) inserted++
        return HeardOutcome.WRITTEN
    }

    /** The UPnP description of an SSDP sighting, merged as discovery merges it (DiscoveryEngine's SSDP branch). */
    private suspend fun described(heard: Heard, ipText: String): DiscoveredHost {
        val location = heard.location ?: return heard.host
        if (location.length > HEARD_LOCATION_MAX_CHARS || fetchedIps.size >= HEARD_FETCH_MAX || !fetchedIps.add(ipText)) return heard.host
        val d = sink.describe(location, heard.host.ip) ?: return heard.host
        val h = heard.host
        val udnMac = if (h.mac == null) d.udn?.let { MacExtract.fromText(it) } else null
        return h.copy(upnpFriendlyName = d.friendlyName, upnpManufacturer = d.manufacturer, upnpModel = d.modelName, upnpDeviceType = d.deviceType,
            mac = h.mac ?: udnMac, macSource = h.macSource ?: udnMac?.let { MacSource.UPNP_UDN })
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 5: Wire the production sink, the fetch opener, the guarded write and the removed set**

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/UpnpDescription.kt`:
1. Replace `import java.net.URL` with
```kotlin
import java.net.URL
import java.net.URLConnection
```
2. Replace
```kotlin
    suspend fun fetch(location: String, allowedHost: Inet4Address): UpnpDevice? {
```
with
```kotlin
    /** [opener] is the listener's Wi-Fi-bound connection (3C spec 3.4, controller ruling); discovery keeps the unbound NO_PROXY default. */
    suspend fun fetch(location: String, allowedHost: Inet4Address, opener: (URL) -> URLConnection = NO_PROXY_OPENER): UpnpDevice? {
```
3. Replace
```kotlin
                // NO_PROXY: a LAN fetch must never be handed to a Wi-Fi proxy or PAC outside the network.
                val c = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
```
with
```kotlin
                val c = opener(url) as HttpURLConnection
```
4. Replace the end of `fetch` and of `UpnpFetcher` (the last five lines of the file)
```kotlin
        } catch (_: Exception) {
            null
        }
    }
}
```
with
```kotlin
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** NO_PROXY: a LAN fetch must never be handed to a Wi-Fi proxy or PAC outside the network. */
        val NO_PROXY_OPENER: (URL) -> URLConnection = { it.openConnection(Proxy.NO_PROXY) }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarCollector.kt`:
1. Replace
```kotlin
    private val hintsFor: suspend (Long) -> List<String> = { emptyList() },
    private val removed: () -> Set<Pair<Long, String>>,
```
with
```kotlin
    private val hintsFor: suspend (Long) -> List<String> = { emptyList() },
    /** The row write: a scan's upsertDiscovered, or the listener's upsertHeard (3C spec 3.4); the lock, the removed check and the identity rule are shared. */
    private val upsert: suspend (Long, DiscoveredHost, Long) -> Long = hosts::upsertDiscovered,
    private val removed: () -> Set<Pair<Long, String>>,
```
2. Replace `        val id = hosts.upsertDiscovered(networkId, h, runId)` with `        val id = upsert(networkId, h, runId)`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ContainerHeardSink.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.HeardContext
import com.northwalessolutions.netprobe.engine.discovery.HeardSink
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.discovery.UpnpDevice
import com.northwalessolutions.netprobe.engine.discovery.UpnpFetcher
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.flow.first
import java.net.Inet4Address

/**
 * The listener's writes into the inventory (3C spec 3.4), one per session. The write is a RadarCollector built with upsertHeard, so it takes
 * hostWriteLock, honours RemovedHosts.skip and applies IdentityWrite.sameDevice exactly as a radar emission does.
 */
class ContainerHeardSink(private val c: AppContainer, private val env: NetworkEnv) : HeardSink {
    private val upnp = UpnpFetcher()
    private val collector = RadarCollector(c.hosts, c.ports, c.deviceIdentifier, c.ouiLookup, c.hostWriteLock,
        hintsFor = { id -> c.toolRepo.hintsFor(id) }, upsert = c.hosts::upsertHeard) { removed() }

    override fun context(): HeardContext? = c.currentNetwork.value?.let { n ->
        HeardContext(n.id, NetworkSignature.identityKey(n), forgotten = n.id == c.forgottenNetworkId.value,
            scanActive = c.radarScanActive.value, undoPending = c.undo.pending.value != null)
    }
    /** The home range from the live settings, this network's manual ranges and its visible hosts (spec 3.4). */
    override suspend fun known(networkId: Long): KnownRanges {
        val s = c.settings.state.value
        return KnownRanges.of(HomeRange.of(env.interfaceIp, env.prefixLength, s.maxPrefix, s.extEnabled).subnet, c.settings.manual.get(networkId),
            c.hosts.hostsForNetwork(networkId).first())
    }
    override suspend fun exists(networkId: Long, ip: String): Boolean = c.hosts.findOnce(networkId, ip) != null
    override fun removed(): Set<Pair<Long, String>> = c.removedDuringScan.value + c.removedWhileListening.value
    /** Bound to the Wi-Fi or Ethernet Network, never unbound; no binder means no fetch. */
    override suspend fun describe(location: String, host: Inet4Address): UpnpDevice? {
        val binder = c.networkEnvMonitor.binderFor(env) ?: return null
        return upnp.fetch(location, host) { url -> binder.openConnection(url) }
    }
    /** A new or promoted row takes the network's last completed run, so it shows undimmed; "Last scan" never moves (spec 3.4). */
    override suspend fun store(networkId: Long, h: DiscoveredHost): Boolean = collector.store(networkId, h, c.networks.byId(networkId)?.lastRunAt ?: 0L)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: replace
```kotlin
    val removedHostIds = MutableStateFlow<Set<Long>>(emptySet())
```
with
```kotlin
    val removedHostIds = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * (network, ip) removed with Remove device since the last radar scan or listening session started (3C spec 3.4 rule 5, G22): the
     * listener does not re-add them. Cleared by both starts; Undo takes its pair out.
     */
    val removedWhileListening = MutableStateFlow<Set<Pair<Long, String>>>(emptySet())
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt` (`removeDevice`):
1. Replace
```kotlin
                    hostSnap to token
```
with
```kotlin
                    // 3C spec 3.4 rule 5: the listener must not re-add it before the next scan or listening session; Undo takes it out below.
                    c.removedWhileListening.update { RemovedHosts.added(it, h.networkId, h.ip) }
                    hostSnap to token
```
2. Replace (in the Undo's `restore`, 28-space indentation)
```kotlin
                            c.removedHostIds.update { it - hostId }
                            c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
```
with
```kotlin
                            c.removedHostIds.update { it - hostId }
                            c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
                            c.removedWhileListening.update { RemovedHosts.without(it, h.networkId, h.ip) }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: replace
```kotlin
            c.removedDuringScan.value = emptySet()          // a new radar scan starts with nothing removed
```
with
```kotlin
            c.removedDuringScan.value = emptySet()          // a new radar scan starts with nothing removed
            c.removedWhileListening.value = emptySet()      // 3C spec 3.4 rule 5: after a scan the listener may add a device again
```

- [ ] **Step 6: Run the suites and check the write path**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
grep -rn "upsertHeard" app/src/main --include=*.kt
```
Expected: `BUILD SUCCESSFUL` twice (3A's `RadarIdentityGuardTest` compiles unchanged, `UpnpDescriptionTest` passes); the grep finds `upsertHeard` only in `Repositories.kt` and `ContainerHeardSink.kt`, so nothing writes a heard host except through `RadarCollector`.

- [ ] **Step 7: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/DiscoveredHost.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Heard.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HeardWriter.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/UpnpDescription.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarCollector.kt app/src/main/java/com/northwalessolutions/netprobe/ui/home/ContainerHeardSink.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardTestSupport.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardWriterTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardRepeatsTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HeardPacketsTest.kt
git status --short
git commit -m "Phase 3C-2: heard writer with drop rules, bounded description fetch and guarded write"
```

---

### Task 11: ListenController, the sockets and the listener's own lock, wired into AppContainer, the activity start and the Settings status line (STRONGER MODEL)

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenSockets.kt`, `engine/discovery/ListenController.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt` (whole file), `AppContainer.kt` (`listen`, `onActivityCreated` starts it), `ui/settings/SettingsScreen.kt` (the wrapper passes the status)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/common/LocksTest.kt`, `engine/discovery/ListenSocketsTest.kt`, `engine/discovery/ListenControllerTest.kt`

**Interfaces:**
- Consumes: `ListenGate`, `ListenPlan`, `ListenStatus`, `ListenStatusRules` (Task 9); `Heard`, `heardQueue`, `HeardRepeats`, `HeardPackets`, `HeardWriter`, `ContainerHeardSink`, `removedWhileListening` (Task 10); `NetworkSignature.identityKey` (MVP); `CancellableIo.closing`, `SocketBinder` (3B, Phase 2); `AppContainer.onActivityCreated` (Task 2); `SettingsUi.listenStatus`, `ListenCopy.status` (Task 9).
- Produces:
```kotlin
interface LockHandle { fun acquire(); fun release() }
class Locks internal constructor(multicast: LockHandle, perf: LockHandle, val listen: LockHandle) { constructor(context: Context); fun acquire(); fun release()
    companion object { const val SCAN_TAG = "netprobe-mdns"; const val PERF_TAG = "netprobe-scan"; const val LISTEN_TAG = "netprobe-listen" } }
data class Datagram(val from: Inet4Address, val length: Int)
interface HeardSocket { suspend fun receive(into: ByteArray): Datagram?; fun close() }
fun interface ListenSockets { fun open(group: String, port: Int, env: NetworkEnv): HeardSocket? }
class MulticastListenSockets(binderFor: (NetworkEnv) -> SocketBinder?, interfaceFor: (Inet4Address) -> NetworkInterface? = ..., newSocket: () -> MulticastSocket = ..., log: (Throwable) -> Unit = {}) : ListenSockets
class ListenController(scope, settings: StateFlow<SettingsState>, foreground: StateFlow<Boolean>, env: StateFlow<NetworkEnv?>, epoch: StateFlow<Int>,
                       scanActive: StateFlow<Boolean>, lock: LockHandle, sockets: ListenSockets, resolve: suspend (NetworkEnv) -> Long?,
                       writerFor: (NetworkEnv, Long) -> HeardWriter, onSessionStart: () -> Unit = {}, io: CoroutineDispatcher = Dispatchers.IO,
                       clock: () -> Long = System::currentTimeMillis, log: (Throwable) -> Unit = {}) {
    val status: StateFlow<ListenStatus>; fun start()
    companion object { const val MDNS_GROUP = "224.0.0.251"; const val MDNS_PORT = 5353; const val SSDP_GROUP = "239.255.255.250"; const val SSDP_PORT = 1900
                       const val MDNS_MAX_PACKET = 9000; const val SSDP_MAX_PACKET = 4096 } }
AppContainer.listen: ListenController
```
Session lifecycle (spec 3.2, D10, G5, G11): the controller keys on (plan, env, network epoch) with `distinctUntilChanged` and `collectLatest`. A session resolves its network row (none, or its Forget pending: status "Not listening", nothing held), acquires `Locks.listen`, clears `removedWhileListening`, opens the planned sockets (a null interface or no binder opens nothing for that socket; none open: the lock is released at once and the status says so), then runs one reader per socket and one writer over a fresh `heardQueue()`; its `finally` closes the sockets, cancels the queue (discarding anything in it) and releases the lock. `collectLatest` joins the old session before the next starts, so the lock is released before it is re-acquired. ON_STOP (foreground false), the switch off, a reset, network loss or change, and a Forget or its Undo (the epoch) all end the session.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/LocksTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import org.junit.Assert.*
import org.junit.Test

/** 3C spec 3.2 (Review Focus 5): the listener's lock is its own; the scan's acquire and release never touch it. */
class LocksTest {
    private class FakeLock : LockHandle {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }
    private val scanMulticast = FakeLock(); private val perf = FakeLock(); private val listen = FakeLock()
    private val locks = Locks(scanMulticast, perf, listen)

    @Test fun `the scan's release never releases the listener's lock`() {
        locks.listen.acquire(); locks.acquire(); locks.release()
        assertFalse(scanMulticast.held); assertFalse(perf.held)
        assertTrue("the listen lock survives a scan's end", listen.held)
    }
    @Test fun `releasing the listener's lock leaves a running scan's locks held`() {
        locks.acquire(); locks.listen.acquire(); locks.listen.release()
        assertTrue(scanMulticast.held); assertTrue(perf.held); assertFalse(listen.held)
    }
    @Test fun `the three tags are distinct`() {
        assertEquals(3, setOf(Locks.SCAN_TAG, Locks.PERF_TAG, Locks.LISTEN_TAG).size)
        assertEquals("netprobe-listen", Locks.LISTEN_TAG); assertEquals("netprobe-mdns", Locks.SCAN_TAG)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenSocketsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.IOException
import java.net.*

/** 3C spec 8.1 item 15, G11 (Review Focus 5). Port 0 everywhere: no test binds 5353 or 1900 on the PC. */
class ListenSocketsTest {
    private object FakeBinder : SocketBinder {
        override fun bind(socket: Socket) {}
        override fun bind(socket: DatagramSocket) {}
        override fun openConnection(url: URL): URLConnection = url.openConnection(Proxy.NO_PROXY)
    }
    private val env = NetworkEnv(Ipv4.parse("10.0.3.50")!!, 24, Ipv4.parse("10.0.3.1")!!, emptyList(), "Home", null, Transport.WIFI)
    private fun loopback(): NetworkInterface? = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())

    @Test fun `a null interface opens no socket, so nothing joins on a default or VPN interface`() {
        var created = 0
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { null }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `an interface lookup that throws opens nothing`() {
        var created = 0
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { throw SocketException("interface gone") }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.SSDP_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `no Wi-Fi or Ethernet binder opens no socket`() {
        val lo = loopback(); assumeNotNull(lo)
        var created = 0
        val s = MulticastListenSockets({ null }, interfaceFor = { lo }, newSocket = { created++; MulticastSocket(null as SocketAddress?) })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertEquals(0, created)
    }
    @Test fun `a socket that cannot join is closed and reported as not open`() {
        val lo = loopback(); assumeNotNull(lo)
        var closed = false
        val failing = object : MulticastSocket(null as SocketAddress?) {
            override fun joinGroup(mcastaddr: SocketAddress?, netIf: NetworkInterface?) { throw IOException("no multicast route") }
            override fun close() { closed = true; super.close() }
        }
        val s = MulticastListenSockets({ FakeBinder }, interfaceFor = { lo }, newSocket = { failing })
        assertNull(s.open(ListenController.MDNS_GROUP, 0, env))
        assertTrue(closed)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenControllerTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.SettingsDefaults
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.common.LockHandle
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.ContinuationInterceptor

/** 3C spec 8.1 items 15 and 18 (the network change), Review Focus 4 and 5: fake lock, fake sockets, test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenControllerTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val envA = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    private val envB = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.9.1"), emptyList(), "Guest", null, Transport.WIFI)

    private class FakeLock : LockHandle {
        var held = false; var acquires = 0; var releases = 0
        override fun acquire() { acquires++; held = true }
        override fun release() { if (held) releases++; held = false }
    }
    private class FakeSocket : HeardSocket {
        val inbox = Channel<Pair<ByteArray, Inet4Address>>(Channel.UNLIMITED)
        var closed = false
        override suspend fun receive(into: ByteArray): Datagram? {
            val (bytes, from) = inbox.receiveCatching().getOrNull() ?: throw IOException("closed")
            bytes.copyInto(into)
            return Datagram(from, bytes.size)
        }
        override fun close() { closed = true; inbox.close() }
    }
    private class FakeSockets : ListenSockets {
        var fail = false
        val opened = mutableListOf<Pair<String, FakeSocket>>()
        override fun open(group: String, port: Int, env: NetworkEnv): HeardSocket? = if (fail) null else FakeSocket().also { opened += group to it }
        fun latest(group: String): FakeSocket = opened.last { it.first == group }.second
    }
    private inner class Rig(scope: CoroutineScope, initial: SettingsState = SettingsState(listenMulticast = true)) {
        val settings = MutableStateFlow(initial)
        val foreground = MutableStateFlow(true)
        val env = MutableStateFlow<NetworkEnv?>(envA)
        val epoch = MutableStateFlow(0)
        val scan = MutableStateFlow(false)
        val lock = FakeLock(); val sockets = FakeSockets(); val sink = FakeSink()
        val writers = mutableListOf<Long>()
        var resolveTo: (NetworkEnv) -> Long? = { e -> if (e == envA) 1L else 2L }
        val controller = ListenController(scope, settings, foreground, env, epoch, scan, lock, sockets,
            resolve = { e -> resolveTo(e) }, writerFor = { _, id -> writers += id; HeardWriter(id, sink) },
            io = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, clock = { 0L })
        fun hear(ipText: String, usn: String) {
            sockets.latest(ListenController.SSDP_GROUP).inbox.trySend(HeardTestSupport.notify(ipText, usn).toByteArray(Charsets.ISO_8859_1) to ip(ipText))
        }
    }

    @Test fun `with the switch off nothing is held`() = runTest {
        val rig = Rig(backgroundScope, SettingsState())
        rig.controller.start(); runCurrent()
        assertEquals(0, rig.lock.acquires); assertTrue(rig.sockets.opened.isEmpty())
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
    }
    @Test fun `switching on acquires once and opens both ports`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        assertEquals(1, rig.lock.acquires); assertTrue(rig.lock.held)
        assertEquals(listOf(ListenController.MDNS_GROUP, ListenController.SSDP_GROUP), rig.sockets.opened.map { it.first })
        assertEquals(ListenStatus.BOTH, rig.controller.status.value)
        rig.controller.start(); runCurrent()
        assertEquals("start is idempotent", 1, rig.lock.acquires)
    }
    @Test fun `a heard announcement on the session's network is written once within the repeat window`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertEquals(listOf(1L to "10.0.3.7"), rig.sink.stored)
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertEquals("a repeat is dropped before the queue", 1, rig.sink.storeCalls)
    }
    @Test fun `going to the background releases the lock and closes the sockets`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.foreground.value = false; runCurrent()
        assertFalse(rig.lock.held); assertEquals(1, rig.lock.releases)
        assertTrue(rig.sockets.opened.all { it.second.closed })
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
        rig.foreground.value = true; runCurrent()
        assertEquals(2, rig.lock.acquires); assertTrue(rig.lock.held)
    }
    @Test fun `turning the switch off and a reset both release`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.settings.value = rig.settings.value.copy(listenMulticast = false); runCurrent()
        assertFalse(rig.lock.held)
        rig.settings.value = rig.settings.value.copy(listenMulticast = true); runCurrent()
        assertTrue(rig.lock.held); assertEquals(2, rig.lock.acquires)
        rig.settings.value = SettingsDefaults.reset(rig.settings.value); runCurrent()
        assertFalse(rig.lock.held); assertEquals(2, rig.lock.releases)
        assertEquals(ListenStatus.OFF, rig.controller.status.value)
    }
    @Test fun `losing the network releases`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.env.value = null; runCurrent()
        assertFalse(rig.lock.held); assertTrue(rig.sockets.opened.all { it.second.closed })
        assertEquals(ListenStatus.NO_NETWORK, rig.controller.status.value)
    }
    @Test fun `a network change releases, then re-acquires with a new writer`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.env.value = envB; runCurrent()
        assertEquals(2, rig.lock.acquires); assertEquals(1, rig.lock.releases); assertTrue(rig.lock.held)
        assertEquals(listOf(1L, 2L), rig.writers)
        assertTrue("the old sockets are closed", rig.sockets.opened.take(2).all { it.second.closed })
        assertEquals(4, rig.sockets.opened.size)
    }
    @Test fun `a Forget or its Undo restarts the session on the network row`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.epoch.value = 1; runCurrent()
        assertEquals(2, rig.lock.acquires); assertEquals(listOf(1L, 1L), rig.writers)
    }
    @Test fun `no network row means nothing is held`() = runTest {
        val rig = Rig(backgroundScope); rig.resolveTo = { null }
        rig.controller.start(); runCurrent()
        assertEquals(0, rig.lock.acquires); assertTrue(rig.sockets.opened.isEmpty())
        assertEquals(ListenStatus.NO_NETWORK, rig.controller.status.value)
    }
    @Test fun `ports that cannot open release the lock and say so`() = runTest {
        val rig = Rig(backgroundScope); rig.sockets.fail = true
        rig.controller.start(); runCurrent()
        assertEquals(1, rig.lock.acquires); assertFalse(rig.lock.held)
        assertEquals(ListenStatus.NO_PORTS, rig.controller.status.value)
    }
    @Test fun `only the enabled protocol is opened`() = runTest {
        val rig = Rig(backgroundScope, SettingsState(listenMulticast = true, ssdp = false))
        rig.controller.start(); runCurrent()
        assertEquals(listOf(ListenController.MDNS_GROUP), rig.sockets.opened.map { it.first })
        assertEquals(ListenStatus.MDNS_ONLY, rig.controller.status.value)
    }
    @Test fun `a running scan shows as paused and what is heard meanwhile is dropped`() = runTest {
        val rig = Rig(backgroundScope)
        rig.controller.start(); runCurrent()
        rig.scan.value = true; rig.sink.ctx = FakeSink.HOME.copy(scanActive = true)
        rig.hear("10.0.3.7", "uuid:a"); runCurrent()
        assertTrue(rig.sink.stored.isEmpty())
        assertEquals(ListenStatus.PAUSED_SCAN, rig.controller.status.value)
        assertTrue("the sockets stay open during a scan", rig.lock.held)
    }
    @Test fun `packets from network A queued at a change are discarded and B's are dropped until Home catches up`() = runTest {
        val rig = Rig(backgroundScope)
        val gate = CompletableDeferred<Unit>(); rig.sink.gate = gate
        rig.controller.start(); runCurrent()
        for (i in 1..3) rig.hear("10.0.3.${10 + i}", "uuid:$i")
        runCurrent()                                    // the writer is parked in A's first store, two more wait in A's queue
        rig.env.value = envB; runCurrent()              // Home has not caught up: the current network is still A (FakeSink.HOME)
        rig.hear("10.0.3.20", "uuid:b"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue("nothing written to A or B", rig.sink.stored.isEmpty())
        assertEquals("only A's parked first store ever started, and it was cancelled", 1, rig.sink.storeCalls)
        assertEquals(listOf(1L, 2L), rig.writers)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.LocksTest" --tests "com.northwalessolutions.netprobe.engine.discovery.Listen*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED` with `Unresolved reference 'LockHandle'`, `'MulticastListenSockets'`, `'ListenController'`, `'HeardSocket'`, `'Datagram'`, `'ListenSockets'`.

- [ ] **Step 3: Implement the lock, the sockets and the controller**

Before writing it (3C-1 plan review G1): `git diff --quiet 82084d3 -- app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt || echo "STOP: app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt changed since 82084d3"`; if it prints STOP, stop and ask the controller.

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt` (whole file; the scan's two locks behave exactly as before):
```kotlin
package com.northwalessolutions.netprobe.engine.common

import android.content.Context
import android.net.wifi.WifiManager

/** A Wi-Fi lock NetProbe holds. acquire and release never throw; release is a no-op when not held. */
interface LockHandle {
    fun acquire()
    fun release()
}

/**
 * The scan's multicast and high-performance locks, and the listener's own multicast lock (3C spec 3.2). They are separate objects: a scan
 * ending calls [release], which never touches [listen], and ListenController releasing [listen] never touches the scan's locks.
 */
class Locks internal constructor(private val multicast: LockHandle, private val perf: LockHandle, val listen: LockHandle) {
    constructor(context: Context) : this(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)

    @Suppress("DEPRECATION")
    private constructor(wifi: WifiManager) : this(
        MulticastHandle(wifi.createMulticastLock(SCAN_TAG)),
        WifiHandle(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, PERF_TAG)),
        MulticastHandle(wifi.createMulticastLock(LISTEN_TAG)))

    fun acquire() { multicast.acquire(); perf.acquire() }
    fun release() { multicast.release(); perf.release() }

    // runCatching as before 3C: a refused lock only means fewer multicast frames or a slower radio; it must never fail a scan or a session.
    private class MulticastHandle(private val lock: WifiManager.MulticastLock) : LockHandle {
        init { lock.setReferenceCounted(false) }
        override fun acquire() { runCatching { lock.acquire() } }
        override fun release() { runCatching { if (lock.isHeld) lock.release() } }
    }
    private class WifiHandle(private val lock: WifiManager.WifiLock) : LockHandle {
        init { lock.setReferenceCounted(false) }
        override fun acquire() { runCatching { lock.acquire() } }
        override fun release() { runCatching { if (lock.isHeld) lock.release() } }
    }

    companion object {
        const val SCAN_TAG = "netprobe-mdns"
        const val PERF_TAG = "netprobe-scan"
        const val LISTEN_TAG = "netprobe-listen"
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenSockets.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/** One received packet: the IPv4 sender and how many bytes of the caller's buffer it filled. */
data class Datagram(val from: Inet4Address, val length: Int)

/** A listening socket (3C spec 3.3). */
interface HeardSocket {
    /** Suspends until a packet arrives; null for a non-IPv4 sender; throws an IOException once the socket is closed. */
    suspend fun receive(into: ByteArray): Datagram?
    fun close()
}

/** Opens a joined multicast socket for one group and port on the env's network, or null when it cannot (spec 3.3). */
fun interface ListenSockets {
    fun open(group: String, port: Int, env: NetworkEnv): HeardSocket?
}

/**
 * Production sockets (spec 3.3): MulticastSocket(null), SO_REUSEADDR (the system mDNS service holds 5353 too), bound to the group port,
 * bound to the Wi-Fi or Ethernet Network, then joined on that network's interface. A null interface or no binder opens nothing (G11): never
 * joinGroup(addr, null), which could join on a VPN's tun device. Reads close the socket at once on cancellation (CancellableIo).
 */
class MulticastListenSockets(
    private val binderFor: (NetworkEnv) -> SocketBinder?,
    private val interfaceFor: (Inet4Address) -> NetworkInterface? = { NetworkInterface.getByInetAddress(it) },
    private val newSocket: () -> MulticastSocket = { MulticastSocket(null as SocketAddress?) },
    private val log: (Throwable) -> Unit = {},
) : ListenSockets {
    override fun open(group: String, port: Int, env: NetworkEnv): HeardSocket? {
        val iface = (try { interfaceFor(env.interfaceIp) } catch (e: Exception) { log(e); null }) ?: return null
        val binder = binderFor(env) ?: return null
        val socket = newSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            binder.bind(socket)
            socket.joinGroup(InetSocketAddress(InetAddress.getByName(group), port), iface)
            Opened(socket)
        } catch (e: Exception) {
            log(e)
            runCatching { socket.close() }   // cleanup only: the socket never opened, so a failed close changes nothing
            null
        }
    }

    private class Opened(private val socket: MulticastSocket) : HeardSocket {
        override suspend fun receive(into: ByteArray): Datagram? = withContext(Dispatchers.IO) {
            val p = DatagramPacket(into, into.size)
            CancellableIo.closing(socket) { socket.receive(p) }
            (p.address as? Inet4Address)?.let { Datagram(it, p.length) }
        }
        override fun close() { socket.close() }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenController.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.data.SettingsState
import com.northwalessolutions.netprobe.engine.common.LockHandle
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Listen between scans" (3C spec 3.2, D10, D11). Hears and never sends, except the writer's bounded UPnP description GET. One session per
 * (plan, env, network epoch); see the Task 11 interface notes for the lifecycle. Nothing runs in the background: foreground false ends it.
 */
class ListenController(
    private val scope: CoroutineScope,
    private val settings: StateFlow<SettingsState>,
    private val foreground: StateFlow<Boolean>,
    private val env: StateFlow<NetworkEnv?>,
    /** AppContainer.networkEpoch: bumped by Forget, its Undo and its commit, so a session re-resolves its network row. */
    private val epoch: StateFlow<Int>,
    scanActive: StateFlow<Boolean>,
    private val lock: LockHandle,
    private val sockets: ListenSockets,
    /** The network row for the env (null when none or its Forget is pending): the only row this session may write to. */
    private val resolve: suspend (NetworkEnv) -> Long?,
    private val writerFor: (NetworkEnv, Long) -> HeardWriter,
    private val onSessionStart: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (Throwable) -> Unit = {},
) {
    private data class Key(val plan: ListenPlan, val env: NetworkEnv, val epoch: Int)
    private val session = MutableStateFlow<ListenStatus?>(null)
    private val started = AtomicBoolean(false)

    val status: StateFlow<ListenStatus> = combine(settings, foreground, env, session, scanActive) { s, fg, e, ss, scan ->
        ListenStatusRules.of(s.listenMulticast, s.mdns, s.ssdp, fg, e != null, ss, scan)
    }.stateIn(scope, SharingStarted.Eagerly, ListenStatus.OFF)

    /** Idempotent; AppContainer.onActivityCreated calls it once per process. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(settings, foreground, env, epoch) { s, fg, e, n ->
                val plan = ListenGate.plan(s.listenMulticast, s.mdns, s.ssdp, fg, e != null)
                if (plan == null || e == null) null else Key(plan, e, n)
            }.distinctUntilChanged().collectLatest { key -> if (key == null) session.value = null else listen(key) }
        }
    }

    private suspend fun listen(key: Key) {
        val networkId = try { resolve(key.env) } catch (ce: CancellationException) { throw ce } catch (e: Exception) { log(e); null }
        if (networkId == null) { session.value = ListenStatus.NO_NETWORK; return }
        val opened = ArrayList<HeardSocket>(2)
        val queue = heardQueue()
        lock.acquire()
        try {
            onSessionStart()
            val mdns = if (key.plan.mdns) sockets.open(MDNS_GROUP, MDNS_PORT, key.env)?.also { opened += it } else null
            val ssdp = if (key.plan.ssdp) sockets.open(SSDP_GROUP, SSDP_PORT, key.env)?.also { opened += it } else null
            session.value = ListenStatusRules.opened(mdns != null, ssdp != null)
            if (opened.isEmpty()) { lock.release(); awaitCancellation() }   // nothing to hear: hold nothing until the key changes
            val identityKey = NetworkSignature.identityKey(key.env)
            val writer = writerFor(key.env, networkId)
            val repeats = HeardRepeats(clock)
            coroutineScope {
                if (mdns != null) launch(io) { read(mdns, MDNS_MAX_PACKET) { bytes, _ -> HeardPackets.mdns(bytes, identityKey).forEach { offer(queue, repeats, it) } } }
                if (ssdp != null) launch(io) { read(ssdp, SSDP_MAX_PACKET) { bytes, from -> HeardPackets.ssdp(from, bytes, identityKey)?.let { offer(queue, repeats, it) } } }
                launch {
                    for (h in queue) {
                        // One failed write (a database error) is logged and never ends the session.
                        try { writer.accept(h) } catch (ce: CancellationException) { throw ce } catch (e: Exception) { log(e) }
                    }
                }
            }
        } finally {
            opened.forEach { s -> runCatching { s.close() } }   // cleanup only: a failed close must not skip the release below
            queue.cancel()                                        // discards anything still queued from this network
            lock.release()
            session.value = null
        }
    }

    private fun offer(queue: Channel<Heard>, repeats: HeardRepeats, h: Heard) {
        if (repeats.admit(h.repeatKey)) queue.trySend(h)   // DROP_OLDEST: never fails, never suspends a reader
    }

    private suspend fun read(socket: HeardSocket, size: Int, onPacket: (ByteArray, Inet4Address) -> Unit) {
        val buf = ByteArray(size)
        while (true) {
            val d = (try { socket.receive(buf) } catch (ce: CancellationException) { throw ce } catch (e: IOException) { log(e); return }) ?: continue
            onPacket(buf.copyOf(d.length), d.from)
        }
    }

    companion object {
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val SSDP_GROUP = "239.255.255.250"
        const val SSDP_PORT = 1900
        /** mDNS packets can reach the jumbo-frame size (RFC 6762 section 17); SSDP NOTIFYs are small. */
        const val MDNS_MAX_PACKET = 9000
        const val SSDP_MAX_PACKET = 4096
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.LocksTest" --tests "com.northwalessolutions.netprobe.engine.discovery.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 0 failures. If a `ListenControllerTest` case hangs, the session's `finally` is not releasing or the reader is not cancellable: fix the controller, never the test's expectations.

- [ ] **Step 5: Wire the controller into the app**

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`:
1. Replace `import com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngine` with
```kotlin
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryEngine
import com.northwalessolutions.netprobe.engine.discovery.HeardWriter
import com.northwalessolutions.netprobe.engine.discovery.ListenController
import com.northwalessolutions.netprobe.engine.discovery.MulticastListenSockets
import com.northwalessolutions.netprobe.engine.neigh.GatewayMac
```
2. Replace `import com.northwalessolutions.netprobe.ui.home.ScanBinding` with
```kotlin
import com.northwalessolutions.netprobe.ui.home.ContainerHeardSink
import com.northwalessolutions.netprobe.ui.home.ScanBinding
```
(if 3C-1 left `ScanBinding` unused here, keep its import as 3C-1 left it; only add the new line).
3. Replace (Task 2's `onActivityCreated`)
```kotlin
        appScope.launch { hostWriteLock.withLock { hosts.deleteOrphanManual() } }
    }
```
with
```kotlin
        appScope.launch { hostWriteLock.withLock { hosts.deleteOrphanManual() } }
        listen.start()
    }

    /**
     * "Listen between scans" (3C spec 3.2): sessions on appScope, gated by the switch, the foreground and the LAN, with their own lock.
     * Started only by onActivityCreated. Each session resolves its env's network row and writes only while Home's current network is that row.
     */
    val listen: ListenController by lazy {
        ListenController(appScope, settings.state, foreground,
            networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()), networkEpoch, radarScanActive,
            locks.listen, MulticastListenSockets(networkEnvMonitor::binderFor, log = { Log.w(AppScopes.LOG_TAG, "listen socket", it) }),
            resolve = { e -> networks.ensure(e, GatewayMac.lookup(e.gateway))?.id?.takeIf { it != forgottenNetworkId.value } },
            writerFor = { e, id -> HeardWriter(id, ContainerHeardSink(this@AppContainer, e)) },
            onSessionStart = { removedWhileListening.value = emptySet() },
            log = { Log.e(AppScopes.LOG_TAG, "listen", it) })
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt` (3C-1's wrapper):
1. Replace
```kotlin
    val wordlistsLoaded = rememberWordlistStore(container).value != null
```
with
```kotlin
    val wordlistsLoaded = rememberWordlistStore(container).value != null
    val listenStatus by container.listen.status.collectAsStateWithLifecycle()   // 3C spec 3.1
```
2. Replace
```kotlin
        SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null),
```
with
```kotlin
        SettingsUi(s, network?.displayName, network?.id, manual, local, if (wordlistsLoaded) wordlists.count { !it.builtIn } else null,
            ListenCopy.status(listenStatus)),
```

- [ ] **Step 6: Check the lock and start paths**

```bash
grep -rn "listen.start\|locks.listen\|LISTEN_TAG\|createMulticastLock" app/src/main --include=*.kt
grep -rn "onActivityCreated" app/src/main --include=*.kt
```
Expected: `listen.start()` only in `AppContainer.onActivityCreated`; `locks.listen` only in `AppContainer.listen`; `createMulticastLock` only in `Locks.kt` (twice: the scan's and the listener's); `onActivityCreated` defined in `AppContainer` and called only from `MainActivity.onCreate`. Anything else is reported before the commit.

- [ ] **Step 7: Task gate**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. Record the JVM test count (883 at the 3B base, plus 3C-1's, plus this plan's).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/northwalessolutions/netprobe/engine/common/Locks.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenSockets.kt app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/ListenController.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/common/LocksTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenSocketsTest.kt app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/ListenControllerTest.kt
git status --short
git commit -m "Phase 3C-2: multicast listener sessions with their own lock, wired to the activity and Settings"
```

---

### Task 12: Deferred on-device QA (executed later, when the phone is available)

This task is NOT executed during the plan. Tasks 1 to 11 end with JVM tests green and every instrumented test compiled; no task has device evidence. When the Pixel 7 Pro (`28221FDH300AP9`) is on the Director's LAN, Bugsy runs every step below in order and records each result, pass or fail, in `docs/qa/phase3c-2-on-device.md`. Per the standing QA rule, Bugsy builds, installs on the device and smoke tests every core flow; a green suite is not a substitute, and code review is not QA. If any step fails, stop, record it, and tell the Director; never fix anything on his live data.

3C-2 changes the schema (3 to 4), so the full 3B data-safety ceremony runs (spec 8.4 item 0, G6): backup of the database, preferences and wordlists with MD5 on both sides, a rollback APK from the 3C-1 head, `DirectorCopyMigration34Test` on a pushed copy of his database before the in-place install, and a check of every mark, note, override, port and tool run afterwards. Schema 4 cannot be opened by the 3C-1 build (Room refuses a downgrade and the app has no destructive fallback), so the restore procedure at the end always puts back the Step 0 database with the rollback APK.

Destructive checks (Forget this network, Remove device) run only on a disposable network: the UniFi guest SSID if the Director keeps one, or the phone's hotspot from a second device. Never on the home network. Each is recorded with the network used.

**Approval gate (one internet contact).** Item 3 makes the app open a TLS handshake to `1.1.1.1:443` (Cloudflare's public resolver) to prove a public target goes "via VPN or other route". That is the only internet contact in this task; the Director approves it with the plan, or item 3 uses a public address he names instead. No agent fetches anything on the PC.

**Files:**
- Create (at execution time): `docs/qa/phase3c-2-on-device.md`, `docs/qa/shots/phase3c-2/*.png`, `docs/qa/shots/phase3c-2/*.mp4`
- Backups, database copies and the rollback APK go OUTSIDE the repository (they hold the Director's network inventory): `K:/claude/netprobe_db_backup/phase3c-2/`

Common setup (as 3C-1's Task 11, with the base on line 3 of `phase3c-base.txt`):
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=28221FDH300AP9
PKG=com.northwalessolutions.netprobe
BK=/k/claude/netprobe_db_backup/phase3c-2
BASE=$(sed -n 3p docs/superpowers/plans/phase3c-base.txt)    # the 3C-1 head the phone runs (Task 1 Step 0, line 3)
mkdir -p "$BK/wordlists" "$BK/probe" docs/qa/shots/phase3c-2
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
# MD5 of the backup "$BK/<name>.bak" on the PC and of its source on the phone; they must match. Usage: same <name> <path on the phone>
same() {
  local pc phone
  pc=$(md5sum < "$BK/$1.bak" | cut -d' ' -f1)
  phone=$("$ADB" -s $DEV exec-out run-as $PKG md5sum "$2" | cut -d' ' -f1)
  if [ "$pc" = "$phone" ]; then echo "MD5 OK $2 $pc"; else echo "MD5 MISMATCH $2: PC $pc, phone $phone"; return 1; fi
}
run() { ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$1" --console=plain 2>&1 | tail -12; }
# Row counts from a pulled COPY of the database, read with the PC's own Python standard library (sqlite3); nothing is installed. The copy is
# taken with the app stopped and lives under $BK, never in the repository. Usage: probe <dir>; rows <dir> "<SQL returning one number>"
probe() { mkdir -p "$1"; "$ADB" -s $DEV shell am force-stop $PKG; for f in netprobe.db netprobe.db-wal; do "$ADB" -s $DEV exec-out run-as $PKG cat databases/$f > "$1/$f"; done; }
rows() { python -I -c "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute(sys.argv[2]).fetchone()[0])" "$(cygpath -w "$1/netprobe.db")" "$2"; }
```
If `leaveApksInstalledAfterRun` is rejected by this AGP version, stop and ask the Director before running any instrumented test without it. If `python` is not on the PATH, record every `rows` check as "NOT RUN: no Python on the PC" and do the on-screen check that goes with it; install nothing.

- [ ] **Step 0: Back up the schema 3 database, the preferences and the wordlists, before anything is installed or any instrumented test runs (spec 8.4 item 0, G6)**

```bash
"$ADB" -s $DEV shell am force-stop $PKG
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do
  "$ADB" -s $DEV exec-out run-as $PKG cat databases/$f > "$BK/$f.bak"
done
"$ADB" -s $DEV exec-out run-as $PKG cat shared_prefs/netprobe.xml > "$BK/netprobe.xml.bak"
same netprobe.db databases/netprobe.db && same netprobe.db-wal databases/netprobe.db-wal && same netprobe.xml shared_prefs/netprobe.xml
"$ADB" -s $DEV exec-out run-as $PKG ls files/wordlists | tr -d '\r' > "$BK/wordlists-list.txt"
cat "$BK/wordlists-list.txt"
for f in $(cat "$BK/wordlists-list.txt"); do
  "$ADB" -s $DEV exec-out run-as $PKG cat files/wordlists/$f > "$BK/wordlists/$f.bak"
  same "wordlists/$f" "files/wordlists/$f" || break
done
grep -c 'listenMulticast' "$BK/netprobe.xml.bak" || echo "no listenMulticast key yet (expected before 3C-2)"
mkdir -p "$BK/schema3" && cp "$BK/netprobe.db.bak" "$BK/schema3/netprobe.db" && cp "$BK/netprobe.db-wal.bak" "$BK/schema3/netprobe.db-wal"
for t in networks hosts ports tool_runs tool_findings; do echo "$t $(rows "$BK/schema3" "SELECT COUNT(*) FROM $t")"; done
echo "marked $(rows "$BK/schema3" "SELECT COUNT(*) FROM hosts WHERE status != 'UNMARKED' OR note != '' OR customName IS NOT NULL OR userType IS NOT NULL")"
ls -l "$BK" "$BK/wordlists"
```
Expected: `netprobe.db.bak` and `netprobe.xml.bak` non-empty; `MD5 OK` for the database, its `-wal`, the preferences and every wordlist file (record each MD5 in the QA record); the schema 3 counts recorded (they are the baseline for Step 4). If anything is empty where it should not be, or any MD5 mismatches, stop.

- [ ] **Step 1: Build the rollback APK from the recorded base, offline (no downloads)**

```bash
RB=/k/claude/netprobe_rollback_${BASE:0:7}
git worktree add "$RB" "$BASE"
cp local.properties "$RB/"
(cd "$RB" && ./gradlew :app:assembleDebug --offline --console=plain 2>&1 | tail -3)
cp "$RB/app/build/outputs/apk/debug/app-debug.apk" "$BK/netprobe-3c1-${BASE:0:7}.apk"
git worktree remove --force "$RB"
ls -l "$BK"
```
Expected: `BUILD SUCCESSFUL` and the APK in `$BK`. If Gradle reports anything missing offline, stop and ask the Director; never drop `--offline`.

- [ ] **Step 2: Put the copy for DirectorCopyMigration34Test in place**

```bash
for f in netprobe.db netprobe.db-wal; do
  put "$BK/$f.bak" "databases/${f/netprobe.db/netprobe-migration-copy.db}" || break
done
"$ADB" -s $DEV shell run-as $PKG ls -l databases/
```
Expected: `MD5 OK` for both files; `netprobe.db*` untouched; `netprobe-migration-copy.db` the same size as `netprobe.db.bak`. On any `MD5 MISMATCH`, stop: do not run Step 3. The test deletes the copy, so repeat this step before every re-run of `DirectorCopyMigration34Test`.

- [ ] **Step 3: The migration tests first (they install the test APKs but never open `netprobe.db`)**

```bash
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ > "$BK/before-tests.txt"
run com.northwalessolutions.netprobe.data.DirectorCopyMigration34Test
run com.northwalessolutions.netprobe.data.Migration34Test
run com.northwalessolutions.netprobe.data.ToolMigrationTest
run com.northwalessolutions.netprobe.data.MigrationTest
"$ADB" -s $DEV shell run-as $PKG ls -l databases/
```
Expected: all pass. `DirectorCopyMigration34Test` reports 1 test (not an assumption failure, which would mean the copy was not found) and proves the Director's own schema 3 data migrates with every network, host, mark, note, override, port and tool run unchanged. `netprobe.db` and its `-wal` have the same size and time as in `before-tests.txt`; the copy is gone. `DirectorCopyMigration23Test` (3B) is NOT run in 3C-2: it asserts a schema 2 copy and would fail on a schema 3 one (it never touches `netprobe.db` either way); say so in the record. If any test fails, stop: do not install the build.

- [ ] **Step 4: In-place migration of the real database (install over, never clean), then a schema 4 backup**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3c-2/q00-after-migration.png
"$ADB" -s $DEV logcat -d | grep -E "AndroidRuntime|FATAL|IllegalStateException: Migration|Room" | tail -10
probe "$BK/schema4"
for t in networks hosts ports tool_runs tool_findings; do echo "$t $(rows "$BK/schema4" "SELECT COUNT(*) FROM $t")"; done
echo "hidden $(rows "$BK/schema4" "SELECT COUNT(*) FROM hosts WHERE manualOnly != 0")  routed $(rows "$BK/schema4" "SELECT COUNT(*) FROM tool_runs WHERE route IS NOT NULL")"
echo "version $(rows "$BK/schema4" "PRAGMA user_version")"
for f in netprobe.db netprobe.db-wal; do cp "$BK/schema4/$f" "$BK/schema4/$f.bak"; done
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
Expected: no crash and no migration error; every count equals Step 0's (the orphan sweep finds nothing in a database that had no manual rows); `hidden 0`, `routed 0`, `version 4`. Check on screen: every host the Director marked shows its mark; open two marked hosts and confirm their notes, custom names, overrides, open ports and tool history (the 3B runs, with no route line). Record pass or fail and the JVM test count from the gate. If anything is missing, stop and tell the Director; the restore procedure below is his call.

- [ ] **Step 5: The rest of the instrumented suite** (DAO and Compose tests use in-memory databases or fake state):
```bash
for c in data.ManualHostDaoTest data.ToolDaoTest data.DaoTest data.RemoveDeviceTest data.RadarIdentityGuardTest \
         ui.home.HomeMenuTest ui.manual.ManualToolsContentTest ui.tools.RouteLineTest ui.tools.ToolResultsSheetTest ui.tools.ToolsBlockTest \
         ui.tools.DirBruteSheetTest ui.settings.SettingsListenTest ui.settings.SettingsContentTest ui.settings.ExtendedSectionTest \
         ui.settings.SettingsRowsTest ui.settings.SettingsToolsTest ui.wordlists.WordlistsScreenTest ui.home.HomeContentTest \
         ui.home.RadarBlockTest ui.home.RadarStateTest ui.detail.DetailContentTest ui.NavTransitionTest ui.ControlsMotionTest ui.ListMotionTest; do
  run com.northwalessolutions.netprobe.$c
done
run com.northwalessolutions.netprobe.ui.HomeScreenTest   # last: it launches the real activity, which opens netprobe.db, runs the orphan sweep and starts the listener (off by default)
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ shared_prefs/ files/wordlists/
```
Expected: every class passes; record each count. A class name that does not exist on the branch is recorded and skipped, not invented. Timing tests that fail on a real frame clock are recorded with their message, not retried into a pass. `files/wordlists/` lists the same files as Step 0.

- [ ] **Step 6: Behaviour checks, each recorded pass or fail** (spec 8.4 items 1 to 7, plus the hidden-row and Forget checks)

1. Manual tools (spec 8.4 item 1). From Home's overflow, "Run a tool..." (screenshot `s01-menu`). Run, one at a time, reading each IP from the Home list: HTTP fingerprint of the Pi-hole on its web port; TLS certificate of the UniFi gateway on 443; SMB session on CHNAS; SNMP walk on the router. Each shows its progress under the pinned Stop, ends with its summary, adds a history row ("<tool> on <ip>:<port>", "<summary>, <age>"), opens the results sheet from that row with "via Wi-Fi" under the title, and Share opens the share sheet with the 3B export. Then run HTTP fingerprint on one LAN address the Home list does not show (a device the Director names, or record "every target was already a known device"): before any scan, Home's device count and Quick scan all's host list are unchanged (the target is hidden); after a radar scan finds it, it appears as a normal device whose Device screen shows the run in its Tools history.
2. Stop and busy (spec 8.4 item 2). Start a directory brute-force of CHNAS's web port with common-short; Stop ends it within about a second (record the `r1-manual-stop` screen recording). Start one again and, while it runs, tap Run for a second tool on the same IP: the snackbar reads "A tool is already running on this device". Then the cancelled sheet: count hidden rows, open the brute-force sheet for a new LAN address, tap Cancel, count again:
```bash
probe "$BK/probe"; echo "hidden before $(rows "$BK/probe" "SELECT COUNT(*) FROM hosts WHERE manualOnly = 1")"; "$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
# (open Run a tool, type a new address and a port, choose Directory brute-force, Run, then Cancel the sheet)
probe "$BK/probe"; echo "hidden after $(rows "$BK/probe" "SELECT COUNT(*) FROM hosts WHERE manualOnly = 1")"; "$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
   Expected: the two counts are equal.
3. Route (spec 8.4 item 3; the approval gate above). TLS certificate of `1.1.1.1:443`: the run completes and its header reads "via VPN or other route". If a VPN app is already installed on the phone (no installs), connect it, run TLS certificate on an address only the VPN routes, and confirm the same header and a result; otherwise record "NOT RUN, covered by `ManualRouteTest` and `ToolRunnerRouteTest`". A known LAN target (the gateway) reads "via Wi-Fi" with the VPN connected too.
4. Forget while a manual run holds a hidden row. Start a brute-force on a hidden target; open Home's overflow: "Forget this network" is disabled until the run ends. Observe only: do not tap it on the home network.
5. Listening (spec 8.4 item 4). Settings: "Listen between scans" sits directly after "HTTP page titles", off; turn it on: the status line reads "Listening for mDNS and SSDP" (or records which protocol could not open, assumption A2). Go to Home and do not scan. Within 10 minutes, at least one device not yet in the inventory appears, or a known device's `lastSeen` moves:
```bash
probe "$BK/probe"; rows "$BK/probe" "SELECT MAX(lastSeen) FROM hosts WHERE manualOnly = 0"; "$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
"$ADB" -s $DEV shell dumpsys wifi | grep -i -A2 "netprobe-listen"     # with Home open: listed
"$ADB" -s $DEV shell input keyevent KEYCODE_HOME; "$ADB" -s $DEV shell sleep 3
"$ADB" -s $DEV shell dumpsys wifi | grep -i -A2 "netprobe-listen" || echo "netprobe-listen not held in the background"
```
   (the phone-side `sleep 3` lets ON_STOP land; it is the only wait in this task.) Record what was heard, by which protocol, and the two `dumpsys` results; expected: held in the foreground, not held after Home is sent to the background.
6. Listening during a scan (spec 8.4 item 5). With listening on, start a radar scan: Settings' status line reads "Paused while a scan runs"; when the scan ends, no device appears twice, `netprobe-mdns` is gone from `dumpsys wifi` and `netprobe-listen` is still held. Then Wi-Fi off and on from the quick settings: the status line reads "Not listening: connect to Wi-Fi or Ethernet" while off, `netprobe-listen` is released, and it is held again once Wi-Fi is back. Record a 20 s screen recording `r2-listen-scan`.
7. Battery (spec 8.4 item 6). Screen on, Home open: `"$ADB" -s $DEV shell dumpsys batterystats --reset`, 15 minutes with listening on, then `"$ADB" -s $DEV shell dumpsys batterystats | grep -iE "wifi|cpu" | head -40 > "$BK/battery-on.txt"`; repeat with listening off into `battery-off.txt`. Record the Wi-Fi and CPU lines of both in the QA record.
8. Network change mid-listen (spec 8.4 item 7, disposable network only). On the guest SSID (or the hotspot), with listening on, switch networks both ways: no guest device lands in the home network's inventory and no home device lands in the guest network's. On the guest network only, Forget this network once and Undo it, then Forget and let it commit: the listener keeps working afterwards (the status line returns to "Listening..."). If no disposable network is available, record "NOT RUN, covered by `ListenControllerTest.packets from network A queued at a change are discarded and B's are dropped until Home catches up` and `HeardWriterTest`".
9. Settings reset turns listening off: with listening on, Settings overflow, Reset to defaults: the switch turns off and the status line goes; Undo turns it back on. (Step 8 restores the Director's preferences.)

- [ ] **Step 7: Screens for the Director at font scale 1.0 and 1.3** (`"$ADB" -s $DEV shell settings put system font_scale 1.3`, restored to 1.0 afterwards):
`s20-home-menu` (overflow open: Quick scan all, Run a tool..., Forget this network, Settings), `s21-run-a-tool` (empty, the fields and Run pinned), `s22-run-a-tool-running` (Stop and progress, history below), `s23-results-route` (the route line under the title), `s24-settings-listen` (the switch and its status line). At 1.3 the pinned block may push the history down but the Run / Stop pill and the fields are never clipped or scrolled away.

- [ ] **Step 8: Restore the Director's preferences and confirm the data is untouched**

Items 1 to 9 changed his toggles; put his preferences back exactly, then check:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
put "$BK/netprobe.xml.bak" shared_prefs/netprobe.xml
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
"$ADB" -s $DEV exec-out run-as $PKG ls files/wordlists | tr -d '\r' | diff "$BK/wordlists-list.txt" - && echo "same files"
```
Expected: `MD5 OK`; Settings shows his tuning and "Listen between scans" off (the key is absent from his preferences, so the default applies); Home shows every mark; Wordlists shows his lists. The manual targets, tool runs and heard devices this QA created stay in the inventory (they are QA data on his network); list them in the record so the Director can Remove any he does not want.

- [ ] **Step 9: Record and hand over**

Write `docs/qa/phase3c-2-on-device.md`: the date, build commit, every step's result, the MD5s, the schema 3 and schema 4 counts, the test counts per class, the screenshot and recording names, what the listener heard (devices, protocol, time to first sighting), the battery figures, the networks used for items 3 and 8, the QA-created rows, and for the Director to rule on: spec section 10 assumptions A2 and A3, open items 1, 3 and 4 as the controller ruled them, and every deviation and gap in the Plan Self-Review Notes below. Then commit only the QA record and its shots:
```bash
git add docs/qa/phase3c-2-on-device.md docs/qa/shots/phase3c-2
git status --short    # nothing else may be staged
git commit -m "Phase 3C-2 on-device QA record"
```
The backups, database copies and the rollback APK in `K:/claude/netprobe_db_backup/phase3c-2/` are never committed.

**Restore procedure (only on the Director's say-so).** The 3C-1 build cannot open a schema 4 database, so a rollback always puts back the Step 0 (schema 3) database: anything recorded since the install (manual runs, heard devices, marks set during QA) is lost, and the record says so. Every file is replaced with the app stopped, because a stale `-wal` or `-shm` next to a restored database can corrupt it:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
"$ADB" -s $DEV install -r -d "$BK/netprobe-3c1-${BASE:0:7}.apk"   # -r keeps the data directory; -d allows going back to the older build
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do "$ADB" -s $DEV shell run-as $PKG rm -f databases/$f; done
for f in netprobe.db netprobe.db-wal; do put "$BK/$f.bak" databases/$f || break; done
put "$BK/netprobe.xml.bak" shared_prefs/netprobe.xml
"$ADB" -s $DEV shell run-as $PKG mkdir -p files/wordlists
for f in $(cat "$BK/wordlists-list.txt"); do put "$BK/wordlists/$f.bak" files/wordlists/$f || break; done
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ shared_prefs/ files/wordlists/
```
Check: `MD5 OK` for every file before going on. On any `MD5 MISMATCH`, do not start the app: run the same `put` again for that file, and if it still mismatches, stop and tell the Director (the backup on the PC is intact). Only then:
```bash
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
Expected: the app opens on the 3C-1 build with every mark, note, setting and imported wordlist of Step 0. Record what was restored and why. To return to 3C-2 later, install the 3C-2 build over it: the schema 3 database migrates again exactly as in Step 4.

---

## Plan Self-Review Notes

**Spec coverage, by section:**
- Section 3 (multicast listening): 3.1 the switch, subtitle and status line, Task 9 (`ListenCopy`, the Settings row) and Task 11 (the status wiring); 3.2 gate, controller, lifecycle and the separate lock, Tasks 9 (`ListenGate`) and 11 (`ListenController`, `Locks.listen`, `onActivityCreated`); 3.3 sockets and parsers, Task 8 (`MdnsPacket`, `DnsWire`, `parseNotify`) and Task 11 (`MulticastListenSockets`, the null-interface rule), the per-packet cap Task 8, the repeat LRU and `Heard` tagging Task 10; 3.4 entry into the inventory, Task 10 (`HeardWriter` drop rules 1 to 5, `KnownRanges` from Task 3, `RadarCollector` with `upsertHeard` from Task 2) plus the controller ruling's bounded description fetch; 3.5 nothing to build (the permission is in the manifest), measured in Task 12 item 7.
- Section 4 (manual tools): 4.1 entry and screen, Task 7 (`HomeMenu`, the route, `ManualToolsContent` with its pinned block); 4.2 validation, Task 4 (`ManualTarget`); 4.3 resolution, keying, the brute-force sheet, the discard and the orphan sweep, Tasks 2 (`manualTarget`, `discardManual`, `deleteOrphanManual`, `onActivityCreated`), 5 (`ManualStarter`) and 7 (the view model's sheet flow); 4.4 results, history and export, Tasks 2 (`recentForNetwork`) and 7 (history rows, the 3B sheet, `ToolExport`); 4.5 route, Tasks 3 (`ManualRoute`, `routeBind`), 2 (`tool_runs.route` written), 4 (the strings) and 6 (the header).
- Section 6: the 3C-2 order is followed with the schema first and the listener last; nothing from 3C-1 is rebuilt.
- Section 7.1: every row of the table is Task 1 or Task 2, including `allForNetwork` for `toolRunningOnNetwork` and the canary `knownIps` (Task 2 Step 5). Section 7.2: every 3C-2 row is a Copy member with a template (Tasks 4 and 9; `ToolCopy.ROUTE_*` in `ToolCopyTableTest`); the removed names `MENU_AGGRESSIVE` and `ERR_PRIVATE` are asserted absent; "Run a tool..." is pinned with three full stops.
- Section 8: 8.1 items 14 to 24 are Tasks 9 (14), 11 (15), 8 (16, 17), 10 and 11 (18), 3 (19, 21), 4 (20, 24), 5 (22), 6 (23); 8.2 items 1 to 3, 5 and 6 are written and compiled in Tasks 1, 2, 6, 7 and 9 and run in Task 12; 8.4 is Task 12 with the full data-safety preamble.

**Placeholder scan:** `grep -nE "TBD|TODO|similar to Task|implementer (completes|finishes|adds)|stub|<[a-z ]+>"` over this file. Remaining hits are prose angle brackets naming a pattern (`<tool> on <ip>:<port>`, `<pattern>` in the standard JVM command, `<file>`), not unwritten code. Every task gives its failing tests, complete production code or an exact replacement block, exact names, exact commands, the gate and an explicit staging list.

**Type consistency (checked against the code at 82084d3 and the 3C-1 plan's blocks):**
- `HostEntity.manualOnly` and `ToolRunEntity.route` are the last fields with defaults (Task 1), so `TestHosts.host`, `ToolRunnerTestSupport`, `DaoTest`, `ToolDaoTest` and every `copy(...)` compile unchanged.
- `ToolStore.startRun(..., route)` has a default body (Task 2), so 3B's `FakeStore` and `object : ToolStore by store` fakes compile unchanged; `ToolRepository` overrides both forms and writes the route in its one insert.
- `ToolTarget.route` and `ToolRunner(routeBind)` are last and defaulted (Task 3); `ToolOptionsJson.encode` writes only `tls` and `serverName`, so a route never leaks into `optionsJson`.
- `RadarCollector`'s new `upsert` sits before the trailing `removed` lambda (Task 10), so the radar call in `HomeViewModel` and 3A's `RadarIdentityGuardTest` (`... Mutex()) { emptySet() }`) compile unchanged.
- `HomeMenu(onRunTool)`, `HomeActions.onRunTool`, `HomeScreen(onOpenTools)`, `NetProbeNavHost(tools)`, `ToolResultsState.route` and `SettingsUi.listenStatus` are all last and defaulted; `HomeContentTest`, `NavTransitionTest`, `ToolsFakes` and 3C-1's `SettingsContentTest` compile unchanged.
- `SettingsState.listenMulticast` is appended after `extGatewayProbe` (Task 9); 3C-1's `SettingsDefaultsTest` is edited for the 15th value (its key-set test would otherwise fail on the new key, which is the point of that test).
- Checked against the code: `HostDao.forNetwork`'s callers are `HostRepository.hostsForNetwork` only, whose callers are `HomeViewModel.hosts`, `quickScanAllBody` and (3C-1) `discoverOn`'s `known`, which Task 2 moves to `allHostsForNetwork`; `RowRules.parseIpv4` and `Ipv4.parse` are the same rule (Task 4 uses `Ipv4`); `ToolApplicability.SMB_PORT` was private and is made public (Task 4); `MainActivity` already sets `foreground` on ON_START and ON_STOP (3B), which `ListenController` reads.

**Deviations, gaps and contradictions for the Director (the spec wins unless noted):**
1. **The listener's description fetch.** Spec 3.1 says the listener sends nothing and does no description fetch, and section 6 lists "UPnP description fetches from the listener" as not in 3C; the controller ruling says it fetches for heard devices inside the known ranges. The plan follows the ruling (Task 10): at most once per IP and 32 per session, never for a LOCATION over 512 characters, through the existing `UpnpFetcher` (2 s, 64 KB, host equal to the packet source, no redirects), bound to Wi-Fi (discovery's own fetch is unbound; this is stricter).
2. **Identity key alone cannot tell two sites with the same addressing apart.** `NetworkSignature.identityKey` is gateway IP and prefix only, so tagging packets with it (spec 3.3) would let a client network on `192.168.1.1/24` match the Director's home row of the same addressing during the window before Home re-resolves. The plan adds, beyond spec 3.2: each session resolves its own network row (gateway MAC, through `networks.ensure`) and writes only while Home's current network is that row (Task 10 rule 1), and sessions restart on `networkEpoch` (Forget, its Undo and its commit), so a re-created row never receives a stale session's writes.
3. **Spec 4.3 step 1 "currentNetwork, else ensure".** A current network left over from the previous env could receive the manual row in the same window; the plan uses `currentNetwork` only when its gateway and prefix equal the env's (Task 7 `networkFor`), else `ensure`.
4. **Spec 4.3 step 4 "in one hosts transaction ... then start".** The runner's start takes its own locks and writes `tool_runs` in its own transaction, so it cannot share the row's; the plan inserts the row in one transaction, starts, then deletes the row only while it is still `manualOnly` with no run (a promotion or a started run is never deleted), and the start-up sweep covers a process killed in between.
5. **"Hidden from exports and the Forget cascade" (the task brief) against the spec.** NetProbe has no inventory export: `ToolExport` exports one run, and spec 4.4 keeps it unchanged, so manual runs export like any run. Spec 7.1 says Forget deletes manual rows with the rest; the plan deletes them (`ManualHostDaoTest.forgetsCascadeRemovesManualRowsAndTheirRuns`) and keeps Forget disabled while a tool holds one. Nothing is hidden from the cascade.
6. **Errors before the first Run tap.** Spec 4.2 says blank fields show no error until the first Run tap; the plan holds every error until then (a half-typed address would otherwise flag at each keystroke), then shows them live. One `takeIf { attempted }` if the Director wants live errors.
7. **`KnownRanges.of` takes host rows, not IP strings**, so the "manual-only hosts contribute nothing" rule (spec 8.1 item 19) lives in the function and is tested there.
8. **Wording nits for the Director:** a manual start with no network shows `ScanController.NO_NETWORK` ("Connect to Wi-Fi or Ethernet to scan"), as spec 4.3 step 6 names; the status line reads "Not listening: connect to Wi-Fi or Ethernet" also when Wi-Fi is up but the network's Forget is pending. One constant each.
9. **The no-binder rule is checked twice.** At the Run tap a known-range target with no Wi-Fi or Ethernet binder is `NoNetwork` before any row exists (spec 4.5); a binder that vanishes between the start and the tool's first socket ends the run FAILED with 3B's "Tool skipped: could not bind to Wi-Fi". Never unbound either way.
10. **The removed-this-session set is process-wide** (`AppContainer.removedWhileListening`), filled by Remove device, cleared at each listening session start and each radar scan start, and emptied of a pair by its Undo; spec 3.4 rule 5 describes a per-session set. Same effect, and it survives the listener restarting on a settings change mid-session only until that restart (the spec's "next listening session").
11. **DnsWire's label limit is opt-in.** The 128-label cap that stops a label flood applies only to the listener; `DeviceInfoTxt` keeps its behaviour unchanged, as spec 3.3 requires.
12. **QA row counts need a SQLite reader on the PC.** Spec 8.4 item 2 counts hidden rows "from the pulled database copy"; the plan uses Python's standard library on a copy under the backup folder (nothing installed), else records the check NOT RUN with the on-screen check.
13. **3B's `DirectorCopyMigration23Test` is not run in 3C-2:** it asserts a schema 2 copy and would fail on the schema 3 copy pushed for `DirectorCopyMigration34Test` (it never touches `netprobe.db`).
14. **QA item 3 contacts the internet** (`1.1.1.1:443`); it is behind an approval gate in Task 12.
15. **The tool runner's network-change stop also ends OTHER-route runs** when the Wi-Fi network changes, even though their traffic may be on a VPN (spec 4.3 keeps the network-change stop untouched). Stated so a stopped VPN run is not reported as a bug.
16. **Pinned at flow level, proved on the device:** "a cancelled brute-force sheet creates no row" (spec 8.1 item 22) is `ManualFlow.firstStep` plus Task 12 item 2's hidden-row count; the view model's Cancel needs an `AppContainer` and is not JVM-tested. "A heard row on a network with no run is undimmed" (8.2 item 3) is pinned with `RowRules.row` and the dim expression `HomeViewModel` uses.
17. **`runIdForNew` is read per store from `networks.byId`**, not from the possibly stale `currentNetwork` entity (spec 3.4 says `lastRunAt ?: 0`).
18. **The 3B device QA is still committing fixes** (F4, in the working tree at plan time: `ToolResultsSheet.kt`, `ToolsUiState.kt`, `DetailViewModel.kt`, the Detail composables, `Calibration.kt`, a new `ui/LiveText.kt`). Task 1 Step 0's `comm` line lists them and stops; the targeted edits this plan makes to `ToolResultsSheet.kt` (Task 6, written to fit either shape), `ToolsUiState.kt` (Task 6) and `DetailViewModel.kt` (Task 10, `removeDevice` only) do not touch F4's lines, and Task 7's per-tick behaviour should follow F4's pattern once it lands.

**Review Focus mapping:**
1. Schema 4 and the Director's data: Task 1 `MigrationSql34Test` (3), `SchemaExportTest` (2 new), `Migration34Test` (2), `DirectorCopyMigration34Test` (1); Task 12 Steps 0 to 4.
2. Hidden rows: Task 2 `ManualHostDaoTest` (9), `HeardRunTest` (3); Task 5 `ManualStarterTest` (10); Task 12 items 1, 2 and 4.
3. Manual route: Task 3 `ManualRouteTest` (6), `ToolRunnerRouteTest` (5); Task 5 `ManualStarterTest`; Task 6 `ToolResultsRouteTest`; Task 12 item 3.
4. Listener writes: Task 8 `MdnsPacketTest` (10), `SsdpNotifyTest` (5); Task 10 `HeardWriterTest` (8), `HeardRepeatsTest` (3), `HeardPacketsTest` (3); Task 11 `ListenControllerTest` (the network change); Task 12 items 5, 6 and 8.
5. Lock lifecycle: Task 11 `ListenControllerTest` (13), `LocksTest` (3), `ListenSocketsTest` (4); Task 12 items 5 and 6.
