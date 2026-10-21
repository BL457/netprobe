package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Rfc1918
import com.northwalessolutions.netprobe.netenv.Subnet
import com.northwalessolutions.netprobe.netenv.Transport
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** How a manual tool run leaves the phone (3C spec 4.5, D20); stored by name in tool_runs.route and shown in the results header. */
enum class ToolRoute {
    WIFI, ETHERNET, OTHER;
    companion object { fun parse(s: String?): ToolRoute? = entries.firstOrNull { it.name == s } }
}

/**
 * The manual tools route rule (3C spec 4.5, ruling 4). Only manual targets use it; scans, Quick scan all, Device-screen runs and the
 * listener keep their own bindings. A target inside the network's known ranges goes over the Wi-Fi or Ethernet Network, and with no
 * binder fails rather than run unbound; any other target goes out on the system's routing, so a VPN or Tailscale route can carry it.
 */
/** What Android says about the LAN and the default network at the Run tap (Director ruling R4); CONNECTED keeps ruling 4 as written. */
data class RouteContext(val lanHasInternet: Boolean, val defaultIsVpn: Boolean) {
    companion object { val CONNECTED = RouteContext(lanHasInternet = true, defaultIsVpn = false) }
}

object ManualRoute {
    /**
     * Director ruling R4 (pending; default per the controller's recommendation). true: on a Wi-Fi or Ethernet network with no internet and no
     * VPN as the default, a private target outside the known ranges goes over that LAN, the only route that can reach it (plan review G10);
     * false: ruling 4 as written (unbound). Public targets are unbound either way.
     */
    const val PRIVATE_TARGETS_OVER_LAN_WITHOUT_INTERNET = true
    private val CGNAT = Subnet(0x64400000, 10)                    // 100.64.0.0/10
    private val LINK_LOCAL = Subnet(0xA9FE0000.toInt(), 16)       // 169.254.0.0/16

    fun of(ip: Inet4Address, known: KnownRanges, env: NetworkEnv, ctx: RouteContext = RouteContext.CONNECTED,
           privateOverLan: Boolean = PRIVATE_TARGETS_OVER_LAN_WITHOUT_INTERNET): ToolRoute {
        val lanRoute = if (env.transport == Transport.ETHERNET) ToolRoute.ETHERNET else ToolRoute.WIFI
        if (known.contains(ip)) return lanRoute
        if (privateOverLan && isPrivate(ip) && !ctx.lanHasInternet && !ctx.defaultIsVpn) return lanRoute
        return ToolRoute.OTHER
    }
    fun isPrivate(ip: Inet4Address): Boolean = Rfc1918.contains(ip) || CGNAT.contains(ip) || LINK_LOCAL.contains(ip)
    fun needsBinder(route: ToolRoute): Boolean = route != ToolRoute.OTHER

    suspend fun <T> bind(route: ToolRoute, env: NetworkEnv, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T {
        if (!needsBinder(route)) return block()
        val b = binderFor(env) ?: throw BindFailedException(IllegalStateException("no Wi-Fi or Ethernet network carries ${env.interfaceIp.hostAddress}"))
        return withContext(BoundNetwork(b)) { block() }
    }
}
