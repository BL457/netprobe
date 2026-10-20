package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

/** Dotted-quad literal to an address without any name lookup; null for anything else. */
object Ipv4 {
    private const val OCTETS = 4
    private const val OCTET_MAX = 255
    private const val MAX_DIGITS = 3
    fun parse(s: String): Inet4Address? {
        val p = s.split('.')
        if (p.size != OCTETS || p.any { it.isEmpty() || it.length > MAX_DIGITS || !it.all(Char::isDigit) }) return null
        val n = p.map { it.toInt() }.takeIf { o -> o.all { it <= OCTET_MAX } } ?: return null
        return InetAddress.getByAddress(ByteArray(OCTETS) { n[it].toByte() }) as Inet4Address
    }
}
