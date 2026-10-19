package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe
import java.net.Inet4Address

data class Liveness(val ip: Inet4Address, val alive: Boolean, val openPorts: Set<Int>, val rttMs: Int?)

class HostProber(val ports: List<Int> = DEFAULT_PORTS, val timeoutMs: Int = 600) {
    suspend fun probe(ip: Inet4Address): Liveness {
        for (port in ports) {
            when (val o = Probe.connect(ip, port, timeoutMs)) {
                is Probe.Outcome.Connected -> return Liveness(ip, true, setOf(port), o.rttMs)
                Probe.Outcome.Refused -> return Liveness(ip, true, emptySet(), null)
                Probe.Outcome.Unreachable -> return Liveness(ip, false, emptySet(), null)
                Probe.Outcome.Timeout -> Unit
            }
        }
        return Liveness(ip, false, emptySet(), null)
    }
    companion object { val DEFAULT_PORTS = listOf(80, 443, 22, 445, 139, 135, 3389, 8080, 62078, 7, 53, 548) }
}
