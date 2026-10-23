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

/**
 * Schema 5 to 6 on a throwaway file (tools platform spec section 7, Gruff ruling 1): hostId becomes nullable and tool_runs gains toolName.
 * Room keeps foreign keys ON during a migration, so the test seeds findings and asserts none of them is lost. Never opens, creates or deletes DB_NAME.
 */
@RunWith(AndroidJUnit4::class)
class Migration56Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun deleteTestDb() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_TEST_DB + it).delete() } }

    @Before fun clean() { assertNotEquals(DB_NAME, MIGRATION_TEST_DB); deleteTestDb() }
    @After fun tidy() { deleteTestDb() }

    private fun seedSchema5(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO networks (id, signature, displayName, gatewayIp, prefixLength, ssid, lastSeen, lastRunAt, forgotten) VALUES (1, 'sig', 'Home', '10.0.3.1', 24, 'Home', 5, 4, NULL)")
        db.execSQL("INSERT INTO hosts (id, networkId, ip, deviceType, identConfidence, identReasons, status, note, isGateway, firstSeen, lastSeen, lastDiscoveryRun, macSource, manualOnly, customName) " +
            "VALUES (7, 1, '10.0.3.59', 'NAS', 80, '[]', 'INTERESTING', 'QA note NAS', 0, 1, 2, 4, 4, 0, 'DiskStation')")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
            "VALUES (1, 7, 'dir-bruteforce', 5000, 100, 200, 'DONE', '{\"tls\":false}', '3 found in 312 paths', 'WIFI')")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
            "VALUES (2, 7, 'http-fingerprint', 5000, 300, 400, 'DONE', '{}', 'nginx, 4 findings', NULL)")
        db.execSQL("INSERT INTO tool_runs (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
            "VALUES (3, 7, 'http-fingerprint', 5000, 500, 600, 'DONE', '{}', 'pruned later', NULL)")
        db.execSQL("DELETE FROM tool_runs WHERE id = 3")      // leaves the AUTOINCREMENT counter at 3
        for ((i, run) in listOf(1, 1, 1, 2, 2).withIndex())
            db.execSQL("INSERT INTO tool_findings (id, runId, kind, value, detail, extra) VALUES (${i + 1}, $run, 'PATH', '/p$i', 'detail $i', NULL)")
    }

    @Test fun findingsSurviveTheRebuildAndTheNewShapeWorks() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 5).use { seedSchema5(it) }
        val v6 = helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 6, true, MIGRATION_5_6)
        v6.query("SELECT COUNT(*) FROM tool_findings").use { c -> assertTrue(c.moveToFirst()); assertEquals("no finding may be lost", 5, c.getInt(0)) }
        v6.query("SELECT runId, COUNT(*) FROM tool_findings GROUP BY runId ORDER BY runId").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals(3, c.getInt(1))
            assertTrue(c.moveToNext()); assertEquals(2, c.getInt(0)); assertEquals(2, c.getInt(1))
        }
        v6.query("SELECT id, hostId, toolId, port, summary, route, toolName FROM tool_runs ORDER BY id").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals(7, c.getInt(1)); assertEquals("dir-bruteforce", c.getString(2)); assertEquals(5000, c.getInt(3))
            assertEquals("3 found in 312 paths", c.getString(4)); assertEquals("WIFI", c.getString(5)); assertEquals("", c.getString(6))
            assertTrue(c.moveToNext()); assertEquals(2, c.getInt(0)); assertTrue(c.isNull(5))
            assertFalse(c.moveToNext())
        }
        // The counter was carried over: a new run does not reuse the pruned id 3, and a run can now have no host.
        v6.execSQL("INSERT INTO tool_runs (hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route, toolName) " +
            "VALUES (NULL, 'user:dns-lookup', 0, 700, NULL, 'RUNNING', '{}', '', NULL, 'DNS lookup')")
        v6.query("SELECT id, hostId, toolName FROM tool_runs WHERE toolId = 'user:dns-lookup'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(4, c.getInt(0)); assertTrue(c.isNull(1)); assertEquals("DNS lookup", c.getString(2))
        }
        // Both cascades still work: deleting the host removes its runs and their findings; the run with no host stays.
        v6.execSQL("PRAGMA foreign_keys = ON")
        v6.execSQL("DELETE FROM hosts WHERE id = 7")
        v6.query("SELECT COUNT(*) FROM tool_runs").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        v6.query("SELECT COUNT(*) FROM tool_findings").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        v6.close()
        assertEquals("the app database file changed", before, stamp(appDb))
    }

    @Test fun roomOpensTheMigratedFileThroughTheProductionBuilder() {
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        helper.createDatabase(MIGRATION_TEST_DB, 5).use { seedSchema5(it) }
        helper.runMigrationsAndValidate(MIGRATION_TEST_DB, 6, true, MIGRATION_5_6).close()
        val room = NetProbeDb.open(context, MIGRATION_TEST_DB)
        try {
            runBlocking {
                assertEquals(setOf(1L, 2L), room.tools().runsForHost(7).first().map { it.id }.toSet())
                assertEquals(3, room.tools().findingCount(1)); assertEquals(2, room.tools().findingCount(2))
                val none = room.tools().insertRun(ToolRunEntity(hostId = null, toolId = "user:x", port = 0, startedAt = 1, finishedAt = null, status = ToolRunStatus.RUNNING,
                    optionsJson = "{}", summary = "", toolName = "X"))
                assertEquals(listOf(none), room.tools().runsWithoutHost("user:x").first().map { it.id })
                assertTrue("recentForNetwork keeps a run with no device", room.tools().recentForNetwork(1, 10).first().any { it.run.id == none && it.ip == null })
            }
        } finally { room.close() }
        assertEquals("the app database file changed", before, stamp(appDb))
    }
}
