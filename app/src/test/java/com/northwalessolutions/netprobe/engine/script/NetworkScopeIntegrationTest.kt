package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.data.ToolRunStatus
import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The guard in front of every transport call, through the real host API: a refused destination never reaches the transport. */
class NetworkScopeIntegrationTest {
    private val target = ScopeTarget(Ipv4.parse("192.168.1.20")!!, "nas.local")
    private val lan = ScopeLan(Ipv4.parse("192.168.1.50")!!, 24)

    private fun harness(scope: NetworkScope, resolver: (String) -> List<InetAddress> = { emptyList() }): Harness {
        val transport = FakeScriptTransport()
        val guard = NetworkScopeGuard(scope, target, lan) { name -> transport.calls += "guard-resolve ${name.name}"; resolver(name.name) }
        return Harness(transport = transport, gate = guard)
    }

    private fun outcome(h: Harness, call: String): String =
        h.body("try { $call; return { status: 'done', summary: 'allowed' }; } catch (e) { return { status: 'done', summary: 'refused: ' + e.message }; }").summary

    /** One call of every network function aimed at [host], each with the arguments that function takes. */
    private fun everyCall(host: String) = mapOf(
        "http.request" to "ctx.http.request({ url: 'http://$host/' })",
        "http.many" to "ctx.http.many([{ url: 'http://$host/' }], 1)",
        "tcp.exchange" to "ctx.tcp.exchange({ host: '$host', port: 22 })",
        "tcp.connect" to "ctx.tcp.connect({ host: '$host', port: 22 })",
        "udp.exchange" to "ctx.udp.exchange({ host: '$host', port: 161 })",
        "tls.certificate" to "ctx.tls.certificate('$host', 443)",
        "dns.lookup" to "ctx.dns.lookup('$host')",
        "dns.reverse" to "ctx.dns.reverse('$host')",
    )

    @Test fun `every network function is refused outside the scope without touching the transport`() {
        for ((name, call) in everyCall("192.168.1.99")) {
            val h = harness(NetworkScope.TARGET)
            val result = outcome(h, call)
            assertTrue("$name: $result", result.startsWith("refused:"))
            assertTrue("$name reached the transport: ${h.transport.calls}", h.transport.calls.isEmpty())
        }
    }

    @Test fun `every network function reaches the transport for the target and only with the approved address`() {
        for ((name, call) in everyCall("192.168.1.20")) {
            val h = harness(NetworkScope.TARGET)
            assertEquals(name, "allowed", outcome(h, call))
            assertEquals("$name: ${h.transport.calls}", 1, h.transport.calls.size)
            if (name != "dns.lookup") assertEquals(name, target.ip, h.transport.clearedAddresses.single())
        }
    }

    @Test fun `non canonical spellings of the target never reach the transport through any function`() {
        for (spelling in listOf("3232235796", "0xC0A80114", "0300.0250.0001.0024", "192.168.1", "::ffff:192.168.1.20")) {
            for ((name, call) in everyCall(spelling)) {
                val h = harness(NetworkScope.TARGET)
                val r = outcome(h, call)
                assertTrue("$name $spelling: $r", r.startsWith("refused:"))
                assertTrue("$name $spelling reached the transport", h.transport.calls.isEmpty())
            }
        }
    }

    @Test fun `a redirect to another host is checked as a new connection`() {
        val h = harness(NetworkScope.LAN)
        h.transport.httpHandler = { _, c -> HttpReply(302, "Found", listOf("Location" to "http://8.8.8.8/steal"), ByteArray(0), false, 1).also { check(c.port == 80) } }
        val r = outcome(h, "ctx.http.request({ url: 'http://192.168.1.20/', followRedirects: true })")
        assertEquals("refused: This tool can only contact addresses on the current network", r)
        assertEquals(1, h.transport.calls.size)
    }

    @Test fun `a redirect to a non canonical or loopback literal is refused`() {
        for (location in listOf("http://2130706433/", "http://0x7f.0.0.1/", "http://127.0.0.1:8080/", "http://[::ffff:127.0.0.1]/", "http://localhost/", "//127.0.0.1/", "http://192.168.1.20.evil.com/")) {
            val h = harness(NetworkScope.LAN) { if (it == "localhost") listOf(InetAddress.getByName("127.0.0.1")) else emptyList() }
            h.transport.httpHandler = { _, _ -> HttpReply(301, "Moved", listOf("Location" to location), ByteArray(0), false, 1) }
            val r = outcome(h, "ctx.http.request({ url: 'http://192.168.1.20/', followRedirects: true })")
            assertTrue("$location: $r", r.startsWith("refused:"))
            assertEquals("$location: ${h.transport.calls}", 1, h.transport.calls.count { it.startsWith("http ") })
        }
    }

    @Test fun `a redirect inside the scope is followed and each hop is cleared`() {
        val h = harness(NetworkScope.LAN)
        var n = 0
        h.transport.httpHandler = { _, _ -> if (n++ == 0) HttpReply(302, "Found", listOf("Location" to "http://192.168.1.21:8080/x"), ByteArray(0), false, 1) else HttpReply(200, "OK", emptyList(), ByteArray(0), false, 1) }
        assertEquals("allowed", outcome(h, "ctx.http.request({ url: 'http://192.168.1.20/', followRedirects: true })"))
        assertEquals(listOf("192.168.1.20", "192.168.1.21"), h.transport.clearedAddresses.map { it.hostAddress })
    }

    @Test fun `a name is resolved once per call and the transport gets that exact address`() {
        val answer = InetAddress.getByName("192.168.1.77")
        val h = harness(NetworkScope.LAN) { listOf(answer) }
        assertEquals("allowed", outcome(h, "ctx.tcp.exchange({ host: 'printer.local', port: 9100 }); ctx.tcp.exchange({ host: 'printer.local', port: 9100 })"))
        assertEquals(2, h.transport.calls.count { it == "guard-resolve printer.local" })
        assertEquals(listOf(answer, answer), h.transport.clearedAddresses.toList())
        assertTrue("the transport never resolved the name itself", h.transport.calls.none { it.startsWith("resolve ") })
    }

    @Test fun `a name that changes its answer is checked again on each call`() {
        var round = 0
        val h = harness(NetworkScope.LAN) { if (round++ == 0) listOf(InetAddress.getByName("192.168.1.77")) else listOf(InetAddress.getByName("127.0.0.1")) }
        val r = h.body("var out = []; for (var i = 0; i < 2; i++) { try { ctx.tcp.exchange({ host: 'rebind.local', port: 80 }); out.push('ok'); } catch (e) { out.push('refused'); } } return { status: 'done', summary: out.join(',') };").summary
        assertEquals("ok,refused", r)
    }

    @Test fun `dns lookups under the target scope never leave for another name`() {
        val h = harness(NetworkScope.TARGET)
        assertEquals("refused: This tool can only look up the name of the device it was run against", outcome(h, "ctx.dns.lookup('secret.evil.com')"))
        assertEquals("allowed", outcome(h, "ctx.dns.lookup('nas.local')"))
        assertEquals(listOf("resolve nas.local"), h.transport.calls.toList())
    }

    @Test fun `any scope reaches the public internet but not loopback`() {
        val h = harness(NetworkScope.ANY) { listOf(InetAddress.getByName("93.184.216.34")) }
        assertEquals("allowed", outcome(h, "ctx.http.request({ url: 'http://example.com/' })"))
        assertEquals("93.184.216.34", h.transport.clearedAddresses.single().hostAddress)
        assertTrue(outcome(h, "ctx.http.request({ url: 'http://127.0.0.1/' })").startsWith("refused:"))
        assertEquals(ToolRunStatus.DONE, h.body("return {};").status)
    }
}
