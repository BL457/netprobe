package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/** A generic HTTP hit ends a scan's ladder as before, but on a manual run the product hellos hinted for the port still get their turn. */
class GenericHttpHitTest {
    private val net = FakeNetwork()
    private val probes = ServiceProbeRules.load(listOf("src/main/res/raw", "app/src/main/res/raw").map { File("$it/service_probes.json") }.first { it.exists() }.readText()).probes
    private val noHttp = object : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? = null
    }
    private val ladder = ServiceProbes(probes, net::transport, noHttp, { net.clock.now }, { "RAND" })
    private fun budget() = ActiveProbeBudget(MAX_ACTIVE_PORTS, Long.MAX_VALUE, Long.MAX_VALUE) { net.clock.now }
    private fun run(port: Int, limits: LadderLimits) = runBlocking {
        ladder.run(InetAddress.getByName("10.0.3.59"), port, port, "Unknown", ProbeMode.Active(budget(), hellos = true, limits = limits), 1L)
    }
    private val grafanaBody = "{\n  \"commit\": \"abc1234\",\n  \"database\": \"ok\",\n  \"version\": \"10.2.2\"\n}"
    private fun grafanaServer() {
        net.respond = { _, _, sent ->
            when {
                sent == null -> Reply.SILENT
                sent.startsWith("HEAD") -> Reply.text("HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n")
                sent.startsWith("GET /api/health") -> Reply.text("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${grafanaBody.length}\r\n\r\n$grafanaBody")
                else -> Reply.SILENT
            }
        }
    }

    @Test fun `a manual run on port 3000 ends with Grafana, not plain HTTP`() {
        grafanaServer()
        val r = run(3000, LadderLimits.THOROUGH)
        assertEquals("Grafana", r.info?.name); assertEquals("Grafana", r.info?.product); assertEquals("10.2.2", r.info?.version)
        assertEquals("http-head then the Grafana hello, nothing unhinted", listOf("null", "http-head", "grafana-health"), r.trace.map { it.id })
        assertTrue(r.hits.map { it.probeId }.containsAll(listOf("http-head", "grafana-health")))
    }
    @Test fun `a scan on the same reply ends with HTTP after the generic hit`() {
        grafanaServer()
        val r = run(3000, LadderLimits.SCAN)
        assertEquals("HTTP", r.info?.name)
        assertTrue("grafana-health was never sent", "grafana-health" !in r.probesSent)
    }
    @Test fun `a manual run with no product reply keeps the generic HTTP result`() {
        net.respond = { _, _, sent -> if (sent != null && sent.startsWith("HEAD")) Reply.text("HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n") else Reply.SILENT }
        val r = run(3000, LadderLimits.THOROUGH)
        assertEquals("HTTP", r.info?.name)
        assertTrue("grafana-health was tried", "grafana-health" in r.probesSent)
    }
    @Test fun `a generic hit on a port with no product hello ends the ladder at once on a manual run too`() {
        net.respond = { _, _, sent -> if (sent != null && sent.startsWith("HEAD")) Reply.text("HTTP/1.1 200 OK\r\n\r\n") else Reply.SILENT }
        val r = run(81, LadderLimits.THOROUGH)
        assertEquals("HTTP", r.info?.name)
        assertEquals(listOf("null", "http-head"), r.trace.map { it.id })
    }
}
