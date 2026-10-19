package com.northwalessolutions.netprobe.data

import com.northwalessolutions.netprobe.engine.identify.DeviceType
import org.junit.Assert.*
import org.junit.Test

class DisplayNameTest {
    private fun host(custom: String? = null, mdns: String? = null, nb: String? = null, upnp: String? = null, rdns: String? = null) = HostEntity(
        networkId = 1, ip = "192.168.1.9", mac = null, reverseDnsName = rdns, mdnsName = mdns, netbiosName = nb, upnpName = upnp, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.UNKNOWN, customName = custom, identVendor = null, identModel = null, identOs = null, identConfidence = 0, identReasons = emptyList(),
        snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = HostStatus.UNMARKED, note = "", isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 0)
    @Test fun `precedence`() {
        assertEquals("Mine", DisplayName.of(host(custom = "Mine", mdns = "m", nb = "n", upnp = "u", rdns = "r")))
        assertEquals("m", DisplayName.of(host(mdns = "m", nb = "n"))); assertEquals("n", DisplayName.of(host(nb = "n", upnp = "u")))
        assertEquals("u", DisplayName.of(host(upnp = "u", rdns = "r"))); assertEquals("r", DisplayName.of(host(rdns = "r"))); assertEquals("192.168.1.9", DisplayName.of(host()))
        assertEquals("192.168.1.9", DisplayName.of(host(custom = "  ")))
    }
}
