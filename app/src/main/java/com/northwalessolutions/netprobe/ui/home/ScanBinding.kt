package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.SubnetMath
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Port scans of rows outside the swept local range run bound to the Wi-Fi network when extended discovery is on, so a VPN that
 * routes private ranges never carries them to its far end (extended spec 5.5, final review I2). Local rows and the toggle-off path
 * are the MVP: unbound. No network to bind is a [BindFailedException], never a silent unbound scan.
 */
object ScanBinding {
    suspend fun <T> run(ip: Inet4Address, env: NetworkEnv?, maxPrefix: Int, extEnabled: Boolean,
                        binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T {
        if (!extEnabled || env == null) return block()
        val local = SubnetMath.sweepRange(env.interfaceIp, env.prefixLength, maxPrefix).subnet
        if (local.contains(ip)) return block()
        val b = binderFor(env) ?: throw BindFailedException(IllegalStateException("no Wi-Fi or Ethernet network carries ${env.interfaceIp.hostAddress}"))
        return withContext(BoundNetwork(b)) { block() }
    }
}
