package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import java.net.Inet4Address
import java.net.InetAddress

object HomeFakes {
    fun v4(s: String) = InetAddress.getByName(s) as Inet4Address
    val env = NetworkEnv(v4("10.0.3.242"), 24, v4("10.0.3.1"), emptyList(), "Home", null, Transport.WIFI)

    /** The one place a fake HostRow is built; Task 16 changes the subnet fields here only. */
    fun row(id: Long, ip: String, name: String = "host$id", identity: String = "Synology DS220+", type: DeviceType = DeviceType.NAS, open: Int = 0,
            status: HostStatus = HostStatus.UNMARKED, gateway: Boolean = false, stale: Boolean = false, mac: String? = null, other: String? = null) =
        HostRow(id, ip, name, identity, type, open, status, gateway, stale, mac, other ?: "10.0.3.0/24", other != null)

    fun rows(local: Int, other: Int = 0): List<HostRow> =
        (1..local).map { row(it.toLong(), "10.0.3.$it", name = if (it == 1) "Ubiquiti gateway" else "host$it", type = if (it == 1) DeviceType.ROUTER else DeviceType.NAS, gateway = it == 1) } +
        (1..other).map { row(100L + it, "10.0.4.${it + 10}", type = DeviceType.IOT, other = "10.0.4.0/24") }

    fun settled(rows: List<HostRow> = rows(20), filter: StatusFilter = StatusFilter.ALL, query: String = "", message: String? = null) =
        HomeUiState(env = env, rows = rows, filter = filter, query = query, networkName = "Home", message = message, lastScanAt = 1_000L, totalHosts = rows.size, groups = HomeGroups.of(rows))

    fun scanning(rows: List<HostRow> = rows(5), filter: StatusFilter = StatusFilter.ALL, query: String = "") =
        settled(rows, filter, query).copy(scanning = true, status = DiscoveryStatus(probed = 171, total = 254, found = rows.size), scanStartedAt = 1_000L)

    fun hero(env: NetworkEnv? = this.env) = HomeUiState(env = env, networkName = env?.ssid)
}

class RecordingHomeActions {
    var scans = 0; var stops = 0; var dismissals = 0; val opened = mutableListOf<Long>(); val events = mutableListOf<String>()
    val actions: HomeActions get() = HomeActions(onScan = { scans++; events += "scan" }, onStop = { stops++ }, onOpenHost = { opened += it },
        onDismissMessage = { dismissals++; events += "dismiss" })
}
