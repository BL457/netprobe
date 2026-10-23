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

/**
 * Schema 2 to 3 (spec 7.3): two new tables and their indices, additive only. No ALTER, no DROP, so no schema-2 row is touched.
 * The createSql strings are copied verbatim from the newly exported 3.json and are the expected values in ToolMigrationSqlTest.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER NOT NULL, " +
            "`toolId` TEXT NOT NULL, `port` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, " +
            "`optionsJson` TEXT NOT NULL, `summary` TEXT NOT NULL, FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tool_findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, " +
            "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `detail` TEXT, `extra` TEXT, " +
            "FOREIGN KEY(`runId`) REFERENCES `tool_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`)")
    }
}

/**
 * Schema 3 to 4 (3C spec 7.1): two additive columns. manualOnly has a SQL default of 0, so every existing host stays visible; route is
 * nullable with no default, so every existing run reads "no route". No row is rewritten, nothing is dropped or recreated.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN manualOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tool_runs ADD COLUMN route TEXT")
    }
}

/**
 * Schema 4 to 5 (Phase 3E spec 7): four additive nullable columns on ports, the software stack, its role, the cpe_map product and the
 * probe or rule id that settled the name. No default, no row rewritten, nothing dropped or recreated; an old row reads "no stack".
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ports ADD COLUMN stack TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN stackRole TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN cpe TEXT")
        db.execSQL("ALTER TABLE ports ADD COLUMN probe TEXT")
    }
}

/**
 * Schema 5 to 6 (tools platform spec section 7): tool_runs.hostId becomes nullable and tool_runs gains toolName, a display-name snapshot
 * so a removed tool still names its history. SQLite cannot drop NOT NULL, so both tables are rebuilt. Room keeps foreign keys ON during a
 * migration, so dropping tool_runs while tool_findings exists would cascade-delete every finding: the child table is rebuilt and dropped
 * first, then the parent. Rows keep their ids, and the AUTOINCREMENT counters are carried over so a pruned id is never reused.
 */
val MIGRATION_5_6_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `tool_runs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `hostId` INTEGER, `toolId` TEXT NOT NULL, " +
        "`port` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `status` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, " +
        "`summary` TEXT NOT NULL, `route` TEXT, `toolName` TEXT NOT NULL DEFAULT '', " +
        "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "INSERT INTO tool_runs_new (id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route) " +
        "SELECT id, hostId, toolId, port, startedAt, finishedAt, status, optionsJson, summary, route FROM tool_runs",
    "CREATE TABLE IF NOT EXISTS `tool_findings_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, " +
        "`kind` TEXT NOT NULL, `value` TEXT NOT NULL, `detail` TEXT, `extra` TEXT, " +
        "FOREIGN KEY(`runId`) REFERENCES `tool_runs_new`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "INSERT INTO tool_findings_new (id, runId, kind, value, detail, extra) SELECT id, runId, kind, value, detail, extra FROM tool_findings",
    "DELETE FROM sqlite_sequence WHERE name IN ('tool_runs_new', 'tool_findings_new')",
    "INSERT INTO sqlite_sequence (name, seq) SELECT 'tool_runs_new', seq FROM sqlite_sequence WHERE name = 'tool_runs'",
    "INSERT INTO sqlite_sequence (name, seq) SELECT 'tool_findings_new', seq FROM sqlite_sequence WHERE name = 'tool_findings'",
    "DROP TABLE tool_findings",
    "DROP TABLE tool_runs",
    "ALTER TABLE tool_runs_new RENAME TO tool_runs",
    "ALTER TABLE tool_findings_new RENAME TO tool_findings",
    "CREATE INDEX IF NOT EXISTS `index_tool_runs_hostId_toolId_port_startedAt` ON `tool_runs` (`hostId`, `toolId`, `port`, `startedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_tool_findings_runId` ON `tool_findings` (`runId`)",
)

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (sql in MIGRATION_5_6_SQL) db.execSQL(sql)
    }
}
