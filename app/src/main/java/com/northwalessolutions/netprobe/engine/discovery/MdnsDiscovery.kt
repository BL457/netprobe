package com.northwalessolutions.netprobe.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.resume

class AndroidNsdSource(context: Context) : NsdSource {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun discover(type: String, onFound: (NsdFound) -> Unit, onLost: (String) -> Unit): AutoCloseable {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { onFound(NsdFound(info.serviceName, MdnsType.normalise(info.serviceType), info)) }
            override fun onServiceLost(info: NsdServiceInfo) { onLost(info.serviceName) }
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        return AutoCloseable { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    override suspend fun resolve(found: NsdFound): MdnsRecord? = suspendCancellableCoroutine { cont ->
        // Pass the NsdServiceInfo from onServiceFound straight through (the documented pattern); rebuild only if the handle is missing.
        val info = found.handle as? NsdServiceInfo
            ?: NsdServiceInfo().apply { this.serviceName = found.name; this.serviceType = found.type }
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses else listOfNotNull(i.host)
                val v4 = addrs.filterIsInstance<Inet4Address>().firstOrNull()
                val v6 = addrs.filterIsInstance<Inet6Address>().map { it.hostAddress.substringBefore('%') }
                val txt = i.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
                if (cont.isActive) cont.resume(v4?.let { MdnsRecord(it, i.serviceName, MdnsType.normalise(i.serviceType), i.port, txt, v6) })
            }
        })
    }
}
