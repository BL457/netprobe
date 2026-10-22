package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Names what listens on an open port. The port table guess is the floor; [probes] (the data-driven ladder of Phase 3E) may raise it;
 * the mDNS and UPnP confirmations stay the final arbiters.
 */
class ServiceIdentifier(
    private val table: PortTable,
    private val probes: ServiceProbes,
    /** Test hook: logical port -> real port to connect to. Empty in production. */
    private val portOverride: Map<Int, Int> = emptyMap(),
) {
    companion object {
        /** Ports the HTTP-ish UI helpers and the UPnP arbitration treat as web ports. */
        val HTTP = setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400)
        val TLS = setOf(443, 8443, 7443, 8006, 5001)
        /** Upper bound on concurrent identify() calls made by the scan consumers. */
        const val MAX_PARALLEL_IDENTIFIES = 8
        private val mdnsConfirms = mapOf("_rfb._tcp" to (5900..5910 to "VNC"), "_ipp._tcp" to (631..631 to "IPP"),
            "_ssh._tcp" to (22..22 to "SSH"), "_http._tcp" to (80..80 to "HTTP"), "_smb._tcp" to (445..445 to "SMB"))
    }

    /**
     * Runs on Dispatchers.IO so ViewModels on Main can call it. [mode] gates the ladder: Passive sends the null probe (or, on a port
     * hinted for it, the one Phase 3A step); Active runs the whole ladder under the scan's budget. [hostId] keys that budget by host.
     */
    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive, hostId: Long = 0L): ServiceInfo =
        identifyTraced(ip, port, evidence, mode, hostId).first

    /** As [identify], also returning what the ladder ran, for the Identify service tool; [onProbe] is told the count after each probe. */
    suspend fun identifyTraced(ip: InetAddress, port: Int, evidence: Evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive, hostId: Long = 0L,
                               onProbe: (Int) -> Unit = {}): Pair<ServiceInfo, LadderResult> =
        withContext(Dispatchers.IO) {
            val baseName = table.nameFor(port)
            val base = ServiceInfo(baseName, confidence = Confidence.PORT_TABLE)
            val ladder = probes.run(ip, port, portOverride[port] ?: port, baseName, mode, hostId, onProbe)
            val fromProbe: ServiceInfo? = ladder.info
            val mdns = mdnsConfirms.entries.firstOrNull { (t, r) -> t in evidence.mdnsTypes && port in r.first }
            val info = when {
                fromProbe != null && fromProbe.confidence == Confidence.CONFIRMED -> fromProbe
                mdns != null -> ServiceInfo(mdns.value.second, detail = "mDNS ${mdns.key}", confidence = Confidence.CONFIRMED)
                fromProbe != null -> fromProbe
                evidence.upnpServer != null && port in HTTP -> base.copy(detail = evidence.upnpServer, confidence = Confidence.CONFIRMED)
                else -> base
            }
            info to ladder
        }
}
