package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
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

class NetworkRepository(private val db: NetProbeDb) {
    suspend fun ensure(env: NetworkEnv): NetworkEntity {
        val sig = NetworkSignature.of(env); val now = System.currentTimeMillis()
        db.networks().bySignature(sig)?.let { db.networks().touch(it.id, now, env.ssid); return it.copy(lastSeen = now) }
        val n = NetworkEntity(signature = sig, displayName = env.ssid ?: env.gateway?.hostAddress ?: "Network", gatewayIp = env.gateway?.hostAddress, prefixLength = env.prefixLength, ssid = env.ssid, lastSeen = now)
        return n.copy(id = db.networks().insert(n))
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
            ip = InetAddress.getByName(e.ip) as Inet4Address, mac = e.mac, reverseDnsName = e.reverseDnsName, mdnsName = e.mdnsName, netbiosName = e.netbiosName,
            upnpFriendlyName = e.upnpName, upnpManufacturer = e.upnpManufacturer, upnpModel = e.upnpModel, upnpDeviceType = e.upnpDeviceType, upnpServer = e.upnpServer,
            mdnsServiceTypes = txt.keys, mdnsTxt = txt, mdnsInstanceNames = setOfNotNull(e.mdnsName),
            snmpSysDescr = e.snmpSysDescr, httpTitle = e.httpTitle, httpServer = e.httpServer, httpAuthRealm = e.httpAuthRealm, isGateway = e.isGateway,
        )
    }

    suspend fun upsertDiscovered(networkId: Long, h: DiscoveredHost, runId: Long): Long {
        val now = System.currentTimeMillis()
        val existing = db.hosts().find(networkId, h.ip.hostAddress)
        val newRank = h.macSource?.rank ?: 0
        if (existing == null) {
            val txt = h.mdnsTxt.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
            return db.hosts().insert(HostEntity(networkId = networkId, ip = h.ip.hostAddress, mac = h.mac, reverseDnsName = h.reverseDnsName, mdnsName = h.mdnsName,
                netbiosName = h.netbiosName, upnpName = h.upnpFriendlyName, upnpManufacturer = h.upnpManufacturer, upnpModel = h.upnpModel, vendor = h.upnpManufacturer,
                deviceType = DeviceType.UNKNOWN, customName = null, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
                snmpSysDescr = h.snmpSysDescr, httpTitle = h.httpTitle, mdnsTxtJson = txt, status = HostStatus.UNMARKED, note = "", isGateway = h.isGateway,
                firstSeen = now, lastSeen = now, lastPortScan = null, lastDiscoveryRun = runId,
                macSource = newRank, upnpDeviceType = h.upnpDeviceType, upnpServer = h.upnpServer, httpServer = h.httpServer, httpAuthRealm = h.httpAuthRealm))
        }
        // MAC precedence is by source rank (netlink > NetBIOS > UPnP > names/EUI-64), not arrival order; equal rank refreshes.
        val takeMac = h.mac != null && (existing.mac == null || newRank >= existing.macSource)
        // mDNS TXT unions per service type: a later emission (for example the _device-info query) must not drop earlier types.
        val txt = (parseTxt(existing.mdnsTxtJson) + h.mdnsTxt).takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }
        // Scan data refreshes; annotations (customName, status, note) are never overwritten.
        db.hosts().update(existing.copy(mac = if (takeMac) h.mac else existing.mac, macSource = if (takeMac) newRank else existing.macSource,
            reverseDnsName = h.reverseDnsName ?: existing.reverseDnsName, mdnsName = h.mdnsName ?: existing.mdnsName,
            netbiosName = h.netbiosName ?: existing.netbiosName, upnpName = h.upnpFriendlyName ?: existing.upnpName, upnpManufacturer = h.upnpManufacturer ?: existing.upnpManufacturer,
            upnpModel = h.upnpModel ?: existing.upnpModel, vendor = h.upnpManufacturer ?: existing.vendor, snmpSysDescr = h.snmpSysDescr ?: existing.snmpSysDescr,
            httpTitle = h.httpTitle ?: existing.httpTitle, mdnsTxtJson = txt, isGateway = existing.isGateway || h.isGateway, lastSeen = now, lastDiscoveryRun = runId,
            upnpDeviceType = h.upnpDeviceType ?: existing.upnpDeviceType, upnpServer = h.upnpServer ?: existing.upnpServer,
            httpServer = h.httpServer ?: existing.httpServer, httpAuthRealm = h.httpAuthRealm ?: existing.httpAuthRealm))
        return existing.id
    }
    suspend fun setIdentity(hostId: Long, identity: DeviceIdentity) {
        val h = db.hosts().byIdOnce(hostId) ?: return
        db.hosts().update(h.copy(identVendor = identity.vendor, identModel = identity.model, identOs = identity.os, deviceType = identity.type, identConfidence = identity.confidence, identReasons = identity.reasons))
    }
    suspend fun setStatus(hostId: Long, status: HostStatus) = db.hosts().setStatus(hostId, status)
    suspend fun setNote(hostId: Long, note: String) = db.hosts().setNote(hostId, note)
    suspend fun setCustomName(hostId: Long, name: String?) = db.hosts().setCustomName(hostId, name?.trim()?.takeIf { it.isNotEmpty() })
}

class PortRepository(private val db: NetProbeDb) {
    fun portsForHost(hostId: Long): Flow<List<PortEntity>> = db.ports().forHost(hostId)
    fun counts(): Flow<Map<Long, Int>> = db.ports().counts().map { rows -> rows.associate { it.hostId to it.n } }
    suspend fun upsertOpen(hostId: Long, port: Int, info: ServiceInfo) {
        val now = System.currentTimeMillis()
        val e = db.ports().find(hostId, port)
        if (e == null) db.ports().insert(PortEntity(hostId = hostId, port = port, serviceName = info.name, product = info.product, version = info.version, detail = info.detail, confidence = info.confidence, lastSeen = now))
        else db.ports().update(e.copy(serviceName = info.name, product = info.product ?: e.product, version = info.version ?: e.version, detail = info.detail ?: e.detail, confidence = info.confidence, lastSeen = now))
    }
    suspend fun markScanned(hostId: Long) = db.hosts().markScanned(hostId, System.currentTimeMillis())
}
class VulnCacheRepository(private val db: NetProbeDb) {
    suspend fun get(query: String, maxAgeMs: Long): VulnCacheEntity? = db.vulnCache().get(query)?.takeIf { System.currentTimeMillis() - it.fetchedAt <= maxAgeMs }
    suspend fun getAny(query: String): VulnCacheEntity? = db.vulnCache().get(query)
    suspend fun put(query: String, json: String) = db.vulnCache().put(VulnCacheEntity(query, json, System.currentTimeMillis()))
    suspend fun clear() = db.vulnCache().clear()
}
