package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.DisplayName
import com.northwalessolutions.netprobe.data.Effective
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.PortEntity
import com.northwalessolutions.netprobe.engine.tools.MacText

/** One open port of a device as `ctx.devices()` shows it. [tls] is true when the app saw TLS on the port (an HTTPS or TLS service, or a certificate). */
data class ScriptPort(val port: Int, val service: String, val tls: Boolean)

/** One discovered device as `ctx.devices()` shows it (spec section 12). Unknown text is the empty string, never null. */
data class ScriptDevice(val ip: String, val mac: String, val name: String, val vendor: String, val ports: List<ScriptPort>)

/** Builds the `ctx.devices()` list from the stored hosts and ports. Pure: the caller supplies the rows. */
object ScriptDevices {
    /** The most devices one call returns (spec section 12). */
    const val MAX_DEVICES = 1_024

    fun portOf(p: PortEntity): ScriptPort =
        ScriptPort(p.port, p.serviceName, p.serviceName.equals("HTTPS", ignoreCase = true) || p.serviceName.equals("TLS", ignoreCase = true) || p.certCn != null)

    fun deviceOf(h: HostEntity, ports: List<PortEntity>): ScriptDevice =
        ScriptDevice(h.ip, MacText.normalize(h.mac), DisplayName.of(h), Effective.vendor(h) ?: h.vendor.orEmpty(), ports.sortedBy { it.port }.map(::portOf))

    /** Newest scan first (lastSeen, then the address), manual tool targets left out, at most [MAX_DEVICES]. */
    suspend fun load(hosts: List<HostEntity>, portsOf: suspend (Long) -> List<PortEntity>): List<ScriptDevice> =
        hosts.filter { !it.manualOnly }
            .sortedWith(compareByDescending<HostEntity> { it.lastSeen }.thenBy { it.ip })
            .take(MAX_DEVICES)
            .map { deviceOf(it, portsOf(it.id)) }
}
