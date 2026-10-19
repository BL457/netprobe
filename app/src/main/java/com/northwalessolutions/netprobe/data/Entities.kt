package com.northwalessolutions.netprobe.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence

enum class HostStatus { UNMARKED, INTERESTING, INVESTIGATED, IGNORED }

@Entity(tableName = "networks", indices = [Index("signature", unique = true)])
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val signature: String, val displayName: String,
    val gatewayIp: String?, val prefixLength: Int, val ssid: String?, val lastSeen: Long,
)

@Entity(tableName = "hosts", indices = [Index("networkId", "ip", unique = true)])
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val networkId: Long, val ip: String, val mac: String?,
    val reverseDnsName: String?, val mdnsName: String?, val netbiosName: String?, val upnpName: String?, val upnpManufacturer: String?, val upnpModel: String?, val vendor: String?,
    val deviceType: DeviceType, val customName: String?, val identVendor: String?, val identModel: String?, val identOs: String?, val identConfidence: Int, val identReasons: List<String>,
    val snmpSysDescr: String?, val httpTitle: String?, val mdnsTxtJson: String?, val status: HostStatus, val note: String, val isGateway: Boolean,
    val firstSeen: Long, val lastSeen: Long, val lastPortScan: Long?, val lastDiscoveryRun: Long,
    /** MacSource.rank of the stored mac, 0 = none. */
    val macSource: Int = 0, val upnpDeviceType: String? = null, val upnpServer: String? = null, val httpServer: String? = null, val httpAuthRealm: String? = null,
)

@Entity(tableName = "ports", indices = [Index("hostId", "port", unique = true)])
data class PortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val port: Int,
    val serviceName: String, val product: String?, val version: String?, val detail: String?, val confidence: Confidence, val lastSeen: Long,
)

@Entity(tableName = "vuln_cache")
data class VulnCacheEntity(@PrimaryKey val query: String, val json: String, val fetchedAt: Long)
