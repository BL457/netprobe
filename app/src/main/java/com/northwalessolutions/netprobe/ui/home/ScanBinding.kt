package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.common.BindFailedException
import com.northwalessolutions.netprobe.engine.common.BoundNetwork
import com.northwalessolutions.netprobe.engine.common.SocketBinder
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Subnet
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Port scans and tool runs of rows outside the home range run bound to the Wi-Fi network when the run binds outside (Aggressive scan
 * on, or manual ranges set: 3C spec 2.4), so a VPN that routes private ranges never carries them to its far end (extended spec 5.5,
 * final review I2). Local rows and the bindOutside-false path are the MVP: unbound. No network to bind is a [BindFailedException],
 * never a silent unbound scan.
 */
object ScanBinding {
    suspend fun <T> run(ip: Inet4Address, env: NetworkEnv?, home: Subnet?, bindOutside: Boolean,
                        binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T {
        if (!bindOutside || env == null || home == null) return block()
        if (home.contains(ip)) return block()
        val b = binderFor(env) ?: throw BindFailedException(IllegalStateException("no Wi-Fi or Ethernet network carries ${env.interfaceIp.hostAddress}"))
        return withContext(BoundNetwork(b)) { block() }
    }

    /** The form every caller uses: the run's snapshot decides the home range and whether to bind (D21). */
    suspend fun <T> forRun(ip: Inet4Address, env: NetworkEnv?, runSettings: RunSettings, binderFor: (NetworkEnv) -> SocketBinder?, block: suspend () -> T): T =
        run(ip, env, env?.let { runSettings.home(it) }, runSettings.bindOutside, binderFor, block)
}
