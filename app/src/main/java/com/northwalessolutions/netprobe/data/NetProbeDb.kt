package com.northwalessolutions.netprobe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [NetworkEntity::class, HostEntity::class, PortEntity::class, VulnCacheEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class NetProbeDb : RoomDatabase() {
    abstract fun networks(): NetworkDao; abstract fun hosts(): HostDao; abstract fun ports(): PortDao; abstract fun vulnCache(): VulnCacheDao
    companion object {
        /** No fallbackToDestructiveMigration: the Director's marks and notes must survive (spec 6). [name] exists for the migration tests. */
        fun open(context: Context, name: String = DB_NAME): NetProbeDb =
            Room.databaseBuilder(context, NetProbeDb::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}
