package com.northwalessolutions.netprobe.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.MacSource
import com.northwalessolutions.netprobe.engine.discovery.MdnsType
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress

private fun parseTxt(json: String?): Map<String, Map<String, String>> =
    json?.let { runCatching { Json.decodeFromString<Map<String, Map<String, String>>>(it) }.getOrNull() }.orEmpty()
        .mapKeys { (k, _) -> MdnsType.normalise(k) }   // rows written before the NSD leading-dot fix stored "._type._tcp"

/** Rank 1 is shared by NAME and EUI64; they merge identically, so NAME stands in for both. */
private fun macSourceOf(rank: Int): MacSource? = when (rank) {
    1 -> MacSource.NAME; 2 -> MacSource.UPNP_UDN; 3 -> MacSource.NETBIOS; 4 -> MacSource.NEIGH; else -> null
}

/** A host row and its port rows, read together, for Remove device and its Undo (spec 5.3). */
data class HostSnapshot(val host: HostEntity, val ports: List<PortEntity>)

class NetworkRepository(private val db: NetProbeDb, private val onPurged: (Long) -> Unit = {}) {
    private val purge = PurgeOnce()

    /**
     * Finds or creates the row for [env]. Identity is gateway MAC + gateway IP + prefix ([NetworkSignature]).
     * On a signature miss, a single row with the same gateway IP and prefix is adopted (rows keyed by the old BSSID rule,
     * rows recorded while the MAC was unreadable, and the location-granted case), unless its stored gateway host has a
     * different MAC: that is another site on the same addressing, and its inventory must not be merged.
     * A row with a pending Forget is never matched or adopted: while any row on this gateway and prefix is forgotten, null is
     * returned and nothing is inserted, so the forgotten row and its id stay untouched and no second row appears for the same
     * network (spec 5.3, Gruff plan review G14). Before the first lookup of the process, a Forget left pending by a killed process
     * is purged once (spec 5.3, G1): the purge happens here, never when the container is built.
     */
    suspend fun ensure(env: NetworkEnv, gatewayMac: String?): NetworkEntity? {
        purge.run { purgeForgotten(onPurged) }
        return resolve(env, gatewayMac)
    }

    private suspend fun resolve(env: NetworkEnv, gatewayMac: String?): NetworkEntity? = db.withTransaction {
        val sig = NetworkSignature.of(env, gatewayMac); val now = System.currentTimeMillis()
        val gw = env.gateway?.hostAddress
        val found = db.networks().bySignature(sig)
        if (found != null) {
            if (found.forgotten != null) return@withTransaction null
            db.networks().touch(found.id, now, env.ssid)
            return@withTransaction found.copy(lastSeen = now, ssid = env.ssid ?: found.ssid)
        }
        val sameAddressing = db.networks().byGateway(gw, env.prefixLength)
        if (sameAddressing.any { it.forgotten != null }) return@withTransaction null
        val mac = gatewayMac?.lowercase()
        val adoptable = sameAddressing.filter { row ->
            val stored = if (mac != null && gw != null) db.networks().gatewayHostMac(row.id, gw)?.lowercase() else null
            stored == null || stored == mac
        }
        if (adoptable.size == 1) {
            val a = adoptable.first(); val ssid = env.ssid ?: a.ssid; val name = env.ssid ?: a.displayName
            db.networks().adopt(a.id, sig, ssid, name, now)
            return@withTransaction a.copy(signature = sig, ssid = ssid, displayName = name, lastSeen = now)
        }
        val n = NetworkEntity(signature = sig, displayName = env.ssid ?: gw ?: "Network", gatewayIp = gw, prefixLength = env.prefixLength, ssid = env.ssid, lastSeen = now)
        try {
            n.copy(id = db.networks().insert(n))
        } catch (e: SQLiteConstraintException) {
            val raced = db.networks().bySignature(sig) ?: throw e
            if (raced.forgotten != null) null else { db.networks().touch(raced.id, now, env.ssid); raced.copy(lastSeen = now) }
        }
    }
    suspend fun byId(id: Long): NetworkEntity? = db.networks().byId(id)
    /** Only completed runs are persisted (spec 5.5, Gruff Q1); the caller decides. */
    suspend fun markRun(networkId: Long, runId: Long) = db.networks().markRun(networkId, runId)
    suspend fun setForgotten(networkId: Long, at: Long?) = db.networks().setForgotten(networkId, at)
    /** Ports of the network's hosts, then the hosts, then the network, in one transaction; nothing from another network. */
    suspend fun deleteNetworkCascade(networkId: Long) = db.withTransaction {
        db.networks().deletePortsOfNetwork(networkId); db.networks().deleteHostsOfNetwork(networkId); db.networks().deleteNetwork(networkId)
    }
    /** A Forget left pending by a killed process is final (spec 5.3); ensure() runs this once. [onDeleted] clears that network's manual ranges. */
    suspend fun purgeForgotten(onDeleted: (Long) -> Unit) {
        for (id in db.networks().forgottenIds()) { deleteNetworkCascade(id); onDeleted(id) }
    }
}

class HostRepository(private val db: NetProbeDb) {
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().forNetwork(networkId)
    fun host(hostId: Long): Flow<HostEntity?> = db.hosts().byId(hostId)
    suspend fun byIdOnce(id: Long): HostEntity? = db.hosts().byIdOnce(id)
    suspend fun findOnce(networkId: Long, ip: String): HostEntity? = db.hosts().find(networkId, ip)
    suspend fun mdnsTypesFor(id: Long): Set<String> = byIdOnce(id)?.let { parseTxt(it.mdnsTxtJson).keys } ?: emptySet()

    /** Identification input rebuilt from the stored columns, so each new emission is judged on the merged picture. */
    fun toDiscovered(e: HostEntity): DiscoveredHost {
        val txt = parseTxt(e.mdnsTxtJson)
        return DiscoveredHost(
            ip = InetAddress.getByName(e.ip) as Inet4Address, mac = e.mac, macSource = macSourceOf(e.macSource), reverseDnsName = e.reverseDnsName, mdnsName = e.mdnsName, netbiosName = e.netbiosName,
            upnpFriendlyName = e.upnpName, upnpManufacturer = e.upnpManufacturer, upnpModel = e.upnpModel, upnpDeviceType = e.upnpDeviceType, upnpServer = e.upnpServer,
            mdnsServiceTypes = txt.keys, mdnsTxt = txt, mdnsInstanceNames = setOfNotNull(e.mdnsName),
            snmpSysDescr = e.snmpSysDescr, httpTitle = e.httpTitle, httpServer = e.httpServer, httpAuthRealm = e.httpAuthRealm, isGateway = e.isGateway,
        )
    }

    /** Read and write are one transaction, and the write lists scan columns only, so annotations written concurrently are never lost. */
    suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val ip = h.ip.hostAddress
        val existing = db.hosts().find(networkId, ip)
        val newRank = h.macSource?.rank ?: 0
        if (existing == null) {
            val txt = h.mdnsTxt.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            try {
                return@withTransaction db.hosts().insert(HostEntity(networkId = networkId, ip = ip, mac = h.mac, reverseDnsName = h.reverseDnsName, mdnsName = h.mdnsName,
                    netbiosName = h.netbiosName, upnpName = h.upnpFriendlyName, upnpManufacturer = h.upnpManufacturer, upnpModel = h.upnpModel, vendor = h.upnpManufacturer,
                    deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
                    snmpSysDescr = h.snmpSysDescr, httpTitle = h.httpTitle, mdnsTxtJson = txt, status = HostStatus.UNMARKED, note = "", isGateway = h.isGateway,
                    firstSeen = now, lastSeen = now, lastPortScan = null, lastDiscoveryRun = runId,
                    macSource = newRank, upnpDeviceType = h.upnpDeviceType, upnpServer = h.upnpServer, httpServer = h.httpServer, httpAuthRealm = h.httpAuthRealm))
            } catch (e: SQLiteConstraintException) {
                // Lost an insert race on (networkId, ip): fall through to the update path on the winner's row.
                val raced = db.hosts().find(networkId, ip) ?: throw e
                return@withTransaction applyScan(raced, h, newRank, now, runId)
            }
        }
        applyScan(existing, h, newRank, now, runId)
    }

    private suspend fun applyScan(existing: HostEntity, h: DiscoveredHost, newRank: Int, now: Long, runId: Long): Long {
        // MAC precedence is by source rank (netlink > NetBIOS > UPnP > names/EUI-64), not arrival order; equal rank refreshes.
        val takeMac = h.mac != null && (existing.mac == null || newRank >= existing.macSource)
        // mDNS TXT unions per service type: a later emission (for example the _device-info query) must not drop earlier types.
        val txt = (parseTxt(existing.mdnsTxtJson) + h.mdnsTxt).takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
        db.hosts().updateScan(existing.id, mac = if (takeMac) h.mac else existing.mac, macSource = if (takeMac) newRank else existing.macSource,
            rdns = h.reverseDnsName ?: existing.reverseDnsName, mdnsName = h.mdnsName ?: existing.mdnsName,
            netbiosName = h.netbiosName ?: existing.netbiosName, upnpName = h.upnpFriendlyName ?: existing.upnpName, upnpManufacturer = h.upnpManufacturer ?: existing.upnpManufacturer,
            upnpModel = h.upnpModel ?: existing.upnpModel, vendor = h.upnpManufacturer ?: existing.vendor, snmpSysDescr = h.snmpSysDescr ?: existing.snmpSysDescr,
            httpTitle = h.httpTitle ?: existing.httpTitle, mdnsTxtJson = txt, isGateway = existing.isGateway || h.isGateway, lastSeen = now, runId = runId,
            upnpDeviceType = h.upnpDeviceType ?: existing.upnpDeviceType, upnpServer = h.upnpServer ?: existing.upnpServer,
            httpServer = h.httpServer ?: existing.httpServer, httpAuthRealm = h.httpAuthRealm ?: existing.httpAuthRealm)
        return existing.id
    }

    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
        db.hosts().updateIdentity(hostId, identity.vendor, identity.model, identity.os, identity.type, identity.confidence, identity.reasons,
            SoftwareColumn.encode(identity.software))
    }
    /** After a port scan (spec 3.3): true when the identity was written, false when the stored one is more confident. */
    suspend fun setIdentityIfNotWeaker(hostId: Long, identity: DeviceIdentity): Boolean =
        db.hosts().updateIdentityIfNotWeaker(hostId, identity.vendor, identity.model, identity.os, identity.type, identity.confidence, identity.reasons,
            SoftwareColumn.encode(identity.software)) > 0
    suspend fun setStatus(hostId: Long, status: HostStatus) = db.hosts().setStatus(hostId, status)
    suspend fun setNote(hostId: Long, note: String) = db.hosts().setNote(hostId, note)
    suspend fun setOverrides(hostId: Long, edits: DeviceEdits) = db.hosts().setOverrides(hostId, edits.name, edits.type, edits.vendor, edits.model)
    suspend fun snapshot(hostId: Long): HostSnapshot? = db.withTransaction {
        db.hosts().byIdOnce(hostId)?.let { HostSnapshot(it, db.ports().forHostOnce(hostId)) }
    }
    suspend fun delete(s: HostSnapshot) = db.withTransaction { db.ports().deletePortsForHost(s.host.id); db.hosts().deleteHost(s.host.id) }
    /**
     * Undo of Remove device (spec 5.3 step 4, Gruff plan review G2): the same rows with the same ids, and nothing a late scan wrote
     * can block it. In one transaction: a row a scan re-inserted at the same (networkId, ip) goes with its ports (scan-derived only),
     * any rows under the snapshot's id go, then the snapshot host and ports are inserted. The Director's mark, note and overrides
     * come back exactly; ABORT stays only as a backstop.
     */
    suspend fun restore(s: HostSnapshot) = db.withTransaction {
        db.hosts().find(s.host.networkId, s.host.ip)?.let { stray -> db.ports().deletePortsForHost(stray.id); db.hosts().deleteHost(stray.id) }
        db.ports().deletePortsForHost(s.host.id); db.hosts().deleteHost(s.host.id)
        db.hosts().insertAll(listOf(s.host)); db.ports().insertAll(s.ports)
    }
}

class PortRepository(private val db: NetProbeDb) {
    fun portsForHost(hostId: Long): Flow<List<PortEntity>> = db.ports().forHost(hostId)
    fun counts(): Flow<Map<Long, Int>> = db.ports().counts().map { rows -> rows.associate { it.hostId to it.n } }
    suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo): Unit = db.withTransaction {
        val now = System.currentTimeMillis()
        val e = db.ports().find(hostId, port)
        if (e == null) {
            try { db.ports().insert(PortMapping.newRow(hostId, port, info, now)) } catch (ex: SQLiteConstraintException) {
                val raced = db.ports().find(hostId, port) ?: throw ex
                db.ports().update(PortMapping.merge(raced, info, now))
            }
        } else db.ports().update(PortMapping.merge(e, info, now))
    }
    /** Scan paths write through this: the row lands only while its host row exists, checked in the same transaction, so a host removed mid-scan never gets orphan ports (G2). */
    suspend fun upsertOpenIfHostExists(hostId: Long, port: Int, info: ServiceInfo): Boolean = db.withTransaction {
        if (db.hosts().byIdOnce(hostId) == null) return@withTransaction false
        upsertOpen(hostId, port, info)
        true
    }
    /** Stored open ports of a host as identification input, all eleven fields, so a rediscovery never forgets earlier findings. */
    suspend fun servicesFor(hostId: Long): Map<Int, ServiceInfo> = db.ports().forHostOnce(hostId).associate { it.port to it.toServiceInfo() }
    suspend fun markScanned(hostId: Long) = db.hosts().markScanned(hostId, System.currentTimeMillis())
}
class VulnCacheRepository(private val db: NetProbeDb) {
    suspend fun get(query: String, maxAgeMs: Long): VulnCacheEntity? = db.vulnCache().get(query)?.takeIf { System.currentTimeMillis() - it.fetchedAt <= maxAgeMs }
    suspend fun getAny(query: String): VulnCacheEntity? = db.vulnCache().get(query)
    suspend fun put(query: String, json: String) = db.vulnCache().put(VulnCacheEntity(query, json, System.currentTimeMillis()))
    suspend fun clear() = db.vulnCache().clear()
}

/** Pure (host, tool, port) selection for hints (spec 4.7, Review Focus 4): newest DONE per group, else the newest STOPPED or INTERRUPTED. */
object ToolHintsRule {
    fun pick(finished: List<ToolRunEntity>): List<Long> {
        // [finished] is already newest-first (startedAt DESC, id DESC).
        val byGroup = finished.groupBy { Triple(it.hostId, it.toolId, it.port) }
        return byGroup.values.mapNotNull { runs -> (runs.firstOrNull { it.status == ToolRunStatus.DONE } ?: runs.firstOrNull())?.id }
    }
}

class ToolRepository(private val db: NetProbeDb) : com.northwalessolutions.netprobe.engine.tools.ToolStore {
    private val dao get() = db.tools()
    private val _ready = CompletableDeferred<Unit>()
    /** Completed by [markInterrupted]; ToolRunner.start awaits it so a run started at launch is never marked INTERRUPTED. */
    override val ready: Deferred<Unit> = _ready

    override suspend fun startRun(hostId: Long, toolId: String, port: Int, startedAt: Long, optionsJson: String): Long = db.withTransaction {
        val id = dao.insertRun(ToolRunEntity(hostId = hostId, toolId = toolId, port = port, startedAt = startedAt,
            finishedAt = null, status = ToolRunStatus.RUNNING, optionsJson = optionsJson, summary = ""))
        dao.prune(hostId, toolId, port, KEEP_RUNS_PER_TOOL)   // per (host, tool, port), ruling R5: port 80's runs never prune port 5000's
        id
    }
    override suspend fun finish(runId: Long, finishedAt: Long?, status: ToolRunStatus, summary: String) = dao.finish(runId, finishedAt, status, summary)
    override suspend fun addFinding(runId: Long, kind: String, value: String, detail: String?, extra: String?): Long =
        dao.insertFinding(ToolFindingEntity(runId = runId, kind = kind, value = value, detail = detail, extra = extra))
    /** Process death left a RUNNING row: mark each INTERRUPTED with its finding count, in one transaction, then complete [ready] (spec 4.4 rule 5). */
    override suspend fun markInterrupted(summaryOf: (found: Int) -> String) {
        try {
            db.withTransaction {
                for (r in dao.runningOnce()) dao.finish(r.id, null, ToolRunStatus.INTERRUPTED, summaryOf(dao.findingCount(r.id)))
            }
        } finally { _ready.complete(Unit) }   // a failure here must never leave ToolRunner.start waiting forever
    }
    fun runsForHost(hostId: Long): Flow<List<ToolRunEntity>> = dao.runsForHost(hostId)
    fun run(id: Long): Flow<ToolRunEntity?> = dao.run(id)
    fun findings(runId: Long): Flow<List<ToolFindingEntity>> = dao.findings(runId)
    suspend fun findingsOnce(runId: Long): List<ToolFindingEntity> = dao.findingsOnce(runId)
    override suspend fun findingCount(runId: Long): Int = dao.findingCount(runId)
    /** One UPDATE of a run's optionsJson, used when a tool records a value it learns mid-run (methodUsed, spec 7.1). */
    override suspend fun setOptions(runId: Long, json: String) = dao.setOptions(runId, json)
    /** Hosts with any tool run: the radar asks hintsFor only for these, so an emission adds no query for the rest (plan review G13). */
    suspend fun hostsWithRuns(): Set<Long> = dao.hostsWithRuns().toSet()
    /** Hints of one run per (host, tool, port) by [ToolHintsRule]. */
    suspend fun hintsFor(hostId: Long): List<String> {
        val ids = ToolHintsRule.pick(dao.finishedRunsOnce(hostId))
        return if (ids.isEmpty()) emptyList() else dao.hintsOf(ids)
    }

    /**
     * Remove device snapshots a host's tool rows into SQLite TEMP tables before the cascade drops them (spec 7.4), so Undo can bring
     * them back. TEMP tables live on Room's single writer connection and die with the process, matching 3A's memory-only Undo lifetime.
     * [token] is digits only, so the host id list and the table names can never be injected.
     */
    suspend fun snapshot(hostIds: List<Long>): String = db.withTransaction {
        val token = tokenSeq.incrementAndGet().toString()
        val inList = hostIds.joinToString(",") { it.toString() }.ifEmpty { "NULL" }
        exec("CREATE TEMP TABLE undo_runs_$token AS SELECT * FROM tool_runs WHERE hostId IN ($inList)")
        exec("CREATE TEMP TABLE undo_findings_$token AS SELECT * FROM tool_findings WHERE runId IN (SELECT id FROM undo_runs_$token)")
        token
    }
    suspend fun restore(token: String) = db.withTransaction {
        exec("INSERT INTO tool_runs SELECT * FROM undo_runs_$token")
        exec("INSERT INTO tool_findings SELECT * FROM undo_findings_$token")
        drop(token)
    }
    suspend fun dropSnapshot(token: String) = db.withTransaction { drop(token) }
    private fun drop(token: String) { exec("DROP TABLE IF EXISTS undo_findings_$token"); exec("DROP TABLE IF EXISTS undo_runs_$token") }
    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    /** [KEEP_RUNS_PER_TOOL] is kept per (host, tool, port) (ruling R5); the name is the plan's. */
    companion object { const val KEEP_RUNS_PER_TOOL = 10; private val tokenSeq = java.util.concurrent.atomic.AtomicLong(0) }
}
