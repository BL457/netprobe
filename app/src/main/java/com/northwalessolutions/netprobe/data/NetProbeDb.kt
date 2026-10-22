package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class, ToolRunEntity::class, ToolFindingEntity::class], version = 5, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    abstract fun tools(): ToolDao
    companion object {
        /**
         * No fallbackToDestructiveMigration: the Director's marks and notes must survive (spec 7.3, 3C spec 7.1). Every version step is
         * registered, so a schema 1, 2 or 3 database reaches 4. [name] exists for the migration tests.
         */
        fun open(context: Context, name: String = DB_NAME): NetProbeDb =
            Room.databaseBuilder(context, NetProbeDb::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
