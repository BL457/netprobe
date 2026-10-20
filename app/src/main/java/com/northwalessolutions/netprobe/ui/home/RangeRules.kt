package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath

/** Display-time grouping (extended spec 7): nothing is stored, so a changed maxPrefix or manual list regroups at once. */
object RangeRules {
    private const val FALLBACK_PREFIX = 24

    /** scannedRanges[0] is the local swept range; later items are ranges swept in the last run. First match wins. */
    fun groupFor(ip: String, scannedRanges: List<Subnet>, manualRanges: List<Subnet>): Pair<String, Boolean> {
        val a = RowRules.parseIpv4(ip) ?: return ip to false
        val local = scannedRanges.firstOrNull() ?: return SubnetMath.of(a, FALLBACK_PREFIX).toString() to false
        if (local.contains(a)) return local.toString() to false
        (scannedRanges.drop(1) + manualRanges).firstOrNull { it.contains(a) }?.let { return it.toString() to true }
        return SubnetMath.of(a, FALLBACK_PREFIX).toString() to true
    }
}
