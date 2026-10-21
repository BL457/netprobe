package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.AppContainer
import com.northwalessolutions.netprobe.data.NetworkSignature
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.discovery.HeardContext
import com.northwalessolutions.netprobe.engine.discovery.HeardSink
import com.northwalessolutions.netprobe.engine.discovery.KnownRanges
import com.northwalessolutions.netprobe.engine.discovery.UpnpDevice
import com.northwalessolutions.netprobe.engine.discovery.UpnpFetcher
import com.northwalessolutions.netprobe.netenv.HomeRange
import com.northwalessolutions.netprobe.netenv.NetworkEnv
import kotlinx.coroutines.flow.first
import java.net.Inet4Address

/**
 * The listener's writes into the inventory (3C spec 3.4), one per session. The write is a RadarCollector built with upsertHeard, so it takes
 * hostWriteLock, honours RemovedHosts.skip and applies IdentityWrite.sameDevice exactly as a radar emission does.
 */
class ContainerHeardSink(private val c: AppContainer, private val env: NetworkEnv) : HeardSink {
    private val upnp = UpnpFetcher()
    private val collector = RadarCollector(c.hosts, c.ports, c.deviceIdentifier, c.ouiLookup, c.hostWriteLock,
        hintsFor = { id -> c.toolRepo.hintsFor(id) }, upsert = c.hosts::upsertHeard) { removed() }

    override fun context(): HeardContext? = c.currentNetwork.value?.let { n ->
        HeardContext(n.id, NetworkSignature.identityKey(n), forgotten = n.id == c.forgottenNetworkId.value,
            scanActive = c.radarScanActive.value, undoPending = c.undo.pending.value != null)
    }
    /** The home range from the live settings, this network's manual ranges and its visible hosts (spec 3.4). */
    override suspend fun known(networkId: Long): KnownRanges {
        val s = c.settings.state.value
        return KnownRanges.of(HomeRange.of(env.interfaceIp, env.prefixLength, s.maxPrefix, s.extEnabled).subnet, c.settings.manual.get(networkId),
            c.hosts.hostsForNetwork(networkId).first())
    }
    override suspend fun exists(networkId: Long, ip: String): Boolean = c.hosts.findOnce(networkId, ip) != null
    override fun removed(): Set<Pair<Long, String>> = c.removedDuringScan.value + c.removedWhileListening.value
    /** Bound to the Wi-Fi or Ethernet Network, never unbound; no binder means no fetch. */
    override suspend fun describe(location: String, host: Inet4Address): UpnpDevice? {
        val binder = c.networkEnvMonitor.binderFor(env) ?: return null
        return upnp.fetch(location, host) { url -> binder.openConnection(url) }
    }
    /** A new or promoted row takes the network's last completed run, so it shows undimmed; "Last scan" never moves (spec 3.4). */
    override suspend fun store(networkId: Long, h: DiscoveredHost): Boolean {
        // hosts has no foreign key to networks: a row for a deleted or forgotten network would never be cleaned up (plan review G3).
        val net = c.networks.byId(networkId) ?: return false
        if (net.forgotten != null || net.id == c.forgottenNetworkId.value) return false
        return collector.store(networkId, h, net.lastRunAt ?: 0L)
    }
}
