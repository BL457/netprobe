# NetProbe Phase 3A Implementation Plan: schema 2, port-scan identification, Open with..., manual details, follow-ups, motion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Director's four requests of 2026-10-20 plus his Phase 2 copy items: a radar that reads as a radar and quiet motion elsewhere; "Open with..." and Try as on every port, with the right primary on HTTP served from an unexpected port; port-scan findings (titles, headers, certificates, banners, confirmed services) used as identification evidence, with a budgeted active probe ladder on Full and Custom scans only; an Edit device dialog for name, type, vendor and model; Forget this network and Remove device with Undo; "Stopped after N s"; stale dimming that survives a restart.

**Architecture:** Same single module and MVVM shape. One Room migration (schema 1 to 2, twelve nullable columns) lands first so every later task builds on v2. Engines stay pure and fake-driven: `ServiceIdentifier` gains a `ProbeMode` gate, an `ActiveProbeBudget` and close-on-cancel sockets; `DeviceIdentifier` gains port-evidence fields, `onPorts` and `software`; `ServiceMerge` is the one never-erase merge used by the repository and the evidence builder. UI changes stay in the stateless `HomeContent` / `DetailContent` split, with pure text and rule helpers JVM-tested; motion uses Compose animation APIs only, and every per-frame draw has its own layer.

**Tech Stack:** Unchanged: Kotlin 2.1.21, AGP 8.10.1, Gradle 8.14, JDK 17, Compose BOM 2025.06.00 (foundation 1.8.2, material3 1.3.x), navigation-compose 2.9.0, Room 2.7.1 with KSP, kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.8.1, JUnit 4, Turbine, AndroidX test, room-testing (already an `androidTestImplementation`). No additions.

**Spec:** `docs/superpowers/specs/2026-10-20-phase3a-polish-design.md` at fd110b8 is binding. Its Gruff review (`2026-10-20-phase3a-gruff-review.md`, ad01848) explains the reasoning; where review and spec differ, the spec wins. The Radar spec (`2026-10-19-radar-ui-design.md`) still owns every token and string this phase does not change.

**Roles:** Tasks 1 (migration), 5 (probe ladder and budget) and 16 (radar redesign with its own layers) need the stronger model for implementation and for review. Every other task is standard.

## Global Constraints

- Kotlin 2.1.21, AGP 8.10.1, Compose BOM 2025.06.00, Room 2.7.1 KSP with exported schema (v1 to v2 in this plan), targetSdk 30.
- No new dependencies, no version bumps, no new repositories, no Gradle plugins.
- No downloads or installs of anything by any agent or subagent. If Gradle prints `Installing Android SDK ...` or `Checking the license for package`, stop and ask the Director.
- No em-dashes anywhere: code, comments, UI strings, docs, commit messages. Use hyphens, commas or colons.
- No attribution lines in commits: plain messages, no `Co-Authored-By` or any other trailer.
- No disclaimers or prompts in UI copy. No "are you sure" dialog; Forget and Remove act at once and offer Undo.
- Only the Stop pill stops a radar scan.
- Scan / Stop (the radar ring, the HERO Scan circle, the Stop pill) and the Detail scan bar are never inside a scroll container.
- Every string lives in `HomeCopy`, `DetailText` / `DetailCopy` or `EditCopy`, and has a template in `CopyTableTest`; regex templates use doubled backslashes (`"\\d+"`).
- Block bodies (`{ ... }`) for every function in which `return` appears; no `return` inside an expression body.
- Every instrumented run passes `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, so NetProbe is never uninstalled and the Director's data survives.
- THE PHONE IS NOT AVAILABLE during Tasks 1 to 17. Every task is built and tested on the JVM. Compose UI tests and the migration tests are written in full and must compile (`./gradlew :app:compileDebugAndroidTestKotlin`), but are not run. No task may claim device evidence. Task 18 lists every instrumented run, screenshot and recording; it is executed later, when the Director returns.
- No instrumented test may open, create or delete a database named `DB_NAME` ("netprobe.db"). Migration tests use `MIGRATION_TEST_DB` ("netprobe-migration-test.db") or the pushed copy "netprobe-migration-copy.db"; DAO tests use in-memory databases.
- All shell commands are Git Bash syntax, run from `K:/claude/netprobe`, using `./gradlew`, never a global `gradle`. Never kill python or java processes wholesale.
- Standard commands used by every task:
  - JVM, one class or package: `./gradlew :app:testDebugUnitTest --tests "<pattern>" --console=plain 2>&1 | tail -20`
  - androidTest compile only: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8`
  - Task gate, every task ends with it green: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6`, expected `BUILD SUCCESSFUL`.

## Review Focus

The five failure modes most likely to bite the Director. Each is pinned to a test in its owning task; the reviewer checks the test exists, fails on the bug, and was not bent to fit the code.

1. **A raw print port receives an active probe and prints junk.** 9100 to 9107, 515, 2000/2001, 6000 to 6063 and the greet-first mail ports get the passive read only, in every profile; 631 gets one HEAD in Full and Custom and never a ClientHello or a GET. Pinned: Task 5 `ServiceIdentifierLadderTest.passive only ports never receive a byte in either mode`, `ipp gets one head in active mode and nothing more`, and `passive only and head only sets are pinned`.
2. **The active budget is not honoured, so a Full scan of an "everything open" host runs for hours.** At most 64 ports enter the ladder and no step starts after 60 s of summed probe time; Quick and Quick scan all send nothing beyond today. Pinned: Task 5 `no more than 64 ports ever enter the ladder`, `no step starts once 60 s have been spent and refused ports stay open and unknown`, `eight parallel identifies overshoot by at most eight steps`, `passive mode sends nothing new to a silent unknown port`, `quick scan all is passive`.
3. **Identity flapping.** A radar re-identification or a timed-out rescan drops what a port scan learned, so the NAS reads Synology, then Unknown, then Synology again. Pinned: Task 3 `ServiceMergeTest.a port table result never replaces a confirmed name`; Task 7 `IdentityStabilityTest` (all three tests); Task 2 `IdentityWriteTest.identity guard replaces on equal or higher confidence only`; instrumented backstop Task 7 `DaoTest.portEvidenceSurvivesRediscoveryAndATimedOutRescan` (run in Task 18).
4. **Undo re-adds a host mid-scan, or Remove leaves orphan ports.** A running radar scan must skip the removed IP, Quick scan all must treat the cancelled host as skipped and carry on, and every new scan must commit (drop) a pending Undo first. Pinned: Task 13 `UndoStoreTest` (all four); Task 14 `RemovedHostsTest.a removed ip is skipped for the rest of the radar scan`, `QuickScanAllLoopTest.cancelling one host skips it and the loop carries on`, `ScanControllerJoinTest.stop and join waits for the scan's cleanup`; instrumented backstop Task 14 `RemoveDeviceTest` (run in Task 18).
5. **The migration loses marks and notes.** Schema 1 to 2 must be additive only, keep every v1 column exactly, and never touch the app's own database file during tests. Pinned: Task 1 `MigrationSqlTest` (all three), `SchemaExportTest.version 2 keeps every version 1 column unchanged`, `version 2 adds exactly the twelve nullable columns`; instrumented Task 1 `MigrationTest.marksNotesAndPortsSurviveTheMigration` and `DirectorCopyMigrationTest.theDirectorsCopyMigratesWithEveryMarkAndNote` (run in Task 18, the second against a copy of the Director's database).

### Review checklist (every UI task)

- Every node whose draw reads a per-frame value has its own layer (`Modifier.graphicsLayer()`), so a per-frame draw never re-records the disc display list or anything above it (spec 1.16, Gruff M1).
- No per-frame value is read during composition; per-frame values are read only in `graphicsLayer {}` blocks and `onDrawBehind` lambdas.
- `LocalReducedMotion` true makes every animation in the task instant (`snap()`, `EnterTransition.None`, static arm).
- No control that starts or stops a scan is placed inside a scroll container.

## File Structure

```
app/build.gradle.kts                                       androidTest assets from schemas/ (Task 1)
app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/2.json   generated by KSP and committed (Task 1)
app/src/main/res/raw/identify_rules.json                   57 new rules, 7 replaced (Task 7)
app/src/main/java/com/northwalessolutions/netprobe/
  AppContainer.kt          appScope, undo, hostScanJobs, removedDuringScan, radarScanNetworkId, purge at start (Tasks 13, 14)
  UndoStore.kt             PendingUndo, UndoStore, AppScopes (Task 13)
  data/        Entities.kt (v2 columns), Migrations.kt (MIGRATION_1_2, DB names), NetProbeDb.kt (v2, open(name)),
               Daos.kt, Repositories.kt (overrides, identity guard, Forget, snapshot), Overrides.kt (DeviceEdits, Effective,
               EditDraft, Detected, SoftwareColumn, IdentityWrite), PortMapping.kt (Tasks 1, 2, 3, 9)
  engine/common/      CloseOnCancel.kt (Task 4)
  engine/serviceid/   ServiceInfo.kt (six fields), ServiceMerge.kt, BannerParsers.kt (headers, RTSP, RDP), BannerReader.kt,
                      TlsPeek.kt (CertInfo), ActiveProbeBudget.kt (ProbeMode, budget, ProbeModes), ServiceIdentifier.kt (Tasks 3 to 5)
  engine/discovery/   HttpTitle.kt (poweredBy, open, close on cancel) (Task 4)
  engine/identify/    IdentifyRules.kt (onPorts, software), DeviceIdentity.kt (software, merge), DeviceIdentifier.kt (Tasks 2, 3, 6)
  actions/     PortUris.kt, ActionProvider.kt (Task 11)
  ui/          Controls.kt (ScanPill morph, PinnedSegmented fades, BackToHome trailing slot), Nav.kt (NetProbeNavHost) (Tasks 10, 17)
  ui/theme/    Motion.kt (Phase 3A tokens) (Task 15)
  ui/home/     RadarGeometry.kt (ArmTrail, afterglow, breath, blipAngleDeg), MotionRules.kt (PulseGate, RowStagger), RadarBlock.kt,
               RadarModes.kt (stopped summary), HomeCopy.kt, HomeContent.kt, HomeComponents.kt, HomeViewModel.kt, HomeStats.kt (StaleRun),
               QuickScanAll.kt (policy, loop), RemovedHosts.kt (Tasks 5, 12 to 17)
  ui/detail/   DetailCopy.kt, EditCopy.kt, EditDevice.kt, DetailText.kt, DetailContent.kt, DetailComponents.kt, DetailViewModel.kt,
               DetailScreen.kt (Tasks 3, 5, 8 to 14, 17)
app/src/test/java/...            JVM tests mirror the packages above
app/src/androidTest/java/...     MigrationTest, DirectorCopyMigrationTest, DaoTest additions, RemoveDeviceTest, RadarBlockTest,
                                 HomeContentTest, DetailContentTest, NavTransitionTest, MotionTest (compile only until Task 18)
docs/qa/phase3a-on-device.md     Task 18 record (written when the Director is back)
```

---

### Task 1: Schema 2: twelve nullable columns, MIGRATION_1_2, exported schema and the migration tests

**Roles:** stronger model (implement and review).

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`, `data/NetProbeDb.kt`, `app/build.gradle.kts`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt`
- Generated and committed: `app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/2.json`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/MigrationSqlTest.kt`, `SchemaExportTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/data/MigrationTest.kt`, `DirectorCopyMigrationTest.kt`

**Interfaces:**
- Consumes: `NetworkEntity`, `HostEntity`, `PortEntity`, `DeviceType`, `app/schemas/.../1.json` (identityHash `01cb3d4a14204fafc8d16708cf6b8f6a`, must stay untouched).
- Produces:
```kotlin
// data/Entities.kt (appended to each constructor, all defaulting to null)
NetworkEntity(..., val lastRunAt: Long? = null, val forgotten: Long? = null)
HostEntity(..., val userType: DeviceType? = null, val userVendor: String? = null, val userModel: String? = null, val identSoftware: String? = null)
PortEntity(..., val title: String? = null, val server: String? = null, val realm: String? = null, val poweredBy: String? = null,
           val certCn: String? = null, val certOrg: String? = null)
// data/Migrations.kt
val MIGRATION_1_2: Migration
const val DB_NAME = "netprobe.db"
const val MIGRATION_TEST_DB = "netprobe-migration-test.db"
// data/NetProbeDb.kt
@Database(version = 2, exportSchema = true) abstract class NetProbeDb
NetProbeDb.open(context: Context, name: String = DB_NAME): NetProbeDb
```

- [ ] **Step 1: Write the failing JVM tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/MigrationSqlTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** MIGRATION_1_2 run against a recording database: twelve additive, nullable columns and nothing else (spec 6, Review Focus 5). */
class MigrationSqlTest {
    private fun recorded(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String else fail("migration called ${method.name}, expected execSQL only")
            null
        } as SupportSQLiteDatabase
        MIGRATION_1_2.migrate(db)
        return sql
    }

    @Test fun `migration adds exactly the twelve columns in order`() {
        assertEquals(listOf(
            "ALTER TABLE hosts ADD COLUMN userType TEXT",
            "ALTER TABLE hosts ADD COLUMN userVendor TEXT",
            "ALTER TABLE hosts ADD COLUMN userModel TEXT",
            "ALTER TABLE hosts ADD COLUMN identSoftware TEXT",
            "ALTER TABLE networks ADD COLUMN lastRunAt INTEGER",
            "ALTER TABLE networks ADD COLUMN forgotten INTEGER",
            "ALTER TABLE ports ADD COLUMN title TEXT",
            "ALTER TABLE ports ADD COLUMN server TEXT",
            "ALTER TABLE ports ADD COLUMN realm TEXT",
            "ALTER TABLE ports ADD COLUMN poweredBy TEXT",
            "ALTER TABLE ports ADD COLUMN certCn TEXT",
            "ALTER TABLE ports ADD COLUMN certOrg TEXT",
        ), recorded())
    }
    @Test fun `every statement is an additive nullable column with no default`() {
        val additive = Regex("ALTER TABLE (hosts|networks|ports) ADD COLUMN \\w+ (TEXT|INTEGER)")
        for (s in recorded()) {
            assertTrue("not additive: $s", additive.matches(s))
            assertFalse("NOT NULL would need a default and a rewrite: $s", s.contains("NOT NULL", ignoreCase = true))
            assertFalse("defaults are not part of schema 2: $s", s.contains("DEFAULT", ignoreCase = true))
        }
    }
    @Test fun `versions and database names`() {
        assertEquals(1, MIGRATION_1_2.startVersion); assertEquals(2, MIGRATION_1_2.endVersion)
        assertEquals("netprobe.db", DB_NAME); assertEquals("netprobe-migration-test.db", MIGRATION_TEST_DB)
        assertNotEquals(DB_NAME, MIGRATION_TEST_DB)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/SchemaExportTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Reads the exported Room schemas; Android unit tests run with the module directory (app/) as the working directory. */
class SchemaExportTest {
    private val dir = File("schemas/com.northwalessolutions.netprobe.data.NetProbeDb")
    private fun root(version: Int): JsonObject = Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject
    /** table -> column -> field object. */
    private fun columns(version: Int): Map<String, Map<String, JsonObject>> =
        root(version)["database"]!!.jsonObject["entities"]!!.jsonArray.associate { e ->
            val o = e.jsonObject
            o["tableName"]!!.jsonPrimitive.content to o["fields"]!!.jsonArray.associate { f -> f.jsonObject["columnName"]!!.jsonPrimitive.content to f.jsonObject }
        }
    private val added = mapOf(
        "hosts" to mapOf("userType" to "TEXT", "userVendor" to "TEXT", "userModel" to "TEXT", "identSoftware" to "TEXT"),
        "networks" to mapOf("lastRunAt" to "INTEGER", "forgotten" to "INTEGER"),
        "ports" to mapOf("title" to "TEXT", "server" to "TEXT", "realm" to "TEXT", "poweredBy" to "TEXT", "certCn" to "TEXT", "certOrg" to "TEXT"),
    )

    @Test fun `version 1 schema is untouched and version 2 is exported`() {
        assertEquals("01cb3d4a14204fafc8d16708cf6b8f6a", root(1)["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content)
        assertEquals(2, root(2)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }
    @Test fun `version 2 keeps every version 1 column unchanged`() {
        val v1 = columns(1); val v2 = columns(2)
        assertEquals(v1.keys, v2.keys)
        for ((table, cols) in v1) for ((name, field) in cols) {
            val now = v2.getValue(table)[name]
            assertNotNull("$table.$name was dropped", now)
            assertEquals("$table.$name affinity", field["affinity"], now!!["affinity"])
            assertEquals("$table.$name notNull", field["notNull"], now["notNull"])
        }
    }
    @Test fun `version 2 adds exactly the twelve nullable columns`() {
        val v1 = columns(1); val v2 = columns(2)
        for ((table, expected) in added) {
            assertEquals("new columns of $table", expected.keys, v2.getValue(table).keys - v1.getValue(table).keys)
            for ((name, affinity) in expected) {
                val f = v2.getValue(table).getValue(name)
                assertEquals("$table.$name affinity", affinity, f["affinity"]!!.jsonPrimitive.content)
                assertFalse("$table.$name must be nullable", f["notNull"]!!.jsonPrimitive.boolean)
                assertNull("$table.$name must have no default", f["defaultValue"])
            }
        }
        assertEquals(v1.getValue("vuln_cache").keys, v2.getValue("vuln_cache").keys)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `e: ... MigrationSqlTest.kt ... Unresolved reference 'MIGRATION_1_2'` (and `DB_NAME`, `MIGRATION_TEST_DB`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/data/Entities.kt`: replace the three entity classes (imports, `HostStatus` and `VulnCacheEntity` unchanged):
```kotlin
@Entity(tableName = "networks", indices = [Index("signature", unique = true)])
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val signature: String, val displayName: String,
    val gatewayIp: String?, val prefixLength: Int, val ssid: String?, val lastSeen: Long,
    /** Run id (start time) of the last completed discovery run; seeds stale dimming after a restart (Phase 3A spec 5.5). */
    val lastRunAt: Long? = null,
    /** Time of a pending "Forget this network": ensure() never adopts or matches the row until Undo or the commit (spec 5.3). */
    val forgotten: Long? = null,
)

@Entity(tableName = "hosts", indices = [Index("networkId", "ip", unique = true)])
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val networkId: Long, val ip: String, val mac: String?,
    val reverseDnsName: String?, val mdnsName: String?, val netbiosName: String?, val upnpName: String?, val upnpManufacturer: String?, val upnpModel: String?, val vendor: String?,
    val deviceType: DeviceType, val customName: String?, val identVendor: String?, val identModel: String?, val identOs: String?, val identConfidence: Int, val identReasons: List<String>,
    val snmpSysDescr: String?, val httpTitle: String?, val mdnsTxtJson: String?, val status: HostStatus, val note: String, val isGateway: Boolean,
    val firstSeen: Long, val lastSeen: Long, val lastPortScan: Long?, val lastDiscoveryRun: Long,
    /** MacSource.rank of the stored mac, 0 = none. */
    val macSource: Int = 0, val upnpDeviceType: String? = null, val upnpServer: String? = null, val httpServer: String? = null, val httpAuthRealm: String? = null,
    /** The Director's own type, vendor and model (spec 4.1); no scan writes these. The name override stays in customName. */
    val userType: DeviceType? = null, val userVendor: String? = null, val userModel: String? = null,
    /** Software names joined by a newline, written with the identity (spec 3.6). */
    val identSoftware: String? = null,
)

@Entity(tableName = "ports", indices = [Index("hostId", "port", unique = true)])
data class PortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val port: Int,
    val serviceName: String, val product: String?, val version: String?, val detail: String?, val confidence: Confidence, val lastSeen: Long,
    /** Port evidence (spec 3.1): page title, full Server header, auth realm, X-Powered-By, certificate subject CN and O. */
    val title: String? = null, val server: String? = null, val realm: String? = null, val poweredBy: String? = null,
    val certCn: String? = null, val certOrg: String? = null,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Migrations.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The app's own database. No instrumented test may open, create or delete it (spec 6, Gruff M8). */
const val DB_NAME = "netprobe.db"
/** The only database name the migration test may create and delete. */
const val MIGRATION_TEST_DB = "netprobe-migration-test.db"

/** Schema 1 to 2 (Phase 3A spec 6): twelve nullable columns with no default, so no row is rewritten. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN userType TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN userVendor TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN userModel TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN identSoftware TEXT")
        db.execSQL("ALTER TABLE networks ADD COLUMN lastRunAt INTEGER")
        db.execSQL("ALTER TABLE networks ADD COLUMN forgotten INTEGER")
        db.execSQL("ALTER TABLE ports ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN server TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN realm TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN poweredBy TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN certCn TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN certOrg TEXT")
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/NetProbeDb.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    companion object {
        /** No fallbackToDestructiveMigration: the Director's marks and notes must survive (spec 6). [name] exists for the migration tests. */
        fun open(context: Context, name: String = DB_NAME): NetProbeDb =
            Room.databaseBuilder(context, NetProbeDb::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}
```

`app/build.gradle.kts`: replace the single `sourceSets { getByName("test") { resources.srcDirs("src/main/res/raw") } }` line inside `android { }` with:
```kotlin
    sourceSets {
        getByName("test") { resources.srcDirs("src/main/res/raw") }
        // MigrationTestHelper reads the exported schemas from the test APK's assets (spec 6).
        getByName("androidTest") { assets.srcDirs("$projectDir/schemas") }
    }
```

Generate and inspect the schema:
```bash
./gradlew :app:kspDebugKotlin --console=plain 2>&1 | tail -3
ls app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/
git status --short app/schemas
```
Expected: `BUILD SUCCESSFUL`; the listing shows `1.json  2.json`; git shows only `?? app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/2.json` (1.json unchanged). If 1.json shows as modified, stop: `git checkout app/schemas/com.northwalessolutions.netprobe.data.NetProbeDb/1.json` and report it.

- [ ] **Step 4: Write the instrumented migration tests (compile only, run in Task 18)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/MigrationTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Schema 1 to 2 on a throwaway file. Never opens, creates or deletes DB_NAME, and proves the app's file is untouched (Gruff M8). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    @Test fun marksNotesAndPortsSurviveTheMigration() {
        val appDb = context.getDatabasePath(DB_NAME)
        val before = stamp(appDb)   // recorded before anything else runs
        helper.createDatabase(MIGRATION_TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5)")
            db.execSQL("INSERT INTO hosts (id, networkId, ip, mac, reverseDnsName, mdnsName, netbiosName, upnpName, upnpManufacturer, upnpModel, vendor, " +
                "deviceType, customName, identVendor, identModel, identOs, identConfidence, identReasons, snmpSysDescr, httpTitle, mdnsTxtJson, status, note, " +
                "isGateway, firstSeen, lastSeen, lastPortScan, lastDiscoveryRun, macSource, upnpDeviceType, upnpServer, httpServer, httpAuthRealm) VALUES " +
                "(7, 1, '10.0.3.59', '00:11:32:9a:1f:e4', 'chnas.lan', 'CHNAS', NULL, NULL, NULL, NULL, NULL, 'NAS', 'DiskStation', 'Synology', 'DS220+', NULL, " +
                "80, '[\"mDNS model DS220+\"]', NULL, NULL, NULL, 'INTERESTING', 'QA note NAS', 0, 1, 2, 3, 4, 4, NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO ports (id, hostId, port, serviceName, product, version, detail, confidence, lastSeen) VALUES (11, 7, 5000, 'HTTP', 'nginx', NULL, '200 OK', 'CONFIRMED', 9)")
        }
        val v2 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 2, true, MIGRATION_1_2)
        v2.query("SELECT customName, status, note, identVendor, userType, userVendor, userModel, identSoftware FROM hosts WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DiskStation", c.getString(0)); assertEquals("INTERESTING", c.getString(1)); assertEquals("QA note NAS", c.getString(2))
            assertEquals("Synology", c.getString(3)); for (i in 4..7) assertTrue("host column $i must be null", c.isNull(i))
        }
        v2.query("SELECT lastRunAt, forgotten, displayName FROM networks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertEquals("Home", c.getString(2))
        }
        v2.query("SELECT serviceName, product, title, server, realm, poweredBy, certCn, certOrg FROM ports WHERE id = 11").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("HTTP", c.getString(0)); assertEquals("nginx", c.getString(1))
            for (i in 2..7) assertTrue("port column $i must be null", c.isNull(i))
        }
        v2.close()
        // Room's own schema validation on the migrated file, through the production builder.
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)
        try {
            runBlocking {
                val h = room.hosts().byIdOnce(7)!!
                assertEquals("QA note NAS", h.note); assertEquals(HostStatus.INTERESTING, h.status); assertEquals(DeviceType.NAS, h.deviceType)
                assertNull(h.userType); assertEquals(1, room.ports().forHostOnce(7).size)
            }
        } finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/DirectorCopyMigrationTest.kt`:
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
 * Runs only when Task 18 step 3 has pushed a copy of the Director's database as [MIGRATION_COPY_DB]; skipped otherwise.
 * Never opens, creates or deletes DB_NAME; deletes only the copy.
 */
@RunWith(AndroidJUnit4::class)
class DirectorCopyMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun rows(query: (String) -> Cursor): List<String> = query(MARKS_SQL).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    @After fun tidy() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_COPY_DB + it).delete() } }

    @Test fun theDirectorsCopyMigratesWithEveryMarkAndNote() {
        val copy = context.getDatabasePath(MIGRATION_COPY_DB)
        assumeTrue("no pushed copy of the Director's database", copy.exists())
        assertNotEquals(DB_NAME, MIGRATION_COPY_DB)
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        val v1 = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertEquals("the copy must be schema 1", 1, v1.version)
        val marksBefore = rows { v1.rawQuery(it, null) }
        val portsBefore = v1.rawQuery("SELECT COUNT(*) FROM ports", null).use { it.moveToFirst(); it.getInt(0) }
        v1.close()
        val v2 = helper.runMigrationsAndValidate(MIGRATION_COPY_DB, 2, true, MIGRATION_1_2)
        assertEquals(marksBefore, rows { v2.query(it) })
        assertEquals(portsBefore, v2.query("SELECT COUNT(*) FROM ports").use { it.moveToFirst(); it.getInt(0) })
        v2.close()
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    private companion object {
        const val MIGRATION_COPY_DB = "netprobe-migration-copy.db"
        const val MARKS_SQL = "SELECT id || '|' || status || '|' || note || '|' || IFNULL(customName, '') FROM hosts ORDER BY id"
    }
}
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`. If `MigrationTestHelper(instrumentation, NetProbeDb::class.java)` is reported as ambiguous or missing, read the room-testing 2.7.1 signature in the compiler message and pass `specs = emptyList()` explicitly; never add a dependency.

- [ ] **Step 5: Run the JVM tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `MigrationSqlTest` (3) and `SchemaExportTest` (3) pass with the existing data tests; then `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Schema 2: twelve nullable columns, MIGRATION_1_2, exported schema and migration tests"
```

---

### Task 2: DAO and repository calls for schema 2: overrides, the identity guard, software, markRun, soft Forget, host snapshot

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt` (replace), `data/Repositories.kt`, `engine/identify/DeviceIdentity.kt`, `ui/home/HomeViewModel.kt` (first line of `discover`)
- Create: `app/src/main/java/com/northwalessolutions/netprobe/data/Overrides.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/IdentityWriteTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt` (additions, compile only)

**Interfaces:**
- Consumes: Task 1 entities.
- Produces:
```kotlin
data class DeviceIdentity(..., val software: List<String> = emptyList())          // engine/identify
data class DeviceEdits(val name: String? = null, val type: DeviceType? = null, val vendor: String? = null, val model: String? = null)   // data
object SoftwareColumn { fun encode(names: List<String>): String?; fun decode(column: String?): List<String> }
object IdentityWrite { fun replaces(storedConfidence: Int, newConfidence: Int): Boolean }
data class HostSnapshot(val host: HostEntity, val ports: List<PortEntity>)
NetworkRepository.ensure(env, gatewayMac): NetworkEntity?        // null while that network's Forget is pending
NetworkRepository.byId(id): NetworkEntity?; markRun(networkId, runId); setForgotten(networkId, at: Long?); deleteNetworkCascade(networkId)
NetworkRepository.purgeForgotten(onDeleted: (Long) -> Unit)
HostRepository.setIdentity(hostId, identity)                      // also writes identSoftware
HostRepository.setIdentityIfNotWeaker(hostId, identity): Boolean
HostRepository.setOverrides(hostId, edits: DeviceEdits)
HostRepository.snapshot(hostId): HostSnapshot?; delete(s: HostSnapshot); restore(s: HostSnapshot)
```

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/northwalessolutions/netprobe/data/IdentityWriteTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import org.junit.Assert.*
import org.junit.Test

class IdentityWriteTest {
    @Test fun `identity guard replaces on equal or higher confidence only`() {   // spec 9.1 test 21, Review Focus 3
        assertFalse(IdentityWrite.replaces(storedConfidence = 90, newConfidence = 70))
        assertTrue(IdentityWrite.replaces(storedConfidence = 90, newConfidence = 90))
        assertTrue(IdentityWrite.replaces(storedConfidence = 70, newConfidence = 90))
    }
    @Test fun `software column round trips names in order without blanks or repeats`() {
        assertEquals("Home Assistant\nPlex Media Server", SoftwareColumn.encode(listOf("Home Assistant", " Plex Media Server ", "", "Home Assistant")))
        assertEquals(listOf("Home Assistant", "Plex Media Server"), SoftwareColumn.decode("Home Assistant\nPlex Media Server"))
        assertNull(SoftwareColumn.encode(emptyList())); assertEquals(emptyList<String>(), SoftwareColumn.decode(null))
    }
    @Test fun `empty edits clear every override`() {
        assertEquals(DeviceEdits(null, null, null, null), DeviceEdits())
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.IdentityWriteTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'IdentityWrite'` (and `SoftwareColumn`, `DeviceEdits`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt`: replace the `DeviceIdentity` class only:
```kotlin
/** [software] is what the box runs ("Runs Home Assistant"), distinct and in rule-file order; never a weighted slot (spec 3.6). */
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>,
                          val software: List<String> = emptyList()) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Overrides.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** The Director's own values for a host (spec 4.1). null means "use the detected value". DeviceEdits.of arrives in Task 9. */
data class DeviceEdits(val name: String? = null, val type: DeviceType? = null, val vendor: String? = null, val model: String? = null)

/** hosts.identSoftware: software names joined by a newline, trimmed, distinct, in rule-file order (spec 3.6). */
object SoftwareColumn {
    private const val SEPARATOR = "\n"
    fun encode(names: List<String>): String? =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    fun decode(column: String?): List<String> = column?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** After a port scan the stored identity is replaced only by one at least as confident (spec 3.3); the DAO query is the atomic form. */
object IdentityWrite {
    fun replaces(storedConfidence: Int, newConfidence: Int): Boolean = newConfidence >= storedConfidence
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Daos.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.data

import androidx.room.*
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import kotlinx.coroutines.flow.Flow

@Dao interface NetworkDao {
    @Query("SELECT * FROM networks WHERE signature = :sig LIMIT 1") suspend fun bySignature(sig: String): NetworkEntity?
    @Query("SELECT * FROM networks WHERE prefixLength = :prefix AND ((:gw IS NULL AND gatewayIp IS NULL) OR gatewayIp = :gw)")
    suspend fun byGateway(gw: String?, prefix: Int): List<NetworkEntity>
    @Query("SELECT * FROM networks WHERE id = :id") suspend fun byId(id: Long): NetworkEntity?
    /** The MAC recorded for the network's own gateway host, if a scan has stored one. */
    @Query("SELECT mac FROM hosts WHERE networkId = :networkId AND ip = :gw LIMIT 1") suspend fun gatewayHostMac(networkId: Long, gw: String): String?
    @Query("UPDATE networks SET signature = :sig, ssid = :ssid, displayName = :name, lastSeen = :now WHERE id = :id") suspend fun adopt(id: Long, sig: String, ssid: String?, name: String, now: Long)
    @Insert suspend fun insert(n: NetworkEntity): Long
    @Query("UPDATE networks SET lastSeen = :now, ssid = COALESCE(:ssid, ssid) WHERE id = :id") suspend fun touch(id: Long, now: Long, ssid: String?)
    @Query("UPDATE networks SET lastRunAt = :runId WHERE id = :id") suspend fun markRun(id: Long, runId: Long)
    @Query("UPDATE networks SET forgotten = :at WHERE id = :id") suspend fun setForgotten(id: Long, at: Long?)
    @Query("SELECT id FROM networks WHERE forgotten IS NOT NULL") suspend fun forgottenIds(): List<Long>
    /** The three halves of a network delete; there are no foreign keys, so the repository runs them in this order in one transaction. */
    @Query("DELETE FROM ports WHERE hostId IN (SELECT id FROM hosts WHERE networkId = :id)") suspend fun deletePortsOfNetwork(id: Long)
    @Query("DELETE FROM hosts WHERE networkId = :id") suspend fun deleteHostsOfNetwork(id: Long)
    @Query("DELETE FROM networks WHERE id = :id") suspend fun deleteNetwork(id: Long)
}
@Dao interface HostDao {
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    @Query("SELECT * FROM hosts WHERE id = :id") fun byId(id: Long): Flow<HostEntity?>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun byIdOnce(id: Long): HostEntity?
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND ip = :ip LIMIT 1") suspend fun find(networkId: Long, ip: String): HostEntity?
    @Insert suspend fun insert(h: HostEntity): Long
    /** Undo of Remove device: the same rows with the same ids; a clash is an error, never a silent replace (spec 5.3). */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(hosts: List<HostEntity>)
    /** Scan-derived columns only; customName, the user* overrides, status and note are deliberately absent so they are never overwritten. */
    @Query("UPDATE hosts SET mac = :mac, macSource = :macSource, reverseDnsName = :rdns, mdnsName = :mdnsName, netbiosName = :netbiosName, upnpName = :upnpName, upnpManufacturer = :upnpManufacturer, " +
        "upnpModel = :upnpModel, vendor = :vendor, snmpSysDescr = :snmpSysDescr, httpTitle = :httpTitle, mdnsTxtJson = :mdnsTxtJson, isGateway = :isGateway, lastSeen = :lastSeen, " +
        "lastDiscoveryRun = :runId, upnpDeviceType = :upnpDeviceType, upnpServer = :upnpServer, httpServer = :httpServer, httpAuthRealm = :httpAuthRealm WHERE id = :id")
    suspend fun updateScan(id: Long, mac: String?, macSource: Int, rdns: String?, mdnsName: String?, netbiosName: String?, upnpName: String?, upnpManufacturer: String?, upnpModel: String?,
        vendor: String?, snmpSysDescr: String?, httpTitle: String?, mdnsTxtJson: String?, isGateway: Boolean, lastSeen: Long, runId: Long, upnpDeviceType: String?, upnpServer: String?,
        httpServer: String?, httpAuthRealm: String?)
    @Query("UPDATE hosts SET identVendor = :v, identModel = :m, identOs = :o, deviceType = :t, identConfidence = :c, identReasons = :r, identSoftware = :s WHERE id = :id")
    suspend fun updateIdentity(id: Long, v: String?, m: String?, o: String?, t: DeviceType, c: Int, r: List<String>, s: String?)
    /** The port-scan write (spec 3.3): one atomic UPDATE that lands only when the new confidence is equal or higher. Returns rows changed. */
    @Query("UPDATE hosts SET identVendor = :v, identModel = :m, identOs = :o, deviceType = :t, identConfidence = :c, identReasons = :r, identSoftware = :s " +
        "WHERE id = :id AND identConfidence <= :c")
    suspend fun updateIdentityIfNotWeaker(id: Long, v: String?, m: String?, o: String?, t: DeviceType, c: Int, r: List<String>, s: String?): Int
    @Query("UPDATE hosts SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: HostStatus)
    @Query("UPDATE hosts SET note = :note WHERE id = :id") suspend fun setNote(id: Long, note: String)
    @Query("UPDATE hosts SET customName = :name WHERE id = :id") suspend fun setCustomName(id: Long, name: String?)
    @Query("UPDATE hosts SET customName = :name, userType = :type, userVendor = :vendor, userModel = :model WHERE id = :id")
    suspend fun setOverrides(id: Long, name: String?, type: DeviceType?, vendor: String?, model: String?)
    @Query("UPDATE hosts SET lastPortScan = :now WHERE id = :id") suspend fun markScanned(id: Long, now: Long)
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun deleteHost(id: Long)
}
data class PortCount(val hostId: Long, val n: Int)

@Dao interface PortDao {
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") fun forHost(hostId: Long): Flow<List<PortEntity>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") suspend fun forHostOnce(hostId: Long): List<PortEntity>
    @Query("SELECT hostId, COUNT(*) AS n FROM ports GROUP BY hostId") fun counts(): Flow<List<PortCount>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId AND port = :port LIMIT 1") suspend fun find(hostId: Long, port: Int): PortEntity?
    @Insert suspend fun insert(p: PortEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(ports: List<PortEntity>)
    @Update suspend fun update(p: PortEntity)
    @Query("DELETE FROM ports WHERE hostId = :hostId") suspend fun deletePortsForHost(hostId: Long)
}
@Dao interface VulnCacheDao {
    @Query("SELECT * FROM vuln_cache WHERE `query` = :q LIMIT 1") suspend fun get(q: String): VulnCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: VulnCacheEntity)
    @Query("DELETE FROM vuln_cache") suspend fun clear()
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt`:

Add above `class NetworkRepository`:
```kotlin
/** A host row and its port rows, read together, for Remove device and its Undo (spec 5.3). */
data class HostSnapshot(val host: HostEntity, val ports: List<PortEntity>)
```

Replace `class NetworkRepository` with:
```kotlin
class NetworkRepository(private val db: NetProbeDb) {
    /**
     * Finds or creates the row for [env]. Identity is gateway MAC + gateway IP + prefix ([NetworkSignature]).
     * On a signature miss, a single row with the same gateway IP and prefix is adopted (rows keyed by the old BSSID rule,
     * rows recorded while the MAC was unreadable, and the location-granted case), unless its stored gateway host has a
     * different MAC: that is another site on the same addressing, and its inventory must not be merged.
     * A row with a pending Forget is never matched or adopted; when it is the signature match, null is returned and nothing
     * is inserted, so the forgotten row and its id stay untouched until Undo or the commit (spec 5.3).
     */
    suspend fun ensure(env: NetworkEnv, gatewayMac: String?): NetworkEntity? = db.withTransaction {
        val sig = NetworkSignature.of(env, gatewayMac); val now = System.currentTimeMillis()
        val gw = env.gateway?.hostAddress
        val found = db.networks().bySignature(sig)
        if (found != null) {
            if (found.forgotten != null) return@withTransaction null
            db.networks().touch(found.id, now, env.ssid)
            return@withTransaction found.copy(lastSeen = now, ssid = env.ssid ?: found.ssid)
        }
        val mac = gatewayMac?.lowercase()
        val adoptable = db.networks().byGateway(gw, env.prefixLength).filter { it.forgotten == null }.filter { row ->
            val stored = if (mac != null && gw != null) db.networks().gatewayHostMac(row.id, gw)?.lowercase() else null
            stored == null || stored == mac
        }
        if (adoptable.size == 1) {
            val a = adoptable.first(); val ssid = env.ssid ?: a.ssid; val name = env.ssid ?: a.displayName
            db.networks().adopt(a.id, sig, ssid, name, now)
            return@withTransaction a.copy(signature = sig, ssid = ssid, displayName = name, lastSeen = now)
        }
        val n = NetworkEntity(signature = sig, displayName = env.ssid ?: gw ?: "Network", gatewayIp = gw, prefixLength = env.prefixLength, ssid = env.ssid, lastSeen = now)
        try {
            n.copy(id = db.networks().insert(n))
        } catch (e: SQLiteConstraintException) {
            val raced = db.networks().bySignature(sig) ?: throw e
            if (raced.forgotten != null) null else { db.networks().touch(raced.id, now, env.ssid); raced.copy(lastSeen = now) }
        }
    }
    suspend fun byId(id: Long): NetworkEntity? = db.networks().byId(id)
    /** Only completed runs are persisted (spec 5.5, Gruff Q1); the caller decides. */
    suspend fun markRun(networkId: Long, runId: Long) = db.networks().markRun(networkId, runId)
    suspend fun setForgotten(networkId: Long, at: Long?) = db.networks().setForgotten(networkId, at)
    /** Ports of the network's hosts, then the hosts, then the network, in one transaction; nothing from another network. */
    suspend fun deleteNetworkCascade(networkId: Long) = db.withTransaction {
        db.networks().deletePortsOfNetwork(networkId); db.networks().deleteHostsOfNetwork(networkId); db.networks().deleteNetwork(networkId)
    }
    /** At start-up: a Forget left pending by a killed process is final (spec 5.3). [onDeleted] clears that network's manual ranges. */
    suspend fun purgeForgotten(onDeleted: (Long) -> Unit) {
        for (id in db.networks().forgottenIds()) { deleteNetworkCascade(id); onDeleted(id) }
    }
}
```

In `class HostRepository`, replace `setIdentity` and add, after `setNote` (keep `setCustomName` until Task 10):
```kotlin
    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
        db.hosts().updateIdentity(hostId, identity.vendor, identity.model, identity.os, identity.type, identity.confidence, identity.reasons,
            SoftwareColumn.encode(identity.software))
    }
    /** After a port scan (spec 3.3): true when the identity was written, false when the stored one is more confident. */
    suspend fun setIdentityIfNotWeaker(hostId: Long, identity: DeviceIdentity): Boolean =
        db.hosts().updateIdentityIfNotWeaker(hostId, identity.vendor, identity.model, identity.os, identity.type, identity.confidence, identity.reasons,
            SoftwareColumn.encode(identity.software)) > 0
    suspend fun setOverrides(hostId: Long, edits: DeviceEdits) = db.hosts().setOverrides(hostId, edits.name, edits.type, edits.vendor, edits.model)
    suspend fun snapshot(hostId: Long): HostSnapshot? = db.withTransaction {
        db.hosts().byIdOnce(hostId)?.let { HostSnapshot(it, db.ports().forHostOnce(hostId)) }
    }
    suspend fun delete(s: HostSnapshot) = db.withTransaction { db.ports().deletePortsForHost(s.host.id); db.hosts().deleteHost(s.host.id) }
    /** Undo: the same rows with the same ids; ABORT on any clash, which the appScope handler catches (spec 5.3 step 4). */
    suspend fun restore(s: HostSnapshot) = db.withTransaction { db.hosts().insertAll(listOf(s.host)); db.ports().insertAll(s.ports) }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: in `discover`, replace the first line (`val net = c.networks.ensure(...); networkId.value = net.id; c.currentNetwork.value = net`) with:
```kotlin
        // null only while this network's Forget is pending; Task 13 commits a pending Undo before this line.
        val net = c.networks.ensure(e, GatewayMac.lookup(e.gateway)) ?: return
        networkId.value = net.id; c.currentNetwork.value = net
```
The env collector already handles a nullable result (`e?.let { ... }`); it compiles unchanged.

- [ ] **Step 4: DAO tests (instrumented, compile only, run in Task 18)**

In `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`, the existing tests `ensureIsRaceSafe`, `locationGrantKeepsTheSameNetwork`, `ensureAdoptsARowKeyedByTheOldRule` and `anotherSiteWithTheSameAddressingIsNotMerged` now receive `NetworkEntity?`: append `!!` to every `repo.ensure(...)` whose result is read (`.id`, `.ssid`, `.signature`, `.displayName`). Then add, before the companion object (spec 9.3 tests 2 and 4 and the repository half of test 5):
```kotlin
    @Test fun overridesRoundTripAndScansNeverTouchThem() = runBlocking {
        val hosts = HostRepository(db); val addr = ip("10.0.3.59")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(addr, mdnsName = "CHNAS"), runId = 1)
        hosts.setOverrides(id, DeviceEdits(name = "DiskStation", type = DeviceType.NAS, vendor = "Synology", model = "DS220+"))
        hosts.upsertDiscovered(1, DiscoveredHost(addr, mdnsName = "CHNAS2"), runId = 2)
        hosts.setIdentity(id, DeviceIdentity("QNAP", "TS-453", null, DeviceType.SERVER, 90, listOf("r"), listOf("Plex Media Server")))
        val h = hosts.byIdOnce(id)!!
        assertEquals("DiskStation", h.customName); assertEquals(DeviceType.NAS, h.userType); assertEquals("Synology", h.userVendor); assertEquals("DS220+", h.userModel)
        assertEquals("QNAP", h.identVendor); assertEquals(DeviceType.SERVER, h.deviceType); assertEquals("Plex Media Server", h.identSoftware)
        hosts.setOverrides(id, DeviceEdits())
        val cleared = hosts.byIdOnce(id)!!
        assertNull(cleared.customName); assertNull(cleared.userType); assertNull(cleared.userVendor); assertNull(cleared.userModel)
    }
    @Test fun identityGuardKeepsAStrongerStoredIdentity() = runBlocking {
        val hosts = HostRepository(db)
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.60")), runId = 1)
        hosts.setIdentity(id, DeviceIdentity("Synology", null, null, DeviceType.NAS, 80, listOf("a")))
        assertFalse(hosts.setIdentityIfNotWeaker(id, DeviceIdentity(null, null, "Linux", DeviceType.UNKNOWN, 40, listOf("b"))))
        assertEquals("Synology", hosts.byIdOnce(id)!!.identVendor)
        assertTrue(hosts.setIdentityIfNotWeaker(id, DeviceIdentity("Synology", "DS220+", null, DeviceType.NAS, 80, listOf("a", "c"))))
        assertEquals("DS220+", hosts.byIdOnce(id)!!.identModel)
        assertTrue(hosts.setIdentityIfNotWeaker(id, DeviceIdentity("Synology", "DS220+", "DSM", DeviceType.NAS, 90, listOf("d"))))
        assertEquals(90, hosts.byIdOnce(id)!!.identConfidence)
    }
    @Test fun markRunPersistsTheLastCompletedRun() = runBlocking {
        val repo = NetworkRepository(db)
        val env = NetworkEnv(ip("10.0.9.5"), 24, ip("10.0.9.1"), emptyList(), "Lab", null, Transport.WIFI)
        val n = repo.ensure(env, GW_MAC)!!
        assertNull(n.lastRunAt)
        repo.markRun(n.id, 1_234L)
        assertEquals(1_234L, repo.ensure(env, GW_MAC)!!.lastRunAt)
    }
    @Test fun forgetIsASoftDeleteUntilTheCommit() = runBlocking {
        val repo = NetworkRepository(db); val hosts = HostRepository(db); val ports = PortRepository(db)
        val env = NetworkEnv(ip("10.0.3.242"), 24, ip("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)
        val other = NetworkEnv(ip("10.0.8.5"), 24, ip("10.0.8.1"), emptyList(), "Office", null, Transport.WIFI)
        val home = repo.ensure(env, GW_MAC)!!; val office = repo.ensure(other, "00:11:22:33:44:55")!!
        val hid = hosts.upsertDiscovered(home.id, DiscoveredHost(ip("10.0.3.59")), 1L); hosts.setNote(hid, "keep me")
        ports.upsertOpen(hid, 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE))
        val oid = hosts.upsertDiscovered(office.id, DiscoveredHost(ip("10.0.8.20")), 1L)
        ports.upsertOpen(oid, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE))
        repo.setForgotten(home.id, 99L)
        assertNull(repo.ensure(env, GW_MAC)); assertEquals(2, count("networks"))       // the signature match is forgotten: null, nothing inserted
        assertNotEquals(home.id, repo.ensure(env, null)?.id)                           // the byGateway re-signature never adopts it
        repo.setForgotten(home.id, null)                                               // Undo
        assertEquals(home.id, repo.ensure(env, GW_MAC)!!.id); assertEquals("keep me", hosts.byIdOnce(hid)!!.note)
        repo.setForgotten(home.id, 100L); repo.deleteNetworkCascade(home.id)           // commit
        assertNull(hosts.byIdOnce(hid)); assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(hid)); assertNull(repo.byId(home.id))
        assertNotNull(hosts.byIdOnce(oid)); assertEquals(setOf(80), ports.servicesFor(oid).keys); assertNotNull(repo.byId(office.id))
    }
    @Test fun purgeForgottenRemovesRowsLeftByAKilledProcess() = runBlocking {
        val repo = NetworkRepository(db); val hosts = HostRepository(db)
        val env = NetworkEnv(ip("10.0.5.5"), 24, ip("10.0.5.1"), emptyList(), "Cafe", null, Transport.WIFI)
        val n = repo.ensure(env, GW_MAC)!!; val hid = hosts.upsertDiscovered(n.id, DiscoveredHost(ip("10.0.5.9")), 1L)
        repo.setForgotten(n.id, 5L)
        val cleared = mutableListOf<Long>()
        repo.purgeForgotten { cleared += it }
        assertEquals(listOf(n.id), cleared); assertNull(repo.byId(n.id)); assertNull(hosts.byIdOnce(hid))
    }
    @Test fun removeSnapshotDeleteRestoreKeepsRowsAndIds() = runBlocking {
        val hosts = HostRepository(db); val ports = PortRepository(db)
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.70"), mdnsName = "Printer"), 1L)
        hosts.setStatus(id, HostStatus.INVESTIGATED); hosts.setNote(id, "toner low")
        ports.upsertOpen(id, 631, ServiceInfo("IPP", confidence = Confidence.CONFIRMED))
        val snap = hosts.snapshot(id)!!
        hosts.delete(snap)
        assertNull(hosts.byIdOnce(id)); assertEquals(0, count("ports"))
        hosts.restore(snap)
        assertEquals(snap, hosts.snapshot(id))
    }
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `IdentityWriteTest` (3) passes; `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Schema 2 DAO and repository calls: overrides, identity guard, software, markRun, soft Forget, host snapshot"
```

---

### Task 3: Port evidence end to end: six ServiceInfo fields, ServiceMerge, header capture, and every read and write path carrying them

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceInfo.kt`, `engine/serviceid/BannerParsers.kt` (`httpHead`), `engine/identify/DeviceIdentity.kt` (`HostEvidenceBuilder`), `data/Repositories.kt` (`PortRepository`), `ui/detail/DetailViewModel.kt` (port row mapping)
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceMerge.kt`, `data/PortMapping.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceMergeTest.kt`, `app/src/test/java/com/northwalessolutions/netprobe/data/PortMappingTest.kt`, `engine/serviceid/BannerParsersTest.kt` (one test added); `androidTest/.../data/DaoTest.kt` (one line)

**Interfaces:**
- Consumes: `PortEntity` six evidence columns (Task 1).
- Produces:
```kotlin
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null, val detail: String? = null,
                       val confidence: Confidence, val title: String? = null, val server: String? = null, val realm: String? = null,
                       val poweredBy: String? = null, val certCn: String? = null, val certOrg: String? = null)
object ServiceMerge { fun merge(stored: ServiceInfo?, fresh: ServiceInfo): ServiceInfo }
fun PortEntity.toServiceInfo(): ServiceInfo
fun PortEntity.withService(s: ServiceInfo, now: Long): PortEntity
object PortMapping { fun merge(stored: PortEntity, fresh: ServiceInfo, now: Long): PortEntity; fun newRow(hostId: Long, port: Int, info: ServiceInfo, now: Long): PortEntity }
BannerParsers.httpHead(response): ServiceInfo?      // now also server (full header), realm, poweredBy
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceMergeTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 16: one never-erase merge for the repository and the evidence builder (Gruff M5, Review Focus 3). */
class ServiceMergeTest {
    private val stored = ServiceInfo("HTTP", "nginx", "1.25", "200 OK", Confidence.CONFIRMED, title = "DiskStation", server = "nginx/1.25", poweredBy = "PHP/8.2")

    @Test fun `new evidence fields merge field by field`() {
        val m = ServiceMerge.merge(stored, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = "Synology DiskStation", realm = "DSM"))
        assertEquals("Synology DiskStation", m.title); assertEquals("nginx/1.25", m.server); assertEquals("DSM", m.realm); assertEquals("PHP/8.2", m.poweredBy)
        assertEquals("nginx", m.product); assertEquals("1.25", m.version); assertEquals("200 OK", m.detail)
    }
    @Test fun `a null never erases a stored value`() {
        assertEquals(stored, ServiceMerge.merge(stored, ServiceInfo("HTTP", confidence = Confidence.CONFIRMED)))
    }
    @Test fun `a port table result never replaces a confirmed name`() {   // a timed-out HEAD on a busy NAS
        val m = ServiceMerge.merge(stored, ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE))
        assertEquals(stored, m)
    }
    @Test fun `a new confirmed name replaces an old one`() {
        val m = ServiceMerge.merge(stored, ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = "nas.lan", certOrg = "Synology Inc."))
        assertEquals("HTTPS", m.name); assertEquals("nas.lan", m.certCn); assertEquals("Synology Inc.", m.certOrg); assertEquals("DiskStation", m.title)
    }
    @Test fun `port table replaces port table and nothing stored keeps the new value`() {
        assertEquals("Unknown", ServiceMerge.merge(ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE), ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE)).name)
        val fresh = ServiceInfo("SSH", "OpenSSH", null, null, Confidence.CONFIRMED)
        assertSame(fresh, ServiceMerge.merge(null, fresh))
    }
    @Test fun `the evidence builder uses the same merge`() {
        val host = DiscoveredHost(InetAddress.getByName("10.0.3.59") as Inet4Address)
        val e = HostEvidenceBuilder.build(host, mapOf(5000 to stored), setOf(5000), mapOf(5000 to ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE)), null)
        assertEquals(stored, e.services[5000])
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/PortMappingTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** Every port read and write path goes through these, so none can drop the six evidence fields (spec 3.1 table, Gruff M5). */
class PortMappingTest {
    @Test fun `a port row carries all eleven service fields both ways`() {
        val s = ServiceInfo("HTTPS", "nginx", "1.25", "cert nas.lan", Confidence.CONFIRMED, "DiskStation", "nginx/1.25", "DSM", "PHP/8.2", "nas.lan", "Synology Inc.")
        val e = PortEntity(hostId = 7, port = 5001, serviceName = "x", product = null, version = null, detail = null, confidence = Confidence.PORT_TABLE, lastSeen = 0)
            .withService(s, now = 9)
        assertEquals(s, e.toServiceInfo()); assertEquals(9L, e.lastSeen); assertEquals(7L, e.hostId); assertEquals(5001, e.port)
        assertEquals(s, PortMapping.newRow(7, 5001, s, 3).toServiceInfo())
    }
    @Test fun `merging into a stored row never erases evidence`() {
        val stored = PortEntity(hostId = 7, port = 5000, serviceName = "HTTP", product = "nginx", version = null, detail = "200 OK",
            confidence = Confidence.CONFIRMED, lastSeen = 1, title = "DiskStation")
        val merged = PortMapping.merge(stored, ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE), now = 2)
        assertEquals("HTTP", merged.serviceName); assertEquals(Confidence.CONFIRMED, merged.confidence); assertEquals("DiskStation", merged.title)
        assertEquals("nginx", merged.product); assertEquals(2L, merged.lastSeen); assertEquals(stored.id, merged.id)
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/BannerParsersTest.kt`, inside the class:
```kotlin
    @Test fun `http head keeps the full server header, the realm and x powered by`() {
        val r = BannerParsers.httpHead("HTTP/1.1 401 Unauthorized\r\nServer: nginx/1.18.0 (Ubuntu)\r\nX-Powered-By: PHP/8.2.7\r\nWWW-Authenticate: Basic realm=\"TP-LINK\"\r\n\r\n")!!
        assertEquals("nginx/1.18.0 (Ubuntu)", r.server); assertEquals("TP-LINK", r.realm); assertEquals("PHP/8.2.7", r.poweredBy)
        assertEquals("nginx", r.product); assertEquals("1.18.0", r.version)
        val bare = BannerParsers.httpHead("HTTP/1.0 200 OK\r\n\r\n")!!
        assertNull(bare.server); assertNull(bare.realm); assertNull(bare.poweredBy)
        assertEquals(120, BannerParsers.httpHead("HTTP/1.1 200 OK\r\nServer: " + "s".repeat(300) + "\r\n\r\n")!!.server!!.length)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.serviceid.*" --tests "com.northwalessolutions.netprobe.data.PortMappingTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ServiceMerge'`, `Cannot find a parameter with this name: title` (and `server`, `withService`, `PortMapping`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceInfo.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

enum class Confidence { PORT_TABLE, CONFIRMED }

/**
 * One open port's findings. The six evidence fields (Phase 3A spec 3.1) come after [confidence] and default to null, so every
 * positional call site compiles unchanged: page [title], full [server] header (HTTP, HTTPS or RTSP), auth [realm], X-Powered-By
 * ([poweredBy]), and the leaf certificate's subject CN and O ([certCn], [certOrg]).
 */
data class ServiceInfo(val name: String, val product: String? = null, val version: String? = null, val detail: String? = null,
                       val confidence: Confidence, val title: String? = null, val server: String? = null, val realm: String? = null,
                       val poweredBy: String? = null, val certCn: String? = null, val certOrg: String? = null)
data class Evidence(val mdnsTypes: Set<String> = emptySet(), val upnpServer: String? = null)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceMerge.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

/**
 * The one merge of a stored port finding with a new one (spec 3.1): field by field a new non-null value wins and a null never
 * erases; the name and confidence never go down, so a PORT_TABLE guess (a timed-out HEAD) never replaces a CONFIRMED name,
 * while a new CONFIRMED name does replace an old one.
 */
object ServiceMerge {
    fun merge(stored: ServiceInfo?, fresh: ServiceInfo): ServiceInfo {
        if (stored == null) return fresh
        val keepName = stored.confidence == Confidence.CONFIRMED && fresh.confidence == Confidence.PORT_TABLE
        return ServiceInfo(
            name = if (keepName) stored.name else fresh.name,
            product = fresh.product ?: stored.product, version = fresh.version ?: stored.version, detail = fresh.detail ?: stored.detail,
            confidence = if (keepName) stored.confidence else fresh.confidence,
            title = fresh.title ?: stored.title, server = fresh.server ?: stored.server, realm = fresh.realm ?: stored.realm,
            poweredBy = fresh.poweredBy ?: stored.poweredBy, certCn = fresh.certCn ?: stored.certCn, certOrg = fresh.certOrg ?: stored.certOrg,
        )
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/BannerParsers.kt`: add `private const val MAX_HEADER = 120` at the top of the object and replace `httpHead` with:
```kotlin
    fun httpHead(response: String): ServiceInfo? {
        val lines = response.split("\r\n", "\n")
        val status = lines.firstOrNull()?.takeIf { it.startsWith("HTTP/") && it.length > 9 } ?: return null
        val headers = lines.drop(1).takeWhile { it.isNotBlank() }
            .mapNotNull { h -> h.indexOf(':').takeIf { it > 0 }?.let { h.substring(0, it).trim().lowercase() to h.substring(it + 1).trim() } }.toMap()
        val server = headers["server"]?.take(MAX_HEADER)?.ifEmpty { null }
        val (product, version) = server?.let { serverHeader(it) } ?: (null to null)
        val realm = headers["www-authenticate"]?.let { Regex("realm=\"?([^\"]+)\"?").find(it)?.groupValues?.get(1) }?.take(MAX_HEADER)
        val detail = listOfNotNull(status.substringAfter(' ').take(40), realm?.let { "realm $it" }).joinToString(", ")
        return ServiceInfo("HTTP", product, version, detail, Confidence.CONFIRMED,
            server = server, realm = realm, poweredBy = headers["x-powered-by"]?.take(MAX_HEADER)?.ifEmpty { null })
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/data/PortMapping.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge

/** The one place a port row and a ServiceInfo convert: discovery, Detail and Quick scan all read all eleven fields (spec 3.1, Gruff M5). */
fun PortEntity.toServiceInfo(): ServiceInfo =
    ServiceInfo(serviceName, product, version, detail, confidence, title, server, realm, poweredBy, certCn, certOrg)

fun PortEntity.withService(s: ServiceInfo, now: Long): PortEntity = copy(
    serviceName = s.name, product = s.product, version = s.version, detail = s.detail, confidence = s.confidence, lastSeen = now,
    title = s.title, server = s.server, realm = s.realm, poweredBy = s.poweredBy, certCn = s.certCn, certOrg = s.certOrg)

object PortMapping {
    /** A stored row updated by a new finding through [ServiceMerge]: never erases, never lowers a confirmed name. */
    fun merge(stored: PortEntity, fresh: ServiceInfo, now: Long): PortEntity = stored.withService(ServiceMerge.merge(stored.toServiceInfo(), fresh), now)
    fun newRow(hostId: Long, port: Int, info: ServiceInfo, now: Long): PortEntity =
        PortEntity(hostId = hostId, port = port, serviceName = info.name, product = null, version = null, detail = null, confidence = info.confidence, lastSeen = now)
            .withService(info, now)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt`: replace `class PortRepository` with:
```kotlin
class PortRepository(private val db: NetProbeDb) {
    fun portsForHost(hostId: Long): Flow<List<PortEntity>> = db.ports().forHost(hostId)
    fun counts(): Flow<Map<Long, Int>> = db.ports().counts().map { rows -> rows.associate { it.hostId to it.n } }
    suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo): Unit = db.withTransaction {
        val now = System.currentTimeMillis()
        val e = db.ports().find(hostId, port)
        if (e == null) {
            try { db.ports().insert(PortMapping.newRow(hostId, port, info, now)) } catch (ex: SQLiteConstraintException) {
                val raced = db.ports().find(hostId, port) ?: throw ex
                db.ports().update(PortMapping.merge(raced, info, now))
            }
        } else db.ports().update(PortMapping.merge(e, info, now))
    }
    /** Stored open ports of a host as identification input, all eleven fields, so a rediscovery never forgets earlier findings. */
    suspend fun servicesFor(hostId: Long): Map<Int, ServiceInfo> = db.ports().forHostOnce(hostId).associate { it.port to it.toServiceInfo() }
    suspend fun markScanned(hostId: Long) = db.hosts().markScanned(hostId, System.currentTimeMillis())
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentity.kt`: replace `HostEvidenceBuilder` with:
```kotlin
/**
 * Identification input for a stored host: everything ever learned about its ports (stored rows) plus this run's findings,
 * merged through [ServiceMerge]: new facts win field by field, a null never erases, a PORT_TABLE guess never lowers a CONFIRMED name.
 */
object HostEvidenceBuilder {
    fun build(host: DiscoveredHost, stored: Map<Int, ServiceInfo>, newPorts: Set<Int>, newServices: Map<Int, ServiceInfo>, ouiVendor: String?): HostEvidence {
        val services = HashMap(stored)
        for ((port, n) in newServices) services[port] = ServiceMerge.merge(stored[port], n)
        return HostEvidence(host, stored.keys + newPorts + newServices.keys, services, ouiVendor)
    }
}
```
and add `import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: in the `state` combine, replace `val info = ServiceInfo(p.serviceName, p.product, p.version, p.detail, p.confidence)` with `val info = p.toServiceInfo()` (all eleven fields reach `PortRow.service`, so `PortUris.guess` and the row sub-line see them); add `import com.northwalessolutions.netprobe.data.toServiceInfo` if the wildcard `data.*` import does not already cover it (it does).

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`: in `removeSnapshotDeleteRestoreKeepsRowsAndIds`, change the port line to `ports.upsertOpen(id, 631, ServiceInfo("IPP", confidence = Confidence.CONFIRMED, title = "HP OfficeJet"))`, so the snapshot round trip covers an evidence column.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.*" --tests "com.northwalessolutions.netprobe.data.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ServiceMergeTest` (6), `PortMappingTest` (2) and the new `BannerParsersTest` test pass; the existing `HostEvidenceBuilderTest` (3), `DeviceIdentifierTest` and `ServiceIdentifierTest` stay green; then `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Port evidence: six ServiceInfo fields, ServiceMerge, header capture, full read and write paths"
```

---

### Task 4: Probe primitives: close-on-cancel sockets, certificate subject fields, X-Powered-By, RTSP and RDP parsers

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/common/CloseOnCancel.kt`
- Modify: `engine/serviceid/BannerReader.kt` (replace), `engine/serviceid/TlsPeek.kt` (replace), `engine/discovery/HttpTitle.kt` (`HttpPeek`, `HttpTitleFetcher`), `engine/serviceid/BannerParsers.kt` (RTSP and RDP), `engine/serviceid/ServiceIdentifier.kt` (TLS branch only)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/CancellationTest.kt`, `TlsSubjectTest.kt`, `ProbeParsersTest.kt`; `engine/discovery/HttpTitleTest.kt` (one test); `engine/serviceid/ServiceIdentifierTest.kt` (TLS fake updated)

**Interfaces:**
- Consumes: `ServiceInfo.server/certCn/certOrg` (Task 3), `Probe.connect`, `currentBinder`, `bindOrFail`.
- Produces:
```kotlin
suspend fun <T> closeOnCancel(close: () -> Unit, block: () -> T): T                       // engine/common
open class BannerReader(timeoutMs: Int = 1000, maxBytes: Int = 1024) { open suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? }
data class CertInfo(val cn: String?, val org: String?, val names: List<String>)
open class TlsPeek { open suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int = TlsPeek.TIMEOUT_MS): CertInfo?
                     companion object { const val TIMEOUT_MS = 1500; fun subjectField(x500: String, key: String): String? } }
data class HttpPeek(val title: String?, val server: String?, val authRealm: String?, val status: Int, val poweredBy: String? = null)
open class HttpTitleFetcher(timeoutMs: Int = 2000, maxBytes: Int = 65536) { open suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? }
BannerParsers.rtspOptions(ip: String, port: Int): ByteArray; BannerParsers.RDP_CONNECTION_REQUEST: ByteArray
BannerParsers.rtspReply(text: String): ServiceInfo?; BannerParsers.rdpConfirm(bytes: ByteArray): ServiceInfo?
```
`TlsPeek.certNames` is removed (its only caller is `ServiceIdentifier`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/CancellationTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** spec 9.1 test 14: Stop must not wait for a socket timeout; a blocking read ignores interrupts, so the socket is closed instead (Gruff m8). */
class CancellationTest {
    private val lo = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val servers = mutableListOf<ServerSocket>()
    private val accepted = mutableListOf<Socket>()
    @After fun tearDown() { synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }; servers.forEach { runCatching { it.close() } } }

    /** Accepts every connection and then says nothing, ever. */
    private fun silent(): Int {
        val s = ServerSocket(0, 50, lo); servers += s
        thread(isDaemon = true) { runCatching { while (true) { val c = s.accept(); synchronized(accepted) { accepted += c } } } }
        return s.localPort
    }
    private fun assertCancelsWithin(limitMs: Long, call: suspend () -> Any?) = runBlocking {
        val job = launch(Dispatchers.IO) { call() }
        delay(BLOCK_MS)                                   // the call is now blocked in a read or a handshake
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms <= limitMs)
    }

    @Test fun `banner read stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { BannerReader(timeoutMs = LONG_MS).read(lo, p) } }
    @Test fun `tls peek stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { TlsPeek().certInfo(lo, p, timeoutMs = LONG_MS) } }
    @Test fun `http fetch stops within 300 ms`() { val p = silent(); assertCancelsWithin(LIMIT_MS) { HttpTitleFetcher(timeoutMs = LONG_MS).fetch(lo, p, tls = false) } }

    private companion object { const val LIMIT_MS = 300L; const val BLOCK_MS = 300L; const val LONG_MS = 10_000 }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/TlsSubjectTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 15: CN and O from representative RFC 2253 subjects, escaped commas included. */
class TlsSubjectTest {
    private val unifi = "CN=unifi.local,OU=UniFi,O=Ubiquiti Inc.,L=New York,ST=New York,C=US"
    @Test fun `cn and o from a unifi console`() {
        assertEquals("unifi.local", TlsPeek.subjectField(unifi, "CN")); assertEquals("Ubiquiti Inc.", TlsPeek.subjectField(unifi, "O"))
    }
    @Test fun `an escaped comma stays inside the value`() {
        assertEquals("Ubiquiti, Inc.", TlsPeek.subjectField("CN=x,O=Ubiquiti\\, Inc.,C=US", "O"))
        assertEquals("a,b", TlsPeek.subjectField("CN=a\\,b,O=Org", "CN"))
    }
    @Test fun `ou is not o and a missing attribute is null`() {
        assertNull(TlsPeek.subjectField("CN=x,OU=Only Unit", "O"))
        assertEquals("Proxmox Virtual Environment", TlsPeek.subjectField("CN=pve.lan,OU=PVE Cluster Node,O=Proxmox Virtual Environment", "O"))
        assertNull(TlsPeek.subjectField("", "CN"))
    }
    @Test fun `values are trimmed and cut to 120 characters`() {
        assertEquals("Synology Inc.", TlsPeek.subjectField("CN=synology, O= Synology Inc. ,C=TW", "O"))
        assertEquals(120, TlsPeek.subjectField("CN=" + "n".repeat(300), "CN")!!.length)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ProbeParsersTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 9. */
class ProbeParsersTest {
    @Test fun `rtsp reply from a hikvision camera`() {
        val r = BannerParsers.rtspReply("RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hikvision/1.0\r\nPublic: OPTIONS, DESCRIBE, PLAY\r\n\r\n")!!
        assertEquals("RTSP", r.name); assertEquals(Confidence.CONFIRMED, r.confidence)
        assertEquals("Hikvision", r.product); assertEquals("1.0", r.version); assertEquals("Hikvision/1.0", r.server)
    }
    @Test fun `non rtsp text is not rtsp`() {
        assertNull(BannerParsers.rtspReply("HTTP/1.1 400 Bad Request\r\n\r\n")); assertNull(BannerParsers.rtspReply(""))
    }
    @Test fun `rtsp options request is exact`() {
        assertEquals("OPTIONS rtsp://10.0.3.40:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n", String(BannerParsers.rtspOptions("10.0.3.40", 554), Charsets.ISO_8859_1))
    }
    @Test fun `rdp connection confirm and three wrong replies`() {
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xE0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00),
            BannerParsers.RDP_CONNECTION_REQUEST)
        val confirm = byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xD0.toByte(), 0x00, 0x00, 0x12, 0x34, 0x00, 0x02, 0x1F, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)
        assertEquals(ServiceInfo("RDP", confidence = Confidence.CONFIRMED), BannerParsers.rdpConfirm(confirm))
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf().also { it[0] = 0x15 }))              // a TLS alert, not TPKT
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf().also { it[5] = 0xE0.toByte() }))     // the request echoed back
        assertNull(BannerParsers.rdpConfirm(confirm.copyOf(5)))                                    // too short to hold the code
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitleTest.kt`, inside the class:
```kotlin
    @Test fun `reads x powered by`() = runBlocking {
        val p = serve { "HTTP/1.1 200 OK\r\nServer: Microsoft-IIS/10.0\r\nX-Powered-By: ASP.NET\r\nContent-Length: 0\r\n\r\n" }
        val r = HttpTitleFetcher().fetch(lo, p, tls = false)!!
        assertEquals("ASP.NET", r.poweredBy); assertEquals("Microsoft-IIS/10.0", r.server)
    }
```

In `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceIdentifierTest.kt`, replace `tlsIdent` and its two tests with:
```kotlin
    private fun tlsIdent(port: Int, info: CertInfo?) = runBlocking {
        val peek = object : TlsPeek() { override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int) = info }
        ServiceIdentifier(table, BannerReader(timeoutMs = 800), peek).identify(lo, port)
    }
    @Test fun `tls failure keeps port table name`() {
        val s = tlsIdent(8006, null)
        assertEquals("Proxmox", s.name); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `tls cert names confirm https and carry cn and o`() {
        val s = tlsIdent(443, CertInfo("router.lan", "Ubiquiti Inc.", listOf("router.lan")))
        assertEquals("HTTPS", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertTrue(s.detail!!.contains("router.lan"))
        assertEquals("router.lan", s.certCn); assertEquals("Ubiquiti Inc.", s.certOrg)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'certInfo'`, `'CertInfo'`, `'subjectField'`, `'rtspReply'`, `'rdpConfirm'`, `'poweredBy'`. (Once these compile against the old socket code, `CancellationTest` would fail with "cancel took ~10000 ms": `runInterruptible` cannot unblock a socket read.)

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/common/CloseOnCancel.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs a blocking socket call on Dispatchers.IO and closes the socket (or disconnects the connection) from invokeOnCancellation,
 * so Stop returns at once: a blocking read ignores thread interrupts on Android, so runInterruptible alone waits for the socket
 * timeout (Phase 3A spec 2.2.5, Gruff m8). After a cancellation the call's result or exception is dropped.
 */
suspend fun <T> closeOnCancel(close: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching(close) }
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { cont.resumeWith(runCatching(block)) })
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/BannerReader.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.Probe
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Open so the probe-ladder tests can record every byte sent (Task 5). */
open class BannerReader(val timeoutMs: Int = 1000, val maxBytes: Int = 1024) {
    /** Connects, optionally sends [sendFirst], reads up to maxBytes or until timeout. Null when nothing readable. Stop closes the socket at once. */
    open suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray? = null): ByteArray? {
        val o = Probe.connect(ip, port, timeoutMs, keepOpen = true) as? Probe.Outcome.Connected ?: return null
        val socket = o.socket ?: return null
        return try {
            closeOnCancel({ socket.close() }) { readBlocking(socket, sendFirst) }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { runCatching { socket.close() } }
    }

    private fun readBlocking(socket: Socket, sendFirst: ByteArray?): ByteArray? {
        socket.soTimeout = timeoutMs
        sendFirst?.let { socket.getOutputStream().write(it); socket.getOutputStream().flush() }
        val buf = ByteArray(maxBytes); var n = 0
        val input = socket.getInputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (n < maxBytes && System.currentTimeMillis() < deadline) {
                val r = input.read(buf, n, maxBytes - n)
                if (r < 0) break
                n += r
                if (sendFirst == null && n > 0 && buf.copyOf(n).contains('\n'.code.toByte())) break
            }
        } catch (_: SocketTimeoutException) { }
        return if (n == 0) null else buf.copyOf(n)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/TlsPeek.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.bindOrFail
import com.northwalessolutions.netprobe.engine.common.closeOnCancel
import com.northwalessolutions.netprobe.engine.common.currentBinder
import kotlinx.coroutines.CancellationException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** The leaf certificate's subject CN and O, and CN plus SANs for the "cert ..." detail (spec 3.1). */
data class CertInfo(val cn: String?, val org: String?, val names: List<String>)

/** Reads certificate names for identification only. Trust-all is never used for the browser launch. */
open class TlsPeek {
    open suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int = TIMEOUT_MS): CertInfo? {
        val route = currentBinder()   // an off-subnet port scan's bound network (final review I2); a failed bind fails closed (null)
        val s = try { factory.createSocket() as SSLSocket } catch (_: Exception) { return null }
        return try {
            closeOnCancel({ s.close() }) {
                route?.bindOrFail(s)
                s.connect(InetSocketAddress(ip, port), timeoutMs); s.soTimeout = timeoutMs
                s.startHandshake()
                val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert == null) CertInfo(null, null, emptyList()) else {
                    val subject = cert.subjectX500Principal.name
                    val cn = subjectField(subject, "CN"); val org = subjectField(subject, "O")
                    val sans = runCatching { cert.subjectAlternativeNames?.mapNotNull { e -> e.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
                    CertInfo(cn, org, (listOfNotNull(cn) + sans).distinct())
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { null
        } finally { runCatching { s.close() } }
    }

    companion object {
        const val TIMEOUT_MS = 1500
        private const val MAX_VALUE = 120
        private val unescape = Regex("\\\\(.)")
        private val factory: SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>() }), null) }.socketFactory
        }
        /** One attribute of an RFC 2253 subject ("CN=a,O=Ubiquiti\, Inc.,C=US"); escaped characters stay in the value; trimmed, at most 120 characters. */
        fun subjectField(x500: String, key: String): String? =
            Regex("(?:^|,)\\s*$key=((?:\\\\.|[^,\\\\])+)").find(x500)?.groupValues?.get(1)
                ?.replace(unescape, "\$1")?.trim()?.take(MAX_VALUE)?.ifEmpty { null }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/discovery/HttpTitle.kt`:
- Replace `data class HttpPeek(...)` with `data class HttpPeek(val title: String?, val server: String?, val authRealm: String?, val status: Int, val poweredBy: String? = null)`.
- Replace the `class HttpTitleFetcher` header and `fetch` with (the companion and `trustAll` are unchanged; add `MAX_HEADER_CHARS = 120` to the companion; import `com.northwalessolutions.netprobe.engine.common.closeOnCancel` and `java.util.concurrent.atomic.AtomicReference`; remove the now unused `Dispatchers` and `runInterruptible` imports):
```kotlin
/** Open so the probe-ladder tests can fake the GET (Task 5). */
open class HttpTitleFetcher(private val timeoutMs: Int = 2000, private val maxBytes: Int = 65536) {
```
```kotlin
    open suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
        val binder = currentBinder()
        val live = AtomicReference<HttpURLConnection?>(null)
        return try {
            // A blocking read ignores interrupts on Android: Stop disconnects the live connection instead (spec 2.2.5).
            closeOnCancel({ live.get()?.disconnect() }) { fetchBlocking(ip, port, tls, binder, live) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
```
- In `fetchBlocking`, add the parameter `live: AtomicReference<HttpURLConnection?>`, call `live.set(c)` right after the connection `c` is created, and change the return to:
```kotlin
                return HttpPeek(HttpTitleParser.title(html), c.getHeaderField("Server"), HttpTitleParser.realm(c.getHeaderField("WWW-Authenticate")), status,
                    c.getHeaderField("X-Powered-By")?.trim()?.take(MAX_HEADER_CHARS)?.ifEmpty { null })
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/BannerParsers.kt`: add inside the object:
```kotlin
    /** RTSP OPTIONS probe (spec 2.2.3). */
    fun rtspOptions(ip: String, port: Int): ByteArray = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    /** The 19-byte X.224 Connection Request (spec 2.2.3). */
    val RDP_CONNECTION_REQUEST: ByteArray =
        byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xE0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x08, 0x00, 0x03, 0x00, 0x00, 0x00)
    private const val TPKT_VERSION = 0x03
    private const val X224_CODE_INDEX = 5
    private const val X224_CONNECTION_CONFIRM = 0xD0
    private const val BYTE_MASK = 0xFF

    /** "RTSP/1.0 ..." confirms RTSP; its Server header fills product and version and is kept whole in server. */
    fun rtspReply(text: String): ServiceInfo? {
        val lines = text.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("RTSP/1.0") != true) return null
        val server = lines.drop(1).takeWhile { it.isNotBlank() }
            .firstOrNull { it.substringBefore(':').trim().equals("server", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.take(MAX_HEADER)?.ifEmpty { null }
        val (product, version) = server?.let { serverHeader(it) } ?: (null to null)
        return ServiceInfo("RTSP", product, version, null, Confidence.CONFIRMED, server = server)
    }
    /** TPKT header 03 00 and X.224 code D0 (Connection Confirm) at byte 5 confirm RDP. */
    fun rdpConfirm(bytes: ByteArray): ServiceInfo? {
        if (bytes.size <= X224_CODE_INDEX) return null
        if ((bytes[0].toInt() and BYTE_MASK) != TPKT_VERSION || bytes[1].toInt() != 0) return null
        if ((bytes[X224_CODE_INDEX].toInt() and BYTE_MASK) != X224_CONNECTION_CONFIRM) return null
        return ServiceInfo("RDP", confidence = Confidence.CONFIRMED)
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceIdentifier.kt`: replace the `port in TLS -> { ... }` branch with (Passive behaviour unchanged; Task 5 rewrites the file):
```kotlin
            port in TLS -> tls.certInfo(ip, real)?.let { c ->
                ServiceInfo("HTTPS", detail = c.names.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
                    confidence = Confidence.CONFIRMED, certCn = c.cn, certOrg = c.org)
            }
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `CancellationTest` (3), `TlsSubjectTest` (4), `ProbeParsersTest` (4), the new `HttpTitleTest` test and the updated `ServiceIdentifierTest` pass, with every existing engine test; then `BUILD SUCCESSFUL`. If `http fetch stops within 300 ms` fails on the JVM while the other two pass, the JDK's `disconnect()` did not unblock the read: report the measured time and stop; do not raise the limit.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Probe primitives: close-on-cancel sockets, certificate CN and O, X-Powered-By, RTSP and RDP parsers"
```

---

### Task 5: The probe ladder: profile gate, passive-only ports, ActiveProbeBudget, RTSP and RDP probes

**Roles:** stronger model (implement and review).

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ActiveProbeBudget.kt`, `ui/home/QuickScanAll.kt`
- Modify: `engine/serviceid/ServiceIdentifier.kt` (replace), `ui/detail/DetailViewModel.kt` (`scanHostOn`), `ui/home/HomeViewModel.kt` (`quickScanHostOn`)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceIdentifierLadderTest.kt`, `ActiveProbeBudgetTest.kt`

**Interfaces:**
- Consumes: `BannerReader`, `TlsPeek.certInfo`, `HttpTitleFetcher.fetch`, `BannerParsers.rtspOptions/rtspReply/RDP_CONNECTION_REQUEST/rdpConfirm/httpHead/serverHeader` (Task 4), `ScanProfile`.
- Produces:
```kotlin
const val MAX_ACTIVE_PORTS = 64
const val MAX_ACTIVE_MS = 60_000L
sealed interface ProbeMode { data object Passive : ProbeMode; class Active(val budget: ActiveProbeBudget) : ProbeMode }
class ActiveProbeBudget(val maxPorts: Int = MAX_ACTIVE_PORTS, val maxMs: Long = MAX_ACTIVE_MS, clock: () -> Long = SystemClock::elapsedRealtime) {
    val spentMs: Long; val enteredPorts: Int
    fun tryEnter(port: Int): Boolean; fun allowsStep(): Boolean; suspend fun <T> step(block: suspend () -> T): T?
}
object ProbeModes { fun forProfile(profile: ScanProfile, newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget() }): ProbeMode }
ServiceIdentifier(table, reader = BannerReader(timeoutMs = BANNER_TIMEOUT_MS), tls = TlsPeek(), http = HttpTitleFetcher(timeoutMs = HTTP_FETCH_TIMEOUT_MS), portOverride = emptyMap())
ServiceIdentifier.identify(ip, port, evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive): ServiceInfo
ServiceIdentifier.PASSIVE_ONLY, HEAD_ONLY, RTSP, RDP: Set<Int>; BANNER_TIMEOUT_MS = 1000, TLS_TIMEOUT_MS = 1500, HTTP_FETCH_TIMEOUT_MS = 2000
object QuickScanAllPolicy { val profile: ScanProfile; fun mode(): ProbeMode }      // ui/home
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ActiveProbeBudgetTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActiveProbeBudgetTest {
    private var now = 0L
    private val b = ActiveProbeBudget(maxPorts = 2, maxMs = 1_000) { now }

    @Test fun `a port enters once and the port cap holds`() {
        assertTrue(b.tryEnter(1)); assertTrue(b.tryEnter(1)); assertTrue(b.tryEnter(2)); assertFalse(b.tryEnter(3))
        assertEquals(2, b.enteredPorts)
    }
    @Test fun `a step adds its own wall time, a started step finishes, then nothing starts`() = runBlocking {
        assertEquals("a", b.step { now += 600; "a" }); assertEquals(600L, b.spentMs); assertTrue(b.allowsStep())
        assertEquals("b", b.step { now += 600; "b" }); assertEquals(1_200L, b.spentMs)
        assertFalse(b.allowsStep()); assertNull(b.step { fail("must not run"); "c" }); assertFalse(b.tryEnter(1))
    }
    @Test fun `defaults are the spec's caps`() { assertEquals(64, MAX_ACTIVE_PORTS); assertEquals(60_000L, MAX_ACTIVE_MS) }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceIdentifierLadderTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.home.QuickScanAllPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** The probe ladder against recording fakes: what is sent to which port, in which order, under which budget (spec 2.2, 9.1 tests 10 to 13). */
class ServiceIdentifierLadderTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val table = PortTable.fromJson("""[{"port":80,"name":"HTTP"},{"port":443,"name":"HTTPS"},{"port":554,"name":"RTSP"},{"port":3389,"name":"RDP"},
        {"port":9100,"name":"JetDirect"},{"port":631,"name":"IPP"},{"port":515,"name":"LPD"}]""")
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val clock = AtomicLong(0)
    /** Fake time each TLS peek, HEAD, probe and GET costs; 0 unless a test sets it. */
    private var stepCostMs = 0L
    /** The budget under watch: every active-capable call records how much was spent when it started. */
    private var watched: ActiveProbeBudget? = null
    private val spentAtCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private fun kind(sent: ByteArray): String {
        val t = String(sent, Charsets.ISO_8859_1)
        return when { t.startsWith("HEAD") -> "HEAD"; t.startsWith("OPTIONS") -> "OPTIONS"; sent.firstOrNull() == 0x03.toByte() -> "X224"; else -> "BYTES" }
    }
    private fun costed() { watched?.let { spentAtCalls += it.spentMs }; clock.addAndGet(stepCostMs) }

    private inner class Reader(val passive: (Int) -> ByteArray? = { null }, val reply: (Int, String) -> ByteArray? = { _, _ -> null }) : BannerReader() {
        override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? {
            if (sendFirst == null) { calls += "read:$port"; return passive(port) }
            val k = kind(sendFirst); calls += "send:$port:$k"; costed()
            return reply(port, k)
        }
    }
    private inner class Tls(val answer: (Int) -> CertInfo? = { null }) : TlsPeek() {
        override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { calls += "tls:$port"; costed(); return answer(port) }
    }
    private inner class Http(val answer: (Int, Boolean) -> HttpPeek? = { _, _ -> null }) : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
            calls += "get:$port:${if (tls) "https" else "http"}"; costed(); return answer(port, tls)
        }
    }
    private fun identifier(reader: BannerReader = Reader(), tls: TlsPeek = Tls(), http: HttpTitleFetcher = Http()) = ServiceIdentifier(table, reader, tls, http)
    private fun active(): ProbeMode.Active = ProbeMode.Active(ActiveProbeBudget(MAX_ACTIVE_PORTS, MAX_ACTIVE_MS) { clock.get() }).also { watched = it.budget }
    private fun run(id: ServiceIdentifier, port: Int, mode: ProbeMode) = runBlocking { id.identify(ip, port, Evidence(), mode) }

    private val okHead = "HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    private val unifiCert = CertInfo("unifi.local", "Ubiquiti Inc.", listOf("unifi.local"))
    private val unknown7000 = ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE)

    // ---- the ladder in Active mode (9.1 test 10) ----
    @Test fun `silent unknown port that answers HEAD becomes HTTP with a title`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }),
            http = Http { _, tls -> if (!tls) HttpPeek("Synology DiskStation", "nginx/1.25.3", null, 200) else null }), 7000, active())
        assertEquals("HTTP", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("Synology DiskStation", s.title); assertEquals("nginx/1.25.3", s.server)
        assertEquals(listOf("read:7000", "tls:7000", "send:7000:HEAD", "get:7000:http"), calls)
    }
    @Test fun `silent unknown port that completes tls and answers GET becomes HTTPS with cert fields`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, tls -> if (tls) HttpPeek("UniFi OS", null, null, 200) else null }), 9443, active())
        assertEquals("HTTPS", s.name); assertEquals("unifi.local", s.certCn); assertEquals("Ubiquiti Inc.", s.certOrg); assertEquals("UniFi OS", s.title)
        assertEquals(listOf("read:9443", "tls:9443", "get:9443:https"), calls)
    }
    @Test fun `a port that completes tls but answers no GET is named TLS`() {
        val s = run(identifier(tls = Tls { CertInfo("mqtt.lan", null, listOf("mqtt.lan")) }), 8883, active())
        assertEquals("TLS", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("mqtt.lan", s.certCn); assertEquals("cert mqtt.lan", s.detail)
    }
    @Test fun `a port that sent one unprintable byte goes to no active step`() {
        val s = run(identifier(Reader(passive = { byteArrayOf(0x15) })), 7000, active())
        assertEquals(listOf("read:7000"), calls); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `a tls set port is never sent a plain HEAD first`() {
        run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null })), 443, active())
        assertEquals("tls:443", calls.first()); assertTrue(calls.indexOf("send:443:HEAD") > calls.indexOf("tls:443"))
    }
    @Test fun `an http set port that fails HEAD falls through to tls`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("UniFi OS", null, null, 200) }), 80, active())
        assertEquals(listOf("send:80:HEAD", "tls:80", "get:80:https"), calls); assertEquals("HTTPS", s.name)
    }
    @Test fun `a silent port with nothing stays at port table confidence`() {
        assertEquals(unknown7000, run(identifier(), 7000, active()))
        assertEquals(listOf("read:7000", "tls:7000", "send:7000:HEAD"), calls)
    }
    @Test fun `rtsp and rdp get their own probe in active mode`() {
        val rtsp = run(identifier(Reader(reply = { _, k ->
            if (k == "OPTIONS") "RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hikvision/1.0\r\n\r\n".toByteArray(Charsets.ISO_8859_1) else null })), 554, active())
        assertEquals("RTSP", rtsp.name); assertEquals("Hikvision/1.0", rtsp.server)
        val rdp = run(identifier(Reader(reply = { _, k -> if (k == "X224") byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xD0.toByte()) else null })), 3389, active())
        assertEquals("RDP", rdp.name)
        assertEquals(listOf("read:554", "send:554:OPTIONS", "read:3389", "send:3389:X224"), calls)
    }

    // ---- the profile gate (9.1 test 11, Review Focus 2) ----
    @Test fun `passive mode sends nothing new to a silent unknown port`() {
        assertEquals(unknown7000, run(identifier(tls = Tls { unifiCert }), 7000, ProbeMode.Passive))
        assertEquals(listOf("read:7000"), calls)
    }
    @Test fun `passive mode gives an http set port exactly one HEAD and no GET`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }), http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 80, ProbeMode.Passive)
        assertEquals(listOf("send:80:HEAD"), calls); assertEquals("HTTP", s.name); assertNull(s.title)
    }
    @Test fun `passive mode gives a tls set port today's peek and no GET`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 443, ProbeMode.Passive)
        assertEquals(listOf("tls:443"), calls); assertEquals("HTTPS", s.name); assertEquals("Ubiquiti Inc.", s.certOrg); assertNull(s.title)
    }
    @Test fun `passive mode gives rtsp and rdp only the passive read`() {
        run(identifier(), 554, ProbeMode.Passive); run(identifier(), 3389, ProbeMode.Passive)
        assertEquals(listOf("read:554", "read:3389"), calls)
    }
    @Test fun `quick scan all is passive`() {
        assertSame(ScanProfile.Quick, QuickScanAllPolicy.profile); assertSame(ProbeMode.Passive, QuickScanAllPolicy.mode())
        assertSame(ProbeMode.Passive, ProbeModes.forProfile(ScanProfile.Quick))
        assertTrue(ProbeModes.forProfile(ScanProfile.Full) is ProbeMode.Active)
        assertTrue(ProbeModes.forProfile(ScanProfile.Custom(listOf(80))) is ProbeMode.Active)
        val a = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active; val b = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active
        assertNotSame("one budget per scan, never shared", a.budget, b.budget)
    }

    // ---- passive-only ports (9.1 test 12, Review Focus 1) ----
    @Test fun `passive only and head only sets are pinned`() {
        assertEquals(setOf(25, 110, 143, 515, 2000, 2001) + (6000..6063).toSet() + (9100..9107).toSet(), ServiceIdentifier.PASSIVE_ONLY)
        assertEquals(setOf(631), ServiceIdentifier.HEAD_ONLY)
    }
    @Test fun `passive only ports never receive a byte in either mode`() {
        val strictReader = object : BannerReader() {
            override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? {
                if (sendFirst != null) fail("wrote ${sendFirst.size} bytes to port $port")
                calls += "read:$port"; return null
            }
        }
        val noTls = object : TlsPeek() { override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { fail("ClientHello sent to port $port"); return null } }
        val noGet = object : HttpTitleFetcher() { override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? { fail("GET sent to port $port"); return null } }
        val id = ServiceIdentifier(table, strictReader, noTls, noGet)
        for (mode in listOf(ProbeMode.Passive, active())) for (port in listOf(9100, 9107, 515, 2000, 6000, 6063, 25, 110, 143)) {
            calls.clear()
            run(id, port, mode)
            assertEquals("port $port in $mode", listOf("read:$port"), calls)
        }
    }
    @Test fun `ipp gets one head in active mode and nothing more`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") "HTTP/1.1 200 OK\r\nServer: CUPS/2.4 IPP/2.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1) else null }),
            tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 631, active())
        assertEquals(listOf("read:631", "send:631:HEAD"), calls); assertEquals("CUPS/2.4 IPP/2.1", s.server)
        calls.clear(); run(identifier(), 631, ProbeMode.Passive); assertEquals(listOf("read:631"), calls)
    }

    // ---- the budget (9.1 test 13, Review Focus 2) ----
    @Test fun `no more than 64 ports ever enter the ladder`() {
        val mode = active(); val id = identifier()                      // steps cost nothing: only the port cap can stop the ladder
        for (p in 20000 until 20100) run(id, p, mode)
        assertEquals(64, calls.count { it.startsWith("tls:") }); assertEquals(64, mode.budget.enteredPorts)
        assertEquals(100, calls.count { it.startsWith("read:") })      // every port still got its passive read
    }
    @Test fun `no step starts once 60 s have been spent and refused ports stay open and unknown`() {
        stepCostMs = 1_000                                              // every TLS peek and HEAD costs 1 s of fake time
        val mode = active(); val id = identifier()
        val results = (20000 until 20100).map { p -> run(id, p, mode) }
        assertTrue("a step started after the budget was spent: $spentAtCalls", spentAtCalls.all { it < MAX_ACTIVE_MS })
        assertEquals(30, calls.count { it.startsWith("tls:") })        // a silent port costs 2 s (TLS then HEAD): 30 ports fill 60 s
        assertEquals(60_000L, mode.budget.spentMs)
        results.forEach { assertEquals(ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE), it) }
        assertEquals(100, calls.count { it.startsWith("read:") })
    }
    @Test fun `today's steps are not counted against the budget`() {
        stepCostMs = 1_000
        val mode = active()
        run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }), http = Http { _, _ -> HttpPeek("t", null, null, 200) }), 80, mode)
        assertEquals("the HEAD on the HTTP set is today's step; only the GET counts", 1_000L, mode.budget.spentMs)
        run(identifier(tls = Tls { unifiCert }), 443, mode)
        assertEquals("the TLS peek on the TLS set is today's step; only the GET counts", 2_000L, mode.budget.spentMs)
    }
    @Test fun `eight parallel identifies overshoot by at most eight steps`() = runBlocking {
        val longest = AtomicLong(0)
        suspend fun slow() { val t0 = System.nanoTime(); delay(SLOW_STEP_MS); longest.accumulateAndGet((System.nanoTime() - t0) / 1_000_000) { a, b -> maxOf(a, b) } }
        val reader = object : BannerReader() { override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? { if (sendFirst != null) slow(); return null } }
        val tls = object : TlsPeek() { override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { slow(); return null } }
        val budget = ActiveProbeBudget(MAX_ACTIVE_PORTS, PARALLEL_BUDGET_MS) { System.nanoTime() / 1_000_000 }
        val id = ServiceIdentifier(table, reader, tls, Http())
        val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
        coroutineScope { for (p in 20000 until 20100) launch(Dispatchers.Default) { sem.withPermit { id.identify(ip, p, Evidence(), ProbeMode.Active(budget)) } } }
        val bound = PARALLEL_BUDGET_MS + ServiceIdentifier.MAX_PARALLEL_IDENTIFIES * (longest.get() + SCHEDULING_SLACK_MS)
        assertTrue("the budget never filled: ${budget.spentMs} ms", budget.spentMs >= PARALLEL_BUDGET_MS)
        assertTrue("spent ${budget.spentMs} ms, bound $bound ms", budget.spentMs <= bound)
    }

    private companion object { const val SLOW_STEP_MS = 20L; const val PARALLEL_BUDGET_MS = 300L; const val SCHEDULING_SLACK_MS = 30L }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.serviceid.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ActiveProbeBudget'` (and `ProbeMode`, `ProbeModes`, `QuickScanAllPolicy`, `MAX_ACTIVE_PORTS`, `PASSIVE_ONLY`), and `Too many arguments for constructor ServiceIdentifier` for the fourth argument.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ActiveProbeBudget.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import android.os.SystemClock
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import java.util.concurrent.atomic.AtomicLong

const val MAX_ACTIVE_PORTS = 64
const val MAX_ACTIVE_MS = 60_000L

/** What an identify may send (spec 2.2.1). */
sealed interface ProbeMode {
    /** Detail Quick and every host of Quick scan all: exactly what was sent before Phase 3A. */
    data object Passive : ProbeMode
    /** Detail Full and Custom: today's steps, then the active ladder under one budget per scan. */
    class Active(val budget: ActiveProbeBudget) : ProbeMode
}

/**
 * One per Detail Full or Custom scan, shared by its identifies, never persisted (spec 2.2.4). A port enters once before its first
 * active step; every active step runs through [step], which refuses to start once [maxMs] of summed probe time is spent and
 * otherwise adds the step's own wall time. A started step finishes, so with 8 parallel identifies the overshoot is at most 8 steps.
 */
class ActiveProbeBudget(val maxPorts: Int = MAX_ACTIVE_PORTS, val maxMs: Long = MAX_ACTIVE_MS, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val spent = AtomicLong(0)
    private val entered = HashSet<Int>()
    val spentMs: Long get() = spent.get()
    val enteredPorts: Int get() = synchronized(entered) { entered.size }

    fun allowsStep(): Boolean = spent.get() < maxMs

    fun tryEnter(port: Int): Boolean {
        if (!allowsStep()) return false
        synchronized(entered) {
            if (port in entered) return true
            if (entered.size >= maxPorts) return false
            entered += port
            return true
        }
    }

    suspend fun <T> step(block: suspend () -> T): T? {
        if (!allowsStep()) return null
        val t0 = clock()
        try { return block() } finally { spent.addAndGet(clock() - t0) }
    }
}

object ProbeModes {
    /** Quick is passive, and so is every host of Quick scan all; Full and Custom get a fresh budget per scan (spec 2.2.1). */
    fun forProfile(profile: ScanProfile, newBudget: () -> ActiveProbeBudget = { ActiveProbeBudget() }): ProbeMode =
        if (profile is ScanProfile.Quick) ProbeMode.Passive else ProbeMode.Active(newBudget())
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/QuickScanAll.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.ProbeMode
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes

/** Quick scan all always uses Quick, so it never runs the active ladder (spec 2.2.1, Gruff M3). */
object QuickScanAllPolicy {
    val profile: ScanProfile = ScanProfile.Quick
    fun mode(): ProbeMode = ProbeModes.forProfile(profile)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/serviceid/ServiceIdentifier.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

class ServiceIdentifier(
    private val table: PortTable,
    private val reader: BannerReader = BannerReader(timeoutMs = BANNER_TIMEOUT_MS),
    private val tls: TlsPeek = TlsPeek(),
    private val http: HttpTitleFetcher = HttpTitleFetcher(timeoutMs = HTTP_FETCH_TIMEOUT_MS),
    /** Test hook: logical port -> real port to connect to. Empty in production. */
    private val portOverride: Map<Int, Int> = emptyMap(),
) {
    companion object {
        val SPEAKS_FIRST = setOf(21, 22, 25, 110, 143, 587, 2222)
        val HTTP = setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400)
        val TLS = setOf(443, 8443, 7443, 8006, 5001)
        val RTSP = setOf(554, 8554)
        val RDP = setOf(3389)
        /** Never sent an active probe: raw print, LPD, Cisco SCCP, X11, and the mail ports that greet first (banner only). */
        val PASSIVE_ONLY: Set<Int> = setOf(25, 110, 143, 515, 2000, 2001) + (6000..6063) + (9100..9107)
        /** IPP: a plain HEAD is safe and useful; never a TLS ClientHello, never a GET. */
        val HEAD_ONLY: Set<Int> = setOf(631)
        const val BANNER_TIMEOUT_MS = 1000
        const val TLS_TIMEOUT_MS = 1500
        const val HTTP_FETCH_TIMEOUT_MS = 2000
        /** Upper bound on concurrent identify() calls made by the scan consumers. */
        const val MAX_PARALLEL_IDENTIFIES = 8
        private const val MAX_HEADER = 120
        private val mdnsConfirms = mapOf("_rfb._tcp" to (5900..5910 to "VNC"), "_ipp._tcp" to (631..631 to "IPP"),
            "_ssh._tcp" to (22..22 to "SSH"), "_http._tcp" to (80..80 to "HTTP"), "_smb._tcp" to (445..445 to "SMB"))
    }

    /**
     * Runs on Dispatchers.IO so ViewModels on Main can call it. [mode] gates the active ladder (spec 2.2.1): Passive sends exactly
     * what was sent before Phase 3A; Active adds the steps of spec 2.2.3 under the scan's budget.
     */
    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive): ServiceInfo = withContext(Dispatchers.IO) {
        val baseName = table.nameFor(port)
        val base = ServiceInfo(baseName, confidence = Confidence.PORT_TABLE)
        val ladder = Ladder(ip, port, portOverride[port] ?: port, baseName, (mode as? ProbeMode.Active)?.budget)
        val fromProbe: ServiceInfo? = when (port) {
            in SPEAKS_FIRST, in PASSIVE_ONLY -> ladder.passiveOnly(speaksFirst = port in SPEAKS_FIRST)
            in HEAD_ONLY -> ladder.headOnly()
            in HTTP -> ladder.httpSet()
            in TLS -> ladder.tlsSet()
            in RTSP -> ladder.rtsp()
            in RDP -> ladder.rdp()
            else -> ladder.unknown()
        }
        val mdns = mdnsConfirms.entries.firstOrNull { (t, r) -> t in evidence.mdnsTypes && port in r.first }
        when {
            fromProbe != null && fromProbe.confidence == Confidence.CONFIRMED -> fromProbe
            mdns != null -> ServiceInfo(mdns.value.second, detail = "mDNS ${mdns.key}", confidence = Confidence.CONFIRMED)
            fromProbe != null -> fromProbe
            evidence.upnpServer != null && port in HTTP -> base.copy(detail = evidence.upnpServer, confidence = Confidence.CONFIRMED)
            else -> base
        }
    }

    /** One identify's probe sequence (spec 2.2.3). Every active step goes through [active], so Passive mode can never send one. */
    private inner class Ladder(private val ip: InetAddress, private val port: Int, private val real: Int, private val baseName: String,
                               private val budget: ActiveProbeBudget?) {
        private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)
        private fun headRequest() = "HEAD / HTTP/1.0\r\nHost: ${ip.hostAddress}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        /** Null without sending anything in Passive mode, when the port is refused entry, or once the time is spent (spec 2.2.4). */
        private suspend fun <T> active(block: suspend () -> T?): T? {
            val b = budget ?: return null
            if (!b.tryEnter(port)) return null
            return b.step(block)
        }
        private suspend fun passiveRead(): ByteArray? = reader.read(ip, real)
        private suspend fun head(): ServiceInfo? = reader.read(ip, real, headRequest())?.let { BannerParsers.httpHead(text(it)) }
        private suspend fun peek(): CertInfo? = tls.certInfo(ip, real, TLS_TIMEOUT_MS)
        private fun tlsInfo(c: CertInfo, name: String) = ServiceInfo(name, detail = c.names.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
            confidence = Confidence.CONFIRMED, certCn = c.cn, certOrg = c.org)
        /** One GET of "/" for the title and headers: an active step; null unless it answered with a status line. */
        private suspend fun get(useTls: Boolean): HttpPeek? {
            val v4 = ip as? Inet4Address ?: return null
            return active { http.fetch(v4, real, useTls) }?.takeIf { it.status > 0 }
        }

        suspend fun passiveOnly(speaksFirst: Boolean): ServiceInfo? = passiveRead()?.let { b ->
            if (speaksFirst) { val line = text(b).lineSequence().first(); BannerParsers.ssh(line) ?: BannerParsers.greeting(line, baseName) }
            else BannerParsers.classifyUnknown(b, baseName)
        }
        suspend fun headOnly(): ServiceInfo? {
            val passive = passiveRead()?.let { BannerParsers.classifyUnknown(it, baseName) }
            if (passive?.confidence == Confidence.CONFIRMED) return passive
            return active { head() } ?: passive
        }
        suspend fun httpSet(): ServiceInfo? {
            val h = head()                                        // today's step: never enters, never spends
            if (h != null) return withPeek(h, get(useTls = false))
            val cert = active { peek() } ?: return null
            return tlsThenGet(cert)
        }
        suspend fun tlsSet(): ServiceInfo? {
            val cert = peek()                                     // today's step: never enters, never spends
            if (cert != null) return withPeek(tlsInfo(cert, "HTTPS"), get(useTls = true))
            val h = active { head() } ?: return null
            return withPeek(h, get(useTls = false))
        }
        suspend fun rtsp(): ServiceInfo? = probeAfterPassive {
            reader.read(ip, real, BannerParsers.rtspOptions(ip.hostAddress ?: "", port))?.let { BannerParsers.rtspReply(text(it)) }
        }
        suspend fun rdp(): ServiceInfo? = probeAfterPassive { reader.read(ip, real, BannerParsers.RDP_CONNECTION_REQUEST)?.let { BannerParsers.rdpConfirm(it) } }
        private suspend fun probeAfterPassive(probe: suspend () -> ServiceInfo?): ServiceInfo? {
            val passive = passiveRead()?.let { BannerParsers.classifyUnknown(it, baseName) }
            if (passive?.confidence == Confidence.CONFIRMED) return passive
            return active { probe() } ?: passive
        }
        suspend fun unknown(): ServiceInfo? {
            val bytes = passiveRead()
            // Any byte at all means it spoke first: today's result, never TLS ("zero bytes" is exact, spec 2.2.3).
            if (bytes != null) return BannerParsers.classifyUnknown(bytes, baseName)
            // TLS before HEAD: a plain HEAD to a TLS port often comes back as an HTTP 400 and would be misread as HTTP.
            val cert = active { peek() }
            if (cert != null) return tlsThenGet(cert)
            val h = active { head() } ?: return null
            return withPeek(h, get(useTls = false))
        }
        /** A handshake on a port outside the TLS set: HTTPS when its GET answers with a status line, TLS when not. */
        private suspend fun tlsThenGet(cert: CertInfo): ServiceInfo {
            val p = get(useTls = true)
            return withPeek(tlsInfo(cert, if (p != null) "HTTPS" else "TLS"), p)
        }
        private fun withPeek(info: ServiceInfo, p: HttpPeek?): ServiceInfo {
            if (p == null) return info
            val server = p.server?.take(MAX_HEADER)
            val (product, version) = server?.let { BannerParsers.serverHeader(it) } ?: (null to null)
            return info.copy(product = info.product ?: product, version = info.version ?: version, title = p.title ?: info.title,
                server = server ?: info.server, realm = p.authRealm?.take(MAX_HEADER) ?: info.realm, poweredBy = p.poweredBy?.take(MAX_HEADER) ?: info.poweredBy)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: in `scanHostOn`, add `val mode = ProbeModes.forProfile(profile)   // one budget per Full or Custom scan, shared by its identifies` after `val found = ...`, and change the identify call to `c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), mode)`. Import `com.northwalessolutions.netprobe.engine.serviceid.ProbeModes`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: in `quickScanHostOn`, change `ScanProfile.Quick` to `QuickScanAllPolicy.profile` and the identify call to `c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), QuickScanAllPolicy.mode())`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.serviceid.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `ActiveProbeBudgetTest` (3) and `ServiceIdentifierLadderTest` (20) pass; the existing `ServiceIdentifierTest` (real loopback sockets, Passive by default) stays green; then `BUILD SUCCESSFUL`. A reviewer checks that no test was relaxed: the expected call lists are the contract.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Probe ladder: profile gate, passive-only ports, active probe budget, RTSP and RDP probes"
```

---

### Task 6: Identification from port evidence: new rule fields, onPorts, software, port reasons, router vendor spelling

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/engine/identify/IdentifyRules.kt` (`Rule`), `engine/identify/DeviceIdentifier.kt` (replace)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierPortEvidenceTest.kt`

**Interfaces:**
- Consumes: `ServiceInfo` evidence fields (Task 3), `DeviceIdentity.software` (Task 2).
- Produces:
```kotlin
data class Rule(..., val onPorts: List<Int>? = null, val software: String? = null)   // appended after sample
DeviceIdentifier.identify(e: HostEvidence): DeviceIdentity   // now reads port evidence; reasons "port P field: X"; fills software
// Fields: httpTitle, httpServer, httpRealm (discovery first unless onPorts, then ports ascending), sshBanner (every SSH port),
// poweredBy, certName, certOrg, portBanner, service (CONFIRMED only)
```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierPortEvidenceTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 17, with inline rules so each field is tested apart from the rules file. */
class DeviceIdentifierPortEvidenceTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val host = DiscoveredHost(ip)
    private fun rule(id: String, field: String, pattern: String? = null, onPorts: List<Int>? = null, vendor: String? = null, model: String? = null,
                     os: String? = null, type: String? = null, software: String? = null, weight: Int = 70, anyPort: List<Int>? = null) =
        Rule(id = id, field = field, pattern = pattern, anyPort = anyPort, vendor = vendor, model = model, os = os, type = type, weight = weight,
            onPorts = onPorts, software = software)
    private fun ev(services: Map<Int, ServiceInfo>, h: DiscoveredHost = host, extraPorts: Set<Int> = emptySet()) = HostEvidence(h, services.keys + extraPorts, services, null)
    private fun http(title: String? = null, server: String? = null, realm: String? = null, poweredBy: String? = null) =
        ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title, server = server, realm = realm, poweredBy = poweredBy)
    private fun one(r: Rule, e: HostEvidence) = DeviceIdentifier(listOf(r)).identify(e)

    @Test fun `httpTitle matches a port title and the reason names the port`() {
        val r = one(rule("t", "httpTitle", "Synology", vendor = "Synology"), ev(mapOf(5000 to http(title = "DiskStation - Synology DiskStation"))))
        assertEquals("Synology", r.vendor); assertEquals(listOf("port 5000 httpTitle: DiskStation - Synology DiskStation"), r.reasons)
    }
    @Test fun `discovery evidence is tried first, then ports ascending`() {
        val rr = rule("t", "httpTitle", "Router", model = "x")
        assertEquals(listOf("httpTitle: Router A"), one(rr, ev(mapOf(8080 to http(title = "Router B")), host.copy(httpTitle = "Router A"))).reasons)
        assertEquals(listOf("port 80 httpTitle: Router C"), one(rr, ev(mapOf(8080 to http(title = "Router B"), 80 to http(title = "Router C")))).reasons)
    }
    @Test fun `onPorts excludes other ports and discovery titles`() {
        val r = rule("t", "httpTitle", "DiskStation", onPorts = listOf(5000, 5001), vendor = "Synology")
        assertEquals(DeviceIdentity.UNKNOWN, one(r, ev(mapOf(8080 to http(title = "DiskStation")), host.copy(httpTitle = "DiskStation"))))
        assertEquals("Synology", one(r, ev(mapOf(5001 to http(title = "DiskStation")))).vendor)
    }
    @Test fun `httpServer reads an rtsp port's server header`() {
        val rtsp = ServiceInfo("RTSP", "Hikvision", "1.0", null, Confidence.CONFIRMED, server = "Hikvision/1.0")
        val r = one(rule("s", "httpServer", "(Hikvision)", vendor = "\$1", type = "CAMERA"), ev(mapOf(554 to rtsp)))
        assertEquals("Hikvision", r.vendor); assertEquals(DeviceType.CAMERA, r.type); assertEquals(listOf("port 554 httpServer: Hikvision/1.0"), r.reasons)
    }
    @Test fun `httpRealm reads a port realm`() {
        val r = one(rule("r", "httpRealm", "NETGEAR", vendor = "NETGEAR"), ev(mapOf(80 to http(realm = "NETGEAR R7000"))))
        assertEquals(listOf("port 80 httpRealm: NETGEAR R7000"), r.reasons)
    }
    @Test fun `sshBanner reads every ssh port, not only 22`() {
        val ssh = ServiceInfo("SSH", "OpenSSH", "9.6p1", "Ubuntu-3ubuntu13", Confidence.CONFIRMED)
        val r = one(rule("s", "sshBanner", "Ubuntu", os = "Linux"), ev(mapOf(2222 to ssh)))
        assertEquals("Linux", r.os); assertEquals(listOf("port 2222 sshBanner: Ubuntu-3ubuntu13"), r.reasons)
        assertEquals(DeviceIdentity.UNKNOWN, one(rule("s", "sshBanner", "Ubuntu", os = "Linux"), ev(mapOf(2222 to ssh.copy(name = "HTTP")))))
    }
    @Test fun `poweredBy certName and certOrg`() {
        assertEquals(listOf("port 80 poweredBy: ASP.NET"), one(rule("p", "poweredBy", "ASP\\.NET", os = "Windows"), ev(mapOf(80 to http(poweredBy = "ASP.NET")))).reasons)
        val tls = ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = "fritz.box", certOrg = "Ubiquiti Inc.")
        assertEquals(listOf("port 443 certName: fritz.box"), one(rule("c", "certName", "fritz\\.box$", vendor = "AVM"), ev(mapOf(443 to tls))).reasons)
        assertEquals(listOf("port 443 certOrg: Ubiquiti Inc."), one(rule("o", "certOrg", "Ubiquiti", vendor = "Ubiquiti"), ev(mapOf(443 to tls))).reasons)
    }
    @Test fun `portBanner joins product, version and detail`() {
        val ftp = ServiceInfo("FTP", "Synology", null, "220 Synology FTP server ready.", Confidence.CONFIRMED)
        assertEquals(listOf("port 21 portBanner: Synology 220 Synology FTP server ready."),
            one(rule("b", "portBanner", "Synology", vendor = "Synology"), ev(mapOf(21 to ftp))).reasons)
    }
    @Test fun `service matches confirmed names only`() {
        val r = rule("r", "service", "^RDP$", os = "Windows", weight = 60)
        assertEquals(listOf("port 3389 service: RDP"), one(r, ev(mapOf(3389 to ServiceInfo("RDP", confidence = Confidence.CONFIRMED)))).reasons)
        assertEquals(DeviceIdentity.UNKNOWN, one(r, ev(mapOf(3389 to ServiceInfo("RDP", confidence = Confidence.PORT_TABLE)))))
    }
    @Test fun `software collects every matching app rule, distinct, in file order`() {
        val rules = listOf(rule("a", "httpTitle", "Grafana", software = "Grafana", type = "SERVER", weight = 40),
            rule("b", "anyPort", software = "Plex Media Server", weight = 40, anyPort = listOf(32400)),
            rule("c", "httpTitle", "Grafana", software = "Grafana", weight = 40))
        val r = DeviceIdentifier(rules).identify(ev(mapOf(3000 to http(title = "Grafana")), extraPorts = setOf(32400)))
        assertEquals(listOf("Grafana", "Plex Media Server"), r.software); assertEquals(DeviceType.SERVER, r.type); assertNull(r.model)
        assertEquals(listOf("port 3000 httpTitle: Grafana", "anyPort: 32400"), r.reasons)
    }
    @Test fun `router vendors are spelled one way`() {
        val rr = rule("v", "httpTitle", "\\b(netgear|tp-link|asus)\\b", vendor = "\$1", type = "ROUTER")
        assertEquals("NETGEAR", one(rr, ev(mapOf(80 to http(title = "netgear router")))).vendor)
        assertEquals("TP-Link", one(rr, ev(mapOf(80 to http(title = "TP-LINK Archer AX55")))).vendor)
        assertEquals("ASUS", one(rr, ev(mapOf(80 to http(title = "Asus RT-AX86U")))).vendor)
    }
    @Test fun `discovery evidence keeps the old reason form`() {
        assertEquals(listOf("httpTitle: pfSense - Login"),
            one(rule("t", "httpTitle", "pfSense", os = "pfSense"), ev(emptyMap(), host.copy(httpTitle = "pfSense - Login"))).reasons)
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `No parameter with name 'onPorts' found` (and `software`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/IdentifyRules.kt`: replace `Rule` with:
```kotlin
@Serializable
data class Rule(
    val id: String, val field: String, val pattern: String? = null, val ports: List<Int>? = null, val anyPort: List<Int>? = null,
    val vendor: String? = null, val model: String? = null, val os: String? = null, val type: String? = null, val weight: Int, val sample: String? = null,
    /** Port evidence from these ports only; discovery-time evidence is not considered (spec 3.2). */
    val onPorts: List<Int>? = null,
    /** What the box runs, shown as "Runs <software>"; collected from every matching rule, never a weighted slot (spec 3.6). */
    val software: String? = null,
)
```

`app/src/main/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifier.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.serviceid.Confidence

class DeviceIdentifier(private val rules: List<Rule>) {
    private class Slot(var value: String? = null, var weight: Int = -1)
    /** A matched candidate: the text the rule saw, the port it came from (null for discovery-time evidence) and its groups. */
    private class Hit(val text: String, val port: Int?, val group1: String?, val txtValue: String? = null)
    private companion object {
        const val MAX_VALUE_LEN = 80
        const val MAX_REASON_LEN = 80
        val CANONICAL_VENDORS = mapOf(
            "hp" to "HP", "hewlett-packard" to "HP", "epson" to "Epson", "canon" to "Canon", "brother" to "Brother",
            "lexmark" to "Lexmark", "kyocera" to "Kyocera", "xerox" to "Xerox",
            // Router vendors, so "$1" from a page title or realm is spelled one way (spec 3.2, Gruff m10).
            "tp-link" to "TP-Link", "netgear" to "NETGEAR", "asus" to "ASUS", "linksys" to "Linksys", "zyxel" to "Zyxel",
            "huawei" to "Huawei", "zte" to "ZTE", "sagemcom" to "Sagemcom", "technicolor" to "Technicolor", "tenda" to "Tenda",
        )
        /** Fields matched against stored port findings (spec 3.2); the three http* fields also read discovery evidence first. */
        val PORT_FIELDS = setOf("httpTitle", "httpServer", "httpRealm", "sshBanner", "poweredBy", "certName", "certOrg", "portBanner", "service")
    }
    private fun canonicalVendor(v: String?): String? = v?.let { CANONICAL_VENDORS[it.lowercase()] ?: it }
    private fun group1Of(m: MatchResult): String? = if (m.groups.size > 1) m.groups[1]?.value else null

    /** Discovery-time candidates of a port field; none when the rule is limited to ports. */
    private fun hostCandidates(r: Rule, e: HostEvidence): List<String> {
        if (r.onPorts != null) return emptyList()
        val h = e.host
        return when (r.field) {
            "httpTitle" -> listOfNotNull(h.httpTitle)
            "httpServer" -> listOfNotNull(h.httpServer)
            "httpRealm" -> listOfNotNull(h.httpAuthRealm)
            else -> emptyList()
        }
    }

    /** Port candidates of a field, ports ascending, limited to [Rule.onPorts] when it is set (spec 3.2 table). */
    private fun portCandidates(r: Rule, e: HostEvidence): List<Pair<Int, String>> =
        e.services.entries.sortedBy { it.key }.filter { r.onPorts == null || it.key in r.onPorts }.flatMap { (port, s) ->
            val values = when (r.field) {
                "httpTitle" -> listOfNotNull(s.title)
                "httpServer" -> listOfNotNull(s.server)
                "httpRealm" -> listOfNotNull(s.realm)
                "sshBanner" -> if (s.name.equals("SSH", ignoreCase = true)) listOfNotNull(s.product, s.detail) else emptyList()
                "poweredBy" -> listOfNotNull(s.poweredBy)
                "certName" -> listOfNotNull(s.certCn)
                "certOrg" -> listOfNotNull(s.certOrg)
                "portBanner" -> listOf(listOfNotNull(s.product, s.version, s.detail).joinToString(" ")).filter { it.isNotBlank() }
                "service" -> if (s.confidence == Confidence.CONFIRMED) listOf(s.name) else emptyList()
                else -> emptyList()
            }
            values.map { port to it }
        }

    fun identify(e: HostEvidence): DeviceIdentity {
        val vendor = Slot(); val model = Slot(); val os = Slot(); val type = Slot()
        val reasons = ArrayList<String>(); val software = LinkedHashSet<String>(); var confidence = 0
        fun set(slot: Slot, v: String?, w: Int) { if (v != null && w > slot.weight) { slot.value = v; slot.weight = w } }
        fun apply(r: Rule, hit: Hit) {
            fun sub(s: String?) = s?.replace("\$1", hit.group1 ?: "")?.replace("\$txt", hit.txtValue ?: "")?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
            set(vendor, canonicalVendor(sub(r.vendor)), r.weight); set(model, sub(r.model), r.weight); set(os, sub(r.os), r.weight); set(type, r.type, r.weight)
            r.software?.let { software += it }
            val where = hit.port?.let { "port $it " }.orEmpty()
            reasons += "$where${r.field}: ${hit.text.take(MAX_REASON_LEN)}"; confidence = maxOf(confidence, r.weight)
        }
        val h = e.host
        // Direct extraction, weight 100.
        val upnpMaker = h.upnpManufacturer?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
        val upnpModel = h.upnpModel?.trim()?.take(MAX_VALUE_LEN)?.takeIf { it.isNotEmpty() }
        if (upnpMaker != null || upnpModel != null) {
            set(vendor, upnpMaker, 100); set(model, upnpModel, 100); reasons += "upnp: ${listOfNotNull(upnpMaker, upnpModel).joinToString(" ")}"; confidence = 100
        }
        val names = listOfNotNull(h.mdnsName, h.netbiosName, h.upnpFriendlyName, h.reverseDnsName) + h.mdnsInstanceNames
        val txtStrings = h.mdnsTxt.flatMap { (t, kv) -> kv.map { (k, v) -> Triple("$t:$k=$v", k, v) } }
        for (r in rules) {
            val re = r.pattern?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            fun first(cands: List<String>): Hit? = cands.firstNotNullOfOrNull { c -> re?.find(c)?.let { m -> Hit(c, null, group1Of(m)) } }
            val hit: Hit? = when (r.field) {
                in PORT_FIELDS -> first(hostCandidates(r, e))
                    ?: portCandidates(r, e).firstNotNullOfOrNull { (p, c) -> re?.find(c)?.let { m -> Hit(c, p, group1Of(m)) } }
                "mdnsType" -> first(h.mdnsServiceTypes.toList())
                "mdnsTxt" -> txtStrings.firstNotNullOfOrNull { (s, _, v) -> re?.find(s)?.let { m -> Hit(s, null, group1Of(m), v) } }
                "upnpManufacturer" -> first(listOfNotNull(h.upnpManufacturer))
                "upnpDeviceType" -> first(listOfNotNull(h.upnpDeviceType))
                "snmpSysDescr" -> first(listOfNotNull(h.snmpSysDescr))
                "hostname" -> first(names)
                "netbiosName" -> first(listOfNotNull(h.netbiosName))
                "ports" -> if (r.ports != null && e.openPorts.containsAll(r.ports)) Hit(r.ports.joinToString(","), null, null) else null
                "anyPort" -> r.anyPort?.firstOrNull { it in e.openPorts }?.let { Hit(it.toString(), null, null) }
                "gateway" -> if (h.isGateway) Hit("default gateway", null, null) else null
                else -> null
            }
            if (r.field == "oui") { if (e.ouiVendor != null) apply(r.copy(vendor = e.ouiVendor), Hit(e.ouiVendor, null, null)) }
            else hit?.let { apply(r, it) }
        }
        if (reasons.isEmpty()) return DeviceIdentity.UNKNOWN
        val t = type.value?.let { runCatching { DeviceType.valueOf(it) }.getOrNull() } ?: DeviceType.UNKNOWN
        return DeviceIdentity(vendor.value, model.value, os.value, t, confidence, reasons.distinct(), software.toList())
    }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.*" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `DeviceIdentifierPortEvidenceTest` (12) passes; the existing `DeviceIdentifierTest` and `HostEvidenceBuilderTest` stay green with the unchanged rules file (the `ssh-windows` reason now reads "port 22 sshBanner: OpenSSH_for_Windows"; its test checks `contains`); then `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Identification reads port evidence: new rule fields, onPorts, software, port reasons, router vendor spelling"
```

---

### Task 7: The rules: 57 new entries, seven converted or lowered, precedence and stability tests

**Files:**
- Modify: `app/src/main/res/raw/identify_rules.json`, `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/SyntheticEvidence.kt` (replace), `DeviceIdentifierTest.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/engine/identify/NewRulesTest.kt`, `RulesPrecedenceTest.kt`, `RouterPatternTest.kt`, `IdentityStabilityTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt` (one test, compile only)

**Interfaces:**
- Consumes: `Rule.onPorts/software`, the port fields of Task 6, `ServiceMerge` (Task 3), `IdentityWrite` (Task 2).
- Produces: the rules file the spec fixes (118 rules: 61 existing, 57 new); no code API.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/SyntheticEvidence.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import java.net.Inet4Address

object SyntheticEvidence {
    /** A port no rule names, for the port evidence of rules without onPorts. */
    const val NEUTRAL_PORT = 40000

    /** Evidence that exercises exactly the rule [r]: its own sample placed in the field the rule reads, in that field's candidate form. */
    fun forRule(r: Rule, ip: Inet4Address): HostEvidence {
        val sample = r.sample.orEmpty()
        val h = DiscoveredHost(ip)
        val port = r.onPorts?.first() ?: NEUTRAL_PORT
        fun ev(host: DiscoveredHost, ports: Set<Int> = emptySet(), services: Map<Int, ServiceInfo> = emptyMap()) = HostEvidence(host, ports, services, null)
        fun onPort(s: ServiceInfo) = ev(h, ports = setOf(port), services = mapOf(port to s))
        fun web(title: String? = null, server: String? = null, realm: String? = null, poweredBy: String? = null) =
            ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title, server = server, realm = realm, poweredBy = poweredBy)
        return when (r.field) {
            "mdnsType" -> ev(h.copy(mdnsServiceTypes = setOf(sample)))
            "mdnsTxt" -> {
                val type = sample.substringBefore(':'); val kv = sample.substringAfter(':')
                ev(h.copy(mdnsServiceTypes = setOf(type), mdnsTxt = mapOf(type to mapOf(kv.substringBefore('=') to kv.substringAfter('=')))))
            }
            "upnpManufacturer" -> ev(h.copy(upnpManufacturer = sample))
            "upnpDeviceType" -> ev(h.copy(upnpDeviceType = sample))
            "snmpSysDescr" -> ev(h.copy(snmpSysDescr = sample))
            "httpTitle" -> if (r.onPorts != null) onPort(web(title = sample)) else ev(h.copy(httpTitle = sample))
            "httpRealm" -> if (r.onPorts != null) onPort(web(realm = sample)) else ev(h.copy(httpAuthRealm = sample))
            "httpServer" -> if (r.onPorts != null) onPort(web(server = sample)) else ev(h.copy(httpServer = sample))
            "hostname" -> ev(h.copy(reverseDnsName = sample))
            "netbiosName" -> ev(h.copy(netbiosName = sample))
            // The rule reads product and detail of SSH ports; the parser puts the software token (for example OpenSSH_for_Windows) in product.
            "sshBanner" -> ev(h, services = mapOf(22 to ServiceInfo("SSH", sample, null, sample, Confidence.CONFIRMED)))
            "poweredBy" -> onPort(web(poweredBy = sample))
            "certName" -> onPort(ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certCn = sample))
            "certOrg" -> onPort(ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, certOrg = sample))
            "portBanner" -> onPort(ServiceInfo("FTP", detail = sample, confidence = Confidence.CONFIRMED))   // joined form = the sample
            "service" -> onPort(ServiceInfo(sample, confidence = Confidence.CONFIRMED))
            "ports", "anyPort" -> ev(h, ports = (r.ports ?: r.anyPort).orEmpty().toSet())
            "gateway" -> ev(h.copy(isGateway = true))
            else -> error("no synthetic evidence for field ${r.field} (rule ${r.id})")
        }
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/DeviceIdentifierTest.kt`:
- Replace `rules file loads with unique ids and valid fields` with:
```kotlin
    @Test fun `rules file loads with unique ids and valid fields`() {
        assertEquals(118, rules.size); assertEquals(rules.size, rules.map { it.id }.toSet().size)
        val fields = setOf("mdnsType", "mdnsTxt", "upnpManufacturer", "upnpDeviceType", "snmpSysDescr", "httpTitle", "httpRealm", "httpServer", "hostname",
            "netbiosName", "sshBanner", "ports", "anyPort", "gateway", "oui", "poweredBy", "certName", "certOrg", "portBanner", "service")
        rules.forEach { assertTrue("bad field in ${it.id}", it.field in fields); assertTrue(it.weight in 1..100) }
        val portFields = setOf("httpTitle", "httpRealm", "httpServer", "sshBanner", "poweredBy", "certName", "certOrg", "portBanner", "service")
        rules.filter { it.onPorts != null }.forEach { assertTrue("${it.id} has onPorts on a non-port field", it.field in portFields) }
    }
    @Test fun `software rules never set a model and never a type above weight 40`() {   // spec 3.6, 9.1 test 18
        for (r in rules.filter { it.software != null }) {
            assertNull("${r.id} sets a model", r.model)
            assertTrue("${r.id} sets a type at weight ${r.weight}", r.type == null || r.weight <= 40)
        }
    }
    @Test fun `new rules sit between ssh-dropbear and gateway in spec order`() {
        val ids = rules.map { it.id }
        assertEquals(ids.indexOf("ssh-dropbear") + 1, ids.indexOf("p-dsm-title"))
        assertEquals(ids.indexOf("p-airplay") + 1, ids.indexOf("gateway"))
        assertEquals(57, ids.indexOf("gateway") - ids.indexOf("p-dsm-title"))
    }
```
- Replace `home assistant and proxmox by title or port` with:
```kotlin
    @Test fun `home assistant is software and proxmox stays a platform`() {
        val hass = id.identify(ev(ports = setOf(8123)))
        assertEquals(listOf("Home Assistant"), hass.software); assertNull(hass.model); assertEquals(DeviceType.SERVER, hass.type); assertEquals(40, hass.confidence)
        assertEquals("Proxmox VE", id.identify(ev(DiscoveredHost(ip, httpTitle = "pve - Proxmox Virtual Environment"))).model)
    }
```
- In `false positives are avoided - plex and asustor`, replace the Plex line with `assertFalse("Plex Media Server" in id.identify(ev(DiscoveredHost(ip, httpTitle = "Complex Systems Login"))).software)`.

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/NewRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.identify.DeviceType.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 18: every new or replaced rule, alone, on its own sample, yields exactly the values the spec gives it. */
class NewRulesTest {
    private data class Expect(val vendor: String? = null, val model: String? = null, val os: String? = null, val type: DeviceType = UNKNOWN,
                              val software: List<String> = emptyList())
    private val rules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText())
    private val ip = InetAddress.getByName("192.168.1.2") as Inet4Address
    private val expected: Map<String, Expect> = linkedMapOf(
        "title-hass" to Expect(type = SERVER, software = listOf("Home Assistant")),
        "port-hass" to Expect(type = SERVER, software = listOf("Home Assistant")),
        "title-plex" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "port-plex" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "title-pihole" to Expect(type = SERVER, software = listOf("Pi-hole")),
        "title-unifi" to Expect(software = listOf("UniFi Network")),
        "port-rdp" to Expect(os = "Windows", type = COMPUTER),
        "p-dsm-title" to Expect(vendor = "Synology", type = NAS),
        "p-dsm-cert" to Expect(vendor = "Synology", type = NAS),
        "p-dsm-ftp" to Expect(vendor = "Synology", type = NAS),
        "p-srm-title" to Expect(vendor = "Synology", model = "Synology Router", type = ROUTER),
        "p-unifi-os" to Expect(vendor = "Ubiquiti", model = "UniFi OS console"),
        "p-unifi-cert" to Expect(software = listOf("UniFi Network")),
        "p-hue-title" to Expect(vendor = "Philips Hue", model = "Hue Bridge", type = IOT),
        "p-hue-cert" to Expect(vendor = "Philips Hue", model = "Hue Bridge", type = IOT),
        "p-plex-http" to Expect(type = SERVER, software = listOf("Plex Media Server")),
        "p-jellyfin" to Expect(type = SERVER, software = listOf("Jellyfin")),
        "p-emby" to Expect(type = SERVER, software = listOf("Emby Server")),
        "p-proxmox-cert" to Expect(model = "Proxmox VE", type = SERVER),
        "p-cups-server" to Expect(type = PRINTER, software = listOf("CUPS")),
        "p-printer-title" to Expect(vendor = "HP", type = PRINTER),
        "p-hp-server" to Expect(vendor = "HP", type = PRINTER),
        "p-rtsp-vendor" to Expect(vendor = "Hikvision", type = CAMERA),
        "p-rtsp-service" to Expect(type = CAMERA),
        "p-router-title" to Expect(vendor = "NETGEAR", type = ROUTER),
        "p-isp-hub" to Expect(model = "Hub Manager", type = ROUTER),
        "p-fritz" to Expect(vendor = "AVM", model = "FRITZ!Box", type = ROUTER),
        "p-fritz-cert" to Expect(vendor = "AVM", model = "FRITZ!Box", type = ROUTER),
        "p-draytek" to Expect(vendor = "DrayTek", type = ROUTER),
        "p-mikrotik-title" to Expect(vendor = "MikroTik", os = "RouterOS", type = ROUTER),
        "p-mikrotik-ssh" to Expect(vendor = "MikroTik", os = "RouterOS", type = ROUTER),
        "p-edgeos" to Expect(vendor = "Ubiquiti", os = "EdgeOS", type = ROUTER),
        "p-rdp" to Expect(os = "Windows"),
        "p-iis" to Expect(os = "Windows", type = SERVER),
        "p-httpapi" to Expect(os = "Windows"),
        "p-aspnet" to Expect(os = "Windows"),
        "p-openssh" to Expect(os = "Linux"),
        "p-ssh-freebsd" to Expect(os = "FreeBSD"),
        "p-ssh-raspbian" to Expect(vendor = "Raspberry Pi", type = COMPUTER),
        "p-esxi-title" to Expect(vendor = "VMware", model = "ESXi", os = "ESXi", type = SERVER),
        "p-esxi-cert" to Expect(vendor = "VMware", type = SERVER),
        "p-qnap-title" to Expect(vendor = "QNAP", type = NAS),
        "p-qnap-cert" to Expect(vendor = "QNAP", type = NAS),
        "p-truenas-title" to Expect(model = "TrueNAS", type = NAS),
        "p-truenas-cert" to Expect(model = "TrueNAS", type = NAS),
        "p-unraid-title" to Expect(model = "Unraid", type = NAS),
        "p-unraid-cert" to Expect(model = "Unraid", type = NAS),
        "p-omv" to Expect(model = "OpenMediaVault", type = NAS),
        "p-grafana" to Expect(type = SERVER, software = listOf("Grafana")),
        "p-portainer" to Expect(type = SERVER, software = listOf("Portainer")),
        "p-nodered-title" to Expect(type = SERVER, software = listOf("Node-RED")),
        "p-nodered-http" to Expect(software = listOf("Node-RED")),
        "p-nextcloud" to Expect(type = SERVER, software = listOf("Nextcloud")),
        "p-adguard" to Expect(type = SERVER, software = listOf("AdGuard Home")),
        "p-octoprint" to Expect(type = IOT, software = listOf("OctoPrint")),
        "p-kodi" to Expect(type = MEDIA, software = listOf("Kodi")),
        "p-tasmota" to Expect(model = "Tasmota", type = IOT),
        "p-shelly" to Expect(vendor = "Shelly", type = IOT),
        "p-frigate" to Expect(type = SERVER, software = listOf("Frigate NVR")),
        "p-z2m" to Expect(type = SERVER, software = listOf("Zigbee2MQTT")),
        "p-kuma" to Expect(type = SERVER, software = listOf("Uptime Kuma")),
        "p-idrac" to Expect(vendor = "Dell", model = "iDRAC", type = SERVER),
        "p-ilo" to Expect(vendor = "HPE", model = "iLO", type = SERVER),
        "p-airplay" to Expect(vendor = "Apple"),
    )

    @Test fun `every new or replaced rule yields exactly its own values on its own sample`() {
        assertEquals(64, expected.size)
        for ((ruleId, want) in expected) {
            val r = rules.single { it.id == ruleId }
            val got = DeviceIdentifier(listOf(r)).identify(SyntheticEvidence.forRule(r, ip))
            assertEquals(ruleId, want, Expect(got.vendor, got.model, got.os, got.type, got.software))
            assertEquals("$ruleId weight", r.weight, got.confidence)
        }
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/RulesPrecedenceTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 3.6 and 9.1 test 19: platforms outrank the apps they run (controller ruling on Gruff M6). */
class RulesPrecedenceTest {
    private val id = DeviceIdentifier(IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()))
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private fun http(title: String) = ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = title)
    private fun ident(services: Map<Int, ServiceInfo>, ports: Set<Int> = emptySet(), host: DiscoveredHost = DiscoveredHost(ip)) =
        id.identify(HostEvidence(host, ports + services.keys, services, null))
    private val pi = DiscoveredHost(ip, reverseDnsName = "raspberrypi.lan")

    @Test fun `synology running plex, jellyfin and home assistant reads synology nas with three software names`() {
        val r = ident(mapOf(5000 to http("DiskStation - Synology DiskStation"), 32400 to http("Plex"), 8096 to http("Jellyfin"), 8123 to http("Home Assistant")), setOf(5001))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type)
        assertEquals(listOf("Home Assistant", "Plex Media Server", "Jellyfin"), r.software)
    }
    @Test fun `unraid running jellyfin and frigate reads unraid nas`() {
        val r = ident(mapOf(80 to http("Tower/Main - Unraid"), 8096 to http("Jellyfin"), 8971 to http("Frigate")))
        assertEquals("Unraid", r.model); assertEquals(DeviceType.NAS, r.type); assertEquals(listOf("Jellyfin", "Frigate NVR"), r.software)
    }
    @Test fun `a windows pc running plex reads windows computer`() {
        val r = ident(mapOf(32400 to http("Plex")), setOf(135, 445))
        assertEquals("Windows", r.os); assertEquals(DeviceType.COMPUTER, r.type); assertEquals(listOf("Plex Media Server"), r.software)
    }
    @Test fun `a raspberry pi running the unifi network app reads raspberry pi computer`() {
        val r = ident(mapOf(8443 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, title = "UniFi Network", certCn = "UniFi")), host = pi)
        assertEquals("Raspberry Pi", r.vendor); assertEquals(DeviceType.COMPUTER, r.type)
        assertNotEquals("Ubiquiti", r.vendor); assertNotEquals(DeviceType.ROUTER, r.type); assertEquals(listOf("UniFi Network"), r.software)
    }
    @Test fun `a pi with xrdp and a raspbian ssh banner reads linux`() {
        val r = ident(mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.2p1", "Raspbian-1+deb12u1", Confidence.CONFIRMED), 3389 to ServiceInfo("RDP", confidence = Confidence.CONFIRMED)))
        assertTrue("os was ${r.os}", r.os!!.startsWith("Linux")); assertEquals(DeviceType.COMPUTER, r.type); assertEquals("Raspberry Pi", r.vendor)
    }
    @Test fun `srm beats dsm on type`() {
        val r = ident(mapOf(5000 to http("Synology Router - SRM")))
        assertEquals(DeviceType.ROUTER, r.type); assertEquals("Synology Router", r.model)
    }
    @Test fun `pi-hole on a pi keeps computer`() {
        val r = ident(mapOf(80 to http("Pi-hole - Login")), host = pi)
        assertEquals(DeviceType.COMPUTER, r.type); assertEquals(listOf("Pi-hole"), r.software)
    }
    @Test fun `openssh loses to the windows port pair on os`() {
        assertEquals("Windows", ident(mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.5", null, Confidence.CONFIRMED)), setOf(135, 445)).os)
    }
    @Test fun `the gateway keeps router over any title`() {
        val r = ident(mapOf(80 to http("hue personal wireless lighting"), 443 to ServiceInfo("HTTPS", confidence = Confidence.CONFIRMED, title = "VMware ESXi")),
            host = DiscoveredHost(ip, isGateway = true))
        assertEquals(DeviceType.ROUTER, r.type)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/RouterPatternTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** spec 9.1 test 20 (Gruff m10). */
class RouterPatternTest {
    private val routerRules = IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()).filter { it.id in setOf("p-router-title", "p-isp-hub") }
    private val ip = InetAddress.getByName("10.0.3.1") as Inet4Address
    private fun title(t: String) = DeviceIdentifier(routerRules).identify(
        HostEvidence(DiscoveredHost(ip), setOf(80), mapOf(80 to ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = t)), null))

    @Test fun `ordinary words that contain a vendor name match no router rule`() {
        assertEquals(2, routerRules.size)
        for (t in listOf("Attendance", "Pegasus", "Aztec")) assertEquals(t, DeviceIdentity.UNKNOWN, title(t))
        assertNotEquals(DeviceType.ROUTER, title("ASUSTOR Data Master").type)
    }
    @Test fun `a vendor in any case is spelled the canonical way`() {
        val r = title("netgear router"); assertEquals("NETGEAR", r.vendor); assertEquals(DeviceType.ROUTER, r.type)
        assertEquals("TP-Link", title("TP-LINK Archer AX55").vendor); assertEquals("ASUS", title("Asus RT-AX86U").vendor)
        assertEquals("Hub Manager", title("Hub Manager").model)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/engine/identify/IdentityStabilityTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.data.IdentityWrite
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** Review Focus 3: an identity a port scan established never flaps on the next radar run or a timed-out rescan (spec 3.1, 3.3). */
class IdentityStabilityTest {
    private val id = DeviceIdentifier(IdentifyRules.load(javaClass.getResource("/identify_rules.json")!!.readText()))
    private val host = DiscoveredHost(InetAddress.getByName("10.0.3.59") as Inet4Address)   // discovery saw no title
    private val dsm = ServiceInfo("HTTP", "nginx", null, "200 OK", Confidence.CONFIRMED, title = "DiskStation - Synology DiskStation")

    @Test fun `a radar re-identification keeps what a port scan learned`() {
        val afterScan = id.identify(HostEvidenceBuilder.build(host, emptyMap(), setOf(5000), mapOf(5000 to dsm), null))
        val stored = mapOf(5000 to ServiceMerge.merge(null, dsm))
        val afterRadar = id.identify(HostEvidenceBuilder.build(host, stored, emptySet(), emptyMap(), null))   // discover(): stored ports, no new services
        assertEquals(DeviceType.NAS, afterScan.type); assertEquals("Synology", afterScan.vendor); assertEquals(afterScan, afterRadar)
    }
    @Test fun `a rescan whose HEAD timed out keeps the confirmed name, the title and the identity`() {
        val timedOut = ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE)
        assertEquals(dsm, ServiceMerge.merge(dsm, timedOut))
        val r = id.identify(HostEvidenceBuilder.build(host, mapOf(5000 to dsm), setOf(5000), mapOf(5000 to timedOut), null))
        assertEquals("Synology", r.vendor); assertEquals(DeviceType.NAS, r.type); assertEquals(90, r.confidence)
    }
    @Test fun `a weaker port-scan identity never replaces a stronger stored one`() {
        val strong = id.identify(HostEvidenceBuilder.build(host, mapOf(5000 to dsm), emptySet(), emptyMap(), null))
        val weak = id.identify(HostEvidenceBuilder.build(host, emptyMap(), setOf(22), mapOf(22 to ServiceInfo("SSH", "OpenSSH", "9.6", null, Confidence.CONFIRMED)), null))
        assertEquals(40, weak.confidence)
        assertFalse(IdentityWrite.replaces(strong.confidence, weak.confidence)); assertTrue(IdentityWrite.replaces(strong.confidence, strong.confidence))
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.*" --console=plain 2>&1 | tail -25
```
Expected: compiles; failures in `rules file loads ...` (61 rules, not 118), `software rules never ...` passes vacuously, `new rules sit between ...`, `home assistant is software ...`, every `NewRulesTest` / `RulesPrecedenceTest` / `RouterPatternTest` case and `IdentityStabilityTest` (no `p-dsm-title` yet), for example `NewRulesTest ... NoSuchElementException: Collection contains no element matching the predicate` and `X tests completed, Y failed`.

- [ ] **Step 3: Implement**

`app/src/main/res/raw/identify_rules.json`: replace these seven lines in place (same positions):
```json
 {"id":"title-hass","field":"httpTitle","pattern":"Home Assistant","software":"Home Assistant","type":"SERVER","weight":40,"sample":"Home Assistant"},
 {"id":"port-hass","field":"anyPort","anyPort":[8123],"software":"Home Assistant","type":"SERVER","weight":40},
 {"id":"title-plex","field":"httpTitle","pattern":"\\bPlex\\b","software":"Plex Media Server","type":"SERVER","weight":40,"sample":"Plex"},
 {"id":"port-plex","field":"anyPort","anyPort":[32400],"software":"Plex Media Server","type":"SERVER","weight":40},
 {"id":"title-pihole","field":"httpTitle","pattern":"Pi-hole","software":"Pi-hole","type":"SERVER","weight":40,"sample":"Pi-hole - Login"},
 {"id":"title-unifi","field":"httpTitle","pattern":"UniFi","software":"UniFi Network","weight":40,"sample":"UniFi Network"},
 {"id":"port-rdp","field":"anyPort","anyPort":[3389],"os":"Windows","type":"COMPUTER","weight":60},
```
Then insert these 57 lines directly after the `ssh-dropbear` line and before the `gateway` line (one leading space, each ending with a comma, as the file's other lines):
```json
 {"id":"p-dsm-title","field":"httpTitle","onPorts":[5000,5001],"pattern":"Synology|DiskStation","vendor":"Synology","type":"NAS","weight":90,"sample":"DiskStation - Synology DiskStation"},
 {"id":"p-dsm-cert","field":"certOrg","pattern":"^Synology","vendor":"Synology","type":"NAS","weight":80,"sample":"Synology Inc."},
 {"id":"p-dsm-ftp","field":"portBanner","onPorts":[21],"pattern":"Synology","vendor":"Synology","type":"NAS","weight":80,"sample":"Synology 220 Synology FTP server ready."},
 {"id":"p-srm-title","field":"httpTitle","pattern":"Synology Router|\\bSRM\\b","vendor":"Synology","model":"Synology Router","type":"ROUTER","weight":95,"sample":"Synology Router - SRM"},
 {"id":"p-unifi-os","field":"httpTitle","pattern":"UniFi OS","vendor":"Ubiquiti","model":"UniFi OS console","weight":80,"sample":"UniFi OS"},
 {"id":"p-unifi-cert","field":"certName","pattern":"^UniFi$","software":"UniFi Network","weight":40,"sample":"UniFi"},
 {"id":"p-hue-title","field":"httpTitle","pattern":"hue personal wireless lighting","vendor":"Philips Hue","model":"Hue Bridge","type":"IOT","weight":90,"sample":"hue personal wireless lighting"},
 {"id":"p-hue-cert","field":"certOrg","pattern":"Philips Hue","vendor":"Philips Hue","model":"Hue Bridge","type":"IOT","weight":90,"sample":"Philips Hue"},
 {"id":"p-plex-http","field":"service","onPorts":[32400],"pattern":"^HTTPS?$","software":"Plex Media Server","type":"SERVER","weight":40,"sample":"HTTP"},
 {"id":"p-jellyfin","field":"httpTitle","pattern":"Jellyfin","software":"Jellyfin","type":"SERVER","weight":40,"sample":"Jellyfin"},
 {"id":"p-emby","field":"httpTitle","pattern":"\\bEmby\\b","software":"Emby Server","type":"SERVER","weight":40,"sample":"Emby"},
 {"id":"p-proxmox-cert","field":"certOrg","pattern":"PVE Cluster","model":"Proxmox VE","type":"SERVER","weight":90,"sample":"PVE Cluster Node"},
 {"id":"p-cups-server","field":"httpServer","pattern":"^CUPS","software":"CUPS","type":"PRINTER","weight":40,"sample":"CUPS/2.4 IPP/2.1"},
 {"id":"p-printer-title","field":"httpTitle","pattern":"^(HP|Brother|EPSON|Canon|Lexmark|Kyocera|Xerox)\\b.*(LaserJet|OfficeJet|DeskJet|ENVY|MFC|DCP|HL-|ET-|WF-|XP-|PIXMA|imageCLASS|ECOSYS|WorkCentre|VersaLink)","vendor":"$1","type":"PRINTER","weight":90,"sample":"HP OfficeJet Pro 9010 series"},
 {"id":"p-hp-server","field":"httpServer","pattern":"HP HTTP Server","vendor":"HP","type":"PRINTER","weight":80,"sample":"HP HTTP Server; HP OfficeJet Pro 9010 series"},
 {"id":"p-rtsp-vendor","field":"httpServer","onPorts":[554,8554],"pattern":"(Hikvision|Dahua|Reolink|Amcrest|Axis|Hipcam)","vendor":"$1","type":"CAMERA","weight":85,"sample":"Hikvision/1.0"},
 {"id":"p-rtsp-service","field":"service","pattern":"^RTSP$","type":"CAMERA","weight":60,"sample":"RTSP"},
 {"id":"p-router-title","field":"httpTitle","pattern":"\\b(TP-Link|NETGEAR|ASUS(?!TOR)|Linksys|Zyxel|Huawei|ZTE|Sagemcom|Technicolor|Tenda)\\b","vendor":"$1","type":"ROUTER","weight":75,"sample":"NETGEAR Router R7000"},
 {"id":"p-isp-hub","field":"httpTitle","pattern":"\\b(BT Smart Hub|EE Smart Hub|Hub Manager|Virgin Media Hub|Sky Hub|Sky Broadband|Plusnet Hub|Vodafone)\\b","model":"$1","type":"ROUTER","weight":75,"sample":"Hub Manager"},
 {"id":"p-fritz","field":"httpTitle","pattern":"FRITZ!Box","vendor":"AVM","model":"FRITZ!Box","type":"ROUTER","weight":90,"sample":"FRITZ!Box"},
 {"id":"p-fritz-cert","field":"certName","pattern":"fritz\\.box$","vendor":"AVM","model":"FRITZ!Box","type":"ROUTER","weight":90,"sample":"fritz.box"},
 {"id":"p-draytek","field":"httpTitle","pattern":"\\bVigor","vendor":"DrayTek","type":"ROUTER","weight":80,"sample":"Vigor Login Page"},
 {"id":"p-mikrotik-title","field":"httpTitle","pattern":"RouterOS","vendor":"MikroTik","os":"RouterOS","type":"ROUTER","weight":90,"sample":"RouterOS router configuration page"},
 {"id":"p-mikrotik-ssh","field":"sshBanner","pattern":"^ROSSSH","vendor":"MikroTik","os":"RouterOS","type":"ROUTER","weight":90,"sample":"ROSSSH"},
 {"id":"p-edgeos","field":"httpTitle","pattern":"EdgeOS|EdgeRouter","vendor":"Ubiquiti","os":"EdgeOS","type":"ROUTER","weight":90,"sample":"EdgeOS"},
 {"id":"p-rdp","field":"service","pattern":"^RDP$","os":"Windows","weight":60,"sample":"RDP"},
 {"id":"p-iis","field":"httpServer","pattern":"Microsoft-IIS","os":"Windows","type":"SERVER","weight":80,"sample":"Microsoft-IIS/10.0"},
 {"id":"p-httpapi","field":"httpServer","pattern":"Microsoft-HTTPAPI","os":"Windows","weight":60,"sample":"Microsoft-HTTPAPI/2.0"},
 {"id":"p-aspnet","field":"poweredBy","pattern":"ASP\\.NET","os":"Windows","weight":50,"sample":"ASP.NET"},
 {"id":"p-openssh","field":"sshBanner","pattern":"^OpenSSH$","os":"Linux","weight":40,"sample":"OpenSSH"},
 {"id":"p-ssh-freebsd","field":"sshBanner","pattern":"FreeBSD","os":"FreeBSD","weight":70,"sample":"FreeBSD-20240806"},
 {"id":"p-ssh-raspbian","field":"sshBanner","pattern":"Raspbian","vendor":"Raspberry Pi","type":"COMPUTER","weight":60,"sample":"Raspbian-1+deb12u1"},
 {"id":"p-esxi-title","field":"httpTitle","pattern":"VMware ESXi","vendor":"VMware","model":"ESXi","os":"ESXi","type":"SERVER","weight":90,"sample":"VMware ESXi"},
 {"id":"p-esxi-cert","field":"certOrg","pattern":"VMware","vendor":"VMware","type":"SERVER","weight":70,"sample":"VMware Installer"},
 {"id":"p-qnap-title","field":"httpTitle","pattern":"QNAP|\\bQTS\\b","vendor":"QNAP","type":"NAS","weight":90,"sample":"QTS"},
 {"id":"p-qnap-cert","field":"certOrg","pattern":"QNAP","vendor":"QNAP","type":"NAS","weight":80,"sample":"QNAP Systems Inc."},
 {"id":"p-truenas-title","field":"httpTitle","pattern":"TrueNAS|FreeNAS","model":"TrueNAS","type":"NAS","weight":90,"sample":"TrueNAS"},
 {"id":"p-truenas-cert","field":"certOrg","pattern":"iXsystems","model":"TrueNAS","type":"NAS","weight":80,"sample":"iXsystems"},
 {"id":"p-unraid-title","field":"httpTitle","pattern":"Unraid","model":"Unraid","type":"NAS","weight":90,"sample":"Tower/Main - Unraid"},
 {"id":"p-unraid-cert","field":"certName","pattern":"unraid\\.net$","model":"Unraid","type":"NAS","weight":80,"sample":"abc123.myunraid.net"},
 {"id":"p-omv","field":"httpTitle","pattern":"openmediavault","model":"OpenMediaVault","type":"NAS","weight":90,"sample":"openmediavault control panel - nas"},
 {"id":"p-grafana","field":"httpTitle","pattern":"^Grafana","software":"Grafana","type":"SERVER","weight":40,"sample":"Grafana"},
 {"id":"p-portainer","field":"httpTitle","pattern":"Portainer","software":"Portainer","type":"SERVER","weight":40,"sample":"Portainer"},
 {"id":"p-nodered-title","field":"httpTitle","pattern":"Node-RED","software":"Node-RED","type":"SERVER","weight":40,"sample":"Node-RED"},
 {"id":"p-nodered-http","field":"service","onPorts":[1880],"pattern":"^HTTP$","software":"Node-RED","weight":40,"sample":"HTTP"},
 {"id":"p-nextcloud","field":"httpTitle","pattern":"Nextcloud","software":"Nextcloud","type":"SERVER","weight":40,"sample":"Nextcloud"},
 {"id":"p-adguard","field":"httpTitle","pattern":"AdGuard Home","software":"AdGuard Home","type":"SERVER","weight":40,"sample":"Login | AdGuard Home"},
 {"id":"p-octoprint","field":"httpTitle","pattern":"OctoPrint","software":"OctoPrint","type":"IOT","weight":40,"sample":"OctoPrint Login"},
 {"id":"p-kodi","field":"httpTitle","pattern":"^Kodi","software":"Kodi","type":"MEDIA","weight":40,"sample":"Kodi"},
 {"id":"p-tasmota","field":"httpTitle","pattern":"Tasmota","model":"Tasmota","type":"IOT","weight":90,"sample":"Tasmota - Main Menu"},
 {"id":"p-shelly","field":"httpTitle","pattern":"Shelly","vendor":"Shelly","type":"IOT","weight":90,"sample":"Shelly Plus 1PM"},
 {"id":"p-frigate","field":"httpTitle","pattern":"Frigate","software":"Frigate NVR","type":"SERVER","weight":40,"sample":"Frigate"},
 {"id":"p-z2m","field":"httpTitle","pattern":"Zigbee2MQTT","software":"Zigbee2MQTT","type":"SERVER","weight":40,"sample":"Zigbee2MQTT"},
 {"id":"p-kuma","field":"httpTitle","pattern":"Uptime Kuma","software":"Uptime Kuma","type":"SERVER","weight":40,"sample":"Uptime Kuma"},
 {"id":"p-idrac","field":"httpTitle","pattern":"iDRAC","vendor":"Dell","model":"iDRAC","type":"SERVER","weight":90,"sample":"iDRAC9 - Login"},
 {"id":"p-ilo","field":"httpTitle","pattern":"\\biLO\\b","vendor":"HPE","model":"iLO","type":"SERVER","weight":90,"sample":"iLO 5 Login"},
 {"id":"p-airplay","field":"httpServer","pattern":"AirTunes","vendor":"Apple","weight":60,"sample":"AirTunes/620.8.2"},
```
Check the result is valid JSON and has 118 rules:
```bash
grep -c '"id"' app/src/main/res/raw/identify_rules.json
```
Expected: `118`. The `RawResourcesTest` and `rules file loads ...` both fail on malformed JSON (the loader returns an empty list).

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`, before the companion (spec 9.3 test 3, Review Focus 3 backstop; add imports `com.northwalessolutions.netprobe.R`, `com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier`, `com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder`, `com.northwalessolutions.netprobe.engine.identify.IdentifyRules`):
```kotlin
    @Test fun portEvidenceSurvivesRediscoveryAndATimedOutRescan() = runBlocking {
        val hosts = HostRepository(db); val ports = PortRepository(db)
        val identifier = DeviceIdentifier(IdentifyRules.load(RawResources.read(ApplicationProvider.getApplicationContext(), R.raw.identify_rules)))
        val addr = ip("10.0.3.59")
        val id = hosts.upsertDiscovered(1, DiscoveredHost(addr), runId = 1)
        // A Full scan finds the DSM page on 5000 and identifies the host.
        val scanned = ServiceInfo("HTTP", "nginx", null, "200 OK", Confidence.CONFIRMED, title = "DiskStation - Synology DiskStation")
        ports.upsertOpen(id, 5000, scanned)
        val afterScan = identifier.identify(HostEvidenceBuilder.build(hosts.toDiscovered(hosts.byIdOnce(id)!!), ports.servicesFor(id), setOf(5000), mapOf(5000 to scanned), null))
        assertTrue(hosts.setIdentityIfNotWeaker(id, afterScan))
        assertEquals(DeviceType.NAS, hosts.byIdOnce(id)!!.deviceType)
        // A radar run re-identifies from stored evidence only: discovery saw no title.
        hosts.upsertDiscovered(1, DiscoveredHost(addr), runId = 2)
        val radar = identifier.identify(HostEvidenceBuilder.build(hosts.toDiscovered(hosts.byIdOnce(id)!!), ports.servicesFor(id), emptySet(), emptyMap(), null))
        hosts.setIdentity(id, radar)
        assertEquals("Synology", hosts.byIdOnce(id)!!.identVendor); assertEquals(DeviceType.NAS, hosts.byIdOnce(id)!!.deviceType)
        // A rescan whose HEAD timed out stores only the port-table guess: the confirmed name and title stay.
        ports.upsertOpen(id, 5000, ServiceInfo("UPnP", confidence = Confidence.PORT_TABLE))
        val stored = ports.servicesFor(id).getValue(5000)
        assertEquals("HTTP", stored.name); assertEquals(Confidence.CONFIRMED, stored.confidence); assertEquals("DiskStation - Synology DiskStation", stored.title)
    }
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.engine.identify.*" --tests "com.northwalessolutions.netprobe.RawResourcesTest" --console=plain 2>&1 | tail -20
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `DeviceIdentifierTest` (with `every rule in the file contributes for its own synthetic evidence` covering all 118), `NewRulesTest` (1), `RulesPrecedenceTest` (9), `RouterPatternTest` (2) and `IdentityStabilityTest` (3) pass; then `BUILD SUCCESSFUL`. If a precedence case fails, fix the rule entry only if it differs from the spec text; otherwise stop and report the case (the spec's entries are binding, the test is the spec's).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Identify rules: 57 port-evidence rules, app rules become software, precedence and stability tests"
```

---

### Task 8: The port-scan identity write, "Why this identity" port lines and the software chips

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`
- Modify: `ui/detail/DetailViewModel.kt` (`scanHostOn`), `ui/home/HomeViewModel.kt` (`quickScanHostOn`), `ui/detail/DetailText.kt` (`softwareLabels`), `ui/detail/DetailComponents.kt` (`SoftwareChips`), `ui/detail/DetailContent.kt` (chip row under the data line)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt` (one test), `ui/home/CopyTableTest.kt` (templates and one test); `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` (two tests, compile only)

**Interfaces:**
- Consumes: `HostRepository.setIdentityIfNotWeaker` (Task 2), `SoftwareColumn` (Task 2), port reason lines (Task 6).
- Produces:
```kotlin
object DetailCopy { fun runs(software: String): String }          // "Runs Home Assistant"; grows in Tasks 9, 11, 12, 14
fun softwareLabels(h: HostEntity): List<String>                  // ui/detail/DetailText.kt
@Composable fun SoftwareChips(labels: List<String>)              // tag "softwareChips"; absent when empty
```

The "Why this identity" sheet needs no code change for port lines: `ReasonsSheet` lists `identReasons`, which now carry "port P field: value" (Task 6). The "Set by you" first line arrives in Task 10.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt` (add `import com.northwalessolutions.netprobe.data.HostEntity` and `com.northwalessolutions.netprobe.data.HostStatus`):
```kotlin
    private fun bare() = HostEntity(networkId = 1, ip = "10.0.3.59", mac = null, reverseDnsName = null, mdnsName = null, netbiosName = null, upnpName = null,
        upnpManufacturer = null, upnpModel = null, vendor = null, deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null,
        identOs = null, identConfidence = 0, identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED,
        note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    @Test fun `software chips read the stored column in order`() {
        assertEquals(listOf("Runs Home Assistant", "Runs Grafana"), softwareLabels(bare().copy(identSoftware = "Home Assistant\nGrafana")))
        assertEquals(emptyList<String>(), softwareLabels(bare()))
    }
```

In `app/src/test/java/com/northwalessolutions/netprobe/ui/home/CopyTableTest.kt`, add to the `templates` list (before the closing `).map { Regex(it) }`):
```kotlin
        "Runs .+",
```
and add the test (import `com.northwalessolutions.netprobe.ui.detail.DetailCopy`):
```kotlin
    @Test fun `software chip strings`() {
        listOf("Home Assistant", "Plex Media Server", "UniFi Network").forEach { inTable(DetailCopy.runs(it)) }
        assertTrue(DetailCopy.runs("Grafana") == "Runs Grafana")
    }
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` (spec 9.2 test 17; the existing six-row test stays for hosts without software, spec 3.6):
```kotlin
    @Test fun softwareChipsShowUnderTheDataLineAndLeaveFivePortRowsAt13() {
        show(DetailFakes.state().copy(host = DetailFakes.host().copy(identSoftware = "Home Assistant\nGrafana")), fontScale = 1.3f)
        rule.onNodeWithTag("softwareChips").assertIsDisplayed()
        rule.onNodeWithText("Runs Home Assistant", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Runs Grafana", useUnmergedTree = true).assertExists()
        assertTrue("rows: ${fullyVisibleRows()}", fullyVisibleRows() >= 5)
    }
    @Test fun noSoftwareMeansNoChipRow() {
        show(DetailFakes.state())
        rule.onNodeWithTag("softwareChips").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'softwareLabels'` and `'DetailCopy'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

/** Detail strings added in Phase 3A (spec 7); older Detail wording stays in DetailText. Every string has a CopyTableTest template. */
object DetailCopy {
    fun runs(software: String) = "Runs $software"
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`: add (imports `com.northwalessolutions.netprobe.data.HostEntity`, `com.northwalessolutions.netprobe.data.SoftwareColumn`):
```kotlin
/** "Runs Home Assistant" chips, in the stored rule-file order (spec 3.6). */
fun softwareLabels(h: HostEntity): List<String> = SoftwareColumn.decode(h.identSoftware).map(DetailCopy::runs)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`: add (imports `androidx.compose.foundation.background`, `androidx.compose.foundation.horizontalScroll`, `androidx.compose.foundation.rememberScrollState`):
```kotlin
private val SOFTWARE_CHIP_RADIUS = 4.dp

/** One neutral chip per software name in a single horizontally scrolling row; the mark-chip shape, not a status (spec 3.6). */
@Composable
fun SoftwareChips(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp).testTag("softwareChips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Text(label, style = NetProbeText.caption, color = NetProbeColors.textSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(SOFTWARE_CHIP_RADIUS)).background(NetProbeColors.surface)
                    .padding(vertical = 2.dp, horizontal = 6.dp).semantics { contentDescription = label })
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`: directly after the `Text(listOfNotNull(h.ip, h.mac).joinToString("  "), ...)` line, add:
```kotlin
                    SoftwareChips(softwareLabels(h))
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: at the end of `scanHostOn`, replace the identity write with:
```kotlin
        c.hosts.byIdOnce(hostId)?.let { e ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(e), c.ports.servicesFor(hostId), found.keys.toSet(), found, e.mac?.let(c.ouiLookup))
            // After a port scan the stored identity is replaced only by one at least as confident (spec 3.3).
            c.hosts.setIdentityIfNotWeaker(hostId, c.deviceIdentifier.identify(ev))
        }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`: at the end of `quickScanHostOn`, replace `c.hosts.setIdentity(h.id, c.deviceIdentifier.identify(ev))` with `c.hosts.setIdentityIfNotWeaker(h.id, c.deviceIdentifier.identify(ev))`. Discovery (`discover`) keeps `setIdentity`: a re-used IP must be able to lose an old identity (spec 3.3).

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: the new `DetailTextTest` and `CopyTableTest` tests pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Port scans write the identity only when not weaker; software chips on Detail"
```

---

### Task 9: Manual details, the pure part: Effective values, DeviceEdits.of, the draft, "Set by you", Edit copy

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/data/Overrides.kt`, `ui/home/HomeViewModel.kt` (`IdentityLine.of(h)`, `RowRules`), `ui/detail/DetailText.kt` (`setByYouLine`), `ui/detail/DetailCopy.kt`, `ui/detail/DetailViewModel.kt` (`lookupVulnsForDevice`)
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/EditCopy.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/data/TestHosts.kt` (test helper), `data/OverridesTest.kt`, `ui/home/IdentityLineOverridesTest.kt`, `ui/detail/DetailTextTest.kt` (one test), `ui/home/CopyTableTest.kt` (templates and one test)

**Interfaces:**
- Consumes: `HostEntity.userType/userVendor/userModel` (Task 1), `DeviceEdits` (Task 2), `DisplayName`.
- Produces:
```kotlin
object Effective { fun type(h: HostEntity): DeviceType; fun vendor(h: HostEntity): String?; fun model(h: HostEntity): String? }
data class EditDraft(val name: String, val type: DeviceType?, val vendor: String, val model: String) { companion object { fun initial(h: HostEntity): EditDraft } }
data class Detected(val name: String, val type: DeviceType, val vendor: String?, val model: String?) { companion object { fun of(h: HostEntity): Detected } }
DeviceEdits.of(draft: EditDraft, detected: Detected): DeviceEdits; DeviceEdits.MAX_LEN = 80
fun setByYouLine(h: HostEntity): String?                                  // ui/detail
object EditCopy { TITLE, NAME, TYPE, VENDOR, MODEL, RESET, NOT_SET, SAVE, CANCEL; fun detected(value: String?): String; TYPES: List<DeviceType>; fun typeLabel(t: DeviceType): String }
DetailCopy += setByYou(parts), editDescription(name), MORE_OPTIONS, EDIT_DEVICE, REMOVE_DEVICE
RowRules.OWN_LINE becomes public (const val OWN_LINE = "Android device")
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/data/TestHosts.kt` (shared test helper):
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** A stored host with only what a test names; everything else empty. */
object TestHosts {
    fun host(ip: String = "10.0.3.59", type: DeviceType = DeviceType.UNKNOWN, vendor: String? = null, model: String? = null, os: String? = null,
             mdns: String? = null, mac: String? = null, gateway: Boolean = false) = HostEntity(
        networkId = 1, ip = ip, mac = mac, reverseDnsName = null, mdnsName = mdns, netbiosName = null, upnpName = null, upnpManufacturer = null,
        upnpModel = null, vendor = null, deviceType = type, customName = null, identVendor = vendor, identModel = model, identOs = os, identConfidence = 0,
        identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = gateway,
        firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
}
```

`app/src/test/java/com/northwalessolutions/netprobe/data/OverridesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 22 and 24. */
class OverridesTest {
    private val detected = Detected(name = "CHNAS", type = DeviceType.NAS, vendor = "Synology", model = null)

    @Test fun `an override wins per field and null falls back to the detected value`() {
        val h = TestHosts.host(type = DeviceType.SERVER, vendor = "QNAP", model = "TS-453")
        assertEquals(DeviceType.SERVER, Effective.type(h)); assertEquals("QNAP", Effective.vendor(h)); assertEquals("TS-453", Effective.model(h))
        val o = h.copy(userType = DeviceType.NAS, userModel = "TS-453D")
        assertEquals(DeviceType.NAS, Effective.type(o)); assertEquals("QNAP", Effective.vendor(o)); assertEquals("TS-453D", Effective.model(o))
    }
    @Test fun `values are trimmed and blanks store nothing`() {
        assertEquals(DeviceEdits(name = "DiskStation", model = "DS220+"), DeviceEdits.of(EditDraft("  DiskStation ", DeviceType.NAS, " ", " DS220+ "), detected))
    }
    @Test fun `a value equal to the detected value stores nothing`() {
        assertEquals(DeviceEdits(), DeviceEdits.of(EditDraft("CHNAS", DeviceType.NAS, "Synology", ""), detected))
    }
    @Test fun `a type equal to the detected type stores nothing and another type is stored`() {
        assertNull(DeviceEdits.of(EditDraft("CHNAS", DeviceType.NAS, "", ""), detected).type)
        assertEquals(DeviceType.SERVER, DeviceEdits.of(EditDraft("CHNAS", DeviceType.SERVER, "", ""), detected).type)
        assertNull(DeviceEdits.of(EditDraft("CHNAS", null, "", ""), detected).type)
    }
    @Test fun `text is cut to 80 characters`() {
        assertEquals(80, DeviceEdits.of(EditDraft("n".repeat(200), null, "", ""), detected).name!!.length)
        assertEquals(80, DeviceEdits.MAX_LEN)
    }
    @Test fun `the own phone's dialog saved unchanged stores no name`() {
        val phone = TestHosts.host(ip = "10.0.3.242", mdns = "Pixel-7-Pro")
        val draft = EditDraft.initial(phone)
        assertEquals("Pixel-7-Pro", draft.name)                             // the detected name, never "This phone"
        assertEquals(DeviceEdits(), DeviceEdits.of(draft, Detected.of(phone)))
    }
    @Test fun `the draft starts from the effective values and detected from the scan`() {
        val h = TestHosts.host(type = DeviceType.NAS, vendor = "Synology", mdns = "CHNAS").copy(customName = "DiskStation", userModel = "DS220+")
        assertEquals(EditDraft("DiskStation", DeviceType.NAS, "Synology", "DS220+"), EditDraft.initial(h))
        assertEquals(Detected("CHNAS", DeviceType.NAS, "Synology", null), Detected.of(h))
        assertNull("UNKNOWN shows as Not set", EditDraft.initial(TestHosts.host()).type)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/IdentityLineOverridesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.theme.EdgeColors
import com.northwalessolutions.netprobe.ui.theme.edgeColor
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 9.1 tests 22 (gateway edge) and 23, and spec 4.2 (search, own phone). */
class IdentityLineOverridesTest {
    @Test fun `identity line shows the overrides`() {
        val h = TestHosts.host(type = DeviceType.SERVER, vendor = "QNAP", model = "TS-453")
        assertEquals("Synology DS220+", IdentityLine.of(h.copy(userVendor = "Synology", userModel = "DS220+")))
        assertEquals("NAS", IdentityLine.of(TestHosts.host().copy(userType = DeviceType.NAS)))
        assertEquals("Linux NAS", IdentityLine.of(TestHosts.host(os = "Linux").copy(userType = DeviceType.NAS)))
    }
    @Test fun `the row type follows the override and a gateway stays teal whatever type is set`() {
        assertEquals(DeviceType.NAS, RowRules.row(TestHosts.host(type = DeviceType.SERVER).copy(userType = DeviceType.NAS), TestEnvs.home, 24, 0, false).type)
        for (t in DeviceType.entries) {
            val r = RowRules.row(TestHosts.host(ip = "192.168.1.1", gateway = true).copy(userType = t), TestEnvs.home, 24, 0, false)
            assertEquals(t, r.type); assertEquals(EdgeColors.router, r.type.edgeColor(r.isGateway))
        }
    }
    @Test fun `search finds a hand-entered model through the identity line`() {
        val row = RowRules.row(TestHosts.host().copy(userModel = "DS220+"), TestEnvs.home, 24, 0, false)
        assertEquals(listOf(row), HomeFilter.apply(listOf(row), StatusFilter.ALL, "ds220"))
    }
    @Test fun `the own phone keeps its model line unless a model is set`() {
        val own = TestHosts.host(ip = "192.168.1.50")                                     // TestEnvs.home's own address
        assertEquals(RowRules.OWN_LINE, RowRules.row(own, TestEnvs.home, 24, 0, false).identityLine)
        assertEquals(DeviceType.PHONE, RowRules.row(own, TestEnvs.home, 24, 0, false).type)
        val set = RowRules.row(own.copy(userModel = "Pixel 7 Pro (work)"), TestEnvs.home, 24, 0, false)
        assertEquals("Pixel 7 Pro (work)", set.identityLine)
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt`:
```kotlin
    @Test fun `set by you lists only the set fields, in order`() {   // spec 9.1 test 25
        val h = bare()
        assertNull(setByYouLine(h)); assertNull(setByYouLine(h.copy(customName = "Mine")))   // the name is the title, not listed
        assertEquals("Set by you: type NAS, vendor Synology, model DS220+", setByYouLine(h.copy(userType = DeviceType.NAS, userVendor = "Synology", userModel = "DS220+")))
        assertEquals("Set by you: vendor Synology", setByYouLine(h.copy(userVendor = "Synology")))
        assertEquals("Set by you: type IoT, model Hue", setByYouLine(h.copy(userType = DeviceType.IOT, userModel = "Hue")))
    }
```

In `CopyTableTest`, add to `templates`:
```kotlin
        "Set by you: (type (Router|Phone|Computer|Printer|Media|IoT|NAS|Camera|Server)(, vendor .+)?(, model .+)?|vendor .+(, model .+)?|model .+)",
        "Edit device, .+", "More options", "Edit device", "Remove device", "Name", "Type", "Vendor", "Model", "Detected: .+", "Reset to detected",
        "Router", "Phone", "Computer", "Printer", "Media", "IoT", "NAS", "Camera", "Server", "Not set", "Save", "Cancel",
```
and the test (imports `com.northwalessolutions.netprobe.ui.detail.EditCopy`, `com.northwalessolutions.netprobe.ui.detail.setByYouLine`, `com.northwalessolutions.netprobe.data.TestHosts`, `com.northwalessolutions.netprobe.engine.identify.DeviceType`):
```kotlin
    @Test fun `edit dialog, overflow and set by you strings`() {
        listOf(EditCopy.TITLE, EditCopy.NAME, EditCopy.TYPE, EditCopy.VENDOR, EditCopy.MODEL, EditCopy.RESET, EditCopy.NOT_SET, EditCopy.SAVE, EditCopy.CANCEL,
            EditCopy.detected("Synology"), EditCopy.detected(null), DetailCopy.MORE_OPTIONS, DetailCopy.EDIT_DEVICE, DetailCopy.REMOVE_DEVICE,
            DetailCopy.editDescription("DiskStation")).forEach { inTable(it) }
        assertEquals("Detected: none", EditCopy.detected(null)); assertEquals(9, EditCopy.TYPES.size)
        EditCopy.TYPES.forEach { inTable(EditCopy.typeLabel(it)) }
        val h = TestHosts.host()
        for (t in listOf(null, DeviceType.NAS)) for (v in listOf(null, "Synology")) for (m in listOf(null, "DS220+"))
            setByYouLine(h.copy(userType = t, userVendor = v, userModel = m))?.let { inTable(it) }
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'Effective'` (and `EditDraft`, `Detected`, `of`, `setByYouLine`, `EditCopy`, `OWN_LINE` is private).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/data/Overrides.kt`: replace `data class DeviceEdits(...)` with:
```kotlin
/** The Director's own values for a host (spec 4.1). null means "use the detected value". */
data class DeviceEdits(val name: String? = null, val type: DeviceType? = null, val vendor: String? = null, val model: String? = null) {
    companion object {
        /** The identifier's MAX_VALUE_LEN; longer input is not accepted by the dialog and is cut here (spec 4.3). */
        const val MAX_LEN = 80
        /**
         * What Save stores (spec 4.3): values trimmed; a blank field stores null; a value equal to the detected value stores null,
         * so confirming a detection never pins it; a type equal to the detected type stores null.
         */
        fun of(draft: EditDraft, detected: Detected): DeviceEdits = DeviceEdits(
            name = text(draft.name, detected.name),
            type = draft.type?.takeIf { it != detected.type && it != DeviceType.UNKNOWN },
            vendor = text(draft.vendor, detected.vendor),
            model = text(draft.model, detected.model),
        )
        private fun text(value: String, detected: String?): String? =
            value.trim().take(MAX_LEN).takeIf { it.isNotEmpty() && it != detected?.trim() }
    }
}

/** Detected values shown under each field as "Detected: ..." (spec 4.3). The name ignores customName, never "This phone". */
data class Detected(val name: String, val type: DeviceType, val vendor: String?, val model: String?) {
    companion object {
        fun of(h: HostEntity): Detected = Detected(DisplayName.of(h.copy(customName = null)), h.deviceType, h.identVendor, h.identModel)
    }
}

/** The dialog's editable values; [type] null shows "Not set" and is only possible while the effective type is UNKNOWN. */
data class EditDraft(val name: String, val type: DeviceType?, val vendor: String, val model: String) {
    companion object {
        fun initial(h: HostEntity): EditDraft = EditDraft(
            name = h.customName ?: Detected.of(h).name,
            type = Effective.type(h).takeIf { it != DeviceType.UNKNOWN },
            vendor = Effective.vendor(h).orEmpty(),
            model = Effective.model(h).orEmpty(),
        )
    }
}

/** Manual values override detected ones everywhere they show (spec 4.2); they never enter identification evidence (A7). */
object Effective {
    fun type(h: HostEntity): DeviceType = h.userType ?: h.deviceType
    fun vendor(h: HostEntity): String? = h.userVendor ?: h.identVendor
    fun model(h: HostEntity): String? = h.userModel ?: h.identModel
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
- In `IdentityLine`, replace `fun of(h: HostEntity)` with:
```kotlin
    /** Same rule from a stored host, with the Director's overrides (spec 4.2); os stays detected. "Only mDNS" means no NetBIOS, UPnP or MAC evidence. */
    fun of(h: HostEntity): String = of(Effective.vendor(h), Effective.model(h), h.identOs, Effective.type(h),
        onlyMdns = h.mdnsName != null && h.netbiosName == null && h.upnpName == null && h.mac == null)
```
- In `RowRules`, make the own-line constant public: `const val OWN_LINE = "Android device"`, and replace the `return HostRow(...)` line of `row` with:
```kotlin
        // The own phone keeps "Android device" (and Build.MODEL on Home) unless the Director set a model; its type stays PHONE unless set.
        val line = if (own && h.userModel == null) OWN_LINE else IdentityLine.of(h)
        val type = if (own) h.userType ?: DeviceType.PHONE else Effective.type(h)
        return HostRow(h.id, h.ip, name, line, type, openPorts, h.status, h.isGateway, stale, h.mac, subnet, off)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt`, in `HostRowItem`: replace the two `if (own) ownModel else null` uses with `if (own && r.identityLine == RowRules.OWN_LINE) ownModel else null`, so a set model wins over `Build.MODEL` (spec 4.2).

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/EditCopy.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.home.IdentityLine

/** Edit device dialog wording (spec 7). */
object EditCopy {
    const val TITLE = "Edit device"
    const val NAME = "Name"
    const val TYPE = "Type"
    const val VENDOR = "Vendor"
    const val MODEL = "Model"
    const val RESET = "Reset to detected"
    const val NOT_SET = "Not set"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    private const val NONE = "none"
    fun detected(value: String?) = "Detected: ${value?.takeIf { it.isNotBlank() } ?: NONE}"
    /** The nine real types, in this order; "unknown" is never offered (spec 4.3). */
    val TYPES = listOf(DeviceType.ROUTER, DeviceType.PHONE, DeviceType.COMPUTER, DeviceType.PRINTER, DeviceType.MEDIA,
        DeviceType.IOT, DeviceType.NAS, DeviceType.CAMERA, DeviceType.SERVER)
    fun typeLabel(t: DeviceType): String = with(IdentityLine) { t.label }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`: add inside the object:
```kotlin
    const val MORE_OPTIONS = "More options"
    const val EDIT_DEVICE = "Edit device"
    const val REMOVE_DEVICE = "Remove device"
    fun editDescription(name: String) = "Edit device, $name"
    fun setByYou(parts: String) = "Set by you: $parts"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`: add:
```kotlin
/** "Set by you: type NAS, vendor Synology, model DS220+": only the set fields, in that order; the name is the title (spec 4.4). */
fun setByYouLine(h: HostEntity): String? {
    val parts = listOfNotNull(h.userType?.let { "type ${EditCopy.typeLabel(it)}" }, h.userVendor?.let { "vendor $it" }, h.userModel?.let { "model $it" })
    return if (parts.isEmpty()) null else DetailCopy.setByYou(parts.joinToString(", "))
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: in `lookupVulnsForDevice`, replace the `DeviceIdentity(h.identVendor, h.identModel, ...)` argument with `DeviceIdentity(Effective.vendor(h), Effective.model(h), h.identOs, Effective.type(h), h.identConfidence, h.identReasons)`, so a hand-entered model can be searched (spec 4.2).

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.data.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `OverridesTest` (7), `IdentityLineOverridesTest` (4), the new `DetailTextTest` and `CopyTableTest` tests pass; the existing `RowRulesTest`, `RowTextTest` and `HomeFilterTest` stay green; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Manual device details: effective values, DeviceEdits rules, Set by you line, Edit copy"
```

---

### Task 10: The Edit device dialog, its three entry points and the Detail overflow

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/detail/EditDevice.kt`
- Modify: `ui/detail/DetailComponents.kt` (`EditableName` replaced by `DetailName`, new `IdentityText`, `DetailMenu`, `ReasonsSheet` first line), `ui/detail/DetailContent.kt` (`DetailActions.onEdit`, dialog, overflow), `ui/Controls.kt` (`BackToHome` trailing slot), `ui/detail/DetailViewModel.kt` (`setOverrides`), `ui/detail/DetailScreen.kt`, `data/Repositories.kt` and `data/Daos.kt` (remove `setCustomName`)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` (five tests, compile only), `androidTest/.../data/DaoTest.kt` (two call sites)

**Interfaces:**
- Consumes: `EditDraft`, `Detected`, `DeviceEdits.of`, `EditCopy`, `DetailCopy`, `setByYouLine`, `Effective` (Task 9), `HostRepository.setOverrides` (Task 2).
- Produces:
```kotlin
@Composable fun EditDeviceDialog(state: EditDraft, detected: Detected, onSave: (DeviceEdits) -> Unit, onCancel: () -> Unit)
// tags: editBody, editName, editType, type:<TYPE>, editVendor, editModel, resetName, resetType, resetVendor, resetModel, editSave, editCancel
@Composable fun DetailName(name: String, onEdit: () -> Unit)                   // tag detailName
@Composable fun IdentityText(text: String, name: String, onEdit: () -> Unit)    // tag identityLine, Role.Button
@Composable fun DetailMenu(onEdit: () -> Unit, onRemove: (() -> Unit)? = null)  // tag detailMenu, "More options"
@Composable fun BackToHome(onBack: () -> Unit, trailing: @Composable () -> Unit = {})
data class DetailActions(..., val onEdit: (DeviceEdits) -> Unit = {}, ...)      // replaces onRename
DetailViewModel.setOverrides(edits: DeviceEdits)                                  // replaces setCustomName
```

- [ ] **Step 1: Write the failing Compose tests (compile only)**

In `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt`: add a field `private val edits = mutableListOf<DeviceEdits>()`, change `recording` to `DetailActions(onAction = { actions += it }, onScan = { scans++ }, onStop = { stops++ }, onEdit = { edits += it })`, add imports `com.northwalessolutions.netprobe.data.DeviceEdits` and `com.northwalessolutions.netprobe.engine.identify.DeviceType`, and add (spec 9.2 tests 14 and 15, first half):
```kotlin
    /** Nothing detected, so every value the test types is an override. */
    private fun bareHost() = DetailFakes.host().copy(deviceType = DeviceType.UNKNOWN, identVendor = null, identModel = null)

    @Test fun editDeviceFromTheIdentityLineSavesOnlyTheOverrides() {
        var st by mutableStateOf(DetailFakes.state().copy(host = bareHost()))
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(phone) { DetailContent(st, recording) } } }
        rule.onNodeWithTag("identityLine").assert(hasContentDescription("Edit device, CHNAS")).performClick()
        rule.onNodeWithText("Edit device").assertIsDisplayed()
        rule.onNodeWithTag("editType").performClick(); rule.onNodeWithTag("type:NAS").performClick()
        rule.onNodeWithTag("editVendor").performTextReplacement("Synology")
        rule.onNodeWithTag("editSave").performClick()
        assertEquals(listOf(DeviceEdits(type = DeviceType.NAS, vendor = "Synology")), edits)
        // Reopened with those overrides stored: "Reset to detected" shows on Type and Vendor only.
        st = st.copy(host = bareHost().copy(userType = DeviceType.NAS, userVendor = "Synology"))
        rule.onNodeWithTag("detailName").assert(hasContentDescription("Edit device, CHNAS")).performClick()
        rule.onNodeWithTag("resetType").assertExists(); rule.onNodeWithTag("resetVendor").assertExists()
        rule.onNodeWithTag("resetName").assertDoesNotExist(); rule.onNodeWithTag("resetModel").assertDoesNotExist()
        rule.onNodeWithTag("resetType").performClick(); rule.onNodeWithTag("editSave").performClick()
        assertEquals(DeviceEdits(vendor = "Synology"), edits.last())
    }
    @Test fun theOwnPhonesNameFieldStartsWithTheDetectedName() {
        show(DetailFakes.state().copy(displayName = "This phone"))
        rule.onNodeWithTag("detailName").performClick()
        rule.onNodeWithTag("editName").assert(hasText("CHNAS")); rule.onNodeWithTag("editName").assert(!hasText("This phone"))
        rule.onNodeWithTag("editSave").performClick()
        assertEquals(DeviceEdits(), edits.single())
    }
    @Test fun editButtonsStayVisibleWithTheBodyScrolledAtFontScale20() {
        show(DetailFakes.state(), fontScale = 2f)
        rule.onNodeWithTag("detailName").performClick()
        rule.onNodeWithTag("editBody").performScrollToNode(hasTestTag("editModel"))
        rule.onNodeWithTag("editSave").assertIsDisplayed(); rule.onNodeWithTag("editCancel").assertIsDisplayed()
    }
    @Test fun overflowOffersEditDevice() {
        show(DetailFakes.state())
        rule.onNodeWithTag("detailMenu").assert(hasContentDescription("More options")).performClick()
        rule.onNodeWithText("Edit device").assertIsEnabled().performClick()
        rule.onNodeWithTag("editSave").assertIsDisplayed()
    }
    @Test fun whyThisIdentityStartsWithSetByYou() {
        show(DetailFakes.state().copy(host = DetailFakes.host().copy(userType = DeviceType.NAS, userModel = "DS220+")))
        rule.onNodeWithText("Why this identity").performClick()
        rule.onNodeWithText("Set by you: type NAS, model DS220+").assertIsDisplayed()
    }
```

In `app/src/androidTest/java/com/northwalessolutions/netprobe/data/DaoTest.kt`: replace `hosts.setCustomName(id, "My PC")` with `hosts.setOverrides(id, DeviceEdits(name = "My PC"))` and `hosts.setCustomName(id, "c$i")` with `hosts.setOverrides(id, DeviceEdits(name = "c$i"))`.

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `No parameter with name 'onEdit' found` in `DetailContentTest`.

- [ ] **Step 2: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/EditDevice.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.northwalessolutions.netprobe.data.DeviceEdits
import com.northwalessolutions.netprobe.data.Detected
import com.northwalessolutions.netprobe.data.EditDraft
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.npFieldColors
import com.northwalessolutions.netprobe.ui.theme.Dimens
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeText

private val FIELD_RADIUS = 8.dp

/**
 * Edit device (spec 4.3). The body scrolls at large font scales; Save and Cancel are the dialog's own buttons, outside the scroll,
 * so they stay pinned. Each field shows "Detected: ..." and, while it holds an override, "Reset to detected". Nothing is saved
 * until Save, which stores DeviceEdits.of(draft, detected).
 */
@Composable
fun EditDeviceDialog(state: EditDraft, detected: Detected, onSave: (DeviceEdits) -> Unit, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf(state) }
    val edits = DeviceEdits.of(draft, detected)
    AlertDialog(
        onDismissRequest = onCancel, containerColor = NetProbeColors.surface,
        title = { Text(EditCopy.TITLE, style = NetProbeText.rowName, color = NetProbeColors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("editBody")) {
                EditTextField(EditCopy.NAME, draft.name, "editName") { draft = draft.copy(name = it) }
                FieldFooter(detected.name, overridden = edits.name != null, tag = "resetName") { draft = draft.copy(name = detected.name) }
                TypeField(draft.type) { draft = draft.copy(type = it) }
                FieldFooter(detected.type.takeIf { it != DeviceType.UNKNOWN }?.let(EditCopy::typeLabel), overridden = edits.type != null, tag = "resetType") {
                    draft = draft.copy(type = detected.type.takeIf { it != DeviceType.UNKNOWN })
                }
                EditTextField(EditCopy.VENDOR, draft.vendor, "editVendor") { draft = draft.copy(vendor = it) }
                FieldFooter(detected.vendor, overridden = edits.vendor != null, tag = "resetVendor") { draft = draft.copy(vendor = detected.vendor.orEmpty()) }
                EditTextField(EditCopy.MODEL, draft.model, "editModel") { draft = draft.copy(model = it) }
                FieldFooter(detected.model, overridden = edits.model != null, tag = "resetModel") { draft = draft.copy(model = detected.model.orEmpty()) }
            }
        },
        confirmButton = { TextButton({ onSave(edits) }, Modifier.testTag("editSave")) { Text(EditCopy.SAVE, color = NetProbeColors.signal) } },
        dismissButton = { TextButton(onCancel, Modifier.testTag("editCancel")) { Text(EditCopy.CANCEL, color = NetProbeColors.textSecondary) } },
    )
}

/** Single line; input beyond 80 characters is not accepted (spec 4.3). */
@Composable
private fun EditTextField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= DeviceEdits.MAX_LEN) onChange(it) }, Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) }, singleLine = true, colors = npFieldColors(), shape = RoundedCornerShape(FIELD_RADIUS))
}

/** Read-only field opening a menu of the nine real types; "Not set" is shown, never chosen. */
@Composable
private fun TypeField(type: DeviceType?, onPick: (DeviceType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(type?.let(EditCopy::typeLabel) ?: EditCopy.NOT_SET, {}, Modifier.fillMaxWidth(), readOnly = true,
            label = { Text(EditCopy.TYPE) }, singleLine = true, colors = npFieldColors(), shape = RoundedCornerShape(FIELD_RADIUS))
        // A read-only text field still takes focus on tap; this layer opens the picker instead.
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true }.testTag("editType"))
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            EditCopy.TYPES.forEach { t ->
                DropdownMenuItem(text = { Text(EditCopy.typeLabel(t)) }, onClick = { onPick(t); open = false }, modifier = Modifier.testTag("type:${t.name}"))
            }
        }
    }
}

@Composable
private fun FieldFooter(detected: String?, overridden: Boolean, tag: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(EditCopy.detected(detected), style = NetProbeText.data, color = NetProbeColors.textMuted, modifier = Modifier.weight(1f))
        if (overridden) Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onReset).testTag(tag), contentAlignment = Alignment.Center) {
            Text(EditCopy.RESET, style = NetProbeText.action, color = NetProbeColors.signal)
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`:
- Replace `EditableName` with:
```kotlin
/** Title; a tap opens Edit device (spec 4.3, replaces the rename dialog). */
@Composable
fun DetailName(name: String, onEdit: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onEdit)
        .semantics { contentDescription = DetailCopy.editDescription(name) }.testTag("detailName"), contentAlignment = Alignment.CenterStart) {
        Text(name, style = NetProbeText.title, color = NetProbeColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The identity line; a tap opens Edit device too (spec 4.3). */
@Composable
fun IdentityText(text: String, name: String, onEdit: () -> Unit) {
    Text(text, style = NetProbeText.secondary, color = NetProbeColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(role = Role.Button, onClick = onEdit).semantics { contentDescription = DetailCopy.editDescription(name) }.testTag("identityLine"))
}

/** Overflow on the "Back to Home" row (spec 4.5): "Edit device" and, from Task 14, "Remove device"; both always enabled. */
@Composable
fun DetailMenu(onEdit: () -> Unit, onRemove: (() -> Unit)? = null) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("detailMenu")) {
            Icon(Icons.Default.MoreVert, DetailCopy.MORE_OPTIONS, tint = NetProbeColors.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(DetailCopy.EDIT_DEVICE) }, onClick = { open = false; onEdit() })
            onRemove?.let { remove -> DropdownMenuItem(text = { Text(DetailCopy.REMOVE_DEVICE) }, onClick = { open = false; remove() }) }
        }
    }
}
```
  (imports `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.MoreVert`.)
- In `ReasonsSheet`, after the "Why this identity" title `Text`, add:
```kotlin
            setByYouLine(h)?.let { Text(it, style = NetProbeText.secondary, color = NetProbeColors.text) }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt`: replace `BackToHome` with:
```kotlin
/** "Back to Home" with a leading chevron, a 48 dp touch row (spec 5.1 item 1, section 6); [trailing] holds Detail's overflow (Phase 3A 4.5). */
@Composable
fun BackToHome(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = Dimens.touch).clickable(role = Role.Button, onClick = onBack).padding(horizontal = Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Back to Home", style = NetProbeText.data, color = NetProbeColors.textMuted)
        }
        trailing()
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`:
- In `DetailActions`, replace `val onRename: (String?) -> Unit = {}` with `val onEdit: (DeviceEdits) -> Unit = {}` (import `com.northwalessolutions.netprobe.data.*`).
- Add `var editing by rememberSaveable { mutableStateOf(false) }` next to `why`.
- Replace the topBar's `BackToHome(actions.onBack)` and the name and identity lines with:
```kotlin
            BackToHome(actions.onBack) { if (state.host != null) DetailMenu(onEdit = { editing = true }) }
            state.host?.let { h ->
                Column(Modifier.padding(horizontal = Dimens.gutter)) {
                    DetailName(state.displayName) { editing = true }
                    identityWithType(IdentityLine.of(h), Effective.type(h))?.let { IdentityText(it, state.displayName) { editing = true } }
```
  (the data line, `SoftwareChips`, `DetailLinks`, status segments and note are unchanged below it).
- After the `if (why) ...` line, add:
```kotlin
    if (editing) state.host?.let { h ->
        EditDeviceDialog(EditDraft.initial(h), Detected.of(h), onSave = { actions.onEdit(it); editing = false }, onCancel = { editing = false })
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: replace `setCustomName` with:
```kotlin
    fun setOverrides(edits: DeviceEdits) { viewModelScope.launch { c.hosts.setOverrides(hostId, edits) } }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt`: change `onRename = vm::setCustomName` to `onEdit = vm::setOverrides`.

`app/src/main/java/com/northwalessolutions/netprobe/data/Repositories.kt`: delete `HostRepository.setCustomName`. `data/Daos.kt`: delete `HostDao.setCustomName` (`setOverrides` replaces both, spec 4.1).

- [ ] **Step 3: Compile the Compose tests, then the task gate**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL` for both (the Compose and DAO tests run in Task 18).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Edit device dialog from the name, the identity line and the Detail overflow; Set by you line"
```

---

### Task 11: "Open with..." on every port: PortUris, ActionProvider, Try as, the port sheet and the title sub-line

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/actions/PortUris.kt`
- Modify: `actions/ActionProvider.kt` (replace), `ui/detail/DetailViewModel.kt` (`PortRow.tryAs`, cached), `ui/detail/DetailText.kt` (`portSubLine`, `sheetSplit`), `ui/detail/DetailCopy.kt`, `ui/detail/DetailComponents.kt` (`PortSheet`, `TryAsRow`, `PortRowItem` sub-line)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/actions/PortUrisTest.kt`, `actions/ActionProviderTest.kt` (tests added), `ui/detail/DetailTextTest.kt` (two tests), `ui/home/CopyTableTest.kt`; androidTest `ui/detail/DetailFakes.kt` (`tryAs` parameter), `DetailContentTest.kt` (two tests, compile only)

**Interfaces:**
- Consumes: `ServiceInfo.certCn` (Task 3), the "HTTP" / "HTTPS" / "TLS" names the ladder produces (Task 5), `HandlerResolver`.
- Produces:
```kotlin
object PortUris { val TRY_AS: List<String>; fun uri(scheme: String, ip: String, port: Int): String
                  fun guess(port: Int, service: ServiceInfo): String?; fun fallback(service: ServiceInfo): String }
ActionProvider.actionsFor(ip, port, service): List<DeviceAction>     // always exactly one OpenWith
ActionProvider.tryAs(ip: String, port: Int): List<Pair<String, String>>
data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>, val tryAs: List<Pair<String, String>> = emptyList())
fun portSubLine(s: ServiceInfo): String?; fun sheetSplit(p: PortRow): Pair<List<Pair<String, DeviceAction>>, List<Pair<String, DeviceAction>>>
DetailCopy += TRY_AS, tryAsDescription(ip, port, scheme)
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/actions/PortUrisTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 6 and 7. */
class PortUrisTest {
    private fun s(name: String, c: Confidence = Confidence.CONFIRMED) = ServiceInfo(name, confidence = c)
    private val ip = "10.0.3.59"

    @Test fun `uri forms for every try as scheme`() {
        assertEquals(listOf("http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb"), PortUris.TRY_AS)
        assertEquals("http://10.0.3.59:8090/", PortUris.uri("http", ip, 8090)); assertEquals("https://10.0.3.59:9443/", PortUris.uri("https", ip, 9443))
        assertEquals("ftp://10.0.3.59:21/", PortUris.uri("ftp", ip, 21))
        assertEquals("ssh://10.0.3.59:2200", PortUris.uri("ssh", ip, 2200)); assertEquals("vnc://10.0.3.59:5901", PortUris.uri("vnc", ip, 5901))
        assertEquals("rdp://10.0.3.59:3389", PortUris.uri("rdp", ip, 3389)); assertEquals("telnet://10.0.3.59:23", PortUris.uri("telnet", ip, 23))
        assertEquals("smb://10.0.3.59/", PortUris.uri("smb", ip, 445)); assertEquals("smb://10.0.3.59/", PortUris.uri("smb", ip, 139))
        assertEquals("smb://10.0.3.59:8445/", PortUris.uri("smb", ip, 8445))
    }
    @Test fun `guess from the service name wins over the port`() {
        assertEquals("http", PortUris.guess(8090, s("HTTP"))); assertEquals("https", PortUris.guess(9443, s("HTTPS")))
        assertNull("bare TLS is not a guess", PortUris.guess(993, s("TLS"))); assertNull(PortUris.guess(8009, s("TLS")))
        assertEquals("ssh", PortUris.guess(2200, s("SSH"))); assertEquals("telnet", PortUris.guess(2323, s("Telnet")))
        assertNull(PortUris.guess(7000, s("Unknown", Confidence.PORT_TABLE)))
    }
    @Test fun `port fallbacks are unchanged`() {
        val unknown = s("Unknown", Confidence.PORT_TABLE)
        for (p in listOf(80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000)) assertEquals("$p", "http", PortUris.guess(p, unknown))
        for (p in listOf(443, 8443)) assertEquals("https", PortUris.guess(p, unknown))
        for (p in listOf(445, 139)) assertEquals("smb", PortUris.guess(p, unknown))
        for (p in listOf(22, 2222)) assertEquals("ssh", PortUris.guess(p, unknown))
        assertEquals("telnet", PortUris.guess(23, unknown)); assertEquals("rdp", PortUris.guess(3389, unknown)); assertEquals("ftp", PortUris.guess(21, unknown))
        for (p in 5900..5910) assertEquals("vnc", PortUris.guess(p, unknown))
    }
    @Test fun `open with fallback is https for tls or a certificate and http otherwise`() {
        assertEquals("https", PortUris.fallback(s("TLS")))
        assertEquals("https", PortUris.fallback(ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE, certCn = "x.lan")))
        assertEquals("http", PortUris.fallback(s("Unknown", Confidence.PORT_TABLE)))
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/actions/ActionProviderTest.kt` (spec 9.1 test 8; import `com.northwalessolutions.netprobe.ui.detail.sheetLabel`):
```kotlin
    private val confirmed = { name: String -> ServiceInfo(name, confidence = Confidence.CONFIRMED) }
    @Test fun `an http banner on any port gives an open primary`() {
        assertEquals("http://10.0.3.59:8123/", (all.primary("10.0.3.59", 8123, confirmed("HTTP")) as DeviceAction.OpenUri).uri)
        assertEquals("http://10.0.3.59:8090/", (all.primary("10.0.3.59", 8090, confirmed("HTTP")) as DeviceAction.OpenUri).uri)
        assertEquals("https://10.0.3.59:9443/", (all.primary("10.0.3.59", 9443, confirmed("HTTPS")) as DeviceAction.OpenUri).uri)
    }
    @Test fun `a bare tls port gets no primary and open with defaults to https`() {
        val tls = ServiceInfo("TLS", confidence = Confidence.CONFIRMED, certCn = "imap.lan")
        assertNull(all.primary("10.0.3.59", 993, tls))
        assertEquals(listOf(DeviceAction.OpenWith("https://10.0.3.59:993/")), all.actionsFor("10.0.3.59", 993, tls).filterIsInstance<DeviceAction.OpenWith>())
    }
    @Test fun `an unknown port gets no primary and open with defaults to http`() {
        assertNull(all.primary("10.0.3.59", 7000, svc("Unknown")))
        assertEquals(listOf(DeviceAction.OpenWith("http://10.0.3.59:7000/")), all.actionsFor("10.0.3.59", 7000, svc("Unknown")).filterIsInstance<DeviceAction.OpenWith>())
    }
    @Test fun `every port has exactly one open with`() {
        for ((port, name) in listOf(80 to "HTTP", 22 to "SSH", 445 to "SMB", 993 to "TLS", 7000 to "Unknown", 3389 to "RDP", 8090 to "HTTP"))
            for (p in listOf(all, none, onlyHttp)) assertEquals("$name on $port", 1, p.actionsFor("10.0.3.59", port, svc(name)).count { it is DeviceAction.OpenWith })
    }
    @Test fun `no app installed is still produced and still filtered by the sheet`() {
        val open = none.actionsFor("10.0.3.59", 8090, confirmed("HTTP")).filterIsInstance<DeviceAction.OpenUri>().single()
        assertEquals("No app installed for http://", open.label); assertFalse(open.hasHandler); assertNull(sheetLabel(open))
    }
    @Test fun `try as offers only schemes with a handler, in order`() {
        val some = ActionProvider { it.startsWith("ssh") || it.startsWith("http") }
        assertEquals(listOf("http" to "http://10.0.3.59:8090/", "https" to "https://10.0.3.59:8090/", "ssh" to "ssh://10.0.3.59:8090"), some.tryAs("10.0.3.59", 8090))
        assertEquals(emptyList<Pair<String, String>>(), none.tryAs("10.0.3.59", 8090))
        assertEquals(PortUris.TRY_AS, all.tryAs("10.0.3.59", 445).map { it.first })
    }
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt`:
```kotlin
    @Test fun `port sub line puts the title before the version line`() {
        assertEquals("Synology DiskStation  nginx 200 OK", portSubLine(ServiceInfo("HTTP", "nginx", null, "200 OK", Confidence.CONFIRMED, title = "Synology DiskStation")))
        assertEquals("Synology DiskStation", portSubLine(ServiceInfo("HTTP", confidence = Confidence.CONFIRMED, title = "Synology DiskStation")))
        assertEquals("OpenSSH 8.2p1", portSubLine(ServiceInfo("SSH", "OpenSSH", "8.2p1", null, Confidence.CONFIRMED)))
        assertNull(portSubLine(http))
    }
    @Test fun `the sheet always has open with and splits around the try as row`() {
        val (head, tail) = sheetSplit(port(8090, "http://10.0.3.59:8090/"))
        assertEquals(listOf("Open with..."), head.map { it.first }); assertEquals(listOf("Copy IP", "Copy IP and port"), tail.map { it.first })
    }
```

In `CopyTableTest`, add to `templates`:
```kotlin
        "Open with\\.\\.\\.", "Try as", "(http|https|ssh|vnc|rdp|ftp|telnet|smb)",
        "Open \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+ as (http|https|ssh|vnc|rdp|ftp|telnet|smb)",
```
and the test (imports `com.northwalessolutions.netprobe.actions.DeviceAction`, `com.northwalessolutions.netprobe.actions.PortUris`):
```kotlin
    @Test fun `port sheet strings`() {
        inTable(DeviceAction.OpenWith("http://10.0.3.59:8090/").label); inTable(DetailCopy.TRY_AS)
        PortUris.TRY_AS.forEach { inTable(it); inTable(DetailCopy.tryAsDescription("10.0.3.59", 8123, it)) }
        assertEquals("Open 10.0.3.59:8123 as ssh", DetailCopy.tryAsDescription("10.0.3.59", 8123, "ssh"))
    }
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailFakes.kt`: give `port` a last parameter `tryAs: List<String> = emptyList()` and pass `tryAs.map { it to PortUris.uri(it, IP, n) }` as the `PortRow`'s fifth argument (import `com.northwalessolutions.netprobe.actions.PortUris`).

Append to `DetailContentTest` (spec 9.2 test 13; import `androidx.compose.ui.semantics.SemanticsProperties` is already present):
```kotlin
    private val tryAsChip = SemanticsMatcher("a Try as chip") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tryAs:") == true }
    @Test fun portSheetOffersOpenWithAndTryAsChips() {
        show(DetailFakes.state(listOf(DetailFakes.port(8090, "http", tryAs = listOf("http", "https", "ssh")))))
        rule.onNodeWithTag("portAction").assertTextEquals("Open")
        rule.onNodeWithText("8090").performTouchInput { longClick() }
        rule.onNodeWithText("Open with...").assertIsDisplayed(); rule.onNodeWithText("Try as").assertIsDisplayed()
        rule.onAllNodes(tryAsChip).assertCountEquals(3)
        rule.onAllNodesWithText("No app installed", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("tryAs:ssh").assert(hasContentDescription("Open 10.0.3.59:8090 as ssh")).performClick()
        assertEquals(DeviceAction.OpenWith("ssh://10.0.3.59:8090"), actions.last())
        rule.onNodeWithText("Open with...").assertDoesNotExist()
    }
    @Test fun noHandlersMeansNoTryAsRow() {
        show(DetailFakes.state(listOf(DetailFakes.port(8090, "http"))))
        rule.onNodeWithText("8090").performTouchInput { longClick() }
        rule.onNodeWithText("Open with...").assertIsDisplayed(); rule.onNodeWithText("Try as").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.actions.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'PortUris'` (and `tryAs`, `portSubLine`, `sheetSplit`, `TRY_AS`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/actions/PortUris.kt`:
```kotlin
package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

/** The one table of port schemes and URI forms (spec 2.3); ActionProvider's port fallback delegates here. */
object PortUris {
    val TRY_AS = listOf("http", "https", "ssh", "vnc", "rdp", "ftp", "telnet", "smb")
    private val NAMED = setOf("SMB", "SSH", "TELNET", "VNC", "RDP", "FTP")
    private val SMB_DEFAULT_PORTS = setOf(139, 445)
    private val HTTP_PORTS = setOf(80, 8080, 8000, 8008, 8081, 8888, 3000, 5000, 9000)
    private val HTTPS_PORTS = setOf(443, 8443)
    private val SSH_PORTS = setOf(22, 2222)
    private val VNC_PORTS = 5900..5910
    private const val TELNET_PORT = 23
    private const val RDP_PORT = 3389
    private const val FTP_PORT = 21

    fun uri(scheme: String, ip: String, port: Int): String = when (scheme) {
        "http", "https", "ftp" -> "$scheme://$ip:$port/"
        "smb" -> if (port in SMB_DEFAULT_PORTS) "smb://$ip/" else "smb://$ip:$port/"
        else -> "$scheme://$ip:$port"
    }

    /**
     * The scheme the app believes the port speaks, first match wins (spec 2.3). A bare "TLS" (a handshake with no HTTP answer)
     * is not a guess, so 993, 995, 465, 636, 853, 8883 and Cast's 8009 never get "Open in browser" (Gruff m6).
     */
    fun guess(port: Int, service: ServiceInfo): String? {
        val name = service.name.uppercase()
        return when {
            name == "HTTPS" -> "https"
            name == "HTTP" -> "http"
            name in NAMED -> name.lowercase()
            else -> byPort(port)
        }
    }

    /** "Open with..." for a port with no guess: https for a bare TLS port or one with a certificate, http otherwise (A8). */
    fun fallback(service: ServiceInfo): String = if (service.name.equals("TLS", ignoreCase = true) || service.certCn != null) "https" else "http"

    private fun byPort(port: Int): String? = when (port) {
        in HTTP_PORTS -> "http"; in HTTPS_PORTS -> "https"; in SMB_DEFAULT_PORTS -> "smb"; in SSH_PORTS -> "ssh"
        TELNET_PORT -> "telnet"; in VNC_PORTS -> "vnc"; RDP_PORT -> "rdp"; FTP_PORT -> "ftp"; else -> null
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/actions/ActionProvider.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.actions

import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

class ActionProvider(private val resolver: HandlerResolver) {
    private val labels = mapOf("http" to "Open in browser", "https" to "Open in browser", "smb" to "Open share", "ssh" to "Connect with SSH app",
        "telnet" to "Connect with Telnet app", "vnc" to "Connect with VNC app", "rdp" to "Connect with RDP app", "ftp" to "Open with FTP app")

    /** The guessed open (or its "No app installed" entry, filtered by the sheet), always one "Open with...", then the two copies (spec 2.3). */
    fun actionsFor(ip: String, port: Int, service: ServiceInfo): List<DeviceAction> {
        val out = ArrayList<DeviceAction>()
        val guess = PortUris.guess(port, service)
        if (guess != null) {
            val u = PortUris.uri(guess, ip, port); val has = resolver.hasHandler(u)
            out += DeviceAction.OpenUri(if (has) labels.getValue(guess) else "No app installed for $guess://", u, has)
        }
        out += DeviceAction.OpenWith(PortUris.uri(guess ?: PortUris.fallback(service), ip, port))
        out += DeviceAction.Copy("Copy IP", ip); out += DeviceAction.Copy("Copy IP:port", "$ip:$port")
        return out
    }
    fun primary(ip: String, port: Int, service: ServiceInfo): DeviceAction? =
        actionsFor(ip, port, service).filterIsInstance<DeviceAction.OpenUri>().firstOrNull { it.hasHandler }
    /** (scheme, uri) for each Try as scheme an installed app handles, in TRY_AS order; empty when none (Gruff m7, D9). */
    fun tryAs(ip: String, port: Int): List<Pair<String, String>> =
        PortUris.TRY_AS.map { it to PortUris.uri(it, ip, port) }.filter { (_, u) -> resolver.hasHandler(u) }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`:
- Replace `data class PortRow(...)` with `data class PortRow(val port: Int, val service: ServiceInfo, val primary: DeviceAction?, val actions: List<DeviceAction>, val tryAs: List<Pair<String, String>> = emptyList())`.
- Add, inside the class (import `java.util.concurrent.ConcurrentHashMap`):
```kotlin
    /** Try as pairs per port, cached: the package manager is asked once per port, not on every state emission (review checklist: no per-emission work). */
    private val tryAsCache = ConcurrentHashMap<Int, List<Pair<String, String>>>()
    private fun tryAsFor(ip: String, port: Int) = tryAsCache.getOrPut(port) { c.actions.tryAs(ip, port) }
```
- In the `state` combine, change the `PortRow(...)` construction to:
```kotlin
            PortRow(p.port, info, h?.let { c.actions.primary(it.ip, p.port, info) }, h?.let { c.actions.actionsFor(it.ip, p.port, info) } ?: emptyList(),
                h?.let { tryAsFor(it.ip, p.port) }.orEmpty()) }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`: add inside the object:
```kotlin
    const val TRY_AS = "Try as"
    fun tryAsDescription(ip: String, port: Int, scheme: String) = "Open $ip:$port as $scheme"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`: add:
```kotlin
/** Port row sub-line: the page title, two spaces, then the version line; either alone when the other is missing (spec 7). */
fun portSubLine(s: ServiceInfo): String? = listOfNotNull(s.title, versionLine(s)).joinToString("  ").ifEmpty { null }

/** Sheet rows split around the Try as chips: the other opens and "Open with..." above, the copy rows below (spec 2.4). */
fun sheetSplit(p: PortRow): Pair<List<Pair<String, DeviceAction>>, List<Pair<String, DeviceAction>>> {
    val rows = sheetActions(p)
    val cut = rows.indexOfFirst { it.second is DeviceAction.OpenWith } + 1
    return rows.take(cut) to rows.drop(cut)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`:
- In `PortRowItem`, replace `versionLine(p.service)?.let { ... }` with `portSubLine(p.service)?.let { Text(it, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }`.
- Replace `PortSheet` with (imports `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.foundation.horizontalScroll`, `androidx.compose.foundation.rememberScrollState` already added in Task 8):
```kotlin
private val TRY_AS_CHIP_HEIGHT = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortSheet(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onVulns: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = NetProbeColors.surface, shape = SHEET_SHAPE) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(bottom = 16.dp)) {
            Text("$ip:${p.port} ${p.service.name}", style = NetProbeText.rowName, color = NetProbeColors.text, modifier = Modifier.padding(bottom = 8.dp))
            val (head, tail) = sheetSplit(p)
            head.forEach { (label, a) -> SheetRow(label, if (a is DeviceAction.OpenWith) NetProbeColors.signal else NetProbeColors.text) { onAction(a); onClose() } }
            if (p.tryAs.isNotEmpty()) TryAsRow(ip, p.port, p.tryAs) { uri -> onAction(DeviceAction.OpenWith(uri)); onClose() }
            tail.forEach { (label, a) -> SheetRow(label, NetProbeColors.text) { onAction(a); onClose() } }
            SheetRow("Look up vulnerabilities for this service", NetProbeColors.signal) { onVulns(); onClose() }
        }
    }
}

/** "Try as": a caption, then one scrolling row of chips, one per scheme with an installed handler (spec 2.4 item 4). */
@Composable
private fun TryAsRow(ip: String, port: Int, pairs: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(DetailCopy.TRY_AS, style = NetProbeText.caption, color = NetProbeColors.textMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pairs.forEach { (scheme, uri) ->
                Box(Modifier.heightIn(min = Dimens.touch).clickable(role = Role.Button) { onPick(uri) }
                    .semantics { contentDescription = DetailCopy.tryAsDescription(ip, port, scheme) }.testTag("tryAs:$scheme"), contentAlignment = Alignment.Center) {
                    Box(Modifier.heightIn(min = TRY_AS_CHIP_HEIGHT).clip(CircleShape).border(1.dp, NetProbeColors.border, CircleShape).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text(scheme, style = NetProbeText.chip, color = NetProbeColors.textSecondary)
                    }
                }
            }
        }
    }
}
```
The sheet is a `ModalBottomSheet` and holds no Scan or Stop control.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.actions.*" --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `PortUrisTest` (4), every `ActionProviderTest` test (old and new), the new `DetailTextTest` and `CopyTableTest` tests pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Open with... on every port, Try as chips, HTTP primary on any port, title sub-line"
```

---

### Task 12: Phase 2 follow-ups: bind wording, "Stopped after N s", persisted last run, identify tail on Detail

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`, `ui/home/RadarModes.kt` (`ScanSummaryText`, `RadarSummaries`, `RadarText`), `ui/home/HomeStats.kt` (`StaleRun`), `ui/home/HomeViewModel.kt` (`HomeUiState.scanCompleted`, `lastRunCompleted`, `markRun`, seeding), `ui/home/HomeContent.kt`, `ui/home/RadarBlock.kt` (announcement), `ui/detail/DetailCopy.kt`, `ui/detail/DetailText.kt` (`detailProgress`, `detailFraction`), `ui/detail/DetailViewModel.kt` (bind wording, `IdentifyCount`), `ui/detail/DetailComponents.kt` (`ScanBar`), `ui/detail/DetailContent.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/StaleRunTest.kt`, `ScanSummaryTextTest.kt` (two tests), `ui/detail/DetailTextTest.kt` (one test), `ui/home/CopyTableTest.kt`; androidTest `ui/home/HomeContentTest.kt` (one test, compile only)

**Interfaces:**
- Consumes: `NetworkEntity.lastRunAt`, `NetworkRepository.markRun` (Task 2), `ScanProgress`.
- Produces:
```kotlin
HomeCopy.stoppedAfter(seconds: Long): String
ScanSummaryText.of(totalHosts, otherSubnetHosts, durationMs, completed: Boolean = true): RadarSummary
RadarSummaries.settled(totalHosts, otherSubnetHosts, lastScanAt, scanStartedAt, scanFinishedAt, now, completed: Boolean = true, formatDate = ...)
RadarText.stopped(headline: String): String
object StaleRun { fun seed(previousNetworkId: Long?, networkId: Long?, current: Long, lastRunAt: Long?): Long; fun toPersist(completed: Boolean, runId: Long): Long? }
HomeUiState(..., val scanCompleted: Boolean = true)
RadarBlock(..., scanCompleted: Boolean = true)
DetailCopy.SCAN_BIND_FAILED; DetailCopy.identifying(n: Int): String
fun detailProgress(p: ScanProgress?, identifying: Int = 0): String; fun detailFraction(p: ScanProgress?, identified: Int): Float
data class IdentifyCount(val pending: Int = 0, val done: Int = 0)
DetailUiState(..., val identifying: Int = 0, val identified: Int = 0)
ScanBar(..., identifying: Int = 0, identified: Int = 0)
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/StaleRunTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.*
import org.junit.Test

/** spec 5.5 and 9.1 test 31 (Gruff m13, Q1). */
class StaleRunTest {
    @Test fun `seeds from the stored run only when the network id changes`() {
        assertEquals(500L, StaleRun.seed(previousNetworkId = null, networkId = 7, current = 0, lastRunAt = 500))
        assertEquals(0L, StaleRun.seed(previousNetworkId = 7, networkId = 8, current = 900, lastRunAt = null))   // also fixes the old cross-network bug
        assertEquals(0L, StaleRun.seed(previousNetworkId = 7, networkId = null, current = 900, lastRunAt = null))
    }
    @Test fun `a re-emission for the same network keeps an in-progress run`() {
        assertEquals(900L, StaleRun.seed(previousNetworkId = 7, networkId = 7, current = 900, lastRunAt = 500))
    }
    @Test fun `only a completed run is persisted`() {
        assertEquals(1_234L, StaleRun.toPersist(completed = true, runId = 1_234)); assertNull(StaleRun.toPersist(completed = false, runId = 1_234))
    }
}
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/ui/home/ScanSummaryTextTest.kt` (spec 9.1 tests 26 and 27):
```kotlin
    @Test fun `a stopped run reads stopped after`() {
        assertEquals(RadarSummary("13 devices", "Stopped after 12 s"), ScanSummaryText.of(13, 0, 12_000, completed = false))
        assertEquals(RadarSummary("13 devices", "Stopped after 12 s, 1 on another subnet"), ScanSummaryText.of(13, 1, 12_000, completed = false))
        assertEquals(RadarSummary("No devices found", "Stopped after 12 s"), ScanSummaryText.of(0, 0, 12_000, completed = false))
        assertEquals("Stopped after 12 s", RadarSummaries.settled(13, 0, 5_000L, 1_000_000L, 1_012_000L, 10_000_000L, completed = false) { "d" }.detail)
        assertEquals("Scanned in 12 s", ScanSummaryText.of(13, 0, 12_000).detail)
    }
    @Test fun `finished and stopped announcements`() {
        assertEquals("Scan stopped, 13 devices", RadarText.stopped("13 devices")); assertEquals("Scan stopped, no devices found", RadarText.stopped("No devices found"))
        assertEquals("Scan finished, 13 devices", RadarText.finished("13 devices"))
    }
```

Append to `app/src/test/java/com/northwalessolutions/netprobe/ui/detail/DetailTextTest.kt` (spec 9.1 test 29):
```kotlin
    @Test fun `detail progress counts identifies`() {
        assertEquals("312 of 65535, found 4", detailProgress(ScanProgress(312, 65535, 4, 0), identifying = 3))   // the sweep runs: text unchanged
        assertEquals("identifying 3 ports", detailProgress(ScanProgress(65535, 65535, 4, 0), identifying = 3))
        assertEquals("identifying 1 port", detailProgress(ScanProgress(1000, 1000, 1, 0), identifying = 1))
        assertEquals("1000 of 1000, found 2", detailProgress(ScanProgress(1000, 1000, 2, 0), identifying = 0))
        assertEquals(0.5f, detailFraction(ScanProgress(100, 100, 100, 0), identified = 0), 0.0001f)
        assertEquals(1f, detailFraction(ScanProgress(100, 100, 4, 0), identified = 4), 0.0001f)
        assertEquals(0f, detailFraction(null, 0), 0f); assertEquals(0f, detailFraction(ScanProgress(0, 0, 0, 0), 0), 0f)
    }
```

In `CopyTableTest`: replace the template `"Scanned in \\d+ s(, \\d+ on another subnet)?"` with `"(Scanned in|Stopped after) \\d+ s(, \\d+ on another subnet)?"`, add to `templates`:
```kotlin
        "Scan (finished|stopped), (\\d+ devices?|no devices found)", "Scan skipped: could not bind to Wi-Fi", "identifying \\d+ ports?",
        "\\d+ of \\d+, found \\d+",
```
in `every summary state matches the table`, wrap the `RadarSummaries.settled(...)` loop body in `for (completed in listOf(true, false))` and pass `completed = completed`, and change the last line to `for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) for (completed in listOf(true, false)) ScanSummaryText.of(hosts, other, 12_000, completed).let { inTable(it.headline); inTable(it.detail) }`; then add:
```kotlin
    @Test fun `announcements, bind wording and the identify tail`() {
        for (h in listOf("No devices found", "1 device", "13 devices")) { inTable(RadarText.finished(h)); inTable(RadarText.stopped(h)) }
        inTable(DetailCopy.SCAN_BIND_FAILED); inTable(DetailCopy.identifying(1)); inTable(DetailCopy.identifying(3))
        for (p in listOf(com.northwalessolutions.netprobe.engine.portscan.ScanProgress(312, 65535, 4, 0), com.northwalessolutions.netprobe.engine.portscan.ScanProgress(9, 9, 2, 0)))
            for (n in listOf(0, 1, 3)) inTable(com.northwalessolutions.netprobe.ui.detail.detailProgress(p, n))
    }
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (spec 9.2 test 18):
```kotlin
    @Test fun aStoppedRunSaysStoppedAfter() {
        show(HomeFakes.settled().copy(scanStartedAt = 1_000L, scanFinishedAt = 13_000L, scanCompleted = false))
        rule.onNodeWithText("Stopped after 12 s").assertIsDisplayed()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'StaleRun'`, `No parameter with name 'completed' found`, `Unresolved reference 'stopped'`, `'detailFraction'`, `'SCAN_BIND_FAILED'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: add `fun stoppedAfter(seconds: Long) = "Stopped after $seconds s"` next to `scannedIn`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarModes.kt`:
```kotlin
object ScanSummaryText {
    private const val MS_PER_S = 1000L
    private const val HALF_S = 500L
    /** "Scanned in N s" after a completed run, "Stopped after N s" after Stop or a failure (spec 5.4); ", K on another subnet" either way. */
    fun of(totalHosts: Int, otherSubnetHosts: Int, durationMs: Long, completed: Boolean = true): RadarSummary {
        val seconds = ((durationMs + HALF_S) / MS_PER_S).coerceAtLeast(1)
        val lead = if (completed) HomeCopy.scannedIn(seconds) else HomeCopy.stoppedAfter(seconds)
        val detail = lead + if (otherSubnetHosts > 0) ", " + HomeCopy.onAnotherSubnet(otherSubnetHosts) else ""
        return RadarSummary(headline(totalHosts), detail)
    }
    fun headline(totalHosts: Int): String = if (totalHosts == 0) HomeCopy.NO_DEVICES else HomeCopy.devices(totalHosts)
}
```
In `RadarSummaries.settled`, add the parameter `completed: Boolean = true` after `now` (before `formatDate`, so the trailing-lambda callers still bind `formatDate`) and pass it: `return ScanSummaryText.of(totalHosts, otherSubnetHosts, scanFinishedAt - scanStartedAt, completed)`.
In `RadarText`, add `fun stopped(headline: String): String = "Scan stopped, ${lowerFirst(headline)}"`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeStats.kt`: add:
```kotlin
/** Stale dimming across restarts (spec 5.5): the in-session latestRun is seeded from the network row only when the network changes. */
object StaleRun {
    fun seed(previousNetworkId: Long?, networkId: Long?, current: Long, lastRunAt: Long?): Long =
        if (previousNetworkId == networkId) current else lastRunAt ?: 0L
    /** A run stopped after 2 s, failed or killed must never dim nearly every host after a restart (Gruff Q1). */
    fun toPersist(completed: Boolean, runId: Long): Long? = if (completed) runId else null
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
- `HomeUiState`: append `val scanCompleted: Boolean = true`.
- Add `private val lastRunCompleted = MutableStateFlow(true)` with the other flows.
- In the `state` combine, add `lastRunCompleted` as the 15th flow (after `lastRunProgress`), and add `scanCompleted = arr[14] as Boolean` to the `HomeUiState(...)` call.
- In the env collector, before `networkId.value = net?.id`, add:
```kotlin
                latestRun.value = StaleRun.seed(networkId.value, net?.id, latestRun.value, net?.lastRunAt)
```
- In `discover`: after `val runId = ...; latestRun.value = runId` add `lastRunCompleted.value = false`; replace `completed = true` with `completed = true; lastRunCompleted.value = true`; in the `finally`, after `c.locks.release()`, add:
```kotlin
            StaleRun.toPersist(completed, runId)?.let { id -> withContext(NonCancellable) { c.networks.markRun(net.id, id) } }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`: pass `completed = state.scanCompleted` to `RadarSummaries.settled(...)` (named, before nothing else changes) and `scanCompleted = state.scanCompleted` to `RadarBlock(...)`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarBlock.kt`: add the parameter `scanCompleted: Boolean = true` after `onStopQuick`, and change the announcement to:
```kotlin
        if (previous == RadarMode.SCANNING && mode == RadarMode.SETTLED)
            view.announceForAccessibility(if (scanCompleted) RadarText.finished(summary.headline) else RadarText.stopped(summary.headline))
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailCopy.kt`: add:
```kotlin
    const val SCAN_BIND_FAILED = "Scan skipped: could not bind to Wi-Fi"
    fun identifying(ports: Int) = if (ports == 1) "identifying 1 port" else "identifying $ports ports"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailText.kt`: replace `detailProgress` with:
```kotlin
/** "312 of 65535, found 4" while the sweep runs; "identifying 3 ports" once it is done and identifies are still running (spec 1.14, Gruff m16). */
fun detailProgress(p: ScanProgress?, identifying: Int = 0): String {
    if (p == null) return "starting"
    if (p.done >= p.total && identifying > 0) return DetailCopy.identifying(identifying)
    return "${p.done} of ${p.total}, found ${p.open}"
}

/** (ports probed + identifies finished) / (ports total + open ports found). */
fun detailFraction(p: ScanProgress?, identified: Int): Float {
    if (p == null) return 0f
    val total = p.total + p.open
    if (total == 0) return 0f
    return ((p.done + identified).toFloat() / total).coerceIn(0f, 1f)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`:
- Add at top level: `/** Identifies started and finished during the current Detail scan (spec 1.14). */ data class IdentifyCount(val pending: Int = 0, val done: Int = 0)`.
- `DetailUiState`: append `val identifying: Int = 0, val identified: Int = 0`.
- Add `private val identify = MutableStateFlow(IdentifyCount())`; add it as the 11th flow of the `state` combine and pass `identifying = (arr[10] as IdentifyCount).pending, identified = (arr[10] as IdentifyCount).done` to `DetailUiState(...)`.
- In `startScan`, replace `message.value = HomeCopy.BIND_FAILED` with `message.value = DetailCopy.SCAN_BIND_FAILED`.
- In `scanHostOn`, set `identify.value = IdentifyCount()` before `coroutineScope`, and replace the collect body with:
```kotlin
            c.portScanEngine.scan(ip, profile, c.settings.scanOptions()).filter { it.state == PortState.OPEN }.collect { r ->
                identify.update { it.copy(pending = it.pending + 1) }
                launch {
                    try {
                        sem.withPermit {
                            val info = c.serviceIdentifier.identify(ip, r.port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), mode)
                            synchronized(found) { found[r.port] = info }
                            c.ports.upsertOpen(hostId, r.port, info)
                        }
                    } finally { identify.update { IdentifyCount(it.pending - 1, it.done + 1) } }
                }
            }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`, `ScanBar`: add parameters `identifying: Int = 0, identified: Int = 0` after `enabled`; the progress text becomes `detailProgress(progress, identifying)` and the indicator `progress = { detailFraction(progress, identified) }`. `DetailContent` passes `identifying = state.identifying, identified = state.identified`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `StaleRunTest` (3), the new `ScanSummaryTextTest`, `DetailTextTest` and `CopyTableTest` tests pass, existing ones stay green; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Follow-ups: Detail bind wording, Stopped after N s, persisted last completed run, identify tail on Detail"
```

---

### Task 13: Undo, the app scope, and Forget this network as a soft delete

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/UndoStore.kt`
- Modify: `AppContainer.kt` (`appScope`, `undo`, start-up purge), `ui/home/HomeViewModel.kt` (Forget, Undo, commit at scan start, `networkId` and `undoText` in state), `ui/home/HomeCopy.kt`, `ui/home/HomeComponents.kt` (`HomeMenu` Forget item, `NoticeLine` Undo), `ui/home/HomeContent.kt`, `ui/home/HomeScreen.kt`, `ui/detail/DetailViewModel.kt` (commit at scan start)
- Test: `app/src/test/java/com/northwalessolutions/netprobe/UndoStoreTest.kt`, `AppScopesTest.kt`, `ui/home/CopyTableTest.kt`; androidTest `ui/home/HomeContentTest.kt` (four tests, compile only)

**Interfaces:**
- Consumes: `NetworkRepository.setForgotten/deleteNetworkCascade/purgeForgotten` and nullable `ensure` (Task 2), `ManualRangeStore.set`.
- Produces:
```kotlin
class PendingUndo(val text: String, val restore: suspend () -> Unit, val commit: suspend () -> Unit)
class UndoStore { val pending: StateFlow<PendingUndo?>; suspend fun post(next: PendingUndo); suspend fun undo(); suspend fun commit() }
object AppScopes { const val LOG_TAG = "NetProbe"; fun handler(log: (Throwable) -> Unit): CoroutineExceptionHandler; fun create(handler: CoroutineExceptionHandler): CoroutineScope }
AppContainer.appScope: CoroutineScope; AppContainer.undo: UndoStore
HomeViewModel.forgetNetwork(); undo(); dismissUndo()
HomeUiState(..., val networkId: Long? = null, val undoText: String? = null)
HomeActions(..., val onForget: () -> Unit = {}, val onUndo: () -> Unit = {}, val onDismissUndo: () -> Unit = {})
HomeCopy.FORGET_NETWORK, HomeCopy.UNDO, HomeCopy.forgot(name), HomeCopy.removed(name)
fun NoticeLine(text: String, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null)
fun HomeMenu(quickEnabled, quickScanning, onQuick, onStopQuick, onSettings, forgetEnabled: Boolean = false, onForget: () -> Unit = {})
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/UndoStoreTest.kt`:
```kotlin
package com.northwalessolutions.netprobe

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 test 30, Review Focus 4: at most one Undo; every scan start commits it, so a restore can never land mid-scan. */
class UndoStoreTest {
    private val log = mutableListOf<String>()
    private fun undoable(name: String) = PendingUndo("Removed $name", restore = { log += "restore $name" }, commit = { log += "commit $name" })

    @Test fun `post commits the previous undo first`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.post(undoable("B"))
        assertEquals(listOf("commit A"), log); assertEquals("Removed B", s.pending.value?.text)
    }
    @Test fun `undo runs restore once and clears`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.undo(); s.undo()
        assertEquals(listOf("restore A"), log); assertNull(s.pending.value)
    }
    @Test fun `commit is idempotent`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.commit(); s.commit()
        assertEquals(listOf("commit A"), log); assertNull(s.pending.value)
    }
    @Test fun `dismissing commits and a later undo restores nothing`() = runTest {
        val s = UndoStore(); s.post(undoable("A")); s.commit(); s.undo()
        assertEquals(listOf("commit A"), log)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/AppScopesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** spec 9.3 test 6 on the JVM: a failure in a delete or restore is logged and the app scope survives. */
class AppScopesTest {
    @Test fun `a failing background task is logged and the scope keeps working`() = runBlocking {
        val seen = CompletableDeferred<Throwable>()
        val scope = AppScopes.create(AppScopes.handler { seen.complete(it) })
        scope.launch { throw IllegalStateException("UNIQUE constraint failed: hosts.id") }
        assertEquals("UNIQUE constraint failed: hosts.id", withTimeout(1_000) { seen.await() }.message)
        assertEquals(42, scope.async { 42 }.await())   // SupervisorJob: one failure never cancels the scope
        scope.cancel()
    }
}
```

In `CopyTableTest`, add to `templates`:
```kotlin
        "Forget this network", "Forgot .+", "Removed .+", "Undo",
```
and the test:
```kotlin
    @Test fun `forget and undo strings`() {
        listOf(HomeCopy.FORGET_NETWORK, HomeCopy.UNDO, HomeCopy.forgot("Home"), HomeCopy.removed("DiskStation")).forEach { inTable(it) }
        assertEquals("Forgot Home", HomeCopy.forgot("Home")); assertEquals("Removed DiskStation", HomeCopy.removed("DiskStation"))
    }
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (spec 9.2 test 16):
```kotlin
    @Test fun undoNoticeShowsAndAnEarlierNoticeReturnsAfterIt() {
        var undos = 0
        var st by mutableStateOf(HomeFakes.settled(message = "Stopped at 10.0.4.0/24").copy(undoText = "Removed DiskStation"))
        rule.setContent { NetProbeTheme { HomeContent(st, HomeActions(onUndo = { undos++ }), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithText("Removed DiskStation").assertIsDisplayed(); rule.onNodeWithText("Stopped at 10.0.4.0/24").assertDoesNotExist()
        rule.onNodeWithTag("noticeUndo").assert(hasContentDescription("Undo")).performClick(); assertEquals(1, undos)
        st = st.copy(undoText = null); rule.waitForIdle()
        rule.onNodeWithText("Stopped at 10.0.4.0/24").assertIsDisplayed()
    }
    @Test fun dismissingTheUndoNoticeCommitsItAndKeepsTheEarlierNotice() {
        var commits = 0; var dismissals = 0
        rule.setContent { NetProbeTheme {
            HomeContent(HomeFakes.settled(message = "Stopped at 10.0.4.0/24").copy(undoText = "Removed DiskStation"),
                HomeActions(onDismissUndo = { commits++ }, onDismissMessage = { dismissals++ }), now = 121_000L, ownModel = "Pixel 7 Pro")
        } }
        rule.onNodeWithContentDescription("Dismiss notice").performClick()
        assertEquals(1, commits); assertEquals(0, dismissals)
    }
    @Test fun forgetThisNetworkIsDisabledWhileARadarOrQuickScanRuns() {
        var st by mutableStateOf(HomeFakes.scanning().copy(networkId = 1))
        rule.setContent { NetProbeTheme { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithTag("homeMenu").performClick(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        st = HomeFakes.settled().copy(networkId = 1, quickScanning = true); rule.waitForIdle()
        rule.onNodeWithText("Forget this network").assertIsNotEnabled()
    }
    @Test fun forgetThisNetworkRunsWhenIdleAndNeedsANetworkRow() {
        var forgets = 0
        var st by mutableStateOf(HomeFakes.settled().copy(networkId = null))
        rule.setContent { NetProbeTheme { HomeContent(st, HomeActions(onForget = { forgets++ }), now = 121_000L, ownModel = "Pixel 7 Pro") } }
        rule.onNodeWithTag("homeMenu").performClick(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        st = st.copy(networkId = 1); rule.waitForIdle()
        rule.onNodeWithText("Forget this network").assertIsEnabled().performClick(); assertEquals(1, forgets)
    }
```
The settle case (Forget disabled while the radar settles) is added in Task 16, when the radar reports busy.

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.UndoStoreTest" --tests "com.northwalessolutions.netprobe.AppScopesTest" --tests "com.northwalessolutions.netprobe.ui.home.CopyTableTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'UndoStore'` (and `PendingUndo`, `AppScopes`, `FORGET_NETWORK`, `forgot`, `removed`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/UndoStore.kt`:
```kotlin
package com.northwalessolutions.netprobe

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One Undo offered in the notice line (spec 5.3). [commit] makes the action final; [restore] reverses it. */
class PendingUndo(val text: String, val restore: suspend () -> Unit, val commit: suspend () -> Unit)

/**
 * At most one pending Undo, memory only (spec 5.3, A6). It is committed when the notice is dismissed, when a new Undo replaces it,
 * and at the very start of every scan body, so a restore can never land while a scan is writing.
 */
class UndoStore {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingUndo?>(null)
    val pending: StateFlow<PendingUndo?> = _pending

    /** Commits the previous Undo first. */
    suspend fun post(next: PendingUndo) {
        mutex.withLock {
            val previous = _pending.value
            _pending.value = null
            previous?.commit?.invoke()
            _pending.value = next
        }
    }
    suspend fun undo() {
        mutex.withLock {
            val p = _pending.value ?: return@withLock
            _pending.value = null
            p.restore()
        }
    }
    /** Idempotent: a second call finds nothing pending. */
    suspend fun commit() {
        mutex.withLock {
            val p = _pending.value ?: return@withLock
            _pending.value = null
            p.commit()
        }
    }
}

object AppScopes {
    const val LOG_TAG = "NetProbe"
    /** Logs and never rethrows: an unexpected failure in a delete or restore can never crash the app (spec 5.3). */
    fun handler(log: (Throwable) -> Unit): CoroutineExceptionHandler = CoroutineExceptionHandler { _, t -> log(t) }
    fun create(handler: CoroutineExceptionHandler): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add (imports `android.util.Log`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.launch`):
```kotlin
    /** Deletes and restores run here, so leaving a screen never cancels them; failures are logged, never thrown (spec 5.3). */
    val appScope: CoroutineScope = AppScopes.create(AppScopes.handler { Log.e(AppScopes.LOG_TAG, "background task failed", it) })
    val undo = UndoStore()
```
and, as the LAST member of the class body (after every `by lazy` property, so the delegates exist before the coroutine can touch them):
```kotlin
    init {
        // A Forget left pending by a killed process is final: purge it once at start-up (spec 5.3).
        appScope.launch { networks.purgeForgotten { settings.manual.set(it, emptyList()) } }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeCopy.kt`: add:
```kotlin
    const val FORGET_NETWORK = "Forget this network"
    const val UNDO = "Undo"
    fun forgot(network: String) = "Forgot $network"
    fun removed(device: String) = "Removed $device"
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
- `HomeUiState`: append `val networkId: Long? = null, val undoText: String? = null`.
- Add fields (import `com.northwalessolutions.netprobe.PendingUndo`):
```kotlin
    /** The network whose Forget is pending: Home shows it empty until Undo or the commit (spec 5.3). */
    private val forgottenId = MutableStateFlow<Long?>(null)
    /** Bumped to make the env collector resolve the network row again (after Forget, Undo or the commit). */
    private val envEpoch = MutableStateFlow(0)
```
- Replace the `hosts` flow with:
```kotlin
    private val hosts: Flow<List<HostEntity>> = combine(networkId, forgottenId) { id, gone -> id?.takeIf { it != gone } }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.hosts.hostsForNetwork(id) }
```
- In the `state` combine, add `networkId` (16th) and `c.undo.pending` (17th) after `lastRunCompleted`, and pass `networkId = arr[15] as Long?, undoText = (arr[16] as PendingUndo?)?.text` to `HomeUiState(...)`.
- Replace `env.collectLatest { e -> ... }` in `init` with `combine(env, envEpoch) { e, _ -> e }.collectLatest { e -> ... }` (body unchanged; `ensure` returns null for the forgotten row, so `networkId` and `c.currentNetwork` become null and the run memory is cleared by `RunMemory.afterNetworkChange`).
- First line of `discover`, before `ensure`: `c.undo.commit()   // a pending Undo is final before the scan's first lookup (spec 5.3)`. First line of `quickScanAllOn`: the same.
- Add:
```kotlin
    /** Home overflow "Forget this network": a soft delete until the Undo ends; the UI disables it while a scan or the settle runs (spec 5.3). */
    fun forgetNetwork() {
        if (controller.isRunning || quick.isRunning) return
        val net = c.currentNetwork.value ?: return
        c.appScope.launch {
            c.networks.setForgotten(net.id, System.currentTimeMillis())
            forgottenId.value = net.id
            envEpoch.update { it + 1 }                      // ensure() now returns null for this network: Home shows it empty
            c.undo.post(PendingUndo(HomeCopy.forgot(net.displayName),
                restore = { c.networks.setForgotten(net.id, null); forgottenId.value = null; envEpoch.update { it + 1 } },
                commit = {
                    c.networks.deleteNetworkCascade(net.id); c.settings.manual.set(net.id, emptyList())
                    forgottenId.value = null; envEpoch.update { it + 1 }   // the env collector then inserts a fresh row for the current env
                }))
        }
    }
    fun undo() { c.appScope.launch { c.undo.undo() } }
    /** Dismissing the Undo notice commits it; a notice showing before it is not dismissed (spec 5.3). */
    fun dismissUndo() { c.appScope.launch { c.undo.commit() } }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: in `startScan`, change the scan body to `scan.start { e -> c.undo.commit(); try { scanHost(h, resolved, e) } catch (_: BindFailedException) { message.value = DetailCopy.SCAN_BIND_FAILED } }`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt`:
- Replace `HomeMenu` with:
```kotlin
@Composable
fun HomeMenu(quickEnabled: Boolean, quickScanning: Boolean, onQuick: () -> Unit, onStopQuick: () -> Unit, onSettings: () -> Unit,
             forgetEnabled: Boolean = false, onForget: () -> Unit = {}) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("homeMenu")) { Icon(Icons.Default.MoreVert, "Menu", tint = NetProbeColors.textSecondary) }
        DropdownMenu(open, { open = false }, containerColor = NetProbeColors.surface) {
            DropdownMenuItem(text = { Text(if (quickScanning) "Stop quick scan" else "Quick scan all") }, enabled = quickEnabled,
                onClick = { open = false; if (quickScanning) onStopQuick() else onQuick() })
            DropdownMenuItem(text = { Text(HomeCopy.FORGET_NETWORK) }, enabled = forgetEnabled, onClick = { open = false; onForget() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
        }
    }
}
```
- Replace `NoticeLine` with:
```kotlin
/** One notice line under the radar for every transient message (D17), with a 48 dp dismiss target; [onUndo] adds the Undo word (spec 5.3). */
@Composable
fun NoticeLine(text: String, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Dimens.gutter).testTag("notice"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = NOTICE_MAX_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onUndo != null) Box(Modifier.heightIn(min = Dimens.touch).widthIn(min = Dimens.touch).clickable(onClick = onUndo)
            .semantics { contentDescription = HomeCopy.UNDO; role = Role.Button }.testTag("noticeUndo"), contentAlignment = Alignment.Center) {
            Text(HomeCopy.UNDO, style = NetProbeText.action, color = NetProbeColors.signal)
        }
        Box(Modifier.size(Dimens.touch).clickable(onClick = onDismiss).semantics { contentDescription = "Dismiss notice"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = NetProbeColors.textMuted, modifier = Modifier.size(ICON_SMALL))
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`:
- `HomeActions`: append `val onForget: () -> Unit = {}, val onUndo: () -> Unit = {}, val onDismissUndo: () -> Unit = {}`.
- Pass `forgetEnabled = state.networkId != null && !state.scanning && !state.quickScanning, onForget = actions.onForget` to `HomeMenu`.
- Replace `state.message?.let { NoticeLine(it, actions.onDismissMessage) }` with:
```kotlin
            // While an Undo is pending it takes the notice line; an earlier notice was not dismissed and shows again afterwards (spec 5.3).
            when {
                state.undoText != null -> NoticeLine(state.undoText, actions.onDismissUndo, onUndo = actions.onUndo)
                state.message != null -> NoticeLine(state.message, actions.onDismissMessage)
            }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeScreen.kt`: add `onForget = vm::forgetNetwork, onUndo = vm::undo, onDismissUndo = vm::dismissUndo` to `HomeActions(...)`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `UndoStoreTest` (4), `AppScopesTest` (1) and the new `CopyTableTest` test pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Undo store and app scope; Forget this network as a soft delete with Undo"
```

---

### Task 14: Remove device: stop that host's scans, skip it for the rest of a radar scan, delete with Undo

**Files:**
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RemovedHosts.kt`
- Modify: `AppContainer.kt` (`hostScanJobs`, `removedDuringScan`, `radarScanNetworkId`), `ui/home/QuickScanAll.kt` (`QuickScanAllLoop`), `ui/home/ScanController.kt` (`stopAndJoin`), `ui/home/HomeViewModel.kt` (`discover` skip, `quickScanAllOn` loop), `ui/detail/DetailViewModel.kt` (`removeDevice`), `ui/detail/DetailContent.kt` (`onRemove`, overflow item), `ui/detail/DetailScreen.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RemovedHostsTest.kt`, `QuickScanAllLoopTest.kt`, `ScanControllerJoinTest.kt`; `app/src/androidTest/java/com/northwalessolutions/netprobe/data/RemoveDeviceTest.kt`, `ui/detail/DetailContentTest.kt` (one test), all androidTest compile only

**Interfaces:**
- Consumes: `UndoStore`, `appScope` (Task 13), `HostRepository.snapshot/delete/restore` (Task 2), `HomeCopy.removed` (Task 13).
- Produces:
```kotlin
object RemovedHosts { fun skip(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Boolean
                      fun added(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>>
                      fun without(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>> }
object QuickScanAllLoop { suspend fun <H> run(hosts: List<H>, idOf: (H) -> Long, skip: (H) -> Boolean, jobs: MutableMap<Long, Job>, scanOne: suspend (H) -> Unit) }
ScanController.stopAndJoin()
AppContainer.hostScanJobs: ConcurrentHashMap<Long, Job>; removedDuringScan: MutableStateFlow<Set<Pair<Long, String>>>; radarScanNetworkId: MutableStateFlow<Long?>
DetailViewModel.removeDevice(); DetailActions(..., val onRemove: () -> Unit = {})
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RemovedHostsTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.*
import org.junit.Test

/** Review Focus 4: the running radar scan never re-inserts a removed host (spec 5.3 step 2). */
class RemovedHostsTest {
    @Test fun `a removed ip is skipped for the rest of the radar scan`() {
        val set = RemovedHosts.added(emptySet(), 1, "10.0.3.59")
        assertTrue(RemovedHosts.skip(set, 1, "10.0.3.59"))
        assertFalse("another network's host with the same ip", RemovedHosts.skip(set, 2, "10.0.3.59"))
        assertFalse(RemovedHosts.skip(set, 1, "10.0.3.60"))
    }
    @Test fun `undo takes the pair out again`() {
        val set = RemovedHosts.added(RemovedHosts.added(emptySet(), 1, "10.0.3.59"), 1, "10.0.3.70")
        assertEquals(setOf(1L to "10.0.3.70"), RemovedHosts.without(set, 1, "10.0.3.59"))
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/QuickScanAllLoopTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** spec 5.3 step 1, Review Focus 4: Remove cancels one host's child job; Quick scan all treats it as "skip this host". */
class QuickScanAllLoopTest {
    @Test fun `cancelling one host skips it and the loop carries on`() = runTest {
        val jobs = ConcurrentHashMap<Long, Job>(); val done = mutableListOf<Long>()
        val inHostTwo = CompletableDeferred<Unit>()
        val loop = launch {
            QuickScanAllLoop.run(listOf(1L, 2L, 3L), { it }, { false }, jobs) { id ->
                if (id == 2L) { inHostTwo.complete(Unit); awaitCancellation() }
                done += id
            }
        }
        inHostTwo.await()
        jobs.getValue(2L).cancelAndJoin()                 // Remove device on host 2
        loop.join()
        assertEquals(listOf(1L, 3L), done); assertTrue(jobs.isEmpty()); assertFalse(loop.isCancelled)
    }
    @Test fun `stopping quick scan all stops the loop`() = runTest {
        val jobs = ConcurrentHashMap<Long, Job>(); val started = CompletableDeferred<Unit>(); val done = mutableListOf<Long>()
        val loop = launch { QuickScanAllLoop.run(listOf(1L, 2L), { it }, { false }, jobs) { started.complete(Unit); awaitCancellation() } }
        started.await(); loop.cancelAndJoin()
        assertTrue(loop.isCancelled); assertTrue(jobs.isEmpty()); assertTrue(done.isEmpty())
    }
    @Test fun `a host removed during the radar scan is skipped`() = runTest {
        val done = mutableListOf<Long>()
        QuickScanAllLoop.run(listOf(1L, 2L), { it }, { it == 2L }, ConcurrentHashMap()) { done += it }
        assertEquals(listOf(1L), done)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/ScanControllerJoinTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerJoinTest {
    @Test fun `stop and join waits for the scan's cleanup`() = runTest {
        val env = MutableStateFlow<NetworkEnv?>(TestEnvs.home); val events = mutableListOf<String>()
        val c = ScanController(backgroundScope, env) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(100); events += "cleanup" } }
        }
        c.start(); runCurrent()
        c.stopAndJoin(); events += "joined"
        assertEquals(listOf("cleanup", "joined"), events); assertFalse(c.state.value.scanning)
    }
    @Test fun `stop and join with nothing running returns at once`() = runTest {
        val c = ScanController(backgroundScope, MutableStateFlow<NetworkEnv?>(TestEnvs.home)) { }
        c.stopAndJoin(); assertFalse(c.isRunning)
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/data/RemoveDeviceTest.kt` (spec 9.3 tests 5 and 6):
```kotlin
package com.northwalessolutions.netprobe.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.AppScopes
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.ui.home.QuickScanAllLoop
import com.northwalessolutions.netprobe.ui.home.RemovedHosts
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class RemoveDeviceTest {
    private lateinit var db: NetProbeDb
    private lateinit var hosts: HostRepository
    private lateinit var ports: PortRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NetProbeDb::class.java).build()
        hosts = HostRepository(db); ports = PortRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test fun aRemovedIpIsNotReinsertedByTheSameRunAndUndoRestoresIt() = runBlocking {
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.59"), mdnsName = "CHNAS"), 1L)
        hosts.setNote(id, "QA note NAS")
        val snap = hosts.snapshot(id)!!; hosts.delete(snap)
        val removed = RemovedHosts.added(emptySet(), 1, "10.0.3.59")
        // The running discovery emits the host again; the collector's guard (HomeViewModel.discover) skips it.
        for (h in listOf(DiscoveredHost(ip("10.0.3.59")), DiscoveredHost(ip("10.0.3.60")))) {
            if (!RemovedHosts.skip(removed, 1, h.ip.hostAddress!!)) hosts.upsertDiscovered(1, h, 1L)
        }
        assertNull(db.hosts().find(1, "10.0.3.59")); assertNotNull(db.hosts().find(1, "10.0.3.60"))
        hosts.restore(snap)
        assertEquals(snap, hosts.snapshot(id)); assertEquals("QA note NAS", hosts.byIdOnce(id)!!.note)
    }
    @Test fun removeDuringQuickScanAllLeavesNoOrphansAndTheLoopCarriesOn() = runBlocking {
        val a = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.1")), 1L)
        val b = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.2")), 1L)
        val c = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.3")), 1L)
        val jobs = ConcurrentHashMap<Long, Job>(); val scanned = mutableListOf<Long>(); val inB = CompletableDeferred<Unit>()
        val loop = launch(Dispatchers.Default) {
            QuickScanAllLoop.run(listOf(a, b, c), { it }, { false }, jobs) { id ->
                if (id == b) { ports.upsertOpen(b, 22, ServiceInfo("SSH", confidence = Confidence.PORT_TABLE)); inB.complete(Unit); awaitCancellation() }
                ports.upsertOpen(id, 80, ServiceInfo("HTTP", confidence = Confidence.PORT_TABLE)); synchronized(scanned) { scanned += id }
            }
        }
        inB.await()
        jobs.getValue(b).cancelAndJoin()                                  // Remove step 1: that host's job, and wait for it
        val snap = hosts.snapshot(b)!!; hosts.delete(snap)                // Remove step 3
        loop.join()
        assertEquals(listOf(a, c), scanned)
        assertNull(hosts.byIdOnce(b)); assertEquals(emptyMap<Int, ServiceInfo>(), ports.servicesFor(b))   // no orphan port rows
    }
    @Test fun aRestoreThatHitsAConstraintIsCaughtByTheAppScopeHandler() = runBlocking {
        val id = hosts.upsertDiscovered(1, DiscoveredHost(ip("10.0.3.80")), 1L)
        val snap = hosts.snapshot(id)!!                                   // not deleted: the restore clashes on the primary key
        val seen = CompletableDeferred<Throwable>()
        val scope = AppScopes.create(AppScopes.handler { seen.complete(it) })
        scope.launch { hosts.restore(snap) }
        assertTrue(withTimeout(5_000) { seen.await() } is SQLiteConstraintException)
        assertEquals(42, scope.async { 42 }.await()); scope.cancel()
    }
}
```

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/detail/DetailContentTest.kt` (spec 9.2 test 15, second half):
```kotlin
    @Test fun overflowRemoveStopsTheScanFirstThenRemovesAndGoesBack() {
        val events = mutableListOf<String>()
        rule.setContent { NetProbeTheme { DeviceConfigurationOverride(phone) {
            DetailContent(DetailFakes.state(scanning = true), DetailActions(onStop = { events += "stop" }, onRemove = { events += "remove" }, onBack = { events += "back" }))
        } } }
        rule.onNodeWithTag("detailMenu").performClick()
        rule.onNodeWithText("Edit device").assertIsEnabled()
        rule.onNodeWithText("Remove device").assertIsEnabled().performClick()
        assertEquals(listOf("stop", "remove", "back"), events)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'RemovedHosts'`, `'QuickScanAllLoop'`, `'stopAndJoin'`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RemovedHosts.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

/** (network, ip) pairs removed while a radar scan runs: that scan merges by IP, so it must skip them until it ends (spec 5.3 step 2). */
object RemovedHosts {
    fun skip(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Boolean = (networkId to ip) in removed
    fun added(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>> = removed + (networkId to ip)
    fun without(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>> = removed - (networkId to ip)
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/QuickScanAll.kt`: add (imports `kotlinx.coroutines.Job`, `kotlinx.coroutines.coroutineScope`, `kotlinx.coroutines.ensureActive`, `kotlinx.coroutines.launch`):
```kotlin
/**
 * Quick scan all's host loop (spec 5.3 step 1): each host runs in its own child job registered in [jobs], so Remove device can
 * cancel exactly that host and wait for it. That one cancellation means "skip this host"; stopping the whole scan still stops the loop.
 * [scanOne] must catch its own failures (one odd host never ends the sweep).
 */
object QuickScanAllLoop {
    suspend fun <H> run(hosts: List<H>, idOf: (H) -> Long, skip: (H) -> Boolean, jobs: MutableMap<Long, Job>, scanOne: suspend (H) -> Unit) {
        coroutineScope {
            for (h in hosts) {
                if (skip(h)) continue
                val id = idOf(h)
                val job = launch { scanOne(h) }
                jobs[id] = job
                try { job.join() } finally { jobs.remove(id, job) }
                ensureActive()
            }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/ScanController.kt`: add:
```kotlin
    /** Stop, then wait until the cancelled job (and its finally) has finished: Remove device must not race a port write (spec 5.3 step 1). */
    suspend fun stopAndJoin() {
        val running = job
        stop()
        running?.join()
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/AppContainer.kt`: add (imports `java.util.concurrent.ConcurrentHashMap`, `kotlinx.coroutines.Job`):
```kotlin
    /** Each host's Quick scan all child job, so Remove device cancels exactly that host's scan (spec 5.3 step 1). */
    val hostScanJobs = ConcurrentHashMap<Long, Job>()
    /** (network, ip) removed while a radar scan runs; that scan and Quick scan all skip them; cleared at the next radar scan start. */
    val removedDuringScan = MutableStateFlow<Set<Pair<Long, String>>>(emptySet())
    /** The network a radar scan is running on, or null. */
    val radarScanNetworkId = MutableStateFlow<Long?>(null)
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeViewModel.kt`:
- In `discover`, inside the `try` before `c.discoveryEngine.discover(...)`, add:
```kotlin
            c.removedDuringScan.value = emptySet()          // a new radar scan starts with nothing removed (spec 5.3 step 2)
            c.radarScanNetworkId.value = net.id
```
  as the first statement of the collect lambda:
```kotlin
                if (RemovedHosts.skip(c.removedDuringScan.value, net.id, h.ip.hostAddress)) return@collect   // removed during this run: never re-inserted
```
  and in the `finally`, first line: `c.radarScanNetworkId.value = null`.
- Replace the `for (h in ...)` loop in `quickScanAllOn` with:
```kotlin
            QuickScanAllLoop.run(c.hosts.hostsForNetwork(net).first(), { it.id }, { RemovedHosts.skip(c.removedDuringScan.value, net, it.ip) }, c.hostScanJobs) { h ->
                // One unreachable or odd host must not end the sweep for the rest; an off-subnet host that cannot be bound is skipped and said so.
                try { quickScanHost(h, e) } catch (ce: CancellationException) { throw ce } catch (_: BindFailedException) { unbound++ } catch (_: Exception) { failed++ }
            }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailViewModel.kt`: add (imports `com.northwalessolutions.netprobe.PendingUndo`, `com.northwalessolutions.netprobe.ui.home.RemovedHosts`):
```kotlin
    /**
     * Detail overflow "Remove device" (spec 5.3): stop every scan of this host and wait for it, mark its IP removed for a running radar
     * scan, delete the host and its ports in one transaction, and offer Undo on Home. Runs on appScope (on Main first, so the scan
     * controller is touched on its own thread), so leaving the screen never cancels it.
     */
    fun removeDevice() {
        val h = state.value.host ?: return
        val name = state.value.displayName
        c.appScope.launch(Dispatchers.Main.immediate) {
            scan.stopAndJoin()
            c.hostScanJobs[hostId]?.cancelAndJoin()
            if (c.radarScanNetworkId.value == h.networkId) c.removedDuringScan.update { RemovedHosts.added(it, h.networkId, h.ip) }
            val snap = c.hosts.snapshot(hostId) ?: return@launch
            c.hosts.delete(snap)
            c.undo.post(PendingUndo(HomeCopy.removed(name),
                restore = { c.hosts.restore(snap); c.removedDuringScan.update { RemovedHosts.without(it, h.networkId, h.ip) } },
                commit = {}))
        }
    }
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt`:
- `DetailActions`: append `val onRemove: () -> Unit = {}`.
- Change the overflow to `DetailMenu(onEdit = { editing = true }, onRemove = { if (state.scanning) actions.onStop(); actions.onRemove(); actions.onBack() })`.

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailScreen.kt`: add `onRemove = vm::removeDevice`.

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `RemovedHostsTest` (2), `QuickScanAllLoopTest` (3), `ScanControllerJoinTest` (2) pass with the existing Home tests; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Remove device: stop that host's scans, skip it for the rest of a radar scan, delete with Undo"
```

---

### Task 15: Motion tokens and the pure radar helpers: ArmTrail, afterglow, breath, blip angle and look, PulseGate, RowStagger

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Motion.kt` (`Motion` object), `ui/home/RadarGeometry.kt`
- Create: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/MotionRules.kt`
- Test: `app/src/test/java/com/northwalessolutions/netprobe/ui/home/RadarMotionTest.kt`, `MotionRulesTest.kt`

**Interfaces:**
- Consumes: `blipOffset`, `RadarGeometry.GOLDEN_ANGLE_DEG`, `RadarGeometry.jitterDeg` (existing).
- Produces:
```kotlin
object Motion { /* existing tokens, ARM_TURN_MS = 3000 */ TRAIL_DEG, ARM_HEAD_ALPHA, HEAD_LINE_ALPHA, HEAD_LINE_WIDTH, AFTERGLOW_DEG, BLIP_REST_ALPHA,
    BLIP_STATIC_ALPHA, GLOW_REST_ALPHA, GLOW_LIT_ALPHA, GLOW_LIT_SCALE, PULSE_MS, PULSE_ALPHA, PULSE_STROKE, PULSE_MIN_GAP_MS, BREATH_MIN, BREATH_MAX,
    SETTLE_DAMPING, SETTLE_STIFFNESS, ARM_FADE_MS, ROW_STAGGER_MS, ROW_STAGGER_MAX_MS, NAV_MS, NAV_ENTER_OFFSET, NAV_EXIT_OFFSET, PORT_ROW_MS,
    SELECT_MS, PILL_MORPH_MS, PILL_SQUASH, PILL_SQUASH_AT_MS, NOTICE_IN_MS, NOTICE_OUT_MS, IDENTITY_FADE_MS, EDGE_COLOUR_MS, PROGRESS_MS }
object ArmTrail { fun stops(): List<Pair<Float, Float>> }
fun afterglow(armDeg: Float, blipDeg: Float): Float
fun breath(armDeg: Float): Float
fun blipAngleDeg(offset: Offset): Float
fun blipCoreAlpha(glow: Float, reduced: Boolean): Float; fun blipGlowAlpha(glow: Float, reduced: Boolean): Float; fun blipGlowScale(glow: Float, reduced: Boolean): Float
object PulseGate { fun shouldStart(nowMs: Long, lastStartMs: Long?): Boolean }
object RowStagger { fun delays(newIds: List<Long>): Map<Long, Int> }
```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/RadarMotionTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.ui.geometry.Offset
import com.northwalessolutions.netprobe.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** spec 9.1 tests 1 to 4, and the reduced-motion blip look of 9.2 test 5 (a draw-phase value no Compose test can read). */
class RadarMotionTest {
    @Test fun `arm trail stops put the bright edge at the head`() {
        val s = ArmTrail.stops()
        assertEquals(listOf(0f, 0.6111f, 0.7222f, 0.8333f, 0.9167f, 0.9722f, 0.9972f, 1f), s.map { (it.first * 10_000).roundToInt() / 10_000f })
        assertEquals(listOf(0f, 0f, 0.03f, 0.09f, 0.20f, 0.38f, 0.55f, 0.55f), s.map { it.second })
        assertEquals(0.55f, s.last().second, 0f); assertEquals(1f, s.last().first, 0f)
        val tailStart = s.single { abs(it.first - 0.6111f) < 0.0001f }
        assertEquals(0f, tailStart.second, 0f); assertEquals(0f, s.first().second, 0f)
        s.filter { it.first >= tailStart.first }.zipWithNext().forEach { (a, b) -> assertTrue("alpha falls from $a to $b", b.second >= a.second) }
        assertEquals(140f, Motion.TRAIL_DEG, 0f); assertEquals(Motion.TRAIL_DEG, (1f - tailStart.first) * 360f, 0.05f)
    }
    @Test fun `afterglow decays over 120 degrees behind the arm`() {
        assertEquals(1f, afterglow(100f, 100f), 1e-6f)
        assertEquals(0.25f, afterglow(160f, 100f), 1e-6f)
        assertEquals(0f, afterglow(220f, 100f), 0f); assertEquals(0f, afterglow(300f, 100f), 0f)
        assertEquals("just ahead of the arm (d = 359)", 0f, afterglow(99f, 100f), 0f)
        val g = 1f - 20f / 120f
        assertEquals("wraps across 0/360", g * g, afterglow(10f, 350f), 1e-6f)
        assertEquals("the resumed arm runs past 360", afterglow(10f, 350f), afterglow(370f, 350f), 1e-6f)
    }
    @Test fun `breath peaks at 0 degrees and dips at 180`() {
        assertEquals(Motion.BREATH_MAX, breath(0f), 1e-6f); assertEquals(Motion.BREATH_MIN, breath(180f), 1e-6f)
        for (d in 0..720 step 7) { val b = breath(d.toFloat()); assertTrue("breath($d) = $b", b >= Motion.BREATH_MIN - 1e-6f && b <= Motion.BREATH_MAX + 1e-6f) }
    }
    @Test fun `blip angles match the arm's clockwise angle`() {
        for (i in 0 until RadarGeometry.MAX_BLIPS) {
            val a = blipAngleDeg(blipOffset(i, 100f))
            assertTrue("blip $i at $a", a >= 0f && a < 360f)
            val expected = ((i * RadarGeometry.GOLDEN_ANGLE_DEG + RadarGeometry.jitterDeg(i)) % 360f + 360f) % 360f
            assertEquals("blip $i", expected, a, 0.01f)
        }
        assertEquals("screen y points down: below the centre is 90 degrees", 90f, blipAngleDeg(Offset(0f, 10f)), 1e-4f)
    }
    @Test fun `blip look at rest, lit and under reduced motion`() {
        assertEquals(Motion.BLIP_REST_ALPHA, blipCoreAlpha(0f, false), 1e-6f); assertEquals(1f, blipCoreAlpha(1f, false), 1e-6f)
        assertEquals(Motion.GLOW_REST_ALPHA, blipGlowAlpha(0f, false), 1e-6f); assertEquals(Motion.GLOW_LIT_ALPHA, blipGlowAlpha(1f, false), 1e-6f)
        assertEquals(1f, blipGlowScale(0f, false), 1e-6f); assertEquals(Motion.GLOW_LIT_SCALE, blipGlowScale(1f, false), 1e-6f)
        assertEquals(Motion.BLIP_STATIC_ALPHA, blipCoreAlpha(1f, true), 0f); assertEquals(Motion.GLOW_REST_ALPHA, blipGlowAlpha(1f, true), 0f)
        assertEquals(1f, blipGlowScale(1f, true), 0f)
    }
}
```

`app/src/test/java/com/northwalessolutions/netprobe/ui/home/MotionRulesTest.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test

/** spec 9.1 tests 5 and 28, and every Phase 3A token pinned to its spec 1.2 value. */
class MotionRulesTest {
    @Test fun `rows landing together start 40 ms apart, capped at 200`() {
        val d = RowStagger.delays((1L..8L).toList())
        assertEquals(listOf(0, 40, 80, 120, 160, 200, 200, 200), (1L..8L).map { d.getValue(it) })
        assertEquals(emptyMap<Long, Int>(), RowStagger.delays(emptyList()))
    }
    @Test fun `pulse gate is by time`() {
        assertTrue(PulseGate.shouldStart(1_000, null))
        assertFalse(PulseGate.shouldStart(1_349, 1_000)); assertTrue(PulseGate.shouldStart(1_350, 1_000))
    }
    @Test fun `tokens match spec 1 2`() {
        assertEquals(3000, Motion.ARM_TURN_MS); assertEquals(0.55f, Motion.ARM_HEAD_ALPHA); assertEquals(0.85f, Motion.HEAD_LINE_ALPHA)
        assertEquals(1.5f, Motion.HEAD_LINE_WIDTH.value); assertEquals(120f, Motion.AFTERGLOW_DEG); assertEquals(0.45f, Motion.BLIP_REST_ALPHA)
        assertEquals(1f, Motion.BLIP_STATIC_ALPHA); assertEquals(0.15f, Motion.GLOW_REST_ALPHA); assertEquals(0.45f, Motion.GLOW_LIT_ALPHA)
        assertEquals(1.5f, Motion.GLOW_LIT_SCALE); assertEquals(700, Motion.PULSE_MS); assertEquals(0.35f, Motion.PULSE_ALPHA)
        assertEquals(1.5f, Motion.PULSE_STROKE.value); assertEquals(350L, Motion.PULSE_MIN_GAP_MS); assertEquals(0.10f, Motion.BREATH_MIN)
        assertEquals(0.20f, Motion.BREATH_MAX); assertEquals(360, Motion.SIZE_MS); assertEquals(0.7f, Motion.SETTLE_DAMPING); assertEquals(400f, Motion.SETTLE_STIFFNESS)
        assertEquals(200, Motion.ARM_FADE_MS); assertEquals(160, Motion.LABEL_FADE_MS); assertEquals(300, Motion.BLIP_IN_MS); assertEquals(600, Motion.ROW_ARRIVAL_MS)
        assertEquals(40, Motion.ROW_STAGGER_MS); assertEquals(200, Motion.ROW_STAGGER_MAX_MS); assertEquals(240, Motion.NAV_MS)
        assertEquals(0.25f, Motion.NAV_ENTER_OFFSET); assertEquals(0.10f, Motion.NAV_EXIT_OFFSET); assertEquals(240, Motion.PORT_ROW_MS)
        assertEquals(160, Motion.SELECT_MS); assertEquals(220, Motion.PILL_MORPH_MS); assertEquals(0.88f, Motion.PILL_SQUASH); assertEquals(90, Motion.PILL_SQUASH_AT_MS)
        assertEquals(200, Motion.NOTICE_IN_MS); assertEquals(160, Motion.NOTICE_OUT_MS); assertEquals(240, Motion.IDENTITY_FADE_MS)
        assertEquals(300, Motion.EDGE_COLOUR_MS); assertEquals(250, Motion.PROGRESS_MS); assertEquals(40f, Motion.STATIC_ARM_DEG)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.home.RadarMotionTest" --tests "com.northwalessolutions.netprobe.ui.home.MotionRulesTest" --console=plain 2>&1 | tail -20
```
Expected: `BUILD FAILED`, `Unresolved reference 'ArmTrail'` (and `afterglow`, `breath`, `blipAngleDeg`, `blipCoreAlpha`, `RowStagger`, `PulseGate`, `TRAIL_DEG` and the other new tokens).

- [ ] **Step 3: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/theme/Motion.kt`: replace the `Motion` object (add `import androidx.compose.animation.core.Spring`):
```kotlin
/** Radar timing (Radar spec 3.6) and the Phase 3A motion tokens (Phase 3A spec 1.2). */
object Motion {
    const val SIZE_MS = 360
    const val LABEL_FADE_MS = 160
    const val BLIP_IN_MS = 300
    const val BLIP_OUT_MS = 200
    /** One full turn, LinearEasing (A1: was 2400). */
    const val ARM_TURN_MS = 3000
    const val ROW_ARRIVAL_MS = 600
    const val STATIC_ARM_DEG = 40f
    const val PROGRESS_SAMPLE_MS = 250L
    val rowEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val ROW_RISE = 6.dp

    // The sweep (spec 1.3 to 1.7)
    const val TRAIL_DEG = 140f
    const val ARM_HEAD_ALPHA = 0.55f
    const val HEAD_LINE_ALPHA = 0.85f
    val HEAD_LINE_WIDTH = 1.5.dp
    const val AFTERGLOW_DEG = 120f
    const val BLIP_REST_ALPHA = 0.45f
    const val BLIP_STATIC_ALPHA = 1f
    const val GLOW_REST_ALPHA = 0.15f
    const val GLOW_LIT_ALPHA = 0.45f
    const val GLOW_LIT_SCALE = 1.5f
    const val PULSE_MS = 700
    const val PULSE_ALPHA = 0.35f
    val PULSE_STROKE = 1.5.dp
    const val PULSE_MIN_GAP_MS = 350L
    const val BREATH_MIN = 0.10f
    const val BREATH_MAX = 0.20f
    const val SETTLE_DAMPING = 0.7f
    const val SETTLE_STIFFNESS = Spring.StiffnessMediumLow   // 400f
    const val ARM_FADE_MS = 200

    // Elsewhere (spec 1.8 to 1.14)
    const val ROW_STAGGER_MS = 40
    const val ROW_STAGGER_MAX_MS = 200
    const val NAV_MS = 240
    const val NAV_ENTER_OFFSET = 0.25f
    const val NAV_EXIT_OFFSET = 0.10f
    const val PORT_ROW_MS = 240
    const val SELECT_MS = 160
    const val PILL_MORPH_MS = 220
    const val PILL_SQUASH = 0.88f
    const val PILL_SQUASH_AT_MS = 90
    const val NOTICE_IN_MS = 200
    const val NOTICE_OUT_MS = 160
    const val IDENTITY_FADE_MS = 240
    const val EDGE_COLOUR_MS = 300
    const val PROGRESS_MS = 250
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarGeometry.kt`: add (imports `com.northwalessolutions.netprobe.ui.theme.Motion`, `kotlin.math.atan2`):
```kotlin
private const val FULL_TURN_DEG = 360f
private const val HALF_F = 0.5f

/** The sweep wedge (spec 1.3): head at 360 degrees, a 140 degree tail behind it; (position, alpha of signal) for Brush.sweepGradient. */
object ArmTrail {
    private val DEGREES_ALPHA = listOf(0f to 0f, 220f to 0f, 260f to 0.03f, 300f to 0.09f, 330f to 0.20f, 350f to 0.38f,
        359f to Motion.ARM_HEAD_ALPHA, 360f to Motion.ARM_HEAD_ALPHA)
    fun stops(): List<Pair<Float, Float>> = DEGREES_ALPHA.map { (deg, alpha) -> deg / FULL_TURN_DEG to alpha }
}

/** 1 when the arm head is on the blip, falling to 0 at AFTERGLOW_DEG behind it; 0 ahead of the arm (spec 1.4). */
fun afterglow(armDeg: Float, blipDeg: Float): Float {
    val d = ((armDeg - blipDeg) % FULL_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG
    if (d >= Motion.AFTERGLOW_DEG) return 0f
    val g = 1f - d / Motion.AFTERGLOW_DEG
    return g * g
}

/** Disc outline tint towards signal, once per arm turn: BREATH_MAX at 0 degrees, BREATH_MIN at 180 (spec 1.6). */
fun breath(armDeg: Float): Float =
    Motion.BREATH_MIN + (Motion.BREATH_MAX - Motion.BREATH_MIN) * (HALF_F + HALF_F * cos(Math.toRadians(armDeg.toDouble())).toFloat())

/** The clockwise screen angle of a blip offset in [0, 360); screen y points down, as the arm's rotationZ does (spec 1.4). */
fun blipAngleDeg(offset: Offset): Float {
    val deg = Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
    return (deg % FULL_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG
}

/** Blip core alpha: lerp(BLIP_REST_ALPHA, 1, glow), or BLIP_STATIC_ALPHA under reduced motion (spec 1.4, 1.15). */
fun blipCoreAlpha(glow: Float, reduced: Boolean): Float = if (reduced) Motion.BLIP_STATIC_ALPHA else Motion.BLIP_REST_ALPHA + (1f - Motion.BLIP_REST_ALPHA) * glow
/** Blip glow alpha: lerp(GLOW_REST_ALPHA, GLOW_LIT_ALPHA, glow), or GLOW_REST_ALPHA under reduced motion. */
fun blipGlowAlpha(glow: Float, reduced: Boolean): Float = if (reduced) Motion.GLOW_REST_ALPHA else Motion.GLOW_REST_ALPHA + (Motion.GLOW_LIT_ALPHA - Motion.GLOW_REST_ALPHA) * glow
/** Glow scale about the blip centre: lerp(1, GLOW_LIT_SCALE, glow), or 1 under reduced motion. */
fun blipGlowScale(glow: Float, reduced: Boolean): Float = if (reduced) 1f else 1f + (Motion.GLOW_LIT_SCALE - 1f) * glow
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/MotionRules.kt`:
```kotlin
package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.ui.theme.Motion

/** A ring pulse starts when none has started this scan, or the last one started at least PULSE_MIN_GAP_MS ago by the frame clock (spec 1.5, Gruff M2). */
object PulseGate {
    fun shouldStart(nowMs: Long, lastStartMs: Long?): Boolean = lastStartMs == null || nowMs - lastStartMs >= Motion.PULSE_MIN_GAP_MS
}

/** The k-th new row of one list emission, in list order, waits min(k * ROW_STAGGER_MS, ROW_STAGGER_MAX_MS) (spec 1.8). */
object RowStagger {
    fun delays(newIds: List<Long>): Map<Long, Int> =
        newIds.withIndex().associate { (k, id) -> id to minOf(k * Motion.ROW_STAGGER_MS, Motion.ROW_STAGGER_MAX_MS) }
}
```

- [ ] **Step 4: Run the tests to see them pass, then the task gate**

```bash
./gradlew :app:testDebugUnitTest --tests "com.northwalessolutions.netprobe.ui.*" --console=plain 2>&1 | tail -15
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `RadarMotionTest` (5) and `MotionRulesTest` (3) pass, with `BlipOffsetTest` and `ReducedMotionTest` still green; `BUILD SUCCESSFUL`. The radar still uses its old private sweep constants until Task 16; only the turn time changes in this task.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Phase 3A motion tokens and pure radar helpers: trail, afterglow, breath, blip angle, pulse gate, row stagger"
```

---

### Task 16: The radar redesign: a trail that leads, afterglow, ring pulse, breathing outline, spring settle, each per-frame draw on its own layer

**Roles:** stronger model (implement and review). The review checklist's first two items are the acceptance bar for this task.

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarBlock.kt` (replace), `ui/home/HomeContent.kt` (busy gating)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/RadarBlockTest.kt` (five tests added, one updated), `HomeContentTest.kt` (one test), compile only

**Interfaces:**
- Consumes: Task 15 tokens and helpers, `RadarGeometry.armMask`, `blipOffset`, `StopPill`, `LocalReducedMotion`, `RadarText`, `HomeCopy`.
- Produces:
```kotlin
@Composable fun RadarBlock(mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean, onScan: () -> Unit, onStop: () -> Unit,
    modifier: Modifier = Modifier, heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {},
    scanCompleted: Boolean = true, onBusyChange: (Boolean) -> Unit = {}, onComposed: () -> Unit = {})
val ArmSpinningKey: SemanticsPropertyKey<Boolean>; val RadarFractionKey: SemanticsPropertyKey<Float>; val PulseCountKey: SemanticsPropertyKey<Int>
val PulseActiveKey: SemanticsPropertyKey<Boolean>; val BreathingKey: SemanticsPropertyKey<Boolean>      // all on the "radarBlock" node
```

Layer structure inside the disc box (spec 1.16): (1) `DiscBackground` with its own `graphicsLayer()`: fill, inner rings, HERO dots, recorded only when `f` or the size changes; (2) `ArmLayer`: rotated in its `graphicsLayer {}` block, a property change per frame; (3) `LiveLayer`: one `Spacer` with its own `graphicsLayer()` then `drawWithCache`, drawing the breathing outline, the pulse ring and the blips, in that order, above the arm. The count, "found" and the Stop pill sit outside all three.

- [ ] **Step 1: Write the Compose tests (compile only, run in Task 18)**

In `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/RadarBlockTest.kt`, add imports (`androidx.compose.runtime.Composable`, `androidx.compose.runtime.mutableIntStateOf`, `androidx.compose.ui.semantics.SemanticsPropertyKey`, `org.junit.Assert.assertFalse`) and these helpers and tests (spec 9.2 tests 1 to 5; every one sets `autoAdvance = false`):
```kotlin
    private fun hosted(reduced: Boolean = false, content: @Composable () -> Unit) =
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) { content() } } }
    private fun <T> value(key: SemanticsPropertyKey<T>): T = rule.onNodeWithTag("radarBlock").fetchSemanticsNode().config[key]

    @Test fun sweepRunsAndBreathesWhileScanningAndStopsAfterTheSettle() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SETTLED)
        hosted { RadarBlock(mode, 3, "probing 3 of 254  1 s", summary, true, {}, {}) }
        mode = RadarMode.SCANNING; rule.mainClock.advanceTimeBy(16)
        assertTrue(value(ArmSpinningKey)); assertTrue(value(BreathingKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeBy(1_000)
        assertFalse(value(ArmSpinningKey)); assertFalse(value(BreathingKey))
    }
    @Test fun ringPulsesAreGatedByTimeAndEveryPulseFinishes() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var found by mutableIntStateOf(0)
        hosted { RadarBlock(mode, found, null, summary, true, {}, {}) }
        rule.mainClock.advanceTimeBy(500)                                   // the expand is done
        val counts = (1..20).map { i -> found = i; rule.mainClock.advanceTimeBy(50); value(PulseCountKey) }   // counts[k] is at (k + 1) * 50 ms
        assertEquals("at 100 ms", 1, counts[1]); assertEquals("at 300 ms", 1, counts[5]); assertEquals("at 400 ms", 2, counts[7])
        for (k in 7 until counts.size) assertTrue("two starts inside one 350 ms window ending at ${(k + 1) * 50} ms", counts[k] - counts[k - 7] <= 1)
        rule.mainClock.advanceTimeBy(700)
        assertFalse("a pulse froze on the disc", value(PulseActiveKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeBy(1_000)
        found = 0; mode = RadarMode.SCANNING; rule.mainClock.advanceTimeBy(100)   // a new scan resets found: no pulse, count reset
        assertEquals(0, value(PulseCountKey)); assertFalse(value(PulseActiveKey))
    }
    @Test fun springSettleUndershootsOnlyTheRingAndLandsCleanly() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var busy = true
        hosted { RadarBlock(mode, 3, null, summary, true, {}, {}, onBusyChange = { busy = it }) }
        rule.mainClock.advanceTimeBy(1_000)
        mode = RadarMode.SETTLED
        val fractions = mutableListOf<Float>()
        for (step in 1..37) {                                               // 16 ms samples for about 600 ms
            rule.mainClock.advanceTimeBy(16)
            val fr = value(RadarFractionKey); fractions += fr
            val h = rule.onNodeWithTag("radarBlock").getBoundsInRoot().height
            assertTrue("block height $h fell below the settled ${Dimens.ringTarget}", h >= Dimens.ringTarget - 0.5.dp)
            if (fr != 0f) rule.onNodeWithTag("radarRing").assertIsNotEnabled()
        }
        assertTrue("no undershoot, minimum ${fractions.min()}", fractions.min() < 0f)
        assertEquals("not at rest by 448 ms", 0f, fractions[27], 0f)
        rule.onNodeWithTag("radarRing").assertIsEnabled()
        assertFalse(busy)
    }
    @Test fun steadyScanningDoesNotRecomposePerFrame() {
        rule.mainClock.autoAdvance = false
        var compositions = 0
        hosted { RadarBlock(RadarMode.SCANNING, 9, "probing 171 of 254  7 s", summary, true, {}, {}, onComposed = { compositions++ }) }
        rule.mainClock.advanceTimeBy(1_000)                                 // after the expand (which recomposes per frame by design)
        val before = compositions
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("recomposed ${compositions - before} times in 60 frames", compositions - before <= 2)
    }
    @Test fun reducedMotionRadarIsStaticAndInstant() {
        rule.mainClock.autoAdvance = false
        var mode by mutableStateOf(RadarMode.SCANNING); var found by mutableIntStateOf(1)
        hosted(reduced = true) { RadarBlock(mode, found, null, summary, true, {}, {}) }
        rule.mainClock.advanceTimeByFrame()
        assertFalse(value(ArmSpinningKey)); assertFalse(value(BreathingKey))
        found = 5; rule.mainClock.advanceTimeBy(100)
        assertEquals(0, value(PulseCountKey)); assertFalse(value(PulseActiveKey))
        mode = RadarMode.SETTLED; rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        assertEquals(0f, value(RadarFractionKey), 0f)
    }
```
In the existing `blockHeightSlidesWithoutAStepBothWays`, change the settle midpoint sample from `rule.mainClock.advanceTimeBy(180)` (the second one, after `mode = RadarMode.SETTLED`) to `rule.mainClock.advanceTimeBy(48)`: the spring passes the ring size before 180 ms and the clamped height then reads as the ring, so the midpoint must be taken early (about 0.57 of the travel left at 64 ms).

Append to `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/home/HomeContentTest.kt` (spec 1.7, 9.2 tests 3 and 16; imports `androidx.compose.runtime.CompositionLocalProvider`, `com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion`):
```kotlin
    @Test fun quickScanAllAndForgetWaitForTheSettle() {
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.scanning().copy(networkId = 1))
        rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides false) { HomeContent(st, rec.actions, now = 121_000L, ownModel = "Pixel 7 Pro") } } }
        rule.mainClock.advanceTimeBy(1_000)
        st = HomeFakes.settled().copy(networkId = 1); rule.mainClock.advanceTimeBy(64)       // mid-settle
        rule.onNodeWithTag("homeMenu").performClick(); rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Quick scan all").assertIsNotEnabled(); rule.onNodeWithText("Forget this network").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Quick scan all").assertIsEnabled(); rule.onNodeWithText("Forget this network").assertIsEnabled()
    }
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `Unresolved reference 'BreathingKey'` (and `PulseCountKey`, `PulseActiveKey`, `RadarFractionKey`, `onBusyChange`, `onComposed`).

- [ ] **Step 2: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/RadarBlock.kt` (replace):
```kotlin
package com.northwalessolutions.netprobe.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.northwalessolutions.netprobe.ui.StopPill
import com.northwalessolutions.netprobe.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test hooks (spec 1.17), all on "radarBlock" and set from composition state, never from the per-frame angle. */
val ArmSpinningKey = SemanticsPropertyKey<Boolean>("ArmSpinning")
private var SemanticsPropertyReceiver.armSpinning by ArmSpinningKey
val RadarFractionKey = SemanticsPropertyKey<Float>("RadarFraction")
private var SemanticsPropertyReceiver.radarFraction by RadarFractionKey
val PulseCountKey = SemanticsPropertyKey<Int>("PulseCount")
private var SemanticsPropertyReceiver.pulseCount by PulseCountKey
val PulseActiveKey = SemanticsPropertyKey<Boolean>("PulseActive")
private var SemanticsPropertyReceiver.pulseActive by PulseActiveKey
val BreathingKey = SemanticsPropertyKey<Boolean>("Breathing")
private var SemanticsPropertyReceiver.breathing by BreathingKey

private const val ANNOUNCE_STEP = 5
private const val MIN_COUNT_SP = 12
private const val FILL_INNER_STOP = 0.3f
private const val FILL_OUTER_STOP = 0.7f
private const val QUICK_ARC_SWEEP = 90f
private const val PULSE_END_FRACTION = 0.95f
private const val FULL_TURN_DEG = 360f
private val RING_STROKE = 3.dp
private val DISC_STROKE = 2.dp
private val INNER_RING_STROKE = 1.dp
private val TEXT_GAP = 10.dp
/** Three static dots suggest a radar at rest in HERO, as fractions of the disc radius. */
private val HERO_DOTS = listOf(Offset(-0.45f, -0.30f), Offset(0.50f, -0.10f), Offset(0.15f, 0.55f))

/**
 * The scan control (Radar spec section 3, Phase 3A spec section 1): one composable owns HERO, SCANNING and SETTLED so the size
 * change is one continuous animation driven by a single `fraction` (0 = ring, 1 = disc). Only the ring, the HERO circle and the
 * Stop pill are actionable. [onBusyChange] is true from SCANNING until the settle ends; [onComposed] is a test hook.
 */
@Composable
fun RadarBlock(
    mode: RadarMode, found: Int, progressLine: String?, summary: RadarSummary, canScan: Boolean,
    onScan: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier,
    heroSubnet: String? = null, quickScanning: Boolean = false, onStopQuick: () -> Unit = {}, scanCompleted: Boolean = true,
    onBusyChange: (Boolean) -> Unit = {}, onComposed: () -> Unit = {},
) {
    SideEffect { onComposed() }
    val reduced = LocalReducedMotion.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val transition = updateTransition(mode, label = "radar")
    // The settle springs (spec 1.7), so fraction may dip below 0: only the ring's own size reads it unclamped.
    val fraction by transition.animateFloat(transitionSpec = {
        when {
            reduced -> snap()
            RadarMode.SCANNING isTransitioningTo RadarMode.SETTLED -> spring(dampingRatio = Motion.SETTLE_DAMPING, stiffness = Motion.SETTLE_STIFFNESS)
            else -> tween(Motion.SIZE_MS, easing = FastOutSlowInEasing)
        }
    }, label = "fraction") { if (it == RadarMode.SETTLED) 0f else 1f }
    val f = fraction.coerceIn(0f, 1f)
    val atRest = transition.currentState == transition.targetState
    val settling = transition.currentState == RadarMode.SCANNING && transition.targetState == RadarMode.SETTLED
    // Arm, blips and the angle exist while scanning or settling; the Stop pill and progress line too.
    val live = mode == RadarMode.SCANNING || settling
    val latestBusy by rememberUpdatedState(onBusyChange)
    LaunchedEffect(live) { latestBusy(live) }

    val labelAlpha by animateFloatAsState(if (mode == RadarMode.SETTLED) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "label")
    val heroAlpha by animateFloatAsState(if (mode == RadarMode.HERO) 1f else 0f, tween(if (reduced) 0 else Motion.LABEL_FADE_MS), label = "hero")
    // The arm and blips fade in with the scan and out over ARM_FADE_MS when the settle starts (spec 1.7).
    val liveAlpha by animateFloatAsState(if (mode == RadarMode.SCANNING) 1f else 0f,
        tween(if (reduced) 0 else if (mode == RadarMode.SCANNING) Motion.BLIP_IN_MS else Motion.ARM_FADE_MS), label = "live")

    // One angle for the arm, the afterglow and the breathing, hoisted here (spec 1.3). Not resumed: frozen where it stopped and
    // resumed from there, through a non-state holder written in the arm's layer block (spec 1.9, Gruff m2).
    val lastAngle = remember { FloatArray(1).also { it[0] = Motion.STATIC_ARM_DEG } }
    val turning = live && resumed && !reduced
    val angle: () -> Float = when {
        reduced -> ({ Motion.STATIC_ARM_DEG })
        turning -> {
            val start = remember { lastAngle[0] }
            val a = rememberInfiniteTransition(label = "arm")
                .animateFloat(start, start + FULL_TURN_DEG, infiniteRepeatable(tween(Motion.ARM_TURN_MS, easing = LinearEasing)), label = "angle")
            ({ a.value })
        }
        else -> ({ lastAngle[0] })
    }
    val breathingOn = turning && mode == RadarMode.SCANNING

    // Blips: one per new host, the newest batch fades in; at most 64 are drawn, the count stays exact. The find counter only rises.
    val newest = remember { Animatable(1f) }
    var shownBlips by remember { mutableIntStateOf(0) }
    var newestFrom by remember { mutableIntStateOf(0) }
    var blipMode by remember { mutableStateOf<RadarMode?>(null) }
    var lastFound by remember { mutableIntStateOf(0) }
    var finds by remember { mutableIntStateOf(0) }
    var pulseStarts by remember { mutableIntStateOf(0) }
    LaunchedEffect(found, mode) {
        val entering = mode == RadarMode.SCANNING && blipMode != RadarMode.SCANNING
        blipMode = mode
        if (mode != RadarMode.SCANNING) return@LaunchedEffect
        if (entering) { shownBlips = 0; newestFrom = 0; finds = 0; pulseStarts = 0; lastFound = found }   // a new scan: no old blips, the reset never pulses
        else { if (found > lastFound) finds++; lastFound = found }
        val n = found.coerceAtMost(RadarGeometry.MAX_BLIPS)
        if (n < shownBlips) { shownBlips = n; newestFrom = n; return@LaunchedEffect }
        if (n > shownBlips) {
            newestFrom = shownBlips; shownBlips = n
            if (reduced) newest.snapTo(1f) else { newest.snapTo(0f); newest.animateTo(1f, tween(Motion.BLIP_IN_MS)) }
        }
    }

    // Ring pulse (spec 1.5, Gruff M2): gated by the frame clock, launched from a scope no found change can cancel, eased at draw time.
    val pulse = remember { Animatable(1f) }                 // 1 = idle
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode, reduced) {
        if (mode != RadarMode.SCANNING || reduced) return@LaunchedEffect
        var lastStart: Long? = null
        var seen = finds
        snapshotFlow { finds }.collect { n ->
            val rose = n > seen
            seen = n
            if (!rose) return@collect
            val now = withFrameMillis { it }
            if (PulseGate.shouldStart(now, lastStart)) {
                lastStart = now; pulseStarts++
                scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(Motion.PULSE_MS, easing = LinearEasing)) }
            }
        }
    }
    val pulseRunning by remember { derivedStateOf { pulse.value < 1f } }

    // TalkBack: polite updates in steps of 5, one announcement when the scan finishes or stops (spec 7.3, Phase 3A 5.4).
    var announced by remember { mutableStateOf(0 to progressLine) }
    LaunchedEffect(mode) { if (mode == RadarMode.SCANNING) announced = 0 to progressLine }
    LaunchedEffect(found) { if (mode == RadarMode.SCANNING && abs(found - announced.first) >= ANNOUNCE_STEP) announced = found to progressLine }
    val view = LocalView.current
    var previous by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        @Suppress("DEPRECATION")
        if (previous == RadarMode.SCANNING && mode == RadarMode.SETTLED)
            view.announceForAccessibility(if (scanCompleted) RadarText.finished(summary.headline) else RadarText.stopped(summary.headline))
        previous = mode
    }

    val actionable = canScan || quickScanning
    // The hooks are composition state (spec 1.17): read here, never from the per-frame angle.
    val pulsesNow = pulseStarts
    val pulseActiveNow = pulseRunning
    BoxWithConstraints(modifier.fillMaxWidth().testTag("radarBlock").semantics {
        armSpinning = turning; radarFraction = fraction; pulseCount = pulsesNow; pulseActive = pulseActiveNow; breathing = breathingOn
    }) {
        val width = maxWidth
        // Unclamped: the ring's own size is the visible bounce. Everything else reads f (spec 1.7 table).
        val diameter = lerp(Dimens.ringDiameter, Dimens.discDiameter, fraction)
        val target = diameter + (Dimens.ringTarget - Dimens.ringDiameter) * (1f - fraction)
        val stroke = lerp(RING_STROKE, DISC_STROKE, fraction)
        val centreX = lerp(Dimens.gutter + Dimens.ringTarget / 2, width / 2, f)
        val areaHeight = lerp(Dimens.ringTarget, Dimens.discDiameter, f)
        // Quick scan dims the ring's base stroke to `border` so the indeterminate `signal` arc reads over it (spec 3.3, 12.9).
        val ringColor = if (actionable && !quickScanning) NetProbeColors.signal else NetProbeColors.border
        val strokeColor = lerp(ringColor, NetProbeColors.borderSubtle, f)
        val modeModifier = when (mode) {
            RadarMode.SETTLED -> Modifier.clip(CircleShape)
                .clickable(enabled = actionable && atRest, role = Role.Button, onClick = if (quickScanning) onStopQuick else onScan)   // no tap until at rest
                .semantics { contentDescription = when { quickScanning -> RadarText.STOP_QUICK; canScan -> RadarText.ringDescription(summary); else -> RadarText.RING_DISABLED } }
                .testTag("radarRing")
            RadarMode.SCANNING -> Modifier
                .semantics { contentDescription = RadarText.scanningDescription(announced.first, announced.second); liveRegion = LiveRegionMode.Polite }
                .testTag("radarDisc")
            RadarMode.HERO -> Modifier.testTag("radarDisc")
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = areaHeight)) {
                Box(Modifier.offset(x = centreX - target / 2, y = (areaHeight - target) / 2).size(target).then(modeModifier), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                        DiscBackground(f, mode == RadarMode.HERO, Modifier.matchParentSize().clearAndSetSemantics { })
                        if (live) ArmLayer(angle, { liveAlpha }, lastAngle, Modifier.matchParentSize())
                        LiveLayer(stroke, strokeColor, breathingOn, shownBlips, newestFrom, reduced, angle, { pulse.value }, { newest.value }, { liveAlpha },
                            Modifier.matchParentSize().clearAndSetSemantics { })
                        if (mode == RadarMode.SETTLED && quickScanning) QuickArc(animate = resumed && !reduced, Modifier.matchParentSize())
                        if (mode != RadarMode.HERO && labelAlpha > 0f) Text(if (quickScanning) HomeCopy.STOP else HomeCopy.SCAN, style = NetProbeText.ringLabel,
                            color = if (actionable) NetProbeColors.signal else NetProbeColors.border, modifier = Modifier.alpha(labelAlpha))
                        if (mode == RadarMode.SCANNING || (mode == RadarMode.SETTLED && labelAlpha < 1f))
                            CountColumn(found, Modifier.alpha((1f - labelAlpha) * (1f - heroAlpha)))
                        if (heroAlpha > 0f) HeroButton(canScan && mode == RadarMode.HERO, canScan, heroSubnet, onScan, Modifier.alpha(heroAlpha))
                    }
                }
                if (f < 1f) Column(Modifier.align(Alignment.CenterStart).padding(start = Dimens.gutter + Dimens.ringTarget + TEXT_GAP, end = Dimens.gutter)
                    .alpha(1f - f)) {
                    Text(summary.headline, style = NetProbeText.summary, color = NetProbeColors.text, maxLines = 1)
                    Text(if (quickScanning) HomeCopy.QUICK_SCANNING else summary.detail, style = NetProbeText.secondary, color = NetProbeColors.textSecondary)
                }
            }
            if (mode == RadarMode.HERO) Column(Modifier.fillMaxWidth().padding(top = 12.dp, start = Dimens.gutter, end = Dimens.gutter),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (canScan) HomeCopy.HERO_TITLE else HomeCopy.HERO_NO_NETWORK, style = NetProbeText.summary, color = NetProbeColors.text)
                Text(if (canScan && heroSubnet != null) HomeCopy.heroSubtitle(heroSubnet) else HomeCopy.HERO_NO_NETWORK_SUB,
                    style = NetProbeText.secondary, color = NetProbeColors.textMuted, textAlign = TextAlign.Center)
            }
            if (live) {
                // Height and alpha follow the clamped fraction, so the list below slides, never steps, and a layout height is never negative.
                Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, (placeable.height * f).roundToInt()) { placeable.place(0, 0) }
                }.graphicsLayer { alpha = f }.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StopPill(if (mode == RadarMode.SCANNING) onStop else ({}), description = RadarText.STOP_SCAN, tag = "radarStop")
                    Spacer(Modifier.height(6.dp))
                    Text(progressLine.orEmpty(), style = NetProbeText.data, color = NetProbeColors.textMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Dimens.gutter).testTag("radarProgress"))
                }
            }
        }
    }
}

/** Layer 1 (spec 1.16): fill, inner rings and HERO dots on their own layer, re-recorded only when f or the size changes. */
@Composable
private fun DiscBackground(f: Float, hero: Boolean, modifier: Modifier) {
    Spacer(modifier.graphicsLayer().drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val fill = Brush.radialGradient(0f to NetProbeColors.discCentre, FILL_INNER_STOP to NetProbeColors.discCentre,
            FILL_OUTER_STOP to NetProbeColors.background, 1f to NetProbeColors.background, center = c, radius = r.coerceAtLeast(1f))
        val ring1 = r - Dimens.discInnerRing1.toPx(); val ring2 = r - Dimens.discInnerRing2.toPx()
        val thin = Stroke(INNER_RING_STROKE.toPx())
        val dotR = Dimens.blip.toPx() / 2
        val dots = HERO_DOTS.map { c + it * r }
        onDrawBehind {
            if (f > 0f) {
                drawCircle(fill, r, c, alpha = f)
                if (ring1 > 0f) drawCircle(NetProbeColors.borderSubtle, ring1, c, alpha = f, style = thin)
                if (ring2 > 0f) drawCircle(NetProbeColors.borderSubtle, ring2, c, alpha = f, style = thin)
            }
            if (hero) dots.forEach { drawCircle(NetProbeColors.textMuted, dotR, it) }
        }
    })
}

/** Layer 2: the sweep, rotated in its layer block (a property change per frame, never a re-record); the bright edge leads (spec 1.3). */
@Composable
private fun ArmLayer(angle: () -> Float, armAlpha: () -> Float, lastAngle: FloatArray, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { }.graphicsLayer {
        val a = angle()
        lastAngle[0] = a % FULL_TURN_DEG          // a non-state holder: no recomposition; the arm resumes from here
        rotationZ = a
        alpha = armAlpha()
    }.drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val sweep = Brush.sweepGradient(*ArmTrail.stops().map { (pos, a) -> pos to NetProbeColors.signal.copy(alpha = a) }.toTypedArray(), center = c)
        // An annulus inside the outline with the centre cut out (even-odd): the count sits on the disc's plain fill.
        val outer = (r - DISC_STROKE.toPx()).coerceAtLeast(0f)
        val inner = RadarGeometry.armMask(Dimens.discCentreMask.toPx(), r)
        val annulus = Path().apply { fillType = PathFillType.EvenOdd; addOval(Rect(c, outer)); addOval(Rect(c, inner)) }
        val head = NetProbeColors.signal.copy(alpha = Motion.HEAD_LINE_ALPHA)
        val headWidth = Motion.HEAD_LINE_WIDTH.toPx()
        onDrawBehind {
            drawPath(annulus, sweep)
            drawLine(head, Offset(c.x + inner, c.y), Offset(c.x + outer, c.y), strokeWidth = headWidth, cap = StrokeCap.Round)
        }
    })
}

/**
 * Layer 3, the one live layer (spec 1.16): the breathing outline, the pulse ring and the blips with their afterglow, in that order,
 * above the arm. It has its own graphicsLayer, so its per-frame redraw re-records only this layer. Positions, angles and brushes are
 * built in the cache once per blip count or size; the draw allocates nothing and reads the angle only while breathing or blips show.
 */
@Composable
private fun LiveLayer(stroke: Dp, strokeColor: Color, breathing: Boolean, blips: Int, newestFrom: Int, reduced: Boolean,
                      angle: () -> Float, pulse: () -> Float, newestAlpha: () -> Float, blipsAlpha: () -> Float, modifier: Modifier) {
    Spacer(modifier.graphicsLayer().drawWithCache {
        val r = size.minDimension / 2
        val c = size.center
        val strokePx = stroke.toPx()
        val outline = Stroke(strokePx)
        val pulseStroke = Stroke(Motion.PULSE_STROKE.toPx())
        val mask = RadarGeometry.armMask(Dimens.discCentreMask.toPx(), r)
        val blipR = Dimens.blip.toPx() / 2; val glowR = blipR + Dimens.blipGlow.toPx()
        val offsets = List(blips) { blipOffset(it, r) }
        val points = offsets.map { c + it }
        val angles = FloatArray(blips) { blipAngleDeg(offsets[it]) }
        val glows = points.map { p -> Brush.radialGradient(listOf(NetProbeColors.signal, Color.Transparent), center = p, radius = glowR) }
        onDrawBehind {
            val b = if (breathing) breath(angle()) else 0f
            drawCircle(lerp(strokeColor, NetProbeColors.signal, b), r - strokePx / 2, c, style = outline)
            val p = pulse()
            if (p < 1f) {
                val e = LinearOutSlowInEasing.transform(p)
                drawCircle(NetProbeColors.signal, lerp(mask, r * PULSE_END_FRACTION, e), c, alpha = Motion.PULSE_ALPHA * (1f - e), style = pulseStroke)
            }
            val all = blipsAlpha()
            if (blips > 0 && all > 0f) {
                val arm = if (reduced) 0f else angle()
                for (i in 0 until blips) {
                    val g = if (reduced) 0f else afterglow(arm, angles[i])
                    val fade = all * if (i >= newestFrom) newestAlpha() else 1f
                    scale(blipGlowScale(g, reduced), pivot = points[i]) { drawCircle(glows[i], glowR, points[i], alpha = blipGlowAlpha(g, reduced) * fade) }
                    drawCircle(NetProbeColors.signal, blipR, points[i], alpha = blipCoreAlpha(g, reduced) * fade)
                }
            }
        }
    })
}

/** Quick scan all: an indeterminate teal arc on the ring; the disc never grows for it (spec 3.3). */
@Composable
private fun QuickArc(animate: Boolean, modifier: Modifier) {
    // Like the arm: the infinite transition exists only while resumed and motion is allowed (spec 3.6, D11).
    if (!animate) QuickArcLayer({ Motion.STATIC_ARM_DEG }, modifier)
    else {
        val angle = rememberInfiniteTransition(label = "quick")
            .animateFloat(0f, FULL_TURN_DEG, infiniteRepeatable(tween(Motion.ARM_TURN_MS / 2, easing = LinearEasing)), label = "quickAngle")
        QuickArcLayer({ angle.value }, modifier)
    }
}

@Composable
private fun QuickArcLayer(angle: () -> Float, modifier: Modifier) {
    Spacer(modifier.clearAndSetSemantics { }.graphicsLayer { rotationZ = angle() }.drawWithCache {
        val w = RING_STROKE.toPx()
        onDrawBehind {
            drawArc(NetProbeColors.signal, 0f, QUICK_ARC_SWEEP, false, topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
        }
    })
}

@Composable
private fun CountColumn(found: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("$found", style = NetProbeText.radarCount.copy(color = NetProbeColors.text, textAlign = TextAlign.Center), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_COUNT_SP.sp, maxFontSize = NetProbeText.radarCount.fontSize),
            modifier = Modifier.widthIn(max = Dimens.countMaxWidth).testTag("radarCount"))
        Text(HomeCopy.FOUND, style = NetProbeText.radarLabel, color = NetProbeColors.textMuted)
    }
}

/** The 84 dp filled Scan circle on the HERO disc: the only actionable part of the disc (spec 3.5). */
@Composable
private fun HeroButton(enabled: Boolean, canScan: Boolean, subnet: String?, onScan: () -> Unit, modifier: Modifier) {
    Box(modifier.size(Dimens.heroButton).clip(CircleShape).background(if (canScan) NetProbeColors.signal else NetProbeColors.border)
        .clickable(enabled = enabled, role = Role.Button, onClick = onScan)
        .semantics { contentDescription = RadarText.heroDescription(if (canScan) subnet else null) }.testTag("scanButton"),
        contentAlignment = Alignment.Center) {
        Text(HomeCopy.SCAN, style = NetProbeText.heroButton, color = if (canScan) NetProbeColors.background else NetProbeColors.textMuted)
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`: add `var radarBusy by remember { mutableStateOf(false) }` at the top of `HomeContent`; change the `HomeMenu` call to
```kotlin
                HomeMenu(quickEnabled = state.quickScanning || (!state.scanning && !radarBusy && state.totalHosts > 0 && state.env != null),
                    quickScanning = state.quickScanning, onQuick = actions.onQuickScanAll, onStopQuick = actions.onStopQuickScan, onSettings = actions.onOpenSettings,
                    forgetEnabled = state.networkId != null && !state.scanning && !state.quickScanning && !radarBusy, onForget = actions.onForget)
```
and pass `onBusyChange = { radarBusy = it }` to `RadarBlock(...)`. Quick scan all is therefore never composed with the arm (spec 1.7, 1.16, 12 row 14).

- [ ] **Step 3: Compile the Compose tests, then the task gate**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL` for both. Reviewer: confirm against the checklist that `DiscBackground`, `ArmLayer` and `LiveLayer` each have their own layer, that `angle()` and `pulse.value` are read only in the arm's layer block and the live layer's `onDrawBehind`, and that no `fraction` reader outside the ring's size uses the unclamped value.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Radar redesign: leading trail with head line, afterglow, ring pulse, breathing outline, spring settle, own layers"
```

---

### Task 17: Motion everywhere else: row stagger, screen transitions, Detail port arrival, selection fades, pill morph, notice slide, identity and edge changes, progress glide

**Files:**
- Modify: `app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt` (`ArrivalDelayKey`, `FilterPill`, `HostRowItem`, `Notice`, `NoticeArea`), `ui/home/HomeContent.kt` (`DeviceList`, notice), `ui/Controls.kt` (`ScanPill`, `PinnedSegmented`), `ui/Nav.kt` (replace), `ui/detail/DetailContent.kt` (port arrival, identity cross-fade), `ui/detail/DetailComponents.kt` (`PortRowItem` modifier, `ScanBar` glide and tag)
- Test: `app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavTransitionTest.kt`, `ui/MotionTest.kt` (compile only)

**Interfaces:**
- Consumes: Task 15 tokens and `RowStagger`, `LocalReducedMotion`, `HomeContent`, `DetailContent`, `HomeScreen`, `DetailScreen`, `SettingsScreen`.
- Produces:
```kotlin
val ArrivalDelayKey: SemanticsPropertyKey<Int>     // ui/home; stagger delay, or -1 for "does not animate"; on Home rows and Detail port rows
data class Notice(val text: String, val undo: Boolean)
@Composable fun NoticeArea(notice: Notice?, onDismiss: () -> Unit, onUndo: () -> Unit, onDismissUndo: () -> Unit)   // tag noticeArea
@Composable fun NetProbeNavHost(nav: NavHostController, home: @Composable () -> Unit, detail: @Composable (Long) -> Unit, settings: @Composable () -> Unit)
@Composable fun PortRowItem(ip: String, p: PortRow, onAction: (DeviceAction) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier)
// tags: chip:<label> (filter chips), segment:<label> (status and profile segments), profileSegments
```

- [ ] **Step 1: Write the Compose tests (compile only, run in Task 18)**

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/NavTransitionTest.kt` (spec 9.2 test 7):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake slot screens, so no test runs a real discovery (spec 1.9). */
@RunWith(AndroidJUnit4::class)
class NavTransitionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController
    private fun show(reduced: Boolean) = rule.setContent { NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) {
        nav = rememberNavController()
        NetProbeNavHost(nav, home = { Box(Modifier.fillMaxSize().testTag("fakeHome")) }, detail = { Box(Modifier.fillMaxSize().testTag("fakeDetail")) },
            settings = { Box(Modifier.fillMaxSize().testTag("fakeSettings")) })
    } } }

    @Test fun homeToDetailSlidesAndFadesBothWays() {
        show(reduced = false); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("host/1") }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.runOnUiThread { nav.popBackStack() }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag("fakeHome").assertExists(); rule.onNodeWithTag("fakeDetail").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithTag("fakeDetail").assertDoesNotExist(); rule.onNodeWithTag("fakeHome").assertExists()
    }
    @Test fun reducedMotionShowsOnlyTheDestination() {
        show(reduced = true); rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate("settings") }
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fakeHome").assertDoesNotExist(); rule.onNodeWithTag("fakeSettings").assertExists()
    }
}
```

`app/src/androidTest/java/com/northwalessolutions/netprobe/ui/MotionTest.kt` (spec 9.2 tests 6, 8, 9, 10, 11, 12; every test sets `autoAdvance = false`):
```kotlin
package com.northwalessolutions.netprobe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.detail.DetailActions
import com.northwalessolutions.netprobe.ui.detail.DetailContent
import com.northwalessolutions.netprobe.ui.detail.DetailFakes
import com.northwalessolutions.netprobe.ui.home.*
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.NetProbeColors
import com.northwalessolutions.netprobe.ui.theme.NetProbeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionTest {
    @get:Rule val rule = createComposeRule()
    private val phone = DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 820.dp))
    private val channels = listOf<(Color) -> Float>({ it.red }, { it.green }, { it.blue })

    private fun motion(reduced: Boolean = false, content: @Composable () -> Unit) = rule.setContent {
        NetProbeTheme { CompositionLocalProvider(LocalReducedMotion provides reduced) { DeviceConfigurationOverride(phone) { content() } } }
    }
    @Composable private fun home(st: HomeUiState) = HomeContent(st, RecordingHomeActions().actions, now = 121_000L, ownModel = "Pixel 7 Pro")
    private fun delayOf(matcher: SemanticsMatcher): Int =
        rule.onNode(matcher and SemanticsMatcher.keyIsDefined(ArrivalDelayKey), useUnmergedTree = true).fetchSemanticsNode().config[ArrivalDelayKey]
    private fun rowDelays(): List<Int> =
        rule.onAllNodes(hasTestTag("hostRow") and SemanticsMatcher.keyIsDefined(ArrivalDelayKey), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[ArrivalDelayKey] }
    /** A pixel on the vertical centre line of [tag], [x] from its left edge (the centre when null). */
    private fun pixel(tag: String, x: Dp? = null): Color {
        val img = rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val px = x?.let { with(rule.density) { it.roundToPx() } } ?: (img.width / 2)
        return img[px, img.height / 2]
    }
    private fun assertBetween(a: Color, b: Color, c: Color, what: String) {
        var differing = 0
        for (ch in channels) {
            val lo = minOf(ch(a), ch(b)); val hi = maxOf(ch(a), ch(b))
            if (hi - lo < CHANNEL_GAP) continue
            differing++
            assertTrue("$what: ${ch(c)} not strictly between $lo and $hi", ch(c) > lo + EPS && ch(c) < hi - EPS)
        }
        assertTrue("$what: the two ends are the same colour", differing > 0)
    }
    private fun assertClose(expected: Color, actual: Color, what: String) { for (ch in channels) assertEquals(what, ch(expected), ch(actual), CLOSE) }

    @Test fun homeRowsLandingInOneEmissionAreStaggered() {   // test 6
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.scanning(rows = HomeFakes.rows(2)))
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)                                   // the scan-start baseline is taken
        st = HomeFakes.scanning(rows = HomeFakes.rows(5)); rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(-1, -1, 0, 40, 80), rowDelays())
        st = HomeFakes.settled(rows = HomeFakes.rows(5)); rule.mainClock.advanceTimeBy(1_000)
        st = HomeFakes.settled(rows = HomeFakes.rows(5), filter = StatusFilter.UNMARKED); rule.mainClock.advanceTimeBy(100)
        assertTrue("a filter change animated: ${rowDelays()}", rowDelays().all { it == -1 })
    }
    @Test fun aNewPortSlidesTheRowsBelowItIntoPlace() {   // test 8
        rule.mainClock.autoAdvance = false
        val p80 = DetailFakes.port(80, "http"); val p8123 = DetailFakes.port(8123, "http"); val p8443 = DetailFakes.port(8443, "https")
        var st by mutableStateOf(DetailFakes.state(listOf(p80, p8443), scanning = true))
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        fun top(port: Int) = rule.onNodeWithText("$port").getBoundsInRoot().top
        val before = top(8443)
        st = st.copy(ports = listOf(p80, p8123, p8443)); rule.mainClock.advanceTimeByFrame()
        assertEquals(0, delayOf(hasAnyDescendant(hasText("8123")))); assertEquals(-1, delayOf(hasAnyDescendant(hasText("80"))))
        assertEquals(-1, delayOf(hasAnyDescendant(hasText("8443"))))
        val tops = (1..15).map { rule.mainClock.advanceTimeBy(16); top(8443) }
        assertTrue("8443 did not move down", tops.last() > before + 1.dp)
        assertTrue("8443 jumped in one frame", tops.first() < tops.last() - 1.dp)
        tops.zipWithNext().forEach { (a, b) -> assertTrue("8443 moved back up", b >= a - 0.5.dp) }
        st = st.copy(scanning = false); rule.mainClock.advanceTimeBy(300)
        st = st.copy(ports = st.ports + DetailFakes.port(9000, "http")); rule.mainClock.advanceTimeByFrame()
        assertEquals(-1, delayOf(hasAnyDescendant(hasText("9000"))))
    }
    @Test fun aSelectedChipCrossFadesItsFill() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)
        assertClose(NetProbeColors.background, pixel("chip:Interesting", FILL_X), "chip before")
        st = st.copy(filter = StatusFilter.INTERESTING); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80); assertBetween(NetProbeColors.background, NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "chip at 80 ms")
        rule.mainClock.advanceTimeBy(96); assertClose(NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "chip at 160 ms")
    }
    @Test fun statusAndProfileSegmentsCrossFade() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(host = DetailFakes.host(status = HostStatus.INTERESTING), profile = ScanProfile.Full); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        assertBetween(NetProbeColors.background, NetProbeColors.mark, pixel("segment:Interesting", FILL_X), "status segment at 80 ms")
        assertBetween(NetProbeColors.background, NetProbeColors.surface, pixel("segment:Full", FILL_X), "profile segment at 80 ms")
        rule.mainClock.advanceTimeBy(96)
        assertClose(NetProbeColors.mark, pixel("segment:Interesting", FILL_X), "status segment at 160 ms")
        assertClose(NetProbeColors.surface, pixel("segment:Full", FILL_X), "profile segment at 160 ms")
    }
    @Test fun reducedMotionSelectionIsInstant() {   // test 9
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion(reduced = true) { home(st) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(filter = StatusFilter.INTERESTING); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        assertClose(NetProbeColors.surface, pixel("chip:Interesting", FILL_X), "reduced motion chip")
    }
    @Test fun scanPillMorphsWithoutMovingTheProfileControl() {   // test 10
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        val nodeId = rule.onNodeWithTag("detailScanButton").fetchSemanticsNode().id
        fun profile() = rule.onNodeWithTag("profileSegments").getBoundsInRoot()
        val at0 = profile()
        st = st.copy(scanning = true); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(90); assertEquals("moved at 90 ms", at0, profile())
        rule.mainClock.advanceTimeBy(20)
        assertBetween(NetProbeColors.signal, NetProbeColors.background, pixel("detailScanButton"), "pill centre at 110 ms")
        rule.mainClock.advanceTimeBy(110); assertEquals("moved at 220 ms", at0, profile())
        assertEquals("the pill is one node throughout", nodeId, rule.onNodeWithTag("detailScanButton").fetchSemanticsNode().id)
    }
    @Test fun noticeSlidesInAndOutWithItsOldWords() {   // test 11
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(HomeFakes.settled())
        motion { home(st) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(message = "Scan failed: socket closed"); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)
        val mid = rule.onNodeWithTag("noticeArea").getBoundsInRoot().height
        rule.mainClock.advanceTimeBy(150)
        val full = rule.onNodeWithTag("noticeArea").getBoundsInRoot().height
        assertTrue("mid $mid, full $full", mid > 0.dp && mid < full)
        st = st.copy(message = null); rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithText("Scan failed: socket closed").assertExists()   // the exit shows the old words, not a blank line
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("noticeArea").assertDoesNotExist()
    }
    @Test fun identityLineCrossFades() {   // test 12
        rule.mainClock.autoAdvance = false
        var st by mutableStateOf(DetailFakes.state())
        motion { DetailContent(st, DetailActions()) }
        rule.mainClock.advanceTimeBy(100)
        st = st.copy(host = DetailFakes.host().copy(identModel = "DS920+")); rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithText("Synology DS220+  NAS").assertExists(); rule.onNodeWithText("Synology DS920+  NAS").assertExists()
        rule.mainClock.advanceTimeBy(140)
        rule.onNodeWithText("Synology DS220+  NAS").assertDoesNotExist(); rule.onNodeWithText("Synology DS920+  NAS").assertExists()
    }

    private companion object {
        val FILL_X = 8.dp                 // inside the fill, clear of the 1 dp outline and the centred label
        const val CHANNEL_GAP = 0.02f
        const val EPS = 0.004f
        const val CLOSE = 0.02f
    }
}
```

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
```
Expected: `BUILD FAILED`, `Unresolved reference 'NetProbeNavHost'` and `'ArrivalDelayKey'`.

- [ ] **Step 2: Implement**

`app/src/main/java/com/northwalessolutions/netprobe/ui/Nav.kt` (replace):
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
import com.northwalessolutions.netprobe.ui.settings.SettingsScreen
import com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion
import com.northwalessolutions.netprobe.ui.theme.Motion

@Composable
fun NetProbeNav(container: AppContainer) {
    val keepOn by container.extendedRunActive.collectAsStateWithLifecycle()
    KeepScreenOn(keepOn)
    val nav = rememberNavController()
    NetProbeNavHost(nav,
        home = { HomeScreen(container, onOpenHost = { nav.navigate("host/$it") }, onOpenSettings = { nav.navigate("settings") }) },
        detail = { id -> DetailScreen(container, id, onBack = { nav.popBackStack() }) },
        settings = { SettingsScreen(container, onBack = { nav.popBackStack() }) })
}

/**
 * The routes with their transitions (spec 1.9): the entering screen slides in from a quarter of the width and fades in, the leaving
 * one slides a tenth and fades out, 240 ms FastOutSlowIn; none under reduced motion. Stateless over its three destinations, so the
 * Compose test passes fakes and no test runs a real discovery.
 */
@Composable
fun NetProbeNavHost(nav: NavHostController, home: @Composable () -> Unit, detail: @Composable (Long) -> Unit, settings: @Composable () -> Unit) {
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
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/Controls.kt` (imports `androidx.compose.animation.animateColorAsState`, `androidx.compose.animation.core.*`, `androidx.compose.runtime.*`, `androidx.compose.ui.graphics.graphicsLayer`, `androidx.compose.ui.platform.testTag` already present, `com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion`, `com.northwalessolutions.netprobe.ui.theme.Motion`):
- Replace `ScanPill` with:
```kotlin
/**
 * Detail Scan pill (spec 5.1 item 8): teal fill "Scan", or an outlined "Stop" while scanning. Phase 3A 1.12: the fill fades out as
 * the outline fades in and the label colour swaps, 220 ms, with a scaleX squash through a layer; the layout width stays max(Scan, Stop)
 * so the profile control never shifts, and the pill stays one node.
 */
@Composable
fun ScanPill(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val active = enabled || scanning
    val floatSpec: AnimationSpec<Float> = if (reduced) snap() else tween(Motion.PILL_MORPH_MS, easing = FastOutSlowInEasing)
    val colorSpec: AnimationSpec<Color> = if (reduced) snap() else tween(Motion.PILL_MORPH_MS, easing = FastOutSlowInEasing)
    val fill by animateFloatAsState(if (scanning) 0f else 1f, floatSpec, label = "pillFill")
    val outline by animateFloatAsState(if (scanning) 1f else 0f, floatSpec, label = "pillOutline")
    val labelColor by animateColorAsState(if (scanning) NetProbeColors.signal else NetProbeColors.background, colorSpec, label = "pillLabel")
    val squash = remember { Animatable(1f) }
    val composedOnce = remember { booleanArrayOf(false) }
    LaunchedEffect(scanning) {
        if (!composedOnce[0]) { composedOnce[0] = true; return@LaunchedEffect }   // the first composition is not a morph
        if (reduced) return@LaunchedEffect
        squash.snapTo(1f)
        squash.animateTo(1f, keyframes {
            durationMillis = Motion.PILL_MORPH_MS
            1f at 0; Motion.PILL_SQUASH at Motion.PILL_SQUASH_AT_MS; 1f at Motion.PILL_MORPH_MS
        })
    }
    Box(modifier.heightIn(min = Dimens.touch).clip(CircleShape).clickable(enabled = active, role = Role.Button, onClick = onClick)
        .alpha(if (active) 1f else DISABLED_ALPHA).testTag("detailScanButton"), contentAlignment = Alignment.Center) {
        // Fill and outline alphas are read in the draw phase only; the squash in the layer block.
        val pill = Modifier.heightIn(min = CONTROL_VISUAL).graphicsLayer { scaleX = squash.value }.drawBehind {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(NetProbeColors.signal, cornerRadius = radius, alpha = fill)
            val w = PILL_STROKE.toPx()
            drawRoundRect(NetProbeColors.signal, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((size.height - w) / 2), style = Stroke(w), alpha = outline)
        }.padding(horizontal = 18.dp)
        // Both labels are laid out and the unused one is invisible, so the pill is max(Scan, Stop) wide at any font scale.
        Box(pill, contentAlignment = Alignment.Center) {
            PillLabel("Scan", labelColor, visible = !scanning)
            PillLabel("Stop", labelColor, visible = scanning)
        }
    }
}
```
- In `PinnedSegmented`, compute once before the loop `val reduced = LocalReducedMotion.current; val colorSpec: AnimationSpec<Color> = if (reduced) snap() else tween(Motion.SELECT_MS, easing = FastOutSlowInEasing)`, and inside `labels.forEachIndexed` replace the segment with:
```kotlin
            val fill by animateColorAsState(if (active) activeFill else activeFill.copy(alpha = 0f), colorSpec, label = "segmentFill")
            val textColour by animateColorAsState(if (active) activeText else NetProbeColors.textSecondary, colorSpec, label = "segmentText")
            Box(Modifier.weight(1f).heightIn(min = Dimens.touch).selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                .padding(vertical = CONTROL_INSET).testTag("segment:$label"), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().heightIn(min = CONTROL_VISUAL).clip(shape).background(fill)
                    .padding(horizontal = SEGMENT_TEXT_PAD), contentAlignment = Alignment.Center) {
                    // The weight change is not animated; it switches at the start (spec 1.11).
                    Text(label, style = NetProbeText.chip.copy(fontWeight = if (active) activeWeight else FontWeight.W400), color = textColour, textAlign = TextAlign.Center)
                }
            }
```
  (Only alpha animates on the fill, so the composite always lies between the two ends.)

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeComponents.kt` (imports `androidx.compose.animation.*`, `androidx.compose.animation.core.*`):
- Add next to the other test hooks:
```kotlin
/** Test hook (spec 1.17): a row's stagger delay in ms, or -1 when it does not animate. Home rows and Detail port rows. */
val ArrivalDelayKey = SemanticsPropertyKey<Int>("ArrivalDelay")
var SemanticsPropertyReceiver.arrivalDelay by ArrivalDelayKey
```
- Replace `FilterPill` with:
```kotlin
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val spec: AnimationSpec<Color> = if (reduced) snap() else tween(Motion.SELECT_MS, easing = FastOutSlowInEasing)
    // Only alpha animates on the fill, so mid-fade pixels lie between the background and the selected surface (spec 1.11).
    val fill by animateColorAsState(if (selected) NetProbeColors.surface else NetProbeColors.surface.copy(alpha = 0f), spec, label = "chipFill")
    val outline by animateColorAsState(if (selected) NetProbeColors.surface else NetProbeColors.border, spec, label = "chipOutline")
    val text by animateColorAsState(if (selected) NetProbeColors.text else NetProbeColors.textSecondary, spec, label = "chipText")
    Box(Modifier.heightIn(min = Dimens.touch).selectable(selected, onClick = onClick, role = Role.Tab)
        .semantics { contentDescription = "Filter: $label" + if (selected) ", selected" else "" }.testTag("chip:$label"), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = CHIP_HEIGHT).clip(CircleShape).background(fill).border(1.dp, outline, CircleShape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = NetProbeText.chip, color = text)
        }
    }
}
```
- In `HostRowItem`: add `val reduced = LocalReducedMotion.current`; rename `edge` to `edgeTarget` and add `val edge by animateColorAsState(edgeTarget, if (reduced) snap() else tween(Motion.EDGE_COLOUR_MS), label = "edge")`; publish `edgeColor = edgeTarget` in the semantics; pass `edge` to `TypeEdge`; and replace the data-line `Text` with:
```kotlin
            // Cross-fades when an identity improves (spec 1.14); Crossfade does not animate on first composition, so a scrolled-in row never fades.
            Crossfade(RowText.dataLine(r, if (own && r.identityLine == RowRules.OWN_LINE) ownModel else null),
                animationSpec = if (reduced) snap() else tween(Motion.IDENTITY_FADE_MS), label = "rowData") { line ->
                Text(line, style = NetProbeText.data, color = NetProbeColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.drawnAlpha(1f).testTag("rowData"))
            }
```
- Add after `NoticeLine`:
```kotlin
data class Notice(val text: String, val undo: Boolean)

/**
 * The notice line expands and fades in over 200 ms and shrinks and fades out over 160 ms (spec 1.13); the last notice is latched so
 * the exit animates the old words, not a blank line. A late notice therefore slides the list down instead of jumping it.
 */
@Composable
fun NoticeArea(notice: Notice?, onDismiss: () -> Unit, onUndo: () -> Unit, onDismissUndo: () -> Unit) {
    val reduced = LocalReducedMotion.current
    var last by remember { mutableStateOf(notice) }
    LaunchedEffect(notice) { if (notice != null) last = notice }
    val shown = notice ?: last
    AnimatedVisibility(visible = notice != null, modifier = Modifier.testTag("noticeArea"),
        enter = if (reduced) EnterTransition.None else expandVertically(tween(Motion.NOTICE_IN_MS)) + fadeIn(tween(Motion.NOTICE_IN_MS)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(Motion.NOTICE_OUT_MS)) + fadeOut(tween(Motion.NOTICE_OUT_MS))) {
        shown?.let { n -> if (n.undo) NoticeLine(n.text, onDismissUndo, onUndo = onUndo) else NoticeLine(n.text, onDismiss) }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/home/HomeContent.kt`:
- Replace the Task 13 `when { state.undoText != null -> ...; state.message != null -> ... }` with:
```kotlin
            val notice = when {
                state.undoText != null -> Notice(state.undoText, undo = true)
                state.message != null -> Notice(state.message, undo = false)
                else -> null
            }
            NoticeArea(notice, actions.onDismissMessage, actions.onUndo, actions.onDismissUndo)
```
- Replace `DeviceList` with (import `kotlinx.coroutines.delay`, `androidx.compose.ui.semantics.semantics`):
```kotlin
@Composable
private fun DeviceList(groups: List<HostGroup>, state: HomeUiState, actions: HomeActions, ownModel: String) {
    val reduced = LocalReducedMotion.current
    // Rows present when the scan began never animate; nor do initial load, filter changes or returning to the screen (spec 3.6).
    var baseline by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(state.scanning) { baseline = if (state.scanning) state.rows.map { it.id }.toSet() else null }
    // Rows landing in the same emission start 40 ms apart, capped at 200 ms (spec 1.8); a row keeps its delay for the scan.
    val known = remember { HashMap<Long, Int>() }
    val delays: Map<Long, Int> = remember(state.rows, baseline, reduced) {
        val b = baseline
        if (b == null || reduced) { known.clear(); emptyMap() }
        else { known.putAll(RowStagger.delays(state.rows.map { it.id }.filter { it !in b && it !in known })); HashMap(known) }
    }
    val risePx = with(LocalDensity.current) { Motion.ROW_RISE.toPx() }
    LazyColumn(Modifier.fillMaxSize().testTag("deviceList")) {
        groups.forEach { g ->
            stickyHeader(key = "header:${g.subnet}:${g.local}") { GroupHeader(GroupHeaders.text(g.subnet, g.local)) }
            items(g.rows, key = { it.id }) { r ->
                val wait = delays[r.id]
                val rise = remember(r.id) { Animatable(if (wait != null) 1f else 0f) }
                LaunchedEffect(r.id) { if (rise.value > 0f) { delay((wait ?: 0).toLong()); rise.animateTo(0f, tween(Motion.ROW_ARRIVAL_MS, easing = Motion.rowEasing)) } }
                val itemModifier = if (wait != null) Modifier.animateItem(fadeInSpec = tween(Motion.ROW_ARRIVAL_MS, delayMillis = wait, easing = Motion.rowEasing),
                    placementSpec = null, fadeOutSpec = null) else Modifier
                HostRowItem(r, own = RowRules.isOwn(r.ip, state.env), ownModel = ownModel, onClick = { actions.onOpenHost(r.id) },
                    modifier = itemModifier.graphicsLayer { translationY = rise.value * risePx }.semantics { arrivalDelay = wait ?: -1 })
            }
        }
    }
}
```

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailComponents.kt`:
- Give `PortRowItem` a last parameter `modifier: Modifier = Modifier` and wrap its `Row` and `HorizontalDivider` in `Column(modifier) { ... }`, so an arriving row's divider moves with it.
- In `ScanBar`: add `val reduced = LocalReducedMotion.current`; tag the profile control `modifier = Modifier.weight(1f).testTag("profileSegments")`; and glide the line (spec 1.14):
```kotlin
        val target = detailFraction(progress, identified)
        val shown by animateFloatAsState(target, if (reduced) snap() else tween(Motion.PROGRESS_MS, easing = LinearEasing), label = "progress")
```
  with `LinearProgressIndicator(progress = { shown }, ...)` (read in the indicator's draw, not in the bar's composition).

`app/src/main/java/com/northwalessolutions/netprobe/ui/detail/DetailContent.kt` (imports `androidx.compose.animation.Crossfade`, `androidx.compose.animation.core.*`, `androidx.compose.ui.graphics.graphicsLayer`, `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.ui.semantics.semantics`, `com.northwalessolutions.netprobe.ui.home.arrivalDelay`, `com.northwalessolutions.netprobe.ui.theme.LocalReducedMotion`, `com.northwalessolutions.netprobe.ui.theme.Motion`):
- Add `val reduced = LocalReducedMotion.current` at the top of `DetailContent`.
- Replace the identity line with:
```kotlin
                    identityWithType(IdentityLine.of(h), Effective.type(h))?.let { line ->
                        Crossfade(line, animationSpec = if (reduced) snap() else tween(Motion.IDENTITY_FADE_MS), label = "identity") {
                            IdentityText(it, state.displayName) { editing = true }
                        }
                    }
```
- Before the `Scaffold`, add:
```kotlin
    // Detail port rows arrive as found (spec 1.10): the set listed when the scan starts never fades or rises.
    var portBaseline by remember { mutableStateOf<Set<Int>?>(null) }
    LaunchedEffect(state.scanning) { portBaseline = if (state.scanning) state.ports.map { it.port }.toSet() else null }
    val risePx = with(LocalDensity.current) { Motion.ROW_RISE.toPx() }
```
- Replace the `items(state.ports, ...)` line with:
```kotlin
            items(state.ports, key = { it.port }) { p ->
                val base = portBaseline
                val arriving = !reduced && base != null && p.port !in base
                val rise = remember(p.port) { Animatable(if (arriving) 1f else 0f) }
                LaunchedEffect(p.port) { if (rise.value > 0f) rise.animateTo(0f, tween(Motion.PORT_ROW_MS, easing = FastOutSlowInEasing)) }
                // While a scan runs every row gets the placement spec, so rows below a new port slide down instead of jumping
                // (spec 9.2 test 8); only the new row fades and rises.
                val itemModifier = if (reduced || base == null) Modifier else Modifier.animateItem(
                    fadeInSpec = if (arriving) tween(Motion.PORT_ROW_MS) else null,
                    placementSpec = tween(Motion.PORT_ROW_MS, easing = FastOutSlowInEasing), fadeOutSpec = null)
                PortRowItem(ip, p, onAction = actions.onAction, onLongPress = { sheetPort = p.port },
                    modifier = itemModifier.graphicsLayer { translationY = rise.value * risePx }.semantics { arrivalDelay = if (arriving) 0 else -1 })
            }
```

- [ ] **Step 3: Compile the Compose tests, then the task gate**

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -8
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL` for both. Reviewer: every new animation has a `snap()` / `None` branch under `LocalReducedMotion`; the pill's fill and outline, the progress line and the arrival rise are read in draw or layer blocks only.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Motion elsewhere: row stagger, screen transitions, port arrival, selection fades, pill morph, notice slide, identity and edge fades"
```

---

### Task 18: Deferred on-device QA (executed later, when the Director returns with the Pixel)

This task is NOT executed during the plan. Tasks 1 to 17 end with JVM tests green and every instrumented test compiled; no task has device evidence. When the Director is back with the Pixel 7 Pro (`28221FDH300AP9`) on his LAN, Bugsy runs every item below in order and records each result, pass or fail, in `docs/qa/phase3a-on-device.md`. Per the standing QA rule, Bugsy builds, installs and runs every item; a green suite is not a substitute, and code review is not QA. If any step fails, stop, record it, and tell the Director; never fix anything on his live database.

**Files:**
- Create (at execution time): `docs/qa/phase3a-on-device.md`, `docs/qa/shots/phase3a/*.png`, `docs/qa/shots/phase3a/*.mp4`
- Backups go OUTSIDE the repository (they hold the Director's network inventory): `K:/claude/netprobe_db_backup/phase3a/`

Common setup:
```bash
ADB="/c/Users/blastdav/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV=28221FDH300AP9
BK=/k/claude/netprobe_db_backup/phase3a
mkdir -p "$BK" docs/qa/shots/phase3a
run() { ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$1" --console=plain 2>&1 | tail -12; }
```
If `leaveApksInstalledAfterRun` is rejected by this AGP version, stop and ask the Director before running any instrumented test without it.

- [ ] **Step 0: Back up the live database, before anything is installed or any instrumented test runs**

The installed build is still schema 1. Force-stop the app first so nothing writes during the copy:
```bash
"$ADB" -s $DEV shell am force-stop com.northwalessolutions.netprobe
for f in netprobe.db netprobe.db-wal netprobe.db-shm; do
  "$ADB" -s $DEV exec-out run-as com.northwalessolutions.netprobe cat databases/$f > "$BK/$f.bak"
done
ls -l "$BK"
```
Expected: three files; `netprobe.db.bak` non-empty (the `-wal` and `-shm` files may be empty if the last session checkpointed; record their sizes). If `netprobe.db.bak` is empty, stop.

- [ ] **Step 1: Push the copy for DirectorCopyMigrationTest**
```bash
"$ADB" -s $DEV push "$BK/netprobe.db.bak" /data/local/tmp/netprobe-migration-copy.db
"$ADB" -s $DEV push "$BK/netprobe.db-wal.bak" /data/local/tmp/netprobe-migration-copy.db-wal
"$ADB" -s $DEV push "$BK/netprobe.db-shm.bak" /data/local/tmp/netprobe-migration-copy.db-shm
for s in "" -wal -shm; do
  "$ADB" -s $DEV shell run-as com.northwalessolutions.netprobe cp /data/local/tmp/netprobe-migration-copy.db$s databases/netprobe-migration-copy.db$s
done
"$ADB" -s $DEV shell run-as com.northwalessolutions.netprobe ls -l databases/
```
Expected: `netprobe.db*` untouched and `netprobe-migration-copy.db*` present.

- [ ] **Step 2: The migration tests first (they install the new APKs but never open `netprobe.db`)**
```bash
run com.northwalessolutions.netprobe.data.MigrationTest
run com.northwalessolutions.netprobe.data.DirectorCopyMigrationTest
"$ADB" -s $DEV shell run-as com.northwalessolutions.netprobe ls -l databases/
```
Expected: both pass (DirectorCopyMigrationTest is not skipped: it reports 1 test, not an assumption failure); `netprobe.db` has the same size and time as before; the copy is gone (the test deletes it). If either fails, stop: do not open the app.

- [ ] **Step 3: In-place migration of the real database (install over, never clean)**
```bash
./gradlew :app:installDebug --console=plain 2>&1 | tail -3
"$ADB" -s $DEV shell am start -W -n com.northwalessolutions.netprobe/.MainActivity
"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3a/q00-after-migration.png
"$ADB" -s $DEV logcat -d | grep -E "AndroidRuntime|FATAL|IllegalStateException: Migration|Room" | tail -10
```
Check on screen: every host the Director marked shows its mark; open two marked hosts and confirm their notes and custom names. Record pass or fail. If anything is missing, stop and restore from `$BK` with the Director.

- [ ] **Step 4: The rest of the instrumented suite** (DAO and Compose tests use in-memory databases or fake state):
```bash
for c in data.DaoTest data.RemoveDeviceTest ui.home.RadarBlockTest ui.home.HomeContentTest ui.home.RadarStateTest ui.detail.DetailContentTest \
         ui.NavTransitionTest ui.MotionTest ui.settings.SettingsRowsTest ui.settings.ExtendedSectionTest ui.theme.TypographyTest ui.KeepScreenOnTest; do
  run com.northwalessolutions.netprobe.$c
done
run com.northwalessolutions.netprobe.ui.HomeScreenTest   # last: it launches the real activity, which opens netprobe.db as the app does
```
Expected: every class passes. Record each class's count. The radar, motion and stagger tests are timing tests on a real frame clock: a failure is recorded with its message, not retried into a pass and not relaxed.

- [ ] **Step 5: Screen recordings** (10 to 20 s each; motion on unless stated):
```bash
rec() { "$ADB" -s $DEV shell screenrecord --time-limit "$2" /sdcard/$1.mp4; "$ADB" -s $DEV pull /sdcard/$1.mp4 docs/qa/shots/phase3a/$1.mp4; "$ADB" -s $DEV shell rm /sdcard/$1.mp4; }
```
1. `rec r1-radar-scan 20` while starting a full radar scan from SETTLED: expand, the sweep with blips lighting as the arm passes, pulses as devices land, breathing outline, spring settle.
2. Turn on Settings > Accessibility > Remove animations, then `rec r2-radar-reduced 15` during a scan: static arm, instant changes. Turn it off again.
3. `rec r3-navigation 15`: Home to Detail and back, Home to Settings and back.
4. `rec r4-detail-full 20`: a Detail Full scan on the NAS or Home Assistant host: port rows arriving and sliding into order, the pill morph, the progress line gliding, the identity line changing.
5. `rec r5-selection 10`: filter chips, status segments and profile segments.

- [ ] **Step 6: Screenshots** (`"$ADB" -s $DEV exec-out screencap -p > docs/qa/shots/phase3a/<name>.png`, at font scale 1.0 and again at 1.3 via `"$ADB" -s $DEV shell settings put system font_scale 1.3`, restored to 1.0 afterwards):
6. `s06-mid-sweep` at about 12 devices (arm head, trail and lit blips visible).
7. `s07-port-sheet` on a non-standard HTTP port showing "Open with..." and the Try as chips; `s07b-chooser` after a chip tap.
8. `s08-why` "Why this identity" for a host identified from port evidence (port lines visible).
9. `s09-edit` the Edit device dialog with two overrides set; `s09b-row` and `s09c-detail` the Home row and Detail identity line showing them.
10. `s10-undo-remove` the Undo notice after "Remove device"; `s10b-undo-forget` after "Forget this network" (then Undo it).
11. `s11-stopped` "Stopped after N s" after Stop.
12. `s12-restart-stale` Home after `am force-stop` and relaunch, stale rows still dimmed.

- [ ] **Step 7: Behaviour checks, each recorded pass or fail**
- The non-standard HTTP port the Director saw shows "Open" and opens in the browser.
- Try as ssh on port 22 offers his SSH app.
- After a Full scan, the Synology, Home Assistant, Hue, UniFi and Hub Manager hosts each gain or keep the right identity (record the identity line and the "Why this identity" lines).
- An identity never drops after a Quick scan, or after the next radar scan (Review Focus 3).
- Remove device on a marked host, then Undo: mark and note restored exactly; Remove during a running radar scan: the host does not come back before the scan ends (Review Focus 4).
- Forget this network, then Undo: every host, mark, note and manual range back; Forget, then dismiss: a fresh empty network row.
- A Full scan of a host with 9100 open prints nothing (check the printer), and the scan ends within the budget (Review Focus 1 and 2).
- Janky frames under 5 percent during a radar scan:
```bash
"$ADB" -s $DEV shell dumpsys gfxinfo com.northwalessolutions.netprobe reset
# start a radar scan on the LAN and wait for it to settle
"$ADB" -s $DEV shell dumpsys gfxinfo com.northwalessolutions.netprobe | grep -E "Total frames rendered|Janky frames"
```
- The database migrated in place on the installed build with marks and notes intact (step 3).

- [ ] **Step 8: Record and hand over**

Write `docs/qa/phase3a-on-device.md`: the date, build commit, every step's result, the test counts per class, the screenshot and recording names, the gfxinfo numbers, and the spec section 10 assumptions (A1 to A10) and section 11 open items for the Director to rule on. Then commit:
```bash
git add docs/qa/phase3a-on-device.md docs/qa/shots/phase3a && git commit -m "Phase 3A on-device QA record"
```
The backups in `K:/claude/netprobe_db_backup/phase3a/` are never committed.

---

## Plan Self-Review Notes

- **Spec coverage, by section:**
  - Decision table: D1 Tasks 15, 16; D2 Task 15 (`ARM_TURN_MS = 3000`); D3 Task 17; D4 Task 17 (identity cross-fade, edge colour, progress glide); D5 Tasks 15 to 17 (Compose APIs only, `LocalReducedMotion` everywhere); D6 Tasks 5 and 11; D7 Task 5; D8 Task 11; D9 Task 11 (`sheetLabel` still drops the no-app row; `tryAs` filtered by handler); D10 Tasks 3, 4, 6; D11 Tasks 2 and 8; D12 Task 7; D13 Task 6 (reason form) and Task 8; D14 Task 10; D15 Tasks 9 and 10; D16 Task 1; D17 Task 12; D18 unchanged (no task touches `RangeSummary`); D19 Tasks 13 and 14; D20 Task 12; D21 Task 12.
  - Section 1 motion: 1.1 diagnosis fixed by 1.3 in Task 16; 1.2 tokens Task 15 (`MotionRulesTest.tokens match spec 1 2`); 1.3 sweep Tasks 15 (`ArmTrail`) and 16 (head line, hoisted angle); 1.4 afterglow Tasks 15 and 16; 1.5 pulse Tasks 15 (`PulseGate`) and 16; 1.6 breathing Tasks 15 and 16; 1.7 spring settle, clamping table, `atRest` click, `onBusyChange` Task 16; 1.8 stagger Tasks 15 and 17; 1.9 transitions and the frozen-angle resume Tasks 16 (resume) and 17 (`NetProbeNavHost`); 1.10 port rows Task 17; 1.11 selection Task 17; 1.12 pill Task 17; 1.13 notice Task 17; 1.14 identity, edge, progress Task 17, identify tail Task 12; 1.15 reduced motion in every motion task; 1.16 layers Task 16 (review checklist); 1.17 hooks Tasks 16 and 17.
  - Section 2: 2.1 explained by Tasks 5 and 11 together; 2.2.1 gate, 2.2.2 passive-only, 2.2.3 steps, 2.2.4 budget Task 5; 2.2.5 timeouts (constants) Task 5 and close-on-cancel Task 4; 2.3 Task 11; 2.4 Task 11.
  - Section 3: 3.1 Task 3 (fields, merge, every read and write path) and Task 4 (`CertInfo`, `poweredBy`); 3.2 Task 6; 3.3 Tasks 2 (DAO) and 8 (callers); 3.4 Task 6 (reason form) and Task 10 ("Set by you" first line); 3.5 Task 7; 3.6 Tasks 6 (software list), 7 (rules), 8 (chips, column write in Task 2).
  - Section 4: 4.1 Tasks 1 and 2; 4.2 Task 9; 4.3 Task 10 (with `DeviceEdits.of` in Task 9); 4.4 Tasks 9 and 10; 4.5 Tasks 10 and 14.
  - Section 5: 5.1 Task 12; 5.2 unchanged; 5.3 Tasks 2 (DAO), 13 (appScope, UndoStore, Forget), 14 (Remove); 5.4 Task 12; 5.5 Task 12 (with `markRun` in Task 2).
  - Section 6: Task 1 (columns, migration, `open(name)`, schema 2, androidTest assets, `MIGRATION_TEST_DB`), Task 2 (DAO).
  - Section 7 copy: every string is a constant in `HomeCopy`, `DetailCopy`, `EditCopy` or `DetailText` and has a `CopyTableTest` template (Tasks 8, 9, 11, 12, 13); the removed rename strings go in Task 10.
  - Section 8 scope: honoured; no file outside the list changes except those named in deviations 3 and 7 below.
  - Section 9.1 JVM tests: 1 to 4 Task 15 `RadarMotionTest`; 5 and 28 Task 15 `MotionRulesTest`; 6, 7 Task 11 `PortUrisTest`; 8 Task 11 `ActionProviderTest`; 9 Task 4 `ProbeParsersTest`; 10 to 13 Task 5 `ServiceIdentifierLadderTest`, `ActiveProbeBudgetTest`; 14 Task 4 `CancellationTest`; 15 Task 4 `TlsSubjectTest`; 16 Task 3 `ServiceMergeTest`, `PortMappingTest`; 17 Task 6; 18 Task 7 (`DeviceIdentifierTest`, `NewRulesTest`); 19 Task 7 `RulesPrecedenceTest`; 20 Task 7 `RouterPatternTest` and Task 6; 21 Task 2 `IdentityWriteTest`; 22, 24 Task 9 `OverridesTest`; 22 (edge), 23 Task 9 `IdentityLineOverridesTest`; 25 Task 9 `DetailTextTest`; 26, 27 Task 12 `ScanSummaryTextTest`; 29 Task 12 `DetailTextTest`; 30 Task 13 `UndoStoreTest`; 31 Task 12 `StaleRunTest`; 32 Tasks 8, 9, 11, 12, 13 `CopyTableTest`.
  - Section 9.2 Compose tests (compiled in their tasks, run in Task 18): 1 to 5 Task 16 `RadarBlockTest`; 3 (menu half) and 16 (settle half) Task 16 `HomeContentTest.quickScanAllAndForgetWaitForTheSettle`; 6, 8 to 12 Task 17 `MotionTest`; 7 Task 17 `NavTransitionTest`; 13 Task 11; 14 Task 10; 15 Tasks 10 and 14; 16 Task 13; 17 Task 8; 18 Task 12; 19 existing tests updated in Tasks 10 (Detail), 16 (radar settle sample) and 17 (notice is animated but its tests run with the clock).
  - Section 9.3 Room: 1 Task 1 `MigrationTest` and `DirectorCopyMigrationTest`; 2 Task 2; 3 Task 7; 4 Task 2; 5 Task 2 (snapshot round trip) and Task 14 `RemoveDeviceTest`; 6 Task 13 `AppScopesTest` (JVM) and Task 14 `RemoveDeviceTest` (instrumented).
  - Section 9.4: Task 18, step 0 included, plus the copy-of-the-Director's-database migration run.
  - Sections 10 to 13: assumptions and open items go to the Director in the Task 18 record; section 12 deviations and section 13 rulings are implemented as written.
- **Placeholder scan:** no "TBD", "similar to Task N" or "add error handling" remains; every test and implementation block is complete code. Where an existing file is edited rather than replaced, the step names the exact function or line and gives the full replacement.
- **Deviations and additions, each flagged for the Director:**
  1. `DeviceEdits`, `Effective`, `EditDraft` and `Detected` live in `data/Overrides.kt`, not `ui/detail/EditDevice.kt`, so `HostRepository.setOverrides(hostId, DeviceEdits)` does not make the data layer depend on the UI.
  2. `deleteNetworkCascade` is a repository transaction over three DAO deletes in the spec's order, not a DAO `@Transaction` default method (avoids the KSP interface default-method path); atomicity is the same.
  3. `AppContainer.radarScanNetworkId` is added: spec 5.3 step 2 needs Detail to know whether a radar scan runs on the removed host's network, and section 8 does not list a field for it.
  4. `HomeUiState` gains `networkId`, `undoText` and `scanCompleted`; `DetailUiState` gains `identifying` and `identified`; `RadarBlock` gains `onBusyChange` (spec) and an `onComposed` test hook (spec 9.2 test 4's counter).
  5. While a Detail scan runs, every port row gets the placement spec (old rows slide, only new rows fade and rise), because spec 1.10's "rows present at scan start get no animation" contradicts 9.2 test 8's sliding 443 row.
  6. Spec 9.2 test 8 says port 8123 lands between 80 and 443; ports sort by number, so the test uses 80, 8123 and 8443.
  7. Spec 9.2 test 14 sets Type NAS and Vendor Synology on a host whose detected values are Synology NAS, which `DeviceEdits.of` must store as nothing; the test uses a host with nothing detected.
  8. An HTTP-set port whose HEAD fails and whose TLS handshake succeeds is named by the unknown-port rule (HTTPS with an HTTP answer, else TLS); the spec names only TLS-set and unknown ports.
  9. RTSP and RDP probes run when the passive read did not confirm a service (the spec's "each step only if the previous did not confirm"); the zero-byte rule is kept for every other port.
  10. The identity line gets `Role.Button` and the "Edit device" description but no 48 dp minimum height, so the font-scale 1.3 port-row floors hold; the name above it is a 48 dp target for the same action.
  11. On the own-phone row, `Build.MODEL` is shown only while the identity line is the own-line, so a set model wins (spec 4.2) without a new `HostRow` field.
  12. The Phase 2 `HomeScreenTest` launches the real activity, which opens `netprobe.db`, against spec 6's rule; Task 18 runs it last, after the backup and the in-place migration check.
  13. `p-proxmox-cert` is kept exactly as specified, but real Proxmox certificates carry "PVE Cluster Node" in OU and "Proxmox Virtual Environment" in O, so the rule will rarely fire; the Director should decide whether it should read the OU or match O.
  14. Three pure helpers, `blipCoreAlpha`, `blipGlowAlpha` and `blipGlowScale`, carry the blip look so the JVM pins the reduced-motion values 9.2 test 5 cannot read from the draw phase.
  15. Close-on-cancel uses `suspendCancellableCoroutine` with `Dispatchers.IO.dispatch`, as the spec words it; Probe.connect's existing watcher pattern is untouched.
- **Type consistency:**
  - `ServiceInfo` keeps its five positional parameters first (Task 3); every existing `ServiceInfo(name, product, version, detail, confidence)` call compiles; `PortEntity.toServiceInfo()` and `withService()` (Task 3) are the only conversions and are used by `PortRepository` (Task 3), `DetailViewModel` (Task 3) and `servicesFor` (Task 3).
  - `ServiceMerge.merge` (Task 3) is used by `PortMapping.merge` (Task 3), `HostEvidenceBuilder` (Task 3) and `IdentityStabilityTest` (Task 7).
  - `CertInfo` (Task 4) is produced by `TlsPeek.certInfo` and consumed by `ServiceIdentifier` (Tasks 4 and 5) and the ladder fakes (Task 5). `TlsPeek.certNames` is removed in Task 4 together with its only caller and its test fake.
  - `BannerReader` and `HttpTitleFetcher` become `open` in Task 4 before Task 5 subclasses them; `HttpPeek.poweredBy` (Task 4) has a default, so `DiscoveryEngineTest`'s four-argument `HttpPeek` still compiles.
  - `ProbeMode`, `ActiveProbeBudget`, `ProbeModes` (Task 5) are consumed by `DetailViewModel.scanHostOn` (Task 5) and `QuickScanAllPolicy` (Task 5); `QuickScanAll.kt` grows `QuickScanAllLoop` in Task 14.
  - `DeviceIdentity.software` (Task 2) is filled by `DeviceIdentifier` (Task 6), stored by `SoftwareColumn.encode` in `setIdentity` / `setIdentityIfNotWeaker` (Task 2), read back by `softwareLabels` (Task 8).
  - `Rule.onPorts` / `Rule.software` (Task 6) are used by the rules file (Task 7) and `SyntheticEvidence` (Task 7).
  - `DeviceEdits` (Task 2) gains its companion `of` in Task 9; `EditDraft`, `Detected`, `Effective` (Task 9) are used by `EditDeviceDialog` and `DetailContent` (Task 10) and by `IdentityLine`, `RowRules`, `lookupVulnsForDevice` (Task 9). `HostRepository.setCustomName` and `HostDao.setCustomName` are removed in Task 10 together with their last callers (`DetailViewModel.setCustomName`, `DaoTest`).
  - `NetworkRepository.ensure` returns `NetworkEntity?` from Task 2; `discover` (Task 2) and the four existing DAO tests (Task 2) are updated in the same task.
  - `DetailCopy` is created in Task 8 and only grows (Tasks 9, 11, 12); `EditCopy` is created in Task 9; `HomeCopy` grows in Tasks 12 and 13.
  - `PortRow.tryAs` (Task 11) has a default, so `DetailTextTest.port` and `DetailFakes.port` compile before and after; `DetailFakes.port` gains the `tryAs` parameter in Task 11.
  - `NoticeLine(text, onDismiss, onUndo)` (Task 13) is wrapped by `NoticeArea` (Task 17); `HomeMenu`'s new parameters (Task 13) have defaults; `forgetEnabled` gains `!radarBusy` in Task 16.
  - `RadarBlock(scanCompleted)` arrives in Task 12 and is kept by the Task 16 rewrite, which adds `onBusyChange` and `onComposed`; the semantics keys `ArmSpinningKey` (existing), `RadarFractionKey`, `PulseCountKey`, `PulseActiveKey`, `BreathingKey` are defined in Task 16 and read by `RadarBlockTest` (Task 16). `ArrivalDelayKey` and its `arrivalDelay` setter (Task 17) are public so `DetailContent` can set it.
  - `ScanController.stopAndJoin` (Task 14) is used by `DetailViewModel.removeDevice` (Task 14). `UndoStore`, `PendingUndo`, `AppScopes` (Task 13) are used in Tasks 13 and 14.
- **Review Focus mapping:**
  1. Printer ports never receive an active probe: Task 5 `ServiceIdentifierLadderTest.passive only ports never receive a byte in either mode`, `ipp gets one head in active mode and nothing more`, `passive only and head only sets are pinned`; device check in Task 18 step 7.
  2. The active budget: Task 5 `no more than 64 ports ever enter the ladder`, `no step starts once 60 s have been spent and refused ports stay open and unknown`, `today's steps are not counted against the budget`, `eight parallel identifies overshoot by at most eight steps`, `passive mode sends nothing new to a silent unknown port`, `quick scan all is passive`; `ActiveProbeBudgetTest`.
  3. Identity flapping: Task 3 `ServiceMergeTest.a port table result never replaces a confirmed name` and `the evidence builder uses the same merge`; Task 7 `IdentityStabilityTest` (three tests); Task 2 `IdentityWriteTest.identity guard replaces on equal or higher confidence only`; instrumented Task 7 `DaoTest.portEvidenceSurvivesRediscoveryAndATimedOutRescan` and Task 2 `DaoTest.identityGuardKeepsAStrongerStoredIdentity`.
  4. Undo re-adding a host mid-scan, and orphan ports: Task 13 `UndoStoreTest` (post commits the previous, undo once, commit idempotent, dismiss commits) with the commit at every scan start; Task 14 `RemovedHostsTest.a removed ip is skipped for the rest of the radar scan`, `QuickScanAllLoopTest.cancelling one host skips it and the loop carries on`, `ScanControllerJoinTest.stop and join waits for the scan's cleanup`; instrumented Task 14 `RemoveDeviceTest` (no re-insert, no orphans, restore clash caught).
  5. Migration preserving marks and notes: Task 1 `MigrationSqlTest` (three tests), `SchemaExportTest` (three tests); instrumented Task 1 `MigrationTest.marksNotesAndPortsSurviveTheMigration` and `DirectorCopyMigrationTest.theDirectorsCopyMigratesWithEveryMarkAndNote`, run first in Task 18 before the real database is touched.
