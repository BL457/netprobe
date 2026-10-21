package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

data class Subnet(val network: Int, val prefix: Int) {
    private val mask: Int get() = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val size: Int get() = if (prefix >= 31) (1 shl (32 - prefix)) else (1 shl (32 - prefix)) - 2
    fun contains(ip: Inet4Address): Boolean = (SubnetMath.toInt(ip) and mask) == network
    fun hosts(): Sequence<Inet4Address> {
        val total = 1L shl (32 - prefix)
        val first = if (prefix >= 31) 0L else 1L
        val last = if (prefix >= 31) total - 1 else total - 2
        return (first..last).asSequence().map { SubnetMath.toAddress(network + it.toInt()) }
    }
    override fun toString() = "${SubnetMath.toAddress(network).hostAddress}/$prefix"
}

data class SweepRange(val subnet: Subnet, val clamped: Boolean, val originalPrefix: Int)

object SubnetMath {
    fun toInt(ip: Inet4Address): Int {
        val b = ip.address
        return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
    }
    fun toAddress(i: Int): Inet4Address = InetAddress.getByAddress(
        byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())) as Inet4Address

    fun of(ip: Inet4Address, prefix: Int): Subnet {
        require(prefix in 0..32) { "prefix out of range: $prefix" }
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return Subnet(toInt(ip) and mask, prefix)
    }

    fun sweepRange(ip: Inet4Address, prefix: Int, maxPrefix: Int = 22): SweepRange =
        if (prefix >= maxPrefix) SweepRange(of(ip, prefix), clamped = false, originalPrefix = prefix)
        else SweepRange(of(ip, 24), clamped = true, originalPrefix = prefix)
}

/**
 * The one place the swept local range ("home range") is computed (3C spec 2.1, D19). Aggressive scan off: exactly
 * SubnetMath.sweepRange (a wider mask sweeps the phone's /24). Aggressive scan on: a wider mask sweeps the limit-sized block that
 * holds the phone, so a client /16 at the widest limit sweeps its /20 (controller ruling), never the whole /16.
 */
object HomeRange {
    fun of(ip: Inet4Address, prefix: Int, maxPrefix: Int, aggressive: Boolean): SweepRange =
        if (!aggressive || prefix >= maxPrefix) SubnetMath.sweepRange(ip, prefix, maxPrefix)
        else SweepRange(SubnetMath.of(ip, maxPrefix), clamped = true, originalPrefix = prefix)
}
