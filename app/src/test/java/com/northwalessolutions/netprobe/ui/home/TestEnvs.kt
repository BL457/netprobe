package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.netenv.Transport
import java.net.Inet4Address
import java.net.InetAddress

/** Two LAN environments that differ only in gateway, so their NetworkSignatures differ. */
object TestEnvs {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address
    val home = NetworkEnv(v4("192.168.1.50"), 24, v4("192.168.1.1"), emptyList(), null, null, Transport.WIFI)
    val office = NetworkEnv(v4("192.168.1.50"), 24, v4("192.168.1.254"), emptyList(), null, null, Transport.WIFI)
}
