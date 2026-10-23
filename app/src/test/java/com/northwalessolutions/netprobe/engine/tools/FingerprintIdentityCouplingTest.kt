package com.northwalessolutions.netprobe.engine.tools

import com.northwalessolutions.netprobe.engine.common.HttpResponse
import com.northwalessolutions.netprobe.engine.common.HttpResult
import com.northwalessolutions.netprobe.engine.discovery.DiscoveredHost
import com.northwalessolutions.netprobe.engine.identify.DeviceIdentifier
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.identify.HostEvidence
import com.northwalessolutions.netprobe.engine.identify.IdentifyRules
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.CollectingProgress
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.control
import com.northwalessolutions.netprobe.engine.tools.ToolTestSupport.ip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Hint-to-rule coupling: the hints the real tool emits for the seven named products set the vendor, model, os and type of the real identify rules. */
class FingerprintIdentityCouplingTest {
    private val fpRules = FingerprintRules.load(javaClass.classLoader!!.getResource("http_stacks.json")!!.readText())
    private val identifier = DeviceIdentifier(IdentifyRules.load(javaClass.classLoader!!.getResource("identify_rules.json")!!.readText()))
    private val host = ip("10.0.3.9")

    private fun page(body: String) = HttpGet { _, _, path ->
        val b = if (path == "/") body else ""
        HttpResult.Ok(HttpResponse(if (path == "/") 200 else 404, "", emptyList(), b.toByteArray(), false, b.length.toLong()))
    }
    private suspend fun hintsFor(body: String): List<String> {
        val p = CollectingProgress()
        HttpFingerprintTool({ page(body) }, fpRules) { "abcdef012345" }.run(ToolTarget(1, host, 80, false, null), ToolInputs.NONE, control(), p)
        return p.hints()
    }
    private suspend fun identify(body: String) = identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = hintsFor(body)))

    @Test fun `synology, ubiquiti and philips hue set vendor and type`() = runTest {
        identify("<title>Synology DiskStation</title>").let { assertEquals("Synology", it.vendor); assertEquals(DeviceType.NAS, it.type) }
        identify("<title>UniFi OS</title>").let { assertEquals("Ubiquiti", it.vendor); assertEquals(DeviceType.ROUTER, it.type) }
        identify("<html>hue personal wireless lighting</html>").let { assertEquals("Philips Hue", it.vendor); assertEquals(DeviceType.IOT, it.type) }
    }
    @Test fun `pi-hole, home assistant and proxmox set model and type`() = runTest {
        identify("<title>Pi-hole - Login</title>").let { assertEquals("Pi-hole", it.model); assertEquals(DeviceType.SERVER, it.type) }
        identify("<title>Home Assistant</title>").let { assertEquals("Home Assistant", it.model); assertEquals(DeviceType.SERVER, it.type) }
        identify("<title>pve - Proxmox Virtual Environment</title>").let { assertEquals("Proxmox VE", it.model); assertEquals(DeviceType.SERVER, it.type) }
    }
    @Test fun `openwrt sets os and type`() = runTest {
        identify("<title>OpenWrt - LuCI</title>").let { assertEquals("OpenWrt", it.os); assertEquals(DeviceType.ROUTER, it.type) }
    }

    /** A device that answers [pages] by path (status, headers, body) and 404 elsewhere. */
    private fun device(pages: Map<String, Triple<Int, List<Pair<String, String>>, String>>) = HttpGet { _, _, path ->
        val (status, headers, body) = pages[path] ?: Triple(404, emptyList(), "")
        HttpResult.Ok(HttpResponse(status, "", headers, body.toByteArray(), false, body.length.toLong()))
    }
    private suspend fun runOn(get: HttpGet, port: Int, tls: Boolean): Pair<ToolResult, CollectingProgress> {
        val p = CollectingProgress()
        return HttpFingerprintTool({ get }, fpRules) { "abcdef012345" }.run(ToolTarget(1, host, port, tls, null), ToolInputs.NONE, control(), p) to p
    }
    private val robotsDisallowAll = "User-agent: *" + LF + "Disallow: /" + LF   // CHNAS robots.txt: 26 bytes, one rule

    /** Phase 3B QA: CHNAS 10.0.3.59:5001 (DS220+, DSM 7) answered "No product named, 4 findings": no Server header, title CHNAS. */
    @Test fun `chnas dsm 7 login page names synology dsm and identifies a synology nas`() = runTest {
        val chnas = device(mapOf(
            "/" to Triple(200, emptyList(), "<!DOCTYPE html><html><head><title>CHNAS</title><script src=" + Q + "webapi/entry.cgi?api=SYNO.Core.Desktop.Defs" + Q + "></script></head></html>"),
            "/robots.txt" to Triple(200, emptyList(), robotsDisallowAll),
        ))
        val (r, p) = runOn(chnas, 5001, tls = true)
        assertEquals(26, robotsDisallowAll.length)
        assertEquals("Synology DSM, 5 findings", r.summary)
        assertTrue(p.hints().contains("http.framework=Synology DSM"))
        val id = identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = p.hints()))
        assertEquals("Synology", id.vendor); assertEquals(DeviceType.NAS, id.type); assertTrue(id.reasons.contains("toolHint: http.framework=Synology DSM"))
    }
    /** Phase 3B QA: the USG 10.0.3.1:443 answered "PHP, 5 findings" from the PHPSESSID cookie; review M4. */
    @Test fun `the usg is named unifi security gateway ahead of php and identifies a ubiquiti router`() = runTest {
        val usg = device(mapOf("/" to Triple(200, listOf("Server" to "Server", "Set-Cookie" to "PHPSESSID=0123456789abcdef; path=/; secure"),
            "<html><head><title>UniFi Security Gateway</title></head></html>")))
        val (r, p) = runOn(usg, 443, tls = true)
        assertEquals("UniFi Security Gateway, 6 findings", r.summary)
        assertEquals(listOf("UniFi Security Gateway", "PHP"), p.of(FindingKind.FRAMEWORK).map { it.value })
        assertTrue(p.hints().contains("http.framework=UniFi Security Gateway"))
        val id = identifier.identify(HostEvidence(DiscoveredHost(host), emptySet(), emptyMap(), null, toolHints = p.hints()))
        assertEquals("Ubiquiti", id.vendor); assertEquals("UniFi Security Gateway", id.model); assertEquals(DeviceType.ROUTER, id.type)
    }
    private companion object { val LF = Char(10).toString(); val Q = Char(34).toString() }
}
