package com.northwalessolutions.netprobe.engine.discovery

import java.net.Inet4Address

enum class Source { SWEEP, MDNS, SSDP, NETBIOS, NEIGH, SNMP, HTTP, GATEWAY }

/** MAC provenance. Higher rank wins on merge: netlink neighbour table is primary, NetBIOS and UPnP secondary, names and EUI-64 tertiary. */
enum class MacSource(val rank: Int) { EUI64(1), NAME(1), UPNP_UDN(2), NETBIOS(3), NEIGH(4) }

data class DiscoveredHost(
    val ip: Inet4Address, val mac: String? = null, val macSource: MacSource? = null, val reverseDnsName: String? = null, val mdnsName: String? = null,
    val netbiosName: String? = null, val netbiosWorkgroup: String? = null,
    val upnpFriendlyName: String? = null, val upnpManufacturer: String? = null, val upnpModel: String? = null, val upnpDeviceType: String? = null, val upnpServer: String? = null,
    val mdnsServiceTypes: Set<String> = emptySet(), val mdnsTxt: Map<String, Map<String, String>> = emptyMap(), val mdnsInstanceNames: Set<String> = emptySet(),
    val ipv6LinkLocal: Set<String> = emptySet(), val snmpSysDescr: String? = null, val snmpSysName: String? = null, val snmpSysObjectId: String? = null,
    val httpTitle: String? = null, val httpServer: String? = null, val httpAuthRealm: String? = null,
    val probedOpenPorts: Set<Int> = emptySet(), val sources: Set<Source> = emptySet(), val isGateway: Boolean = false,
) {
    fun merge(o: DiscoveredHost): DiscoveredHost {
        val takeOther = mac == null || (o.mac != null && (o.macSource?.rank ?: 0) > (macSource?.rank ?: 0))
        val (m, ms) = if (takeOther) o.mac to o.macSource else mac to macSource
        return mergeFields(o, m, ms)
    }
    private fun mergeFields(o: DiscoveredHost, m: String?, ms: MacSource?): DiscoveredHost = DiscoveredHost(
        ip = ip, mac = m, macSource = ms, reverseDnsName = reverseDnsName ?: o.reverseDnsName, mdnsName = mdnsName ?: o.mdnsName,
        netbiosName = netbiosName ?: o.netbiosName, netbiosWorkgroup = netbiosWorkgroup ?: o.netbiosWorkgroup,
        upnpFriendlyName = upnpFriendlyName ?: o.upnpFriendlyName, upnpManufacturer = upnpManufacturer ?: o.upnpManufacturer, upnpModel = upnpModel ?: o.upnpModel,
        upnpDeviceType = upnpDeviceType ?: o.upnpDeviceType, upnpServer = upnpServer ?: o.upnpServer,
        mdnsServiceTypes = mdnsServiceTypes + o.mdnsServiceTypes, mdnsTxt = mdnsTxt + o.mdnsTxt, mdnsInstanceNames = mdnsInstanceNames + o.mdnsInstanceNames,
        ipv6LinkLocal = ipv6LinkLocal + o.ipv6LinkLocal, snmpSysDescr = snmpSysDescr ?: o.snmpSysDescr, snmpSysName = snmpSysName ?: o.snmpSysName, snmpSysObjectId = snmpSysObjectId ?: o.snmpSysObjectId,
        httpTitle = httpTitle ?: o.httpTitle, httpServer = httpServer ?: o.httpServer, httpAuthRealm = httpAuthRealm ?: o.httpAuthRealm,
        probedOpenPorts = probedOpenPorts + o.probedOpenPorts, sources = sources + o.sources, isGateway = isGateway || o.isGateway,
    )
    /** Tertiary MAC sources (names, EUI-64), applied only when this emission has no MAC. `merge` arbitrates by rank across emissions. */
    fun withDerivedMac(): DiscoveredHost {
        if (mac != null) return this
        (mdnsInstanceNames + listOfNotNull(upnpFriendlyName)).firstNotNullOfOrNull { MacExtract.fromText(it) }?.let { return copy(mac = it, macSource = MacSource.NAME) }
        ipv6LinkLocal.firstNotNullOfOrNull { Eui64.macFromLinkLocal(it) }?.let { return copy(mac = it, macSource = MacSource.EUI64) }
        return this
    }
}
