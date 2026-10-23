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
    /** The per-port rule of one built-in tool key (spec 4.2); any other key does not apply. Each built-in module answers [ToolModule.applies] from here. */
    fun appliesTo(tool: String, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT -> webScheme(port, service) != null
        ToolKeys.TLS_CERTIFICATE -> webScheme(port, service) == "https" || port in TLS_EXTRA
        ToolKeys.SMB_SESSION -> port == SMB_PORT
        ToolKeys.SNMP_WALK -> false
        ToolKeys.IDENTIFY -> true
        ToolKeys.IDENTIFY_ALL -> false   // a per-host tool: it works over the whole port list
        else -> false
    }
    /** The Identify tools have no chip or block on the Device screen: they are manual tools and a port row action (3E spec O1, ruling R2). */
    private val NOT_ON_DEVICE_PAGE = setOf(ToolKeys.IDENTIFY, ToolKeys.IDENTIFY_ALL)
    fun onDevicePage(toolKey: String): Boolean = toolKey !in NOT_ON_DEVICE_PAGE
    /** The registry's PORT tools that apply to the port, in registry order (built-ins first), for the Device screen chips. */
    fun toolsFor(registry: ToolRegistry, port: Int, service: ServiceInfo): List<ToolModule> =
        registry.all.value.filter { it.descriptor.target == TargetKind.PORT && onDevicePage(it.descriptor.key) && it.applies(port, service) }
    /** The registry's HOST tools that apply to the device, for the host-level buttons. */
    fun hostToolsFor(registry: ToolRegistry, host: HostEntity): List<ToolModule> =
        registry.all.value.filter { it.descriptor.target == TargetKind.HOST && it.appliesToHost(host) }
    fun targetTls(tool: String, port: Int, service: ServiceInfo): Boolean = when (tool) {
        ToolKeys.TLS_CERTIFICATE -> true
        ToolKeys.DIR_BRUTEFORCE, ToolKeys.HTTP_FINGERPRINT -> webScheme(port, service) == "https"
        else -> false
    }
    fun snmp(host: HostEntity): Boolean = host.snmpSysDescr != null
    /** mDNS name, else reverse DNS, trailing dot removed; null when absent or not a DNS-shaped name (spec 4.9). */
    fun serverName(host: HostEntity): String? =
        listOfNotNull(host.mdnsName, host.reverseDnsName).map { it.trim().trimEnd('.') }.firstOrNull { it.isNotEmpty() && DNS_NAME.matches(it) }
}
