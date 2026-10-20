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
