package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.netenv.Ipv4
import com.northwalessolutions.netprobe.netenv.Rfc1918
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.SubnetMath
import java.net.Inet4Address

/**
 * The ranges a network is known to hold (3C spec 3.4, 4.5): its home range (any class), its manual ranges, and the /24 of every stored
 * visible private host. The listener inserts heard hosts only inside these; a manual tool target inside them is bound to Wi-Fi or Ethernet.
 * Manual-only rows contribute nothing: a typed target must never widen what counts as this network.
 */
class KnownRanges(val ranges: List<Subnet>) {
    fun contains(ip: Inet4Address): Boolean = ranges.any { it.contains(ip) }

    companion object {
        private const val UNIT_PREFIX = 24
        fun of(home: Subnet, manual: List<Subnet>, hosts: List<HostEntity>): KnownRanges {
            val units = hosts.asSequence().filter { !it.manualOnly }.mapNotNull { Ipv4.parse(it.ip) }.filter { Rfc1918.contains(it) }
                .map { SubnetMath.of(it, UNIT_PREFIX) }.toList()
            return KnownRanges((listOf(home) + manual + units).distinct())
        }
    }
}
