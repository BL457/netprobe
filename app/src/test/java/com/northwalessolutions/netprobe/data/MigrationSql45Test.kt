package com.northwalessolutions.netprobe.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** MIGRATION_4_5 against a recording database: four additive ALTERs on ports and nothing else (Phase 3E spec 7). */
class MigrationSql45Test {
    private fun recorded(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String else fail("migration called ${method.name}, expected execSQL only")
            null
        } as SupportSQLiteDatabase
        MIGRATION_4_5.migrate(db)
        return sql
    }

    @Test fun `versions are 4 to 5`() {
        assertEquals(4, MIGRATION_4_5.startVersion); assertEquals(5, MIGRATION_4_5.endVersion)
    }
    @Test fun `exactly the four additive nullable columns on ports and nothing else`() {
        assertEquals(listOf("ALTER TABLE ports ADD COLUMN stack TEXT", "ALTER TABLE ports ADD COLUMN stackRole TEXT",
            "ALTER TABLE ports ADD COLUMN cpe TEXT", "ALTER TABLE ports ADD COLUMN probe TEXT"), recorded())
    }
    @Test fun `no statement rewrites, drops or recreates anything`() {
        for (s in recorded()) for (word in listOf("UPDATE", "DELETE", "DROP", "INSERT", "CREATE", "RENAME", "NOT NULL", "DEFAULT"))
            assertFalse("$word in an additive migration: $s", s.contains(word, ignoreCase = true))
    }
}
