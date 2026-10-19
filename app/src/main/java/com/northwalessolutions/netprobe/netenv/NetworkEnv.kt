package com.northwalessolutions.netprobe.netenv

import java.net.Inet4Address
import java.net.InetAddress

enum class Transport { WIFI, ETHERNET }

data class NetworkEnv(
    val interfaceIp: Inet4Address, val prefixLength: Int, val gateway: Inet4Address?,
    val dnsServers: List<InetAddress>, val ssid: String?, val bssid: String?, val transport: Transport,
)

data class LinkSnapshot(
    val addresses: List<Pair<InetAddress, Int>>, val routes: List<Pair<InetAddress?, Boolean>>,
    val dns: List<InetAddress>, val isWifi: Boolean, val isEthernet: Boolean, val isVpn: Boolean,
    val ssid: String?, val bssid: String?,
)

object NetworkEnvMapper {
    fun map(s: LinkSnapshot): NetworkEnv? {
        if (s.isVpn) return null
        val transport = when { s.isWifi -> Transport.WIFI; s.isEthernet -> Transport.ETHERNET; else -> return null }
        val (ip, prefix) = s.addresses.firstOrNull { it.first is Inet4Address && !it.first.isLoopbackAddress } ?: return null
        val gw = s.routes.firstOrNull { it.second && it.first is Inet4Address }?.first as Inet4Address?
        val isWifi = transport == Transport.WIFI
        val ssid = s.ssid?.takeIf { isWifi }?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        val bssid = s.bssid?.takeIf { isWifi }?.takeUnless { it == "02:00:00:00:00:00" || it.isBlank() }
        return NetworkEnv(ip as Inet4Address, prefix, gw, s.dns, ssid, bssid, transport)
    }
}
