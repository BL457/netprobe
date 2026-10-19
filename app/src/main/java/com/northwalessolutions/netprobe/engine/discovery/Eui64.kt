package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet6Address
import java.net.InetAddress

object Eui64 {
    private const val LITERAL_CHARS = "0123456789abcdefABCDEF:."
    private const val LITERAL_START_CHARS = "0123456789abcdefABCDEF:"
    private const val FF_INDEX = 11
    private const val FE_INDEX = 12
    private const val BYTE_MASK = 0xff
    private const val UNIVERSAL_LOCAL_BIT = 0x02

    fun macFromLinkLocal(ipv6: String): String? {
        val lit = ipv6.substringBefore('%')
        // getByName does a DNS lookup for non-literals (slow, network-dependent), so only hand it a plausible literal.
        if (!lit.contains(':') || lit.first() !in LITERAL_START_CHARS || lit.any { it !in LITERAL_CHARS }) return null
        val addr = try {
            InetAddress.getByName(lit)
        } catch (_: Exception) {
            return null
        }
        if (addr !is Inet6Address || !addr.isLinkLocalAddress) return null
        val b = addr.address
        if ((b[FF_INDEX].toInt() and BYTE_MASK) != 0xff || (b[FE_INDEX].toInt() and BYTE_MASK) != 0xfe) return null
        val mac = byteArrayOf((b[8].toInt() xor UNIVERSAL_LOCAL_BIT).toByte(), b[9], b[10], b[13], b[14], b[15])
        return mac.joinToString(":") { "%02x".format(it) }
    }
}
