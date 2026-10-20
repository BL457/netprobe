package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.serviceid.ServiceMerge

enum class DeviceType { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, IOT, NAS, CAMERA, SERVER, UNKNOWN }
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?)
/** [software] is what the box runs ("Runs Home Assistant"), distinct and in rule-file order; never a weighted slot (spec 3.6). */
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>,
                          val software: List<String> = emptyList()) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) }
}

/**
 * Identification input for a stored host: everything ever learned about its ports (stored rows) plus this run's findings,
 * merged through [ServiceMerge]: new facts win field by field, a null never erases, a PORT_TABLE guess never lowers a CONFIRMED name.
 */
object HostEvidenceBuilder {
    fun build(host: DiscoveredHost, stored: Map<Int, ServiceInfo>, newPorts: Set<Int>, newServices: Map<Int, ServiceInfo>, ouiVendor: String?): HostEvidence {
        val services = HashMap(stored)
        for ((port, n) in newServices) services[port] = ServiceMerge.merge(stored[port], n)
        return HostEvidence(host, stored.keys + newPorts + newServices.keys, services, ouiVendor)
    }
}
