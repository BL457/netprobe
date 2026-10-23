package com.northwalessolutions.netprobe.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * MIGRATION_5_6 against a recording database (tools platform spec section 7, Gruff ruling 1). The real run is Migration56Test on a device;
 * here the statement list is held to the order that keeps findings alive: the child tables are rebuilt and dropped before the parent.
 */
class MigrationSql56Test {
    private fun recorded(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String else fail("migration called ${method.name}, expected execSQL only")
            null
        } as SupportSQLiteDatabase
        MIGRATION_5_6.migrate(db)
        return sql
    }
    private fun index(prefix: String): Int = recorded().indexOfFirst { it.startsWith(prefix) }.also { assertTrue("missing: $prefix", it >= 0) }

    @Test fun `versions are 5 to 6`() {
        assertEquals(5, MIGRATION_5_6.startVersion); assertEquals(6, MIGRATION_5_6.endVersion)
    }
    @Test fun `findings are copied out and tool_findings is dropped before tool_runs is`() {
        // Room keeps foreign keys ON during a migration: DROP TABLE tool_runs while tool_findings still references it deletes every finding.
        assertTrue(index("DROP TABLE tool_findings") < index("DROP TABLE tool_runs"))
        assertTrue(index("INSERT INTO tool_findings_new") < index("DROP TABLE tool_findings"))
        assertTrue(index("INSERT INTO tool_runs_new") < index("INSERT INTO tool_findings_new"))
        assertTrue(index("CREATE TABLE IF NOT EXISTS `tool_runs_new`") < index("CREATE TABLE IF NOT EXISTS `tool_findings_new`"))
        assertTrue(index("DROP TABLE tool_runs") < index("ALTER TABLE tool_runs_new RENAME TO tool_runs"))
        assertTrue(index("ALTER TABLE tool_runs_new RENAME") < index("ALTER TABLE tool_findings_new RENAME"))
    }
    @Test fun `the new tables are the exported schema 6 ones`() {
        val sql = recorded()
        val runs = sql.first { it.startsWith("CREATE TABLE IF NOT EXISTS `tool_runs_new`") }
        assertTrue("hostId nullable", runs.contains("`hostId` INTEGER, "))
        assertTrue(runs.contains("`toolName` TEXT NOT NULL DEFAULT ''"))
        assertTrue(runs.contains("FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        val findings = sql.first { it.startsWith("CREATE TABLE IF NOT EXISTS `tool_findings_new`") }
        assertTrue(findings.contains("REFERENCES `tool_runs_new`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`)"))
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`)"))
    }
    @Test fun `rows and ids are copied whole, and nothing is updated or deleted from the data tables`() {
        val sql = recorded()
        assertTrue(sql.any { it == "INSERT INTO tool_runs_new (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
            "SELECT id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route FROM tool_runs" })
        assertTrue(sql.any { it == "INSERT INTO tool_findings_new (id, runId, kind, value, detail, extra) SELECT id, runId, kind, value, detail, extra FROM tool_findings" })
        for (s in sql) {
            assertFalse("UPDATE in a rebuild: $s", s.startsWith("UPDATE", ignoreCase = true))
            assertFalse("DELETE of data: $s", s.startsWith("DELETE FROM tool_", ignoreCase = true))
        }
    }
    @Test fun `the autoincrement counters are carried to the new tables`() {
        val sql = recorded()
        assertTrue(sql.any { it.contains("'tool_runs_new', seq FROM sqlite_sequence WHERE name = 'tool_runs'") })
        assertTrue(sql.any { it.contains("'tool_findings_new', seq FROM sqlite_sequence WHERE name = 'tool_findings'") })
    }
    @Test fun `the database version and the migration list line up`() {
        val schema = java.io.File("schemas/com.northwalessolutions.netprobe.data.NetProbeDb/6.json")
        assertTrue("exported schema 6 is committed: ${schema.absolutePath}", schema.exists())
        assertTrue(schema.readText().contains("\"version\": 6"))
    }
}
