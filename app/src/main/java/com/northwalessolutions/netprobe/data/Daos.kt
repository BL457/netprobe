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
    /** Every visible row: manual tool targets are hidden from Home, its counts, groups, search and Quick scan all (3C spec 7.1). */
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND manualOnly = 0 ORDER BY ip") fun forNetwork(networkId: Long): Flow<List<HostEntity>>
    /** Every row, manual targets included: Forget's tool check and the canary picks read this (3C spec 7.1). */
    @Query("SELECT * FROM hosts WHERE networkId = :networkId ORDER BY ip") fun allForNetwork(networkId: Long): Flow<List<HostEntity>>
    @Query("SELECT * FROM hosts WHERE id = :id") fun byId(id: Long): Flow<HostEntity?>
    @Query("SELECT * FROM hosts WHERE id = :id") suspend fun byIdOnce(id: Long): HostEntity?
    @Query("SELECT * FROM hosts WHERE networkId = :networkId AND ip = :ip LIMIT 1") suspend fun find(networkId: Long, ip: String): HostEntity?
    @Insert suspend fun insert(h: HostEntity): Long
    /** Undo of Remove device: the same rows with the same ids; a clash is an error, never a silent replace (spec 5.3). */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(hosts: List<HostEntity>)
    /**
     * Scan-derived columns only; customName, the user* overrides, status and note are deliberately absent so they are never overwritten.
     * A scan or a heard packet finding a manual tool target promotes it (manualOnly = 0), keeping its tool history (3C spec 7.1).
     */
    @Query("UPDATE hosts SET mac = :mac, macSource = :macSource, reverseDnsName = :rdns, mdnsName = :mdnsName, netbiosName = :netbiosName, upnpName = :upnpName, upnpManufacturer = :upnpManufacturer, " +
        "upnpModel = :upnpModel, vendor = :vendor, snmpSysDescr = :snmpSysDescr, httpTitle = :httpTitle, mdnsTxtJson = :mdnsTxtJson, isGateway = :isGateway, lastSeen = :lastSeen, " +
        "lastDiscoveryRun = :runId, manualOnly = 0, upnpDeviceType = :upnpDeviceType, upnpServer = :upnpServer, httpServer = :httpServer, httpAuthRealm = :httpAuthRealm WHERE id = :id")
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
    /** Start-up sweep (3C spec 4.3, G8): manual targets a killed process left with no run. A promoted row or one with a run is kept. */
    @Query("DELETE FROM hosts WHERE manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs WHERE hostId IS NOT NULL)") suspend fun deleteOrphanManual(): Int
    /** A refused manual start (spec 4.3 step 4): only while the row is still manualOnly and has no run, so a promotion or a started run keeps it. */
    @Query("DELETE FROM hosts WHERE id = :id AND manualOnly = 1 AND id NOT IN (SELECT hostId FROM tool_runs WHERE hostId IS NOT NULL)") suspend fun deleteManualIfUnused(id: Long): Int
}
data class PortCount(val hostId: Long, val n: Int)
/** One run with its host's IP, for the manual tools history (3C spec 4.1, 7.1). */
data class NetworkRun(@Embedded val run: ToolRunEntity, val ip: String?)

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

/** Tool runs and findings (spec 7.2). History is kept per (host, tool, port); HINT findings are hidden from the finding lists and counts. */
@Dao interface ToolDao {
    @Insert suspend fun insertRun(r: ToolRunEntity): Long
    @Query("UPDATE tool_runs SET finishedAt = :at, status = :status, summary = :summary WHERE id = :id")
    suspend fun finish(id: Long, at: Long?, status: ToolRunStatus, summary: String)
    /** Keeps the newest [keep] runs of one (host, tool, port), newest by id: AUTOINCREMENT ids only grow, so a clock that goes back never prunes the new run (ruling R5). */
    @Query("DELETE FROM tool_runs WHERE hostId IS :hostId AND toolId = :toolId AND port = :port AND id NOT IN " +
        "(SELECT id FROM tool_runs WHERE hostId IS :hostId AND toolId = :toolId AND port = :port ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(hostId: Long?, toolId: String, port: Int, keep: Int)
    @Query("SELECT * FROM tool_runs WHERE status = 'RUNNING'") suspend fun runningOnce(): List<ToolRunEntity>
    @Query("SELECT id FROM tool_runs") suspend fun allRunIds(): List<Long>
    @Insert suspend fun insertFinding(f: ToolFindingEntity): Long
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId ORDER BY startedAt DESC, id DESC") fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>>
    @Query("SELECT * FROM tool_runs WHERE id = :id") fun run(id: Long): Flow<ToolRunEntity?>
    /** The runs of one tool that had no device (schema 6), newest first: the history group a null host forms. */
    @Query("SELECT * FROM tool_runs WHERE hostId IS NULL AND toolId = :toolId ORDER BY startedAt DESC, id DESC") fun runsWithoutHost(toolId: String): Flow<List<ToolRunEntity>>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") fun findings(runId: Long): Flow<List<ToolFindingEntity>>
    @Query("SELECT * FROM tool_findings WHERE runId = :runId AND kind != 'HINT' ORDER BY id") suspend fun findingsOnce(runId: Long): List<ToolFindingEntity>
    @Query("SELECT COUNT(*) FROM tool_findings WHERE runId = :runId AND kind != 'HINT'") suspend fun findingCount(runId: Long): Int
    @Query("SELECT * FROM tool_runs WHERE hostId = :hostId AND status IN ('DONE','STOPPED','INTERRUPTED') ORDER BY startedAt DESC, id DESC")
    suspend fun finishedRunsOnce(hostId: Long): List<ToolRunEntity>
    @Query("SELECT value FROM tool_findings WHERE kind = 'HINT' AND runId IN (:runIds)") suspend fun hintsOf(runIds: List<Long>): List<String>
    @Query("UPDATE tool_runs SET optionsJson = :json WHERE id = :id") suspend fun setOptions(id: Long, json: String)
    @Query("SELECT DISTINCT hostId FROM tool_runs WHERE hostId IS NOT NULL") suspend fun hostsWithRuns(): List<Long>
    /** Newest first, every host of the network, manual targets included (3C spec 7.1), plus runs that had no device (ip is null). */
    @Query("SELECT tool_runs.*, hosts.ip AS ip FROM tool_runs LEFT JOIN hosts ON hosts.id = tool_runs.hostId " +
        "WHERE hosts.networkId = :networkId OR tool_runs.hostId IS NULL " +
        "ORDER BY tool_runs.startedAt DESC, tool_runs.id DESC LIMIT :limit")
    fun recentForNetwork(networkId: Long, limit: Int): Flow<List<NetworkRun>>
}
