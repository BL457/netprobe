package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostRepository
import com.northwalessolutions.netprobe.data.PortRepository
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.HostEvidenceBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One radar discovery emission, stored exactly as HomeViewModel.discoverOn stores it (spec 5.3 step 2, plan review G2). The removed
 * check and the write happen under [lock], which Remove device also holds while it marks and deletes, so an emission can never
 * slip between the check and the delete and re-insert a removed host.
 */
class RadarCollector(
    private val hosts: HostRepository, private val ports: PortRepository, private val identifier: DeviceIdentifier,
    private val ouiLookup: (String) -> String?, private val lock: Mutex, private val removed: () -> Set<Pair<Long, String>>,
) {
    /** Ports seen open per IP during this run: later emissions for the host carry none. */
    private val openPorts = HashMap<String, MutableSet<Int>>()

    /** False when the host was removed during this run and the emission was dropped. */
    suspend fun store(networkId: Long, h: DiscoveredHost, runId: Long): Boolean = lock.withLock {
        val ip = h.ip.hostAddress ?: return@withLock false
        if (RemovedHosts.skip(removed(), networkId, ip)) return@withLock false
        val id = hosts.upsertDiscovered(networkId, h, runId)
        val merged = hosts.byIdOnce(id) ?: return@withLock true
        val seen = openPorts.getOrPut(ip) { HashSet() }.also { it += h.probedOpenPorts }.toSet()
        // Stored port findings are part of the evidence, so a rediscovery never downgrades an identity a port scan established.
        val ev = HostEvidenceBuilder.build(hosts.toDiscovered(merged), ports.servicesFor(id), seen, emptyMap(), merged.mac?.let(ouiLookup))
        hosts.setIdentity(id, identifier.identify(ev))
        true
    }
}
