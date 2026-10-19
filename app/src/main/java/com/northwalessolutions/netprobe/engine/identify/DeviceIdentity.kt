package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

enum class DeviceType { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, IOT, NAS, CAMERA, SERVER, UNKNOWN }
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?)
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) }
}

/**
 * Identification input for a stored host: everything ever learned about its ports (stored rows) plus this run's findings.
 * New service facts win field by field, but a null never erases a stored product, version or detail.
 */
object HostEvidenceBuilder {
    fun build(host: DiscoveredHost, stored: Map<Int, ServiceInfo>, newPorts: Set<Int>, newServices: Map<Int, ServiceInfo>, ouiVendor: String?): HostEvidence {
        val services = HashMap(stored)
        for ((port, n) in newServices) {
            val o = stored[port]
            services[port] = if (o == null) n else n.copy(product = n.product ?: o.product, version = n.version ?: o.version, detail = n.detail ?: o.detail)
        }
        return HostEvidence(host, stored.keys + newPorts + newServices.keys, services, ouiVendor)
    }
}
