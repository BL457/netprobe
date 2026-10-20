package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.netenv.SubnetMath
import com.northwalessolutions.netprobe.ui.tools.ToolCopy

data class HostGroup(val subnet: String, val local: Boolean, val rows: List<HostRow>)

object RowText {
    private const val GAP = "  "
    private const val UNKNOWN_PREFIX = "Unknown device"

    /** Known identity: not blank and not the "Unknown device" fallback. The own-phone row substitutes Build.MODEL (spec 4.3). */
    private fun identity(r: HostRow, ownModel: String?): String? =
        ownModel ?: r.identityLine.trim().takeUnless { it.isEmpty() || it.startsWith(UNKNOWN_PREFIX) }

    /** Line 2: "10.0.3.59  Synology DS220+"; identity, else MAC, else the IP alone. No subnet suffix: the group header carries it. */
    fun dataLine(r: HostRow, ownModel: String?): String {
        val second = identity(r, ownModel) ?: r.mac
        return when {
            r.displayName == r.ip -> second ?: r.ip
            second != null -> r.ip + GAP + second
            else -> r.ip
        }
    }

    /** "Ubiquiti gateway, gateway, 10.0.3.1, 3 open ports", then the mark and the stale note (spec 7.3). */
    fun description(r: HostRow, ownModel: String?): String = buildList {
        add(r.displayName)
        if (r.isGateway) add("gateway")
        add(r.ip)
        if (r.openPorts > 0) add(if (r.openPorts == 1) "1 open port" else "${r.openPorts} open ports")
        if (r.status != HostStatus.UNMARKED) add("marked ${r.status.label()}")
        if (r.stale) add("not seen in the last scan")
        if (r.running) add(ToolCopy.rowSuffix(r.runningIsScan))
    }.joinToString(", ")
}

object GroupHeaders {
    fun text(subnet: String, local: Boolean): String = (if (local) "Home subnet " else "Other subnet ") + subnet
}

object HomeGroups {
    private const val UNSIGNED_MASK = 0xFFFFFFFFL
    private fun networkKey(cidr: String): Long =
        RowRules.parseIpv4(cidr.substringBefore('/'))?.let { SubnetMath.toInt(it).toLong() and UNSIGNED_MASK } ?: Long.MAX_VALUE

    /** Local group first, then other ranges by network address ascending; rows keep their input order (Radar spec 4.2, extended spec 8.1). */
    fun of(rows: List<HostRow>): List<HostGroup> {
        val local = rows.filter { !it.offSubnet }
        val others = rows.filter { it.offSubnet }.groupBy { it.subnet }.entries.sortedBy { networkKey(it.key) }
        return buildList {
            if (local.isNotEmpty()) add(HostGroup(local.first().subnet, true, local))
            others.forEach { (subnet, rs) -> add(HostGroup(subnet, false, rs)) }
        }
    }
}
