package com.northwalessolutions.netprobe.ui.home

/**
 * Removed hosts stay out of running scans (spec 5.3 step 2, plan review G2): the radar merges by IP, so it skips (network, ip)
 * pairs until it ends; Quick scan all also skips any host id Remove device recorded, radar running or not.
 */
object RemovedHosts {
    fun skip(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Boolean = (networkId to ip) in removed
    fun added(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>> = removed + (networkId to ip)
    fun without(removed: Set<Pair<Long, String>>, networkId: Long, ip: String): Set<Pair<Long, String>> = removed - (networkId to ip)
    fun skipQuickScan(removedIds: Set<Long>, removedPairs: Set<Pair<Long, String>>, networkId: Long, hostId: Long, ip: String): Boolean =
        hostId in removedIds || skip(removedPairs, networkId, ip)
}
