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
