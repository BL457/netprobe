package com.northwalessolutions.netprobe.engine.neigh

import com.northwalessolutions.netprobe.engine.common.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/** The gateway's MAC from the kernel neighbour table; no permission needed. Null when the table is unreadable or has no entry. */
object GatewayMac {
    private const val NUDGE_PORT = 80
    private const val NUDGE_TIMEOUT_MS = 300

    fun pick(entries: List<NeighbourEntry>, gateway: String): String? = entries.firstOrNull { it.ip == gateway }?.mac?.lowercase()

    suspend fun lookup(gateway: Inet4Address?): String? {
        gateway ?: return null
        return withContext(Dispatchers.IO) {
            // Any connect attempt makes the kernel resolve the gateway, which puts it in the table; the outcome does not matter.
            Probe.connect(gateway, NUDGE_PORT, NUDGE_TIMEOUT_MS)
            NeighbourTable.dump().getOrNull()?.let { pick(it, gateway.hostAddress) }
        }
    }
}
