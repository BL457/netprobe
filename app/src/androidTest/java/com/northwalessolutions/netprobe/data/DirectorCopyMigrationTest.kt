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
 * Runs only when Task 19 step 2 has put a copy of the Director's database as [MIGRATION_COPY_DB]; skipped otherwise.
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
