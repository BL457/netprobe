package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class ServiceIdentifier(
    private val table: PortTable,
    private val reader: BannerReader = BannerReader(),
    private val tls: TlsPeek = TlsPeek(),
    /** Test hook: logical port -> real port to connect to. Empty in production. */
    private val portOverride: Map<Int, Int> = emptyMap(),
) {
    companion object {
        val SPEAKS_FIRST = setOf(21, 22, 25, 110, 143, 587, 2222)
        val HTTP = setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400)
        val TLS = setOf(443, 8443, 7443, 8006, 5001)
        /** Upper bound on concurrent identify() calls made by the scan consumers (Task 21). */
        const val MAX_PARALLEL_IDENTIFIES = 8
        private val mdnsConfirms = mapOf("_rfb._tcp" to (5900..5910 to "VNC"), "_ipp._tcp" to (631..631 to "IPP"),
            "_ssh._tcp" to (22..22 to "SSH"), "_http._tcp" to (80..80 to "HTTP"), "_smb._tcp" to (445..445 to "SMB"))
    }

    /** Runs on Dispatchers.IO so ViewModels on Main can call it; banner and TLS sockets throw NetworkOnMainThreadException otherwise. */
    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence()): ServiceInfo = withContext(Dispatchers.IO) {
        val real = portOverride[port] ?: port
        val baseName = table.nameFor(port)
        val base = ServiceInfo(baseName, confidence = Confidence.PORT_TABLE)
        val fromBanner: ServiceInfo? = when {
            port in SPEAKS_FIRST -> reader.read(ip, real)?.let { b ->
                val line = String(b, Charsets.ISO_8859_1).lineSequence().first()
                BannerParsers.ssh(line) ?: BannerParsers.greeting(line, baseName) }
            port in HTTP -> reader.read(ip, real, "HEAD / HTTP/1.0\r\nHost: ${ip.hostAddress}\r\n\r\n".toByteArray())
                ?.let { BannerParsers.httpHead(String(it, Charsets.ISO_8859_1)) }
            port in TLS -> {
                val names = tls.certNames(ip, real)
                if (names == null) null
                else ServiceInfo("HTTPS", detail = names.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
                    confidence = Confidence.CONFIRMED)
            }
            else -> reader.read(ip, real)?.let { BannerParsers.classifyUnknown(it, baseName) }
        }
        val mdns = mdnsConfirms.entries.firstOrNull { (t, r) -> t in evidence.mdnsTypes && port in r.first }
        when {
            fromBanner != null && fromBanner.confidence == Confidence.CONFIRMED -> fromBanner
            mdns != null -> ServiceInfo(mdns.value.second, detail = "mDNS ${mdns.key}", confidence = Confidence.CONFIRMED)
            fromBanner != null -> fromBanner
            evidence.upnpServer != null && port in HTTP -> base.copy(detail = evidence.upnpServer, confidence = Confidence.CONFIRMED)
            else -> base
        }
    }
}
