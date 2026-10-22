package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.common.HttpFailure
import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** spec 6.1 and 12.1: requests, the hop, every finding kind, hints with versions stripped, cookie values never stored. */
class HttpFingerprintTest {
    private val rules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_stacks.json")!!.readText())
    private fun target(tls: Boolean = false, port: Int = 5000) = ToolTarget(7, ip("10.0.3.9"), port, tls, "chnas.local")
    private fun ok(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList(), reason: String = "OK") =
        HttpResult.Ok(HttpResponse(status, reason, headers, body.toByteArray(), false, body.length.toLong()))
    private suspend fun run(get: HttpGet, t: ToolTarget = target()): Pair<com.northwalessolutions.netprobe.engine.tools.ToolResult, CollectingProgress> {
        val p = CollectingProgress(); return HttpFingerprintTool({ get }, rules) { "abcdef012345" }.run(t, ToolOptions.None, control(), p) to p
    }

    @Test fun `a root GET lost to a pause is retried once after the resume`() = runTest {
        val c = ToolTestSupport.EpochControl(); var roots = 0
        val get = HttpGet { _, _, path ->
            if (path == "/" && roots++ == 0) { c.epoch++; HttpResult.Failed(HttpFailure.Timeout) }      // a pause began mid-request
            else if (path == "/") ok(200, "<title>NAS</title>") else ok(404)
        }
        val p = CollectingProgress()
        val r = HttpFingerprintTool({ get }, rules) { "abcdef012345" }.run(target(), ToolOptions.None, c, p)
        assertEquals(ToolRunStatus.DONE, r.status); assertEquals("200 OK", p.of(FindingKind.STATUS).first().value)
    }
    @Test fun `names synology from the title and strips the version in the framework hint`() = runTest {
        val (r, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "<title>Synology DiskStation 7</title>", listOf("Server" to "nginx/1.25")) else ok(404, reason = "Not Found") })
        assertEquals(ToolRunStatus.DONE, r.status)
        assertEquals(listOf("Synology DSM", "nginx 1.25"), p.of(FindingKind.FRAMEWORK).map { it.value })
        assertEquals("Matched title: Synology DiskStation 7", p.of(FindingKind.FRAMEWORK).first().detail)
        assertTrue("http.framework=Synology DSM" in p.hints()); assertTrue("http.framework=nginx" in p.hints())
        assertTrue("http.server=nginx/1.25" in p.hints()); assertTrue("http.title=Synology DiskStation 7" in p.hints())
        assertEquals("Synology DSM, ${p.findings.count { it.kind != FindingKind.HINT }} findings", r.summary)
    }
    @Test fun `status and headers carry the name as sent`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "", listOf("server" to "lighttpd", "X-Powered-By" to "PHP/8.2.7", "Via" to "1.1 proxy")) else ok(404) })
        assertEquals("200 OK", p.of(FindingKind.STATUS).single().value); assertEquals("GET /", p.of(FindingKind.STATUS).single().detail)
        assertEquals("http://10.0.3.9:5000/", p.of(FindingKind.STATUS).single().url)
        assertEquals(listOf("server" to "lighttpd", "X-Powered-By" to "PHP/8.2.7", "Via" to "1.1 proxy"), p.of(FindingKind.HEADER).map { it.detail to it.value })
        assertTrue("http.poweredBy=PHP/8.2.7" in p.hints())
    }
    @Test fun `cookie names appear with their flags but never their values`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "", listOf("Set-Cookie" to "PHPSESSID=secretvalue; Path=/; Secure; HttpOnly; SameSite=Lax")) else ok(404) })
        val c = p.of(FindingKind.COOKIE).single()
        assertEquals("PHPSESSID", c.value); assertEquals("Cookie, Secure, HttpOnly, SameSite=Lax", c.detail)
        assertTrue("no cookie value anywhere", p.findings.none { it.value.contains("secretvalue") || it.detail.orEmpty().contains("secretvalue") || it.extra.values.any { v -> v.contains("secretvalue") } })
        assertTrue("http.cookie=PHPSESSID" in p.hints())
    }
    @Test fun `generator, sitemap, security txt and the error page signature`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> when (path) {
            "/" -> ok(200, "<meta name=\"generator\" content=\"WordPress 6.4.2\">")
            "/sitemap.xml" -> ok(200, "<urlset><loc>a</loc><loc>b</loc></urlset>")
            "/.well-known/security.txt" -> ok(200, "Contact: mailto:sec@example.com\nExpires: 2027-01-01")
            else -> ok(404, "<html><address>Apache/2.4.57 (Debian) Server at 10.0.3.9 Port 5000</address></html>", reason = "Not Found")
        } })
        assertEquals("WordPress 6.4.2", p.of(FindingKind.GENERATOR).single().value)
        assertEquals("2 URLs", p.of(FindingKind.SITEMAP).single().detail)
        assertEquals("Contact: mailto:sec@example.com", p.of(FindingKind.SECURITY_TXT).single().detail)
        assertEquals("Apache/2.4.57 (Debian) Server at 10.0.3.9 Port 5000", p.of(FindingKind.ERROR_PAGE).single().value)
        assertTrue("WordPress 6.4.2" in p.of(FindingKind.FRAMEWORK).map { it.value }); assertTrue("http.framework=WordPress" in p.hints())
    }
    @Test fun `robots rules are counted and paths capped at fifty, wildcards without a url`() = runTest {
        val robots = (1..79).joinToString("\n") { "Disallow: /p$it" } + "\nAllow: /pub*"
        val (_, p) = run(HttpGet { _, _, path -> when (path) { "/" -> ok(200); "/robots.txt" -> ok(200, robots); else -> ok(404) } })
        assertEquals("80 rules", p.of(FindingKind.ROBOTS).single().detail)
        assertEquals(50, p.of(FindingKind.PATH).size)
        assertEquals("Disallowed in robots.txt", p.of(FindingKind.PATH).first().detail)
    }
    @Test fun `a file answering anything but 200 produces nothing`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200) else ok(403, "nope", reason = "Forbidden") })
        assertTrue(p.of(FindingKind.ROBOTS).isEmpty()); assertTrue(p.of(FindingKind.SITEMAP).isEmpty()); assertTrue(p.of(FindingKind.SECURITY_TXT).isEmpty())
    }
    @Test fun `a redirect on slash to another scheme and port on the same ip is followed once`() = runTest {
        val seen = mutableListOf<Triple<Boolean, Int, String>>()
        val (_, p) = run(HttpGet { tls, port, path -> seen += Triple(tls, port, path); when {
            !tls && path == "/" -> ok(302, "", listOf("Location" to "https://10.0.3.9:5001/"), reason = "Found")
            else -> ok(200, "<title>Landed</title>")
        } })
        assertEquals(Triple(true, 5001, "/"), seen[1])
        assertTrue("robots, sitemap, security.txt and the error page went to 5001", seen.drop(2).all { it.first && it.second == 5001 })
        val statuses = p.of(FindingKind.STATUS)
        assertEquals("GET /, to https://10.0.3.9:5001/", statuses[0].detail); assertEquals("GET https://10.0.3.9:5001/", statuses[1].detail)
        assertEquals(6, p.lastTotal)
    }
    @Test fun `a redirect to another ip is not followed`() = runTest {
        val seen = mutableListOf<Int>()
        run(HttpGet { _, port, path -> seen += port; if (path == "/") ok(302, "", listOf("Location" to "http://10.0.3.99/")) else ok(404) })
        assertEquals(5, seen.size); assertTrue(seen.all { it == 5000 })
    }
    @Test fun `a location with a space or control character is not followed`() = runTest {
        for (bad in listOf("/a b", "/a\u0001b", "https://10.0.3.9:5001/x y")) {
            val seen = mutableListOf<String>()
            run(HttpGet { _, _, path -> seen += path; if (path == "/") ok(302, "", listOf("Location" to bad)) else ok(404) })
            assertEquals(bad, 5, seen.size)
        }
    }
    @Test fun `a location naming the server name is followed and a query string is kept`() = runTest {
        val seen = mutableListOf<Triple<Boolean, Int, String>>()
        run(HttpGet { tls, port, path -> seen += Triple(tls, port, path); if (path == "/") ok(302, "", listOf("Location" to "http://CHNAS.local:8080/a?b=1#frag")) else ok(200) })
        assertEquals(Triple(false, 8080, "/a?b=1"), seen[1])
    }
    @Test fun `a different hostname, a protocol relative path and a bad port are not followed`() = runTest {
        for (bad in listOf("http://other.local/", "//10.0.3.9/x", "http://10.0.3.9:0/", "http://10.0.3.9:70000/", "http://10.0.3.9:99999999999/")) {
            val seen = mutableListOf<String>()
            run(HttpGet { _, _, path -> seen += path; if (path == "/") ok(302, "", listOf("Location" to bad)) else ok(404) })
            assertEquals(bad, 5, seen.size)
        }
    }
    @Test fun `a fragment on a relative location is stripped`() = runTest {
        val seen = mutableListOf<String>()
        run(HttpGet { _, _, path -> seen += path; if (path == "/") ok(302, "", listOf("Location" to "/login#top")) else ok(200) })
        assertEquals("/login", seen[1])
    }
    @Test fun `server and powered-by hints come from the hop page too`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> when {
            path == "/" -> ok(302, "", listOf("Location" to "/home"))
            path == "/home" -> ok(200, "", listOf("Server" to "lighttpd/1.4", "X-Powered-By" to "PHP/8"))
            else -> ok(404)
        } })
        assertTrue("http.server=lighttpd/1.4" in p.hints()); assertTrue("http.poweredBy=PHP/8" in p.hints())
    }
    @Test fun `a generator meta with content before name is found`() = runTest {
        val (_, p) = run(HttpGet { _, _, path -> if (path == "/") ok(200, "<meta content='Hugo 0.1' name='generator'>") else ok(404) })
        assertEquals("Hugo 0.1", p.of(FindingKind.GENERATOR).single().value)
    }
    @Test fun `no response on slash is FAILED`() = runTest {
        val (r, _) = run(HttpGet { _, _, _ -> HttpResult.Failed(HttpFailure.Timeout) })
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("No HTTP response on this port", r.summary)
    }
}
