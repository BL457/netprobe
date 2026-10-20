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
