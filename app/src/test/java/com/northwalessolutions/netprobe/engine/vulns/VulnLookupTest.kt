package com.northwalessolutions.netprobe.engine.vulns

import com.northwalessolutions.netprobe.engine.identify.DeviceIdentity
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.engine.serviceid.ServiceInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VulnLookupTest {
    private class FakeCache : VulnCache {
        val store = HashMap<String, Pair<String, Long>>()
        override suspend fun get(query: String, maxAgeMs: Long) = store[query]?.takeIf { System.currentTimeMillis() - it.second <= maxAgeMs }
        override suspend fun getAny(query: String) = store[query]
        override suspend fun put(query: String, json: String) { store[query] = json to System.currentTimeMillis() }
    }
    private val json = javaClass.getResource("/fixtures/nvd_response.json")!!.readText()
    private val cpeMap = Cpe.load(javaClass.getResource("/cpe_map.json")!!.readText())
    private val ssh = ServiceInfo("SSH", "OpenSSH", "9.6p1", null, Confidence.CONFIRMED)

    @Test fun `builds queries`() {
        val l = VulnLookup({ _, _ -> 200 to json }, FakeCache(), { null }, cpeMap)
        assertTrue(l.queryForService(ssh) is VulnQuery.ByCpe)
        assertEquals("GoAhead-Webs 2.5", l.queryForService(ServiceInfo("HTTP", "GoAhead-Webs", "2.5", null, Confidence.CONFIRMED))!!.text)
        assertNull(l.queryForService(ServiceInfo("VNC", confidence = Confidence.PORT_TABLE)))
        assertEquals("TP-Link Archer AX55", l.queryForDevice(DeviceIdentity("TP-Link", "Archer AX55", null, DeviceType.ROUTER, 100, emptyList()))!!.text)
        assertNull(l.queryForDevice(DeviceIdentity.UNKNOWN))
    }
    @Test fun `fetches parses caches and sends api key header`() = runBlocking {
        var seenUrl = ""; var seenHeaders = emptyMap<String, String>(); var calls = 0
        val cache = FakeCache()
        val l = VulnLookup({ u, h -> calls++; seenUrl = u; seenHeaders = h; 200 to json }, cache, { "KEY123" }, cpeMap)
        val r = l.run(l.queryForService(ssh)!!) as VulnResult.Ok
        assertEquals(2, r.vulns.size); assertFalse(r.fromCache)
        assertTrue(seenUrl.startsWith("https://services.nvd.nist.gov/rest/json/cves/2.0?")); assertTrue(seenUrl.contains("cpeName=cpe%3A2.3%3Aa%3Aopenbsd%3Aopenssh%3A9.6%3Ap1"))
        assertEquals("KEY123", seenHeaders["apiKey"])
        val again = l.run(l.queryForService(ssh)!!) as VulnResult.Ok
        assertTrue(again.fromCache); assertEquals(1, calls)
    }
    @Test fun `cpe query with zero results retries once as a keyword search`() = runBlocking {
        val urls = mutableListOf<String>()
        val l = VulnLookup({ u, _ -> urls += u; if (u.contains("cpeName=")) 200 to """{"totalResults":0,"vulnerabilities":[]}""" else 200 to json }, FakeCache(), { null }, cpeMap)
        val r = l.run(l.queryForService(ssh)!!) as VulnResult.Ok
        assertEquals(2, urls.size)                                    // the CPE call, then the keyword retry
        assertTrue(urls[1].contains("keywordSearch=OpenSSH+9.6p1") || urls[1].contains("keywordSearch=OpenSSH%209.6p1"))
        assertEquals(2, r.vulns.size); assertEquals("OpenSSH 9.6p1", r.queryText)
    }
    @Test fun `keyword query is url encoded`() = runBlocking {
        var seenUrl = ""
        val l = VulnLookup({ u, _ -> seenUrl = u; 200 to json }, FakeCache(), { null }, cpeMap)
        l.run(VulnQuery.ByKeyword("TP-Link Archer AX55")); assertTrue(seenUrl.contains("keywordSearch=TP-Link+Archer+AX55") || seenUrl.contains("keywordSearch=TP-Link%20Archer%20AX55"))
    }
    @Test fun `offline with cache serves cache and says so`() = runBlocking {
        val cache = FakeCache(); cache.store["kw:kw"] = json to (System.currentTimeMillis() - 48 * 3600_000)   // stale
        val l = VulnLookup({ _, _ -> throw IOException("no network") }, cache, { null }, cpeMap)
        val r = l.run(VulnQuery.ByKeyword("kw")) as VulnResult.Failed
        assertEquals("Offline", r.reason); assertEquals(2, r.cached!!.vulns.size); assertTrue(r.cached!!.fromCache)
    }
    @Test fun `offline without cache fails plainly, 403 and 429 are rate limit messages, malformed body is a parse failure`() = runBlocking {
        assertEquals("Offline", (VulnLookup({ _, _ -> throw IOException() }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason)
        assertTrue((VulnLookup({ _, _ -> 403 to "" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("rate limit", true))
        assertTrue((VulnLookup({ _, _ -> 429 to "" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("rate limit", true))
        assertTrue((VulnLookup({ _, _ -> 200 to "<html>" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("x")) as VulnResult.Failed).reason.contains("unexpected", true))
    }
    @Test fun `cancellation propagates instead of becoming Offline`() {
        val l = VulnLookup({ _, _ -> throw kotlinx.coroutines.CancellationException("c") }, FakeCache(), { null }, cpeMap)
        try { runBlocking { l.run(VulnQuery.ByKeyword("x")) }; fail("expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) {}
    }

    private fun staleCache(key: String) = FakeCache().also { it.store[key] = json to (System.currentTimeMillis() - 48 * 3600_000) }

    @Test fun `no key sends no headers and only the nvd host`() = runBlocking {
        for (k in listOf<String?>(null, "", "   ")) {
            var url = ""; var hdrs: Map<String, String>? = null
            VulnLookup({ u, h -> url = u; hdrs = h; 200 to json }, FakeCache(), { k }, cpeMap).run(VulnQuery.ByKeyword("x"))
            assertTrue(hdrs!!.isEmpty()); assertTrue(url.startsWith("https://services.nvd.nist.gov/")); assertTrue(url.contains("resultsPerPage=50"))
        }
    }
    @Test fun `key is sent only as the apiKey header and never in the url`() = runBlocking {
        var url = ""; var hdrs: Map<String, String>? = null
        VulnLookup({ u, h -> url = u; hdrs = h; 200 to json }, FakeCache(), { "SECRETKEY" }, cpeMap).run(VulnQuery.ByKeyword("x"))
        assertEquals(mapOf("apiKey" to "SECRETKEY"), hdrs); assertFalse(url.contains("SECRETKEY"))
    }
    @Test fun `http 500 fails with status and stale cache is attached for 429, 500 and malformed`() = runBlocking {
        val r500 = VulnLookup({ _, _ -> 500 to "" }, FakeCache(), { null }, cpeMap).run(VulnQuery.ByKeyword("kw")) as VulnResult.Failed
        assertEquals("NVD returned HTTP 500", r500.reason); assertNull(r500.cached)
        for (resp in listOf(429 to "", 500 to "", 200 to "<html>")) {
            val r = VulnLookup({ _, _ -> resp }, staleCache("kw:kw"), { null }, cpeMap).run(VulnQuery.ByKeyword("kw")) as VulnResult.Failed
            assertNotNull("stale for $resp", r.cached); assertEquals(2, r.cached!!.vulns.size)
        }
    }
    @Test fun `cache older than ttl hits network and younger is served without it`() = runBlocking {
        var calls = 0
        val old = staleCache("kw:kw")
        val r1 = VulnLookup({ _, _ -> calls++; 200 to json }, old, { null }, cpeMap).run(VulnQuery.ByKeyword("kw")) as VulnResult.Ok
        assertFalse(r1.fromCache); assertEquals(1, calls)
        val fresh = FakeCache().also { it.store["kw:kw"] = json to (System.currentTimeMillis() - 23 * 3600_000) }
        val r2 = VulnLookup({ _, _ -> calls++; 200 to json }, fresh, { null }, cpeMap).run(VulnQuery.ByKeyword("kw")) as VulnResult.Ok
        assertTrue(r2.fromCache); assertEquals(1, calls)
    }
}
