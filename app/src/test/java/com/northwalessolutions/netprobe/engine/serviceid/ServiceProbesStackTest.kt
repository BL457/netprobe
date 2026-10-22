package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** The HTTP tier's stack pass inside the ladder (Phase 3E spec 5): one GET read, extra GETs only from rules, results on ServiceInfo. */
class ServiceProbesStackTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val net = FakeNetwork()
    private var peek: HttpPeek? = null
    private val extra = mutableListOf<String>()
    private var extraReplies: Map<String, StackReply> = emptyMap()
    private val fakeHttp = object : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? { net.events += "get:$port"; net.clock.advance(100); return peek }
    }
    private val head = """{"id":"http-head","service":"HTTP","protocols":["tcp"],"send":"HEAD / HTTP/1.0\r\n\r\n","ports":[80,8123],"rarity":1,"safety":"safe","tls":"also",
        "waitMs":800,"maxBytes":2048,"parser":null,"quick":true,"matches":[{"regex":"^HTTP/1","service":"HTTP"}],"samples":[],"negativeSamples":[]}"""
    private val ha = """{"id":"home-assistant","all":[{"where":"title","pattern":"Home Assistant"}],"stack":"Home Assistant","role":"software","cpeProduct":"Home Assistant",
        "paths":[{"path":"/api/","status":401,"body":"Unauthorized"}],"sample":"Home Assistant"}"""
    private val jenkins = """{"id":"jenkins","all":[{"where":"header:x-jenkins","pattern":"^([\\d.]+)$"}],"stack":"Jenkins","version":"$1","cpeProduct":"Jenkins","hard":true,"sample":"2.4"}"""
    private val grafana = """{"id":"grafana","all":[{"where":"title","pattern":"Grafana"}],"stack":"Grafana","cpeProduct":"Grafana","hard":true,"sample":"Grafana"}"""
    private val nginx = """{"id":"nginx","all":[{"where":"server","pattern":"^nginx/?([\\d.]*)"}],"stack":"nginx","version":"$1","cpeProduct":"nginx","sample":"nginx/1.2"}"""
    private val hue = """{"id":"hue","all":[{"where":"body","pattern":"hue personal wireless lighting"}],"stack":"Philips Hue","role":"device","vendor":"Philips Hue","type":"HUB","hard":true,"sample":"hue personal wireless lighting"}"""

    private fun ladder(vararg stackRules: String): ServiceProbes {
        val probes = ServiceProbeRules.load("[$head]").probes
        val rules = HttpStackRules.load("[" + stackRules.joinToString(",") + "]").also { assertTrue(it.skipped.toString(), it.skipped.isEmpty()) }.rules
        return ServiceProbes(probes, net::transport, fakeHttp, { net.clock.now }, { "RAND" }, rules) { _, _, _ ->
            StackGet { path, _ -> extra += path; extraReplies[path] }
        }
    }
    private fun serve() { net.respond = { _, _, sent -> if (sent != null) Reply.text("HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n") else Reply.SILENT } }
    private fun run(l: ServiceProbes, port: Int = 8123): LadderResult =
        runBlocking { l.run(ip, port, port, "HTTP", ProbeMode.Active(ActiveProbeBudget(MAX_ACTIVE_PORTS, MAX_ACTIVE_MS, HOST_WALL_MS) { net.clock.now }), 1L) }

    @Test fun `a hard hit on the get names the stack, its role, the cpe product and the rule as probe`() {
        serve(); peek = HttpPeek("Home Assistant", null, null, 200, headers = listOf("Content-Type" to "text/html"), body = "<title>Home Assistant</title>")
        extraReplies = mapOf("/api/" to StackReply(401, "401: Unauthorized".toByteArray()))
        val r = run(ladder(ha))
        val i = r.info!!
        assertEquals("Home Assistant", i.stack); assertEquals("software", i.stackRole); assertEquals("home-assistant", i.probe)
        assertNull("no version the stack could give and no product of that name", i.cpe)
        assertEquals(listOf("/api/"), extra); assertEquals("/api/", r.stackHits.single().confirmedAt)
        assertEquals(1, net.events.count { it.startsWith("get:") })
    }
    @Test fun `the get is never refetched and rules read its headers cookies and body`() {
        serve(); peek = HttpPeek("Dashboard", "Jetty(9.4)", null, 200, headers = listOf("X-Jenkins" to "2.426.1", "Server" to "Jetty(9.4)"), cookies = listOf("JSESSIONID"), body = "")
        val i = run(ladder(jenkins)).info!!
        assertEquals("Jenkins", i.stack); assertEquals("2.426.1", i.version); assertEquals("Jenkins", i.product); assertEquals("Jenkins", i.cpe)
        assertEquals(1, net.events.count { it.startsWith("get:") }); assertTrue(extra.isEmpty())
    }
    @Test fun `a cpe is kept only when the version belongs to the stack or to the product that sent it`() {
        serve(); peek = HttpPeek("Grafana", "Python/3.11", null, 200, headers = listOf("Server" to "Python/3.11"))
        val g = run(ladder(grafana)).info!!
        assertEquals("Grafana", g.stack); assertNull("1.25.3 is the Server header product's, not Grafana's", g.cpe); assertEquals("1.25.3", g.version)
        peek = HttpPeek("Dashboard", "nginx/1.25.3", null, 200, headers = listOf("Server" to "nginx/1.25.3"))
        val n = run(ladder(nginx)).info!!
        assertNull("nginx is not hard, so it names no stack", n.stack); assertEquals("nginx", n.product)
    }
    @Test fun `a soft hit sets no stack but stays in the result as a reason`() {
        serve(); peek = HttpPeek("Dashboard", "nginx/1.25.3", null, 200, headers = listOf("Server" to "nginx/1.25.3"))
        val r = run(ladder(nginx))
        assertNull(r.info!!.stack); assertEquals(listOf("nginx"), r.stackHits.map { it.rule.id }); assertEquals("http-head", r.info!!.probe)
    }
    @Test fun `a device role hit is carried as device`() {
        serve(); peek = HttpPeek(null, null, null, 200, body = "<title>hue personal wireless lighting</title>")
        val i = run(ladder(hue)).info!!
        assertEquals("Philips Hue", i.stack); assertEquals("device", i.stackRole)
    }
    @Test fun `no rules or no get means no stack pass and no extra requests`() {
        serve(); peek = HttpPeek("Home Assistant", null, null, 200, body = "x")
        assertNull(run(ServiceProbes(ServiceProbeRules.load("[$head]").probes, net::transport, fakeHttp, { net.clock.now }, { "RAND" })).info!!.stack)
        peek = null
        val r = run(ladder(ha))
        assertNull(r.info!!.stack); assertTrue(extra.isEmpty())
    }
    @Test fun `quick passive mode never reaches the stack pass`() {
        serve(); peek = HttpPeek("Home Assistant", null, null, 200, body = "x")
        val r = runBlocking { ladder(ha).run(ip, 8123, 8123, "HTTP", ProbeMode.Passive, 1L) }
        assertNull(r.info?.stack); assertTrue(extra.isEmpty())
    }
    @Test fun `the extra gets are cut off when the budget is spent`() {
        serve(); peek = HttpPeek("Home Assistant", null, null, 200, body = "x")
        val spent = ActiveProbeBudget(MAX_ACTIVE_PORTS, 1L, HOST_WALL_MS) { net.clock.now }       // one ms of probe time: the GET itself uses it up
        val r = runBlocking { ladder(ha).run(ip, 8123, 8123, "HTTP", ProbeMode.Active(spent), 1L) }
        assertTrue(extra.isEmpty()); assertNotNull(r.info)
    }
}
