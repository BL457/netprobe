package com.northwalessolutions.netprobe.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface NetworkDao {
    @Query("SELECT * FROM networks WHERE signature = :sig LIMIT 1") suspend fun bySignature(sig: String): NetworkEntity?
    @Insert suspend fun insert(n: NetworkEntity): Long
    @Query("UPDATE networks SET lastSeen = :now, ssid = COALESCE(:ssid, ssid) WHERE id = :id") suspend fun touch(id: Long, now: Long, ssid: String?)
}
@Dao interface HostDao {
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    @Query("SELECT * FROM hosts WHERE id = :id") fun byId(id: Long): Flow<HostEntity?>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun byIdOnce(id: Long): HostEntity?
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND ip = :ip LIMIT 1") suspend fun find(networkId: Long, ip: String): HostEntity?
    @Insert suspend fun insert(h: HostEntity): Long
    @Update suspend fun update(h: HostEntity)
    @Query("UPDATE hosts SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: HostStatus)
    @Query("UPDATE hosts SET note = :note WHERE id = :id") suspend fun setNote(id: Long, note: String)
    @Query("UPDATE hosts SET customName = :name WHERE id = :id") suspend fun setCustomName(id: Long, name: String?)
    @Query("UPDATE hosts SET lastPortScan = :now WHERE id = :id") suspend fun markScanned(id: Long, now: Long)
}
data class PortCount(val hostId: Long, val n: Int)

@Dao interface PortDao {
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") fun forHost(hostId: Long): Flow<List<PortEntity>>
    @Query("SELECT hostId, COUNT(*) AS n FROM ports GROUP BY hostId") fun counts(): Flow<List<PortCount>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId AND port = :port LIMIT 1") suspend fun find(hostId: Long, port: Int): PortEntity?
    @Insert suspend fun insert(p: PortEntity): Long
    @Update suspend fun update(p: PortEntity)
}
@Dao interface VulnCacheDao {
    @Query("SELECT * FROM vuln_cache WHERE `query` = :q LIMIT 1") suspend fun get(q: String): VulnCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: VulnCacheEntity)
    @Query("DELETE FROM vuln_cache") suspend fun clear()
}
