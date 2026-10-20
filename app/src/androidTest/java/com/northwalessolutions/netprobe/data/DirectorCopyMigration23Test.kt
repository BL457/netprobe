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
