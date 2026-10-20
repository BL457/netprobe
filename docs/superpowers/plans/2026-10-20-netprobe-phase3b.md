# NetProbe Phase 3B Implementation Plan: the tools framework, directory brute-force, four companion tools, wordlists, schema 3

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn an open port into an answer to "what is behind it?". Five follow-up tools behind one framework: directory brute-force (selectable wordlists, calibrated not-found filter, adaptive concurrency), HTTP fingerprint, TLS certificate, SMB session and SNMP walk. One `ToolRunner` on `appScope`, one run per host, up to four hosts at once, pausing in the background and surviving a trip to the browser. Runs and findings persist in Room (schema 2 to 3, additive); results feed device identification as hints that never lower an identity; a Wordlists screen imports lists through SAF. Built on Phase 3A, after 3A is merged.

**Architecture:** Same single module and MVVM shape. 3B is schema version 3: two new tables (`tool_runs` with a `port` column, `tool_findings`), a `MIGRATION_2_3` that only adds tables, and `ToolRepository` alongside 3A's repositories. The engine stays pure and fake-driven: a `Tool` interface with its I/O injected, a `ToolRunner` that owns the pause gate, the rate ceiling and the ticker, a raw-socket `HttpClient` and a `CancellableIo.closing` helper that inherits the caller's dispatcher so tool reads never funnel onto the shared 64-thread `Dispatchers.IO`. UI changes stay in the stateless content split (`DetailContent`, new `ui/tools/` and `ui/wordlists/`); every string is a `ToolCopy` / `WordlistCopy` constant or helper with a `ToolCopyTableTest` template. All tool sockets go through `Probe.connect` and `ScanBinding.run`, so binding to Wi-Fi for off-subnet hosts is identical to a port scan.

**Tech Stack:** Unchanged from 3A: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00, navigation-compose 2.9.0, Room 2.7.1 with KSP, kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, JUnit 4, Turbine, AndroidX test, room-testing (already an `androidTestImplementation`). No additions, no version bumps, no new Gradle plugins. `androidx.lifecycle:lifecycle-process` is NOT added; the foreground signal comes from `MainActivity`'s own lifecycle.

**Spec:** `docs/superpowers/specs/2026-10-20-phase3b-followup-tools-design.md` at c79e6aa is binding. Its Gruff review (`docs/superpowers/specs/2026-10-20-phase3b-gruff-review.md`) explains the reasoning; where review and spec differ, the spec wins. The 3A plan (`2026-10-20-netprobe-phase3a.md` at 6b992d4) owns every name this phase consumes, and where the 3B spec and the 3A plan disagree the 3A plan wins (recorded in the Plan Self-Review Notes). The Radar spec and the 3A spec own every token, type style and motion this phase reuses.

**Roles:** The stronger model implements and reviews Task 1 (the schema 3 migration), Task 6 (the `ToolRunner`: pause, resume, INTERRUPTED, the ticker, end states) and Task 10 (the directory brute-force engine: calibration, the not-found filter, adaptive concurrency). Tasks 14 and 15 (the SMB session probe: hand-written MD4 / NTLM / SPNEGO in 14, SMB2, framing and the probe in 15) are built after the other tools and get their own review as the spec directs (15.2.4), but do not need the stronger model. Tasks 17 and 19 edit screens 3A's motion task also edits; each opens with a Step 0 that re-reads those files on the branch. Every other task is standard. The plan has 20 tasks: 19 built and JVM-tested now, and Task 20, the deferred on-device QA.

## Global Constraints

Carried from the 3A plan (unchanged, they still bind):

- Kotlin 2.1.21, AGP 8.10.1, Compose BOM 2025.06.00, Room 2.7.1 KSP with exported schema, targetSdk 30.
- No new dependencies, no version bumps, no new repositories, no Gradle plugins.
- No downloads or installs of anything by any agent or subagent. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director. The bundled wordlists are transcribed from Task 8; the TLS test fixtures are generated locally with the JDK 17 `keytool` (Task 12); nothing is fetched.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- No attribution lines in commits: plain messages, no `Co-Authored-By` or any other trailer.
- No disclaimers, warnings or authorisation prompts in UI copy. The one confirmation dialog is the wordlist delete `AlertDialog` the spec defines (8.6).
- Only the pinned scan bar's pill stops a tool run; Scan / Stop and the tool Run pill are never inside a scroll container.
- Every user-visible string lives in a Copy object (`ToolCopy`, `WordlistCopy`, or 3A's `HomeCopy` / `DetailCopy`) and has a template in a copy table test (`ToolCopyTableTest` for 3B, 3A's `CopyTableTest` for 3A strings); regex templates use doubled backslashes (`"\\d+"`). Tool summaries and finding details are user-visible, so tools read them from `ToolCopy` too.
- Block bodies (`{ ... }`) for every function in which `return` appears; no `return` inside an expression body.
- Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so NetProbe is never uninstalled and the Director's data survives.
- THE PHONE IS NOT AVAILABLE during Tasks 1 to 19. Every task is built and tested on the JVM. Compose UI tests and the migration tests are written in full and must compile (`./gradlew :app:compileDebugAndroidTestKotlin`) but are not run. No task may claim device evidence. Task 20 lists every instrumented run, screenshot and recording; it is executed later, when the Director returns.
- No instrumented test may open, create or delete a database named `DB_NAME` ("netprobe.db"). Migration tests use `MIGRATION_TEST_DB` ("netprobe-migration-test.db") or the pushed copy "netprobe-migration-copy.db"; DAO tests use in-memory databases.
- Building `AppContainer` never touches the database (no `init` block, no eager repository use). The one new exception is `ToolRepository.markInterrupted`, launched on `appScope` when the `toolRunner` lazy is first read (Task 6): it runs off the main thread, and `ToolRunner.start` awaits its `ready` before inserting a run, so a run started in the first moments is never marked INTERRUPTED under its live job. `NetProbeNav` reads `toolRunActive`, so the only instrumented test that reaches the production `toolRunner` is 3A's `HomeScreenTest`, which launches the real activity; Task 20 runs it last, after the in-place migration (plan review G26).
- 3B starts only when 3A is finished: Task 1 Step 0 checks 3A's last task and final review are committed and records the base commit in `docs/superpowers/plans/phase3b-base.txt`, from which Task 20 builds the rollback APK (plan review G17).
- Every commit stages the files its task names and checks `git status --short` before committing; never `git add -A` or `git add .`, because another agent may be working in the same tree (plan review G21).
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`, never a global `gradle`. Never kill python or java processes wholesale.
- Standard commands used by every task:
  - JVM, one class or package: `./gradlew :app:testDebugUnitTest --tests "<pattern>" --console=plain 2>&1 | tail -20`
  - androidTest compile only: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8`
  - Task gate, every task ends with it green: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6`, expected `BUILD SUCCESSFUL`.

New in 3B:

- Schema version 3 with `MIGRATION_2_3`, added after 3A's `MIGRATION_1_2`, and additive only: it creates `tool_runs` and `tool_findings` and their indices with `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`, and alters no existing table. A test proves it runs `execSQL` only and contains no `ALTER` or `DROP`. `tool_runs` carries a `port` column; `tool_findings` is foreign-keyed to `tool_runs` with cascade, and `tool_runs` to `hosts` with cascade.
- History is capped at `KEEP_RUNS_PER_TOOL = 10` runs per host per tool (oldest pruned on insert) and `MAX_FINDINGS = 500` findings per run.
- The three bundled wordlists are authored in this plan as complete lists (Task 8) and committed as plain `res/raw/*.txt` resources, LF line endings, ASCII only, no leading slash, no comments, no blanks, no duplicates, and no entry whose GET can change a device's state (reboot, upgrade, apply, logout, save: plan review G10). Imports are `text/plain` only through `ActivityResultContracts.OpenDocument`; no network fetch of any list.
- All tool I/O runs on `Dispatchers.IO.limitedParallelism(TOOL_THREADS)` with `TOOL_THREADS = 16`; `CancellableIo.closing` inherits the caller's dispatcher and never names `Dispatchers.IO`.
- A per-host token bucket caps every tool at `MAX_REQUESTS_PER_SECOND = 100` (burst 10), a constant and not an option. A token is taken once per request sent: brute-force calls `control.gate()` before the HEAD and again before the GET, so a HEAD-200 catch-all still sends at most 100 requests a second (plan review G3).
- Every companion tool wraps each exchange in `RunControl.excusedOnce`, so a request that fails while the Director is in the browser is retried once after the resume instead of ending a step (spec 4.8, plan review G5).
- Directory brute-force always sends the IP as the Host header; the TLS certificate and HTTP fingerprint tools send the host's known name (mDNS, else reverse DNS) as SNI and Host when one exists, with a retry on the IP if the named handshake fails.

## Review Focus

The five failure modes most likely to bite the Director. Each is pinned to a test in its owning task; the reviewer checks the test exists, fails on the bug, and was not bent to fit the code.

1. **A frozen browser trip turns a live run into a false failure.** Opening a found path sends NetProbe to the background; the cached-apps freezer times out every in-flight read. The run must pause on `ON_STOP`, excuse those failures (not counted, not counted towards the 50 consecutive, no concurrency cut), stop the elapsed clock, and resume on `ON_START`. Pinned: Task 6 `ToolRunnerPauseTest.gate suspends while foreground is false and resumes when true`, `a pause stops the elapsed clock`, `a failure spanning a pause is excused` (driven with `runCurrent`, never `advanceUntilIdle`, plan review G2); Task 3 `ExcusedOnceTest`; Task 10 `DirBruteFlowTest.an excused failure is retried after the resume and never counted`; the companion tools' pause tests in Tasks 11, 12, 13 and 15 (`a ... lost to a pause is retried once ...`).
2. **The not-found filter fails on a nonce page and every path reads as a hit.** Calibration takes 5 random paths per group, a size band per status, a HEAD/GET agreement check and a runaway check; a nonce-varying catch-all is filtered, a HEAD-404/GET-200 server switches to GET only and still finds its paths, and more than 50 alike in the first 200 ends the run. Pinned: Task 10 `DirBruteCalibrationTest.a nonce varying catch all is filtered by the band`, `a head 404 get 200 server switches to get only and finds its paths`, `DirBruteRunawayTest.more than fifty alike in the first two hundred ends the run`.
3. **Tool reads starve discovery of IO threads.** Four brute-forces at concurrency 16 must not hold the whole `Dispatchers.IO` pool: `CancellableIo.closing` inherits the caller's dispatcher and the runner uses one 16-thread view. Pinned: Task 3 `CancellableIoTest.a dispatchers io task still runs while sixteen tool reads are blocked` and `tool reads never run on more than TOOL_THREADS threads`.
4. **Hints keyed by tool, not by (tool, port), silently drop identity evidence.** A fingerprint of port 80 must never hide what a fingerprint of 5000 found, and a STOPPED partial run must never hide a DONE run; tool-triggered writes go through 3A's `setIdentityIfNotWeaker`. Pinned: Task 2 `ToolHintsRuleTest` (all four) and the instrumented `ToolDaoTest.hintsForPicksPerToolAndPort`; Task 7 `DeviceIdentifierToolHintTest`.
5. **The schema 3 migration touches an existing table and loses marks.** `MIGRATION_2_3` must be additive only: new tables and indices, no `ALTER`, and schema 2's rows read back intact. Pinned: Task 1 `ToolMigrationSqlTest` (all three), `SchemaExportTest.version 3 keeps every version 2 table and column unchanged`; instrumented Task 1 `ToolMigrationTest.schema2RowsSurviveAndTheNewTablesWork` and `oneToThreeInOneChain`, run in Task 20.

### Review checklist (every task)

- Every blocking socket call runs inside `CancellableIo.closing` (or `Probe.connect`), so Stop ends within about a second during a read, not only during a connect. No tool names `Dispatchers.IO`. Every socket is opened through `Probe.connect` or bound through `currentBinder()`, so `BindingPropagationTest` (Task 15) keeps holding.
- Every request a tool sends is preceded by its own `control.gate()`: one rate token per HTTP request, never one per path.
- Every test fails on the bug its name states; a test that would pass with the rule removed is rewritten, not kept (plan review G12).
- Every tool takes its I/O as a constructor parameter (a function type or a small interface), so its JVM test fakes it; the only real sockets in unit tests are the loopback `ServerSocket` / `SSLServerSocket` / `DatagramSocket` fakes the spec's test list names.
- Every user-visible string is a `ToolCopy` / `WordlistCopy` member with a `ToolCopyTableTest` template; no string literal in a composable or a tool summary.
- No control that starts or stops a run sits inside a scroll container (the pinned bar, the options-sheet footer, the Wordlists import pill).
- No parser (HTTP, TLS, SMB, SPNEGO, NTLM, BER) ever throws on malformed bytes: it returns null or a Failed result.

## File Structure

```
app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json   generated by KSP and committed (Task 1)
app/src/main/res/raw/
  wordlist_common_short.txt   298 entries (Task 8)
  wordlist_admin_panels.txt   153 entries (Task 8)
  wordlist_api_paths.txt      137 entries (Task 8)
  http_fingerprints.json      framework rules (Task 11)
  identify_rules.json         14 hint rules before `gateway` (Task 7)
app/src/main/java/com/northwalessolutions/netprobe/
  AppContainer.kt          toolRepo (2), hostBusy, appMessages, foreground, toolDispatcher (4), tools map, toolRunner, toolRunActive,
                           reidentifyFromTools (6, 7), wordlistStore (9), dirBruteOptions (16)
  MainActivity.kt          a DefaultLifecycleObserver sets AppContainer.foreground (4)
  data/        Entities.kt, Migrations.kt, NetProbeDb.kt (1), Daos.kt (ToolDao), Repositories.kt (ToolRepository, ToolHintsRule) (1, 2)
  engine/common/   CancellableIo.kt (3), RateBucket.kt (4), HttpClient.kt (5)
  engine/tools/    ToolId.kt, ToolApplicability.kt (3), HostBusyRegistry.kt, AppMessages.kt (4), ToolStore.kt, ToolJson.kt, ToolRunner.kt (6),
                   dirbrute/ (DirBruteOptions.kt, AdaptiveLimiter.kt, NotFoundFilter.kt, Calibration.kt, DirBrutePaths.kt, DirBruteforceTool.kt) (10),
                   HttpFingerprintTool.kt, FingerprintRules.kt (11), TlsCertificateTool.kt, CertSummaries.kt (12),
                   SnmpWalkTool.kt, UdpSnmpExchange.kt (13), smb/ (Md4.kt, Ntlm.kt, Spnego.kt (14); Smb2.kt, SmbTransport.kt, SmbSessionTool.kt (15))
  engine/serviceid/ TlsPeek.kt (details, TlsDetails) (12)
  engine/discovery/ Snmp.kt (GETNEXT, unsigned, exceptions, SnmpVarbind) (13)
  engine/identify/  DeviceIdentity.kt, DeviceIdentifier.kt (toolHint) (7)
  wordlists/   WordlistParser.kt (8), BuiltInList.kt, WordlistStore.kt (9)
  ui/Controls.kt   ScanPill stopDescription (17), BackRow (19)
  ui/Nav.kt        KeepScreenOn includes tool runs (6), the `wordlists` route (19)
  ui/tools/    ToolCopy.kt (3), ToolRunOptions.kt, ToolExport.kt, ToolsUiState.kt, DirBruteOptionsStore.kt (16),
               ToolControls.kt, ToolsBlock.kt (17), DirBruteSheet.kt, ToolResultsSheet.kt (18)
  ui/wordlists/ WordlistCopy.kt (3), WordlistsScreen.kt (19)
  ui/detail/   DetailViewModel.kt (2, 7, 16), DetailComponents.kt, DetailScreen.kt (17), DetailContent.kt (17, 18)
  ui/home/     HomeViewModel.kt, HomeContent.kt, HomeComponents.kt, RowText.kt, RadarCollector.kt (7, 19)
  ui/settings/ SettingsScreen.kt, ToolsSection.kt (19)
app/src/test/java/...            JVM tests mirror the packages above, plus engine/tools/BindingPropagationTest.kt (15) and
                                 engine/tools/smb/NtlmFixtures.kt (14), SmbFixtures.kt (15)
app/src/test/resources/tls/      keytool PKCS12 fixtures (12)
app/src/androidTest/java/...     ToolMigrationTest, DirectorCopyMigration23Test (1), ToolDaoTest (2), ToolsFakes, ToolsBlockTest,
                                 DetailContentTest additions (17), DirBruteSheetTest, ToolResultsSheetTest (18), WordlistsScreenTest,
                                 SettingsToolsTest, HomeContentTest additions (19)
docs/superpowers/plans/phase3b-base.txt   the 3A commit 3B builds on (Task 1 step 0)
docs/qa/phase3b-on-device.md     Task 20 record (written when the Director is back)
```

---

### Task 1: Schema 3: the tool_runs and tool_findings tables, MIGRATION_2_3, exported schema and the migration tests

**Roles:** stronger model (implement and review).

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`, `data/NetProbeDb.kt`, `data/Migrations.kt`, `data/Daos.kt` (a minimal ToolDao stub, replaced in Task 2)
- Generated and committed: `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/ToolMigrationSqlTest.kt`, `SchemaExportTest.kt` (append); `app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolMigrationTest.kt`, `DirectorCopyMigration23Test.kt`
- Create: `docs/superpowers/plans/phase3b-base.txt` (the 3A commit this phase builds on; Step 0)

**Interfaces:**
- Consumes: 3A's `HostEntity`, `DB_NAME`, `MIGRATION_TEST_DB`, `MIGRATION_1_2`, `@Database(version = 2)`, `app/schemas/.../2.json`.
- Produces:
```kotlin
// data/Entities.kt
enum class ToolRunStatus { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }
@Entity(tableName = "tool_runs", ...) data class ToolRunEntity(...)        // id, hostId, toolId, port, startedAt, finishedAt?, status, optionsJson, summary
@Entity(tableName = "tool_findings", ...) data class ToolFindingEntity(...) // id, runId, kind, value, detail?, extra?
// data/Migrations.kt
val MIGRATION_2_3: Migration
// data/NetProbeDb.kt
@Database(version = 3, exportSchema = true) abstract class NetProbeDb      // entities += both; abstract fun tools(): ToolDao
```

- [ ] **Step 0: Confirm 3A is finished before anything is built (plan review G17)**

3B edits code in the shape 3A's last tasks leave it (the motion pass, the notice area, the scan bar glide). Check:
```bash
git log --oneline -30 | grep -iE "phase 3a|3a " | head -20
ls docs/superpowers/plans/ | grep -iE "phase3a.*(final|review)"
git status --short
```
Expected: 3A's last implementation task (its deferred device QA aside) is committed, its final review is recorded under `docs/superpowers/plans/`, and the tree has no uncommitted 3A work. If any of the three is not true, stop and report to the controller; do not start 3B. Then record the base:
```bash
git rev-parse HEAD > docs/superpowers/plans/phase3b-base.txt
cat docs/superpowers/plans/phase3b-base.txt
```
Task 20 builds the rollback APK from this commit.

- [ ] **Step 1: Write the failing JVM tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/ToolMigrationSqlTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** MIGRATION_2_3 against a recording database: new tables and indices only, no ALTER (spec 7.3, Review Focus 5). */
class ToolMigrationSqlTest {
    private fun recorded(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String else fail("migration called ${method.name}, expected execSQL only")
            null
        } as SupportSQLiteDatabase
        MIGRATION_2_3.migrate(db)
        return sql
    }

    @Test fun `versions are 2 to 3`() {
        assertEquals(2, MIGRATION_2_3.startVersion); assertEquals(3, MIGRATION_2_3.endVersion)
    }
    @Test fun `every statement creates a table or index if not exists and nothing is altered`() {
        val sql = recorded()
        assertEquals(4, sql.size)
        for (s in sql) {
            assertTrue("not a CREATE IF NOT EXISTS: $s", s.startsWith("CREATE TABLE IF NOT EXISTS") || s.startsWith("CREATE INDEX IF NOT EXISTS"))
            assertFalse("no existing table may be altered: $s", s.contains("ALTER", ignoreCase = true))
            assertFalse("no DROP in an additive migration: $s", s.contains("DROP", ignoreCase = true))
        }
    }
    @Test fun `the two tables and two indices are created with the exported shapes`() {
        val sql = recorded()
        assertTrue(sql.any { it.contains("CREATE TABLE IF NOT EXISTS `tool_runs`") && it.contains("`port` INTEGER NOT NULL") &&
            it.contains("FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`)") && it.contains("ON DELETE CASCADE") })
        assertTrue(sql.any { it.contains("CREATE TABLE IF NOT EXISTS `tool_findings`") &&
            it.contains("FOREIGN KEY(`runId`) REFERENCES `tool_runs`(`id`)") && it.contains("ON DELETE CASCADE") })
        assertTrue(sql.any { it == "CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`)" })
        assertTrue(sql.any { it == "CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`)" })
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/data/SchemaExportTest.kt`, inside the class (the helpers `root` and `columns` exist from 3A):
```kotlin
    @Test fun `version 3 is exported`() {
        assertEquals(3, root(3)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }
    @Test fun `version 3 keeps every version 2 table and column unchanged`() {
        val v2 = columns(2); val v3 = columns(3)
        for ((table, cols) in v2) {
            val now = v3[table]
            assertNotNull("table $table was dropped", now)
            for ((name, field) in cols) {
                val col = now!![name]
                assertNotNull("$table.$name was dropped", col)
                assertEquals("$table.$name affinity", field["affinity"], col!!["affinity"])
                assertEquals("$table.$name notNull", field["notNull"], col["notNull"])
            }
        }
    }
    @Test fun `version 3 adds exactly tool_runs and tool_findings`() {
        val v2 = columns(2); val v3 = columns(3)
        assertEquals(setOf("tool_runs", "tool_findings"), v3.keys - v2.keys)
        assertEquals(setOf("id", "hostId", "toolId", "port", "startedAt", "finishedAt", "status", "optionsJson", "summary"), v3.getValue("tool_runs").keys)
        assertEquals(setOf("id", "runId", "kind", "value", "detail", "extra"), v3.getValue("tool_findings").keys)
        assertEquals("INTEGER", v3.getValue("tool_runs").getValue("port")["affinity"]!!.jsonPrimitive.content)
        assertTrue("port is NOT NULL", v3.getValue("tool_runs").getValue("port")["notNull"]!!.jsonPrimitive.boolean)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.ToolMigrationSqlTest" --tests "com.northwalessolutions.netprobe.data.SchemaExportTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'MIGRATION_2_3'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`: append (add `import androidx.room.ForeignKey`):
```kotlin
/** A tool run's lifecycle (spec 7.1); Room stores it as TEXT by its name, as HostStatus does. */
enum class ToolRunStatus { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }

/** One follow-up tool run on one port of one host (spec 7.1). [port] is the target port, 161 for the SNMP walk. */
@Entity(tableName = "tool_runs",
    foreignKeys = [ForeignKey(entity = HostEntity::class, parentColumns = ["id"], childColumns = ["hostId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("hostId", "toolId", "port", "startedAt")])
data class ToolRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val toolId: String, val port: Int,
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String,
)

/** One finding of a run (spec 7.1). [kind] is TEXT, parsed leniently; [extra] is a JSON object or null. */
@Entity(tableName = "tool_findings",
    foreignKeys = [ForeignKey(entity = ToolRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")])
data class ToolFindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val kind: String,
    val value: String, val detail: String?, val extra: String?,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt`: append:
```kotlin
/**
 * Schema 2 to 3 (spec 7.3): two new tables and their indices, additive only. No ALTER, no DROP, so no schema-2 row is touched.
 * The createSql strings are copied verbatim from the newly exported 3.json and are the expected values in ToolMigrationSqlTest.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER NOT NULL, " +
            "`toolId` TEXT NOT NULL, `port` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, " +
            "`optionsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, " +
            "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `detail` TEXT, `extra` TEXT, " +
            "FOREIGN KEY(`runId`) REFERENCES `tool_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`)")
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt`: add a minimal `ToolDao` now (replaced wholesale in Task 2), so `NetProbeDb.tools()` compiles and KSP exports the entities:
```kotlin
@Dao interface ToolDao {
    @Insert suspend fun insertRun(r: ToolRunEntity): Long
    @Insert suspend fun insertFinding(f: ToolFindingEntity): Long
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt` (replace the class): bump to version 3, add both entities and the `tools()` accessor, and add `MIGRATION_2_3` to the builder:
```kotlin
@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class, ToolRunEntity::class, ToolFindingEntity::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    abstract fun tools(): ToolDao
    companion object {
        /** No fallbackToDestructiveMigration: the Director's marks and notes must survive (spec 7.3). [name] exists for the migration tests. */
        fun open(context: Context, name: String = DB_NAME): NetProbeDb =
            Room.databaseBuilder(context, NetProbeDb::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
```

Generate and inspect the schema:
```bash
./gradlew :app:kspDebugKotlin --console=plain 2>&1 | tail -3
ls app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/
git status --short app/schemas
```
Expected: `BUILD SUCCESSFUL`; the listing shows `1.json  2.json  3.json`; git shows only `?? .../3.json`. If 1.json or 2.json shows as modified, stop, `git checkout` them and report it. Copy the `createSql` strings from `3.json` and confirm they match `MIGRATION_2_3` and `ToolMigrationSqlTest` character for character.

- [ ] **Step 4: Write the instrumented migration test (compile only, run in Task 20)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolMigrationTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 2 to 3 on a throwaway file; additive migration (spec 7.3, Review Focus 5). Never opens, creates or deletes DB_NAME. */
@RunWith(AndroidJUnit4::class)
class ToolMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema2(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
            "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
            "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm, " +
            "userType, userVendor, userModel, identSoftware) VALUES " +
            "(7, 1, '10.0.3.59', NULL, 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
            "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
        db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
    }

    @Test fun schema2RowsSurviveAndTheNewTablesWork() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 2).use { seedSchema2(it) }
        val v3 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 3, true, MIGRATION_2_3)
        v3.query("SELECT customName, status, note, identVendor FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1))
            assertEquals("QA note NAS", c.getString(2)); assertEquals("Synology", c.getString(3))
        }
        v3.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary) " +
            "VALUES (1, 7, 'dir-bruteforce', 5000, 100, 200, 'DONE', '{}', '7 found in 4614 paths')")
        v3.execSQL("INSERT INTO tool_findings (id, runId, kind, value, detail, extra) VALUES (1, 1, 'PATH', '/admin', '200  1.2 KB', NULL)")
        v3.query("SELECT COUNT(*) FROM tool_findings WHERE runId = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        v3.execSQL("PRAGMA foreign_keys = ON")
        v3.execSQL("DELETE FROM hosts WHERE id = 7")
        v3.query("SELECT COUNT(*) FROM tool_runs").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        v3.query("SELECT COUNT(*) FROM tool_findings").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        v3.close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)
        try { runBlocking { assertNotNull(room.networks().byId(1)) } } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    @Test fun oneToThreeInOneChain() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', NULL, NULL, 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', NULL, NULL, NULL, NULL, 0, '[]', NULL, NULL, NULL, 'INTERESTING', 'keep', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
        }
        val v3 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)
        v3.query("SELECT note, userType, identSoftware FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        v3.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/DirectorCopyMigration23Test.kt` (plan review G7; it runs only when Task 20 step 2 has pushed a copy of the Director's schema 2 database, and is skipped otherwise):
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Director's own data, schema 2 to 3, on a copy. Never opens, creates or deletes DB_NAME; deletes only the copy. */
@RunWith(AndroidJUnit4::class)
class DirectorCopyMigration23Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun rows(query: (String) -> Cursor): List<String> = query(MARKS_SQL).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    private fun count(query: (String) -> Cursor, table: String): Int = query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    @After fun tidy() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_COPY_DB + it).delete() } }

    @Test fun theDirectorsSchema2CopyMigratesWithEveryMarkNoteAndPort() {
        val copy = context.getDatabasePath(MIGRATION_COPY_DB)
        assumeTrue("no pushed copy of the Director's database", copy.exists())
        assertNotEquals(DB_NAME, MIGRATION_COPY_DB)
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        val v2 = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertEquals("the copy must be schema 2 (the 3A build)", 2, v2.version)
        val marksBefore = rows { v2.rawQuery(it, null) }
        val hostsBefore = count({ v2.rawQuery(it, null) }, "hosts"); val portsBefore = count({ v2.rawQuery(it, null) }, "ports")
        v2.close()
        val v3 = helper.runMigrationsAndValidate(MIGRATION_COPY_DB, 3, true, MIGRATION_2_3)
        assertEquals(marksBefore, rows { v3.query(it) })
        assertEquals(hostsBefore, count({ v3.query(it) }, "hosts")); assertEquals(portsBefore, count({ v3.query(it) }, "ports"))
        assertEquals(0, count({ v3.query(it) }, "tool_runs")); assertEquals(0, count({ v3.query(it) }, "tool_findings"))
        v3.close()
        val room = NetProbeDb.open(context, MIGRATION_COPY_DB)     // Room's own validation through the production builder
        try { runBlocking { assertEquals(portsBefore, room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ports").use { it.moveToFirst(); it.getInt(0) }) } }
        finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    private companion object {
        const val MIGRATION_COPY_DB = "netprobe-migration-copy.db"
        const val MARKS_SQL = "SELECT id || '|' || status || '|' || note || '|' || IFNULL(customName, '') || '|' || IFNULL(userType, '') FROM hosts ORDER BY id"
    }
}
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the JVM tests, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ToolMigrationSqlTest` (3) and the new `SchemaExportTest` cases pass with 3A's data tests; `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt \
  app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/3.json \
  app/src/test/java/com/northwalessolutions/netprobe/data/ToolMigrationSqlTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/data/SchemaExportTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolMigrationTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/DirectorCopyMigration23Test.kt \
  docs/superpowers/plans/phase3b-base.txt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Schema 3: tool_runs and tool_findings, MIGRATION_2_3, exported schema and migration tests"
```

---

### Task 2: ToolDao, ToolRepository, history pruning, hintsFor per (tool, port), and the 3A Remove-device tool snapshot

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt` (replace the Task 1 `ToolDao` stub with the full DAO), `data/Repositories.kt` (add `ToolRepository`), `AppContainer.kt` (`toolRepo`), `ui/detail/DetailViewModel.kt` (`removeDevice`: snapshot and restore tool rows)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/ToolHintsRuleTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolDaoTest.kt`

**Interfaces:**
- Consumes: Task 1 entities, 3A's `withTransaction`, `HostRepository`, `PortRepository`, 3A's `removeDevice` and its `PendingUndo` (built in 3A Task 14).
- Produces:
```kotlin
@Dao interface ToolDao { ... }                                   // spec 7.2, replacing the Task 1 stub
class ToolRepository(private val db: NetProbeDb) {
    val ready: kotlinx.coroutines.Deferred<Unit>
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long   // insert + prune, one transaction
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long
    suspend fun markInterrupted(summaryOf: (found: Int) -> String)   // caller supplies the string (ToolSummaries, Task 5); completes ready
    fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>>
    fun run(id: Long): Flow<ToolRunEntity?>
    fun findings(runId: Long): Flow<List<ToolFindingEntity>>
    suspend fun findingsOnce(runId: Long): List<ToolFindingEntity>
    suspend fun findingCount(runId: Long): Int
    suspend fun hintsFor(hostId: Long): List<String>                 // one run per (tool, port) by the 4.7 rule
    suspend fun setOptions(runId: Long, json: String); suspend fun hostsWithRuns(): Set<Long>
    suspend fun snapshot(hostIds: List<Long>): String                // TEMP tables, returns a token
    suspend fun restore(token: String); suspend fun dropSnapshot(token: String)
    companion object { const val KEEP_RUNS_PER_TOOL = 10 }
}
object ToolHintsRule { fun pick(finished: List<ToolRunEntity>): List<Long> }   // pure: run ids to read hints from
AppContainer.toolRepo: ToolRepository
```

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/northwalessolutions/netprobe/data/ToolHintsRuleTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import org.junit.Assert.*
import org.junit.Test

/** spec 4.7, Review Focus 4: one run per (tool, port), newest DONE preferred, else newest STOPPED or INTERRUPTED; never FAILED or RUNNING. */
class ToolHintsRuleTest {
    private var seq = 0L
    private fun run(tool: String, port: Int, status: ToolRunStatus, startedAt: Long) =
        ToolRunEntity(id = ++seq, hostId = 1, toolId = tool, port = port, startedAt = startedAt, finishedAt = startedAt + 1, status = status, optionsJson = "{}", summary = "")

    @Test fun `a done run on 5000 is not hidden by a later empty run on 80`() {
        val a = run("http-fingerprint", 5000, ToolRunStatus.DONE, 10)
        val b = run("http-fingerprint", 80, ToolRunStatus.DONE, 20)
        assertEquals(setOf(a.id, b.id), ToolHintsRule.pick(listOf(b, a)).toSet())
    }
    @Test fun `a newer stopped run never hides an older done run on the same port`() {
        val done = run("http-fingerprint", 5000, ToolRunStatus.DONE, 10)
        val stopped = run("http-fingerprint", 5000, ToolRunStatus.STOPPED, 20)
        assertEquals(listOf(done.id), ToolHintsRule.pick(listOf(stopped, done)))
    }
    @Test fun `with no done run the newest stopped or interrupted wins`() {
        val old = run("snmp-walk", 161, ToolRunStatus.INTERRUPTED, 10)
        val new = run("snmp-walk", 161, ToolRunStatus.STOPPED, 20)
        assertEquals(listOf(new.id), ToolHintsRule.pick(listOf(new, old)))
    }
    @Test fun `each tool and port is its own group`() {
        val tls443 = run("tls-certificate", 443, ToolRunStatus.DONE, 10)
        val tls8443 = run("tls-certificate", 8443, ToolRunStatus.DONE, 20)
        assertEquals(setOf(tls443.id, tls8443.id), ToolHintsRule.pick(listOf(tls8443, tls443)).toSet())
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.ToolHintsRuleTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ToolHintsRule'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt`: replace the Task 1 `ToolDao` stub with the full DAO (spec 7.2):
```kotlin
@Dao interface ToolDao {
    @Insert suspend fun insertRun(r: ToolRunEntity): Long
    @Query("UPDATE tool_runs SET finishedAt = :at, status = :status, summary = :summary WHERE id = :id")
    suspend fun finish(id: Long, at: Long?, status: ToolRunStatus, summary: String)
    @Query("DELETE FROM tool_runs WHERE hostId = :hostId AND toolId = :toolId AND id NOT IN " +
        "(SELECT id FROM tool_runs WHERE hostId = :hostId AND toolId = :toolId ORDER BY startedAt DESC, id DESC LIMIT :keep)")
    suspend fun prune(hostId: Long, toolId: String, keep: Int)
    @Query("SELECT * FROM tool_runs WHERE status = 'RUNNING'") suspend fun runningOnce(): List<ToolRunEntity>
    @Insert suspend fun insertFinding(f: ToolFindingEntity): Long
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId ORDER BY startedAt DESC, id DESC") fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>>
    @Query("SELECT * FROM tool_runs WHERE id = :id") fun run(id: Long): Flow<ToolRunEntity?>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") fun findings(runId: Long): Flow<List<ToolFindingEntity>>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") suspend fun findingsOnce(runId: Long): List<ToolFindingEntity>
    @Query("SELECT COUNT(*) FROM tool_findings WHERE runId = :runId AND kind != 'HINT'") suspend fun findingCount(runId: Long): Int
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId AND status IN ('DONE','STOPPED','INTERRUPTED') ORDER BY startedAt DESC, id DESC")
    suspend fun finishedRunsOnce(hostId: Long): List<ToolRunEntity>
    @Query("SELECT value FROM tool_findings WHERE kind = 'HINT' AND runId IN (:runIds)") suspend fun hintsOf(runIds: List<Long>): List<String>
    @Query("UPDATE tool_runs SET optionsJson = :json WHERE id = :id") suspend fun setOptions(id: Long, json: String)
    @Query("SELECT DISTINCT hostId FROM tool_runs") suspend fun hostsWithRuns(): List<Long>
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt`: add (imports `androidx.room.withTransaction`, `kotlinx.coroutines.CompletableDeferred`, `kotlinx.coroutines.Deferred`):
```kotlin
/** Pure (tool, port) selection for hints (spec 4.7, Review Focus 4): newest DONE per group, else the newest STOPPED or INTERRUPTED. */
object ToolHintsRule {
    fun pick(finished: List<ToolRunEntity>): List<Long> {
        // [finished] is already newest-first (startedAt DESC, id DESC).
        val byGroup = finished.groupBy { it.toolId to it.port }
        return byGroup.values.mapNotNull { runs -> (runs.firstOrNull { it.status == ToolRunStatus.DONE } ?: runs.firstOrNull())?.id }
    }
}

class ToolRepository(private val db: NetProbeDb) {
    private val dao get() = db.tools()
    private val _ready = CompletableDeferred<Unit>()
    /** Completed by [markInterrupted]; ToolRunner.start awaits it so a run started at launch is never marked INTERRUPTED. */
    val ready: Deferred<Unit> = _ready

    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long = db.withTransaction {
        val id = dao.insertRun(ToolRunEntity(hostId = hostId, toolId = toolId, port = port, startedAt = startedAt,
            finishedAt = null, status = ToolRunStatus.RUNNING, optionsJson = optionsJson, summary = ""))
        dao.prune(hostId, toolId, KEEP_RUNS_PER_TOOL)
        id
    }
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String) = dao.finish(runId, finishedAt, status, summary)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long =
        dao.insertFinding(ToolFindingEntity(runId = runId, kind = kind, value = value, detail = detail, extra = extra))
    /** Process death left a RUNNING row: mark each INTERRUPTED with its finding count, in one transaction, then complete [ready] (spec 4.4 rule 5). */
    suspend fun markInterrupted(summaryOf: (found: Int) -> String) {
        try {
            db.withTransaction {
                for (r in dao.runningOnce()) dao.finish(r.id, null, ToolRunStatus.INTERRUPTED, summaryOf(dao.findingCount(r.id)))
            }
        } finally { _ready.complete(Unit) }   // a failure here must never leave ToolRunner.start waiting forever
    }
    fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>> = dao.runsForHost(hostId)
    fun run(id: Long): Flow<ToolRunEntity?> = dao.run(id)
    fun findings(runId: Long): Flow<List<ToolFindingEntity>> = dao.findings(runId)
    suspend fun findingsOnce(runId: Long): List<ToolFindingEntity> = dao.findingsOnce(runId)
    suspend fun findingCount(runId: Long): Int = dao.findingCount(runId)
    /** One UPDATE of a run's optionsJson, used when a tool records a value it learns mid-run (methodUsed, spec 7.1). */
    suspend fun setOptions(runId: Long, json: String) = dao.setOptions(runId, json)
    /** Hosts with any tool run: the radar asks hintsFor only for these, so an emission adds no query for the rest (plan review G13). */
    suspend fun hostsWithRuns(): Set<Long> = dao.hostsWithRuns().toSet()
    suspend fun hintsFor(hostId: Long): List<String> {
        val ids = ToolHintsRule.pick(dao.finishedRunsOnce(hostId))
        return if (ids.isEmpty()) emptyList() else dao.hintsOf(ids)
    }

    /**
     * Remove device snapshots a host's tool rows into SQLite TEMP tables before the cascade drops them (spec 7.4), so Undo can bring
     * them back. TEMP tables live on Room's single writer connection and die with the process, matching 3A's memory-only Undo lifetime.
     * [token] is digits only, so the host id list and the table names can never be injected.
     */
    suspend fun snapshot(hostIds: List<Long>): String = db.withTransaction {
        val token = tokenSeq.incrementAndGet().toString()
        val inList = hostIds.joinToString(",") { it.toString() }.ifEmpty { "NULL" }
        exec("CREATE TEMP TABLE undo_runs_$token AS SELECT * FROM tool_runs WHERE hostId IN ($inList)")
        exec("CREATE TEMP TABLE undo_findings_$token AS SELECT * FROM tool_findings WHERE runId IN (SELECT id FROM undo_runs_$token)")
        token
    }
    suspend fun restore(token: String) = db.withTransaction {
        exec("INSERT INTO tool_runs SELECT * FROM undo_runs_$token")
        exec("INSERT INTO tool_findings SELECT * FROM undo_findings_$token")
        drop(token)
    }
    suspend fun dropSnapshot(token: String) = db.withTransaction { drop(token) }
    private fun drop(token: String) { exec("DROP TABLE IF EXISTS undo_findings_$token"); exec("DROP TABLE IF EXISTS undo_runs_$token") }
    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    companion object { const val KEEP_RUNS_PER_TOOL = 10; private val tokenSeq = java.util.concurrent.atomic.AtomicLong(0) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add with the other `by lazy` repositories:
```kotlin
    val toolRepo by lazy { ToolRepository(db) }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: an additive edit to the committed `removeDevice` (branch commit aeb3be3), which already marks `removedHostIds` before stopping the scans, pairs the radar skip on `radarScanActive` (3A review M2), and clears both marks in a `finally` inside `hostWriteLock` (3A review M3). Keep every one of those lines exactly; add only the three marked lines. Before the edit (current code):
```kotlin
            val snap = c.hostWriteLock.withLock {
                c.removedHostIds.update { it + hostId }
                // Any running radar, even one still resolving its network (review M2): the pair is keyed by network, so another network's scan ignores it.
                if (c.radarScanActive.value) c.removedDuringScan.update { RemovedHosts.added(it, h.networkId, h.ip) }
                c.hosts.snapshot(hostId)?.also { c.hosts.delete(it) }
            } ?: return@launch
            c.undo.post(PendingUndo(HomeCopy.removed(name),
                ...
                restore = {
                    c.hostWriteLock.withLock {
                        try { c.hosts.restore(snap) }
                        finally {
                            c.removedHostIds.update { it - hostId }
                            c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
                        }
                    }
                },
                commit = {}))
```
After the edit:
```kotlin
            val token = c.toolRepo.snapshot(listOf(hostId))     // NEW (3B 7.4): the host's tool rows into TEMP tables before the cascade drops them
            val snap = c.hostWriteLock.withLock {
                c.removedHostIds.update { it + hostId }
                // Any running radar, even one still resolving its network (review M2): the pair is keyed by network, so another network's scan ignores it.
                if (c.radarScanActive.value) c.removedDuringScan.update { RemovedHosts.added(it, h.networkId, h.ip) }
                c.hosts.snapshot(hostId)?.also { c.hosts.delete(it) }
            } ?: run { c.toolRepo.dropSnapshot(token); return@launch }   // NEW: nothing was deleted, so the snapshot is dropped
            c.undo.post(PendingUndo(HomeCopy.removed(name),
                // The marks are cleared in finally (the Task 13 pattern), so a failed restore never leaves the host skipped by every later scan,
                // and inside the lock (review M3), so no radar emission sees the restored row while its ip is still marked and skips its update.
                // update {} is an atomic read-modify-write that touches only this host's id and pair, so a second Remove's marks are kept.
                restore = {
                    c.hostWriteLock.withLock {
                        try {
                            c.hosts.restore(snap)
                            c.toolRepo.restore(token)                    // NEW: runs and findings back with their original ids, after the host row exists
                        } finally {
                            c.removedHostIds.update { it - hostId }
                            c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) }
                        }
                    }
                },
                commit = { c.toolRepo.dropSnapshot(token) }))           // CHANGED from commit = {}: a lapsed Undo drops the TEMP tables
```
The `...` in the "before" block stands for the three comment lines shown in full in the "after" block; nothing else in `removeDevice` changes. The snapshot is taken before `hostWriteLock` because it is its own transaction on Room's writer connection, and nothing can write this host's tool rows meanwhile: Remove is disabled while a tool holds the host (Task 16 guards the view model, Task 17 the menu).

- [ ] **Step 4: DAO tests (instrumented, compile only, run in Task 20)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolDaoTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class ToolDaoTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var ports: PortRepository
    private lateinit var tools: ToolRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); ports = PortRepository(db); tools = ToolRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private suspend fun host(ip: String) = hosts.upsertDiscovered(1, DiscoveredHost(ip(ip)), runId = 1)
    private suspend fun doneRun(hostId: Long, tool: String, port: Int, at: Long, hints: List<String> = emptyList(), shown: Int = 0): Long {
        val id = tools.startRun(hostId, tool, port, at, "{}")
        repeat(shown) { tools.addFinding(id, "PATH", "/p$it", null, null) }
        hints.forEach { tools.addFinding(id, "HINT", it, null, null) }
        tools.finish(id, at + 1, ToolRunStatus.DONE, "done")
        return id
    }

    @Test fun insertAndPruneKeepsTheNewestTenPerHostAndTool() = runBlocking {
        val h = host("10.0.3.59")
        val ids = (1..13L).map { tools.startRun(h, "dir-bruteforce", 5000, it, "{}").also { id -> tools.finish(id, it + 1, ToolRunStatus.DONE, "r$it") } }
        // Another tool and another port are untouched by pruning this (tool, port).
        tools.startRun(h, "http-fingerprint", 80, 100, "{}")
        val kept = tools.runsForHost(h).first()
        assertEquals(10, kept.count { it.toolId == "dir-bruteforce" })
        assertFalse(kept.any { it.id == ids[0] || it.id == ids[2] })   // the three oldest pruned
        assertEquals(1, kept.count { it.toolId == "http-fingerprint" })
    }
    @Test fun findingsExcludeHintsAndTheCountIgnoresHints() = runBlocking {
        val h = host("10.0.3.60"); val id = doneRun(h, "dir-bruteforce", 5000, 1, hints = listOf("http.path=/admin"), shown = 3)
        assertEquals(3, tools.findings(id).first().size); assertEquals(3, tools.findingCount(id))
        assertTrue(tools.findings(id).first().none { it.kind == "HINT" })
    }
    @Test fun deletingAHostCascadesToRunsAndFindings() = runBlocking {
        val h = host("10.0.3.61"); doneRun(h, "dir-bruteforce", 5000, 1, shown = 2)
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        db.openHelper.writableDatabase.execSQL("DELETE FROM hosts WHERE id = $h")
        assertEquals(emptyList<ToolRunEntity>(), tools.runsForHost(h).first())
    }
    @Test fun upsertDiscoveredLeavesToolRunsIntact() = runBlocking {   // the REPLACE guard (spec 7.2)
        val h = host("10.0.3.62"); val id = doneRun(h, "dir-bruteforce", 5000, 1, shown = 1)
        hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.62"), mdnsName = "again"), runId = 2)
        assertTrue(tools.runsForHost(h).first().any { it.id == id })
    }
    @Test fun markInterruptedStampsEveryRunningRow() = runBlocking {
        val h = host("10.0.3.63"); val id = tools.startRun(h, "dir-bruteforce", 5000, 1, "{}"); tools.addFinding(id, "PATH", "/a", null, null)
        tools.markInterrupted { n -> "Interrupted, $n found" }
        val r = tools.run(id).first()!!
        assertEquals(ToolRunStatus.INTERRUPTED, r.status); assertNull(r.finishedAt); assertEquals("Interrupted, 1 found", r.summary)
        assertTrue(tools.ready.isCompleted)
    }
    @Test fun hintsForPicksPerToolAndPort() = runBlocking {   // Review Focus 4
        val h = host("10.0.3.64")
        doneRun(h, "http-fingerprint", 5000, 10, hints = listOf("http.framework=Synology DSM"))
        doneRun(h, "http-fingerprint", 80, 20, hints = emptyList())   // newer, empty; must not hide 5000
        val stoppedId = tools.startRun(h, "tls-certificate", 443, 30, "{}"); tools.addFinding(stoppedId, "HINT", "tls.subject=x", null, null)
        tools.finish(stoppedId, 31, ToolRunStatus.STOPPED, "stopped")
        doneRun(h, "tls-certificate", 443, 15, hints = listOf("tls.subject=done"))   // older DONE wins over newer STOPPED
        val hints = tools.hintsFor(h)
        assertTrue("Synology DSM" in hints.map { it.substringAfter("=") })
        assertTrue("done" in hints.map { it.substringAfter("=") }); assertFalse("x" in hints.map { it.substringAfter("=") })
    }
    @Test fun setOptionsRewritesOneRowAndHostsWithRunsListsOnlyHostsThatHaveRuns() = runBlocking {
        val h = host("10.0.3.66"); host("10.0.3.67")
        val id = tools.startRun(h, "dir-bruteforce", 5000, 1, "{}")
        tools.setOptions(id, "{\"methodUsed\":\"GET_ONLY\"}")
        assertEquals("{\"methodUsed\":\"GET_ONLY\"}", tools.run(id).first()!!.optionsJson)
        assertEquals(setOf(h), tools.hostsWithRuns())
    }
    @Test fun snapshotSurvivesSeparateTransactionsAndRestoresIds() = runBlocking {
        val h = host("10.0.3.65"); val id = doneRun(h, "dir-bruteforce", 5000, 1, hints = listOf("http.path=/admin"), shown = 2)
        val token = tools.snapshot(listOf(h))
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        hosts.delete(hosts.snapshot(h)!!)                              // cascade drops the tool rows
        assertEquals(emptyList<ToolRunEntity>(), tools.runsForHost(h).first())
        // Re-insert the host under its id (as 3A's restore does) so the foreign key holds, then restore the tool rows.
        db.openHelper.writableDatabase.execSQL("INSERT INTO hosts (id, networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource) " +
            "VALUES ($h, 1, '10.0.3.65', 'UNKNOWN', 0, '[]', 'UNMARKED', '', 0, 0, 0, 0, 0)")
        tools.restore(token)
        val runs = tools.runsForHost(h).first()
        assertEquals(1, runs.size); assertEquals(id, runs.first().id); assertEquals(2, tools.findingCount(id))
        assertEquals(listOf("http.path=/admin"), tools.hintsFor(h))
    }
}
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the test to see it pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ToolHintsRuleTest` (4) passes with 3A's data tests; `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt \
  app/src/test/java/com/northwalessolutions/netprobe/data/ToolHintsRuleTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/data/ToolDaoTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "ToolDao and ToolRepository: history pruning, hintsFor per tool and port, Remove-device tool snapshot"
```

---

### Task 3: The tool framework interfaces, ToolApplicability, CancellableIo, and the full ToolCopy and WordlistCopy string sets

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt`, `engine/tools/ToolApplicability.kt`, `engine/common/CancellableIo.kt`, `ui/tools/ToolCopy.kt`, `ui/wordlists/WordlistCopy.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicabilityTest.kt`, `engine/tools/ExcusedOnceTest.kt`, `engine/common/CancellableIoTest.kt`, `ui/tools/ToolCopyTableTest.kt`

**Interfaces:**
- Consumes: 3A's `PortUris.guess`, `ServiceIdentifier.HTTP`, `ServiceIdentifier.TLS`, `ServiceInfo`, `HostEntity`, `HomeCopy.devices`; Task 1's `ToolRunStatus`.
- Produces:
```kotlin
const val TOOL_THREADS = 16
enum class ToolId(val key: String) { DIR_BRUTEFORCE("dir-bruteforce"), HTTP_FINGERPRINT("http-fingerprint"), TLS_CERTIFICATE("tls-certificate"), SMB_SESSION("smb-session"), SNMP_WALK("snmp-walk"); companion object { fun byKey(key: String): ToolId? } }
enum class FindingKind { PATH, STATUS, HEADER, COOKIE, TITLE, GENERATOR, ROBOTS, SITEMAP, SECURITY_TXT, ERROR_PAGE, FRAMEWORK, CERT_SUBJECT, CERT_ISSUER, CERT_SAN, CERT_VALIDITY, CERT_KEY, CERT_SIGNATURE, CERT_FINGERPRINT, CERT_CHAIN, TLS_SESSION, FLAG, SMB_DIALECT, SMB_SIGNING, SMB_SESSION, SMB_HOST, SNMP_SYSTEM, SNMP_INTERFACE, SNMP_ADDRESS, HINT, OTHER; companion object { fun parse(s: String): FindingKind } }
data class ToolTarget(val hostId: Long, val ip: Inet4Address, val port: Int, val tls: Boolean, val serverName: String?)
interface RunControl { suspend fun gate(); val pauseEpoch: Int; fun excused(startEpoch: Int): Boolean }
interface ToolProgress { fun update(done: Int, total: Int?, found: Int, errors: Int = 0); suspend fun finding(f: Finding); suspend fun option(key: String, value: String) {} }
suspend fun <T> RunControl.excusedOnce(failed: (T) -> Boolean, block: suspend () -> T): T
data class Finding(val kind: FindingKind, val value: String, val detail: String? = null, val url: String? = null, val extra: Map<String, String> = emptyMap())
data class ToolResult(val status: ToolRunStatus, val summary: String)
interface Tool { val id: ToolId; val name: String; fun applies(port: Int, service: ServiceInfo): Boolean; fun appliesToHost(host: HostEntity): Boolean = false; suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult }
sealed interface ToolOptions { object None : ToolOptions }      // Task 10 adds DirBrute
object ToolApplicability { fun webScheme(port: Int, service: ServiceInfo): String?; fun appliesTo(tool: ToolId, port: Int, service: ServiceInfo): Boolean
    fun toolsFor(port: Int, service: ServiceInfo): List<ToolId>; fun targetTls(tool: ToolId, port: Int, service: ServiceInfo): Boolean; fun snmp(host: HostEntity): Boolean
    fun serverName(host: HostEntity): String?; const val SNMP_PORT = 161 }
object CancellableIo { suspend fun <T> closing(socket: Closeable, block: () -> T): T }
object ToolCopy { ... }        // every section 9 tool string, listed in full below
object WordlistCopy { ... }    // every section 9 wordlist and Settings string
```
`ToolOptions` ships here with the `None` case only; Task 10 adds `data class DirBrute(...)`. Every later task reads its user-visible strings from `ToolCopy` / `WordlistCopy`, which is why they land in this task.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicabilityTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** spec 4.2 and 4.9: which tools a port gets, the target tls flag, the SNMP gate and the name sent. */
class ToolApplicabilityTest {
    private fun svc(name: String, c: Confidence = Confidence.CONFIRMED) = ServiceInfo(name, confidence = c)
    private val unknown = svc("Unknown", Confidence.PORT_TABLE)
    private fun host(snmp: String? = null, mdns: String? = null, rdns: String? = null) = HostEntity(networkId = 1, ip = "10.0.3.1", mac = null,
        reverseDnsName = rdns, mdnsName = mdns, netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0,
        identReasons = emptyList(), snmpSysDescr = snmp, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "",
        isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)

    @Test fun `web tools apply to http and https on any port`() {
        assertEquals("http", ToolApplicability.webScheme(81, unknown))                       // "HTTP alt" on 81, in ServiceIdentifier.HTTP
        assertEquals("http", ToolApplicability.webScheme(5000, unknown))                     // "Web / Synology", a PortUris port
        assertEquals("https", ToolApplicability.webScheme(5001, svc("HTTPS")))               // confirmed banner
        assertEquals("https", ToolApplicability.webScheme(5001, unknown))                    // no banner: ServiceIdentifier.TLS fallback
        assertEquals("http", ToolApplicability.webScheme(7123, svc("HTTP")))                 // an unknown port confirmed HTTP by 3A's ladder
        assertNull(ToolApplicability.webScheme(22, svc("SSH")))
        assertTrue(ToolApplicability.appliesTo(ToolId.DIR_BRUTEFORCE, 81, unknown))
        assertTrue(ToolApplicability.appliesTo(ToolId.HTTP_FINGERPRINT, 7123, svc("HTTP")))
    }
    @Test fun `tls certificate applies to https and the bare tls ports`() {
        assertTrue(ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, 5001, svc("HTTPS")))
        for (p in listOf(465, 636, 993, 995, 5986, 8883)) assertTrue("$p", ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, p, unknown))
        assertFalse(ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, 5000, svc("HTTP")))
    }
    @Test fun `smb session is port 445 only, 139 excluded`() {
        assertTrue(ToolApplicability.appliesTo(ToolId.SMB_SESSION, 445, svc("SMB")))
        assertFalse(ToolApplicability.appliesTo(ToolId.SMB_SESSION, 139, svc("NetBIOS")))
    }
    @Test fun `snmp walk is per host only, gated on a discovery answer`() {
        assertFalse(ToolApplicability.appliesTo(ToolId.SNMP_WALK, 161, unknown))
        assertFalse(ToolApplicability.snmp(host(snmp = null))); assertTrue(ToolApplicability.snmp(host(snmp = "Linux 5.10")))
    }
    @Test fun `toolsFor lists in ToolId order and target tls matches the scheme`() {
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT), ToolApplicability.toolsFor(5000, svc("HTTP")))
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE), ToolApplicability.toolsFor(5001, svc("HTTPS")))
        assertEquals(listOf(ToolId.SMB_SESSION), ToolApplicability.toolsFor(445, svc("SMB")))
        assertTrue(ToolApplicability.targetTls(ToolId.DIR_BRUTEFORCE, 5001, svc("HTTPS"))); assertFalse(ToolApplicability.targetTls(ToolId.DIR_BRUTEFORCE, 5000, svc("HTTP")))
        assertTrue(ToolApplicability.targetTls(ToolId.TLS_CERTIFICATE, 993, unknown)); assertFalse(ToolApplicability.targetTls(ToolId.SMB_SESSION, 445, svc("SMB")))
    }
    @Test fun `server name is mdns then reverse dns, trailing dot removed`() {
        assertEquals("chnas.local", ToolApplicability.serverName(host(mdns = "chnas.local.", rdns = "chnas.lan")))
        assertEquals("chnas.lan", ToolApplicability.serverName(host(rdns = "chnas.lan")))
        assertNull(ToolApplicability.serverName(host()))
    }
    @Test fun `finding kind parses leniently`() {
        assertEquals(FindingKind.PATH, FindingKind.parse("PATH")); assertEquals(FindingKind.OTHER, FindingKind.parse("WAT"))
        assertEquals(ToolId.SNMP_WALK, ToolId.byKey("snmp-walk")); assertNull(ToolId.byKey("nope"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ExcusedOnceTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 4.8: companion tools retry once a failure that spans a pause; nothing else is retried. */
class ExcusedOnceTest {
    private class Control : RunControl { var epoch = 0; var gates = 0
        override suspend fun gate() { gates++ }; override val pauseEpoch get() = epoch; override fun excused(startEpoch: Int) = startEpoch != epoch }

    @Test fun `a failure that spans a pause is retried once after the gate`() = runTest {
        val c = Control(); var calls = 0
        val r = c.excusedOnce({ it == null }) { calls++; if (calls == 1) { c.epoch++; null } else "ok" }
        assertEquals("ok", r); assertEquals(2, calls); assertEquals(1, c.gates)
    }
    @Test fun `a plain failure and a success are not retried`() = runTest {
        val c = Control(); var calls = 0
        assertNull(c.excusedOnce({ it == null }) { calls++; null as String? }); assertEquals(1, calls)
        assertEquals("ok", c.excusedOnce({ it == null }) { calls++; c.epoch++; "ok" }); assertEquals(2, calls)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/CancellableIoTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import com.northwalessolutions.netprobe.engine.tools.TOOL_THREADS
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Review Focus 3: closing() inherits the caller's dispatcher, so tool reads never spill onto the whole Dispatchers.IO pool (spec 4.6, Gruff M7). */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellableIoTest {
    private val tool = Dispatchers.IO.limitedParallelism(TOOL_THREADS)

    @Test fun `a dispatchers io task still runs while sixteen tool reads are blocked`() = runBlocking {
        val parked = CountDownLatch(TOOL_THREADS); val release = CountDownLatch(1)
        val jobs = (1..TOOL_THREADS * 4).map { launch(tool) { CancellableIo.closing(Closeable { }) { parked.countDown(); release.await() } } }
        assertTrue("sixteen tool reads did not block", parked.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val done = withContext(Dispatchers.IO) { 42 }
        assertEquals(42, done)
        assertTrue("an IO task waited ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 100)
        release.countDown(); jobs.forEach { it.join() }
    }
    @Test fun `tool reads never run on more than TOOL_THREADS threads`() = runBlocking {
        val inFlight = AtomicInteger(0); val max = AtomicInteger(0); val release = CountDownLatch(1); val started = CountDownLatch(TOOL_THREADS)
        val jobs = (1..TOOL_THREADS * 4).map {
            launch(tool) { CancellableIo.closing(Closeable { }) {
                val n = inFlight.incrementAndGet(); max.updateAndGet { m -> maxOf(m, n) }; started.countDown(); release.await(); inFlight.decrementAndGet()
            } }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS)); Thread.sleep(200)
        release.countDown(); jobs.forEach { it.join() }
        assertEquals(TOOL_THREADS, max.get())
    }
    @Test fun `the block runs on the caller's dispatcher`() = runBlocking {
        val single = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "caller-thread") }.asCoroutineDispatcher()
        try {
            val name = withContext(single) { CancellableIo.closing(Closeable { }) { Thread.currentThread().name } }
            assertEquals("caller-thread", name)
        } finally { single.close() }
    }
    @Test fun `cancellation closes the socket at once`() = runBlocking {
        val closed = CountDownLatch(1); val entered = CountDownLatch(1)
        val job = launch(tool) { CancellableIo.closing(Closeable { closed.countDown() }) { entered.countDown(); Thread.sleep(10_000) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("socket not closed on cancel", closed.await(1, TimeUnit.SECONDS))
        job.join()
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy
import org.junit.Assert.*
import org.junit.Test

/**
 * spec 9 held as regex templates. Each test drives a matrix of inputs through every ToolCopy and WordlistCopy helper and asserts the
 * output matches a template, so wording outside the table fails here. No template contains an em-dash, a disclaimer or a prompt.
 */
class ToolCopyTableTest {
    private val date = "3 Jan 2026"
    private val ts = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"
    private val templates: List<Regex> = listOf(
        // names, Tools row and blocks
        "Directory brute-force", "HTTP fingerprint", "TLS certificate", "SMB session", "SNMP walk",
        "Tools", "\\d+ available", "Last run .+: .+ on (\\d+|UDP 161), .+", "No tools run yet", "Tools, \\d+ available, (expand|collapse)",
        "Last run .+ on (\\d+|UDP 161): .+", "Not run yet", "Results", "Show .+ results", "\\d+", "UDP 161", "Run .+ on port (\\d+|UDP 161)", "Run tool",
        // busy, Home, Quick scan all
        "A tool is already running on this device", "Wait for the port scan to finish", "Four tools are already running, stop one first",
        "A tool is running on this device", "running", "port scan running", "tool running",
        "Quick scan finished, \\d+ devices? could not be scanned", "Quick scan finished, \\d+ devices? skipped: a tool is running",
        "Quick scan finished, \\d+ devices? could not be scanned, \\d+ skipped: a tool is running",
        // pinned bar, progress, end message, statuses
        ".+ on (\\d+|UDP 161)", "\\d+ of \\d+, \\d+ found(, \\d+ errors)?  \\d+ s", "\\d+ done, \\d+ found  \\d+ s", "starting", "Stop tool run",
        ".+ finished: .+", "Running", "Done", "Stopped", "Failed", "Interrupted",
        // summaries
        "Network changed, run stopped", "Tool skipped: could not bind to Wi-Fi", "Failed: .+", "Interrupted, \\d+ found",
        "Stopped after \\d+ of \\d+, \\d+ found", "Stopped after \\d+ steps?, \\d+ found",
        "\\d+ found in \\d+ paths?(, \\d+ errors?)?", "Stopped at 500 findings", "Stopped: the server answers every path alike",
        "The server did not answer the first request", "The server stopped answering after \\d+ paths?",
        ".+, \\d+ findings?", "No HTTP response on this port", ".+, expires .+(, self-signed)?", "No TLS handshake on this port",
        "SMB [0-9.]+, signing (required|not required), anonymous (allowed|refused), guest (allowed|refused)",
        "No SMB2 dialect in common", "No SMB response on this port", ".+, \\d+ interfaces?", "No SNMP answer to public",
        // options sheet and errors
        "Wordlist", "\\d+ entr(y|ies)", "Manage wordlists", "Extensions", "php, html, txt", "Each word is also tried with these endings",
        "Method", "HEAD then GET", "GET only", "Concurrency", "Timeout per request", "\\d+ s", "Report status codes", "200, 204, 301, 302, 307, 401, 403",
        "Filter not-found pages", "Checks random paths first and hides answers that match them", "\\d+ paths?", "Run",
        "Use letters and digits only, for example php, html", "At most 8 extensions", "Use codes from 100 to 599, for example 200, 301, 403",
        "Add at least one status code", "Wordlist not found, using common-short",
        // results sheet
        ".+  \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+", "(Running|Done|Stopped|Failed|Interrupted), .+", "(Done|Stopped|Failed|Interrupted), .+, as .+",
        "Run \\d+ of \\d+", "Older run", "Newer run", "Open with\\.\\.\\.", "Copy", "Share", "Copied",
        "Running, findings appear as they are found", "No findings", "Open .+ with", "Copy .+",
        // export
        "NetProbe .+ \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+", "NetProbe .+", "Target: .+", "Name sent: .+", "Started: $ts", "Finished: ($ts|not finished)",
        "Status: (Running|Done|Stopped|Failed|Interrupted)", "Summary: .*",
        "Options: wordlist .+ \\(\\d+ entr(y|ies)\\), extensions .+, (HEAD then GET|GET only|GET only \\(HEAD and GET disagree\\)), concurrency \\d+, timeout \\d+ s, codes [0-9 ]+, not-found filter (on|off)",
        // brute-force finding detail and sizes
        "\\d{3}(  (\\d+ B|\\d+\\.\\d KB|64 KB\\+))?(  to .+)?",
        // fingerprint details
        "GET /", "GET /, to .+", "GET .+", "Cookie(, Secure)?(, HttpOnly)?(, SameSite=.+)?", "Page title", "Generator meta tag", "\\d+ rules?",
        "Disallowed in robots\\.txt", "Allowed in robots\\.txt", "\\d+ URLs?", "Contact: .+", "Present", "Error page signature", "Matched .+: .*",
        // TLS details
        "Subject", "Issuer", "Alternative name \\((DNS|IP)\\)", ".+ to .+", "Valid", "Expired", "Not yet valid", "Expires in \\d+ days?",
        "(RSA|EC|DSA) \\d+ bit", "Ed25519", "Ed448", "Public key", "Signature algorithm", "SHA-256 fingerprint", "Chain certificate \\d+ of \\d+",
        "Protocol and cipher", "Certificate", "Self-signed", "RSA key under 2048 bit", "Signed with SHA-1",
        // SMB details
        "Dialect", "SMB (2\\.0\\.2|2\\.1|3\\.0|3\\.0\\.2)", "Signing", "Required", "Enabled, not required", "Session", "Anonymous (allowed|refused)",
        "Guest (allowed|refused|mapped to guest)", "NetBIOS name", "NetBIOS domain", "DNS name", "DNS domain", "OS build \\(NTLM\\)",
        // SNMP details
        "sysDescr", "sysObjectID", "Uptime", "sysContact", "sysName", "sysLocation", "\\d+ days? \\d+ h", "\\d+ h \\d+ min",
        "((up|down|testing|unknown|dormant|notPresent|lowerLayerDown|ethernetCsmacd|softwareLoopback|propVirtual|ieee80211|gigabitEthernet|tunnel|l2vlan|ieee8023adLag|bridge|type \\d+|\\d+ (M|G)bit/s|([0-9a-f]{2}:){5}[0-9a-f]{2})(, )?)*",
        "Interface address", "\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+",
        // Wordlists screen, import results, Settings
        "Back", "Wordlists", "Import wordlist", "Importing", "Built in", "Imported", "\\d+ entr(y|ies), built in", "\\d+ entr(y|ies), imported $date",
        "Delete", "Delete .+\\?", "Cancel", "No imported wordlists yet", "Delete .+", "Imported .+, \\d+ entr(y|ies)( \\(\\d+ skipped\\))?",
        "That file is too large \\(limit 10 MB\\)", "That file has more than 200000 entries", "That file has no entries", "Could not read that file",
        "Imported list", "3 built in, \\d+ imported",
    ).map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the tool copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `no string carries an em-dash`() {
        val all = everyString()
        all.forEach { assertFalse("em-dash in '$it'", it.contains('\u2014')) }
        assertTrue(all.size > 150)
    }
    @Test fun `every helper over its input matrix is in the table`() { everyString().forEach { inTable(it) } }
    @Test fun `exact wording of the key strings`() {
        assertEquals("1234 of 4614, 7 found  12 s", ToolCopy.progress(1234, 4614, 7, 0, 12))
        assertEquals("1234 of 4614, 7 found, 3 errors  12 s", ToolCopy.progress(1234, 4614, 7, 3, 12))
        assertEquals("12 done, 3 found  2 s", ToolCopy.progress(12, null, 3, 0, 2))
        assertEquals("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths",
            ToolCopy.rowHistory("2 hours ago", ToolCopy.DIR_BRUTEFORCE, "5000", ToolCopy.bruteDone(7, 4614, 0)))
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", ToolCopy.smbSummary("SMB 3.0.2", true, true, false))
        assertEquals("200  1.2 KB", ToolCopy.pathDetail(200, ToolCopy.size(1229, false), null))
        assertEquals("301  0 B  to /admin/", ToolCopy.pathDetail(301, ToolCopy.size(0, false), "/admin/"))
        assertEquals("12 days 4 h", ToolCopy.uptime((12L * 86_400 + 4L * 3_600) * 100))
        assertEquals("up, ethernetCsmacd, 1 Gbit/s, 00:11:22:33:44:55", ToolCopy.interfaceDetail(1, 6, 1_000_000_000L, "00:11:22:33:44:55"))
        assertEquals("Cookie, Secure, HttpOnly", ToolCopy.cookieDetail(secure = true, httpOnly = true, sameSite = null))
    }

    private fun everyString(): List<String> = buildList {
        ToolId.entries.forEach { add(ToolCopy.toolName(it)) }
        for (n in listOf(1, 3)) { add(ToolCopy.available(n)); add(ToolCopy.rowDescription(n, false)); add(ToolCopy.rowDescription(n, true)) }
        for (snmp in listOf(false, true)) {
            val p = ToolCopy.portLabel(5000, snmp)
            add(p); add(ToolCopy.rowHistory("just now", ToolCopy.SNMP_WALK, p, "gw, 3 interfaces")); add(ToolCopy.blockHistory("yesterday", p, "Done"))
            add(ToolCopy.chipDescription(ToolCopy.TLS_CERTIFICATE, p)); add(ToolCopy.pinnedLabel(ToolCopy.SNMP_WALK, p))
        }
        addAll(listOf(ToolCopy.TOOLS, ToolCopy.NO_TOOLS_RUN, ToolCopy.NOT_RUN_YET, ToolCopy.RESULTS, ToolCopy.resultsDescription(ToolCopy.HTTP_FINGERPRINT),
            ToolCopy.RUN_TOOL, ToolCopy.BUSY_TOOL, ToolCopy.BUSY_PORT_SCAN, ToolCopy.BUSY_TOO_MANY, ToolCopy.SCAN_REFUSED, ToolCopy.RUNNING_MARKER,
            ToolCopy.rowSuffix(true), ToolCopy.rowSuffix(false), ToolCopy.STARTING, ToolCopy.STOP_DESCRIPTION, ToolCopy.finished(ToolCopy.SMB_SESSION, "x"),
            ToolCopy.NETWORK_CHANGED, ToolCopy.BIND_FAILED, ToolCopy.failed("Connection reset"), ToolCopy.interrupted(4)))
        for (f in 0..2) for (s in 0..2) ToolCopy.quickEnd(f, s)?.let(::add)
        for (total in listOf<Int?>(null, 4614)) for (e in listOf(0, 3)) add(ToolCopy.progress(1234, total, 7, e, 12))
        ToolRunStatus.entries.forEach { add(ToolCopy.statusLabel(it)) }
        for (t in listOf<Int?>(null, 4614)) for (d in listOf(1, 12)) add(ToolCopy.stopped(d, t, 7))
        for (e in listOf(0, 1, 3)) for (p in listOf(1, 4614)) add(ToolCopy.bruteDone(7, p, e))
        addAll(listOf(ToolCopy.BRUTE_CAP, ToolCopy.BRUTE_RUNAWAY, ToolCopy.BRUTE_NO_ANSWER, ToolCopy.bruteStoppedAnswering(1), ToolCopy.bruteStoppedAnswering(51)))
        for (n in listOf(1, 6)) { add(ToolCopy.fingerprintSummary("Pi-hole", n)); add(ToolCopy.fingerprintSummary(null, n)) }
        addAll(listOf(ToolCopy.FINGERPRINT_NO_RESPONSE, ToolCopy.tlsSummary("unifi.local", date, false), ToolCopy.tlsSummary("unifi.local", date, true), ToolCopy.TLS_NO_HANDSHAKE))
        for (a in listOf(true, false)) for (b in listOf(true, false)) for (c in listOf(true, false)) add(ToolCopy.smbSummary("SMB 2.1", a, b, c))
        addAll(listOf(ToolCopy.SMB_NO_DIALECT, ToolCopy.SMB_NO_RESPONSE, ToolCopy.snmpSummary("gw", 1), ToolCopy.snmpSummary("gw", 5), ToolCopy.SNMP_NO_ANSWER))
        addAll(listOf(ToolCopy.WORDLIST, ToolCopy.entries(1), ToolCopy.entries(312), ToolCopy.MANAGE_WORDLISTS, ToolCopy.EXTENSIONS, ToolCopy.EXTENSIONS_PLACEHOLDER,
            ToolCopy.EXTENSIONS_SUPPORT, ToolCopy.METHOD, ToolCopy.HEAD_THEN_GET, ToolCopy.GET_ONLY, ToolCopy.CONCURRENCY, ToolCopy.TIMEOUT, ToolCopy.seconds(3),
            ToolCopy.REPORT_CODES, ToolCopy.DEFAULT_CODES_TEXT, ToolCopy.FILTER, ToolCopy.FILTER_SUPPORT, ToolCopy.paths(1), ToolCopy.paths(312), ToolCopy.RUN,
            ToolCopy.ERR_EXT_CHARS, ToolCopy.ERR_EXT_TOO_MANY, ToolCopy.ERR_CODE_RANGE, ToolCopy.ERR_CODE_EMPTY, ToolCopy.WORDLIST_FALLBACK))
        addAll(listOf(ToolCopy.resultsTitle(ToolCopy.DIR_BRUTEFORCE, "10.0.3.10", 5000), ToolCopy.resultsStatus("Done", "2 hours ago", null),
            ToolCopy.resultsStatus("Done", "2 hours ago", "chnas.local"), ToolCopy.resultsRunning("12 of 300, 2 found  3 s"), ToolCopy.runOf(1, 3),
            ToolCopy.OLDER_RUN, ToolCopy.NEWER_RUN, ToolCopy.OPEN_WITH, ToolCopy.COPY, ToolCopy.SHARE, ToolCopy.COPIED, ToolCopy.EMPTY_RUNNING, ToolCopy.NO_FINDINGS,
            ToolCopy.openDescription("http://10.0.3.10:5000/admin"), ToolCopy.copyDescription("/admin")))
        addAll(listOf(ToolCopy.exportSubject(ToolCopy.TLS_CERTIFICATE, "10.0.3.10", 443), ToolCopy.exportTitle(ToolCopy.TLS_CERTIFICATE),
            ToolCopy.exportTarget("10.0.3.10:443"), ToolCopy.exportNameSent("chnas.local"), ToolCopy.exportStarted("2026-10-20 14:03:11"),
            ToolCopy.exportFinished("2026-10-20 14:04:02"), ToolCopy.exportFinished(null), ToolCopy.exportStatus("Done"), ToolCopy.exportSummary("7 found in 4614 paths")))
        for (ext in listOf(emptyList(), listOf("php", "html"))) for (m in listOf(Triple(false, false, true), Triple(true, false, false), Triple(true, true, true)))
            add(ToolCopy.exportOptions(ToolCopy.optionsLine("common-short", 312, ext, m.first, m.second, 8, 3, listOf(200, 204, 301), m.third)))
        for (size in listOf(ToolCopy.size(0, false), ToolCopy.size(1023, false), ToolCopy.size(1229, false), ToolCopy.size(null, true), null))
            for (loc in listOf(null, "/login")) add(ToolCopy.pathDetail(302, size, loc))
        addAll(listOf(ToolCopy.GET_ROOT, ToolCopy.getRootTo("https://10.0.3.9:5001/"), ToolCopy.getUrl("https://10.0.3.9:5001/"),
            ToolCopy.cookieDetail(false, false, null), ToolCopy.cookieDetail(true, true, "Lax"), ToolCopy.PAGE_TITLE, ToolCopy.GENERATOR_META,
            ToolCopy.rules(1), ToolCopy.rules(7), ToolCopy.DISALLOWED, ToolCopy.ALLOWED, ToolCopy.urls(1), ToolCopy.urls(40), ToolCopy.contact("mailto:a@b.c"),
            ToolCopy.PRESENT, ToolCopy.ERROR_SIGNATURE, ToolCopy.matched("title", "Synology DiskStation")))
        addAll(listOf(ToolCopy.SUBJECT, ToolCopy.ISSUER, ToolCopy.SAN_DNS, ToolCopy.SAN_IP, ToolCopy.validityRange("3 Jan 2026", "3 Jan 2027"), ToolCopy.VALID,
            ToolCopy.EXPIRED, ToolCopy.NOT_YET_VALID, ToolCopy.expiresIn(1), ToolCopy.expiresIn(12), ToolCopy.keyRsa(2048), ToolCopy.keyEc(256), ToolCopy.keyDsa(2048),
            ToolCopy.ED25519, ToolCopy.ED448, ToolCopy.PUBLIC_KEY, ToolCopy.SIGNATURE_ALGORITHM, ToolCopy.SHA256_FINGERPRINT, ToolCopy.chainCertificate(1, 2),
            ToolCopy.PROTOCOL_AND_CIPHER, ToolCopy.CERTIFICATE, ToolCopy.SELF_SIGNED, ToolCopy.WEAK_RSA, ToolCopy.SHA1_SIGNED))
        addAll(listOf(ToolCopy.DIALECT, ToolCopy.SIGNING, ToolCopy.REQUIRED, ToolCopy.ENABLED_NOT_REQUIRED, ToolCopy.SESSION, ToolCopy.ANONYMOUS_ALLOWED,
            ToolCopy.ANONYMOUS_REFUSED, ToolCopy.GUEST_ALLOWED, ToolCopy.GUEST_MAPPED, ToolCopy.GUEST_REFUSED, ToolCopy.NETBIOS_NAME, ToolCopy.NETBIOS_DOMAIN,
            ToolCopy.DNS_NAME, ToolCopy.DNS_DOMAIN, ToolCopy.OS_BUILD))
        listOf(0x0202, 0x0210, 0x0300, 0x0302).forEach { add(ToolCopy.dialectName(it)!!) }
        addAll(listOf(ToolCopy.SYS_DESCR, ToolCopy.SYS_OBJECT_ID, ToolCopy.UPTIME, ToolCopy.SYS_CONTACT, ToolCopy.SYS_NAME, ToolCopy.SYS_LOCATION,
            ToolCopy.uptime(100L * 86_400 * 2 + 360_000), ToolCopy.uptime(100L * 86_400 + 360_000), ToolCopy.uptime(1_152_000), ToolCopy.INTERFACE_ADDRESS,
            ToolCopy.address("10.0.3.1", 24)))
        for (st in listOf<Long?>(null, 1, 7)) for (ty in listOf<Long?>(null, 6, 999)) for (sp in listOf<Long?>(null, 0, 100_000_000, 10_000_000_000))
            add(ToolCopy.interfaceDetail(st, ty, sp, if (st == 1L) "00:11:22:33:44:55" else null))
        addAll(listOf(WordlistCopy.BACK, WordlistCopy.TITLE, WordlistCopy.IMPORT, WordlistCopy.IMPORTING, WordlistCopy.BUILT_IN, WordlistCopy.IMPORTED,
            WordlistCopy.entriesBuiltIn(1), WordlistCopy.entriesBuiltIn(312), WordlistCopy.entriesImported(4614, date), WordlistCopy.DELETE,
            WordlistCopy.deletePrompt("my list"), WordlistCopy.CANCEL, WordlistCopy.NO_IMPORTED, WordlistCopy.deleteDescription("my list"),
            WordlistCopy.imported("my list", 1, 0), WordlistCopy.imported("my list", 4614, 3), WordlistCopy.TOO_LARGE, WordlistCopy.TOO_MANY,
            WordlistCopy.EMPTY_FILE, WordlistCopy.UNREADABLE, WordlistCopy.DEFAULT_NAME, WordlistCopy.SETTINGS_SECTION, WordlistCopy.SETTINGS_ROW,
            WordlistCopy.settingsSupport(0), WordlistCopy.settingsSupport(2)))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.ToolApplicabilityTest" --tests "com.northwalessolutions.netprobe.engine.common.CancellableIoTest" --tests "com.northwalessolutions.netprobe.ui.tools.ToolCopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ToolApplicability'`, `'CancellableIo'`, `'TOOL_THREADS'`, `'ToolCopy'`, `'WordlistCopy'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import java.net.Inet4Address

/** All tool coroutines and blocking calls run on Dispatchers.IO.limitedParallelism(TOOL_THREADS) (spec 4.4). */
const val TOOL_THREADS = 16

enum class ToolId(val key: String) {
    DIR_BRUTEFORCE("dir-bruteforce"), HTTP_FINGERPRINT("http-fingerprint"),
    TLS_CERTIFICATE("tls-certificate"), SMB_SESSION("smb-session"), SNMP_WALK("snmp-walk");
    companion object { fun byKey(key: String): ToolId? = entries.firstOrNull { it.key == key } }
}

/** Finding kinds (spec 7.1); [parse] falls back to OTHER so a kind removed in a later version never crashes a read. */
enum class FindingKind {
    PATH, STATUS, HEADER, COOKIE, TITLE, GENERATOR, ROBOTS, SITEMAP, SECURITY_TXT, ERROR_PAGE, FRAMEWORK,
    CERT_SUBJECT, CERT_ISSUER, CERT_SAN, CERT_VALIDITY, CERT_KEY, CERT_SIGNATURE, CERT_FINGERPRINT, CERT_CHAIN, TLS_SESSION, FLAG,
    SMB_DIALECT, SMB_SIGNING, SMB_SESSION, SMB_HOST, SNMP_SYSTEM, SNMP_INTERFACE, SNMP_ADDRESS, HINT, OTHER;
    companion object { fun parse(s: String): FindingKind = entries.firstOrNull { it.name == s } ?: OTHER }
}

data class ToolTarget(
    val hostId: Long, val ip: Inet4Address, val port: Int,
    val tls: Boolean,                // HTTPS for the web tools; always true for TLS certificate; false otherwise
    val serverName: String?,         // known name for SNI and Host (4.9); null when none or for brute-force
)

/** Run-wide controls owned by the runner: the pause gate, the rate ceiling and the pause epoch (spec 4.8, 5.3). */
interface RunControl {
    suspend fun gate()                       // suspends while paused, then takes one rate token for this host
    val pauseEpoch: Int                      // incremented on every pause
    fun excused(startEpoch: Int): Boolean    // true when a pause began after startEpoch, or the run is paused now
}

interface ToolProgress {
    fun update(done: Int, total: Int?, found: Int, errors: Int = 0)   // total null when unknown (SNMP walk)
    suspend fun finding(f: Finding)                                   // persisted immediately (spec 4.6)
    /** Records a value learned mid-run into the run's optionsJson, for example methodUsed after calibration (spec 7.1). */
    suspend fun option(key: String, value: String) {}
}

/**
 * spec 4.8, the companion-tool rule: an exchange that failed while a pause began (or is under way) is retried once after the resume.
 * [failed] says whether a result is a failure; a success, or a failure with no pause involved, is returned as it is.
 */
suspend fun <T> RunControl.excusedOnce(failed: (T) -> Boolean, block: suspend () -> T): T {
    val epoch = pauseEpoch
    val first = block()
    if (!failed(first) || !excused(epoch)) return first
    gate()
    return block()
}

data class Finding(val kind: FindingKind, val value: String, val detail: String? = null, val url: String? = null, val extra: Map<String, String> = emptyMap())
data class ToolResult(val status: ToolRunStatus, val summary: String)   // DONE or FAILED; STOPPED is set by the runner

sealed interface ToolOptions { object None : ToolOptions }

interface Tool {
    val id: ToolId
    val name: String
    fun applies(port: Int, service: ServiceInfo): Boolean
    fun appliesToHost(host: HostEntity): Boolean = false
    /** Suspends until done. Cancellation of the calling coroutine is the only stop. Calls control.gate() before every request. */
    suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicability.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.actions.PortUris
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

/** Which tools a port gets, from the stored ServiceInfo and the port number (spec 4.2); it reuses 3A's PortUris.guess. */
object ToolApplicability {
    const val SNMP_PORT = 161
    private const val SMB_PORT = 445
    /** TLS certificate also applies to these non-web TLS ports (spec 4.2). */
    private val TLS_EXTRA = setOf(465, 636, 993, 995, 5986, 8883)
    private val DNS_NAME = Regex("^[A-Za-z0-9.-]+$")

    fun webScheme(port: Int, service: ServiceInfo): String? {
        val guess = PortUris.guess(port, service)
        return when {
            guess == "http" || guess == "https" -> guess
            port in ServiceIdentifier.TLS -> "https"
            port in ServiceIdentifier.HTTP -> "http"
            else -> null
        }
    }
    fun appliesTo(tool: ToolId, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> webScheme(port, service) != null
        ToolId.TLS_CERTIFICATE -> webScheme(port, service) == "https" || port in TLS_EXTRA
        ToolId.SMB_SESSION -> port == SMB_PORT
        ToolId.SNMP_WALK -> false
    }
    /** Applicable per-port tools in ToolId order (SNMP walk is per host, so never here). */
    fun toolsFor(port: Int, service: ServiceInfo): List<ToolId> = ToolId.entries.filter { it != ToolId.SNMP_WALK && appliesTo(it, port, service) }
    fun targetTls(tool: ToolId, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolId.TLS_CERTIFICATE -> true
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> webScheme(port, service) == "https"
        ToolId.SMB_SESSION, ToolId.SNMP_WALK -> false
    }
    fun snmp(host: HostEntity): Boolean = host.snmpSysDescr != null
    /** mDNS name, else reverse DNS, trailing dot removed; null when absent or not a DNS-shaped name (spec 4.9). */
    fun serverName(host: HostEntity): String? =
        listOfNotNull(host.mdnsName, host.reverseDnsName).map { it.trim().trimEnd('.') }.firstOrNull { it.isNotEmpty() && DNS_NAME.matches(it) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/CancellableIo.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking socket call with runInterruptible and NO dispatcher argument, so it inherits the caller's dispatcher as
 * Probe.connect does (spec 4.6, Gruff M7). A watcher coroutine closes the socket when the caller is cancelled, so a blocked read
 * ends at once, not at its timeout. Putting Dispatchers.IO here would funnel every tool read onto the shared 64-thread pool.
 * (The watcher uses Dispatchers.Unconfined only so the close runs on the cancelling thread; it does no I/O.)
 */
object CancellableIo {
    suspend fun <T> closing(socket: Closeable, block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished.get()) runCatching { socket.close() } }
        }
        try { runInterruptible { block() } } finally { finished.set(true); watcher.cancel() }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import java.util.Locale

/**
 * Every 3B tool string (spec 9): sentence case, active voice, no disclaimers, no thousands separators. Tools read their summaries and
 * finding details from here too, because both are shown to the Director. Templates live in ToolCopyTableTest.
 */
object ToolCopy {
    // Tool names
    const val DIR_BRUTEFORCE = "Directory brute-force"
    const val HTTP_FINGERPRINT = "HTTP fingerprint"
    const val TLS_CERTIFICATE = "TLS certificate"
    const val SMB_SESSION = "SMB session"
    const val SNMP_WALK = "SNMP walk"
    fun toolName(id: ToolId): String = when (id) {
        ToolId.DIR_BRUTEFORCE -> DIR_BRUTEFORCE; ToolId.HTTP_FINGERPRINT -> HTTP_FINGERPRINT; ToolId.TLS_CERTIFICATE -> TLS_CERTIFICATE
        ToolId.SMB_SESSION -> SMB_SESSION; ToolId.SNMP_WALK -> SNMP_WALK
    }

    // Tools row and blocks (8.1)
    const val TOOLS = "Tools"
    const val NO_TOOLS_RUN = "No tools run yet"
    const val NOT_RUN_YET = "Not run yet"
    const val RESULTS = "Results"
    const val RUN_TOOL = "Run tool"
    fun available(n: Int) = "$n available"
    fun portLabel(port: Int, snmp: Boolean) = if (snmp) "UDP 161" else "$port"
    fun rowHistory(relative: String, tool: String, portLabel: String, summary: String) = "Last run $relative: $tool on $portLabel, $summary"
    fun rowDescription(n: Int, expanded: Boolean) = "Tools, $n available, ${if (expanded) "collapse" else "expand"}"
    fun blockHistory(relative: String, portLabel: String, summary: String) = "Last run $relative on $portLabel: $summary"
    fun resultsDescription(tool: String) = "Show $tool results"
    fun chipDescription(tool: String, portLabel: String) = "Run $tool on port $portLabel"

    // Busy, Home, Quick scan all (4.3, 8.8)
    const val BUSY_TOOL = "A tool is already running on this device"
    const val BUSY_PORT_SCAN = "Wait for the port scan to finish"
    const val BUSY_TOO_MANY = "Four tools are already running, stop one first"
    const val SCAN_REFUSED = "A tool is running on this device"
    const val RUNNING_MARKER = "running"
    /** The Home row description item, joined with ", " by RowText.description. */
    fun rowSuffix(portScan: Boolean) = if (portScan) "port scan running" else "tool running"
    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
    /** The Quick scan all end message for failed and tool-skipped hosts; null when both are zero. */
    fun quickEnd(failed: Int, skipped: Int): String? = when {
        failed > 0 && skipped > 0 -> "Quick scan finished, ${devices(failed)} could not be scanned, $skipped skipped: a tool is running"
        failed > 0 -> "Quick scan finished, ${devices(failed)} could not be scanned"
        skipped > 0 -> "Quick scan finished, ${devices(skipped)} skipped: a tool is running"
        else -> null
    }

    // Pinned bar, progress, end message, statuses (8.2)
    const val STARTING = "starting"
    const val STOP_DESCRIPTION = "Stop tool run"
    fun pinnedLabel(tool: String, portLabel: String) = "$tool on $portLabel"
    fun progress(done: Int, total: Int?, found: Int, errors: Int, seconds: Long): String =
        if (total != null) "$done of $total, $found found${if (errors > 0) ", $errors errors" else ""}  $seconds s"
        else "$done done, $found found  $seconds s"
    fun finished(tool: String, summary: String) = "$tool finished: $summary"
    fun statusLabel(s: ToolRunStatus): String = when (s) {
        ToolRunStatus.RUNNING -> "Running"; ToolRunStatus.DONE -> "Done"; ToolRunStatus.STOPPED -> "Stopped"
        ToolRunStatus.FAILED -> "Failed"; ToolRunStatus.INTERRUPTED -> "Interrupted"
    }

    // Common summaries
    const val NETWORK_CHANGED = "Network changed, run stopped"
    const val BIND_FAILED = "Tool skipped: could not bind to Wi-Fi"
    fun failed(reason: String) = "Failed: $reason"
    fun interrupted(found: Int) = "Interrupted, $found found"
    fun stopped(done: Int, total: Int?, found: Int): String =
        if (total != null) "Stopped after $done of $total, $found found" else "Stopped after ${if (done == 1) "1 step" else "$done steps"}, $found found"

    // Brute-force summaries (5.5)
    const val BRUTE_CAP = "Stopped at 500 findings"
    const val BRUTE_RUNAWAY = "Stopped: the server answers every path alike"
    const val BRUTE_NO_ANSWER = "The server did not answer the first request"
    fun bruteDone(found: Int, paths: Int, errors: Int) = "$found found in ${paths(paths)}${if (errors > 0) ", ${if (errors == 1) "1 error" else "$errors errors"}" else ""}"
    fun bruteStoppedAnswering(paths: Int) = "The server stopped answering after ${paths(paths)}"

    // Companion summaries (6.1 to 6.4)
    const val FINGERPRINT_NO_RESPONSE = "No HTTP response on this port"
    fun fingerprintSummary(name: String?, findings: Int) = "${name ?: "No product named"}, ${if (findings == 1) "1 finding" else "$findings findings"}"
    const val TLS_NO_HANDSHAKE = "No TLS handshake on this port"
    fun tlsSummary(name: String, date: String, selfSigned: Boolean) = "$name, expires $date${if (selfSigned) ", self-signed" else ""}"
    const val SMB_NO_DIALECT = "No SMB2 dialect in common"
    const val SMB_NO_RESPONSE = "No SMB response on this port"
    fun smbSummary(dialect: String, signingRequired: Boolean, anonymousAllowed: Boolean, guestAllowed: Boolean) =
        "$dialect, signing ${if (signingRequired) "required" else "not required"}, anonymous ${if (anonymousAllowed) "allowed" else "refused"}, guest ${if (guestAllowed) "allowed" else "refused"}"
    const val SNMP_NO_ANSWER = "No SNMP answer to public"
    fun snmpSummary(name: String, interfaces: Int) = "$name, ${if (interfaces == 1) "1 interface" else "$interfaces interfaces"}"

    // Options sheet (8.4) and its errors
    const val WORDLIST = "Wordlist"
    const val MANAGE_WORDLISTS = "Manage wordlists"
    const val EXTENSIONS = "Extensions"
    const val EXTENSIONS_PLACEHOLDER = "php, html, txt"
    const val EXTENSIONS_SUPPORT = "Each word is also tried with these endings"
    const val METHOD = "Method"
    const val HEAD_THEN_GET = "HEAD then GET"
    const val GET_ONLY = "GET only"
    const val CONCURRENCY = "Concurrency"
    const val TIMEOUT = "Timeout per request"
    const val REPORT_CODES = "Report status codes"
    const val DEFAULT_CODES_TEXT = "200, 204, 301, 302, 307, 401, 403"
    const val FILTER = "Filter not-found pages"
    const val FILTER_SUPPORT = "Checks random paths first and hides answers that match them"
    const val RUN = "Run"
    const val ERR_EXT_CHARS = "Use letters and digits only, for example php, html"
    const val ERR_EXT_TOO_MANY = "At most 8 extensions"
    const val ERR_CODE_RANGE = "Use codes from 100 to 599, for example 200, 301, 403"
    const val ERR_CODE_EMPTY = "Add at least one status code"
    const val WORDLIST_FALLBACK = "Wordlist not found, using common-short"
    fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun seconds(n: Int) = "$n s"
    fun paths(n: Int) = if (n == 1) "1 path" else "$n paths"

    // Results sheet (8.5)
    const val OLDER_RUN = "Older run"
    const val NEWER_RUN = "Newer run"
    const val OPEN_WITH = "Open with..."
    const val COPY = "Copy"
    const val SHARE = "Share"
    const val COPIED = "Copied"
    const val EMPTY_RUNNING = "Running, findings appear as they are found"
    const val NO_FINDINGS = "No findings"
    fun resultsTitle(tool: String, ip: String, port: Int) = "$tool  $ip:$port"
    fun resultsStatus(label: String, relative: String, nameSent: String?) = "$label, $relative${nameSent?.let { ", as $it" } ?: ""}"
    fun resultsRunning(progress: String) = "Running, $progress"
    fun runOf(n: Int, m: Int) = "Run $n of $m"
    fun openDescription(url: String) = "Open $url with"
    fun copyDescription(value: String) = "Copy $value"

    // Export (7.5)
    fun exportSubject(tool: String, ip: String, port: Int) = "NetProbe $tool $ip:$port"
    fun exportTitle(tool: String) = "NetProbe $tool"
    fun exportTarget(target: String) = "Target: $target"
    fun exportNameSent(name: String) = "Name sent: $name"
    fun exportStarted(at: String) = "Started: $at"
    fun exportFinished(at: String?) = "Finished: ${at ?: "not finished"}"
    fun exportStatus(label: String) = "Status: $label"
    fun exportSummary(summary: String) = "Summary: $summary"
    fun exportOptions(line: String) = "Options: $line"
    fun optionsLine(wordlist: String, entries: Int, extensions: List<String>, getOnly: Boolean, switchedToGetOnly: Boolean,
                    concurrency: Int, timeoutS: Int, codes: List<Int>, filter: Boolean): String {
        val method = when { switchedToGetOnly -> "GET only (HEAD and GET disagree)"; getOnly -> GET_ONLY; else -> HEAD_THEN_GET }
        return "wordlist $wordlist (${entries(entries)}), extensions ${extensions.joinToString(" ").ifEmpty { "none" }}, $method, " +
            "concurrency $concurrency, timeout $timeoutS s, codes ${codes.joinToString(" ")}, not-found filter ${if (filter) "on" else "off"}"
    }

    // Brute-force finding detail and sizes (5.5)
    /** "N B" up to 1023, one decimal "1.2 KB" above, "64 KB+" when truncated with no Content-Length, null when unknown. */
    fun size(bytes: Long?, truncatedUnknown: Boolean): String? = when {
        truncatedUnknown -> "64 KB+"
        bytes == null -> null
        bytes < 1024 -> "$bytes B"
        else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    }
    fun pathDetail(status: Int, size: String?, location: String?): String =
        listOfNotNull(status.toString(), size).joinToString("  ") + (location?.let { "  to $it" } ?: "")

    // HTTP fingerprint details (6.1)
    const val GET_ROOT = "GET /"
    const val PAGE_TITLE = "Page title"
    const val GENERATOR_META = "Generator meta tag"
    const val DISALLOWED = "Disallowed in robots.txt"
    const val ALLOWED = "Allowed in robots.txt"
    const val PRESENT = "Present"
    const val ERROR_SIGNATURE = "Error page signature"
    fun getRootTo(location: String) = "GET /, to $location"
    fun getUrl(url: String) = "GET $url"
    fun cookieDetail(secure: Boolean, httpOnly: Boolean, sameSite: String?) =
        "Cookie" + (if (secure) ", Secure" else "") + (if (httpOnly) ", HttpOnly" else "") + (sameSite?.let { ", SameSite=$it" } ?: "")
    fun rules(n: Int) = if (n == 1) "1 rule" else "$n rules"
    fun urls(n: Int) = if (n == 1) "1 URL" else "$n URLs"
    fun contact(value: String) = "Contact: $value"
    fun matched(source: String, excerpt: String) = "Matched $source: $excerpt"

    // TLS details (6.2)
    const val SUBJECT = "Subject"; const val ISSUER = "Issuer"
    const val SAN_DNS = "Alternative name (DNS)"; const val SAN_IP = "Alternative name (IP)"
    const val VALID = "Valid"; const val EXPIRED = "Expired"; const val NOT_YET_VALID = "Not yet valid"
    const val ED25519 = "Ed25519"; const val ED448 = "Ed448"
    const val PUBLIC_KEY = "Public key"; const val SIGNATURE_ALGORITHM = "Signature algorithm"; const val SHA256_FINGERPRINT = "SHA-256 fingerprint"
    const val PROTOCOL_AND_CIPHER = "Protocol and cipher"; const val CERTIFICATE = "Certificate"
    const val SELF_SIGNED = "Self-signed"; const val WEAK_RSA = "RSA key under 2048 bit"; const val SHA1_SIGNED = "Signed with SHA-1"
    fun validityRange(from: String, to: String) = "$from to $to"
    fun expiresIn(days: Long) = if (days == 1L) "Expires in 1 day" else "Expires in $days days"
    fun keyRsa(bits: Int) = "RSA $bits bit"
    fun keyEc(bits: Int) = "EC $bits bit"
    fun keyDsa(bits: Int) = "DSA $bits bit"
    fun chainCertificate(n: Int, m: Int) = "Chain certificate $n of $m"

    // SMB details (6.3)
    const val DIALECT = "Dialect"; const val SIGNING = "Signing"; const val REQUIRED = "Required"; const val ENABLED_NOT_REQUIRED = "Enabled, not required"
    const val SESSION = "Session"; const val ANONYMOUS_ALLOWED = "Anonymous allowed"; const val ANONYMOUS_REFUSED = "Anonymous refused"
    const val GUEST_ALLOWED = "Guest allowed"; const val GUEST_MAPPED = "Guest mapped to guest"; const val GUEST_REFUSED = "Guest refused"
    const val NETBIOS_NAME = "NetBIOS name"; const val NETBIOS_DOMAIN = "NetBIOS domain"; const val DNS_NAME = "DNS name"; const val DNS_DOMAIN = "DNS domain"
    const val OS_BUILD = "OS build (NTLM)"
    fun dialectName(code: Int): String? = when (code) { 0x0202 -> "SMB 2.0.2"; 0x0210 -> "SMB 2.1"; 0x0300 -> "SMB 3.0"; 0x0302 -> "SMB 3.0.2"; else -> null }

    // SNMP details (6.4)
    const val SYS_DESCR = "sysDescr"; const val SYS_OBJECT_ID = "sysObjectID"; const val UPTIME = "Uptime"
    const val SYS_CONTACT = "sysContact"; const val SYS_NAME = "sysName"; const val SYS_LOCATION = "sysLocation"
    const val INTERFACE_ADDRESS = "Interface address"
    private val IF_STATUS = mapOf(1L to "up", 2L to "down", 3L to "testing", 4L to "unknown", 5L to "dormant", 6L to "notPresent", 7L to "lowerLayerDown")
    private val IF_TYPE = mapOf(6L to "ethernetCsmacd", 24L to "softwareLoopback", 53L to "propVirtual", 71L to "ieee80211", 117L to "gigabitEthernet",
        131L to "tunnel", 135L to "l2vlan", 161L to "ieee8023adLag", 209L to "bridge")
    /** TimeTicks (hundredths of a second): "12 days 4 h" from one day up, else "3 h 12 min". */
    fun uptime(ticks: Long): String {
        val s = ticks / 100; val days = s / 86_400; val hours = (s % 86_400) / 3_600; val minutes = (s % 3_600) / 60
        return if (days > 0) "${if (days == 1L) "1 day" else "$days days"} $hours h" else "$hours h $minutes min"
    }
    /** "<status>, <type>, <speed>, <MAC>" with empty parts omitted (spec 6.4). [speedBits] is bit/s; 0 or null omits the speed. */
    fun interfaceDetail(status: Long?, type: Long?, speedBits: Long?, mac: String?): String = buildList {
        status?.let { IF_STATUS[it] }?.let(::add)
        type?.let { add(IF_TYPE[it] ?: "type $it") }
        speedBits?.takeIf { it > 0 }?.let { add(if (it % 1_000_000_000L == 0L) "${it / 1_000_000_000L} Gbit/s" else "${it / 1_000_000L} Mbit/s") }
        mac?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(", ")
    fun address(addr: String, prefix: Int) = "$addr/$prefix"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

/** Every Wordlists screen, import and Settings string (spec 9). Templates live in ToolCopyTableTest. */
object WordlistCopy {
    const val BACK = "Back"
    const val TITLE = "Wordlists"
    const val IMPORT = "Import wordlist"
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
    const val DEFAULT_NAME = "Imported list"
    const val SETTINGS_SECTION = "Tools"
    const val SETTINGS_ROW = "Wordlists"
    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun entriesBuiltIn(n: Int) = "${entries(n)}, built in"
    fun entriesImported(n: Int, date: String) = "${entries(n)}, imported $date"
    fun deletePrompt(name: String) = "Delete $name?"
    fun deleteDescription(name: String) = "Delete $name"
    fun imported(name: String, n: Int, skipped: Int) = "Imported $name, ${entries(n)}${if (skipped > 0) " ($skipped skipped)" else ""}"
    fun settingsSupport(imported: Int) = "3 built in, $imported imported"
}
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.*" --tests "com.northwalessolutions.netprobe.engine.common.CancellableIoTest" --tests "com.northwalessolutions.netprobe.ui.tools.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ToolApplicabilityTest` (7), `ExcusedOnceTest` (2), `CancellableIoTest` (4) and `ToolCopyTableTest` (3) pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicability.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/common/CancellableIo.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolCopy.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistCopy.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolApplicabilityTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ExcusedOnceTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/common/CancellableIoTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolCopyTableTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Tool framework interfaces, ToolApplicability, CancellableIo, ToolCopy and WordlistCopy"
```

---

### Task 4: HostBusyRegistry, AppMessages, the per-host rate bucket, the foreground flow and AppContainer wiring

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistry.kt`, `engine/tools/AppMessages.kt`, `engine/common/RateBucket.kt`
- Modify: `AppContainer.kt` (`hostBusy`, `appMessages`, `foreground`, `toolDispatcher`), `MainActivity.kt` (a `DefaultLifecycleObserver`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistryTest.kt`, `AppMessagesTest.kt`, `engine/common/RateBucketTest.kt`

**Interfaces:**
- Consumes: `TOOL_THREADS` (Task 3).
- Produces:
```kotlin
class HostBusyRegistry { enum class Work { PORT_SCAN, TOOL }; val busy: StateFlow<Map<Long, Work>>; fun tryClaim(hostId: Long, work: Work): Boolean; fun release(hostId: Long, work: Work) }
data class AppMessage(val id: Long, val text: String, val hostId: Long?)
class AppMessages { val current: StateFlow<AppMessage?>; fun post(text: String, hostId: Long? = null); fun dismiss(id: Long) }
class RateBucket(val ratePerSecond: Int = MAX_REQUESTS_PER_SECOND, val burst: Int = BURST, now: () -> Long = System::currentTimeMillis) {
    fun acquireDelayMs(): Long; fun reset()
    companion object { const val MAX_REQUESTS_PER_SECOND = 100; const val BURST = 10 }
}
AppContainer.hostBusy: HostBusyRegistry; AppContainer.appMessages: AppMessages
AppContainer.foreground: MutableStateFlow<Boolean>; AppContainer.toolDispatcher: CoroutineDispatcher
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistryTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 4.3: one work holds a host at a time; release by the wrong work is a no-op. */
class HostBusyRegistryTest {
    private val r = HostBusyRegistry()
    @Test fun `claim then a second claim is refused until release`() {
        assertTrue(r.tryClaim(7, HostBusyRegistry.Work.TOOL))
        assertFalse(r.tryClaim(7, HostBusyRegistry.Work.PORT_SCAN))
        assertEquals(HostBusyRegistry.Work.TOOL, r.busy.value[7])
        r.release(7, HostBusyRegistry.Work.TOOL)
        assertNull(r.busy.value[7]); assertTrue(r.tryClaim(7, HostBusyRegistry.Work.PORT_SCAN))
    }
    @Test fun `release by the wrong work does nothing`() {
        assertTrue(r.tryClaim(7, HostBusyRegistry.Work.TOOL))
        r.release(7, HostBusyRegistry.Work.PORT_SCAN)
        assertEquals(HostBusyRegistry.Work.TOOL, r.busy.value[7])
    }
    @Test fun `different hosts are independent`() {
        assertTrue(r.tryClaim(1, HostBusyRegistry.Work.TOOL)); assertTrue(r.tryClaim(2, HostBusyRegistry.Work.PORT_SCAN))
        assertEquals(2, r.busy.value.size)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/AppMessagesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 4.3: one unread message; post replaces; dismiss clears only if the id still matches. */
class AppMessagesTest {
    private val m = AppMessages()
    @Test fun `post replaces any unread message and ids are unique`() {
        m.post("a", 1); val first = m.current.value!!
        m.post("b", 2); val second = m.current.value!!
        assertEquals("b", second.text); assertEquals(2L, second.hostId); assertNotEquals(first.id, second.id)
    }
    @Test fun `dismiss clears only the message it names`() {
        m.post("a"); val id = m.current.value!!.id
        m.dismiss(id + 1); assertNotNull(m.current.value)   // a stale dismiss is ignored
        m.dismiss(id); assertNull(m.current.value)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/RateBucketTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import org.junit.Assert.*
import org.junit.Test

/** spec 5.3: a per-host token bucket, 100 per second, burst 10, that refills from empty after a pause (Gruff M3). */
class RateBucketTest {
    private var now = 0L
    private val bucket = RateBucket(ratePerSecond = 100, burst = 10) { now }

    @Test fun `the burst grants ten at once, then a wait`() {
        repeat(10) { assertEquals("token $it", 0L, bucket.acquireDelayMs()) }
        assertTrue("the eleventh must wait", bucket.acquireDelayMs() > 0)
    }
    @Test fun `no more than one hundred are granted in any one second window`() {
        var granted = 0
        // Drive a fake second one millisecond at a time, always taking a token when one is free.
        while (now <= 1000) { if (bucket.acquireDelayMs() == 0L) granted++; now += 1 }
        assertTrue("granted $granted in 1 s", granted <= 100 + 10)   // at most the window plus the initial burst
        assertTrue(granted >= 100)
    }
    @Test fun `reset empties the bucket so the next token waits`() {
        assertEquals(0L, bucket.acquireDelayMs())
        bucket.reset()
        assertTrue("after reset the bucket is empty", bucket.acquireDelayMs() > 0)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.HostBusyRegistryTest" --tests "com.northwalessolutions.netprobe.engine.tools.AppMessagesTest" --tests "com.northwalessolutions.netprobe.engine.common.RateBucketTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'HostBusyRegistry'`, `'AppMessages'`, `'RateBucket'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistry.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One app-level claim per host: a port scan or a tool, never both (spec 4.3). Consulted by Quick scan all, Detail port scans and the tool runner. */
class HostBusyRegistry {
    enum class Work { PORT_SCAN, TOOL }
    private val _busy = MutableStateFlow<Map<Long, Work>>(emptyMap())
    val busy: StateFlow<Map<Long, Work>> = _busy

    @Synchronized fun tryClaim(hostId: Long, work: Work): Boolean {
        if (_busy.value.containsKey(hostId)) return false
        _busy.value = _busy.value + (hostId to work); return true
    }
    @Synchronized fun release(hostId: Long, work: Work) {
        if (_busy.value[hostId] == work) _busy.value = _busy.value - hostId
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/AppMessages.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** One unread end-of-run message, read by the visible screen (Home notice line, Detail snackbar) (spec 4.3). */
data class AppMessage(val id: Long, val text: String, val hostId: Long?)
class AppMessages {
    private val seq = AtomicLong(0)
    private val _current = MutableStateFlow<AppMessage?>(null)
    val current: StateFlow<AppMessage?> = _current
    fun post(text: String, hostId: Long? = null) { _current.value = AppMessage(seq.incrementAndGet(), text, hostId) }
    fun dismiss(id: Long) { if (_current.value?.id == id) _current.value = null }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/RateBucket.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlin.math.ceil
import kotlin.math.min

/**
 * A per-host token bucket (spec 5.3): [ratePerSecond] refill, [burst] capacity. [acquireDelayMs] consumes a token and returns 0 when
 * one is free, else the whole milliseconds until the next token; the caller delays and retries. [reset] empties it, so the bucket
 * refills from empty after a background pause. A constant, never a user option.
 */
class RateBucket(val ratePerSecond: Int = MAX_REQUESTS_PER_SECOND, val burst: Int = BURST, private val now: () -> Long = System::currentTimeMillis) {
    private var tokens = burst.toDouble()
    private var last = now()
    private fun refill() { val t = now(); tokens = min(burst.toDouble(), tokens + (t - last) * ratePerSecond / 1000.0); last = t }
    @Synchronized fun acquireDelayMs(): Long {
        refill()
        if (tokens >= 1.0) { tokens -= 1.0; return 0L }
        return ceil((1.0 - tokens) / ratePerSecond * 1000.0).toLong().coerceAtLeast(1L)
    }
    @Synchronized fun reset() { tokens = 0.0; last = now() }
    companion object { const val MAX_REQUESTS_PER_SECOND = 100; const val BURST = 10 }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add (imports `kotlinx.coroutines.CoroutineDispatcher`, `kotlinx.coroutines.Dispatchers`, `com.northwalessolutions.netprobe.engine.tools.*`, `com.northwalessolutions.netprobe.engine.tools.TOOL_THREADS`):
```kotlin
    /** One run at a time per host, across Quick scan all, Detail scans and the tool runner (spec 4.3). */
    val hostBusy = HostBusyRegistry()
    /** End-of-run messages for whichever screen is visible (spec 4.3). */
    val appMessages = AppMessages()
    /** NetProbe's foreground state; MainActivity's lifecycle sets it. Runs pause while false (spec 4.8). */
    val foreground = MutableStateFlow(true)
    /** Every tool coroutine and blocking call runs here, so tool I/O never holds more than TOOL_THREADS threads total (spec 4.4, Gruff M7). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val toolDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(TOOL_THREADS)
```

`app/src/main/java/com/northwalessolutions/netprobe/MainActivity.kt`: register a lifecycle observer that sets `container.foreground` (imports `androidx.lifecycle.DefaultLifecycleObserver`, `androidx.lifecycle.LifecycleOwner`). In `onCreate`, after `val container = ...`:
```kotlin
        // NetProbe is single-activity, so this activity's own lifecycle is the app's foreground state (spec 4.8). No new dependency.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { container.foreground.value = false }
        })
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.*" --tests "com.northwalessolutions.netprobe.engine.common.RateBucketTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `HostBusyRegistryTest` (3), `AppMessagesTest` (2) and `RateBucketTest` (3) pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistry.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/AppMessages.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/common/RateBucket.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/main/java/com/northwalessolutions/netprobe/MainActivity.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/HostBusyRegistryTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/AppMessagesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/common/RateBucketTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "HostBusyRegistry, AppMessages, the per-host rate bucket and the foreground flow"
```

---

### Task 5: HttpClient: a raw-socket HTTP/1.1 client with CancellableIo, chunked and close framing, the 64 KB cap and trust-all TLS

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/HttpClient.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/common/HttpClientTest.kt`

**Interfaces:**
- Consumes: 3A's `Probe.connect`, `currentBinder`, `CancellableIo.closing` (Task 3).
- Produces:
```kotlin
data class HttpResponse(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: ByteArray, val bodyTruncated: Boolean, val contentLength: Long?) {
    fun header(name: String): String?; fun headersAll(name: String): List<String>
}
sealed interface HttpFailure { object Refused : HttpFailure; object Timeout : HttpFailure; object Reset : HttpFailure; object Malformed : HttpFailure }
sealed interface HttpResult { data class Ok(val response: HttpResponse) : HttpResult; data class Failed(val failure: HttpFailure) : HttpResult }
class HttpClient(private val timeoutMs: Int, private val maxBody: Int = MAX_BODY_BYTES) {
    companion object { const val MAX_BODY_BYTES = 65_536; const val MAX_HEADER_BYTES = 16_384; const val USER_AGENT = "NetProbe/0.1"; fun trustAllContext(): SSLContext }
    suspend fun request(ip: Inet4Address, port: Int, tls: Boolean, method: String, path: String, hostName: String? = null, sslContext: SSLContext? = null): HttpResult
}
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/engine/common/HttpClientTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** spec 4.6 and 12.1: exact request bytes, framing, caps, failures and cancellation, against loopback ServerSocket scripts. */
class HttpClientTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val ip9 = InetAddress.getByName("10.0.3.9") as Inet4Address
    private val servers = mutableListOf<ServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    /** Accepts one connection, hands the request text to [handle], and writes what it returns, then closes (unless [keepOpen]). */
    private fun serve(keepOpen: Boolean = false, handle: (String) -> ByteArray): Pair<Int, () -> String> {
        val s = ServerSocket(0, 50, lo); servers += s
        @Volatile var seen = ""
        thread(isDaemon = true) {
            runCatching {
                val c = s.accept()
                val req = StringBuilder(); val input = c.getInputStream()
                // Read the request head (up to the blank line).
                val buf = ByteArray(4096)
                while (true) { val n = input.read(buf); if (n < 0) break; req.append(String(buf, 0, n, Charsets.ISO_8859_1)); if (req.contains("\r\n\r\n")) break }
                seen = req.toString()
                c.getOutputStream().write(handle(seen)); c.getOutputStream().flush()
                if (!keepOpen) c.close() else { Thread.sleep(400); c.close() }
            }
        }
        return s.localPort to { seen }
    }
    private fun ok(body: String) = ("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1)
    private fun client(timeout: Int = 1000) = HttpClient(timeoutMs = timeout)
    private fun result(port: Int, method: String = "GET", path: String = "/", host: String? = null) = runBlocking {
        client().request(lo, port, tls = false, method = method, path = path, hostName = host)
    }

    @Test fun `get request bytes are exact with the ip host and its port`() {
        val (port, seen) = serve { ok("hi") }
        result(port, path = "/x")
        assertEquals("GET /x HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUser-Agent: NetProbe/0.1\r\nAccept: */*\r\nConnection: close\r\n\r\n", seen())
    }
    @Test fun `head sends head and gets no body`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n".toByteArray() }
        val r = result(port, method = "HEAD") as HttpResult.Ok
        assertEquals(200, r.response.status); assertEquals(0, r.response.body.size)
    }
    @Test fun `host uses the name when given and omits the port for 443 on https is covered in the tls task`() {
        val (port, seen) = serve { ok("hi") }
        result(port, host = "chnas.local")
        assertTrue(seen().contains("Host: chnas.local:$port\r\n"))
    }
    @Test fun `content length body is read`() {
        val (port, _) = serve { ok("hello") }
        val r = result(port) as HttpResult.Ok
        assertEquals("hello", String(r.response.body, Charsets.ISO_8859_1)); assertEquals(5L, r.response.contentLength); assertFalse(r.response.bodyTruncated)
    }
    @Test fun `chunked body is de-chunked including an extension and a trailer`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\nX-T: 1\r\n\r\n").toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals("Wikipedia", String(r.response.body, Charsets.ISO_8859_1)); assertNull(r.response.contentLength)
    }
    @Test fun `a close delimited body ends at eof`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\r\n\r\nno length here".toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals("no length here", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `no body for 204 and 304`() {
        for (code in listOf(204 to "No Content", 304 to "Not Modified")) {
            val (port, _) = serve { "HTTP/1.1 ${code.first} ${code.second}\r\n\r\n".toByteArray() }
            val r = result(port) as HttpResult.Ok; assertEquals(code.first, r.response.status); assertEquals(0, r.response.body.size)
        }
    }
    @Test fun `interim 100 continue is skipped`() {
        val (port, _) = serve { "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals(200, r.response.status); assertEquals("ok", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `a bare lf status line and headers are accepted`() {
        val (port, _) = serve { "HTTP/1.1 200 OK\nServer: lwip\nContent-Length: 1\n\nx".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals("lwip", r.response.header("server")); assertEquals("x", String(r.response.body, Charsets.ISO_8859_1))
    }
    @Test fun `the body is capped at sixty four kilobytes and marked truncated`() {
        val big = "a".repeat(100_000)
        val (port, _) = serve(keepOpen = true) { "HTTP/1.1 200 OK\r\n\r\n$big".toByteArray() }
        val r = result(port) as HttpResult.Ok
        assertEquals(HttpClient.MAX_BODY_BYTES, r.response.body.size); assertTrue(r.response.bodyTruncated)
    }
    @Test fun `headers beyond sixteen kilobytes are malformed`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nX: " + "y".repeat(20_000) + "\r\n\r\n").toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `a non http status line is malformed`() {
        val (port, _) = serve { "220 ftp ready\r\n".toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `a server that never answers times out`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { val c = s.accept(); Thread.sleep(5_000); c.close() } }
        assertEquals(HttpFailure.Timeout, (runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Failed).failure)
    }
    @Test fun `a refused port is Refused`() {
        val s = ServerSocket(0, 50, lo); val port = s.localPort; s.close()
        val f = runBlocking { client(300).request(lo, port, false, "GET", "/") }
        assertEquals(HttpFailure.Refused, (f as HttpResult.Failed).failure)
    }
    @Test fun `a redirect is returned and not followed`() {
        val (port, _) = serve { "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\n\r\n".toByteArray() }
        val r = result(port) as HttpResult.Ok; assertEquals(302, r.response.status); assertEquals("http://127.0.0.1:1/elsewhere", r.response.header("location"))
    }
    @Test fun `the host header omits only the default port of the scheme`() {
        assertEquals("10.0.3.9", HttpClient.hostHeader(ip9, 80, false, null))
        assertEquals("nas.lan", HttpClient.hostHeader(ip9, 443, true, "nas.lan"))
        assertEquals("10.0.3.9:443", HttpClient.hostHeader(ip9, 443, false, null))
        assertEquals("nas.lan:80", HttpClient.hostHeader(ip9, 80, true, "nas.lan"))
    }
    @Test fun `a server that accepts tcp but stalls tls times out`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { val c = s.accept(); Thread.sleep(5_000); c.close() } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, tls = true, method = "GET", path = "/") }
        assertEquals(HttpFailure.Timeout, (r as HttpResult.Failed).failure)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
    }
    @Test fun `a server that drips bytes is stopped by the overall deadline`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray())
            repeat(100) { c.getOutputStream().write('a'.code); c.getOutputStream().flush(); Thread.sleep(50) }
        } } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Ok
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500); assertTrue(r.response.body.size < 100)
    }
    @Test fun `no framing on a connection kept open ends at the read timeout with the body so far`() {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            c.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\npartial".toByteArray()); c.getOutputStream().flush(); Thread.sleep(3_000)
        } } }
        val t0 = System.nanoTime()
        val r = runBlocking { client(300).request(lo, s.localPort, false, "GET", "/") } as HttpResult.Ok
        assertEquals("partial", String(r.response.body)); assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
    }
    @Test fun `a megabyte header line with no lf is malformed, not memory`() {
        val (port, _) = serve { ("HTTP/1.1 200 OK\r\nX: " + "a".repeat(1_000_000)).toByteArray() }
        assertEquals(HttpFailure.Malformed, (result(port) as HttpResult.Failed).failure)
    }
    @Test fun `cancellation mid read closes the socket within a second`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        val accepted = java.util.concurrent.CountDownLatch(1)
        lateinit var peer: Socket
        thread(isDaemon = true) { runCatching { peer = s.accept(); accepted.countDown(); peer.getOutputStream().write("HTTP/1.1 200 OK\r\n".toByteArray()); peer.getOutputStream().flush(); Thread.sleep(10_000) } }
        val job = launch(Dispatchers.IO) { client(10_000).request(lo, s.localPort, false, "GET", "/") }
        assertTrue(accepted.await(2, java.util.concurrent.TimeUnit.SECONDS)); delay(300)
        val t0 = System.nanoTime(); job.cancelAndJoin()
        assertTrue("cancel took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 <= 1000)
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.HttpClientTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'HttpClient'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/HttpClient.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class HttpResponse(
    val status: Int, val reason: String,
    val headers: List<Pair<String, String>>,   // in order, names as sent
    val body: ByteArray, val bodyTruncated: Boolean, val contentLength: Long?,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    fun headersAll(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface HttpFailure { object Refused : HttpFailure; object Timeout : HttpFailure; object Reset : HttpFailure; object Malformed : HttpFailure }
sealed interface HttpResult { data class Ok(val response: HttpResponse) : HttpResult; data class Failed(val failure: HttpFailure) : HttpResult }

/**
 * HTTP/1.1 over a raw socket (spec 4.6): Connection: close, redirects never followed, a 64 KB body cap. Every exchange runs inside
 * CancellableIo.closing, so Stop ends within about a second during a read. TLS is layered over the connected socket with trust-all,
 * used only to read; the browser launch never uses this.
 */
class HttpClient(private val timeoutMs: Int, private val maxBody: Int = MAX_BODY_BYTES) {
    suspend fun request(ip: Inet4Address, port: Int, tls: Boolean, method: String, path: String, hostName: String? = null, sslContext: SSLContext? = null): HttpResult {
        val outcome = Probe.connect(ip, port, timeoutMs, keepOpen = true)
        val raw = (outcome as? Probe.Outcome.Connected)?.socket ?: return HttpResult.Failed(failureOf(outcome))
        return try {
            CancellableIo.closing(raw) { exchange(raw, ip, port, tls, method, path, hostName, sslContext) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { HttpResult.Failed(HttpFailure.Reset)
        } finally { runCatching { raw.close() } }
    }

    private fun failureOf(o: Probe.Outcome): HttpFailure = when (o) {
        is Probe.Outcome.Refused -> HttpFailure.Refused
        is Probe.Outcome.Timeout, is Probe.Outcome.Unreachable -> HttpFailure.Timeout
        else -> HttpFailure.Reset
    }

    private fun exchange(raw: Socket, ip: Inet4Address, port: Int, tls: Boolean, method: String, path: String, hostName: String?, sslContext: SSLContext?): HttpResult {
        // Before the handshake (plan review G4): a device that accepts TCP and never answers the ClientHello times out instead of
        // hanging the run. The connect itself is bounded by Probe.connect's timeout argument.
        raw.soTimeout = timeoutMs
        val socket: Socket = if (!tls) raw else try { layerTls(raw, ip, port, hostName, sslContext) }
            catch (_: SocketTimeoutException) { return HttpResult.Failed(HttpFailure.Timeout) }
            catch (_: IOException) { return HttpResult.Failed(HttpFailure.Reset) }
        socket.soTimeout = timeoutMs
        val deadline = System.currentTimeMillis() + 2L * timeoutMs       // overall, so a dripping server still stops
        val host = hostHeader(ip, port, tls, hostName)
        val head = "$method $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $USER_AGENT\r\nAccept: */*\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); flush() }
        return try { read(BufferedInputStream(socket.getInputStream(), READ_BUFFER), method, deadline) } catch (_: SocketTimeoutException) { HttpResult.Failed(HttpFailure.Timeout) } catch (_: SocketException) { HttpResult.Failed(HttpFailure.Reset) }
    }

    private fun layerTls(raw: Socket, ip: Inet4Address, port: Int, hostName: String?, sslContext: SSLContext?): SSLSocket {
        val ctx = sslContext ?: trustAllContext()
        val sni = hostName ?: ip.hostAddress
        val s = ctx.socketFactory.createSocket(raw, sni, port, true) as SSLSocket
        // Enable every protocol and cipher so old TLS 1.0/1.1 and RSA/3DES-only devices can still be read; each setter is wrapped (spec 4.5).
        runCatching { s.enabledProtocols = s.supportedProtocols }
        runCatching { s.enabledCipherSuites = s.supportedCipherSuites }
        s.startHandshake()
        return s
    }

    private fun read(input: InputStream, method: String, deadline: Long): HttpResult {
        val reader = HeadReader(input, deadline)
        var statusLine = reader.line() ?: return HttpResult.Failed(HttpFailure.Malformed)
        var m = STATUS.matchEntire(statusLine) ?: return HttpResult.Failed(HttpFailure.Malformed)
        while (m.groupValues[1].toInt() in 100..199) {                   // skip an interim 1xx head, headers and all, then read the next status
            while (true) { val h = reader.line() ?: return HttpResult.Failed(HttpFailure.Malformed); if (h.isEmpty()) break }
            statusLine = reader.line() ?: return HttpResult.Failed(HttpFailure.Malformed)
            m = STATUS.matchEntire(statusLine) ?: return HttpResult.Failed(HttpFailure.Malformed)
        }
        val status = m.groupValues[1].toInt(); val reason = m.groupValues[2].trim()
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val h = reader.line() ?: return HttpResult.Failed(HttpFailure.Malformed)
            if (h.isEmpty()) break
            if (reader.headBytes > MAX_HEADER_BYTES) return HttpResult.Failed(HttpFailure.Malformed)
            val i = h.indexOf(':'); if (i > 0) headers += h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        if (reader.headBytes > MAX_HEADER_BYTES) return HttpResult.Failed(HttpFailure.Malformed)
        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toLongOrNull()
        val chunked = headers.any { it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true) }
        val bodyless = method == "HEAD" || status in 100..199 || status == 204 || status == 304
        if (bodyless) return HttpResult.Ok(HttpResponse(status, reason, headers, ByteArray(0), false, contentLength))
        val (body, truncated) = when {
            chunked -> readChunked(reader, deadline)
            contentLength != null -> readFixed(reader, contentLength, deadline)
            else -> readToClose(reader, deadline)
        }
        return HttpResult.Ok(HttpResponse(status, reason, headers, body, truncated, contentLength))
    }

    private fun readFixed(r: HeadReader, length: Long, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(); var remaining = minOf(length, maxBody.toLong()).toInt()
        while (remaining > 0 && System.currentTimeMillis() <= deadline) { val b = r.readByte() ?: break; out.write(b); remaining-- }
        return out.toByteArray() to (length > maxBody)
    }
    /** No framing: read until close, the cap or the overall deadline; a read timeout ends the body, it does not fail the exchange. */
    private fun readToClose(r: HeadReader, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        try {
            while (out.size() < maxBody && System.currentTimeMillis() <= deadline) { val b = r.readByte() ?: return out.toByteArray() to false; out.write(b) }
            return out.toByteArray() to (out.size() >= maxBody && r.readByte() != null)
        } catch (_: SocketTimeoutException) { return out.toByteArray() to false }
    }
    private fun readChunked(r: HeadReader, deadline: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(); var truncated = false
        while (System.currentTimeMillis() <= deadline) {
            val sizeLine = r.line() ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) { while (true) { val t = r.line(); if (t == null || t.isEmpty()) break }; break }   // trailers then end
            var left = size
            while (left > 0) { val b = r.readByte() ?: return out.toByteArray() to truncated; if (out.size() < maxBody) out.write(b) else truncated = true; left-- }
            r.line()   // the CRLF after the chunk data
        }
        return out.toByteArray() to truncated
    }

    /** Reads a byte-oriented stream a line at a time (CRLF or bare LF) and single bytes, counting head bytes for the 16 KB cap. */
    private class HeadReader(private val input: InputStream, private val deadline: Long) {
        var headBytes = 0; private set
        fun readByte(): Int? { if (System.currentTimeMillis() > deadline) return null; val b = input.read(); return if (b < 0) null else b }
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                if (System.currentTimeMillis() > deadline) return if (sb.isEmpty()) null else sb.toString()
                val b = input.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                headBytes++
                if (headBytes > MAX_HEADER_BYTES) return null       // a line with no LF never grows past the header cap (plan review G15)
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 65_536; const val MAX_HEADER_BYTES = 16_384; const val USER_AGENT = "NetProbe/0.1"
        private const val READ_BUFFER = 8192
        /** The Host header (spec 4.6): the name when given, else the IP, plus ":<port>" unless 80 for http or 443 for https. */
        fun hostHeader(ip: Inet4Address, port: Int, tls: Boolean, hostName: String?): String {
            val name = hostName ?: ip.hostAddress
            val defaultPort = if (tls) 443 else 80
            return if (port == defaultPort) name else "$name:$port"
        }
        private val STATUS = Regex("HTTP/1\\.[01] (\\d{3})( .*)?")
        /** Trust-all, used only to read (spec 4.5); the browser launch never uses this. One per run resumes the session cache. */
        fun trustAllContext(): SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
        }
    }
}
```
- [ ] **Step 4: Run the test to see it pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.common.HttpClientTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: every `HttpClientTest` case passes, including the TLS stall, drip, keep-open and long-line cases; `BUILD SUCCESSFUL`. The TLS path (SNI, the retry without a name) is exercised in Task 12 against the committed keystore; it shares this exact code.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/common/HttpClient.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/common/HttpClientTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "HttpClient: raw-socket HTTP/1.1 with CancellableIo, chunked and close framing, the 64 KB cap and trust-all TLS"
```

---

### Task 6: ToolRunner: one run per host, the pause gate and rate ceiling, the ticker, INTERRUPTED, end states and onFinished

**Roles:** stronger model (implement and review).

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolStore.kt`, `engine/tools/ToolJson.kt`, `engine/tools/ToolRunner.kt`
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt` (`ToolRepository : ToolStore`), `AppContainer.kt` (`tools`, `toolRunner`, `toolRunActive`, the `reidentifyFromTools` placeholder that Task 7 fills), `ui/Nav.kt` (`KeepScreenOn(extendedRunActive || toolRunActive)`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerTestSupport.kt`, `ToolRunnerTest.kt`, `ToolRunnerPauseTest.kt`, `ToolJsonTest.kt`

**Interfaces:**
- Consumes: Task 3 (`Tool`, `ToolTarget`, `RunControl`, `ToolProgress`, `Finding`, `ToolResult`, `ToolOptions`, `ToolCopy`), Task 4 (`HostBusyRegistry`, `AppMessages`, `RateBucket`), Task 2 (`ToolRepository`), 3A's `appScope`, `ScanBinding.run`, `NetworkEnv`, `NetworkSignature.identityKey`, `BindFailedException`, `extendedRunActive`, `KeepScreenOn`.
- Produces:
```kotlin
interface ToolStore { val ready: Deferred<Unit>
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long
    suspend fun findingCount(runId: Long): Int
    suspend fun markInterrupted(summaryOf: (found: Int) -> String); suspend fun setOptions(runId: Long, json: String) }
object ToolOptionsJson { fun encode(target: ToolTarget, options: ToolOptions): String; fun withOption(json: String, key: String, value: String): String }   // Task 10 adds the brute-force fields
object FindingExtraJson { fun encode(f: Finding): String? }                                // url plus extra; null when both empty
class ToolRunner(scope: CoroutineScope, dispatcher: CoroutineDispatcher, tools: Map<ToolId, Tool>, repo: ToolStore, busy: HostBusyRegistry,
                 messages: AppMessages, env: StateFlow<NetworkEnv?>, foreground: StateFlow<Boolean>,
                 bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit, onFinished: suspend (hostId: Long) -> Unit,
                 clock: () -> Long = System::currentTimeMillis, log: (Throwable) -> Unit = {}) {
    data class Active(val runId: Long, val hostId: Long, val toolId: ToolId, val port: Int, val activeMs: Long, val done: Int, val total: Int?, val found: Int, val errors: Int, val paused: Boolean)
    val active: StateFlow<Map<Long, Active>>
    sealed interface Start { data class Started(val runId: Long) : Start; object HostBusy : Start; object TooMany : Start; object NoNetwork : Start }
    suspend fun start(target: ToolTarget, toolId: ToolId, options: ToolOptions): Start
    fun stop(hostId: Long)
    companion object { const val MAX_ACTIVE_RUNS = 4; const val KEEP_RUNS_PER_TOOL = 10; const val PROGRESS_INTERVAL_MS = 200L; const val TOOL_THREADS = 16; const val MAX_REQUESTS_PER_SECOND = 100 }
}
AppContainer.tools: Map<ToolId, Tool>; AppContainer.toolRunner: ToolRunner; AppContainer.toolRunActive: StateFlow<Boolean>
```

Rules this task implements (spec 4.4, 4.8): `start` awaits `repo.ready`; it returns `NoNetwork` when `env.value` is null, `TooMany` when `MAX_ACTIVE_RUNS` hosts run, `HostBusy` when the claim fails; the row is inserted (with pruning) before the launch; the tool runs on `dispatcher` inside `bind`; end states are written in `NonCancellable` with `finishedAt = clock()` (the end write is retried once, spec 11.12), then the host is released, `"<tool> finished: <summary>"` is posted, and `onFinished` runs unless the run FAILED with no finding row at all; a network identity change cancels the runs that started on the old key; `foreground` false pauses every run (epoch up, gate closed, clock stopped), true resumes it (rate bucket emptied, clock restarted); the ticker republishes `active` every `PROGRESS_INTERVAL_MS` while any run is live and ends when none is.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerTestSupport.kt` (shared fakes, test code only):
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.ContinuationInterceptor

object ToolRunnerTestSupport {
    fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    val home = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
    val office = NetworkEnv(ip("10.0.3.50"), 24, ip("10.0.9.1"), emptyList(), "Office", null, Transport.WIFI)
    fun target(hostId: Long) = ToolTarget(hostId, ip("10.0.3.9"), 5000, tls = false, serverName = null)

    class FakeStore : ToolStore {
        override val ready = CompletableDeferred<Unit>().also { it.complete(Unit) }
        private var nextId = 0L
        val started = mutableListOf<String>()
        val finished = mutableListOf<Triple<Long, ToolRunStatus, String>>()
        val finishedAt = mutableListOf<Long?>()
        val findings = mutableListOf<Pair<Long, String>>()
        val extras = mutableListOf<String?>()
        override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long { started += optionsJson; return ++nextId }
        override suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String) { finished += Triple(runId, status, summary); this.finishedAt += finishedAt }
        override suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long { findings += runId to value; extras += extra; return findings.size.toLong() }
        override suspend fun findingCount(runId: Long) = findings.count { it.first == runId }
        override suspend fun markInterrupted(summaryOf: (Int) -> String) {}
        val optionWrites = mutableListOf<String>()
        override suspend fun setOptions(runId: Long, json: String) { optionWrites += json }
    }
    class FakeTool(override val id: ToolId, val body: suspend (RunControl, ToolProgress) -> ToolResult) : Tool {
        override val name = id.key
        override fun applies(port: Int, service: ServiceInfo) = true
        override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress) = body(control, progress)
    }
    class Rig(scope: CoroutineScope, tool: Tool, val store: FakeStore = FakeStore(), val busy: HostBusyRegistry = HostBusyRegistry(),
              val messages: AppMessages = AppMessages(), now: () -> Long = { 0L },
              bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit = { _, _, block -> block() }) {
        val env = MutableStateFlow<NetworkEnv?>(home)
        val foreground = MutableStateFlow(true)
        val finishedHosts = mutableListOf<Long>()
        val runner = ToolRunner(scope, scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, mapOf(tool.id to tool), store, busy,
            messages, env, foreground, bind, onFinished = { finishedHosts += it }, clock = now)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.office
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerTest {
    private val ok = ToolResult(ToolRunStatus.DONE, "ok")

    @Test fun `a second run on the same host is refused`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> gate.await(); ok })
        assertTrue(rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.HostBusy, rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None))
        gate.complete(Unit); runCurrent()
        assertEquals(1, rig.store.finished.size)
    }
    @Test fun `TooMany at four hosts and NoNetwork with no env`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> gate.await(); ok })
        for (h in 1L..4L) assertTrue(rig.runner.start(target(h), ToolId.DIR_BRUTEFORCE, ToolOptions.None) is ToolRunner.Start.Started)
        assertEquals(ToolRunner.Start.TooMany, rig.runner.start(target(5), ToolId.DIR_BRUTEFORCE, ToolOptions.None))
        assertNull("a refused start never claims the host", rig.busy.busy.value[5])
        gate.complete(Unit); runCurrent()
        rig.env.value = null
        assertEquals(ToolRunner.Start.NoNetwork, rig.runner.start(target(6), ToolId.DIR_BRUTEFORCE, ToolOptions.None))
    }
    @Test fun `a start waits for markInterrupted to complete ready`() = runTest {
        val store = ToolRunnerTestSupport.FakeStore()
        val pending = CompletableDeferred<Unit>()
        val waiting = object : ToolStore by store { override val ready = pending }
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> ok })
        val runner = ToolRunner(backgroundScope, StandardTestDispatcher(testScheduler), mapOf(ToolId.DIR_BRUTEFORCE to FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> ok }),
            waiting, HostBusyRegistry(), AppMessages(), rig.env, rig.foreground, { _, _, b -> b() }, {})
        val started = async { runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None) }
        runCurrent()
        assertTrue("start must wait for ready", started.isActive); assertTrue(store.started.isEmpty())
        pending.complete(Unit); runCurrent()
        assertTrue(started.await() is ToolRunner.Start.Started)
    }
    @Test fun `a normal return writes the result, releases the host, posts the message and re-identifies`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, p ->
            p.finding(Finding(FindingKind.PATH, "/admin", "200  10 B", "http://10.0.3.9:5000/admin", mapOf("status" to "200")))
            ToolResult(ToolRunStatus.DONE, "1 found in 10 paths") })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.DONE, "1 found in 10 paths"), rig.store.finished.single())
        assertEquals(listOf(1L to "/admin"), rig.store.findings)
        assertEquals("{\"url\":\"http://10.0.3.9:5000/admin\",\"status\":\"200\"}", rig.store.extras.single())
        assertNull(rig.busy.busy.value[7]); assertEquals(listOf(7L), rig.finishedHosts)
        assertEquals("dir-bruteforce finished: 1 found in 10 paths", rig.messages.current.value!!.text)
        assertTrue(rig.runner.active.value.isEmpty())
    }
    @Test fun `an option the tool records is written into optionsJson`() = runTest {
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, p -> p.option("methodUsed", "GET_ONLY"); ok })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(listOf("{\"tls\":false,\"serverName\":null,\"methodUsed\":\"GET_ONLY\"}"), rig.store.optionWrites)
    }
    @Test fun `Stop ends a blocked tool and writes STOPPED with the stopped summary`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { c, p -> p.update(1234, 4614, 7); entered.complete(Unit); c.gate(); awaitCancellation() })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); entered.await()
        rig.runner.stop(7); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Stopped after 1234 of 4614, 7 found"), rig.store.finished.single())
        assertNull(rig.busy.busy.value[7])
    }
    @Test fun `Stop ends a tool blocked in CancellableIo closing within a second`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ToolRunnerTestSupport.FakeStore(); val entered = CompletableDeferred<Unit>()
        val tool = FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ ->
            com.northwalessolutions.netprobe.engine.common.CancellableIo.closing(java.io.Closeable { }) { entered.complete(Unit); Thread.sleep(10_000) }
            ok
        }
        val runner = ToolRunner(scope, Dispatchers.IO.limitedParallelism(16), mapOf(tool.id to tool), store, HostBusyRegistry(), AppMessages(),
            kotlinx.coroutines.flow.MutableStateFlow(ToolRunnerTestSupport.home), kotlinx.coroutines.flow.MutableStateFlow(true), { _, _, b -> b() }, {})
        runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); entered.await()
        val t0 = System.nanoTime(); runner.stop(7)
        withTimeout(1_000) { while (store.finished.isEmpty()) delay(10) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000); assertEquals(ToolRunStatus.STOPPED, store.finished.single().second)
        scope.cancel()
    }
    @Test fun `a network identity change stops every run`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); entered.await()
        rig.env.value = office; runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.STOPPED, "Network changed, run stopped"), rig.store.finished.single())
    }
    @Test fun `a bind failure is FAILED with the bind wording and a throwing tool is FAILED with its reason`() = runTest {
        val bound = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> ok }, bind = { _, _, _ -> throw BindFailedException(IllegalStateException("vpn")) })
        bound.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Tool skipped: could not bind to Wi-Fi"), bound.store.finished.single())
        val boom = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> throw IllegalStateException("boom") })
        boom.runner.start(target(8), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Failed: boom"), boom.store.finished.single())
    }
    @Test fun `onFinished is skipped for a FAILED run with no finding and runs for one with a finding`() = runTest {
        val empty = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> ToolResult(ToolRunStatus.FAILED, "No HTTP response on this port") })
        empty.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(emptyList<Long>(), empty.finishedHosts)
        val partial = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, p -> p.finding(Finding(FindingKind.HINT, "http.path=/a")); ToolResult(ToolRunStatus.FAILED, "x") })
        partial.runner.start(target(8), ToolId.DIR_BRUTEFORCE, ToolOptions.None); runCurrent()
        assertEquals(listOf(8L), partial.finishedHosts)
    }
    @Test fun `the ticker republishes elapsed time while no update arrives`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() }, now = { testScheduler.currentTime })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); entered.await()
        advanceTimeBy(1_000); runCurrent()
        val first = rig.runner.active.value.getValue(7).activeMs
        advanceTimeBy(1_000); runCurrent()
        assertTrue("elapsed moved from $first", rig.runner.active.value.getValue(7).activeMs >= first + 800)
        rig.runner.stop(7); runCurrent()
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerPauseTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.FakeTool
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 1: the background pause closes the gate, stops the clock and excuses failures that span it (spec 4.8). */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolRunnerPauseTest {
    @Test fun `gate suspends while foreground is false and resumes when true`() = runTest {
        val passed = CompletableDeferred<Unit>(); val reached = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { c, _ -> reached.complete(Unit); c.gate(); passed.complete(Unit); ToolResult(ToolRunStatus.DONE, "ok") })
        rig.foreground.value = false
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None)
        reached.await(); runCurrent()
        assertFalse("the gate must stay closed in the background", passed.isCompleted)
        assertTrue(rig.runner.active.value.getValue(7).paused)
        rig.foreground.value = true; runCurrent()
        assertTrue(passed.isCompleted)
    }
    @Test fun `a pause stops the elapsed clock`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { _, _ -> entered.complete(Unit); awaitCancellation() }, now = { testScheduler.currentTime })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); entered.await()
        advanceTimeBy(1_000); runCurrent()
        rig.foreground.value = false; runCurrent()
        val atPause = rig.runner.active.value.getValue(7).activeMs
        advanceTimeBy(60_000); runCurrent()
        assertEquals("a minute in the browser adds nothing", atPause, rig.runner.active.value.getValue(7).activeMs)
        rig.foreground.value = true; advanceTimeBy(1_000); runCurrent()
        assertTrue(rig.runner.active.value.getValue(7).activeMs > atPause)
        rig.runner.stop(7); runCurrent()
    }
    @Test fun `a failure spanning a pause is excused`() = runTest {
        var excusedAfter = false; var notExcusedFresh = true
        val inRequest = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val rig = Rig(backgroundScope, FakeTool(ToolId.DIR_BRUTEFORCE) { c, _ ->
            val epoch = c.pauseEpoch
            inRequest.complete(Unit); resume.await()            // the request is "in flight" across the pause
            excusedAfter = c.excused(epoch)                     // the pause began after this request started
            notExcusedFresh = c.excused(c.pauseEpoch)           // a request started after the resume is not excused
            ToolResult(ToolRunStatus.DONE, "ok")
        })
        rig.runner.start(target(7), ToolId.DIR_BRUTEFORCE, ToolOptions.None); inRequest.await()
        rig.foreground.value = false; runCurrent()
        rig.foreground.value = true; runCurrent()
        resume.complete(Unit); runCurrent()
        assertTrue(excusedAfter); assertFalse(notExcusedFresh)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolJsonTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ToolJsonTest {
    private val t = ToolTarget(7, InetAddress.getByName("10.0.3.9") as Inet4Address, 443, tls = true, serverName = "chnas.local")
    @Test fun `options json always carries tls and serverName`() {
        assertEquals("{\"tls\":true,\"serverName\":\"chnas.local\"}", ToolOptionsJson.encode(t, ToolOptions.None))
        assertEquals("{\"tls\":true,\"serverName\":null}", ToolOptionsJson.encode(t.copy(serverName = null), ToolOptions.None))
    }
    @Test fun `withOption adds or replaces one key and leaves malformed json alone`() {
        assertEquals("{\"tls\":false,\"methodUsed\":\"GET_ONLY\"}", ToolOptionsJson.withOption("{\"tls\":false}", "methodUsed", "GET_ONLY"))
        assertEquals("{\"methodUsed\":\"GET_ONLY\"}", ToolOptionsJson.withOption("{\"methodUsed\":\"HEAD_THEN_GET\"}", "methodUsed", "GET_ONLY"))
        assertEquals("nope", ToolOptionsJson.withOption("nope", "a", "b"))
    }
    @Test fun `extra json is url then the per kind fields, null when empty`() {
        assertNull(FindingExtraJson.encode(Finding(FindingKind.TITLE, "x")))
        assertEquals("{\"url\":\"http://a/\",\"status\":\"200\"}", FindingExtraJson.encode(Finding(FindingKind.PATH, "/", url = "http://a/", extra = mapOf("status" to "200"))))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.ToolRunner*" --tests "com.northwalessolutions.netprobe.engine.tools.ToolJsonTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ToolRunner'`, `'ToolStore'`, `'ToolOptionsJson'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolStore.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import kotlinx.coroutines.Deferred

/** The slice of ToolRepository the runner needs, so the runner is fake-driven on the JVM (spec 12.1 "a fake repository"). */
interface ToolStore {
    val ready: Deferred<Unit>
    suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long
    suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String)
    suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long
    suspend fun findingCount(runId: Long): Int
    suspend fun markInterrupted(summaryOf: (found: Int) -> String)
    suspend fun setOptions(runId: Long, json: String)
}
```
In `data/Repositories.kt`, change the class line to `class ToolRepository(private val db: NetProbeDb) : com.northwalessolutions.netprobe.engine.tools.ToolStore {` and add `override` to `ready`, `startRun`, `finish`, `addFinding`, `findingCount`, `markInterrupted` and `setOptions` (Task 2 defines `setOptions`). `findingCount` counts non-HINT rows, which is what `markInterrupted` reports; the runner's "recorded no finding" rule uses its own count of every finding it forwarded (below), hints included.

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolJson.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** tool_runs.optionsJson (spec 7.1): always tls and serverName; Task 10 adds the brute-force fields. */
object ToolOptionsJson {
    fun encode(target: ToolTarget, options: ToolOptions): String = buildJsonObject {
        put("tls", target.tls)
        put("serverName", target.serverName?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()
    /** [json] with [key] set to [value]; a malformed [json] is returned unchanged. */
    fun withOption(json: String, key: String, value: String): String =
        runCatching { JsonObject(Json.parseToJsonElement(json).jsonObject + (key to JsonPrimitive(value))).toString() }.getOrElse { json }
}

/** tool_findings.extra (spec 7.1): url when present, then the per-kind fields; null when both are empty. */
object FindingExtraJson {
    fun encode(f: Finding): String? {
        if (f.url == null && f.extra.isEmpty()) return null
        return buildJsonObject { f.url?.let { put("url", it) }; f.extra.forEach { (k, v) -> put(k, v) } }.toString()
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.RateBucket
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns tool runs (spec 4.4): one per host, at most MAX_ACTIVE_RUNS hosts at once, on appScope so leaving the Device screen does not
 * stop them. Each run has a RunControl holding the pause gate and its own rate bucket. A foreground collector pauses and resumes every
 * run; an env collector stops the runs whose network identity changed; a ticker republishes elapsed time while any run is live.
 */
class ToolRunner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val tools: Map<ToolId, Tool>,
    private val repo: ToolStore,
    private val busy: HostBusyRegistry,
    private val messages: AppMessages,
    private val env: StateFlow<NetworkEnv?>,
    private val foreground: StateFlow<Boolean>,
    private val bind: suspend (Inet4Address, NetworkEnv, suspend () -> Unit) -> Unit,
    private val onFinished: suspend (hostId: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (Throwable) -> Unit = {},
) {
    data class Active(val runId: Long, val hostId: Long, val toolId: ToolId, val port: Int, val activeMs: Long,
                      val done: Int, val total: Int?, val found: Int, val errors: Int, val paused: Boolean)
    sealed interface Start { data class Started(val runId: Long) : Start; object HostBusy : Start; object TooMany : Start; object NoNetwork : Start }

    private val runs = ConcurrentHashMap<Long, Run>()          // hostId -> live run
    private val startLock = Mutex()
    private val _active = MutableStateFlow<Map<Long, Active>>(emptyMap())
    val active: StateFlow<Map<Long, Active>> = _active
    private var ticker: Job? = null

    init {
        scope.launch { foreground.collect { fg -> val now = clock(); runs.values.forEach { if (fg) it.resume(now) else it.pause(now) }; publish() } }
        scope.launch {
            env.collect { e ->
                val key = e?.let { NetworkSignature.identityKey(it) }
                runs.values.filter { it.startKey != key }.forEach { it.networkChanged = true; it.job?.cancel() }
            }
        }
    }

    suspend fun start(target: ToolTarget, toolId: ToolId, options: ToolOptions): Start {
        repo.ready.await()                                     // a run started at launch is never marked INTERRUPTED (rule 1)
        val run: Run = startLock.withLock {
            val e = env.value ?: return Start.NoNetwork
            if (runs.size >= MAX_ACTIVE_RUNS) return Start.TooMany
            if (!busy.tryClaim(target.hostId, HostBusyRegistry.Work.TOOL)) return Start.HostBusy
            val startedAt = clock()
            val optionsJson = ToolOptionsJson.encode(target, options)
            val runId = try {
                repo.startRun(target.hostId, toolId.key, target.port, startedAt, optionsJson)
            } catch (t: Throwable) {
                busy.release(target.hostId, HostBusyRegistry.Work.TOOL); throw t
            }
            Run(runId, target.hostId, toolId, target.port, startedAt, NetworkSignature.identityKey(e), e, optionsJson).also { r ->
                if (!foreground.value) r.pause(startedAt)
                // LAZY, assigned, registered, then started, all inside the lock (plan review G14): a Stop or a network change can never
                // find the run in `runs` without its job and lose the cancel.
                val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) { execute(target, tools.getValue(toolId), options, r) }
                r.job = job
                runs[target.hostId] = r
                job.start()
            }
        }
        publish(); ensureTicker()
        return Start.Started(run.runId)
    }

    fun stop(hostId: Long) { runs[hostId]?.job?.cancel() }

    private suspend fun execute(target: ToolTarget, tool: Tool, options: ToolOptions, run: Run) {
        val progress = object : ToolProgress {
            override fun update(done: Int, total: Int?, found: Int, errors: Int) { run.done = done; run.total = total; run.found = found; run.errors = errors }
            override suspend fun finding(f: Finding) {
                run.recorded.incrementAndGet()
                try { repo.addFinding(run.runId, f.kind.name, f.value, f.detail, FindingExtraJson.encode(f)) }
                catch (ce: CancellationException) { throw ce } catch (ex: Exception) { log(ex) }   // logged, the run continues (11.12)
            }
            override suspend fun option(key: String, value: String) {
                // One UPDATE of optionsJson (spec 7.1): for example methodUsed after a calibration switch (plan review G8).
                val json = run.withOption(key, value)
                try { repo.setOptions(run.runId, json) } catch (ce: CancellationException) { throw ce } catch (ex: Exception) { log(ex) }
            }
        }
        var status = ToolRunStatus.STOPPED; var summary = ""
        try {
            var result: ToolResult? = null
            bind(target.ip, run.env) { result = tool.run(target, options, Control(run), progress) }
            val r = result ?: ToolResult(ToolRunStatus.FAILED, ToolCopy.failed("no result"))
            status = r.status; summary = r.summary
        } catch (ce: CancellationException) {
            status = ToolRunStatus.STOPPED
            summary = if (run.networkChanged) ToolCopy.NETWORK_CHANGED else ToolCopy.stopped(run.done, run.total, run.found)
        } catch (b: BindFailedException) {
            status = ToolRunStatus.FAILED; summary = ToolCopy.BIND_FAILED
        } catch (ex: Exception) {
            status = ToolRunStatus.FAILED; summary = ToolCopy.failed(ex.message ?: ex.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { end(run, status, summary) }
        }
    }

    private suspend fun end(run: Run, status: ToolRunStatus, summary: String) {
        val at = clock()
        try { repo.finish(run.runId, at, status, summary) } catch (first: Exception) {
            log(first)
            try { repo.finish(run.runId, at, status, summary) } catch (second: Exception) { log(second) }   // left RUNNING: INTERRUPTED next launch
        }
        runs.remove(run.hostId, run)
        busy.release(run.hostId, HostBusyRegistry.Work.TOOL)
        publish()
        messages.post(ToolCopy.finished(tools[run.toolId]?.name ?: ToolCopy.toolName(run.toolId), summary), run.hostId)
        if (status == ToolRunStatus.FAILED && run.recorded.get() == 0) return
        try { onFinished(run.hostId) } catch (ex: Exception) { log(ex) }
    }

    private fun publish() {
        val now = clock(); val fg = foreground.value
        _active.value = runs.values.associate { r ->
            r.hostId to Active(r.runId, r.hostId, r.toolId, r.port, r.activeMs(now), r.done, r.total, r.found, r.errors, !fg)
        }
    }

    /** One ticker while any run is live; it ends itself when the last run ends (rule 8). */
    private fun ensureTicker() {
        synchronized(this) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL_MS)
                    publish()
                    if (runs.isEmpty()) break
                }
            }
        }
    }

    private inner class Control(private val run: Run) : RunControl {
        override suspend fun gate() {
            if (!foreground.value) foreground.first { it }
            while (true) {
                val wait = run.bucket.acquireDelayMs()
                if (wait == 0L) return
                delay(wait)
            }
        }
        override val pauseEpoch: Int get() = run.pauseEpoch
        override fun excused(startEpoch: Int): Boolean = startEpoch != run.pauseEpoch || !foreground.value
    }

    private inner class Run(val runId: Long, val hostId: Long, val toolId: ToolId, val port: Int, startedAt: Long, val startKey: String, val env: NetworkEnv,
                            private var optionsJson: String) {
        @Synchronized fun withOption(key: String, value: String): String { optionsJson = ToolOptionsJson.withOption(optionsJson, key, value); return optionsJson }
        val bucket = RateBucket(MAX_REQUESTS_PER_SECOND, RateBucket.BURST, clock)
        val recorded = AtomicInteger(0)
        @Volatile var done = 0; @Volatile var total: Int? = null; @Volatile var found = 0; @Volatile var errors = 0
        @Volatile var pauseEpoch = 0; @Volatile var networkChanged = false
        @Volatile var job: Job? = null
        private var accumulatedMs = 0L
        private var segmentStart = startedAt          // -1 while paused
        @Synchronized fun pause(now: Long) { if (segmentStart >= 0) { accumulatedMs += now - segmentStart; segmentStart = -1; pauseEpoch++ } }
        @Synchronized fun resume(now: Long) { if (segmentStart < 0) { segmentStart = now; bucket.reset() } }
        @Synchronized fun activeMs(now: Long): Long = accumulatedMs + if (segmentStart >= 0) now - segmentStart else 0L
    }

    companion object {
        const val MAX_ACTIVE_RUNS = 4; const val KEEP_RUNS_PER_TOOL = 10; const val PROGRESS_INTERVAL_MS = 200L
        const val TOOL_THREADS = 16; const val MAX_REQUESTS_PER_SECOND = 100
    }
}
```
(`return Start.NoNetwork` inside `startLock.withLock { }` is a non-local return from `start`, which `withLock` allows because it is inline; `start` has a block body. A failure spanning a pause: the request records `pauseEpoch` before it starts, so `excused` is true whenever a pause began since, or the run is paused now, in whatever order the thaw and ON_START arrive. `KEEP_RUNS_PER_TOOL` is applied by `ToolRepository.startRun` (Task 2), which prunes in the same transaction.)

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add (imports `com.northwalessolutions.netprobe.engine.tools.*`, `com.northwalessolutions.netprobe.ui.home.ScanBinding`, `com.northwalessolutions.netprobe.ui.tools.ToolCopy`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`, `kotlinx.coroutines.launch`):
```kotlin
    /** The registered tools; each tool task (10 to 14) adds its line to toolMap(). A ToolId absent here never gets a chip. */
    val tools: Map<ToolId, Tool> by lazy { toolMap() }
    private fun toolMap(): Map<ToolId, Tool> = buildMap {
        // Task 10: DIR_BRUTEFORCE. Task 11: HTTP_FINGERPRINT. Task 12: TLS_CERTIFICATE. Task 13: SNMP_WALK. Task 15: SMB_SESSION.
    }
    /** Reading this lazy is the only place markInterrupted starts; no instrumented test reads it (Global Constraints). */
    val toolRunner: ToolRunner by lazy {
        appScope.launch { toolRepo.markInterrupted(ToolCopy::interrupted) }
        ToolRunner(appScope, toolDispatcher, tools, toolRepo, hostBusy, appMessages,
            networkEnvMonitor.env.stateIn(appScope, SharingStarted.Eagerly, networkEnvMonitor.current()),
            foreground,
            bind = { ip, e, block -> ScanBinding.run(ip, e, settings.state.value.maxPrefix, settings.state.value.extEnabled, networkEnvMonitor::binderFor) { block() } },
            onFinished = { hostId -> reidentifyFromTools(hostId) },
            log = { android.util.Log.e(AppScopes.LOG_TAG, "tool run", it) })
    }
    val toolRunActive: StateFlow<Boolean> by lazy { toolRunner.active.map { it.isNotEmpty() }.stateIn(appScope, SharingStarted.Eagerly, false) }
    /** Filled in Task 7 (identification from tool hints). */
    private suspend fun reidentifyFromTools(hostId: Long) { }
```
In `toolMap()` the `buildMap` body is empty until Task 10 adds its `put`; the comment lists which task adds which line. `reidentifyFromTools` is an empty function in this task and Task 7, the next task, gives it its body. Until then no tool is registered, so no run can finish and the empty function is never reached.

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt`: replace the keep-screen-on lines with:
```kotlin
    val extended by container.extendedRunActive.collectAsStateWithLifecycle()
    val toolRun by container.toolRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(extended || toolRun)
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.ToolRunner*" --tests "com.northwalessolutions.netprobe.engine.tools.ToolJsonTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ToolRunnerTest` (11), `ToolRunnerPauseTest` (3) and `ToolJsonTest` (3) pass; `BUILD SUCCESSFUL`. Every runner test drives the runner with `runCurrent()` (and `advanceTimeBy` where a delay or the ticker is involved), never `advanceUntilIdle()`: the runner, its collectors, its runs and its ticker live on `backgroundScope`, and since kotlinx-coroutines-test 1.7 `advanceUntilIdle()` returns as soon as only background work is queued (the same rule `HomeViewModelTest` documents), so it would run none of them (plan review G2). Each test fails on the bug it names: remove the end write, the release, the message, the pause gate or the clock stop and the matching assertion fails. A green result reached any other way, for example by moving the runner off `backgroundScope` or relaxing an assertion, is a bent test: stop and report instead.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolStore.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolJson.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolRunner.kt \
  app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerTestSupport.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolRunnerPauseTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolJsonTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "ToolRunner: one run per host, pause gate and rate ceiling, ticker, INTERRUPTED and end states"
```

---

### Task 7: Identification hints: toolHints on the evidence, the toolHint rule field, the seed rules, and wiring hintsFor into every identify path

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt` (`HostEvidence.toolHints`, `HostEvidenceBuilder.build`), `engine/identify/DeviceIdentifier.kt` (the `toolHint` field), `engine/identify/IdentifyRules.kt` (no change; `toolHint` reuses `pattern`), `app/src/main/res/raw/identify_rules.json` (14 hint rules before `gateway`), `AppContainer.kt` (`reidentifyFromTools`), `ui/detail/DetailViewModel.kt` (`scanHostOn` passes hints), `ui/home/HomeViewModel.kt` (`quickScanHostOn` and `discoverOn`/`RadarCollector` pass hints), `ui/home/RadarCollector.kt` (a hints provider param), `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierTest.kt` and `SyntheticEvidence.kt` (3A count and field-set edits)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierToolHintTest.kt`

**Interfaces:**
- Consumes: 3A's `HostEvidence`, `HostEvidenceBuilder.build`, `DeviceIdentifier`, `Rule`, `setIdentityIfNotWeaker`, `RadarCollector`, `ToolRepository.hintsFor` and `hostsWithRuns` (Task 2).
- Produces:
```kotlin
data class HostEvidence(..., val toolHints: List<String> = emptyList())
HostEvidenceBuilder.build(..., toolHints: List<String> = emptyList()): HostEvidence
// DeviceIdentifier handles field "toolHint", reason "toolHint: <hint>"
AppContainer.reidentifyFromTools(hostId: Long)   // ToolRunner.onFinished (Task 6) calls it
class RadarCollector(hosts, ports, identifier, ouiLookup, lock, hintsFor: suspend (Long) -> List<String> = { emptyList() }, removed: () -> Set<Pair<Long, String>>)
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierToolHintTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 4.7, Review Focus 4: tool hints add evidence, read by the toolHint field, with reasons "toolHint: <hint>". */
class DeviceIdentifierToolHintTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private fun ev(hints: List<String>) = HostEvidence(DiscoveredHost(ip), emptySet(), emptyMap(), null, toolHints = hints)
    private fun rule(id: String, pattern: String, vendor: String? = null, model: String? = null, os: String? = null, type: String? = null, weight: Int = 90) =
        Rule(id = id, field = "toolHint", pattern = pattern, weight = weight, vendor = vendor, model = model, os = os, type = type)
    private fun one(r: Rule, hints: List<String>) = DeviceIdentifier(listOf(r)).identify(ev(hints))

    @Test fun `a synology framework hint sets vendor and type and names itself in the reason`() {
        val r = one(rule("hint-synology-dsm", "^http\\.framework=Synology DSM$", vendor = "Synology", type = "NAS"), listOf("http.framework=Synology DSM"))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type); assertEquals(listOf("toolHint: http.framework=Synology DSM"), r.reasons)
    }
    @Test fun `a non matching hint yields nothing`() {
        assertEquals(DeviceIdentity.UNKNOWN, one(rule("hint-pihole", "^http\\.framework=Pi-hole$", model = "Pi-hole", type = "SERVER"), listOf("http.framework=Something Else")))
    }
    @Test fun `tls and snmp hints match their fields`() {
        assertEquals("Ubiquiti", one(rule("hint-tls-ubiquiti", "^tls\\.issuer=.*(UniFi|Ubiquiti)", vendor = "Ubiquiti", type = "ROUTER", weight = 70), listOf("tls.issuer=UniFi CA")).vendor)
        assertEquals("MikroTik", one(rule("hint-snmp-mikrotik", "^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.14988\\.", vendor = "MikroTik", os = "RouterOS", type = "ROUTER"), listOf("snmp.sysObjectID=1.3.6.1.4.1.14988.1")).vendor)
    }
    @Test fun `every seeded hint rule fires on its own sample`() {
        val rules = IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()).filter { it.field == "toolHint" }
        assertEquals(14, rules.size)
        for (r in rules) {
            val got = DeviceIdentifier(listOf(r)).identify(ev(listOf(r.sample!!)))
            assertNotEquals("${r.id} did not fire on its sample", DeviceIdentity.UNKNOWN, got)
            assertEquals("${r.id} weight", r.weight, got.confidence)
        }
    }
}
```
(The test reads `identify_rules.json` from the test resources, which 3A already wires as `getByName("test") { resources.srcDirs("src/main/res/raw") }`.)

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.DeviceIdentifierToolHintTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `No value passed for parameter 'toolHints'` (or `Unresolved reference 'toolHints'`), and the 14-rule count fails.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt`:
- Add `val toolHints: List<String> = emptyList()` to `HostEvidence`:
```kotlin
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?, val toolHints: List<String> = emptyList())
```
- In `HostEvidenceBuilder.build`, add the parameter and pass it:
```kotlin
    fun build(host: DiscoveredHost, stored: Map<Int, ServiceInfo>, newPorts: Set<Int>, newServices: Map<Int, ServiceInfo>, ouiVendor: String?, toolHints: List<String> = emptyList()): HostEvidence {
        val services = HashMap(stored)
        for ((port, n) in newServices) services[port] = ServiceMerge.merge(stored[port], n)
        return HostEvidence(host, stored.keys + newPorts + newServices.keys, services, ouiVendor, toolHints)
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifier.kt`: in the `when (r.field)` of `identify`, add the `toolHint` case (it reads `e.toolHints`, port is null so the reason reads "toolHint: <hint>"):
```kotlin
                "toolHint" -> e.toolHints.firstNotNullOfOrNull { c -> re?.find(c)?.let { m -> Hit(c, null, group1Of(m)) } }
```

`app/src/main/res/raw/identify_rules.json`: insert these 14 lines immediately before the `{"id":"gateway",...}` line (hints only ever add evidence; they never lower an identity because tool-triggered writes use `setIdentityIfNotWeaker`):
```json
 {"id":"hint-synology-dsm","field":"toolHint","pattern":"^http\\.framework=Synology DSM$","vendor":"Synology","type":"NAS","weight":90,"sample":"http.framework=Synology DSM"},
 {"id":"hint-pihole","field":"toolHint","pattern":"^http\\.framework=Pi-hole$","model":"Pi-hole","type":"SERVER","weight":90,"sample":"http.framework=Pi-hole"},
 {"id":"hint-unifi","field":"toolHint","pattern":"^http\\.framework=UniFi OS$","vendor":"Ubiquiti","type":"ROUTER","weight":70,"sample":"http.framework=UniFi OS"},
 {"id":"hint-hue","field":"toolHint","pattern":"^http\\.framework=Philips Hue$","vendor":"Philips Hue","type":"IOT","weight":90,"sample":"http.framework=Philips Hue"},
 {"id":"hint-home-assistant","field":"toolHint","pattern":"^http\\.framework=Home Assistant$","model":"Home Assistant","type":"SERVER","weight":90,"sample":"http.framework=Home Assistant"},
 {"id":"hint-proxmox","field":"toolHint","pattern":"^http\\.framework=Proxmox VE$","model":"Proxmox VE","type":"SERVER","weight":90,"sample":"http.framework=Proxmox VE"},
 {"id":"hint-openwrt","field":"toolHint","pattern":"^http\\.framework=OpenWrt LuCI$","os":"OpenWrt","type":"ROUTER","weight":90,"sample":"http.framework=OpenWrt LuCI"},
 {"id":"hint-tls-synology","field":"toolHint","pattern":"^tls\\.subject=.*Synology","vendor":"Synology","type":"NAS","weight":70,"sample":"tls.subject=CN=synology,O=Synology Inc."},
 {"id":"hint-tls-ubiquiti","field":"toolHint","pattern":"^tls\\.issuer=.*(UniFi|Ubiquiti)","vendor":"Ubiquiti","type":"ROUTER","weight":70,"sample":"tls.issuer=CN=UniFi CA"},
 {"id":"hint-snmp-synology","field":"toolHint","pattern":"^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.6574\\.","vendor":"Synology","type":"NAS","weight":90,"sample":"snmp.sysObjectID=1.3.6.1.4.1.6574.1"},
 {"id":"hint-snmp-mikrotik","field":"toolHint","pattern":"^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.14988\\.","vendor":"MikroTik","os":"RouterOS","type":"ROUTER","weight":90,"sample":"snmp.sysObjectID=1.3.6.1.4.1.14988.1"},
 {"id":"hint-snmp-ubiquiti","field":"toolHint","pattern":"^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.41112\\.","vendor":"Ubiquiti","weight":70,"sample":"snmp.sysObjectID=1.3.6.1.4.1.41112.1"},
 {"id":"hint-snmp-cisco","field":"toolHint","pattern":"^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.9\\.","vendor":"Cisco","type":"ROUTER","weight":70,"sample":"snmp.sysObjectID=1.3.6.1.4.1.9.1"},
 {"id":"hint-snmp-netsnmp","field":"toolHint","pattern":"^snmp\\.sysObjectID=1\\.3\\.6\\.1\\.4\\.1\\.(8072|2021)\\.","os":"Linux","weight":50,"sample":"snmp.sysObjectID=1.3.6.1.4.1.8072.3.2.10"},
```

3A test edits (the rule set grew by 14):
- In `DeviceIdentifierTest`, `rules file loads with unique ids and valid fields`: change `assertEquals(118, rules.size)` to `assertEquals(132, rules.size)`, and add `"toolHint"` to the `fields` set.
- In `DeviceIdentifierTest`, `new rules sit between ssh-dropbear and gateway in spec order`: change `assertEquals(57, ids.indexOf("gateway") - ids.indexOf("p-dsm-title"))` to `assertEquals(71, ids.indexOf("gateway") - ids.indexOf("p-dsm-title"))`, and after it add `assertEquals(ids.indexOf("p-airplay") + 1, ids.indexOf("hint-synology-dsm"))` and `assertEquals(ids.indexOf("hint-snmp-netsnmp") + 1, ids.indexOf("gateway"))`.
- In `SyntheticEvidence.forRule`, add a `toolHint` case before the `else`:
```kotlin
            "toolHint" -> HostEvidence(h, emptySet(), emptyMap(), null, toolHints = listOf(sample))
```

Wiring (hints only ever add evidence; every path that identifies a stored host passes `hintsFor`, so a re-identification keeps the tool-derived identity):

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: replace the Task 6 stub `reidentifyFromTools` with:
```kotlin
    /** ToolRunner.onFinished: re-identify from stored ports plus the host's tool hints, never lowering the identity (spec 4.7). */
    private suspend fun reidentifyFromTools(hostId: Long) {
        val h = hosts.byIdOnce(hostId) ?: return
        val ev = HostEvidenceBuilder.build(hosts.toDiscovered(h), ports.servicesFor(hostId), emptySet(), emptyMap(), h.mac?.let(ouiLookup), toolRepo.hintsFor(hostId))
        hosts.setIdentityIfNotWeaker(hostId, deviceIdentifier.identify(ev))
    }
```
(imports `com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder`.)

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: in `scanHostOn`, the post-scan identity write now passes hints:
```kotlin
        c.hosts.byIdOnce(hostId)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(hostId), found.keys.toSet(), found, e.mac?.let(c.ouiLookup), c.toolRepo.hintsFor(hostId))
            c.hosts.setIdentityIfNotWeaker(hostId, c.deviceIdentifier.identify(ev))
        }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: in `quickScanHostOn`, the post-scan write passes hints:
```kotlin
        c.hosts.byIdOnce(h.id)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(h.id), found.keys.toSet(), found, e.mac?.let(c.ouiLookup), c.toolRepo.hintsFor(h.id))
            c.hosts.setIdentityIfNotWeaker(h.id, c.deviceIdentifier.identify(ev))
        }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarCollector.kt`: add a hints provider so discovery keeps a host's tool-derived identity (3A discovery always writes, but passing the hints means an unchanged host keeps its tool identity, spec 4.7). The new parameter goes **before** `removed` (plan review G13): `removed` is passed as a trailing lambda in `HomeViewModel.discoverOn` (line 213) and in 3A's `RemoveDeviceTest.collector()`, and a new last parameter would capture that lambda and break both call sites. With `hintsFor` defaulted and placed before it, both call sites compile unchanged. The constructor becomes:
```kotlin
class RadarCollector(
    private val hosts: HostRepository, private val ports: PortRepository, private val identifier: DeviceIdentifier,
    private val ouiLookup: (String) -> String?, private val lock: Mutex,
    private val hintsFor: suspend (Long) -> List<String> = { emptyList() },
    private val removed: () -> Set<Pair<Long, String>>,
) {
```
and in `store` the evidence line becomes:
```kotlin
        val ev = HostEvidenceBuilder.build(hosts.toDiscovered(merged), ports.servicesFor(id), seen, emptyMap(), merged.mac?.let(ouiLookup), hintsFor(id))
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: in `discoverOn`, replace the collector line with the two lines below. The set of hosts with tool runs is read once per scan, so a host with no runs (nearly all of them) costs no extra query inside `hostWriteLock`, which Remove device waits on (plan review G13); a host whose first run finishes mid-scan is re-identified by `reidentifyFromTools` instead.
```kotlin
            val withRuns = c.toolRepo.hostsWithRuns()
            val collector = RadarCollector(c.hosts, c.ports, c.deviceIdentifier, c.ouiLookup, c.hostWriteLock,
                hintsFor = { id -> if (id in withRuns) c.toolRepo.hintsFor(id) else emptyList() }) { c.removedDuringScan.value }
```
3A's `RemoveDeviceTest.collector()` (`RadarCollector(hosts, ports, DeviceIdentifier(emptyList()), { null }, lock) { removed }`) is not edited: `hintsFor` takes its default and the trailing lambda still binds to `removed`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `DeviceIdentifierToolHintTest` (4) passes; the edited 3A `DeviceIdentifierTest` and the other identify tests stay green; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifier.kt \
  app/src/main/res/raw/identify_rules.json \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarCollector.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/identify/SyntheticEvidence.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierToolHintTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Identification hints: toolHints evidence, the toolHint rule, 14 seed rules, hintsFor wired into every identify path"
```

---

### Task 8: The bundled wordlists (authored here) and WordlistParser

**Files:**
- Create: `app/src/main/res/raw/wordlist_common_short.txt`, `wordlist_admin_panels.txt`, `wordlist_api_paths.txt` (transcribed from this task), `app/src/main/java/com/northwalessolutions/netprobe/wordlists/WordlistParser.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistParserTest.kt`, `BundledWordlistsTest.kt`

**Authoring rules (spec 5.6.1):** each file is plain UTF-8, LF line endings, ASCII only, one entry per line, no leading slash, no comments, no blank lines, no duplicates. The lists below are the implementer's own composition from well-known web and device paths, written out in full; they are **globally sorted** with `LC_ALL=C` ordering (so `.`-prefixed entries come first, then the rest alphabetically), which is distinctive and verifiable and reproduces no published list's ordering or headers. Nothing is downloaded. The three lists below were machine-checked for order, uniqueness and count while this plan was written. No entry is an action endpoint (plan review G10: `reboot.html`, `upgrade.html`, `apply.cgi`, `setup.cgi`, `webproc`, `cgi-bin/webproc`, `boaform/admin/formLogin`, `logout` and `save` were removed), and `BundledWordlistsTest` holds that line with a deny pattern.

**Interfaces:**
- Produces:
```kotlin
data class ParseResult(val entries: List<String>, val skipped: Int)
object WordlistParser { const val MAX_ENTRY_CHARS = 256; fun parse(input: InputStream): ParseResult }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistParserTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import org.junit.Assert.*
import org.junit.Test

/** spec 5.6.4: BOM, line endings, trimming, comments, blanks, leading slashes, duplicates, over-long and bad bytes. */
class WordlistParserTest {
    private fun parse(s: String) = WordlistParser.parse(s.toByteArray(Charsets.UTF_8).inputStream())
    private fun parse(b: ByteArray) = WordlistParser.parse(b.inputStream())

    @Test fun `splits on lf crlf and a lone cr and trims`() {
        assertEquals(listOf("a", "b", "c", "d"), parse("a\nb\r\nc\rd").entries)
        assertEquals(listOf("x", "y"), parse("  x  \n\ty\t").entries)
    }
    @Test fun `drops a bom, blanks and hash comments without counting them`() {
        val r = parse("\uFEFFadmin\n\n# a comment\nlogin\n")
        assertEquals(listOf("admin", "login"), r.entries); assertEquals(0, r.skipped)
    }
    @Test fun `strips leading slashes and drops a line that becomes empty`() {
        assertEquals(listOf("admin", "a/b"), parse("///admin\n//\n/a/b").entries)
    }
    @Test fun `removes duplicates keeping the first and the order`() {
        assertEquals(listOf("a", "b", "c"), parse("a\nb\na\nc\nb").entries)
    }
    @Test fun `skips and counts over long lines and control characters`() {
        val long = "a".repeat(257)
        val r = parse("ok\n$long\nbad\u0001char\nfine")
        assertEquals(listOf("ok", "fine"), r.entries); assertEquals(2, r.skipped)
    }
    @Test fun `skips and counts non utf8 bytes`() {
        val bytes = "good\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\nalsogood".toByteArray(Charsets.UTF_8)
        val r = parse(bytes)
        assertEquals(listOf("good", "alsogood"), r.entries); assertEquals(1, r.skipped)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/BundledWordlistsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import org.junit.Assert.*
import org.junit.Test

/**
 * spec 5.6.1: each list in its range, parses to itself, ASCII, no leading slash or duplicate, globally sorted, and (plan review G10)
 * no entry whose GET can change a device's state: a reboot, firmware or apply handler, a logout, a save. Brute-force sends HEAD and
 * GET to every entry on the Director's and his clients' devices, so an action endpoint in a bundled list is a footgun.
 */
class BundledWordlistsTest {
    private companion object {
        val STATE_CHANGING = Regex("(?i)(reboot|restart|shutdown|reset|factory|upgrade|apply|logout|logoff|signout|webproc|formlogin|setup\\.cgi|(^|/)save($|\\.))")
    }
    private fun read(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun check(name: String, min: Int, max: Int) {
        val text = read(name)
        assertFalse("$name must use LF only", text.contains("\r"))
        val lines = text.split("\n").filter { it.isNotEmpty() }
        assertTrue("$name has ${lines.size} entries, want $min..$max", lines.size in min..max)
        assertEquals("$name has a blank or trailing line", lines, text.trimEnd('\n').split("\n"))
        assertEquals("$name has duplicates", lines.size, lines.toSet().size)
        lines.forEach { assertFalse("$name entry has a leading slash: $it", it.startsWith("/")) }
        lines.forEach { l -> assertTrue("$name entry not ascii: $l", l.all { it.code in 33..126 }) }
        lines.forEach { assertFalse("$name entry can change device state: $it", STATE_CHANGING.containsMatchIn(it)) }
        assertEquals("$name is not sorted (LC_ALL=C)", lines.sortedWith(compareBy { it }), lines)
        val parsed = WordlistParser.parse(text.toByteArray(Charsets.UTF_8).inputStream())
        assertEquals("$name does not parse to itself", lines, parsed.entries); assertEquals(0, parsed.skipped)
    }
    @Test fun `common short`() { check("wordlist_common_short.txt", 250, 350) }
    @Test fun `the deny pattern catches the endpoints it exists for`() {
        for (bad in listOf("reboot.html", "cgi-bin/webproc", "apply.cgi", "boaform/admin/formLogin", "logout", "save", "admin/factory_reset"))
            assertTrue(bad, STATE_CHANGING.containsMatchIn(bad))
        for (fine in listOf("settings", "setup.html", "saved-searches", "firmware")) assertFalse(fine, STATE_CHANGING.containsMatchIn(fine))
    }
    @Test fun `admin panels`() { check("wordlist_admin_panels.txt", 120, 180) }
    @Test fun `api paths`() { check("wordlist_api_paths.txt", 120, 180) }
}
```
(`compareBy { it }` on Kotlin `String` is lexicographic by UTF-16 unit, which for this ASCII set matches `LC_ALL=C`. Keep the files sorted that way.)

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'WordlistParser'`, and the resources missing.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/wordlists/WordlistParser.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class ParseResult(val entries: List<String>, val skipped: Int)

/** Streaming parse and normalisation of a wordlist (spec 5.6.4), used for imports and verified against the bundled lists. */
object WordlistParser {
    const val MAX_ENTRY_CHARS = 256

    fun parse(input: InputStream): ParseResult {
        val entries = LinkedHashSet<String>()
        var skipped = 0
        val bytes = input.readBytes()
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        var i = start
        val line = ArrayList<Byte>()
        fun flush() {
            if (line.isEmpty() && entries.isEmpty()) { /* nothing yet */ }
            val raw = line.toByteArray(); line.clear()
            val text = decodeUtf8(raw) ?: run { if (raw.isNotEmpty()) skipped++; return }
            var t = text.trim()
            if (t.isEmpty() || t.startsWith("#")) return
            t = t.trimStart('/')
            if (t.isEmpty()) return
            if (t.length > MAX_ENTRY_CHARS || t.any { it.isISOControl() }) { skipped++; return }
            entries.add(t)
        }
        while (i < bytes.size) {
            val b = bytes[i]
            when (b) {
                '\n'.code.toByte() -> { flush(); i++ }
                '\r'.code.toByte() -> { flush(); i++; if (i < bytes.size && bytes[i] == '\n'.code.toByte()) i++ }
                else -> { line.add(b); i++ }
            }
        }
        flush()
        return ParseResult(entries.toList(), skipped)
    }

    private fun decodeUtf8(raw: ByteArray): String? {
        if (raw.isEmpty()) return ""
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString() }.getOrNull()
    }
}
```
(The `flush` for a blank or comment line does not count as skipped; only an over-long, control-bearing or non-UTF-8 line counts. A trailing newline flushes an empty final line, which is dropped without counting.)

`app/src/main/res/raw/wordlist_common_short.txt` (transcribe exactly; LF endings; the full list: 298 entries, globally sorted):
```
.DS_Store
.aws/credentials
.bash_history
.bashrc
.config
.env
.env.bak
.env.local
.env.prod
.git-credentials
.git/HEAD
.git/config
.git/index
.gitignore
.htaccess
.htpasswd
.npmrc
.ssh/id_rsa
.ssh/known_hosts
.svn/entries
.well-known/security.txt
about
account
accounts
actuator
admin
admin.php
administrator
ajax
api
app
app.js
application.wadl
apps
archive
assets
auth
backend
backup
backup.sql
backup.tar.gz
backup.zip
backups
bin
blog
board
boot
bower.json
build
bundle.js
cache
cart
cgi-bin
changelog
changelog.txt
checkout
cloud
cms
composer.json
composer.lock
conf
config
config.bak
config.inc.php
config.json
config.php
config.xml
console
contact
content
controlpanel
core
cron
crossdomain.xml
css
dashboard
data
database
database.sql
db
debug
default
demo
deploy
dev
dist
doc
docker-compose.yml
dockerfile
docs
download
downloads
dump
dump.sql
editor
email
engine
error
error_log
errors
export
feed
file
files
forum
ftp
gallery
git
graphql
guest
help
home
host
htdocs
http
humans.txt
icons
id_rsa
images
img
import
include
includes
index
index.html
index.php
info
info.php
install
install.php
internal
intranet
inventory
invoice
js
json
lang
latest
lib
library
license
license.txt
list
local
log
log.txt
login
login.php
logs
mail
main
main.js
manage
manager
media
member
members
menu
message
messages
meta
mobile
modules
monitor
my
mysql
new
news
node_modules
notes
oauth
old
order
orders
out
package-lock.json
package.json
page
pages
panel
password
passwords
payment
payments
phpinfo.php
phpmyadmin
plugins
portal
post
posts
private
prod
product
products
profile
profiles
public
query
readme
readme.md
readme.txt
register
release
releases
remote
repo
report
reports
resources
rest
robots.txt
root
rss
sample
script
scripts
search
secret
secrets
secure
security
server-info
server-status
service
services
session
sessions
settings
setup
setup.php
shop
sitemap
sitemap.xml
sites
slack
smb
soap
software
source
sql
src
ssh
ssl
staff
stage
staging
static
stats
status
storage
store
style.css
styles
support
swagger
sync
sys
system
tag
tags
temp
template
templates
test
test.php
testing
tests
tmp
token
tools
tracking
upload
upload.php
uploads
user
users
util
utils
v1
v2
var
vendor
version
view
views
web
web.config
webadmin
webmail
webservice
wp-admin
wp-config.php
wp-content
wp-includes
wp-login.php
www
xml
xmlrpc.php
```

`app/src/main/res/raw/wordlist_admin_panels.txt` (device and appliance panels; the full list: 153 entries, globally sorted):
```
HNAP1
account/login
adm
adm.cgi
adm/index.html
admin
admin.cgi
admin.html
admin.php
admin/admin.php
admin/config
admin/dashboard
admin/home
admin/index.html
admin/index.php
admin/login
admin/login.php
admin/settings
admin/users
admin1
admin_area
adminconsole
admincp
adminer.php
administration
administrator
administrator/index.php
adminpanel
advanced.htm
basic.htm
camera
cgi-bin
cgi-bin/admin.cgi
cgi-bin/login.cgi
cgi-bin/luci
cgi-bin/status.cgi
cgi/index
cli
cloudcmd
cockpit
config
config.html
config/login
configuration
console
control
controlpanel
cp
dana-na/auth/url_default/welcome.cgi
dashboard
device
device.rsp
diag.html
dsm
dvr
ecp
firewall
firmware
gateway
grafana
gui
hassio
hnap
home.asp
home.htm
iDRAC
ilo
index.asp
index.cgi
info.html
ipmi
jenkins
kibana
lighttpd
login
login.asp
login.cgi
login.htm
login.html
login.php
login.rsp
logon
luci
maintenance
management
manager
manager/html
mgmt
mikrotik
monitor
mqtt
myadmin
nas
netadmin
netdata
network
nmc
nodered
octoprint
opnsense
overview
owa
panel
pfadmin
phpmyadmin
phppgadmin
pihole
plex
portainer
portal
portal/login
printer
prometheus
proxmox
qnap
remote
router
rpc
settings
setup
setup.html
status.htm
status.html
supervisor
synology
sysmgmt
system
system/login
tr069
traefik
ui
ui/login
unifi
userRpm
users
usg
vpn
vpns
web
web/index.html
webadmin
webcm
webconsole
webdav
webfig
webman
webmanager
webpanel
webui
wireless.htm
wizard
wui
zabbix
```

`app/src/main/res/raw/wordlist_api_paths.txt` (API and discovery endpoints; the full list: 137 entries, globally sorted):
```
.well-known/assetlinks.json
.well-known/change-password
.well-known/host-meta
.well-known/jwks.json
.well-known/matrix/server
.well-known/oauth-authorization-server
.well-known/openid-configuration
.well-known/webfinger
_api
_health
_status
actuator
actuator/beans
actuator/configprops
actuator/env
actuator/health
actuator/info
actuator/loggers
actuator/mappings
actuator/metrics
actuator/prometheus
actuator/threaddump
admin/api
api
api-docs
api.json
api/admin
api/auth
api/config
api/devices
api/docs
api/health
api/info
api/jsonws
api/login
api/logs
api/me
api/metrics
api/ping
api/session
api/settings
api/spec
api/stats
api/status
api/swagger.json
api/system
api/token
api/users
api/v1
api/v1/auth
api/v1/config
api/v1/devices
api/v1/health
api/v1/login
api/v1/me
api/v1/status
api/v1/system
api/v1/users
api/v2
api/v2/health
api/v2/users
api/v3
api/v4
api/version
apis
app/config
auth
auth/login
auth/token
config.json
debug/pprof
debug/vars
docs
docs/api
env
graphiql
graphql
graphql/console
health
healthcheck
healthz
info
jsonrpc
jwks.json
livez
login
manifest.json
metrics
metrics/prometheus
oauth/authorize
oauth/token
oauth2/token
openapi
openapi.json
openapi.yaml
openapi/v3
ping
query
readyz
rest
rest/v1
rpc
rpc/v1
server-info
server-status
service/health
sitemap.xml
socket.io
spec
stats
status
swagger
swagger-resources
swagger-ui
swagger-ui.html
swagger.json
swagger.yaml
swagger/index.html
system/info
telemetry
token
user
users
v1
v1/api
v1/health
v1/status
v1/users
v2
v2/api
v2/status
v3
version
version.json
well-known/health
ws
xmlrpc
```
- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `WordlistParserTest` (6) and `BundledWordlistsTest` (4) pass; `BUILD SUCCESSFUL`. The lists above were checked for order, uniqueness, ASCII and range with `LC_ALL=C sort -c` while writing this plan; a failure here is a transcription error to fix against this task's text.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/res/raw/wordlist_common_short.txt \
  app/src/main/res/raw/wordlist_admin_panels.txt \
  app/src/main/res/raw/wordlist_api_paths.txt \
  app/src/main/java/com/northwalessolutions/netprobe/wordlists/WordlistParser.kt \
  app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistParserTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/wordlists/BundledWordlistsTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Bundled wordlists (common-short, admin-panels, api-paths) and WordlistParser"
```

---

### Task 9: WordlistStore: built-ins, SAF import, delete, withEntries and the index

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/wordlists/BuiltInList.kt`, `wordlists/WordlistStore.kt`
- Modify: `AppContainer.kt` (`wordlistStore`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistStoreTest.kt`

**Interfaces:**
- Consumes: `WordlistParser` (Task 8), `AppSettings`/context for `filesDir` and the raw resources.
- Produces:
```kotlin
class BuiltInList(val id: String, val name: String, val open: () -> InputStream)
@Serializable data class WordlistMeta(val id: String, val name: String, val entries: Int, val importedAt: Long?, val builtIn: Boolean)
sealed interface ImportResult { data class Imported(val meta: WordlistMeta, val skipped: Int) : ImportResult; object TooLarge : ImportResult; object TooMany : ImportResult; object Empty : ImportResult; object Unreadable : ImportResult }
class WordlistStore(private val dir: File, private val builtIns: List<BuiltInList>) {
    val lists: StateFlow<List<WordlistMeta>>
    suspend fun import(displayName: String?, source: InputStream): ImportResult
    suspend fun delete(id: String)
    suspend fun <T> withEntries(id: String, block: suspend (Sequence<String>) -> T): T?
    companion object { const val MAX_IMPORT_BYTES = 10_485_760; const val MAX_ENTRIES = 200_000 }
}
AppContainer.wordlistStore: WordlistStore
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class WordlistStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun builtIn(id: String, name: String, body: String) = BuiltInList(id, name) { body.toByteArray(Charsets.UTF_8).inputStream() }
    private fun store(vararg b: BuiltInList) = WordlistStore(tmp.newFolder("wl-${b.size}-${System.nanoTime()}"), b.toList())
    private val commons = builtIn("builtin:common-short", "common-short", "admin\nlogin\nbackup")

    @Test fun `built ins come first with a computed entry count and cannot be deleted`() = runTest {
        val s = store(commons)
        val meta = s.lists.first().first()
        assertEquals("builtin:common-short", meta.id); assertEquals(3, meta.entries); assertTrue(meta.builtIn)
        s.delete("builtin:common-short")
        assertTrue(s.lists.first().any { it.id == "builtin:common-short" })
    }
    @Test fun `import writes a normalised file and an index entry, newest first`() = runTest {
        val s = store(commons)
        val r1 = s.import("list one.txt", "a\n/b\n#c\na\n".byteInputStream()) as ImportResult.Imported
        assertEquals("list one", r1.meta.name); assertEquals(2, r1.meta.entries); assertFalse(r1.meta.builtIn)
        val lists = s.lists.first()
        assertEquals("builtin:common-short", lists.first().id)       // built-in still first
        assertEquals(r1.meta.id, lists[1].id)
        assertEquals(listOf("a", "b"), s.withEntries(r1.meta.id) { it.toList() })
    }
    @Test fun `a name clash gets a numbered suffix`() = runTest {
        val s = store(commons)
        s.import("dup", "x".byteInputStream()); val second = s.import("dup", "y".byteInputStream()) as ImportResult.Imported
        assertEquals("dup (2)", second.meta.name)
    }
    @Test fun `the byte limit aborts mid stream with no size hint`() = runTest {
        val s = store()
        val big = ByteArray(WordlistStore.MAX_IMPORT_BYTES + 10) { 'a'.code.toByte() }
        assertEquals(ImportResult.TooLarge, s.import("big", big.inputStream()))
    }
    @Test fun `too many entries and empty are rejected and keep nothing`() = runTest {
        val s = store()
        val many = (1..WordlistStore.MAX_ENTRIES + 1).joinToString("\n") { "p$it" }        // about 1.5 MB, under the byte cap
        assertEquals(ImportResult.TooMany, s.import("m", many.byteInputStream()))
        assertEquals(ImportResult.Empty, s.import("e", "\n\n# only comments".byteInputStream()))
        assertTrue(s.lists.first().isEmpty())
        assertTrue("nothing written", tmp.root.walk().none { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".tmp")) })
    }
    @Test fun `delete removes the file and the entry`() = runTest {
        val s = store(commons); val m = (s.import("gone", "a\nb".byteInputStream()) as ImportResult.Imported).meta
        s.delete(m.id)
        assertNull(s.withEntries(m.id) { it.toList() })
        assertTrue(s.lists.first().none { it.id == m.id })
    }
    /** Counts close() so a test can see the reader released (plan review G12). */
    private class Tracking(body: String) : InputStream() {
        private val inner = body.toByteArray(Charsets.UTF_8).inputStream()
        @Volatile var closed = false
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() { closed = true }
    }
    @Test fun `withEntries closes the reader when the block is cancelled`() = runBlocking {
        var last: Tracking? = null
        val s = store(BuiltInList("builtin:t", "t") { Tracking((1..1000).joinToString("\n") { "p$it" }).also { last = it } })
        val entered = CompletableDeferred<String>()
        val job = launch(Dispatchers.Default) { s.withEntries("builtin:t") { seq -> entered.complete(seq.first()); awaitCancellation() } }
        assertEquals("p1", entered.await())
        assertFalse("open while the block runs", last!!.closed)
        job.cancelAndJoin()
        assertTrue("the reader must close when the block is cancelled", last!!.closed)
    }
    @Test fun `deleting a list during withEntries lets the block finish`() = runTest {
        val s = store(); val m = (s.import("live", "a\nb\nc".byteInputStream()) as ImportResult.Imported).meta
        val got = s.withEntries(m.id) { seq ->
            val it = seq.iterator(); val first = it.next()
            s.delete(m.id)                                           // spec 12.1: the run that is reading keeps reading
            listOf(first) + it.asSequence().toList()
        }
        assertEquals(listOf("a", "b", "c"), got)
        assertTrue(s.lists.first().none { it.id == m.id })
    }
    @Test fun `concurrent imports keep every index entry`() = runTest {
        val s = store()
        (1..8).map { n -> async(Dispatchers.Default) { s.import("l$n", "x$n".byteInputStream()) } }.awaitAll()
        assertEquals(8, s.lists.first().size)
        assertEquals(8, WordlistStore(s.dirForTest, emptyList()).lists.first().size)        // the index on disk has all eight
    }
    @Test fun `a corrupt index loads empty and orphan files are removed`() = runTest {
        val dir = tmp.newFolder("corrupt")
        File(dir, "index.json").writeText("{ not json"); File(dir, "orphan.txt").writeText("x"); File(dir, "half.txt.tmp").writeText("x")
        val s = WordlistStore(dir, listOf(commons))
        assertEquals(listOf("builtin:common-short"), s.lists.first().map { it.id })
        assertFalse(File(dir, "orphan.txt").exists())
        assertFalse("an interrupted import's temp file is removed (plan review G23)", File(dir, "half.txt.tmp").exists())
    }
}
```
(Every `import` call passes an `InputStream`, here from the stdlib `String.byteInputStream()` (plan review G20). The delete test also passes on Windows, where an open file cannot be deleted: the read finishes either way and the next `load()` removes the orphan.)

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.WordlistStoreTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'WordlistStore'`, `'BuiltInList'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/wordlists/BuiltInList.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import java.io.InputStream

/** A bundled list: its id, name and a fresh InputStream over its raw resource (spec 5.6.1). */
class BuiltInList(val id: String, val name: String, val open: () -> InputStream)
```

`app/src/main/java/com/northwalessolutions/netprobe/wordlists/WordlistStore.kt`:
```kotlin
package com.northwalessolutions.netprobe.wordlists

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.util.UUID

@Serializable data class WordlistMeta(val id: String, val name: String, val entries: Int, val importedAt: Long?, val builtIn: Boolean)

sealed interface ImportResult {
    data class Imported(val meta: WordlistMeta, val skipped: Int) : ImportResult
    object TooLarge : ImportResult; object TooMany : ImportResult; object Empty : ImportResult; object Unreadable : ImportResult
}

/**
 * Wordlists as files (spec 5.6.3): built-ins read from their raw resources, imports normalised into [dir] as <uuid>.txt with
 * metadata in index.json. [lists] is built-ins first (table order), then imported newest first. withEntries streams and always closes.
 */
class WordlistStore(private val dir: File, private val builtIns: List<BuiltInList>) {
    private val index = File(dir, "index.json")
    /** Guards every read-modify-write of index.json and [_lists] (plan review G23); withEntries never takes it. */
    private val indexLock = Mutex()
    internal val dirForTest: File get() = dir
    private val builtInMeta: List<WordlistMeta> = builtIns.map { b -> WordlistMeta(b.id, b.name, countOf(b.open()), null, true) }
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<List<WordlistMeta>> = _lists

    private fun load(): List<WordlistMeta> {
        dir.mkdirs()
        val imported = runCatching { Json.decodeFromString<List<WordlistMeta>>(index.takeIf { it.exists() }?.readText() ?: "[]") }.getOrElse { emptyList() }
        // Drop index entries whose file is gone, and delete .txt files with no index entry.
        val present = imported.filter { File(dir, fileName(it.id)).exists() }
        val known = present.map { fileName(it.id) }.toSet() + "index.json"
        dir.listFiles()?.forEach { if ((it.name !in known && it.name.endsWith(".txt")) || it.name.endsWith(TMP)) it.delete() }   // orphans and half imports
        if (present.size != imported.size) writeIndex(present)
        return builtInMeta + present.sortedByDescending { it.importedAt ?: 0 }
    }
    private fun fileName(id: String) = id.removePrefix("user:") + ".txt"
    private fun writeIndex(imported: List<WordlistMeta>) { index.writeText(Json.encodeToString(imported)) }
    private fun countOf(stream: InputStream): Int = stream.use { WordlistParser.parse(it).entries.size }

    suspend fun import(displayName: String?, source: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val bytes = readCapped(source) ?: return@withContext ImportResult.TooLarge
        val parsed = runCatching { WordlistParser.parse(bytes.inputStream()) }.getOrNull() ?: return@withContext ImportResult.Unreadable
        if (parsed.entries.size > MAX_ENTRIES) return@withContext ImportResult.TooMany
        if (parsed.entries.isEmpty()) return@withContext ImportResult.Empty
        val baseName = (displayName?.substringBeforeLast('.')?.trim().orEmpty()).ifEmpty { com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy.DEFAULT_NAME }
        val id = "user:" + UUID.randomUUID().toString()
        val target = File(dir, fileName(id))
        val temp = File(dir, fileName(id) + TMP); temp.writeText(parsed.entries.joinToString("\n"))
        if (!temp.renameTo(target)) { temp.delete(); return@withContext ImportResult.Unreadable }
        indexLock.withLock {
            val meta = WordlistMeta(id, uniqueName(baseName), parsed.entries.size, System.currentTimeMillis(), false)
            val imported = currentImported() + meta
            writeIndex(imported); _lists.value = builtInMeta + imported.sortedByDescending { it.importedAt ?: 0 }
            ImportResult.Imported(meta, parsed.skipped)
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        if (id.startsWith("builtin:")) return@withContext
        indexLock.withLock {
            File(dir, fileName(id)).delete()                       // a run reading it keeps its open reader (spec 5.6.3)
            val imported = currentImported().filterNot { it.id == id }
            writeIndex(imported); _lists.value = builtInMeta + imported.sortedByDescending { it.importedAt ?: 0 }
        }
    }

    suspend fun <T> withEntries(id: String, block: suspend (Sequence<String>) -> T): T? = withContext(Dispatchers.IO) {
        val reader: BufferedReader = when {
            id.startsWith("builtin:") -> builtIns.firstOrNull { it.id == id }?.open()?.bufferedReader() ?: return@withContext null
            else -> File(dir, fileName(id)).takeIf { it.exists() }?.bufferedReader() ?: return@withContext null
        }
        try { block(reader.lineSequence().mapNotNull { normalise(it) }) } finally { reader.close() }
    }

    private fun normalise(line: String): String? {
        var t = line.trim(); if (t.isEmpty() || t.startsWith("#")) return null
        t = t.trimStart('/'); if (t.isEmpty() || t.length > WordlistParser.MAX_ENTRY_CHARS || t.any { it.isISOControl() }) return null
        return t
    }
    private fun currentImported(): List<WordlistMeta> = _lists.value.filter { !it.builtIn }
    private fun uniqueName(base: String): String {
        val taken = _lists.value.map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2; while ("$base ($n)" in taken) n++; return "$base ($n)"
    }
    private fun readCapped(source: InputStream): ByteArray? = source.use {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
        while (true) {
            val n = it.read(buf); if (n < 0) break
            total += n; if (total > MAX_IMPORT_BYTES) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    companion object { const val MAX_IMPORT_BYTES = 10_485_760; const val MAX_ENTRIES = 200_000; private const val TMP = ".tmp" }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add:
```kotlin
    val wordlistStore by lazy {
        WordlistStore(java.io.File(context.filesDir, "wordlists"), listOf(
            BuiltInList("builtin:common-short", "common-short") { context.resources.openRawResource(R.raw.wordlist_common_short) },
            BuiltInList("builtin:admin-panels", "admin-panels") { context.resources.openRawResource(R.raw.wordlist_admin_panels) },
            BuiltInList("builtin:api-paths", "api-paths") { context.resources.openRawResource(R.raw.wordlist_api_paths) },
        ))
    }
```
(imports `com.northwalessolutions.netprobe.wordlists.*`.)

- [ ] **Step 4: Run the test to see it pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.wordlists.WordlistStoreTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `WordlistStoreTest` (10) passes; `BUILD SUCCESSFUL`. The reviewer confirms the cancellation test fails with the `finally { reader.close() }` removed and the TooMany case fails with the entry check removed.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/wordlists/BuiltInList.kt \
  app/src/main/java/com/northwalessolutions/netprobe/wordlists/WordlistStore.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/wordlists/WordlistStoreTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "WordlistStore: built-ins, SAF import, delete, withEntries and the index"
```

---

### Task 10: Directory brute-force: options, calibration, the not-found filter, adaptive concurrency and the request flow

**Roles:** stronger model (implement and review).

**Files:**
- Create: `engine/tools/dirbrute/DirBruteOptions.kt`, `dirbrute/AdaptiveLimiter.kt`, `dirbrute/NotFoundFilter.kt`, `dirbrute/Calibration.kt`, `dirbrute/DirBrutePaths.kt`, `dirbrute/DirBruteforceTool.kt` (all under `app/src/main/java/com/northwalessolutions/netprobe/`)
- Modify: `engine/tools/ToolId.kt` (the `DirBrute` case of `ToolOptions`), `engine/tools/ToolJson.kt` (brute-force fields in `ToolOptionsJson`), `AppContainer.kt` (the DIR_BRUTEFORCE entry in `toolMap()` and `resolveWordlist`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteTestSupport.kt`, `DirBruteOptionsTest.kt`, `AdaptiveLimiterTest.kt`, `DirBruteCalibrationTest.kt`, `DirBruteRunawayTest.kt`, `DirBruteFlowTest.kt`

**Interfaces:**
- Consumes: `HttpResult` / `HttpFailure` / `HttpResponse` / `HttpClient` (Task 5), Task 3 framework types and `ToolCopy`, `ToolOptionsJson` (Task 6), `WordlistStore` (Task 9).
- Produces:
```kotlin
@Serializable enum class DirMethod { HEAD_THEN_GET, GET_ONLY }
@Serializable data class DirBruteOptions(val wordlistId: String = "builtin:common-short", val extensions: List<String> = emptyList(), val method: DirMethod = DirMethod.HEAD_THEN_GET,
    val concurrency: Int = 8, val timeoutS: Int = 3, val statusCodes: List<Int> = listOf(200, 204, 301, 302, 307, 401, 403), val baseline: Boolean = true) { fun coerced(): DirBruteOptions }
enum class FieldError { EXT_CHARS, EXT_TOO_MANY, CODE_RANGE, CODE_EMPTY }
sealed interface Parsed<out T> { data class Ok<T>(val value: T) : Parsed<T>; data class Bad(val error: FieldError) : Parsed<Nothing> }
object DirBruteParse { fun extensions(text: String): Parsed<List<String>>; fun statusCodes(text: String): Parsed<List<Int>> }
sealed interface ToolOptions { object None; data class DirBrute(val options: DirBruteOptions, val wordlistName: String = "", val wordlistEntries: Int = 0) }
fun interface Exchange { suspend fun request(method: String, path: String): HttpResult }
data class LoadedWordlist(val id: String, val name: String, val count: Int, val stream: suspend (collect: suspend (String) -> Unit) -> Unit)
class AdaptiveLimiter(c: Int, now: () -> Long = System::currentTimeMillis, windowMs: Long = 3000) { val limitNow: Int; suspend fun acquire(); suspend fun release(); suspend fun onSuccess(); suspend fun onTimeoutOrReset() }
class DirBruteforceTool(exchangeFor: (ToolTarget, timeoutMs: Int) -> Exchange, resolve: suspend (String) -> LoadedWordlist?, random: () -> String = ::randomToken, clock: () -> Long = System::currentTimeMillis) : Tool
fun encodePath(word: String): String; fun randomToken(): String; fun urlOf(target: ToolTarget, path: String): String
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteTestSupport.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.*
import java.net.Inet4Address
import java.net.InetAddress

object DirBruteTestSupport {
    class CollectingProgress : ToolProgress {
        val findings = mutableListOf<Finding>()
        var lastDone = 0; var lastTotal: Int? = null; var lastFound = 0; var lastErrors = 0
        override fun update(done: Int, total: Int?, found: Int, errors: Int) { lastDone = done; lastTotal = total; lastFound = found; lastErrors = errors }
        override suspend fun finding(f: Finding) { findings += f }
        val options = mutableMapOf<String, String>()
        override suspend fun option(key: String, value: String) { options[key] = value }
        fun paths() = findings.filter { it.kind == FindingKind.PATH }.map { it.value }
    }
    /** Never pauses, never rate-limits; [epoch] lets a test simulate a pause that began during a request. */
    class TestControl : RunControl {
        var epoch = 0
        override suspend fun gate() {}
        override val pauseEpoch: Int get() = epoch
        override fun excused(startEpoch: Int) = startEpoch != epoch
    }
    fun target() = ToolTarget(7, InetAddress.getByName("10.0.3.9") as Inet4Address, 5000, tls = false, serverName = null)
    fun resp(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        HttpResult.Ok(HttpResponse(status, "x", listOf("Content-Length" to body.length.toString()) + headers, body.toByteArray(), false, body.length.toLong()))
    fun sized(status: Int, size: Int) = HttpResult.Ok(HttpResponse(status, "x", listOf("Content-Length" to "$size"), ByteArray(size), false, size.toLong()))
    fun tool(exchange: Exchange, words: List<String>, count: Int = words.size): DirBruteforceTool = DirBruteforceTool(
        exchangeFor = { _, _ -> exchange },
        resolve = { LoadedWordlist("builtin:common-short", "common-short", count) { collect -> words.forEach { collect(it) } } },
        random = { "abcdef012345" }, clock = { 0L })
    fun options(o: DirBruteOptions) = ToolOptions.DirBrute(o, "common-short", 0)
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteOptionsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** spec 5.1: coercion, the two text fields, and the JSON round trip. */
class DirBruteOptionsTest {
    @Test fun `concurrency and timeout are coerced to range`() {
        assertEquals(16, DirBruteOptions(concurrency = 99).coerced().concurrency); assertEquals(1, DirBruteOptions(concurrency = 0).coerced().concurrency)
        assertEquals(10, DirBruteOptions(timeoutS = 50).coerced().timeoutS); assertEquals(1, DirBruteOptions(timeoutS = 0).coerced().timeoutS)
    }
    @Test fun `extensions are trimmed, dot stripped, validated, deduped and capped`() {
        assertEquals(Parsed.Ok(listOf("php", "html")), DirBruteParse.extensions(" .php , html , php "))
        assertEquals(Parsed.Bad(FieldError.EXT_CHARS), DirBruteParse.extensions("ph p"))
        assertEquals(Parsed.Bad(FieldError.EXT_CHARS), DirBruteParse.extensions("toolongext"))
        assertEquals(Parsed.Bad(FieldError.EXT_TOO_MANY), DirBruteParse.extensions("a,b,c,d,e,f,g,h,i"))
        assertEquals(Parsed.Ok(emptyList<String>()), DirBruteParse.extensions("  "))
    }
    @Test fun `status codes parse, range check and need at least one`() {
        assertEquals(Parsed.Ok(listOf(200, 301, 403)), DirBruteParse.statusCodes("200, 301, 403, 200"))
        assertEquals(Parsed.Bad(FieldError.CODE_RANGE), DirBruteParse.statusCodes("99"))
        assertEquals(Parsed.Bad(FieldError.CODE_RANGE), DirBruteParse.statusCodes("2xx"))
        assertEquals(Parsed.Bad(FieldError.CODE_EMPTY), DirBruteParse.statusCodes(" "))
    }
    @Test fun `options round trip through json`() {
        val o = DirBruteOptions(extensions = listOf("php"), method = DirMethod.GET_ONLY, concurrency = 4)
        assertEquals(o, Json.decodeFromString<DirBruteOptions>(Json.encodeToString(DirBruteOptions.serializer(), o)))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/AdaptiveLimiterTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 5.3: AIMD, halve at most once per window, recover one per 20 successes up to C, never more than the limit in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveLimiterTest {
    @Test fun `halves at most once per window and recovers by one per twenty successes up to C`() = runTest {
        var now = 0L; val l = AdaptiveLimiter(c = 8, now = { now }, windowMs = 1000)
        assertEquals(8, l.limitNow)
        l.onTimeoutOrReset(); assertEquals(4, l.limitNow)
        l.onTimeoutOrReset(); assertEquals("same window, one halving only", 4, l.limitNow)
        now = 1001; l.onTimeoutOrReset(); assertEquals(2, l.limitNow)
        repeat(19) { l.onSuccess() }; assertEquals(2, l.limitNow)
        l.onSuccess(); assertEquals(3, l.limitNow)
        repeat(200) { l.onSuccess() }; assertEquals("never above C", 8, l.limitNow)
    }
    @Test fun `a waiter is admitted only within the limit and a cancelled waiter leaks no permit`() = runTest {
        val l = AdaptiveLimiter(c = 2, now = { 0L })
        l.acquire(); l.acquire()
        val third = launch { l.acquire() }
        advanceUntilIdle(); assertTrue("the third acquire must wait", third.isActive)
        third.cancelAndJoin()
        l.release(); l.release()
        val a = launch { l.acquire() }; val b = launch { l.acquire() }; advanceUntilIdle()
        assertFalse(a.isActive); assertFalse(b.isActive)                // two permits are free again: nothing leaked
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteCalibrationTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.resp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 2: calibration and the not-found filter (spec 5.4). */
class DirBruteCalibrationTest {
    private suspend fun run(ex: Exchange, words: List<String>, o: DirBruteOptions = DirBruteOptions(concurrency = 1)): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return tool(ex, words).run(target(), options(o), TestControl(), p) to p
    }

    @Test fun `a nonce varying catch all is filtered by the band`() = runTest {
        var n = 0
        val ex = Exchange { _, path -> if (path.startsWith("/admin")) resp(200, "A".repeat(5000)) else resp(200, "nonce" + "x".repeat(n++ % 7)) }
        val (r, p) = run(ex, listOf("home", "about", "admin", "contact"))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a path echoing 200 is filtered`() = runTest {
        val ex = Exchange { _, path -> if (path == "/admin") resp(200, "A".repeat(3000)) else resp(200, "Not found: $path") }
        val (_, p) = run(ex, listOf("home", "a-much-longer-word", "admin"))
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a catch all redirect is filtered and a real redirect is reported`() = runTest {
        val ex = Exchange { _, path -> if (path == "/admin") resp(301, "", listOf("Location" to "http://x/admin/")) else resp(302, "", listOf("Location" to "http://x/login")) }
        val (_, p) = run(ex, listOf("home", "admin", "contact"))
        assertEquals(listOf("/admin"), p.paths())
        assertEquals("301  0 B  to http://x/admin/", p.findings.first { it.value == "/admin" }.detail)
    }
    @Test fun `an extension group has its own baseline`() = runTest {
        // Bare unknowns 404; unknown .php paths answer 200 with a small page; /index.php is a real, larger page.
        val ex = Exchange { _, path -> when {
            path == "/index.php" -> resp(200, "P".repeat(2000))
            path.endsWith(".php") -> resp(200, "php handler")
            else -> resp(404, "")
        } }
        val (_, p) = run(ex, listOf("index", "home"), DirBruteOptions(extensions = listOf("php"), concurrency = 1))
        assertEquals(listOf("/index.php"), p.paths())
    }
    @Test fun `a head 404 get 200 server switches to get only and finds its paths`() = runTest {
        val ex = Exchange { method, path -> when {
            method == "HEAD" -> resp(404, "")
            path.startsWith("/admin") -> resp(200, "A".repeat(4000))
            else -> resp(200, "nf")
        } }
        val (r, p) = run(ex, listOf("home", "admin"))
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(listOf("/admin"), p.paths())
        assertEquals("the switch is written back to optionsJson (plan review G8)", "GET_ONLY", p.options["methodUsed"])
    }
    @Test fun `a group with only three responses is calibrated from what it has`() = runTest {
        var gets = 0
        val ex = Exchange { method, path -> when {
            path == "/abcdef012345" && method == "GET" && gets++ < 4 -> HttpResult.Failed(HttpFailure.Refused)   // two samples fail both tries
            path == "/admin" -> resp(200, "A".repeat(4000))
            else -> resp(200, "nf")
        } }
        val (_, p) = run(ex, listOf("home", "admin"))
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a failed calibration ends the run FAILED`() = runTest {
        val (r, _) = run(Exchange { _, _ -> HttpResult.Failed(HttpFailure.Timeout) }, listOf("a", "b"))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The server did not answer the first request", r.summary)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteRunawayTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Review Focus 2: more than 50 alike findings in the first 200 paths ends the run (spec 5.4.3). */
class DirBruteRunawayTest {
    @Test fun `more than fifty alike in the first two hundred ends the run`() = runTest {
        val p = CollectingProgress()
        val r = tool(Exchange { _, _ -> sized(200, 1024) }, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped: the server answers every path alike", r.summary)
        assertTrue("stopped near the 200 mark, at ${p.lastDone}", p.lastDone in 200..240)
    }
    @Test fun `fifty or fewer alike does not trigger the runaway`() = runTest {
        var i = 0
        val ex = Exchange { method, _ -> if (method == "GET" && i++ < 50) sized(200, 1024) else sized(404, 0) }
        val r = tool(ex, (1..250).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, method = DirMethod.GET_ONLY, concurrency = 1)), TestControl(), CollectingProgress())
        assertEquals("50 found in 250 paths", r.summary)
    }
    @Test fun `alike means within sixty four bytes, spread sizes never trigger`() {
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it) }))
        assertFalse(DirBruteforceTool.runaway((1..51).map { 200 to (1000L + it * 100) }))
        assertFalse(DirBruteforceTool.runaway((1..50).map { 200 to 1000L }))
        assertTrue(DirBruteforceTool.runaway((1..51).map { 200 to null }))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute/DirBruteFlowTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.common.RateBucket
import com.northwalessolutions.netprobe.engine.tools.FindingKind
import com.northwalessolutions.netprobe.engine.tools.RunControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.TestControl
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.options
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.resp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.sized
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.target
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteTestSupport.tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** spec 5.2 and 5.3: report-set filtering, fallback, retries, the abort, the cap, order, encoding, load, the rate ceiling and the excused rule. */
class DirBruteFlowTest {
    private val off = DirBruteOptions(baseline = false, concurrency = 1)

    @Test fun `only report-set statuses become findings, with url, extra and hints`() = runTest {
        val ex = Exchange { _, path -> when (path) { "/admin" -> resp(200, "0123456789"); "/secret" -> resp(403, "denied"); else -> resp(404) } }
        val p = CollectingProgress()
        val r = tool(ex, listOf("admin", "secret", "nope")).run(target(), options(off), TestControl(), p)
        assertEquals(listOf("/admin", "/secret"), p.paths()); assertEquals("2 found in 3 paths", r.summary)
        val admin = p.findings.first { it.value == "/admin" }
        assertEquals("200  10 B", admin.detail); assertEquals("http://10.0.3.9:5000/admin", admin.url)
        assertEquals(mapOf("status" to "200", "size" to "10"), admin.extra)
        assertEquals(listOf("http.path=/admin"), p.findings.filter { it.kind == FindingKind.HINT }.map { it.value })   // 403 gets no hint
    }
    @Test fun `extension order and the path total`() = runTest {
        val seen = mutableListOf<String>()
        val ex = Exchange { _, path -> seen += path; resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("a"), count = 1).run(target(), options(off.copy(extensions = listOf("php", "html"))), TestControl(), p)
        assertEquals(listOf("/a", "/a.php", "/a.html"), seen); assertEquals(3, p.lastTotal)
    }
    @Test fun `head 405 re-issues as get and after three switches to get only`() = runTest {
        val heads = AtomicInteger(0); val gets = AtomicInteger(0)
        val ex = Exchange { method, _ -> if (method == "HEAD") { heads.incrementAndGet(); resp(405) } else { gets.incrementAndGet(); resp(404) } }
        val p = CollectingProgress()
        tool(ex, (1..10).map { "p$it" }).run(target(), options(off), TestControl(), p)
        assertEquals(3, heads.get()); assertEquals(10, gets.get())
        assertNull("spec 5.2: only the calibration switch is written to methodUsed", p.options["methodUsed"])
    }
    @Test fun `head 501 also falls back`() = runTest {
        val ex = Exchange { method, path -> if (method == "HEAD") resp(501) else if (path == "/admin") resp(200, "x") else resp(404) }
        val p = CollectingProgress()
        tool(ex, listOf("admin")).run(target(), options(off), TestControl(), p)
        assertEquals(listOf("/admin"), p.paths())
    }
    @Test fun `a timeout is retried once then counts as an error`() = runTest {
        val calls = AtomicInteger(0)
        val ex = Exchange { _, path -> if (path == "/x") { calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Timeout) } else resp(404) }
        val p = CollectingProgress()
        val r = tool(ex, listOf("x", "y")).run(target(), options(off), TestControl(), p)
        assertEquals(2, calls.get()); assertEquals(1, p.lastErrors); assertEquals("0 found in 2 paths, 1 error", r.summary)
    }
    @Test fun `refused and malformed are not retried`() = runTest {
        val calls = AtomicInteger(0)
        val ex = Exchange { _, _ -> calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Malformed) }
        tool(ex, listOf("x")).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(1, calls.get())
    }
    @Test fun `an excused failure is retried after the resume and never counted`() = runTest {
        val control = TestControl(); var first = true
        val ex = Exchange { _, path ->
            if (path == "/x" && first) { first = false; control.epoch++; HttpResult.Failed(HttpFailure.Timeout) }   // a pause began mid-request
            else if (path == "/x") resp(200, "hello") else resp(404)
        }
        val p = CollectingProgress()
        val r = tool(ex, listOf("x")).run(target(), options(off), control, p)
        assertEquals(listOf("/x"), p.paths()); assertEquals(0, p.lastErrors); assertEquals("1 found in 1 path", r.summary)
    }
    @Test fun `fifty consecutive counted failures end the run FAILED`() = runTest {
        val r = tool(Exchange { _, _ -> HttpResult.Failed(HttpFailure.Refused) }, (1..100).map { "p$it" }).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("The server stopped answering after 50 paths", r.summary)
    }
    @Test fun `the five hundred finding cap ends the run DONE`() = runTest {
        var n = 0
        val ex = Exchange { method, _ -> if (method == "HEAD") sized(200, 0) else sized(200, (n++ % 400) * 100 + 10) }   // sizes spread, so no runaway
        val r = tool(ex, (1..600).map { "p$it" }).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("Stopped at 500 findings", r.summary)
    }
    @Test fun `paths are percent encoded`() = runTest {
        val seen = mutableListOf<String>()
        tool(Exchange { _, path -> seen += path; resp(404) }, listOf("a b", "c%20d", "e/f", "caf\u00e9")).run(target(), options(off), TestControl(), CollectingProgress())
        assertEquals(listOf("/a%20b", "/c%20d", "/e/f", "/caf%C3%A9"), seen)
    }
    @Test fun `after an injected timeout the in-flight peak drops to the halved limit`() = runTest {
        // Eight workers always exist, so only the limiter can hold the peak at 4 once the first timeout halves it (plan review G12).
        // Every fifth path times out once (its retry succeeds); each timeout resets the recovery count, and the fixed clock keeps
        // every later timeout in the first window, so the limit stays at 4 for the rest of the run.
        val inFlight = AtomicInteger(0); val peakBefore = AtomicInteger(0); val peakAfter = AtomicInteger(0)
        val timeouts = AtomicInteger(0); val startsAfter = AtomicInteger(0); val failedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val ex = Exchange { _, path ->
            val n = inFlight.incrementAndGet()
            if (timeouts.get() == 0) peakBefore.updateAndGet { maxOf(it, n) }
            else if (startsAfter.incrementAndGet() > 1) peakAfter.updateAndGet { maxOf(it, n) }   // the first start after was admitted at 8
            try {
                yield(); yield()
                if (path.removePrefix("/p").toInt() % 5 == 0 && failedOnce.add(path)) { timeouts.incrementAndGet(); HttpResult.Failed(HttpFailure.Timeout) }
                else resp(404)
            } finally { inFlight.decrementAndGet() }
        }
        val p = CollectingProgress()
        val r = tool(ex, (1..120).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, method = DirMethod.GET_ONLY, concurrency = 8)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(0, p.lastErrors)
        assertEquals("all eight run before the first timeout", 8, peakBefore.get())
        assertTrue("peak after the halving ${peakAfter.get()}", peakAfter.get() in 1..4)
    }
    @Test fun `the rate ceiling is taken per request, so a head 200 catch all stays at one hundred a second`() = runTest {
        // HEAD 200 on every path means every path sends HEAD and GET (plan review G3). A gate per path would let 200 a second through.
        val bucket = RateBucket { testScheduler.currentTime }
        val control = object : RunControl {
            override suspend fun gate() { while (true) { val d = bucket.acquireDelayMs(); if (d == 0L) return; delay(d) } }
            override val pauseEpoch: Int get() = 0
            override fun excused(startEpoch: Int) = false
        }
        val stamps = java.util.Collections.synchronizedList(mutableListOf<Long>()); var n = 0
        val ex = Exchange { method, _ ->
            stamps += testScheduler.currentTime
            if (method == "HEAD") sized(200, 0) else sized(200, (n++ % 400) * 100 + 10)   // sizes spread, so no runaway
        }
        tool(ex, (1..300).map { "p$it" }).run(target(), options(DirBruteOptions(baseline = false, concurrency = 16)), control, CollectingProgress())
        assertEquals(600, stamps.size)
        assertTrue("first second ${stamps.count { it < 1000 }}", stamps.count { it < 1000 } <= 110)   // the window plus the initial burst
        for (w in 1L..stamps.max() / 1000) {
            val inWindow = stamps.count { it >= w * 1000 && it < (w + 1) * 1000 }
            assertTrue("window $w: $inWindow", inWindow <= 100)
        }
    }
    @Test fun `a server that times out above two concurrent completes with no counted errors once the limit drops`() = runTest {
        val inFlight = AtomicInteger(0)
        val ex = Exchange { _, _ ->
            val n = inFlight.incrementAndGet()
            try { yield(); if (n > 2) HttpResult.Failed(HttpFailure.Timeout) else resp(404) } finally { inFlight.decrementAndGet() }
        }
        var tick = 0L                                    // every limiter reading is a new window, so each counted timeout halves
        val t = DirBruteforceTool({ _, _ -> ex }, { LoadedWordlist("builtin:common-short", "common-short", 40) { c -> (1..40).forEach { c("p$it") } } },
            { "abcdef012345" }, clock = { tick += 10_000; tick })
        val p = CollectingProgress()
        val r = t.run(target(), options(DirBruteOptions(baseline = false, concurrency = 8)), TestControl(), p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(0, p.lastErrors); assertEquals(40, p.lastDone)
    }
}
```
(The limit falls 8, 4, 2, 1 during the first burst of timeouts; each failed path's single retry then runs at a limit of 1 or 2, so it succeeds and nothing is counted. When the limit later recovers to 3, the third concurrent request times out, the limit halves again and that path's retry succeeds.)

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.dirbrute.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'DirBruteOptions'`, `'AdaptiveLimiter'`, `'DirBruteforceTool'`.

- [ ] **Step 3: Implement**

`engine/tools/ToolId.kt`: replace the `ToolOptions` declaration with (and add `import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions`):
```kotlin
sealed interface ToolOptions {
    object None : ToolOptions
    /** [wordlistName] and [wordlistEntries] are recorded in optionsJson so history still names a deleted list (spec 7.1). */
    data class DirBrute(val options: DirBruteOptions, val wordlistName: String = "", val wordlistEntries: Int = 0) : ToolOptions
}
```

`engine/tools/ToolJson.kt`: replace `ToolOptionsJson` with:
```kotlin
object ToolOptionsJson {
    fun encode(target: ToolTarget, options: ToolOptions): String = buildJsonObject {
        put("tls", target.tls)
        put("serverName", target.serverName?.let { JsonPrimitive(it) } ?: JsonNull)
        if (options is ToolOptions.DirBrute) {
            val o = options.options
            put("wordlistId", o.wordlistId); put("wordlistName", options.wordlistName); put("wordlistEntries", options.wordlistEntries)
            put("extensions", JsonArray(o.extensions.map { JsonPrimitive(it) }))
            put("method", o.method.name); put("methodUsed", o.method.name)   // the chosen method; the calibration switch rewrites methodUsed (spec 5.2, G8)
            put("concurrency", o.concurrency); put("timeoutS", o.timeoutS)
            put("statusCodes", JsonArray(o.statusCodes.map { JsonPrimitive(it) })); put("baseline", o.baseline)
        }
    }.toString()
}
```
(add `import kotlinx.serialization.json.JsonArray`.)

`engine/tools/dirbrute/DirBruteOptions.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.serialization.Serializable

@Serializable enum class DirMethod { HEAD_THEN_GET, GET_ONLY }

@Serializable
data class DirBruteOptions(
    val wordlistId: String = DEFAULT_WORDLIST,
    val extensions: List<String> = emptyList(),
    val method: DirMethod = DirMethod.HEAD_THEN_GET,
    val concurrency: Int = 8,
    val timeoutS: Int = 3,
    val statusCodes: List<Int> = listOf(200, 204, 301, 302, 307, 401, 403),
    val baseline: Boolean = true,
) {
    fun coerced(): DirBruteOptions = copy(concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY), timeoutS = timeoutS.coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S))
    companion object {
        const val DEFAULT_WORDLIST = "builtin:common-short"
        const val MIN_CONCURRENCY = 1; const val MAX_CONCURRENCY = 16; const val MIN_TIMEOUT_S = 1; const val MAX_TIMEOUT_S = 10
        const val MAX_EXTENSIONS = 8; const val MIN_STATUS = 100; const val MAX_STATUS = 599
    }
}

/** Which text-field rule failed; the options sheet maps each to its ToolCopy message (spec 5.1, 9). */
enum class FieldError { EXT_CHARS, EXT_TOO_MANY, CODE_RANGE, CODE_EMPTY }
sealed interface Parsed<out T> { data class Ok<T>(val value: T) : Parsed<T>; data class Bad(val error: FieldError) : Parsed<Nothing> }

object DirBruteParse {
    private val EXT = Regex("^[A-Za-z0-9]{1,8}$")
    fun extensions(text: String): Parsed<List<String>> {
        val parts = text.split(',').map { it.trim().removePrefix(".") }.filter { it.isNotEmpty() }
        if (parts.any { !EXT.matches(it) }) return Parsed.Bad(FieldError.EXT_CHARS)
        val distinct = parts.distinct()
        if (distinct.size > DirBruteOptions.MAX_EXTENSIONS) return Parsed.Bad(FieldError.EXT_TOO_MANY)
        return Parsed.Ok(distinct)
    }
    fun statusCodes(text: String): Parsed<List<Int>> {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Parsed.Bad(FieldError.CODE_EMPTY)
        val codes = parts.map { it.toIntOrNull() }
        if (codes.any { it == null || it !in DirBruteOptions.MIN_STATUS..DirBruteOptions.MAX_STATUS }) return Parsed.Bad(FieldError.CODE_RANGE)
        return Parsed.Ok(codes.filterNotNull().distinct())
    }
}
```

`engine/tools/dirbrute/AdaptiveLimiter.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A resizable concurrency limiter (spec 5.3, Gruff M3): starts at C, halves on a counted Timeout or Reset at most once per window,
 * and recovers by one per RECOVERY_SUCCESSES consecutive successes, never above C. After a halving no permit is granted until the
 * running count falls below the new limit, so effective concurrency shrinks without interrupting requests in flight.
 */
class AdaptiveLimiter(private val c: Int, private val now: () -> Long = System::currentTimeMillis, private val windowMs: Long = 3_000) {
    private val mutex = Mutex()
    private var limit = c
    private var active = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var consecutive = 0
    private var lastShrink = Long.MIN_VALUE / 2
    val limitNow: Int get() = limit

    suspend fun acquire() {
        val wait = mutex.withLock { if (active < limit) { active++; null } else CompletableDeferred<Unit>().also { waiters.addLast(it) } } ?: return
        try {
            wait.await()
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { if (!waiters.remove(wait)) { active--; admit() } } }   // already admitted: hand the permit back
            throw ce
        }
    }
    suspend fun release() { mutex.withLock { active--; admit() } }
    suspend fun onSuccess() {
        mutex.withLock {
            consecutive++
            if (consecutive >= RECOVERY_SUCCESSES) { consecutive = 0; if (limit < c) { limit++; admit() } }
        }
    }
    suspend fun onTimeoutOrReset() {
        mutex.withLock {
            consecutive = 0
            val t = now()
            if (t - lastShrink >= windowMs) { lastShrink = t; limit = maxOf(1, limit / 2) }
        }
    }
    /** Called under the lock: admit waiters while there is room under the current limit. */
    private fun admit() { while (active < limit && waiters.isNotEmpty()) { active++; waiters.removeFirst().complete(Unit) } }
    companion object { const val RECOVERY_SUCCESSES = 20 }
}
```

`engine/tools/dirbrute/NotFoundFilter.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import kotlin.math.max

/** One status's size band in one group (spec 5.4.2); [unknown] when a sample had no size, which forms its own match. */
data class SizeBand(val min: Long, val max: Long, val known: Boolean, val unknown: Boolean) {
    fun contains(size: Long?): Boolean = if (size == null) unknown else known && size in min..max
}
/** A group's baselines: a band per status, and for 3xx the set of normalised Locations. */
data class Baseline(val bands: Map<Int, SizeBand>, val locations: Map<Int, Set<String>>)

object NotFoundFilter {
    const val PAD_MIN = 32L
    const val PATH_TOKEN = "<PATH>"
    /** Body bytes minus (occurrences of the requested path x its byte length); null when truncated with no Content-Length. */
    fun normalisedSize(r: HttpResponse, requestedPath: String): Long? {
        if (r.bodyTruncated && r.contentLength == null) return null
        val pat = requestedPath.toByteArray(Charsets.UTF_8)
        return r.body.size.toLong() - occurrences(r.body, pat).toLong() * pat.size
    }
    private fun occurrences(body: ByteArray, pat: ByteArray): Int {
        if (pat.isEmpty() || pat.size > body.size) return 0
        var count = 0; var i = 0
        while (i <= body.size - pat.size) {
            var match = true
            for (j in pat.indices) if (body[i + j] != pat[j]) { match = false; break }
            if (match) { count++; i += pat.size } else i++
        }
        return count
    }
    fun normaliseLocation(location: String?, requestedPath: String): String? = location?.replace(requestedPath, PATH_TOKEN)
    /** [min - pad, max + pad] with pad = max(32, max - min) over the known sizes of one status's samples. */
    fun bandOf(sizes: List<Long?>): SizeBand {
        val known = sizes.filterNotNull()
        if (known.isEmpty()) return SizeBand(0, 0, known = false, unknown = true)
        val lo = known.min(); val hi = known.max(); val pad = max(PAD_MIN, hi - lo)
        return SizeBand(lo - pad, hi + pad, known = true, unknown = sizes.any { it == null })
    }
    /** Hidden when the group has a band for this status containing the size, and for 3xx the normalised Location is in its set. */
    fun isHidden(baseline: Baseline, status: Int, size: Long?, normalisedLocation: String?): Boolean {
        val band = baseline.bands[status] ?: return false
        if (!band.contains(size)) return false
        if (status !in 300..399) return true
        val set = baseline.locations[status] ?: return false
        return normalisedLocation != null && normalisedLocation in set
    }
}
```

`engine/tools/dirbrute/Calibration.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.RunControl

data class Calibration(val baselines: Map<String, Baseline>, val forceGetOnly: Boolean, val anyResponse: Boolean)

/**
 * spec 5.4.1: groups "" (bare) and one per extension; per group, GET 5 random paths; HEAD / and GET /, and HEAD the first bare random
 * path. A HEAD/GET status disagreement forces GET_ONLY. A failed sample is retried once. No response at all is a failed calibration.
 */
object Calibrator {
    const val SAMPLES_PER_GROUP = 5
    private const val RANDOM_LEN = 12

    private suspend fun request(exchange: Exchange, control: RunControl, method: String, path: String): HttpResult {
        control.gate()
        val first = exchange.request(method, path)
        if (first is HttpResult.Ok) return first
        control.gate()
        return exchange.request(method, path)
    }

    suspend fun calibrate(groups: List<String>, exchange: Exchange, control: RunControl, random: () -> String): Calibration {
        var anyResponse = false; var forceGetOnly = false
        val headRoot = request(exchange, control, "HEAD", "/"); val getRoot = request(exchange, control, "GET", "/")
        if (headRoot is HttpResult.Ok || getRoot is HttpResult.Ok) anyResponse = true
        if (headRoot is HttpResult.Ok && getRoot is HttpResult.Ok && headRoot.response.status != getRoot.response.status) forceGetOnly = true
        val baselines = HashMap<String, Baseline>()
        for (group in groups) {
            val sizes = HashMap<Int, MutableList<Long?>>(); val locations = HashMap<Int, MutableSet<String>>()
            for (i in 0 until SAMPLES_PER_GROUP) {
                val path = "/" + random().take(RANDOM_LEN) + if (group.isEmpty()) "" else ".$group"
                val r = request(exchange, control, "GET", path)
                if (group.isEmpty() && i == 0) {
                    val head = request(exchange, control, "HEAD", path)
                    if (head is HttpResult.Ok && r is HttpResult.Ok && head.response.status != r.response.status) forceGetOnly = true
                }
                if (r !is HttpResult.Ok) continue
                anyResponse = true
                val status = r.response.status
                sizes.getOrPut(status) { mutableListOf() } += NotFoundFilter.normalisedSize(r.response, path)
                if (status in 300..399) NotFoundFilter.normaliseLocation(r.response.header("Location"), path)?.let { locations.getOrPut(status) { mutableSetOf() } += it }
            }
            if (sizes.isNotEmpty()) baselines[group] = Baseline(sizes.mapValues { NotFoundFilter.bandOf(it.value) }, locations.mapValues { it.value.toSet() })
        }
        return Calibration(baselines, forceGetOnly, anyResponse)
    }
}
```

`engine/tools/dirbrute/DirBrutePaths.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.engine.tools.ToolTarget

private const val UNRESERVED_EXTRA = "-._~"
private const val HEX = "0123456789ABCDEF"
private fun unreserved(b: Int): Boolean = (b in 'A'.code..'Z'.code) || (b in 'a'.code..'z'.code) || (b in '0'.code..'9'.code) || UNRESERVED_EXTRA.indexOf(b.toChar()) >= 0
private fun isHex(b: Byte): Boolean { val c = (b.toInt() and 0xFF).toChar(); return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }

/** Percent-encodes every byte outside the RFC 3986 unreserved set, '/' and an existing valid %XX escape, as UTF-8 (spec 5.2). */
fun encodePath(word: String): String {
    val bytes = word.toByteArray(Charsets.UTF_8); val sb = StringBuilder(); var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == '/'.code || unreserved(b) -> sb.append(b.toChar())
            b == '%'.code && i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2]) -> sb.append('%')
            else -> sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        i++
    }
    return sb.toString()
}
/** 12 random lowercase letters and digits (spec 5.4.1). */
fun randomToken(): String { val a = "abcdefghijklmnopqrstuvwxyz0123456789"; return (1..12).map { a.random() }.joinToString("") }
fun urlOf(target: ToolTarget, path: String): String = (if (target.tls) "https" else "http") + "://" + target.ip.hostAddress + ":" + target.port + path
```

`engine/tools/dirbrute/DirBruteforceTool.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.dirbrute

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

fun interface Exchange { suspend fun request(method: String, path: String): HttpResult }

/** A resolved wordlist: id, display name, entry count (for the path total) and a streaming collector (spec 5.6.3). */
data class LoadedWordlist(val id: String, val name: String, val count: Int, val stream: suspend (collect: suspend (String) -> Unit) -> Unit)

/**
 * Directory brute-force (spec 5). The wordlist streams through a channel to [DirBruteOptions.concurrency] workers. Every path first
 * takes an adaptive-limiter permit, and every request it sends (HEAD and GET each) first passes control.gate() (pause and rate ceiling). HEAD then GET with the 405/501 fallback, the calibrated
 * filter per group, one retry for Timeout and Reset, the 50-in-a-row abort, the 500 cap and the runaway check. Excused failures
 * (a pause began during the request) are retried after the resume and never counted.
 */
class DirBruteforceTool(
    private val exchangeFor: (target: ToolTarget, timeoutMs: Int) -> Exchange,
    private val resolve: suspend (wordlistId: String) -> LoadedWordlist?,
    private val random: () -> String = ::randomToken,
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = ToolId.DIR_BRUTEFORCE
    override val name = ToolCopy.DIR_BRUTEFORCE
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.DIR_BRUTEFORCE, port, service)

    private data class Item(val path: String, val group: String)
    private sealed interface Outcome { data class Hit(val r: HttpResponse) : Outcome; object Miss : Outcome; data class Failure(val f: HttpFailure) : Outcome }

    /** Shared run state; every field is touched by the workers, so all are atomic or guarded. */
    private class State(getOnly: Boolean) {
        @Volatile var getOnly = getOnly
        val done = AtomicInteger(0); val found = AtomicInteger(0); val errors = AtomicInteger(0)
        val consecutive = AtomicInteger(0); val headFallbacks = AtomicInteger(0)
        val samples = ArrayList<Pair<Int, Long?>>()
        @Volatile var end: ToolResult? = null
        @Synchronized fun endWith(r: ToolResult) { if (end == null) end = r }
        @Synchronized fun sample(status: Int, size: Long?) { samples += status to size }
        @Synchronized fun snapshot(): List<Pair<Int, Long?>> = samples.toList()
        /** [found] counts claimed slots, which can pass the cap by the few dropped claims (plan review G22). */
        fun foundCount(): Int = minOf(found.get(), MAX_FINDINGS)
    }

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        val o = ((options as? ToolOptions.DirBrute)?.options ?: DirBruteOptions()).coerced()
        val list = resolve(o.wordlistId) ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.failed(ToolCopy.WORDLIST_FALLBACK))
        val timeoutMs = o.timeoutS * MS_PER_S
        val exchange = exchangeFor(target, timeoutMs)
        val total = list.count * (1 + o.extensions.size)
        progress.update(0, total, 0, 0)

        var getOnly = o.method == DirMethod.GET_ONLY
        val calibration = if (o.baseline) Calibrator.calibrate(listOf("") + o.extensions, exchange, control, random) else null
        if (calibration != null) {
            if (!calibration.anyResponse) return ToolResult(ToolRunStatus.FAILED, ToolCopy.BRUTE_NO_ANSWER)
            if (calibration.forceGetOnly && !getOnly) { getOnly = true; progress.option(METHOD_USED, DirMethod.GET_ONLY.name) }
        }
        val state = State(getOnly)
        val limiter = AdaptiveLimiter(o.concurrency, clock, timeoutMs.toLong())
        val reportSet = o.statusCodes.toSet()

        coroutineScope {
            val channel = Channel<Item>(capacity = CHANNEL_CAPACITY)
            val producer = launch {
                try {
                    list.stream { w ->
                        channel.send(Item("/" + encodePath(w), ""))
                        for (ext in o.extensions) channel.send(Item("/" + encodePath("$w.$ext"), ext))
                    }
                } finally { channel.close() }
            }
            val workers = List(o.concurrency) {
                launch {
                    for (item in channel) {
                        if (state.end != null) break
                        handle(item, target, exchange, control, limiter, reportSet, calibration, state, progress, total)
                    }
                }
            }
            workers.joinAll()
            producer.cancel()
        }

        state.end?.let { return it }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.bruteDone(state.foundCount(), state.done.get(), state.errors.get()))
    }

    private suspend fun handle(item: Item, target: ToolTarget, exchange: Exchange, control: RunControl, limiter: AdaptiveLimiter, reportSet: Set<Int>,
                               calibration: Calibration?, state: State, progress: ToolProgress, total: Int) {
        val outcome = attemptCounted(item.path, exchange, control, limiter, reportSet, state)
        val d = state.done.incrementAndGet()
        when (outcome) {
            is Outcome.Hit -> { state.consecutive.set(0); report(item, outcome.r, target, calibration, state, progress) }
            is Outcome.Miss -> state.consecutive.set(0)
            is Outcome.Failure -> {
                state.errors.incrementAndGet()
                if (state.consecutive.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES)
                    state.endWith(ToolResult(ToolRunStatus.FAILED, ToolCopy.bruteStoppedAnswering(d)))
            }
        }
        progress.update(d, total, state.foundCount(), state.errors.get())
        if (d == RUNAWAY_AFTER && runaway(state.snapshot())) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_RUNAWAY))
    }

    /**
     * One path to a counted outcome: excused failures retry after the resume; Timeout and Reset retry once after RETRY_DELAY_MS.
     * The gate is not taken here: [attempt] takes it before every request, so a HEAD then GET costs two rate tokens (plan review G3).
     * The epoch is read before the first gate, so a pause that starts while this path waits at the gate also excuses its failure:
     * at worst one uncounted extra try, never a counted error the Director caused.
     */
    private suspend fun attemptCounted(path: String, exchange: Exchange, control: RunControl, limiter: AdaptiveLimiter, reportSet: Set<Int>, state: State): Outcome {
        var retried = false
        while (true) {
            val epoch = control.pauseEpoch
            limiter.acquire()
            val outcome = try { attempt(path, exchange, control, reportSet, state) } finally { limiter.release() }
            if (outcome !is Outcome.Failure) { limiter.onSuccess(); return outcome }
            if (control.excused(epoch)) continue                         // spec 4.8: not counted, no limiter change, run again after the resume
            val transient = outcome.f == HttpFailure.Timeout || outcome.f == HttpFailure.Reset
            if (!transient) return outcome                               // Refused and Malformed: no retry
            limiter.onTimeoutOrReset()
            if (retried) return outcome
            retried = true
            delay(RETRY_DELAY_MS)
        }
    }

    /** HEAD then GET, or GET only; [RunControl.gate] (pause, then one rate token) runs before each request it sends. */
    private suspend fun attempt(path: String, exchange: Exchange, control: RunControl, reportSet: Set<Int>, state: State): Outcome {
        if (!state.getOnly) {
            control.gate()
            when (val head = exchange.request("HEAD", path)) {
                is HttpResult.Failed -> return Outcome.Failure(head.failure)
                is HttpResult.Ok -> {
                    val s = head.response.status
                    if (s == 405 || s == 501) { if (state.headFallbacks.incrementAndGet() >= HEAD_FALLBACK_SWITCH) state.getOnly = true }
                    else if (s !in reportSet) return Outcome.Miss
                }
            }
        }
        control.gate()
        return when (val get = exchange.request("GET", path)) {
            is HttpResult.Failed -> Outcome.Failure(get.failure)
            is HttpResult.Ok -> if (get.response.status in reportSet) Outcome.Hit(get.response) else Outcome.Miss
        }
    }

    private suspend fun report(item: Item, r: HttpResponse, target: ToolTarget, calibration: Calibration?, state: State, progress: ToolProgress) {
        val normalised = NotFoundFilter.normalisedSize(r, item.path)
        val location = if (r.status in 300..399) r.header("Location") else null
        val baseline = calibration?.baselines?.get(item.group)
        if (baseline != null && NotFoundFilter.isHidden(baseline, r.status, normalised, NotFoundFilter.normaliseLocation(location, item.path))) return
        if (state.end != null) return
        val slot = state.found.incrementAndGet()                   // claim the slot first, so concurrent hits never pass the cap (G22)
        if (slot > MAX_FINDINGS) return
        state.sample(r.status, normalised)
        val truncatedUnknown = r.bodyTruncated && r.contentLength == null
        val bytes = if (truncatedUnknown) null else (r.contentLength ?: r.body.size.toLong())
        val extra = buildMap { put("status", r.status.toString()); put("size", bytes?.toString() ?: ""); location?.let { put("location", it) } }
        progress.finding(Finding(FindingKind.PATH, item.path, ToolCopy.pathDetail(r.status, ToolCopy.size(bytes, truncatedUnknown), location), urlOf(target, item.path), extra))
        if (r.status == 200 || r.status == 204 || r.status == 401) progress.finding(Finding(FindingKind.HINT, "http.path=${item.path}"))
        if (slot == MAX_FINDINGS) state.endWith(ToolResult(ToolRunStatus.DONE, ToolCopy.BRUTE_CAP))
    }

    companion object {
        const val MAX_FINDINGS = 500; const val MAX_CONSECUTIVE_FAILURES = 50; const val HEAD_FALLBACK_SWITCH = 3
        const val RETRY_DELAY_MS = 250L; const val RUNAWAY_AFTER = 200; const val RUNAWAY_MIN_ALIKE = 50; const val RUNAWAY_BAND = 64L
        private const val CHANNEL_CAPACITY = 16; private const val MS_PER_S = 1000; private const val METHOD_USED = "methodUsed"

        /** spec 5.4.3: true when more than RUNAWAY_MIN_ALIKE findings share one status and their sizes lie within RUNAWAY_BAND bytes. */
        fun runaway(samples: List<Pair<Int, Long?>>): Boolean {
            for ((_, group) in samples.groupBy { it.first }) {
                val unknown = group.count { it.second == null }
                if (unknown > RUNAWAY_MIN_ALIKE) return true
                val sizes = group.mapNotNull { it.second }.sorted()
                var lo = 0
                for (hi in sizes.indices) {
                    while (sizes[hi] - sizes[lo] > RUNAWAY_BAND) lo++
                    if (hi - lo + 1 > RUNAWAY_MIN_ALIKE) return true
                }
            }
            return false
        }
    }
}
```

`AppContainer.kt`: in `toolMap()` add the first entry, and add `resolveWordlist` (imports `com.northwalessolutions.netprobe.engine.common.HttpClient`, `com.northwalessolutions.netprobe.engine.tools.dirbrute.*`):
```kotlin
        put(ToolId.DIR_BRUTEFORCE, DirBruteforceTool(
            exchangeFor = { t, timeoutMs ->
                val client = HttpClient(timeoutMs)
                val ctx = if (t.tls) HttpClient.trustAllContext() else null          // one SSLContext per run: TLS sessions resume
                Exchange { method, path -> client.request(t.ip, t.port, t.tls, method, path, null, ctx) }   // Host is always the IP
            },
            resolve = { id -> resolveWordlist(id) }))
```
```kotlin
    /** The chosen list, else common-short (spec 5.1, 11.10); streams through withEntries, so a list is never loaded whole. */
    private fun resolveWordlist(id: String): LoadedWordlist? {
        val lists = wordlistStore.lists.value
        val meta = lists.firstOrNull { it.id == id } ?: lists.firstOrNull { it.id == DirBruteOptions.DEFAULT_WORDLIST } ?: return null
        return LoadedWordlist(meta.id, meta.name, meta.entries) { collect -> wordlistStore.withEntries(meta.id) { seq -> for (w in seq) collect(w) } }
    }
```
(`seq` is a `Sequence`, so `for (w in seq) collect(w)` calls the suspending `collect` inside the suspending `withEntries` block.)

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.dirbrute.*" --console=plain 2>&1 | tail -30
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `DirBruteOptionsTest` (4), `AdaptiveLimiterTest` (2), `DirBruteCalibrationTest` (7), `DirBruteRunawayTest` (3) and `DirBruteFlowTest` (13) pass; `BUILD SUCCESSFUL`. The reviewer confirms each Review Focus 2 test fails when its rule is removed (drop the band, drop the HEAD/GET check, drop the runaway call), that the rate test fails with the gate moved back to once per path, and that the limiter test fails with `limiter.acquire()` removed.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/dirbrute \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolId.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/ToolJson.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/dirbrute
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Directory brute-force: options, calibration, the not-found filter, adaptive concurrency and the request flow"
```

---

### Task 11: HTTP fingerprint tool and the framework rules

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/FingerprintRules.kt`, `engine/tools/HttpFingerprintTool.kt`, `app/src/main/res/raw/http_fingerprints.json`
- Modify: `AppContainer.kt` (the HTTP_FINGERPRINT entry in `toolMap()`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolTestSupport.kt`, `FingerprintRulesTest.kt`, `HttpFingerprintTest.kt`

**Interfaces:**
- Consumes: `HttpResult` / `HttpResponse` / `HttpClient` (Task 5), 3A's `HttpTitleParser.title`, Task 3 framework types and `ToolCopy`, `randomToken` (Task 10), `RawResources`.
- Produces:
```kotlin
@Serializable data class FingerprintRule(val id: String, val source: String, val pattern: String, val name: String, val sample: String, val negativeSample: String? = null)
data class FpSources(val headers: List<Pair<String, String>>, val cookies: List<String>, val title: String?, val generator: String?, val body: String, val errorBody: String)
data class FpMatch(val name: String, val excerpt: String)
object FingerprintRules { fun load(json: String): List<FingerprintRule>; fun match(rule: FingerprintRule, sources: FpSources): FpMatch? }
fun interface HttpGet { suspend fun get(tls: Boolean, port: Int, path: String): HttpResult }
class HttpFingerprintTool(getFor: (ToolTarget) -> HttpGet, rules: List<FingerprintRule>, random: () -> String = ::randomToken) : Tool {
    companion object { const val FINGERPRINT_TIMEOUT_MS = 3000; const val MAX_ROBOTS_PATHS = 50; const val STEPS = 5; val HEADER_NAMES: List<String> } }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolTestSupport.kt` (shared by the companion-tool tests of Tasks 11 to 15; test code):
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import java.net.Inet4Address
import java.net.InetAddress

object ToolTestSupport {
    class CollectingProgress : ToolProgress {
        val findings = mutableListOf<Finding>()
        var lastDone = 0; var lastTotal: Int? = null; var lastFound = 0
        override fun update(done: Int, total: Int?, found: Int, errors: Int) { lastDone = done; lastTotal = total; lastFound = found }
        override suspend fun finding(f: Finding) { findings += f }
        fun of(kind: FindingKind) = findings.filter { it.kind == kind }
        fun hints() = of(FindingKind.HINT).map { it.value }
    }
    fun control() = object : RunControl { override suspend fun gate() {}; override val pauseEpoch = 0; override fun excused(startEpoch: Int) = false }
    /** A test bumps [epoch] inside a fake exchange to simulate a pause that began while that exchange was in flight (spec 4.8). */
    class EpochControl : RunControl {
        var epoch = 0
        override suspend fun gate() {}
        override val pauseEpoch: Int get() = epoch
        override fun excused(startEpoch: Int) = startEpoch != epoch
    }
    fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/FingerprintRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import org.junit.Assert.*
import org.junit.Test

/** spec 6.1: every seed rule fires on its sample and not on its negativeSample; $1 substitutes and trims. */
class FingerprintRulesTest {
    private val rules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText())
    private fun sources(rule: FingerprintRule, text: String) = when {
        rule.source == "title" -> FpSources(emptyList(), emptyList(), text, null, "", "")
        rule.source == "generator" -> FpSources(emptyList(), emptyList(), null, text, "", "")
        rule.source == "body" -> FpSources(emptyList(), emptyList(), null, null, text, "")
        rule.source == "errorBody" -> FpSources(emptyList(), emptyList(), null, null, "", text)
        rule.source == "cookie" -> FpSources(emptyList(), listOf(text), null, null, "", "")
        else -> FpSources(listOf(rule.source.removePrefix("header:") to text), emptyList(), null, null, "", "")
    }

    @Test fun `the file loads all 27 seed rules with unique ids`() {
        assertEquals(27, rules.size); assertEquals(27, rules.map { it.id }.toSet().size)
    }
    @Test fun `every rule matches its sample and not its negative sample`() {
        for (r in rules) {
            assertNotNull("${r.id} did not match its sample", FingerprintRules.match(r, sources(r, r.sample)))
            r.negativeSample?.let { assertNull("${r.id} matched its negative sample", FingerprintRules.match(r, sources(r, it))) }
        }
    }
    @Test fun `a version group substitutes and an empty group trims`() {
        val nginx = rules.single { it.id == "nginx" }
        assertEquals("nginx 1.25.3", FingerprintRules.match(nginx, sources(nginx, "nginx/1.25.3"))!!.name)
        assertEquals("nginx", FingerprintRules.match(nginx, sources(nginx, "nginx"))!!.name)
    }
    @Test fun `unifi os is exact and unifi network does not match`() {
        val unifi = rules.single { it.id == "unifi-os" }
        assertNotNull(FingerprintRules.match(unifi, sources(unifi, "UniFi OS"))); assertNull(FingerprintRules.match(unifi, sources(unifi, "UniFi Network")))
    }
    @Test fun `header rules read the named header case insensitively and the excerpt is the text read`() {
        val plex = rules.single { it.id == "plex" }
        val m = FingerprintRules.match(plex, FpSources(listOf("X-Plex-Protocol" to "1.0"), emptyList(), null, null, "", ""))!!
        assertEquals("Plex Media Server", m.name); assertEquals("1.0", m.excerpt)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/HttpFingerprintTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 6.1 and 12.1: requests, the hop, every finding kind, hints with versions stripped, cookie values never stored. */
class HttpFingerprintTest {
    private val rules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_fingerprints.json")!!.readText())
    private fun target(tls: Boolean = false, port: Int = 5000) = ToolTarget(7, ip("10.0.3.9"), port, tls, "chnas.local")
    private fun ok(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList(), reason: String = "OK") =
        HttpResult.Ok(HttpResponse(status, reason, headers, body.toByteArray(), false, body.length.toLong()))
    private suspend fun run(get: HttpGet, t: ToolTarget = target()): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return HttpFingerprintTool({ get }, rules) { "abcdef012345" }.run(t, ToolOptions.None, control(), p) to p
    }

    @Test fun `a root GET lost to a pause is retried once after the resume`() = runTest {
        val c = ToolTestSupport.EpochControl(); var roots = 0
        val get = HttpGet { _, _, path ->
            if (path == "/" && roots++ == 0) { c.epoch++; HttpResult.Failed(HttpFailure.Timeout) }      // a pause began mid-request
            else if (path == "/") ok(200, "<title>NAS</title>") else ok(404)
        }
        val p = CollectingProgress()
        val r = HttpFingerprintTool({ get }, rules) { "abcdef012345" }.run(target(), ToolOptions.None, c, p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("200 OK", p.of(FindingKind.STATUS).first().value)
    }
    @Test fun `names synology from the title and strips the version in the framework hint`() = runTest {
        val (r, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "<title>Synology DiskStation 7</title>", listOf("Server" to "nginx/1.25")) else ok(404, reason = "Not Found") })
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals(listOf("Synology DSM", "nginx 1.25"), p.of(FindingKind.FRAMEWORK).map { it.value })
        assertEquals("Matched title: Synology DiskStation 7", p.of(FindingKind.FRAMEWORK).first().detail)
        assertTrue("http.framework=Synology DSM" in p.hints()); assertTrue("http.framework=nginx" in p.hints())
        assertTrue("http.server=nginx/1.25" in p.hints()); assertTrue("http.title=Synology DiskStation 7" in p.hints())
        assertEquals("Synology DSM, ${p.findings.count { it.kind != FindingKind.HINT }} findings", r.summary)
    }
    @Test fun `status and headers carry the name as sent`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "", listOf("server" to "lighttpd", "X-Powered-By" to "PHP/8.2.7", "Via" to "1.1 proxy")) else ok(404) })
        assertEquals("200 OK", p.of(FindingKind.STATUS).single().value); assertEquals("GET /", p.of(FindingKind.STATUS).single().detail)
        assertEquals("http://10.0.3.9:5000/", p.of(FindingKind.STATUS).single().url)
        assertEquals(listOf("server" to "lighttpd", "X-Powered-By" to "PHP/8.2.7", "Via" to "1.1 proxy"), p.of(FindingKind.HEADER).map { it.detail to it.value })
        assertTrue("http.poweredBy=PHP/8.2.7" in p.hints())
    }
    @Test fun `cookie names appear with their flags but never their values`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "", listOf("Set-Cookie" to "PHPSESSID=secretvalue; Path=/; Secure; HttpOnly; SameSite=Lax")) else ok(404) })
        val c = p.of(FindingKind.COOKIE).single()
        assertEquals("PHPSESSID", c.value); assertEquals("Cookie, Secure, HttpOnly, SameSite=Lax", c.detail)
        assertTrue("no cookie value anywhere", p.findings.none { it.value.contains("secretvalue") || it.detail.orEmpty().contains("secretvalue") || it.extra.values.any { v -> v.contains("secretvalue") } })
        assertTrue("http.cookie=PHPSESSID" in p.hints())
    }
    @Test fun `generator, sitemap, security txt and the error page signature`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> when (path) {
            "/" -> ok(200, "<meta name=\"generator\" content=\"WordPress 6.4.2\">")
            "/sitemap.xml" -> ok(200, "<urlset><loc>a</loc><loc>b</loc></urlset>")
            "/.well-known/security.txt" -> ok(200, "Contact: mailto:sec@example.com\nExpires: 2027-01-01")
            else -> ok(404, "<html><address>Apache/2.4.57 (Debian) Server at 10.0.3.9 Port 5000</address></html>", reason = "Not Found")
        } })
        assertEquals("WordPress 6.4.2", p.of(FindingKind.GENERATOR).single().value)
        assertEquals("2 URLs", p.of(FindingKind.SITEMAP).single().detail)
        assertEquals("Contact: mailto:sec@example.com", p.of(FindingKind.SECURITY_TXT).single().detail)
        assertEquals("Apache/2.4.57 (Debian) Server at 10.0.3.9 Port 5000", p.of(FindingKind.ERROR_PAGE).single().value)
        assertTrue("WordPress 6.4.2" in p.of(FindingKind.FRAMEWORK).map { it.value }); assertTrue("http.framework=WordPress" in p.hints())
    }
    @Test fun `robots rules are counted and paths capped at fifty, wildcards without a url`() = runTest {
        val robots = (1..79).joinToString("\n") { "Disallow: /p$it" } + "\nAllow: /pub*"
        val (_, p) = run(HttpGet { _, _, path -> when (path) { "/" -> ok(200); "/robots.txt" -> ok(200, robots); else -> ok(404) } })
        assertEquals("80 rules", p.of(FindingKind.ROBOTS).single().detail)
        assertEquals(50, p.of(FindingKind.PATH).size)
        assertEquals("Disallowed in robots.txt", p.of(FindingKind.PATH).first().detail)
    }
    @Test fun `a file answering anything but 200 produces nothing`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200) else ok(403, "nope", reason = "Forbidden") })
        assertTrue(p.of(FindingKind.ROBOTS).isEmpty()); assertTrue(p.of(FindingKind.SITEMAP).isEmpty()); assertTrue(p.of(FindingKind.SECURITY_TXT).isEmpty())
    }
    @Test fun `a redirect on slash to another scheme and port on the same ip is followed once`() = runTest {
        val seen = mutableListOf<Triple<Boolean, Int, String>>()
        val (_, p) = run(HttpGet { tls, port, path -> seen += Triple(tls, port, path); when {
            !tls && path == "/" -> ok(302, "", listOf("Location" to "https://10.0.3.9:5001/"), reason = "Found")
            else -> ok(200, "<title>Landed</title>")
        } })
        assertEquals(Triple(true, 5001, "/"), seen[1])
        assertTrue("robots, sitemap, security.txt and the error page went to 5001", seen.drop(2).all { it.first && it.second == 5001 })
        val statuses = p.of(FindingKind.STATUS)
        assertEquals("GET /, to https://10.0.3.9:5001/", statuses[0].detail); assertEquals("GET https://10.0.3.9:5001/", statuses[1].detail)
        assertEquals(6, p.lastTotal)
    }
    @Test fun `a redirect to another ip is not followed`() = runTest {
        val seen = mutableListOf<Int>()
        run(HttpGet { _, port, path -> seen += port; if (path == "/") ok(302, "", listOf("Location" to "http://10.0.3.99/")) else ok(404) })
        assertEquals(5, seen.size); assertTrue(seen.all { it == 5000 })
    }
    @Test fun `no response on slash is FAILED`() = runTest {
        val (r, _) = run(HttpGet { _, _, _ -> HttpResult.Failed(HttpFailure.Timeout) })
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No HTTP response on this port", r.summary)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.HttpFingerprintTest" --tests "com.northwalessolutions.netprobe.engine.tools.FingerprintRulesTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'HttpFingerprintTool'`, `'FingerprintRules'`, and the resource missing.

- [ ] **Step 3: Implement**

`app/src/main/res/raw/http_fingerprints.json` (the 27 seed rules of spec 6.1; patterns are case-insensitive regexes, JSON-escaped):
```json
[
 {"id":"synology-title","source":"title","pattern":"Synology|DiskStation","name":"Synology DSM","sample":"Synology DiskStation"},
 {"id":"synology-body","source":"body","pattern":"SYNO\\.SDS|/webman/","name":"Synology DSM","sample":"var x = SYNO.SDS.Session; /webman/index.cgi"},
 {"id":"pihole","source":"title","pattern":"Pi-hole","name":"Pi-hole","sample":"Pi-hole - Login"},
 {"id":"unifi-os","source":"title","pattern":"^UniFi OS$","name":"UniFi OS","sample":"UniFi OS","negativeSample":"UniFi Network"},
 {"id":"hue","source":"body","pattern":"hue personal wireless lighting","name":"Philips Hue","sample":"<title>hue personal wireless lighting</title>"},
 {"id":"home-assistant","source":"title","pattern":"Home Assistant","name":"Home Assistant","sample":"Home Assistant"},
 {"id":"proxmox","source":"title","pattern":"Proxmox Virtual Environment","name":"Proxmox VE","sample":"pve - Proxmox Virtual Environment"},
 {"id":"openwrt","source":"title","pattern":"LuCI|OpenWrt","name":"OpenWrt LuCI","sample":"OpenWrt - LuCI"},
 {"id":"grafana","source":"title","pattern":"Grafana","name":"Grafana","sample":"Grafana"},
 {"id":"jellyfin","source":"title","pattern":"Jellyfin","name":"Jellyfin","sample":"Jellyfin"},
 {"id":"plex","source":"header:x-plex-protocol","pattern":".+","name":"Plex Media Server","sample":"1.0"},
 {"id":"wordpress-gen","source":"generator","pattern":"WordPress ?([\\d.]*)","name":"WordPress $1","sample":"WordPress 6.4.2"},
 {"id":"wordpress-body","source":"body","pattern":"/wp-content/|/wp-includes/","name":"WordPress","sample":"<link href='/wp-content/themes/x/style.css'>"},
 {"id":"drupal","source":"header:x-generator","pattern":"Drupal","name":"Drupal","sample":"Drupal 10 (https://www.drupal.org)"},
 {"id":"joomla","source":"generator","pattern":"Joomla","name":"Joomla","sample":"Joomla! - Open Source Content Management"},
 {"id":"php","source":"header:x-powered-by","pattern":"PHP/?([\\d.]*)","name":"PHP $1","sample":"PHP/8.2.7"},
 {"id":"php-cookie","source":"cookie","pattern":"^PHPSESSID$","name":"PHP","sample":"PHPSESSID"},
 {"id":"aspnet","source":"header:x-aspnet-version","pattern":".+","name":"ASP.NET","sample":"4.0.30319"},
 {"id":"aspnet-cookie","source":"cookie","pattern":"^ASP\\.NET_SessionId$","name":"ASP.NET","sample":"ASP.NET_SessionId"},
 {"id":"java","source":"cookie","pattern":"^JSESSIONID$","name":"Java servlet container","sample":"JSESSIONID"},
 {"id":"express","source":"header:x-powered-by","pattern":"^Express$","name":"Express (Node.js)","sample":"Express"},
 {"id":"laravel","source":"cookie","pattern":"^laravel_session$","name":"Laravel","sample":"laravel_session"},
 {"id":"tomcat","source":"errorBody","pattern":"Apache Tomcat/([\\d.]+)","name":"Apache Tomcat $1","sample":"<h3>Apache Tomcat/9.0.71</h3>"},
 {"id":"nginx","source":"header:server","pattern":"^nginx/?([\\d.]*)","name":"nginx $1","sample":"nginx/1.25.3"},
 {"id":"apache","source":"header:server","pattern":"^Apache/?([\\d.]*)","name":"Apache httpd $1","sample":"Apache/2.4.57 (Debian)"},
 {"id":"iis","source":"header:server","pattern":"^Microsoft-IIS/([\\d.]+)","name":"Microsoft IIS $1","sample":"Microsoft-IIS/10.0"},
 {"id":"lighttpd","source":"header:server","pattern":"^lighttpd/?([\\d.]*)","name":"lighttpd $1","sample":"lighttpd/1.4.69"}
]
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/FingerprintRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class FingerprintRule(val id: String, val source: String, val pattern: String, val name: String, val sample: String, val negativeSample: String? = null)

/** What a rule reads (spec 6.1): response headers in order, cookie names, the page title and generator, the page body and the error page body. */
data class FpSources(val headers: List<Pair<String, String>>, val cookies: List<String>, val title: String?, val generator: String?, val body: String, val errorBody: String)
data class FpMatch(val name: String, val excerpt: String)

object FingerprintRules {
    private const val EXCERPT_CHARS = 40
    private val json = Json { ignoreUnknownKeys = true }
    fun load(text: String): List<FingerprintRule> = runCatching { json.decodeFromString<List<FingerprintRule>>(text) }.getOrElse { emptyList() }

    /** The resolved product name and the matched text when the rule matches, else null; $1 is the first group, trimmed. */
    fun match(rule: FingerprintRule, sources: FpSources): FpMatch? {
        val re = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val candidates: List<String> = when {
            rule.source == "title" -> listOfNotNull(sources.title)
            rule.source == "generator" -> listOfNotNull(sources.generator)
            rule.source == "body" -> listOf(sources.body)
            rule.source == "errorBody" -> listOf(sources.errorBody)
            rule.source == "cookie" -> sources.cookies
            rule.source.startsWith("header:") -> { val h = rule.source.removePrefix("header:"); sources.headers.filter { it.first.equals(h, ignoreCase = true) }.map { it.second } }
            else -> emptyList()
        }
        for (c in candidates) {
            val m = re.find(c) ?: continue
            val group = if (m.groups.size > 1) m.groups[1]?.value.orEmpty() else ""
            return FpMatch(rule.name.replace("\$1", group).trim(), c.take(EXCERPT_CHARS))   // the excerpt is the text the rule read
        }
        return null
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/HttpFingerprintTool.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleParser
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.dirbrute.randomToken
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.net.Inet4Address

/** One GET through HttpClient with the target's name as Host; [tls] and [port] can differ from the target after the redirect hop. */
fun interface HttpGet { suspend fun get(tls: Boolean, port: Int, path: String): HttpResult }

/** HTTP fingerprint (spec 6.1): GET /, one redirect hop on the same IP, then robots.txt, sitemap.xml, security.txt and a random error page. */
class HttpFingerprintTool(
    private val getFor: (ToolTarget) -> HttpGet,
    private val rules: List<FingerprintRule>,
    private val random: () -> String = ::randomToken,
) : Tool {
    override val id = ToolId.HTTP_FINGERPRINT
    override val name = ToolCopy.HTTP_FINGERPRINT
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.HTTP_FINGERPRINT, port, service)

    private class Counter(var done: Int = 0, var total: Int = STEPS, var found: Int = 0)

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        val get = getFor(target)
        val n = Counter()
        suspend fun emit(f: Finding) { n.found++; progress.finding(f); progress.update(n.done, n.total, n.found) }
        suspend fun hint(v: String) { progress.finding(Finding(FindingKind.HINT, v)) }
        suspend fun fetch(tls: Boolean, port: Int, path: String): HttpResult {
            control.gate()
            val r = control.excusedOnce<HttpResult>({ it is HttpResult.Failed }) { get.get(tls, port, path) }   // spec 4.8 (plan review G5)
            n.done++; progress.update(n.done, n.total, n.found); return r
        }

        val root = (fetch(target.tls, target.port, "/") as? HttpResult.Ok)?.response ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.FINGERPRINT_NO_RESPONSE)
        var pageTls = target.tls; var pagePort = target.port; var page = root
        val location = if (root.status in 300..399) root.header("Location") else null
        val hop = location?.let { sameIpHop(it, target) }
        emit(Finding(FindingKind.STATUS, statusText(root), if (hop != null) ToolCopy.getRootTo(location) else ToolCopy.GET_ROOT, url(target.tls, target.ip, target.port, "/")))
        if (hop != null) {
            n.total = STEPS + 1
            val hopUrl = url(hop.tls, target.ip, hop.port, hop.path)
            val r = fetch(hop.tls, hop.port, hop.path)
            if (r is HttpResult.Ok) {
                emit(Finding(FindingKind.STATUS, statusText(r.response), ToolCopy.getUrl(hopUrl), hopUrl))
                page = r.response; pageTls = hop.tls; pagePort = hop.port
            }
        }
        val responses = if (page === root) listOf(root) else listOf(root, page)
        val headers = responses.flatMap { it.headers }.distinct()
        for (name in HEADER_NAMES) for ((sent, value) in headers.filter { it.first.equals(name, ignoreCase = true) }) emit(Finding(FindingKind.HEADER, value, sent))
        val cookies = responses.flatMap { it.headersAll("Set-Cookie") }.mapNotNull(::parseCookie).distinctBy { it.name }
        for (c in cookies) { emit(Finding(FindingKind.COOKIE, c.name, ToolCopy.cookieDetail(c.secure, c.httpOnly, c.sameSite))); hint("http.cookie=${c.name}") }

        val pageBody = String(page.body, Charsets.UTF_8)
        val title = HttpTitleParser.title(pageBody)
        title?.let { emit(Finding(FindingKind.TITLE, it, ToolCopy.PAGE_TITLE)); hint("http.title=$it") }
        val generator = GENERATOR.find(pageBody)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        generator?.let { emit(Finding(FindingKind.GENERATOR, it, ToolCopy.GENERATOR_META)); hint("http.generator=$it") }

        textAt(fetch(pageTls, pagePort, "/robots.txt"))?.let { body ->
            val rulesFound = ROBOTS_RULE.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
            emit(Finding(FindingKind.ROBOTS, "/robots.txt", ToolCopy.rules(rulesFound.size), url(pageTls, target.ip, pagePort, "/robots.txt")))
            for ((kind, path) in rulesFound.take(MAX_ROBOTS_PATHS)) {
                val pathUrl = if (path.contains('*') || path.contains('$')) null else url(pageTls, target.ip, pagePort, path)
                emit(Finding(FindingKind.PATH, path, if (kind.equals("Disallow", ignoreCase = true)) ToolCopy.DISALLOWED else ToolCopy.ALLOWED, pathUrl))
            }
        }
        textAt(fetch(pageTls, pagePort, "/sitemap.xml"))?.let { body ->
            emit(Finding(FindingKind.SITEMAP, "/sitemap.xml", ToolCopy.urls(LOC.findAll(body).count()), url(pageTls, target.ip, pagePort, "/sitemap.xml")))
        }
        textAt(fetch(pageTls, pagePort, "/.well-known/security.txt"))?.let { body ->
            val contact = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Contact:", ignoreCase = true) }?.substringAfter(':')?.trim()?.ifEmpty { null }
            emit(Finding(FindingKind.SECURITY_TXT, "/.well-known/security.txt", contact?.let { ToolCopy.contact(it) } ?: ToolCopy.PRESENT, url(pageTls, target.ip, pagePort, "/.well-known/security.txt")))
        }
        val errorBody = (fetch(pageTls, pagePort, "/" + random()) as? HttpResult.Ok)?.response?.let { String(it.body, Charsets.UTF_8) }.orEmpty()
        ERROR_SIGNATURE.find(errorBody)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.trim()?.let { emit(Finding(FindingKind.ERROR_PAGE, it, ToolCopy.ERROR_SIGNATURE)) }

        val sources = FpSources(headers, cookies.map { it.name }, title, generator, pageBody, errorBody)
        val names = LinkedHashSet<String>()
        for (rule in rules) {
            val m = FingerprintRules.match(rule, sources) ?: continue
            if (!names.add(m.name)) continue
            emit(Finding(FindingKind.FRAMEWORK, m.name, ToolCopy.matched(rule.source, m.excerpt)))
            hint("http.framework=${m.name.replace(VERSION_TAIL, "")}")
        }
        root.header("Server")?.let { hint("http.server=$it") }
        root.header("X-Powered-By")?.let { hint("http.poweredBy=$it") }
        root.header("WWW-Authenticate")?.let { REALM.find(it)?.groupValues?.get(1)?.let { realm -> hint("http.realm=$realm") } }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.fingerprintSummary(names.firstOrNull() ?: root.header("Server"), n.found))
    }

    private data class Cookie(val name: String, val secure: Boolean, val httpOnly: Boolean, val sameSite: String?)
    private data class Hop(val tls: Boolean, val port: Int, val path: String)

    /** Name and flags of one Set-Cookie header; the value is dropped here and never stored (spec 6.1). */
    private fun parseCookie(header: String): Cookie? {
        val parts = header.split(';').map { it.trim() }
        val name = parts.firstOrNull()?.substringBefore('=')?.trim()?.ifEmpty { null } ?: return null
        val attrs = parts.drop(1)
        return Cookie(name, attrs.any { it.equals("Secure", true) }, attrs.any { it.equals("HttpOnly", true) },
            attrs.firstOrNull { it.startsWith("SameSite=", true) }?.substringAfter('=')?.trim()?.ifEmpty { null })
    }
    /** A Location on the same IP with any scheme and port, or a relative path; anything else is not followed. */
    private fun sameIpHop(location: String, target: ToolTarget): Hop? {
        if (location.startsWith("/")) return Hop(target.tls, target.port, location)
        val m = ABSOLUTE.matchEntire(location) ?: return null
        if (m.groupValues[2] != target.ip.hostAddress) return null
        val tls = m.groupValues[1].equals("https", true)
        return Hop(tls, m.groupValues[3].toIntOrNull() ?: if (tls) HTTPS_PORT else HTTP_PORT, m.groupValues[4].ifEmpty { "/" })
    }
    private fun textAt(r: HttpResult): String? = (r as? HttpResult.Ok)?.response?.takeIf { it.status == HTTP_OK }?.let { String(it.body, Charsets.UTF_8) }
    private fun statusText(r: HttpResponse) = if (r.reason.isEmpty()) "${r.status}" else "${r.status} ${r.reason}"
    private fun url(tls: Boolean, ip: Inet4Address, port: Int, path: String) = (if (tls) "https" else "http") + "://" + ip.hostAddress + ":" + port + path

    companion object {
        const val FINGERPRINT_TIMEOUT_MS = 3000; const val MAX_ROBOTS_PATHS = 50; const val STEPS = 5
        val HEADER_NAMES = listOf("Server", "X-Powered-By", "X-AspNet-Version", "X-AspNetMvc-Version", "X-Generator", "Via", "WWW-Authenticate")
        private const val HTTP_OK = 200; private const val HTTP_PORT = 80; private const val HTTPS_PORT = 443
        private val GENERATOR = Regex("<meta[^>]*name=[\"']generator[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val ROBOTS_RULE = Regex("(?im)^\\s*(Disallow|Allow)\\s*:\\s*(\\S+)")
        private val LOC = Regex("<loc>", RegexOption.IGNORE_CASE)
        private val ERROR_SIGNATURE = Regex("<address>([^<]+)</address>|<center>(nginx[^<]*)</center>|(Apache Tomcat/[\\d.]+)", RegexOption.IGNORE_CASE)
        private val REALM = Regex("realm=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE)
        private val VERSION_TAIL = Regex("\\s+[\\d.]+$")
        private val ABSOLUTE = Regex("^(https?)://([^/:?#]+)(?::(\\d+))?(/[^#]*)?$", RegexOption.IGNORE_CASE)
    }
}
```
(The FRAMEWORK and hint order follows the rules file, so `synology-title` comes before `nginx` in the first test. `m.groupValues[4]` is the path including its leading slash, or empty when the Location has none.)

`AppContainer.kt`: in `toolMap()` add (imports `com.northwalessolutions.netprobe.engine.common.HttpResult`, `com.northwalessolutions.netprobe.data.RawResources`):
```kotlin
        put(ToolId.HTTP_FINGERPRINT, HttpFingerprintTool(
            getFor = { t ->
                val client = HttpClient(HttpFingerprintTool.FINGERPRINT_TIMEOUT_MS)
                val ctx = HttpClient.trustAllContext()
                HttpGet { tls, port, path ->
                    val named = client.request(t.ip, port, tls, "GET", path, t.serverName, if (tls) ctx else null)
                    // spec 4.9: a failed exchange with the name is retried once on the IP.
                    if (named is HttpResult.Failed && t.serverName != null) client.request(t.ip, port, tls, "GET", path, null, if (tls) ctx else null) else named
                }
            },
            rules = FingerprintRules.load(RawResources.read(context, R.raw.http_fingerprints))))
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.HttpFingerprintTest" --tests "com.northwalessolutions.netprobe.engine.tools.FingerprintRulesTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `FingerprintRulesTest` (5) and `HttpFingerprintTest` (10) pass; `BUILD SUCCESSFUL`. The pause test fails with the `excusedOnce` wrapper removed (the run ends FAILED "No HTTP response on this port").

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/FingerprintRules.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/HttpFingerprintTool.kt \
  app/src/main/res/raw/http_fingerprints.json \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/ToolTestSupport.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/FingerprintRulesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/HttpFingerprintTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "HTTP fingerprint tool and the framework rules"
```

---

### Task 12: TLS certificate tool, TlsPeek.details and the CertSummaries mapper

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/CertSummaries.kt`, `engine/tools/TlsCertificateTool.kt`; the keytool fixtures under `app/src/test/resources/tls/`
- Modify: `engine/serviceid/TlsPeek.kt` (`TlsDetails`, `details`; 3A's `certInfo` unchanged), `AppContainer.kt` (the TLS_CERTIFICATE entry in `toolMap()`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/CertSummariesTest.kt`, `TlsCertificateToolTest.kt`, `engine/serviceid/TlsLoopbackTest.kt`

**Interfaces:**
- Consumes: 3A's `TlsPeek` (open class, `certInfo`), `Probe.connect`, `CancellableIo.closing` (Task 3), `HttpClient` and `HttpClient.trustAllContext()` (Task 5), Task 3 framework types and `ToolCopy`.
- Produces:
```kotlin
data class TlsDetails(val protocol: String, val cipherSuite: String, val chain: List<X509Certificate>, val serverNameSent: String?)   // engine/serviceid
open suspend fun TlsPeek.details(ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int = TlsPeek.TLS_TOOL_TIMEOUT_MS): TlsDetails?
object CertSummaries { const val EXPIRY_SOON_DAYS = 30; const val WEAK_RSA_BITS = 2048
    fun of(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): List<Finding>
    fun summary(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): String }
class TlsCertificateTool(peek: suspend (target: ToolTarget, serverName: String?) -> TlsDetails?, now: () -> Long = System::currentTimeMillis) : Tool
```

- [ ] **Step 1: Generate the committed TLS fixtures (local JDK 17 keytool, nothing downloaded)**

```bash
KT="${JAVA_HOME:-/c/Program Files/Java/jdk-17.0.2}/bin/keytool"
D=app/src/test/resources/tls; mkdir -p "$D"
kt() { "$KT" -genkeypair -storetype PKCS12 -storepass changeit -keypass changeit -alias a -dname "$2" -keystore "$D/$1.p12" "${@:3}"; }
kt rsa2048      "CN=rsa.lan,O=Synology Inc.,C=TW"   -keyalg RSA -keysize 2048 -validity 3650
kt rsa1024_weak "CN=weak.lan,O=Old Co"              -keyalg RSA -keysize 1024 -validity 3650
kt ec_p256      "CN=ec.lan,O=EC Co"                 -keyalg EC -groupname secp256r1 -validity 3650
kt dsa2048      "CN=dsa.lan,O=DSA Co"               -keyalg DSA -keysize 2048 -validity 3650
kt ed25519      "CN=ed25519.lan,O=Ed Co"            -keyalg Ed25519 -validity 3650
kt sha1         "CN=sha1.lan,O=Old Co"              -keyalg RSA -keysize 2048 -sigalg SHA1withRSA -validity 3650
kt sans         "CN=unifi.local,O=Ubiquiti Inc."    -keyalg RSA -keysize 2048 -validity 3650 -ext "SAN=dns:unifi.local,dns:unifi,ip:10.0.4.1"
kt expired      "CN=expired.lan,O=Old Co"           -keyalg RSA -keysize 2048 -startdate -800d -validity 1
"$KT" -genkeypair -storetype PKCS12 -storepass changeit -keypass changeit -alias ca -dname "CN=Test Root CA,O=NWS" -keystore "$D/chain_ca.p12" -keyalg RSA -keysize 2048 -validity 3650 -ext bc:c
"$KT" -genkeypair -storetype PKCS12 -storepass changeit -keypass changeit -alias leaf -dname "CN=leaf.lan,O=NWS" -keystore "$D/chain_leaf.p12" -keyalg RSA -keysize 2048 -validity 3650
"$KT" -certreq -alias leaf -keystore "$D/chain_leaf.p12" -storepass changeit | "$KT" -gencert -alias ca -keystore "$D/chain_ca.p12" -storepass changeit -validity 3650 -rfc > "$D/leaf.cer"
"$KT" -exportcert -alias ca -keystore "$D/chain_ca.p12" -storepass changeit -rfc > "$D/ca.cer"
ls -l "$D"
```
Expected: ten `.p12` files and two `.cer` files. keytool prints a weak-algorithm warning for `sha1` and `rsa1024_weak`; that is expected. If this JDK lacks a key type, stop and report which; do not download anything or switch JDK.

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/CertSummariesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** spec 6.2 and 12.1: every finding, every flag, every key type and the summary from the committed keytool fixtures. */
class CertSummariesTest {
    private val dir = File("src/test/resources/tls")
    private val date: (Long) -> String = { "D$it" }
    private fun leaf(name: String): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12"); File(dir, "$name.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks.getCertificate(ks.aliases().nextElement()) as X509Certificate
    }
    private fun cer(name: String) = File(dir, name).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun details(chain: List<X509Certificate>) = TlsDetails("TLSv1.3", "TLS_AES_128_GCM_SHA256", chain, null)
    private fun findings(name: String, now: Long = leaf(name).notBefore.time + 1) = CertSummaries.of(details(listOf(leaf(name))), now, date)
    private fun value(name: String, kind: FindingKind) = findings(name).first { it.kind == kind }.value

    @Test fun `subject, issuer, signature, fingerprint and session`() {
        val f = findings("rsa2048")
        assertEquals("CN=rsa.lan,O=Synology Inc.,C=TW", f.first { it.kind == FindingKind.CERT_SUBJECT }.value)
        assertEquals("Subject", f.first { it.kind == FindingKind.CERT_SUBJECT }.detail)
        assertEquals("CN=rsa.lan,O=Synology Inc.,C=TW", f.first { it.kind == FindingKind.CERT_ISSUER }.value)
        assertEquals("SHA256withRSA", f.first { it.kind == FindingKind.CERT_SIGNATURE }.value)
        assertTrue(f.first { it.kind == FindingKind.CERT_FINGERPRINT }.value.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals("TLSv1.3 TLS_AES_128_GCM_SHA256", f.first { it.kind == FindingKind.TLS_SESSION }.value)
    }
    @Test fun `every key type is named`() {
        assertEquals("RSA 2048 bit", value("rsa2048", FindingKind.CERT_KEY))
        assertEquals("RSA 1024 bit", value("rsa1024_weak", FindingKind.CERT_KEY))
        assertEquals("EC 256 bit", value("ec_p256", FindingKind.CERT_KEY))
        assertEquals("DSA 2048 bit", value("dsa2048", FindingKind.CERT_KEY))
        assertEquals("Ed25519", value("ed25519", FindingKind.CERT_KEY))
    }
    @Test fun `the three flags`() {
        assertTrue(findings("rsa2048").any { it.kind == FindingKind.FLAG && it.value == "Self-signed" && it.detail == "Certificate" })
        assertTrue(findings("rsa1024_weak").any { it.kind == FindingKind.FLAG && it.value == "RSA key under 2048 bit" })
        assertTrue(findings("sha1").any { it.kind == FindingKind.FLAG && it.value == "Signed with SHA-1" })
        assertFalse(findings("rsa2048").any { it.kind == FindingKind.FLAG && it.value != "Self-signed" })
    }
    @Test fun `dns and ip sans each become a finding and a hint`() {
        val f = findings("sans")
        assertEquals(listOf("unifi.local" to "Alternative name (DNS)", "unifi" to "Alternative name (DNS)", "10.0.4.1" to "Alternative name (IP)"),
            f.filter { it.kind == FindingKind.CERT_SAN }.map { it.value to it.detail })
        assertEquals(listOf("tls.subject=CN=unifi.local,O=Ubiquiti Inc.", "tls.issuer=CN=unifi.local,O=Ubiquiti Inc.", "tls.san=unifi.local", "tls.san=unifi", "tls.san=10.0.4.1"),
            f.filter { it.kind == FindingKind.HINT }.map { it.value })
    }
    @Test fun `validity reads valid, expired, not yet valid and expires in N days`() {
        val c = leaf("rsa2048"); val day = 86_400_000L
        fun detail(now: Long) = CertSummaries.of(details(listOf(c)), now, date).first { it.kind == FindingKind.CERT_VALIDITY }.detail
        assertEquals("D${c.notBefore.time} to D${c.notAfter.time}", findings("rsa2048").first { it.kind == FindingKind.CERT_VALIDITY }.value)
        assertEquals("Valid", detail(c.notBefore.time + day))
        assertEquals("Not yet valid", detail(c.notBefore.time - day))
        assertEquals("Expires in 12 days", detail(c.notAfter.time - 12 * day))
        assertEquals("Expires in 1 day", detail(c.notAfter.time - day))
        assertEquals("Expired", CertSummaries.of(details(listOf(leaf("expired"))), System.currentTimeMillis(), date).first { it.kind == FindingKind.CERT_VALIDITY }.detail)
    }
    @Test fun `a two level chain lists the issuer certificate and the leaf is not self signed`() {
        val f = CertSummaries.of(details(listOf(cer("leaf.cer"), cer("ca.cer"))), System.currentTimeMillis(), date)
        assertEquals(listOf("CN=Test Root CA,O=NWS" to "Chain certificate 1 of 1"), f.filter { it.kind == FindingKind.CERT_CHAIN }.map { it.value to it.detail })
        assertFalse(f.any { it.kind == FindingKind.FLAG && it.value == "Self-signed" })
    }
    @Test fun `the summary names the cn, else the first san, and appends self-signed`() {
        val c = leaf("rsa2048")
        assertEquals("rsa.lan, expires D${c.notAfter.time}, self-signed", CertSummaries.summary(details(listOf(c)), 0L, date))
        assertEquals("leaf.lan, expires D${cer("leaf.cer").notAfter.time}", CertSummaries.summary(details(listOf(cer("leaf.cer"), cer("ca.cer"))), 0L, date))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/TlsCertificateToolTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

class TlsCertificateToolTest {
    private val target = ToolTarget(7, ip("10.0.3.9"), 5001, tls = true, serverName = "chnas.local")
    private fun leaf(): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/rsa2048.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        return ks.getCertificate("a") as X509Certificate
    }
    @Test fun `no handshake is FAILED`() = runTest {
        val r = TlsCertificateTool({ _, _ -> null }).run(target, ToolOptions.None, control(), CollectingProgress())
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No TLS handshake on this port", r.summary)
    }
    @Test fun `a handshake lost to a pause is retried once after the resume`() = runTest {
        val c = ToolTestSupport.EpochControl(); var calls = 0
        val r = TlsCertificateTool({ _, name ->
            calls++
            if (calls == 1) { c.epoch++; null } else TlsDetails("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", listOf(leaf()), name)
        }, { leaf().notBefore.time + 1 }).run(target, ToolOptions.None, c, CollectingProgress())
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals(2, calls)
    }
    @Test fun `a handshake reports its findings, passes the target name and summarises`() = runTest {
        var asked: String? = "unset"
        val p = CollectingProgress()
        val r = TlsCertificateTool({ _, name -> asked = name; TlsDetails("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", listOf(leaf()), name) }, { leaf().notBefore.time + 1 })
            .run(target, ToolOptions.None, control(), p)
        assertEquals("chnas.local", asked); assertEquals(ToolRunStatus.DONE, r.status)
        assertTrue(r.summary.startsWith("rsa.lan, expires ")); assertTrue(r.summary.endsWith(", self-signed"))
        assertTrue(p.of(FindingKind.CERT_SUBJECT).isNotEmpty())
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/TlsLoopbackTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.*
import kotlin.concurrent.thread

/** spec 12.1: TLS through HttpClient and TlsPeek.details against a loopback SSLServerSocket, SNI sent, and the retry without a name. */
class TlsLoopbackTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val servers = mutableListOf<SSLServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private fun serverContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12"); File("src/test/resources/tls/rsa2048.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); kmf.init(ks, "changeit".toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
    /** Accepts up to [connections] handshakes, recording each SNI name sent (null when none); only [onlyName] is accepted when set. */
    private fun serve(onlyName: String?, connections: Int, reply: String?): Pair<Int, MutableList<String?>> {
        val ss = serverContext().serverSocketFactory.createServerSocket(0, 50, lo) as SSLServerSocket; servers += ss
        if (onlyName != null) ss.sslParameters = ss.sslParameters.apply { serverNames = null; sniMatchers = listOf(SNIHostName.createSNIMatcher(Regex.escape(onlyName))) }
        val names = Collections.synchronizedList(mutableListOf<String?>())
        thread(isDaemon = true) {
            repeat(connections) {
                runCatching {
                    val s = ss.accept() as SSLSocket
                    s.use {
                        it.startHandshake()
                        names += ((it.session as ExtendedSSLSession).requestedServerNames.firstOrNull() as? SNIHostName)?.asciiName
                        if (reply != null) {
                            val head = StringBuilder(); val input = it.inputStream
                            while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()) }
                            it.outputStream.write(reply.toByteArray()); it.outputStream.flush()
                        }
                    }
                }
            }
        }
        return ss.localPort to names
    }

    @Test fun `http client over tls sends the name as sni and as host`() = runBlocking {
        val (port, names) = serve("chnas.local", 1, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val r = HttpClient(2000).request(lo, port, true, "GET", "/", "chnas.local", null) as HttpResult.Ok
        assertEquals(200, r.response.status); assertEquals("ok", String(r.response.body))
        assertEquals(listOf<String?>("chnas.local"), names.toList())
    }
    @Test fun `details sends the name and reads the chain`() = runBlocking {
        val (port, names) = serve("chnas.local", 1, null)
        val d = TlsPeek().details(lo, port, "chnas.local", 2000)!!
        assertEquals("chnas.local", d.serverNameSent); assertTrue(d.chain.first().subjectX500Principal.name.contains("rsa.lan"))
        assertEquals(listOf<String?>("chnas.local"), names.toList())
    }
    @Test fun `a refused name is retried once without a name`() = runBlocking {
        val (port, names) = serve("chnas.local", 2, null)
        val d = TlsPeek().details(lo, port, "wrong.name", 2000)!!
        assertNull(d.serverNameSent)
        assertEquals(listOf<String?>(null), names.toList())        // the named attempt failed its handshake and recorded nothing
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.CertSummariesTest" --tests "com.northwalessolutions.netprobe.engine.tools.TlsCertificateToolTest" --tests "com.northwalessolutions.netprobe.engine.serviceid.TlsLoopbackTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'TlsDetails'`, `'details'`, `'CertSummaries'`, `'TlsCertificateTool'`.

- [ ] **Step 4: Implement**

`engine/serviceid/TlsPeek.kt`: add above `open class TlsPeek` (imports `com.northwalessolutions.netprobe.engine.common.CancellableIo`, `com.northwalessolutions.netprobe.engine.common.HttpClient`, `com.northwalessolutions.netprobe.engine.common.Probe`):
```kotlin
/** One handshake as the TLS certificate tool reads it (spec 6.2): protocol, suite, the whole peer chain and the SNI name sent. */
data class TlsDetails(val protocol: String, val cipherSuite: String, val chain: List<X509Certificate>, val serverNameSent: String?)
```
and inside the class:
```kotlin
    /** Named handshake first, then once without a name if it fails (spec 4.9). Null when no handshake completes. */
    open suspend fun details(ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int = TLS_TOOL_TIMEOUT_MS): TlsDetails? {
        val named = attemptDetails(ip, port, serverName, timeoutMs)
        if (named != null || serverName == null) return named
        return attemptDetails(ip, port, null, timeoutMs)
    }

    private suspend fun attemptDetails(ip: InetAddress, port: Int, serverName: String?, timeoutMs: Int): TlsDetails? {
        val connected = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return null
        val raw = connected.socket ?: return null
        return try {
            // SNI is the name when given; an IP literal sends no SNI extension at all.
            val s = HttpClient.trustAllContext().socketFactory.createSocket(raw, serverName ?: ip.hostAddress, port, true) as SSLSocket
            runCatching { s.enabledProtocols = s.supportedProtocols }
            runCatching { s.enabledCipherSuites = s.supportedCipherSuites }
            CancellableIo.closing(s) {
                s.soTimeout = timeoutMs
                s.startHandshake()
                TlsDetails(s.session.protocol, s.session.cipherSuite, s.session.peerCertificates.filterIsInstance<X509Certificate>(), serverName)
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { runCatching { raw.close() } }
    }
```
and add `const val TLS_TOOL_TIMEOUT_MS = 3000` to the companion. Closing `raw` closes the layered socket too (`autoClose = true`).

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/CertSummaries.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.DateFormat
import java.util.Date

fun mediumDate(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))

/** Pure mapper from one handshake to findings (spec 6.2); every path is pinned by keytool fixtures. */
object CertSummaries {
    const val EXPIRY_SOON_DAYS = 30
    const val WEAK_RSA_BITS = 2048
    private const val DAY_MS = 86_400_000L
    private const val SAN_DNS = 2
    private const val SAN_IP = 7
    /** SubjectPublicKeyInfo AlgorithmIdentifier OIDs 1.3.101.112 and 1.3.101.113, as DER (RFC 8410). */
    private val ED25519_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x70)
    private val ED448_OID = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x71)

    fun of(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): List<Finding> {
        val leaf = details.chain.firstOrNull() ?: return emptyList()
        val subject = leaf.subjectX500Principal.name; val issuer = leaf.issuerX500Principal.name
        val sans = sans(leaf)
        val out = ArrayList<Finding>()
        out += Finding(FindingKind.CERT_SUBJECT, subject, ToolCopy.SUBJECT)
        out += Finding(FindingKind.CERT_ISSUER, issuer, ToolCopy.ISSUER)
        sans.forEach { (value, ip) -> out += Finding(FindingKind.CERT_SAN, value, if (ip) ToolCopy.SAN_IP else ToolCopy.SAN_DNS) }
        out += Finding(FindingKind.CERT_VALIDITY, ToolCopy.validityRange(formatDate(leaf.notBefore.time), formatDate(leaf.notAfter.time)), validity(leaf, now))
        out += Finding(FindingKind.CERT_KEY, keyText(leaf.publicKey), ToolCopy.PUBLIC_KEY)
        out += Finding(FindingKind.CERT_SIGNATURE, leaf.sigAlgName, ToolCopy.SIGNATURE_ALGORITHM)
        out += Finding(FindingKind.CERT_FINGERPRINT, sha256(leaf), ToolCopy.SHA256_FINGERPRINT)
        val chain = details.chain.drop(1)
        chain.forEachIndexed { i, c -> out += Finding(FindingKind.CERT_CHAIN, c.subjectX500Principal.name, ToolCopy.chainCertificate(i + 1, chain.size)) }
        out += Finding(FindingKind.TLS_SESSION, "${details.protocol} ${details.cipherSuite}", ToolCopy.PROTOCOL_AND_CIPHER)
        if (selfSigned(leaf)) out += Finding(FindingKind.FLAG, ToolCopy.SELF_SIGNED, ToolCopy.CERTIFICATE)
        (leaf.publicKey as? RSAPublicKey)?.let { if (it.modulus.bitLength() < WEAK_RSA_BITS) out += Finding(FindingKind.FLAG, ToolCopy.WEAK_RSA, ToolCopy.PUBLIC_KEY) }
        if (leaf.sigAlgName.contains("SHA1", ignoreCase = true)) out += Finding(FindingKind.FLAG, ToolCopy.SHA1_SIGNED, ToolCopy.SIGNATURE_ALGORITHM)
        out += Finding(FindingKind.HINT, "tls.subject=$subject")
        out += Finding(FindingKind.HINT, "tls.issuer=$issuer")
        sans.forEach { (value, _) -> out += Finding(FindingKind.HINT, "tls.san=$value") }
        return out
    }

    fun summary(details: TlsDetails, now: Long, formatDate: (Long) -> String = ::mediumDate): String {
        val leaf = details.chain.firstOrNull() ?: return ToolCopy.TLS_NO_HANDSHAKE
        val name = commonName(leaf.subjectX500Principal.name) ?: sans(leaf).firstOrNull()?.first ?: leaf.subjectX500Principal.name
        return ToolCopy.tlsSummary(name, formatDate(leaf.notAfter.time), selfSigned(leaf))
    }

    private fun validity(leaf: X509Certificate, now: Long): String = when {
        now > leaf.notAfter.time -> ToolCopy.EXPIRED
        now < leaf.notBefore.time -> ToolCopy.NOT_YET_VALID
        leaf.notAfter.time - now <= EXPIRY_SOON_DAYS * DAY_MS -> ToolCopy.expiresIn((leaf.notAfter.time - now) / DAY_MS)
        else -> ToolCopy.VALID
    }
    private fun keyText(key: PublicKey): String = when (key) {
        is RSAPublicKey -> ToolCopy.keyRsa(key.modulus.bitLength())
        is ECPublicKey -> ToolCopy.keyEc(key.params.curve.field.fieldSize)
        is DSAPublicKey -> ToolCopy.keyDsa(key.params.p.bitLength())
        else -> when {
            contains(key.encoded ?: ByteArray(0), ED25519_OID) -> ToolCopy.ED25519
            contains(key.encoded ?: ByteArray(0), ED448_OID) -> ToolCopy.ED448
            else -> key.algorithm
        }
    }
    private fun contains(hay: ByteArray, needle: ByteArray): Boolean = (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
    private fun sans(leaf: X509Certificate): List<Pair<String, Boolean>> =
        runCatching { leaf.subjectAlternativeNames }.getOrNull().orEmpty().mapNotNull { e ->
            val type = e.getOrNull(0) as? Int; val value = e.getOrNull(1) as? String
            when { value == null -> null; type == SAN_DNS -> value to false; type == SAN_IP -> value to true; else -> null }
        }
    private fun commonName(x500: String): String? = TlsPeek.subjectField(x500, "CN")
    private fun selfSigned(leaf: X509Certificate): Boolean =
        leaf.issuerX500Principal == leaf.subjectX500Principal && runCatching { leaf.verify(leaf.publicKey); true }.getOrDefault(false)
    private fun sha256(leaf: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":") { "%02X".format(it) }
}
```
(add `import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek`; `TlsPeek.subjectField` is 3A's RFC 2253 attribute reader. `ToolCopy.expiresIn((notAfter - now) / DAY_MS)` gives whole days, so 12 days and 1 hour reads "Expires in 12 days".)

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/TlsCertificateTool.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.TlsDetails
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

/** TLS certificate (spec 6.2): one handshake (named, then on the IP) mapped to findings by the pure CertSummaries. */
class TlsCertificateTool(
    private val peek: suspend (target: ToolTarget, serverName: String?) -> TlsDetails?,
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = ToolId.TLS_CERTIFICATE
    override val name = ToolCopy.TLS_CERTIFICATE
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.TLS_CERTIFICATE, port, service)

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        control.gate()
        val details = control.excusedOnce<TlsDetails?>({ it == null }) { peek(target, target.serverName) }   // spec 4.8 (plan review G5)
            ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.TLS_NO_HANDSHAKE)
        val at = now()
        var shown = 0
        for (f in CertSummaries.of(details, at)) {
            progress.finding(f)
            if (f.kind != FindingKind.HINT) { shown++; progress.update(1, 1, shown) }
        }
        return ToolResult(ToolRunStatus.DONE, CertSummaries.summary(details, at))
    }
}
```
Where `serverNameSent` matters (the results header "as <name>"), the runner already records `target.serverName` in `optionsJson`; when the named handshake failed and the IP retry succeeded, `details.serverNameSent` is null, and the results sheet reads the name from the finding set: the tool adds no extra row for it, so "as <name>" shows the attempted name. (Self-Review deviation 9.)

`AppContainer.kt`: in `toolMap()` add (import `com.northwalessolutions.netprobe.engine.serviceid.TlsPeek`):
```kotlin
        put(ToolId.TLS_CERTIFICATE, TlsCertificateTool(peek = { t, name -> TlsPeek().details(t.ip, t.port, name) }))
```

- [ ] **Step 5: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.CertSummariesTest" --tests "com.northwalessolutions.netprobe.engine.tools.TlsCertificateToolTest" --tests "com.northwalessolutions.netprobe.engine.serviceid.TlsLoopbackTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `CertSummariesTest` (7), `TlsCertificateToolTest` (3) and `TlsLoopbackTest` (3) pass, with 3A's `TlsSubjectTest` and `CancellationTest` still green; `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/CertSummaries.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/TlsCertificateTool.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/TlsPeek.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/resources/tls \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/CertSummariesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/TlsCertificateToolTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/TlsLoopbackTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "TLS certificate tool, TlsPeek.details and the CertSummaries mapper"
```

---

### Task 13: SNMP walk tool, the GET and GETNEXT PDUs, the BER extensions and the UDP exchange

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/SnmpWalkTool.kt`, `engine/tools/UdpSnmpExchange.kt`
- Modify: `engine/discovery/Snmp.kt` (`Ber.Reader.unsigned`, `SnmpVarbind`, `SnmpPdu`), `AppContainer.kt` (the SNMP_WALK entry in `toolMap()`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SnmpPduTest.kt`, `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/SnmpTestAgent.kt`, `SnmpWalkTest.kt`, `UdpSnmpExchangeTest.kt`

**Interfaces:**
- Consumes: 3A's `Ber` (internal object in `engine/discovery/Snmp.kt`), `currentBinder`, `bindOrFail`, `CancellableIo.closing` (Task 3), Task 3 framework types and `ToolCopy`, `ToolApplicability.snmp`.
- Produces:
```kotlin
// engine/discovery/Snmp.kt
fun Ber.Reader.unsigned(): Long      // reads a length and that many content bytes as an unsigned big-endian value (tag already read)
data class SnmpVarbind(val oid: String, val type: Int, val str: String?, val long: Long?, val raw: ByteArray?, val endOfMib: Boolean, val exception: Boolean)
data class SnmpResponse(val requestId: Int, val errorStatus: Int, val varbinds: List<SnmpVarbind>)
object SnmpPdu { const val GET = 0xa0; const val GETNEXT = 0xa1; const val RESPONSE = 0xa2
    fun build(pduType: Int, oids: List<String>, community: String, requestId: Int): ByteArray; fun parse(bytes: ByteArray): SnmpResponse?; fun oidParts(oid: String): IntArray }
// engine/tools
sealed interface SnmpStep { data class Ok(val varbinds: List<SnmpVarbind>) : SnmpStep; object Timeout : SnmpStep; object Error : SnmpStep }
interface SnmpExchange { suspend fun getNext(oids: List<String>): SnmpStep; suspend fun get(oids: List<String>): SnmpStep }
class UdpSnmpExchange(ip: InetAddress, port: Int = 161, timeoutMs: Int = SNMP_TIMEOUT_MS, community: String = "public") : SnmpExchange
class SnmpWalkTool(exchangeFor: (ToolTarget) -> SnmpExchange) : Tool { companion object { const val SNMP_TIMEOUT_MS = 1000; const val SNMP_MAX_REQUESTS = 400; const val SNMP_MAX_INTERFACES = 64; const val SNMP_MAX_ADDRESSES = 64 } }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SnmpPduTest.kt` (expected bytes written out; `Ber` is `internal`, visible to tests in the same module):
```kotlin
package com.northwalessolutions.netprobe.engine.discovery

import org.junit.Assert.*
import org.junit.Test

/** spec 6.4: the GETNEXT request bytes, and response parsing of every application type and exception. */
class SnmpPduTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `a getnext for sysDescr is exact`() {
        // SEQUENCE { INTEGER 1 (v2c), OCTET STRING "public", GetNextRequest { id 7, error 0, index 0, varbinds { { OID 1.3.6.1.2.1.1, NULL } } } }
        val expected = "3025" + "020101" + "0406" + "7075626c6963" + "a118" + "020107" + "020100" + "020100" + "300d" + "300b" + "0607" + "2b060102010101" + "0500"
        assertEquals(expected, hexOf(SnmpPdu.build(SnmpPdu.GETNEXT, listOf("1.3.6.1.2.1.1.1"), "public", 7)))
    }
    @Test fun `a get uses pdu a0`() {
        assertEquals(0xa0.toByte(), SnmpPdu.build(SnmpPdu.GET, listOf("1.3.6.1.2.1.1.1.0"), "public", 1)[13])
    }
    @Test fun `a response with every value type parses`() {
        val vb = { oid: String, value: ByteArray -> Ber.seq(Ber.oid(SnmpPdu.oidParts(oid)), value) }
        val varbinds = Ber.seq(
            vb("1.3.6.1.2.1.1.1.0", Ber.str("Linux gw")),
            vb("1.3.6.1.2.1.1.2.0", Ber.oid(intArrayOf(1, 3, 6, 1, 4, 1, 8072, 3, 2, 10))),
            vb("1.3.6.1.2.1.1.3.0", hex("43 05 00 ff ff ff ff")),                 // TimeTicks 4294967295: unsigned, never negative
            vb("1.3.6.1.2.1.4.20.1.1.10.0.3.1", hex("40 04 0a 00 03 01")),       // IpAddress 10.0.3.1
            vb("1.3.6.1.2.1.2.2.1.5.1", hex("42 04 3b 9a ca 00")),               // Gauge32 1000000000
            vb("1.3.6.1.2.1.2.2.1.10.1", hex("41 05 00 80 00 00 00")),           // Counter32 2147483648
            vb("1.3.6.1.2.1.2.2.1.3.1", Ber.int(6)),                              // INTEGER 6
            vb("1.3.6.1.2.1.2.2.1.6.1", Ber.tlv(0x04, hex("001122334455"))),     // ifPhysAddress raw bytes
            vb("1.3.6.1.2.1.9.0", hex("82 00")))                                  // endOfMibView
        val pdu = Ber.tlv(0xa2, Ber.int(9) + Ber.int(0) + Ber.int(0) + varbinds)
        val r = SnmpPdu.parse(Ber.seq(Ber.int(1), Ber.str("public"), pdu))!!
        assertEquals(9, r.requestId); assertEquals(0, r.errorStatus)
        val v = r.varbinds
        assertEquals("Linux gw", v[0].str); assertEquals("1.3.6.1.4.1.8072.3.2.10", v[1].str)
        assertEquals(4294967295L, v[2].long); assertEquals("10.0.3.1", v[3].str)
        assertEquals(1_000_000_000L, v[4].long); assertEquals(2_147_483_648L, v[5].long); assertEquals(6L, v[6].long)
        assertEquals("001122334455", hexOf(v[7].raw!!)); assertTrue(v[8].endOfMib); assertTrue(v[8].exception)
    }
    @Test fun `negative integers sign extend and garbage is null`() {
        val pdu = Ber.tlv(0xa2, Ber.int(1) + Ber.int(0) + Ber.int(0) + Ber.seq(Ber.seq(Ber.oid(intArrayOf(1, 3, 6, 1)), Ber.int(-2))))
        assertEquals(-2L, SnmpPdu.parse(Ber.seq(Ber.int(1), Ber.str("public"), pdu))!!.varbinds.single().long)
        assertNull(SnmpPdu.parse(hex("30 05 02 01")))
        assertNull(SnmpPdu.parse(ByteArray(0)))
    }
}
```
(The expected bytes: `2b06010201 0101` is OID 1.3.6.1.2.1.1.1, first two arcs packed as 40 x 1 + 3 = 0x2b; lengths: varbind 0x0b = 2 + 7 + 2, list 0x0d, PDU 0x18 = 3 + 3 + 3 + 15, message 0x25 = 3 + 8 + 26.)

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/SnmpTestAgent.kt` (test code: an in-memory MIB with numeric OID order, used by the walk test and by the loopback agent):
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.SnmpVarbind

object SnmpTestAgent {
    fun str(oid: String, s: String) = SnmpVarbind(oid, 0x04, s, null, s.toByteArray(), false, false)
    fun raw(oid: String, b: ByteArray) = SnmpVarbind(oid, 0x04, null, null, b, false, false)
    fun num(oid: String, v: Long, type: Int = 0x02) = SnmpVarbind(oid, type, null, v, null, false, false)
    fun oidv(oid: String, v: String) = SnmpVarbind(oid, 0x06, v, null, null, false, false)
    fun ip(oid: String, v: String) = SnmpVarbind(oid, 0x40, v, null, null, false, false)
    fun end(oid: String) = SnmpVarbind(oid, 0x82, null, null, null, endOfMib = true, exception = true)
    private fun parts(oid: String) = oid.split('.').map { it.toLong() }
    val byOid = Comparator<String> { a, b ->
        val x = parts(a); val y = parts(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return@Comparator x[i].compareTo(y[i])
        x.size.compareTo(y.size)
    }
    /** GETNEXT semantics over [mib]: the first entry strictly after each requested OID, else endOfMibView. */
    fun exchange(mib: List<SnmpVarbind>, onRequest: () -> Unit = {}): SnmpExchange {
        val sorted = mib.sortedWith { a, b -> byOid.compare(a.oid, b.oid) }
        return object : SnmpExchange {
            override suspend fun getNext(oids: List<String>): SnmpStep { onRequest(); return SnmpStep.Ok(oids.map { q -> sorted.firstOrNull { byOid.compare(it.oid, q) > 0 } ?: end(q) }) }
            override suspend fun get(oids: List<String>): SnmpStep { onRequest(); return SnmpStep.Ok(oids.map { q -> sorted.firstOrNull { it.oid == q } ?: end(q) }) }
        }
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/SnmpWalkTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.end
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.ip
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.num
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.oidv
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.raw
import com.northwalessolutions.netprobe.engine.tools.SnmpTestAgent.str
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 6.4 and 12.1: walk termination, table assembly by index suffix with a sparse column, ifHighSpeed, formatting, hints, the cap. */
class SnmpWalkTest {
    private val target = ToolTarget(7, ToolTestSupport.ip("10.0.3.1"), 161, false, null)
    private val system = listOf(
        str("1.3.6.1.2.1.1.1.0", "Linux gw 5.10"), oidv("1.3.6.1.2.1.1.2.0", "1.3.6.1.4.1.8072.3.2.10"), num("1.3.6.1.2.1.1.3.0", (12L * 86_400 + 4 * 3_600) * 100, 0x43),
        str("1.3.6.1.2.1.1.4.0", "admin"), str("1.3.6.1.2.1.1.5.0", "gw"), str("1.3.6.1.2.1.1.6.0", "rack"))
    private val interfaces = listOf(
        str("1.3.6.1.2.1.2.2.1.2.1", "eth0"), str("1.3.6.1.2.1.2.2.1.2.2", "wlan0"), str("1.3.6.1.2.1.2.2.1.2.10", "lo"),
        num("1.3.6.1.2.1.2.2.1.3.1", 6), num("1.3.6.1.2.1.2.2.1.3.10", 24),                                  // ifType missing for index 2: sparse
        num("1.3.6.1.2.1.2.2.1.5.1", 4294967295L, 0x42), num("1.3.6.1.2.1.2.2.1.5.2", 54_000_000, 0x42), num("1.3.6.1.2.1.2.2.1.5.10", 10_000_000, 0x42),
        raw("1.3.6.1.2.1.2.2.1.6.1", byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)),
        num("1.3.6.1.2.1.2.2.1.8.1", 1), num("1.3.6.1.2.1.2.2.1.8.2", 2), num("1.3.6.1.2.1.2.2.1.8.10", 1),
        num("1.3.6.1.2.1.31.1.1.1.15.1", 10_000, 0x42))                                                       // ifHighSpeed 10000 Mbit/s
    private val addresses = listOf(ip("1.3.6.1.2.1.4.20.1.1.10.0.3.1", "10.0.3.1"), ip("1.3.6.1.2.1.4.20.1.1.10.0.4.1", "10.0.4.1"),
        ip("1.3.6.1.2.1.4.20.1.3.10.0.3.1", "255.255.255.0"), ip("1.3.6.1.2.1.4.20.1.3.10.0.4.1", "255.255.0.0"))

    private suspend fun run(ex: SnmpExchange): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return SnmpWalkTool { ex }.run(target, ToolOptions.None, control(), p) to p
    }

    @Test fun `a request lost to a pause is retried once and the walk continues`() = runTest {
        val c = ToolTestSupport.EpochControl(); val agent = SnmpTestAgent.exchange(system + interfaces + addresses); var first = true
        val ex = object : SnmpExchange {
            override suspend fun getNext(oids: List<String>): SnmpStep {
                if (first) { first = false; c.epoch++; return SnmpStep.Timeout }                           // a pause began mid-request
                return agent.getNext(oids)
            }
            override suspend fun get(oids: List<String>) = agent.get(oids)
        }
        val r = SnmpWalkTool { ex }.run(target, ToolOptions.None, c, CollectingProgress())
        assertEquals("gw, 3 interfaces", r.summary)
    }
    @Test fun `no system answer is FAILED`() = runTest {
        val (r, _) = run(object : SnmpExchange { override suspend fun getNext(oids: List<String>) = SnmpStep.Timeout; override suspend fun get(oids: List<String>) = SnmpStep.Timeout })
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SNMP answer to public", r.summary)
    }
    @Test fun `the system group, its formatting and its hints`() = runTest {
        val (r, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf("sysDescr" to "Linux gw 5.10", "sysObjectID" to "1.3.6.1.4.1.8072.3.2.10", "Uptime" to "12 days 4 h",
            "sysContact" to "admin", "sysName" to "gw", "sysLocation" to "rack"), p.of(FindingKind.SNMP_SYSTEM).map { it.detail to it.value })
        assertEquals(listOf("snmp.sysDescr=Linux gw 5.10", "snmp.sysObjectID=1.3.6.1.4.1.8072.3.2.10", "snmp.sysName=gw"), p.hints())
        assertEquals("gw, 3 interfaces", r.summary); assertNull(p.lastTotal)
    }
    @Test fun `interfaces assemble by index with a sparse column and the high speed substitution`() = runTest {
        val (_, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf(
            "eth0" to "up, ethernetCsmacd, 10 Gbit/s, 00:11:22:33:44:55",
            "wlan0" to "down, 54 Mbit/s",
            "lo" to "up, softwareLoopback, 10 Mbit/s"), p.of(FindingKind.SNMP_INTERFACE).map { it.value to it.detail })
    }
    @Test fun `addresses carry their prefix`() = runTest {
        val (_, p) = run(SnmpTestAgent.exchange(system + interfaces + addresses))
        assertEquals(listOf("10.0.3.1/24", "10.0.4.1/16"), p.of(FindingKind.SNMP_ADDRESS).map { it.value })
        assertTrue(p.of(FindingKind.SNMP_ADDRESS).all { it.detail == "Interface address" })
    }
    @Test fun `a step that times out ends that step only`() = runTest {
        val agent = SnmpTestAgent.exchange(system + interfaces + addresses)
        val ex = object : SnmpExchange {
            override suspend fun getNext(oids: List<String>) = if (oids.any { it.startsWith("1.3.6.1.2.1.2.2.1") }) SnmpStep.Timeout else agent.getNext(oids)
            override suspend fun get(oids: List<String>) = agent.get(oids)
        }
        val (r, p) = run(ex)
        assertEquals(ToolRunStatus.DONE, r.status); assertTrue(p.of(FindingKind.SNMP_INTERFACE).isEmpty())
        assertEquals(2, p.of(FindingKind.SNMP_ADDRESS).size); assertEquals("gw, 0 interfaces", r.summary)
    }
    @Test fun `an error status ends the step`() = runTest {
        val ex = object : SnmpExchange { override suspend fun getNext(oids: List<String>) = SnmpStep.Error; override suspend fun get(oids: List<String>) = SnmpStep.Error }
        assertEquals(ToolRunStatus.FAILED, run(ex).first.status)
    }
    @Test fun `the walk stops at four hundred requests`() = runTest {
        var requests = 0
        val endless = object : SnmpExchange {   // an agent that never leaves the system subtree
            override suspend fun getNext(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { str("1.3.6.1.2.1.1.9.$requests", "x") }) }
            override suspend fun get(oids: List<String>): SnmpStep { requests++; return SnmpStep.Ok(oids.map { end(it) }) }
        }
        run(endless)
        assertEquals(SnmpWalkTool.SNMP_MAX_REQUESTS, requests)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/UdpSnmpExchangeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.discovery.Ber
import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** spec 6.4 and 12.1: a loopback DatagramSocket agent end to end; one retry; mismatched request ids ignored. */
class UdpSnmpExchangeTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val agents = mutableListOf<DatagramSocket>()
    @After fun tearDown() { agents.forEach { it.close() } }

    /** Reads the request id of a GET or GETNEXT and the first OID. */
    private fun request(bytes: ByteArray): Pair<Int, String> {
        val r = Ber.Reader(bytes)
        r.tag(); r.length(); r.int(); r.tag(); r.bytes(r.length())    // sequence, version, community
        r.tag(); r.length(); val id = r.int(); r.int(); r.int()        // pdu, id, error, index
        r.tag(); r.length(); r.tag(); r.length(); r.tag()              // varbind list, first varbind, OID tag
        return id to r.oidString(r.bytes(r.length()))
    }
    private fun response(id: Int, oid: String, value: String): ByteArray =
        Ber.seq(Ber.int(1), Ber.str("public"), Ber.tlv(SnmpPdu.RESPONSE, Ber.int(id) + Ber.int(0) + Ber.int(0) + Ber.seq(Ber.seq(Ber.oid(SnmpPdu.oidParts(oid)), Ber.str(value)))))

    /** An agent that ignores the first [drop] requests and, for the rest, first sends a reply with the wrong id, then the right one. */
    private fun agent(drop: Int): Int {
        val s = DatagramSocket(0, lo); agents += s
        thread(isDaemon = true) {
            var seen = 0
            runCatching {
                while (true) {
                    val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
                    if (seen++ < drop) continue
                    val (id, oid) = request(buf.copyOf(p.length))
                    for (reply in listOf(response(id + 1000, "$oid.0", "wrong"), response(id, "$oid.0", "right")))
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            }
        }
        return s.localPort
    }

    @Test fun `getnext answers through a loopback agent and ignores a mismatched id`() = runBlocking {
        val step = UdpSnmpExchange(lo, agent(drop = 0), timeoutMs = 500).getNext(listOf("1.3.6.1.2.1.1.5")) as SnmpStep.Ok
        assertEquals("1.3.6.1.2.1.1.5.0", step.varbinds.single().oid); assertEquals("right", step.varbinds.single().str)
    }
    @Test fun `one lost datagram is retried`() = runBlocking {
        val step = UdpSnmpExchange(lo, agent(drop = 1), timeoutMs = 300).getNext(listOf("1.3.6.1.2.1.1.5"))
        assertTrue(step is SnmpStep.Ok)
    }
    @Test fun `two lost datagrams are a timeout`() = runBlocking {
        assertEquals(SnmpStep.Timeout, UdpSnmpExchange(lo, agent(drop = 2), timeoutMs = 200).getNext(listOf("1.3.6.1.2.1.1.5")))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.SnmpPduTest" --tests "com.northwalessolutions.netprobe.engine.tools.SnmpWalkTest" --tests "com.northwalessolutions.netprobe.engine.tools.UdpSnmpExchangeTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'SnmpPdu'`, `'SnmpVarbind'`, `'SnmpWalkTool'`, `'UdpSnmpExchange'`.

- [ ] **Step 3: Implement**

`engine/discovery/Snmp.kt`: inside `Ber.Reader`, add:
```kotlin
        /** Counter32, Gauge32, TimeTicks: unsigned 32-bit into Long (spec 6.4). The tag has been read; reads the length and content. */
        fun unsigned(): Long { val n = length(); var v = 0L; for (x in bytes(n)) v = (v shl 8) or (x.toLong() and 0xff); return v }
        /** INTEGER content as a sign-extended Long. The tag has been read. */
        fun signed(): Long { val b = bytes(length()); var v = if (b.isNotEmpty() && b[0] < 0) -1L else 0L; for (x in b) v = (v shl 8) or (x.toLong() and 0xff); return v }
```
and at the end of the file, top level:
```kotlin
/** One varbind as the walk reads it: [str] for strings, OIDs and IpAddress; [long] for numbers; [raw] keeps OCTET STRING bytes (MACs). */
data class SnmpVarbind(val oid: String, val type: Int, val str: String?, val long: Long?, val raw: ByteArray?, val endOfMib: Boolean, val exception: Boolean)
data class SnmpResponse(val requestId: Int, val errorStatus: Int, val varbinds: List<SnmpVarbind>)

/** SNMPv2c GET (0xa0) and GETNEXT (0xa1) requests and their responses (0xa2), with the application types and exceptions (spec 6.4). */
object SnmpPdu {
    const val GET = 0xa0; const val GETNEXT = 0xa1; const val RESPONSE = 0xa2
    private const val T_INTEGER = 0x02; private const val T_OCTETS = 0x04; private const val T_NULL = 0x05; private const val T_OID = 0x06
    private const val T_IPADDRESS = 0x40; private const val T_COUNTER32 = 0x41; private const val T_GAUGE32 = 0x42; private const val T_TIMETICKS = 0x43
    private const val NO_SUCH_OBJECT = 0x80; private const val NO_SUCH_INSTANCE = 0x81; private const val END_OF_MIB_VIEW = 0x82
    private const val SEQUENCE = 0x30; private const val VERSION_2C = 1

    fun oidParts(oid: String): IntArray = oid.split('.').map { it.toInt() }.toIntArray()
    fun build(pduType: Int, oids: List<String>, community: String, requestId: Int): ByteArray {
        val varbinds = Ber.seq(*oids.map { Ber.seq(Ber.oid(oidParts(it)), Ber.nul()) }.toTypedArray())
        return Ber.seq(Ber.int(VERSION_2C), Ber.str(community), Ber.tlv(pduType, Ber.int(requestId) + Ber.int(0) + Ber.int(0) + varbinds))
    }
    /** Null on anything malformed: nothing a device sends can throw out of here (spec 11.2). */
    fun parse(bytes: ByteArray): SnmpResponse? = try {
        val r = Ber.Reader(bytes)
        require(r.tag() == SEQUENCE); r.length()
        r.int()
        require(r.tag() == T_OCTETS); r.bytes(r.length())
        require(r.tag() == RESPONSE); r.length()
        val id = r.int(); val errorStatus = r.int(); r.int()
        require(r.tag() == SEQUENCE); val end = r.length().let { r.pos + it }
        val out = ArrayList<SnmpVarbind>()
        while (r.pos < end) {
            require(r.tag() == SEQUENCE); r.length()
            require(r.tag() == T_OID); val oid = r.oidString(r.bytes(r.length()))
            out += when (val t = r.tag()) {
                T_OCTETS -> { val b = r.bytes(r.length()); SnmpVarbind(oid, t, String(b, Charsets.UTF_8), null, b, false, false) }
                T_OID -> SnmpVarbind(oid, t, r.oidString(r.bytes(r.length())), null, null, false, false)
                T_IPADDRESS -> SnmpVarbind(oid, t, r.bytes(r.length()).joinToString(".") { (it.toInt() and 0xff).toString() }, null, null, false, false)
                T_COUNTER32, T_GAUGE32, T_TIMETICKS -> SnmpVarbind(oid, t, null, r.unsigned(), null, false, false)
                T_INTEGER -> SnmpVarbind(oid, t, null, r.signed(), null, false, false)
                NO_SUCH_OBJECT, NO_SUCH_INSTANCE, END_OF_MIB_VIEW -> { r.bytes(r.length()); SnmpVarbind(oid, t, null, null, null, t == END_OF_MIB_VIEW, true) }
                else -> { r.bytes(r.length()); SnmpVarbind(oid, t, null, null, null, false, t == T_NULL) }
            }
        }
        SnmpResponse(id, errorStatus, out)
    } catch (_: Exception) { null }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/UdpSnmpExchange.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.currentBinder
import com.northwalessolutions.netprobe.engine.discovery.SnmpPdu
import com.northwalessolutions.netprobe.engine.discovery.SnmpResponse
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * One SNMP request with one retry (spec 6.4): request ids increment, a reply carrying another id is ignored, each attempt waits
 * [timeoutMs]. The socket is bound through currentBinder() as Snmp.query does; the blocking receive runs inside CancellableIo.closing.
 */
class UdpSnmpExchange(private val ip: InetAddress, private val port: Int = SNMP_PORT, private val timeoutMs: Int = SnmpWalkTool.SNMP_TIMEOUT_MS,
                      private val community: String = "public") : SnmpExchange {
    private var nextId = 1

    override suspend fun getNext(oids: List<String>): SnmpStep = send(SnmpPdu.GETNEXT, oids)
    override suspend fun get(oids: List<String>): SnmpStep = send(SnmpPdu.GET, oids)

    private suspend fun send(pduType: Int, oids: List<String>): SnmpStep {
        val binder = currentBinder()
        DatagramSocket().use { socket ->
            binder?.bindOrFail(socket)
            socket.soTimeout = timeoutMs
            repeat(ATTEMPTS) {
                val id = nextId++
                val request = SnmpPdu.build(pduType, oids, community, id)
                val reply = CancellableIo.closing(socket) {
                    socket.send(DatagramPacket(request, request.size, ip, port))
                    receiveMatching(socket, id)
                }
                if (reply != null) return if (reply.errorStatus != 0) SnmpStep.Error else SnmpStep.Ok(reply.varbinds)
            }
        }
        return SnmpStep.Timeout
    }

    /** Waits up to [timeoutMs] for a reply with [id]; replies with another id or garbage are skipped. */
    private fun receiveMatching(socket: DatagramSocket, id: Int): SnmpResponse? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(MAX_DATAGRAM)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { return null }
            val r = SnmpPdu.parse(buf.copyOf(packet.length)) ?: continue
            if (r.requestId == id) return r
        }
        return null
    }

    private companion object { const val SNMP_PORT = 161; const val ATTEMPTS = 2; const val MAX_DATAGRAM = 65_535 }
}
```
(`return` inside `repeat` and `use`, both inline, returns from `send`.)

`app/src/main/java/com/northwalessolutions/netprobe/engine/tools/SnmpWalkTool.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.discovery.SnmpVarbind
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

sealed interface SnmpStep { data class Ok(val varbinds: List<SnmpVarbind>) : SnmpStep; object Timeout : SnmpStep; object Error : SnmpStep }
interface SnmpExchange { suspend fun getNext(oids: List<String>): SnmpStep; suspend fun get(oids: List<String>): SnmpStep }

/** SNMPv2c walk with community public (spec 6.4): the system group, the interface table and the address table, keyed by index suffix. */
class SnmpWalkTool(private val exchangeFor: (ToolTarget) -> SnmpExchange) : Tool {
    override val id = ToolId.SNMP_WALK
    override val name = ToolCopy.SNMP_WALK
    override fun applies(port: Int, service: ServiceInfo) = false
    override fun appliesToHost(host: HostEntity) = ToolApplicability.snmp(host)

    /** Counts requests against SNMP_MAX_REQUESTS and progress; null from [next] means the cap is reached or the step ended. */
    private class Walk(val ex: SnmpExchange, val control: RunControl, val progress: ToolProgress) {
        var requests = 0; var done = 0; var found = 0
        suspend fun next(oids: List<String>, get: Boolean = false): List<SnmpVarbind>? {
            if (requests >= SNMP_MAX_REQUESTS) return null
            control.gate()
            val step = control.excusedOnce<SnmpStep>({ it !is SnmpStep.Ok }) {                // spec 4.8 (plan review G5)
                requests++; if (get) ex.get(oids) else ex.getNext(oids)
            }
            done++; progress.update(done, null, found)
            return (step as? SnmpStep.Ok)?.varbinds
        }
        suspend fun emit(f: Finding) { found++; progress.finding(f); progress.update(done, null, found) }
    }

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        val w = Walk(exchangeFor(target), control, progress)
        var sysName: String? = null; var sysDescr: String? = null; var answered = false

        // 1. System group: GETNEXT from 1.3.6.1.2.1.1 until the reply leaves the subtree.
        var cursor = SYSTEM
        while (true) {
            val vb = w.next(listOf(cursor))?.firstOrNull() ?: break
            if (vb.exception || !vb.oid.startsWith("$SYSTEM.")) break
            answered = true; cursor = vb.oid
            val (label, value) = systemField(vb) ?: continue
            if (label == ToolCopy.SYS_NAME) sysName = value
            if (label == ToolCopy.SYS_DESCR) sysDescr = value
            w.emit(Finding(FindingKind.SNMP_SYSTEM, value, label))
            when (label) {
                ToolCopy.SYS_DESCR -> progress.finding(Finding(FindingKind.HINT, "snmp.sysDescr=$value"))
                ToolCopy.SYS_OBJECT_ID -> progress.finding(Finding(FindingKind.HINT, "snmp.sysObjectID=$value"))
                ToolCopy.SYS_NAME -> progress.finding(Finding(FindingKind.HINT, "snmp.sysName=$value"))
            }
        }
        if (!answered) return ToolResult(ToolRunStatus.FAILED, ToolCopy.SNMP_NO_ANSWER)

        // 2. Interfaces, lockstep over five columns, rows keyed by index suffix.
        val ifTable = walkTable(w, IF_COLUMNS, IF_DESCR, SNMP_MAX_INTERFACES)
        val rows = ifTable.getValue(IF_DESCR).keys.sortedWith(INDEX_ORDER)
        for (index in rows) {
            val descr = ifTable.getValue(IF_DESCR).getValue(index).str ?: continue
            var speedBits = ifTable.getValue(IF_SPEED)[index]?.long
            if (speedBits == SATURATED_SPEED) {
                w.next(listOf("$IF_HIGH_SPEED.$index"), get = true)?.firstOrNull()?.long?.let { speedBits = it * BITS_PER_MBIT }
            }
            val mac = ifTable.getValue(IF_MAC)[index]?.raw?.takeIf { it.size == MAC_BYTES }?.joinToString(":") { "%02x".format(it) }
            w.emit(Finding(FindingKind.SNMP_INTERFACE, descr,
                ToolCopy.interfaceDetail(ifTable.getValue(IF_STATUS)[index]?.long, ifTable.getValue(IF_TYPE)[index]?.long, speedBits, mac)))
        }

        // 3. Addresses: ipAdEntAddr and ipAdEntNetMask in lockstep.
        val addrTable = walkTable(w, ADDR_COLUMNS, ADDR, SNMP_MAX_ADDRESSES)
        for (index in addrTable.getValue(ADDR).keys.sortedWith(INDEX_ORDER)) {
            val addr = addrTable.getValue(ADDR).getValue(index).str ?: continue
            val mask = addrTable.getValue(MASK)[index]?.str ?: continue
            w.emit(Finding(FindingKind.SNMP_ADDRESS, ToolCopy.address(addr, prefixOf(mask)), ToolCopy.INTERFACE_ADDRESS))
        }

        return ToolResult(ToolRunStatus.DONE, ToolCopy.snmpSummary(sysName ?: sysDescr?.take(SUMMARY_DESCR_CHARS) ?: target.ip.hostAddress, rows.size))
    }

    /**
     * GETNEXT over the still-live columns until each has left its subtree, the key column holds [maxRows] rows, the step times out
     * or errors, or the request cap is reached. Each column's values are keyed by the index suffix after "<column>.", so a sparse
     * column never shifts the others.
     */
    private suspend fun walkTable(w: Walk, columns: List<String>, key: String, maxRows: Int): Map<String, Map<String, SnmpVarbind>> {
        val table = columns.associateWith { LinkedHashMap<String, SnmpVarbind>() }
        val cursors = columns.associateWith { it }.toMutableMap()
        val live = columns.toMutableList()
        while (live.isNotEmpty() && table.getValue(key).size < maxRows) {
            val replies = w.next(live.map { cursors.getValue(it) }) ?: break
            val stillLive = ArrayList<String>()
            live.forEachIndexed { i, column ->
                val vb = replies.getOrNull(i) ?: return@forEachIndexed
                if (vb.exception || !vb.oid.startsWith("$column.")) return@forEachIndexed
                table.getValue(column)[vb.oid.removePrefix("$column.")] = vb
                cursors[column] = vb.oid; stillLive += column
            }
            live.clear(); live += stillLive
        }
        return table
    }

    private fun systemField(vb: SnmpVarbind): Pair<String, String>? {
        val leaf = vb.oid.removePrefix("$SYSTEM.").substringBefore('.')
        return when (leaf) {
            "1" -> ToolCopy.SYS_DESCR to (vb.str ?: return null)
            "2" -> ToolCopy.SYS_OBJECT_ID to (vb.str ?: return null)
            "3" -> ToolCopy.UPTIME to ToolCopy.uptime(vb.long ?: return null)
            "4" -> ToolCopy.SYS_CONTACT to (vb.str ?: return null)
            "5" -> ToolCopy.SYS_NAME to (vb.str ?: return null)
            "6" -> ToolCopy.SYS_LOCATION to (vb.str ?: return null)
            else -> null
        }
    }
    private fun prefixOf(mask: String): Int = mask.split('.').sumOf { Integer.bitCount(it.toIntOrNull()?.and(0xff) ?: 0) }

    companion object {
        const val SNMP_TIMEOUT_MS = 1000; const val SNMP_MAX_REQUESTS = 400; const val SNMP_MAX_INTERFACES = 64; const val SNMP_MAX_ADDRESSES = 64
        private const val SYSTEM = "1.3.6.1.2.1.1"
        private const val IF_DESCR = "1.3.6.1.2.1.2.2.1.2"; private const val IF_TYPE = "1.3.6.1.2.1.2.2.1.3"; private const val IF_SPEED = "1.3.6.1.2.1.2.2.1.5"
        private const val IF_MAC = "1.3.6.1.2.1.2.2.1.6"; private const val IF_STATUS = "1.3.6.1.2.1.2.2.1.8"; private const val IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15"
        private val IF_COLUMNS = listOf(IF_DESCR, IF_TYPE, IF_SPEED, IF_MAC, IF_STATUS)
        private const val ADDR = "1.3.6.1.2.1.4.20.1.1"; private const val MASK = "1.3.6.1.2.1.4.20.1.3"
        private val ADDR_COLUMNS = listOf(ADDR, MASK)
        private const val SATURATED_SPEED = 4_294_967_295L; private const val BITS_PER_MBIT = 1_000_000L
        private const val MAC_BYTES = 6; private const val SUMMARY_DESCR_CHARS = 40
        private val INDEX_ORDER = Comparator<String> { a, b ->
            val x = a.split('.').map { it.toLongOrNull() ?: 0 }; val y = b.split('.').map { it.toLongOrNull() ?: 0 }
            (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }?.let { x[it].compareTo(y[it]) } ?: x.size.compareTo(y.size)
        }
    }
}
```
(The `"x" to (vb.str ?: return null)` form returns from `systemField` when the value is missing. With the walk test's agent, "wlan0" has no ifType, so its detail reads "down, 54 Mbit/s"; "lo" at index 10 sorts after 2 numerically. A `Timeout` or `Error` step returns null from `next` and ends that step only; the next step still runs.)

`AppContainer.kt`: in `toolMap()` add:
```kotlin
        put(ToolId.SNMP_WALK, SnmpWalkTool(exchangeFor = { t -> UdpSnmpExchange(t.ip) }))
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.discovery.*" --tests "com.northwalessolutions.netprobe.engine.tools.SnmpWalkTest" --tests "com.northwalessolutions.netprobe.engine.tools.UdpSnmpExchangeTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `SnmpPduTest` (4), `SnmpWalkTest` (8) and `UdpSnmpExchangeTest` (3) pass, with the existing `SnmpTest` unchanged and green; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/SnmpWalkTool.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/UdpSnmpExchange.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/Snmp.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/SnmpPduTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/SnmpTestAgent.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/SnmpWalkTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/UdpSnmpExchangeTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "SNMP walk tool, GET and GETNEXT PDUs, BER extensions and the UDP exchange"
```

---

### Task 14: SMB part 1: MD4, NTLMv2 and SPNEGO

**Roles:** built after the other tools, with its own review (spec 15.2.4). Not the stronger model, but the reviewer reads every byte layout below against MS-SMB2 (2.2.1, 2.2.3, 2.2.4, 2.2.5, 2.2.6), MS-NLMP (2.2.1.1, 2.2.1.2, 2.2.1.3, 2.2.2.1, 3.3.2) and MS-SPNG / RFC 4178, and confirms the crypto against the published vectors in the tests. Split from the single SMB task at plan review G16: this task is pure byte code with no sockets; Task 15 builds the SMB2 messages, the framing and the probe on it.

**Confidence note for the reviewer:** every request this task and Task 15 build is pinned to bytes written out in `NtlmTest`, `SpnegoBytesTest` and `SmbBytesTest`; the layouts follow the specifications section by section. The NTLM crypto is checked against published vectors (RFC 1320 for MD4, MS-NLMP 4.2.2 and 4.2.4 for NTOWFv1, NTOWFv2, LMv2 and NTProofStr), which I transcribed from memory of those documents. If any published-vector assertion fails, the implementer stops and reports it to the controller with the computed value; the vector is never edited to match, and the guest leg (the only user of NTLMv2) is then dropped from the task as a recorded reduction rather than shipped unverified. The parsers are tested on fixtures built from the same reading of the specifications, so, as the spec says, the Task 20 device check against the CHNAS and a Windows machine is the real proof.

**Files:**
- Create (all under `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/`): `Md4.kt`, `Ntlm.kt`, `Spnego.kt`
- Test (under `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/`): `Md4Test.kt`, `NtlmTest.kt`, `SpnegoBytesTest.kt`, `NtlmFixtures.kt`

**Interfaces:**
- Consumes: the JDK only (`java.nio`, `javax.crypto.Mac` for HMAC-MD5).
- Produces:
```kotlin
object Md4 { fun digest(input: ByteArray): ByteArray }
object Ntlm { const val NEGOTIATE_UNICODE..NEGOTIATE_56; fun negotiateFlags(anonymous: Boolean): Int; fun negotiate(flags: Int): ByteArray
    data class TargetInfoNames(val netbiosComputer: String?, val netbiosDomain: String?, val dnsComputer: String?, val dnsDomain: String?, val timestamp: ByteArray?, val osBuild: String?)
    class Challenge(val serverChallenge: ByteArray, val targetInfo: ByteArray, val flags: Int, val names: TargetInfoNames)
    fun parseChallenge(bytes: ByteArray): Challenge?; fun ntowfv2(user: String, domain: String, password: String): ByteArray
    fun lmV2(ntowf: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray
    fun blob(timestamp: ByteArray, clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray
    fun ntResponseV2(ntowf: ByteArray, serverChallenge: ByteArray, blob: ByteArray): ByteArray
    fun authenticate(user: String, domain: String, lm: ByteArray, nt: ByteArray, flags: Int): ByteArray; fun filetime(ms: Long): ByteArray }
object Der { fun len(n: Int): ByteArray; fun tlv(tag: Int, content: ByteArray): ByteArray; fun oid(dotted: String): ByteArray }
object Spnego { fun negTokenInit(ntlm: ByteArray): ByteArray; fun negTokenResp(ntlm: ByteArray): ByteArray; fun extractNtlm(blob: ByteArray): ByteArray? }
```

- [ ] **Step 1: Write the failing tests**

`Md4Test.kt` (RFC 1320 appendix A.5 and MS-NLMP 4.2.2.1.2, so the test is not circular):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

class Md4Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    @Test fun `rfc 1320 test suite`() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(Md4.digest("".toByteArray())))
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", hex(Md4.digest("a".toByteArray())))
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(Md4.digest("abc".toByteArray())))
        assertEquals("d9130a8164549fe818874806e1c7014b", hex(Md4.digest("message digest".toByteArray())))
        assertEquals("d79e1c308aa5bbcdeea8ed63df412da9", hex(Md4.digest("abcdefghijklmnopqrstuvwxyz".toByteArray())))
        assertEquals("043f8582f241db351ce627e153e7f0e4", hex(Md4.digest("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toByteArray())))
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", hex(Md4.digest("1234567890".repeat(8).toByteArray())))
    }
    @Test fun `ntowfv1 of Password is the ms-nlmp value`() {
        assertEquals("a4f49c406510bdcab6824ee7c30fd852", hex(Md4.digest("Password".toByteArray(Charsets.UTF_16LE))))
    }
}
```

`NtlmTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-NLMP 4.2.4 published vectors, and the exact NEGOTIATE and anonymous AUTHENTICATE bytes. */
class NtlmTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val serverChallenge = bytes("0123456789abcdef")
    private val clientChallenge = bytes("aaaaaaaaaaaaaaaa")
    private val ntowf get() = Ntlm.ntowfv2("User", "Domain", "Password")

    @Test fun `ntowfv2 matches ms-nlmp 4_2_4`() { assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hex(ntowf)) }
    @Test fun `lmv2 matches ms-nlmp 4_2_4`() {
        assertEquals("86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", hex(Ntlm.lmV2(ntowf, serverChallenge, clientChallenge)))
    }
    @Test fun `ntproofstr matches ms-nlmp 4_2_4`() {
        val av = { id: Int, s: String -> val v = s.toByteArray(Charsets.UTF_16LE); byteArrayOf(id.toByte(), 0, v.size.toByte(), 0) + v }
        val targetInfo = av(2, "Domain") + av(1, "Server") + byteArrayOf(0, 0, 0, 0)
        val blob = Ntlm.blob(ByteArray(8), clientChallenge, targetInfo)
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", hex(Ntlm.ntResponseV2(ntowf, serverChallenge, blob).copyOfRange(0, 16)))
        assertEquals(hex(blob), hex(Ntlm.ntResponseV2(ntowf, serverChallenge, blob).copyOfRange(16, 16 + blob.size)))
    }
    @Test fun `the flags are the spec's set`() {
        assertEquals(0xA2888205.toInt(), Ntlm.negotiateFlags(anonymous = false))
        assertEquals(0xA2888A05.toInt(), Ntlm.negotiateFlags(anonymous = true))
    }
    @Test fun `negotiate message bytes`() {
        assertEquals("4e544c4d53535000" + "01000000" + "058288a2" + "0000" + "0000" + "28000000" + "0000" + "0000" + "28000000" + "060100000000000f",
            hex(Ntlm.negotiate(Ntlm.negotiateFlags(false))))
    }
    @Test fun `anonymous authenticate bytes per ms-nlmp 3_2_5_1_2`() {
        val expected = "4e544c4d53535000" + "03000000" +
            "0100" + "0100" + "48000000" +       // LmChallengeResponse: one zero byte at 72
            "0000" + "0000" + "49000000" +       // NtChallengeResponse: empty, at 73
            "0000" + "0000" + "48000000" +       // DomainName: empty
            "0000" + "0000" + "48000000" +       // UserName: empty
            "0000" + "0000" + "48000000" +       // Workstation: empty
            "0000" + "0000" + "49000000" +       // EncryptedRandomSessionKey: empty
            "058a88a2" + "060100000000000f" + "00"
        assertEquals(expected, hex(Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(true))))
    }
    @Test fun `a guest authenticate places user and responses after the 72 byte header`() {
        val a = Ntlm.authenticate("guest", "", ByteArray(24) { 1 }, ByteArray(30) { 2 }, Ntlm.negotiateFlags(false))
        assertEquals(72 + 10 + 24 + 30, a.size)
        assertEquals("0a000a0048000000", hex(a.copyOfRange(36, 44)))   // UserNameFields: len 10, offset 72
        assertEquals("guest", String(a.copyOfRange(72, 82), Charsets.UTF_16LE))
    }
    @Test fun `a challenge parses its names, timestamp and os build`() {
        val c = Ntlm.parseChallenge(NtlmFixtures.challenge())!!
        assertEquals("0123456789abcdef", hex(c.serverChallenge))
        assertEquals("Server", c.names.netbiosComputer); assertEquals("Domain", c.names.netbiosDomain); assertEquals("srv.lan", c.names.dnsComputer)
        assertNull(c.names.dnsDomain); assertEquals("0102030405060708", hex(c.names.timestamp!!)); assertEquals("10.0.19041", c.names.osBuild)
    }
    @Test fun `garbage and truncated challenges are null`() {
        assertNull(Ntlm.parseChallenge(ByteArray(10))); assertNull(Ntlm.parseChallenge(NtlmFixtures.challenge().copyOf(60)))
        assertNull(Ntlm.parseChallenge(NtlmFixtures.challenge().also { it[8] = 3 }))
    }
}
```

`SpnegoBytesTest.kt` (every DER byte written out):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-SPNG / RFC 4178 wrapping and the DER helpers pinned to bytes. */
class SpnegoBytesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `spnego negTokenInit and negTokenResp bytes`() {
        assertEquals("6023" + "06062b0601050502" + "a019" + "3017" + "a00e" + "300c" + "060a2b06010401823702020a" + "a205" + "0403010203",
            hex(Spnego.negTokenInit(byteArrayOf(1, 2, 3))))
        assertEquals("a109" + "3007" + "a205" + "0403010203", hex(Spnego.negTokenResp(byteArrayOf(1, 2, 3))))
    }
    @Test fun `der lengths use the long form above 127`() {
        assertEquals("7f", hex(Der.len(127))); assertEquals("8180", hex(Der.len(128))); assertEquals("820100", hex(Der.len(256)))
        assertEquals("060a2b06010401823702020a", hex(Der.oid("1.3.6.1.4.1.311.2.2.10")))
    }
    @Test fun `extractNtlm finds the ntlmssp blob inside spnego`() {
        val ntlm = Ntlm.negotiate(Ntlm.negotiateFlags(false))
        assertEquals(hex(ntlm), hex(Spnego.extractNtlm(Spnego.negTokenInit(ntlm))!!))
        assertNull(Spnego.extractNtlm(byteArrayOf(1, 2, 3)))
    }
}
```

`NtlmFixtures.kt` (test code; the CHALLENGE message laid out field by field, shared with Task 15's `SmbFixtures`):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NtlmFixtures {
    private fun le() = ByteOrder.LITTLE_ENDIAN
    private fun utf16(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun av(id: Int, value: ByteArray) = ByteBuffer.allocate(4 + value.size).order(le()).putShort(id.toShort()).putShort(value.size.toShort()).put(value).array()

    /**
     * NTLM CHALLENGE (MS-NLMP 2.2.1.2): signature, type 2, empty TargetName at 56, flags with NEGOTIATE_VERSION, ServerChallenge
     * 0123456789abcdef, Reserved, TargetInfo fields (offset 56), Version 10.0 build 19041, then AV pairs: NbDomain "Domain",
     * NbComputer "Server", DnsComputer "srv.lan", Timestamp 0102030405060708, EOL.
     */
    fun challenge(): ByteArray {
        val ti = av(2, utf16("Domain")) + av(1, utf16("Server")) + av(3, utf16("srv.lan")) + av(7, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) + av(0, ByteArray(0))
        val head = ByteBuffer.allocate(56).order(le())
            .put("NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)).putInt(2)
            .putShort(0).putShort(0).putInt(56)
            .putInt(Ntlm.negotiateFlags(false))
            .put(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte())).putLong(0)
            .putShort(ti.size.toShort()).putShort(ti.size.toShort()).putInt(56)
            .put(byteArrayOf(10, 0, 0x61, 0x4a, 0, 0, 0, 0x0f))
            .array()
        return head + ti
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.smb.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'Md4'`, `'Ntlm'`, `'Spnego'`, `'Der'`.

- [ ] **Step 3: Implement**

`smb/Md4.kt` (RFC 1320, little-endian words):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

/** MD4 per RFC 1320, for NTOWFv2 (spec 6.3). Verified against the RFC 1320 A.5 suite. */
object Md4 {
    private const val BLOCK = 64
    private const val LENGTH_AT = 56
    private const val R2 = 0x5a827999
    private const val R3 = 0x6ed9eba1
    private val ORDER2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val ORDER3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
    private val S1 = intArrayOf(3, 7, 11, 19); private val S2 = intArrayOf(3, 5, 9, 13); private val S3 = intArrayOf(3, 9, 11, 15)

    fun digest(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val pad = ((LENGTH_AT - (input.size + 1) % BLOCK) + BLOCK) % BLOCK
        val msg = input + byteArrayOf(0x80.toByte()) + ByteArray(pad) + ByteArray(8) { ((bitLen ushr (8 * it)) and 0xff).toByte() }
        val h = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
        val x = IntArray(16)
        for (block in 0 until msg.size / BLOCK) {
            for (i in 0 until 16) {
                val o = block * BLOCK + i * 4
                x[i] = (msg[o].toInt() and 0xff) or ((msg[o + 1].toInt() and 0xff) shl 8) or ((msg[o + 2].toInt() and 0xff) shl 16) or ((msg[o + 3].toInt() and 0xff) shl 24)
            }
            val v = h.copyOf()     // a, b, c, d
            // Each step updates one of a, d, c, b in turn (index 0, 3, 2, 1) from the other three.
            fun step(round: Int, i: Int, k: Int, s: Int) {
                val t = intArrayOf(0, 3, 2, 1)[i % 4]
                val b = v[(t + 1) % 4]; val c = v[(t + 2) % 4]; val d = v[(t + 3) % 4]
                val f = when (round) { 1 -> (b and c) or (b.inv() and d); 2 -> (b and c) or (b and d) or (c and d); else -> b xor c xor d }
                val add = when (round) { 1 -> 0; 2 -> R2; else -> R3 }
                v[t] = Integer.rotateLeft(v[t] + f + x[k] + add, s)
            }
            for (i in 0 until 16) step(1, i, i, S1[i % 4])
            for (i in 0 until 16) step(2, i, ORDER2[i], S2[i % 4])
            for (i in 0 until 16) step(3, i, ORDER3[i], S3[i % 4])
            for (i in 0 until 4) h[i] += v[i]
        }
        return ByteArray(16) { i -> ((h[i / 4] ushr (8 * (i % 4))) and 0xff).toByte() }
    }
}
```
(The step rotation follows RFC 1320 section 3.4: round 1 is `[abcd k s]` with a = (a + F(b,c,d) + X[k]) <<< s, applied to a, then d with (a, b, c), then c with (d, a, b), then b with (c, d, a). With `t` the register updated, its three successors in the cycle a, b, c, d are exactly those arguments: for t = d (3) they are a, b, c; for t = c (2) they are d, a, b; for t = b (1) they are c, d, a.)

`smb/Ntlm.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NTLM messages and NTLMv2 responses (MS-NLMP), hand-written with no library (spec 6.3). */
object Ntlm {
    const val NEGOTIATE_UNICODE = 0x00000001
    const val REQUEST_TARGET = 0x00000004
    const val NEGOTIATE_NTLM = 0x00000200
    const val NEGOTIATE_ANONYMOUS = 0x00000800
    const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
    const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
    const val NEGOTIATE_TARGET_INFO = 0x00800000
    const val NEGOTIATE_VERSION = 0x02000000
    const val NEGOTIATE_128 = 0x20000000
    const val NEGOTIATE_56 = Int.MIN_VALUE                    // 0x80000000
    private val SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)
    /** Version (MS-NLMP 2.2.2.10): 6.1 build 0, NTLMSSP_REVISION_W2K3 (15). */
    private val VERSION = byteArrayOf(6, 1, 0, 0, 0, 0, 0, 0x0f)
    private const val TYPE_NEGOTIATE = 1; private const val TYPE_CHALLENGE = 2; private const val TYPE_AUTHENTICATE = 3
    private const val NEGOTIATE_SIZE = 40; private const val AUTHENTICATE_HEADER = 72; private const val CHALLENGE_HEADER = 56
    private const val AV_EOL = 0; private const val AV_NB_COMPUTER = 1; private const val AV_NB_DOMAIN = 2
    private const val AV_DNS_COMPUTER = 3; private const val AV_DNS_DOMAIN = 4; private const val AV_TIMESTAMP = 7
    private const val FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L; private const val FILETIME_TICKS_PER_MS = 10_000L

    fun negotiateFlags(anonymous: Boolean): Int {
        val base = NEGOTIATE_UNICODE or REQUEST_TARGET or NEGOTIATE_NTLM or NEGOTIATE_ALWAYS_SIGN or NEGOTIATE_EXTENDED_SESSIONSECURITY or
            NEGOTIATE_TARGET_INFO or NEGOTIATE_VERSION or NEGOTIATE_128 or NEGOTIATE_56
        return if (anonymous) base or NEGOTIATE_ANONYMOUS else base
    }

    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.field(length: Int, offset: Int): ByteBuffer = putShort(length.toShort()).putShort(length.toShort()).putInt(offset)
    private fun unicode(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacMD5").run { init(SecretKeySpec(key, "HmacMD5")); doFinal(data) }

    /** NEGOTIATE_MESSAGE (2.2.1.1): no domain or workstation supplied; empty fields point at the end of the 40-byte message. */
    fun negotiate(flags: Int): ByteArray =
        buf(NEGOTIATE_SIZE).put(SIGNATURE).putInt(TYPE_NEGOTIATE).putInt(flags).field(0, NEGOTIATE_SIZE).field(0, NEGOTIATE_SIZE).put(VERSION).array()

    data class TargetInfoNames(val netbiosComputer: String?, val netbiosDomain: String?, val dnsComputer: String?, val dnsDomain: String?,
                               val timestamp: ByteArray?, val osBuild: String?)
    class Challenge(val serverChallenge: ByteArray, val targetInfo: ByteArray, val flags: Int, val names: TargetInfoNames)

    /** CHALLENGE_MESSAGE (2.2.1.2): flags at 20, ServerChallenge at 24, TargetInfoFields at 40, Version at 48; AV pairs (2.2.2.1). Null when malformed. */
    fun parseChallenge(bytes: ByteArray): Challenge? = runCatching {
        require(bytes.size >= CHALLENGE_HEADER && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(8) == TYPE_CHALLENGE)
        val flags = b.getInt(20)
        val serverChallenge = bytes.copyOfRange(24, 32)
        val tiLen = b.getShort(40).toInt() and 0xFFFF; val tiOff = b.getInt(44)
        require(tiOff >= 0 && tiOff + tiLen <= bytes.size)
        val targetInfo = bytes.copyOfRange(tiOff, tiOff + tiLen)
        val osBuild = if (flags and NEGOTIATE_VERSION != 0) "${bytes[48].toInt() and 0xff}.${bytes[49].toInt() and 0xff}.${b.getShort(50).toInt() and 0xFFFF}" else null
        var nbComputer: String? = null; var nbDomain: String? = null; var dnsComputer: String? = null; var dnsDomain: String? = null; var ts: ByteArray? = null
        var i = 0
        val t = ByteBuffer.wrap(targetInfo).order(ByteOrder.LITTLE_ENDIAN)
        while (i + 4 <= targetInfo.size) {
            val id = t.getShort(i).toInt() and 0xFFFF; val len = t.getShort(i + 2).toInt() and 0xFFFF
            require(i + 4 + len <= targetInfo.size)
            val value = targetInfo.copyOfRange(i + 4, i + 4 + len)
            when (id) {
                AV_EOL -> break
                AV_NB_COMPUTER -> nbComputer = String(value, Charsets.UTF_16LE)
                AV_NB_DOMAIN -> nbDomain = String(value, Charsets.UTF_16LE)
                AV_DNS_COMPUTER -> dnsComputer = String(value, Charsets.UTF_16LE)
                AV_DNS_DOMAIN -> dnsDomain = String(value, Charsets.UTF_16LE)
                AV_TIMESTAMP -> ts = value
            }
            i += 4 + len
        }
        Challenge(serverChallenge, targetInfo, flags, TargetInfoNames(nbComputer, nbDomain, dnsComputer, dnsDomain, ts, osBuild))
    }.getOrNull()

    /** NTOWFv2 (3.3.2) = HMAC_MD5(MD4(UNICODE(password)), UNICODE(Uppercase(user) + domain)). */
    fun ntowfv2(user: String, domain: String, password: String): ByteArray = hmacMd5(Md4.digest(unicode(password)), unicode(user.uppercase() + domain))
    /** LMv2 (3.3.2) = HMAC_MD5(NTOWFv2, ServerChallenge || ClientChallenge) || ClientChallenge. */
    fun lmV2(ntowf: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray = hmacMd5(ntowf, serverChallenge + clientChallenge) + clientChallenge
    /** The NTLMv2 temp blob (3.3.2): 0x01 0x01, Z(6), timestamp, client challenge, Z(4), target info, Z(4). */
    fun blob(timestamp: ByteArray, clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray =
        byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0) + timestamp + clientChallenge + ByteArray(4) + targetInfo + ByteArray(4)
    /** NtChallengeResponse = NTProofStr || blob, NTProofStr = HMAC_MD5(NTOWFv2, ServerChallenge || blob). */
    fun ntResponseV2(ntowf: ByteArray, serverChallenge: ByteArray, blob: ByteArray): ByteArray = hmacMd5(ntowf, serverChallenge + blob) + blob
    /** FILETIME little-endian: 100 ns ticks since 1601-01-01. */
    fun filetime(ms: Long): ByteArray = buf(8).putLong((ms + FILETIME_EPOCH_OFFSET_MS) * FILETIME_TICKS_PER_MS).array()

    /**
     * AUTHENTICATE_MESSAGE (2.2.1.3) without a MIC: a 72-byte header, then the payload in the order domain, user, workstation (empty),
     * LmChallengeResponse, NtChallengeResponse, with an empty EncryptedRandomSessionKey at the end.
     */
    fun authenticate(user: String, domain: String, lm: ByteArray, nt: ByteArray, flags: Int): ByteArray {
        val d = unicode(domain); val u = unicode(user)
        val dOff = AUTHENTICATE_HEADER; val uOff = dOff + d.size; val wOff = uOff + u.size
        val lmOff = wOff; val ntOff = lmOff + lm.size; val keyOff = ntOff + nt.size
        val header = buf(AUTHENTICATE_HEADER).put(SIGNATURE).putInt(TYPE_AUTHENTICATE)
            .field(lm.size, lmOff).field(nt.size, ntOff).field(d.size, dOff).field(u.size, uOff).field(0, wOff).field(0, keyOff)
            .putInt(flags).put(VERSION).array()
        return header + d + u + lm + nt
    }
}
```

`smb/Spnego.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import java.io.ByteArrayOutputStream

/** The minimal DER writer SPNEGO needs (X.690): definite lengths, short form under 128. */
object Der {
    private const val SHORT_MAX = 0x7F
    fun len(n: Int): ByteArray = when {
        n <= SHORT_MAX -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        n <= 0xFFFF -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream(); out.write((p[0] * 40 + p[1]).toInt())
        for (arc in p.drop(2)) {
            val groups = ArrayList<Int>(); var v = arc
            do { groups.add((v and 0x7F).toInt()); v = v ushr 7 } while (v > 0)
            for (k in groups.indices.reversed()) out.write(groups[k] or if (k > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }
}

/** SPNEGO (RFC 4178, MS-SPNG) wrapping of the NTLM tokens for SESSION_SETUP (spec 6.3). */
object Spnego {
    private const val SPNEGO_OID = "1.3.6.1.5.5.2"
    private const val NTLMSSP_OID = "1.3.6.1.4.1.311.2.2.10"
    private const val APPLICATION_0 = 0x60; private const val SEQUENCE = 0x30; private const val OCTET_STRING = 0x04
    private const val CTX0 = 0xa0; private const val CTX1 = 0xa1; private const val CTX2 = 0xa2
    private val NTLM_SIGNATURE = "NTLMSSP\u0000".toByteArray(Charsets.ISO_8859_1)

    /** InitialContextToken { thisMech spnego, [0] NegTokenInit { [0] mechTypes { NTLMSSP }, [2] mechToken } }. */
    fun negTokenInit(ntlm: ByteArray): ByteArray {
        val mechTypes = Der.tlv(CTX0, Der.tlv(SEQUENCE, Der.oid(NTLMSSP_OID)))
        val mechToken = Der.tlv(CTX2, Der.tlv(OCTET_STRING, ntlm))
        return Der.tlv(APPLICATION_0, Der.oid(SPNEGO_OID) + Der.tlv(CTX0, Der.tlv(SEQUENCE, mechTypes + mechToken)))
    }
    /** [1] NegTokenResp { [2] responseToken }. */
    fun negTokenResp(ntlm: ByteArray): ByteArray = Der.tlv(CTX1, Der.tlv(SEQUENCE, Der.tlv(CTX2, Der.tlv(OCTET_STRING, ntlm))))
    /** The NTLMSSP message inside a server's security buffer, found by its signature so DER variations between servers do not matter. */
    fun extractNtlm(blob: ByteArray): ByteArray? {
        for (i in 0..blob.size - NTLM_SIGNATURE.size) {
            if (NTLM_SIGNATURE.indices.all { blob[i + it] == NTLM_SIGNATURE[it] }) return blob.copyOfRange(i, blob.size)
        }
        return null
    }
}
```
(`extractNtlm` returns the rest of the buffer from the signature; `parseChallenge` reads only within its own declared offsets, so trailing DER bytes such as a mechListMIC are ignored.)

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.smb.*" --console=plain 2>&1 | tail -30
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `Md4Test` (2), `NtlmTest` (9) and `SpnegoBytesTest` (3) pass; `BUILD SUCCESSFUL`. A failing published-vector assertion follows the confidence note above: stop, report, do not edit the vector.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/Md4.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/Ntlm.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/Spnego.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/Md4Test.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/NtlmTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/SpnegoBytesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/NtlmFixtures.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "SMB part 1: MD4, NTLMv2 and SPNEGO"
```

---

### Task 15: SMB part 2: SMB2 NEGOTIATE and SESSION_SETUP, framing, the session probe and binding propagation

**Roles:** built after the other tools, with its own review (spec 15.2.4). Not the stronger model, but the reviewer reads every byte layout below against MS-SMB2 (2.2.1, 2.2.3, 2.2.4, 2.2.5, 2.2.6), and the AUTHENTICATE use of MS-NLMP 3.3.2; Task 14 already confirmed the crypto against the published vectors.

**Scope (spec 6.3):** NEGOTIATE and SESSION_SETUP only; each connection is closed after its last SESSION_SETUP reply. Nothing else is ever sent (no TREE_CONNECT, CREATE, READ, WRITE, IOCTL or LOGOFF), and a test asserts it. Share listing is Phase 3C.

**Files:**
- Create (all under `app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/`): `Smb2.kt`, `SmbTransport.kt`, `SmbSessionTool.kt`
- Modify: `AppContainer.kt` (the SMB_SESSION entry in `toolMap()`)
- Test (under `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/`): `SmbBytesTest.kt`, `SmbFixtures.kt`, `SmbFramingTest.kt`, `SmbSessionTest.kt`; and `app/src/test/java/com/northwalessolutions/netprobe/engine/tools/BindingPropagationTest.kt`

**Interfaces:**
- Consumes: Task 14 (`Ntlm`, `Spnego`, `Der`, and the test fixture `NtlmFixtures`), 3A's `Probe.connect`, `BoundNetwork`, `SocketBinder`, `CancellableIo.closing` (Task 3), `HttpClient` (Task 5), the `ToolRunnerTestSupport.Rig` (Task 6), `HttpFingerprintTool` / `HttpGet` (Task 11), `TlsPeek.details` (Task 12), `UdpSnmpExchange` (Task 13), Task 3 framework types and `ToolCopy`, `ToolApplicability`.
- Produces:
```kotlin
object Smb2 { /* constants; header, negotiate, sessionSetup builders; parseHeader, parseNegotiate, parseSessionSetup */ }
interface SmbTransport { suspend fun exchange(request: ByteArray): ByteArray?; fun close() }
object SmbFraming { const val MAX_MESSAGE = 65_536; fun frame(message: ByteArray): ByteArray; fun readFrame(input: InputStream): ByteArray? }
class TcpSmbTransport(socket: Socket, timeoutMs: Int) : SmbTransport
class SmbSessionTool(connect: suspend (ToolTarget) -> SmbTransport?, randomBytes: (Int) -> ByteArray = ..., clock: () -> Long = ...) : Tool { companion object { const val SMB_TIMEOUT_MS = 3000 } }
```

- [ ] **Step 1: Write the failing tests**

`SmbBytesTest.kt` (every request byte written out):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import org.junit.Assert.*
import org.junit.Test

/** MS-SMB2 layouts pinned to bytes; parsers of each response form; garbage never throws. */
class SmbBytesTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val zeros16 = "00".repeat(16)

    @Test fun `negotiate request bytes`() {
        val header = "fe534d42" + "4000" + "0000" + "00000000" + "0000" + "0100" + "00000000" + "00000000" + "0000000000000000" + "00000000" + "00000000" + "0000000000000000" + zeros16
        val body = "2400" + "0400" + "0100" + "0000" + "00000000" + "11".repeat(16) + "0000000000000000" + "0202" + "1002" + "0003" + "0203"
        assertEquals(header + body, hex(Smb2.negotiate(messageId = 0, clientGuid = ByteArray(16) { 0x11 })))
    }
    @Test fun `session setup request bytes`() {
        val header = "fe534d42" + "4000" + "0100" + "00000000" + "0100" + "0100" + "00000000" + "00000000" + "0100000000000000" + "00000000" + "00000000" + "8877665544332211" + zeros16
        val body = "1900" + "00" + "01" + "00000000" + "00000000" + "5800" + "0300" + "0000000000000000" + "010203"
        assertEquals(header + body, hex(Smb2.sessionSetup(messageId = 1, sessionId = 0x1122334455667788L, creditCharge = 1, securityBlob = byteArrayOf(1, 2, 3))))
    }
    @Test fun `negotiate responses give dialect and the signing bit`() {
        val required = Smb2.parseNegotiate(SmbFixtures.negotiateResponse(dialect = 0x0302, securityMode = 0x0003))!!
        assertEquals(0, required.status); assertEquals(0x0302, required.dialect); assertTrue(required.signingRequired)
        val enabled = Smb2.parseNegotiate(SmbFixtures.negotiateResponse(dialect = 0x0210, securityMode = 0x0001))!!
        assertEquals(0x0210, enabled.dialect); assertFalse(enabled.signingRequired)
        for (d in listOf(0x0202, 0x0300)) assertEquals(d, Smb2.parseNegotiate(SmbFixtures.negotiateResponse(d, 1))!!.dialect)
        val refused = Smb2.parseNegotiate(SmbFixtures.errorResponse(Smb2.NEGOTIATE, 0xC00000BB.toInt()))!!
        assertEquals(0xC00000BB.toInt(), refused.status)
    }
    @Test fun `session setup responses give status, session id, flags and the blob`() {
        val more = Smb2.parseSessionSetup(SmbFixtures.challengeResponse(sessionId = 0x55L))!!
        assertEquals(Smb2.STATUS_MORE_PROCESSING_REQUIRED, more.status); assertEquals(0x55L, more.sessionId)
        assertNotNull(Ntlm.parseChallenge(Spnego.extractNtlm(more.securityBlob)!!))
        val nullSession = Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x55L, Smb2.SESSION_FLAG_IS_NULL))!!
        assertEquals(Smb2.SESSION_FLAG_IS_NULL, nullSession.sessionFlags)
        val guest = Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x56L, Smb2.SESSION_FLAG_IS_GUEST))!!
        assertEquals(Smb2.SESSION_FLAG_IS_GUEST, guest.sessionFlags)
        for (status in listOf(SmbFixtures.STATUS_LOGON_FAILURE, SmbFixtures.STATUS_ACCOUNT_DISABLED)) {
            val failed = Smb2.parseSessionSetup(SmbFixtures.errorResponse(Smb2.SESSION_SETUP, status))!!
            assertEquals(status, failed.status); assertEquals(0, failed.sessionFlags); assertEquals(0, failed.securityBlob.size)
        }
    }
    @Test fun `truncated or foreign messages parse to null`() {
        assertNull(Smb2.parseNegotiate(ByteArray(10))); assertNull(Smb2.parseSessionSetup(ByteArray(64)))
        assertNull(Smb2.parseHeader(byteArrayOf(0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte()) + ByteArray(60)))   // SMB1, not SMB2
        assertNull(Smb2.parseSessionSetup(SmbFixtures.sessionSetupResponse(0, 1, 0).copyOf(70)))
    }
}
```

`SmbFixtures.kt` (test code; every response header and DER byte laid out by hand, never by `Smb2` or `Spnego`, so a layout bug in the code under test cannot cancel itself out: plan review G19):
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SmbFixtures {
    const val STATUS_LOGON_FAILURE = 0xC000006D.toInt()
    const val STATUS_ACCOUNT_DISABLED = 0xC0000072.toInt()
    const val STATUS_ACCESS_DENIED = 0xC0000022.toInt()
    private const val STATUS_MORE_PROCESSING = 0xC0000016.toInt()
    private const val COMMAND_NEGOTIATE = 0x0000
    private const val COMMAND_SESSION_SETUP = 0x0001
    private fun le() = ByteOrder.LITTLE_ENDIAN
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(le()).putShort(v.toShort()).array()
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(le()).putInt(v).array()
    private fun le64(v: Long) = ByteBuffer.allocate(8).order(le()).putLong(v).array()

    /**
     * A response SYNC header (MS-SMB2 2.2.1.2), 64 bytes: ProtocolId fe 'S' 'M' 'B', StructureSize 64, CreditCharge 0, Status,
     * Command, CreditResponse 1, Flags SERVER_TO_REDIR, NextCommand 0, MessageId, Reserved 0, TreeId 0, SessionId, Signature zero.
     */
    private fun header(command: Int, status: Int, messageId: Long, sessionId: Long): ByteArray =
        hex("fe534d42" + "4000" + "0000") + le32(status) + le16(command) + hex("0100" + "01000000" + "00000000") + le64(messageId) +
            hex("00000000" + "00000000") + le64(sessionId) + ByteArray(16)

    /** NEGOTIATE response (MS-SMB2 2.2.4): StructureSize 65, SecurityMode, DialectRevision, then zeros to a 64-byte fixed part. */
    fun negotiateResponse(dialect: Int, securityMode: Int): ByteArray {
        val body = ByteBuffer.allocate(64).order(le()).putShort(65).putShort(securityMode.toShort()).putShort(dialect.toShort()).array()
        return header(COMMAND_NEGOTIATE, 0, 0, 0) + body
    }
    /** SMB2 ERROR response (MS-SMB2 2.2.2): StructureSize 9, ErrorContextCount 0, Reserved, ByteCount 0, one ErrorData byte. */
    fun errorResponse(command: Int, status: Int): ByteArray =
        header(command, status, 0, 0) + ByteBuffer.allocate(9).order(le()).putShort(9).array()
    /** SESSION_SETUP response (MS-SMB2 2.2.6): StructureSize 9, SessionFlags, SecurityBufferOffset 72, SecurityBufferLength, buffer. */
    fun sessionSetupResponse(status: Int, sessionId: Long, flags: Int, blob: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteBuffer.allocate(8 + blob.size).order(le()).putShort(9).putShort(flags.toShort()).putShort(72).putShort(blob.size.toShort()).put(blob).array()
        return header(COMMAND_SESSION_SETUP, status, 1, sessionId) + body
    }
    /**
     * The challenge reply: a negTokenResp (RFC 4178 4.2.2) around the 122-byte CHALLENGE, its DER written out:
     * a1 81 80 (128 bytes) { 30 7e { a2 7c { 04 7a <CHALLENGE> } } }.
     */
    fun challengeResponse(sessionId: Long): ByteArray {
        val ntlm = NtlmFixtures.challenge()
        check(ntlm.size == CHALLENGE_BYTES) { "the DER lengths below assume a $CHALLENGE_BYTES-byte CHALLENGE, got ${ntlm.size}" }
        return sessionSetupResponse(STATUS_MORE_PROCESSING, sessionId, 0, hex("a18180" + "307e" + "a27c" + "047a") + ntlm)
    }
    private const val CHALLENGE_BYTES = 122
}
```

`SmbFramingTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Direct-TCP framing (MS-SMB2 2.1): a zero byte, a 24-bit big-endian length, the message; replies over 64 KB are refused. */
class SmbFramingTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val servers = mutableListOf<ServerSocket>()
    @After fun tearDown() { servers.forEach { runCatching { it.close() } } }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun `frame bytes`() {
        assertEquals("00000003010203", hex(SmbFraming.frame(byteArrayOf(1, 2, 3))))
        assertEquals("00010000", hex(SmbFraming.frame(ByteArray(65_536)).copyOf(4)))
    }
    @Test fun `read frame rejects a non zero first byte, a short stream and an oversized length`() {
        assertEquals("0102", hex(SmbFraming.readFrame(byteArrayOf(0, 0, 0, 2, 1, 2).inputStream())!!))
        assertNull(SmbFraming.readFrame(byteArrayOf(1, 0, 0, 2, 1, 2).inputStream()))
        assertNull(SmbFraming.readFrame(byteArrayOf(0, 0, 0, 5, 1).inputStream()))
        assertNull(SmbFraming.readFrame(byteArrayOf(0, 0x01, 0, 1).inputStream()))     // 65537 bytes declared
    }
    @Test fun `the tcp transport sends one frame and reads one reply`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { c ->
            val got = SmbFraming.readFrame(c.getInputStream())!!
            c.getOutputStream().write(SmbFraming.frame(got.reversedArray())); c.getOutputStream().flush()
        } } }
        val t = TcpSmbTransport(Socket(lo, s.localPort), 1000)
        assertEquals("030201", hex(t.exchange(byteArrayOf(1, 2, 3))!!))
        t.close()
    }
    @Test fun `no reply is null`() = runBlocking {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { s.accept().use { Thread.sleep(2_000) } } }
        val t = TcpSmbTransport(Socket(lo, s.localPort), 200)
        assertNull(t.exchange(byteArrayOf(1))); t.close()
    }
}
```

`SmbSessionTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** spec 6.3 and 12.1: the sequence over scripted connections, the summaries, header sequencing, and nothing past SESSION_SETUP. */
class SmbSessionTest {
    private val target = ToolTarget(7, ToolTestSupport.ip("10.0.3.59"), 445, false, null)
    private class Script(vararg replies: ByteArray?) : SmbTransport {
        val sent = mutableListOf<ByteArray>(); var closed = false
        private val queue = ArrayDeque(replies.toList())
        override suspend fun exchange(request: ByteArray): ByteArray? { sent += request; return queue.removeFirstOrNull() }
        override fun close() { closed = true }
    }
    private fun le16(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt() and 0xFFFF
    private fun le64(b: ByteArray, at: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong(at)
    private suspend fun run(vararg connections: Script?): Triple<ToolResult, CollectingProgress, List<Script?>> {
        val queue = ArrayDeque(connections.toList()); val p = CollectingProgress()
        val r = SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L }).run(target, ToolOptions.None, control(), p)
        return Triple(r, p, connections.toList())
    }
    private fun anonymous(final: ByteArray) = Script(SmbFixtures.negotiateResponse(0x0302, 3), SmbFixtures.challengeResponse(0x77), final)
    private fun guest(dialect: Int, final: ByteArray) = Script(SmbFixtures.negotiateResponse(dialect, 1), SmbFixtures.challengeResponse(0x78), final)

    @Test fun `a negotiate lost to a pause is retried once on a fresh connection`() = runTest {
        val c = ToolTestSupport.EpochControl()
        val frozen = object : SmbTransport {
            var closed = false
            override suspend fun exchange(request: ByteArray): ByteArray? { c.epoch++; return null }        // a pause began mid-read
            override fun close() { closed = true }
        }
        val queue = ArrayDeque<SmbTransport?>(listOf(frozen, anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))))
        val r = SmbSessionTool(connect = { queue.removeFirstOrNull() }, randomBytes = { n -> ByteArray(n) { 0x5a } }, clock = { 0L })
            .run(target, ToolOptions.None, c, CollectingProgress())
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary); assertTrue(frozen.closed)
    }
    @Test fun `anonymous allowed, guest refused, signing required`() = runTest {
        val (r, p, conns) = run(anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE)))
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals("SMB 3.0.2, signing required, anonymous allowed, guest refused", r.summary)
        assertEquals(listOf("SMB 3.0.2"), p.of(FindingKind.SMB_DIALECT).map { it.value }); assertEquals("Required", p.of(FindingKind.SMB_SIGNING).single().value)
        assertEquals(listOf("Anonymous allowed", "Guest refused"), p.of(FindingKind.SMB_SESSION).map { it.value })
        assertEquals(listOf("NetBIOS name" to "Server", "NetBIOS domain" to "Domain", "DNS name" to "srv.lan", "OS build (NTLM)" to "10.0.19041"),
            p.of(FindingKind.SMB_HOST).map { it.detail to it.value })
        assertEquals(listOf("smb.netbiosName=Server", "smb.dnsName=srv.lan", "smb.domain=Domain", "smb.osBuild=10.0.19041"), p.hints())
        assertTrue(conns.all { it!!.closed })
    }
    @Test fun `guest only, mapped to guest, reads as allowed`() = runTest {
        val (r, p, _) = run(anonymous(SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCESS_DENIED)),
            guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, Smb2.SESSION_FLAG_IS_GUEST)))
        assertEquals(listOf("Anonymous refused", "Guest mapped to guest"), p.of(FindingKind.SMB_SESSION).map { it.value })
        assertEquals("SMB 3.0.2, signing required, anonymous refused, guest allowed", r.summary)
    }
    @Test fun `both refused on smb 2_1 without required signing`() = runTest {
        val first = Script(SmbFixtures.negotiateResponse(0x0210, 1), SmbFixtures.challengeResponse(0x77), SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCESS_DENIED))
        val (r, _, _) = run(first, guest(0x0210, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_ACCOUNT_DISABLED)))
        assertEquals("SMB 2.1, signing not required, anonymous refused, guest refused", r.summary)
    }
    @Test fun `guest allowed without the guest flag`() = runTest {
        val (r, p, _) = run(anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL)),
            guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, 0)))
        assertEquals("Guest allowed", p.of(FindingKind.SMB_SESSION).last().value); assertTrue(r.summary.endsWith("guest allowed"))
    }
    @Test fun `headers sequence message ids, carry the session id and charge credits by dialect`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        val second = guest(0x0202, SmbFixtures.errorResponse(Smb2.SESSION_SETUP, SmbFixtures.STATUS_LOGON_FAILURE))
        run(first, second)
        assertEquals(listOf(0L, 1L, 2L), first.sent.map { le64(it, 24) })
        assertEquals(listOf(0L, 0L, 0x77L), first.sent.map { le64(it, 40) })
        assertEquals(listOf(0, 1, 1), first.sent.map { le16(it, 6) })            // 3.0.2: CreditCharge 1 after NEGOTIATE
        assertEquals(listOf(0, 0, 0), second.sent.map { le16(it, 6) })           // 2.0.2: CreditCharge 0
        assertEquals(listOf(1, 1, 1), first.sent.map { le16(it, 14) })           // CreditRequest 1
    }
    @Test fun `the anonymous leg sends the exact anonymous authenticate`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        run(first, null)
        val sentAuth = Spnego.extractNtlm(first.sent[2].copyOfRange(88, first.sent[2].size))!!
        assertArrayEquals(Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(true)), sentAuth)
    }
    @Test fun `nothing but negotiate and session setup is ever written`() = runTest {
        val first = anonymous(SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x77, Smb2.SESSION_FLAG_IS_NULL))
        val second = guest(0x0302, SmbFixtures.sessionSetupResponse(Smb2.STATUS_SUCCESS, 0x78, Smb2.SESSION_FLAG_IS_GUEST))
        run(first, second)
        val commands = (first.sent + second.sent).map { le16(it, 12) }
        assertEquals(listOf(0, 1, 1, 0, 1, 1), commands)
        assertTrue((first.sent + second.sent).all { Smb2.parseHeader(it) != null })
    }
    @Test fun `no connection or no reply is FAILED with no response`() = runTest {
        assertEquals("No SMB response on this port", run(null).first.summary)
        val silent = Script()
        val (r, _, _) = run(silent)
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SMB response on this port", r.summary); assertTrue(silent.closed)
    }
    @Test fun `a refused negotiate is no dialect in common`() = runTest {
        val (r, _, _) = run(Script(SmbFixtures.errorResponse(Smb2.NEGOTIATE, 0xC00000BB.toInt())))
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No SMB2 dialect in common", r.summary)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/tools/BindingPropagationTest.kt` (spec 12.1 "Binding propagation", plan review G6):
```kotlin
package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.serviceid.TlsPeek
import com.northwalessolutions.netprobe.engine.tools.ToolRunnerTestSupport.Rig
import com.northwalessolutions.netprobe.engine.tools.smb.TcpSmbTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Every tool socket is bound through the BoundNetwork on the coroutine context, which is how ScanBinding.run routes a run over
 * Wi-Fi past a VPN on a client's network. The four calls below are exactly the socket seams AppContainer's tool factories use:
 * HttpClient.request (dir-bruteforce, fingerprint), TlsPeek.details (tls-certificate), UdpSnmpExchange (snmp-walk) and
 * Probe.connect into TcpSmbTransport (smb-session). A refactor that drops the context (a stray withContext(EmptyCoroutineContext
 * + Dispatchers.IO), a socket opened without Probe.connect) fails the first test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BindingPropagationTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val closers = mutableListOf<Closeable>()
    @After fun closeAll() { closers.forEach { runCatching { it.close() } } }

    private class RecordingBinder : SocketBinder {
        val tcp = AtomicInteger(0); val udp = AtomicInteger(0)
        override fun bind(socket: Socket) { tcp.incrementAndGet() }
        override fun bind(socket: DatagramSocket) { udp.incrementAndGet() }
        override fun openConnection(url: URL): URLConnection = throw UnsupportedOperationException("tools never open a URLConnection")
    }
    /** A VPN in lockdown: every bind is refused. */
    private object RefusingBinder : SocketBinder {
        override fun bind(socket: Socket): Unit = throw SecurityException("vpn lockdown")
        override fun bind(socket: DatagramSocket): Unit = throw SecurityException("vpn lockdown")
        override fun openConnection(url: URL): URLConnection = throw SecurityException("vpn lockdown")
    }
    /** A loopback server that answers every connection with an empty 404 and counts the connections it accepted. */
    private fun http404(accepted: AtomicInteger = AtomicInteger(0)): ServerSocket {
        val s = ServerSocket(0, 50, lo); closers += s
        thread(isDaemon = true) {
            while (!s.isClosed) runCatching {
                s.accept().use { c -> accepted.incrementAndGet(); c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
        }
        return s
    }

    @Test fun `http, tls, snmp and smb sockets all reach the context binder`() = runBlocking {
        val binder = RecordingBinder(); val tcp = http404()
        val udp = DatagramSocket(0, lo); closers += udp                               // never answers: the exchange times out after binding
        withContext(BoundNetwork(binder)) {
            HttpClient(500).request(lo, tcp.localPort, false, "GET", "/")
            TlsPeek().details(lo, tcp.localPort, "nas.lan", 500)                    // the named handshake fails on plain HTTP, then the IP retry
            UdpSnmpExchange(lo, udp.localPort, 200).getNext(listOf("1.3.6.1.2.1.1"))
            (Probe.connect(lo, tcp.localPort, 500, keepOpen = true) as? Probe.Outcome.Connected)?.socket?.let { TcpSmbTransport(it, 500).close() }
        }
        assertEquals("http 1, tls 2 (named, then IP), smb 1", 4, binder.tcp.get())
        assertEquals("snmp 1", 1, binder.udp.get())
    }
    @Test fun `a refusing binder fails the run with the bind wording and opens no unbound socket`() = runTest {
        val accepted = AtomicInteger(0); val tcp = http404(accepted)
        val tool = HttpFingerprintTool({ t -> HttpGet { tls, port, path -> HttpClient(500).request(t.ip, port, tls, "GET", path) } }, emptyList())
        val rig = Rig(backgroundScope, tool, bind = { _, _, block -> withContext(BoundNetwork(RefusingBinder)) { block() } })
        rig.runner.start(ToolTarget(7, lo, tcp.localPort, tls = false, serverName = null), ToolId.HTTP_FINGERPRINT, ToolOptions.None); runCurrent()
        assertEquals(Triple(1L, ToolRunStatus.FAILED, "Tool skipped: could not bind to Wi-Fi"), rig.store.finished.single())
        Thread.sleep(SETTLE_MS)
        assertEquals("no connection reached the server", 0, accepted.get())
    }
    private companion object { const val SETTLE_MS = 200L }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.smb.*" --tests "com.northwalessolutions.netprobe.engine.tools.BindingPropagationTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'Smb2'`, `'SmbFraming'`, `'SmbSessionTool'`, `'TcpSmbTransport'`.

- [ ] **Step 3: Implement**

`smb/Smb2.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SMB2 NEGOTIATE and SESSION_SETUP only (MS-SMB2 2.2.1, 2.2.3 to 2.2.6); nothing else is ever built (spec 6.3). */
object Smb2 {
    const val HEADER_SIZE = 64
    const val NEGOTIATE = 0x0000
    const val SESSION_SETUP = 0x0001
    const val STATUS_SUCCESS = 0
    const val STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016.toInt()
    val DIALECTS = listOf(0x0202, 0x0210, 0x0300, 0x0302)
    const val SIGNING_ENABLED = 0x0001
    const val SIGNING_REQUIRED = 0x0002
    const val SESSION_FLAG_IS_GUEST = 0x0001
    const val SESSION_FLAG_IS_NULL = 0x0002
    private val PROTOCOL_ID = byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
    private const val CREDIT_REQUEST = 1
    private const val NEGOTIATE_STRUCTURE = 36; private const val NEGOTIATE_RESPONSE_STRUCTURE = 65
    private const val SESSION_SETUP_STRUCTURE = 25; private const val SESSION_SETUP_RESPONSE_STRUCTURE = 9
    private const val SESSION_SETUP_FIXED = 24
    private const val GUID_BYTES = 16

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun creditCharge(dialect: Int): Int = if (dialect == 0x0202) 0 else 1

    /** The 64-byte SYNC header (2.2.1.2): ProtocolId, StructureSize 64, CreditCharge, Status 0, Command, CreditRequest 1, Flags 0,
     *  NextCommand 0, MessageId, Reserved, TreeId 0, SessionId, Signature zero (no request is signed). */
    fun header(command: Int, messageId: Long, sessionId: Long, creditCharge: Int): ByteArray = le(HEADER_SIZE)
        .put(PROTOCOL_ID).putShort(HEADER_SIZE.toShort()).putShort(creditCharge.toShort()).putInt(0)
        .putShort(command.toShort()).putShort(CREDIT_REQUEST.toShort()).putInt(0).putInt(0)
        .putLong(messageId).putInt(0).putInt(0).putLong(sessionId).put(ByteArray(16)).array()

    /** NEGOTIATE request (2.2.3): four dialects, SecurityMode SIGNING_ENABLED, no capabilities, a ClientGuid, no negotiate contexts. */
    fun negotiate(messageId: Long, clientGuid: ByteArray): ByteArray {
        require(clientGuid.size == GUID_BYTES)
        val body = le(NEGOTIATE_STRUCTURE + DIALECTS.size * 2).putShort(NEGOTIATE_STRUCTURE.toShort()).putShort(DIALECTS.size.toShort())
            .putShort(SIGNING_ENABLED.toShort()).putShort(0).putInt(0).put(clientGuid).putLong(0)
        DIALECTS.forEach { body.putShort(it.toShort()) }
        return header(NEGOTIATE, messageId, 0, 0) + body.array()
    }
    /** SESSION_SETUP request (2.2.5): Flags 0, SecurityMode SIGNING_ENABLED, no capabilities, the blob at offset 88 (64 + 24). */
    fun sessionSetup(messageId: Long, sessionId: Long, creditCharge: Int, securityBlob: ByteArray): ByteArray {
        val body = le(SESSION_SETUP_FIXED + securityBlob.size).putShort(SESSION_SETUP_STRUCTURE.toShort()).put(0.toByte()).put(SIGNING_ENABLED.toByte())
            .putInt(0).putInt(0).putShort((HEADER_SIZE + SESSION_SETUP_FIXED).toShort()).putShort(securityBlob.size.toShort()).putLong(0).put(securityBlob)
        return header(SESSION_SETUP, messageId, sessionId, creditCharge) + body.array()
    }

    data class Header(val status: Int, val command: Int, val messageId: Long, val sessionId: Long)
    data class NegotiateResponse(val status: Int, val dialect: Int, val signingRequired: Boolean)
    class SessionSetupResponse(val status: Int, val sessionId: Long, val sessionFlags: Int, val securityBlob: ByteArray)

    fun parseHeader(m: ByteArray): Header? {
        if (m.size < HEADER_SIZE || !m.copyOfRange(0, 4).contentEquals(PROTOCOL_ID)) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        return Header(b.getInt(8), b.getShort(12).toInt() and 0xFFFF, b.getLong(24), b.getLong(40))
    }
    /** A non-zero status means no dialect was agreed; the body is then an ERROR response and is not read. */
    fun parseNegotiate(m: ByteArray): NegotiateResponse? {
        val h = parseHeader(m) ?: return null
        if (h.command != NEGOTIATE) return null
        if (h.status != STATUS_SUCCESS) return NegotiateResponse(h.status, 0, false)
        if (m.size < HEADER_SIZE + 6) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(HEADER_SIZE).toInt() and 0xFFFF) != NEGOTIATE_RESPONSE_STRUCTURE) return null
        val securityMode = b.getShort(HEADER_SIZE + 2).toInt() and 0xFFFF
        return NegotiateResponse(h.status, b.getShort(HEADER_SIZE + 4).toInt() and 0xFFFF, securityMode and SIGNING_REQUIRED != 0)
    }
    /** SESSION_SETUP response (2.2.6) for SUCCESS and MORE_PROCESSING_REQUIRED; any other status is an ERROR response with no flags or blob. */
    fun parseSessionSetup(m: ByteArray): SessionSetupResponse? {
        val h = parseHeader(m) ?: return null
        if (h.command != SESSION_SETUP) return null
        if (h.status != STATUS_SUCCESS && h.status != STATUS_MORE_PROCESSING_REQUIRED) return SessionSetupResponse(h.status, h.sessionId, 0, ByteArray(0))
        if (m.size < HEADER_SIZE + 8) return null
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(HEADER_SIZE).toInt() and 0xFFFF) != SESSION_SETUP_RESPONSE_STRUCTURE) return null
        val flags = b.getShort(HEADER_SIZE + 2).toInt() and 0xFFFF
        val off = b.getShort(HEADER_SIZE + 4).toInt() and 0xFFFF; val len = b.getShort(HEADER_SIZE + 6).toInt() and 0xFFFF
        if (len > 0 && (off < HEADER_SIZE || off + len > m.size)) return null
        return SessionSetupResponse(h.status, h.sessionId, flags, if (len == 0) ByteArray(0) else m.copyOfRange(off, off + len))
    }
}
```

`smb/SmbTransport.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.engine.common.CancellableIo
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/** One SMB2 request and its single reply over one connection; null when no reply arrives. */
interface SmbTransport {
    suspend fun exchange(request: ByteArray): ByteArray?
    fun close()
}

/** Direct TCP transport framing (MS-SMB2 2.1): a zero byte and a 24-bit big-endian length before each message; replies capped at 64 KB. */
object SmbFraming {
    const val MAX_MESSAGE = 65_536
    private const val FRAME_HEADER = 4
    fun frame(message: ByteArray): ByteArray {
        val n = message.size
        return byteArrayOf(0, (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + message
    }
    fun readFrame(input: InputStream): ByteArray? {
        val head = readFully(input, FRAME_HEADER) ?: return null
        if (head[0].toInt() != 0) return null
        val length = ((head[1].toInt() and 0xff) shl 16) or ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        if (length > MAX_MESSAGE) return null
        return readFully(input, length)
    }
    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n); var got = 0
        while (got < n) { val r = input.read(out, got, n - got); if (r < 0) return null; got += r }
        return out
    }
}

/** A connected TCP socket from Probe.connect; every exchange runs inside CancellableIo.closing, so Stop ends a blocked read at once. */
class TcpSmbTransport(private val socket: Socket, private val timeoutMs: Int) : SmbTransport {
    override suspend fun exchange(request: ByteArray): ByteArray? = try {
        CancellableIo.closing(socket) {
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply { write(SmbFraming.frame(request)); flush() }
            SmbFraming.readFrame(socket.getInputStream())
        }
    } catch (e: CancellationException) { throw e } catch (_: IOException) { null }
    override fun close() { runCatching { socket.close() } }
}
```
(`SocketTimeoutException` is an `IOException`, so a silent server gives null.)

`smb/SmbSessionTool.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.tools.smb

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.*
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.security.SecureRandom

/**
 * SMB2 session probe (spec 6.3): NEGOTIATE; on the same connection an anonymous SESSION_SETUP pair; on a new connection the same
 * NEGOTIATE and a guest SESSION_SETUP pair with an NTLMv2 response. Each connection is closed after its last SESSION_SETUP reply.
 */
class SmbSessionTool(
    private val connect: suspend (ToolTarget) -> SmbTransport?,
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = ToolId.SMB_SESSION
    override val name = ToolCopy.SMB_SESSION
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.SMB_SESSION, port, service)

    private class Leg(val allowed: Boolean, val names: Ntlm.TargetInfoNames?)
    private enum class Guest { ALLOWED, MAPPED, REFUSED }
    private sealed interface Opened {
        object NoResponse : Opened
        object NoDialect : Opened
        class Ok(val neg: Smb2.NegotiateResponse, val anonymous: Leg) : Opened
    }

    /** The first connection: NEGOTIATE, then the anonymous SESSION_SETUP pair; the connection is always closed. */
    private suspend fun open(target: ToolTarget, control: RunControl): Opened {
        val conn = connect(target) ?: return Opened.NoResponse
        try {
            val n = negotiate(conn) ?: return Opened.NoResponse
            if (n.status != Smb2.STATUS_SUCCESS || n.dialect !in Smb2.DIALECTS) return Opened.NoDialect
            control.gate()
            return Opened.Ok(n, sessionLeg(conn, n, anonymousLeg = true))
        } finally { conn.close() }
    }

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        var found = 0
        suspend fun emit(kind: FindingKind, value: String, detail: String) { found++; progress.finding(Finding(kind, value, detail)); progress.update(found, null, found) }
        suspend fun hint(value: String) { progress.finding(Finding(FindingKind.HINT, value)) }

        control.gate()
        // spec 4.8 (plan review G5): a first connection that got no reply while a pause began is retried once, on a fresh connection.
        val (neg, anonymous) = when (val opened = control.excusedOnce<Opened>({ it === Opened.NoResponse }) { open(target, control) }) {
            Opened.NoResponse -> return ToolResult(ToolRunStatus.FAILED, ToolCopy.SMB_NO_RESPONSE)
            Opened.NoDialect -> return ToolResult(ToolRunStatus.FAILED, ToolCopy.SMB_NO_DIALECT)
            is Opened.Ok -> opened.neg to opened.anonymous
        }

        val dialect = ToolCopy.dialectName(neg.dialect)!!
        emit(FindingKind.SMB_DIALECT, dialect, ToolCopy.DIALECT)
        emit(FindingKind.SMB_SIGNING, if (neg.signingRequired) ToolCopy.REQUIRED else ToolCopy.ENABLED_NOT_REQUIRED, ToolCopy.SIGNING)
        anonymous.names?.let { n ->
            n.netbiosComputer?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.NETBIOS_NAME) }
            n.netbiosDomain?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.NETBIOS_DOMAIN) }
            n.dnsComputer?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.DNS_NAME) }
            n.dnsDomain?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.DNS_DOMAIN) }
            n.osBuild?.let { emit(FindingKind.SMB_HOST, it, ToolCopy.OS_BUILD) }
        }
        emit(FindingKind.SMB_SESSION, if (anonymous.allowed) ToolCopy.ANONYMOUS_ALLOWED else ToolCopy.ANONYMOUS_REFUSED, ToolCopy.SESSION)

        control.gate()
        val guest = control.excusedOnce<Guest>({ it == Guest.REFUSED }) {
            connect(target)?.let { conn ->
                try {
                    val again = negotiate(conn)
                    if (again == null || again.status != Smb2.STATUS_SUCCESS || again.dialect !in Smb2.DIALECTS) Guest.REFUSED else guestLeg(conn, again)
                } finally { conn.close() }
            } ?: Guest.REFUSED
        }
        emit(FindingKind.SMB_SESSION, when (guest) { Guest.ALLOWED -> ToolCopy.GUEST_ALLOWED; Guest.MAPPED -> ToolCopy.GUEST_MAPPED; Guest.REFUSED -> ToolCopy.GUEST_REFUSED }, ToolCopy.SESSION)

        anonymous.names?.let { n ->
            n.netbiosComputer?.let { hint("smb.netbiosName=$it") }
            n.dnsComputer?.let { hint("smb.dnsName=$it") }
            (n.netbiosDomain ?: n.dnsDomain)?.let { hint("smb.domain=$it") }
            n.osBuild?.let { hint("smb.osBuild=$it") }
        }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.smbSummary(dialect, neg.signingRequired, anonymous.allowed, guest != Guest.REFUSED))
    }

    private suspend fun negotiate(conn: SmbTransport): Smb2.NegotiateResponse? =
        conn.exchange(Smb2.negotiate(0, randomBytes(GUID_BYTES)))?.let(Smb2::parseNegotiate)

    /** MessageId 1: SESSION_SETUP with NTLM NEGOTIATE; the reply carries the CHALLENGE. Returns the reply and the challenge. */
    private suspend fun challengeStep(conn: SmbTransport, neg: Smb2.NegotiateResponse, anonymous: Boolean): Pair<Smb2.SessionSetupResponse, Ntlm.Challenge>? {
        val reply = conn.exchange(Smb2.sessionSetup(1, 0, Smb2.creditCharge(neg.dialect), Spnego.negTokenInit(Ntlm.negotiate(Ntlm.negotiateFlags(anonymous)))))
            ?.let(Smb2::parseSessionSetup) ?: return null
        if (reply.status != Smb2.STATUS_MORE_PROCESSING_REQUIRED) return null
        val challenge = Spnego.extractNtlm(reply.securityBlob)?.let(Ntlm::parseChallenge) ?: return null
        return reply to challenge
    }

    /** The anonymous leg (MS-NLMP 3.2.5.1.2): empty user and domain, an empty NT response and one zero LM byte. */
    private suspend fun sessionLeg(conn: SmbTransport, neg: Smb2.NegotiateResponse, anonymousLeg: Boolean): Leg {
        val (first, challenge) = challengeStep(conn, neg, anonymousLeg) ?: return Leg(false, null)
        val auth = Ntlm.authenticate("", "", byteArrayOf(0), ByteArray(0), Ntlm.negotiateFlags(anonymous = true))
        val reply = conn.exchange(Smb2.sessionSetup(2, first.sessionId, Smb2.creditCharge(neg.dialect), Spnego.negTokenResp(auth)))?.let(Smb2::parseSessionSetup)
        return Leg(reply?.status == Smb2.STATUS_SUCCESS, challenge.names)
    }

    /** The guest leg: user "guest", empty domain and password, NTLMv2 with the server's timestamp when it sent one, no MIC. */
    private suspend fun guestLeg(conn: SmbTransport, neg: Smb2.NegotiateResponse): Guest {
        val (first, challenge) = challengeStep(conn, neg, anonymous = false) ?: return Guest.REFUSED
        val ntowf = Ntlm.ntowfv2(GUEST_USER, "", "")
        val clientChallenge = randomBytes(CLIENT_CHALLENGE_BYTES)
        val blob = Ntlm.blob(challenge.names.timestamp ?: Ntlm.filetime(clock()), clientChallenge, challenge.targetInfo)
        val auth = Ntlm.authenticate(GUEST_USER, "", Ntlm.lmV2(ntowf, challenge.serverChallenge, clientChallenge),
            Ntlm.ntResponseV2(ntowf, challenge.serverChallenge, blob), Ntlm.negotiateFlags(anonymous = false))
        val reply = conn.exchange(Smb2.sessionSetup(2, first.sessionId, Smb2.creditCharge(neg.dialect), Spnego.negTokenResp(auth)))
            ?.let(Smb2::parseSessionSetup) ?: return Guest.REFUSED
        if (reply.status != Smb2.STATUS_SUCCESS) return Guest.REFUSED
        return if (reply.sessionFlags and Smb2.SESSION_FLAG_IS_GUEST != 0) Guest.MAPPED else Guest.ALLOWED
    }

    companion object {
        const val SMB_TIMEOUT_MS = 3000
        private const val GUID_BYTES = 16; private const val CLIENT_CHALLENGE_BYTES = 8; private const val GUEST_USER = "guest"
    }
}
```
(The early `return`s inside `open`'s `try` still run `finally`, so the first connection is always closed, including on the excused retry.)

`AppContainer.kt`: in `toolMap()` add (imports `com.northwalessolutions.netprobe.engine.common.Probe`, `com.northwalessolutions.netprobe.engine.tools.smb.*`):
```kotlin
        put(ToolId.SMB_SESSION, SmbSessionTool(connect = { t ->
            // Probe.connect binds through currentBinder(), so off-subnet hosts are reached over Wi-Fi exactly as port scans are.
            (Probe.connect(t.ip, t.port, SmbSessionTool.SMB_TIMEOUT_MS, keepOpen = true) as? Probe.Outcome.Connected)?.socket
                ?.let { TcpSmbTransport(it, SmbSessionTool.SMB_TIMEOUT_MS) }
        }))
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.tools.smb.*" --tests "com.northwalessolutions.netprobe.engine.tools.BindingPropagationTest" --console=plain 2>&1 | tail -30
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `SmbBytesTest` (5), `SmbFramingTest` (4), `SmbSessionTest` (10) and `BindingPropagationTest` (2) pass, with Task 14's `Md4Test`, `NtlmTest` and `SpnegoBytesTest` still green; `BUILD SUCCESSFUL`. The reviewer confirms the first binding test fails when `HttpClient.request` opens its socket with a plain `Socket()` instead of `Probe.connect`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/Smb2.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbTransport.kt \
  app/src/main/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbSessionTool.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbBytesTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbFixtures.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbFramingTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/smb/SmbSessionTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/engine/tools/BindingPropagationTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "SMB part 2: SMB2 negotiate and session setup, framing, the session probe and binding propagation"
```

---

### Task 16: The pure Tools UI model, the export text, the options store, and the Detail view-model wiring

**Files:**
- Create: `ui/tools/ToolRunOptions.kt`, `ui/tools/ToolExport.kt`, `ui/tools/ToolsUiState.kt`, `ui/tools/DirBruteOptionsStore.kt` (all under `app/src/main/java/com/northwalessolutions/netprobe/`)
- Modify: `ui/detail/DetailViewModel.kt` (tool state, starts, stop, sheets, the Detail port-scan claim, the Remove guard), `actions/AndroidActionRunner.kt` (`share`), `AppContainer.kt` (`dirBruteOptions`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolRunOptionsTest.kt`, `ToolExportTest.kt`, `ToolsUiModelTest.kt`, `ToolResultsModelTest.kt`, `DirBruteOptionsStoreTest.kt`

**Interfaces:**
- Consumes: `ToolCopy` (Task 3), `ToolApplicability`, `ToolId` (Task 3), `ToolRunner` / `ToolRunner.Active` (Task 6), `HostBusyRegistry`, `AppMessages` (Task 4), `DirBruteOptions` / `DirBruteParse` (Task 10), `WordlistMeta` / `WordlistStore` (Task 9), `ToolRunEntity` / `ToolFindingEntity` (Task 1), 3A's `RelativeTime`, `RowRules.parseIpv4`, `toServiceInfo`, `DetailCopy`, `ScanController.NO_NETWORK`, the Detail `message` flow, `startScan`, `removeDevice`.
- Produces:
```kotlin
@Serializable data class ToolRunOptions(...) { val isDirBrute: Boolean; fun optionsLine(): String?; companion object { fun parse(json: String): ToolRunOptions } }
object FindingExtra { fun decode(json: String?): Map<String, String> }
data class ExportFinding(val value: String, val detail: String?, val url: String?)
data class ExportRun(val toolName: String, val target: String, val nameSent: String?, val startedAt: Long, val finishedAt: Long?, val statusLabel: String,
                     val summary: String, val optionsLine: String?, val findings: List<ExportFinding>, val zone: TimeZone)
object ToolExport { fun target(toolId: ToolId, ip: String, port: Int, tls: Boolean): String; fun subject(toolName: String, ip: String, port: Int): String
    fun text(e: ExportRun): String; fun of(run: ToolRunEntity, ip: String, findings: List<ToolFindingEntity>, zone: TimeZone = TimeZone.getDefault()): ExportRun }
data class ToolChip(val toolId: ToolId, val port: Int, val label: String, val description: String)
data class ToolBlock(val toolId: ToolId, val name: String, val hasRun: Boolean, val history: String, val chips: List<ToolChip>)
data class ToolsUiState(val available: Int, val headerHistory: String, val blocks: List<ToolBlock>)
data class ToolRunBar(val label: String, val progress: String, val fraction: Float?)
data class FindingRow(val id: Long, val value: String, val detail: String?, val url: String?)
data class ToolResultsState(val runId: Long, val title: String, val statusLine: String, val summary: String, val index: Int, val count: Int,
                            val findings: List<FindingRow>, val active: Boolean)
data class DirBruteSheetState(val port: Int, val targetUrl: String, val lists: List<WordlistMeta>, val initial: DirBruteOptions, val busyReason: String?)
data class DetailToolsState(val tools: ToolsUiState? = null, val bar: ToolRunBar? = null, val busy: HostBusyRegistry.Work? = null,
                            val results: ToolResultsState? = null, val dirBrute: DirBruteSheetState? = null, val appMessage: AppMessage? = null)
object ToolsUiModel { fun of(host: HostEntity, ports: List<Pair<Int, ServiceInfo>>, runs: List<ToolRunEntity>, now: Long, formatDate: (Long) -> String = RelativeTime::defaultDate): ToolsUiState?
    fun bar(a: ToolRunner.Active): ToolRunBar; fun busyMessage(work: HostBusyRegistry.Work): String }
object ToolResultsModel { fun of(run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String, findings: List<ToolFindingEntity>, active: ToolRunner.Active?, now: Long,
    formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState }
class DirBruteOptionsStore(read: () -> String?, write: (String) -> Unit) { fun load(): DirBruteOptions; fun save(o: DirBruteOptions); companion object { const val KEY = "tool.dirBrute.options" } }
DetailViewModel.toolState: StateFlow<DetailToolsState>; runTool(toolId, port); startDirBrute(o: DirBruteOptions); closeDirBrute(); openResults(toolId); olderRun(); newerRun();
    closeResults(); stopTool(); shareRun(); copyFinding(row: FindingRow); openFinding(row: FindingRow); dismissAppMessage(id: Long)
AndroidActionRunner.share(subject: String, text: String): Boolean
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolRunOptionsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolOptions
import com.northwalessolutions.netprobe.engine.tools.ToolOptionsJson
import com.northwalessolutions.netprobe.engine.tools.ToolTarget
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirMethod
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 7.1: optionsJson read back with defaults, the Options line of 7.5, malformed JSON tolerated. */
class ToolRunOptionsTest {
    private val t = ToolTarget(7, InetAddress.getByName("10.0.3.10") as Inet4Address, 5000, false, null)
    @Test fun `a brute force run reads back into the options line`() {
        val json = ToolOptionsJson.encode(t, ToolOptions.DirBrute(DirBruteOptions(), "common-short", 312))
        val o = ToolRunOptions.parse(json)
        assertTrue(o.isDirBrute)
        assertEquals("wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on", o.optionsLine())
    }
    @Test fun `get only chosen, extensions, filter off`() {
        val json = ToolOptionsJson.encode(t, ToolOptions.DirBrute(DirBruteOptions(extensions = listOf("php", "html"), method = DirMethod.GET_ONLY, baseline = false), "api-paths", 137))
        assertEquals("wordlist api-paths (137 entries), extensions php html, GET only, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter off",
            ToolRunOptions.parse(json).optionsLine())
    }
    @Test fun `a calibration switch reads as GET only because HEAD and GET disagree`() {
        val o = ToolRunOptions(wordlistId = "builtin:common-short", wordlistName = "common-short", wordlistEntries = 300, method = "HEAD_THEN_GET", methodUsed = "GET_ONLY",
            concurrency = 8, timeoutS = 3, statusCodes = listOf(200), baseline = true)
        assertTrue(o.optionsLine()!!.contains(", GET only (HEAD and GET disagree), "))
    }
    @Test fun `non brute runs have no options line and malformed json gives defaults`() {
        assertNull(ToolRunOptions.parse(ToolOptionsJson.encode(t.copy(serverName = "nas.lan"), ToolOptions.None)).optionsLine())
        assertEquals("nas.lan", ToolRunOptions.parse(ToolOptionsJson.encode(t.copy(serverName = "nas.lan"), ToolOptions.None)).serverName)
        assertEquals(ToolRunOptions(), ToolRunOptions.parse("{ not json"))
        assertEquals(mapOf("url" to "http://a/", "status" to "200"), FindingExtra.decode("{\"url\":\"http://a/\",\"status\":\"200\"}"))
        assertEquals(emptyMap<String, String>(), FindingExtra.decode(null)); assertEquals(emptyMap<String, String>(), FindingExtra.decode("x"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolExportTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** spec 7.5: the share text for each tool, Name sent, not finished, the Options line, no hints. */
class ToolExportTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val opts = "{\"tls\":false,\"serverName\":null,\"wordlistId\":\"builtin:common-short\",\"wordlistName\":\"common-short\",\"wordlistEntries\":312," +
        "\"extensions\":[],\"method\":\"HEAD_THEN_GET\",\"methodUsed\":\"HEAD_THEN_GET\",\"concurrency\":8,\"timeoutS\":3,\"statusCodes\":[200,204,301,302,307,401,403],\"baseline\":true}"
    private fun run(tool: ToolId, port: Int, json: String, finished: Long? = 1_792_505_042_000L, status: ToolRunStatus = ToolRunStatus.DONE, summary: String = "7 found in 4614 paths") =
        ToolRunEntity(id = 1, hostId = 7, toolId = tool.key, port = port, startedAt = 1_792_504_991_000L, finishedAt = finished, status = status, optionsJson = json, summary = summary)

    @Test fun `the brute force export, exactly`() {
        val findings = listOf(ToolFindingEntity(1, 1, "PATH", "/admin", "200  1.2 KB", "{\"url\":\"http://10.0.3.10:5000/admin\",\"status\":\"200\"}"),
            ToolFindingEntity(2, 1, "PATH", "/old", null, null))
        val text = ToolExport.text(ToolExport.of(run(ToolId.DIR_BRUTEFORCE, 5000, opts), "10.0.3.10", findings, utc))
        assertEquals(listOf(
            "NetProbe Directory brute-force",
            "Target: http://10.0.3.10:5000/",
            "Started: 2026-10-20 14:03:11",
            "Finished: 2026-10-20 14:04:02",
            "Status: Done",
            "Summary: 7 found in 4614 paths",
            "Options: wordlist common-short (312 entries), extensions none, HEAD then GET, concurrency 8, timeout 3 s, codes 200 204 301 302 307 401 403, not-found filter on",
            "",
            "/admin\t200  1.2 KB\thttp://10.0.3.10:5000/admin",
            "/old\t\t"), text.split("\n"))
    }
    @Test fun `a tls export names the name sent and has no options line, an unfinished run says so`() {
        val text = ToolExport.text(ToolExport.of(run(ToolId.TLS_CERTIFICATE, 443, "{\"tls\":true,\"serverName\":\"chnas.local\"}", finished = null, status = ToolRunStatus.RUNNING, summary = ""),
            "10.0.3.10", emptyList(), utc))
        assertTrue(text.contains("\nTarget: 10.0.3.10:443\n")); assertTrue(text.contains("\nName sent: chnas.local\n"))
        assertTrue(text.contains("\nFinished: not finished\n")); assertTrue(text.contains("\nStatus: Running\n")); assertFalse(text.contains("Options:"))
    }
    @Test fun `targets for every tool and the subject`() {
        assertEquals("https://10.0.3.10:5001/", ToolExport.target(ToolId.HTTP_FINGERPRINT, "10.0.3.10", 5001, true))
        assertEquals("smb://10.0.3.10/", ToolExport.target(ToolId.SMB_SESSION, "10.0.3.10", 445, false))
        assertEquals("udp://10.0.3.10:161", ToolExport.target(ToolId.SNMP_WALK, "10.0.3.10", 161, false))
        assertEquals("NetProbe TLS certificate 10.0.3.10:443", ToolExport.subject(ToolCopy.TLS_CERTIFICATE, "10.0.3.10", 443))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiModelTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import org.junit.Assert.*
import org.junit.Test

/** spec 8.1, 8.2: the Tools block assembly, the history lines, the chips, the pinned-bar label and progress. */
class ToolsUiModelTest {
    private val now = 10_000_000L
    private fun host(snmp: String? = null) = HostEntity(id = 7, networkId = 1, ip = "10.0.3.59", mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null,
        upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.NAS, customName = null, identVendor = null, identModel = null,
        identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = snmp, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED,
        note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    private fun svc(name: String) = ServiceInfo(name, confidence = Confidence.CONFIRMED)
    private val ports = listOf(22 to svc("SSH"), 5001 to svc("HTTPS"), 5000 to svc("HTTP"), 445 to svc("SMB"))
    private fun run(tool: ToolId, port: Int, at: Long, summary: String, status: ToolRunStatus = ToolRunStatus.DONE) =
        ToolRunEntity(id = at, hostId = 7, toolId = tool.key, port = port, startedAt = at, finishedAt = at + 1, status = status, optionsJson = "{}", summary = summary)

    @Test fun `blocks in tool order with chips in port order, snmp only with a discovery answer`() {
        val s = ToolsUiModel.of(host(), ports, emptyList(), now)!!
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT, ToolId.TLS_CERTIFICATE, ToolId.SMB_SESSION), s.blocks.map { it.toolId })
        assertEquals(4, s.available); assertEquals("No tools run yet", s.headerHistory)
        assertEquals(listOf("5000", "5001"), s.blocks[0].chips.map { it.label })
        assertEquals("Run Directory brute-force on port 5000", s.blocks[0].chips[0].description)
        assertEquals("Not run yet", s.blocks[0].history); assertFalse(s.blocks[0].hasRun)
        val snmp = ToolsUiModel.of(host(snmp = "Linux"), ports, emptyList(), now)!!.blocks.last()
        assertEquals(ToolId.SNMP_WALK, snmp.toolId); assertEquals(listOf("UDP 161"), snmp.chips.map { it.label })
        assertEquals("Run SNMP walk on port UDP 161", snmp.chips.single().description)
    }
    @Test fun `no applicable tool means no block`() {
        assertNull(ToolsUiModel.of(host(), listOf(22 to svc("SSH")), emptyList(), now))
    }
    @Test fun `history lines read the newest run`() {
        val runs = listOf(run(ToolId.DIR_BRUTEFORCE, 5000, now - 7_200_000, "7 found in 4614 paths"), run(ToolId.DIR_BRUTEFORCE, 5001, now - 9_000_000, "older"))
        val s = ToolsUiModel.of(host(), ports, runs, now)!!
        assertEquals("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths", s.headerHistory)
        assertEquals("Last run 2 hours ago on 5000: 7 found in 4614 paths", s.blocks[0].history); assertTrue(s.blocks[0].hasRun)
    }
    @Test fun `a running row with no summary yet reads its status label`() {
        val s = ToolsUiModel.of(host(), ports, listOf(run(ToolId.HTTP_FINGERPRINT, 5000, now, "", ToolRunStatus.RUNNING)), now)!!
        assertEquals("Last run just now on 5000: Running", s.blocks[1].history)
    }
    @Test fun `the pinned bar label, progress and fraction`() {
        fun active(done: Int, total: Int?, found: Int, ms: Long, tool: ToolId = ToolId.DIR_BRUTEFORCE, port: Int = 5000) =
            ToolRunner.Active(1, 7, tool, port, ms, done, total, found, 0, false)
        assertEquals(ToolRunBar("Directory brute-force on 5000", "starting", null), ToolsUiModel.bar(active(0, null, 0, 0)))
        assertEquals(ToolRunBar("Directory brute-force on 5000", "1234 of 4614, 7 found  12 s", 1234f / 4614), ToolsUiModel.bar(active(1234, 4614, 7, 12_400)))
        assertEquals(ToolRunBar("SNMP walk on UDP 161", "12 done, 3 found  2 s", null), ToolsUiModel.bar(active(12, null, 3, 2_000, ToolId.SNMP_WALK, 161)))
        assertEquals("A tool is already running on this device", ToolsUiModel.busyMessage(HostBusyRegistry.Work.TOOL))
        assertEquals("Wait for the port scan to finish", ToolsUiModel.busyMessage(HostBusyRegistry.Work.PORT_SCAN))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsModelTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import org.junit.Assert.*
import org.junit.Test

/** spec 8.5: the results header, the run switcher position, rows with their url, the active state. */
class ToolResultsModelTest {
    private val now = 10_000_000L
    private fun run(id: Long, at: Long, json: String = "{\"tls\":false,\"serverName\":null}", status: ToolRunStatus = ToolRunStatus.DONE) =
        ToolRunEntity(id = id, hostId = 7, toolId = ToolId.HTTP_FINGERPRINT.key, port = 5000, startedAt = at, finishedAt = at + 1, status = status, optionsJson = json, summary = "Pi-hole, 6 findings")
    @Test fun `header, position and rows`() {
        val runs = listOf(run(3, now - 60_000), run(2, now - 7_200_000, "{\"tls\":false,\"serverName\":\"pi.hole\"}"), run(1, now - 9_000_000))
        val f = listOf(ToolFindingEntity(5, 2, "STATUS", "200 OK", "GET /", "{\"url\":\"http://10.0.3.5:5000/\"}"), ToolFindingEntity(6, 2, "TITLE", "Pi-hole", "Page title", null))
        val s = ToolResultsModel.of(runs[1], runs, "10.0.3.5", f, null, now)
        assertEquals("HTTP fingerprint  10.0.3.5:5000", s.title); assertEquals("Done, 2 hours ago, as pi.hole", s.statusLine); assertEquals("Pi-hole, 6 findings", s.summary)
        assertEquals(2, s.index); assertEquals(3, s.count)
        assertEquals(listOf(FindingRow(5, "200 OK", "GET /", "http://10.0.3.5:5000/"), FindingRow(6, "Pi-hole", "Page title", null)), s.findings)
        assertFalse(s.active)
    }
    @Test fun `an active run shows Running and its live progress`() {
        val r = run(3, now, status = ToolRunStatus.RUNNING)
        val s = ToolResultsModel.of(r, listOf(r), "10.0.3.5", emptyList(), ToolRunner.Active(3, 7, ToolId.HTTP_FINGERPRINT, 5000, 3_000, 2, 5, 1, 0, false), now)
        assertEquals("Running, 2 of 5, 1 found  3 s", s.statusLine); assertTrue(s.active)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/tools/DirBruteOptionsStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirMethod
import org.junit.Assert.*
import org.junit.Test

/** spec 5.1: saved globally as JSON under tool.dirBrute.options; invalid or absent JSON loads the defaults; values are coerced. */
class DirBruteOptionsStoreTest {
    private var stored: String? = null
    private val store = DirBruteOptionsStore({ stored }, { stored = it })
    @Test fun `absent and invalid json load defaults`() {
        assertEquals(DirBruteOptions(), store.load()); stored = "{ nope"; assertEquals(DirBruteOptions(), store.load())
    }
    @Test fun `a saved option set round trips and out of range values are coerced on load`() {
        val o = DirBruteOptions(wordlistId = "user:abc", extensions = listOf("php"), method = DirMethod.GET_ONLY, concurrency = 4, timeoutS = 5, statusCodes = listOf(200), baseline = false)
        store.save(o); assertEquals(o, store.load())
        stored = stored!!.replace("\"concurrency\":4", "\"concurrency\":99"); assertEquals(16, store.load().concurrency)
        assertEquals("tool.dirBrute.options", DirBruteOptionsStore.KEY)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.tools.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ToolRunOptions'`, `'ToolExport'`, `'ToolsUiModel'`, `'ToolResultsModel'`, `'DirBruteOptionsStore'`.

- [ ] **Step 3: Implement**

`ui/tools/ToolRunOptions.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** tool_runs.optionsJson read back (spec 7.1); every field defaults, and a malformed value decodes to defaults. */
@Serializable
data class ToolRunOptions(
    val tls: Boolean = false, val serverName: String? = null,
    val wordlistId: String? = null, val wordlistName: String? = null, val wordlistEntries: Int? = null,
    val extensions: List<String> = emptyList(), val method: String? = null, val methodUsed: String? = null,
    val concurrency: Int? = null, val timeoutS: Int? = null, val statusCodes: List<Int> = emptyList(), val baseline: Boolean? = null,
) {
    val isDirBrute: Boolean get() = wordlistId != null
    /** The export Options line (spec 7.5), brute-force runs only. */
    fun optionsLine(): String? {
        val id = wordlistId ?: return null
        val name = wordlistName?.takeIf { it.isNotEmpty() } ?: id.substringAfter(':')
        val usedGetOnly = methodUsed == GET_ONLY
        return ToolCopy.optionsLine(name, wordlistEntries ?: 0, extensions, getOnly = usedGetOnly || method == GET_ONLY,
            switchedToGetOnly = usedGetOnly && method != GET_ONLY, concurrency = concurrency ?: 8, timeoutS = timeoutS ?: 3, codes = statusCodes, filter = baseline ?: true)
    }
    companion object {
        private const val GET_ONLY = "GET_ONLY"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun parse(text: String): ToolRunOptions = runCatching { json.decodeFromString<ToolRunOptions>(text) }.getOrElse { ToolRunOptions() }
    }
}

/** tool_findings.extra read back (spec 7.1): string fields only; anything malformed is empty. */
object FindingExtra {
    fun decode(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val obj: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
    }
}
```

`ui/tools/ToolExport.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.tools.ToolId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExportFinding(val value: String, val detail: String?, val url: String?)
data class ExportRun(val toolName: String, val target: String, val nameSent: String?, val startedAt: Long, val finishedAt: Long?, val statusLabel: String,
                     val summary: String, val optionsLine: String?, val findings: List<ExportFinding>, val zone: TimeZone)

/** The plain-text share of one run (spec 7.5). Hints never reach here: the DAO's findings query excludes them. */
object ToolExport {
    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
    fun target(toolId: ToolId, ip: String, port: Int, tls: Boolean): String = when (toolId) {
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> "${if (tls) "https" else "http"}://$ip:$port/"
        ToolId.TLS_CERTIFICATE -> "$ip:$port"
        ToolId.SMB_SESSION -> "smb://$ip/"
        ToolId.SNMP_WALK -> "udp://$ip:161"
    }
    fun subject(toolName: String, ip: String, port: Int): String = ToolCopy.exportSubject(toolName, ip, port)
    fun of(run: ToolRunEntity, ip: String, findings: List<ToolFindingEntity>, zone: TimeZone = TimeZone.getDefault()): ExportRun {
        val tool = ToolId.byKey(run.toolId) ?: ToolId.DIR_BRUTEFORCE
        val o = ToolRunOptions.parse(run.optionsJson)
        return ExportRun(ToolCopy.toolName(tool), target(tool, ip, run.port, o.tls), o.serverName, run.startedAt, run.finishedAt, ToolCopy.statusLabel(run.status),
            run.summary, o.optionsLine(), findings.map { ExportFinding(it.value, it.detail, FindingExtra.decode(it.extra)["url"]) }, zone)
    }
    fun text(e: ExportRun): String {
        val fmt = SimpleDateFormat(TIME_PATTERN, Locale.ROOT).apply { timeZone = e.zone }
        val lines = buildList {
            add(ToolCopy.exportTitle(e.toolName))
            add(ToolCopy.exportTarget(e.target))
            e.nameSent?.let { add(ToolCopy.exportNameSent(it)) }
            add(ToolCopy.exportStarted(fmt.format(Date(e.startedAt))))
            add(ToolCopy.exportFinished(e.finishedAt?.let { fmt.format(Date(it)) }))
            add(ToolCopy.exportStatus(e.statusLabel))
            add(ToolCopy.exportSummary(e.summary))
            e.optionsLine?.let { add(ToolCopy.exportOptions(it)) }
            add("")
            e.findings.forEach { add(listOf(it.value, it.detail.orEmpty(), it.url.orEmpty()).joinToString("\t")) }
        }
        return lines.joinToString("\n")
    }
}
```
(The test instants are UTC: 1_792_504_991_000 ms is 2026-10-20 14:03:11 and 1_792_505_042_000 ms is 14:04:02, checked with a calendar library while writing this plan.)

`ui/tools/ToolsUiState.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.ToolFindingEntity
import com.northwalessolutions.netprobe.data.ToolRunEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.AppMessage
import com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry
import com.northwalessolutions.netprobe.engine.tools.ToolApplicability
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.ToolRunner
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.wordlists.WordlistMeta

data class ToolChip(val toolId: ToolId, val port: Int, val label: String, val description: String)
data class ToolBlock(val toolId: ToolId, val name: String, val hasRun: Boolean, val history: String, val chips: List<ToolChip>)
data class ToolsUiState(val available: Int, val headerHistory: String, val blocks: List<ToolBlock>)
/** The pinned bar during a run (spec 8.2); [fraction] null draws an indeterminate line. */
data class ToolRunBar(val label: String, val progress: String, val fraction: Float?)
data class FindingRow(val id: Long, val value: String, val detail: String?, val url: String?)
data class ToolResultsState(val runId: Long, val title: String, val statusLine: String, val summary: String, val index: Int, val count: Int,
                            val findings: List<FindingRow>, val active: Boolean)
data class DirBruteSheetState(val port: Int, val targetUrl: String, val lists: List<WordlistMeta>, val initial: DirBruteOptions, val busyReason: String?)
data class DetailToolsState(val tools: ToolsUiState? = null, val bar: ToolRunBar? = null, val busy: HostBusyRegistry.Work? = null,
                            val results: ToolResultsState? = null, val dirBrute: DirBruteSheetState? = null, val appMessage: AppMessage? = null)

object ToolsUiModel {
    private const val MS_PER_S = 1000L
    /** The Tools block (spec 8.1), or null when no tool applies to the host. [runs] are newest first (the DAO order). */
    fun of(host: HostEntity, ports: List<Pair<Int, ServiceInfo>>, runs: List<ToolRunEntity>, now: Long, formatDate: (Long) -> String = RelativeTime::defaultDate): ToolsUiState? {
        val blocks = ToolId.entries.mapNotNull { id ->
            val snmp = id == ToolId.SNMP_WALK
            val targets = if (snmp) listOfNotNull(ToolApplicability.SNMP_PORT.takeIf { ToolApplicability.snmp(host) })
                          else ports.filter { (p, s) -> ToolApplicability.appliesTo(id, p, s) }.map { it.first }.sorted()
            if (targets.isEmpty()) return@mapNotNull null
            val name = ToolCopy.toolName(id)
            val newest = runs.firstOrNull { it.toolId == id.key }
            ToolBlock(id, name, newest != null,
                newest?.let { ToolCopy.blockHistory(RelativeTime.of(it.startedAt, now, formatDate), ToolCopy.portLabel(it.port, snmp), summaryOf(it)) } ?: ToolCopy.NOT_RUN_YET,
                targets.map { p -> val label = ToolCopy.portLabel(p, snmp); ToolChip(id, p, label, ToolCopy.chipDescription(name, label)) })
        }
        if (blocks.isEmpty()) return null
        val newest = runs.firstOrNull { ToolId.byKey(it.toolId) != null }
        val header = newest?.let { r ->
            val id = ToolId.byKey(r.toolId)!!
            ToolCopy.rowHistory(RelativeTime.of(r.startedAt, now, formatDate), ToolCopy.toolName(id), ToolCopy.portLabel(r.port, id == ToolId.SNMP_WALK), summaryOf(r))
        } ?: ToolCopy.NO_TOOLS_RUN
        return ToolsUiState(blocks.size, header, blocks)
    }
    fun summaryOf(r: ToolRunEntity): String = r.summary.ifEmpty { ToolCopy.statusLabel(r.status) }
    fun bar(a: ToolRunner.Active): ToolRunBar {
        val snmp = a.toolId == ToolId.SNMP_WALK
        val label = ToolCopy.pinnedLabel(ToolCopy.toolName(a.toolId), ToolCopy.portLabel(a.port, snmp))
        val started = a.total != null || a.done > 0 || a.found > 0
        val progress = if (started) ToolCopy.progress(a.done, a.total, a.found, a.errors, a.activeMs / MS_PER_S) else ToolCopy.STARTING
        val fraction = a.total?.let { t -> if (t <= 0) 0f else (a.done.toFloat() / t).coerceIn(0f, 1f) }
        return ToolRunBar(label, progress, fraction)
    }
    fun busyMessage(work: HostBusyRegistry.Work): String = when (work) {
        HostBusyRegistry.Work.TOOL -> ToolCopy.BUSY_TOOL
        HostBusyRegistry.Work.PORT_SCAN -> ToolCopy.BUSY_PORT_SCAN
    }
}

object ToolResultsModel {
    /** The results sheet (spec 8.5) for [run], positioned in [sameTool] (newest first, this host and tool). */
    fun of(run: ToolRunEntity, sameTool: List<ToolRunEntity>, ip: String, findings: List<ToolFindingEntity>, active: ToolRunner.Active?, now: Long,
           formatDate: (Long) -> String = RelativeTime::defaultDate): ToolResultsState {
        val tool = ToolId.byKey(run.toolId) ?: ToolId.DIR_BRUTEFORCE
        val live = active?.takeIf { it.runId == run.id }
        val status = if (live != null) ToolCopy.resultsRunning(ToolsUiModel.bar(live).progress)
                     else ToolCopy.resultsStatus(ToolCopy.statusLabel(run.status), RelativeTime.of(run.startedAt, now, formatDate), ToolRunOptions.parse(run.optionsJson).serverName)
        val index = sameTool.indexOfFirst { it.id == run.id }.let { if (it < 0) 1 else it + 1 }
        return ToolResultsState(run.id, ToolCopy.resultsTitle(ToolCopy.toolName(tool), ip, run.port), status, run.summary, index, maxOf(sameTool.size, 1),
            findings.map { FindingRow(it.id, it.value, it.detail, FindingExtra.decode(it.extra)["url"]) }, live != null)
    }
}
```

`ui/tools/DirBruteOptionsStore.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import kotlinx.serialization.json.Json

/** Last-used brute-force options, saved globally when Run is tapped (spec 5.1). Invalid stored JSON loads the defaults. */
class DirBruteOptionsStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(): DirBruteOptions = read()?.let { runCatching { json.decodeFromString<DirBruteOptions>(it) }.getOrNull() }?.coerced() ?: DirBruteOptions()
    fun save(o: DirBruteOptions) { write(json.encodeToString(DirBruteOptions.serializer(), o.coerced())) }
    companion object { const val KEY = "tool.dirBrute.options" }
}
```

`AppContainer.kt`: add (import `com.northwalessolutions.netprobe.ui.tools.DirBruteOptionsStore`):
```kotlin
    val dirBruteOptions by lazy {
        val prefs = context.getSharedPreferences("netprobe", Context.MODE_PRIVATE)
        DirBruteOptionsStore({ prefs.getString(DirBruteOptionsStore.KEY, null) }, { prefs.edit().putString(DirBruteOptionsStore.KEY, it).apply() })
    }
```

`actions/AndroidActionRunner.kt`: add (imports `com.northwalessolutions.netprobe.ui.tools.ToolCopy`):
```kotlin
    /** Plain-text share through the system sheet (spec 7.5); false when nothing can receive it. */
    fun share(subject: String, text: String): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, ToolCopy.SHARE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false }
```

`ui/detail/DetailViewModel.kt`: add the imports `com.northwalessolutions.netprobe.engine.tools.*`, `com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions`, `com.northwalessolutions.netprobe.ui.tools.*`, `com.northwalessolutions.netprobe.ui.home.RowRules`, `android.os.Build`, `kotlinx.coroutines.flow.flatMapLatest`, `kotlinx.coroutines.flow.flowOf`, then add inside the class:
```kotlin
    private val resultsRunId = MutableStateFlow<Long?>(null)
    private val dirBrutePort = MutableStateFlow<Int?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val resultsSource: Flow<Pair<ToolRunEntity, List<ToolFindingEntity>>?> = resultsRunId.flatMapLatest { id ->
        if (id == null) flowOf(null) else combine(c.toolRepo.run(id), c.toolRepo.findings(id)) { r, f -> r?.let { it to f } }
    }

    /** Tools block, pinned bar, busy state, both sheets and the app message for this host (spec 8.1 to 8.5, 4.3). */
    val toolState: StateFlow<DetailToolsState> = combine(listOf(c.hosts.host(hostId), c.ports.portsForHost(hostId), c.toolRepo.runsForHost(hostId),
        c.toolRunner.active, c.hostBusy.busy, resultsSource, dirBrutePort, c.wordlistStore.lists, c.appMessages.current)) { arr ->
        @Suppress("UNCHECKED_CAST") val ports = arr[1] as List<PortEntity>
        @Suppress("UNCHECKED_CAST") val runs = arr[2] as List<ToolRunEntity>
        @Suppress("UNCHECKED_CAST") val active = (arr[3] as Map<Long, ToolRunner.Active>)[hostId]
        @Suppress("UNCHECKED_CAST") val busyMap = arr[4] as Map<Long, HostBusyRegistry.Work>
        @Suppress("UNCHECKED_CAST") val results = arr[5] as Pair<ToolRunEntity, List<ToolFindingEntity>>?
        @Suppress("UNCHECKED_CAST") val lists = arr[7] as List<WordlistMeta>
        val h = arr[0] as HostEntity?; val sheetPort = arr[6] as Int?; val message = arr[8] as AppMessage?
        val now = System.currentTimeMillis()
        val busy = busyMap[hostId]
        DetailToolsState(
            tools = h?.let { ToolsUiModel.of(it, ports.map { p -> p.port to p.toServiceInfo() }, runs, now) },
            bar = active?.let(ToolsUiModel::bar),
            busy = busy,
            results = if (h == null) null else results?.let { (run, findings) ->
                ToolResultsModel.of(run, runs.filter { it.toolId == run.toolId }, h.ip, findings, active, now)
            },
            dirBrute = if (h == null) null else sheetPort?.let { port -> dirBruteSheet(h, port, ports, lists, busy, (arr[3] as Map<*, *>).size) },
            appMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailToolsState())

    init {
        // The results sheet closes when its run disappears (pruned by a new run, or its host removed) (spec 8.5, 11.15).
        viewModelScope.launch { resultsRunId.flatMapLatest { id -> if (id == null) flowOf(Unit) else c.toolRepo.run(id).filter { it == null }.map { } }
            .collect { if (resultsRunId.value != null) resultsRunId.value = null } }
    }

    private fun dirBruteSheet(h: HostEntity, port: Int, ports: List<PortEntity>, lists: List<WordlistMeta>, busy: HostBusyRegistry.Work?, runningHosts: Int): DirBruteSheetState {
        val service = ports.firstOrNull { it.port == port }?.toServiceInfo()
        val tls = service?.let { ToolApplicability.targetTls(ToolId.DIR_BRUTEFORCE, port, it) } ?: false
        val saved = c.dirBruteOptions.load()
        val initial = if (lists.any { it.id == saved.wordlistId }) saved else saved.copy(wordlistId = DirBruteOptions.DEFAULT_WORDLIST)
        val reason = busy?.let(ToolsUiModel::busyMessage) ?: ToolCopy.BUSY_TOO_MANY.takeIf { runningHosts >= ToolRunner.MAX_ACTIVE_RUNS }
        return DirBruteSheetState(port, ToolExport.target(ToolId.DIR_BRUTEFORCE, h.ip, port, tls), lists, initial, reason)
    }

    /** A chip or port-sheet row (spec 8.1, 8.3): brute-force opens its sheet, the others start at once; a held host shows its reason. */
    fun runTool(toolId: ToolId, port: Int) {
        val busy = c.hostBusy.busy.value[hostId]
        if (busy != null) { message.value = ToolsUiModel.busyMessage(busy); return }
        if (toolId == ToolId.DIR_BRUTEFORCE) {
            val saved = c.dirBruteOptions.load()
            if (c.wordlistStore.lists.value.none { it.id == saved.wordlistId }) message.value = ToolCopy.WORDLIST_FALLBACK
            dirBrutePort.value = port
            return
        }
        start(toolId, port, ToolOptions.None)
    }
    /** Run in the options sheet: save the options, close the sheet, start the run (spec 8.4). */
    fun startDirBrute(o: DirBruteOptions) {
        val port = dirBrutePort.value ?: return
        val options = o.coerced()
        c.dirBruteOptions.save(options)
        dirBrutePort.value = null
        val meta = c.wordlistStore.lists.value.firstOrNull { it.id == options.wordlistId }
        start(ToolId.DIR_BRUTEFORCE, port, ToolOptions.DirBrute(options, meta?.name ?: "", meta?.entries ?: 0))
    }
    fun closeDirBrute() { dirBrutePort.value = null }

    private fun start(toolId: ToolId, port: Int, options: ToolOptions) {
        c.appScope.launch {
            val h = c.hosts.byIdOnce(hostId) ?: return@launch
            val ip = RowRules.parseIpv4(h.ip) ?: return@launch
            val service = c.ports.servicesFor(hostId)[port]
            val tls = service?.let { ToolApplicability.targetTls(toolId, port, it) } ?: (toolId == ToolId.TLS_CERTIFICATE)
            // Brute-force always sends the IP; the fingerprint and TLS tools send the known name (spec 4.9).
            val name = if (toolId == ToolId.DIR_BRUTEFORCE) null else ToolApplicability.serverName(h)
            when (c.toolRunner.start(ToolTarget(hostId, ip, port, tls, name), toolId, options)) {
                ToolRunner.Start.HostBusy -> message.value = ToolsUiModel.busyMessage(c.hostBusy.busy.value[hostId] ?: HostBusyRegistry.Work.TOOL)
                ToolRunner.Start.TooMany -> message.value = ToolCopy.BUSY_TOO_MANY
                ToolRunner.Start.NoNetwork -> message.value = ScanController.NO_NETWORK
                is ToolRunner.Start.Started -> Unit
            }
        }
    }
    fun stopTool() { c.toolRunner.stop(hostId) }

    /** "Results" opens the newest run of that tool (spec 8.1); the switcher moves through this tool's runs. */
    fun openResults(toolId: ToolId) {
        viewModelScope.launch { resultsRunId.value = c.toolRepo.runsForHost(hostId).first().firstOrNull { it.toolId == toolId.key }?.id }
    }
    fun olderRun() { stepRun(+1) }
    fun newerRun() { stepRun(-1) }
    private fun stepRun(delta: Int) {
        viewModelScope.launch {
            val current = resultsRunId.value ?: return@launch
            val runs = c.toolRepo.runsForHost(hostId).first()
            val tool = runs.firstOrNull { it.id == current }?.toolId ?: return@launch
            val same = runs.filter { it.toolId == tool }
            same.getOrNull(same.indexOfFirst { it.id == current } + delta)?.let { resultsRunId.value = it.id }
        }
    }
    fun closeResults() { resultsRunId.value = null }
    fun shareRun() {
        viewModelScope.launch {
            val id = resultsRunId.value ?: return@launch
            val run = c.toolRepo.run(id).first() ?: return@launch
            val h = c.hosts.byIdOnce(hostId) ?: return@launch
            val export = ToolExport.of(run, h.ip, c.toolRepo.findingsOnce(id))
            c.actionRunner.share(ToolExport.subject(export.toolName, h.ip, run.port), ToolExport.text(export))
        }
    }
    /** "Copy" copies the URL when there is one, else the value; below API 33 a snackbar confirms (spec 8.5). */
    fun copyFinding(row: FindingRow) {
        c.actionRunner.run(DeviceAction.Copy(ToolCopy.COPY, row.url ?: row.value))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message.value = ToolCopy.COPIED
    }
    fun openFinding(row: FindingRow) { row.url?.let { runAction(DeviceAction.OpenWith(it)) } }
    fun dismissAppMessage(id: Long) { c.appMessages.dismiss(id) }
```
and change `private companion object` to keep `STOP_TIMEOUT_MS` (it already exists there).

Edits to 3A code in the same file:
- `startScan` (3A form `scan.start { e -> c.undo.committedBefore { try { scanHost(h, resolved, e) } catch (_: BindFailedException) { message.value = DetailCopy.SCAN_BIND_FAILED } } }`): replace with
```kotlin
        if (c.hostBusy.busy.value[hostId] == HostBusyRegistry.Work.TOOL) { message.value = ToolCopy.SCAN_REFUSED; return }
        scan.start { e ->
            // One run per host across port scans and tools (spec 4.3): claim before scanning, release whatever happens.
            if (!c.hostBusy.tryClaim(hostId, HostBusyRegistry.Work.PORT_SCAN)) { message.value = ToolCopy.SCAN_REFUSED; return@start }
            try {
                c.undo.committedBefore { try { scanHost(h, resolved, e) } catch (_: BindFailedException) { message.value = DetailCopy.SCAN_BIND_FAILED } }
            } finally { c.hostBusy.release(hostId, HostBusyRegistry.Work.PORT_SCAN) }
        }
```
- `removeDevice`: add as its first line `if (c.hostBusy.busy.value[hostId] == HostBusyRegistry.Work.TOOL) return` (the menu item is also disabled in Task 17, spec 4.3).

Imports also needed in the file: `com.northwalessolutions.netprobe.ui.home.ScanController` (already), `com.northwalessolutions.netprobe.wordlists.WordlistMeta`, `kotlinx.coroutines.flow.filter`, `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.first`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.tools.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ToolRunOptionsTest` (4), `ToolExportTest` (3), `ToolsUiModelTest` (5), `ToolResultsModelTest` (2), `DirBruteOptionsStoreTest` (2) and Task 3's `ToolCopyTableTest` pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolRunOptions.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolExport.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiState.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/DirBruteOptionsStore.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/actions/AndroidActionRunner.kt \
  app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolRunOptionsTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolExportTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolsUiModelTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsModelTest.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/tools/DirBruteOptionsStoreTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Tools UI model, export text, options store and the Detail view-model wiring"
```

---

### Task 17: Device screen part 1: the Tools block, the pinned bar during a run and the port-sheet tool group

Split from the single Device screen task at plan review G16; Task 18 adds the two sheets.

**Files:**
- Create (under `app/src/main/java/com/northwalessolutions/netprobe/ui/tools/`): `ToolControls.kt` (`ActionPill`, `ToolActions`), `ToolsBlock.kt`
- Modify: `ui/Controls.kt` (`ScanPill` gains `stopDescription`), `ui/detail/DetailComponents.kt` (`ScanBar` tool mode, `PortSheet` tool group, `DetailMenu` remove enabled flag), `ui/detail/DetailContent.kt` (the Tools block as the first list item, the app-message snackbar), `ui/detail/DetailScreen.kt` (wiring)
- Test (compile only, run in Task 20): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsFakes.kt`, `ToolsBlockTest.kt`, `ToolsFakesForDetail.kt`, additions to `ui/detail/DetailContentTest.kt`

**Interfaces:**
- Consumes: `DetailToolsState`, `ToolsUiState`, `ToolBlock`, `ToolChip`, `ToolRunBar`, and for the shared fakes `ToolResultsState`, `FindingRow`, `DirBruteSheetState` (Task 16), `ToolCopy`, `ToolApplicability`, `ToolId` (Task 3), `DirBruteOptions` (Task 10), `WordlistMeta` (Task 9), 3A's `ScanPill`, `PinnedSegmented`, `DetailMenu`, `PortSheet`, `ScanBar`, `DetailContent`, `DetailActions`, tokens, `LocalReducedMotion`.
- Produces:
```kotlin
@Composable fun ActionPill(label: String, enabled: Boolean, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier)
data class ToolActions(val onRunTool: (ToolId, Int) -> Unit = { _, _ -> }, val onStartDirBrute: (DirBruteOptions) -> Unit = {}, val onCloseDirBrute: () -> Unit = {},
    val onManageWordlists: () -> Unit = {}, val onOpenResults: (ToolId) -> Unit = {}, val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {},
    val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {}, val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {},
    val onStopTool: () -> Unit = {}, val onDismissAppMessage: (Long) -> Unit = {})
@Composable fun ToolsBlock(state: ToolsUiState, expanded: Boolean, busy: Boolean, onToggle: () -> Unit, onRunChip: (ToolId, Int) -> Unit, onResults: (ToolId) -> Unit)
fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, stopDescription: String? = null)
fun ScanBar(..., toolBar: ToolRunBar? = null, onStopTool: () -> Unit = {})
fun PortSheet(ip: String, p: PortRow, onAction, onVulns, onClose, tools: List<ToolId> = emptyList(), toolsBusy: Boolean = false, onTool: (ToolId) -> Unit = {})
fun DetailMenu(onEdit: () -> Unit, onRemove: (() -> Unit)? = null, removeEnabled: Boolean = true)
fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false, toolState: DetailToolsState = DetailToolsState(), toolActions: ToolActions = ToolActions())
object ToolsFakes   // androidTest: chip, tools, lists, sheet, results; Task 18's sheet tests use it
// tags: toolsHeader, toolChip:<toolId>:<port>, toolResults:<toolId>, sheetTool:<toolId>, toolBarLabel, toolBarProgress
```

- [ ] **Step 0: Re-read the 3A files this task edits (plan review G17)**

3A's motion task (Task 18 of the 3A plan) edits `ui/Controls.kt`, `ui/detail/DetailComponents.kt` and `ui/detail/DetailContent.kt` and may land after this plan was written. Before editing, read the current `ScanPill`, `ScanBar`, `PortSheet`, `DetailMenu` and `DetailContent` on the branch. The edits below are additive (new parameters with defaults, one new list item, new branches inside existing `when`s); apply them to the code as it stands, keep every 3A modifier and animation, and if a signature named here has changed, stop and report it rather than reshaping 3A code.

- [ ] **Step 1: Write the failing Compose tests (compile only)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsFakes.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.wordlists.WordlistMeta

object ToolsFakes {
    fun chip(tool: ToolId, port: Int) = ToolChip(tool, port, "$port", ToolCopy.chipDescription(ToolCopy.toolName(tool), "$port"))
    fun tools(hasRun: Boolean = true) = ToolsUiState(2, "Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths", listOf(
        ToolBlock(ToolId.DIR_BRUTEFORCE, ToolCopy.DIR_BRUTEFORCE, hasRun, "Last run 2 hours ago on 5000: 7 found in 4614 paths", listOf(chip(ToolId.DIR_BRUTEFORCE, 5000), chip(ToolId.DIR_BRUTEFORCE, 5001))),
        ToolBlock(ToolId.HTTP_FINGERPRINT, ToolCopy.HTTP_FINGERPRINT, false, ToolCopy.NOT_RUN_YET, listOf(chip(ToolId.HTTP_FINGERPRINT, 5000)))))
    val lists = listOf(WordlistMeta("builtin:common-short", "common-short", 298, null, true), WordlistMeta("builtin:admin-panels", "admin-panels", 153, null, true),
        WordlistMeta("builtin:api-paths", "api-paths", 137, null, true))
    fun sheet(busyReason: String? = null) = DirBruteSheetState(5000, "http://10.0.3.10:5000/", lists, DirBruteOptions(), busyReason)
    fun results(findings: List<FindingRow> = listOf(FindingRow(1, "/admin", "200  1.2 KB", "http://10.0.3.10:5000/admin"), FindingRow(2, "Pi-hole", "Page title", null)),
                index: Int = 1, count: Int = 3, active: Boolean = false) =
        ToolResultsState(9, "Directory brute-force  10.0.3.10:5000", if (active) "Running, 12 of 300, 2 found  3 s" else "Done, 2 hours ago", "7 found in 300 paths", index, count, findings, active)
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsBlockTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 1. */
@RunWith(AndroidJUnit4::class)
class ToolsBlockTest {
    @get:Rule val rule = createComposeRule()
    private val ran = mutableListOf<Pair<ToolId, Int>>(); private val opened = mutableListOf<ToolId>()

    private fun show(state: ToolsUiState, busy: Boolean = false) = rule.setContent {
        var expanded by remember { mutableStateOf(false) }
        NetProbeTheme { ToolsBlock(state, expanded, busy, onToggle = { expanded = !expanded }, onRunChip = { t, p -> ran += t to p }, onResults = { opened += it }) }
    }

    @Test fun collapsedByDefaultWithAvailableAndTheNewestHistory() {
        show(ToolsFakes.tools())
        rule.onNodeWithText("Tools").assertIsDisplayed(); rule.onNodeWithText("2 available").assertIsDisplayed()
        rule.onNodeWithText("Last run 2 hours ago: Directory brute-force on 5000, 7 found in 4614 paths").assertIsDisplayed()
        rule.onNodeWithTag("toolsHeader").assert(hasContentDescription("Tools, 2 available, expand"))
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").assertDoesNotExist()
    }
    @Test fun expandsAndCollapses() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolsHeader").assert(hasContentDescription("Tools, 2 available, collapse"))
        rule.onNodeWithTag("toolChip:dir-bruteforce:5001").assertIsDisplayed()
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5001").assertDoesNotExist()
    }
    @Test fun chipsFireTheRightLambdaAndResultsShowsOnlyWithARun() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:http-fingerprint:5000").assert(hasContentDescription("Run HTTP fingerprint on port 5000")).performClick()
        assertEquals(listOf(ToolId.HTTP_FINGERPRINT to 5000), ran)
        rule.onNodeWithTag("toolResults:dir-bruteforce").assert(hasContentDescription("Show Directory brute-force results")).performClick()
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE), opened)
        rule.onNodeWithTag("toolResults:http-fingerprint").assertDoesNotExist()
        rule.onAllNodesWithText("Results").assertCountEquals(1)
    }
    @Test fun chipTargetsAreAtLeast48dp() {
        show(ToolsFakes.tools())
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
    }
    @Test fun whileHeldAChipStillReportsTheTapSoTheScreenCanSayWhy() {
        show(ToolsFakes.tools(), busy = true)
        rule.onNodeWithTag("toolsHeader").performClick()
        rule.onNodeWithTag("toolChip:dir-bruteforce:5000").performClick()
        assertEquals(listOf(ToolId.DIR_BRUTEFORCE to 5000), ran)   // the view model answers a held host with its busy snackbar (Task 16)
    }
}
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` (spec 12.2 items 2, 3, 10; the "sheet closes when its run is removed" behaviour lives in the view model, which sets `results` to null, and is covered on the device in Task 20 step 8):
```kotlin
    private fun showTools(state: DetailUiState, tools: com.northwalessolutions.netprobe.ui.tools.DetailToolsState,
                          toolActions: com.northwalessolutions.netprobe.ui.tools.ToolActions = com.northwalessolutions.netprobe.ui.tools.ToolActions()) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(phone) { DetailContent(state, recording, toolState = tools, toolActions = toolActions) } }
    }
    @Test fun thePinnedBarShowsTheToolRunAndItsStop() {
        var stopped = 0
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(
            bar = com.northwalessolutions.netprobe.ui.tools.ToolRunBar("Directory brute-force on 5000", "1234 of 4614, 7 found  12 s", 0.27f),
            busy = com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work.TOOL),
            com.northwalessolutions.netprobe.ui.tools.ToolActions(onStopTool = { stopped++ }))
        rule.onNodeWithTag("toolBarLabel").assertTextEquals("Directory brute-force on 5000")
        rule.onNodeWithTag("toolBarProgress").assertTextEquals("1234 of 4614, 7 found  12 s")
        rule.onNodeWithTag("detailScanButton").assert(hasContentDescription("Stop tool run")).performClick()
        assertEquals(1, stopped); assertEquals(0, scans)
        rule.onNodeWithText("Full").assertIsNotEnabled()
    }
    @Test fun thePortSheetShowsRunToolRowsOnlyForApplicableTools() {
        val ran = mutableListOf<Pair<com.northwalessolutions.netprobe.engine.tools.ToolId, Int>>()
        showTools(DetailFakes.state(listOf(DetailFakes.port(5000, "http"), DetailFakes.port(22, "ssh", name = "SSH"))),
            com.northwalessolutions.netprobe.ui.tools.DetailToolsState(), com.northwalessolutions.netprobe.ui.tools.ToolActions(onRunTool = { t, p -> ran += t to p }))
        rule.onNodeWithText("5000").performTouchInput { longClick() }
        rule.onNodeWithText("Run tool").assertIsDisplayed()
        rule.onNodeWithTag("sheetTool:dir-bruteforce").assertIsDisplayed(); rule.onNodeWithTag("sheetTool:http-fingerprint").assertIsDisplayed()
        rule.onNodeWithTag("sheetTool:smb-session").assertDoesNotExist()
        rule.onNodeWithTag("sheetTool:http-fingerprint").performClick()
        assertEquals(listOf(com.northwalessolutions.netprobe.engine.tools.ToolId.HTTP_FINGERPRINT to 5000), ran)
        rule.onNodeWithText("22").performTouchInput { longClick() }
        rule.onNodeWithText("Run tool").assertDoesNotExist()
    }
    @Test fun removeDeviceIsDisabledWhileAToolHoldsTheHost() {
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(busy = com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry.Work.TOOL))
        rule.onNodeWithTag("detailMenu").performClick()
        rule.onNodeWithText("Remove device").assertIsNotEnabled()
    }
    @Test fun theToolsBlockIsTheFirstListItem() {
        showTools(DetailFakes.state(), com.northwalessolutions.netprobe.ui.tools.DetailToolsState(tools = com.northwalessolutions.netprobe.ui.tools.ToolsFakesForDetail.tools))
        val header = rule.onNodeWithTag("toolsHeader").getBoundsInRoot(); val firstPort = rule.onAllNodesWithTag("portAction")[0].getBoundsInRoot()
        assertTrue(header.bottom <= firstPort.top)
    }
```
and add `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsFakesForDetail.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

object ToolsFakesForDetail { val tools: ToolsUiState get() = ToolsFakes.tools() }
```
(`DetailFakes.port(n, scheme, name = ...)` is 3A's helper; port 5000 named "HTTP" makes the web tools apply and port 22 "SSH" makes none apply.)

- [ ] **Step 2: Compile to see them fail**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `Unresolved reference 'ToolsBlock'`, `'ToolActions'`, `No parameter with name 'toolState'`.

- [ ] **Step 3: Implement**

`ui/Controls.kt`: in 3A's `ScanPill`, add the parameter `stopDescription: String? = null` after `modifier`, and append to the outer `Box` modifier chain, after `.testTag("detailScanButton")`:
```kotlin
        .semantics { if (scanning && stopDescription != null) contentDescription = stopDescription }
```
(import `androidx.compose.ui.semantics.contentDescription` and `semantics` are already imported in Controls.kt.)

`ui/tools/ToolControls.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.theme.DISABLED_ALPHA
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val PILL_VISUAL = 40.dp
private val PILL_PAD = 18.dp

/** A filled teal pill in the ScanPill style: 40 dp visual in a 48 dp target (spec 8.4 Run, 8.6 Import). */
@Composable
fun ActionPill(label: String, enabled: Boolean, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else DISABLED_ALPHA).testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = PILL_VISUAL).clip(CircleShape).background(NetProbeColors.signal).padding(horizontal = PILL_PAD), contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.scanPill, color = NetProbeColors.background)
        }
    }
}

/** Every tool action the Device screen fires; DetailScreen wires them to DetailViewModel (Task 16). */
data class ToolActions(
    val onRunTool: (ToolId, Int) -> Unit = { _, _ -> }, val onStartDirBrute: (DirBruteOptions) -> Unit = {}, val onCloseDirBrute: () -> Unit = {},
    val onManageWordlists: () -> Unit = {}, val onOpenResults: (ToolId) -> Unit = {}, val onOlderRun: () -> Unit = {}, val onNewerRun: () -> Unit = {},
    val onCloseResults: () -> Unit = {}, val onShare: () -> Unit = {}, val onCopyFinding: (FindingRow) -> Unit = {}, val onOpenFinding: (FindingRow) -> Unit = {},
    val onStopTool: () -> Unit = {}, val onDismissAppMessage: (Long) -> Unit = {},
)
```

`ui/tools/ToolsBlock.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.tools.ToolId
import com.northwalessolutions.netprobe.ui.theme.*

private val ROW_MIN = 58.dp
private val NAME_GAP = 10.dp
private val CHIP_VISUAL = 32.dp
private val CHIP_GAP = 8.dp
private val CHIP_PAD = 12.dp
private val CHEVRON = 20.dp
private val BLOCK_VERTICAL = 10.dp

/**
 * The first item of the Device screen's port list (spec 8.1): collapsed, one row the height of a port row; expanded, one block per
 * applicable tool with its history line and its target chips. While the host is held the chips are dimmed and a tap is answered with
 * the busy snackbar by the view model. No Run or Stop control lives here: runs start from chips, and stop from the pinned bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsBlock(state: ToolsUiState, expanded: Boolean, busy: Boolean, onToggle: () -> Unit, onRunChip: (ToolId, Int) -> Unit, onResults: (ToolId) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(onClick = onToggle)
            .clearAndSetSemantics { contentDescription = ToolCopy.rowDescription(state.available, expanded); role = Role.Button }.testTag("toolsHeader")
            .padding(start = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolCopy.TOOLS, style = NetProbeText.secondary, color = NetProbeColors.text)
                    Spacer(Modifier.width(NAME_GAP))
                    Text(ToolCopy.available(state.available), style = NetProbeText.data, color = NetProbeColors.textMuted)
                }
                Text(state.headerHistory, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(Dimens.touch), contentAlignment = Alignment.Center) {
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(CHEVRON))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        if (expanded) state.blocks.forEach { block ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = BLOCK_VERTICAL)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(block.name, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f))
                    if (block.hasRun) {
                        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onResults(block.toolId) }
                            .semantics { contentDescription = ToolCopy.resultsDescription(block.name) }.testTag("toolResults:${block.toolId.key}"),
                            contentAlignment = Alignment.CenterEnd) {
                            Text(ToolCopy.RESULTS, style = NetProbeText.action, color = NetProbeColors.signal)
                        }
                    }
                }
                Text(block.history, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    block.chips.forEach { chip ->
                        Box(Modifier.heightIn(min = Dimens.touch).alpha(if (busy) DISABLED_ALPHA else 1f).clickable(role = Role.Button) { onRunChip(chip.toolId, chip.port) }
                            .semantics { contentDescription = chip.description }.testTag("toolChip:${chip.toolId.key}:${chip.port}"),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.heightIn(min = CHIP_VISUAL).clip(CircleShape).border(1.dp, NetProbeColors.border, CircleShape).padding(horizontal = CHIP_PAD),
                                contentAlignment = Alignment.Center) {
                                Text(chip.label, style = NetProbeText.data, color = NetProbeColors.textSecondary)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
        }
    }
}
```

`ui/detail/DetailComponents.kt`:
- `DetailMenu` (3A): add the parameter `removeEnabled: Boolean = true` and pass `enabled = removeEnabled` to the "Remove device" `DropdownMenuItem`.
- `ScanBar` (3A): add the parameters `toolBar: ToolRunBar? = null, onStopTool: () -> Unit = {}` at the end. Then:
  - the profile `PinnedSegmented` gets `enabled = !scanning && toolBar == null`;
  - the pill becomes
```kotlin
            if (toolBar != null) ScanPill(scanning = true, enabled = true, onClick = onStopTool, stopDescription = ToolCopy.STOP_DESCRIPTION)
            else ScanPill(scanning, enabled = enabled && !(profile is ScanProfile.Custom && customError != null), onClick = onScan)
```
  - the reserved progress `Box` becomes (same reserved height, no height added, spec 8.2):
```kotlin
        Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).heightIn(min = progressHeight), contentAlignment = Alignment.CenterEnd) {
            when {
                toolBar != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(toolBar.label, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("toolBarLabel"))
                    Text(toolBar.progress, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("toolBarProgress"))
                }
                scanning -> Text(detailProgress(progress, identifying), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.End)
            }
        }
```
  - the 2 dp line `Box` becomes:
```kotlin
        val toolFraction = toolBar?.fraction
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            when {
                toolFraction != null -> LinearProgressIndicator(progress = { toolFraction }, modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal,
                    trackColor = NetProbeColors.surface, gapSize = 0.dp, drawStopIndicator = {})
                toolBar != null -> LinearProgressIndicator(modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal, trackColor = NetProbeColors.surface, gapSize = 0.dp)
                scanning -> LinearProgressIndicator(progress = { detailFraction(progress, identified) }, modifier = Modifier.fillMaxSize(), color = NetProbeColors.signal,
                    trackColor = NetProbeColors.surface, gapSize = 0.dp, drawStopIndicator = {})
            }
        }
```
  (3A's Task 18 progress glide animates the scanning fraction; keep its animated value in the `scanning` branch exactly as 3A left it, and leave the tool branches unanimated, which also satisfies reduced motion.)
- `PortSheet` (3A): add the parameters `tools: List<ToolId> = emptyList(), toolsBusy: Boolean = false, onTool: (ToolId) -> Unit = {}` and, between the `tail.forEach { ... }` rows and the vulnerabilities `SheetRow`, insert:
```kotlin
            if (tools.isNotEmpty()) {
                Text(ToolCopy.RUN_TOOL, style = NetProbeText.caption, color = NetProbeColors.textMuted, modifier = Modifier.padding(top = 4.dp))
                tools.forEach { tool ->
                    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button) { onTool(tool); onClose() }.testTag("sheetTool:${tool.key}"),
                        contentAlignment = Alignment.CenterStart) {
                        Text(ToolCopy.toolName(tool), style = NetProbeText.secondary, color = if (toolsBusy) NetProbeColors.textMuted else NetProbeColors.signal)
                    }
                }
            }
```
(imports `com.northwalessolutions.netprobe.engine.tools.ToolId`, `com.northwalessolutions.netprobe.ui.tools.ToolCopy`, `com.northwalessolutions.netprobe.ui.tools.ToolRunBar`. A held host's row still reports the tap; the view model answers with the busy snackbar, spec 8.3.)

`ui/detail/DetailContent.kt`:
- Signature: `fun DetailContent(state: DetailUiState, actions: DetailActions, startNoteExpanded: Boolean = false, toolState: DetailToolsState = DetailToolsState(), toolActions: ToolActions = ToolActions())`.
- After the existing `LaunchedEffect(state.message)`, add the app-message snackbar (spec 4.3: the visible screen shows it and then dismisses it):
```kotlin
    LaunchedEffect(toolState.appMessage?.id) {
        toolState.appMessage?.let { m -> snackbar.showSnackbar(m.text); toolActions.onDismissAppMessage(m.id) }
    }
    var toolsExpanded by rememberSaveable(state.host?.id) { mutableStateOf(false) }
```
- `DetailMenu(...)` in the back row gets `removeEnabled = toolState.busy != HostBusyRegistry.Work.TOOL`.
- `ScanBar(...)` gets `toolBar = toolState.bar, onStopTool = toolActions.onStopTool`.
- Replace the list part (`if (state.ports.isEmpty()) EmptyState(...) else LazyColumn(...) { items(...) }`) with:
```kotlin
        val tools = toolState.tools
        if (state.ports.isEmpty() && tools == null) EmptyState(if (state.scanning) "Scanning ports" else "No open ports yet, run a scan", pad)
        else LazyColumn(Modifier.padding(pad).fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
            .testTag("portList")) {
            tools?.let { t ->
                item(key = "tools") {
                    ToolsBlock(t, toolsExpanded, busy = toolState.busy != null, onToggle = { toolsExpanded = !toolsExpanded },
                        onRunChip = toolActions.onRunTool, onResults = toolActions.onOpenResults)
                }
            }
            items(state.ports, key = { it.port }) { p -> PortRowItem(ip, p, onAction = actions.onAction, onLongPress = { sheetPort = p.port }) }
        }
```
  (keep 3A's Task 18 row-arrival modifiers on `PortRowItem` exactly as they are; only the `tools` item and the empty condition are new. The two empty-state strings are 3A's.)
- The `PortSheet(...)` call gets `tools = ToolApplicability.toolsFor(p.port, p.service), toolsBusy = toolState.busy != null, onTool = { toolActions.onRunTool(it, p.port) }`.
- imports: `com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry`, `com.northwalessolutions.netprobe.engine.tools.ToolApplicability`, `com.northwalessolutions.netprobe.ui.tools.*`.

`ui/detail/DetailScreen.kt`: add the parameter `onOpenWordlists: () -> Unit = {}` and pass the tool state and actions:
```kotlin
    val tools by vm.toolState.collectAsStateWithLifecycle()
    DetailContent(s, DetailActions(/* 3A's existing actions, unchanged */), toolState = tools, toolActions = ToolActions(
        onRunTool = vm::runTool, onStartDirBrute = vm::startDirBrute, onCloseDirBrute = vm::closeDirBrute, onManageWordlists = onOpenWordlists,
        onOpenResults = vm::openResults, onOlderRun = vm::olderRun, onNewerRun = vm::newerRun, onCloseResults = vm::closeResults, onShare = vm::shareRun,
        onCopyFinding = vm::copyFinding, onOpenFinding = vm::openFinding, onStopTool = vm::stopTool, onDismissAppMessage = vm::dismissAppMessage))
```
(Task 19 passes `onOpenWordlists = { nav.navigate("wordlists") }` from `NetProbeNav`.)

- [ ] **Step 4: Compile the androidTest sources, then the task gate**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. Reviewer checks: the Stop for a tool run is the pinned bar's pill, never a control inside `portList`; every new target is at least 48 dp; no string literal appears in these composables except 3A's two empty-state strings. The tool chips start a run from inside the scrolling list, as spec 8.1 places them, while Stop stays pinned; that tension with the standing pinned-controls rule is listed for the Director's ruling in Task 20 (plan review G25).

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolControls.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolsBlock.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsFakes.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsBlockTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolsFakesForDetail.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Device screen part 1: Tools block, pinned bar during a run, port-sheet tool group"
```

---

### Task 18: Device screen part 2: the brute-force options sheet and the results sheet

Split from the single Device screen task at plan review G16.

**Files:**
- Create (under `app/src/main/java/com/northwalessolutions/netprobe/ui/tools/`): `DirBruteSheet.kt`, `ToolResultsSheet.kt`
- Modify: `ui/detail/DetailContent.kt` (the two sheets)
- Test (compile only, run in Task 20): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/DirBruteSheetTest.kt`, `ToolResultsSheetTest.kt`

**Interfaces:**
- Consumes: `DetailToolsState`, `ToolResultsState`, `FindingRow`, `DirBruteSheetState` (Task 16), `ToolCopy` (Task 3), `DirBruteOptions`, `DirBruteParse`, `FieldError`, `DirMethod` (Task 10), `WordlistMeta` (Task 9), `ActionPill`, `ToolActions`, `ToolsFakes` (Task 17), 3A's `PinnedSegmented`, `npFieldColors`, `StepSlider`, `SwitchRow`, tokens.
- Produces:
```kotlin
@Composable fun DirBruteSheet(state: DirBruteSheetState, onRun: (DirBruteOptions) -> Unit, onManageWordlists: () -> Unit, onDismiss: () -> Unit)
@Composable fun ToolResultsSheet(state: ToolResultsState, onOlder: () -> Unit, onNewer: () -> Unit, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit, onShare: () -> Unit, onDismiss: () -> Unit)
// tags: toolRunPill, dirBruteBody, extensionsField, codesField, filterSwitch, wordlist:<id>, manageWordlists, resultsList, finding:<id>, open:<id>, copy:<id>,
//       olderRun, newerRun, shareRun
```

- [ ] **Step 1: Write the failing Compose tests (compile only)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/DirBruteSheetTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.engine.tools.dirbrute.DirBruteOptions
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 4. */
@RunWith(AndroidJUnit4::class)
class DirBruteSheetTest {
    @get:Rule val rule = createComposeRule()
    private val runs = mutableListOf<DirBruteOptions>(); private var manage = 0
    private fun show(state: DirBruteSheetState = ToolsFakes.sheet(), fontScale: Float = 1f) = rule.setContent {
        NetProbeTheme { DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))) {
            DirBruteSheet(state, onRun = { runs += it }, onManageWordlists = { manage++ }, onDismiss = {})
        } }
    }

    @Test fun defaultsAreShownAndRunReturnsThem() {
        show()
        rule.onNodeWithText("Directory brute-force").assertIsDisplayed(); rule.onNodeWithText("http://10.0.3.10:5000/").assertIsDisplayed()
        rule.onNodeWithTag("wordlist:builtin:common-short").assertIsSelected()
        rule.onNodeWithText("298 paths").assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsEnabled().performClick()
        assertEquals(listOf(DirBruteOptions()), runs)
    }
    @Test fun extensionsMultiplyTheTotalAndInvalidInputDisablesRun() {
        show()
        rule.onNodeWithTag("extensionsField").performTextInput("php, html")
        rule.onNodeWithText("894 paths").assertIsDisplayed()
        rule.onNodeWithTag("extensionsField").performTextClearance(); rule.onNodeWithTag("extensionsField").performTextInput("ph p")
        rule.onNodeWithText("Use letters and digits only, for example php, html").assertExists()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
        rule.onNodeWithTag("extensionsField").performTextClearance()
        rule.onNodeWithTag("codesField").performTextClearance(); rule.onNodeWithTag("codesField").performTextInput("99")
        rule.onNodeWithText("Use codes from 100 to 599, for example 200, 301, 403").assertExists()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
        rule.onNodeWithTag("codesField").performTextClearance()
        rule.onNodeWithText("Add at least one status code").assertExists()
    }
    @Test fun aBusyHostShowsItsReasonAndDisablesRun() {
        show(ToolsFakes.sheet(busyReason = ToolCopy.BUSY_TOO_MANY))
        rule.onNodeWithText("Four tools are already running, stop one first").assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsNotEnabled()
    }
    @Test fun theFooterAndRunPillStayVisibleWithTheBodyScrolledToTheEndAtFontScale13() {
        show(fontScale = 1.3f)
        rule.onNodeWithTag("filterSwitch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("toolRunPill").assertIsDisplayed()
        rule.onNodeWithText("Directory brute-force").assertIsDisplayed()
        val pill = rule.onNodeWithTag("toolRunPill").fetchSemanticsNode()
        val body = rule.onNodeWithTag("dirBruteBody").fetchSemanticsNode()
        assertFalse("the Run pill must not be inside the scrolling body", generateSequence(pill.parent) { it.parent }.any { it.id == body.id })
    }
    @Test fun manageWordlistsFiresItsLambda() {
        show(); rule.onNodeWithTag("manageWordlists").performClick(); assertEquals(1, manage)
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsSheetTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 5. */
@RunWith(AndroidJUnit4::class)
class ToolResultsSheetTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private fun show(state: ToolResultsState) = rule.setContent {
        NetProbeTheme { ToolResultsSheet(state, onOlder = { events += "older" }, onNewer = { events += "newer" }, onCopy = { events += "copy ${it.id}" },
            onOpen = { events += "open ${it.id}" }, onShare = { events += "share" }, onDismiss = {}) }
    }

    @Test fun openWithOnlyWithAUrlCopyAlwaysAndEveryTargetIs48dp() {
        show(ToolsFakes.results())
        rule.onNodeWithTag("open:1").assert(hasContentDescription("Open http://10.0.3.10:5000/admin with")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("open:2").assertDoesNotExist()
        rule.onNodeWithTag("copy:2").assert(hasContentDescription("Copy Pi-hole")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("copy:1").assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("open 1", "copy 2"), events)
    }
    @Test fun theRunSwitcherIsBoundedAtBothEnds() {
        show(ToolsFakes.results(index = 1, count = 3))
        rule.onNodeWithText("Run 1 of 3").assertIsDisplayed()
        rule.onNodeWithTag("newerRun").assertIsNotEnabled(); rule.onNodeWithTag("olderRun").assertIsEnabled().performClick()
        assertEquals(listOf("older"), events)
    }
    @Test fun theOldestRunCannotGoOlder() {
        show(ToolsFakes.results(index = 3, count = 3))
        rule.onNodeWithTag("olderRun").assertIsNotEnabled(); rule.onNodeWithTag("newerRun").assertIsEnabled()
    }
    @Test fun emptyStatesAndShareDisabledWhileRunning() {
        show(ToolsFakes.results(findings = emptyList(), active = true))
        rule.onNodeWithText("Running, findings appear as they are found").assertIsDisplayed()
        rule.onNodeWithTag("shareRun").assertIsNotEnabled()
    }
    @Test fun aFinishedRunWithNothingSaysNoFindingsAndShares() {
        show(ToolsFakes.results(findings = emptyList()))
        rule.onNodeWithText("No findings").assertIsDisplayed()
        rule.onNodeWithTag("shareRun").assertIsEnabled().performClick(); assertEquals(listOf("share"), events)
    }
}
```

- [ ] **Step 2: Compile to see them fail**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `Unresolved reference 'DirBruteSheet'`, `'ToolResultsSheet'`.

- [ ] **Step 3: Implement**

`ui/tools/DirBruteSheet.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.engine.tools.dirbrute.*
import com.northwalessolutions.netprobe.ui.PinnedSegmented
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.settings.StepSlider
import com.northwalessolutions.netprobe.ui.settings.SwitchRow
import com.northwalessolutions.netprobe.ui.theme.*

private val SHEET_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val FIELD_SHAPE = RoundedCornerShape(8.dp)
private val GROUP_GAP = 12.dp
private const val SHEET_MAX_FRACTION = 0.9f
private val METHODS = listOf(DirMethod.HEAD_THEN_GET, DirMethod.GET_ONLY)

private fun message(e: FieldError): String = when (e) {
    FieldError.EXT_CHARS -> ToolCopy.ERR_EXT_CHARS; FieldError.EXT_TOO_MANY -> ToolCopy.ERR_EXT_TOO_MANY
    FieldError.CODE_RANGE -> ToolCopy.ERR_CODE_RANGE; FieldError.CODE_EMPTY -> ToolCopy.ERR_CODE_EMPTY
}

/**
 * Directory brute-force options (spec 8.4). Three parts in a height-bounded Column: a fixed header, a scrolling body, and a fixed
 * footer holding the live path count and the Run pill, so Run is never inside the scroll. Run is disabled while a field has an error
 * or the host is held; dismissing starts nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirBruteSheet(state: DirBruteSheetState, onRun: (DirBruteOptions) -> Unit, onManageWordlists: () -> Unit, onDismiss: () -> Unit) {
    val init = state.initial
    var wordlistId by remember(init) { mutableStateOf(init.wordlistId) }
    var extText by remember(init) { mutableStateOf(init.extensions.joinToString(", ")) }
    var method by remember(init) { mutableStateOf(init.method) }
    var concurrency by remember(init) { mutableIntStateOf(init.concurrency) }
    var timeoutS by remember(init) { mutableIntStateOf(init.timeoutS) }
    var codesText by remember(init) { mutableStateOf(init.statusCodes.joinToString(", ")) }
    var baseline by remember(init) { mutableStateOf(init.baseline) }
    val ext = DirBruteParse.extensions(extText); val codes = DirBruteParse.statusCodes(codesText)
    val extError = (ext as? Parsed.Bad)?.let { message(it.error) }; val codesError = (codes as? Parsed.Bad)?.let { message(it.error) }
    val extensions = (ext as? Parsed.Ok)?.value.orEmpty()
    val entries = state.lists.firstOrNull { it.id == wordlistId }?.entries ?: 0
    val canRun = extError == null && codesError == null && state.busyReason == null
    val maxHeight = (LocalConfiguration.current.screenHeightDp * SHEET_MAX_FRACTION).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            // 1. Header (fixed).
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                Text(ToolCopy.DIR_BRUTEFORCE, style = NetProbeText.rowName, color = NetProbeColors.text)
                Text(state.targetUrl, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // 2. Body (scrolls).
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).testTag("dirBruteBody"),
                verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                Spacer(Modifier.height(4.dp))
                Group(ToolCopy.WORDLIST) {
                    Column(Modifier.selectableGroup()) {
                        state.lists.forEach { meta ->
                            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).selectable(meta.id == wordlistId, role = Role.RadioButton) { wordlistId = meta.id }
                                .testTag("wordlist:${meta.id}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = meta.id == wordlistId, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = NetProbeColors.signal, unselectedColor = NetProbeColors.textMuted))
                                Spacer(Modifier.width(8.dp))
                                Text(meta.name, style = NetProbeText.secondary, color = NetProbeColors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ToolCopy.entries(meta.entries), style = NetProbeText.data, color = NetProbeColors.textMuted)
                            }
                        }
                    }
                    Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onManageWordlists).testTag("manageWordlists"), contentAlignment = Alignment.CenterStart) {
                        Text(ToolCopy.MANAGE_WORDLISTS, style = NetProbeText.secondary.copy(fontWeight = FontWeight.W500), color = NetProbeColors.signal)
                    }
                }
                Group(ToolCopy.EXTENSIONS) {
                    OutlinedTextField(extText, { extText = it }, Modifier.fillMaxWidth().testTag("extensionsField"), singleLine = true, textStyle = NetProbeText.data,
                        placeholder = { Text(ToolCopy.EXTENSIONS_PLACEHOLDER) }, isError = extError != null,
                        supportingText = { Text(extError ?: ToolCopy.EXTENSIONS_SUPPORT, color = if (extError != null) NetProbeColors.error else NetProbeColors.textMuted) },
                        colors = npFieldColors(), shape = FIELD_SHAPE)
                }
                Group(ToolCopy.METHOD) {
                    PinnedSegmented(listOf(ToolCopy.HEAD_THEN_GET, ToolCopy.GET_ONLY), METHODS.indexOf(method), { method = METHODS[it] },
                        activeFill = NetProbeColors.background, activeText = NetProbeColors.text, modifier = Modifier.fillMaxWidth())
                }
                StepSlider(ToolCopy.CONCURRENCY, concurrency, DirBruteOptions.MIN_CONCURRENCY, DirBruteOptions.MAX_CONCURRENCY, 1, "") { concurrency = it }
                StepSlider(ToolCopy.TIMEOUT, timeoutS, DirBruteOptions.MIN_TIMEOUT_S, DirBruteOptions.MAX_TIMEOUT_S, 1, " s") { timeoutS = it }
                Group(ToolCopy.REPORT_CODES) {
                    OutlinedTextField(codesText, { codesText = it }, Modifier.fillMaxWidth().testTag("codesField"), singleLine = true, textStyle = NetProbeText.data,
                        isError = codesError != null, supportingText = codesError?.let { e -> { Text(e, color = NetProbeColors.error) } },
                        colors = npFieldColors(), shape = FIELD_SHAPE)
                }
                Box(Modifier.testTag("filterSwitch")) {
                    SwitchRow(ToolCopy.FILTER, baseline, supporting = ToolCopy.FILTER_SUPPORT) { baseline = it }
                }
                Spacer(Modifier.height(4.dp))
            }
            // 3. Footer (fixed): never inside the scroll.
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Dimens.gutter)) {
                state.busyReason?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.error, modifier = Modifier.padding(bottom = 6.dp)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ToolCopy.paths(entries * (1 + extensions.size)), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
                    ActionPill(ToolCopy.RUN, enabled = canRun, tag = "toolRunPill", onClick = {
                        val parsedCodes = (codes as? Parsed.Ok)?.value ?: return@ActionPill
                        onRun(DirBruteOptions(wordlistId, extensions, method, concurrency, timeoutS, parsedCodes, baseline).coerced())
                    })
                }
            }
        }
    }
}

@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = NetProbeText.caption, color = NetProbeColors.textMuted)
        content()
    }
}
```
(The spec's footer rule is "a 1 dp `surface` rule above"; on the `surface` sheet that rule is drawn in `background` so it is visible, the same choice 3A made for the Detail scan bar's divider. `StepSlider` saves on release, which here updates local state; `SwitchRow` and `StepSlider` are `internal` in `ui/settings` and visible module-wide.)

`ui/tools/ToolResultsSheet.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.*

private val RESULTS_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private const val RESULTS_MAX_FRACTION = 0.9f
private const val VALUE_COLLAPSED_LINES = 3
private val ROW_VERTICAL = 10.dp

/**
 * The results of one run (spec 8.5): a fixed header with the run switcher, the findings in a LazyColumn, and a fixed footer with
 * Share (disabled while the run is active). The view model holds the run id and closes the sheet when that run disappears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolResultsSheet(state: ToolResultsState, onOlder: () -> Unit, onNewer: () -> Unit, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit,
                     onShare: () -> Unit, onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * RESULTS_MAX_FRACTION).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NetProbeColors.surface, shape = RESULTS_SHAPE) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.title, style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onNewer, enabled = state.index > 1, modifier = Modifier.testTag("newerRun").semantics { contentDescription = ToolCopy.NEWER_RUN }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = if (state.index > 1) NetProbeColors.textSecondary else NetProbeColors.border)
                    }
                    Text(ToolCopy.runOf(state.index, state.count), style = NetProbeText.data, color = NetProbeColors.textMuted)
                    IconButton(onClick = onOlder, enabled = state.index < state.count, modifier = Modifier.testTag("olderRun").semantics { contentDescription = ToolCopy.OLDER_RUN }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (state.index < state.count) NetProbeColors.textSecondary else NetProbeColors.border)
                    }
                }
                Text(state.statusLine, style = NetProbeText.data, color = NetProbeColors.textMuted)
                if (state.summary.isNotEmpty()) Text(state.summary, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider, modifier = Modifier.padding(top = 8.dp))
            if (state.findings.isEmpty()) {
                Text(if (state.active) ToolCopy.EMPTY_RUNNING else ToolCopy.NO_FINDINGS, style = NetProbeText.secondary, color = NetProbeColors.textMuted,
                    modifier = Modifier.padding(Dimens.gutter))
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).testTag("resultsList")) {
                    items(state.findings, key = { it.id }) { row -> FindingRowItem(row, onCopy, onOpen) }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = NetProbeColors.background)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Dimens.gutter), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onShare, enabled = !state.active, modifier = Modifier.heightIn(min = Dimens.touch).testTag("shareRun")) {
                    Text(ToolCopy.SHARE, style = NetProbeText.action, color = if (state.active) NetProbeColors.textMuted else NetProbeColors.signal)
                }
            }
        }
    }
}

@Composable
private fun FindingRowItem(row: FindingRow, onCopy: (FindingRow) -> Unit, onOpen: (FindingRow) -> Unit) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = ROW_VERTICAL).testTag("finding:${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
            Text(row.value, style = NetProbeText.data, color = NetProbeColors.text, maxLines = if (expanded) Int.MAX_VALUE else VALUE_COLLAPSED_LINES, overflow = TextOverflow.Ellipsis)
            row.detail?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        row.url?.let { url ->
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onOpen(row) }
                .semantics { contentDescription = ToolCopy.openDescription(url) }.testTag("open:${row.id}"), contentAlignment = Alignment.Center) {
                Text(ToolCopy.OPEN_WITH, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button) { onCopy(row) }
            .semantics { contentDescription = ToolCopy.copyDescription(row.value) }.testTag("copy:${row.id}"), contentAlignment = Alignment.Center) {
            Text(ToolCopy.COPY, style = NetProbeText.action.copy(fontWeight = FontWeight.W400), color = NetProbeColors.textMuted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
    HorizontalDivider(thickness = 1.dp, color = NetProbeColors.rowDivider)
}
```

`ui/detail/DetailContent.kt`:
- After the `VulnSheet` line, add:
```kotlin
    toolState.dirBrute?.let { DirBruteSheet(it, onRun = toolActions.onStartDirBrute, onManageWordlists = { toolActions.onCloseDirBrute(); toolActions.onManageWordlists() }, onDismiss = toolActions.onCloseDirBrute) }
    toolState.results?.let { ToolResultsSheet(it, toolActions.onOlderRun, toolActions.onNewerRun, toolActions.onCopyFinding, toolActions.onOpenFinding, toolActions.onShare, toolActions.onCloseResults) }
```

- [ ] **Step 4: Compile the androidTest sources, then the task gate**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`. Reviewer checks: the Run pill and Share sit outside the scrolling body (the `DirBruteSheetTest` ancestry assertion pins Run); every new target is at least 48 dp; no string literal appears in these composables.

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/DirBruteSheet.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsSheet.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/DirBruteSheetTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/tools/ToolResultsSheetTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Device screen part 2: brute-force options sheet and results sheet"
```

---

### Task 19: Wordlists screen, the Settings Tools section, navigation, the Home running marker, the app-message notice and Quick scan all skipping

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt`, `ui/settings/ToolsSection.kt`
- Modify: `ui/Controls.kt` (`BackRow`), `ui/Nav.kt` (the `wordlists` route and two entry points), `ui/settings/SettingsScreen.kt` (the Tools section), `ui/detail/DetailScreen.kt` caller in Nav, `ui/home/HomeViewModel.kt` (`HostRow` fields, `HomeUiState` fields, the busy and app-message overlay, `dismissAppMessage`, Quick scan all claim and skip), `ui/home/RowText.kt` (description suffix), `ui/home/HomeComponents.kt` (`Notice.app`, `NoticeArea` app dismiss, `HostRowItem` marker), `ui/home/HomeContent.kt` (notice priority, `HomeActions.onDismissApp`), `ui/home/HomeScreen.kt` (wiring)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowTextToolsTest.kt`; compile only (run in Task 20): `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt`, `ui/settings/SettingsToolsTest.kt`, additions to `ui/home/HomeContentTest.kt`

**Interfaces:**
- Consumes: `WordlistStore`, `WordlistMeta`, `ImportResult` (Task 9), `WordlistCopy`, `ToolCopy` (Task 3), `ActionPill` (Task 17), `HostBusyRegistry`, `AppMessages`, `AppMessage` (Task 4), 3A's `BackToHome`, `Section`, `HomeUiState`, `HostRow`, `RowText`, `HostRowItem`, `Notice`, `NoticeArea`, `NoticeLine`, `HomeActions`, `quickScanAllBody`, `QuickScanAllLoop`, `RelativeTime`, `HomeGroups`, `NetProbeNavHost` transitions.
- Produces:
```kotlin
@Composable fun BackRow(label: String, onBack: () -> Unit)
data class WordlistsActions(val onBack: () -> Unit = {}, val onImport: () -> Unit = {}, val onDelete: (WordlistMeta) -> Unit = {})
@Composable fun WordlistsContent(lists: List<WordlistMeta>, importing: Boolean, message: String?, onMessageShown: () -> Unit, actions: WordlistsActions, formatDate: (Long) -> String = RelativeTime::defaultDate)
@Composable fun WordlistsScreen(container: AppContainer, onBack: () -> Unit)        // route "wordlists"
object WordlistImport { fun message(result: ImportResult): String }
@Composable internal fun ToolsSection(imported: Int, onOpenWordlists: () -> Unit)    // tag wordlistsRow
SettingsScreen(container, onBack, onOpenWordlists: () -> Unit = {})
HostRow(..., val running: Boolean = false, val runningIsScan: Boolean = false)
HomeUiState(..., val appMessage: String? = null, val appMessageId: Long? = null, val toolRunningOnNetwork: Boolean = false)
data class Notice(val text: String, val undo: Boolean, val app: Boolean = false); NoticeArea(..., onDismissApp: () -> Unit = {})
HomeActions(..., val onDismissApp: () -> Unit = {}); HomeViewModel.dismissAppMessage()
// HomeContent: forgetEnabled also needs !state.toolRunningOnNetwork; HomeViewModel.forgetNetwork returns early while it is true
```

- [ ] **Step 0: Re-read the 3A files this task edits (plan review G17)**

3A's motion task (Task 18 of the 3A plan) edits `ui/home/HomeContent.kt`, `HomeComponents.kt` (`NoticeArea`, `HostRowItem`) and `ui/Controls.kt`, and may land after this plan was written. Before editing, read the current `HomeMenu` call and its `forgetEnabled` expression, the notice `when`, `NoticeArea`, `HostRowItem`, `HomeViewModel.state`, `forgetNetwork` and `quickScanAllBody` on the branch. Every edit below adds to that code; keep 3A's modifiers, animations and guards, and if a named signature has changed, stop and report it rather than reshaping 3A code.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowTextToolsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.wordlists.WordlistImport
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import org.junit.Assert.*
import org.junit.Test

/** spec 4.3 and 9: a held row's description suffix; the import result messages. */
class RowTextToolsTest {
    private fun row(running: Boolean, scan: Boolean = false) = HostRow(7, "10.0.3.59", "CHNAS", "Synology DS220+", DeviceType.NAS, 3, HostStatus.UNMARKED,
        false, false, null, running = running, runningIsScan = scan)
    @Test fun `a held row says what holds it, last`() {
        assertEquals("CHNAS, 10.0.3.59, 3 open ports, tool running", RowText.description(row(true), null))
        assertEquals("CHNAS, 10.0.3.59, 3 open ports, port scan running", RowText.description(row(true, scan = true), null))
        assertEquals("CHNAS, 10.0.3.59, 3 open ports", RowText.description(row(false), null))
    }
    @Test fun `every import result has its message`() {
        val meta = WordlistMeta("user:1", "my list", 4614, 1L, false)
        assertEquals("Imported my list, 4614 entries", WordlistImport.message(ImportResult.Imported(meta, 0)))
        assertEquals("Imported my list, 4614 entries (3 skipped)", WordlistImport.message(ImportResult.Imported(meta, 3)))
        assertEquals("That file is too large (limit 10 MB)", WordlistImport.message(ImportResult.TooLarge))
        assertEquals("That file has more than 200000 entries", WordlistImport.message(ImportResult.TooMany))
        assertEquals("That file has no entries", WordlistImport.message(ImportResult.Empty))
        assertEquals("Could not read that file", WordlistImport.message(ImportResult.Unreadable))
    }
}
```
(`HostRow`'s positional order is 3A's: id, ip, displayName, identityLine, type, openPorts, status, isGateway, stale, mac, then the named fields.)

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 6. */
@RunWith(AndroidJUnit4::class)
class WordlistsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val builtIns = listOf(WordlistMeta("builtin:common-short", "common-short", 298, null, true), WordlistMeta("builtin:admin-panels", "admin-panels", 153, null, true))
    private val mine = WordlistMeta("user:1", "my list", 4614, 0L, false)
    private val deleted = mutableListOf<WordlistMeta>(); private var imports = 0
    private fun show(lists: List<WordlistMeta>, importing: Boolean = false) = rule.setContent {
        NetProbeTheme { WordlistsContent(lists, importing, null, {}, WordlistsActions(onImport = { imports++ }, onDelete = { deleted += it }), formatDate = { "3 Jan 2026" }) }
    }

    @Test fun builtInRowsHaveNoDelete() {
        show(builtIns)
        rule.onNodeWithText("298 entries, built in").assertIsDisplayed()
        rule.onAllNodesWithText("Delete").assertCountEquals(0)
        rule.onNodeWithText("No imported wordlists yet").assertIsDisplayed()
    }
    @Test fun anImportedRowConfirmsBeforeDelete() {
        show(builtIns + mine)
        rule.onNodeWithText("4614 entries, imported 3 Jan 2026").assertIsDisplayed()
        rule.onNodeWithTag("delete:user:1").assert(hasContentDescription("Delete my list")).performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("Delete my list?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick(); assertTrue(deleted.isEmpty())
        rule.onNodeWithTag("delete:user:1").performClick(); rule.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf(mine), deleted)
    }
    @Test fun theImportPillIsOutsideTheScrollingList() {
        show(builtIns + (1..40).map { WordlistMeta("user:$it", "list $it", it, 0L, false) })
        rule.onNodeWithTag("wordlistList").performScrollToIndex(40)
        rule.onNodeWithTag("importWordlist").assertIsDisplayed().performClick(); assertEquals(1, imports)
        val pill = rule.onNodeWithTag("importWordlist").fetchSemanticsNode(); val list = rule.onNodeWithTag("wordlistList").fetchSemanticsNode()
        assertFalse(generateSequence(pill.parent) { it.parent }.any { it.id == list.id })
    }
    @Test fun whileImportingThePillReadsImportingAndIsDisabled() {
        show(builtIns, importing = true)
        rule.onNodeWithText("Importing").assertIsDisplayed(); rule.onNodeWithTag("importWordlist").assertIsNotEnabled()
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsToolsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** spec 12.2 item 7. */
@RunWith(AndroidJUnit4::class)
class SettingsToolsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun theToolsSectionShowsTheWordlistsRowAndOpensIt() {
        var opened = 0
        rule.setContent { NetProbeTheme { ToolsSection(imported = 2, onOpenWordlists = { opened++ }) } }
        rule.onNodeWithText("Tools").assertIsDisplayed(); rule.onNodeWithText("3 built in, 2 imported").assertIsDisplayed()
        rule.onNodeWithTag("wordlistsRow").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(56f)).performClick()
        assertEquals(1, opened)
    }
}
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (spec 12.2 item 8, and plan review G9 for Forget):
```kotlin
    @Test fun aHeldHostShowsRunningInPlaceOfItsPortCount() {
        val base = HomeFakes.settled()
        val rows = base.rows.mapIndexed { i, r -> if (i == 0) r.copy(openPorts = 3, running = true) else r }
        show(base.copy(rows = rows, groups = HomeGroups.of(rows)))
        rule.onNodeWithTag("rowRunning").assertTextEquals("running")
        rule.onAllNodesWithTag("hostRow")[0].assert(hasContentDescription("tool running", substring = true))
        rule.onNodeWithText("3 open").assertDoesNotExist()
    }
    @Test fun forgetThisNetworkIsDisabledWhileAToolRunsOnIt() {
        var st by mutableStateOf(HomeFakes.settled().copy(networkId = 1, toolRunningOnNetwork = true))
        rule.setContent { NetProbeTheme { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithTag("homeMenu").performClick(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        st = st.copy(toolRunningOnNetwork = false); rule.waitForIdle()
        rule.onNodeWithText("Forget this network").assertIsEnabled()
    }
    @Test fun anAppMessageShowsBelowAnUndoAndIsDismissedThroughItsOwnAction() {
        var dismissed = 0
        var st by mutableStateOf(HomeFakes.settled().copy(appMessage = "HTTP fingerprint finished: Pi-hole, 6 findings", appMessageId = 5, undoText = "Removed DiskStation"))
        rule.setContent { NetProbeTheme { HomeContent(st, HomeActions(onDismissApp = { dismissed++ }), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Removed DiskStation").assertIsDisplayed()                       // Undo wins
        rule.onNodeWithText("HTTP fingerprint finished: Pi-hole, 6 findings").assertDoesNotExist()
        st = st.copy(undoText = null); rule.waitForIdle()
        rule.onNodeWithText("HTTP fingerprint finished: Pi-hole, 6 findings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss notice").performClick()
        assertEquals(1, dismissed)
    }
```

- [ ] **Step 2: Run the JVM test and compile to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.RowTextToolsTest" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `No parameter with name 'running'`, `Unresolved reference 'WordlistImport'`, `'WordlistsContent'`, `'ToolsSection'`, `No parameter with name 'onDismissApp'`.

- [ ] **Step 3: Implement**

`ui/Controls.kt`: add after `BackToHome`:
```kotlin
/** A back row with its own label, for screens whose way back is not Home (spec 8.6: the Wordlists screen's "Back"). */
@Composable
fun BackRow(label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onBack).padding(horizontal = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = NetProbeText.data, color = NetProbeColors.textMuted)
    }
}
```

`ui/wordlists/WordlistsScreen.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.wordlists

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.ui.BackRow
import com.northwalessolutions.netprobe.ui.home.RelativeTime
import com.northwalessolutions.netprobe.ui.theme.*
import com.northwalessolutions.netprobe.ui.tools.ActionPill
import com.northwalessolutions.netprobe.wordlists.ImportResult
import com.northwalessolutions.netprobe.wordlists.WordlistMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WordlistsActions(val onBack: () -> Unit = {}, val onImport: () -> Unit = {}, val onDelete: (WordlistMeta) -> Unit = {})

/** The snackbar text for an import (spec 9). */
object WordlistImport {
    fun message(result: ImportResult): String = when (result) {
        is ImportResult.Imported -> WordlistCopy.imported(result.meta.name, result.meta.entries, result.skipped)
        ImportResult.TooLarge -> WordlistCopy.TOO_LARGE
        ImportResult.TooMany -> WordlistCopy.TOO_MANY
        ImportResult.Empty -> WordlistCopy.EMPTY_FILE
        ImportResult.Unreadable -> WordlistCopy.UNREADABLE
    }
}

private val SECTION_PAD = 10.dp
private val ROW_VERTICAL = 8.dp

/**
 * The Wordlists screen (spec 8.6), stateless: a pinned top block (back row, title, the Import pill) and a scrolling list of built-in
 * then imported lists. Delete confirms in an AlertDialog. The Import pill is never inside the list.
 */
@Composable
fun WordlistsContent(lists: List<WordlistMeta>, importing: Boolean, message: String?, onMessageShown: () -> Unit, actions: WordlistsActions,
                     formatDate: (Long) -> String = RelativeTime::defaultDate) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    var confirm by remember { mutableStateOf<WordlistMeta?>(null) }
    Scaffold(containerColor = NetProbeColors.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Column(Modifier.fillMaxWidth().background(NetProbeColors.background).windowInsetsPadding(WindowInsets.statusBars)) {
            BackRow(WordlistCopy.BACK, actions.onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                Text(WordlistCopy.TITLE, style = NetProbeText.title, color = NetProbeColors.text, modifier = Modifier.weight(1f))
                ActionPill(if (importing) WordlistCopy.IMPORTING else WordlistCopy.IMPORT, enabled = !importing, onClick = actions.onImport, tag = "importWordlist")
            }
        }
    }) { pad ->
        val builtIn = lists.filter { it.builtIn }; val imported = lists.filter { !it.builtIn }
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("wordlistList")) {
            item(key = "builtInHeader") { SectionHeader(WordlistCopy.BUILT_IN) }
            items(builtIn, key = { it.id }) { WordlistRow(it.name, WordlistCopy.entriesBuiltIn(it.entries), null) }
            item(key = "importedHeader") { SectionHeader(WordlistCopy.IMPORTED) }
            if (imported.isEmpty()) item(key = "empty") {
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
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).padding(start = Dimens.gutter, end = 6.dp, top = ROW_VERTICAL, bottom = ROW_VERTICAL),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NetProbeText.secondary, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        if (deleteId != null) {
            Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = WordlistCopy.deleteDescription(name) }.testTag("delete:$deleteId"), contentAlignment = Alignment.Center) {
                Text(WordlistCopy.DELETE, style = NetProbeText.action, color = NetProbeColors.signal, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

/** Route "wordlists": SAF import (ACTION_OPEN_DOCUMENT, text/plain, no permission), import on Dispatchers.IO, delete through the store. */
@Composable
fun WordlistsScreen(container: AppContainer, onBack: () -> Unit) {
    val lists by container.wordlistStore.lists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { container.wordlistStore.import(displayName(ctx, uri), it) } }.getOrNull() ?: ImportResult.Unreadable
            }
            importing = false
            message = WordlistImport.message(result)
        }
    }
    WordlistsContent(lists, importing, message, { message = null },
        WordlistsActions(onBack = onBack, onImport = { launcher.launch(arrayOf("text/plain")) }, onDelete = { meta -> scope.launch { container.wordlistStore.delete(meta.id) } }))
}

private fun displayName(ctx: Context, uri: Uri): String? =
    runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
```
(import `androidx.compose.foundation.background`. `import` itself enforces the 10 MB byte cap, the 200000-entry cap, the empty rejection and the " (2)" name suffixes (Task 9); a missing display name becomes "Imported list" there.)

`ui/settings/ToolsSection.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText
import com.northwalessolutions.netprobe.ui.wordlists.WordlistCopy

private val ROW_MIN = 56.dp

/** Settings > Tools (spec 8.7): one row that opens the Wordlists screen; tool defaults live in the options sheet. */
@Composable
internal fun ToolsSection(imported: Int, onOpenWordlists: () -> Unit) {
    Section(WordlistCopy.SETTINGS_SECTION)
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable(role = Role.Button, onClick = onOpenWordlists).testTag("wordlistsRow"),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(WordlistCopy.SETTINGS_ROW, style = NetProbeText.secondary, color = NetProbeColors.text)
            Text(WordlistCopy.settingsSupport(imported), style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(20.dp))
    }
}
```

`ui/settings/SettingsScreen.kt`: add the parameter `onOpenWordlists: () -> Unit = {}` to `SettingsScreen`, and after the "Clear vulnerability cache" `TextButton` (before the final `Spacer`), add:
```kotlin
            val wordlists by container.wordlistStore.lists.collectAsStateWithLifecycle()
            ToolsSection(imported = wordlists.count { !it.builtIn }, onOpenWordlists = onOpenWordlists)
```

`ui/Nav.kt`: in 3A's `NetProbeNavHost`, give the `settings` destination `SettingsScreen(container, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") })`, give the `host/{id}` destination `DetailScreen(container, id, onBack = { nav.popBackStack() }, onOpenWordlists = { nav.navigate("wordlists") })`, and add a destination with the same transition arguments 3A gives `settings`:
```kotlin
        composable("wordlists") { WordlistsScreen(container, onBack = { nav.popBackStack() }) }
```
(import `com.northwalessolutions.netprobe.ui.wordlists.WordlistsScreen`.)

`ui/home/HomeViewModel.kt`:
- `HostRow`: append `val running: Boolean = false, val runningIsScan: Boolean = false`.
- `HomeUiState`: append `val appMessage: String? = null, val appMessageId: Long? = null, val toolRunningOnNetwork: Boolean = false`.
- Rename the existing `val state: StateFlow<HomeUiState> = combine(...) { ... }.stateIn(...)` to keep its combine body and `stateIn` but feed an overlay: change its declaration to `private val baseState: Flow<HomeUiState> = combine(...) { ... }` (drop its `.stateIn(...)`), and add:
```kotlin
    /** Rows held by a port scan or a tool show "running"; the newest end-of-run message is offered to the notice line (spec 4.3, 8.8). */
    /** Rows held by a port scan or a tool show "running"; the newest end-of-run message is offered to the notice line (spec 4.3, 8.8).
     *  [HomeUiState.toolRunningOnNetwork] reads the 3A `hosts` flow (every host of the shown network, not only the filtered rows), so
     *  Forget this network stays disabled while a tool holds any of them (plan review G9). */
    val state: StateFlow<HomeUiState> = combine(baseState, c.hostBusy.busy, c.appMessages.current, hosts) { s, busy, message, hs ->
        val rows = s.rows.map { r -> busy[r.id]?.let { w -> r.copy(running = true, runningIsScan = w == HostBusyRegistry.Work.PORT_SCAN) } ?: r }
        s.copy(rows = rows, groups = HomeGroups.of(rows), appMessage = message?.text, appMessageId = message?.id,
            toolRunningOnNetwork = hs.any { busy[it.id] == HostBusyRegistry.Work.TOOL })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    fun dismissAppMessage() { state.value.appMessageId?.let { c.appMessages.dismiss(it) } }
```
- `forgetNetwork()`: extend its first guard to `if (controller.isRunning || quick.isRunning || state.value.toolRunningOnNetwork) return` (plan review G9). A Forget during a run would soft-delete the network while the run's end writes and re-identification still target its host; the menu item is disabled for the same state below, and the guard covers a tap that races the flow.
- In `quickScanAllBody`, declare `var skipped = 0` next to `failed` and `unbound`, and change the `QuickScanAllLoop.run(...)` body lambda to (label it `scanOne@`):
```kotlin
                scanOne@{ h ->
                    // A host a tool holds is skipped and counted (spec 4.3); a host another port scan holds is skipped silently.
                    if (!c.hostBusy.tryClaim(h.id, HostBusyRegistry.Work.PORT_SCAN)) {
                        if (c.hostBusy.busy.value[h.id] == HostBusyRegistry.Work.TOOL) skipped++
                        return@scanOne
                    }
                    try {
                        try { quickScanHost(h, e) } catch (ce: CancellationException) { throw ce } catch (_: BindFailedException) { unbound++ } catch (_: Exception) { failed++ }
                    } finally { c.hostBusy.release(h.id, HostBusyRegistry.Work.PORT_SCAN) }
                }
```
  and in its `finally`, replace the `parts` line with:
```kotlin
            val parts = listOfNotNull(ToolCopy.quickEnd(failed, skipped), if (unbound > 0) HomeCopy.BIND_FAILED else null)
```
  (imports `com.northwalessolutions.netprobe.engine.tools.HostBusyRegistry`, `com.northwalessolutions.netprobe.ui.tools.ToolCopy`. The old "device(s)" wording is replaced by `ToolCopy.quickEnd`'s plural forms, which spec 9 defines.)

`ui/home/RowText.kt`: in `description`, add as the last item of the `buildList`:
```kotlin
        if (r.running) add(ToolCopy.rowSuffix(r.runningIsScan))
```
(import `com.northwalessolutions.netprobe.ui.tools.ToolCopy`.)

`ui/home/HomeComponents.kt`:
- `Notice`: `data class Notice(val text: String, val undo: Boolean, val app: Boolean = false)`.
- `NoticeArea`: add the parameter `onDismissApp: () -> Unit = {}` and change its last line to
```kotlin
        shown?.let { n -> when { n.undo -> NoticeLine(n.text, onDismissUndo, onUndo = onUndo); n.app -> NoticeLine(n.text, onDismissApp); else -> NoticeLine(n.text, onDismiss) } }
```
- `HostRowItem`: replace the open-port count `Text` with
```kotlin
        if (r.running) Text(ToolCopy.RUNNING_MARKER, style = NetProbeText.caption, color = NetProbeColors.signal, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).testTag("rowRunning"))
        else if (r.openPorts > 0) Text("${r.openPorts} open", style = NetProbeText.count, color = NetProbeColors.signal, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp))
```
  (the "N open" literal is the existing MVP wording, unchanged.)

`ui/home/HomeContent.kt`:
- `HomeActions`: append `val onDismissApp: () -> Unit = {}`.
- `HomeMenu(...)`: its `forgetEnabled` argument becomes `state.networkId != null && !state.scanning && !state.quickScanning && !radarBusy && !state.toolRunningOnNetwork`.
- The notice `when` (3A Task 18 form) becomes:
```kotlin
            val notice = when {
                state.undoText != null -> Notice(state.undoText, undo = true)
                state.appMessage != null -> Notice(state.appMessage, undo = false, app = true)
                state.message != null -> Notice(state.message, undo = false)
                else -> null
            }
            NoticeArea(notice, actions.onDismissMessage, actions.onUndo, actions.onDismissUndo, onDismissApp = actions.onDismissApp)
```

`ui/home/HomeScreen.kt`: add `onDismissApp = vm::dismissAppMessage` to `HomeActions(...)`.

- [ ] **Step 4: Run the JVM test, compile the androidTest sources, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `RowTextToolsTest` (2) passes with 3A's Home tests; `BUILD SUCCESSFUL`. The Home row does not grow (the marker replaces the count, spec 8.9).

- [ ] **Step 5: Commit**

Stage only this task's files, never `git add -A`: another agent may be working in the same tree (plan review G21).
```bash
git add \
  app/src/main/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/settings/ToolsSection.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/settings/SettingsScreen.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/RowText.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt \
  app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt \
  app/src/test/java/com/northwalessolutions/netprobe/ui/home/RowTextToolsTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/wordlists/WordlistsScreenTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/settings/SettingsToolsTest.kt \
  app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt
git status --short   # plan review G21: only the paths above may show as staged (first column); anything else staged is unstaged and reported
git commit -m "Wordlists screen, Settings Tools section, navigation, Home running marker, app-message notice and Quick scan all skipping"
```

---

### Task 20: Deferred on-device QA (executed later, when the Director returns with the Pixel)

This task is NOT executed during the plan. Tasks 1 to 19 end with JVM tests green and every instrumented test compiled; no task has device evidence. When the Director is back with the Pixel 7 Pro (`28221FDH300AP9`) on his LAN, Bugsy runs every item below in order and records each result, pass or fail, in `docs/qa/phase3b-on-device.md`. Per the standing QA rule, Bugsy builds, installs on the device and smoke tests every core flow; a green suite is not a substitute, and code review is not QA. If any step fails, stop, record it, and tell the Director; never fix anything on his live database. The migration here is schema 2 to 3 and is additive (new tables only).

Why the order is safe: building `AppContainer` touches no database; the only startup database work is `ToolRepository.markInterrupted`, launched on `appScope` the first time something reads `toolRunner`. `NetProbeNav` reads `toolRunActive`, so only an instrumented class that launches the real activity reaches it, and the only such class is 3A's `HomeScreenTest` (plan review G26). Steps 3 and 5 therefore never open `netprobe.db`, step 3's "unchanged" check is real, and `HomeScreenTest` runs last, after the in-place migration of step 4.

**Files:**
- Create (at execution time): `docs/qa/phase3b-on-device.md`, `docs/qa/shots/phase3b/*.png`, `docs/qa/shots/phase3b/*.mp4`
- Backups and the rollback APK go OUTSIDE the repository (they hold the Director's network inventory): `K:/claude/netprobe_db_backup/phase3b/`
- Modify (step R, before anything else): the `phase3b` branch itself, rebased onto `phase3a` (final review I4)

- [ ] **Step R: Rebase `phase3b` onto `phase3a` and re-run the full gate, before any other step (ruling R2, final review I4)**

The phone runs the `phase3a` build (`87ab3c1`), so 3B must be rebuilt on top of it before anything is installed. The `.gitattributes` from final review I2 must already be on `phase3b` (it pins `app/src/main/res/raw/*.txt` to LF); without it the rebase checks the bundled wordlists out as CRLF on this PC (`core.autocrlf=true`) and `BundledWordlistsTest` fails. `phase3a` may carry docs-only commits after `87ab3c1`; that does not change the build.
```bash
git status --short                      # must be empty
git checkout phase3b
grep -qF 'app/src/main/res/raw/*.txt text eol=lf' .gitattributes || echo "STOP: the I2 .gitattributes is missing"
git rebase phase3a
```
Expect exactly two conflicts, both in the Task 7 commit (`38fcd2b` before the rebase). Resolve them like this and nothing else:
1. `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarCollector.kt`: keep 3A's F1 body (`previousMac`, `IdentityWrite.sameDevice` and the guarded `setIdentityIfNotWeaker`) and add `hintsFor(id)` as the last argument of `HostEvidenceBuilder.build`. Keep 3B's `hintsFor` constructor parameter, before `removed`.
2. `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierTest.kt`: set the rule count to `134` (118, plus 2 from 3A, plus 14 from 3B) and the `gateway - p-dsm-title` gap to `73`, and keep 3B's two hint-position assertions.

`identify_rules.json` merges cleanly (3A's two rules sit after `p-unifi-cert`, 3B's 14 before `gateway`). If it or any other file conflicts, run `git rebase --abort` and stop. After each resolution:
```bash
git add <the resolved file>
git rebase --continue
```
Then check the line endings and run the full gate:
```bash
git ls-files --eol app/src/main/res/raw/    # every *.txt must read i/lf w/lf attr/text eol=lf
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL`, 0 failures. Dot measured 882 JVM tests on the trial rebase; the final review's I1 test adds one, so expect 883 (sum the `tests` attributes in `app/build/test-results/testDebugUnitTest/*.xml` and record the number). `compileDebugAndroidTestKotlin` must pass, which proves 3A's `RadarIdentityGuardTest` compiles against the new `RadarCollector` signature. If anything is red, stop and tell the Director; do not install. The rebase rewrites every 3B commit, so record the new `phase3b` tip as the build commit in the QA record.

Common setup (as the 3A plan's device QA task):
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=28221FDH300AP9
PKG=com.northwalessolutions.netprobe
BK=/k/claude/netprobe_db_backup/phase3b
BASE=$(cat docs/superpowers/plans/phase3b-base.txt)    # 87ab3c1, the phase3a build on the phone (final review I4)
mkdir -p "$BK" docs/qa/shots/phase3b
# Binary-safe copy into the app's data directory, then an MD5 check (final review I3). Never stream a file with
# `adb shell ... < file`: on this PC it truncates binary stdin (3A device QA finding F5: 4220 of 323584 bytes written).
# `exec-in` is the form 3A's QA proved byte for byte. Usage: put <local file> <path under the app's data directory>
put() {
  "$ADB" -s $DEV exec-in run-as $PKG sh -c "cat > $2" < "$1"
  local want got
  want=$(md5sum < "$1" | cut -d' ' -f1)
  got=$("$ADB" -s $DEV exec-out run-as $PKG md5sum "$2" | cut -d' ' -f1)
  if [ "$want" = "$got" ]; then echo "MD5 OK $2 $got"; else echo "MD5 MISMATCH $2: PC $want, phone $got"; return 1; fi
}
run() { ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$1" --console=plain 2>&1 | tail -12; }
```
If `leaveApksInstalledAfterRun` is rejected by this AGP version, stop and ask the Director before running any instrumented test without it.

- [ ] **Step 0: Back up the live database, before anything is installed or any instrumented test runs**

The installed build is schema 2 (the 3A build). Force-stop the app first so nothing writes during the copy:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do
  "$ADB" -s $DEV exec-out run-as $PKG cat databases/$f > "$BK/$f.bak"
done
ls -l "$BK"
```
Expected: three files; `netprobe.db.bak` non-empty (the `-wal` and `-shm` files may be empty if the last session checkpointed; record their sizes). If `netprobe.db.bak` is empty, stop.

- [ ] **Step 1: Build the schema 2 rollback APK from the local tree (no downloads)**

The Phase 3B install replaces the schema 2 build; keep a way back. Build the recorded base commit in a throwaway worktree, offline:
```bash
RB=/k/claude/netprobe_rollback_${BASE:0:7}
git worktree add "$RB" "$BASE"
cp local.properties "$RB/"
(cd "$RB" && ./gradlew :app:assembleDebug --offline --console=plain 2>&1 | tail -3)
cp "$RB/app/build/outputs/apk/debug/app-debug.apk" "$BK/netprobe-schema2-${BASE:0:7}.apk"
git worktree remove --force "$RB"
ls -l "$BK"
```
Expected: `BUILD SUCCESSFUL` and the APK in `$BK`. If Gradle reports anything missing offline, stop and ask the Director; never drop `--offline`.

- [ ] **Step 2: Put the copy for DirectorCopyMigration23Test in place (plan review G7)**

Written straight into the app's data directory with `put` (`adb exec-in`, never `adb shell ... < file`), so no SELinux rule on `/data/local/tmp` can block it; SQLite rebuilds the `-shm` file. Each file's MD5 is compared with its backup before anything else runs:
```bash
for f in netprobe.db netprobe.db-wal; do
  put "$BK/$f.bak" "databases/${f/netprobe.db/netprobe-migration-copy.db}" || break
done
"$ADB" -s $DEV shell run-as $PKG ls -l databases/
```
Expected: `MD5 OK` for both files; `netprobe.db*` untouched; `netprobe-migration-copy.db` the same size as `netprobe.db.bak`. On any `MD5 MISMATCH`, stop: do not run step 3. The test deletes the copy, so repeat this step before every re-run of `DirectorCopyMigration23Test`.

- [ ] **Step 3: The migration tests first (they install the new APKs but never open `netprobe.db`)**
```bash
"$ADB" -s $DEV shell run-as $PKG ls -l databases/ > "$BK/before-tests.txt"
run com.northwalessolutions.netprobe.data.ToolMigrationTest
run com.northwalessolutions.netprobe.data.DirectorCopyMigration23Test
"$ADB" -s $DEV shell run-as $PKG ls -l databases/
```
Expected: both pass. `ToolMigrationTest` covers the 2-to-3 case and the 1-to-3 chain on synthetic data; `DirectorCopyMigration23Test` reports 1 test (not an assumption failure, which would mean the copy was not found) and proves the Director's own schema 2 data migrates with every mark, note, host and port. `netprobe.db` and its `-wal` have the same size and time as in `before-tests.txt`; the copy is gone (the test deletes it). If either fails, stop: do not open the app.

- [ ] **Step 4: In-place migration of the real database (install over, never clean)**
```bash
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3b/q00-after-migration.png
"$ADB" -s $DEV logcat -d | grep -E "AndroidRuntime|FATAL|IllegalStateException: Migration|Room" | tail -10
```
Check on screen: every host the Director marked shows its mark; open two marked hosts and confirm their notes, custom names and open ports. Record pass or fail. If anything is missing, stop and tell the Director; the restore procedure below is his call.

- [ ] **Step 5: The rest of the instrumented suite** (DAO and Compose tests use in-memory databases or fake state; 3A's classes run again because 3B edits the screens they cover):
```bash
for c in data.MigrationTest data.RadarIdentityGuardTest data.ToolDaoTest data.DaoTest data.RemoveDeviceTest \
         ui.tools.ToolsBlockTest ui.tools.DirBruteSheetTest ui.tools.ToolResultsSheetTest ui.wordlists.WordlistsScreenTest ui.settings.SettingsToolsTest \
         ui.home.RadarBlockTest ui.home.HomeContentTest ui.home.RadarStateTest ui.detail.DetailContentTest \
         ui.NavTransitionTest ui.ControlsMotionTest ui.ListMotionTest ui.settings.SettingsRowsTest ui.settings.ExtendedSectionTest \
         ui.theme.TypographyTest ui.KeepScreenOnTest; do
  run com.northwalessolutions.netprobe.$c
done
run com.northwalessolutions.netprobe.ui.HomeScreenTest   # last (plan review G26): it launches the real activity, which opens netprobe.db and runs markInterrupted
```
`data.MigrationTest` is 3A's 1 to 2 test, which now also opens through the version 3 builder, so it is a real regression check; `data.RadarIdentityGuardTest` is 3A's F1 identity guard, on the branch after step R. `NeighbourSpikeTest` and 3A's `DirectorCopyMigrationTest` stay out on purpose (a spike, and a schema 1 copy test whose source is no longer on the phone); say so in the record.

Expected: every class passes. Record each class's count. A class name that does not exist on the branch (3A renamed or merged it) is recorded and skipped, not invented. Timing tests on a real frame clock that fail are recorded with their message, not retried into a pass and not relaxed.

- [ ] **Step 6: Screen recordings** (10 to 20 s each):
```bash
rec() { "$ADB" -s $DEV shell screenrecord --time-limit "$2" /sdcard/$1.mp4; "$ADB" -s $DEV pull /sdcard/$1.mp4 docs/qa/shots/phase3b/$1.mp4; "$ADB" -s $DEV shell rm /sdcard/$1.mp4; }
```
1. `rec r1-brute-force 20`: a directory brute-force of the CHNAS on 5000 with common-short from the Tools block: chips, the options sheet, the run, findings arriving, Stop ending it within about a second.
2. `rec r2-browser-roundtrip 20`: during a brute-force, "Open with..." a found path, read it for 60 s, return; the run continues and ends DONE with no counted errors (Review Focus 1).
3. `rec r3-results 15`: the results sheet, the run switcher, Share.
4. `rec r4-home-running 15`: start a run, go Home: the row shows "running"; Quick scan all skips it and says so; Forget this network is disabled while it runs; the end message on Home's notice line.

- [ ] **Step 7: Screenshots** (`"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3b/<name>.png`, at font scale 1.0 and again at 1.3 via `"$ADB" -s $DEV shell settings put system font_scale 1.3`, restored to 1.0 afterwards):
5. `s05-tools-collapsed` and `s05b-expanded` the Tools block.
6. `s06-options` the brute-force options sheet (footer and Run pill visible at 1.3 with the body scrolled).
7. `s07-fingerprint` the HTTP fingerprint results on the Pi-hole naming Pi-hole; `s07b-identity` the identity line with the `toolHint` reason.
8. `s08-tls` the TLS certificate results on 5001 (subject, SANs, expiry, self-signed, "as <name>").
9. `s09-smb` the SMB session results on the CHNAS.
10. `s10-snmp` the SNMP walk interfaces and addresses.
11. `s11-wordlists` the Wordlists screen with an imported list.

- [ ] **Step 8: Behaviour checks, each recorded pass or fail** (spec 12.3 items 1 to 17):
- 1 Install over the 3A build; existing data survives (step 4).
- 2 CHNAS brute-force common-short and defaults: DSM paths appear (for example `/webman`), catch-all noise filtered, Stop within about a second; a second run with admin-panels finishes. Times against 5.7.
- 3 Mid-run browser round trip: continues, ends DONE, never "The server stopped answering" (Review Focus 1).
- 4 CHNAS HTTP fingerprint on 5000 names Synology DSM and the identity line shows Synology NAS with a `toolHint` reason; TLS certificate on 5001 shows subject, SANs, expiry, the self-signed flag, and "as <name>".
- 5 CHNAS SMB session: dialect, signing, anonymous, guest against the 6.3 table and the DSM guest setting.
- 6 The Director's Windows PC, if SMB on 445: SMB session against the Windows rows.
- 7 CHNAS or UniFi, if either answered SNMP: SNMP walk lists interfaces and addresses.
- 8 Pi-hole admin: HTTP fingerprint names Pi-hole; a brute-force with admin-panels finds `/admin`.
- 9 UniFi gateway: TLS on 443; a brute-force with api-paths finishes with its catch-all redirect filtered. His gateways are UniFi Security Gateways (title "UniFi Security Gateway", certificate `UbiquitiRouterUI`), and the `unifi-os` fingerprint rule is `^UniFi OS$`, so on a USG expect "nginx" or "No product named" unless a `unifi-usg` fingerprint rule (final review M4) has landed; that is not a tool failure. "UniFi OS" is expected only on the UniFi OS console at 10.0.0.6.
- 10 Hue bridge off-subnet (`10.0.4.20`, extended on): fingerprint names Philips Hue; a brute-force completes without stalling (adaptive concurrency); the run is bound to Wi-Fi.
- 11 Start a brute-force, go Home: the row shows "running"; Quick scan all skips it and says so; Forget this network is disabled until the run ends (plan review G9); the end message on Home.
- 12 Import a wordlist on the phone; check name, count, date; run with it; delete it and check the saved options fall back with the notice.
- 13 Switch Wi-Fi mid-run: it stops with "Network changed, run stopped".
- 14 Results: Copy copies the URL; Share produces the 7.5 text.
- 15 Force-stop mid-run and relaunch: the run shows "Interrupted" with its findings.
- 16 Remove a device with tool history, Undo: the history is back. Remove again and let the Undo lapse: the history is gone. This item is destructive (the lapse permanently deletes the host's mark, note, custom name and tool history), so it follows 3A Task 19's destructive-check rule: run it on a disposable network only (the phone's own hotspot from a second device, or a second Wi-Fi the Director does not keep, never the home network), on a throwaway host with at least one tool run, and never on CHNAS. If no disposable network is available, record item 16 as pending, never as passed. Record which network and host were used. Items 13 (switch Wi-Fi) and 15 (force-stop) are not destructive and run on the home network.
- 17 Font scale 1.3: the Device screen budget (8.9) and the options sheet footer.
- 18 A printer, if he has one: an HTTP fingerprint and a brute-force with common-short (the most fragile embedded web server on a LAN, so a good adaptive-concurrency check), and an SNMP walk if discovery got an answer to `public`.
- Janky frames under 5 percent during a brute-force with the results sheet open:
```bash
"$ADB" -s $DEV shell dumpsys gfxinfo $PKG reset
# run a brute-force with the results sheet open until it ends
"$ADB" -s $DEV shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames"
```

- [ ] **Step 9: Record and hand over**

Write `docs/qa/phase3b-on-device.md`: the date, build commit, every step's result, the test counts per class, the screenshot and recording names, the gfxinfo numbers, the 5.7 timings (correct the table if off by more than 50 percent), and for the Director to rule on: the spec section 13 assumptions (1 to 15), the section 14 open items, the plan deviations and rulings listed in the Self-Review Notes, and these two plan-review items:
- **Tools leave traces on the hosts they probe (plan review G18).** The SMB session probe's anonymous and guest SESSION_SETUP attempts are logon attempts: a Windows host records them in its Security event log (event 4625 for a refused logon, 4624 for an accepted anonymous or guest one), and a Synology NAS logs them in its connection log. A directory brute-force fills the target's web access log with hundreds of 404s, and the HTTP fingerprint and TLS certificate tools appear there as ordinary requests. Nothing is changed on the host, but on a client's network these entries are visible to its administrator and can trip an intrusion alert, so run the tools on client hosts only where the engagement allows it.
- **Tool chips start runs from inside the scrolling list (plan review G25).** Spec 8.1 puts the Run-tool chips in the Tools block, which is the first item of the scrolling port list, while the standing rule says Run and Stop controls never live in a scroll container. Stop is pinned (the scan bar pill), which is what the rule protects, so the plan follows the spec; the Director rules whether the chips may stay where they are.

Then commit only the QA record and its shots:
```bash
git add docs/qa/phase3b-on-device.md docs/qa/shots/phase3b
git status --short    # nothing else may be staged
git commit -m "Phase 3B on-device QA record"
```
The backups and the rollback APK in `K:/claude/netprobe_db_backup/phase3b/` are never committed.

**Restore procedure (only on the Director's say-so).** This puts the schema 2 build and the step 0 backup back together. All three database files are replaced with the app stopped, because a stale `-wal` or `-shm` next to a restored file can corrupt it:
```bash
"$ADB" -s $DEV shell am force-stop $PKG
"$ADB" -s $DEV install -r -d "$BK/netprobe-schema2-${BASE:0:7}.apk"   # -r keeps the data directory; -d allows going back to the older build
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do "$ADB" -s $DEV shell run-as $PKG rm -f databases/$f; done
for f in netprobe.db netprobe.db-wal; do put "$BK/$f.bak" databases/$f || break; done
"$ADB" -s $DEV shell run-as $PKG ls -l databases/
ls -l "$BK"
```
Check: `MD5 OK` for both files before going on. On any `MD5 MISMATCH`, do not start the app: run the same `put` again for that file, and if it still mismatches, stop and tell the Director (the backup on the PC is intact). Only then:
```bash
"$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity
```
Expected: `netprobe.db` and `netprobe.db-wal` the same sizes and MD5s as their backups; the app opens on the schema 2 build (`87ab3c1`) with every mark and note. Record what was restored and why.

---
## Plan Self-Review Notes

**Spec coverage, by section:**
- Section 2 scope: the framework (2.1 item 1) is Tasks 3, 4 and 6; brute-force with wordlists (item 2) Tasks 8, 9 and 10; the four companions (item 3) Tasks 11 to 15; the raw-socket `HttpClient` (item 4) Task 5; the Wordlists screen (item 5) Task 19; identification hints (item 6) Task 7; schema 2 to 3 (item 7) Task 1; the Home "running" marker (item 8) Task 19. Non-goals (2.2) are honoured: no recursion, proxies, custom Host, scheduling, keep-alive, SNMPv3, SMB past SESSION_SETUP, downloads, wordlist editing or history clearing. Build constraints (2.3) are in Global Constraints.
- Section 3 decisions: one framework (Tasks 3, 6); Tools block plus port-sheet group (Task 17); one run per host, four at once, a 16-thread view (Tasks 3, 6); `HostBusyRegistry` consulted by Quick scan all, the Detail scan and the runner (Tasks 4, 6, 16, 19); runs on `appScope` (Task 6); pause on ON_STOP (Tasks 4, 6); app-level messages (Tasks 4, 17, 19); Stop only in the pinned bar (Task 17); cancellation through `CancellableIo` (Task 3); raw-socket client (Task 5); binding through `Probe.connect` and `ScanBinding.run` (Tasks 5, 6, 12, 13, 15, pinned by `BindingPropagationTest` in Task 15); adaptive concurrency and 100 per second (Tasks 4, 10); calibration (Task 10); HEAD then GET (Task 10); wordlist source and storage (Tasks 8, 9); two tables (Task 1); 10 runs (Task 2); 500 findings (Task 10); options only for brute-force (Task 18); hints per (tool, port) (Tasks 2, 7); SNMP gate (Task 3); SMB depth (Task 15); name sent (Tasks 3, 11, 12, 16); removal and Undo (Task 2, deviation 1); export (Task 16).
- Section 4: 4.1 Task 3; 4.2 Task 3; 4.3 Tasks 4, 16, 17, 19; 4.4 Task 6; 4.5 Tasks 5, 12, 13, 15; 4.6 Tasks 3, 5; 4.7 Tasks 2, 7; 4.8 Tasks 3 (`excusedOnce`), 4, 6, 10, 11, 12, 13, 15; 4.9 Tasks 3, 11, 12, 16.
- Section 5: 5.1 Tasks 10, 16, 18; 5.2 Task 10; 5.3 Tasks 4, 10; 5.4 Task 10; 5.5 Tasks 3, 10; 5.6 Tasks 8, 9, 19; 5.7 Task 20.
- Section 6: 6.1 Task 11; 6.2 Task 12; 6.3 Tasks 14 and 15; 6.4 Task 13.
- Section 7: 7.1 Tasks 1, 6, 10, 16; 7.2 Task 2 (with the REPLACE guard test); 7.3 Task 1; 7.4 Task 2 (deviation 1); 7.5 Task 16.
- Section 8: 8.1 to 8.5 Tasks 17 and 18 (model in Task 16); 8.6 Task 19; 8.7 Task 19; 8.8 Task 19; 8.9 Tasks 17, 19 and Task 20 step 8.
- Section 9: every string is a `ToolCopy` or `WordlistCopy` member (Task 3), pinned by `ToolCopyTableTest`, which runs every helper over an input matrix and also rejects em-dashes.
- Sections 10 to 12: the data flow is realised across Tasks 6, 16, 17, 19; the edge cases 11.1 to 11.16 map to Tasks 5 (11.2 HTTP), 6 (11.3, 11.4, 11.5, 11.12), 9 (11.9, 11.10, 11.11), 10 (11.1, 11.6, 11.7, 11.8), 11 to 15 (11.2, 11.13, 11.14), 15 (11.15), 17 (11.16); the JVM tests of 12.1 are in each task, the 12.1 binding-propagation test is Task 15's `BindingPropagationTest`, the instrumented tests of 12.2 are compiled in Tasks 1, 2, 17, 18, 19 and run in Task 20, and 12.3 is Task 20 step 8. Sections 13 to 15 go to the Director in the Task 20 record.

**Placeholder scan (re-run after the Gruff plan review pass):** `grep -nE "TBD|TODO|similar to Task|implementer (completes|finishes|adds|tops)|stub|placeholder|no-op placeholder|<[a-z ]+>" docs/superpowers/plans/2026-10-20-netprobe-phase3b.md` was run on the assembled file. Every remaining hit is one of these, and none is unwritten code: (a) the empty `reidentifyFromTools` function and the empty `toolMap()` body in Task 6, which Tasks 7, 10 to 13 and 15 fill in order (until then no tool is registered, so neither is reachable); (b) the word "stub" in Task 1's minimal `ToolDao`, which Task 2 replaces with the full DAO; (c) the Compose `placeholder =` parameter of the extensions field in Task 18; (d) test-tag and copy patterns written with angle brackets (`toolChip:<toolId>:<port>`, "as <name>"), which name a value, not missing code; (e) this note. Every task gives its failing tests, complete production code, exact names, exact commands, the gate and an explicit staging list. Every composable is written in full in Tasks 17, 18 and 19; the SMB2, NTLM and SPNEGO byte layouts, the direct-TCP framing and the SNMP UDP exchange are written in full in Tasks 13, 14 and 15, each request pinned to bytes written out in a test and each response fixture laid out by hand.

**Type consistency:**
- `ToolRunStatus` (Task 1) is used by `ToolResult` (Task 3), `ToolStore` and the runner (Task 6), `ToolCopy.statusLabel` (Task 3) and the UI model (Task 16). `FindingKind` and `ToolId` (Task 3) are stored as TEXT keys (`kind`, `toolId`) and parsed back with `FindingKind.parse` / `ToolId.byKey`.
- `ToolOptions` ships with `None` in Task 3; Task 10 adds `DirBrute(options, wordlistName, wordlistEntries)` and extends `ToolOptionsJson` (Task 6); `ToolRunOptions.parse` (Task 16) reads every field back with defaults, and `ToolRunOptionsTest` round-trips the encoder into the decoder.
- `ToolStore` (Task 6) is implemented by `ToolRepository` (Task 2, `override`s added in Task 6); `ready` completes in a `finally` (Task 2), so a failed `markInterrupted` can never block `start`.
- `CancellableIo.closing` (Task 3) is used by `HttpClient` (Task 5), `TlsPeek.details` (Task 12), `UdpSnmpExchange` (Task 13) and `TcpSmbTransport` (Task 15); none names `Dispatchers.IO`.
- `HttpClient.request(..., hostName = null, sslContext = null)` (Task 5) is called with both arguments by Tasks 10 and 11 and with defaults by the Task 5 tests; `HttpClient.trustAllContext()` is reused by Task 12.
- Injected I/O per tool: `Exchange` (Task 10), `HttpGet` (Task 11), `peek: suspend (ToolTarget, String?) -> TlsDetails?` (Task 12), `SnmpExchange` with `getNext` and `get` (Task 13), `SmbTransport` with `exchange` and `close` (Task 15). Each `AppContainer.toolMap()` entry builds the production implementation.
- The tool test helpers are `DirBruteTestSupport` (package `dirbrute`, Task 10) and `ToolTestSupport` (package `engine.tools`, Task 11, with `EpochControl` for the pause tests, reused by Tasks 12, 13 and 15); `ToolRunnerTestSupport` serves Task 6 and Task 15's `BindingPropagationTest`.
- `ToolProgress.option(key, value)` (Task 3, default no-op) is implemented by the runner (Task 6) through `ToolStore.setOptions`, which `ToolRepository.setOptions` (Task 2) implements over `ToolDao.setOptions`; `ToolOptionsJson.withOption` (Task 6) patches the stored JSON. `RunControl.excusedOnce` (Task 3) is called by Tasks 11, 12, 13 and 15.
- `ToolRepository.hostsWithRuns(): Set<Long>` (Task 2) feeds the per-scan cache in `HomeViewModel.discoverOn` (Task 7).
- `HostEvidence.toolHints` and `HostEvidenceBuilder.build(..., toolHints)` (Task 7) have defaults, so every 3A call site compiles; the four identify paths pass `hintsFor`. `RadarCollector`'s new `hintsFor` parameter sits before `removed`, checked against the branch: `HomeViewModel.kt` line 213 and 3A's `RemoveDeviceTest.collector()` both pass `removed` as a trailing lambda and compile unchanged.
- `DetailToolsState` and `ToolActions` (Tasks 16, 17) are new `DetailContent` parameters with defaults, so every 3A `DetailContentTest` call still compiles; `HostRow.running`, `HomeUiState.appMessage`, `HomeUiState.toolRunningOnNetwork`, `Notice.app`, `NoticeArea(onDismissApp)` and `HomeActions.onDismissApp` (Task 19) all have defaults, so 3A's Home tests compile unchanged.
- `ScanPill(stopDescription)`, `ScanBar(toolBar, onStopTool)`, `PortSheet(tools, toolsBusy, onTool)` and `DetailMenu(removeEnabled)` (Task 17) add defaulted parameters to 3A composables.
- Checked against the committed 3A code on the branch: `DetailViewModel.removeDevice` (commit aeb3be3: `removedHostIds` marked first, `radarScanActive` pairing, marks cleared in a `finally` inside `hostWriteLock`), which Task 2 extends without rewriting; `Probe.connect(ip, port, timeoutMs, keepOpen, binder)`, `BoundNetwork`, `SocketBinder` and `currentBinder()` as Task 15's binding test uses them; the Home `forgetEnabled` expression and `forgetNetwork` guard that Task 19 extends.
- `ActionPill` (Task 17) is reused by the options sheet (Task 18) and the Wordlists screen (Task 19); `StepSlider`, `SwitchRow` and `Section` are 3A/MVP `internal` composables in `ui/settings`, visible module-wide.

**Deviations flagged for the Director (3B spec vs 3A plan or code; the 3A plan wins):**
1. **Forget this network takes no tool snapshot (3B spec 7.4 vs 3A plan).** Spec 7.4 assumes Forget deletes hosts at once and needs a TEMP-table snapshot. The 3A plan makes Forget a soft delete that deletes only when the Undo commits, so tool rows survive the Undo window and the commit's cascade removes them for good. Only Remove device snapshots tool rows (Task 2).
2. **`ToolStore` interface.** The spec names `ToolRepository` in the runner's constructor; the runner depends on a `ToolStore` interface that `ToolRepository` implements, to meet the spec's own "fake repository" test requirement without Room on the JVM.
3. **`markInterrupted` at first use of the runner.** It is the only startup database work, launched on `appScope` when the `toolRunner` lazy is first read, a documented exception to 3A's "building `AppContainer` touches no database".
4. **Withdrawn (plan review G8).** `optionsJson.methodUsed` now follows spec 5.2: the calibration switch writes GET_ONLY back through `ToolProgress.option` and `ToolRepository.setOptions` (Tasks 2, 6, 10), so the export's "GET only (HEAD and GET disagree)" is reachable. The 405/501 fallback switch does not write it, as the spec names only the calibration switch.
5. **The bundled wordlists are one globally sorted file each** (298, 153 and 137 entries after plan review G10 removed the action endpoints), not per-category blocks, so the order is machine-checked by `BundledWordlistsTest` and reproduces no published list's ordering. Per-category order remains a reviewer concern only.
6. **3A test edits.** Task 7 changes 3A's `DeviceIdentifierTest` rule count (118 to 132) and gateway-index assertions, and adds a `toolHint` case to `SyntheticEvidence`.
7. **Minimal `ToolDao` in Task 1**, replaced wholesale in Task 2, so Task 1 can export `3.json` on its own.
8. **SMB verification.** The SMB2, NTLM and SPNEGO messages are pinned to bytes written from the specifications; the crypto is checked against published vectors (RFC 1320, MS-NLMP 4.2.2 and 4.2.4) that I transcribed from memory. If any published-vector assertion fails, the implementer stops and reports it; the vector is never edited, and the guest leg (the only NTLMv2 user) would then be removed as a recorded reduction. No reduction was needed to write the plan: the full session probe (NEGOTIATE, anonymous and guest SESSION_SETUP) is specified. The Task 20 device check is the real proof, as the spec says.
9. **The results header's "as <name>"** shows the name the run attempted (from `optionsJson`), even when the TLS handshake fell back to the IP.
10. **The rate bucket admits its burst on top of the rate**, so a fresh run can make up to 110 requests in its first second and then holds 100 per second; `RateBucketTest` pins this. The spec's "at or under 100 in every 1 s window (burst 10)" cannot hold literally with a burst; the Director may prefer no initial burst.
11. **`SnmpExchange` has `get` as well as `getNext`, and `SnmpStep` has `Error`**, because the ifHighSpeed substitution is a GET and an error-status reply must end a step (spec 6.4, 12.1).
12. **Tools read their summaries and finding details from `ToolCopy`** (package `ui.tools`), so the engine imports a UI copy object. It holds no Compose code, so the dependency is harmless, and it is the only way to keep every user-visible string in one table.
13. **`ToolOptions.DirBrute` carries `wordlistName` and `wordlistEntries`**, so `optionsJson` names a list after it is deleted (spec 7.1 requires the fields; the spec's type had no slot for them).
14. **Quick scan all skips a host held by another port scan silently**; only tool-held hosts are counted in "skipped: a tool is running".
15. **The server name is sanitised**: trailing dot removed, and a name that is not DNS-shaped is not sent.
16. **The Quick scan all end message's old "device(s)" wording** is replaced by `ToolCopy.quickEnd`'s plural forms (spec 9).
17. **Spec file location.** The task brief named the Gruff review under `plans/`; it is `docs/superpowers/specs/2026-10-20-phase3b-gruff-review.md`.
18. **Binding propagation is tested at the socket seams, not through `AppContainer.toolMap()` (plan review G6, a recorded reduction).** `toolMap()` is private and needs an Android `Context`, so a JVM test cannot build it. `BindingPropagationTest` drives the exact four calls the factories make (`HttpClient.request`, `TlsPeek.details`, `UdpSnmpExchange`, `Probe.connect` into `TcpSmbTransport`) under a recording binder, and a refusing binder through the real runner. A factory that bypassed those seams would not be caught; the review checklist covers that.
19. **Brute-force reads the pause epoch before the first gate**, so a pause that begins while a path waits at the gate also excuses that path's failure: at worst one uncounted extra try, never a counted error the Director caused.
20. **Tasks 14 and 16 of the first draft are split (plan review G16).** SMB is Tasks 14 (MD4, NTLM, SPNEGO) and 15 (SMB2, framing, the probe); the Device screen is Tasks 17 (Tools block, pinned bar, port sheet) and 18 (the two sheets). The plan now has 20 tasks, the deferred device QA last.
21. **For the Director's ruling, also listed in the Task 20 hand-over:** the Run-tool chips start runs from inside the scrolling port list as spec 8.1 places them, while Stop stays pinned (plan review G25); and the SMB and brute-force tools leave visible entries in target logs, including Windows Security events 4624/4625 (plan review G18).

**Review Focus mapping:**
1. Frozen browser trip: Task 6 `ToolRunnerPauseTest` (all three, driven with `runCurrent`), Task 3 `ExcusedOnceTest`, Task 10 `DirBruteFlowTest.an excused failure is retried after the resume and never counted`, the pause tests of Tasks 11, 12, 13 and 15; device check Task 20 step 8 item 3.
2. Not-found filter: Task 10 `DirBruteCalibrationTest` (nonce band, path echo, redirect, extension group, HEAD-404/GET-200 with its `methodUsed` write, three responses, failed calibration) and `DirBruteRunawayTest` (three).
3. IO starvation: Task 3 `CancellableIoTest` (four, including the caller's dispatcher).
4. Hints per (tool, port): Task 2 `ToolHintsRuleTest` (four) and instrumented `ToolDaoTest.hintsForPicksPerToolAndPort`; Task 7 `DeviceIdentifierToolHintTest`.
5. Additive migration: Task 1 `ToolMigrationSqlTest` (three), `SchemaExportTest` version-3 cases; instrumented `ToolMigrationTest` (two) and `DirectorCopyMigration23Test` on a copy of the Director's own database, both run first in Task 20.

## Gruff plan review response

One line per finding of `docs/superpowers/plans/2026-10-20-phase3b-plan-gruff-review.md`. Applied unless marked otherwise.

- G1 (BLOCKING) Applied. Task 2 extends the committed `removeDevice` (aeb3be3) in place: the tool snapshot is taken before `hostWriteLock`, `removedHostIds` is still marked first, the `radarScanActive` pairing is kept, and the restore runs inside the lock with the marks cleared in its `finally`.
- G2 (BLOCKING) Applied. Every `ToolRunnerTest` and `ToolRunnerPauseTest` step uses `runCurrent()` (with `advanceTimeBy` for the ticker and the pause clock); the Task 6 note records why and that each test fails on its bug.
- G3 (BLOCKING) Applied. `attempt` calls `control.gate()` before the HEAD and before the GET; `DirBruteFlowTest` adds the HEAD-200 catch-all rate test (at most 110 in the first second, at most 100 in each later second).
- G4 (BLOCKING) Applied. `HttpClient` sets `soTimeout` on the raw socket before the TLS handshake (the connect is bounded by `Probe.connect`'s timeout); a stalled handshake test is added.
- G5 Applied. `RunControl.excusedOnce` (Task 3, with `ExcusedOnceTest`) wraps every exchange of the fingerprint, TLS, SNMP and SMB tools, with one fake-epoch test per tool.
- G6 Applied, with a recorded reduction (deviation 18). `BindingPropagationTest` in Task 15 checks all four socket seams under a recording binder and a refusing binder through the runner.
- G7 Applied. `DirectorCopyMigration23Test` (Task 1) migrates a copy of the Director's schema 2 database; Task 20 pushes the copy, runs it before the in-place install, builds the rollback APK from `phase3b-base.txt` and writes the restore commands in full.
- G8 Applied. `ToolProgress.option`, `ToolStore.setOptions` and `ToolRepository.setOptions` write `methodUsed` after the calibration switch; deviation 4 is withdrawn.
- G9 Applied. `HomeUiState.toolRunningOnNetwork` disables Forget this network and `forgetNetwork` returns early while a tool holds a host of the shown network, with a `HomeContentTest` case.
- G10 Applied. The action endpoints (`reboot.html`, `upgrade.html`, `apply.cgi`, `setup.cgi`, `webproc`, `cgi-bin/webproc`, `boaform/admin/formLogin`, `logout`, `save`) are removed and `BundledWordlistsTest` enforces a deny pattern.
- G11 Applied. Task 8 is rebuilt: the stray list is gone, Step 1 is restored, and all three lists are re-sorted with `LC_ALL=C` and machine-checked.
- G12 Applied. The `withEntries` cancellation test uses a closing-tracking stream, a delete-during-read test and a 200,001-entry TooMany case are added, the limiter test asserts the peak drops to 4 after a timeout, and the Host header, drip and keep-open cases are added to `HttpClientTest`.
- G13 Applied. `RadarCollector.hintsFor` sits before `removed`, and `discoverOn` passes hints only for hosts in a per-scan `hostsWithRuns()` set.
- G14 Applied. `ToolRunner.start` launches the job LAZY inside `startLock`, assigns it, registers the run and starts it there.
- G15 Applied. The response stream is buffered and `line()` stops at the header cap even with no line feed; a 1 MB header-line test is added.
- G16 Applied. Tasks 14 and 16 are each split in two; the plan has 20 tasks and every cross-reference was renumbered.
- G17 Applied. Task 1 Step 0 checks 3A is finished and records the base commit; Tasks 17 and 19 open with a Step 0 that re-reads the 3A files they edit.
- G18 Applied. The Task 20 hand-over tells the Director about the logon events and access-log entries the tools leave.
- G19 Applied. `SmbFixtures` lays out every response header and the negTokenResp DER by hand; `NtlmFixtures` holds the CHALLENGE.
- G20 Applied. Both `WordlistStoreTest` calls pass `.byteInputStream()`.
- G21 Applied. Every commit step stages its task's files by path and checks `git status --short`; no `git add -A` remains.
- G22 Applied. A finding claims its slot with `found.incrementAndGet()` first and is dropped above 500.
- G23 Applied. `load()` deletes `*.tmp`, and import and delete hold a `Mutex` around the index read-modify-write, with a concurrent-import test.
- G24 Applied. `.DS_Store` and `.ssh/known_hosts` replace the misspelt entries.
- G25 Applied as a hand-over item: the chips stay where spec 8.1 puts them and the question goes to the Director in Task 20.
- G26 Applied. The Global Constraint now says only `HomeScreenTest` reaches the production `toolRunner`, and Task 20 runs it last, after the in-place migration.
