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
 * The Director's own data, schema 4 to 5, on a pushed copy (Phase 3E spec 7 and 10). Never opens, creates or deletes DB_NAME; deletes
 * only the copy, in @After, even when an assertion fails. Skipped (an assumption failure, not a pass) when no copy was pushed.
 */
@RunWith(AndroidJUnit4::class)
class DirectorCopyMigration45Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    @get:Rule val helper = MigrationTestHelper(instrumentation, NetProbeDb::class.java)

    private data class Stamp(val exists: Boolean, val length: Long, val modified: Long)
    private fun stamp(f: File) = if (f.exists()) Stamp(true, f.length(), f.lastModified()) else Stamp(false, -1, -1)
    private fun rows(query: (String) -> Cursor, sql: String): List<String> = query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    private fun count(query: (String) -> Cursor, sql: String): Int = query(sql).use { it.moveToFirst(); it.getInt(0) }
    /** Every schema 4 column of every table, read once at version 4. */
    private fun columns(query: (String) -> Cursor): Map<String, List<String>> = TABLES.associateWith { t ->
        query("PRAGMA table_info(`$t`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
    }
    /** Those columns, row by row in rowid order; quote() keeps NULL, numbers, text and blobs distinct. */
    private fun snapshot(query: (String) -> Cursor, cols: Map<String, List<String>>): Map<String, List<String>> = cols.mapValues { (t, cs) ->
        rows(query, "SELECT " + cs.joinToString(" || '|' || ") { "quote(`$it`)" } + " FROM `$t` ORDER BY rowid")
    }
    @After fun tidy() { listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(MIGRATION_COPY_DB + it).delete() } }

    @Test fun theDirectorsSchema4CopyMigratesWithEveryMarkNotePortAndToolRun() {
        val copy = context.getDatabasePath(MIGRATION_COPY_DB)
        assumeTrue("no pushed copy of the Director's database", copy.exists())
        assertNotEquals(DB_NAME, MIGRATION_COPY_DB)
        val appDb = context.getDatabasePath(DB_NAME); val before = stamp(appDb)
        val v4 = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
        assertEquals("the copy must be schema 4 (the 3E batch 1 build or earlier phase 3D build)", 4, v4.version)
        val cols = columns { v4.rawQuery(it, null) }
        val was = snapshot({ v4.rawQuery(it, null) }, cols)
        v4.close()
        val v5 = helper.runMigrationsAndValidate(MIGRATION_COPY_DB, 5, true, MIGRATION_4_5)
        for (t in TABLES) assertEquals("$t: every schema 4 column of every row unchanged", was.getValue(t), snapshot({ v5.query(it) }, cols).getValue(t))
        for (c in listOf("stack", "stackRole", "cpe", "probe"))
            assertEquals("no existing port gains a $c", 0, count({ v5.query(it) }, "SELECT COUNT(*) FROM ports WHERE $c IS NOT NULL"))
        v5.close()
        val room = NetProbeDb.open(context, MIGRATION_COPY_DB)     // Room's own validation through the production builder
        try { assertEquals(was.getValue("hosts").size, room.openHelper.readableDatabase.query("SELECT COUNT(*) FROM hosts").use { it.moveToFirst(); it.getInt(0) }) }
        finally { room.close() }
        assertEquals("the app's own database file changed", before, stamp(appDb))
    }

    private companion object {
        const val MIGRATION_COPY_DB = "netprobe-migration-copy.db"
        val TABLES = listOf("networks", "hosts", "ports", "vuln_cache", "tool_runs", "tool_findings")
    }
}
