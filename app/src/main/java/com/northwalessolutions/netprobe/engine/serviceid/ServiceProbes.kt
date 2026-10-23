package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.common.HttpClient
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import java.net.Inet4Address
import java.net.InetAddress

/** What one port's ladder produced: the best finding (null when nothing beyond the port table), the soft and hard hits, and the probe ids sent. */
class LadderResult(val info: ServiceInfo?, val hits: List<ProbeHit>, val probesSent: List<String>, val stackHits: List<StackHit> = emptyList(),
                   /** One entry per probe the ladder ran (null read, TLS check, hellos, the HTTP GET), for the Identify service tool. */
                   val trace: List<ProbeTrace> = emptyList())

/** What one probe got: the bytes read (0 for none) and the name it matched, if any. */
class ProbeTrace(val id: String, val bytes: Int, val match: String?)

/**
 * The nmap-style service ladder (Phase 3E spec 3 and 6). Data drives it: [probes] come from `service_probes.json`. The port table
 * guess stays the floor (the caller keeps it), and a refused connect, a spent cap or a spent budget ends the ladder with the best
 * result so far, which is never below what the Phase 3A steps (the first probes in the file) found.
 *
 * Order for an open port: null probe (read what the port says first), the TLS check, the ordered probes (hinted ones in file order,
 * then safe rarity 1 to 3 by rarity), then the HTTP tier (one GET for title and headers, over TLS when the port spoke TLS).
 */
class ServiceProbes(
    private val probes: List<ServiceProbe>,
    private val newTransport: () -> ProbeTransport = { SocketTransport() },
    private val http: HttpTitleFetcher = HttpTitleFetcher(timeoutMs = HTTP_FETCH_TIMEOUT_MS),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val rand: () -> String = ::randomToken,
    /** The HTTP stack rules of `http_stacks.json`; empty turns the stack pass off. */
    private val stackRules: List<HttpStackRule> = emptyList(),
    /** The extra idempotent GETs a stack rule may spend (paths, favicon) on one port; tests script it. */
    private val stackGetFor: (Inet4Address, Int, Boolean) -> StackGet = ::httpClientStackGet,
) {
    companion object {
        const val NULL_WAIT_MS = 1000
        const val CONNECT_TIMEOUT_MS = 1000
        const val TLS_TIMEOUT_MS = 1500
        const val HTTP_FETCH_TIMEOUT_MS = 2000
        const val UNHINTED_WAIT_MS = 400
        const val MAX_PROBES_PER_PORT = LadderLimits.SCAN_MAX_CONNECTS
        const val PORT_WALL_MS = LadderLimits.SCAN_PORT_WALL_MS
        const val DEFAULT_MAX_RARITY = LadderLimits.SCAN_MAX_RARITY
        /** null, tls, http-head, http-get, rtsp-options, rdp-x224: the Phase 3A steps, the first probes of the file. */
        const val THREE_A_PROBES = 6
        private const val MIN_STEP_MS = 50L
        private const val MAX_HEADER = 120
        private const val NANOS_PER_MS = 1_000_000L
        private const val TOKEN_LENGTH = 8
        private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Speak first (banner only), so never offered a TLS ClientHello. */
        val PLAIN_ONLY: Set<Int> = setOf(21, 22, 25, 110, 143, 587, 2222)
        /** Raw print, LPD, Cisco SCCP, X11 and the mail ports: the null probe only, nothing else ever. */
        val FRAGILE: Set<Int> = setOf(25, 110, 143, 587, 515, 2000, 2001) + (6000..6063) + (9100..9107)
        /** Fieldbus ports: no connect at all except a hinted careful probe made for the port. */
        val INDUSTRIAL: Set<Int> = setOf(102, 502, 44818, 47808)
        /** IPP: the null read and its hinted HEAD, no other hello, no TLS, no GET. */
        val HINT_ONLY: Set<Int> = setOf(631)

        fun randomToken(): String = (1..TOKEN_LENGTH).map { TOKEN_CHARS.random() }.joinToString("")

        /** The real stack GET: HttpClient (no redirects, body cut at [maxBytes]), the TLS context made only when a GET is actually made. */
        fun httpClientStackGet(ip: Inet4Address, port: Int, tls: Boolean): StackGet {
            val ctx by lazy { HttpClient.trustAllContext() }
            return StackGet { path, maxBytes ->
                val r = HttpClient(HTTP_FETCH_TIMEOUT_MS, maxBytes).request(ip, port, tls, "GET", path, null, if (tls) ctx else null)
                (r as? HttpResult.Ok)?.response?.let { StackReply(it.status, it.body) }
            }
        }
    }

    private val tlsProbe: ServiceProbe? = probes.firstOrNull { it.builtin == ServiceProbeRules.BUILTIN_TLS }
    private val nullMatchers: List<ServiceProbe> = probes.filter { it.matchesNullReply }

    private fun text(b: ByteArray) = String(b, Charsets.ISO_8859_1)

    /** Runs the ladder for [port] ([real] is the port actually connected to, equal in production). */
    suspend fun run(ip: InetAddress, port: Int, real: Int, baseName: String, mode: ProbeMode, hostId: Long = 0L, onProbe: (Int) -> Unit = {}): LadderResult =
        Ladder(ip, port, real, baseName, (mode as? ProbeMode.Active)?.budget, hostId, (mode as? ProbeMode.Active)?.hellos ?: true, onProbe,
            (mode as? ProbeMode.Active)?.limits ?: LadderLimits.SCAN).run()

    /** How many connections the full ladder may make on [port] under [limits]: the null read plus the planned probes, capped by [LadderLimits.maxConnects]. */
    fun plannedProbeCount(port: Int, limits: LadderLimits): Int = minOf(limits.maxConnects, uncappedCount(port, true, limits))

    private fun uncappedCount(port: Int, hellos: Boolean, limits: LadderLimits): Int =
        (if (port in INDUSTRIAL) 0 else 1) + plan(port, hellos, limits).size

    private fun plan(port: Int, hellos: Boolean, limits: LadderLimits): List<ServiceProbe> {
        if (port in FRAGILE && !limits.allHellos) return emptyList()
        if (!hellos) return threeAPlan(port)
        val industrial = port in INDUSTRIAL
        // A manual run with unsafe hellos off sends no CAREFUL probe, hinted or not, except the industrial careful-only rule below.
        val safeOnly = limits.allHellos && !limits.includeUnsafe && !industrial
        val hinted = probes.filter { port in it.ports && (!safeOnly || it.safety == ProbeSafety.SAFE) }
        val everyHello = !industrial || limits.includeUnsafe
        val rest: List<ServiceProbe> =
            if (!everyHello || (port in HINT_ONLY && !limits.allHellos)) emptyList()
            else {
                val others = probes.filter { it !in hinted && it.rarity <= limits.maxRarity }
                others.filter { it.safety == ProbeSafety.SAFE }.sortedBy { it.rarity } +
                    (if (limits.includeUnsafe) others.filter { it.safety == ProbeSafety.CAREFUL }.sortedBy { it.rarity } else emptyList())
            }
        val all = (hinted + rest).filter { it.builtin == ServiceProbeRules.BUILTIN_TLS || it.sendsBytes }
        return if (industrial && !limits.includeUnsafe) all.filter { it.safety == ProbeSafety.CAREFUL } else all
    }

    /** The Settings switch off: only the first six probes of the file, each on its hinted ports as in 3A, and the TLS check on any port. */
    private fun threeAPlan(port: Int): List<ServiceProbe> {
        if (port in INDUSTRIAL) return emptyList()
        return probes.take(THREE_A_PROBES).filter { (port in it.ports || it.builtin == ServiceProbeRules.BUILTIN_TLS) && (it.builtin == ServiceProbeRules.BUILTIN_TLS || it.sendsBytes) }
    }

    private inner class Ladder(private val ip: InetAddress, private val port: Int, private val real: Int, private val baseName: String,
                               private val budget: ActiveProbeBudget?, private val hostId: Long, private val hellos: Boolean = true,
                               private val onProbe: (Int) -> Unit = {}, private val limits: LadderLimits = LadderLimits.SCAN) {
        private val trace = mutableListOf<ProbeTrace>()
        private fun traced(id: String, bytes: Int, hit: ProbeHit?): Unit = traced(id, bytes, hit?.let { listOfNotNull(it.service, it.product, it.version).fold(listOf<String>()) { acc, p -> if (acc.lastOrNull().equals(p, ignoreCase = true)) acc else acc + p }.joinToString(" ") })
        private fun traced(id: String, bytes: Int, match: String?) { trace += ProbeTrace(id, bytes, match); onProbe(trace.size) }
        private val start = clock()
        private var connects = 0
        private var ended = false
        private var refused = false
        private var best: ServiceInfo? = null
        private var tlsOn = false
        private var narrowed: Set<String>? = null
        private var nullReplied = false
        private var getDone = false
        private val sent = mutableListOf<String>()
        private val sentIds = HashSet<String>()
        private val hits = mutableListOf<ProbeHit>()
        private var stackHits: List<StackHit> = emptyList()

        private val industrial = port in INDUSTRIAL
        private val fragile = port in FRAGILE
        private val hintOnly = port in HINT_ONLY
        private val ip4: Inet4Address? get() = ip as? Inet4Address

        private val connectCap: Int by lazy { minOf(limits.maxConnects, uncappedCount(port, hellos, limits)) }
        private fun elapsed() = clock() - start
        private fun remainingMs() = limits.portWallMs - elapsed()
        /** Another connection may start: port caps, host wall (Active only). */
        private fun canConnect(): Boolean =
            !ended && connects < connectCap && remainingMs() >= MIN_STEP_MS && (budget == null || budget.hostAllows(hostId))
        private fun clip(ms: Int): Int = minOf(ms.toLong(), remainingMs()).toInt().coerceAtLeast(1)

        /** Times one connection step and charges the host's wall. */
        private suspend fun <T> timed(block: suspend () -> T): T {
            val t0 = clock()
            try { return block() } finally { budget?.addHostTime(hostId, clock() - t0) }
        }
        /** A step that counts against the scan budget; null when the budget refused it (Passive mode never reaches here). */
        private suspend fun <T> counted(block: suspend () -> T): T? {
            val b = budget ?: return null
            if (!b.tryEnter(hostId, port)) return null
            return b.step { timed(block) }
        }

        suspend fun run(): LadderResult {
            if (budget == null) passive() else active()
            return LadderResult(best, hits, sent, stackHits, trace.toList())
        }

        // ---- Quick: the null probe, or the one 3A step the port is hinted for ----
        private suspend fun passive() {
            if (industrial) return
            val quick = probes.firstOrNull { it.quick && port in it.ports }
            if (quick != null) { runStep(quick); return }
            nullStep()
        }

        // ---- Full and Custom ----
        private suspend fun active() {
            if (!industrial) {
                timed { nullStep() }
                if (ended) { finishHttpTier(); return }
            }
            for (p in plan(port, hellos, limits)) {
                if (ended || !canConnect()) break
                if (!admissible(p)) continue
                runStep(p)
            }
            finishHttpTier()
        }

        private fun admissible(p: ServiceProbe): Boolean {
            if (p.id in sentIds) return false
            narrowed?.let { if (p.id !in it) return false }
            if (p.builtin == ServiceProbeRules.BUILTIN_TLS) return !plainOnlyNow() && !tlsOn
            if (tlsOn) return false                        // inside TLS only the HTTP tier speaks (one fetch, so two handshakes at most)
            if (p.tls == TlsMode.ONLY) return false
            if (p.safety == ProbeSafety.CAREFUL && port !in p.ports && !limits.includeUnsafe) return false
            return true
        }
        private fun plainOnlyNow(): Boolean = port in PLAIN_ONLY || (fragile && !limits.allHellos) || nullReplied || (hintOnly && !limits.allHellos)

        /** Free steps are the null probe and the 3A steps (quick probes on their own hinted ports); everything else is counted. */
        private suspend fun runStep(p: ServiceProbe) {
            val free = budget == null || (p.quick && port in p.ports)
            if (free) { if (budget != null) timed { exec(p) } else exec(p) }
            else if (counted { exec(p) } == null) ended = true      // budget refused: keep the best result so far
        }

        private suspend fun exec(p: ServiceProbe) {
            sentIds += p.id
            if (p.builtin == ServiceProbeRules.BUILTIN_TLS) tlsStep(p) else rawStep(p)
        }

        // ---- the null probe ----
        private suspend fun nullStep() {
            connects++
            val t = newTransport()
            try {
                val o = t.connect(ip, real, clip(CONNECT_TIMEOUT_MS))
                if (o == ConnectOutcome.REFUSED || o == ConnectOutcome.UNREACHABLE) { ended = true; refused = true; return }
                if (o != ConnectOutcome.CONNECTED) return
                val bytes = t.read(ServiceProbeRules.MAX_REPLY, clip(NULL_WAIT_MS), stopAtNewline = true)
                if (bytes == null) {
                    traced("null", 0, null as String?)
                    if (t.peerClosed) { best = ServiceInfo(if (baseName == "Unknown") "TCP" else baseName, detail = "tcpwrapped", confidence = Confidence.PORT_TABLE); ended = true }
                    return
                }
                nullReplied = true
                val hit = nullMatchers.firstNotNullOfOrNull { p -> p.match(bytes) }
                traced("null", bytes.size, hit)
                if (hit != null) { accept(hit, text(bytes)); return }
                best = legacyBanner(bytes)
                if ((fragile && !limits.allHellos) || industrial || budget == null) ended = true
            } finally { t.close() }
        }

        /** Today's reading of a banner nothing in the file named (3A): ssh, a mail or FTP greeting, HTTP, or the printable first line. */
        private fun legacyBanner(bytes: ByteArray): ServiceInfo {
            val line = text(bytes).lineSequence().first()
            return BannerParsers.ssh(line) ?: BannerParsers.greeting(line, baseName) ?: BannerParsers.classifyUnknown(bytes, baseName)
        }

        // ---- a TLS check: one handshake, certificate fields kept ----
        private suspend fun tlsStep(p: ServiceProbe) {
            connects++
            val t = newTransport()
            try {
                when (t.connectTls(ip, real, clip(TLS_TIMEOUT_MS))) {
                    ConnectOutcome.CONNECTED -> {
                        tlsOn = true
                        val c = t.peerCert() ?: CertInfo(null, null, emptyList())
                        val name = if (port in p.ports) "HTTPS" else "TLS"
                        val tlsInfo = ServiceInfo(name, detail = c.names.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "cert $it" },
                            confidence = Confidence.CONFIRMED, certCn = c.cn, certOrg = c.org, probe = p.id)
                        best = tlsInfo
                        sent += p.id
                        traced(p.id, 0, "TLS")
                    }
                    ConnectOutcome.REFUSED, ConnectOutcome.UNREACHABLE -> { ended = true; refused = true }
                    else -> sent += p.id
                }
            } finally { t.close() }
        }

        // ---- a raw hello: write, read, match ----
        private suspend fun rawStep(p: ServiceProbe) {
            connects++
            sent += p.id
            val t = newTransport()
            try {
                val o = t.connect(ip, real, clip(CONNECT_TIMEOUT_MS))
                if (o == ConnectOutcome.REFUSED || o == ConnectOutcome.UNREACHABLE) { ended = true; refused = true; return }
                if (o != ConnectOutcome.CONNECTED) return
                t.write(p.sendBytes(ip.hostAddress ?: "", port, rand()))
                val hinted = port in p.ports
                val wait = clip(if (hinted) p.waitMs else minOf(p.waitMs, UNHINTED_WAIT_MS))
                val bytes = t.read(p.maxBytes, wait)
                val hit = bytes?.let { p.match(it) }
                traced(p.id, bytes?.size ?: 0, hit)
                if (bytes == null || hit == null) return
                accept(hit, text(bytes))
            } finally { t.close() }
        }

        /** A hard hit ends the ladder; a soft hit keeps the best finding and narrows the rest to its `narrow` ids. */
        private fun accept(hit: ProbeHit, replyText: String) {
            hits += hit
            val base = ServiceInfo(hit.service, hit.product, hit.version, hit.detail, Confidence.CONFIRMED, stack = hit.stack,
                stackRole = hit.stack?.let { hit.role.name.lowercase() }, cpe = hit.cpeProduct, probe = hit.probeId)
            val info = enrich(base, replyText)
            if (hit.soft) {
                if (best == null || best?.confidence != Confidence.CONFIRMED) best = info
                if (hit.narrow.isNotEmpty()) narrowed = hit.narrow.toSet()
            } else { best = info; ended = true }
        }

        /** HTTP, IPP and RTSP replies carry the Server header, realm and X-Powered-By; their old parsers fill what a regex cannot (spec 13). */
        private fun enrich(info: ServiceInfo, replyText: String): ServiceInfo {
            val parsed = when {
                (info.name == "HTTP" || info.name == "IPP") && replyText.startsWith("HTTP/") -> BannerParsers.httpHead(replyText)
                info.name == "RTSP" && replyText.startsWith("RTSP/") -> BannerParsers.rtspReply(replyText)
                else -> null
            } ?: return info
            return info.copy(product = parsed.product ?: info.product, version = parsed.version ?: info.version, detail = parsed.detail ?: info.detail,
                server = parsed.server, realm = parsed.realm, poweredBy = parsed.poweredBy)
        }

        // ---- the HTTP tier: one GET for title and headers ----
        private suspend fun finishHttpTier() {
            if (budget == null || getDone || refused || (fragile && !limits.allHellos) || industrial || (hintOnly && !limits.allHellos)) return
            val current = best
            val isHttp = current?.name == "HTTP" || current?.name == "HTTPS" || tlsOn
            if (!isHttp || remainingMs() < MIN_STEP_MS) return
            val v4 = ip4 ?: return
            getDone = true
            val peek = counted { http.fetch(v4, real, tlsOn) }?.takeIf { it.status > 0 }
            if (peek != null) { sent += ServiceProbeRules.BUILTIN_HTTP_GET; traced(ServiceProbeRules.BUILTIN_HTTP_GET, peek.body.length, "HTTP ${peek.status}") }
            val base = current ?: return
            val named = if (tlsOn && peek != null && base.name == "TLS") base.copy(name = "HTTPS") else base
            best = withPeek(named, peek)
            if (peek != null) best = stackPass(best ?: return, peek, v4)
        }

        /** The stack pass over the GET already made (never refetched); the first hard hit names the stack, every hit is kept (spec 5). */
        private suspend fun stackPass(info: ServiceInfo, peek: HttpPeek, v4: Inet4Address): ServiceInfo {
            if (stackRules.isEmpty()) return info
            val base = StackSources(peek.headers, peek.cookies, peek.title, HttpStacks.generator(peek.body), peek.body, "", peek.status)
            val raw = stackGetFor(v4, real, tlsOn)
            val get = StackGet { path, maxBytes -> if (hellos) counted { raw.get(path, maxBytes) } else null }
            val canSpend = { remainingMs() >= MIN_STEP_MS && (budget == null || (budget.allowsStep() && budget.hostAllows(hostId))) }
            val result = HttpStacks.run(stackRules, base, true, get, canSpend)
            stackHits = result.hits
            val hard = result.hard ?: return info
            val rule = hard.rule
            // A version belongs to the product that sent it: the stack's own capture replaces the Server header's pair, and a CPE is kept
            // only when the version it will be built with is the stack's or the header's product is that very product.
            val own = hard.version != null
            val keepCpe = rule.cpeProduct != null && (own || info.product.equals(rule.cpeProduct, ignoreCase = true))
            return info.copy(
                product = if (own) rule.product ?: hard.stack else info.product, version = if (own) hard.version else info.version,
                stack = hard.stack, stackRole = rule.role.name.lowercase(), cpe = if (keepCpe) rule.cpeProduct else null, probe = rule.id)
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
