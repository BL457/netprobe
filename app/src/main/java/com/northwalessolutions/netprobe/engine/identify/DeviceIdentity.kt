package com.northwalessolutions.netprobe.engine.identify

import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

enum class DeviceType { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, IOT, NAS, CAMERA, SERVER, UNKNOWN }
data class HostEvidence(val host: DiscoveredHost, val openPorts: Set<Int>, val services: Map<Int, ServiceInfo>, val ouiVendor: String?)
data class DeviceIdentity(val vendor: String?, val model: String?, val os: String?, val type: DeviceType, val confidence: Int, val reasons: List<String>) {
    companion object { val UNKNOWN = DeviceIdentity(null, null, null, DeviceType.UNKNOWN, 0, emptyList()) }
}
