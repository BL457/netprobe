package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleParser
import com.northwalessolutions.netprobe.engine.serviceid.HttpStackRule
import com.northwalessolutions.netprobe.engine.serviceid.HttpStacks
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import com.northwalessolutions.netprobe.engine.tools.dirbrute.randomToken
import com.northwalessolutions.netprobe.ui.tools.ToolCopy
import java.net.Inet4Address

/** One GET through HttpClient with the target's name as Host; [tls] and [port] can differ from the target after the redirect hop. */
fun interface HttpGet { suspend fun get(tls: Boolean, port: Int, path: String): HttpResult }

/**
 * spec 4.9: one exchange with the host's known name; when that is a TLS handshake failure (the name may not suit the device's certificate
 * setup), the same exchange once more on the IP. Timeouts, refusals and plain HTTP are never retried: a dead host costs one wait, not two.
 */
suspend fun namedThenIp(tls: Boolean, serverName: String?, exchange: suspend (name: String?) -> HttpResult): HttpResult {
    val named = exchange(serverName)
    val handshake = named is HttpResult.Failed && named.failure == HttpFailure.Handshake
    return if (serverName != null && tls && handshake) exchange(null) else named
}

/** HTTP fingerprint (spec 6.1): GET /, one redirect hop on the same IP, then robots.txt, sitemap.xml, security.txt and a random error page. */
class HttpFingerprintTool(
    private val getFor: (ToolTarget) -> HttpGet,
    private val rules: List<HttpStackRule>,
    private val random: () -> String = ::randomToken,
) : Tool {
    override val id = ToolId.HTTP_FINGERPRINT
    override val name = ToolCopy.HTTP_FINGERPRINT
    override fun applies(port: Int, service: ServiceInfo) = ToolApplicability.appliesTo(ToolId.HTTP_FINGERPRINT, port, service)

    private class Counter(var done: Int = 0, var total: Int = STEPS, var found: Int = 0)

    override suspend fun run(target: ToolTarget, options: ToolOptions, control: RunControl, progress: ToolProgress): ToolResult {
        val get = getFor(target)
        val n = Counter()
        suspend fun emit(f: Finding) { n.found++; progress.finding(f); progress.update(n.done, n.total, n.found) }
        suspend fun hint(v: String) { progress.finding(Finding(FindingKind.HINT, v)) }
        suspend fun fetch(tls: Boolean, port: Int, path: String): HttpResult {
            control.gate()
            val r = control.excusedOnce<HttpResult>({ it is HttpResult.Failed }) { get.get(tls, port, path) }   // spec 4.8 (plan review G5)
            n.done++; progress.update(n.done, n.total, n.found); return r
        }

        val root = (fetch(target.tls, target.port, "/") as? HttpResult.Ok)?.response ?: return ToolResult(ToolRunStatus.FAILED, ToolCopy.FINGERPRINT_NO_RESPONSE)
        var pageTls = target.tls; var pagePort = target.port; var page = root
        val location = if (root.status in REDIRECT_STATUSES) root.header("Location") else null
        val hop = location?.let { sameHostHop(it, target) }
        emit(Finding(FindingKind.STATUS, statusText(root), if (hop != null) ToolCopy.getRootTo(location) else ToolCopy.GET_ROOT, url(target.tls, target.ip, target.port, "/")))
        if (hop != null) {
            n.total = STEPS + 1
            val hopUrl = url(hop.tls, target.ip, hop.port, hop.path)
            val r = fetch(hop.tls, hop.port, hop.path)
            if (r is HttpResult.Ok) {
                emit(Finding(FindingKind.STATUS, statusText(r.response), ToolCopy.getUrl(hopUrl), hopUrl))
                page = r.response; pageTls = hop.tls; pagePort = hop.port
            }
        }
        val responses = if (page === root) listOf(root) else listOf(root, page)
        val headers = responses.flatMap { it.headers }.distinct()
        for (name in HEADER_NAMES) for ((sent, value) in headers.filter { it.first.equals(name, ignoreCase = true) }) emit(Finding(FindingKind.HEADER, value, sent))
        val cookies = responses.flatMap { it.headersAll("Set-Cookie") }.mapNotNull(::parseCookie).distinctBy { it.name }
        for (c in cookies) { emit(Finding(FindingKind.COOKIE, c.name, ToolCopy.cookieDetail(c.secure, c.httpOnly, c.sameSite))); hint("http.cookie=${c.name}") }

        val pageBody = String(page.body, Charsets.UTF_8)
        val title = HttpTitleParser.title(pageBody)
        title?.let { emit(Finding(FindingKind.TITLE, it, ToolCopy.PAGE_TITLE)); hint("http.title=$it") }
        // HttpStacks.generator scans a bounded span per meta tag, so a hostile page cannot make the match backtrack over the body.
        val generator = HttpStacks.generator(pageBody)
        generator?.let { emit(Finding(FindingKind.GENERATOR, it, ToolCopy.GENERATOR_META)); hint("http.generator=$it") }

        textAt(fetch(pageTls, pagePort, "/robots.txt"))?.let { body ->
            val rulesFound = ROBOTS_RULE.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
            emit(Finding(FindingKind.ROBOTS, "/robots.txt", ToolCopy.rules(rulesFound.size), url(pageTls, target.ip, pagePort, "/robots.txt")))
            for ((kind, path) in rulesFound.take(MAX_ROBOTS_PATHS)) {
                val pathUrl = if (path.contains('*') || path.contains('$')) null else url(pageTls, target.ip, pagePort, path)
                emit(Finding(FindingKind.PATH, path, if (kind.equals("Disallow", ignoreCase = true)) ToolCopy.DISALLOWED else ToolCopy.ALLOWED, pathUrl))
            }
        }
        textAt(fetch(pageTls, pagePort, "/sitemap.xml"))?.let { body ->
            emit(Finding(FindingKind.SITEMAP, "/sitemap.xml", ToolCopy.urls(LOC.findAll(body).count()), url(pageTls, target.ip, pagePort, "/sitemap.xml")))
        }
        textAt(fetch(pageTls, pagePort, "/.well-known/security.txt"))?.let { body ->
            val contact = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Contact:", ignoreCase = true) }?.substringAfter(':')?.trim()?.ifEmpty { null }
            emit(Finding(FindingKind.SECURITY_TXT, "/.well-known/security.txt", contact?.let { ToolCopy.contact(it) } ?: ToolCopy.PRESENT, url(pageTls, target.ip, pagePort, "/.well-known/security.txt")))
        }
        val errorBody = (fetch(pageTls, pagePort, "/" + random()) as? HttpResult.Ok)?.response?.let { String(it.body, Charsets.UTF_8) }.orEmpty()
        ERROR_SIGNATURE.find(errorBody)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.trim()?.let { emit(Finding(FindingKind.ERROR_PAGE, it, ToolCopy.ERROR_SIGNATURE)) }

        val sources = FpSources(headers, cookies.map { it.name }, title, generator, pageBody, errorBody, page.status)
        val names = LinkedHashSet<String>()
        for (rule in rules) {
            val m = FingerprintRules.match(rule, sources) ?: continue
            if (!names.add(m.name)) continue
            emit(Finding(FindingKind.FRAMEWORK, m.name, ToolCopy.matched(rule.source, m.excerpt)))
            hint("http.framework=${m.name.replace(VERSION_TAIL, "")}")
        }
        fun headerOf(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second   // root first, then the hop page
        headerOf("Server")?.let { hint("http.server=$it") }
        headerOf("X-Powered-By")?.let { hint("http.poweredBy=$it") }
        headerOf("WWW-Authenticate")?.let { REALM.find(it)?.groupValues?.get(1)?.let { realm -> hint("http.realm=$realm") } }
        return ToolResult(ToolRunStatus.DONE, ToolCopy.fingerprintSummary(names.firstOrNull() ?: headerOf("Server"), n.found))
    }

    private data class Cookie(val name: String, val secure: Boolean, val httpOnly: Boolean, val sameSite: String?)
    private data class Hop(val tls: Boolean, val port: Int, val path: String)

    /** Name and flags of one Set-Cookie header; the value is dropped here and never stored (spec 6.1). */
    private fun parseCookie(header: String): Cookie? {
        val parts = header.split(';').map { it.trim() }
        val name = parts.firstOrNull()?.substringBefore('=')?.trim()?.ifEmpty { null } ?: return null
        val attrs = parts.drop(1)
        return Cookie(name, attrs.any { it.equals("Secure", true) }, attrs.any { it.equals("HttpOnly", true) },
            attrs.firstOrNull { it.startsWith("SameSite=", true) }?.substringAfter('=')?.trim()?.ifEmpty { null })
    }
    /**
     * A Location on the same host (the target's IP or its known name) with any scheme and port, or a relative path; anything else is not
     * followed. The path comes from the device, so it must be a path only: a leading single slash and visible ASCII, no space or control
     * character (HttpClient would reject it as Malformed, and it must never reach a request head).
     */
    private fun sameHostHop(location: String, target: ToolTarget): Hop? {
        val noFragment = location.substringBefore('#')
        if (noFragment.startsWith("/")) return validPath(noFragment)?.let { Hop(target.tls, target.port, it) }
        val m = ABSOLUTE.matchEntire(noFragment) ?: return null
        val host = m.groupValues[2]
        if (host != target.ip.hostAddress && !host.equals(target.serverName, ignoreCase = true)) return null
        val tls = m.groupValues[1].equals("https", true)
        val path = validPath(m.groupValues[4].ifEmpty { "/" }) ?: return null
        val port = m.groupValues[3].ifEmpty { null }?.let { it.toIntOrNull() ?: return null } ?: if (tls) HTTPS_PORT else HTTP_PORT
        return if (port in MIN_PORT..MAX_PORT) Hop(tls, port, path) else null
    }
    private fun validPath(path: String): String? =
        path.takeIf { it.startsWith("/") && !it.startsWith("//") && it.all { c -> c.code in FIRST_VISIBLE_ASCII..LAST_VISIBLE_ASCII } }
    private fun textAt(r: HttpResult): String? = (r as? HttpResult.Ok)?.response?.takeIf { it.status == HTTP_OK }?.let { String(it.body, Charsets.UTF_8) }
    private fun statusText(r: HttpResponse) = if (r.reason.isEmpty()) "${r.status}" else "${r.status} ${r.reason}"
    private fun url(tls: Boolean, ip: Inet4Address, port: Int, path: String) = (if (tls) "https" else "http") + "://" + ip.hostAddress + ":" + port + path

    companion object {
        const val FINGERPRINT_TIMEOUT_MS = 3000; const val MAX_ROBOTS_PATHS = 50; const val STEPS = 5
        val HEADER_NAMES = listOf("Server", "X-Powered-By", "X-AspNet-Version", "X-AspNetMvc-Version", "X-Generator", "Via", "WWW-Authenticate")
        private const val MIN_PORT = 1; private const val MAX_PORT = 65535;
        private const val HTTP_OK = 200; private const val HTTP_PORT = 80; private const val HTTPS_PORT = 443
        private val REDIRECT_STATUSES = 300..399
        private const val FIRST_VISIBLE_ASCII = 0x21; private const val LAST_VISIBLE_ASCII = 0x7E
        private val ROBOTS_RULE = Regex("(?im)^\\s*(Disallow|Allow)\\s*:\\s*(\\S+)")
        private val LOC = Regex("<loc>", RegexOption.IGNORE_CASE)
        private val ERROR_SIGNATURE = Regex("<address>([^<]+)</address>|<center>(nginx[^<]*)</center>|(Apache Tomcat/[\\d.]+)", RegexOption.IGNORE_CASE)
        private val REALM = Regex("realm=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE)
        private val VERSION_TAIL = Regex("\\s+[\\d.]+$")
        private val ABSOLUTE = Regex("^(https?)://([^/:?#]+)(?::(\\d+))?(/[^#]*)?$", RegexOption.IGNORE_CASE)
    }
}
