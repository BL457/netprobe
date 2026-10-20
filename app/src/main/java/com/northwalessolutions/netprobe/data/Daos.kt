package com.northwalessolutions.netprobe.data

import androidx.room.*
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import kotlinx.coroutines.flow.Flow

@Dao interface NetworkDao {
    @Query("SELECT * FROM networks WHERE signature = :sig LIMIT 1") suspend fun bySignature(sig: String): NetworkEntity?
    @Query("SELECT * FROM networks WHERE prefixLength = :prefix AND ((:gw IS NULL AND gatewayIp IS NULL) OR gatewayIp = :gw)")
    suspend fun byGateway(gw: String?, prefix: Int): List<NetworkEntity>
    @Query("SELECT * FROM networks WHERE id = :id") suspend fun byId(id: Long): NetworkEntity?
    /** The MAC recorded for the network's own gateway host, if a scan has stored one. */
    @Query("SELECT mac FROM hosts WHERE networkId = :networkId AND ip = :gw LIMIT 1") suspend fun gatewayHostMac(networkId: Long, gw: String): String?
    @Query("UPDATE networks SET signature = :sig, ssid = :ssid, displayName = :name, lastSeen = :now WHERE id = :id") suspend fun adopt(id: Long, sig: String, ssid: String?, name: String, now: Long)
    @Insert suspend fun insert(n: NetworkEntity): Long
    @Query("UPDATE networks SET lastSeen = :now, ssid = COALESCE(:ssid, ssid) WHERE id = :id") suspend fun touch(id: Long, now: Long, ssid: String?)
    @Query("UPDATE networks SET lastRunAt = :runId WHERE id = :id") suspend fun markRun(id: Long, runId: Long)
    @Query("UPDATE networks SET forgotten = :at WHERE id = :id") suspend fun setForgotten(id: Long, at: Long?)
    @Query("SELECT id FROM networks WHERE forgotten IS NOT NULL") suspend fun forgottenIds(): List<Long>
    /** The three halves of a network delete; there are no foreign keys, so the repository runs them in this order in one transaction. */
    @Query("DELETE FROM ports WHERE hostId IN (SELECT id FROM hosts WHERE networkId = :id)") suspend fun deletePortsOfNetwork(id: Long)
    @Query("DELETE FROM hosts WHERE networkId = :id") suspend fun deleteHostsOfNetwork(id: Long)
    @Query("DELETE FROM networks WHERE id = :id") suspend fun deleteNetwork(id: Long)
}
@Dao interface HostDao {
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    @Query("SELECT * FROM hosts WHERE id = :id") fun byId(id: Long): Flow<HostEntity?>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun byIdOnce(id: Long): HostEntity?
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND ip = :ip LIMIT 1") suspend fun find(networkId: Long, ip: String): HostEntity?
    @Insert suspend fun insert(h: HostEntity): Long
    /** Undo of Remove device: the same rows with the same ids; a clash is an error, never a silent replace (spec 5.3). */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(hosts: List<HostEntity>)
    /** Scan-derived columns only; customName, the user* overrides, status and note are deliberately absent so they are never overwritten. */
    @Query("UPDATE hosts SET mac = :mac, macSource = :macSource, reverseDnsName = :rdns, mdnsName = :mdnsName, netbiosName = :netbiosName, upnpName = :upnpName, upnpManufacturer = :upnpManufacturer, " +
        "upnpModel = :upnpModel, vendor = :vendor, snmpSysDescr = :snmpSysDescr, httpTitle = :httpTitle, mdnsTxtJson = :mdnsTxtJson, isGateway = :isGateway, lastSeen = :lastSeen, " +
        "lastDiscoveryRun = :runId, upnpDeviceType = :upnpDeviceType, upnpServer = :upnpServer, httpServer = :httpServer, httpAuthRealm = :httpAuthRealm WHERE id = :id")
    suspend fun updateScan(id: Long, mac: String?, macSource: Int, rdns: String?, mdnsName: String?, netbiosName: String?, upnpName: String?, upnpManufacturer: String?, upnpModel: String?,
        vendor: String?, snmpSysDescr: String?, httpTitle: String?, mdnsTxtJson: String?, isGateway: Boolean, lastSeen: Long, runId: Long, upnpDeviceType: String?, upnpServer: String?,
        httpServer: String?, httpAuthRealm: String?)
    @Query("UPDATE hosts SET identVendor = :v, identModel = :m, identOs = :o, deviceType = :t, identConfidence = :c, identReasons = :r, identSoftware = :s WHERE id = :id")
    suspend fun updateIdentity(id: Long, v: String?, m: String?, o: String?, t: DeviceType, c: Int, r: List<String>, s: String?)
    /** The port-scan write (spec 3.3): one atomic UPDATE that lands only when the new confidence is equal or higher. Returns rows changed. */
    @Query("UPDATE hosts SET identVendor = :v, identModel = :m, identOs = :o, deviceType = :t, identConfidence = :c, identReasons = :r, identSoftware = :s " +
        "WHERE id = :id AND identConfidence <= :c")
    suspend fun updateIdentityIfNotWeaker(id: Long, v: String?, m: String?, o: String?, t: DeviceType, c: Int, r: List<String>, s: String?): Int
    @Query("UPDATE hosts SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: HostStatus)
    @Query("UPDATE hosts SET note = :note WHERE id = :id") suspend fun setNote(id: Long, note: String)
    @Query("UPDATE hosts SET customName = :name, userType = :type, userVendor = :vendor, userModel = :model WHERE id = :id")
    suspend fun setOverrides(id: Long, name: String?, type: DeviceType?, vendor: String?, model: String?)
    @Query("UPDATE hosts SET lastPortScan = :now WHERE id = :id") suspend fun markScanned(id: Long, now: Long)
    @Query("DELETE FROM hosts WHERE id = :id") suspend fun deleteHost(id: Long)
}
data class PortCount(val hostId: Long, val n: Int)

@Dao interface PortDao {
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") fun forHost(hostId: Long): Flow<List<PortEntity>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId ORDER BY port") suspend fun forHostOnce(hostId: Long): List<PortEntity>
    @Query("SELECT hostId, COUNT(*) AS n FROM ports GROUP BY hostId") fun counts(): Flow<List<PortCount>>
    @Query("SELECT * FROM ports WHERE hostId = :hostId AND port = :port LIMIT 1") suspend fun find(hostId: Long, port: Int): PortEntity?
    @Insert suspend fun insert(p: PortEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(ports: List<PortEntity>)
    @Update suspend fun update(p: PortEntity)
    @Query("DELETE FROM ports WHERE hostId = :hostId") suspend fun deletePortsForHost(hostId: Long)
}
@Dao interface VulnCacheDao {
    @Query("SELECT * FROM vuln_cache WHERE `query` = :q LIMIT 1") suspend fun get(q: String): VulnCacheEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: VulnCacheEntity)
    @Query("DELETE FROM vuln_cache") suspend fun clear()
}

/** Minimal stub so NetProbeDb.tools() compiles and KSP exports the tool tables; replaced wholesale in Task 2. */
@Dao interface ToolDao {
    @Insert suspend fun insertRun(r: ToolRunEntity): Long
    @Insert suspend fun insertFinding(f: ToolFindingEntity): Long
}
