package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

class ServiceIdentifier(
    private val table: PortTable,
    private val reader: BannerReader = BannerReader(timeoutMs = BANNER_TIMEOUT_MS),
    private val tls: TlsPeek = TlsPeek(),
    private val http: HttpTitleFetcher = HttpTitleFetcher(timeoutMs = HTTP_FETCH_TIMEOUT_MS),
    /** Test hook: logical port -> real port to connect to. Empty in production. */
    private val portOverride: Map<Int, Int> = emptyMap(),
) {
    companion object {
        val SPEAKS_FIRST = setOf(21, 22, 25, 110, 143, 587, 2222)
        val HTTP = setOf(80, 81, 3000, 5000, 8000, 8008, 8080, 8081, 8888, 9000, 9090, 8123, 8096, 32400)
        val TLS = setOf(443, 8443, 7443, 8006, 5001)
        val RTSP = setOf(554, 8554)
        val RDP = setOf(3389)
        /** Never sent an active probe: raw print, LPD, Cisco SCCP, X11, and the mail ports that greet first (banner only). */
        val PASSIVE_ONLY: Set<Int> = setOf(25, 110, 143, 515, 2000, 2001) + (6000..6063) + (9100..9107)
        /** IPP: a plain HEAD is safe and useful; never a TLS ClientHello, never a GET. */
        val HEAD_ONLY: Set<Int> = setOf(631)
        const val BANNER_TIMEOUT_MS = 1000
        const val TLS_TIMEOUT_MS = 1500
        const val HTTP_FETCH_TIMEOUT_MS = 2000
        /** Upper bound on concurrent identify() calls made by the scan consumers. */
        const val MAX_PARALLEL_IDENTIFIES = 8
        private const val MAX_HEADER = 120
        private val mdnsConfirms = mapOf("_rfb._tcp" to (5900..5910 to "VNC"), "_ipp._tcp" to (631..631 to "IPP"),
            "_ssh._tcp" to (22..22 to "SSH"), "_http._tcp" to (80..80 to "HTTP"), "_smb._tcp" to (445..445 to "SMB"))
    }

    /**
     * Runs on Dispatchers.IO so ViewModels on Main can call it. [mode] gates the active ladder (spec 2.2.1): Passive sends exactly
     * what was sent before Phase 3A; Active adds the steps of spec 2.2.3 under the scan's budget.
     */
    suspend fun identify(ip: InetAddress, port: Int, evidence: Evidence = Evidence(), mode: ProbeMode = ProbeMode.Passive): ServiceInfo = withContext(Dispatchers.IO) {
        val baseName = table.nameFor(port)
        val base = ServiceInfo(baseName, confidence = Confidence.PORT_TABLE)
        val ladder = Ladder(ip, port, portOverride[port] ?: port, baseName, (mode as? ProbeMode.Active)?.budget)
        val fromProbe: ServiceInfo? = when (port) {
            in SPEAKS_FIRST, in PASSIVE_ONLY -> ladder.passiveOnly(speaksFirst = port in SPEAKS_FIRST)
            in HEAD_ONLY -> ladder.headOnly()
            in HTTP -> ladder.httpSet()
            in TLS -> ladder.tlsSet()
            in RTSP -> ladder.rtsp()
            in RDP -> ladder.rdp()
            else -> ladder.unknown()
        }
        val mdns = mdnsConfirms.entries.firstOrNull { (t, r) -> t in evidence.mdnsTypes && port in r.first }
        when {
            fromProbe != null && fromProbe.confidence == Confidence.CONFIRMED -> fromProbe
            mdns != null -> ServiceInfo(mdns.value.second, detail = "mDNS ${mdns.key}", confidence = Confidence.CONFIRMED)
            fromProbe != null -> fromProbe
            evidence.upnpServer != null && port in HTTP -> base.copy(detail = evidence.upnpServer, confidence = Confidence.CONFIRMED)
            else -> base
        }
    }

    /** One identify's probe sequence (spec 2.2.3). Every active step goes through [active], so Passive mode can never send one. */
    private inner class Ladder(private val ip: InetAddress, private val port: Int, private val real: Int, private val baseName: String,
                               private val budget: ActiveProbeBudget?) {
        private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)
        private fun headRequest() = "HEAD / HTTP/1.0\r\nHost: ${ip.hostAddress}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        /** Null without sending anything in Passive mode, when the port is refused entry, or once the time is spent (spec 2.2.4). */
        private suspend fun <T> active(block: suspend () -> T?): T? {
            val b = budget ?: return null
            if (!b.tryEnter(port)) return null
            return b.step(block)
        }
        private suspend fun passiveRead(): ByteArray? = reader.read(ip, real)
        private suspend fun head(): ServiceInfo? = reader.read(ip, real, headRequest())?.let { BannerParsers.httpHead(text(it)) }
        private suspend fun peek(): CertInfo? = tls.certInfo(ip, real, TLS_TIMEOUT_MS)
        private fun tlsInfo(c: CertInfo, name: String) = ServiceInfo(name, detail = c.names.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
            confidence = Confidence.CONFIRMED, certCn = c.cn, certOrg = c.org)
        /** One GET of "/" for the title and headers: an active step; null unless it answered with a status line. */
        private suspend fun get(useTls: Boolean): HttpPeek? {
            val v4 = ip as? Inet4Address ?: return null
            return active { http.fetch(v4, real, useTls) }?.takeIf { it.status > 0 }
        }

        suspend fun passiveOnly(speaksFirst: Boolean): ServiceInfo? = passiveRead()?.let { b ->
            if (speaksFirst) { val line = text(b).lineSequence().first(); BannerParsers.ssh(line) ?: BannerParsers.greeting(line, baseName) }
            else BannerParsers.classifyUnknown(b, baseName)
        }
        suspend fun headOnly(): ServiceInfo? {
            val passive = passiveRead()?.let { BannerParsers.classifyUnknown(it, baseName) }
            if (passive?.confidence == Confidence.CONFIRMED) return passive
            return active { head() } ?: passive
        }
        suspend fun httpSet(): ServiceInfo? {
            val h = head()                                        // today's step: never enters, never spends
            if (h != null) return withPeek(h, get(useTls = false))
            val cert = active { peek() } ?: return null
            return tlsThenGet(cert)
        }
        suspend fun tlsSet(): ServiceInfo? {
            val cert = peek()                                     // today's step: never enters, never spends
            if (cert != null) return withPeek(tlsInfo(cert, "HTTPS"), get(useTls = true))
            val h = active { head() } ?: return null
            return withPeek(h, get(useTls = false))
        }
        suspend fun rtsp(): ServiceInfo? = probeAfterPassive {
            reader.read(ip, real, BannerParsers.rtspOptions(ip.hostAddress ?: "", port))?.let { BannerParsers.rtspReply(text(it)) }
        }
        suspend fun rdp(): ServiceInfo? = probeAfterPassive { reader.read(ip, real, BannerParsers.RDP_CONNECTION_REQUEST)?.let { BannerParsers.rdpConfirm(it) } }
        private suspend fun probeAfterPassive(probe: suspend () -> ServiceInfo?): ServiceInfo? {
            val passive = passiveRead()?.let { BannerParsers.classifyUnknown(it, baseName) }
            if (passive?.confidence == Confidence.CONFIRMED) return passive
            return active { probe() } ?: passive
        }
        suspend fun unknown(): ServiceInfo? {
            val bytes = passiveRead()
            // Any byte at all means it spoke first: today's result, never TLS ("zero bytes" is exact, spec 2.2.3).
            if (bytes != null) return BannerParsers.classifyUnknown(bytes, baseName)
            // TLS before HEAD: a plain HEAD to a TLS port often comes back as an HTTP 400 and would be misread as HTTP.
            val cert = active { peek() }
            if (cert != null) return tlsThenGet(cert)
            val h = active { head() } ?: return null
            return withPeek(h, get(useTls = false))
        }
        /** A handshake on a port outside the TLS set: HTTPS when its GET answers with a status line, TLS when not. */
        private suspend fun tlsThenGet(cert: CertInfo): ServiceInfo {
            val p = get(useTls = true)
            return withPeek(tlsInfo(cert, if (p != null) "HTTPS" else "TLS"), p)
        }
        private fun withPeek(info: ServiceInfo, p: HttpPeek?): ServiceInfo {
            if (p == null) return info
            val server = p.server?.take(MAX_HEADER)
            val (product, version) = server?.let { BannerParsers.serverHeader(it) } ?: (null to null)
            return info.copy(product = info.product ?: product, version = info.version ?: version, title = p.title ?: info.title,
                server = server ?: info.server, realm = p.authRealm?.take(MAX_HEADER) ?: info.realm, poweredBy = p.poweredBy?.take(MAX_HEADER) ?: info.poweredBy)
        }
    }
}
