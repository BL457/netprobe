package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity

/** Read-only figures for the radar (Radar spec section 9). [hosts] and [rows] are the unfiltered lists. */
data class HomeStats(val lastScanAt: Long?, val totalHosts: Int, val otherSubnetHosts: Int) {
    companion object {
        /** [finishedAt] is this session's scan finish time; it stands in when no stored host carries a discovery run (zero hosts found). */
        fun of(hosts: List<HostEntity>, rows: List<HostRow>, finishedAt: Long? = null): HomeStats = HomeStats(
            lastScanAt = hosts.maxOfOrNull { it.lastDiscoveryRun }?.takeIf { it > 0 } ?: finishedAt,
            totalHosts = hosts.size,
            otherSubnetHosts = rows.count { it.offSubnet && !it.stale },
        )
    }
}

/** The completion time lives in view-model state, so a Detail round trip mid-scan still measures the true duration (spec 3.4). */
object ScanFinish {
    fun next(wasScanning: Boolean, scanning: Boolean, previous: Long?, now: Long): Long? = when {
        scanning -> null
        wasScanning -> now
        else -> previous
    }

    /** A scan the network change cancelled never finished on any network: the controller's NETWORK_CHANGED state yields no finish time. */
    fun fromController(state: ScanController.State, wasScanning: Boolean, previous: Long?, now: Long): Long? =
        if (state.message == ScanController.NETWORK_CHANGED) null else next(wasScanning, state.scanning, previous, now)

    /** "Scanned in N s" belongs to one network: when the resolved network id changes, the finish time goes (the view model calls this). */
    fun afterNetworkChange(previousNetworkId: Long?, networkId: Long?, finishedAt: Long?): Long? =
        if (previousNetworkId == networkId) finishedAt else null
}
