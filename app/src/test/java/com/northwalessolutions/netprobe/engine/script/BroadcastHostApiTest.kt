package com.northwalessolutions.netprobe.engine.script

import com.northwalessolutions.netprobe.engine.tools.NetworkScope
import com.northwalessolutions.netprobe.netenv.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ctx.udp.send and the broadcast limits through the real host API and the real guard, with a fake transport (spec section 11). */
class BroadcastHostApiTest {
    private val target = ScopeTarget(Ipv4.parse("192.168.1.20")!!, null)
    private val lan = ScopeLan(Ipv4.parse("192.168.1.50")!!, 24)

    private fun harness(scope: NetworkScope = NetworkScope.LAN, declared: Boolean = true): Harness =
        Harness(transport = FakeScriptTransport(), gate = NetworkScopeGuard(scope, target, lan, declared) { emptyList() })

    private fun outcome(h: Harness, js: String): String =
        h.body("try { $js; return { status: 'done', summary: 'ok' }; } catch (e) { return { status: 'done', summary: 'refused: ' + e.message }; }").summary

    @Test fun `udp send reaches the transport once and marks a broadcast`() {
        val h = harness()
        assertEquals("ok", outcome(h, "ctx.udp.send({ host: '192.168.1.255', port: 9, send: [255, 1, 2] })"))
        val sent = h.transport.sentDatagrams.single()
        assertEquals("192.168.1.255", sent.address.hostAddress); assertEquals(9, sent.port); assertTrue(sent.broadcast)
        assertEquals(listOf<Byte>(-1, 1, 2), sent.data.toList())
        assertTrue("fire and forget: nothing was exchanged", h.transport.calls.none { it.startsWith("udp ") })
    }

    @Test fun `udp send to a normal lan host is allowed and not counted as broadcast`() {
        val h = harness()
        assertEquals("ok", outcome(h, "for (var i = 0; i < 80; i++) { ctx.udp.send({ host: '192.168.1.20', port: 9, send: 'x' }); }"))
        assertEquals(80, h.transport.sentDatagrams.size)
        assertFalse(h.transport.sentDatagrams.any { it.broadcast })
    }

    @Test fun `udp exchange to the broadcast address works for a declared lan tool`() {
        val h = harness()
        assertEquals("ok", outcome(h, "ctx.udp.exchange({ host: '192.168.1.255', port: 1900, send: 'M-SEARCH' })"))
        assertEquals(listOf("udp 192.168.1.255:1900"), h.transport.calls)
    }

    @Test fun `a script can send 50 broadcast datagrams in one run and the 51st is refused`() {
        val h = harness()
        val r = h.body("var sent = 0; try { for (var i = 0; i < 60; i++) { ctx.udp.send({ host: '192.168.1.255', port: 9, send: 'x' }); sent++; } } catch (e) { return { status: 'done', summary: sent + ' ' + e.message }; } return { status: 'done', summary: 'no limit' };")
        assertEquals("50 At most 50 broadcast packets can be sent in one run", r.summary)
        assertEquals(50, h.transport.sentDatagrams.size)
    }

    @Test fun `the cap is per run, so the next run starts again`() {
        val h = harness()
        for (run in 1..2) assertEquals("ok", outcome(h, "for (var i = 0; i < 50; i++) { ctx.udp.send({ host: '192.168.1.255', port: 9, send: 'x' }); }"))
        assertEquals(100, h.transport.sentDatagrams.size)
    }

    @Test fun `the cap is shared by send and exchange`() {
        val h = harness()
        val r = h.body("var n = 0; try { for (var i = 0; i < 60; i++) { if (i % 2 == 0) ctx.udp.send({ host: '192.168.1.255', port: 9, send: 'x' }); else ctx.udp.exchange({ host: '192.168.1.255', port: 9, send: 'x', timeoutMs: 100 }); n++; } } catch (e) { return { status: 'done', summary: n + ' ' + e.message }; } return { status: 'done', summary: 'no limit' };")
        assertEquals("50 At most 50 broadcast packets can be sent in one run", r.summary)
    }

    @Test fun `a broadcast datagram can be 1 KB but not more`() {
        val h = harness()
        assertEquals("ok", outcome(h, "ctx.udp.send({ host: '192.168.1.255', port: 9, send: new Array(1025).join('a') })"))
        assertEquals(1024, h.transport.sentDatagrams.single().data.size)
        assertEquals("refused: A broadcast packet can be at most 1 KB", outcome(h, "ctx.udp.send({ host: '192.168.1.255', port: 9, send: new Array(1027).join('a') })"))
        assertEquals("a refused packet is not sent", 1, h.transport.sentDatagrams.size)
        assertTrue(outcome(h, "ctx.udp.exchange({ host: '192.168.1.255', port: 9, send: new Array(1027).join('a') })").startsWith("refused: A broadcast packet"))
    }

    @Test fun `a datagram over 1 KB to a normal host is not limited by the broadcast rule`() {
        val h = harness()
        assertEquals("ok", outcome(h, "ctx.udp.send({ host: '192.168.1.20', port: 9, send: new Array(3001).join('a') })"))
    }

    @Test fun `without the declaration nothing reaches the transport`() {
        val h = harness(declared = false)
        assertTrue(outcome(h, "ctx.udp.send({ host: '192.168.1.255', port: 9, send: 'x' })").startsWith("refused: This tool did not declare broadcast"))
        assertTrue(outcome(h, "ctx.udp.exchange({ host: '192.168.1.255', port: 9 })").startsWith("refused:"))
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `255 255 255 255 and other subnets never reach the transport`() {
        val h = harness()
        for (host in listOf("255.255.255.255", "192.168.2.255", "10.255.255.255")) {
            assertTrue(host, outcome(h, "ctx.udp.send({ host: '$host', port: 9, send: 'x' })").startsWith("refused:"))
            assertTrue(host, outcome(h, "ctx.udp.exchange({ host: '$host', port: 9 })").startsWith("refused:"))
        }
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `tcp and the other calls cannot use the broadcast address even when declared`() {
        val h = harness()
        for (call in listOf("ctx.tcp.exchange({ host: '192.168.1.255', port: 22 })", "ctx.tcp.connect({ host: '192.168.1.255', port: 22 })",
            "ctx.http.request({ url: 'http://192.168.1.255/' })", "ctx.tls.certificate('192.168.1.255', 443)", "ctx.dns.reverse('192.168.1.255')")) {
            assertTrue(call, outcome(h, call).startsWith("refused:"))
        }
        assertTrue(h.transport.calls.isEmpty())
    }

    @Test fun `udp send needs its options like the other calls`() {
        val h = harness()
        assertEquals("refused: ctx.udp.send needs the option port", outcome(h, "ctx.udp.send({ host: '192.168.1.20' })"))
        assertEquals("refused: ctx.udp.send needs the option host", outcome(h, "ctx.udp.send({ port: 9 })"))
        assertTrue(outcome(h, "ctx.udp.send()").startsWith("refused:"))
    }

    @Test fun `udp send is refused outside the scope like any call`() {
        val h = harness(NetworkScope.TARGET, declared = false)
        assertTrue(outcome(h, "ctx.udp.send({ host: '192.168.1.99', port: 9, send: 'x' })").startsWith("refused:"))
        assertEquals("ok", outcome(h, "ctx.udp.send({ host: '192.168.1.20', port: 9, send: 'x' })"))
    }
}
