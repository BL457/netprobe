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
