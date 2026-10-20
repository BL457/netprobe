package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address

/** The only address space extended discovery ever touches (extended spec 4.1). CGNAT and link-local are excluded by construction. */
object Rfc1918 {
    private const val ENCLOSING_PREFIX = 16
    val BLOCKS: List<Subnet> = listOf(
        Subnet(0x0A000000, 8),             // 10.0.0.0/8
        Subnet(0xAC100000.toInt(), 12),    // 172.16.0.0/12
        Subnet(0xC0A80000.toInt(), 16),    // 192.168.0.0/16
    )
    fun contains(ip: Inet4Address): Boolean = BLOCKS.any { it.contains(ip) }
    fun contains(s: Subnet): Boolean = BLOCKS.any { it.containsSubnet(s) }
    /** 10.a.0.0/16, 172.b.0.0/16 or 192.168.0.0/16 around [ip]; never the whole 10/8 (extended spec 4.3 step 2). */
    fun enclosing16(ip: Inet4Address): Subnet? = if (contains(ip)) SubnetMath.of(ip, ENCLOSING_PREFIX) else null
}
