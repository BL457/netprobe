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

class NetworkRepository(private val db: NetProbeDb) {
    /**
     * Finds or creates the row for [env]. Identity is gateway MAC + gateway IP + prefix ([NetworkSignature]).
     * On a signature miss, a single row with the same gateway IP and prefix is adopted (rows keyed by the old BSSID rule,
     * rows recorded while the MAC was unreadable, and the location-granted case), unless its stored gateway host has a
     * different MAC: that is another site on the same addressing, and its inventory must not be merged.
     */
    suspend fun ensure(env: NetworkEnv, gatewayMac: String?): NetworkEntity = db.withTransaction {
        val sig = NetworkSignature.of(env, gatewayMac); val now = System.currentTimeMillis()
        val gw = env.gateway?.hostAddress
        val found = db.networks().bySignature(sig)
        if (found != null) { db.networks().touch(found.id, now, env.ssid); return@withTransaction found.copy(lastSeen = now, ssid = env.ssid ?: found.ssid) }
        val mac = gatewayMac?.lowercase()
        val adoptable = db.networks().byGateway(gw, env.prefixLength).filter { row ->
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
            db.networks().touch(raced.id, now, env.ssid)
            raced.copy(lastSeen = now)
        }
    }
}

class HostRepository(private val db: NetProbeDb) {
    fun hostsForNetwork(networkId: Long): Flow<List<HostEntity>> = db.hosts().forNetwork(networkId)
    fun host(hostId: Long): Flow<HostEntity?> = db.hosts().byId(hostId)
    suspend fun byIdOnce(id: Long): HostEntity? = db.hosts().byIdOnce(id)
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
        db.hosts().updateIdentity(hostId, identity.vendor, identity.model, identity.os, identity.type, identity.confidence, identity.reasons)
    }
    suspend fun setStatus(hostId: Long, status: HostStatus) = db.hosts().setStatus(hostId, status)
    suspend fun setNote(hostId: Long, note: String) = db.hosts().setNote(hostId, note)
    suspend fun setCustomName(hostId: Long, name: String?) = db.hosts().setCustomName(hostId, name?.trim()?.takeIf { it.isNotEmpty() })
}

class PortRepository(private val db: NetProbeDb) {
    fun portsForHost(hostId: Long): Flow<List<PortEntity>> = db.ports().forHost(hostId)
    fun counts(): Flow<Map<Long, Int>> = db.ports().counts().map { rows -> rows.associate { it.hostId to it.n } }
    suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo): Unit = db.withTransaction {
        val now = System.currentTimeMillis()
        val e = db.ports().find(hostId, port)
        val fresh = PortEntity(hostId = hostId, port = port, serviceName = info.name, product = info.product, version = info.version, detail = info.detail, confidence = info.confidence, lastSeen = now)
        if (e == null) {
            try { db.ports().insert(fresh) } catch (ex: SQLiteConstraintException) {
                val raced = db.ports().find(hostId, port) ?: throw ex
                db.ports().update(mergePort(raced, info, now))
            }
        } else db.ports().update(mergePort(e, info, now))
    }
    private fun mergePort(e: PortEntity, info: ServiceInfo, now: Long) =
        e.copy(serviceName = info.name, product = info.product ?: e.product, version = info.version ?: e.version, detail = info.detail ?: e.detail, confidence = info.confidence, lastSeen = now)
    /** Stored open ports of a host as identification input, so a rediscovery or narrow rescan never forgets earlier findings. */
    suspend fun servicesFor(hostId: Long): Map<Int, ServiceInfo> =
        db.ports().forHostOnce(hostId).associate { it.port to ServiceInfo(it.serviceName, it.product, it.version, it.detail, it.confidence) }
    suspend fun markScanned(hostId: Long) = db.hosts().markScanned(hostId, System.currentTimeMillis())
}
class VulnCacheRepository(private val db: NetProbeDb) {
    suspend fun get(query: String, maxAgeMs: Long): VulnCacheEntity? = db.vulnCache().get(query)?.takeIf { System.currentTimeMillis() - it.fetchedAt <= maxAgeMs }
    suspend fun getAny(query: String): VulnCacheEntity? = db.vulnCache().get(query)
    suspend fun put(query: String, json: String) = db.vulnCache().put(VulnCacheEntity(query, json, System.currentTimeMillis()))
    suspend fun clear() = db.vulnCache().clear()
}
