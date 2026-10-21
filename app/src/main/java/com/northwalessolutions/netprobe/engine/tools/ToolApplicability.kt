package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.actions.PortUris
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.serviceid.ServiceIdentifier
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

/** Which tools a port gets, from the stored ServiceInfo and the port number (spec 4.2); it reuses 3A's PortUris.guess. */
object ToolApplicability {
    const val SNMP_PORT = 161
    const val SMB_PORT = 445
    /** TLS certificate also applies to these non-web TLS ports (spec 4.2). */
    private val TLS_EXTRA = setOf(465, 636, 993, 995, 5986, 8883)
    private val DNS_NAME = Regex("^[A-Za-z0-9.-]+$")

    fun webScheme(port: Int, service: ServiceInfo): String? {
        val guess = PortUris.guess(port, service)
        return when {
            guess == "http" || guess == "https" -> guess
            port in ServiceIdentifier.TLS -> "https"
            port in ServiceIdentifier.HTTP -> "http"
            else -> null
        }
    }
    fun appliesTo(tool: ToolId, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> webScheme(port, service) != null
        ToolId.TLS_CERTIFICATE -> webScheme(port, service) == "https" || port in TLS_EXTRA
        ToolId.SMB_SESSION -> port == SMB_PORT
        ToolId.SNMP_WALK -> false
    }
    /** Applicable per-port tools in ToolId order (SNMP walk is per host, so never here). */
    fun toolsFor(port: Int, service: ServiceInfo): List<ToolId> = ToolId.entries.filter { it != ToolId.SNMP_WALK && appliesTo(it, port, service) }
    fun targetTls(tool: ToolId, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolId.TLS_CERTIFICATE -> true
        ToolId.DIR_BRUTEFORCE, ToolId.HTTP_FINGERPRINT -> webScheme(port, service) == "https"
        ToolId.SMB_SESSION, ToolId.SNMP_WALK -> false
    }
    fun snmp(host: HostEntity): Boolean = host.snmpSysDescr != null
    /** mDNS name, else reverse DNS, trailing dot removed; null when absent or not a DNS-shaped name (spec 4.9). */
    fun serverName(host: HostEntity): String? =
        listOfNotNull(host.mdnsName, host.reverseDnsName).map { it.trim().trimEnd('.') }.firstOrNull { it.isNotEmpty() && DNS_NAME.matches(it) }
}
