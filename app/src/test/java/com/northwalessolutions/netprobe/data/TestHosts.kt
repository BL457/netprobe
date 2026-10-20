package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType

/** A stored host with only what a test names; everything else empty. */
object TestHosts {
    fun host(ip: String = "10.0.3.59", type: DeviceType = DeviceType.UNKNOWN, vendor: String? = null, model: String? = null, os: String? = null,
             mdns: String? = null, mac: String? = null, gateway: Boolean = false) = HostEntity(
        networkId = 1, ip = ip, mac = mac, reverseDnsName = null, mdnsName = mdns, netbiosName = null, upnpName = null, upnpManufacturer = null,
        upnpModel = null, vendor = null, deviceType = type, customName = null, identVendor = vendor, identModel = model, identOs = os, identConfidence = 0,
        identReasons = emptyList(), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = gateway,
        firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
}
