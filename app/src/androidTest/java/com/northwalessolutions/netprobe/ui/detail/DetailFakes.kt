package com.northwalessolutions.netprobe.ui.detail

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.actions.PortUris
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.data.HostStatus
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo

object DetailFakes {
    const val IP = "10.0.3.59"
    fun host(note: String = "", status: HostStatus = HostStatus.UNMARKED) = HostEntity(id = 7, networkId = 1, ip = IP, mac = "00:11:32:9a:1f:e4",
        reverseDnsName = "chnas.lan", mdnsName = "CHNAS", netbiosName = null, upnpName = null, upnpManufacturer = null, upnpModel = null, vendor = null,
        deviceType = DeviceType.NAS, customName = null, identVendor = "Synology", identModel = "DS220+", identOs = null, identConfidence = 80,
        identReasons = listOf("mDNS model DS220+"), snmpSysDescr = null, httpTitle = null, mdnsTxtJson = null, status = status, note = note,
        isGateway = false, firstSeen = 0, lastSeen = 0, lastPortScan = null, lastDiscoveryRun = 1)

    /** A port whose primary action uses [scheme]; [has] false models "no app installed" (no primary). */
    fun port(n: Int, scheme: String?, has: Boolean = true, name: String = "HTTP", product: String? = null, version: String? = null,
             tryAs: List<String> = emptyList()): PortRow {
        val uri = scheme?.let { if (it == "smb") "smb://$IP/" else "$it://$IP:$n/" }
        val open = uri?.let { DeviceAction.OpenUri(if (has) "Open in browser" else "No app installed for $scheme://", it, has) }
        val actions = listOfNotNull(open, uri?.let { DeviceAction.OpenWith(it) }, DeviceAction.Copy("Copy IP", IP), DeviceAction.Copy("Copy IP:port", "$IP:$n"))
        return PortRow(n, ServiceInfo(name, product, version, null, Confidence.CONFIRMED), open?.takeIf { has }, actions, tryAs.map { it to PortUris.uri(it, IP, n) })
    }
    fun ports(n: Int) = (1..n).map { port(8000 + it, "http") }

    fun state(ports: List<PortRow> = ports(30), note: String = "", profile: ScanProfile = ScanProfile.Quick, customText: String = "",
              customError: String? = null, scanning: Boolean = false) =
        DetailUiState(host = host(note), displayName = "CHNAS", ports = ports, scanning = scanning, profile = profile,
            customPortsText = customText, customError = customError)
}
