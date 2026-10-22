package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** The ladder of Phase 3E spec 3, 6 and 10 against a scripted network: no sockets, no sleeping, virtual time. */
class ServiceProbesTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val net = FakeNetwork()
    private val cert = CertInfo("unifi.local", "Ubiquiti Inc.", listOf("unifi.local"))

    // ---- fixture: one JSON line per probe, every send is "SEND-<id>" so the log names the probe ----
    private fun spec(id: String, regex: String, service: String = id, ports: String = "[]", rarity: Int = 2, safety: String = "safe", tls: String = "never",
                     wait: Int = 800, protocols: String = "[\"tcp\"]", send: String? = null, soft: Boolean = false, narrow: String = "[]",
                     quick: Boolean = false, builtin: String? = null): String {
        val s = send ?: if (protocols == "[\"null\"]") "" else "SEND-$id"
        val b = if (builtin != null) ",\"builtin\":\"$builtin\"" else ""
        return """{"id":"$id","service":"$service","protocols":$protocols,"send":"$s","ports":$ports,"rarity":$rarity,"safety":"$safety","tls":"$tls","waitMs":$wait,
            "maxBytes":2048,"parser":null,"quick":$quick$b,"matches":[{"regex":"$regex","service":"$service","soft":$soft,"narrow":$narrow}],
            "samples":[],"negativeSamples":[]}"""
    }
    private val ssh = spec("ssh", "^SSH-", "SSH", protocols = "[\"null\"]", rarity = 1)
    private val tls = spec("tls", "^zzz", "TLS", ports = "[443]", rarity = 1, tls = "also", wait = 1500, quick = true, builtin = "tls")
    private val head = spec("http-head", "^HTTP/1", "HTTP", ports = "[80]", rarity = 1, tls = "also", quick = true)
    private val helloA = spec("hello-a", "^A-OK", "A", rarity = 2)
    private val helloB = spec("hello-b", "^B-OK", "B", rarity = 3)
    private val helloC = spec("hello-c", "^C-OK", "C", rarity = 4)
    private val redis = spec("redis", "^R-OK", "Redis", ports = "[6379]", rarity = 3, wait = 1500)
    private val pg = spec("pg", "^P-OK", "PG", ports = "[5432]", rarity = 5, safety = "careful", wait = 1500)
    private val modbus = spec("modbus", "^M-OK", "Modbus", ports = "[502]", rarity = 5, safety = "careful", wait = 1500)
    private val soft = spec("soft", "^SOFT", "Soft", rarity = 2, soft = true, narrow = "[\"hello-b\"]")

    private var httpAnswer: (Int, Boolean) -> HttpPeek? = { _, _ -> null }
    private val fakeHttp = object : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
            net.events += "get:$port:${if (tls) "tls" else "plain"}"; net.clock.advance(100); return httpAnswer(port, tls)
        }
    }
    private fun load(vararg probes: String): List<ServiceProbe> {
        val r = ServiceProbeRules.load("[" + probes.joinToString(",") + "]")
        assertTrue("fixture skipped: ${r.skipped.map { it.id + ": " + it.reason }}", r.skipped.isEmpty())
        return r.probes
    }
    private fun ladder(vararg probes: String) = ServiceProbes(load(*probes), net::transport, fakeHttp, { net.clock.now }, { "RAND" })
    private fun budget(maxMs: Long = MAX_ACTIVE_MS, hostWallMs: Long = HOST_WALL_MS) = ActiveProbeBudget(MAX_ACTIVE_PORTS, maxMs, hostWallMs) { net.clock.now }
    private fun active(b: ActiveProbeBudget = budget()) = ProbeMode.Active(b)
    private fun run(l: ServiceProbes, port: Int, mode: ProbeMode = active(), host: Long = 1L, base: String = "Unknown") =
        runBlocking { l.run(ip, port, port, base, mode, host) }
    private fun sends(): List<String> = net.events.filter { it.startsWith("send:") }.map { it.substringAfter(":").substringAfter(":").removePrefix("SEND-").substringBefore("\\r") }
    private fun silent() { net.respond = { _, _, _ -> Reply.SILENT } }

    // ---- 1. the null probe ----
    @Test fun `a speak first banner is matched by the null probe with one connect and nothing sent`() {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("SSH-2.0-OpenSSH_9.6p1 Debian-3\r\n") else Reply.SILENT }
        val r = run(ladder(ssh, tls, head, helloA), 2222)
        assertEquals("SSH", r.info!!.name); assertEquals(Confidence.CONFIRMED, r.info!!.confidence)
        assertEquals(listOf("connect:2222"), net.connectLog()); assertTrue(net.sent().isEmpty())
    }
    @Test fun `a null reply nothing names keeps the plain banner reading, gets no tls check, and the hellos still run`() {
        net.respond = { _, _, sent -> if (sent == null) Reply.text("WELCOME to the box\r\n") else if (sent.startsWith("SEND-hello-a")) Reply.text("A-OK") else Reply.SILENT }
        val r = run(ladder(ssh, tls, helloA), 7000)
        assertEquals("A", r.info!!.name)
        assertTrue("no tls after a null reply", net.connectLog().none { it.startsWith("tls:") })
    }
    @Test fun `accepted then closed is tcpwrapped at port table confidence`() {
        net.respond = { _, _, _ -> Reply.ACCEPT_THEN_CLOSE }
        val r = run(ladder(ssh, tls, head, helloA), 7000, base = "Unknown")
        assertEquals("TCP", r.info!!.name); assertEquals("tcpwrapped", r.info!!.detail); assertEquals(Confidence.PORT_TABLE, r.info!!.confidence)
        assertEquals(listOf("connect:7000"), net.connectLog())
        assertEquals("a known name stays", "Telnet", run(ladder(ssh, tls), 23, base = "Telnet").info!!.name)
    }

    // ---- 2. plainOnly and the single TLS peek ----
    @Test fun `plain only ports never get a tls peek`() {
        silent()
        for (port in listOf(21, 22, 2222)) { net.events.clear(); run(ladder(ssh, tls, helloA), port); assertTrue("port $port", net.connectLog().none { it.startsWith("tls:") }) }
    }
    @Test fun `a silent port gets exactly one tls peek and keeps the certificate fields`() {
        net.cert = { cert }
        val r = run(ladder(ssh, tls, helloA), 7000)
        assertEquals(1, net.connectLog().count { it.startsWith("tls:") })
        assertEquals("TLS", r.info!!.name); assertEquals("unifi.local", r.info!!.certCn); assertEquals("Ubiquiti Inc.", r.info!!.certOrg); assertEquals("cert unifi.local", r.info!!.detail)
    }
    @Test fun `inside tls only the http tier speaks, with two handshakes at most`() {
        net.cert = { cert }; httpAnswer = { _, tls -> if (tls) HttpPeek("UniFi OS", "nginx/1.2", null, 200) else null }
        val r = run(ladder(ssh, tls, head, helloA, helloB), 9443)
        assertEquals("HTTPS", r.info!!.name); assertEquals("UniFi OS", r.info!!.title)
        assertEquals(listOf("connect:9443", "tls:9443"), net.connectLog()); assertTrue("nothing written inside tls", net.sent().isEmpty())
        assertEquals(1, net.events.count { it == "get:9443:tls" }); assertEquals(1, net.handshakes.get())
    }
    @Test fun `a tls set port is named HTTPS from the peek and plain hellos are not sent over it`() {
        net.cert = { cert }
        val r = run(ladder(ssh, tls, head), 443)
        assertEquals("HTTPS", r.info!!.name); assertTrue(net.sent().isEmpty())
    }

    // ---- 3. the ordered probes ----
    @Test fun `hinted probes go first in file order then safe rarity 1 to 3 by rarity and rarity 4 is never reached unhinted`() {
        silent()
        run(ladder(helloC, helloB, helloA, redis), 6379)
        assertEquals(listOf("redis", "hello-a", "hello-b"), sends())
        net.events.clear()
        run(ladder(helloC, helloB, helloA), 7000)
        assertEquals(listOf("hello-a", "hello-b"), sends())
    }
    @Test fun `careful probes are only sent to their hinted ports`() {
        silent()
        run(ladder(pg, helloA), 7000); assertEquals(listOf("hello-a"), sends())
        net.events.clear(); run(ladder(pg, helloA), 5432); assertEquals(listOf("pg", "hello-a"), sends())
    }
    @Test fun `a probe is sent at most once per port`() {
        silent()
        run(ladder(helloA, redis), 6379)
        assertEquals(sends().size, sends().toSet().size)
    }
    @Test fun `a hard match ends the ladder`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-hello-a") == true) Reply.text("A-OK") else Reply.SILENT }
        val r = run(ladder(helloA, helloB), 7000)
        assertEquals("A", r.info!!.name); assertEquals(listOf("hello-a"), sends())
    }
    @Test fun `a soft match narrows the rest of the ladder to its narrow ids`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-soft") == true) Reply.text("SOFT hint") else Reply.SILENT }
        val r = run(ladder(soft, helloA, helloB), 7000)
        assertEquals(listOf("soft", "hello-b"), sends()); assertEquals("Soft", r.info!!.name)
    }
    @Test fun `unhinted probes wait 400 ms and hinted probes use their own wait`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-hello-a") == true) Reply(("A-OK").toByteArray(), delayMs = 600) else Reply.SILENT }
        assertNull("600 ms is past the unhinted 400 ms", run(ladder(helloA), 7000).info)
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-redis") == true) Reply(("R-OK").toByteArray(), delayMs = 600) else Reply.SILENT }
        assertEquals("hinted: its own 1500 ms", "Redis", run(ladder(redis), 6379).info!!.name)
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-hello-a") == true) Reply(("A-OK").toByteArray(), delayMs = 300) else Reply.SILENT }
        assertEquals("300 ms is inside 400", "A", run(ladder(helloA), 7000).info!!.name)
    }
    @Test fun `a reply is cut at 2048 bytes before matching`() {
        net.respond = { _, _, sent -> if (sent != null) Reply(("x".repeat(3000) + "A-OK").toByteArray()) else Reply.SILENT }
        assertNull(run(ladder(spec("far", "A-OK$", "Far")), 7000).info)
        net.respond = { _, _, sent -> if (sent != null) Reply(("A-OK" + "y".repeat(1_000_000)).toByteArray()) else Reply.SILENT }
        assertEquals("A", run(ladder(helloA), 7000).info!!.name)
    }

    // ---- 4. fragile and industrial ports: a table ----
    @Test fun `fragile ports get the null probe only, whatever the file holds`() {
        silent()
        val l = ladder(ssh, tls, head, helloA, spec("hint", "^X", "X", ports = "[9100,515,25,6000,2000,587]", rarity = 1))
        for (port in listOf(9100, 9107, 515, 2000, 2001, 6000, 6063, 25, 110, 143, 587)) {
            net.events.clear(); run(l, port)
            assertEquals("port $port", listOf("connect:$port"), net.connectLog()); assertTrue("port $port", net.sent().isEmpty())
        }
    }
    @Test fun `industrial ports get no connect at all except their hinted careful probe`() {
        silent()
        val l = ladder(ssh, tls, head, helloA, modbus)
        for (port in listOf(102, 44818, 47808)) { net.events.clear(); run(l, port); assertTrue("port $port", net.connectLog().isEmpty()) }
        net.events.clear(); run(l, 502)
        assertEquals(listOf("connect:502"), net.connectLog()); assertEquals(listOf("modbus"), sends())
        net.events.clear(); run(l, 502, ProbeMode.Passive); assertTrue("Quick never touches a fieldbus port", net.connectLog().isEmpty())
    }
    @Test fun `ipp is the null read and its hinted head only`() {
        silent()
        run(ladder(ssh, tls, helloA, spec("ipp-head", "^HTTP/1", "IPP", ports = "[631]", rarity = 5, safety = "careful")), 631)
        assertEquals(listOf("ipp-head"), sends()); assertTrue(net.connectLog().none { it.startsWith("tls:") })
    }

    // ---- 5. the time model ----
    @Test fun `no more than six connects per port`() {
        silent()
        val many = (1..12).map { spec("h$it", "^NO$it", "H$it", rarity = 2) }.toTypedArray()
        run(ladder(*many), 7000)
        assertEquals(6, net.connects.get())
    }
    @Test fun `a port's whole ladder stops at its 6 s wall`() {
        silent()
        val slow = (1..5).map { spec("s$it", "^NO$it", "S$it", ports = "[7001]", rarity = 5, wait = 3000) }.toTypedArray()
        val t0 = net.clock.now
        run(ladder(*slow), 7001)
        assertTrue("took ${net.clock.now - t0} ms", net.clock.now - t0 <= ServiceProbes.PORT_WALL_MS)
        assertEquals("null 1 s, then 3 s, then the 2 s left", 3, net.connects.get())
    }
    @Test fun `a host stops starting connections at its 20 s wall but every port still gets its null read`() {
        silent()
        val l = ladder(ssh, spec("slow", "^NO", "Slow", ports = "[7001,7002,7003,7004,7005]", rarity = 5, wait = 5000))
        val b = budget()
        for (port in 7001..7004) run(l, port, active(b))
        assertTrue(b.hostSpentMs(1L) >= HOST_WALL_MS)
        net.events.clear(); net.connects.set(0)
        run(l, 7005, active(b))
        assertEquals(1, net.connects.get())
        net.connects.set(0); run(l, 7001, active(b), host = 2L)
        assertTrue("a second host has its own wall", net.connects.get() > 1)
    }
    @Test fun `the scan budget is keyed by host and port`() {
        val b = budget()
        assertTrue(b.tryEnter(1L, 80)); assertTrue(b.tryEnter(2L, 80)); assertEquals(2, b.enteredPorts)
    }
    @Test fun `a refused connect ends the ladder and keeps the best result so far`() {
        var n = 0
        net.connectOutcome = { _, _ -> if (n++ == 0) ConnectOutcome.CONNECTED else ConnectOutcome.REFUSED }
        net.respond = { _, _, sent -> if (sent == null) Reply.text("WELCOME to the box\r\n") else Reply.SILENT }
        val r = run(ladder(ssh, tls, helloA, helloB), 7000)
        assertEquals("WELCOME to the box", r.info!!.detail); assertEquals(2, net.connects.get())
    }
    @Test fun `a refused null connect leaves nothing and runs nothing else`() {
        net.connectOutcome = { _, _ -> ConnectOutcome.REFUSED }
        val r = run(ladder(ssh, tls, helloA), 7000)
        assertNull(r.info); assertEquals(1, net.connects.get()); assertTrue(net.events.none { it.startsWith("get:") })
    }
    @Test fun `a starved budget never lowers the Phase 3A result`() {
        val starved = budget(maxMs = 0)
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-http-head") == true) Reply.text("HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n") else Reply.SILENT }
        val h = run(ladder(ssh, tls, head, helloA), 80, active(starved))
        assertEquals("HTTP", h.info!!.name); assertEquals("nginx", h.info!!.product)
        net.cert = { cert }
        assertEquals("HTTPS", run(ladder(ssh, tls, head, helloA), 443, active(starved)).info!!.name)
        net.events.clear(); silent()
        run(ladder(ssh, tls, head, helloA), 7000, active(starved))
        assertEquals("starved: the null read only", listOf("connect:7000"), net.connectLog())
    }

    // ---- 6. the http tier and Quick ----
    @Test fun `an http reply gets one GET for title and headers in Active mode`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-http-head") == true) Reply.text("HTTP/1.1 200 OK\r\nServer: lighttpd/1.4.59\r\n\r\n") else Reply.SILENT }
        httpAnswer = { _, _ -> HttpPeek("Synology DiskStation", "nginx/1.25.3", null, 200) }
        val r = run(ladder(ssh, tls, head), 80)
        assertEquals("HTTP", r.info!!.name); assertEquals("Synology DiskStation", r.info!!.title)
        assertEquals(1, net.events.count { it.startsWith("get:") })
    }
    @Test fun `Quick is passive, the null probe or the one 3A step the port is hinted for, and no GET`() {
        silent(); httpAnswer = { _, _ -> HttpPeek("t", null, null, 200) }
        val l = ladder(ssh, tls, head, helloA)
        run(l, 7000, ProbeMode.Passive); assertEquals(listOf("connect:7000"), net.connectLog()); assertTrue(net.sent().isEmpty())
        net.events.clear(); run(l, 80, ProbeMode.Passive); assertEquals(listOf("connect:80"), net.connectLog()); assertEquals(listOf("http-head"), sends())
        net.events.clear(); net.cert = { cert }; run(l, 443, ProbeMode.Passive); assertEquals(listOf("tls:443"), net.connectLog())
        assertTrue(net.events.none { it.startsWith("get:") })
    }

    // ---- Settings switch "Service hellos" ----
    @Test fun `with hellos off only the first six probes run, hinted, and the unhinted hellos and later hinted probes do not`() {
        silent()
        val l = ladder(ssh, tls, head, helloA, helloB, helloC, redis)
        run(l, 7000, ProbeMode.Active(budget(), hellos = false))
        assertTrue("sent ${sends()}", sends().isEmpty())
        net.events.clear(); run(l, 6379, ProbeMode.Active(budget(), hellos = false))
        assertTrue("redis is past the first six: ${sends()}", sends().isEmpty())
        net.events.clear(); run(l, 80, ProbeMode.Active(budget(), hellos = false))
        assertEquals(listOf("http-head"), sends())
    }
    @Test fun `with hellos off the tls check still runs on a silent port and the same ports get the full ladder with hellos on`() {
        silent(); net.cert = { cert }
        val l = ladder(ssh, tls, head, helloA, helloB, helloC, redis)
        run(l, 9999, ProbeMode.Active(budget(), hellos = false)); assertEquals(listOf("connect:9999", "tls:9999"), net.connectLog())
        net.events.clear(); net.cert = { null }
        run(l, 7000, ProbeMode.Active(budget(), hellos = true)); assertEquals(listOf("hello-a", "hello-b"), sends().filter { it.startsWith("hello") })
    }
    @Test fun `with hellos off the http stack pass spends no extra GETs`() {
        net.respond = { _, _, sent -> if (sent?.startsWith("SEND-http-head") == true) Reply.text("HTTP/1.1 200 OK\r\n\r\n") else Reply.SILENT }
        httpAnswer = { _, _ -> HttpPeek("Home Assistant", null, null, 200) }
        var extra = 0
        val rules = HttpStackRules.load("""[{"id":"home-assistant","all":[{"where":"title","pattern":"Home Assistant"}],"stack":"Home Assistant","hard":true,
            "paths":[{"path":"/api/","status":401,"body":"Unauthorized"}],"sample":"Home Assistant"}]""")
        assertTrue(rules.skipped.toString(), rules.skipped.isEmpty())
        val l = ServiceProbes(load(ssh, tls, head), net::transport, fakeHttp, { net.clock.now }, { "RAND" }, rules.rules,
            { _, _, _ -> StackGet { _, _ -> extra++; StackReply(401, "Unauthorized".toByteArray()) } })
        val on = run(l, 80, ProbeMode.Active(budget(), hellos = true)); assertEquals(1, extra); assertEquals("Home Assistant", on.info!!.stack)
        extra = 0; net.events.clear()
        val r = run(l, 80, ProbeMode.Active(budget(), hellos = false))
        assertEquals(0, extra); assertEquals("Home Assistant", r.info!!.stack)
    }

    // ---- 7. Stop ----
    @Test fun `stop during a tls handshake closes the socket`() = runBlocking {
        net.hangHandshake = true
        val l = ladder(ssh, tls, head)
        val job = launch(Dispatchers.Default) { l.run(ip, 443, 443, "HTTPS", active(), 1L) }
        var waited = 0
        while (!net.handshakeEntered && waited++ < 500) delay(10)
        assertTrue("handshake never started", net.handshakeEntered)
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals("every connection, the one inside the handshake included, was closed", net.connects.get(), net.closes.get())
    }
}
