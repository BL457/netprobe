package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.serviceid.Confidence
import com.northwalessolutions.netprobe.data.PortEntity
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ctx.devices and the base64 and digest helpers of ctx.bytes (spec section 12). */
class DevicesAndBytesTest {
    private fun json(h: Harness, js: String): String {
        val r = h.body("function json(v) { return { status: 'done', summary: JSON.stringify(v) }; }\n$js")
        check(r.status == ToolRunStatus.DONE) { "run failed: ${r.summary}" }
        return r.summary
    }
    private val two = listOf(
        ScriptDevice("192.168.1.20", "aa:bb:cc:00:11:22", "nas", "Acme", listOf(ScriptPort(80, "HTTP", false), ScriptPort(443, "HTTPS", true))),
        ScriptDevice("192.168.1.30", "", "192.168.1.30", "", emptyList()),
    )

    // ctx.devices

    @Test fun `a lan tool gets plain device objects`() {
        val h = Harness(network = NetworkScope.LAN, devices = { two })
        assertEquals(
            """[{"ip":"192.168.1.20","mac":"aa:bb:cc:00:11:22","name":"nas","vendor":"Acme","ports":[{"port":80,"service":"HTTP","tls":false},{"port":443,"service":"HTTPS","tls":true}]},{"ip":"192.168.1.30","mac":"","name":"192.168.1.30","vendor":"","ports":[]}]""",
            json(h, "return json(ctx.devices());"))
    }

    @Test fun `an any tool gets devices too`() {
        val h = Harness(network = NetworkScope.ANY, devices = { two })
        assertEquals("2", json(h, "return json(ctx.devices().length);"))
    }

    @Test fun `a target tool is refused and the script can catch it`() {
        val h = Harness(network = NetworkScope.TARGET, devices = { two })
        assertEquals("\"ctx.devices needs a tool with the network set to lan or any\"", json(h, "try { ctx.devices(); return json('allowed'); } catch (e) { return json(e.message); }"))
    }

    @Test fun `no devices is an empty array`() {
        assertEquals("[]", json(Harness(network = NetworkScope.LAN), "return json(ctx.devices());"))
    }

    @Test fun `at most 1024 devices are returned`() {
        val many = (0 until 1_500).map { ScriptDevice("10.0.${it / 250}.${it % 250 + 1}", "", "d$it", "", emptyList()) }
        val h = Harness(network = NetworkScope.LAN, devices = { many })
        assertEquals("1024", json(h, "return json(ctx.devices().length);"))
        assertEquals("\"d1023\"", json(h, "var d = ctx.devices(); return json(d[d.length - 1].name);"))
    }

    @Test fun `each call counts as one host call`() {
        val few = Harness(limits = ScriptLimits(maxHostCalls = 2, findingsPerSecond = 1_000_000, findingsBurst = 1_000_000), network = NetworkScope.LAN, devices = { two })
        val r = few.body("ctx.devices(); ctx.devices(); ctx.devices(); return { status: 'done', summary: 'reached' };")
        assertEquals(ToolRunStatus.FAILED, r.status); assertEquals("Script made more than 20,000 network calls", r.summary)
    }

    @Test fun `the list is asked for again on every call`() {
        var n = 0
        val h = Harness(network = NetworkScope.LAN, devices = { n++; two })
        json(h, "ctx.devices(); ctx.devices(); return json(1);")
        assertEquals(2, n)
    }

    // ScriptDevices.load

    private fun port(hostId: Long, port: Int, service: String, certCn: String? = null) =
        PortEntity(hostId = hostId, port = port, serviceName = service, product = null, version = null, detail = null, confidence = Confidence.CONFIRMED, lastSeen = 0, certCn = certCn)

    @Test fun `devices are listed newest scan first, without manual targets, and capped`() {
        val hosts = listOf(
            TestHosts.host("10.0.0.1").copy(id = 1, lastSeen = 100), TestHosts.host("10.0.0.2").copy(id = 2, lastSeen = 300),
            TestHosts.host("10.0.0.3").copy(id = 3, lastSeen = 300), TestHosts.host("10.0.0.4").copy(id = 4, lastSeen = 999, manualOnly = true),
        )
        val out = runBlocking { ScriptDevices.load(hosts) { emptyList() } }
        assertEquals(listOf("10.0.0.2", "10.0.0.3", "10.0.0.1"), out.map { it.ip })
        val big = (1..1_300).map { TestHosts.host("10.1.${it / 250}.${it % 250}").copy(id = it.toLong(), lastSeen = it.toLong()) }
        assertEquals(ScriptDevices.MAX_DEVICES, runBlocking { ScriptDevices.load(big) { emptyList() } }.size)
    }

    @Test fun `tls comes from an https or tls service or a certificate, and ports are sorted`() {
        val h = TestHosts.host("10.0.0.2", mac = "AA-BB-CC-00-11-22", vendor = "Acme").copy(id = 2)
        val ports = listOf(port(2, 8443, "HTTPS"), port(2, 9000, "TLS"), port(2, 8080, "HTTP"), port(2, 5001, "HTTP", certCn = "nas"), port(2, 22, "SSH"))
        val d = ScriptDevices.deviceOf(h, ports)
        assertEquals("aa:bb:cc:00:11:22", d.mac); assertEquals("Acme", d.vendor)
        assertEquals(listOf(22, 5001, 8080, 8443, 9000), d.ports.map { it.port })
        assertEquals(mapOf(22 to false, 5001 to true, 8080 to false, 8443 to true, 9000 to true), d.ports.associate { it.port to it.tls })
    }

    // ctx.bytes

    @Test fun `base64 encodes bytes and strings`() {
        val h = Harness()
        assertEquals("\"YWRtaW46cGFzc3dvcmQ=\"", json(h, "return json(ctx.bytes.base64('admin:password'));"))
        assertEquals("\"AP8=\"", json(h, "return json(ctx.bytes.base64([0, 255]));"))
        assertEquals("\"\"", json(h, "return json(ctx.bytes.base64(''));"))
    }

    @Test fun `fromBase64 decodes, forgives padding and white space, and refuses junk`() {
        val h = Harness()
        assertEquals("\"admin:password\"", json(h, "return json(ctx.bytes.toUtf8(ctx.bytes.fromBase64('YWRtaW46cGFzc3dvcmQ=')));"))
        assertEquals("\"admin:password\"", json(h, "return json(ctx.bytes.toUtf8(ctx.bytes.fromBase64('YWRt aW46cGFz\\nc3dvcmQ')));"))
        assertEquals("[0,255]", json(h, "return json(ctx.bytes.fromBase64('AP8='));"))
        assertEquals("[251,255]", json(h, "return json(ctx.bytes.fromBase64('-_8'));"))
        for (bad in listOf("!!!!", "A", "YW*t", "abc=def")) {
            assertEquals(bad, "\"Not a valid base64 string\"", json(h, "try { ctx.bytes.fromBase64('$bad'); return json('allowed'); } catch (e) { return json(e.message); }"))
        }
    }

    @Test fun `digests are lower-case hex and take bytes or a string`() {
        val h = Harness()
        assertEquals("\"900150983cd24fb0d6963f7d28e17f72\"", json(h, "return json(ctx.bytes.md5('abc'));"))
        assertEquals("\"d41d8cd98f00b204e9800998ecf8427e\"", json(h, "return json(ctx.bytes.md5(''));"))
        assertEquals("\"a9993e364706816aba3e25717850c26c9cd0d89d\"", json(h, "return json(ctx.bytes.sha1([97, 98, 99]));"))
        assertEquals("\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"", json(h, "return json(ctx.bytes.sha256('abc'));"))
    }

    @Test fun `a digest of a bad argument is an error the script can catch`() {
        val h = Harness()
        assertTrue(json(h, "try { ctx.bytes.sha256(5); return json('allowed'); } catch (e) { return json('refused'); }").contains("refused"))
        assertTrue(json(h, "try { ctx.bytes.md5({}); return json('allowed'); } catch (e) { return json('refused'); }").contains("refused"))
    }

    @Test fun `the new functions are sealed like the rest`() {
        val h = Harness()
        assertEquals("\"function\"", json(h, "try { ctx.bytes.md5 = 1; } catch (e) { } return json(typeof ctx.bytes.md5);"))
    }
}
