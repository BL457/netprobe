package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.neigh.NeighbourEntry
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Subnet
import java.net.Inet4Address

/** Which neighbour entries may give MACs and evidence (extended spec 6.2). The JNI dump has no interface index; the subnet test is the filter. */
object NeighbourRules {
    const val PROXY_ARP_MIN_IPS = 3

    /** [onLinkIps] are every on-link host seen, even when its MAC claim was refused; [macs] and [evidence] only carry trusted entries. */
    data class Outcome(val macs: Map<String, String>, val evidence: List<Inet4Address>, val onLinkIps: List<Inet4Address>)

    fun apply(entries: List<NeighbourEntry>, trueSubnet: Subnet, swept: Subnet, gateway: Inet4Address?): Outcome {
        val onLink = entries.mapNotNull { e -> Ipv4.parse(e.ip)?.let { it to e.mac.lowercase() } }.filter { (ip, _) -> trueSubnet.contains(ip) }
        val ipsPerMac = onLink.groupBy({ it.second }, { it.first }).mapValues { (_, ips) -> ips.distinct().size }
        val gatewayMac = gateway?.let { g -> onLink.firstOrNull { it.first == g }?.second }
        val usable = onLink.filter { (ip, mac) -> ip == gateway || ((ipsPerMac[mac] ?: 0) < PROXY_ARP_MIN_IPS && mac != gatewayMac) }
        return Outcome(usable.associate { (ip, mac) -> ip.hostAddress to mac }, usable.map { it.first }.filter { !swept.contains(it) }, onLink.map { it.first }.distinct())
    }
}
