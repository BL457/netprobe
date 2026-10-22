package com.northwalessolutions.netprobe.engine.portscan

import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.HostEntity
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import com.northwalessolutions.netprobe.engine.serviceid.Evidence
import com.northwalessolutions.netprobe.engine.serviceid.ProbeModes
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import com.northwalessolutions.netprobe.ui.home.RunSettings
import com.northwalessolutions.netprobe.ui.home.ScanBinding
import java.net.Inet4Address
import java.net.InetAddress

/** One Device port scan: sweep, identify each open port, store, re-identify the device. Returns the number of open ports found. */
class HostScanBody(private val c: AppContainer) {
    suspend fun run(h: HostEntity, profile: ScanProfile, e: NetworkEnv, sink: ScanSink): Int {
        val ip = InetAddress.getByName(h.ip) as Inet4Address
        // One settings read per scan (3C spec D21); manual ranges bind off-subnet rows even with Aggressive scan off (spec 2.4).
        val runSettings = RunSettings.of(c.settings.state.value, c.settings.manual.forNetwork(h.networkId))
        return ScanBinding.forRun(ip, e, runSettings, c.networkEnvMonitor::binderFor) { scanOn(h, profile, ip, sink) }
    }

    private suspend fun scanOn(h: HostEntity, profile: ScanProfile, ip: Inet4Address, sink: ScanSink): Int {
        val types = c.hosts.mdnsTypesFor(h.id)                  // once per scan, not per port
        val mode = ProbeModes.forProfile(profile, c.settings.state.value.serviceHellos)   // one budget per Full or Custom scan, shared by its identifies
        sink.identify { IdentifyCount() }
        val pipeline = ScanPipeline(c.portScanEngine, gate = c.foregroundGate::await, options = c.settings.scanOptions(),
            identify = { port -> c.serviceIdentifier.identify(ip, port, Evidence(mdnsTypes = types, upnpServer = h.upnpServer), mode, h.id) },
            store = { port, info -> c.ports.upsertOpenIfHostExists(h.id, port, info) })
        val result = pipeline.run(ip, profile, sink)
        val found = result.found
        c.ports.markScanned(h.id)
        // Stored ports are part of the evidence: a narrow Custom scan must not forget what a Full scan found.
        c.hosts.byIdOnce(h.id)?.let { stored ->
            val ev = HostEvidenceBuilder.build(c.hosts.toDiscovered(stored), c.ports.servicesFor(h.id), found.keys.toSet(), found,
                stored.mac?.let(c.ouiLookup), c.toolRepo.hintsFor(h.id))
            // After a port scan the stored identity is replaced only by one at least as confident (3A spec 3.3).
            c.hosts.setIdentityIfNotWeaker(h.id, c.deviceIdentifier.identify(ev))
        }
        return result.opened
    }
}
