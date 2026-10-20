package com.northwalessolutions.netprobe.engine.serviceid

import com.northwalessolutions.netprobe.engine.discovery.HttpPeek
import com.northwalessolutions.netprobe.engine.discovery.HttpTitleFetcher
import com.northwalessolutions.netprobe.engine.portscan.PortTable
import com.northwalessolutions.netprobe.engine.portscan.ScanProfile
import com.northwalessolutions.netprobe.ui.home.QuickScanAllPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** The probe ladder against recording fakes: what is sent to which port, in which order, under which budget (spec 2.2, 9.1 tests 10 to 13). */
class ServiceIdentifierLadderTest {
    private val ip = InetAddress.getByName("10.0.3.59") as Inet4Address
    private val table = PortTable.fromJson("""[{"port":80,"name":"HTTP"},{"port":443,"name":"HTTPS"},{"port":554,"name":"RTSP"},{"port":3389,"name":"RDP"},
        {"port":9100,"name":"JetDirect"},{"port":631,"name":"IPP"},{"port":515,"name":"LPD"}]""")
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val clock = AtomicLong(0)
    /** Fake time each TLS peek, HEAD, probe and GET costs; 0 unless a test sets it. */
    private var stepCostMs = 0L
    /** The budget under watch: every active-capable call records how much was spent when it started. */
    private var watched: ActiveProbeBudget? = null
    private val spentAtCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private fun kind(sent: ByteArray): String {
        val t = String(sent, Charsets.ISO_8859_1)
        return when { t.startsWith("HEAD") -> "HEAD"; t.startsWith("OPTIONS") -> "OPTIONS"; sent.firstOrNull() == 0x03.toByte() -> "X224"; else -> "BYTES" }
    }
    private fun costed() { watched?.let { spentAtCalls += it.spentMs }; clock.addAndGet(stepCostMs) }

    private inner class Reader(val passive: (Int) -> ByteArray? = { null }, val reply: (Int, String) -> ByteArray? = { _, _ -> null }) : BannerReader() {
        override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? {
            if (sendFirst == null) { calls += "read:$port"; return passive(port) }
            val k = kind(sendFirst); calls += "send:$port:$k"; costed()
            return reply(port, k)
        }
    }
    private inner class Tls(val answer: (Int) -> CertInfo? = { null }) : TlsPeek() {
        override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { calls += "tls:$port"; costed(); return answer(port) }
    }
    private inner class Http(val answer: (Int, Boolean) -> HttpPeek? = { _, _ -> null }) : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? {
            calls += "get:$port:${if (tls) "https" else "http"}"; costed(); return answer(port, tls)
        }
    }
    private fun identifier(reader: BannerReader = Reader(), tls: TlsPeek = Tls(), http: HttpTitleFetcher = Http()) = ServiceIdentifier(table, reader, tls, http)
    private fun active(): ProbeMode.Active = ProbeMode.Active(ActiveProbeBudget(MAX_ACTIVE_PORTS, MAX_ACTIVE_MS) { clock.get() }).also { watched = it.budget }
    private fun run(id: ServiceIdentifier, port: Int, mode: ProbeMode) = runBlocking { id.identify(ip, port, Evidence(), mode) }

    private val okHead = "HTTP/1.1 200 OK\r\nServer: nginx/1.25.3\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    private val unifiCert = CertInfo("unifi.local", "Ubiquiti Inc.", listOf("unifi.local"))
    private val unknown7000 = ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE)

    // ---- the ladder in Active mode (9.1 test 10) ----
    @Test fun `silent unknown port that answers HEAD becomes HTTP with a title`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }),
            http = Http { _, tls -> if (!tls) HttpPeek("Synology DiskStation", "nginx/1.25.3", null, 200) else null }), 7000, active())
        assertEquals("HTTP", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("Synology DiskStation", s.title); assertEquals("nginx/1.25.3", s.server)
        assertEquals(listOf("read:7000", "tls:7000", "send:7000:HEAD", "get:7000:http"), calls)
    }
    @Test fun `silent unknown port that completes tls and answers GET becomes HTTPS with cert fields`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, tls -> if (tls) HttpPeek("UniFi OS", null, null, 200) else null }), 9443, active())
        assertEquals("HTTPS", s.name); assertEquals("unifi.local", s.certCn); assertEquals("Ubiquiti Inc.", s.certOrg); assertEquals("UniFi OS", s.title)
        assertEquals(listOf("read:9443", "tls:9443", "get:9443:https"), calls)
    }
    @Test fun `a port that completes tls but answers no GET is named TLS`() {
        val s = run(identifier(tls = Tls { CertInfo("mqtt.lan", null, listOf("mqtt.lan")) }), 8883, active())
        assertEquals("TLS", s.name); assertEquals(Confidence.CONFIRMED, s.confidence); assertEquals("mqtt.lan", s.certCn); assertEquals("cert mqtt.lan", s.detail)
    }
    @Test fun `a port that sent one unprintable byte goes to no active step`() {
        val s = run(identifier(Reader(passive = { byteArrayOf(0x15) })), 7000, active())
        assertEquals(listOf("read:7000"), calls); assertEquals(Confidence.PORT_TABLE, s.confidence)
    }
    @Test fun `a tls set port is never sent a plain HEAD first`() {
        run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null })), 443, active())
        assertEquals("tls:443", calls.first()); assertTrue(calls.indexOf("send:443:HEAD") > calls.indexOf("tls:443"))
    }
    @Test fun `an http set port that fails HEAD falls through to tls`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("UniFi OS", null, null, 200) }), 80, active())
        assertEquals(listOf("send:80:HEAD", "tls:80", "get:80:https"), calls); assertEquals("HTTPS", s.name)
    }
    @Test fun `a silent port with nothing stays at port table confidence`() {
        assertEquals(unknown7000, run(identifier(), 7000, active()))
        assertEquals(listOf("read:7000", "tls:7000", "send:7000:HEAD"), calls)
    }
    @Test fun `rtsp and rdp get their own probe in active mode`() {
        val rtsp = run(identifier(Reader(reply = { _, k ->
            if (k == "OPTIONS") "RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: Hikvision/1.0\r\n\r\n".toByteArray(Charsets.ISO_8859_1) else null })), 554, active())
        assertEquals("RTSP", rtsp.name); assertEquals("Hikvision/1.0", rtsp.server)
        val rdp = run(identifier(Reader(reply = { _, k -> if (k == "X224") byteArrayOf(0x03, 0x00, 0x00, 0x13, 0x0E, 0xD0.toByte()) else null })), 3389, active())
        assertEquals("RDP", rdp.name)
        assertEquals(listOf("read:554", "send:554:OPTIONS", "read:3389", "send:3389:X224"), calls)
    }

    // ---- the profile gate (9.1 test 11, Review Focus 2) ----
    @Test fun `passive mode sends nothing new to a silent unknown port`() {
        assertEquals(unknown7000, run(identifier(tls = Tls { unifiCert }), 7000, ProbeMode.Passive))
        assertEquals(listOf("read:7000"), calls)
    }
    @Test fun `passive mode gives an http set port exactly one HEAD and no GET`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }), http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 80, ProbeMode.Passive)
        assertEquals(listOf("send:80:HEAD"), calls); assertEquals("HTTP", s.name); assertNull(s.title)
    }
    @Test fun `passive mode gives a tls set port today's peek and no GET`() {
        val s = run(identifier(tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 443, ProbeMode.Passive)
        assertEquals(listOf("tls:443"), calls); assertEquals("HTTPS", s.name); assertEquals("Ubiquiti Inc.", s.certOrg); assertNull(s.title)
    }
    @Test fun `passive mode gives rtsp and rdp only the passive read`() {
        run(identifier(), 554, ProbeMode.Passive); run(identifier(), 3389, ProbeMode.Passive)
        assertEquals(listOf("read:554", "read:3389"), calls)
    }
    @Test fun `quick scan all is passive`() {
        assertSame(ScanProfile.Quick, QuickScanAllPolicy.profile); assertSame(ProbeMode.Passive, QuickScanAllPolicy.mode())
        assertSame(ProbeMode.Passive, ProbeModes.forProfile(ScanProfile.Quick))
        assertTrue(ProbeModes.forProfile(ScanProfile.Full) is ProbeMode.Active)
        assertTrue(ProbeModes.forProfile(ScanProfile.Custom(listOf(80))) is ProbeMode.Active)
        val a = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active; val b = ProbeModes.forProfile(ScanProfile.Full) as ProbeMode.Active
        assertNotSame("one budget per scan, never shared", a.budget, b.budget)
    }

    // ---- passive-only ports (9.1 test 12, Review Focus 1) ----
    @Test fun `passive only and head only sets are pinned`() {
        assertEquals(setOf(25, 110, 143, 515, 2000, 2001) + (6000..6063).toSet() + (9100..9107).toSet(), ServiceIdentifier.PASSIVE_ONLY)
        assertEquals(setOf(631), ServiceIdentifier.HEAD_ONLY)
    }
    /** Records every attempt in [calls] before failing, so a runCatching around a ladder call cannot hide the attempt. */
    private val noTls = object : TlsPeek() {
        override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { calls += "tls:$port"; fail("ClientHello sent to port $port"); return null }
    }
    private val noGet = object : HttpTitleFetcher() {
        override suspend fun fetch(ip: Inet4Address, port: Int, tls: Boolean): HttpPeek? { calls += "get:$port"; fail("GET sent to port $port"); return null }
    }
    @Test fun `passive only ports never receive a byte in either mode`() {
        val strictReader = object : BannerReader() {
            override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? {
                if (sendFirst != null) { calls += "send:$port:${kind(sendFirst)}"; fail("wrote ${sendFirst.size} bytes to port $port") }
                calls += "read:$port"; return null
            }
        }
        val id = ServiceIdentifier(table, strictReader, noTls, noGet)
        for (mode in listOf(ProbeMode.Passive, active())) for (port in listOf(9100, 9107, 515, 2000, 6000, 6063, 25, 110, 143)) {
            calls.clear()
            run(id, port, mode)
            assertEquals("port $port in $mode", listOf("read:$port"), calls)
        }
    }
    @Test fun `ipp gets one head in active mode and nothing more`() {
        val s = run(identifier(Reader(reply = { _, k -> if (k == "HEAD") "HTTP/1.1 200 OK\r\nServer: CUPS/2.4 IPP/2.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1) else null }),
            tls = Tls { unifiCert }, http = Http { _, _ -> HttpPeek("x", null, null, 200) }), 631, active())
        assertEquals(listOf("read:631", "send:631:HEAD"), calls); assertEquals("CUPS/2.4 IPP/2.1", s.server)
        calls.clear(); run(identifier(), 631, ProbeMode.Passive); assertEquals(listOf("read:631"), calls)
    }
    @Test fun `ipp with a silent head gets nothing after it`() {
        val s = run(ServiceIdentifier(table, Reader(), noTls, noGet), 631, active())
        assertEquals("no TLS, GET, RTSP or RDP after a failed HEAD", listOf("read:631", "send:631:HEAD"), calls)
        assertEquals(Confidence.PORT_TABLE, s.confidence)
    }

    // ---- the budget (9.1 test 13, Review Focus 2) ----
    @Test fun `no more than 64 ports ever enter the ladder`() {
        val mode = active(); val id = identifier()                      // steps cost nothing: only the port cap can stop the ladder
        for (p in 20000 until 20100) run(id, p, mode)
        assertEquals(64, calls.count { it.startsWith("tls:") }); assertEquals(64, mode.budget.enteredPorts)
        assertEquals(100, calls.count { it.startsWith("read:") })      // every port still got its passive read
    }
    @Test fun `no step starts once 60 s have been spent and refused ports stay open and unknown`() {
        stepCostMs = 1_000                                              // every TLS peek and HEAD costs 1 s of fake time
        val mode = active(); val id = identifier()
        val results = (20000 until 20100).map { p -> run(id, p, mode) }
        assertTrue("a step started after the budget was spent: $spentAtCalls", spentAtCalls.all { it < MAX_ACTIVE_MS })
        assertEquals(30, calls.count { it.startsWith("tls:") })        // a silent port costs 2 s (TLS then HEAD): 30 ports fill 60 s
        assertEquals(60_000L, mode.budget.spentMs)
        results.forEach { assertEquals(ServiceInfo("Unknown", confidence = Confidence.PORT_TABLE), it) }
        assertEquals(100, calls.count { it.startsWith("read:") })
    }
    @Test fun `today's steps are not counted against the budget`() {
        stepCostMs = 1_000
        val mode = active()
        run(identifier(Reader(reply = { _, k -> if (k == "HEAD") okHead else null }), http = Http { _, _ -> HttpPeek("t", null, null, 200) }), 80, mode)
        assertEquals("the HEAD on the HTTP set is today's step; only the GET counts", 1_000L, mode.budget.spentMs)
        run(identifier(tls = Tls { unifiCert }), 443, mode)
        assertEquals("the TLS peek on the TLS set is today's step; only the GET counts", 2_000L, mode.budget.spentMs)
    }
    @Test fun `eight parallel identifies overshoot by at most eight steps`() = runBlocking {
        val longest = AtomicLong(0)
        val startedAt: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        lateinit var budget: ActiveProbeBudget
        // step() reads the clock straight after its admission check and then runs the block on the same thread with no suspension
        // before slow(), so the spend seen at that clock read is the spend the step was admitted at. Reading spentMs later, inside
        // slow(), races with parallel steps finishing in between and can report a value past the cap for a step admitted under it.
        val admittedAt = ThreadLocal<Long>()
        suspend fun slow() {
            startedAt += admittedAt.get()
            val t0 = System.nanoTime(); delay(SLOW_STEP_MS); longest.accumulateAndGet((System.nanoTime() - t0) / 1_000_000) { a, b -> maxOf(a, b) }
        }
        val reader = object : BannerReader() { override suspend fun read(ip: InetAddress, port: Int, sendFirst: ByteArray?): ByteArray? { if (sendFirst != null) slow(); return null } }
        val tls = object : TlsPeek() { override suspend fun certInfo(ip: InetAddress, port: Int, timeoutMs: Int): CertInfo? { slow(); return null } }
        budget = ActiveProbeBudget(MAX_ACTIVE_PORTS, PARALLEL_BUDGET_MS) { admittedAt.set(budget.spentMs); System.nanoTime() / 1_000_000 }
        val id = ServiceIdentifier(table, reader, tls, Http())
        val sem = Semaphore(ServiceIdentifier.MAX_PARALLEL_IDENTIFIES)
        coroutineScope { for (p in 20000 until 20100) launch(Dispatchers.Default) { sem.withPermit { id.identify(ip, p, Evidence(), ProbeMode.Active(budget)) } } }
        val bound = PARALLEL_BUDGET_MS + ServiceIdentifier.MAX_PARALLEL_IDENTIFIES * (longest.get() + SCHEDULING_SLACK_MS)
        assertTrue("the budget never filled: ${budget.spentMs} ms", budget.spentMs >= PARALLEL_BUDGET_MS)
        assertTrue("spent ${budget.spentMs} ms, bound $bound ms", budget.spentMs <= bound)
        assertTrue("a step started after the budget was spent: $startedAt", startedAt.isNotEmpty() && startedAt.all { it < PARALLEL_BUDGET_MS })
    }

    private companion object { const val SLOW_STEP_MS = 20L; const val PARALLEL_BUDGET_MS = 300L; const val SCHEDULING_SLACK_MS = 30L }
}
