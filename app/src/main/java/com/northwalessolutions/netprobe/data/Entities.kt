package com.northwalessolutions.netprobe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence

enum class HostStatus { UNMARKED, INTERESTING, INVESTIGATED, IGNORED }

@Entity(tableName = "networks", indices = [Index("signature", unique = true)])
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val signature: String, val displayName: String,
    val gatewayIp: String?, val prefixLength: Int, val ssid: String?, val lastSeen: Long,
    /** Run id (start time) of the last completed discovery run; seeds stale dimming after a restart (Phase 3A spec 5.5). */
    val lastRunAt: Long? = null,
    /** Time of a pending "Forget this network": ensure() never adopts or matches the row until Undo or the commit (spec 5.3). */
    val forgotten: Long? = null,
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
    /** The Director's own type, vendor and model (spec 4.1); no scan writes these. The name override stays in customName. */
    val userType: DeviceType? = null, val userVendor: String? = null, val userModel: String? = null,
    /** Software names joined by a newline, written with the identity (spec 3.6). */
    val identSoftware: String? = null,
    /**
     * 3C spec 7.1: a manual tool target nothing has discovered. Hidden from every device list and count (HostDao.forNetwork); a scan or a
     * heard packet finding the IP clears it (HostDao.updateScan), keeping the row's tool history. SQL default 0, so MIGRATION_3_4 rewrites no row.
     */
    @ColumnInfo(defaultValue = "0") val manualOnly: Boolean = false,
)

@Entity(tableName = "ports", indices = [Index("hostId", "port", unique = true)])
data class PortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val port: Int,
    val serviceName: String, val product: String?, val version: String?, val detail: String?, val confidence: Confidence, val lastSeen: Long,
    /** Port evidence (spec 3.1): page title, full Server header, auth realm, X-Powered-By, certificate subject CN and O. */
    val title: String? = null, val server: String? = null, val realm: String? = null, val poweredBy: String? = null,
    val certCn: String? = null, val certOrg: String? = null,
    /** Phase 3E (schema 5): the software stack the HTTP rules named, its role, the cpe_map product and the probe or rule id that settled the name. */
    val stack: String? = null, val stackRole: String? = null, val cpe: String? = null, val probe: String? = null,
)

@Entity(tableName = "vuln_cache")
data class VulnCacheEntity(@PrimaryKey val query: String, val json: String, val fetchedAt: Long)

/** A tool run's lifecycle (spec 7.1); Room stores it as TEXT by its name, as HostStatus does. */
enum class ToolRunStatus { RUNNING, DONE, STOPPED, FAILED, INTERRUPTED }

/** One follow-up tool run on one port of one host (spec 7.1). [port] is the target port, 161 for the SNMP walk. */
@Entity(tableName = "tool_runs",
    foreignKeys = [ForeignKey(entity = HostEntity::class, parentColumns = ["id"], childColumns = ["hostId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("hostId", "toolId", "port", "startedAt")])
data class ToolRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val hostId: Long, val toolId: String, val port: Int,
    val startedAt: Long, val finishedAt: Long?, val status: ToolRunStatus, val optionsJson: String, val summary: String,
    /** 3C spec 4.5: how a manual run left the phone, a ToolRoute name ("WIFI", "ETHERNET", "OTHER"); null for Device-screen and pre-4 runs. */
    val route: String? = null,
)

/** One finding of a run (spec 7.1). [kind] is TEXT, parsed leniently; [extra] is a JSON object or null. */
@Entity(tableName = "tool_findings",
    foreignKeys = [ForeignKey(entity = ToolRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("runId")])
data class ToolFindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val kind: String,
    val value: String, val detail: String?, val extra: String?,
)
