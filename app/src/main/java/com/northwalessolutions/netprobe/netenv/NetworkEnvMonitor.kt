package com.northwalessolutions.netprobe.netenv

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkEnvMonitor(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Emits the current usable LAN environment, or null. Re-emits on any link change. */
    val env: Flow<NetworkEnv?> = callbackFlow {
        fun publish() { trySend(current()) }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = publish()
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = publish()
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        cm.registerNetworkCallback(req, cb)
        publish()
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    @Suppress("DEPRECATION")
    fun current(): NetworkEnv? {
        // Prefer a Wi-Fi or Ethernet network even when a VPN is the active default.
        val candidates = cm.allNetworks.mapNotNull { n ->
            val nc = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
            snapshot(nc, lp)
        }
        return candidates.firstNotNullOfOrNull { NetworkEnvMapper.map(it) }
    }

    private fun snapshot(nc: NetworkCapabilities, lp: LinkProperties): LinkSnapshot {
        @Suppress("DEPRECATION") val info = runCatching { wifi.connectionInfo }.getOrNull()
        return LinkSnapshot(
            addresses = lp.linkAddresses.map { it.address to it.prefixLength },
            routes = lp.routes.map { it.gateway to it.isDefaultRoute },
            dns = lp.dnsServers,
            isWifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isEthernet = nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            isVpn = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            ssid = info?.ssid, bssid = info?.bssid,
        )
    }
}
